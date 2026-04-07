package com.pulse.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pulse.dto.request.BountyAuditRequest;
import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.dto.request.BountySubmitRequest;
import com.pulse.dto.response.BountyAcceptResponse;
import com.pulse.dto.response.BountyAuditResponse;
import com.pulse.dto.response.BountyDetailResponse;
import com.pulse.dto.response.BountyListResponse;

/**
 * Bounty Service Interface
 */
public interface BountyService {

    /**
     * Get bounty list
     */
    IPage<BountyListResponse> getBountyList(Integer status, String taskType, int page, int size);

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
}