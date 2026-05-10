package com.pulse.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pulse.dto.request.BountyAuditRequest;
import com.pulse.dto.request.BountyCancelRequest;
import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.dto.request.BountySubmitRequest;
import com.pulse.dto.response.BountyAcceptResponse;
import com.pulse.dto.response.BountyAuditResponse;
import com.pulse.dto.response.BountyDetailResponse;
import com.pulse.dto.response.BountyListResponse;
import com.pulse.dto.response.BountyLogResponse;

import java.util.List;

/**
 * Bounty Service Interface
 */
public interface BountyService {

    /**
     * Get bounty list (all public bounties)
     * @param sortBy Sort field: reward_points, accepted_count, submission_count, created_at (default)
     * @param sortOrder Sort order: asc, desc (default)
     */
    IPage<BountyListResponse> getBountyList(Integer status, String taskType, String sortBy, String sortOrder, int page, int size);

    /**
     * Get my bounties (published by user or their agents)
     * @param sortBy Sort field: reward_points, accepted_count, submission_count, created_at (default)
     * @param sortOrder Sort order: asc, desc (default)
     */
    IPage<BountyListResponse> getMyBounties(Long userId, Integer status, String sortBy, String sortOrder, int page, int size);

    /**
     * Get bounty detail
     */
    BountyDetailResponse getBountyDetail(Long userId, Long taskId);

    /**
     * Create bounty
     */
    BountyDetailResponse createBounty(Long ownerId, BountyCreateRequest request);

    /**
     * Accept bounty
     */
    BountyAcceptResponse acceptBounty(Long userId, Long taskId);

    /**
     * Submit bounty answer
     */
    BountyAcceptResponse submitBounty(Long userId, Long taskId, BountySubmitRequest request);

    /**
     * Audit bounty submission
     */
    BountyAuditResponse auditSubmission(Long userId, Long taskId, BountyAuditRequest request);

    /**
     * Cancel bounty and release frozen reward points.
     */
    BountyDetailResponse cancelBounty(Long userId, Long taskId, String reason);

    /**
     * Get recent bounty logs
     */
    List<BountyLogResponse> getRecentLogs(int limit);

    /**
     * Get logs for a specific bounty
     */
    List<BountyLogResponse> getLogsByTaskId(Long taskId);

    /**
     * Get bounties accepted by user (猎手接取的任务)
     * @param sortBy Sort field: reward_points, accepted_count, submission_count, created_at (default)
     * @param sortOrder Sort order: asc, desc (default)
     */
    IPage<BountyListResponse> getMyAcceptedBounties(Long userId, Integer status, String sortBy, String sortOrder, int page, int size);
}
