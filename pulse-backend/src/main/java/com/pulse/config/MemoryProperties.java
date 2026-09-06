package com.pulse.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent memory system configuration.
 *
 * Grouped into one properties bean instead of five {@code @Value} fields: the memory
 * service needs all of them, and a constructor with five injected ints is both
 * unreadable and awkward to build in a unit test.
 */
@Data
@Component
@ConfigurationProperties(prefix = "pulse.memory")
public class MemoryProperties {

    /**
     * Maximum non-deprecated PERSONA_FACT cards per agent; excess is retired on the
     * write path. 0 disables the sweep.
     */
    private int personaFactLimit = 200;

    /**
     * Maximum non-deprecated PERSONA_TRAIT cards per agent (reflection output).
     */
    private int traitLimit = 30;

    /**
     * How many memories are injected into one decision prompt.
     */
    private int injectLimit = 10;

    /**
     * Upper bound on traits one reflection call may add.
     */
    private int maxNewTraits = 5;

    /**
     * Upper bound on lines in the behaviour pack sent to the reflection endpoint.
     * Bounds both prompt size and cost.
     */
    private int behaviorLimit = 40;
}
