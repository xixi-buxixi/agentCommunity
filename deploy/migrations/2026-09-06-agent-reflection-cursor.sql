-- Pulse reflection cursor migration (2026-09-06)
--
-- Adds the column the daily memory-reflection pass orders its candidates by:
--   agents.last_reflection_attempt_at - when the pass last TRIED to reflect on this
--                                       agent, whatever came of it (a successful
--                                       distillation, a gateway failure, or an empty
--                                       behaviour pack). NULL = never attempted.
-- and the index that makes that ordering cheap:
--   agents.idx_reflection_cursor (status, deleted, last_reflection_attempt_at)
--
-- Deliberately "last ATTEMPT", not "last success": the column exists to hand out turns
-- fairly, and an agent whose reflection fails every night would otherwise keep the front
-- of the queue for ever while the tail never got a turn at all.
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have ALTER privileges. Use an account
-- that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-09-06-agent-reflection-cursor.sql
--
-- Every statement is idempotent (information_schema-guarded ALTERs), so re-running it
-- is safe.
--
-- What the application does WITHOUT this migration (see SchemaCapabilities, capability
-- flag `reflectionCursorColumn`):
--   column missing
--     -> the capability is false and MemoryReflectionScheduler keeps its original
--        candidate query, ordered by id with an id keyset cursor. Nothing fails and no
--        agent is skipped when a run reaches the end of the list. What is lost is
--        fairness under the per-run ceiling: an id-ordered run always starts at the
--        lowest id, so once `scheduler.memory-reflection.max-agents-per-run` bites, the
--        same low-id agents are reflected on every night and the tail never is.
--   The column is never written on that path either - the UPDATE that stamps it names
--   the column explicitly and only runs when the capability is true.
--
-- Cost note: enabling this changes WHICH agents a capped run reflects on, not HOW MANY.
-- The per-run ceiling and the one-reflection-per-agent-per-day rule are unchanged, so
-- the nightly token spend does not move.
--
-- Rollback (incident playbook):
--   1. Nothing to switch off: the column is nullable and the application re-probes it at
--      startup. Dropping it is NOT required to roll the application back - an older build
--      simply ignores it.
--   2. If it must be removed anyway, stop the application first (the stamp UPDATE and the
--      candidate query both name it), then:
--        ALTER TABLE agents DROP INDEX idx_reflection_cursor;
--        ALTER TABLE agents DROP COLUMN last_reflection_attempt_at;
--      and restart: the capability probe reports it absent and the pass falls back to the
--      id cursor on its own.

-- ---------- agents.last_reflection_attempt_at ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN last_reflection_attempt_at DATETIME NULL COMMENT ''Last daily-reflection attempt (any outcome); NULL = never''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'last_reflection_attempt_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents.idx_reflection_cursor ----------
-- Serves the candidate query's ORDER BY (NULLs first, then oldest attempt, then id) with
-- the same status/deleted prefix every other agent selection uses.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD INDEX idx_reflection_cursor (status, deleted, last_reflection_attempt_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'idx_reflection_cursor');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
