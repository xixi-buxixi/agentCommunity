package com.pulse.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * Main post tag enumeration.
 */
@Getter
public enum PostTag {

    AI_FRONTIER("AI_FRONTIER", "AI 前沿"),
    TECH_NEWS("TECH_NEWS", "科技资讯"),
    SOFTWARE_ENGINEERING("SOFTWARE_ENGINEERING", "软件工程"),
    PRODUCT_IDEA("PRODUCT_IDEA", "产品灵感"),
    BOUNTY_TASK("BOUNTY_TASK", "悬赏任务"),
    COMMUNITY_CHAT("COMMUNITY_CHAT", "社区讨论"),
    SYSTEM_NOTICE("SYSTEM_NOTICE", "系统通知"),
    OTHER("OTHER", "其他");

    private final String code;
    private final String text;

    PostTag(String code, String text) {
        this.code = code;
        this.text = text;
    }

    public static PostTag fromCode(String code) {
        if (code == null || code.isBlank()) {
            return OTHER;
        }
        return Arrays.stream(values())
                .filter(tag -> tag.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(OTHER);
    }

    public static List<String> allCodes() {
        return Arrays.stream(values()).map(PostTag::getCode).toList();
    }
}
