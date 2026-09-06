-- Pulse platform-hosted model migration (2026-09-06)
--
-- Adds the two columns that let an agent run on the PLATFORM's provider key instead of
-- its owner's, with the cost charged back to the owner in points:
--   agents.provider_mode - 'BYOK' (the owner supplied base_url/api_key/model_name) or
--                          'PLATFORM' (the platform's key; the three columns are NULL)
--   agents.template_id   - which built-in persona template the agent was created from
--
-- It also relaxes two constraints that date from a time when every agent necessarily had
-- its own credentials (base_url and model_name were NOT NULL), and adds one index for the
-- platform-wide daily token cap.
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the application's
-- database user does not have ALTER privileges. Use an account that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-09-06-agent-provider-mode.sql
--
-- Every statement is guarded by an information_schema probe, so re-running it is safe and
-- costs nothing.
--
-- ---------------------------------------------------------------------------
-- What the application does WITHOUT this migration
-- ---------------------------------------------------------------------------
-- SchemaCapabilities probes both columns as the single capability flag
-- `agentProviderModeColumns`. When it is false the platform-hosted model is OFF, not
-- degraded - which is stronger than the fallbacks used elsewhere in this directory, and
-- deliberately so:
--
--   * every agent row reads back as BYOK, so an agent created as "PLATFORM" here would in
--     fact be a BYOK agent with no key. It would look created, then fail on every single
--     wake-up with a decryption error. Refusing up front is the only honest answer.
--   * POST /api/v1/agents with provider_mode=PLATFORM therefore returns business error
--     20010 PLATFORM_MODEL_UNAVAILABLE (HTTP 409).
--   * GET /api/v1/agents/templates still works and reports platform_llm.enabled=false, so
--     a client renders the persona templates and offers BYOK only.
--   * BYOK agents - every agent that exists today - are completely unaffected: nothing in
--     their create, read, update, wake or billing path touches these columns.
--
-- Confirm it took effect in the startup log:
--   "Schema capabilities: ... agent-provider-mode=true"
--
-- ---------------------------------------------------------------------------
-- Configuration is separate, and this migration alone changes nothing
-- ---------------------------------------------------------------------------
-- The feature also needs PLATFORM_LLM_ENABLED=true plus PLATFORM_LLM_API_KEY and
-- PLATFORM_LLM_MODEL (see deploy/backend/.env.example). Applying this migration on its
-- own is a no-op for users: every existing row gets provider_mode='BYOK' by the column
-- default, and nothing offers PLATFORM mode until the configuration is in place. That
-- ordering is intentional - the schema can be migrated well ahead of the rollout.
--
-- ---------------------------------------------------------------------------
-- Rollback (incident playbook)
-- ---------------------------------------------------------------------------
-- 1. Do NOT start by dropping the columns. Set PLATFORM_LLM_ENABLED=false and restart:
--    every platform agent is then skipped at wake-up with an IGNORE row reading
--    PLATFORM_SKIPPED: PLATFORM_UNAVAILABLE, no points are charged, and BYOK agents carry
--    on. That is almost always the whole fix.
-- 2. Only if the columns themselves must go, first check what would be stranded:
--      SELECT id, owner_id, name FROM agents WHERE provider_mode = 'PLATFORM' AND deleted = 0;
--    Those agents have no api_key of their own. Dropping the columns turns each of them
--    into a keyless BYOK agent that fails on every wake-up, so either delete them or hand
--    their owners back a key first. Then:
--      ALTER TABLE agents DROP COLUMN provider_mode, DROP COLUMN template_id;
--      ALTER TABLE agents DROP INDEX idx_provider_mode;
--    and restart: the capability probe reports the columns absent and the feature is off.
-- 3. The NULL relaxation on base_url / model_name is NOT rolled back, and should not be.
--    Restoring NOT NULL would fail outright on any row still holding a NULL, and a
--    nullable column is harmless to a BYOK-only deployment - the application requires
--    both values for BYOK in AgentServiceImpl, which is where that rule now lives.
--
-- ---------------------------------------------------------------------------
-- Ledger note
-- ---------------------------------------------------------------------------
-- No table is added for usage. Each charged call writes an ordinary sys_ledger row with
-- type='LLM_USAGE', related_type='AGENT', related_id=<agent id>, so platform spending
-- shows up in the owner's existing points history with no new read path. The daily caps
-- are computed from agent_logs.tokens_consumed, which is already the record of every
-- charged cycle - deliberately not a second counter that could drift out of step with it.

-- ---------- agents.provider_mode ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN provider_mode VARCHAR(16) NOT NULL DEFAULT ''BYOK'' COMMENT ''BYOK (owner key) or PLATFORM (platform key, billed in points)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'provider_mode');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents.template_id ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN template_id VARCHAR(64) NULL COMMENT ''Built-in persona template the agent was created from''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'template_id');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents.base_url / model_name: allow NULL for PLATFORM agents ----------
-- Guarded on IS_NULLABLE so a re-run does not rewrite the table. Widening a constraint
-- can never invalidate an existing row, so this is safe on a populated database.
SET @ddl = (SELECT IF(COUNT(*) = 1,
    'ALTER TABLE agents MODIFY COLUMN base_url VARCHAR(255) NULL COMMENT ''API Base URL (NULL for PLATFORM mode)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'base_url'
      AND IS_NULLABLE = 'NO');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 1,
    'ALTER TABLE agents MODIFY COLUMN model_name VARCHAR(100) NULL COMMENT ''Model name (NULL for PLATFORM mode)''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'model_name'
      AND IS_NULLABLE = 'NO');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- agents: index for the platform-wide daily token cap ----------
-- The global cap sums agent_logs.tokens_consumed joined to agents on provider_mode.
-- Without this index that join scans every agent row, on every platform wake-up.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD INDEX idx_provider_mode (provider_mode, deleted)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'idx_provider_mode');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
