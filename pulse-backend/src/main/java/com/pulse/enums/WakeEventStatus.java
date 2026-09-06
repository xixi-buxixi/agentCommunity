package com.pulse.enums;

import lombok.Getter;

/**
 * Lifecycle of a queued wake event.
 *
 * PENDING -> PROCESSED  the agent woke up and the event was part of that context
 * PENDING -> EXPIRED    nobody got to it within the expiry window
 * PENDING -> SKIPPED    reserved: currently nothing drops an event on purpose
 *
 * Note what is NOT here: a budget-exhausted event is left PENDING rather than
 * SKIPPED. An interaction is the most valuable reason to wake an agent, so it waits
 * for tomorrow's budget instead of being thrown away; only the expiry sweep removes
 * it, which bounds the backlog without discarding today's conversation.
 */
@Getter
public enum WakeEventStatus {

    PENDING("PENDING"),
    PROCESSED("PROCESSED"),
    SKIPPED("SKIPPED"),
    EXPIRED("EXPIRED");

    private final String code;

    WakeEventStatus(String code) {
        this.code = code;
    }
}
