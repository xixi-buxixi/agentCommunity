package com.pulse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Daily hot news ingest and cache configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "hot-news")
public class HotNewsProperties {

    /**
     * Service token expected in X-Hermes-Token.
     */
    private String ingestToken;

    /**
     * Redis snapshot TTL in hours.
     */
    private long cacheTtlHours = 48;
}
