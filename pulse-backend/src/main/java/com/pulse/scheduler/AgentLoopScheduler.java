package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.config.WakeMode;
import com.pulse.entity.Agent;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent Loop Scheduler - the legacy heartbeat.
 *
 * Wakes a batch of agents every 12 hours, in round-robin order. This is the behaviour
 * the community has been running on, and it is deliberately left intact as the fallback
 * for the per-agent wake queue (see {@link AgentWakeQueueScheduler}): with
 * {@code scheduler.agent-loop.mode=legacy} - the default - nothing about the timing or
 * the selection changes.
 *
 * Exactly one of the two schedulers does work at a time; the other returns immediately
 * after checking the mode.
 *
 * The per-agent flow (context, LLM call, action, charge, memory) lives in
 * {@link AgentWakeProcessor}, shared with the queue scheduler so both modes behave
 * identically once an agent is awake.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopScheduler {

    private final AgentMapper agentMapper;
    private final AgentActionExecutor agentActionExecutor;
    private final AgentWakeProcessor agentWakeProcessor;
    private final SchemaCapabilities schemaCapabilities;

    @Value("${scheduler.agent-loop.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${scheduler.agent-loop.batch-size:10}")
    private int batchSize;

    @Value("${scheduler.agent-loop.mode:legacy}")
    private String mode;

    /**
     * Execute the agent loop on the configured interval (12h by default).
     *
     * fixedDelayString, not fixedRateString: with a real thread pool a slow cycle
     * would otherwise overlap the next one and wake the same agents twice.
     * ShedLock keeps a second instance from burning the user's tokens in parallel.
     */
    @Scheduled(fixedDelayString = "${scheduler.agent-loop.interval:43200000}")
    @SchedulerLock(name = "agentLoopCycle", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void executeAgentLoop() {
        if (!schedulerEnabled) {
            log.debug("Agent loop scheduler is disabled");
            return;
        }
        if (WakeMode.resolve(mode, schemaCapabilities.isWakeQueueSchema()) != WakeMode.LEGACY) {
            log.debug("Agent loop is in queue mode; the legacy batch stays idle");
            return;
        }

        log.info("=== Agent Loop Cycle Started ===");

        // Step 1: Fetch the agents whose turn it is (round-robin when the schema
        // supports it, random otherwise - see SchemaCapabilities)
        List<Agent> activeAgents = schemaCapabilities.isLastDispatchedAtColumn()
                ? agentMapper.findRandomActiveAgents(batchSize)
                : agentMapper.findRandomActiveAgentsLegacy(batchSize);

        log.info("Fetched {} active agents for processing", activeAgents.size());

        for (Agent agent : activeAgents) {
            try {
                agentWakeProcessor.wake(agent, WakeReason.LEGACY_BATCH, List.of());
            } catch (Exception e) {
                log.error("Agent processing failed: agentId={}", agent.getId(), e);
                // Log error but continue processing other agents
                agentActionExecutor.logAgentError(agent, safeMessage(e), 0);
            }
        }

        log.info("=== Agent Loop Cycle Completed ===");
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
