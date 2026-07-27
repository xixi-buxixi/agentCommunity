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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Log time',

    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_id (agent_id),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent activity logs';

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
-- This file is re-applied on every deploy and `mysql < file` aborts on the first
-- error, so plain ALTER TABLE statements cannot be used here: the second deploy
-- would fail on "Duplicate key name" and silently skip everything after it. These
-- helpers check information_schema first, which makes re-running the file a no-op.

DROP PROCEDURE IF EXISTS pulse_add_index;
DROP PROCEDURE IF EXISTS pulse_add_column;
DROP PROCEDURE IF EXISTS pulse_add_agent_name_unique;

DELIMITER $$

CREATE PROCEDURE pulse_add_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_cols VARCHAR(255))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table)
       AND NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE()
                         AND TABLE_NAME = p_table
                         AND INDEX_NAME = p_index) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD INDEX `', p_index, '` (', p_cols, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE pulse_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table)
       AND NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE()
                         AND TABLE_NAME = p_table
                         AND COLUMN_NAME = p_column) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$

-- Agent names must be unique per owner: agentNameExists() only checks in
-- application code, so two concurrent creates could both succeed.
--
-- The key is built on a generated column that is NULL for soft-deleted rows.
-- A key over (owner_id, name, deleted) would break the ordinary lifecycle:
-- create "A", delete it, create "A" again, delete again -> the second delete
-- collides with the first deleted row. NULLs are never equal in a MySQL unique
-- index, so deleted rows simply drop out of the constraint.
--
-- Added only when existing data allows it; otherwise this step fails and the
-- operator has to deduplicate active agents first.
CREATE PROCEDURE pulse_add_agent_name_unique()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'agents'
                     AND COLUMN_NAME = 'active_name') THEN
        ALTER TABLE agents
            ADD COLUMN active_name VARCHAR(100)
            AS (IF(deleted = 0, name, NULL)) STORED;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 'agents'
                     AND INDEX_NAME = 'uk_owner_active_name')
       AND NOT EXISTS (SELECT 1 FROM (
                           SELECT owner_id, name FROM agents WHERE deleted = 0
                           GROUP BY owner_id, name HAVING COUNT(*) > 1
                       ) AS dupes) THEN
        ALTER TABLE agents ADD UNIQUE KEY uk_owner_active_name (owner_id, active_name);
    END IF;
END$$

DELIMITER ;

-- Composite indexes for the access paths that had none
CALL pulse_add_index('posts', 'idx_author_created', 'author_type, author_id, created_at');
CALL pulse_add_index('comments', 'idx_post_author', 'post_id, author_type, author_id');
CALL pulse_add_index('bounty_tasks', 'idx_agent_created', 'agent_id, created_at');
CALL pulse_add_index('bounty_tasks', 'idx_status_deadline', 'status, deadline');
CALL pulse_add_index('agent_logs', 'idx_agent_created', 'agent_id, created_at');
CALL pulse_add_index('sys_ledger', 'idx_user_created', 'user_id, created_at');

-- Materialized hot score. Sorting by the expression (like*3 + comment*5 + view)
-- forced a full scan plus filesort on every ranking refresh; a stored generated
-- column can be indexed and MySQL keeps it in sync automatically.
CALL pulse_add_column('posts', 'hot_score',
    'INT AS (COALESCE(like_count,0) * 3 + COALESCE(comment_count,0) * 5 + COALESCE(view_count,0)) STORED');
CALL pulse_add_index('posts', 'idx_hot_score', 'hot_score, created_at');

-- Round-robin agent dispatch. ORDER BY RAND() scans the whole table and builds a
-- temporary file on every scheduler tick; ordering by "least recently dispatched"
-- uses an index and is fairer than random selection.
CALL pulse_add_column('agents', 'last_dispatched_at', 'DATETIME NULL');
CALL pulse_add_index('agents', 'idx_dispatch_order', 'status, deleted, last_dispatched_at');

CALL pulse_add_agent_name_unique();

DROP PROCEDURE IF EXISTS pulse_add_index;
DROP PROCEDURE IF EXISTS pulse_add_column;
DROP PROCEDURE IF EXISTS pulse_add_agent_name_unique;

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
