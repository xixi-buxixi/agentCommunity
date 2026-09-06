-- Pulse notification centre migration (2026-09-06)
--
-- Adds the single table behind /api/v1/notifications:
--   notifications - one row = "this happened, and exactly one human user should be
--                   told". Title and body are a snapshot rendered at write time;
--                   actor_type/actor_id stay as ids so the display name can change.
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have CREATE privileges. Use an account
-- that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-09-06-notifications.sql
--
-- Every statement is idempotent (CREATE TABLE IF NOT EXISTS), so re-running it is safe.
--
-- What the application does WITHOUT this migration (see SchemaCapabilities,
-- capability flag `notificationsTable`):
--   table missing
--     -> every producer (comment, agent reply, tip, agent death, bounty submit and
--        audit) drops its notification and logs a warning. The business action itself
--        - the comment, the points movement, the bounty settlement - is completely
--        unaffected: producers run inside those transactions and never throw.
--     -> the read endpoints (GET /api/v1/notifications, /unread-count,
--        POST /{id}/read, /read-all) answer with business error 90001
--        NOTIFICATIONS_UNAVAILABLE (HTTP 409).
--   The read side deliberately does NOT degrade to an empty page: same reasoning as
--   D-0008 for agent_memories - an inbox that silently looks empty is
--   indistinguishable from working software, and this one exists to tell people
--   things they would otherwise miss.
--
-- No SecurityConfig change is required: /api/v1/notifications/** is not in any
-- permitAll matcher, so it falls through to anyRequest().authenticated().
--
-- Retention (NOT implemented, recommended policy):
--   Nothing prunes this table today; it grows with community activity. Once volume
--   justifies it, delete read notifications older than 90 days, in bounded batches so
--   the delete never holds a long transaction:
--     DELETE FROM notifications
--      WHERE created_at < DATE_SUB(NOW(), INTERVAL 90 DAY) AND is_read = 1
--      LIMIT 1000;   -- repeat until 0 rows affected
--   Keeping unread rows regardless of age is deliberate: an unread notification is
--   the one case where the user has demonstrably not seen it yet.
--
-- Rollback (incident playbook):
--   1. Nothing to switch off first: the application re-probes the table at startup,
--      and an older build simply ignores it.
--   2. If the table must be removed, stop the application (or accept that in-flight
--      writes will log warnings), then:
--        DROP TABLE notifications;
--      and restart: the capability probe reports it absent, producers degrade to a
--      warning and the read endpoints report NOTIFICATIONS_UNAVAILABLE.

-- ---------- notifications ----------
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

-- ---------- notifications: index for a table created by an earlier hand-written DDL ----------
-- CREATE TABLE IF NOT EXISTS does nothing when the table already exists, so a database
-- where it was created without the composite index would keep scanning.
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE notifications ADD INDEX idx_recipient_read_created (recipient_user_id, is_read, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notifications'
      AND INDEX_NAME = 'idx_recipient_read_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
