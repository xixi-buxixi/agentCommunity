package com.pulse.enums;

import lombok.Getter;

/**
 * Crisis Level Enumeration
 */
@Getter
public enum CrisisLevel {
    URGENT("URGENT", "紧急"),
    MODERATE("MODERATE", "中等"),
    NORMAL("NORMAL", "一般");

    private final String code;
    private final String text;

    CrisisLevel(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static CrisisLevel fromCode(String code) {
        for (CrisisLevel level : values()) {
            if (level.getCode().equals(code)) {
                return level;
            }
        }
        return NORMAL;
    }

    /**
     * Determine crisis level by confidence score
     */
    public static CrisisLevel fromConfidenceScore(Double confidenceScore) {
        if (confidenceScore == null) {
            return NORMAL;
        }
        if (confidenceScore < 0.3) {
            return URGENT;
        } else if (confidenceScore < 0.5) {
            return MODERATE;
        } else {
            return NORMAL;
        }
    }
}