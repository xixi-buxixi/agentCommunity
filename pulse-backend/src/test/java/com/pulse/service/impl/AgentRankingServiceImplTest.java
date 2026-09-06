package com.pulse.service.impl;

import com.pulse.dto.AgentRankingScore;
import com.pulse.dto.response.AgentRankingItemResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.User;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentRankingMapper;
import com.pulse.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the agent leaderboards.
 *
 * The behaviours worth pinning here are the ones that are invisible in a single happy
 * path: that a Redis hit really does skip MySQL, that a Redis failure is a fallback
 * rather than an error, that the requested limit cannot exceed the cap, and that an
 * unknown board name is rejected instead of quietly answering a different question.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRankingServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private AgentRankingMapper agentRankingMapper;

    @Mock
    private AgentMapper agentMapper;

    @Mock
    private UserMapper userMapper;

    private AgentRankingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AgentRankingServiceImpl(redisTemplate, agentRankingMapper, agentMapper, userMapper);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.getStringSerializer()).thenReturn(RedisSerializer.string());
    }

    // ========== Redis hit ==========

    @Test
    void redisHitIsServedWithoutQueryingMySql() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0), tuple("9", 5.0));
        agentsExist(agent(7L, "Ada", 1, 100L), agent(9L, "Bo", 0, 101L));
        ownersExist(user(100L, "alice"), user(101L, "bob"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("replied", 10);

        assertThat(ranking).hasSize(2);
        assertThat(ranking.get(0).getRank()).isEqualTo(1);
        assertThat(ranking.get(0).getAgentId()).isEqualTo(7L);
        assertThat(ranking.get(0).getName()).isEqualTo("Ada");
        assertThat(ranking.get(0).getOwnerName()).isEqualTo("alice");
        assertThat(ranking.get(0).getScore()).isEqualByComparingTo("12");
        assertThat(ranking.get(0).getType()).isEqualTo("replied");
        assertThat(ranking.get(1).getRank()).isEqualTo(2);
        assertThat(ranking.get(1).getAgentId()).isEqualTo(9L);

        verifyNoInteractions(agentRankingMapper);
    }

    /**
     * A DEAD agent keeps the rank it earned inside the window, and says so.
     */
    @Test
    void deadAgentStaysOnTheBoardWithItsStatus() {
        cacheHolds("pulse:rank:agent:active", tuple("9", 4.0));
        agentsExist(agent(9L, "Bo", 0, 101L));
        ownersExist(user(101L, "bob"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("active", 10);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).getStatus()).isZero();
        assertThat(ranking.get(0).getStatusText()).isEqualTo("死机");
    }

    /**
     * An id that no longer resolves is dropped and the ranks close up behind it,
     * rather than producing a blank row or a gap in the numbering.
     */
    @Test
    void unresolvableAgentIsDroppedAndRanksCloseUp() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0), tuple("42", 8.0), tuple("9", 5.0));
        agentsExist(agent(7L, "Ada", 1, 100L), agent(9L, "Bo", 1, 101L));
        ownersExist(user(100L, "alice"), user(101L, "bob"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("replied", 10);

        assertThat(ranking).extracting(AgentRankingItemResponse::getAgentId)
                .containsExactly(7L, 9L);
        assertThat(ranking).extracting(AgentRankingItemResponse::getRank)
                .containsExactly(1, 2);
    }

    // ========== MySQL fallback ==========

    @Test
    void emptyCacheFallsBackToMySql() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("replied", 10);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).getAgentId()).isEqualTo(7L);
        assertThat(ranking.get(0).getScore()).isEqualByComparingTo("12");
    }

    /**
     * Redis being down is a cache miss, not a failed request.
     */
    @Test
    void redisFailureFallsBackToMySql() {
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenThrow(new IllegalStateException("redis is down"));
        when(agentRankingMapper.findTopByActivity(any(), anyInt()))
                .thenReturn(List.of(score(7L, "3")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("active", 10);

        assertThat(ranking).hasSize(1);
        assertThat(ranking.get(0).getScore()).isEqualByComparingTo("3");
    }

    /**
     * The fallback also rebuilds the cache, so the next request is a hit; a failure
     * while doing so must not take the answer down with it.
     */
    @Test
    void cacheRebuildFailureDoesNotFailTheRequest() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        when(redisTemplate.delete(anyString())).thenThrow(new IllegalStateException("redis is down"));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        assertThat(service.getAgentRanking("replied", 10)).hasSize(1);
    }

    @Test
    void emptyWindowReturnsEmptyList() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());

        assertThat(service.getAgentRanking("tipped", 10)).isEmpty();
        verify(agentMapper, never()).selectBatchIds(anyCollection());
    }

    @Test
    void nullMapperResultIsTreatedAsEmpty() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(null);

        assertThat(service.getAgentRanking("tipped", 10)).isEmpty();
    }

    // ========== Windows and units ==========

    @Test
    void repliedUsesASevenDayWindow() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt())).thenReturn(List.of());

        service.getAgentRanking("replied", 10);

        assertThat(capturedSince(7)).isTrue();
    }

    @Test
    void tippedUsesAThirtyDayWindowAndKeepsTwoDecimals() {
        cacheHolds("pulse:rank:agent:tipped", tuple("7", 12.5));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        List<AgentRankingItemResponse> ranking = service.getAgentRanking("tipped", 10);

        assertThat(ranking.get(0).getScore()).isEqualByComparingTo("12.50");
        assertThat(ranking.get(0).getScore().scale()).isEqualTo(2);
    }

    @Test
    void tippedFallbackUsesAThirtyDayWindow() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());

        service.getAgentRanking("tipped", 10);

        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentRankingMapper, org.mockito.Mockito.atLeastOnce())
                .findTopByTipsReceived(since.capture(), anyInt());
        assertThat(since.getValue()).isBefore(LocalDateTime.now().minusDays(29));
        assertThat(since.getValue()).isAfter(LocalDateTime.now().minusDays(31));
    }

    // ========== Limit handling ==========

    @Test
    void limitAboveTheCapIsTruncated() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        service.getAgentRanking("replied", 500);

        // reverseRangeWithScores takes an inclusive end index, so 50 rows is 0..49
        verify(zSetOperations).reverseRangeWithScores("pulse:rank:agent:replied", 0, 49);
    }

    @Test
    void limitBelowOneIsRaisedToOne() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        service.getAgentRanking("replied", 0);

        verify(zSetOperations).reverseRangeWithScores("pulse:rank:agent:replied", 0, 0);
    }

    @Test
    void limitIsPassedToTheFallbackQueryAndTheRebuildStillFillsTheCache() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt())).thenReturn(List.of());

        service.getAgentRanking("replied", 5);

        // The caller gets exactly what it asked for...
        verify(agentRankingMapper).findTopByRepliesReceived(any(), eq(5));
        // ...while the rebuild that follows fills the cache to its full size, so the
        // next request for a larger page is still a hit.
        verify(agentRankingMapper).findTopByRepliesReceived(any(), eq(50));
    }

    // ========== Type validation ==========

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> service.getAgentRanking("popular", 10))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAMETER.getCode()));
    }

    @Test
    void nullTypeIsRejected() {
        assertThatThrownBy(() -> service.getAgentRanking(null, 10))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void typeIsCaseInsensitiveAndTrimmed() {
        cacheHolds("pulse:rank:agent:tipped", tuple("7", 12.0));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        assertThat(service.getAgentRanking("  TIPPED ", 10)).hasSize(1);
    }

    // ========== Refresh ==========

    @Test
    void refreshWritesEveryRowToTheCache() {
        when(agentRankingMapper.findTopByActivity(any(), anyInt()))
                .thenReturn(List.of(score(7L, "9"), score(9L, "4")));

        service.refreshAgentRankingCache("active");

        verify(redisTemplate).delete("pulse:rank:agent:active");
        verify(redisTemplate).executePipelined(any(RedisCallback.class));
    }

    /**
     * An empty window must clear the key. Returning early with the old board in place
     * would keep serving a window that has already rolled past.
     */
    @Test
    @SuppressWarnings("unchecked")
    void refreshWritesOneZSetEntryPerRow() {
        when(agentRankingMapper.findTopByActivity(any(), anyInt()))
                .thenReturn(List.of(score(7L, "9"), score(9L, "4")));

        service.refreshAgentRankingCache("active");

        ArgumentCaptor<RedisCallback<Object>> callback = ArgumentCaptor.forClass(RedisCallback.class);
        verify(redisTemplate).executePipelined(callback.capture());

        RedisConnection connection = org.mockito.Mockito.mock(RedisConnection.class);
        callback.getValue().doInRedis(connection);

        byte[] key = "pulse:rank:agent:active".getBytes(StandardCharsets.UTF_8);
        verify(connection).zAdd(key, 9.0d, "7".getBytes(StandardCharsets.UTF_8));
        verify(connection).zAdd(key, 4.0d, "9".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refreshOnAnEmptyWindowClearsTheKey() {
        when(agentRankingMapper.findTopByActivity(any(), anyInt())).thenReturn(List.of());

        service.refreshAgentRankingCache("active");

        verify(redisTemplate).delete("pulse:rank:agent:active");
        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    @Test
    void refreshAllCoversEveryBoard() {
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt())).thenReturn(List.of());
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());
        when(agentRankingMapper.findTopByActivity(any(), anyInt())).thenReturn(List.of());

        service.refreshAllAgentRankingCaches();

        verify(redisTemplate).delete("pulse:rank:agent:replied");
        verify(redisTemplate).delete("pulse:rank:agent:tipped");
        verify(redisTemplate).delete("pulse:rank:agent:active");
    }

    /**
     * One board failing is one board's problem. The loop had no per-board try/catch, so
     * a Redis write failure or a slow aggregate on the first board cost the other two
     * their refresh for the whole tick - and the scheduler's own try block only
     * separates the agent boards from the post boards, not the agent boards from each
     * other.
     */
    @Test
    void refreshAllKeepsGoingWhenOneBoardFails() {
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenThrow(new IllegalStateException("aggregate timed out"));
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());
        when(agentRankingMapper.findTopByActivity(any(), anyInt())).thenReturn(List.of());

        service.refreshAllAgentRankingCaches();

        verify(redisTemplate).delete("pulse:rank:agent:tipped");
        verify(redisTemplate).delete("pulse:rank:agent:active");
    }

    // ========== Empty boards ==========

    /**
     * An empty window is a cacheable answer.
     *
     * A Sorted Set cannot hold "no members", so an empty board and a never-built board
     * are the same absent key: every anonymous request on an empty board ran the
     * aggregate twice (once for the answer, once for the rebuild that wrote nothing)
     * and left the next request to do it again. The marker is what stops that.
     */
    @Test
    void refreshOnAnEmptyWindowRecordsThatTheBoardIsEmpty() {
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());

        service.refreshAgentRankingCache("tipped");

        verify(valueOperations).set("pulse:rank:agent:tipped:empty", "1", Duration.ofMinutes(5));
    }

    @Test
    void aBoardCachedAsEmptyIsAnsweredWithoutTouchingMySql() {
        cacheIsEmpty();
        markedEmpty("pulse:rank:agent:tipped");

        assertThat(service.getAgentRanking("tipped", 10)).isEmpty();

        verifyNoInteractions(agentRankingMapper);
        verify(agentMapper, never()).selectBatchIds(anyCollection());
    }

    /**
     * The marker is per board: an empty tipped board must not silence the replied one.
     */
    @Test
    void anEmptyMarkerOnOneBoardDoesNotAffectAnother() {
        cacheIsEmpty();
        markedEmpty("pulse:rank:agent:tipped");
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        assertThat(service.getAgentRanking("replied", 10)).hasSize(1);
    }

    /**
     * A refresh that finds rows again drops the marker, so the board does not stay
     * silent until the TTL runs out.
     */
    @Test
    void refreshWithRowsClearsTheEmptyMarker() {
        when(agentRankingMapper.findTopByActivity(any(), anyInt()))
                .thenReturn(List.of(score(7L, "9")));

        service.refreshAgentRankingCache("active");

        verify(redisTemplate).delete("pulse:rank:agent:active:empty");
    }

    /**
     * Redis being unable to answer "is this board empty?" is not "yes". Failing that
     * way costs one aggregate; the other way would serve an empty leaderboard on the
     * strength of a failed read.
     */
    @Test
    void aFailedMarkerReadFallsBackToMySqlRatherThanAnsweringEmpty() {
        cacheIsEmpty();
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("redis is down"));
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        assertThat(service.getAgentRanking("replied", 10)).hasSize(1);
    }

    @Test
    void refreshRejectsAnUnknownType() {
        assertThatThrownBy(() -> service.refreshAgentRankingCache("popular"))
                .isInstanceOf(BusinessException.class);
    }

    // ========== In-process fallback cache ==========

    /**
     * With Redis down, the IP limiter in front of this endpoint is down too - it is
     * backed by the same Redis and fails open. Without a second bound, every anonymous
     * request runs the aggregate twice (once for the answer, once for the rebuild).
     * The fallback answer is therefore memoised in-process for a minute.
     */
    @Test
    void aRepeatedFallbackIsServedFromTheInProcessCache() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        List<AgentRankingItemResponse> first = service.getAgentRanking("replied", 10);
        org.mockito.Mockito.clearInvocations(agentRankingMapper, agentMapper, userMapper);
        List<AgentRankingItemResponse> second = service.getAgentRanking("replied", 10);

        assertThat(second).isEqualTo(first);
        verifyNoInteractions(agentRankingMapper);
        verifyNoInteractions(agentMapper);
        verifyNoInteractions(userMapper);
    }

    /**
     * An empty fallback answer is memoised too: "the window really is empty" is the
     * case that would otherwise re-run the aggregate on every single request.
     */
    @Test
    void anEmptyFallbackAnswerIsMemoisedAsWell() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByTipsReceived(any(), anyInt())).thenReturn(List.of());

        assertThat(service.getAgentRanking("tipped", 10)).isEmpty();
        org.mockito.Mockito.clearInvocations(agentRankingMapper);
        assertThat(service.getAgentRanking("tipped", 10)).isEmpty();

        verifyNoInteractions(agentRankingMapper);
    }

    /** The key carries the limit, so a wider board is not answered from a narrower one. */
    @Test
    void theFallbackCacheIsKeyedByTypeAndLimit() {
        cacheIsEmpty();
        when(agentRankingMapper.findTopByRepliesReceived(any(), anyInt()))
                .thenReturn(List.of(score(7L, "12")));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        service.getAgentRanking("replied", 10);
        org.mockito.Mockito.clearInvocations(agentRankingMapper);
        service.getAgentRanking("replied", 25);

        verify(agentRankingMapper).findTopByRepliesReceived(any(), eq(25));
    }

    /**
     * The memo covers the MySQL fallback only. A board Redis can answer keeps answering
     * from Redis, at Redis's own freshness.
     */
    @Test
    void aRedisHitIsNotServedFromTheInProcessCache() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0));
        agentsExist(agent(7L, "Ada", 1, 100L));
        ownersExist(user(100L, "alice"));

        service.getAgentRanking("replied", 10);
        service.getAgentRanking("replied", 10);

        verify(zSetOperations, org.mockito.Mockito.times(2))
                .reverseRangeWithScores(eq("pulse:rank:agent:replied"), anyLong(), anyLong());
        verify(agentMapper, org.mockito.Mockito.times(2)).selectBatchIds(anyCollection());
    }

    // ========== status_text ==========

    /**
     * The same label the public profile uses. Two anonymous endpoints rendering the
     * same field from the same rows must not disagree about what an unknown status is
     * called.
     */
    @Test
    void anUnknownOrMissingStatusIsLabelledUnknown() {
        cacheHolds("pulse:rank:agent:replied", tuple("7", 12.0), tuple("9", 5.0));
        agentsExist(agent(7L, "Ada", null, 100L), agent(9L, "Bo", 99, 101L));
        ownersExist(user(100L, "alice"), user(101L, "bob"));

        assertThat(service.getAgentRanking("replied", 10))
                .extracting(AgentRankingItemResponse::getStatusText)
                .containsExactly("UNKNOWN", "UNKNOWN");
    }

    // ========== Fixtures ==========

    @SafeVarargs
    private void cacheHolds(String key, ZSetOperations.TypedTuple<String>... tuples) {
        Set<ZSetOperations.TypedTuple<String>> ordered = new LinkedHashSet<>(List.of(tuples));
        when(zSetOperations.reverseRangeWithScores(eq(key), anyLong(), anyLong())).thenReturn(ordered);
    }

    private void cacheIsEmpty() {
        when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                .thenReturn(Set.of());
    }

    private void markedEmpty(String boardKey) {
        when(redisTemplate.hasKey(boardKey + ":empty")).thenReturn(true);
    }

    private void agentsExist(Agent... agents) {
        when(agentMapper.selectBatchIds(anyCollection())).thenReturn(List.of(agents));
    }

    private void ownersExist(User... users) {
        when(userMapper.selectBatchIds(anyCollection())).thenReturn(List.of(users));
    }

    private boolean capturedSince(int expectedDays) {
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentRankingMapper, org.mockito.Mockito.atLeastOnce())
                .findTopByRepliesReceived(since.capture(), anyInt());
        LocalDateTime value = since.getValue();
        return value.isBefore(LocalDateTime.now().minusDays(expectedDays).plusMinutes(1))
                && value.isAfter(LocalDateTime.now().minusDays(expectedDays).minusMinutes(1));
    }

    private static ZSetOperations.TypedTuple<String> tuple(String value, double score) {
        return ZSetOperations.TypedTuple.of(value, score);
    }

    private static AgentRankingScore score(Long agentId, String score) {
        AgentRankingScore row = new AgentRankingScore();
        row.setAgentId(agentId);
        row.setScore(new BigDecimal(score));
        return row;
    }

    private static Agent agent(Long id, String name, Integer status, Long ownerId) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setName(name);
        agent.setAvatarUrl("https://example.test/" + id + ".png");
        agent.setStatus(status);
        agent.setOwnerId(ownerId);
        return agent;
    }

    private static User user(Long id, String username) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        return user;
    }
}
