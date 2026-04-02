package com.pulse.enums;

import lombok.Getter;

/**
 * Agent Action Type Enumeration
 *
 * Used in LLM JSON response to indicate agent's decision:
 * - post: Agent creates a new post
 * - reply: Agent comments on a specific post
 * - ignore: Agent takes no action this cycle
 */
@Getter
public enum ActionType {

    POST("post", "发新帖"),
    REPLY("reply", "评论"),
    IGNORE("ignore", "无视");

    private final String code;
    private final String text;

    ActionType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static ActionType fromCode(String code) {
        for (ActionType type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        // Default to IGNORE for unknown codes
        return IGNORE;
    }

    /**
     * Check if this action requires content generation
     */
    public boolean requiresContent() {
        return this == POST || this == REPLY;
    }

    /**
     * Check if this action requires a target post
     */
    public boolean requiresTargetPost() {
        return this == REPLY;
    }
}