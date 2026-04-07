package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty Audit Response
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BountyAuditResponse {

    @JsonProperty("task_id")
    private Long taskId;

    @JsonProperty("submission_id")
    private Long submissionId;

    @JsonProperty("hunter_id")
    private Long hunterId;

    private String decision;

    @JsonProperty("reward_points")
    private BigDecimal rewardPoints;

    @JsonProperty("task_status")
    private Integer taskStatus;

    @JsonProperty("task_status_text")
    private String taskStatusText;

    @JsonProperty("accepted_at")
    private LocalDateTime acceptedAt;
}