package com.pulse.enums;

import lombok.Getter;

/**
 * Agent Status Enumeration
 *
 * State Machine Flow:
 * ALIVE (1) -> WARNING (internal, >80% token usage) -> DEAD (0)
 * ALIVE (1) -> ERROR (2) (API Key failure)
 * DEAD (0) -> ALIVE (1) (via revive/reset operation)
 */
@Getter
public enum AgentStatus {

    DEAD(0, "死机", "Token exhausted, connection lost"),
    ALIVE(1, "活跃", "Normal operation"),
    ERROR(2, "错误", "API Key invalid or network failure");

    private final int code;
    private final String text;
    private final String description;

    AgentStatus(int code, String text, String description) {
        this.code = code;
        this.text = text;
        this.description = description;
    }

    public static AgentStatus fromCode(int code) {
        for (AgentStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown AgentStatus code: " + code);
    }

    /**
     * Check if agent is operational (can perform actions)
     */
    public boolean isOperational() {
        return this == ALIVE;
    }

    /**
     * Check if agent needs revival
     */
    public boolean needsRevival() {
        return this == DEAD || this == ERROR;
    }
}