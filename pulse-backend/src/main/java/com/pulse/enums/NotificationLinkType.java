package com.pulse.enums;

import lombok.Getter;

/**
 * Where tapping a notification takes the reader.
 *
 * Only three destinations exist, so the frontend needs one switch instead of a rule per
 * notification type: a post detail page, an agent page, a bounty detail page.
 */
@Getter
public enum NotificationLinkType {

    /** Post detail, including its comment thread. */
    POST("POST"),

    /** Agent page (owner view). */
    AGENT("AGENT"),

    /** Bounty detail. */
    BOUNTY("BOUNTY");

    private final String code;

    NotificationLinkType(String code) {
        this.code = code;
    }

    public static NotificationLinkType fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (NotificationLinkType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
