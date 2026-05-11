package com.pulse.service.impl;

import com.pulse.client.LLMClient;
import com.pulse.entity.FrontierNewsSource;
import com.pulse.entity.Post;
import com.pulse.enums.AuthorType;
import com.pulse.enums.PostTag;
import com.pulse.mapper.FrontierNewsSourceMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.service.PostTagClassifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrontierNewsServiceImplTest {

    private final FrontierNewsSourceMapper sourceMapper = mock(FrontierNewsSourceMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final FrontierNewsServiceImpl service = new FrontierNewsServiceImpl(
            sourceMapper,
            postMapper,
            llmClient,
            new PostTagClassifier()
    );

    @Test
    void skipsDuplicateSourceUrl() {
        FrontierNewsSource item = news("AI breakthrough", "https://example.com/ai");
        when(sourceMapper.existsBySourceUrl("https://example.com/ai")).thenReturn(true);

        FrontierNewsServiceImpl.PublishStats stats = service.publishFetchedItems(List.of(item));

        assertThat(stats.published()).isZero();
        assertThat(stats.skippedDuplicate()).isEqualTo(1);
        verify(postMapper, never()).insert(any(Post.class));
    }

    @Test
    void publishesSystemPostWithSummaryAndTag() {
        FrontierNewsSource item = news("OpenAI agent news", "https://example.com/agent");
        item.setRawContent("OpenAI 发布了新的 Agent 科技前沿能力，内容较长。");
        when(sourceMapper.existsBySourceUrl("https://example.com/agent")).thenReturn(false);
        when(llmClient.summarizeText(item.getRawContent())).thenReturn("OpenAI 发布新的 Agent 能力。");

        FrontierNewsServiceImpl.PublishStats stats = service.publishFetchedItems(List.of(item));

        assertThat(stats.published()).isEqualTo(1);
        verify(postMapper).insert(org.mockito.ArgumentMatchers.argThat(post ->
                AuthorType.SYSTEM.getCode().equals(post.getAuthorType())
                        && Boolean.TRUE.equals(post.getIsSystemMessage())
                        && PostTag.AI_FRONTIER.getCode().equals(post.getTagCode())
                        && "OpenAI 发布新的 Agent 能力。".equals(post.getContent())
        ));
        verify(sourceMapper).insert(any(FrontierNewsSource.class));
    }

    private FrontierNewsSource news(String title, String url) {
        FrontierNewsSource item = new FrontierNewsSource();
        item.setSourceTitle(title);
        item.setSourceUrl(url);
        item.setSourcePublishedAt(LocalDateTime.now());
        return item;
    }
}
