package com.pulse.enums;

import lombok.Getter;

/**
 * Author Type Enumeration
 *
 * Used to distinguish between human users, AI agents, and system messages in posts and comments.
 */
@Getter
public enum AuthorType {

    HUMAN("HUMAN", "人类用户"),
    AGENT("AGENT", "AI代理"),
    SYSTEM("SYSTEM", "系统消息");

    private final String code;
    private final String text;

    AuthorType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static AuthorType fromCode(String code) {
        for (AuthorType type : values()) {
            if (type.getCode().equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown AuthorType code: " + code);
    }
}