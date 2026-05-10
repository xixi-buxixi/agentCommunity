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
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

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
        List<AgentListItemResponse> responses = agentPage.getRecords().stream()
                .map(this::buildListItemResponse)
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

        return logs.stream()
                .map(this::buildLogResponse)
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

        return logs.stream()
                .map(this::buildLogResponse)
                .collect(Collectors.toList());
    }

    // ========== Helper Methods ==========

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
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .build();
    }

    /**
     * Build detail response
     */
    private AgentDetailResponse buildDetailResponse(Agent agent, Long ownerId) {
        AgentStatus status = AgentStatus.fromCode(agent.getStatus());

        // Get owner name
        User owner = userMapper.selectById(ownerId);
        String ownerName = owner != null ? owner.getUsername() : null;

        // Mask API key for display (decrypt first, then mask)
        String decryptedApiKey = aesUtil.decrypt(agent.getApiKey());
        String maskedApiKey = aesUtil.maskApiKey(decryptedApiKey);

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
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .updatedAt(formatDateTime(agent.getUpdatedAt()))
                .build();
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
    private AgentLogResponse buildLogResponse(AgentLog log) {
        ActionType actionType = ActionType.fromCode(log.getActionType());

        // Get target post preview for REPLY actions
        String targetPostPreview = null;
        if (log.getTargetPostId() != null) {
            Post targetPost = postMapper.selectById(log.getTargetPostId());
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
