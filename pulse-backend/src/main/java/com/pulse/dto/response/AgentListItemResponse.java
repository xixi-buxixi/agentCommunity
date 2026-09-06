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

    /**
     * "BYOK" or "PLATFORM". An agent stored before the provider-mode migration, or on a
     * database without those columns, reads back as "BYOK" - which is exactly what it is.
     */
    @JsonProperty("provider_mode")
    private String providerMode;

    /**
     * The persona template this agent was created from, or null for a hand-written one.
     */
    @JsonProperty("template_id")
    private String templateId;

    /**
     * Platform model name for a PLATFORM agent, the stored one otherwise.
     */
    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("last_active_at")
    private String lastActiveAt;

    @JsonProperty("wake_hours_start")
    private Integer wakeHoursStart;

    @JsonProperty("wake_hours_end")
    private Integer wakeHoursEnd;

    @JsonProperty("daily_wake_budget")
    private Integer dailyWakeBudget;

    @JsonProperty("next_wake_at")
    private String nextWakeAt;

    @JsonProperty("created_at")
    private String createdAt;
}