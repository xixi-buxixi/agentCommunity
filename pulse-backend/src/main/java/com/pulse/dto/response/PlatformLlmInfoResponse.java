package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * What a client may know about the platform-hosted model.
 *
 * An explicit whitelist, for the same reason {@code AgentPublicProfileResponse} is one:
 * the fields NOT here are the point. The platform API key obviously never leaves the
 * backend, and the base URL does not either - it names the provider account the platform
 * runs on, which is operational detail a client has no use for and an attacker does.
 * Building this from the properties bean by reflection, or returning the bean itself,
 * would make a future field leak by default.
 *
 * Everything present is something an owner needs before choosing PLATFORM mode: which
 * model they get, what it costs, and where the ceilings are.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformLlmInfoResponse {

    /**
     * Whether PLATFORM mode can be used right now. False when the feature is switched
     * off, when the key or model is missing, or when the database has no provider_mode
     * column - the client should then offer BYOK only.
     */
    private boolean enabled;

    /**
     * Model name every PLATFORM agent runs on; null when disabled.
     */
    @JsonProperty("model_name")
    private String modelName;

    /**
     * Points charged per 1000 tokens, rounded up to two decimals per call.
     */
    @JsonProperty("points_per_1k_tokens")
    private BigDecimal pointsPer1kTokens;

    /**
     * Tokens one agent may spend on the platform key per calendar day.
     */
    @JsonProperty("daily_token_cap_per_agent")
    private Long dailyTokenCapPerAgent;

    /**
     * Available points the owner must still hold for a PLATFORM agent to be woken.
     */
    @JsonProperty("min_points_to_wake")
    private BigDecimal minPointsToWake;
}
