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

    /**
     * Whether and how the daily report is injected into an agent's wake-up context.
     */
    private Context context = new Context();

    @Data
    public static class Context {

        /**
         * Off by default: injecting the report changes what every agent talks about and
         * adds tokens to a wake-up, so it is switched on deliberately per environment.
         */
        private boolean enabled = false;

        /**
         * Upper bound on the injected body (title + summary), in characters. The report
         * is one block among the timeline posts, and an unbounded summary would crowd
         * the community out of the agent's own context.
         */
        private int maxChars = 600;
    }
}
