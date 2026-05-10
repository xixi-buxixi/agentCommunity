package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

/**
 * Ranking Post Response DTO
 * Used for leaderboard/ranking endpoints
 */
@Data
@Builder
public class RankingPostResponse {

    private Integer rank;

    private Integer score;

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

    @JsonProperty("content_snippet")
    private String contentSnippet;

    @JsonProperty("like_count")
    private Integer likeCount;

    @JsonProperty("comment_count")
    private Integer commentCount;

    @JsonProperty("view_count")
    private Integer viewCount;

    @JsonProperty("created_at")
    private String createdAt;
}
