package com.pulse.scheduler;

import com.pulse.client.LLMClient;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.service.AgentMemoryService;
import com.pulse.service.PointsService;
import com.pulse.service.support.AgentPointsNotifier;
import com.pulse.service.support.LlmCredentialResolver;
import com.pulse.service.support.PlatformUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The platform gate inside the nightly reflection pass.
 *
 * Reflection was the one path that could call the platform model without passing the
 * spending gate and without charging anyone: a PLATFORM agent whose owner was out of
 * points, or whose daily allowance was gone, still got a free distillation every night at
 * 03:40. These tests pin both halves - the refusal before the call and the charge after
 * it - and, just as importantly, what a refusal must NOT do: consume the per-run ceiling
 * that belongs to agents which can still pay, or leave the refused agent parked at the
 * head of tomorrow's queue.
 *
 * The service under the scheduler is the real one rather than a mock, because "BYOK
 * agents are not charged" is a claim about points actually moving, and a mock would let
 * that claim pass while the ledger filled up.
 */
class MemoryReflectionPlatformGateTest {

    private static final Long OWNER_ID = 7L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);
    private final ReflectionPersistExecutor reflectionPersistExecutor =
            mock(ReflectionPersistExecutor.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final PlatformLlmProperties properties = new PlatformLlmProperties();
    private final LlmCredentialResolver credentialResolver = mock(LlmCredentialResolver.class);
    private final PointsService pointsService = mock(PointsService.class);
    private final AgentPointsNotifier agentPointsNotifier = mock(AgentPointsNotifier.class);

    private final PlatformUsageService platformUsageService = new PlatformUsageService(
            properties, credentialResolver, agentLogMapper, pointsService, agentPointsNotifier);

    private final MemoryReflectionScheduler scheduler = new MemoryReflectionScheduler(
            agentMapper, agentLogMapper, agentMemoryService, llmClient,
            agentActionExecutor, reflectionPersistExecutor, schemaCapabilities,
            platformUsageService);

    @BeforeEach
    void aPlatformAgentUnderEveryCap() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 50);
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 500);
        ReflectionTestUtils.setField(scheduler, "windowHours", 26);
        ReflectionTestUtils.setField(scheduler, "minTokenCharge", 200L);

        properties.setEnabled(true);
        properties.setApiKey("sk-platform");
        properties.setModelName("platform-model");
        properties.setPointsPer1kTokens(BigDecimal.ONE);
        properties.setDailyTokenCapPerAgent(50000L);
        properties.setDailyTokenCapGlobal(2000000L);
        properties.setMinPointsToWake(BigDecimal.ONE);

        when(credentialResolver.isPlatformAgent(any())).thenReturn(true);
        when(credentialResolver.isPlatformAvailable()).thenReturn(true);
        when(pointsService.getAvailablePoints(OWNER_ID)).thenReturn(new BigDecimal("100.00"));
        when(agentLogMapper.sumTokensSince(anyLong(), any(LocalDateTime.class))).thenReturn(0L);
        when(agentLogMapper.sumPlatformTokensSince(any(LocalDateTime.class))).thenReturn(0L);
        when(pointsService.spendAvailablePoints(anyLong(), any(BigDecimal.class), anyString(),
                anyString(), anyLong(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        givenBehaviors();
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(2000).build());
    }

    // ========== Refused before the call ==========

    @Test
    void anUnavailablePlatformSkipsTheReflection() {
        when(credentialResolver.isPlatformAvailable()).thenReturn(false);
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        thenNothingWasSpent();
    }

    @Test
    void anOwnerBelowTheFloorSkipsTheReflection() {
        when(pointsService.getAvailablePoints(OWNER_ID)).thenReturn(new BigDecimal("0.40"));
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        thenNothingWasSpent();
    }

    @Test
    void anAgentAtItsDailyTokenCapSkipsTheReflection() {
        when(agentLogMapper.sumTokensSince(eq(1L), any(LocalDateTime.class))).thenReturn(50000L);
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        thenNothingWasSpent();
    }

    @Test
    void theGlobalDailyCapSkipsTheReflection() {
        when(agentLogMapper.sumPlatformTokensSince(any(LocalDateTime.class))).thenReturn(2000000L);
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        thenNothingWasSpent();
    }

    /**
     * A refused agent has still had its turn. Without the stamp it sorts to the front of
     * tomorrow's queue - and of every queue after that, for as long as its owner stays
     * out of points - so the cap would bite on a prefix of agents that can only ever be
     * refused, and the agents that can pay would never be reached.
     */
    @Test
    void aRefusedAgentStillGetsItsCursorStamped() {
        when(schemaCapabilities.isReflectionCursorColumn()).thenReturn(true);
        when(credentialResolver.isPlatformAvailable()).thenReturn(false);
        when(agentMapper.findAliveAgentsActiveSinceByReflectionCursor(any(LocalDateTime.class),
                eq(false), any(), eq(0L), any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(agent(1L)));

        scheduler.reflectOnRecentBehavior();

        verify(agentMapper).markReflectionAttempt(eq(1L), any(LocalDateTime.class));
    }

    /**
     * The per-run ceiling is a cost brake, so it counts calls. A refusal costs nothing and
     * must leave the whole budget to the agents behind it.
     */
    @Test
    void aRefusedAgentDoesNotConsumeThePerRunCeiling() {
        ReflectionTestUtils.setField(scheduler, "maxAgentsPerRun", 1);
        // Agent 1 is out of points; agent 2 can pay and must still get the run's one call.
        when(pointsService.getAvailablePoints(OWNER_ID))
                .thenReturn(new BigDecimal("0.40"))
                .thenReturn(new BigDecimal("100.00"));
        givenCandidates(agent(1L), agent(2L));

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(reflectionPersistExecutor).applyAndCharge(captor.capture(),
                any(ReflectionResult.class), anyLong(), anyString(), anyString());
        assertThat(captor.getValue().getId()).isEqualTo(2L);
    }

    // ========== Charged after the call ==========

    @Test
    void aPlatformReflectionIsChargedToTheOwnerAndNamedInTheLedger() {
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(pointsService).spendAvailablePoints(eq(OWNER_ID), cost.capture(), anyString(),
                anyString(), eq(1L), description.capture());
        // 2000 tokens at 1 point/1k
        assertThat(cost.getValue()).isEqualByComparingTo("2.00");
        // The ledger row has to say which of the two platform call sites this was
        assertThat(description.getValue()).contains("反思").contains("2000")
                .contains("platform-model");
    }

    /**
     * Same reasoning as the token charge: a failure envelope does not prove the provider
     * did not bill, so the floor is charged in points as well.
     */
    @Test
    void aFailedPlatformReflectionStillChargesTheFloor() {
        givenGatewayResult(ReflectionResult.failed("gateway down"));
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        ArgumentCaptor<BigDecimal> cost = ArgumentCaptor.forClass(BigDecimal.class);
        verify(pointsService).spendAvailablePoints(eq(OWNER_ID), cost.capture(), anyString(),
                anyString(), eq(1L), anyString());
        assertThat(cost.getValue()).isEqualByComparingTo("0.20");
    }

    /**
     * The gateway answered without calling a model, so there is nothing to bill for -
     * neither in tokens nor in points.
     */
    @Test
    void anExplicitlyFreeRunMovesNoPoints() {
        givenGatewayResult(ReflectionResult.builder().success(true).totalTokens(0).build());
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        verify(pointsService, never()).spendAvailablePoints(anyLong(), any(BigDecimal.class),
                anyString(), anyString(), anyLong(), anyString());
    }

    /**
     * A BYOK agent already paid its provider directly. Charging it points as well would
     * bill the owner twice for one call.
     */
    @Test
    void aByokReflectionMovesNoPointsAndPassesNoGate() {
        when(credentialResolver.isPlatformAgent(any())).thenReturn(false);
        givenCandidates(agent(1L));

        scheduler.reflectOnRecentBehavior();

        // The reflection itself happened as before
        verify(reflectionPersistExecutor).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), eq(2000L), anyString(), anyString());
        // but nothing about the platform was consulted or charged
        verifyNoInteractions(pointsService);
        verify(agentLogMapper, never()).sumPlatformTokensSince(any(LocalDateTime.class));
    }

    /**
     * The tokens are already spent by the time the charge runs, so a points failure has
     * nothing left to prevent - only the rest of the queue to lose.
     */
    @Test
    void aChargeFailureDoesNotEndTheBatch() {
        when(pointsService.spendAvailablePoints(anyLong(), any(BigDecimal.class), anyString(),
                anyString(), anyLong(), anyString()))
                .thenThrow(new RuntimeException("ledger down"));
        givenCandidates(agent(1L), agent(2L));

        assertThatCode(scheduler::reflectOnRecentBehavior).doesNotThrowAnyException();

        // Both agents were reflected on and both charges were attempted
        verify(reflectionPersistExecutor, times(2)).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), eq(2000L), anyString(), anyString());
        verify(pointsService, times(2)).spendAvailablePoints(anyLong(), any(BigDecimal.class),
                anyString(), anyString(), anyLong(), anyString());
    }

    /**
     * The service is documented never to throw, and this is the belt to that braces: if a
     * future change or a wrapping proxy ever lets one out, it must cost the agent it was
     * charging, not every agent behind it in tonight's queue.
     */
    @Test
    void aChargeThatThrowsAnywayDoesNotEndTheBatch() {
        PlatformUsageService throwing = mock(PlatformUsageService.class);
        when(throwing.charge(any(), anyLong(), any(), anyString()))
                .thenThrow(new RuntimeException("points bean exploded"));
        MemoryReflectionScheduler withThrowingCharge = new MemoryReflectionScheduler(
                agentMapper, agentLogMapper, agentMemoryService, llmClient,
                agentActionExecutor, reflectionPersistExecutor, schemaCapabilities, throwing);
        ReflectionTestUtils.setField(withThrowingCharge, "enabled", true);
        ReflectionTestUtils.setField(withThrowingCharge, "batchSize", 50);
        ReflectionTestUtils.setField(withThrowingCharge, "maxAgentsPerRun", 500);
        ReflectionTestUtils.setField(withThrowingCharge, "windowHours", 26);
        ReflectionTestUtils.setField(withThrowingCharge, "minTokenCharge", 200L);
        givenCandidates(agent(1L), agent(2L));

        assertThatCode(withThrowingCharge::reflectOnRecentBehavior).doesNotThrowAnyException();

        verify(reflectionPersistExecutor, times(2)).applyAndCharge(any(Agent.class),
                any(ReflectionResult.class), eq(2000L), anyString(), anyString());
    }

    // ========== helpers ==========

    /**
     * A refusal spends nothing at all: no model call, no context build, no token charge
     * and no points movement.
     */
    private void thenNothingWasSpent() {
        verifyNoInteractions(llmClient);
        verifyNoInteractions(reflectionPersistExecutor);
        verifyNoInteractions(agentActionExecutor);
        verify(agentMemoryService, never()).buildReflectionContext(anyLong(), any(LocalDateTime.class));
        verify(pointsService, never()).spendAvailablePoints(anyLong(), any(BigDecimal.class),
                anyString(), anyString(), anyLong(), anyString());
    }

    private void givenCandidates(Agent... agents) {
        when(agentMapper.findAliveAgentsActiveSince(any(LocalDateTime.class), eq(0L), anyInt()))
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

    private Agent agent(Long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setOwnerId(OWNER_ID);
        agent.setName("Agent" + id);
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        agent.setIsUnlimited(false);
        return agent;
    }
}
