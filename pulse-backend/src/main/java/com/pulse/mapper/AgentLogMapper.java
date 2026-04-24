package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.AgentLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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