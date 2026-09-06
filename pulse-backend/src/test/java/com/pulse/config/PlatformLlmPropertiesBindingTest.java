package com.pulse.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The property names in application.yml actually bind to the fields.
 *
 * Worth a test rather than a reading, because of one name in particular:
 * {@code points-per-1k-tokens} has a digit where relaxed binding usually capitalises a
 * letter. A name that silently failed to bind would leave the rate at its default of 1
 * point per 1000 tokens - the platform charging the wrong price, with nothing in the log
 * to say so.
 */
class PlatformLlmPropertiesBindingTest {

    @Test
    void everyConfiguredPropertyReachesItsField() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("platform-llm.enabled", "true");
        environment.setProperty("platform-llm.api-key", "sk-platform");
        environment.setProperty("platform-llm.base-url", "https://platform.example/v1");
        environment.setProperty("platform-llm.model-name", "platform-model");
        environment.setProperty("platform-llm.points-per-1k-tokens", "0.30");
        environment.setProperty("platform-llm.daily-token-cap-per-agent", "12345");
        environment.setProperty("platform-llm.daily-token-cap-global", "6789000");
        environment.setProperty("platform-llm.min-points-to-wake", "2.50");

        PlatformLlmProperties properties = new Binder(
                ConfigurationPropertySources.get(environment))
                .bind("platform-llm", PlatformLlmProperties.class)
                .get();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getApiKey()).isEqualTo("sk-platform");
        assertThat(properties.getBaseUrl()).isEqualTo("https://platform.example/v1");
        assertThat(properties.getModelName()).isEqualTo("platform-model");
        assertThat(properties.getPointsPer1kTokens()).isEqualByComparingTo("0.30");
        assertThat(properties.getDailyTokenCapPerAgent()).isEqualTo(12345L);
        assertThat(properties.getDailyTokenCapGlobal()).isEqualTo(6789000L);
        assertThat(properties.getMinPointsToWake()).isEqualByComparingTo("2.50");
        assertThat(properties.isUsable()).isTrue();
    }

    /**
     * The shipped defaults: off, and unusable even if somebody flips only the switch.
     */
    @Test
    void theDefaultsLeaveThePlatformModelOff() {
        PlatformLlmProperties properties = new PlatformLlmProperties();

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.isUsable()).isFalse();
        assertThat(properties.getPointsPer1kTokens()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(properties.getDailyTokenCapPerAgent()).isEqualTo(50000L);
        assertThat(properties.getDailyTokenCapGlobal()).isEqualTo(2000000L);

        properties.setEnabled(true);
        assertThat(properties.isUsable())
                .as("enabled alone must not be enough - the key and model are still empty")
                .isFalse();
    }

    /**
     * A misconfigured negative rate would pay owners for spending platform tokens.
     */
    @Test
    void aNegativeRateIsClampedToZero() {
        PlatformLlmProperties properties = new PlatformLlmProperties();
        properties.setPointsPer1kTokens(new BigDecimal("-5"));
        properties.setMinPointsToWake(new BigDecimal("-1"));

        assertThat(properties.effectivePointsPer1kTokens()).isEqualByComparingTo("0");
        assertThat(properties.effectiveMinPointsToWake()).isEqualByComparingTo("0");
    }

    /**
     * The redacted description is what reaches the startup log, so it must never carry
     * the value.
     */
    @Test
    void theKeyIsDescribedByLengthOnly() {
        PlatformLlmProperties properties = new PlatformLlmProperties();
        properties.setApiKey("sk-platform-secret");

        assertThat(properties.describe()).doesNotContain("sk-platform-secret").contains("18");
    }
}
