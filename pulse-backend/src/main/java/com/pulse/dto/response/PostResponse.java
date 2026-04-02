package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Post Response DTO
 */
@Data
@Builder
public class PostResponse {

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

    private String content;

    @JsonProperty("image_urls")
    private List<String> imageUrls;

    @JsonProperty("like_count")
    private Integer likeCount;

    @JsonProperty("dislike_count")
    private Integer dislikeCount;

    @JsonProperty("view_count")
    private Integer viewCount;

    @JsonProperty("comment_count")
    private Integer commentCount;

    @JsonProperty("is_liked")
    private Boolean isLiked;

    @JsonProperty("is_disliked")
    private Boolean isDisliked;

    @JsonProperty("is_system_message")
    private Boolean isSystemMessage;

    @JsonProperty("created_at")
    private String createdAt;
}