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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Distils each active agent's recent behaviour into persona traits, once a day.
 *
 * This is the other half of the hybrid write strategy: {@code AgentActionExecutor}
 * writes PERSONA_FACT cards for free (pure code, no model), and this job spends one
 * LLM call per active agent per day to turn those facts into PERSONA_TRAIT cards -
 * stances, style, recurring topics - which is what actually gives an agent a
 * recognizable personality across cycles.
 *
 * Cost control is structural rather than best-effort:
 * - one call per agent per day: agents whose reflection already settled today (success
 *   or an explicitly free run) are skipped, so a manual re-trigger costs nothing and
 *   creates no duplicates;
 * - only agents that did something real, ignoring this job's own audit rows;
 * - the per-run ceiling counts reflection calls, not candidates examined, so skipped
 *   agents never consume another agent's budget;
 * - agents out of tokens are skipped before the call, not after;
 * - PLATFORM agents pass the same spending gate as a wake-up before the call, and are
 *   charged their owner's points after it: the nightly pass spends the platform's money
 *   just as a wake-up does, so it cannot be the one path that bypasses the caps;
 * - an empty behaviour pack skips the HTTP call entirely;
 * - the behaviour pack is bounded by MemoryProperties, so a busy agent does not
 *   produce a bigger prompt than a quiet one.
 *
 * WHO GOES FIRST is a cost decision too. On a database with
 * agents.last_reflection_attempt_at the candidates are ordered by how long they have
 * waited for a turn, so the per-run ceiling truncates the queue at the agents served most
 * recently instead of at the same high-id agents every night. Without the column the run
 * keeps its original id-ordered cursor - correct, just unfair the moment the cap bites.
 *
 * The LLM call is deliberately outside any transaction (same rule as the agent loop):
 * holding a pooled connection across a slow gateway call exhausts the pool. Persisting
 * the answer and charging for it happen together afterwards, in the single transaction
 * owned by {@link ReflectionPersistExecutor}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryReflectionScheduler {

    private final AgentMapper agentMapper;
    private final AgentLogMapper agentLogMapper;
    private final AgentMemoryService agentMemoryService;
    private final LLMClient llmClient;
    private final AgentActionExecutor agentActionExecutor;
    private final ReflectionPersistExecutor reflectionPersistExecutor;
    private final SchemaCapabilities schemaCapabilities;
    private final PlatformUsageService platformUsageService;

    /**
     * Names this call site in the owner's ledger, so a nightly reflection is
     * distinguishable from a wake-up in a list of otherwise identical LLM_USAGE rows.
     */
    private static final String REFLECTION_USAGE_LABEL = "反思";

    /**
     * Safety stop on the page walk.
     *
     * The keyset advances strictly, so the loop terminates on its own; this only bounds
     * the damage if a future edit breaks that property, which is exactly the kind of bug
     * that turns a nightly job into an infinite one.
     */
    private static final int MAX_PAGES_PER_RUN = 10_000;

    /**
     * What one candidate cost.
     *
     * The distinction that matters is the per-run ceiling: only ATTEMPTED consumed it.
     * The cursor is not part of this decision - every candidate the run examined is
     * stamped, so that being refused night after night cannot keep an agent at the head
     * of the queue.
     */
    private enum Outcome {
        /** A call was made - success, failure, or a throw that may have reached the model. */
        ATTEMPTED,
        /** No call, but the agent had its turn: there was nothing to distil. */
        EMPTY,
        /**
         * No call: a PLATFORM agent the spending gate turned away. It consumed none of
         * the per-run ceiling. Kept separate from EMPTY only so the reason is legible
         * here and in the logs.
         */
        BLOCKED,
        /** No call and nothing decided: out of tokens, or already settled today. */
        SKIPPED
    }

    /**
     * Off by default. This scheduler spends the agent owner's real tokens once a day
     * per active agent, so it has to be switched on deliberately after the cost has
     * been verified in a deployment - the same philosophy as shipping the wake-up
     * rework in legacy mode first.
     */
    @Value("${scheduler.memory-reflection.enabled:false}")
    private boolean enabled;

    /**
     * Page size of the candidate cursor, NOT a total limit: the run walks every
     * candidate page by page.
     */
    @Value("${scheduler.memory-reflection.batch-size:50}")
    private int batchSize;

    /**
     * Hard ceiling on reflection CALLS per run, as a runaway-cost brake. Agents skipped
     * for idempotence, exhaustion or an empty behaviour pack cost nothing and therefore
     * do not count against it. Hitting the ceiling is logged with the number of
     * candidates left unexamined; 0 means no ceiling.
     */
    @Value("${scheduler.memory-reflection.max-agents-per-run:500}")
    private int maxAgentsPerRun;

    /**
     * Hours of activity to reflect on. Slightly more than a day so an agent that acted
     * just before the previous run is never skipped twice.
     */
    @Value("${scheduler.memory-reflection.window-hours:26}")
    private int windowHours;

    /**
     * Floor charged when the gateway reports no usage. Mirrors the agent loop: a cycle
     * that reached the model is never free, or token_threshold stops being a limit.
     */
    @Value("${scheduler.memory-reflection.min-token-charge:200}")
    private long minTokenCharge;

    /**
     * 03:40 daily - low traffic, and clear of the 04:20 counter reconciliation.
     */
    @Scheduled(cron = "${scheduler.memory-reflection.cron:0 40 3 * * *}")
    @SchedulerLock(name = "memoryReflectionCycle", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void reflectOnRecentBehavior() {
        if (!enabled) {
            log.debug("Memory reflection scheduler is disabled");
            return;
        }

        log.info("=== Memory reflection cycle started ===");
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(windowHours, 1));
        LocalDateTime today = LocalDate.now().atStartOfDay();
        // Read once and used as the "already served this run" watermark: every stamp this
        // run writes is at or after it, so a stamped agent drops out of the candidate
        // query for the rest of the run.
        LocalDateTime runStartedAt = LocalDateTime.now();

        boolean orderByCursor = schemaCapabilities.isReflectionCursorColumn();

        // visited = candidates examined; attempted = candidates that actually cost an
        // HTTP call. Only the latter consumes the run's quota: counting skips against it
        // meant a same-day re-run had its whole budget eaten by agents it then skipped
        // for idempotence, so the agents that still needed reflecting never got a turn.
        int visited = 0;
        int attempted = 0;
        int skipped = 0;
        int pageSize = Math.max(batchSize, 1);
        // Composite keyset when ordering by the attempt cursor, plain id otherwise.
        boolean hasCursor = false;
        LocalDateTime cursorAttemptAt = null;
        long cursorId = 0L;
        boolean capReached = false;
        int pages = 0;

        // Keyset pagination over every candidate, not a top-N: ordering by id with a
        // fixed LIMIT meant agents past the first page were never reflected on at all.
        while (pages < MAX_PAGES_PER_RUN) {
            List<Agent> page;
            try {
                page = orderByCursor
                        ? agentMapper.findAliveAgentsActiveSinceByReflectionCursor(since,
                                hasCursor, cursorAttemptAt, cursorId, runStartedAt, pageSize)
                        : agentMapper.findAliveAgentsActiveSince(since, cursorId, pageSize);
            } catch (Exception e) {
                log.error("Memory reflection could not select agents (cursor={}/{})",
                        cursorAttemptAt, cursorId, e);
                return;
            }
            if (page == null || page.isEmpty()) {
                break;
            }
            pages++;

            for (Agent agent : page) {
                // The cursor carries the values as they were READ, never the stamp written
                // below: the keyset is over the pre-update ordering, and mixing the two
                // would make the next page start in the wrong place.
                if (orderByCursor) {
                    cursorAttemptAt = agent.getLastReflectionAttemptAt();
                    cursorId = agent.getId();
                    hasCursor = true;
                } else {
                    cursorId = Math.max(cursorId, agent.getId());
                }
                if (maxAgentsPerRun > 0 && attempted >= maxAgentsPerRun) {
                    capReached = true;
                    break;
                }
                visited++;
                Outcome outcome;
                try {
                    outcome = reflectOne(agent, since, today);
                } catch (Exception e) {
                    // One agent's failure must not end the batch, and there is nothing
                    // to repair: the next run reflects on the same window. Counted as an
                    // attempt because the throw may have come from the call itself - the
                    // quota is a cost ceiling and must err upwards.
                    log.error("Memory reflection failed: agentId={}", agent.getId(), e);
                    outcome = Outcome.ATTEMPTED;
                }
                if (outcome == Outcome.ATTEMPTED) {
                    attempted++;
                } else {
                    skipped++;
                }
                // Stamped for every candidate examined, whatever it cost: a successful
                // distillation, a failed one, an empty behaviour pack, and equally an
                // agent turned away for exhaustion or for having settled today.
                //
                // The stamp records an ATTEMPT, not a reflection. Leaving the refused
                // ones unstamped was the older rule and it starved the queue: an agent
                // that has never reflected sorts first for ever
                // (last_reflection_attempt_at IS NULL leads the ordering), so a
                // permanently exhausted one was re-read and re-refused at the head of
                // every night's first page while the agents behind it never came into
                // view. Moving its cursor costs nothing - it is examined again tomorrow,
                // just behind everybody who has waited longer.
                //
                // What does NOT change is the per-run ceiling: it counts reflection
                // calls, so a stamped skip still consumes none of another agent's budget.
                markReflectionAttempt(agent, orderByCursor);
            }

            if (capReached || page.size() < pageSize) {
                break;
            }
        }

        if (pages >= MAX_PAGES_PER_RUN) {
            log.error("Memory reflection stopped at its page ceiling of {} - the candidate "
                    + "cursor is not advancing", MAX_PAGES_PER_RUN);
        }

        if (capReached) {
            // Never truncate silently: say how many agents did not get a turn tonight.
            int total = safeCandidateCount(since);
            log.warn("Memory reflection hit its per-run cap of {} reflection calls: "
                            + "attempted={}, visited={}, candidates={}, not examined this run={}",
                    maxAgentsPerRun, attempted, visited, total, Math.max(total - visited, 0));
        }

        log.info("=== Memory reflection cycle completed: visited={}, attempted={}, skipped={} ===",
                visited, attempted, skipped);
    }

    private int safeCandidateCount(LocalDateTime since) {
        try {
            return agentMapper.countAliveAgentsActiveSince(since);
        } catch (Exception e) {
            log.warn("Could not count reflection candidates: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Record that this run examined this agent, so the next run starts behind it.
     *
     * Best effort by design: the stamp is an ordering hint, and failing to write it costs
     * at most one unfair turn. It must never be able to abort a run that has already
     * spent the owner's tokens.
     */
    private void markReflectionAttempt(Agent agent, boolean columnPresent) {
        if (!columnPresent) {
            return;
        }
        try {
            agentMapper.markReflectionAttempt(agent.getId(), LocalDateTime.now());
        } catch (Exception e) {
            log.warn("Could not record the reflection attempt cursor: agentId={}, error={}",
                    agent.getId(), e.getMessage());
        }
    }

    /**
     * @return what this candidate cost, see {@link Outcome}
     */
    private Outcome reflectOne(Agent agent, LocalDateTime since, LocalDateTime today) {
        // Re-check capacity: used_tokens moved while the batch was being processed, and
        // spending a dead agent's budget on self-reflection would be indefensible.
        if (agent.isTokenExhausted()) {
            log.info("Skipping reflection for token-exhausted agent: agentId={}", agent.getId());
            return Outcome.SKIPPED;
        }

        // Idempotence for manual re-triggers: one settled reflection per agent per day.
        // SKIPPED counts as settled (repeating a run the gateway already answered without
        // a model call only buys another round trip); FAILED does not, so a broken
        // gateway or a rolled-back write can be retried.
        if (agentLogMapper.countCompletedReflectionsSince(agent.getId(), today) > 0) {
            log.info("Skipping reflection, already settled today: agentId={}", agent.getId());
            return Outcome.SKIPPED;
        }

        // The same gate a wake-up passes, for the same reason: this call is paid for with
        // the owner's points and counted against the platform's daily allowance, so the
        // nightly pass must not be the one path that spends outside the caps. BYOK agents
        // get null here and fall straight through, unchanged.
        //
        // Nothing is written but a log line: no tokens, no points, no agent_logs row -
        // in particular no REFLECTION row, which would make the agent look settled for
        // the day and cost it the retry it is owed once points are topped up. The owner
        // is not notified either; the wake path owns that message, and reflection runs at
        // 03:40, so notifying here would spend the one-per-day notice on the hour the
        // owner is least likely to read it.
        PlatformUsageService.SkipReason skipReason = platformUsageService.checkReadiness(agent);
        if (skipReason != null) {
            log.info("Skipping reflection for a platform agent: agentId={}, reason={} - {}",
                    agent.getId(), skipReason.name(), skipReason.getText());
            return Outcome.BLOCKED;
        }

        ReflectionContext context = agentMemoryService.buildReflectionContext(agent.getId(), since);
        if (!context.hasBehaviors()) {
            // No HTTP call at all: an empty behaviour pack cannot produce a trait, and
            // the gateway would only bill us for confirming that.
            log.debug("Skipping reflection, no behaviour recorded: agentId={}", agent.getId());
            // The agent HAS had its turn: there was simply nothing to distil. Its cursor
            // moves, or a permanently quiet agent would keep the front of the queue.
            return Outcome.EMPTY;
        }

        // Outside any transaction - see the class comment.
        ReflectionResult result = llmClient.callReflection(agent, context);
        long tokensCharged = resolveTokenCharge(result);

        if (!result.isSuccessful()) {
            log.warn("Reflection returned no usable result, retrying tomorrow: agentId={}, error={}",
                    agent.getId(), result.getErrorMessage());
            agentActionExecutor.chargeReflectionTokens(agent, tokensCharged,
                    AgentActionExecutor.REFLECTION_FAILED,
                    truncateNote("REFLECTION_FAILED: " + result.getErrorMessage()));
            // Same reasoning as the token charge above, applied to the owner's points: a
            // failure envelope does not prove the provider did not bill.
            chargePlatformUsage(agent, tokensCharged);
            return Outcome.ATTEMPTED;
        }

        String label = tokensCharged == 0
                ? AgentActionExecutor.REFLECTION_SKIPPED
                : AgentActionExecutor.REFLECTION_SUCCESS;
        String note = truncateNote(String.format("REFLECTION: new=%d, updated=%d, deprecated=%d",
                result.safeNewTraits().size(), result.safeUpdatedTraits().size(),
                result.safeDeprecatedTraitIds().size()));

        try {
            // Persist + charge + audit in one transaction, so the audit row can never
            // claim a distillation that did not land, and a crash cannot make the call free.
            reflectionPersistExecutor.applyAndCharge(agent, result, tokensCharged, label, note);
        } catch (Exception e) {
            // Persisting failed and rolled back - but the provider already billed, so the
            // tokens still have to be charged. Separate transaction, honest label.
            log.error("Failed to persist reflection result, charging as failed: agentId={}",
                    agent.getId(), e);
            try {
                agentActionExecutor.chargeReflectionTokens(agent, tokensCharged,
                        AgentActionExecutor.REFLECTION_FAILED,
                        truncateNote("REFLECTION_PERSIST_FAILED: " + e.getMessage()));
            } catch (Exception chargeError) {
                // Both halves failed: nothing left but to say so and retry tomorrow.
                log.error("Failed to charge reflection tokens after a persist failure: agentId={}",
                        agent.getId(), chargeError);
            }
        }
        // Outside the persist transaction on purpose, and reached on both paths above: the
        // provider billed for the call, not for the write, so the points move whether or
        // not the traits landed - and a points movement must never be able to roll back a
        // distillation that did.
        chargePlatformUsage(agent, tokensCharged);
        return Outcome.ATTEMPTED;
    }

    /**
     * Charge one reflection call to a PLATFORM agent's owner. A no-op for BYOK agents,
     * which is decided inside the service so this loop does not have to know the mode.
     *
     * The service is documented never to throw, and this catch is the belt to that
     * braces: a points failure at the very end of one agent's turn must not be able to
     * end the run for every agent behind it in the queue. The tokens are already spent by
     * the time this runs, so the only thing left to get wrong is the batch.
     */
    private void chargePlatformUsage(Agent agent, long tokensCharged) {
        try {
            platformUsageService.charge(agent, tokensCharged, null, REFLECTION_USAGE_LABEL);
        } catch (Exception e) {
            log.error("Could not charge platform reflection usage: agentId={}, tokens={}",
                    agent.getId(), tokensCharged, e);
        }
    }

    /**
     * Effective charge for one reflection call.
     *
     * Three distinct cases, and conflating the last two was a real overcharge: the
     * gateway answers {@code total_tokens: 0} when it never called the model (for
     * example everything was filtered out of the behaviour pack), and that run must be
     * free. Only an absent figure - where the model may well have run and billed - gets
     * the floor.
     */
    private long resolveTokenCharge(ReflectionResult result) {
        Integer reported = result.getTotalTokens();
        if (reported != null && reported > 0) {
            return reported.longValue();
        }
        if (reported != null && result.isSuccessful()) {
            log.info("Reflection reported an explicitly free run (0 tokens); charging nothing");
            return 0L;
        }
        log.warn("Reflection reported no token usage; charging the configured floor of {}", minTokenCharge);
        return minTokenCharge;
    }

    /**
     * agent_logs.action_content is VARCHAR(500).
     */
    private String truncateNote(String note) {
        if (note == null) {
            return null;
        }
        return note.length() <= 500 ? note : note.substring(0, 500);
    }
}
