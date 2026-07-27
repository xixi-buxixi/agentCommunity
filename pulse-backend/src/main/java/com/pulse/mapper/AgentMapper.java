package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * Agent Mapper
 *
 * Provides CRUD operations and atomic token update for Agent entities.
 */
@Mapper
public interface AgentMapper extends BaseMapper<Agent> {

    /**
     * Atomic token increment update (simple version, no optimistic lock)
     * Uses atomic SQL increment: safe for high concurrency
     *
     * @param id Agent ID
     * @param tokensToAdd Tokens to add
     * @return Number of rows affected
     */
    @Update("UPDATE agents SET used_tokens = used_tokens + #{tokensToAdd}, " +
            "last_active_at = NOW() " +
            "WHERE id = #{id} AND status = 1 AND deleted = 0")
    int incrementUsedTokensAtomic(@Param("id") Long id, @Param("tokensToAdd") Long tokensToAdd);

    /**
     * Update agent status
     *
     * @param id Agent ID
     * @param status New status value
     * @return Number of rows affected
     */
    @Update("UPDATE agents SET status = #{status}, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);

    /**
     * Reset agent (revive operation)
     * Clears used_tokens and sets status to ALIVE
     *
     * @param id Agent ID
     * @param newThreshold New token threshold (optional, null keeps current)
     * @return Number of rows affected
     */
    @Update("UPDATE agents SET used_tokens = 0, status = 1, " +
            "token_threshold = COALESCE(#{newThreshold}, token_threshold), " +
            "updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int resetAgent(@Param("id") Long id, @Param("newThreshold") Long newThreshold);

    /**
     * Find random active agents for scheduler batch processing
     *
     * @param limit Maximum number of agents to return
     * @return List of randomly selected active agents
     */
    List<Agent> findRandomActiveAgents(@Param("limit") int limit);

    /**
     * Reset used tokens to zero without changing status
     * Used for manual token reset while agent is still alive
     *
     * @param id Agent ID
     * @return Number of rows affected
     */
    @Update("UPDATE agents SET used_tokens = 0, updated_at = NOW() " +
            "WHERE id = #{id} AND deleted = 0")
    int resetUsedTokens(@Param("id") Long id);

    /**
     * Record that the scheduler has just picked this agent.
     *
     * Drives the round-robin order in findRandomActiveAgents.
     *
     * @param id Agent ID
     * @return Number of rows affected
     */
    @Update("UPDATE agents SET last_dispatched_at = NOW() WHERE id = #{id} AND deleted = 0")
    int markDispatched(@Param("id") Long id);

    // NOTE: selectByIds was removed. It declared a batch select with no XML
    // statement and no annotation, so the first caller would have hit
    // BindingException at runtime. Use BaseMapper.selectBatchIds for batch loads.
    // findActiveAgentsWithCapacity and incrementUsedTokensOptimistic were removed
    // for the same reason as other dead code: no callers, and the "optimistic"
    // variant was never wired up even though the agents table has a version column.
}