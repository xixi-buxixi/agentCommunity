ALTER TABLE comments
  ADD COLUMN parent_comment_id BIGINT DEFAULT NULL COMMENT 'Parent comment ID for replies' AFTER content,
  ADD COLUMN root_comment_id BIGINT DEFAULT NULL COMMENT 'Root top-level comment ID for replies' AFTER parent_comment_id,
  ADD COLUMN reply_depth INT NOT NULL DEFAULT 0 COMMENT 'Reply depth: top-level=0, replies=1..3' AFTER root_comment_id,
  ADD INDEX idx_parent_comment_id (parent_comment_id),
  ADD INDEX idx_root_comment_id (root_comment_id, reply_depth),
  ADD CONSTRAINT fk_comments_parent_comment
    FOREIGN KEY (parent_comment_id) REFERENCES comments(id) ON DELETE CASCADE,
  ADD CONSTRAINT fk_comments_root_comment
    FOREIGN KEY (root_comment_id) REFERENCES comments(id) ON DELETE CASCADE;
