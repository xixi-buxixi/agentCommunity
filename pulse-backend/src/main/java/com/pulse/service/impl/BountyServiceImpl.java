package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.BountyAuditRequest;
import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.dto.request.BountySubmitRequest;
import com.pulse.dto.response.BountyAcceptResponse;
import com.pulse.dto.response.BountyAuditResponse;
import com.pulse.dto.response.BountyDetailResponse;
import com.pulse.dto.response.BountyListResponse;
import com.pulse.entity.*;
import com.pulse.enums.AcceptanceStatus;
import com.pulse.enums.BountyStatus;
import com.pulse.enums.CrisisLevel;
import com.pulse.enums.TaskType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.*;
import com.pulse.service.BountyService;
import com.pulse.service.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Bounty Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BountyServiceImpl implements BountyService {

    private final BountyTaskMapper bountyTaskMapper;
    private final BountyAcceptanceMapper bountyAcceptanceMapper;
    private final BountySubmissionMapper bountySubmissionMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final PointsService pointsService;

    @Override
    public IPage<BountyListResponse> getBountyList(Integer status, String taskType, int page, int size) {
        Page<BountyTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();

        if (status != null) {
            wrapper.eq(BountyTask::getStatus, status);
        }
        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(BountyTask::getTaskType, taskType);
        }
        wrapper.orderByDesc(BountyTask::getCreatedAt);

        IPage<BountyTask> taskPage = bountyTaskMapper.selectPage(pageParam, wrapper);

        return taskPage.convert(this::buildListResponse);
    }

    @Override
    public BountyDetailResponse getBountyDetail(Long userId, Long taskId) {
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }

        BountyDetailResponse response = buildDetailResponse(task);

        // Check if user has accepted this bounty
        if (userId != null) {
            BountyAcceptance acceptance = bountyAcceptanceMapper.findByTaskAndHunter(taskId, userId);
            response.setIsAcceptedByMe(acceptance != null);
        } else {
            response.setIsAcceptedByMe(false);
        }

        return response;
    }

    @Override
    @Transactional
    public BountyDetailResponse createBounty(Long ownerId, BountyCreateRequest request) {
        // 1. Validate agent ownership
        Agent agent = agentMapper.selectById(request.getAgentId());
        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (!agent.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
        }

        // 2. Validate reward points
        if (request.getRewardPoints().compareTo(BigDecimal.TEN) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_REWARD);
        }
        if (request.getRewardPoints().compareTo(new BigDecimal("500")) > 0) {
            throw new BusinessException(ErrorCode.REWARD_LIMIT_EXCEEDED);
        }

        // 3. Check and deduct points
        BigDecimal availablePoints = pointsService.getAvailablePoints(ownerId);
        if (availablePoints.compareTo(request.getRewardPoints()) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // 4. Calculate deadline
        int deadlineHours = request.getDeadlineHours() != null ? request.getDeadlineHours() : 72;
        if (deadlineHours > 168) {
            deadlineHours = 168;
        }
        LocalDateTime deadline = LocalDateTime.now().plusHours(deadlineHours);

        // 5. Determine crisis level
        CrisisLevel crisisLevel = CrisisLevel.fromConfidenceScore(
            request.getConfidenceScore() != null ? request.getConfidenceScore().doubleValue() : null
        );

        // 6. Create bounty task
        BountyTask task = new BountyTask();
        task.setAgentId(request.getAgentId());
        task.setOwnerId(ownerId);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setRewardPoints(request.getRewardPoints());
        task.setTaskType(request.getTaskType() != null ? request.getTaskType() : TaskType.KNOWLEDGE.getCode());
        task.setCrisisLevel(crisisLevel.getCode());
        task.setConfidenceScore(request.getConfidenceScore());
        task.setStatus(BountyStatus.PENDING.getCode());
        task.setSourcePostId(request.getSourcePostId());
        task.setDeadline(deadline);
        task.setAcceptedCount(0);
        task.setSubmissionCount(0);

        bountyTaskMapper.insert(task);

        // 7. Deduct points
        pointsService.deductPoints(ownerId, request.getRewardPoints(), task.getId(),
            "Agent [" + agent.getName() + "] 发布悬赏");

        log.info("Bounty created: taskId={}, ownerId={}, reward={}", task.getId(), ownerId, request.getRewardPoints());

        return buildDetailResponse(task);
    }

    @Override
    @Transactional
    public BountyAcceptResponse acceptBounty(Long userId, Long taskId) {
        // 1. Validate task exists and is pending
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        if (task.getStatus() != BountyStatus.PENDING.getCode()) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_ACCEPTABLE);
        }
        if (task.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOUNTY_TASK_EXPIRED);
        }

        // 2. Check if already accepted
        BountyAcceptance existing = bountyAcceptanceMapper.findByTaskAndHunter(taskId, userId);
        if (existing != null) {
            throw new BusinessException(ErrorCode.BOUNTY_ALREADY_ACCEPTED);
        }

        // 3. Create acceptance record
        BountyAcceptance acceptance = new BountyAcceptance();
        acceptance.setTaskId(taskId);
        acceptance.setHunterId(userId);
        acceptance.setStatus(AcceptanceStatus.ACCEPTED.getCode());
        acceptance.setAcceptedAt(LocalDateTime.now());

        bountyAcceptanceMapper.insert(acceptance);

        // 4. Increment accepted count
        bountyTaskMapper.incrementAcceptedCount(taskId);

        log.info("Bounty accepted: taskId={}, hunterId={}", taskId, userId);

        return BountyAcceptResponse.builder()
            .acceptanceId(acceptance.getId())
            .taskId(taskId)
            .hunterId(userId)
            .status(AcceptanceStatus.ACCEPTED.getCode())
            .acceptedAt(acceptance.getAcceptedAt())
            .deadline(task.getDeadline())
            .build();
    }

    @Override
    @Transactional
    public BountyAcceptResponse submitBounty(Long userId, Long taskId, BountySubmitRequest request) {
        // 1. Validate task
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        if (task.getStatus() != BountyStatus.PENDING.getCode()) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_ACCEPTABLE);
        }
        if (task.getDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.BOUNTY_TASK_EXPIRED);
        }

        // 2. Validate acceptance
        BountyAcceptance acceptance = bountyAcceptanceMapper.findByTaskAndHunter(taskId, userId);
        if (acceptance == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_ACCEPTED);
        }

        // 3. Check if already submitted
        if (bountySubmissionMapper.existsByTaskAndHunter(taskId, userId)) {
            throw new BusinessException(ErrorCode.BOUNTY_ALREADY_SUBMITTED);
        }

        // 4. Create submission
        BountySubmission submission = new BountySubmission();
        submission.setTaskId(taskId);
        submission.setHunterId(userId);
        submission.setContent(request.getContent());
        submission.setAttachmentUrls(request.getAttachmentUrls());
        submission.setIsAccepted(false);
        submission.setCreatedAt(LocalDateTime.now());

        bountySubmissionMapper.insert(submission);

        // 5. Update acceptance status
        bountyAcceptanceMapper.updateStatus(taskId, userId, AcceptanceStatus.SUBMITTED.getCode());

        // 6. Update task submission count and status
        bountyTaskMapper.incrementSubmissionCount(taskId);
        bountyTaskMapper.updateStatus(taskId, BountyStatus.REVIEWING.getCode());

        log.info("Bounty submitted: taskId={}, hunterId={}, submissionId={}", taskId, userId, submission.getId());

        return BountyAcceptResponse.builder()
            .acceptanceId(acceptance.getId())
            .taskId(taskId)
            .hunterId(userId)
            .status(AcceptanceStatus.SUBMITTED.getCode())
            .acceptedAt(acceptance.getAcceptedAt())
            .deadline(task.getDeadline())
            .build();
    }

    @Override
    @Transactional
    public BountyAuditResponse auditSubmission(Long userId, Long taskId, BountyAuditRequest request) {
        // 1. Validate task ownership
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        if (!task.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.BOUNTY_OWNER_REQUIRED);
        }

        // 2. Validate submission
        BountySubmission submission = bountySubmissionMapper.selectById(request.getSubmissionId());
        if (submission == null || !submission.getTaskId().equals(taskId)) {
            throw new BusinessException(ErrorCode.SUBMISSION_NOT_FOUND);
        }

        BountyAuditResponse.BountyAuditResponseBuilder responseBuilder = BountyAuditResponse.builder()
            .taskId(taskId)
            .submissionId(submission.getId())
            .hunterId(submission.getHunterId())
            .decision(request.getDecision());

        if ("ACCEPT".equalsIgnoreCase(request.getDecision())) {
            // Accept submission
            submission.setIsAccepted(true);
            submission.setReviewedAt(LocalDateTime.now());
            bountySubmissionMapper.updateById(submission);

            // Update task status
            task.setStatus(BountyStatus.COMPLETED.getCode());
            bountyTaskMapper.updateById(task);

            // Update acceptance status
            bountyAcceptanceMapper.updateStatus(taskId, submission.getHunterId(), AcceptanceStatus.SELECTED.getCode());

            // Reject other submissions
            bountySubmissionMapper.rejectOtherSubmissions(taskId, submission.getId(), "其他答案已被采纳");

            // Settle points - give reward to hunter
            pointsService.addPoints(submission.getHunterId(), task.getRewardPoints(), taskId,
                "答案被采纳", "BOUNTY_RECV");

            responseBuilder
                .rewardPoints(task.getRewardPoints())
                .taskStatus(BountyStatus.COMPLETED.getCode())
                .taskStatusText(BountyStatus.COMPLETED.getText())
                .acceptedAt(LocalDateTime.now());

            log.info("Bounty accepted: taskId={}, submissionId={}, hunterId={}", taskId, submission.getId(), submission.getHunterId());

        } else {
            // Reject submission
            submission.setIsAccepted(false);
            submission.setRejectReason(request.getFeedback());
            submission.setReviewedAt(LocalDateTime.now());
            bountySubmissionMapper.updateById(submission);

            // Update acceptance status
            bountyAcceptanceMapper.updateStatus(taskId, submission.getHunterId(), AcceptanceStatus.REJECTED.getCode());

            responseBuilder
                .taskStatus(task.getStatus())
                .taskStatusText(BountyStatus.fromCode(task.getStatus()).getText());

            log.info("Bounty rejected: taskId={}, submissionId={}", taskId, submission.getId());
        }

        return responseBuilder.build();
    }

    private BountyListResponse buildListResponse(BountyTask task) {
        Agent agent = agentMapper.selectById(task.getAgentId());
        User owner = userMapper.selectById(task.getOwnerId());

        return BountyListResponse.builder()
            .id(task.getId())
            .agentId(task.getAgentId())
            .agentName(agent != null ? agent.getName() : "Unknown")
            .ownerId(task.getOwnerId())
            .ownerName(owner != null ? owner.getUsername() : "Unknown")
            .title(task.getTitle())
            .description(task.getDescription())
            .rewardPoints(task.getRewardPoints())
            .taskType(task.getTaskType())
            .crisisLevel(task.getCrisisLevel())
            .status(task.getStatus())
            .statusText(BountyStatus.fromCode(task.getStatus()).getText())
            .acceptedCount(task.getAcceptedCount())
            .deadline(task.getDeadline())
            .createdAt(task.getCreatedAt())
            .build();
    }

    private BountyDetailResponse buildDetailResponse(BountyTask task) {
        Agent agent = agentMapper.selectById(task.getAgentId());
        User owner = userMapper.selectById(task.getOwnerId());

        return BountyDetailResponse.builder()
            .id(task.getId())
            .agentId(task.getAgentId())
            .agentName(agent != null ? agent.getName() : "Unknown")
            .agentAvatar(agent != null ? agent.getAvatarUrl() : null)
            .ownerId(task.getOwnerId())
            .ownerName(owner != null ? owner.getUsername() : "Unknown")
            .title(task.getTitle())
            .description(task.getDescription())
            .rewardPoints(task.getRewardPoints())
            .taskType(task.getTaskType())
            .crisisLevel(task.getCrisisLevel())
            .confidenceScore(task.getConfidenceScore())
            .status(task.getStatus())
            .statusText(BountyStatus.fromCode(task.getStatus()).getText())
            .acceptedCount(task.getAcceptedCount())
            .submissionCount(task.getSubmissionCount())
            .deadline(task.getDeadline())
            .createdAt(task.getCreatedAt())
            .isAcceptedByMe(false)
            .build();
    }
}