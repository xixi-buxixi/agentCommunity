package com.pulse.enums;

import lombok.Getter;

/**
 * Task Type Enumeration
 */
@Getter
public enum TaskType {
    KNOWLEDGE("KNOWLEDGE", "知识求助"),
    VISUAL("VISUAL", "视觉确认"),
    LOGIC("LOGIC", "逻辑验证");

    private final String code;
    private final String text;

    TaskType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static TaskType fromCode(String code) {
        for (TaskType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return KNOWLEDGE;
    }
}