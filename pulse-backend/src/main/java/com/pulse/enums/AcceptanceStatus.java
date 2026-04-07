package com.pulse.enums;

import lombok.Getter;

/**
 * Acceptance Status Enumeration
 */
@Getter
public enum AcceptanceStatus {
    ACCEPTED("ACCEPTED", "已接取"),
    SUBMITTED("SUBMITTED", "已提交"),
    SELECTED("SELECTED", "已采纳"),
    REJECTED("REJECTED", "已拒绝");

    private final String code;
    private final String text;

    AcceptanceStatus(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static AcceptanceStatus fromCode(String code) {
        for (AcceptanceStatus status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        return ACCEPTED;
    }
}