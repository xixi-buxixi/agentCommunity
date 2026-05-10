package com.pulse.service.impl;

import com.pulse.dto.response.RankingPostResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.Post;
import com.pulse.entity.User;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Ranking Service Implementation
 *
 * Core business logic for post ranking/leaderboard.
 *
 * Redis Key Design:
 * - Hot ranking: pulse:rank:hot (Sorted Set, score = like_count * 3 + comment_count * 5 + view_count)
 * - Like ranking: pulse:rank:like (Sorted Set, score = like_count)
 * - Comment ranking: pulse:rank:comment (Sorted Set, score = comment_count)
 *
 * Features:
 * - Redis cache with MySQL fallback
 * - Pipeline operations for efficiency
 * - Author info enrichment (human/agent)
 * - Content truncation (max 30 chars)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RankingServiceImpl implements RankingService {

    private final StringRedisTemplate redisTemplate;
    private final PostMapper postMapper;
    private final UserMapper userMapper;
    private final AgentMapper agentMapper;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // Redis key prefixes
    private static final String REDIS_KEY_PREFIX = "pulse:rank:";
    private static final String HOT_RANKING_KEY = REDIS_KEY_PREFIX + "hot";
    private static final String LIKE_RANKING_KEY = REDIS_KEY_PREFIX + "like";
    private static final String COMMENT_RANKING_KEY = REDIS_KEY_PREFIX + "comment";

    // Max content snippet length
    private static final int MAX_SNIPPET_LENGTH = 30;

    // Default limit if not specified
    private static final int DEFAULT_LIMIT = 10;

    // Max limit to prevent abuse
    private static final int MAX_LIMIT = 10;

    @Override
    public List<RankingPostResponse> getRankingPosts(String type, int limit) {
        // Validate and normalize parameters
        String normalizedType = validateType(type);
        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        String redisKey = getRedisKey(normalizedType);

        // Try to get from Redis first
        Set<ZSetOperations.TypedTuple<String>> redisData = getFromRedis(redisKey, normalizedLimit);

        List<Long> postIds;
        Map<Long, Integer> scoreMap = new HashMap<>();

        if (redisData != null && !redisData.isEmpty()) {
            // Redis has data - extract postIds and scores
            log.debug("Ranking data found in Redis for type: {}", normalizedType);
            postIds = extractPostIdsFromRedis(redisData, scoreMap);
        } else {
            // Redis empty - fallback to MySQL
            log.info("Redis ranking cache empty for type: {}, falling back to MySQL", normalizedType);
            postIds = getFromMySQL(normalizedType, normalizedLimit, scoreMap);

            // Async refresh cache (fire and forget)
            try {
                refreshRankingCache(normalizedType);
            } catch (Exception e) {
                log.warn("Failed to refresh ranking cache for type: {}", normalizedType, e);
            }
        }

        if (postIds.isEmpty()) {
            return Collections.emptyList();
        }

        // Batch fetch post details and build responses
        return buildRankingResponses(postIds, scoreMap);
    }

    @Override
    public void refreshRankingCache(String type) {
        String normalizedType = validateType(type);
        String redisKey = getRedisKey(normalizedType);

        log.info("Refreshing ranking cache for type: {}", normalizedType);

        // Get top posts from MySQL
        List<Post> topPosts = fetchTopPostsFromMySQL(normalizedType, DEFAULT_LIMIT);

        if (topPosts.isEmpty()) {
            log.warn("No posts found for ranking type: {}", normalizedType);
            return;
        }

        // Delete old key
        redisTemplate.delete(redisKey);

        // Batch write to Redis using Pipeline
        writeToRedisWithPipeline(redisKey, topPosts);

        log.info("Ranking cache refreshed for type: {}, count: {}", normalizedType, topPosts.size());
    }

    @Override
    public void refreshAllRankingCaches() {
        log.info("Refreshing all ranking caches");
        refreshRankingCache("hot");
        refreshRankingCache("like");
        refreshRankingCache("comment");
        log.info("All ranking caches refreshed");
    }

    // ========== Private Helper Methods ==========

    /**
     * Validate and normalize ranking type
     */
    private String validateType(String type) {
        if (type == null || type.isBlank()) {
            return "hot";
        }
        String normalized = type.toLowerCase().trim();
        if ("likes".equals(normalized)) {
            return "like";
        }
        if ("comments".equals(normalized)) {
            return "comment";
        }
        if (!"hot".equals(normalized) && !"like".equals(normalized) && !"comment".equals(normalized)) {
            log.warn("Invalid ranking type: {}, using default 'hot'", type);
            return "hot";
        }
        return normalized;
    }

    /**
     * Get Redis key for ranking type
     */
    private String getRedisKey(String type) {
        if ("like".equals(type)) {
            return LIKE_RANKING_KEY;
        }
        if ("comment".equals(type)) {
            return COMMENT_RANKING_KEY;
        }
        return HOT_RANKING_KEY;
    }

    /**
     * Get ranking data from Redis Sorted Set (descending order)
     */
    private Set<ZSetOperations.TypedTuple<String>> getFromRedis(String key, int limit) {
        try {
            // Use reverseRangeWithScores to get highest scores first
            return redisTemplate.opsForZSet()
                    .reverseRangeWithScores(key, 0, limit - 1);
        } catch (Exception e) {
            log.error("Failed to get ranking from Redis: key={}", key, e);
            return null;
        }
    }

    /**
     * Extract postIds from Redis data, populate scoreMap
     */
    private List<Long> extractPostIdsFromRedis(
            Set<ZSetOperations.TypedTuple<String>> redisData,
            Map<Long, Integer> scoreMap) {
        return redisData.stream()
                .map(tuple -> {
                    String postIdStr = tuple.getValue();
                    Double score = tuple.getScore();
                    if (postIdStr != null && score != null) {
                        try {
                            Long postId = Long.parseLong(postIdStr);
                            scoreMap.put(postId, score.intValue());
                            return postId;
                        } catch (NumberFormatException e) {
                            log.warn("Invalid postId in Redis: {}", postIdStr);
                            return null;
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get ranking data from MySQL fallback
     */
    private List<Long> getFromMySQL(String type, int limit, Map<Long, Integer> scoreMap) {
        List<Post> posts = fetchTopPostsFromMySQL(type, limit);
        return posts.stream()
                .map(post -> {
                    int score = calculateScore(post, type);
                    scoreMap.put(post.getId(), score);
                    return post.getId();
                })
                .collect(Collectors.toList());
    }

    /**
     * Fetch top posts from MySQL based on ranking type
     */
    private List<Post> fetchTopPostsFromMySQL(String type, int limit) {
        if ("like".equals(type)) {
            return postMapper.findTopByLikeCount(limit);
        } else if ("comment".equals(type)) {
            return postMapper.findTopByCommentCount(limit);
        } else {
            return postMapper.findTopByHotScore(limit);
        }
    }

    /**
     * Write ranking data to Redis using Pipeline for efficiency
     */
    private void writeToRedisWithPipeline(String key, List<Post> posts) {
        // Use RedisCallback for pipeline operations with explicit type
        redisTemplate.executePipelined((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            for (Post post : posts) {
                String postIdStr = String.valueOf(post.getId());
                String type = key.substring(REDIS_KEY_PREFIX.length());
                double score = calculateScore(post, type);
                // Use raw connection to add to sorted set
                connection.zAdd(
                        redisTemplate.getStringSerializer().serialize(key),
                        score,
                        redisTemplate.getStringSerializer().serialize(postIdStr)
                );
            }
            return null;  // Pipeline requires null return
        });
    }

    /**
     * Build ranking responses with author info
     */
    private List<RankingPostResponse> buildRankingResponses(
            List<Long> postIds,
            Map<Long, Integer> scoreMap) {
        // Batch fetch posts
        List<Post> posts = postMapper.selectBatchIds(postIds);

        if (posts.isEmpty()) {
            return Collections.emptyList();
        }

        // Build responses with rank
        List<RankingPostResponse> responses = new ArrayList<>();
        int rank = 1;

        for (Long postId : postIds) {
            // Find the post by id (maintain order from ranking)
            Post post = posts.stream()
                    .filter(p -> p.getId().equals(postId))
                    .findFirst()
                    .orElse(null);

            if (post == null || post.getDeleted() == 1) {
                log.warn("Post not found or deleted in ranking: postId={}", postId);
                continue;
            }

            // Get author info
            AuthorInfo authorInfo = getAuthorInfo(post);

            // Get score (like_count or comment_count)
            Integer score = scoreMap.getOrDefault(postId, 0);

            // Truncate content to 30 chars
            String contentSnippet = truncateContent(post.getContent(), MAX_SNIPPET_LENGTH);

            RankingPostResponse response = RankingPostResponse.builder()
                    .rank(rank)
                    .score(score)
                    .postId(post.getId())
                    .authorId(post.getAuthorId())
                    .authorType(post.getAuthorType())
                    .authorName(authorInfo.authorName)
                    .authorAvatar(authorInfo.authorAvatar)
                    .agentOwnerName(authorInfo.agentOwnerName)
                    .contentSnippet(contentSnippet)
                    .likeCount(post.getLikeCount())
                    .commentCount(post.getCommentCount())
                    .viewCount(post.getViewCount())
                    .createdAt(post.getCreatedAt() != null
                            ? post.getCreatedAt().format(DATE_FORMATTER)
                            : null)
                    .build();

            responses.add(response);
            rank++;
        }

        return responses;
    }

    /**
     * Get author info (human user or agent)
     */
    private AuthorInfo getAuthorInfo(Post post) {
        AuthorInfo info = new AuthorInfo();

        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Human author
            User user = userMapper.selectById(post.getAuthorId());
            if (user != null) {
                info.authorName = user.getUsername();
                info.authorAvatar = user.getAvatarUrl();
            }
        } else if (AuthorType.AGENT.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // Agent author
            Agent agent = agentMapper.selectById(post.getAuthorId());
            if (agent != null) {
                info.authorName = agent.getName();
                info.authorAvatar = agent.getAvatarUrl();

                // Get owner name
                User owner = userMapper.selectById(agent.getOwnerId());
                if (owner != null) {
                    info.agentOwnerName = owner.getUsername();
                }
            }
        } else if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(post.getAuthorType())) {
            // System message
            info.authorName = "SYSTEM";
        }

        return info;
    }

    /**
     * Truncate content to max length
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }

    private int calculateScore(Post post, String type) {
        if ("like".equals(type)) {
            return post.getLikeCount() != null ? post.getLikeCount() : 0;
        }
        if ("comment".equals(type)) {
            return post.getCommentCount() != null ? post.getCommentCount() : 0;
        }
        int likes = post.getLikeCount() != null ? post.getLikeCount() : 0;
        int comments = post.getCommentCount() != null ? post.getCommentCount() : 0;
        int views = post.getViewCount() != null ? post.getViewCount() : 0;
        return likes * 3 + comments * 5 + views;
    }

    /**
     * Internal class for author info
     */
    private static class AuthorInfo {
        String authorName;
        String authorAvatar;
        String agentOwnerName;
    }
}
