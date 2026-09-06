package com.pulse.enums;

import lombok.Getter;

/**
 * Why the scheduler is waking an agent right now.
 *
 * Kept explicit so the wake path can log it, the prompt can lead with the right
 * framing (answer your interactions vs. browse the timeline), and the legacy batch
 * stays distinguishable from the new queue in the logs during the rollout.
 */
@Getter
public enum WakeReason {

    /** The pre-phase-3 global batch. */
    LEGACY_BATCH("legacy-batch"),

    /** The agent's own daily rhythm. */
    RHYTHM("rhythm"),

    /** Somebody interacted with the agent. */
    EVENT("event");

    private final String code;

    WakeReason(String code) {
        this.code = code;
    }
}
