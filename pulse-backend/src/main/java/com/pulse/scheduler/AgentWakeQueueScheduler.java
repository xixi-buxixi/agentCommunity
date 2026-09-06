package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.config.WakeMode;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.dto.WakeLogContext;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentWakeEventMapper;
import com.pulse.service.support.WakeScheduleCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The per-agent wake queue: a light tick every few minutes that decides who wakes up.
 *
 * Two reasons to wake an agent, in priority order:
 * 1. Someone interacted with it (a reply, a comment, a tip). All of that agent's pending
 *    events are answered by ONE wake-up - five replies are one conversation, not five
 *    LLM calls.
 * 2. Its own rhythm came due, inside its own active hours.
 *
 * Every wake-up passes through {@link AgentMapper#claimWakeSlot}, a single conditional
 * UPDATE that enforces the daily budget and the debounce interval atomically. That is
 * what makes the cost bounded: the tick can run every five minutes, on several
 * instances, and an agent still cannot be woken more often than its owner allows.
 *
 * Cost of a full backlog is bounded rather than dropped: an agent out of budget keeps its
 * events PENDING for tomorrow (an interaction is the most valuable reason to wake up, so
 * it waits instead of being discarded), and the expiry sweep retires anything older than
 * the configured window so a long silence cannot turn into a wake-up storm.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWakeQueueScheduler {

    private final AgentMapper agentMapper;
    private final AgentWakeEventMapper agentWakeEventMapper;
    private final AgentWakeProcessor agentWakeProcessor;
    private final AgentActionExecutor agentActionExecutor;
    private final WakeScheduleCalculator wakeScheduleCalculator;
    private final SchemaCapabilities schemaCapabilities;

    /**
     * Source of "now".
     *
     * A seam, not indirection for its own sake: every agent in a batch has to be judged
     * against a freshly read clock (see wakeForEvents / wakeForRhythm), and a test needs to
     * be able to prove that rather than race the wall clock.
     */
    private Supplier<LocalDateTime> clock = LocalDateTime::now;

    @Value("${scheduler.agent-loop.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${scheduler.agent-loop.mode:legacy}")
    private String mode;

    /** Agents woken for interactions in one tick. */
    @Value("${scheduler.agent-loop.event-batch-size:20}")
    private int eventBatchSize;

    /** Agents woken by their own rhythm in one tick. */
    @Value("${scheduler.agent-loop.rhythm-batch-size:20}")
    private int rhythmBatchSize;

    /**
     * Events merged into a single wake-up. Anything beyond this stays PENDING and is picked
     * up by a later wake, so a very popular agent answers its backlog across several turns
     * rather than in one enormous prompt.
     */
    @Value("${scheduler.agent-loop.max-events-per-wake:10}")
    private int maxEventsPerWake;

    /** Debounce: an agent woken more recently than this is left alone. */
    @Value("${scheduler.agent-loop.min-wake-interval-minutes:15}")
    private int minWakeIntervalMinutes;

    /** Pending events older than this are retired unanswered. */
    @Value("${scheduler.agent-loop.event-expiry-hours:24}")
    private int eventExpiryHours;

    /** Target number of rhythm wake-ups per day, before jitter. */
    @Value("${scheduler.agent-loop.target-daily-rhythm-wakes:3}")
    private int targetDailyRhythmWakes;

    /** Budget assumed for rows predating the migration. */
    @Value("${scheduler.agent-loop.default-daily-wake-budget:4}")
    private int defaultDailyWakeBudget;

    /**
     * Light tick, five minutes by default: minutes-level latency for an interaction
     * without polling anything expensive. ShedLock keeps a second instance from waking
     * the same agents in parallel.
     */
    @Scheduled(fixedDelayString = "${scheduler.agent-loop.tick-interval:300000}")
    @SchedulerLock(name = "agentWakeQueueTick", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void tick() {
        if (!schedulerEnabled) {
            log.debug("Agent loop scheduler is disabled");
            return;
        }
        if (!schemaCapabilities.isWakeQueueSchema()) {
            // No queue table to keep tidy either
            log.debug("Wake-queue schema is absent; the queue tick has nothing to do");
            return;
        }

        // Tick-level time is fine for the batch-wide sweeps and queries; each agent then
        // re-reads the clock for its own decisions, because a long batch would otherwise
        // judge the last agent against a timestamp from minutes ago - crossing midnight,
        // leaving its active window, or shortening its debounce.
        LocalDateTime tickNow = clock.get();

        // Housekeeping runs in BOTH modes, deliberately.
        //
        // Enqueueing is gated on the schema, not on the mode, so a deployment that falls
        // back to legacy keeps collecting interaction events. That is the intended
        // behaviour - a short rollback should not lose the conversations that happened
        // during it, and switching back to queue picks them up - but only as long as
        // something keeps trimming the table. Without this, a rollback would leave events
        // accumulating for ever with the expiry sweep switched off. It is pure database
        // work, no model call, so running it in legacy mode costs nothing.
        expireStaleEvents(tickNow);

        if (WakeMode.resolve(mode, schemaCapabilities.isWakeQueueSchema()) != WakeMode.QUEUE) {
            log.debug("Agent loop is in legacy mode; the wake queue only does housekeeping");
            return;
        }

        int eventWakes = runEventWakes(tickNow);
        int rhythmWakes = runRhythmWakes(tickNow);

        if (eventWakes > 0 || rhythmWakes > 0) {
            log.info("Wake queue tick: eventWakes={}, rhythmWakes={}", eventWakes, rhythmWakes);
        }
    }

    /**
     * Retire events nobody could get to. Without this, an agent that was out of budget
     * for a week would wake up to a week of backlog all at once.
     */
    private void expireStaleEvents(LocalDateTime now) {
        try {
            int expired = agentWakeEventMapper.expirePendingOlderThan(
                    now.minusHours(Math.max(eventExpiryHours, 1)), now);
            if (expired > 0) {
                log.info("Expired {} stale wake events", expired);
            }
        } catch (Exception e) {
            log.warn("Could not expire stale wake events: {}", e.getMessage());
        }
        try {
            // A dead agent's events are the oldest in the queue, so they win the ordering
            // and can never be consumed - a few dead agents would starve every living one.
            int orphaned = agentWakeEventMapper.expirePendingForInactiveAgents(now);
            if (orphaned > 0) {
                log.info("Expired {} wake events belonging to agents that are no longer alive", orphaned);
            }
        } catch (Exception e) {
            log.warn("Could not expire wake events of inactive agents: {}", e.getMessage());
        }
    }

    // ========== Interaction wakes ==========

    private int runEventWakes(LocalDateTime tickNow) {
        List<Long> agentIds;
        try {
            agentIds = agentWakeEventMapper.findAgentIdsWithPendingEvents(Math.max(eventBatchSize, 1));
        } catch (Exception e) {
            log.error("Could not read the wake queue", e);
            return 0;
        }
        if (agentIds == null || agentIds.isEmpty()) {
            return 0;
        }

        List<Agent> agents = agentMapper.findAliveAgentsByIds(agentIds);
        int woken = 0;
        for (Agent agent : agents) {
            // Filled in by wakeForEvents as soon as it knows which events this wake-up
            // is answering, so the failure path below can attribute its error row to
            // the same wake-up the successful path would have attributed it to.
            List<AgentWakeEvent> attempted = new ArrayList<>();
            try {
                if (wakeForEvents(agent, attempted)) {
                    woken++;
                }
            } catch (Exception e) {
                // The events stay PENDING, so the next tick retries; a chronically
                // failing event is eventually retired by the expiry sweep.
                log.error("Event wake failed: agentId={}", agent.getId(), e);
                // Four-arg overload: an error row written inside a wake-up records WHY
                // the agent was awake, exactly like the action rows around it. The
                // three-arg call left wake_reason NULL on precisely the rows an
                // operator reads first.
                agentActionExecutor.logAgentError(agent, safeMessage(e), 0,
                        WakeLogContext.of(WakeReason.EVENT, attempted));
            }
        }
        return woken;
    }

    private boolean wakeForEvents(Agent agent, List<AgentWakeEvent> attempted) {
        // This agent's own "now": the batch may have been running for minutes.
        LocalDateTime now = clock.get();

        List<AgentWakeEvent> events = agentWakeEventMapper.findPendingByAgent(
                agent.getId(), Math.max(maxEventsPerWake, 1));
        if (events == null || events.isEmpty()) {
            return false;
        }
        // Best information available until the consume step narrows it down.
        attempted.addAll(events);

        if (!claimWakeSlot(agent, now)) {
            // Debounced or out of budget. The events stay PENDING on purpose: an
            // interaction is the most valuable reason to wake up, so it waits for the
            // next window (or tomorrow's budget) instead of being thrown away.
            logClaimRefusal(agent, now, "event wake", events.size() + " pending interaction(s)");
            return false;
        }

        // Read straight after the claim, while the counter still says "1": see
        // isFirstWakeToday.
        boolean firstToday = isFirstWakeToday(agent, now);

        // Consume the events BEFORE the model call, not after.
        //
        // The other order looks safer but is not: a crash between a committed action and
        // the status update leaves the events PENDING, so the next tick replays them,
        // calls the model again and charges the owner twice for one conversation. Marking
        // first means a crash costs at most one missed reply - and a reply nobody sees is
        // cheaper than tokens nobody authorised.
        List<AgentWakeEvent> consumed;
        try {
            consumed = consumeEvents(events, now);
        } catch (Exception e) {
            // The slot is already claimed. Give it back, or the agent pays for a wake-up
            // that never happened while its interactions sit PENDING until they expire.
            log.error("Could not consume wake events, releasing the claimed slot: agentId={}",
                    agent.getId(), e);
            releaseWakeSlot(agent, now);
            return false;
        }

        if (consumed.isEmpty()) {
            // Another tick took them all first; nothing to answer, so hand the slot back.
            log.info("Another tick consumed these events first: agentId={}", agent.getId());
            releaseWakeSlot(agent, now);
            return false;
        }

        // Narrow the attribution to what this tick actually won, matching the context
        // AgentWakeProcessor builds from the same list.
        attempted.clear();
        attempted.addAll(consumed);

        // Only the events actually consumed here are answered, and only their posts get the
        // duplicate-reply exemption: a partially consumed batch must not let this agent
        // reply under a post whose interaction somebody else is handling.
        WakeOutcome outcome = agentWakeProcessor.wake(agent, WakeReason.EVENT, consumed, firstToday);

        if (outcome == WakeOutcome.SKIPPED) {
            // A platform agent turned away at the door (no points, a cap, the feature
            // switched off). Nothing was spent, so the slot goes back: the owner must not
            // lose a turn out of their daily budget for a wake-up that never happened.
            //
            // The events stay consumed, deliberately. Re-opening them would have the next
            // tick offer the same interactions again, hit the same condition, and write
            // the same IGNORE row every five minutes for as long as it lasted. The cost of
            // this choice is that interactions arriving while an owner is out of points
            // go unanswered rather than queueing up - which is also what the owner is
            // being notified about.
            releaseWakeSlot(agent, now);
            return false;
        }

        log.info("Agent woken by {} interaction(s): agentId={}, offered={}",
                consumed.size(), agent.getId(), events.size());
        return true;
    }

    /**
     * Claim the events one at a time, keeping only the ones this tick really won.
     *
     * Per event rather than one statement for the batch, because the batch result is a
     * count: with a concurrent tick holding some of them, "3 of 5 updated" does not say
     * WHICH three, and answering an interaction another instance is already answering is
     * exactly the double-charge this ordering exists to prevent. The batch is capped at
     * max-events-per-wake, so this is a handful of statements at most.
     */
    private List<AgentWakeEvent> consumeEvents(List<AgentWakeEvent> events, LocalDateTime now) {
        List<AgentWakeEvent> consumed = new ArrayList<>(events.size());
        for (AgentWakeEvent event : events) {
            if (agentWakeEventMapper.markProcessed(List.of(event.getId()), now) > 0) {
                consumed.add(event);
            }
        }
        return consumed;
    }

    /**
     * Explain a refused claim.
     *
     * The two reasons need different volumes. Debounce is routine - the tick runs every
     * five minutes against a fifteen-minute floor, so most refusals are that and logging
     * them at INFO would be noise. A spent daily budget is the opposite: it is the reason an
     * owner's agent went quiet for the rest of the day, and at INFO (the production level)
     * it was previously invisible, so nobody could tell which agent had burned its budget.
     *
     * The extra read only happens on the refusal path.
     */
    private void logClaimRefusal(Agent agent, LocalDateTime now, String kind, String detail) {
        AgentWakeSettings settings = null;
        try {
            settings = agentMapper.findWakeSettings(agent.getId());
        } catch (Exception e) {
            log.debug("Could not read wake settings while explaining a refused claim: agentId={}",
                    agent.getId());
        }
        if (settings != null) {
            int used = settings.wakeCountFor(now.toLocalDate());
            int budget = settings.getDailyWakeBudget() != null
                    ? settings.getDailyWakeBudget()
                    : defaultDailyWakeBudget;
            if (used >= budget) {
                log.info("Daily wake budget exhausted, {} deferred: agentId={}, used={}/{}, {}",
                        kind, agent.getId(), used, budget, detail);
                return;
            }
        }
        log.debug("{} deferred by the debounce interval: agentId={}, {}", kind, agent.getId(), detail);
    }

    /**
     * Hand back a claimed slot. Failure here only costs the agent one wake-up out of its
     * daily budget, so it is logged rather than retried: the direction that matters is
     * "rather one wake fewer than one charged for nothing".
     */
    private void releaseWakeSlot(Agent agent, LocalDateTime now) {
        try {
            agentMapper.releaseWakeSlot(agent.getId(), now.toLocalDate());
        } catch (Exception e) {
            log.warn("Could not release the wake slot for agent {}: {}", agent.getId(), e.getMessage());
        }
    }

    // ========== Rhythm wakes ==========

    private int runRhythmWakes(LocalDateTime tickNow) {
        List<Agent> candidates;
        try {
            candidates = agentMapper.findRhythmWakeCandidates(tickNow, Math.max(rhythmBatchSize, 1));
        } catch (Exception e) {
            log.error("Could not select rhythm wake candidates", e);
            return 0;
        }
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int woken = 0;
        for (Agent agent : candidates) {
            try {
                if (wakeForRhythm(agent)) {
                    woken++;
                }
            } catch (Exception e) {
                log.error("Rhythm wake failed: agentId={}", agent.getId(), e);
                // A rhythm wake answers no interactions, so the context carries the
                // reason and no event types - the same pair the action rows of a
                // successful rhythm wake carry.
                agentActionExecutor.logAgentError(agent, safeMessage(e), 0,
                        WakeLogContext.of(WakeReason.RHYTHM, List.of()));
            }
        }
        return woken;
    }

    private boolean wakeForRhythm(Agent agent) {
        // This agent's own "now", for the same reason as in wakeForEvents: the active-hours
        // check and the day boundary must not be judged against a stale timestamp.
        LocalDateTime now = clock.get();

        // Never scheduled (every stored agent, the moment queue mode is switched on): give it
        // a slot spread across the coming day instead of waking it now. NULL sorts first in
        // the candidate query, so waking these immediately would mean the whole existing
        // population calling the model within the first few ticks - a self-inflicted
        // thundering herd, charged to the owners.
        if (agent.getNextWakeAt() == null) {
            LocalDateTime spread = wakeScheduleCalculator.spreadInitialWake(now,
                    agent.getWakeHoursStart(), agent.getWakeHoursEnd());
            agentMapper.updateNextWakeAt(agent.getId(), spread);
            log.info("Agent had no wake schedule yet, spread to {}: agentId={}", spread, agent.getId());
            return false;
        }

        if (!wakeScheduleCalculator.isWithinActiveHours(now,
                agent.getWakeHoursStart(), agent.getWakeHoursEnd())) {
            // Outside its hours: push the schedule to the next window instead of leaving
            // next_wake_at in the past, or this agent would be re-examined every tick.
            LocalDateTime next = wakeScheduleCalculator.nextRhythmWake(now,
                    agent.getWakeHoursStart(), agent.getWakeHoursEnd(), targetDailyRhythmWakes);
            agentMapper.updateNextWakeAt(agent.getId(), next);
            log.debug("Rhythm wake outside active hours, rescheduled: agentId={}, next={}",
                    agent.getId(), next);
            return false;
        }

        if (!claimWakeSlot(agent, now)) {
            // Out of budget or too soon. Move the schedule forward anyway, so the agent
            // is not re-read on every tick for the rest of the day.
            LocalDateTime next = wakeScheduleCalculator.nextRhythmWake(now,
                    agent.getWakeHoursStart(), agent.getWakeHoursEnd(), targetDailyRhythmWakes);
            agentMapper.updateNextWakeAt(agent.getId(), next);
            logClaimRefusal(agent, now, "rhythm wake", "next=" + next);
            return false;
        }

        boolean firstToday = isFirstWakeToday(agent, now);

        // Compute the next slot BEFORE the (slow) model call, so a crash mid-wake cannot
        // leave next_wake_at in the past and re-trigger this agent on every tick.
        LocalDateTime next = wakeScheduleCalculator.nextRhythmWake(now,
                agent.getWakeHoursStart(), agent.getWakeHoursEnd(), targetDailyRhythmWakes);
        agentMapper.updateNextWakeAt(agent.getId(), next);

        WakeOutcome outcome = agentWakeProcessor.wake(agent, WakeReason.RHYTHM, List.of(), firstToday);

        if (outcome == WakeOutcome.SKIPPED) {
            // Same compensation as the event path. next_wake_at has already been moved
            // forward, which is what keeps a skipped platform agent from being re-examined
            // on every tick for the rest of the day.
            releaseWakeSlot(agent, now);
            return false;
        }

        log.info("Agent woken by its rhythm: agentId={}, nextWakeAt={}", agent.getId(), next);
        return true;
    }

    /**
     * Atomic budget + debounce claim. A false return means "not now", and the caller must
     * not call the model.
     */
    private boolean claimWakeSlot(Agent agent, LocalDateTime now) {
        LocalDateTime debounceCutoff = now.minusMinutes(Math.max(minWakeIntervalMinutes, 0));
        // One clock for the stamp, the debounce comparison and the day boundary: see
        // AgentMapper#claimWakeSlot for why NOW()/CURDATE() are not used there.
        int claimed = agentMapper.claimWakeSlot(agent.getId(), now, now.toLocalDate(),
                debounceCutoff, Math.max(defaultDailyWakeBudget, 1));
        return claimed > 0;
    }

    /**
     * Whether the slot just claimed is this agent's first of the day.
     *
     * Read AFTER the claim and from the database, not from the Agent object: the counter
     * is incremented inside {@link AgentMapper#claimWakeSlot}, and the object in hand was
     * selected before that statement ran - it carries the pre-increment value, and on a
     * day boundary a stale one (the counter resets lazily, so yesterday's number is still
     * in the row until the claim rewrites it). Post-claim, "first today" is exactly
     * "the counter now reads 1".
     *
     * One extra small SELECT per wake-up that actually happens, not per tick. A failure
     * answers "not the first", which only means the world block is skipped for this
     * wake-up.
     */
    private boolean isFirstWakeToday(Agent agent, LocalDateTime now) {
        try {
            AgentWakeSettings settings = agentMapper.findWakeSettings(agent.getId());
            return settings != null && settings.wakeCountFor(now.toLocalDate()) == 1;
        } catch (Exception e) {
            log.debug("Could not tell whether this is the first wake of the day: agentId={}, {}",
                    agent.getId(), e.getMessage());
            return false;
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
