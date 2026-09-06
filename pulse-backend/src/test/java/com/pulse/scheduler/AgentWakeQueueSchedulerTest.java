package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentWakeEventMapper;
import com.pulse.service.support.WakeScheduleCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The queue tick decides who wakes up and why. Its whole job is to be cheap and hard to
 * abuse: every wake-up passes an atomic budget+debounce claim, interactions are merged
 * into one call, and nothing is dropped just because the budget ran out.
 */
class AgentWakeQueueSchedulerTest {

    private static final Long AGENT_ID = 42L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentWakeEventMapper agentWakeEventMapper = mock(AgentWakeEventMapper.class);
    private final AgentWakeProcessor agentWakeProcessor = mock(AgentWakeProcessor.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final WakeScheduleCalculator wakeScheduleCalculator =
            new WakeScheduleCalculator(new Random(7));

    private final AgentWakeQueueScheduler scheduler = new AgentWakeQueueScheduler(
            agentMapper, agentWakeEventMapper, agentWakeProcessor, agentActionExecutor,
            wakeScheduleCalculator, schemaCapabilities);

    @BeforeEach
    void configureQueueMode() {
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", true);
        ReflectionTestUtils.setField(scheduler, "mode", "queue");
        ReflectionTestUtils.setField(scheduler, "eventBatchSize", 20);
        ReflectionTestUtils.setField(scheduler, "rhythmBatchSize", 20);
        ReflectionTestUtils.setField(scheduler, "maxEventsPerWake", 10);
        ReflectionTestUtils.setField(scheduler, "minWakeIntervalMinutes", 15);
        ReflectionTestUtils.setField(scheduler, "eventExpiryHours", 24);
        ReflectionTestUtils.setField(scheduler, "targetDailyRhythmWakes", 3);
        ReflectionTestUtils.setField(scheduler, "defaultDailyWakeBudget", 4);
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
        // default: the slot is granted
        when(agentMapper.claimWakeSlot(anyLong(), any(LocalDateTime.class), any(LocalDate.class),
                any(LocalDateTime.class), anyInt())).thenReturn(1);
        // events are consumed before the model call, so the default is "we got them"
        when(agentWakeEventMapper.markProcessed(any(), any(LocalDateTime.class))).thenReturn(1);
    }

    // ========== Mode gating ==========

    @Test
    void legacyModeWakesNobody() {
        ReflectionTestUtils.setField(scheduler, "mode", "legacy");

        scheduler.tick();

        verifyNoInteractions(agentWakeProcessor);
        verify(agentWakeEventMapper, never()).findAgentIdsWithPendingEvents(anyInt());
    }

    /**
     * Queue mode on a database without the migration must not silence the community: it
     * falls back to legacy, which means this tick does nothing and the legacy batch runs.
     */
    @Test
    void queueModeWithoutTheSchemaFallsBackToLegacy() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        scheduler.tick();

        verifyNoInteractions(agentWakeEventMapper);
        verifyNoInteractions(agentWakeProcessor);
    }

    @Test
    void anUnknownModeDoesNotWakeAnybody() {
        ReflectionTestUtils.setField(scheduler, "mode", "aggressive");

        scheduler.tick();

        verifyNoInteractions(agentWakeProcessor);
    }

    @Test
    void aDisabledSchedulerDoesNothingAtAll() {
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", false);

        scheduler.tick();

        verifyNoInteractions(agentWakeEventMapper);
        verifyNoInteractions(agentWakeProcessor);
    }

    /**
     * The switch-on stampede: every stored agent has next_wake_at NULL, and NULL sorts first
     * in the candidate query. Waking them on the spot would put the whole population through
     * the model within the first ticks of queue mode - hundreds of calls, charged to owners.
     * A NULL schedule means "not scheduled yet", so the tick only spreads them.
     */
    @Test
    void agentsWithNoScheduleAreSpreadOutInsteadOfWokenImmediately() {
        Agent first = agent(alwaysActiveHours());
        Agent second = agent(alwaysActiveHours());
        second.setId(43L);
        Agent third = agent(alwaysActiveHours());
        third.setId(44L);
        // fresh from the migration: no schedule at all
        first.setNextWakeAt(null);
        second.setNextWakeAt(null);
        third.setNextWakeAt(null);
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(first, second, third));

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.RHYTHM), any());
        verify(agentMapper, never()).claimWakeSlot(anyLong(), any(), any(), any(), anyInt());

        ArgumentCaptor<LocalDateTime> scheduled = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMapper, times(3)).updateNextWakeAt(anyLong(), scheduled.capture());
        // spread across the coming day, and no two agents on the same instant
        assertThat(scheduled.getAllValues()).doesNotHaveDuplicates();
        assertThat(scheduled.getAllValues()).allSatisfy(when ->
                assertThat(when).isAfter(LocalDateTime.now()).isBefore(LocalDateTime.now().plusDays(2)));
    }

    @Test
    void anAgentWithAScheduleIsStillWokenNormally() {
        Agent agent = agent(alwaysActiveHours());
        agent.setNextWakeAt(LocalDateTime.now().minusMinutes(1));
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(agent));

        scheduler.tick();

        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.RHYTHM), eq(List.of()));
    }

    /**
     * Enqueueing is gated on the schema, not the mode, so a rollback to legacy keeps
     * collecting events (deliberately - a short rollback should not lose the conversations
     * that happened during it). That only holds if something keeps trimming the table, so the
     * housekeeping sweeps run in legacy too.
     */
    @Test
    void housekeepingStillRunsInLegacyMode() {
        ReflectionTestUtils.setField(scheduler, "mode", "legacy");

        scheduler.tick();

        verify(agentWakeEventMapper).expirePendingOlderThan(any(LocalDateTime.class),
                any(LocalDateTime.class));
        verify(agentWakeEventMapper).expirePendingForInactiveAgents(any(LocalDateTime.class));
        // ...but nothing is woken and no model is called
        verifyNoInteractions(agentWakeProcessor);
        verify(agentWakeEventMapper, never()).findAgentIdsWithPendingEvents(anyInt());
    }

    @Test
    void withoutTheSchemaEvenHousekeepingStaysQuiet() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        scheduler.tick();

        verifyNoInteractions(agentWakeEventMapper);
        verifyNoInteractions(agentWakeProcessor);
    }

    // ========== Interaction wakes ==========

    /**
     * Five replies are one conversation. Merging them into a single wake-up is the
     * difference between answering an interaction and paying per notification.
     */
    @Test
    void allPendingEventsOfOneAgentAreAnsweredByASingleWake() {
        givenPendingEvents(event(1L), event(2L), event(3L));

        scheduler.tick();

        ArgumentCaptor<List<AgentWakeEvent>> captor = eventListCaptor();
        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.EVENT), captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        // claimed one at a time, so a concurrent tick's partial win is knowable
        verify(agentWakeEventMapper).markProcessed(eq(List.of(1L)), any(LocalDateTime.class));
        verify(agentWakeEventMapper).markProcessed(eq(List.of(2L)), any(LocalDateTime.class));
        verify(agentWakeEventMapper).markProcessed(eq(List.of(3L)), any(LocalDateTime.class));
    }

    /**
     * The claim is the only gate that matters for cost: refused means no model call. The
     * events stay PENDING because an interaction is the most valuable reason to wake up -
     * it waits for tomorrow's budget instead of being thrown away.
     */
    @Test
    void aRefusedClaimLeavesTheEventsPendingAndCallsNoModel() {
        givenPendingEvents(event(1L));
        when(agentMapper.claimWakeSlot(anyLong(), any(LocalDateTime.class), any(LocalDate.class),
                any(LocalDateTime.class), anyInt())).thenReturn(0);

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.EVENT), any());
        verify(agentWakeEventMapper, never()).markProcessed(any(), any(LocalDateTime.class));
    }

    @Test
    void theClaimCarriesTodayAndTheDebounceCutoff() {
        givenPendingEvents(event(1L));

        LocalDateTime before = LocalDateTime.now();
        scheduler.tick();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMapper).claimWakeSlot(eq(AGENT_ID), any(LocalDateTime.class), eq(LocalDate.now()),
                cutoff.capture(), eq(4));
        // 15-minute debounce window, so the cutoff sits ~15 minutes in the past
        assertThat(cutoff.getValue()).isBefore(before.minusMinutes(14));
        assertThat(cutoff.getValue()).isAfter(before.minusMinutes(16));
    }

    /**
     * Two instances (or a tick that overran its lock) must not both consume the same
     * events. The loser's conditional update reports 0 rows and it then skips the wake
     * entirely - calling the model anyway would charge the owner for a conversation
     * somebody else is already answering.
     */
    @Test
    void losingTheRaceToConsumeEventsSkipsTheWake() {
        givenPendingEvents(event(1L));
        when(agentWakeEventMapper.markProcessed(any(), any(LocalDateTime.class))).thenReturn(0);

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.EVENT), any());
    }

    /**
     * Events are consumed BEFORE the model call, so a crash costs one missed reply rather
     * than a replay that charges the owner twice for the same conversation.
     */
    @Test
    void eventsAreConsumedBeforeTheModelCallAndNotReturnedOnFailure() {
        givenPendingEvents(event(1L));
        org.mockito.Mockito.doThrow(new RuntimeException("gateway exploded"))
                .when(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.EVENT), any());

        scheduler.tick();

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(agentWakeEventMapper, agentWakeProcessor);
        order.verify(agentWakeEventMapper).markProcessed(any(), any(LocalDateTime.class));
        order.verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.EVENT), any());
        verify(agentActionExecutor).logAgentError(any(Agent.class), any(), eq(0L));
    }

    /**
     * A dead agent's events are the oldest in the queue, so they would win the ordering for
     * ever and starve every living agent.
     */
    @Test
    void eventsOfAgentsThatAreNoLongerAliveAreExpired() {
        scheduler.tick();

        verify(agentWakeEventMapper).expirePendingForInactiveAgents(any(LocalDateTime.class));
    }

    /**
     * Only the events this tick actually won are answered. Handing a half-claimed batch to
     * the model would have this agent replying under a post whose interaction another
     * instance is already handling.
     */
    @Test
    void aPartiallyConsumedBatchOnlyAnswersWhatItWon() {
        givenPendingEvents(event(1L), event(2L), event(3L));
        when(agentWakeEventMapper.markProcessed(eq(List.of(1L)), any(LocalDateTime.class))).thenReturn(1);
        when(agentWakeEventMapper.markProcessed(eq(List.of(2L)), any(LocalDateTime.class))).thenReturn(0);
        when(agentWakeEventMapper.markProcessed(eq(List.of(3L)), any(LocalDateTime.class))).thenReturn(1);

        scheduler.tick();

        ArgumentCaptor<List<AgentWakeEvent>> captor = eventListCaptor();
        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.EVENT), captor.capture());
        assertThat(captor.getValue()).extracting(AgentWakeEvent::getId).containsExactly(1L, 3L);
    }

    /**
     * The claim is already spent when the consumption fails, so it has to be handed back -
     * otherwise the owner is billed a wake-up that never happened while the interaction
     * sits PENDING until it expires.
     */
    @Test
    void aFailureToConsumeReleasesTheClaimedSlot() {
        givenPendingEvents(event(1L));
        when(agentWakeEventMapper.markProcessed(any(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db down"));

        scheduler.tick();

        verify(agentMapper).releaseWakeSlot(eq(AGENT_ID), any(LocalDate.class));
        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.EVENT), any());
    }

    @Test
    void losingEveryEventAlsoReleasesTheSlot() {
        givenPendingEvents(event(1L));
        when(agentWakeEventMapper.markProcessed(any(), any(LocalDateTime.class))).thenReturn(0);

        scheduler.tick();

        verify(agentMapper).releaseWakeSlot(eq(AGENT_ID), any(LocalDate.class));
    }

    /**
     * A long batch must not judge its last agent against a timestamp from minutes ago -
     * that is how a wake crosses midnight, escapes its active window, or gets a shortened
     * debounce. Every agent re-reads the clock.
     */
    @Test
    void everyAgentIsJudgedAgainstAFreshlyReadClock() {
        LocalDateTime base = LocalDateTime.of(2026, 7, 28, 12, 0);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ReflectionTestUtils.setField(scheduler, "clock",
                (java.util.function.Supplier<LocalDateTime>) () -> base.plusMinutes(reads.getAndIncrement()));

        when(agentWakeEventMapper.findAgentIdsWithPendingEvents(anyInt()))
                .thenReturn(List.of(AGENT_ID, 43L));
        Agent second = agent(alwaysActiveHours());
        second.setId(43L);
        when(agentMapper.findAliveAgentsByIds(List.of(AGENT_ID, 43L)))
                .thenReturn(List.of(agent(alwaysActiveHours()), second));
        when(agentWakeEventMapper.findPendingByAgent(anyLong(), anyInt())).thenReturn(List.of(event(1L)));

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMapper, times(2)).claimWakeSlot(anyLong(), nowCaptor.capture(),
                any(LocalDate.class), any(LocalDateTime.class), anyInt());
        assertThat(nowCaptor.getAllValues().get(0)).isNotEqualTo(nowCaptor.getAllValues().get(1));
    }

    /**
     * A spent budget is why an owner's agent goes quiet for the rest of the day, so it has
     * to be visible at the production log level and distinguishable from routine debounce.
     */
    @Test
    void aRefusedClaimIsExplainedFromTheAgentsBudget() {
        givenPendingEvents(event(1L));
        when(agentMapper.claimWakeSlot(anyLong(), any(LocalDateTime.class), any(LocalDate.class),
                any(LocalDateTime.class), anyInt())).thenReturn(0);
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(AgentWakeSettings.builder()
                .agentId(AGENT_ID)
                .dailyWakeBudget(4)
                .wakeCountToday(4)
                .wakeCountDate(LocalDate.now())
                .build());

        scheduler.tick();

        // the used/limit numbers come from this read, which only happens on refusal
        verify(agentMapper).findWakeSettings(AGENT_ID);
        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.EVENT), any());
    }

    /**
     * An unreadable budget must not turn a routine refusal into a failure.
     */
    @Test
    void anUnreadableBudgetStillJustDefersTheWake() {
        givenPendingEvents(event(1L));
        when(agentMapper.claimWakeSlot(anyLong(), any(LocalDateTime.class), any(LocalDate.class),
                any(LocalDateTime.class), anyInt())).thenReturn(0);
        when(agentMapper.findWakeSettings(AGENT_ID)).thenThrow(new RuntimeException("unknown column"));

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.EVENT), any());
        verify(agentWakeEventMapper, never()).markProcessed(any(), any(LocalDateTime.class));
    }

    // ========== Expiry ==========

    @Test
    void staleEventsAreExpiredEveryTick() {
        LocalDateTime before = LocalDateTime.now();

        scheduler.tick();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentWakeEventMapper).expirePendingOlderThan(cutoff.capture(), any(LocalDateTime.class));
        assertThat(cutoff.getValue()).isBefore(before.minusHours(23));
        assertThat(cutoff.getValue()).isAfter(before.minusHours(25));
    }

    // ========== Rhythm wakes ==========

    @Test
    void anAgentInsideItsWindowIsWokenAndRescheduled() {
        Agent agent = agent(alwaysActiveHours());
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(agent));

        scheduler.tick();

        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.RHYTHM), eq(List.of()));
        // rescheduled BEFORE the call, so a crash cannot leave next_wake_at in the past
        verify(agentMapper).updateNextWakeAt(eq(AGENT_ID), any(LocalDateTime.class));
    }

    /**
     * Outside its hours the agent is rescheduled instead of woken - and rescheduled at
     * all, so it is not re-read on every tick for the rest of the night.
     */
    @Test
    void anAgentOutsideItsWindowIsRescheduledNotWoken() {
        int hour = LocalDateTime.now().getHour();
        // a one-hour window that certainly does not contain "now"
        int start = (hour + 3) % 24;
        Agent agent = agent(new int[]{start, (start + 1) % 24});
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(agent));

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.RHYTHM), any());
        verify(agentMapper).updateNextWakeAt(eq(AGENT_ID), any(LocalDateTime.class));
        verify(agentMapper, never()).claimWakeSlot(anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void aRhythmWakeRefusedByTheBudgetIsStillRescheduled() {
        Agent agent = agent(alwaysActiveHours());
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(agent));
        when(agentMapper.claimWakeSlot(anyLong(), any(LocalDateTime.class), any(LocalDate.class),
                any(LocalDateTime.class), anyInt())).thenReturn(0);

        scheduler.tick();

        verify(agentWakeProcessor, never()).wake(any(Agent.class), eq(WakeReason.RHYTHM), any());
        verify(agentMapper).updateNextWakeAt(eq(AGENT_ID), any(LocalDateTime.class));
    }

    /**
     * Interactions are answered before routine browsing, and both share the one budget.
     */
    @Test
    void bothWakeReasonsRunInOneTick() {
        givenPendingEvents(event(1L));
        Agent rhythmAgent = agent(alwaysActiveHours());
        rhythmAgent.setId(43L);
        when(agentMapper.findRhythmWakeCandidates(any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of(rhythmAgent));

        scheduler.tick();

        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.EVENT), any());
        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.RHYTHM), eq(List.of()));
        verify(agentMapper, times(2)).claimWakeSlot(anyLong(), any(), any(), any(), anyInt());
    }

    // ========== Fixtures ==========

    private void givenPendingEvents(AgentWakeEvent... events) {
        when(agentWakeEventMapper.findAgentIdsWithPendingEvents(anyInt())).thenReturn(List.of(AGENT_ID));
        when(agentMapper.findAliveAgentsByIds(List.of(AGENT_ID)))
                .thenReturn(List.of(agent(alwaysActiveHours())));
        when(agentWakeEventMapper.findPendingByAgent(eq(AGENT_ID), anyInt())).thenReturn(List.of(events));
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<AgentWakeEvent>> eventListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    /** start == end reads as "always active", which keeps these tests clock-independent. */
    private int[] alwaysActiveHours() {
        return new int[]{0, 0};
    }

    private Agent agent(int[] hours) {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setOwnerId(7L);
        agent.setName("Pulse");
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        agent.setIsUnlimited(false);
        agent.setWakeHoursStart(hours[0]);
        agent.setWakeHoursEnd(hours[1]);
        agent.setDailyWakeBudget(4);
        // Already scheduled and due. A NULL schedule means "never scheduled", which is its
        // own path (see agentsWithNoScheduleAreSpreadOutInsteadOfWokenImmediately).
        agent.setNextWakeAt(LocalDateTime.now().minusMinutes(1));
        return agent;
    }

    private AgentWakeEvent event(Long id) {
        AgentWakeEvent event = new AgentWakeEvent();
        event.setId(id);
        event.setAgentId(AGENT_ID);
        event.setEventType("REPLIED");
        event.setSourceType("COMMENT");
        event.setSourceId(500L + id);
        event.setActorType("HUMAN");
        event.setActorId(7L);
        event.setStatus("PENDING");
        event.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        return event;
    }
}
