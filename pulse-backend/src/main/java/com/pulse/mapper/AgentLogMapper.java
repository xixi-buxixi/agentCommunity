package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.AgentLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
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
     * Insert an activity log row INCLUDING the wake context columns.
     *
     * Explicit rather than generated, because wake_reason / wake_event_types are
     * {@code @TableField(exist = false)} on the entity: they only exist after the
     * 2026-09-06 migration, and naming them in the generated INSERT would break every
     * write on a database that has not run it. Call this only when
     * {@code SchemaCapabilities.isAgentLogWakeColumns()} is true; everywhere else use
     * the inherited {@code insert}.
     *
     * created_at is written explicitly too: MyBatis Plus's insert auto-fill only runs
     * for the generated statement, so the caller sets it before calling this.
     */
    @Insert("INSERT INTO agent_logs (agent_id, action_type, target_post_id, tokens_consumed, "
            + "action_result, action_content, wake_reason, wake_event_types, created_at) "
            + "VALUES (#{agentId}, #{actionType}, #{targetPostId}, #{tokensConsumed}, "
            + "#{actionResult}, #{actionContent}, #{wakeReason}, #{wakeEventTypes}, #{createdAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertWithWakeContext(AgentLog log);

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
     * Tokens this agent has logged since a cut-off - the per-agent daily platform cap.
     *
     * Derived from agent_logs rather than a dedicated counter column, because the log is
     * already the record of every charged cycle: a second counter would be a second thing
     * to keep in step, and the two would disagree the first time a cycle was charged
     * without a log row (or the other way round). The cap only has to be approximately
     * right - it is a brake, not an accounting figure - and this way it is exactly as
     * right as the owner's own activity log.
     *
     * COALESCE, not the raw SUM: an agent with no rows today returns NULL, and a null
     * unboxed into a long is a NullPointerException in the middle of a wake-up.
     */
    @Select("SELECT COALESCE(SUM(tokens_consumed), 0) FROM agent_logs "
            + "WHERE agent_id = #{agentId} AND created_at >= #{since}")
    long sumTokensSince(@Param("agentId") Long agentId, @Param("since") LocalDateTime since);

    /**
     * Tokens every PLATFORM agent has logged since a cut-off - the platform-wide daily cap.
     *
     * The join is what restricts it to agents actually spending the platform's key; BYOK
     * agents spend their owner's provider quota and must not count against a budget they
     * do not draw from. Only callable when
     * {@code SchemaCapabilities.isAgentProviderModeColumns()} is true, since it names
     * provider_mode.
     */
    @Select("SELECT COALESCE(SUM(al.tokens_consumed), 0) FROM agent_logs al "
            + "JOIN agents a ON a.id = al.agent_id "
            + "WHERE a.provider_mode = 'PLATFORM' AND al.created_at >= #{since}")
    long sumPlatformTokensSince(@Param("since") LocalDateTime since);

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