package com.pulse.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;

/**
 * Platform-hosted model configuration: the credentials a PLATFORM agent runs on and the
 * spending caps that make offering them affordable.
 *
 * The key never leaves this bean. It is read by {@code LlmCredentialResolver} on its way
 * into the AI gateway request and by {@link SecretsValidator} for the placeholder check;
 * nothing serialises it, and {@link #describe()} exists so the startup log can say
 * whether it is configured without saying what it is. The templates endpoint reports the
 * caps and the model name only - never the key, never the base URL, because a base URL is
 * itself an operational detail of the platform account.
 *
 * Off by default. A deployment that enables it without a key or a model name is reported
 * at WARN and treated as unavailable rather than failing startup: this is one optional
 * feature, and refusing to boot over it would turn a configuration gap into an outage
 * for every BYOK agent as well.
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "platform-llm")
public class PlatformLlmProperties {

    /**
     * Master switch. False means the platform offers no hosted model at all: creating a
     * PLATFORM agent is refused with PLATFORM_MODEL_UNAVAILABLE and existing ones are
     * skipped at wake-up instead of falling back to anything.
     */
    private boolean enabled = false;

    /**
     * The platform's own provider key, from the environment. Never defaulted, never
     * logged, never returned by any endpoint.
     */
    private String apiKey = "";

    /**
     * Provider endpoint the platform key belongs to.
     */
    private String baseUrl = "https://api.openai.com/v1";

    /**
     * Model every PLATFORM agent runs on. Reported to the client so an owner knows what
     * they are getting; it is not a secret.
     */
    private String modelName = "";

    /**
     * Points charged per 1000 tokens. BigDecimal rather than double because it multiplies
     * a balance: a rate of 0.15 that is really 0.1499999 drifts a ledger.
     */
    private BigDecimal pointsPer1kTokens = BigDecimal.ONE;

    /**
     * Tokens one agent may spend on the platform key per day. The per-agent brake: it
     * bounds what a single runaway persona can cost before anyone notices.
     */
    private long dailyTokenCapPerAgent = 50000L;

    /**
     * Tokens every PLATFORM agent may spend together per day. The platform-wide brake,
     * and the number that actually bounds the bill.
     */
    private long dailyTokenCapGlobal = 2000000L;

    /**
     * Available points an owner must still hold for their PLATFORM agent to be woken.
     *
     * Deliberately a floor rather than "enough for this call": the cost is only known
     * after the call, so the choice is between refusing at zero and letting a call happen
     * that cannot be paid for. Refusing early also gives the owner a notification while
     * they can still act on it.
     */
    private BigDecimal minPointsToWake = BigDecimal.ONE;

    /**
     * Whether a PLATFORM call can actually be made right now.
     *
     * Enabled alone is not enough - a switch turned on against an empty key would make
     * every wake-up fail at the gateway instead of being skipped cleanly here.
     */
    public boolean isUsable() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && modelName != null && !modelName.isBlank();
    }

    /**
     * Rate, never null and never negative: a misconfigured negative rate would pay owners
     * for spending platform tokens.
     */
    public BigDecimal effectivePointsPer1kTokens() {
        if (pointsPer1kTokens == null || pointsPer1kTokens.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return pointsPer1kTokens;
    }

    public BigDecimal effectiveMinPointsToWake() {
        if (minPointsToWake == null || minPointsToWake.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return minPointsToWake;
    }

    @PostConstruct
    public void report() {
        if (!enabled) {
            log.info("Platform-hosted model is disabled; every agent must bring its own key");
            return;
        }
        if (!isUsable()) {
            log.warn("PLATFORM_LLM_ENABLED is true but {} is not configured; the platform model is "
                            + "treated as unavailable - creating a PLATFORM agent is refused and "
                            + "existing ones are skipped at wake-up",
                    (apiKey == null || apiKey.isBlank()) ? "PLATFORM_LLM_API_KEY" : "PLATFORM_LLM_MODEL");
            return;
        }
        log.info("Platform-hosted model enabled: model={}, key={}, rate={} points/1k tokens, "
                        + "caps={}/agent/day and {}/day overall, minPoints={}",
                modelName, describe(), effectivePointsPer1kTokens(),
                dailyTokenCapPerAgent, dailyTokenCapGlobal, effectiveMinPointsToWake());
    }

    /**
     * Redacted description of the key, for logs. Length only - the same stance as
     * {@link SecretsValidator}, which prints byte counts and never values.
     */
    public String describe() {
        return apiKey == null || apiKey.isBlank() ? "absent" : "configured(" + apiKey.length() + " chars)";
    }
}
