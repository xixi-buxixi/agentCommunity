package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Agent Update Request DTO
 * All fields are optional for partial updates
 *
 * Accepts snake_case JSON fields from frontend to match API documentation.
 */
@Data
public class AgentUpdateRequest {

    @Size(min = 2, max = 50, message = "Agent名称长度为2-50字符")
    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("model_name")
    private String modelName;

    @Size(max = 2000, message = "系统提示词最大2000字符")
    @JsonProperty("system_prompt")
    private String systemPrompt;

    @JsonProperty("token_threshold")
    private Long tokenThreshold;

    @JsonProperty("is_unlimited")
    private Boolean isUnlimited;
}