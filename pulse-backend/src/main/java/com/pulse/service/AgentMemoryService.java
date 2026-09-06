package com.pulse.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.dto.AgentMemoryCard;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.dto.request.AgentMemoryUpdateRequest;
import com.pulse.dto.response.AgentMemoryResponse;
import com.pulse.entity.Agent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Memory Service Interface
 */
public interface AgentMemoryService {

    /**
     * List one agent's memories for its owner.
     *
     * @param status     optional status filter (1 ACTIVE / 0 DISABLED / 2 DEPRECATED)
     * @param memoryType optional type filter, must be a known {@code MemoryType}
     */
    Page<AgentMemoryResponse> getMemories(Long ownerId, Long agentId, Integer status,
                                          String memoryType, int page, int size);

    /**
     * Enable/disable or correct one memory (the owner brake).
     */
    AgentMemoryResponse updateMemory(Long ownerId, Long agentId, Long memoryId,
                                     AgentMemoryUpdateRequest request);

    /**
     * The memories to inject into this agent's next decision prompt, in injection
     * order (PERSONA_TRAIT before PERSONA_FACT, then importance, then recency).
     *
     * Only ACTIVE, non-expired memories are ever returned - a memory the owner
     * disabled, or one the system retired, must never reach the model.
     */
    List<AgentMemoryCard> selectForInjection(Long agentId);

    /**
     * Assemble the behaviour pack for one reflection call: what the agent did since
     * {@code since} plus the traits it already holds. Everything is sanitized,
     * flattened and bounded here, not by the caller.
     */
    ReflectionContext buildReflectionContext(Long agentId, LocalDateTime since);

    /**
     * Persist a reflection answer.
     *
     * Treats every field as hostile: ids are checked against the agent's own
     * PERSONA_TRAIT rows, scores are clamped, content and evidence are sanitized, and
     * the number of new traits is capped. Returns the number of rows actually changed.
     */
    int applyReflection(Agent agent, ReflectionResult result);

    /**
     * Write the PERSONA_FACT cards for a batch of executed actions and apply the
     * retention cap.
     *
     * MUST be called after the action transaction has committed, and never throws:
     * a memory card is an observability/continuity feature, so a failure here may
     * only produce a log line - the agent's action and token charge stand.
     */
    void recordActionMemories(Agent agent, List<AgentActionOutcome> outcomes);
}
