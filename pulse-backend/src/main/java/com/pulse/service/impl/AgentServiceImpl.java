package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.AgentCreateRequest;
import com.pulse.dto.request.AgentDeleteRequest;
import com.pulse.dto.request.AgentReviveRequest;
import com.pulse.dto.request.AgentUpdateRequest;
import com.pulse.dto.response.AgentDetailResponse;
import com.pulse.dto.response.AgentListItemResponse;
import com.pulse.dto.response.AgentLogResponse;
import com.pulse.dto.response.AgentReviveResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
import com.pulse.entity.User;
import com.pulse.enums.AgentStatus;
import com.pulse.enums.ActionType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.entity.Post;
import com.pulse.service.AgentService;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.service.support.WakeScheduleCalculator;
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements AgentService {

    private final AgentMapper agentMapper;
    private final AgentLogMapper agentLogMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final AesUtil aesUtil;
    private final SchemaCapabilities schemaCapabilities;
    private final WakeScheduleCalculator wakeScheduleCalculator;

    /**
     * Rhythm wake-ups per day used when seeding a new agent's schedule; the scheduler
     * owns the same knob for recomputation.
     */
    @Value("${scheduler.agent-loop.target-daily-rhythm-wakes:3}")
    private int targetDailyRhythmWakes;

    @Value("${scheduler.agent-loop.default-daily-wake-budget:4}")
    private int defaultDailyWakeBudget;

    /** Daytime window used when a stored bound is missing or unusable. */
    private static final int DEFAULT_WAKE_HOURS_START = 9;
    private static final int DEFAULT_WAKE_HOURS_END = 23;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    /**
     * Local ISO-8601 without a zone suffix, for the wake-rhythm timestamps.
     *
     * Deliberately NOT the formatter above. That one appends a literal 'Z', which claims
     * the value is UTC when it is in fact server-local - harmless for a "created 3 days
     * ago" label, but next_wake_at is a clock time the owner reads as "my agent wakes at
     * 21:40", and being eight hours out makes the whole rhythm feature look broken. New
     * fields therefore state local time honestly, matching how the bounty responses ship
     * their LocalDateTime values.
     */
    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Override
    @Transactional
    public AgentDetailResponse createAgent(Long ownerId, AgentCreateRequest request) {
        String name = normalizeText(request.getName());
        String baseUrl = normalizeText(request.getBaseUrl());
        String apiKey = normalizeText(request.getApiKey());
        String modelName = normalizeText(request.getModelName());
        String systemPrompt = normalizeText(request.getSystemPrompt());

        // Check if name already exists for this owner
        if (agentNameExists(ownerId, name)) {
            throw new BusinessException(ErrorCode.AGENT_NAME_EXISTS);
        }

        // Create agent with encrypted API key
        Agent agent = new Agent();
        agent.setOwnerId(ownerId);
        agent.setName(name);
        agent.setAvatarUrl(request.getAvatarUrl());
        // Trim baseUrl to avoid URL encoding issues (spaces become %20)
        agent.setBaseUrl(baseUrl);
        agent.setApiKey(aesUtil.encrypt(apiKey)); // Encrypt API Key
        agent.setModelName(modelName);
        agent.setSystemPrompt(systemPrompt);
        agent.setTokenThreshold(request.getTokenThreshold());
        agent.setUsedTokens(0L);
        agent.setStatus(AgentStatus.ALIVE.getCode());
        agent.setIsUnlimited(request.getIsUnlimited());
        agent.setVersion(0);

        agentMapper.insert(agent);
        assignInitialWakeRhythm(agent);

        log.info("Agent created: agentId={}, ownerId={}, name={}", agent.getId(), ownerId, agent.getName());

        return buildDetailResponse(agent, ownerId);
    }

    @Override
    public Page<AgentListItemResponse> getAgentList(Long ownerId, Integer status, int page, int size) {
        Page<Agent> pageParam = new Page<>(page, Math.min(size, 50));

        LambdaQueryWrapper<Agent> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Agent::getOwnerId, ownerId);

        if (status != null) {
            queryWrapper.eq(Agent::getStatus, status);
        }

        queryWrapper.orderByDesc(Agent::getCreatedAt);

        Page<Agent> agentPage = agentMapper.selectPage(pageParam, queryWrapper);

        // Convert to response
        Page<AgentListItemResponse> responsePage = new Page<>(agentPage.getCurrent(), agentPage.getSize(), agentPage.getTotal());
        Map<Long, AgentWakeSettings> wakeSettings = loadWakeSettings(agentPage.getRecords());
        List<AgentListItemResponse> responses = agentPage.getRecords().stream()
                .map(agent -> buildListItemResponse(agent, wakeSettings.get(agent.getId())))
                .collect(Collectors.toList());
        responsePage.setRecords(responses);

        return responsePage;
    }

    @Override
    public AgentDetailResponse getAgentDetail(Long ownerId, Long agentId) {
        Agent agent = validateAgentOwnership(ownerId, agentId);
        return buildDetailResponse(agent, ownerId);
    }

    @Override
    @Transactional
    public AgentDetailResponse updateAgent(Long ownerId, Long agentId, AgentUpdateRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Update fields if provided
        if (request.getName() != null) {
            String normalizedName = normalizeText(request.getName());
            if (!normalizedName.equals(agent.getName()) && agentNameExists(ownerId, normalizedName)) {
                throw new BusinessException(ErrorCode.AGENT_NAME_EXISTS);
            }
            agent.setName(normalizedName);
        }

        if (request.getAvatarUrl() != null) {
            agent.setAvatarUrl(request.getAvatarUrl());
        }

        if (request.getBaseUrl() != null) {
            // Trim baseUrl to avoid URL encoding issues
            agent.setBaseUrl(normalizeText(request.getBaseUrl()));
        }

        if (request.getApiKey() != null) {
            agent.setApiKey(aesUtil.encrypt(normalizeText(request.getApiKey()))); // Encrypt new API Key
        }

        if (request.getModelName() != null) {
            agent.setModelName(normalizeText(request.getModelName()));
        }

        if (request.getSystemPrompt() != null) {
            agent.setSystemPrompt(normalizeText(request.getSystemPrompt()));
        }

        if (request.getTokenThreshold() != null) {
            agent.setTokenThreshold(request.getTokenThreshold());
        }

        if (request.getIsUnlimited() != null) {
            agent.setIsUnlimited(request.getIsUnlimited());
        }

        agentMapper.updateById(agent);

        // Separate, capability-guarded statement: the rhythm columns are invisible to
        // updateById on purpose (see Agent), so an un-migrated database keeps working.
        applyWakeSettings(agent, request);

        log.info("Agent updated: agentId={}, ownerId={}", agentId, ownerId);

        return buildDetailResponse(agent, ownerId);
    }

    @Override
    @Transactional
    public AgentReviveResponse reviveAgent(Long ownerId, Long agentId, AgentReviveRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Reset tokens and status
        Long newThreshold = request.getNewThreshold() != null ? request.getNewThreshold() : agent.getTokenThreshold();
        agentMapper.resetAgent(agentId, newThreshold);

        log.info("Agent revived: agentId={}, ownerId={}, newThreshold={}", agentId, ownerId, newThreshold);

        return AgentReviveResponse.builder()
                .id(agentId)
                .status(AgentStatus.ALIVE.getCode())
                .usedTokens(0L)
                .tokenThreshold(newThreshold)
                .revivedAt(formatDateTime(LocalDateTime.now()))
                .build();
    }

    @Override
    @Transactional
    public void deleteAgent(Long ownerId, Long agentId, AgentDeleteRequest request) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Verify confirmation name matches
        if (!agent.getName().equals(request.getConfirmName())) {
            throw new BusinessException(ErrorCode.AGENT_CONFIRM_NAME_MISMATCH);
        }

        agentMapper.deleteById(agentId);

        log.info("Agent deleted: agentId={}, ownerId={}", agentId, ownerId);
    }

    @Override
    public boolean agentNameExists(Long ownerId, String name) {
        LambdaQueryWrapper<Agent> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Agent::getOwnerId, ownerId);
        queryWrapper.eq(Agent::getName, name);
        return agentMapper.selectCount(queryWrapper) > 0;
    }

    @Override
    public List<AgentLogResponse> getAgentLogs(Long ownerId, Long agentId, int limit) {
        // Validate ownership first
        validateAgentOwnership(ownerId, agentId);

        List<AgentLog> logs = agentLogMapper.findByAgentId(agentId, Math.min(limit, 50));
        Map<Long, Post> targetPosts = loadTargetPosts(logs);

        return logs.stream()
                .map(entry -> buildLogResponse(entry, targetPosts))
                .collect(Collectors.toList());
    }

    @Override
    public int getAgentActionCount(Long ownerId, Long agentId) {
        // Validate ownership first
        validateAgentOwnership(ownerId, agentId);

        return agentLogMapper.countByAgentId(agentId);
    }

    @Override
    @Transactional
    public AgentDetailResponse resetTokens(Long ownerId, Long agentId) {
        Agent agent = validateAgentOwnership(ownerId, agentId);

        // Reset used tokens to zero, keep threshold unchanged
        agentMapper.resetUsedTokens(agentId);

        log.info("Agent tokens reset: agentId={}, ownerId={}", agentId, ownerId);

        // Fetch updated agent
        agent = agentMapper.selectById(agentId);
        return buildDetailResponse(agent, ownerId);
    }

    @Override
    public List<AgentLogResponse> getAllAgentLogs(Long ownerId, int limit) {
        List<AgentLog> logs = agentLogMapper.findByOwnerId(ownerId, Math.min(limit, 50));
        Map<Long, Post> targetPosts = loadTargetPosts(logs);

        return logs.stream()
                .map(entry -> buildLogResponse(entry, targetPosts))
                .collect(Collectors.toList());
    }

    // ========== Helper Methods ==========

    /**
     * Give a new agent its own routine.
     *
     * Random hours rather than one shared default: if every agent were awake 09-23 the
     * community would still pulse, just on a different beat. Written with an explicit
     * UPDATE after the insert, because the rhythm columns are deliberately outside the
     * generated INSERT - a database without the migration must still be able to create
     * agents.
     */
    private void assignInitialWakeRhythm(Agent agent) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return;
        }
        int[] hours = wakeScheduleCalculator.randomActiveHours();
        LocalDateTime firstWake = wakeScheduleCalculator.initialWake(
                LocalDateTime.now(), hours[0], hours[1], targetDailyRhythmWakes);
        try {
            agentMapper.updateWakeSettings(agent.getId(), hours[0], hours[1],
                    defaultDailyWakeBudget, firstWake);
            agent.setWakeHoursStart(hours[0]);
            agent.setWakeHoursEnd(hours[1]);
            agent.setDailyWakeBudget(defaultDailyWakeBudget);
            agent.setNextWakeAt(firstWake);
        } catch (Exception e) {
            // An agent without a rhythm still works - the legacy loop or a later edit will
            // pick it up - so this must not fail the creation the user asked for.
            log.warn("Could not seed the wake rhythm for agent {}: {}", agent.getId(), e.getMessage());
        }
    }

    /**
     * Apply the owner's rhythm settings.
     *
     * Both hour columns are always written together, defaulting the one the request left
     * out: a window assembled from one new bound and one old one would have the scheduler
     * computing against hours the owner never chose.
     *
     * Changing the active hours re-plans the next wake-up immediately - an owner who moves
     * an agent to nights should not have to wait out the old schedule.
     */
    private void applyWakeSettings(Agent agent, AgentUpdateRequest request) {
        boolean requested = request.getWakeHoursStart() != null
                || request.getWakeHoursEnd() != null
                || request.getDailyWakeBudget() != null;
        if (!requested) {
            return;
        }
        if (!schemaCapabilities.isWakeQueueSchema()) {
            // An explicit 409 beats a 500 from an unknown column: the feature is simply
            // not enabled on this deployment yet.
            throw new BusinessException(ErrorCode.AGENT_WAKE_SETTINGS_UNAVAILABLE);
        }

        AgentWakeSettings current = agentMapper.findWakeSettings(agent.getId());
        int start = resolveHour(request.getWakeHoursStart(),
                current != null ? current.getWakeHoursStart() : null, DEFAULT_WAKE_HOURS_START);
        int end = resolveHour(request.getWakeHoursEnd(),
                current != null ? current.getWakeHoursEnd() : null, DEFAULT_WAKE_HOURS_END);
        int budget = request.getDailyWakeBudget() != null
                ? request.getDailyWakeBudget()
                : (current != null && current.getDailyWakeBudget() != null
                        ? current.getDailyWakeBudget()
                        : defaultDailyWakeBudget);

        boolean hoursChanged = request.getWakeHoursStart() != null || request.getWakeHoursEnd() != null;
        LocalDateTime nextWake = hoursChanged
                ? wakeScheduleCalculator.initialWake(LocalDateTime.now(), start, end, targetDailyRhythmWakes)
                : (current != null ? current.getNextWakeAt() : null);

        agentMapper.updateWakeSettings(agent.getId(), start, end, budget, nextWake);

        agent.setWakeHoursStart(start);
        agent.setWakeHoursEnd(end);
        agent.setDailyWakeBudget(budget);
        agent.setNextWakeAt(nextWake);
        if (current != null) {
            agent.setWakeCountToday(current.getWakeCountToday());
            agent.setWakeCountDate(current.getWakeCountDate());
        }
    }

    private int resolveHour(Integer requested, Integer stored, int fallback) {
        if (requested != null) {
            return requested;
        }
        if (stored != null && stored >= 0 && stored <= 23) {
            return stored;
        }
        return fallback;
    }

    /**
     * Load the rhythm for display. Absent schema or an absent row simply means the
     * response carries no rhythm fields, never an error.
     */
    private AgentWakeSettings loadWakeSettings(Long agentId) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return null;
        }
        try {
            return agentMapper.findWakeSettings(agentId);
        } catch (Exception e) {
            log.warn("Could not read wake settings for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    private Map<Long, AgentWakeSettings> loadWakeSettings(List<Agent> agents) {
        if (!schemaCapabilities.isWakeQueueSchema() || agents.isEmpty()) {
            return Map.of();
        }
        try {
            List<Long> ids = agents.stream().map(Agent::getId).collect(Collectors.toList());
            Map<Long, AgentWakeSettings> byId = new HashMap<>();
            for (AgentWakeSettings settings : agentMapper.findWakeSettingsByIds(ids)) {
                byId.put(settings.getAgentId(), settings);
            }
            return byId;
        } catch (Exception e) {
            log.warn("Could not read wake settings for a list page: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Validate agent belongs to owner
     */
    private Agent validateAgentOwnership(Long ownerId, Long agentId) {
        Agent agent = agentMapper.selectById(agentId);

        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }

        if (!agent.getOwnerId().equals(ownerId)) {
            throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
        }

        return agent;
    }

    private String normalizeText(String value) {
        return value != null ? value.trim() : null;
    }

    /**
     * Build list item response
     */
    private AgentListItemResponse buildListItemResponse(Agent agent) {
        return buildListItemResponse(agent, null);
    }

    private AgentListItemResponse buildListItemResponse(Agent agent, AgentWakeSettings wake) {
        AgentStatus status = AgentStatus.fromCode(agent.getStatus());

        return AgentListItemResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .avatarUrl(agent.getAvatarUrl())
                .status(agent.getStatus())
                .statusText(status.getText())
                .usedTokens(agent.getUsedTokens())
                .tokenThreshold(agent.getTokenThreshold())
                .tokenPercentage(agent.getTokenPercentage())
                .modelName(agent.getModelName())
                .lastActiveAt(formatDateTime(agent.getLastActiveAt()))
                .wakeHoursStart(wake != null ? wake.getWakeHoursStart() : null)
                .wakeHoursEnd(wake != null ? wake.getWakeHoursEnd() : null)
                .dailyWakeBudget(wake != null ? wake.getDailyWakeBudget() : null)
                .nextWakeAt(wake != null ? formatLocalDateTime(wake.getNextWakeAt()) : null)
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .build();
    }

    /**
     * Build detail response
     */
    private AgentDetailResponse buildDetailResponse(Agent agent, Long ownerId) {
        AgentStatus status = AgentStatus.fromCode(agent.getStatus());
        AgentWakeSettings wake = loadWakeSettings(agent.getId());

        // Get owner name
        User owner = userMapper.selectById(ownerId);
        String ownerName = owner != null ? owner.getUsername() : null;

        // Mask API key for display (decrypt first, then mask).
        // Decryption now throws on failure, but a single unreadable key must not
        // take the whole detail view down - show it as fully masked instead.
        String maskedApiKey;
        try {
            maskedApiKey = aesUtil.maskApiKey(aesUtil.decrypt(agent.getApiKey()));
        } catch (RuntimeException e) {
            log.warn("Unable to decrypt stored API key for display: agentId={}", agent.getId());
            maskedApiKey = "****";
        }

        return AgentDetailResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .avatarUrl(agent.getAvatarUrl())
                .status(agent.getStatus())
                .statusText(status.getText())
                .usedTokens(agent.getUsedTokens())
                .tokenThreshold(agent.getTokenThreshold())
                .tokenPercentage(agent.getTokenPercentage())
                .isUnlimited(agent.getIsUnlimited())
                .baseUrl(agent.getBaseUrl())
                .apiKeyMasked(maskedApiKey)
                .modelName(agent.getModelName())
                .systemPrompt(agent.getSystemPrompt())
                .ownerId(ownerId)
                .ownerName(ownerName)
                .lastActiveAt(formatDateTime(agent.getLastActiveAt()))
                .wakeHoursStart(wake != null ? wake.getWakeHoursStart() : null)
                .wakeHoursEnd(wake != null ? wake.getWakeHoursEnd() : null)
                .dailyWakeBudget(wake != null ? wake.getDailyWakeBudget() : null)
                .nextWakeAt(wake != null ? formatLocalDateTime(wake.getNextWakeAt()) : null)
                // Stale counters read as zero: the reset is lazy, and showing yesterday's
                // number would misreport the owner's remaining budget
                .wakeCountToday(wake != null ? wake.wakeCountFor(LocalDate.now()) : null)
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .updatedAt(formatDateTime(agent.getUpdatedAt()))
                .build();
    }

    /**
     * Format a wake timestamp as local ISO-8601, without pretending it is UTC.
     */
    private String formatLocalDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(LOCAL_DATE_TIME_FORMATTER);
    }

    /**
     * Format datetime to ISO 8601 string
     */
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(DATE_FORMATTER);
    }

    /**
     * Build log response
     */
    /**
     * Batch-load the posts referenced by a page of logs.
     *
     * buildLogResponse used to issue one selectById per row, so a 50-row log page
     * meant up to 50 extra queries.
     */
    private Map<Long, Post> loadTargetPosts(List<AgentLog> logs) {
        Set<Long> postIds = logs.stream()
                .map(AgentLog::getTargetPostId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Post> byId = new HashMap<>();
        for (Post post : postMapper.selectBatchIds(postIds)) {
            byId.put(post.getId(), post);
        }
        return byId;
    }

    private AgentLogResponse buildLogResponse(AgentLog log) {
        return buildLogResponse(log, Map.of());
    }

    /**
     * @param postPreviews target posts pre-loaded by id; an empty map falls back to
     *                     fetching this single post
     */
    private AgentLogResponse buildLogResponse(AgentLog log, Map<Long, Post> postPreviews) {
        ActionType actionType = ActionType.fromCode(log.getActionType());

        // Get target post preview for REPLY actions
        String targetPostPreview = null;
        if (log.getTargetPostId() != null) {
            Post targetPost = postPreviews.get(log.getTargetPostId());
            if (targetPost == null && postPreviews.isEmpty()) {
                targetPost = postMapper.selectById(log.getTargetPostId());
            }
            if (targetPost != null && targetPost.getContent() != null) {
                // Truncate to first 50 characters
                targetPostPreview = targetPost.getContent().length() > 50
                        ? targetPost.getContent().substring(0, 50) + "..."
                        : targetPost.getContent();
            }
        }

        // Truncate action content for display
        String contentPreview = null;
        if (log.getActionContent() != null) {
            contentPreview = log.getActionContent().length() > 100
                    ? log.getActionContent().substring(0, 100) + "..."
                    : log.getActionContent();
        }

        return AgentLogResponse.builder()
                .id(log.getId())
                .agentId(log.getAgentId())
                .actionType(log.getActionType())
                .actionTypeText(actionType.getText())
                .targetPostId(log.getTargetPostId())
                .targetPostPreview(targetPostPreview)
                .tokensConsumed(log.getTokensConsumed())
                .result(log.getActionResult())
                .content(contentPreview)
                .createdAt(formatDateTime(log.getCreatedAt()))
                .build();
    }
}
