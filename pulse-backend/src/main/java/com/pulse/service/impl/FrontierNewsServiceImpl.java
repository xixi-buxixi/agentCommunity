package com.pulse.service.impl;

import com.pulse.client.LLMClient;
import com.pulse.entity.FrontierNewsSource;
import com.pulse.entity.Post;
import com.pulse.enums.AuthorType;
import com.pulse.enums.PostTag;
import com.pulse.mapper.FrontierNewsSourceMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.service.FrontierNewsService;
import com.pulse.service.PostTagClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FrontierNewsServiceImpl implements FrontierNewsService {

    private final FrontierNewsSourceMapper sourceMapper;
    private final PostMapper postMapper;
    private final LLMClient llmClient;
    private final PostTagClassifier postTagClassifier;

    public record PublishStats(int fetched, int published, int skippedDuplicate, int failed) {
    }

    @Override
    @Transactional
    public PublishStats publishFetchedItems(List<FrontierNewsSource> items) {
        int published = 0;
        int skipped = 0;
        int failed = 0;

        for (FrontierNewsSource item : items) {
            if (item.getSourceUrl() == null || item.getSourceUrl().isBlank()) {
                failed++;
                continue;
            }
            if (sourceMapper.existsBySourceUrl(item.getSourceUrl())) {
                skipped++;
                continue;
            }

            try {
                String raw = firstNonBlank(item.getRawContent(), item.getSourceTitle());
                String summary = firstNonBlank(llmClient.summarizeText(raw), truncate(raw, 500));
                PostTag tag = postTagClassifier.classify(item.getSourceTitle() + "\n" + summary);

                Post post = new Post();
                post.setAuthorId(0L);
                post.setAuthorType(AuthorType.SYSTEM.getCode());
                post.setContent(truncate(summary, 500));
                post.setLikeCount(0);
                post.setDislikeCount(0);
                post.setCommentCount(0);
                post.setViewCount(0);
                post.setIsSystemMessage(true);
                post.setTagCode(tag.getCode());
                post.setSourceTitle(item.getSourceTitle());
                post.setSourceUrl(item.getSourceUrl());
                post.setSourcePublishedAt(item.getSourcePublishedAt());
                postMapper.insert(post);

                item.setSummary(post.getContent());
                item.setPublished(true);
                item.setPostId(post.getId());
                item.setFetchedAt(LocalDateTime.now());
                sourceMapper.insert(item);
                published++;
            } catch (Exception e) {
                log.warn("Frontier news publish failed: url={}, error={}", item.getSourceUrl(), e.getMessage());
                item.setPublished(false);
                item.setFailureReason(truncate(e.getMessage(), 500));
                item.setFetchedAt(LocalDateTime.now());
                sourceMapper.insert(item);
                failed++;
            }
        }

        return new PublishStats(items.size(), published, skipped, failed);
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first.trim() : fallback;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
