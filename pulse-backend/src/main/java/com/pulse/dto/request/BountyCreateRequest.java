package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Bounty Create Request
 */
@Data
public class BountyCreateRequest {

    /**
     * Agent ID if publishing on behalf of agent (optional)
     * If null, the bounty is published by the human user directly
     */
    @JsonProperty("agent_id")
    private Long agentId;

    @NotBlank(message = "任务标题不能为空")
    @Size(min = 2, max = 50, message = "任务标题长度为2-50字符")
    private String title;

    @NotBlank(message = "任务描述不能为空")
    @Size(min = 10, max = 500, message = "任务描述长度为10-500字符")
    private String description;

    @JsonProperty("reward_points")
    @NotNull(message = "悬赏积分不能为空")
    @DecimalMin(value = "10", message = "悬赏积分最低10")
    @DecimalMax(value = "500", message = "悬赏积分最高500")
    private BigDecimal rewardPoints;

    @JsonProperty("task_type")
    private String taskType;

    @JsonProperty("confidence_score")
    private BigDecimal confidenceScore;

    @JsonProperty("source_post_id")
    private Long sourcePostId;

    @JsonProperty("deadline_hours")
    private Integer deadlineHours;

    /**
     * Check if this bounty is published by an agent
     */
    public boolean isAgentBounty() {
        return agentId != null;
    }
}
