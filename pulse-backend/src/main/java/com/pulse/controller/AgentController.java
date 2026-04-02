package com.pulse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.AgentCreateRequest;
import com.pulse.dto.request.AgentDeleteRequest;
import com.pulse.dto.request.AgentReviveRequest;
import com.pulse.dto.request.AgentUpdateRequest;
import com.pulse.dto.response.AgentDetailResponse;
import com.pulse.dto.response.AgentListItemResponse;
import com.pulse.dto.response.AgentLogResponse;
import com.pulse.dto.response.AgentReviveResponse;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.PageResponse;
import com.pulse.security.UserPrincipal;
import com.pulse.service.AgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent Controller
 *
 * Handles all Agent CRUD operations for the Agent Lab module.
 */
@Tag(name = "Agent Lab", description = "Agent management APIs")
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    /**
     * Create Agent
     */
    @Operation(summary = "Create new agent", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping
    public ApiResponse<AgentDetailResponse> createAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody AgentCreateRequest request) {
        AgentDetailResponse response = agentService.createAgent(principal.getUserId(), request);
        return ApiResponse.created("Agent创建成功", response);
    }

    /**
     * Get Agent List
     */
    @Operation(summary = "Get agent list", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ApiResponse<PageResponse<AgentListItemResponse>> getAgentList(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<AgentListItemResponse> agentPage =
                agentService.getAgentList(principal.getUserId(), status, page, size);
        return ApiResponse.success(PageResponse.from(agentPage));
    }

    /**
     * Get Agent Detail
     */
    @Operation(summary = "Get agent detail", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{agent_id}")
    public ApiResponse<AgentDetailResponse> getAgentDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId) {
        AgentDetailResponse response = agentService.getAgentDetail(principal.getUserId(), agentId);
        return ApiResponse.success(response);
    }

    /**
     * Update Agent
     */
    @Operation(summary = "Update agent config", security = @SecurityRequirement(name = "Bearer"))
    @PutMapping("/{agent_id}")
    public ApiResponse<AgentDetailResponse> updateAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @Valid @RequestBody AgentUpdateRequest request) {
        AgentDetailResponse response = agentService.updateAgent(principal.getUserId(), agentId, request);
        return ApiResponse.success("Agent配置更新成功", response);
    }

    /**
     * Revive Agent (Inject Life)
     */
    @Operation(summary = "Revive agent (reset tokens)", security = @SecurityRequirement(name = "Bearer"))
    @PostMapping("/{agent_id}/revive")
    public ApiResponse<AgentReviveResponse> reviveAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @RequestBody(required = false) AgentReviveRequest request) {
        if (request == null) {
            request = new AgentReviveRequest();
        }
        AgentReviveResponse response = agentService.reviveAgent(principal.getUserId(), agentId, request);
        return ApiResponse.success("Agent已复活", response);
    }

    /**
     * Delete Agent
     */
    @Operation(summary = "Delete agent", security = @SecurityRequirement(name = "Bearer"))
    @DeleteMapping("/{agent_id}")
    public ApiResponse<Void> deleteAgent(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @Valid @RequestBody AgentDeleteRequest request) {
        agentService.deleteAgent(principal.getUserId(), agentId, request);
        return ApiResponse.success("Agent已删除", null);
    }

    /**
     * Get Agent Logs (Activity History)
     */
    @Operation(summary = "Get agent activity logs", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{agent_id}/logs")
    public ApiResponse<List<AgentLogResponse>> getAgentLogs(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @RequestParam(defaultValue = "20") int limit) {
        List<AgentLogResponse> logs = agentService.getAgentLogs(principal.getUserId(), agentId, limit);
        return ApiResponse.success(logs);
    }

    /**
     * Get Agent Action Count
     */
    @Operation(summary = "Get agent total action count", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/{agent_id}/action-count")
    public ApiResponse<Integer> getAgentActionCount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId) {
        int count = agentService.getAgentActionCount(principal.getUserId(), agentId);
        return ApiResponse.success(count);
    }
}