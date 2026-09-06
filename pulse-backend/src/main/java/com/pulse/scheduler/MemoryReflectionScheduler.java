package com.pulse.scheduler;

import com.pulse.client.LLMClient;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.service.AgentMemoryService;
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
 * - an empty behaviour pack skips the HTTP call entirely;
 * - the behaviour pack is bounded by MemoryProperties, so a busy agent does not
 *   produce a bigger prompt than a quiet one.
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

        // visited = candidates examined; attempted = candidates that actually cost an
        // HTTP call. Only the latter consumes the run's quota: counting skips against it
        // meant a same-day re-run had its whole budget eaten by agents it then skipped
        // for idempotence, so the agents that still needed reflecting never got a turn.
        int visited = 0;
        int attempted = 0;
        int skipped = 0;
        long cursor = 0L;
        boolean capReached = false;

        // Keyset pagination over every candidate, not a top-N: ordering by id with a
        // fixed LIMIT meant agents past the first page were never reflected on at all.
        while (true) {
            List<Agent> page;
            try {
                page = agentMapper.findAliveAgentsActiveSince(since, cursor, Math.max(batchSize, 1));
            } catch (Exception e) {
                log.error("Memory reflection could not select agents (cursor={})", cursor, e);
                return;
            }
            if (page == null || page.isEmpty()) {
                break;
            }

            for (Agent agent : page) {
                cursor = Math.max(cursor, agent.getId());
                if (maxAgentsPerRun > 0 && attempted >= maxAgentsPerRun) {
                    capReached = true;
                    break;
                }
                visited++;
                try {
                    if (reflectOne(agent, since, today)) {
                        attempted++;
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    // One agent's failure must not end the batch, and there is nothing
                    // to repair: the next run reflects on the same window. Counted as an
                    // attempt because the throw may have come from the call itself - the
                    // quota is a cost ceiling and must err upwards.
                    log.error("Memory reflection failed: agentId={}", agent.getId(), e);
                    attempted++;
                }
            }

            if (capReached || page.size() < Math.max(batchSize, 1)) {
                break;
            }
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
     * @return true when a reflection call was actually made
     */
    private boolean reflectOne(Agent agent, LocalDateTime since, LocalDateTime today) {
        // Re-check capacity: used_tokens moved while the batch was being processed, and
        // spending a dead agent's budget on self-reflection would be indefensible.
        if (agent.isTokenExhausted()) {
            log.info("Skipping reflection for token-exhausted agent: agentId={}", agent.getId());
            return false;
        }

        // Idempotence for manual re-triggers: one settled reflection per agent per day.
        // SKIPPED counts as settled (repeating a run the gateway already answered without
        // a model call only buys another round trip); FAILED does not, so a broken
        // gateway or a rolled-back write can be retried.
        if (agentLogMapper.countCompletedReflectionsSince(agent.getId(), today) > 0) {
            log.info("Skipping reflection, already settled today: agentId={}", agent.getId());
            return false;
        }

        ReflectionContext context = agentMemoryService.buildReflectionContext(agent.getId(), since);
        if (!context.hasBehaviors()) {
            // No HTTP call at all: an empty behaviour pack cannot produce a trait, and
            // the gateway would only bill us for confirming that.
            log.debug("Skipping reflection, no behaviour recorded: agentId={}", agent.getId());
            return false;
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
            return true;
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
        return true;
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
