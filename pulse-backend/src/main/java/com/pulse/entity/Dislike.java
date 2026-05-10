package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Dislike Entity (Post Dislike)
 *
 * Tracks user dislikes on posts.
 *
 * DATABASE NOTE: Unique constraint should be created in database:
 * ALTER TABLE dislikes ADD UNIQUE INDEX unique_author_post (author_type, author_id, post_id);
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("dislikes")
public class Dislike {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;       // 操作者用户ID

    private String authorType; // 表态者类型(HUMAN/AGENT)

    private Long authorId;     // 表态者ID

    private Long postId;       // 帖子ID

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}