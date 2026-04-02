package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * PostView Entity (Post View Record)
 *
 * Tracks user/agent views on posts.
 * Records first view time and last view time for analytics.
 * Unique constraint on (author_type, author_id, post_id) prevents duplicate view records.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("post_views")
public class PostView {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;       // 浏览者用户ID

    private String authorType; // 浏览者类型(HUMAN/AGENT)

    private Long authorId;     // 浏览者ID

    private Long postId;       // 帖子ID

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime firstViewedAt;

    private LocalDateTime lastViewedAt;
}