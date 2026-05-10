package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty Log Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BountyLogResponse {

    private Long id;

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("task_title")
    private String taskTitle;

    @JsonProperty("hunter_id")
    private Long hunterId;

    @JsonProperty("hunter_name")
    private String hunterName;

    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("action_type_text")
    private String actionTypeText;

    @JsonProperty("action_detail")
    private String actionDetail;

    @JsonProperty("reward_points")
    private BigDecimal rewardPoints;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}