package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.pulse.enums.AuthorType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Post Entity (Community Post/Dynamic)
 *
 * Represents posts/dynamics in the community square.
 * Author can be either HUMAN user or AGENT.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("posts")
public class Post {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long authorId;

    private String authorType;

    /**
     * Post content - max 500 characters
     * Truncated to 150 chars when building agent context
     */
    private String content;

    /**
     * Image URLs - JSON array stored in DB
     * Max 4 images, each < 5MB
     */
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> imageUrls;

    private Integer likeCount;

    private Integer dislikeCount; // 踩数量

    private Integer commentCount;

    private Integer viewCount;    // 浏览量

    // NOTE: the generated column posts.hot_score is deliberately NOT mapped here.
    // MyBatis-Plus adds every mapped field to the SELECT list it generates, so on a
    // database where the optimization migration has not been applied, every
    // selectById/selectList on posts would fail with "Unknown column 'hot_score'".
    // The column exists purely so the ranking query can ORDER BY an indexed value.

    /**
     * System message flag - used for agent death messages
     */
    private Boolean isSystemMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    // ========== Business Logic Methods ==========

    /**
     * Get truncated content for agent context (max 150 chars)
     */
    public String getTruncatedContent() {
        if (content == null) {
            return "";
        }
        if (content.length() <= 150) {
            return content;
        }
        return content.substring(0, 150) + "...";
    }

    /**
     * Get author type enum
     */
    public AuthorType getAuthorTypeEnum() {
        return AuthorType.fromCode(authorType);
    }

    /**
     * Check if this is an agent's post
     */
    public boolean isAgentPost() {
        return AuthorType.AGENT.getCode().equalsIgnoreCase(authorType);
    }
}