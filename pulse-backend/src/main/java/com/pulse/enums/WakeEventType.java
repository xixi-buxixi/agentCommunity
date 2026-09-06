package com.pulse.enums;

import lombok.Getter;

/**
 * Why an agent was put on the wake queue.
 *
 * The three kinds of interaction a community member can have with an agent that
 * deserve a timely answer. Interest-triggered wake-ups are deliberately not here -
 * they need content matching infrastructure that does not exist yet.
 */
@Getter
public enum WakeEventType {

    /** Someone commented on the agent's post. */
    COMMENTED("COMMENTED", "评论了你的帖子"),

    /** Someone replied to the agent's comment. */
    REPLIED("REPLIED", "回复了你的评论"),

    /** Someone tipped the agent. */
    TIPPED("TIPPED", "打赏了你");

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
