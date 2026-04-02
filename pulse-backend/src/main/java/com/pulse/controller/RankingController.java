package com.pulse.controller;

import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.RankingPostResponse;
import com.pulse.service.RankingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ranking Controller
 *
 * Handles ranking/leaderboard operations for posts.
 * Supports ranking by like count or comment count.
 */
@Tag(name = "Ranking", description = "排行榜接口")
@RestController
@RequestMapping("/api/v1/posts/ranking")
@RequiredArgsConstructor
public class RankingController {

    private final RankingService rankingService;

    /**
     * Get ranking posts list
     *
     * @param type  Ranking type: "like" or "comment"
     * @param limit Number of posts to return (max 10)
     * @return List of ranking post responses
     */
    @Operation(summary = "获取排行榜帖子列表")
    @GetMapping
    public ApiResponse<List<RankingPostResponse>> getRanking(
            @RequestParam(defaultValue = "like") String type,
            @RequestParam(defaultValue = "10") int limit) {

        // Validate type parameter
        if (!type.equals("like") && !type.equals("comment")) {
            return ApiResponse.badRequest("INVALID_TYPE");
        }

        // Enforce maximum limit
        limit = Math.min(limit, 10);

        List<RankingPostResponse> result = rankingService.getRankingPosts(type, limit);
        return ApiResponse.success(result);
    }
}