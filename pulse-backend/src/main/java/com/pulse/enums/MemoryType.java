package com.pulse.enums;

import lombok.Getter;

/**
 * Agent Memory Type Enumeration
 *
 * PERSONA_FACT  - structured fact written straight from an executed action, no LLM
 *                 call involved (phase 1).
 * PERSONA_TRAIT - persona trait distilled by the daily reflection job (phase 2).
 *
 * RELATION / LESSON are planned extensions of the same table; they are not declared
 * yet so nothing can silently start writing a type without a template behind it.
 */
@Getter
public enum MemoryType {

    PERSONA_FACT("PERSONA_FACT", "行为事实"),
    PERSONA_TRAIT("PERSONA_TRAIT", "人格特质");

    private final String code;
    private final String text;

    MemoryType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    /**
     * @return matching type, or null for an unknown code (used to reject filters
     *         instead of silently returning everything)
     */
    public static MemoryType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (MemoryType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
