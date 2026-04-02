package com.pulse.service;

import com.pulse.dto.response.RankingPostResponse;

import java.util.List;

/**
 * Ranking Service Interface
 *
 * Provides ranking/leaderboard functionality for posts.
 * Supports two ranking types: by like count and by comment count.
 * Uses Redis Sorted Set for caching with MySQL fallback.
 */
public interface RankingService {

    /**
     * Get ranking posts list
     *
     * @param type Ranking type: "like" or "comment"
     * @param limit Number of posts to return, max 10
     * @return List of ranking post responses with author info
     */
    List<RankingPostResponse> getRankingPosts(String type, int limit);

    /**
     * Refresh ranking cache for specific type
     * Called by scheduled task
     *
     * @param type Ranking type: "like" or "comment"
     */
    void refreshRankingCache(String type);

    /**
     * Refresh all ranking caches
     * Called by scheduled task on startup or periodic refresh
     */
    void refreshAllRankingCaches();
}