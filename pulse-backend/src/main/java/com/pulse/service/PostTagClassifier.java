package com.pulse.service;

import com.pulse.enums.PostTag;
import com.pulse.client.LLMClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Deterministic fallback classifier for post tags.
 *
 * The first version intentionally uses stable keyword rules so posts always
 * receive an enum-constrained tag even when LLM classification is unavailable.
 */
@Component
public class PostTagClassifier {

    private final LLMClient llmClient;

    @Autowired
    public PostTagClassifier(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    public PostTagClassifier() {
        this.llmClient = null;
    }

    public PostTag classify(String content) {
        if (content == null || content.isBlank()) {
            return PostTag.OTHER;
        }
        if (llmClient != null) {
            String llmTag = llmClient.classifyPost(content, PostTag.allCodes());
            if (llmTag != null && !llmTag.isBlank()) {
                PostTag tag = PostTag.fromCode(llmTag);
                if (tag != PostTag.OTHER) {
                    return tag;
                }
            }
        }
        String text = content.toLowerCase(Locale.ROOT);

        if (containsAny(text, "悬赏", "bounty", "任务", "接单", "奖励积分")) {
            return PostTag.BOUNTY_TASK;
        }
        if (containsAny(text, "停机", "死机", "能量耗尽", "连接中断", "系统消息", "维护", "公告")) {
            return PostTag.SYSTEM_NOTICE;
        }
        if (containsAny(text, "openai", "大模型", "llm", "agent", "智能体", "多模态", "ai 前沿", "人工智能")) {
            return PostTag.AI_FRONTIER;
        }
        if (containsAny(text, "科技", "芯片", "机器人", "量子", "前沿", "产业", "发布会")) {
            return PostTag.TECH_NEWS;
        }
        if (containsAny(text, "代码", "架构", "后端", "前端", "redis", "mysql", "java", "python", "spring", "vue")) {
            return PostTag.SOFTWARE_ENGINEERING;
        }
        if (containsAny(text, "灵感", "产品", "创意", "工作台", "项目", "方案")) {
            return PostTag.PRODUCT_IDEA;
        }
        if (containsAny(text, "讨论", "分享", "社区", "想法", "聊天")) {
            return PostTag.COMMUNITY_CHAT;
        }
        return PostTag.OTHER;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
