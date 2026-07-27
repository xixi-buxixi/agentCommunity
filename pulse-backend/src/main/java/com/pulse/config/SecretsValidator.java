package com.pulse.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Startup validation for injected secrets.
 *
 * A secret with a default value committed to the repository is a public secret:
 * the JWT key would let anyone mint tokens for any account, and the AES key would
 * decrypt every stored LLM API key. Placeholders are therefore rejected at startup
 * instead of silently running a compromised deployment.
 */
@Slf4j
@Component
public class SecretsValidator {

    /**
     * Values that have appeared in the repository (or in a sample .env) and must
     * never reach a running instance.
     */
    private static final Set<String> PUBLIC_PLACEHOLDERS = Set.of(
            "change_me",
            "changeme",
            "PulseSecretKey2026ForAgentCommunityMustBe256BitsOrLonger!",
            "PulseAES256SecretKey!",
            "change_this_to_your_secure_jwt_secret_at_least_32_chars",
            "change_this_to_your_aes_secret_key",
            "change_this_to_a_long_random_token",
            "your_mysql_password_here",
            "your_mysql_root_password"
    );

    private static final int MIN_JWT_SECRET_BYTES = 32;
    private static final int MIN_AES_SECRET_LENGTH = 16;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${aes.secret-key}")
    private String aesSecretKey;

    @Value("${hot-news.ingest-token}")
    private String hermesIngestToken;

    @Value("${pulse.trusted-proxies:}")
    private String trustedProxies;

    @PostConstruct
    public void validate() {
        requireStrongSecret("JWT_SECRET (jwt.secret)", jwtSecret);
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_JWT_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_JWT_SECRET_BYTES + " bytes for HMAC-SHA256");
        }

        requireStrongSecret("AES_SECRET (aes.secret-key)", aesSecretKey);
        if (aesSecretKey.length() < MIN_AES_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "AES_SECRET must be at least " + MIN_AES_SECRET_LENGTH + " characters");
        }

        // The ingest token is shared with an external publisher (Hermes). Rotating it
        // unilaterally would stop the daily report, so a weak value is reported loudly
        // instead of aborting startup.
        if (isPlaceholder(hermesIngestToken)) {
            log.error("SECURITY: HERMES_INGEST_TOKEN is still a public placeholder value. "
                    + "Anyone can push daily-report content until it is rotated on both sides.");
        }

        // Redacted resolution summary.
        //
        // The production outage during this rollout was a deployment script that
        // silently exported no environment variables at all: the JVM then died on
        // "Could not resolve placeholder", with nothing in the log about what had
        // been resolved. Printing lengths (never values) makes that failure mode
        // obvious at a glance next time.
        log.info("Secret validation passed (jwt={} bytes, aes={} chars, ingest-token={}, trusted-proxies={})",
                jwtSecret.getBytes(StandardCharsets.UTF_8).length,
                aesSecretKey.length(),
                isPlaceholder(hermesIngestToken) ? "PLACEHOLDER" : "configured",
                trustedProxies == null || trustedProxies.isBlank() ? "none (loopback/private only)" : "configured");
    }

    private void requireStrongSecret(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be provided (no default value exists)");
        }
        if (isPlaceholder(value)) {
            throw new IllegalStateException(name + " is set to a publicly known placeholder value. "
                    + "Generate a unique secret, e.g. `openssl rand -hex 32`.");
        }
    }

    private boolean isPlaceholder(String value) {
        return value == null || value.isBlank() || PUBLIC_PLACEHOLDERS.contains(value.trim());
    }
}
