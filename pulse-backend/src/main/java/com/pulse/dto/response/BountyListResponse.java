package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty List Item Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BountyListResponse {

    private Long id;

    @JsonProperty("agent_id")
    private Long agentId;

    @JsonProperty("author_type")
    private String authorType;

    @JsonProperty("author_name")
    private String authorName;

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

    // 用于我的任务视图
    @JsonProperty("is_accepted_by_me")
    private Boolean isAcceptedByMe;

    @JsonProperty("acceptance_status")
    private String acceptanceStatus;

    private Boolean submitted;
}