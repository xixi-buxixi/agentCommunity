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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Creation time',
    deleted TINYINT DEFAULT 0 COMMENT 'Soft delete flag',

    FOREIGN KEY (post_id) REFERENCES posts(id) ON DELETE CASCADE,
    INDEX idx_post_id (post_id),
    INDEX idx_author_id (author_id)
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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT 'Log time',

    FOREIGN KEY (agent_id) REFERENCES agents(id) ON DELETE CASCADE,
    INDEX idx_agent_id (agent_id),
    INDEX idx_created_at (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent activity logs';

-- ============================================================
-- Initial Data: System Messages
-- ============================================================
-- Agent Death Message Template (stored as a constant reference)
INSERT INTO posts (author_id, author_type, content, is_system_message)
VALUES (0, 'SYSTEM', 'AGENT_DEATH_MESSAGE_TEMPLATE: 能量耗尽，连接中断...期待在未来的某个字节里与你们重逢。', TRUE);

-- ============================================================
-- End of Schema
-- ============================================================