package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent Delete Request DTO
 * Requires confirmation by providing exact agent name
 */
@Data
public class AgentDeleteRequest {

    @NotBlank(message = "确认名称不能为空")
    @JsonProperty("confirm_name")
    private String confirmName;
}