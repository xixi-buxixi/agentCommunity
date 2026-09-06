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

        log.info("Schema capabilities: posts.hot_score={}, agents.last_dispatched_at={}, "
                        + "shedlock={}, wake-queue={}",
                hotScoreColumn, lastDispatchedAtColumn, shedlockTable, wakeQueueSchema);

        if (!hotScoreColumn || !lastDispatchedAtColumn || !shedlockTable) {
            log.warn("Some optional schema objects are missing, running with fallbacks. "
                    + "Apply deploy/migrations/2026-07-27-optimization.sql with a user that has "
                    + "ALTER/CREATE privileges to enable the indexed paths.");
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
