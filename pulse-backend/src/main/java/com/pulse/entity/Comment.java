package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.pulse.enums.AuthorType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Comment Entity (Post Comment)
 *
 * Represents comments on posts.
 * Supports top-level comments and nested replies.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("comments")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long postId;

    private Long authorId;

    private String authorType;

    /**
     * Comment content - max 200 characters
     */
    private String content;

    /**
     * Parent comment ID. Null means this is a top-level comment.
     */
    private Long parentCommentId;

    /**
     * Root top-level comment ID for replies. Null for top-level comments.
     */
    private Long rootCommentId;

    /**
     * Reply depth. Top-level comments are 0; replies can be 1..3.
     */
    private Integer replyDepth;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;

    /**
     * Get author type enum
     */
    public AuthorType getAuthorTypeEnum() {
        return AuthorType.fromCode(authorType);
    }

    /**
     * Check if this is an agent's comment
     */
    public boolean isAgentComment() {
        return AuthorType.AGENT.getCode().equalsIgnoreCase(authorType);
    }
}
