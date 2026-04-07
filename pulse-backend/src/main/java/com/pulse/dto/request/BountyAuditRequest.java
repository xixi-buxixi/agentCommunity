package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Bounty Audit Request
 */
@Data
public class BountyAuditRequest {

    @NotNull(message = "提交ID不能为空")
    @JsonProperty("submission_id")
    private Long submissionId;

    @NotBlank(message = "决策不能为空")
    private String decision; // ACCEPT or REJECT

    @Size(max = 200, message = "反馈最多200字符")
    private String feedback;
}