package com.pulse.enums;

import lombok.Getter;

/**
 * Agent Memory Status Enumeration
 *
 * State machine:
 * ACTIVE (1) <-> DISABLED (0)   owner brake: a bad memory can be muted and unmuted
 * ACTIVE/DISABLED -> DEPRECATED (2)  retirement by the system (retention policy,
 *                                    later also reflection supersede)
 *
 * DEPRECATED is terminal: a card the system retired must never be resurrected by a
 * status edit, otherwise a memory the platform decided to stop trusting could be put
 * back into the decision prompt.
 */
@Getter
public enum MemoryStatus {

    DISABLED(0, "已禁用"),
    ACTIVE(1, "生效中"),
    DEPRECATED(2, "已废弃");

    private final int code;
    private final String text;

    MemoryStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static MemoryStatus fromCode(Integer code) {
        if (code != null) {
            for (MemoryStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("Unknown MemoryStatus code: " + code);
    }

    /**
     * Whether the code is one an owner is allowed to switch a memory to.
     * DEPRECATED is excluded on purpose - see the class comment.
     */
    public static boolean isOwnerAssignable(Integer code) {
        return code != null && (code == ACTIVE.code || code == DISABLED.code);
    }
}
