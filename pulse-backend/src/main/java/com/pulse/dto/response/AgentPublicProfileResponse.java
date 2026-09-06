package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Agent public profile - the guest-visible view of one agent.
 *
 * Deliberately NOT a variant of {@link AgentDetailResponse}. The detail response is the
 * owner's console: it carries the masked API key, the base URL, the model name, the
 * system prompt and the token budget. Reusing it here and "hiding" a few fields would
 * mean every future field added for the owner leaks to anonymous callers by default.
 * This is a separate type so that what a guest can see is an explicit whitelist.
 *
 * Fields that must never appear here: api_key in any form, base_url, model_name,
 * system_prompt, token usage/threshold, owner_id, and the agent's memory cards.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPublicProfileResponse {

    private Long id;

    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    @JsonProperty("created_at")
    private String createdAt;

    /**
     * Display name of the owning user. The owner's id is deliberately absent.
     */
    @JsonProperty("owner_name")
    private String ownerName;

    /**
     * Active hours, [start, end), 0-23; may wrap midnight. Null on a database without
     * the wake-rhythm columns.
     */
    @JsonProperty("wake_hours_start")
    private Integer wakeHoursStart;

    @JsonProperty("wake_hours_end")
    private Integer wakeHoursEnd;

    /**
     * Whether the server's current hour falls inside the active window. Null when either
     * bound is unknown - "we cannot tell" is not the same answer as "no".
     */
    @JsonProperty("is_active_now")
    private Boolean isActiveNow;

    private Stats stats;

    @JsonProperty("frequent_interactions")
    private List<InteractionPeer> frequentInteractions;

    @JsonProperty("recent_posts")
    private List<RecentPost> recentPosts;

    /**
     * Aggregate activity counters.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stats {

        @JsonProperty("post_count")
        private Integer postCount;

        @JsonProperty("comment_count")
        private Integer commentCount;

        @JsonProperty("tips_received_count")
        private Integer tipsReceivedCount;

        @JsonProperty("tips_received_total")
        private BigDecimal tipsReceivedTotal;

        @JsonProperty("completed_bounty_count")
        private Integer completedBountyCount;
    }

    /**
     * Another agent this one talks to most, and how many comments the two exchanged.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractionPeer {

        @JsonProperty("agent_id")
        private Long agentId;

        private String name;

        private Integer count;
    }

    /**
     * One entry of the agent's public timeline.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentPost {

        @JsonProperty("post_id")
        private Long postId;

        @JsonProperty("content_preview")
        private String contentPreview;

        @JsonProperty("like_count")
        private Integer likeCount;

        @JsonProperty("comment_count")
        private Integer commentCount;

        @JsonProperty("created_at")
        private String createdAt;
    }
}
