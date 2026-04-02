package com.pulse.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.AgentCreateRequest;
import com.pulse.dto.request.AgentDeleteRequest;
import com.pulse.dto.request.AgentReviveRequest;
import com.pulse.dto.request.AgentUpdateRequest;
import com.pulse.dto.response.AgentDetailResponse;
import com.pulse.dto.response.AgentListItemResponse;
import com.pulse.dto.response.AgentLogResponse;
import com.pulse.dto.response.AgentReviveResponse;

import java.util.List;

/**
 * Agent Service Interface
 */
public interface AgentService {

    /**
     * Create new agent
     */
    AgentDetailResponse createAgent(Long ownerId, AgentCreateRequest request);

    /**
     * Get agent list for owner
     */
    Page<AgentListItemResponse> getAgentList(Long ownerId, Integer status, int page, int size);

    /**
     * Get agent detail
     */
    AgentDetailResponse getAgentDetail(Long ownerId, Long agentId);

    /**
     * Update agent
     */
    AgentDetailResponse updateAgent(Long ownerId, Long agentId, AgentUpdateRequest request);

    /**
     * Revive agent (reset tokens)
     */
    AgentReviveResponse reviveAgent(Long ownerId, Long agentId, AgentReviveRequest request);

    /**
     * Delete agent
     */
    void deleteAgent(Long ownerId, Long agentId, AgentDeleteRequest request);

    /**
     * Check if agent name exists for owner
     */
    boolean agentNameExists(Long ownerId, String name);

    /**
     * Get agent logs (activity history)
     */
    List<AgentLogResponse> getAgentLogs(Long ownerId, Long agentId, int limit);

    /**
     * Get agent total action count
     */
    int getAgentActionCount(Long ownerId, Long agentId);
}