package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.AgentLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Log Mapper
 *
 * Provides CRUD operations for AgentLog entities.
 */
@Mapper
public interface AgentLogMapper extends BaseMapper<AgentLog> {

    /**
     * Find logs by agent_id ordered by created_at desc
     */
    @Select("SELECT * FROM agent_logs WHERE agent_id = #{agentId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AgentLog> findByAgentId(@Param("agentId") Long agentId, @Param("limit") int limit);

    /**
     * Behaviour logs written since a cut-off, oldest first - the chronological half of
     * the reflection behaviour pack.
     *
     * Reflection's own audit rows are excluded. They are bookkeeping, not behaviour:
     * feeding "REFLECTION_SUCCESS" back in as something the agent did would have the
     * job distil its own paperwork, and (worse) would make yesterday's reflection count
     * as today's activity - see {@code AgentMapper#findAliveAgentsActiveSince}.
     */
    @Select("SELECT * FROM agent_logs WHERE agent_id = #{agentId} AND created_at >= #{since} "
            + "AND (action_result IS NULL OR action_result NOT LIKE 'REFLECTION%') "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<AgentLog> findByAgentIdSince(@Param("agentId") Long agentId,
                                      @Param("since") LocalDateTime since,
                                      @Param("limit") int limit);

    /**
     * Whether this agent's reflection for the day is already settled.
     *
     * SUCCESS and SKIPPED both count as settled: SKIPPED means the gateway answered
     * without calling a model, and repeating that costs a pointless HTTP round trip for
     * a guaranteed no-op. FAILED is deliberately excluded, so a broken gateway or a
     * rolled-back write can be retried.
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE agent_id = #{agentId} "
            + "AND created_at >= #{since} "
            + "AND action_result IN ('REFLECTION_SUCCESS', 'REFLECTION_SKIPPED')")
    int countCompletedReflectionsSince(@Param("agentId") Long agentId,
                                       @Param("since") LocalDateTime since);

    /**
     * Count logs by agent_id
     */
    @Select("SELECT COUNT(*) FROM agent_logs WHERE agent_id = #{agentId}")
    int countByAgentId(@Param("agentId") Long agentId);

    /**
     * Find logs by owner's agents (all agents owned by user)
     */
    @Select("SELECT al.* FROM agent_logs al " +
            "JOIN agents a ON al.agent_id = a.id " +
            "WHERE a.owner_id = #{ownerId} " +
            "ORDER BY al.created_at DESC LIMIT #{limit}")
    List<AgentLog> findByOwnerId(@Param("ownerId") Long ownerId, @Param("limit") int limit);
}