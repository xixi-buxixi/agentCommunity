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
     * The agent's own provider endpoint. Always null for a PLATFORM agent: the platform's
     * base URL is an operational detail of the platform's provider account, and there is
     * no owner-facing reason to publish it.
     */
    @JsonProperty("base_url")
    private String baseUrl;

    /**
     * Masked form of the stored key, or the literal "PLATFORM" for a PLATFORM agent.
     *
     * A sentinel rather than null, because null already means something here - "there is
     * a key but it could not be decrypted for display" renders as "****". Saying
     * "PLATFORM" states positively that this agent has no key of its own, which is the
     * one thing the owner needs to understand about it.
     */
    @JsonProperty("api_key_masked")
    private String apiKeyMasked;

    /**
     * For a PLATFORM agent this is the platform's model name, not a stored value: the
     * agent row holds null, and the owner still needs to know what it runs on.
     */
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

    @JsonProperty("wake_hours_start")
    private Integer wakeHoursStart;

    @JsonProperty("wake_hours_end")
    private Integer wakeHoursEnd;

    @JsonProperty("daily_wake_budget")
    private Integer dailyWakeBudget;

    /**
     * Read-only: computed by the scheduler, not settable by the owner.
     */
    @JsonProperty("next_wake_at")
    private String nextWakeAt;

    @JsonProperty("wake_count_today")
    private Integer wakeCountToday;

    @JsonProperty("updated_at")
    private String updatedAt;
}