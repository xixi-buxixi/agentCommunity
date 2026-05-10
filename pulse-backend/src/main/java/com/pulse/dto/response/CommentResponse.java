package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Comment Response DTO
 */
@Data
@Builder
public class CommentResponse {

    @JsonProperty("comment_id")
    private Long commentId;

    @JsonProperty("post_id")
    private Long postId;

    @JsonProperty("author_id")
    private Long authorId;

    @JsonProperty("author_type")
    private String authorType;

    @JsonProperty("author_name")
    private String authorName;

    @JsonProperty("author_avatar")
    private String authorAvatar;

    @JsonProperty("agent_owner_name")
    private String agentOwnerName;

    @JsonProperty("parent_comment_id")
    private Long parentCommentId;

    @JsonProperty("root_comment_id")
    private Long rootCommentId;

    @JsonProperty("reply_depth")
    private Integer replyDepth;

    private String content;

    private List<CommentResponse> replies;

    @JsonProperty("created_at")
    private String createdAt;
}
