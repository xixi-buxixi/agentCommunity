package com.pulse.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.BountyAuditRequest;
import com.pulse.dto.request.BountyCancelRequest;
import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.dto.request.BountySubmitRequest;
import com.pulse.dto.response.*;
import com.pulse.security.UserPrincipal;
import com.pulse.service.BountyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounty Controller
 *
 * Handles all Bounty Guild operations for Phase 2.
 */
@Tag(name = "Bounty Guild", description = "Bounty system APIs")
@RestController
@RequestMapping("/api/v2/bounties")
@RequiredArgsConstructor
public class BountyController {

    private final BountyService bountyService;

    /**
     * Get Bounty List (Public)
     */
    @Operation(summary = "Get bounty list", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ApiResponse<Map<String, Object>> getBountyList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String task_type,
            @RequestParam(value = "sort_by", required = false, defaultValue = "created_at") String sortBy,
            @RequestParam(value = "sort_order", required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        Long userId = principal != null ? principal.getUserId() : null;
        IPage<BountyListResponse> bountyPage = bountyService.getBountyList(status, task_type, sortBy, sortOrder, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("list", bountyPage.getRecords());
        data.put("total", bountyPage.getTotal());
        data.put("page", bountyPage.getCurrent());
        data.put("size", bountyPage.getSize());

        return ApiResponse.success(data);
    }

    /**
     * Get My Bounties (审核列表 - bounties published by user or their agents)
     */
    @Operation(summary = "Get my bounties", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/my")
    public ApiResponse<Map<String, Object>> getMyBounties(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer status,
            @RequestParam(value = "sort_by", required = false, defaultValue = "created_at") String sortBy,
            @RequestParam(value = "sort_order", required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        IPage<BountyListResponse> bountyPage = bountyService.getMyBounties(principal.getUserId(), status, sortBy, sortOrder, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("list", bountyPage.getRecords());
        data.put("total", bountyPage.getTotal());
        data.put("page", bountyPage.getCurrent());
        data.put("size", bountyPage.getSize());

        return ApiResponse.success(data);
    }

    /**
     * Get My Accepted Bounties (我的任务 - bounties accepted by user)
     */
    @Operation(summary = "Get my accepted bounties", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/accepted")
    public ApiResponse<Map<String, Object>> getMyAcceptedBounties(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer status,
            @RequestParam(value = "sort_by", required = false, defaultValue = "created_at") String sortBy,
            @RequestParam(value = "sort_order", required = false, defaultValue = "desc") String sortOrder,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {

        IPage<BountyListResponse> bountyPage = bountyService.getMyAcceptedBounties(principal.getUserId(), status, sortBy, sortOrder, page, size);

        Map<String, Object> data = new HashMap<>();
        data.put("list", bountyPage.getRecords());
        data.put("total", bountyPage.getTotal());
        data.put("page", bountyPage.getCurrent());
        data.put("size", bountyPage.getSize());

        return ApiResponse.success(data);
    }

    /**
     * Get Recent Bounty Logs
     */
    @Operation(summary = "Get recent bounty logs", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/logs")
    public ApiResponse<List<BountyLogResponse>> getBountyLogs(
            @RequestParam(defaultValue = "20") int limit) {

        List<BountyLogResponse> logs = bountyService.getRecentLogs(limit);
        return ApiResponse.success(logs);
    }

    /**
     * Get Logs for a specific bounty
     */
    @Operation(summary = "Get bounty logs by task id", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{taskId}/logs")
    public ApiResponse<List<BountyLogResponse>> getBountyLogsByTaskId(
            @PathVariable Long taskId) {

        List<BountyLogResponse> logs = bountyService.getLogsByTaskId(taskId);
        return ApiResponse.success(logs);
    }

    /**
     * Get Bounty Detail
     */
    @Operation(summary = "Get bounty detail", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{taskId}")
    public ApiResponse<BountyDetailResponse> getBountyDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long taskId) {

        Long userId = principal != null ? principal.getUserId() : null;
        BountyDetailResponse response = bountyService.getBountyDetail(userId, taskId);
        return ApiResponse.success(response);
    }

    /**
     * Create Bounty
     */
    @Operation(summary = "Create bounty", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping
    public ApiResponse<BountyDetailResponse> createBounty(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody BountyCreateRequest request) {

        BountyDetailResponse response = bountyService.createBounty(principal.getUserId(), request);
        return ApiResponse.created("悬赏发布成功", response);
    }

    /**
     * Accept Bounty
     */
    @Operation(summary = "Accept bounty", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{taskId}/accept")
    public ApiResponse<BountyAcceptResponse> acceptBounty(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long taskId) {

        BountyAcceptResponse response = bountyService.acceptBounty(principal.getUserId(), taskId);
        return ApiResponse.success("悬赏接取成功", response);
    }

    /**
     * Submit Bounty Answer
     */
    @Operation(summary = "Submit bounty answer", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{taskId}/submit")
    public ApiResponse<BountyAcceptResponse> submitBounty(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long taskId,
            @Valid @RequestBody BountySubmitRequest request) {

        BountyAcceptResponse response = bountyService.submitBounty(principal.getUserId(), taskId, request);
        return ApiResponse.success("答案提交成功", response);
    }

    /**
     * Audit Bounty Submission
     */
    @Operation(summary = "Audit bounty submission", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{taskId}/audit")
    public ApiResponse<BountyAuditResponse> auditSubmission(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long taskId,
            @Valid @RequestBody BountyAuditRequest request) {

        BountyAuditResponse response = bountyService.auditSubmission(principal.getUserId(), taskId, request);

        if ("ACCEPT".equalsIgnoreCase(request.getDecision())) {
            return ApiResponse.success("答案已采纳，积分已结算", response);
        } else {
            return ApiResponse.success("答案已拒绝", response);
        }
    }

    /**
     * Cancel Bounty before submission review.
     */
    @Operation(summary = "Cancel bounty", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{taskId}/cancel")
    public ApiResponse<BountyDetailResponse> cancelBounty(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long taskId,
            @Valid @RequestBody(required = false) BountyCancelRequest request) {

        String reason = request != null ? request.getReason() : null;
        BountyDetailResponse response = bountyService.cancelBounty(principal.getUserId(), taskId, reason);
        return ApiResponse.success("悬赏已取消，冻结积分已释放", response);
    }
}
