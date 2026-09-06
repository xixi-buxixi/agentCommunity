package com.pulse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentInteractionCount;
import com.pulse.dto.AgentTipTotals;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.dto.response.AgentPublicProfileResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.Post;
import com.pulse.entity.User;
import com.pulse.enums.AgentStatus;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.support.WakeScheduleCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The public profile is the one agent endpoint an anonymous caller can reach, so two
 * things have to hold no matter what the database looks like:
 *
 * - nothing that identifies or authenticates the agent's model connection may appear in
 *   the payload, and
 * - a deployment without the phase-3 wake-rhythm columns must still render the page.
 *
 * The serialization assertion below is deliberately made against the JSON rather than the
 * DTO's getters: a field added to the response type later would slip past a getter-by-
 * getter check, but not past "the rendered document must not contain the string api_key".
 */
class AgentProfileServiceImplTest {

    private static final Long AGENT_ID = 42L;
    private static final Long OWNER_ID = 7L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final SysLedgerMapper sysLedgerMapper = mock(SysLedgerMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    // The real calculator: the active-hours window is exactly the logic under test here,
    // and the profile badge must agree with what the scheduler would decide.
    private final WakeScheduleCalculator wakeScheduleCalculator = new WakeScheduleCalculator();

    private final AgentProfileServiceImpl service = new AgentProfileServiceImpl(
            agentMapper, userMapper, postMapper, commentMapper, sysLedgerMapper,
            schemaCapabilities, wakeScheduleCalculator);

    @BeforeEach
    void configure() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(AgentStatus.ALIVE.getCode()));
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(wakeSettings(9, 23));
        when(userMapper.selectById(OWNER_ID)).thenReturn(user());
        when(postMapper.countAgentPosts(AGENT_ID)).thenReturn(12);
        when(commentMapper.countAgentComments(AGENT_ID)).thenReturn(34);
        when(sysLedgerMapper.findAgentTipTotals(AGENT_ID))
                .thenReturn(new AgentTipTotals(3, new BigDecimal("150.00")));
        when(commentMapper.findFrequentAgentInteractions(anyLong(), anyInt()))
                .thenReturn(List.of(new AgentInteractionCount(8L, "Echo", 9),
                        new AgentInteractionCount(9L, "Nova", 4)));
        when(postMapper.findRecentAgentPosts(anyLong(), anyInt()))
                .thenReturn(List.of(post(101L, "hello world")));
    }

    // ========== Unexpected status values ==========

    /**
     * agents.status is a nullable TINYINT and AgentStatus.fromCode throws on anything it
     * does not recognise, so one row carrying an unexpected value used to turn this
     * anonymous page into a 500 - the leaderboard, reading the same column through the
     * same anonymous door, already tolerated it.
     */
    @Test
    void anUnknownStatusCodeIsLabelledRatherThanThrown() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agentWithRawStatus(99));

        AgentPublicProfileResponse profile = service.getPublicProfile(AGENT_ID);

        // The raw code is still reported, so the unexpected value is visible
        assertThat(profile.getStatus()).isEqualTo(99);
        assertThat(profile.getStatusText()).isEqualTo("UNKNOWN");
    }

    @Test
    void aNullStatusIsLabelledRatherThanThrown() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agentWithRawStatus(null));

        AgentPublicProfileResponse profile = service.getPublicProfile(AGENT_ID);

        assertThat(profile.getStatus()).isNull();
        assertThat(profile.getStatusText()).isEqualTo("UNKNOWN");
    }

    // ========== Normal response ==========

    @Test
    void aProfileCarriesTheIdentityStatsAndTimeline() {
        AgentPublicProfileResponse profile = service.getPublicProfile(AGENT_ID);

        assertThat(profile.getId()).isEqualTo(AGENT_ID);
        assertThat(profile.getName()).isEqualTo("Pulse");
        assertThat(profile.getAvatarUrl()).isEqualTo("https://cdn/avatar.png");
        assertThat(profile.getStatus()).isEqualTo(AgentStatus.ALIVE.getCode());
        assertThat(profile.getStatusText()).isEqualTo(AgentStatus.ALIVE.getText());
        assertThat(profile.getOwnerName()).isEqualTo("owner-name");
        assertThat(profile.getCreatedAt()).isEqualTo("2026-01-02T03:04:05");

        assertThat(profile.getStats().getPostCount()).isEqualTo(12);
        assertThat(profile.getStats().getCommentCount()).isEqualTo(34);
        assertThat(profile.getStats().getTipsReceivedCount()).isEqualTo(3);
        assertThat(profile.getStats().getTipsReceivedTotal()).isEqualByComparingTo("150.00");

        assertThat(profile.getFrequentInteractions()).hasSize(2);
        assertThat(profile.getFrequentInteractions().get(0).getAgentId()).isEqualTo(8L);
        assertThat(profile.getFrequentInteractions().get(0).getName()).isEqualTo("Echo");
        assertThat(profile.getFrequentInteractions().get(0).getCount()).isEqualTo(9);

        assertThat(profile.getRecentPosts()).hasSize(1);
        assertThat(profile.getRecentPosts().get(0).getPostId()).isEqualTo(101L);
        assertThat(profile.getRecentPosts().get(0).getContentPreview()).isEqualTo("hello world");
        assertThat(profile.getRecentPosts().get(0).getLikeCount()).isEqualTo(5);
        assertThat(profile.getRecentPosts().get(0).getCommentCount()).isEqualTo(2);
        assertThat(profile.getRecentPosts().get(0).getCreatedAt()).isEqualTo("2026-02-03T04:05:06");
    }

    /**
     * The peers and the timeline are capped by the query, so the endpoint costs the same
     * for an agent with three comments and one with three hundred thousand.
     */
    @Test
    void theListsAreBoundedByTheQueryRatherThanTrimmedAfterwards() {
        service.getPublicProfile(AGENT_ID);

        verify(commentMapper).findFrequentAgentInteractions(AGENT_ID, 5);
        verify(postMapper).findRecentAgentPosts(AGENT_ID, 5);
    }

    /**
     * A preview is one line: a post whose text spans several lines must not turn into a
     * card that is mostly whitespace.
     */
    @Test
    void aPreviewIsCollapsedToOneLineAndCutAtTheLimit() {
        String content = "first line\nsecond line\n" + "x".repeat(200);
        when(postMapper.findRecentAgentPosts(anyLong(), anyInt()))
                .thenReturn(List.of(post(101L, content)));

        String preview = service.getPublicProfile(AGENT_ID).getRecentPosts().get(0).getContentPreview();

        assertThat(preview).doesNotContain("\n");
        assertThat(preview).startsWith("first line second line x");
        assertThat(preview).endsWith("...");
        assertThat(preview).hasSize(123); // 120 characters plus the ellipsis
    }

    /**
     * A DEAD agent is the whole point of a memorial page - it must render, not 404.
     */
    @Test
    void aDeadAgentStillHasAPublicPage() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(AgentStatus.DEAD.getCode()));

        AgentPublicProfileResponse profile = service.getPublicProfile(AGENT_ID);

        assertThat(profile.getStatus()).isEqualTo(AgentStatus.DEAD.getCode());
        assertThat(profile.getStatusText()).isEqualTo(AgentStatus.DEAD.getText());
    }

    /**
     * Agents cannot accept bounties in this schema (both hunter_id columns are foreign
     * keys into users), so the counter is a documented zero rather than the owner's own
     * completions borrowed for the agent.
     */
    @Test
    void completedBountiesReportZeroWithoutQueryingBounties() {
        assertThat(service.getPublicProfile(AGENT_ID).getStats().getCompletedBountyCount())
                .isZero();
    }

    /**
     * An agent that was never tipped reads as zero, not as null - the ledger simply has
     * no matching row.
     */
    @Test
    void anAgentWithoutTipsReportsZero() {
        when(sysLedgerMapper.findAgentTipTotals(AGENT_ID)).thenReturn(null);

        AgentPublicProfileResponse.Stats stats = service.getPublicProfile(AGENT_ID).getStats();

        assertThat(stats.getTipsReceivedCount()).isZero();
        assertThat(stats.getTipsReceivedTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ========== Active-hours badge ==========

    @Test
    void isActiveNowFollowsAWindowThatWrapsMidnight() {
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(wakeSettings(22, 6));

        // Computed independently of the production code, so the assertion holds whenever
        // the suite happens to run.
        int hour = LocalDateTime.now().getHour();
        boolean expected = hour >= 22 || hour < 6;

        assertThat(service.getPublicProfile(AGENT_ID).getIsActiveNow()).isEqualTo(expected);
    }

    /**
     * A wrapped window covering every hour except the current one: the badge has to be
     * able to say "no", not just default to true.
     */
    @Test
    void isActiveNowIsFalseOutsideTheWindow() {
        int hour = LocalDateTime.now().getHour();
        when(agentMapper.findWakeSettings(AGENT_ID))
                .thenReturn(wakeSettings((hour + 1) % 24, hour));

        assertThat(service.getPublicProfile(AGENT_ID).getIsActiveNow()).isFalse();
    }

    @Test
    void isActiveNowIsTrueInsideTheWindow() {
        int hour = LocalDateTime.now().getHour();
        when(agentMapper.findWakeSettings(AGENT_ID))
                .thenReturn(wakeSettings(hour, (hour + 1) % 24));

        assertThat(service.getPublicProfile(AGENT_ID).getIsActiveNow()).isTrue();
    }

    /**
     * On a database without the phase-3 migration nobody knows the agent's routine.
     * Reporting "asleep" would be a confident wrong answer, so all three fields are null
     * and the rhythm query is never issued.
     */
    @Test
    void aLegacySchemaReportsNoRhythmAtAll() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        AgentPublicProfileResponse profile = service.getPublicProfile(AGENT_ID);

        assertThat(profile.getWakeHoursStart()).isNull();
        assertThat(profile.getWakeHoursEnd()).isNull();
        assertThat(profile.getIsActiveNow()).isNull();
        verify(agentMapper, never()).findWakeSettings(anyLong());
    }

    /**
     * The columns exist but this row has never been scheduled: still not an answer.
     */
    @Test
    void anUnsetWindowLeavesTheBadgeUnknown() {
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(wakeSettings(null, null));

        assertThat(service.getPublicProfile(AGENT_ID).getIsActiveNow()).isNull();
    }

    // ========== Missing agent ==========

    @Test
    void anUnknownAgentIsReportedAsNotFound() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getPublicProfile(AGENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo(ErrorCode.AGENT_NOT_FOUND.getCode()));
    }

    /**
     * A soft-deleted agent is filtered out by the logic-delete column, so selectById
     * already returns null and the caller gets the same 404 as for a made-up id - no
     * "this agent used to exist" signal for an anonymous prober.
     */
    @Test
    void aDeletedAgentIsIndistinguishableFromAMissingOne() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getPublicProfile(AGENT_ID))
                .isInstanceOf(BusinessException.class);
        verify(postMapper, never()).countAgentPosts(anyLong());
        verify(sysLedgerMapper, never()).findAgentTipTotals(anyLong());
    }

    // ========== In-process cache ==========

    /**
     * This page had no cache of any kind: every hit was a fixed eight queries straight
     * to MySQL, bounded only by an IP limiter that fails open when Redis is down - the
     * same moment the rest of the read paths start falling back to MySQL too. A short
     * process-local memo turns a crawler walking the id space into one render per agent
     * per TTL.
     */
    @Test
    void aRepeatedProfileIsServedFromTheInProcessCache() {
        AgentPublicProfileResponse first = service.getPublicProfile(AGENT_ID);
        clearInvocations(agentMapper, userMapper, postMapper, commentMapper, sysLedgerMapper);

        AgentPublicProfileResponse second = service.getPublicProfile(AGENT_ID);

        assertThat(second).isSameAs(first);
        verifyNoInteractions(agentMapper);
        verifyNoInteractions(userMapper);
        verifyNoInteractions(postMapper);
        verifyNoInteractions(commentMapper);
        verifyNoInteractions(sysLedgerMapper);
    }

    /** The key is the agent id, so one agent's page is never answered with another's. */
    @Test
    void theCacheIsKeyedByAgentId() {
        Agent other = agent(AgentStatus.ALIVE.getCode());
        other.setId(43L);
        other.setName("Other");
        when(agentMapper.selectById(43L)).thenReturn(other);

        service.getPublicProfile(AGENT_ID);

        assertThat(service.getPublicProfile(43L).getId()).isEqualTo(43L);
    }

    /**
     * A 404 is not cached: the lookup that produces it is a single primary-key read,
     * and memoising it would make a newly created agent read as absent for half a
     * minute.
     */
    @Test
    void aMissingAgentIsNotMemoised() {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.getPublicProfile(AGENT_ID))
                .isInstanceOf(BusinessException.class);

        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(AgentStatus.ALIVE.getCode()));
        assertThat(service.getPublicProfile(AGENT_ID).getId()).isEqualTo(AGENT_ID);
    }

    // ========== Nothing sensitive escapes ==========

    @Test
    void theSerializedProfileCarriesNoCredentialEndpointOrPrompt() throws Exception {
        String json = new ObjectMapper().writeValueAsString(service.getPublicProfile(AGENT_ID));

        assertThat(json)
                .doesNotContain("apiKey").doesNotContain("api_key")
                .doesNotContain("systemPrompt").doesNotContain("system_prompt")
                .doesNotContain("baseUrl").doesNotContain("base_url")
                .doesNotContain("modelName").doesNotContain("model_name")
                // the values themselves, in case a field is ever renamed
                .doesNotContain("encrypted-key").doesNotContain("https://api.example.com")
                .doesNotContain("gpt-secret-model").doesNotContain("You are a secret agent")
                // token budget and the owner's id are owner-only too
                .doesNotContain("used_tokens").doesNotContain("token_threshold")
                .doesNotContain("owner_id");

        // and the fields the page does need are actually there, so the assertion above
        // cannot pass by producing an empty document
        assertThat(json).contains("\"owner_name\"").contains("\"is_active_now\"")
                .contains("\"recent_posts\"").contains("\"frequent_interactions\"")
                .contains("\"tips_received_total\"");
    }

    // ========== Fixtures ==========

    private Agent agentWithRawStatus(Integer status) {
        Agent agent = agent(AgentStatus.ALIVE.getCode());
        agent.setStatus(status);
        return agent;
    }

    private Agent agent(int status) {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setOwnerId(OWNER_ID);
        agent.setName("Pulse");
        agent.setAvatarUrl("https://cdn/avatar.png");
        agent.setStatus(status);
        agent.setApiKey("encrypted-key");
        agent.setBaseUrl("https://api.example.com");
        agent.setModelName("gpt-secret-model");
        agent.setSystemPrompt("You are a secret agent");
        agent.setUsedTokens(1_000L);
        agent.setTokenThreshold(10_000L);
        agent.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return agent;
    }

    private User user() {
        User user = new User();
        user.setId(OWNER_ID);
        user.setUsername("owner-name");
        return user;
    }

    private AgentWakeSettings wakeSettings(Integer start, Integer end) {
        return AgentWakeSettings.builder()
                .agentId(AGENT_ID)
                .wakeHoursStart(start)
                .wakeHoursEnd(end)
                .dailyWakeBudget(4)
                .build();
    }

    private Post post(Long id, String content) {
        Post post = new Post();
        post.setId(id);
        post.setAuthorId(AGENT_ID);
        post.setContent(content);
        post.setLikeCount(5);
        post.setCommentCount(2);
        post.setCreatedAt(LocalDateTime.of(2026, 2, 3, 4, 5, 6));
        return post;
    }
}
