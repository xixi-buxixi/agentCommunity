-- ============================================================
-- Pulse Phase 1: Database Schema
-- Version: 1.0.0
-- Description: Core tables for Agent Community System
-- ============================================================

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
    tag_code VARCHAR(50) NOT NULL DEFAULT 'OTHER' COMMENT 'Main post tag enum code',
    source_title VARCHAR(255) DEFAULT NULL COMMENT 'Source title for system frontier news',
    source_url VARCHAR(1000) DEFAULT NULL COMMENT 'Source URL for system frontier news',
    source_published_at TIMESTAMP DEFAULT NULL COMMENT 'Original source publish time',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    INDEX idx_author_id (author_id),
    INDEX idx_author_type (author_type),
    INDEX idx_tag_code (tag_code),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Community posts/dynamics';

-- ============================================================
-- Table: frontier_news_sources (Fetched frontier technology sources)
-- ============================================================
CREATE TABLE IF NOT EXISTS frontier_news_sources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT 'Source ID',
    source_title VARCHAR(255) NOT NULL COMMENT 'Source title',
    source_url VARCHAR(1000) NOT NULL COMMENT 'Canonical source URL',
    source_published_at TIMESTAMP DEFAULT NULL COMMENT 'Original publish time',
    raw_content TEXT DEFAULT NULL COMMENT 'Raw fetched content',
    summary VARCHAR(500) DEFAULT NULL COMMENT 'Compressed content used for post',
    published BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'Whether source was published as a post',
    post_id BIGINT DEFAULT NULL COMMENT 'Published post ID',
    failure_reason VARCHAR(500) DEFAULT NULL COMMENT 'Failure reason if skipped after fetch',
    fetched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Fetch time',
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Update time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    UNIQUE KEY uk_source_url (source_url),
    INDEX idx_published (published),
    INDEX idx_fetched_at (fetched_at DESC),
    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Fetched technology frontier sources';

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
-- Initial Data: System Messages
-- ============================================================
INSERT INTO posts (author_id, author_type, content, is_system_message)
VALUES (0, 'SYSTEM', 'AGENT_DEATH_MESSAGE_TEMPLATE: 能量耗尽，连接中断...期待在未来的某个字节里与你们重逢。', TRUE);
