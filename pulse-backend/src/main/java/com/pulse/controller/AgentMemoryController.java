package com.pulse.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.request.AgentMemoryUpdateRequest;
import com.pulse.dto.response.AgentMemoryResponse;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.PageResponse;
import com.pulse.security.UserPrincipal;
import com.pulse.service.AgentMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent Memory Controller
 *
 * The owner-facing brake on agent memory: view, disable/enable, correct.
 *
 * Mapped under the existing /api/v1/agents family on purpose. A /api/v2 memory
 * endpoint was once called by the frontend and never existed on the backend
 * (see docs/contracts/overview.md); the real one lives here.
 */
@Tag(name = "Agent Memory", description = "Agent memory management APIs")
@RestController
@RequestMapping("/api/v1/agents/{agent_id}/memories")
@RequiredArgsConstructor
public class AgentMemoryController {

    private final AgentMemoryService agentMemoryService;

    /**
     * Get Agent Memories (paged, owner only)
     */
    @Operation(summary = "Get agent memories", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ApiResponse<PageResponse<AgentMemoryResponse>> getMemories(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @RequestParam(required = false) Integer status,
            @RequestParam(name = "memory_type", required = false) String memoryType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<AgentMemoryResponse> memoryPage = agentMemoryService.getMemories(
                principal.getUserId(), agentId, status, memoryType, page, size);
        return ApiResponse.success(PageResponse.from(memoryPage));
    }

    /**
     * Update One Memory (disable/enable or correct content)
     */
    @Operation(summary = "Disable/enable or correct one memory",
            security = @SecurityRequirement(name = "Bearer"))
    @PatchMapping("/{memory_id}")
    public ApiResponse<AgentMemoryResponse> updateMemory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable("agent_id") Long agentId,
            @PathVariable("memory_id") Long memoryId,
            @Valid @RequestBody AgentMemoryUpdateRequest request) {
        AgentMemoryResponse response = agentMemoryService.updateMemory(
                principal.getUserId(), agentId, memoryId, request);
        return ApiResponse.success("记忆已更新", response);
    }
}
