package com.pulse.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Detects which optional schema objects exist, so the application degrades instead
 * of failing when a migration could not be applied.
 *
 * The deploy pipeline applies schema.sql with the application's own database user,
 * which may not hold DDL privileges (and cannot be granted them from CI). Without
 * this check, a migration that silently did not run turned into runtime errors:
 * queries referencing posts.hot_score or agents.last_dispatched_at fail outright,
 * and ShedLock throws on a missing shedlock table - taking down the schedulers.
 *
 * Each capability therefore has a documented fallback:
 * - hot_score missing        -> ranking sorts by the raw expression (slower, correct)
 * - last_dispatched_at missing-> agent selection falls back to ORDER BY RAND()
 * - shedlock missing         -> scheduler locking is disabled (safe on one instance)
 *
 * The exact statements to run manually are in deploy/migrations/.
 */
@Slf4j
@Getter
@Component
@RequiredArgsConstructor
public class SchemaCapabilities {

    private final JdbcTemplate jdbcTemplate;

    private boolean hotScoreColumn;
    private boolean lastDispatchedAtColumn;
    private boolean shedlockTable;

    /**
     * Whether the per-agent wake queue can run at all: the agents rhythm columns AND the
     * event table. Without it the scheduler stays on the legacy global batch, which works
     * on any schema version.
     */
    private boolean wakeQueueSchema;

    /**
     * Whether agent_logs can record WHY the agent was awake: both wake_reason and
     * wake_event_types must exist, because the explicit INSERT names both.
     *
     * Deliberately independent of {@link #wakeQueueSchema}: the two migrations are
     * separate files and either can be applied without the other, so coupling them
     * would either lose the wake reason on a database that has the columns, or write
     * a column that is not there.
     *
     * Without it every agent_logs write stays on the generated INSERT and the reason
     * is simply not recorded - see deploy/migrations/2026-09-06-agent-log-wake-context.sql.
     */
    private boolean agentLogWakeColumns;

    /**
     * Whether the notification centre has a table to write to.
     *
     * The two halves degrade differently on purpose: producers drop the notification
     * with a warning (a comment or a tip must never fail over one), while the read
     * endpoints report NOTIFICATIONS_UNAVAILABLE instead of an empty page - see D-0008
     * for why a user-facing inbox must not silently look empty.
     *
     * Apply deploy/migrations/2026-09-06-notifications.sql to enable it.
     */
    private boolean notificationsTable;

    @PostConstruct
    public void detect() {
        hotScoreColumn = columnExists("posts", "hot_score");
        lastDispatchedAtColumn = columnExists("agents", "last_dispatched_at");
        shedlockTable = tableExists("shedlock");
        // last_dispatched_at is part of the contract too: claimWakeSlot stamps it, so a
        // database with the rhythm columns but without that one would fail on every wake.
        wakeQueueSchema = lastDispatchedAtColumn
                && columnExists("agents", "next_wake_at")
                && columnExists("agents", "wake_hours_start")
                && columnExists("agents", "wake_hours_end")
                && columnExists("agents", "daily_wake_budget")
                && columnExists("agents", "wake_count_today")
                && columnExists("agents", "wake_count_date")
                && tableExists("agent_wake_events");
        agentLogWakeColumns = columnExists("agent_logs", "wake_reason")
                && columnExists("agent_logs", "wake_event_types");
        notificationsTable = tableExists("notifications");

        log.info("Schema capabilities: posts.hot_score={}, agents.last_dispatched_at={}, "
                        + "shedlock={}, wake-queue={}, agent-log-wake-columns={}, notifications={}",
                hotScoreColumn, lastDispatchedAtColumn, shedlockTable, wakeQueueSchema,
                agentLogWakeColumns, notificationsTable);

        if (!hotScoreColumn || !lastDispatchedAtColumn || !shedlockTable) {
            log.warn("Some optional schema objects are missing, running with fallbacks. "
                    + "Apply deploy/migrations/2026-07-27-optimization.sql with a user that has "
                    + "ALTER/CREATE privileges to enable the indexed paths.");
        }
        if (!agentLogWakeColumns) {
            log.warn("agent_logs has no wake_reason / wake_event_types columns; activity log rows "
                    + "are written without the wake reason. Apply "
                    + "deploy/migrations/2026-09-06-agent-log-wake-context.sql to enable it.");
        }
        if (!notificationsTable) {
            log.warn("notifications table is absent; every notification is dropped with a warning "
                    + "and the notification endpoints report NOTIFICATIONS_UNAVAILABLE. Apply "
                    + "deploy/migrations/2026-09-06-notifications.sql to enable it.");
        }
        if (!wakeQueueSchema) {
            log.warn("Wake-queue schema is incomplete (agents rhythm columns / agent_wake_events); "
                    + "queue mode is unavailable and the agent loop will stay in legacy mode.");
        }
    }

    private boolean columnExists(String table, String column) {
        return count("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                table, column);
    }

    private boolean tableExists(String table) {
        return count("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?", table);
    }

    private boolean count(String sql, Object... args) {
        try {
            Integer found = jdbcTemplate.queryForObject(sql, Integer.class, args);
            return found != null && found > 0;
        } catch (Exception e) {
            // Never let detection itself break startup; assume "missing" and use the
            // fallback path, which works on any schema version.
            log.warn("Schema capability probe failed, assuming the object is absent: {}", e.getMessage());
            return false;
        }
    }
}
