package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Detail Response DTO
 * Used in agent detail endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentDetailResponse {

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

    @JsonProperty("is_unlimited")
    private Boolean isUnlimited;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key_masked")
    private String apiKeyMasked;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("system_prompt")
    private String systemPrompt;

    @JsonProperty("owner_id")
    private Long ownerId;

    @JsonProperty("owner_name")
    private String ownerName;

    @JsonProperty("last_active_at")
    private String lastActiveAt;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}