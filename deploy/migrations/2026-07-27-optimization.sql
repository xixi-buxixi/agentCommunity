-- Pulse optimization migration (2026-07-27)
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have ALTER/CREATE privileges. Use an
-- account that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-07-27-optimization.sql
--
-- Every statement is idempotent, so re-running it is safe.
--
-- What the application does without it (see SchemaCapabilities):
--   posts.hot_score missing          -> ranking sorts by the raw expression
--                                       (correct, but a full scan + filesort)
--   agents.last_dispatched_at missing-> agent selection falls back to ORDER BY RAND()
--   shedlock missing                 -> scheduler locking disabled (single instance only)
--
-- Requires MySQL 5.7+ (generated columns).

-- ============================================================
-- Idempotent migrations (M4)
-- ============================================================
-- This file is re-applied on every deploy, so every statement below has to be safe
-- to run twice. Plain ALTER TABLE is not: the second deploy fails with
-- "Duplicate key name" / "Duplicate column name".
--
-- The pattern below builds the DDL as a string only when the object is missing and
-- executes a harmless SELECT otherwise. It deliberately avoids stored procedures:
-- CREATE PROCEDURE requires the CREATE ROUTINE privilege, which the application's
-- database user typically does not have - the first attempt at this migration
-- failed in CI for exactly that reason.
--
-- Requires MySQL 5.7+ (generated columns).

-- ---------- posts: composite index for author timelines ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD INDEX idx_author_created (author_type, author_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND INDEX_NAME = 'idx_author_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- comments ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_post_author (post_id, author_type, author_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_post_author');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- bounty_tasks ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE bounty_tasks ADD INDEX idx_agent_created (agent_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bounty_tasks' AND INDEX_NAME = 'idx_agent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE bounty_tasks ADD INDEX idx_status_deadline (status, deadline)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bounty_tasks' AND INDEX_NAME = 'idx_status_deadline');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agent_logs ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_logs ADD INDEX idx_agent_created (agent_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_logs' AND INDEX_NAME = 'idx_agent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- sys_ledger ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE sys_ledger ADD INDEX idx_user_created (user_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_ledger' AND INDEX_NAME = 'idx_user_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- posts.hot_score: materialized ranking score ----------
-- Sorting by the raw expression (like*3 + comment*5 + view) can never use an index,
-- so every ranking refresh scanned all posts and filesorted them. A stored
-- generated column is maintained by MySQL itself and can be indexed.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD COLUMN hot_score INT AS (COALESCE(like_count,0) * 3 + COALESCE(comment_count,0) * 5 + COALESCE(view_count,0)) STORED',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND COLUMN_NAME = 'hot_score');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD INDEX idx_hot_score (hot_score, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND INDEX_NAME = 'idx_hot_score');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents.last_dispatched_at: round-robin scheduling ----------
-- Replaces ORDER BY RAND(), which scanned the whole table into a temporary table on
-- every scheduler tick and could starve an agent indefinitely.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN last_dispatched_at DATETIME NULL',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'last_dispatched_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD INDEX idx_dispatch_order (status, deleted, last_dispatched_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'idx_dispatch_order');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents: one active name per owner ----------
-- agentNameExists() only checks in application code, so two concurrent creates could
-- both succeed. The key is built on a generated column that is NULL for soft-deleted
-- rows: a key over (owner_id, name, deleted) would break the normal lifecycle
-- (create A, delete, create A, delete again -> collision), while NULLs are never
-- equal in a MySQL unique index.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN active_name VARCHAR(100) AS (IF(deleted = 0, name, NULL)) STORED',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'active_name');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Only add the constraint when existing data satisfies it; otherwise skip it so the
-- deploy keeps working and the operator can deduplicate active agents first.
SET @dupes = (SELECT COUNT(*) FROM (
    SELECT owner_id, name FROM agents WHERE deleted = 0
    GROUP BY owner_id, name HAVING COUNT(*) > 1) AS d);
SET @has_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'uk_owner_active_name');
SET @ddl = IF(@dupes = 0 AND @has_key = 0,
    'ALTER TABLE agents ADD UNIQUE KEY uk_owner_active_name (owner_id, active_name)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- ShedLock: single-run guarantee for @Scheduled jobs
-- ============================================================
-- Without it, a second instance wakes the same agents (burning the user's real
-- LLM tokens twice) and can release the same bounty freeze twice.
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL COMMENT 'Lock name',
    lock_until TIMESTAMP(3) NOT NULL COMMENT 'Lock held until',
    locked_at TIMESTAMP(3) NOT NULL COMMENT 'Lock acquired at',
    locked_by VARCHAR(255) NOT NULL COMMENT 'Instance holding the lock',
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Distributed scheduler locks';

