-- Pulse agent log wake context migration (2026-09-06)
--
-- Adds the two columns that record WHY an agent was awake when it wrote an
-- activity log row:
--   agent_logs.wake_reason       - WakeReason name (RHYTHM / EVENT / LEGACY_BATCH)
--   agent_logs.wake_event_types  - the distinct wake event types this wake-up
--                                  consumed, de-duplicated, sorted alphabetically
--                                  and comma separated ("COMMENTED,TIPPED");
--                                  NULL for a wake-up that was not event driven
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have ALTER privileges. Use an account
-- that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-09-06-agent-log-wake-context.sql
--
-- Every statement is idempotent (information_schema-guarded ALTERs), so
-- re-running it is safe.
--
-- What the application does WITHOUT this migration (see SchemaCapabilities,
-- capability flag `agentLogWakeColumns`):
--   both columns missing (or only one of them present)
--     -> the capability is false and every agent_logs write keeps using the
--        MyBatis-Plus generated INSERT, exactly as before. No wake reason is
--        recorded, the API returns wake_reason / wake_event_types as null and
--        wake_reason_text as null. Nothing fails, nothing is degraded beyond
--        the missing audit detail.
--   The capability is deliberately independent of `wakeQueueSchema`: a database
--   may have the wake queue without these columns and vice versa.
--
-- Rollback (incident playbook):
--   1. Nothing to switch off: the columns are nullable and the application
--      re-probes them at startup. Dropping them is NOT required to roll the
--      application back - an older build simply ignores them.
--   2. If the columns must be removed anyway, stop the application first (the
--      explicit INSERT names them), then:
--        ALTER TABLE agent_logs DROP COLUMN wake_event_types;
--        ALTER TABLE agent_logs DROP COLUMN wake_reason;
--      and restart: the capability probe reports them absent and the write path
--      falls back to the generated INSERT on its own.

-- ---------- agent_logs.wake_reason ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_logs ADD COLUMN wake_reason VARCHAR(16) NULL COMMENT ''WakeReason name (RHYTHM/EVENT/LEGACY_BATCH)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_logs' AND COLUMN_NAME = 'wake_reason');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agent_logs.wake_event_types ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_logs ADD COLUMN wake_event_types VARCHAR(64) NULL COMMENT ''Distinct wake event types consumed, sorted, comma separated''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_logs' AND COLUMN_NAME = 'wake_event_types');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
