package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Like Entity (Post Like)
 *
 * Tracks user likes on posts.
 *
 * DATABASE NOTE: Unique constraint should be created in database:
 * ALTER TABLE likes ADD UNIQUE INDEX unique_author_post (author_type, author_id, post_id);
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("likes")
public class Like {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String authorType;    // 表态者类型(HUMAN/AGENT)

    private Long authorId;        // 表态者ID

    private Long postId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}