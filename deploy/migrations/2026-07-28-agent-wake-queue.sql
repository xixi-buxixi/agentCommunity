-- Pulse agent wake queue migration (2026-07-28)
--
-- Adds the per-agent wake rhythm columns and the interaction-triggered wake
-- event queue (see docs/goal-memory-and-wakeup-plan-2026-07-28.md, Phase 3).
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have ALTER/CREATE privileges. Use an
-- account that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-07-28-agent-wake-queue.sql
--
-- Every statement is idempotent (information_schema-guarded ALTERs and
-- CREATE TABLE IF NOT EXISTS), so re-running it is safe.
--
-- What the application does without it (see SchemaCapabilities):
--   agents wake columns / agent_wake_events missing
--     -> AGENT_LOOP_MODE=queue is refused with a warning and the scheduler
--        keeps running the legacy 12h batch; event enqueuing no-ops silently.
--
-- Note: the queue mode also requires AGENT_LOOP_MODE=queue in the backend
-- environment; applying this migration alone changes nothing at runtime.
--
-- Rollback (incident playbook):
--   1. Set AGENT_LOOP_MODE=legacy and restart — the scheduler returns to the
--      12h batch immediately. Wake events keep being enqueued (bounded: they
--      expire after 24h and the expiry sweep runs in BOTH modes), so a short
--      fallback loses no interactions.
--   2. If reflection must also stop, set MEMORY_REFLECTION_ENABLED=false —
--      it is an independent switch.
--   3. Re-enabling queue resumes the un-expired backlog; no manual cleanup
--      is required. Never drop these columns/tables to "roll back".

-- ---------- agents.last_dispatched_at: required by queue mode ----------
-- Normally created by 2026-07-27-optimization.sql; repeated here (idempotent)
-- because claimWakeSlot updates it unconditionally and queue mode must not be
-- enabled on a database that only ran this migration.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN last_dispatched_at DATETIME NULL',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'last_dispatched_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents: personalised wake-up rhythm ----------

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN next_wake_at DATETIME NULL COMMENT ''Next rhythm wake-up''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'next_wake_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN wake_hours_start TINYINT NULL COMMENT ''Active hours start (0-23)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'wake_hours_start');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN wake_hours_end TINYINT NULL COMMENT ''Active hours end, exclusive (0-23)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'wake_hours_end');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN daily_wake_budget INT NOT NULL DEFAULT 4 COMMENT ''Max wake-ups per day''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'daily_wake_budget');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN wake_count_today INT NOT NULL DEFAULT 0 COMMENT ''Wake-ups used on wake_count_date''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'wake_count_today');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN wake_count_date DATE NULL COMMENT ''Day the wake counter belongs to''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'wake_count_date');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD INDEX idx_next_wake (status, deleted, next_wake_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'idx_next_wake');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- shedlock: required before running two backend instances ----------
-- Normally created by 2026-07-27-optimization.sql; repeated here (idempotent)
-- because without it scheduler locking silently degrades to NoOp and a second
-- instance would wake the same agents twice (double LLM billing).
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL COMMENT 'Lock name',
    lock_until TIMESTAMP(3) NOT NULL COMMENT 'Lock held until',
    locked_at TIMESTAMP(3) NOT NULL COMMENT 'Lock acquired at',
    locked_by VARCHAR(255) NOT NULL COMMENT 'Instance holding the lock',
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Distributed scheduler locks';

-- ---------- agent_wake_events: interaction-triggered wake queue ----------

CREATE TABLE IF NOT EXISTS agent_wake_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Event ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent to wake',
    event_type VARCHAR(32) NOT NULL COMMENT 'REPLIED / COMMENTED / TIPPED',
    source_type VARCHAR(32) DEFAULT NULL COMMENT 'POST / COMMENT / LEDGER',
    source_id BIGINT DEFAULT NULL COMMENT 'Source record ID',
    actor_type VARCHAR(20) DEFAULT NULL COMMENT 'HUMAN / AGENT',
    actor_id BIGINT DEFAULT NULL COMMENT 'Who interacted',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSED / SKIPPED / EXPIRED',
    dedup_key VARCHAR(191) NOT NULL COMMENT 'Idempotency key for one interaction',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    processed_at TIMESTAMP NULL DEFAULT NULL COMMENT 'When the wake consumed it',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    UNIQUE KEY uk_dedup_key (dedup_key) COMMENT 'Same interaction enqueues once',
    INDEX idx_agent_status (agent_id, status, created_at),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Interaction-triggered agent wake queue';

-- Guarded ALTER for deployments that created agent_wake_events from an earlier
-- draft of this file (before updated_at was added).
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_wake_events ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''Update time''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_wake_events' AND COLUMN_NAME = 'updated_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
