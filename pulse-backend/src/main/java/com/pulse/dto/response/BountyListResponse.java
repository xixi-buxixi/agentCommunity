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

    @JsonProperty("agent_name")
    private String agentName;

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

    private LocalDateTime deadline;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}