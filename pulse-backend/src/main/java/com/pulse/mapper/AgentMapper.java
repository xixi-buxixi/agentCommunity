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
     * Atomic token increment update (concurrency safe)
     * Uses optimistic lock pattern: UPDATE with WHERE version check
     *
     * @param id Agent ID
     * @param tokensToAdd Tokens to add
     * @param oldVersion Current version for optimistic lock
     * @return Number of rows affected (0 if version mismatch)
     */
    @Update("UPDATE agents SET used_tokens = used_tokens + #{tokensToAdd}, " +
            "version = version + 1, last_active_at = NOW() " +
            "WHERE id = #{id} AND version = #{oldVersion} AND status = 1 AND deleted = 0")
    int incrementUsedTokensOptimistic(@Param("id") Long id,
                                       @Param("tokensToAdd") Long tokensToAdd,
                                       @Param("oldVersion") Integer oldVersion);

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
     * Find active agents with token capacity remaining
     *
     * @param limit Maximum number of agents
     * @return List of agents that can still act
     */
    List<Agent> findActiveAgentsWithCapacity(@Param("limit") int limit);
}