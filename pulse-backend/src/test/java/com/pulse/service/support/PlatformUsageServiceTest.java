package com.pulse.service.support;

import com.pulse.config.PlatformLlmProperties;
import com.pulse.entity.Agent;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.service.PointsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two gates on platform spending: who may be woken, and what the call costs.
 *
 * Both are about somebody else's money, in opposite directions. checkReadiness may refuse
 * (nothing has been spent yet); charge may not (the tokens are already gone). The tests
 * below pin that asymmetry as hard as they pin the arithmetic.
 */
class PlatformUsageServiceTest {

    private static final Long AGENT_ID = 42L;
    private static final Long OWNER_ID = 7L;

    private final PlatformLlmProperties properties = new PlatformLlmProperties();
    private final LlmCredentialResolver credentialResolver = mock(LlmCredentialResolver.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final PointsService pointsService = mock(PointsService.class);
    private final AgentPointsNotifier agentPointsNotifier = mock(AgentPointsNotifier.class);

    private final PlatformUsageService service = new PlatformUsageService(
            properties, credentialResolver, agentLogMapper, pointsService, agentPointsNotifier);

    @BeforeEach
    void everythingIsAvailableAndUnderTheCaps() {
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
    }

    // ========== checkReadiness ==========

    @Test
    void aByokAgentIsNeverSubjectToAnyOfThis() {
        when(credentialResolver.isPlatformAgent(any())).thenReturn(false);

        assertThat(service.checkReadiness(agent())).isNull();
        // Not one query: BYOK agents must not pay for a feature they do not use
        verifyNoInteractions(agentLogMapper);
        verifyNoInteractions(pointsService);
    }

    @Test
    void aHealthyPlatformAgentIsCleared() {
        assertThat(service.checkReadiness(agent())).isNull();
    }

    @Test
    void anUnavailablePlatformSkipsWithPlatformUnavailable() {
        when(credentialResolver.isPlatformAvailable()).thenReturn(false);

        assertThat(service.checkReadiness(agent()))
                .isEqualTo(PlatformUsageService.SkipReason.PLATFORM_UNAVAILABLE);
        // Cheapest check first: an unconfigured deployment never touches the database
        verifyNoInteractions(agentLogMapper);
        verifyNoInteractions(pointsService);
    }

    @Test
    void anEmptyOwnerBalanceSkipsWithOwnerPointsInsufficient() {
        when(pointsService.getAvailablePoints(OWNER_ID)).thenReturn(new BigDecimal("0.50"));

        assertThat(service.checkReadiness(agent()))
                .isEqualTo(PlatformUsageService.SkipReason.OWNER_POINTS_INSUFFICIENT);
    }

    @Test
    void exactlyTheFloorIsStillEnough() {
        when(pointsService.getAvailablePoints(OWNER_ID)).thenReturn(new BigDecimal("1.00"));

        assertThat(service.checkReadiness(agent())).isNull();
    }

    @Test
    void theAgentDailyCapSkipsWithAgentDailyCap() {
        when(agentLogMapper.sumTokensSince(eq(AGENT_ID), any(LocalDateTime.class))).thenReturn(50000L);

        assertThat(service.checkReadiness(agent()))
                .isEqualTo(PlatformUsageService.SkipReason.AGENT_DAILY_CAP);
    }

    @Test
    void theGlobalDailyCapSkipsWithGlobalDailyCap() {
        when(agentLogMapper.sumPlatformTokensSince(any(LocalDateTime.class))).thenReturn(2000000L);

        assertThat(service.checkReadiness(agent()))
                .isEqualTo(PlatformUsageService.SkipReason.GLOBAL_DAILY_CAP);
    }

    @Test
    void aZeroCapMeansNoCap() {
        properties.setDailyTokenCapPerAgent(0);
        properties.setDailyTokenCapGlobal(0);
        when(agentLogMapper.sumTokensSince(anyLong(), any(LocalDateTime.class))).thenReturn(999999999L);

        assertThat(service.checkReadiness(agent())).isNull();
    }

    /**
     * A cap query that will not run must not silence every platform agent in the
     * community. Overspending against a brake for one cycle is the lesser failure.
     */
    @Test
    void anUnreadableCounterDoesNotSilenceTheAgent() {
        when(agentLogMapper.sumTokensSince(anyLong(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("no such column"));

        assertThat(service.checkReadiness(agent())).isNull();
    }

    @Test
    void anUnreadableBalanceDoesNotSilenceTheAgent() {
        when(pointsService.getAvailablePoints(anyLong()))
                .thenThrow(new RuntimeException("database down"));

        assertThat(service.checkReadiness(agent())).isNull();
    }

    // ========== Notification ==========

    @Test
    void onlyTheOwnerActionableReasonProducesANotification() {
        service.notifyIfActionable(agent(), PlatformUsageService.SkipReason.OWNER_POINTS_INSUFFICIENT);
        verify(agentPointsNotifier).notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse");

        service.notifyIfActionable(agent(), PlatformUsageService.SkipReason.GLOBAL_DAILY_CAP);
        service.notifyIfActionable(agent(), PlatformUsageService.SkipReason.AGENT_DAILY_CAP);
        service.notifyIfActionable(agent(), PlatformUsageService.SkipReason.PLATFORM_UNAVAILABLE);
        // Still just the one call: the other three are platform conditions the owner can
        // do nothing about.
        verify(agentPointsNotifier, org.mockito.Mockito.times(1))
                .notifyPointsInsufficient(anyLong(), anyLong(), anyString());
    }

    // ========== Cost arithmetic ==========

    @Test
    void costIsTokensOverAThousandTimesTheRate() {
        assertThat(service.costOf(1000)).isEqualByComparingTo("1.00");
        assertThat(service.costOf(1500)).isEqualByComparingTo("1.50");
        assertThat(service.costOf(2000)).isEqualByComparingTo("2.00");
    }

    /**
     * Rounding is UP, at two decimals. Half-even would let a long run of small calls round
     * to nothing repeatedly, which is the platform paying for them.
     */
    @Test
    void aFractionOfACentRoundsUp() {
        assertThat(service.costOf(1501)).isEqualByComparingTo("1.51");
        assertThat(service.costOf(1)).isEqualByComparingTo("0.01");
        assertThat(service.costOf(10)).isEqualByComparingTo("0.01");
    }

    @Test
    void theRateScalesTheCost() {
        properties.setPointsPer1kTokens(new BigDecimal("0.30"));

        assertThat(service.costOf(1000)).isEqualByComparingTo("0.30");
        assertThat(service.costOf(3333)).isEqualByComparingTo("1.00");
    }

    @Test
    void aZeroRateMakesThePlatformModelFree() {
        properties.setPointsPer1kTokens(BigDecimal.ZERO);

        assertThat(service.costOf(999999)).isEqualByComparingTo("0");
        assertThat(service.charge(agent(), 999999, "platform-model")).isEqualByComparingTo("0");
        verify(pointsService, never()).spendAvailablePoints(anyLong(), any(), anyString(),
                anyString(), anyLong(), anyString());
    }

    // ========== charge ==========

    @Test
    void chargeWritesAnLlmUsageLedgerRowAgainstTheAgent() {
        when(pointsService.spendAvailablePoints(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString())).thenReturn(new BigDecimal("2.00"));

        BigDecimal spent = service.charge(agent(), 2000, "platform-model");

        assertThat(spent).isEqualByComparingTo("2.00");
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(pointsService).spendAvailablePoints(eq(OWNER_ID),
                eq(new BigDecimal("2.00")), eq("LLM_USAGE"), eq("AGENT"), eq(AGENT_ID),
                description.capture());
        // The owner has to be able to tell what they paid for from the ledger alone
        assertThat(description.getValue()).contains("2000").contains("platform-model").contains("Pulse");
    }

    @Test
    void aByokAgentIsNeverCharged() {
        when(credentialResolver.isPlatformAgent(any())).thenReturn(false);

        assertThat(service.charge(agent(), 5000, "gpt-4o-mini")).isEqualByComparingTo("0");
        verifyNoInteractions(pointsService);
    }

    @Test
    void aCycleWithNoTokensIsNotCharged() {
        assertThat(service.charge(agent(), 0, "platform-model")).isEqualByComparingTo("0");
        verifyNoInteractions(pointsService);
    }

    /**
     * The tokens are already spent. A short balance is charged to what is there, and the
     * wake-up carries on - the alternatives are inventing debt or giving the call away.
     */
    @Test
    void aShortBalanceIsChargedToZeroWithoutThrowing() {
        when(pointsService.spendAvailablePoints(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString())).thenReturn(new BigDecimal("0.40"));

        assertThat(service.charge(agent(), 2000, "platform-model")).isEqualByComparingTo("0.40");
    }

    @Test
    void aFailingLedgerDoesNotAbortTheWakeUp() {
        when(pointsService.spendAvailablePoints(anyLong(), any(), anyString(), anyString(),
                anyLong(), anyString())).thenThrow(new RuntimeException("deadlock"));

        assertThat(service.charge(agent(), 2000, "platform-model")).isEqualByComparingTo("0");
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setOwnerId(OWNER_ID);
        agent.setName("Pulse");
        agent.setProviderMode("PLATFORM");
        return agent;
    }
}
