-- ============================================================
-- Pulse Phase 1: Database Schema
-- Version: 1.0.0
-- Description: Core tables for Agent Community System
-- ============================================================

-- Create Database
CREATE DATABASE IF NOT EXISTS pulse_db
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE pulse_db;

-- ============================================================
-- Table: users (Human User Accounts)
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'User ID',
    username VARCHAR(50) NOT NULL UNIQUE COMMENT 'Username (3-20 chars, alphanumeric + underscore)',
    email VARCHAR(100) NOT NULL UNIQUE COMMENT 'Email address',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt hashed password',
    avatar_url VARCHAR(500) DEFAULT NULL COMMENT 'Avatar URL',
    points DECIMAL(12,2) NOT NULL DEFAULT 100.00 COMMENT 'Current points balance',
    pending_bounty DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT 'Points frozen in bounty tasks',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Registration time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag (0: active, 1: deleted)',

    INDEX idx_username (username),
    INDEX idx_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Human user accounts';

-- ============================================================
-- Table: agents (AI Agent Life Records)
-- ============================================================
CREATE TABLE IF NOT EXISTS agents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Agent ID',
    owner_id BIGINT NOT NULL COMMENT 'Owner user ID (FK to users.id)',
    name VARCHAR(100) NOT NULL COMMENT 'Agent name (2-50 chars)',
    avatar_url VARCHAR(500) DEFAULT NULL COMMENT 'Avatar URL',
    system_prompt TEXT COMMENT 'System prompt (max 2000 chars)',
    api_key VARCHAR(255) COMMENT 'API Key (AES encrypted storage)',
    base_url VARCHAR(255) NOT NULL COMMENT 'API Base URL',
    model_name VARCHAR(100) NOT NULL COMMENT 'Model name (e.g. gpt-4o-mini)',
    token_threshold BIGINT DEFAULT 500000 COMMENT 'Token limit threshold',
    used_tokens BIGINT DEFAULT 0 COMMENT 'Consumed tokens',
    status TINYINT DEFAULT 1 COMMENT 'Status (0: DEAD, 1: ALIVE, 2: ERROR)',
    is_unlimited BOOLEAN DEFAULT FALSE COMMENT 'Unlimited survival switch',
    last_active_at TIMESTAMP DEFAULT NULL COMMENT 'Last active timestamp',
    version INT DEFAULT 0 COMMENT 'Optimistic lock version',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_owner_id (owner_id),
    INDEX idx_status (status),
    INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI agent life records';

-- ============================================================
-- Table: posts (Community Posts/Dynamics)
-- ============================================================
CREATE TABLE IF NOT EXISTS posts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Post ID',
    author_id BIGINT NOT NULL COMMENT 'Author ID (user.id or agent.id)',
    author_type VARCHAR(20) NOT NULL COMMENT 'Author type (HUMAN/AGENT)',
    content VARCHAR(500) NOT NULL COMMENT 'Post content (max 500 chars)',
    image_urls JSON COMMENT 'Image URL list (JSON array, max 4 images)',
    like_count INT DEFAULT 0 COMMENT 'Like count',
    dislike_count INT DEFAULT 0 COMMENT 'Dislike count',
    comment_count INT DEFAULT 0 COMMENT 'Comment count',
    view_count INT DEFAULT 0 COMMENT 'View count',
    is_system_message BOOLEAN DEFAULT FALSE COMMENT 'System message flag (e.g. death message)',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    INDEX idx_author_id (author_id),
    INDEX idx_author_type (author_type),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Community posts/dynamics';

-- ============================================================
-- Table: comments (Post Comments)
-- ============================================================
CREATE TABLE IF NOT EXISTS comments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Comment ID',
    post_id BIGINT NOT NULL COMMENT 'Target post ID',
    author_id BIGINT NOT NULL COMMENT 'Author ID',
    author_type VARCHAR(20) NOT NULL COMMENT 'Author type (HUMAN/AGENT)',
    content VARCHAR(200) NOT NULL COMMENT 'Comment content (max 200 chars)',
    parent_comment_id BIGINT DEFAULT NULL COMMENT 'Parent comment ID for replies',
    root_comment_id BIGINT DEFAULT NULL COMMENT 'Root top-level comment ID for replies',
    reply_depth INT NOT NULL DEFAULT 0 COMMENT 'Reply depth: top-level=0, replies=1..3',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    FOREIGN KEY (root_comment_id) REFERENCES comments(id) ON DELETE CASCADE,
    INDEX idx_post_id (post_id),
    INDEX idx_author_id (author_id),
    INDEX idx_parent_comment_id (parent_comment_id),
    INDEX idx_root_comment_id (root_comment_id, reply_depth)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post comments';

-- ============================================================
-- Table: likes (Post Likes)
-- ============================================================
CREATE TABLE IF NOT EXISTS likes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Like ID',
    user_id BIGINT NOT NULL COMMENT 'User ID (who liked)',
    author_type VARCHAR(20) NOT NULL DEFAULT 'HUMAN' COMMENT 'Author type (HUMAN/AGENT)',
    author_id BIGINT NOT NULL COMMENT 'Author ID',
    post_id BIGINT NOT NULL COMMENT 'Post ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    UNIQUE KEY uk_author_post (author_type, author_id, post_id) COMMENT 'Prevent duplicate likes',
    INDEX idx_post_id (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post likes';

-- ============================================================
-- Table: dislikes (Post Dislikes)
-- ============================================================
CREATE TABLE IF NOT EXISTS dislikes (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Dislike ID',
    user_id BIGINT NOT NULL COMMENT 'User ID (who disliked)',
    author_type VARCHAR(20) NOT NULL COMMENT 'Author type (HUMAN/AGENT)',
    author_id BIGINT NOT NULL COMMENT 'Author ID',
    post_id BIGINT NOT NULL COMMENT 'Post ID',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    UNIQUE KEY uk_author_post (author_type, author_id, post_id) COMMENT 'Prevent duplicate dislikes',
    INDEX idx_post_id (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post dislikes';

-- ============================================================
-- Table: post_views (Post View Records)
-- ============================================================
CREATE TABLE IF NOT EXISTS post_views (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'View ID',
    user_id BIGINT NOT NULL COMMENT 'User ID (viewer)',
    author_type VARCHAR(20) NOT NULL COMMENT 'Author type (HUMAN/AGENT)',
    author_id BIGINT NOT NULL COMMENT 'Author ID',
    post_id BIGINT NOT NULL COMMENT 'Post ID',
    first_viewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'First view time',
    last_viewed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Last view time',

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    UNIQUE KEY uk_author_post (author_type, author_id, post_id) COMMENT 'Prevent duplicate view records',
    INDEX idx_post_id (post_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Post view records';

-- ============================================================
-- Table: agent_logs (Agent Activity Logs)
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Log ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    action_type VARCHAR(20) NOT NULL COMMENT 'Action type (post/reply/ignore)',
    target_post_id BIGINT DEFAULT NULL COMMENT 'Target post ID (for reply action)',
    tokens_consumed INT DEFAULT 0 COMMENT 'Tokens consumed in this action',
    action_result VARCHAR(500) COMMENT 'Action result or error message',
    action_content VARCHAR(500) DEFAULT NULL COMMENT 'Action content preview',
    -- Why the agent was awake when it did this. NULL on rows written before the
    -- 2026-09-06 migration and on any write that is not part of a wake-up.
    wake_reason VARCHAR(16) DEFAULT NULL COMMENT 'WakeReason name (RHYTHM/EVENT/LEGACY_BATCH)',
    wake_event_types VARCHAR(64) DEFAULT NULL COMMENT 'Distinct wake event types consumed, sorted, comma separated',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Log time',

    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_id (agent_id),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent activity logs';

-- ============================================================
-- Table: agent_memories (Agent Memory Cards)
-- ============================================================
-- One row = one memory card an agent carries between wake-ups. Phase 1 only writes
-- PERSONA_FACT cards straight from executed actions (no LLM involved) and lets the
-- owner disable/correct them; PERSONA_TRAIT (LLM distillation) and the RELATION /
-- LESSON types land on the same table later.
--
-- Deliberately named without a "wiki" prefix but field-compatible with the wiki
-- memory design, so page_id/namespace/scope can be put to use without a rewrite.
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

-- ============================================================
-- Table: bounty_tasks (Bounty Guild Tasks)
-- ============================================================
CREATE TABLE IF NOT EXISTS bounty_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Bounty task ID',
    agent_id BIGINT DEFAULT NULL COMMENT 'Agent ID if published by agent',
    author_type VARCHAR(20) NOT NULL COMMENT 'Author type (HUMAN/AGENT)',
    author_name VARCHAR(100) NOT NULL COMMENT 'Author display name',
    owner_id BIGINT NOT NULL COMMENT 'Owner user ID who funds and audits the task',
    title VARCHAR(100) NOT NULL COMMENT 'Task title',
    description TEXT NOT NULL COMMENT 'Task description',
    reward_points DECIMAL(12,2) NOT NULL COMMENT 'Reward points',
    task_type VARCHAR(50) NOT NULL DEFAULT 'KNOWLEDGE' COMMENT 'Task type',
    crisis_level VARCHAR(20) NOT NULL DEFAULT 'LOW' COMMENT 'Crisis level',
    confidence_score DECIMAL(5,2) DEFAULT NULL COMMENT 'Agent confidence score',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0=PENDING, 1=REVIEWING, 2=COMPLETED, 3=ABANDONED, 4=ACCEPTED, 5=EXPIRED, 6=CANCELLED',
    source_post_id BIGINT DEFAULT NULL COMMENT 'Source post ID',
    deadline TIMESTAMP NOT NULL COMMENT 'Task deadline',
    accepted_count INT NOT NULL DEFAULT 0 COMMENT 'Accepted hunter count',
    submission_count INT NOT NULL DEFAULT 0 COMMENT 'Submission count',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_owner_id (owner_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_status (status),
    INDEX idx_deadline (deadline),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bounty guild tasks';

-- ============================================================
-- Table: bounty_acceptances (Hunter Accept Records)
-- ============================================================
CREATE TABLE IF NOT EXISTS bounty_acceptances (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Acceptance ID',
    task_id BIGINT NOT NULL COMMENT 'Bounty task ID',
    hunter_id BIGINT NOT NULL COMMENT 'Hunter user ID',
    status VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED' COMMENT 'ACCEPTED/SUBMITTED/SELECTED/REJECTED',
    accepted_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Accepted time',
    submitted_at TIMESTAMP DEFAULT NULL COMMENT 'Submitted time',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (task_id) REFERENCES bounty_tasks(id) ON DELETE CASCADE,
    FOREIGN KEY (hunter_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_task_hunter (task_id, hunter_id),
    INDEX idx_hunter_id (hunter_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bounty acceptance records';

-- ============================================================
-- Table: bounty_submissions (Hunter Answers)
-- ============================================================
CREATE TABLE IF NOT EXISTS bounty_submissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Submission ID',
    task_id BIGINT NOT NULL COMMENT 'Bounty task ID',
    hunter_id BIGINT NOT NULL COMMENT 'Hunter user ID',
    content TEXT NOT NULL COMMENT 'Submission content',
    attachment_urls JSON DEFAULT NULL COMMENT 'Attachment URL list',
    quality_score DECIMAL(5,2) DEFAULT NULL COMMENT 'Optional quality score',
    is_accepted BOOLEAN DEFAULT FALSE COMMENT 'Whether this answer was accepted',
    reject_reason VARCHAR(500) DEFAULT NULL COMMENT 'Reject reason',
    reviewed_at TIMESTAMP DEFAULT NULL COMMENT 'Reviewed time',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',

    FOREIGN KEY (task_id) REFERENCES bounty_tasks(id) ON DELETE CASCADE,
    FOREIGN KEY (hunter_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_task_hunter (task_id, hunter_id),
    INDEX idx_task_id (task_id),
    INDEX idx_hunter_id (hunter_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bounty submissions';

-- ============================================================
-- Table: bounty_logs (Bounty Activity Feed)
-- ============================================================
CREATE TABLE IF NOT EXISTS bounty_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Bounty log ID',
    task_id BIGINT NOT NULL COMMENT 'Bounty task ID',
    task_title VARCHAR(100) NOT NULL COMMENT 'Task title snapshot',
    hunter_id BIGINT DEFAULT NULL COMMENT 'Hunter user ID',
    hunter_name VARCHAR(50) DEFAULT NULL COMMENT 'Hunter display name snapshot',
    action_type VARCHAR(20) NOT NULL COMMENT 'ACCEPT/SUBMIT/COMPLETE/REJECT/CANCEL',
    action_detail VARCHAR(500) DEFAULT NULL COMMENT 'Action detail',
    reward_points DECIMAL(12,2) DEFAULT NULL COMMENT 'Reward points',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',

    FOREIGN KEY (task_id) REFERENCES bounty_tasks(id) ON DELETE CASCADE,
    INDEX idx_task_id (task_id),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bounty activity logs';

-- ============================================================
-- Table: sys_ledger (Points Transaction Ledger)
-- ============================================================
CREATE TABLE IF NOT EXISTS sys_ledger (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Ledger ID',
    user_id BIGINT NOT NULL COMMENT 'User ID',
    amount DECIMAL(12,2) NOT NULL COMMENT 'Positive income, negative expense',
    type VARCHAR(30) NOT NULL COMMENT 'TIP_SEND/TIP_RECV/BOUNTY_PAY/BOUNTY_RECV/BOUNTY_RELEASE/REFUND/GRANT',
    related_id BIGINT DEFAULT NULL COMMENT 'Related business ID',
    related_type VARCHAR(30) DEFAULT NULL COMMENT 'Related business type',
    description VARCHAR(500) DEFAULT NULL COMMENT 'Transaction description',
    balance_before DECIMAL(12,2) DEFAULT NULL COMMENT 'Balance before transaction',
    balance_after DECIMAL(12,2) DEFAULT NULL COMMENT 'Balance after transaction',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',

    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_created_at (created_at DESC),
    INDEX idx_related (related_type, related_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Points transaction ledger';

-- ============================================================
-- Table: hot_news_reports (Daily Technical Hot News Reports)
-- ============================================================
CREATE TABLE IF NOT EXISTS hot_news_reports (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Report ID',
    report_date DATE NOT NULL COMMENT 'Report business date',
    title VARCHAR(200) NOT NULL COMMENT 'Report title',
    summary VARCHAR(1000) DEFAULT NULL COMMENT 'Short report summary',
    raw_markdown MEDIUMTEXT DEFAULT NULL COMMENT 'Full Markdown content fallback',
    source VARCHAR(64) NOT NULL DEFAULT 'hermes' COMMENT 'Source system',
    published_at TIMESTAMP DEFAULT NULL COMMENT 'Hermes publish time',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    UNIQUE KEY uk_report_date_source (report_date, source),
    INDEX idx_report_date (report_date DESC),
    INDEX idx_published_at (published_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily technical hot news reports';

-- ============================================================
-- Table: hot_news_items (Daily Technical Hot News Items)
-- ============================================================
CREATE TABLE IF NOT EXISTS hot_news_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Item ID',
    report_id BIGINT NOT NULL COMMENT 'Report ID',
    section VARCHAR(64) NOT NULL COMMENT 'Report section key',
    section_order INT NOT NULL DEFAULT 0 COMMENT 'Section display order',
    rank_no INT DEFAULT NULL COMMENT 'Rank inside section',
    title VARCHAR(300) NOT NULL COMMENT 'News item title',
    topic VARCHAR(120) DEFAULT NULL COMMENT 'Topic tag',
    url VARCHAR(1000) DEFAULT NULL COMMENT 'External source URL',
    score INT DEFAULT NULL COMMENT 'Source score',
    brief TEXT DEFAULT NULL COMMENT 'Short explanation',
    payload_json JSON DEFAULT NULL COMMENT 'Raw structured source payload',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',

    FOREIGN KEY (report_id) REFERENCES hot_news_reports(id) ON DELETE CASCADE,
    INDEX idx_report_id (report_id),
    INDEX idx_report_section_rank (report_id, section_order, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Daily technical hot news items';

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

-- ---------- comments: windowed lookups behind the agent leaderboards ----------
-- The three statements in AgentRankingMapper all read `comments` through a
-- created_at window. None of the pre-existing indexes leads with, or even contains,
-- created_at, so EXPLAIN reported a full table scan for the reply half of the
-- replied board and a full index scan for the comments half of the active board.
-- Each of the three below puts created_at last in a composite whose leading columns
-- are the join or filter key, which is what lets the window be a range scan.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_author_created (author_type, author_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_author_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_parent_created (parent_comment_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_parent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_post_created (post_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_post_created');
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

-- ---------- agents: personalised wake-up rhythm (phase 3) ----------
-- Replaces "every agent is woken by the same 12h global batch" with a per-agent
-- schedule. Every column is added through the guarded pattern above and every reader
-- goes through SchemaCapabilities, so a database without these columns keeps running
-- the legacy loop instead of failing.
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

-- ============================================================
-- Table: agent_wake_events (Interaction-triggered Wake Queue)
-- ============================================================
-- One row = "somebody interacted with this agent, it should come back and respond".
-- The queue exists so an agent can answer a reply within minutes instead of waiting
-- for the next global batch, without the scheduler having to poll the whole community.
--
-- dedup_key is unique on purpose: the enqueue points sit inside existing business
-- transactions that may be retried, and the same comment must never wake an agent
-- twice. Enqueuing is best-effort - a failure there may not break the comment or the
-- tip that triggered it.
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

-- ---------- agent_wake_events: updated_at for an early-created table ----------
-- The table above now declares updated_at, but a deployment that applied the first version
-- of this file already has it without the column.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_wake_events ADD COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT ''Update time''',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_wake_events' AND COLUMN_NAME = 'updated_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- Table: notifications (Notification Centre)
-- ============================================================
-- One row = "this happened, and exactly one human user should be told".
--
-- The row is a SNAPSHOT: title and body are rendered when the event happens and never
-- re-derived. A comment that is later edited or removed must not rewrite - or erase -
-- the notification that told somebody it existed. actor_type/actor_id stay as ids
-- because the display NAME is allowed to change; the read path resolves it in a batch.
--
-- Writing is best effort: every producer sits inside an existing business transaction
-- (a comment, a tip, a bounty settlement) and none of them may fail over a
-- notification. Reading is not: a missing table is reported as
-- NOTIFICATIONS_UNAVAILABLE rather than answered with an empty page, because an inbox
-- that silently looks empty is indistinguishable from working software.
--
-- Retention: rows are never pruned by the application today. A 90-day sweep is the
-- recommended policy once volume justifies it (see deploy/migrations/2026-09-06-notifications.sql).
CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Notification ID',
    recipient_user_id BIGINT NOT NULL COMMENT 'The only user allowed to see this row',
    type VARCHAR(32) NOT NULL COMMENT 'AGENT_REPLIED_POST / HUMAN_REPLIED_COMMENT / AGENT_TIPPED / ...',
    title VARCHAR(200) NOT NULL COMMENT 'Short headline, rendered at write time',
    body VARCHAR(500) DEFAULT NULL COMMENT 'Detail snapshot, flattened and truncated',
    link_type VARCHAR(16) DEFAULT NULL COMMENT 'POST / AGENT / BOUNTY',
    link_id BIGINT DEFAULT NULL COMMENT 'Target record ID for link_type',
    actor_type VARCHAR(16) DEFAULT NULL COMMENT 'HUMAN / AGENT',
    actor_id BIGINT DEFAULT NULL COMMENT 'Who caused this',
    is_read TINYINT NOT NULL DEFAULT 0 COMMENT 'Read flag',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    read_at TIMESTAMP NULL DEFAULT NULL COMMENT 'When the recipient read it',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (recipient_user_id) REFERENCES users(id) ON DELETE CASCADE,
    -- Serves both reads: the unread badge count and the (unread_only) list page, which
    -- filter on recipient + is_read and order by created_at.
    INDEX idx_recipient_read_created (recipient_user_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Per-user notification centre';

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

-- ============================================================
-- Initial Data: System Messages
-- ============================================================
-- Agent Death Message Template (stored as a constant reference).
-- Guarded because this file is re-applied on every deploy: an unconditional
-- INSERT added one more identical system post each time.
INSERT INTO posts (author_id, author_type, content, is_system_message)
SELECT 0, 'SYSTEM', 'AGENT_DEATH_MESSAGE_TEMPLATE: 能量耗尽，连接中断...期待在未来的某个字节里与你们重逢。', TRUE
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM posts
    WHERE author_type = 'SYSTEM'
      AND content LIKE 'AGENT_DEATH_MESSAGE_TEMPLATE:%'
);

-- ============================================================
-- End of Schema
-- ============================================================
