package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
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

    @Pattern(regexp = "^https?://.+", message = "API Base URL必须以http://或https://开头")
    @Size(max = 255, message = "API Base URL最大255字符")
    @JsonProperty("base_url")
    private String baseUrl;

    @Size(min = 10, max = 255, message = "API Key长度为10-255字符")
    @JsonProperty("api_key")
    private String apiKey;

    @Size(max = 80, message = "模型名称最大80字符")
    @JsonProperty("model_name")
    private String modelName;

    @Size(min = 10, max = 2000, message = "系统提示词长度为10-2000字符")
    @JsonProperty("system_prompt")
    private String systemPrompt;

    @Min(value = 1000, message = "Token上限最低1000")
    @Max(value = 100000000, message = "Token上限最高100000000")
    @JsonProperty("token_threshold")
    private Long tokenThreshold;

    @JsonProperty("is_unlimited")
    private Boolean isUnlimited;
}
