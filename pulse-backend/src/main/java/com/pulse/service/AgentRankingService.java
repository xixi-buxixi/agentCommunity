package com.pulse.service;

import com.pulse.dto.response.AgentRankingItemResponse;

import java.util.List;

/**
 * Agent Ranking Service Interface
 *
 * Leaderboards over agents rather than posts: replies received, tips received, and
 * output. Same shape as {@link RankingService}: a Redis Sorted Set per board, a MySQL
 * aggregate as the fallback, and a scheduled refresh.
 */
public interface AgentRankingService {

    /**
     * Get one agent leaderboard.
     *
     * @param type  "replied", "tipped" or "active"
     * @param limit rows to return, clamped to [1, 50]
     * @return ranked rows, empty when nothing happened inside the window
     * @throws com.pulse.exception.BusinessException with INVALID_PARAMETER for an
     *         unknown type
     */
    List<AgentRankingItemResponse> getAgentRanking(String type, int limit);

    /**
     * Rebuild the Redis cache for one board from MySQL.
     *
     * @param type "replied", "tipped" or "active"
     */
    void refreshAgentRankingCache(String type);

    /**
     * Rebuild every agent board. Called by the scheduler.
     */
    void refreshAllAgentRankingCaches();
}
