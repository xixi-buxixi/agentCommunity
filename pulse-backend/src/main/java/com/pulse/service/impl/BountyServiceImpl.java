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
import com.pulse.dto.response.BountyLogResponse;
import com.pulse.entity.*;
import com.pulse.enums.AcceptanceStatus;
import com.pulse.enums.AuthorType;
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
import java.util.*;
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
    private final BountyLogMapper bountyLogMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final PointsService pointsService;

    private static final int AGENT_DAILY_BOUNTY_LIMIT = 3;
    private static final BigDecimal AGENT_SINGLE_BOUNTY_LIMIT = new BigDecimal("100");

    @Override
    public IPage<BountyListResponse> getBountyList(Integer status, String taskType, String sortBy, String sortOrder, int page, int size) {
        Page<BountyTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();

        if (status != null) {
            wrapper.eq(BountyTask::getStatus, status);
        }
        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(BountyTask::getTaskType, taskType);
        }

        // Apply sorting
        applyBountySorting(wrapper, sortBy, sortOrder);

        IPage<BountyTask> taskPage = bountyTaskMapper.selectPage(pageParam, wrapper);
        List<BountyTask> tasks = taskPage.getRecords();

        // ========== N+1 Query Optimization: Batch preload owner info ==========

        // Collect all owner IDs
        Set<Long> ownerIds = tasks.stream()
                .map(BountyTask::getOwnerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        // Batch load owners
        Map<Long, User> ownerCache = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            List<User> owners = userMapper.selectBatchIds(ownerIds);
            owners.forEach(u -> ownerCache.put(u.getId(), u));
        }

        // Build responses using cached data
        List<BountyListResponse> responses = tasks.stream()
                .map(task -> buildListResponseCached(task, ownerCache))
                .collect(Collectors.toList());

        Page<BountyListResponse> responsePage = new Page<>(taskPage.getCurrent(), taskPage.getSize(), taskPage.getTotal());
        responsePage.setRecords(responses);

        return responsePage;
    }

    /**
     * Apply sorting to bounty query
     * @param wrapper Query wrapper
     * @param sortBy Sort field: reward_points, accepted_count, submission_count, created_at
     * @param sortOrder Sort order: asc, desc
     */
    private void applyBountySorting(LambdaQueryWrapper<BountyTask> wrapper, String sortBy, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);

        switch (sortBy.toLowerCase()) {
            case "reward_points":
                if (isAsc) {
                    wrapper.orderByAsc(BountyTask::getRewardPoints);
                } else {
                    wrapper.orderByDesc(BountyTask::getRewardPoints);
                }
                break;
            case "accepted_count":
                if (isAsc) {
                    wrapper.orderByAsc(BountyTask::getAcceptedCount);
                } else {
                    wrapper.orderByDesc(BountyTask::getAcceptedCount);
                }
                break;
            case "submission_count":
                if (isAsc) {
                    wrapper.orderByAsc(BountyTask::getSubmissionCount);
                } else {
                    wrapper.orderByDesc(BountyTask::getSubmissionCount);
                }
                break;
            case "created_at":
            default:
                if (isAsc) {
                    wrapper.orderByAsc(BountyTask::getCreatedAt);
                } else {
                    wrapper.orderByDesc(BountyTask::getCreatedAt);
                }
                break;
        }
    }

    @Override
    public IPage<BountyListResponse> getMyBounties(Long userId, Integer status, String sortBy, String sortOrder, int page, int size) {
        Page<BountyTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();

        // Get user's agents
        List<Long> agentIds = agentMapper.selectList(
            new LambdaQueryWrapper<Agent>().eq(Agent::getOwnerId, userId)
        ).stream().map(Agent::getId).collect(Collectors.toList());

        // Filter by owner_id = userId OR agent_id in agentIds
        wrapper.and(w -> w
            .eq(BountyTask::getOwnerId, userId)
            .or()
            .in(agentIds.size() > 0, BountyTask::getAgentId, agentIds)
        );

        if (status != null) {
            wrapper.eq(BountyTask::getStatus, status);
        }

        // Apply sorting
        applyBountySorting(wrapper, sortBy, sortOrder);

        IPage<BountyTask> taskPage = bountyTaskMapper.selectPage(pageParam, wrapper);
        List<BountyTask> tasks = taskPage.getRecords();

        // Batch preload owner info
        Set<Long> ownerIds = tasks.stream()
                .map(BountyTask::getOwnerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, User> ownerCache = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            List<User> owners = userMapper.selectBatchIds(ownerIds);
            owners.forEach(u -> ownerCache.put(u.getId(), u));
        }

        List<BountyListResponse> responses = tasks.stream()
                .map(task -> buildListResponseCached(task, ownerCache))
                .collect(Collectors.toList());

        Page<BountyListResponse> responsePage = new Page<>(taskPage.getCurrent(), taskPage.getSize(), taskPage.getTotal());
        responsePage.setRecords(responses);

        return responsePage;
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

        // If user is the owner, include submissions
        if (userId != null && userId.equals(task.getOwnerId())) {
            List<BountySubmission> submissions = bountySubmissionMapper.findByTaskId(taskId);
            List<BountyDetailResponse.BountySubmissionResponse> submissionResponses = submissions.stream()
                .map(sub -> {
                    User hunter = userMapper.selectById(sub.getHunterId());
                    return BountyDetailResponse.BountySubmissionResponse.builder()
                        .id(sub.getId())
                        .hunterId(sub.getHunterId())
                        .hunterName(hunter != null ? hunter.getUsername() : "Unknown")
                        .content(sub.getContent())
                        .isAccepted(sub.getIsAccepted())
                        .rejectReason(sub.getRejectReason())
                        .createdAt(sub.getCreatedAt())
                        .build();
                })
                .collect(Collectors.toList());
            response.setSubmissions(submissionResponses);
        }

        return response;
    }

    @Override
    @Transactional
    public BountyDetailResponse createBounty(Long ownerId, BountyCreateRequest request) {
        User user = userMapper.selectById(ownerId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Determine author type and name
        String authorType;
        String authorName;
        Long agentId = null;

        if (request.isAgentBounty()) {
            // Agent bounty
            Agent agent = agentMapper.selectById(request.getAgentId());
            if (agent == null) {
                throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
            }
            if (!agent.getOwnerId().equals(ownerId)) {
                throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
            }
            authorType = AuthorType.AGENT.getCode();
            authorName = agent.getName();
            agentId = agent.getId();
            validateAgentBountyQuota(agentId, request.getRewardPoints());
        } else {
            // Human bounty
            authorType = AuthorType.HUMAN.getCode();
            authorName = user.getUsername();
        }

        // Validate reward points
        if (request.getRewardPoints() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "悬赏积分不能为空");
        }
        if (request.getRewardPoints().compareTo(BigDecimal.TEN) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_REWARD);
        }
        if (request.getRewardPoints().compareTo(new BigDecimal("500")) > 0) {
            throw new BusinessException(ErrorCode.REWARD_LIMIT_EXCEEDED);
        }

        // Check and deduct points
        BigDecimal availablePoints = pointsService.getAvailablePoints(ownerId);
        if (availablePoints.compareTo(request.getRewardPoints()) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // Calculate deadline
        int deadlineHours = request.getDeadlineHours() != null ? request.getDeadlineHours() : 72;
        if (deadlineHours > 168) {
            deadlineHours = 168;
        }
        LocalDateTime deadline = LocalDateTime.now().plusHours(deadlineHours);

        // Determine crisis level
        CrisisLevel crisisLevel = CrisisLevel.fromConfidenceScore(
            request.getConfidenceScore() != null ? request.getConfidenceScore().doubleValue() : null
        );

        // Create bounty task
        BountyTask task = new BountyTask();
        task.setAgentId(agentId);
        task.setAuthorType(authorType);
        task.setAuthorName(authorName);
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

        // Deduct points
        String publisherName = request.isAgentBounty() ? "Agent [" + authorName + "]" : "用户 [" + authorName + "]";
        pointsService.deductPoints(ownerId, request.getRewardPoints(), task.getId(),
            publisherName + " 发布悬赏");

        log.info("Bounty created: taskId={}, ownerId={}, authorType={}, reward={}",
            task.getId(), ownerId, authorType, request.getRewardPoints());

        return buildDetailResponse(task);
    }

    @Override
    @Transactional
    public BountyAcceptResponse acceptBounty(Long userId, Long taskId) {
        // 1. Validate task exists and is acceptable (PENDING, ACCEPTED or REVIEWING)
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        // Allow accepting before someone's answer is accepted
        if (task.getStatus() != BountyStatus.PENDING.getCode()
                && task.getStatus() != BountyStatus.ACCEPTED.getCode()
                && task.getStatus() != BountyStatus.REVIEWING.getCode()) {
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
        if (task.getStatus() == BountyStatus.PENDING.getCode()) {
            bountyTaskMapper.updateStatus(taskId, BountyStatus.ACCEPTED.getCode());
        }

        // 5. Log the acceptance
        User hunter = userMapper.selectById(userId);
        String hunterName = hunter != null ? hunter.getUsername() : "Unknown";
        createLog(taskId, task.getTitle(), userId, hunterName, "ACCEPT",
            "接取了悬赏任务", null);

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
        // 1. Validate task - allow submission before completion/cancel
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        if (task.getStatus() != BountyStatus.PENDING.getCode()
                && task.getStatus() != BountyStatus.ACCEPTED.getCode()
                && task.getStatus() != BountyStatus.REVIEWING.getCode()) {
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

        // 7. Log the submission
        User hunter = userMapper.selectById(userId);
        String hunterName = hunter != null ? hunter.getUsername() : "Unknown";
        createLog(taskId, task.getTitle(), userId, hunterName, "SUBMIT",
            "提交了答案等待审核", null);

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

        // Get hunter info
        User hunter = userMapper.selectById(submission.getHunterId());
        String hunterName = hunter != null ? hunter.getUsername() : "Unknown";

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

            // Settle publisher's frozen points. Publishing only freezes points;
            // accepting an answer is the moment the reward is actually paid.
            int settled = userMapper.settleFrozenPointsAtomic(task.getOwnerId(), task.getRewardPoints());
            if (settled == 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
            }

            // Settle points - give reward to hunter
            pointsService.addPoints(submission.getHunterId(), task.getRewardPoints(), taskId,
                "答案被采纳，获得悬赏奖励", "BOUNTY_RECV");

            // Log completion
            createLog(taskId, task.getTitle(), submission.getHunterId(), hunterName, "COMPLETE",
                "答案被采纳，获得 " + task.getRewardPoints() + " 积分", task.getRewardPoints());

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

            // Log rejection
            createLog(taskId, task.getTitle(), submission.getHunterId(), hunterName, "REJECT",
                "答案被拒绝: " + (request.getFeedback() != null ? request.getFeedback() : "无原因"), null);

            responseBuilder
                .taskStatus(task.getStatus())
                .taskStatusText(BountyStatus.fromCode(task.getStatus()).getText());

            log.info("Bounty rejected: taskId={}, submissionId={}", taskId, submission.getId());
        }

        return responseBuilder.build();
    }

    @Override
    @Transactional
    public BountyDetailResponse cancelBounty(Long userId, Long taskId, String reason) {
        BountyTask task = bountyTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.BOUNTY_NOT_FOUND);
        }
        if (!task.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.BOUNTY_OWNER_REQUIRED);
        }

        BountyStatus status = BountyStatus.fromCode(task.getStatus());
        if (status != BountyStatus.PENDING && status != BountyStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.BOUNTY_STATUS_INVALID);
        }

        task.setStatus(BountyStatus.CANCELLED.getCode());
        bountyTaskMapper.updateById(task);

        String normalizedReason = reason != null && !reason.isBlank() ? reason.trim() : "发布者主动取消";
        pointsService.refundPoints(task.getOwnerId(), task.getRewardPoints(), task.getId(),
            "取消悬赏释放冻结积分: " + normalizedReason);

        User owner = userMapper.selectById(task.getOwnerId());
        String ownerName = owner != null ? owner.getUsername() : "Unknown";
        createLog(taskId, task.getTitle(), task.getOwnerId(), ownerName, "CANCEL",
            "取消悬赏，释放 " + task.getRewardPoints() + " 积分: " + normalizedReason,
            task.getRewardPoints());

        log.info("Bounty cancelled: taskId={}, ownerId={}, reward={}", taskId, userId, task.getRewardPoints());

        return buildDetailResponse(task);
    }

    @Override
    public List<BountyLogResponse> getRecentLogs(int limit) {
        List<BountyLog> logs = bountyLogMapper.findRecentLogs(limit);
        return logs.stream().map(this::buildLogResponse).collect(Collectors.toList());
    }

    @Override
    public List<BountyLogResponse> getLogsByTaskId(Long taskId) {
        List<BountyLog> logs = bountyLogMapper.findByTaskId(taskId);
        return logs.stream().map(this::buildLogResponse).collect(Collectors.toList());
    }

    @Override
    public IPage<BountyListResponse> getMyAcceptedBounties(Long userId, Integer status, String sortBy, String sortOrder, int page, int size) {
        // 获取用户接取的任务ID列表
        List<BountyAcceptance> acceptances = bountyAcceptanceMapper.findByHunterId(userId);
        if (acceptances.isEmpty()) {
            return new Page<>(page, size);
        }

        List<Long> taskIds = acceptances.stream()
            .map(BountyAcceptance::getTaskId)
            .collect(Collectors.toList());

        // 查询任务
        Page<BountyTask> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BountyTask::getId, taskIds);

        if (status != null) {
            wrapper.eq(BountyTask::getStatus, status);
        }

        // Apply sorting
        applyBountySorting(wrapper, sortBy, sortOrder);

        IPage<BountyTask> taskPage = bountyTaskMapper.selectPage(pageParam, wrapper);
        List<BountyTask> tasks = taskPage.getRecords();

        // ========== N+1 Query Optimization: Batch preload owner info ==========

        Set<Long> ownerIds = tasks.stream()
                .map(BountyTask::getOwnerId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());

        Map<Long, User> ownerCache = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            List<User> owners = userMapper.selectBatchIds(ownerIds);
            owners.forEach(u -> ownerCache.put(u.getId(), u));
        }

        // Build responses with cached data and add user acceptance status
        List<BountyListResponse> responses = tasks.stream()
            .map(task -> {
                BountyListResponse response = buildListResponseCached(task, ownerCache);
                response.setIsAcceptedByMe(true);
                // Find acceptance status for this task
                BountyAcceptance acceptance = acceptances.stream()
                    .filter(a -> a.getTaskId().equals(task.getId()))
                    .findFirst()
                    .orElse(null);
                if (acceptance != null) {
                    response.setAcceptanceStatus(acceptance.getStatus());
                    boolean submitted = bountySubmissionMapper.existsByTaskAndHunter(task.getId(), userId);
                    response.setSubmitted(submitted);
                }
                return response;
            })
            .collect(Collectors.toList());

        Page<BountyListResponse> responsePage = new Page<>(taskPage.getCurrent(), taskPage.getSize(), taskPage.getTotal());
        responsePage.setRecords(responses);

        return responsePage;
    }

    /**
     * Create a bounty log entry
     */
    private void createLog(Long taskId, String taskTitle, Long hunterId, String hunterName,
                          String actionType, String actionDetail, BigDecimal rewardPoints) {
        BountyLog logEntry = new BountyLog();
        logEntry.setTaskId(taskId);
        logEntry.setTaskTitle(taskTitle);
        logEntry.setHunterId(hunterId);
        logEntry.setHunterName(hunterName);
        logEntry.setActionType(actionType);
        logEntry.setActionDetail(actionDetail);
        logEntry.setRewardPoints(rewardPoints);
        logEntry.setCreatedAt(LocalDateTime.now());
        bountyLogMapper.insert(logEntry);
    }

    /**
     * Build log response
     */
    private BountyLogResponse buildLogResponse(BountyLog log) {
        String actionTypeText;
        switch (log.getActionType()) {
            case "ACCEPT": actionTypeText = "接取悬赏"; break;
            case "SUBMIT": actionTypeText = "提交答案"; break;
            case "COMPLETE": actionTypeText = "完成悬赏"; break;
            case "REJECT": actionTypeText = "被拒绝"; break;
            case "CANCEL": actionTypeText = "取消悬赏"; break;
            default: actionTypeText = log.getActionType();
        }

        return BountyLogResponse.builder()
            .id(log.getId())
            .taskId(log.getTaskId())
            .taskTitle(log.getTaskTitle())
            .hunterId(log.getHunterId())
            .hunterName(log.getHunterName())
            .actionType(log.getActionType())
            .actionTypeText(actionTypeText)
            .actionDetail(log.getActionDetail())
            .rewardPoints(log.getRewardPoints())
            .createdAt(log.getCreatedAt())
            .build();
    }

    private BountyListResponse buildListResponse(BountyTask task) {
        User owner = userMapper.selectById(task.getOwnerId());

        return BountyListResponse.builder()
            .id(task.getId())
            .agentId(task.getAgentId())
            .authorType(task.getAuthorType())
            .authorName(task.getAuthorName())
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
            .submissionCount(task.getSubmissionCount())
            .deadline(task.getDeadline())
            .createdAt(task.getCreatedAt())
            .build();
    }

    /**
     * Build BountyListResponse using pre-loaded cached data (N+1 optimization).
     * Used by getBountyList for batch processing.
     */
    private BountyListResponse buildListResponseCached(BountyTask task, Map<Long, User> ownerCache) {
        User owner = ownerCache.get(task.getOwnerId());

        return BountyListResponse.builder()
            .id(task.getId())
            .agentId(task.getAgentId())
            .authorType(task.getAuthorType())
            .authorName(task.getAuthorName())
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
            .submissionCount(task.getSubmissionCount())
            .deadline(task.getDeadline())
            .createdAt(task.getCreatedAt())
            .build();
    }

    private BountyDetailResponse buildDetailResponse(BountyTask task) {
        User owner = userMapper.selectById(task.getOwnerId());
        String avatarUrl = null;

        if (task.getAgentId() != null) {
            // Agent bounty - get agent avatar
            Agent agent = agentMapper.selectById(task.getAgentId());
            avatarUrl = agent != null ? agent.getAvatarUrl() : null;
        } else {
            // Human bounty - get user avatar
            avatarUrl = owner != null ? owner.getAvatarUrl() : null;
        }

        return BountyDetailResponse.builder()
            .id(task.getId())
            .agentId(task.getAgentId())
            .authorType(task.getAuthorType())
            .authorName(task.getAuthorName())
            .agentAvatar(avatarUrl)
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

    private void validateAgentBountyQuota(Long agentId, BigDecimal rewardPoints) {
        if (rewardPoints != null && rewardPoints.compareTo(AGENT_SINGLE_BOUNTY_LIMIT) > 0) {
            throw new BusinessException(ErrorCode.REWARD_LIMIT_EXCEEDED);
        }
        LocalDateTime todayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        int createdToday = bountyTaskMapper.countByAgentIdSince(agentId, todayStart);
        if (createdToday >= AGENT_DAILY_BOUNTY_LIMIT) {
            throw new BusinessException(ErrorCode.AGENT_BOUNTY_DAILY_LIMIT);
        }
    }
}
