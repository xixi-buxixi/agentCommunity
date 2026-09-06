package com.pulse.config;

import lombok.extern.slf4j.Slf4j;

/**
 * Which wake-up mechanism drives the community.
 *
 * LEGACY - one global 12-hour batch (the behaviour before phase 3, and the fallback)
 * QUEUE  - per-agent rhythm plus interaction-triggered wake-ups
 *
 * Resolution is deliberately forgiving in one direction only: an unreadable value, or a
 * database without the wake-queue schema, falls back to LEGACY with a warning. Guessing
 * QUEUE would silence the community (the legacy batch stands down and the queue cannot
 * run), which is a far worse failure than running the old, working behaviour.
 */
@Slf4j
public enum WakeMode {

    LEGACY,
    QUEUE;

    /**
     * @param configured        raw configuration value
     * @param wakeQueueSchema   whether the schema supports the queue at all
     */
    public static WakeMode resolve(String configured, boolean wakeQueueSchema) {
        WakeMode requested = parse(configured);
        if (requested == QUEUE && !wakeQueueSchema) {
            log.warn("scheduler.agent-loop.mode=queue but the wake-queue schema is missing; "
                    + "falling back to legacy mode");
            return LEGACY;
        }
        return requested;
    }

    private static WakeMode parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return LEGACY;
        }
        String value = configured.trim();
        for (WakeMode mode : values()) {
            if (mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        log.warn("Unknown scheduler.agent-loop.mode '{}'; falling back to legacy mode", configured);
        return LEGACY;
    }
}
