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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reflection scheduler's job is orchestration and billing: who gets reflected on,
 * who is skipped, and what the agent is charged. The distillation itself is the
 * gateway's problem and persisting it is the service's.
 */
class MemoryReflectionSchedulerTest {

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
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 500);
        ReflectionTestUtils.setField(scheduler, "windowHours", 26);
        ReflectionTestUtils.setField(scheduler, "minTokenCharge", 200L);
    }

    // ========== Billing ==========

    @Test
    void aSuccessfulReflectionIsPersistedAndChargedInOneTransaction() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder()
                .success(true)
                .newTraits(List.of(ReflectionResult.TraitDraft.builder().content("特质").build()))
                .totalTokens(1234)
                .build());

        scheduler.reflectOnRecentBehavior();

        // persist + charge + audit are one unit, so the scheduler never charges directly
        verify(reflectionPersistExecutor).applyAndCharge(any(Agent.class), any(ReflectionResult.class),
                eq(1234L), eq(AgentActionExecutor.REFLECTION_SUCCESS), anyString());
        verify(agentActionExecutor, never())
                .chargeReflectionTokens(any(Agent.class), anyLong(), anyString(), anyString());
    }

    /**
     * Same rule as the agent loop: a call that reached the model is never free, or
     * token_threshold stops being a limit an agent can die from.
     */
    @Test
    void aFailedReflectionWithNoUsageChargesTheFloorAndPersistsNothing() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.failed("gateway down"));

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(reflectionPersistExecutor);
        verify(agentActionExecutor).chargeReflectionTokens(any(Agent.class), eq(200L),
                eq(AgentActionExecutor.REFLECTION_FAILED), anyString());
    }

    /**
     * The gateway answers total_tokens=0 when it never called the model (for example
     * everything was filtered out of the behaviour pack). Charging the floor there billed
     * the owner for work nobody did.
     */
    @Test
    void anExplicitlyFreeRunChargesNothingAndIsLabelledSkipped() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder()
                .success(true)
                .newTraits(List.of())
                .updatedTraits(List.of())
                .deprecatedTraitIds(List.of())
                .totalTokens(0)
                .build());

        scheduler.reflectOnRecentBehavior();

        verify(reflectionPersistExecutor).applyAndCharge(any(Agent.class), any(ReflectionResult.class),
                eq(0L), eq(AgentActionExecutor.REFLECTION_SKIPPED), anyString());
    }

    @Test
    void aFailedReflectionThatReportedUsageIsChargedThatUsage() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        ReflectionResult failed = ReflectionResult.failed("model refused");
        failed.setTotalTokens(40);
        givenGatewayResult(failed);

        scheduler.reflectOnRecentBehavior();

        verify(agentActionExecutor).chargeReflectionTokens(any(Agent.class), eq(40L),
                eq(AgentActionExecutor.REFLECTION_FAILED), anyString());
    }

    /**
     * The provider already billed, so a rolled-back persist must still cost tokens - and
     * the audit row must say FAILED, not SUCCESS.
     */
    @Test
    void aPersistFailureIsChargedSeparatelyAndMarkedFailed() {
        givenCandidates(agent(1L, 0L, 100000L), agent(2L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(500).build());
        when(reflectionPersistExecutor.applyAndCharge(any(Agent.class), any(ReflectionResult.class),
                anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        scheduler.reflectOnRecentBehavior();

        // charged for both agents despite the rollback, and the batch continued
        verify(agentActionExecutor, times(2)).chargeReflectionTokens(any(Agent.class), eq(500L),
                eq(AgentActionExecutor.REFLECTION_FAILED), anyString());
    }

    @Test
    void whenBothPersistAndChargeFailTheRunStillCompletes() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(500).build());
        when(reflectionPersistExecutor.applyAndCharge(any(Agent.class), any(ReflectionResult.class),
                anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));
        doThrow(new RuntimeException("db still down")).when(agentActionExecutor)
                .chargeReflectionTokens(any(Agent.class), anyLong(), anyString(), anyString());

        scheduler.reflectOnRecentBehavior();

        verify(agentActionExecutor).chargeReflectionTokens(any(Agent.class), eq(500L),
                eq(AgentActionExecutor.REFLECTION_FAILED), anyString());
    }

    // ========== Who gets skipped ==========

    @Test
    void aTokenExhaustedAgentIsSkippedBeforeTheCall() {
        givenCandidates(agent(1L, 100000L, 100000L));

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(llmClient);
        verifyNoInteractions(agentActionExecutor);
        verify(agentMemoryService, never()).buildReflectionContext(anyLong(), any(LocalDateTime.class));
    }

    /**
     * One settled reflection per agent per day, so a manual re-trigger is a no-op instead
     * of a second set of duplicate traits. SKIPPED counts as settled; FAILED does not, so
     * a broken gateway can be retried.
     */
    @Test
    void anAgentAlreadyReflectedOnTodayIsSkipped() {
        givenCandidates(agent(1L, 0L, 100000L));
        when(agentLogMapper.countCompletedReflectionsSince(eq(1L), any(LocalDateTime.class)))
                .thenReturn(1);

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(llmClient);
        verifyNoInteractions(agentActionExecutor);
        verify(agentMemoryService, never()).buildReflectionContext(anyLong(), any(LocalDateTime.class));
    }

    /**
     * An agent whose only recent log rows are reflection's own audit rows produces an
     * empty behaviour pack, so no HTTP call is made at all.
     */
    @Test
    void anAgentWithNoRecordedBehaviorCostsNoHttpCall() {
        givenCandidates(agent(1L, 0L, 100000L));
        when(agentMemoryService.buildReflectionContext(anyLong(), any(LocalDateTime.class)))
                .thenReturn(ReflectionContext.builder().recentBehaviors(List.of()).build());

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(llmClient);
        verifyNoInteractions(agentActionExecutor);
        verifyNoInteractions(reflectionPersistExecutor);
    }

    @Test
    void disabledSchedulerDoesNothing() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(agentMapper);
        verifyNoInteractions(llmClient);
    }

    // ========== Candidate walking ==========

    /**
     * A fixed top-N starved every agent after the first page, for ever. The run has to
     * page through the whole candidate set on an id cursor.
     */
    @Test
    void everyCandidateIsVisitedAcrossPages() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 2);
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(0L), eq(2)))
                .thenReturn(List.of(agent(1L, 0L, 100000L), agent(2L, 0L, 100000L)));
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(2L), eq(2)))
                .thenReturn(List.of(agent(3L, 0L, 100000L)));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(10).build());

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(reflectionPersistExecutor, times(3)).applyAndCharge(captor.capture(),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
        assertThat(captor.getAllValues()).extracting(Agent::getId).containsExactly(1L, 2L, 3L);
    }

    /**
     * The cap is a cost brake, not a silent truncation: the run stops and asks how many
     * candidates it did not get to, so the log can say so.
     */
    @Test
    void theHardCapStopsTheRunAndCountsWhatItSkipped() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 2);
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 2);
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(0L), eq(2)))
                .thenReturn(List.of(agent(1L, 0L, 100000L), agent(2L, 0L, 100000L)));
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(2L), eq(2)))
                .thenReturn(List.of(agent(3L, 0L, 100000L), agent(4L, 0L, 100000L)));
        when(agentMapper.countAliveAgentsActiveSince(any(LocalDateTime.class))).thenReturn(4);
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(10).build());

        scheduler.reflectOnRecentBehavior();

        verify(reflectionPersistExecutor, times(2)).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
        verify(agentMapper).countAliveAgentsActiveSince(any(LocalDateTime.class));
    }

    /**
     * The quota counts calls, not candidates. When most of the candidate set was already
     * settled earlier the same day, the agents that still need reflecting must not find
     * the budget already spent on skipping the others.
     */
    @Test
    void agentsSkippedForIdempotenceDoNotConsumeTheQuota() {
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 1);
        givenCandidates(agent(1L, 0L, 100000L), agent(2L, 0L, 100000L), agent(3L, 0L, 100000L));
        when(agentLogMapper.countCompletedReflectionsSince(eq(1L), any(LocalDateTime.class)))
                .thenReturn(1);
        when(agentLogMapper.countCompletedReflectionsSince(eq(2L), any(LocalDateTime.class)))
                .thenReturn(1);
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(10).build());

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(reflectionPersistExecutor).applyAndCharge(captor.capture(),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
        assertThat(captor.getValue().getId()).isEqualTo(3L);
    }

    /**
     * A run the gateway answered without calling a model is settled too - repeating it
     * only buys another round trip for a guaranteed no-op.
     */
    @Test
    void anAgentWhoseRunWasSkippedTodayIsAlsoConsideredSettled() {
        givenCandidates(agent(1L, 0L, 100000L));
        when(agentLogMapper.countCompletedReflectionsSince(eq(1L), any(LocalDateTime.class)))
                .thenReturn(1);

        scheduler.reflectOnRecentBehavior();

        verifyNoInteractions(llmClient);
    }

    @Test
    void theActivityWindowIsPassedToBothTheQueryAndTheContext() {
        givenCandidates(agent(1L, 0L, 100000L));
        givenBehaviors("[记忆] 我发布了帖子《A》");
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(10).build());

        LocalDateTime before = LocalDateTime.now().minusHours(26);
        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMapper).findAliveAgentsActiveSince(captor.capture(), eq(0L), eq(50));
        assertThat(captor.getValue()).isAfterOrEqualTo(before.minusMinutes(1));
        verify(agentMemoryService).buildReflectionContext(eq(1L), any(LocalDateTime.class));
    }

    private void givenCandidates(Agent... agents) {
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(0L), anyInt()))
                .thenReturn(List.of(agents));
    }

    private void givenBehaviors(String... behaviors) {
        when(agentMemoryService.buildReflectionContext(anyLong(), any(LocalDateTime.class)))
                .thenReturn(ReflectionContext.builder()
                        .recentBehaviors(List.of(behaviors))
                        .existingTraits(List.of())
                        .maxNewTraits(5)
                        .maxTotalTraits(30)
                        .build());
    }

    private void givenGatewayResult(ReflectionResult result) {
        when(llmClient.callReflection(any(Agent.class), any(ReflectionContext.class))).thenReturn(result);
    }

    private Agent agent(Long id, Long usedTokens, Long threshold) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setOwnerId(7L);
        agent.setName("Agent" + id);
        agent.setUsedTokens(usedTokens);
        agent.setTokenThreshold(threshold);
        agent.setIsUnlimited(false);
        return agent;
    }
}
