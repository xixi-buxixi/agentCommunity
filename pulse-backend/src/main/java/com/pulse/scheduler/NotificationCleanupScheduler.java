package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Retention for the notification centre: delete read notifications once they are old
 * enough that nobody will go back for them.
 *
 * The table has no other bound. It grows with community activity - every comment, tip,
 * bounty and death writes a row - and the 2026-09-06 migration shipped with the policy
 * written down but not implemented. This is that policy.
 *
 * Two things it deliberately does NOT do:
 * - touch unread rows, at any age. An unread notification is the one case where the user
 *   has demonstrably not seen it yet, and deleting it destroys the only copy of something
 *   they were meant to be told.
 * - delete in one statement. A single unbounded DELETE on a table this size holds row
 *   locks and a transaction for as long as it takes; the loop below deletes in bounded
 *   batches, each its own statement, so a long purge never blocks a write.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCleanupScheduler {

    /**
     * Safety stop for the batch loop.
     *
     * A run that has deleted this many batches has removed hundreds of thousands of rows
     * and is far more likely to be a mistake (a clock jump, a misconfigured retention of
     * 0 days) than a real backlog. It stops and says so; the next night continues.
     */
    private static final int MAX_BATCHES_PER_RUN = 200;

    private final NotificationMapper notificationMapper;
    private final SchemaCapabilities schemaCapabilities;

    /**
     * On by default, unlike the schedulers that spend tokens: this one only deletes rows
     * the retention policy already declared expendable, and leaving it off is how the
     * table grew unbounded in the first place.
     */
    @Value("${notifications.cleanup.enabled:true}")
    private boolean enabled;

    /**
     * Age at which a READ notification is deleted, in days. 90 by default, matching the
     * policy written into deploy/migrations/2026-09-06-notifications.sql. A value of 0 or
     * less is treated as a misconfiguration and the run is skipped rather than deleting
     * everything ever read.
     */
    @Value("${notifications.retention-days:90}")
    private int retentionDays;

    /**
     * Rows per DELETE. Bounds how long one statement holds locks; the run repeats until a
     * batch deletes nothing.
     */
    @Value("${notifications.cleanup.batch-size:1000}")
    private int batchSize;

    /**
     * 04:00 daily - after the 03:40 reflection pass and before the 04:20 counter
     * reconciliation, so the three nightly jobs do not contend for the same connections.
     */
    @Scheduled(cron = "${notifications.cleanup.cron:0 0 4 * * *}")
    @SchedulerLock(name = "notificationCleanup", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeOldReadNotifications() {
        if (!enabled) {
            log.debug("Notification cleanup is disabled");
            return;
        }
        if (!schemaCapabilities.isNotificationsTable()) {
            log.debug("notifications table is absent; there is nothing to clean up");
            return;
        }
        if (retentionDays <= 0) {
            // Refusing rather than obeying: "keep nothing" is never what an operator means
            // by a retention setting, and the mistake is unrecoverable.
            log.warn("notifications.retention-days is {}, which would delete every read "
                    + "notification; skipping the cleanup run", retentionDays);
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int limit = Math.max(batchSize, 1);
        int deleted = 0;
        int batches = 0;

        while (batches < MAX_BATCHES_PER_RUN) {
            int removed;
            try {
                removed = notificationMapper.deleteReadOlderThan(cutoff, limit);
            } catch (Exception e) {
                // Whatever has already been deleted is committed and correct; the rest
                // waits for tomorrow. Retention is not urgent enough to retry into a
                // database that is having a bad night.
                log.error("Notification cleanup failed after {} rows, stopping this run", deleted, e);
                return;
            }
            batches++;
            deleted += removed;
            if (removed < limit) {
                break;
            }
        }

        if (batches >= MAX_BATCHES_PER_RUN) {
            log.warn("Notification cleanup hit its per-run batch ceiling of {} ({} rows deleted); "
                    + "the remaining backlog is left for the next run", MAX_BATCHES_PER_RUN, deleted);
        }
        if (deleted > 0) {
            log.info("Notification cleanup deleted {} read notifications older than {} ({} batches)",
                    deleted, cutoff, batches);
        } else {
            log.debug("Notification cleanup found nothing older than {}", cutoff);
        }
    }
}
