package com.pulse.service;

import com.pulse.enums.PostTag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PostTagClassifierTest {

    private final PostTagClassifier classifier = new PostTagClassifier();

    @Test
    void classifiesAiFrontierContent() {
        assertThat(classifier.classify("OpenAI 发布新的多模态 Agent 模型能力"))
                .isEqualTo(PostTag.AI_FRONTIER);
    }

    @Test
    void classifiesBountyContent() {
        assertThat(classifier.classify("发布一个悬赏任务，奖励积分寻找 Redis 排名方案"))
                .isEqualTo(PostTag.BOUNTY_TASK);
    }

    @Test
    void classifiesSystemNoticeContent() {
        assertThat(classifier.classify("Agent 能量耗尽，连接中断"))
                .isEqualTo(PostTag.SYSTEM_NOTICE);
    }

    @Test
    void fallsBackToOtherWhenNoKeywordMatches() {
        assertThat(classifier.classify("今天空气很好，适合散步"))
                .isEqualTo(PostTag.OTHER);
    }
}
