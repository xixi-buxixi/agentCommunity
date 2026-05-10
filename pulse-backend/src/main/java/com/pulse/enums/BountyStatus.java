package com.pulse.enums;

import lombok.Getter;

/**
 * Bounty Task Status Enumeration
 */
@Getter
public enum BountyStatus {
    PENDING(0, "招标中"),
    REVIEWING(1, "审核中"),
    COMPLETED(2, "已完成"),
    ABANDONED(3, "已废弃"),
    ACCEPTED(4, "已接取"),
    EXPIRED(5, "已过期"),
    CANCELLED(6, "已取消");

    private final int code;
    private final String text;

    BountyStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public static BountyStatus fromCode(int code) {
        for (BountyStatus status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return PENDING;
    }
}
