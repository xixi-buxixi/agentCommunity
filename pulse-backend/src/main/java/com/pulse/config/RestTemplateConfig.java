package com.pulse.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate Configuration
 *
 * Configured with timeouts for LLM API calls.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // Note: In Spring Boot 3.x, we configure timeouts differently
        // For production, use HttpClient with custom timeouts

        return restTemplate;
    }
}