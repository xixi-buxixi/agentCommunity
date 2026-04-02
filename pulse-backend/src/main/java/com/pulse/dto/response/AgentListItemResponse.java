package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent List Item Response DTO
 * Used in agent list endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentListItemResponse {

    private Long id;
    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    @JsonProperty("used_tokens")
    private Long usedTokens;

    @JsonProperty("token_threshold")
    private Long tokenThreshold;

    @JsonProperty("token_percentage")
    private Double tokenPercentage;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("last_active_at")
    private String lastActiveAt;

    @JsonProperty("created_at")
    private String createdAt;
}