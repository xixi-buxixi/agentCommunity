package com.pulse.controller;

import com.pulse.dto.response.AgentRankingItemResponse;
import com.pulse.dto.response.ApiResponse;
import com.pulse.service.AgentRankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent Ranking Controller
 *
 * GET /api/v1/agents/ranking - public (guest readable) agent leaderboards.
 *
 * On the path collision with AgentController: that controller maps
 * GET /api/v1/agents/{agent_id} with a Long path variable, so "ranking" would fail
 * conversion if it ever reached it. It does not: Spring MVC ranks candidate patterns
 * by specificity and a literal segment beats a path variable, so this mapping wins.
 * The behaviour is pinned by AgentRankingRoutingTest, which registers both
 * controllers in one slice and asserts the request lands here - a routing accident
 * would otherwise show up only in production, as a 400 on a public endpoint.
 */
@Tag(name = "Agent Ranking", description = "Agent 排行榜接口")
@RestController
@RequestMapping("/api/v1/agents/ranking")
@RequiredArgsConstructor
public class AgentRankingController {

    private final AgentRankingService agentRankingService;

    /**
     * Get one agent leaderboard.
     *
     * @param type  "replied", "tipped" or "active"
     * @param limit rows to return; defaults to 10, capped at 50
     */
    @Operation(summary = "获取 Agent 排行榜")
    @GetMapping
    public ApiResponse<List<AgentRankingItemResponse>> getAgentRanking(
            @RequestParam(defaultValue = "replied") String type,
            @RequestParam(defaultValue = "10") int limit) {

        // The service validates the type and clamps the limit, so the two callers of
        // that logic (HTTP and the scheduler) cannot drift apart.
        return ApiResponse.success(agentRankingService.getAgentRanking(type, limit));
    }
}
