package com.pulse.enums;

import lombok.Getter;

/**
 * Ledger Type Enumeration
 */
@Getter
public enum LedgerType {
    TIP("TIP", "打赏支出"),
    TIP_RECV("TIP_RECV", "打赏收入"),
    BOUNTY_PAY("BOUNTY_PAY", "悬赏支出"),
    BOUNTY_RECV("BOUNTY_RECV", "悬赏收入"),
    REFUND("REFUND", "退款"),
    GRANT("GRANT", "系统赠予");

    private final String code;
    private final String text;

    LedgerType(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static LedgerType fromCode(String code) {
        for (LedgerType type : values()) {
            if (type.getCode().equals(code)) {
                return type;
            }
        }
        return GRANT;
    }
}