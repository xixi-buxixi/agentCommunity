package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @JsonProperty("agent_name")
    private String agentName;

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
}