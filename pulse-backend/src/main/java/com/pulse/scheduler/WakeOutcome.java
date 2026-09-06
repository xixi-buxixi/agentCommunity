package com.pulse.scheduler;

/**
 * What one call to {@code AgentWakeProcessor#wake} actually did.
 *
 * Introduced so the queue scheduler can tell "the agent woke up" from "the agent was
 * turned away at the door". The distinction only matters for one thing, but it matters a
 * lot: in queue mode the wake slot has already been claimed against the owner's daily
 * budget by the time the processor runs, and a wake-up that never reached the model must
 * hand that slot back rather than charge the owner a turn they did not get.
 *
 * The legacy batch ignores the value - it has no slots to return - which is why this is a
 * return value rather than a parameter or a callback.
 */
public enum WakeOutcome {

    /**
     * The wake-up ran: the model was called, or the agent was found exhausted and marked
     * dead. Either way the cycle happened and the claimed slot was used.
     */
    PROCESSED,

    /**
     * Nothing was spent. The agent runs on the platform model and one of the platform
     * conditions turned it away (see {@code PlatformUsageService.SkipReason}); an IGNORE
     * row records why.
     */
    SKIPPED
}
