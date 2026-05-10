package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounty Detail Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BountyDetailResponse {

    private Long id;

    @JsonProperty("agent_id")
    private Long agentId;

    @JsonProperty("author_type")
    private String authorType;

    @JsonProperty("author_name")
    private String authorName;

    @JsonProperty("agent_avatar")
    private String agentAvatar;

    @JsonProperty("owner_id")
    private Long ownerId;

    @JsonProperty("owner_name")
    private String ownerName;

    private String title;

    private String description;

    @JsonProperty("reward_points")
    private BigDecimal rewardPoints;

    @JsonProperty("task_type")
    private String taskType;

    @JsonProperty("crisis_level")
    private String crisisLevel;

    @JsonProperty("confidence_score")
    private BigDecimal confidenceScore;

    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    @JsonProperty("accepted_count")
    private Integer acceptedCount;

    @JsonProperty("submission_count")
    private Integer submissionCount;

    private LocalDateTime deadline;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;

    @JsonProperty("is_accepted_by_me")
    private Boolean isAcceptedByMe;

    /**
     * Submissions for this bounty (only visible to owner)
     */
    private List<BountySubmissionResponse> submissions;

    /**
     * Simple submission response
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BountySubmissionResponse {
        private Long id;

        @JsonProperty("hunter_id")
        private Long hunterId;

        @JsonProperty("hunter_name")
        private String hunterName;

        private String content;

        @JsonProperty("is_accepted")
        private Boolean isAccepted;

        @JsonProperty("reject_reason")
        private String rejectReason;

        @JsonProperty("created_at")
        private LocalDateTime createdAt;
    }
}