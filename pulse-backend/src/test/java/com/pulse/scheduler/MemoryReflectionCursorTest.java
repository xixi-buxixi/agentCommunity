package com.pulse.scheduler;

import com.pulse.client.LLMClient;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.service.AgentMemoryService;
import com.pulse.service.support.PlatformUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who the daily reflection pass gets to, and in what order.
 *
 * The id cursor it shipped with is fair only when a run reaches the end of the list. The
 * moment the per-run ceiling bites - or the job is interrupted - it starts at the lowest
 * id again, so the same agents are reflected on every night and the tail never is. With
 * agents.last_reflection_attempt_at the queue is ordered by how long each agent has
 * waited, and this is where the paging over that ordering is pinned down: a keyset that
 * skips a row loses an agent's turn for the night, and one that repeats a row spends the
 * owner's tokens twice.
 */
class MemoryReflectionCursorTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);
    private final ReflectionPersistExecutor reflectionPersistExecutor =
            mock(ReflectionPersistExecutor.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final PlatformUsageService platformUsageService = mock(PlatformUsageService.class);

    private final MemoryReflectionScheduler scheduler = new MemoryReflectionScheduler(
            agentMapper, agentLogMapper, agentMemoryService, llmClient,
            agentActionExecutor, reflectionPersistExecutor, schemaCapabilities,
            platformUsageService);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 2);
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 500);
        ReflectionTestUtils.setField(scheduler, "windowHours", 26);
        ReflectionTestUtils.setField(scheduler, "minTokenCharge", 200L);
        when(schemaCapabilities.isReflectionCursorColumn()).thenReturn(true);
        givenBehaviors();
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(10).build());
    }

    // ========== paging ==========

    /**
     * The first page carries no keyset at all; every later page carries the values of the
     * LAST agent of the previous page, exactly as they were read.
     */
    @Test
    void theFirstPageHasNoCursorAndTheNextOneStartsAtTheLastAgentRead() {
        LocalDateTime older = LocalDateTime.of(2026, 9, 1, 3, 40);
        LocalDateTime newer = LocalDateTime.of(2026, 9, 3, 3, 40);
        givenPage(false, null, 0L, agent(1L, older), agent(2L, newer));
        givenPage(true, newer, 2L);

        scheduler.reflectOnRecentBehavior();

        InOrder order = inOrder(agentMapper);
        order.verify(agentMapper).findAliveAgentsActiveSinceByReflectionCursor(
                any(LocalDateTime.class), eq(false), isNull(), eq(0L),
                any(LocalDateTime.class), eq(2));
        order.verify(agentMapper).findAliveAgentsActiveSinceByReflectionCursor(
                any(LocalDateTime.class), eq(true), eq(newer), eq(2L),
                any(LocalDateTime.class), eq(2));
    }

    /**
     * A NULL cursor value is a legitimate position, not "no cursor": never-attempted
     * agents sort first, so "after (NULL, 41)" means "still inside that block, past agent
     * 41". Collapsing the two would restart the run at the top of the list.
     */
    @Test
    void aNullAttemptTimeIsCarriedAsACursorPositionRatherThanAsNoCursor() {
        givenPage(false, null, 0L, agent(41L, null), agent(42L, null));
        givenPage(true, null, 42L);

        scheduler.reflectOnRecentBehavior();

        verify(agentMapper).findAliveAgentsActiveSinceByReflectionCursor(
                any(LocalDateTime.class), eq(true), isNull(), eq(42L),
                any(LocalDateTime.class), eq(2));
    }

    /**
     * Every agent gets exactly one turn, and no page repeats one.
     *
     * This is what the run-start watermark is for: stamping an agent moves it to the END
     * of this ordering, which is by definition past the cursor, so without the watermark
     * a run would keep re-selecting the work it had just finished - and bill for it again.
     */
    @Test
    void everyCandidateIsReflectedOnExactlyOnceAcrossPages() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 3, 40);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 2, 3, 40);
        givenPage(false, null, 0L, agent(1L, null), agent(2L, t1));
        givenPage(true, t1, 2L, agent(3L, t2));

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(reflectionPersistExecutor, times(3)).applyAndCharge(captor.capture(),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
        assertThat(captor.getAllValues()).extracting(Agent::getId).containsExactly(1L, 2L, 3L);
    }

    /**
     * The watermark is read once and reused for every page. Recomputing it per page would
     * let an agent stamped early in a long run become eligible again on a later page.
     */
    @Test
    void theRunStartWatermarkIsTheSameForEveryPage() {
        LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 3, 40);
        givenPage(false, null, 0L, agent(1L, null), agent(2L, t1));
        givenPage(true, t1, 2L);

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<LocalDateTime> watermark = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMapper, times(2)).findAliveAgentsActiveSinceByReflectionCursor(
                any(LocalDateTime.class), anyBoolean(), any(), anyLong(),
                watermark.capture(), anyInt());
        assertThat(watermark.getAllValues().get(1)).isEqualTo(watermark.getAllValues().get(0));
    }

    // ========== stamping ==========

    @Test
    void aSuccessfulReflectionStampsTheCursor() {
        givenPage(false, null, 0L, agent(1L, null));

        scheduler.reflectOnRecentBehavior();

        verify(agentMapper).markReflectionAttempt(eq(1L), any(LocalDateTime.class));
    }

    /**
     * A failed one stamps too. Stamping only successes would leave an agent whose
     * reflection fails every night permanently at the front of the queue, taking the same
     * turn for ever.
     */
    @Test
    void aFailedReflectionStampsTheCursorAsWell() {
        givenPage(false, null, 0L, agent(1L, null));
        givenGatewayResult(ReflectionResult.failed("gateway down"));

        scheduler.reflectOnRecentBehavior();

        verify(agentMapper).markReflectionAttempt(eq(1L), any(LocalDateTime.class));
    }

    /**
     * So does an empty behaviour pack: the agent HAD its turn, there was simply nothing
     * to distil. Otherwise a permanently quiet agent would hold the front of the queue.
     */
    @Test
    void anEmptyBehaviourPackStampsTheCursorEvenThoughNoCallWasMade() {
        givenPage(false, null, 0L, agent(1L, null));
        when(agentMemoryService.buildReflectionContext(anyLong(), any(LocalDateTime.class)))
                .thenReturn(ReflectionContext.builder().recentBehaviors(List.of()).build());

        scheduler.reflectOnRecentBehavior();

        verify(llmClient, never()).callReflection(any(Agent.class), any(ReflectionContext.class));
        verify(agentMapper).markReflectionAttempt(eq(1L), any(LocalDateTime.class));
    }

    /**
     * The two refusals are stamped as well, and this is the whole point of the stamp
     * being an ATTEMPT rather than a reflection. An agent that has never reflected sorts
     * first for ever (NULL leads the ordering), so leaving an exhausted one unstamped had
     * it re-read and re-refused at the head of the first page every night, while the
     * agents behind it never came into view.
     */
    @Test
    void anAgentSkippedForExhaustionOrIdempotenceIsStampedToo() {
        Agent exhausted = agent(1L, null);
        exhausted.setUsedTokens(100000L);
        Agent settled = agent(2L, null);
        givenPage(false, null, 0L, exhausted, settled);
        when(agentLogMapper.countCompletedReflectionsSince(eq(2L), any(LocalDateTime.class)))
                .thenReturn(1);

        scheduler.reflectOnRecentBehavior();

        verify(llmClient, never()).callReflection(any(Agent.class), any(ReflectionContext.class));
        verify(agentMapper).markReflectionAttempt(eq(1L), any(LocalDateTime.class));
        verify(agentMapper).markReflectionAttempt(eq(2L), any(LocalDateTime.class));
    }

    /**
     * The stamp is an ordering hint. Failing to write it costs at most one unfair turn and
     * must never abort a run that has already spent the owner's tokens.
     */
    @Test
    void aFailingStampDoesNotStopTheRun() {
        givenPage(false, null, 0L, agent(1L, null), agent(2L, null));
        when(agentMapper.markReflectionAttempt(anyLong(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("agents is gone"));

        assertThatCode(scheduler::reflectOnRecentBehavior).doesNotThrowAnyException();

        verify(reflectionPersistExecutor, times(2)).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
    }

    // ========== fallback ==========

    /**
     * Without the column the pass keeps its original id cursor and never names the column
     * in a statement - the whole point of the capability flag.
     */
    @Test
    void withoutTheColumnTheRunFallsBackToTheIdCursorAndStampsNothing() {
        when(schemaCapabilities.isReflectionCursorColumn()).thenReturn(false);
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(0L), eq(2)))
                .thenReturn(List.of(agent(1L, null), agent(2L, null)));
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(2L), eq(2)))
                .thenReturn(List.of());

        scheduler.reflectOnRecentBehavior();

        verify(agentMapper, never()).findAliveAgentsActiveSinceByReflectionCursor(
                any(LocalDateTime.class), anyBoolean(), any(), anyLong(),
                any(LocalDateTime.class), anyInt());
        verify(agentMapper, never()).markReflectionAttempt(anyLong(), any(LocalDateTime.class));
        verify(reflectionPersistExecutor, times(2)).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
    }

    // ========== fixtures ==========

    private void givenPage(boolean hasCursor, LocalDateTime afterAttemptAt, long afterId,
                           Agent... agents) {
        when(agentMapper.findAliveAgentsActiveSinceByReflectionCursor(any(LocalDateTime.class),
                eq(hasCursor), afterAttemptAt == null ? isNull() : eq(afterAttemptAt),
                eq(afterId), any(LocalDateTime.class), eq(2)))
                .thenReturn(List.of(agents));
    }

    private void givenBehaviors() {
        when(agentMemoryService.buildReflectionContext(anyLong(), any(LocalDateTime.class)))
                .thenReturn(ReflectionContext.builder()
                        .recentBehaviors(List.of("[记忆] 我发布了帖子《A》"))
                        .existingTraits(List.of())
                        .maxNewTraits(5)
                        .maxTotalTraits(30)
                        .build());
    }

    private void givenGatewayResult(ReflectionResult result) {
        when(llmClient.callReflection(any(Agent.class), any(ReflectionContext.class)))
                .thenReturn(result);
    }

    private Agent agent(Long id, LocalDateTime lastAttempt) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setOwnerId(7L);
        agent.setName("Agent" + id);
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        agent.setIsUnlimited(false);
        agent.setLastReflectionAttemptAt(lastAttempt);
        return agent;
    }
}
