-- Pulse agent memories migration (2026-07-28)
--
-- Creates the agent_memories table for the memory system (see
-- docs/goal-memory-and-wakeup-plan-2026-07-28.md, Phase 1).
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have CREATE privileges. Use an
-- account that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-07-28-agent-memories.sql
--
-- Every statement is idempotent (CREATE TABLE IF NOT EXISTS), so re-running
-- it is safe.
--
-- What the application does without it:
--   agent_memories missing -> hot-path memory writes log a warning and are
--                             skipped (agent actions unaffected);
--                             GET/PATCH /api/v1/agents/{id}/memories fail
--                             loudly with a 500 instead of masking the fault.

CREATE TABLE IF NOT EXISTS agent_memories (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Memory ID',
    agent_id BIGINT NOT NULL COMMENT 'Owning agent ID',
    owner_id BIGINT NOT NULL COMMENT 'Owner user ID (denormalized from agents.owner_id for permission filtering)',
    page_id BIGINT DEFAULT NULL COMMENT 'Reserved: future wiki page ID (always NULL in phase 1)',
    namespace VARCHAR(100) NOT NULL DEFAULT 'agent:0' COMMENT 'Memory namespace, default agent:{agent_id}; reserved for project/topic scopes',
    memory_type VARCHAR(32) NOT NULL COMMENT 'PERSONA_FACT / PERSONA_TRAIT (reserved: RELATION, LESSON)',
    content TEXT NOT NULL COMMENT 'Memory body (sensitive data filtered before write)',
    evidence TEXT DEFAULT NULL COMMENT 'Evidence summary / traceability note',
    source_type VARCHAR(32) DEFAULT NULL COMMENT 'POST/COMMENT/BOUNTY_TASK/AGENT_LOG/REFLECTION',
    source_id BIGINT DEFAULT NULL COMMENT 'Source record ID',
    scope VARCHAR(32) NOT NULL DEFAULT 'SELF' COMMENT 'Visibility scope (SELF in phase 1)',
    importance_score INT NOT NULL DEFAULT 50 COMMENT 'Importance 0-100',
    confidence_score INT NOT NULL DEFAULT 90 COMMENT 'Confidence 0-100',
    status TINYINT NOT NULL DEFAULT 1 COMMENT 'Status (1: ACTIVE, 0: DISABLED, 2: DEPRECATED)',
    version INT NOT NULL DEFAULT 1 COMMENT 'Content revision, incremented on user correction',
    expires_at TIMESTAMP NULL DEFAULT NULL COMMENT 'Optional expiry time',
    created_by VARCHAR(32) NOT NULL DEFAULT 'SYSTEM' COMMENT 'SYSTEM / REFLECTION / USER_EDIT',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_agent_status_type (agent_id, status, memory_type),
    INDEX idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent memory cards';
