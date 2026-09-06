package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.mapper.AgentMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Retention for memory cards: physically delete cards that have been retired long enough.
 *
 * The existing retention sweep (AgentMemoryServiceImpl, on the write path) only ever sets
 * status = DEPRECATED. That bounds how many cards are LIVE per agent - which is what the
 * prompt cost depends on - and does nothing at all for table size: a busy agent retires
 * cards continuously and every one of them is kept for ever. This job is the other half.
 *
 * The one rule that matters is which cards it may touch:
 * - DEPRECATED (2) only.
 * - ACTIVE (1) is what the agent currently believes. Never touched, at any age.
 * - DISABLED (0) is what the agent's OWNER decided it may not believe. Never touched
 *   either, and for a stronger reason than age: deleting a disabled card lifts the
 *   owner's brake, because nothing then stops the same fact being learned again as a
 *   fresh ACTIVE card. A disabled card is a standing instruction, not a stale row.
 *
 * A separate scheduler rather than an addition to the reflection pass on purpose: that
 * one is off by default (it spends the owner's tokens) and this one must run whether or
 * not anybody enabled reflection, or the cleanup would be gated on an unrelated switch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryPurgeScheduler {

    /** Same safety stop as the notification cleanup, and for the same reason. */
    private static final int MAX_BATCHES_PER_RUN = 200;

    private final AgentMemoryMapper agentMemoryMapper;
    private final SchemaCapabilities schemaCapabilities;

    /**
     * On by default: it only deletes cards that were already retired, and it costs no
     * tokens.
     */
    @Value("${memory.retention.purge-enabled:true}")
    private boolean enabled;

    /**
     * How long a retired card is kept before it is deleted, in days, counted from the
     * moment it was retired (updated_at). 0 or less is treated as a misconfiguration and
     * the run is skipped - "delete every retired card immediately" is never what an
     * operator means by a retention setting, and it is unrecoverable.
     */
    @Value("${memory.retention.deprecated-purge-days:30}")
    private int deprecatedPurgeDays;

    /** Rows per DELETE, so no single statement holds locks for long. */
    @Value("${memory.retention.purge-batch-size:1000}")
    private int batchSize;

    /**
     * 04:10 daily - between the 04:00 notification cleanup and the 04:20 counter
     * reconciliation, so the nightly jobs do not contend for the same connections.
     */
    @Scheduled(cron = "${memory.retention.purge-cron:0 10 4 * * *}")
    @SchedulerLock(name = "memoryPurge", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeDeprecatedMemories() {
        if (!enabled) {
            log.debug("Memory purge is disabled");
            return;
        }
        if (!schemaCapabilities.isAgentMemoriesTable()) {
            log.debug("agent_memories table is absent; there is nothing to purge");
            return;
        }
        if (deprecatedPurgeDays <= 0) {
            log.warn("memory.retention.deprecated-purge-days is {}, which would delete every "
                    + "retired memory card; skipping the purge run", deprecatedPurgeDays);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(deprecatedPurgeDays);
        int limit = Math.max(batchSize, 1);
        int deleted = 0;
        int batches = 0;

        while (batches < MAX_BATCHES_PER_RUN) {
            int removed;
            try {
                removed = agentMemoryMapper.deleteDeprecatedOlderThan(cutoff, limit);
            } catch (Exception e) {
                // What has been deleted is committed and correct; the rest waits for
                // tomorrow. Retention is never urgent enough to retry into a struggling
                // database.
                log.error("Memory purge failed after {} rows, stopping this run", deleted, e);
                return;
            }
            batches++;
            deleted += removed;
            if (removed < limit) {
                break;
            }
        }

        if (batches >= MAX_BATCHES_PER_RUN) {
            log.warn("Memory purge hit its per-run batch ceiling of {} ({} rows deleted); the "
                    + "remaining backlog is left for the next run", MAX_BATCHES_PER_RUN, deleted);
        }
        if (deleted > 0) {
            log.info("Memory purge deleted {} deprecated cards retired before {} ({} batches)",
                    deleted, cutoff, batches);
        } else {
            log.debug("Memory purge found no deprecated cards retired before {}", cutoff);
        }
    }
}
