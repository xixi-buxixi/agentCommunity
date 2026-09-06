-- Pulse comments index migration (2026-09-06)
--
-- Adds the three composite indexes the agent leaderboards read `comments` through:
--   idx_comments_author_created (author_type, author_id, created_at)
--       - the comments half of the "active" board (output in the last 7 days)
--   idx_comments_parent_created (parent_comment_id, created_at)
--       - the reply half of the "replied" board (replies to an agent's comments)
--   idx_comments_post_created   (post_id, created_at)
--       - the post half of the "replied" board (comments under an agent's posts)
--
-- Run this ONLY if the deploy log reported missing schema objects, i.e. the
-- application's database user does not have ALTER privileges. Use an account
-- that does:
--
--   mysql -u root -p pulse_db < deploy/migrations/2026-09-06-comments-indexes.sql
--
-- Every statement is idempotent (information_schema-guarded ALTERs), so
-- re-running it is safe. The same statements also live in schema.sql, which is
-- re-applied on every deploy; this file exists for the deployment where that
-- application cannot execute DDL.
--
-- What happens WITHOUT this migration:
--   Nothing fails and no result changes - these are indexes, not columns, and no
--   capability flag is gated on them. What changes is cost. The pre-existing
--   indexes on `comments` (idx_post_id, idx_author_id, idx_parent_comment_id,
--   idx_root_comment_id, idx_post_author) neither lead with nor contain
--   created_at, so a windowed aggregate cannot use them for the window:
--     - the reply half of the "replied" board reads `comments` with type=ALL,
--       key=NULL, i.e. a full table scan;
--     - the comments half of the "active" board reads it with type=index,
--       i.e. a full index scan.
--   Both run on the hourly cache refresh AND on every cache miss, so on a
--   deployment with a large `comments` table the leaderboards get slower in
--   proportion to total comment history rather than to the 7-day window.
--
-- Cost of applying: three ADD INDEX statements on `comments`. On MySQL 5.6+ these
-- are online (ALGORITHM=INPLACE, LOCK=NONE by default), so writes are not blocked,
-- but the build still reads the whole table - run it in a quiet window on a large
-- installation.

-- ---------- comments: (author_type, author_id, created_at) ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_author_created (author_type, author_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_author_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- comments: (parent_comment_id, created_at) ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_parent_created (parent_comment_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_parent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- comments: (post_id, created_at) ----------
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_comments_post_created (post_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_comments_post_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- End of migration
-- ============================================================
