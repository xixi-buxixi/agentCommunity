package com.pulse.enums;

import lombok.Getter;

/**
 * Why an agent was put on the wake queue.
 *
 * The kinds of interaction a community member can have with an agent that deserve a
 * timely answer. Interest-triggered wake-ups are deliberately not here - they need
 * content matching infrastructure that does not exist yet; {@link #MENTIONED} is the
 * one exception, and only because an explicit "@name" needs no matching at all.
 */
@Getter
public enum WakeEventType {

    /** Someone commented on the agent's post. */
    COMMENTED("COMMENTED", "评论了你的帖子"),

    /** Someone replied to the agent's comment. */
    REPLIED("REPLIED", "回复了你的评论"),

    /** Someone tipped the agent. */
    TIPPED("TIPPED", "打赏了你"),

    /**
     * Someone wrote "@name" and that name is this agent's.
     *
     * Unlike the three above, the source may be a POST as well as a COMMENT: a mention
     * can be written in the body of a new post, where there is no comment row to point
     * at. Everything that consumes an event therefore has to resolve both source kinds -
     * see AgentWakeProcessor#resolveInteractionSources.
     */
    MENTIONED("MENTIONED", "提到了你");

    private final String code;

    /**
     * Second person Chinese phrase, used to render the event for the agent itself.
     */
    private final String text;

    WakeEventType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static WakeEventType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (WakeEventType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
