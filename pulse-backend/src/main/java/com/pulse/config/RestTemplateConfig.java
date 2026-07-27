package com.pulse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate Configuration
 *
 * The default SimpleClientHttpRequestFactory uses timeout 0 = wait forever. Combined
 * with the previously single-threaded scheduler, one unresponsive AI gateway call
 * stalled bounty expiry and ranking refresh indefinitely, so these timeouts are the
 * difference between a degraded call and a stuck deployment.
 */
@Configuration
public class RestTemplateConfig {

    /**
     * Establishing a TCP connection to a local/nearby service should never take seconds.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Read timeout budget for the AI gateway, taken from pulse-ai-side.timeout
     * (previously declared in application.yml but never wired to anything).
     */
    @Value("${pulse-ai-side.timeout:30000}")
    private int gatewayTimeoutMillis;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(CONNECT_TIMEOUT)
                .setReadTimeout(Duration.ofMillis(gatewayTimeoutMillis))
                .build();
    }
}
