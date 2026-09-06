package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pulse.entity.AgentWakeEvent;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Wake Event Mapper (the interaction wake queue).
 */
@Mapper
public interface AgentWakeEventMapper extends BaseMapper<AgentWakeEvent> {

    /**
     * Enqueue one event, ignoring a duplicate.
     *
     * INSERT IGNORE rather than a select-then-insert: the enqueue points run inside
     * existing business transactions (comment creation, tipping), and a lost race there
     * would either wake the agent twice for one comment or throw a duplicate-key error
     * into a transaction that has nothing to do with wake-ups. "Already queued" is a
     * success from the caller's point of view.
     *
     * @return 1 when the row was inserted, 0 when the dedup key already existed
     */
    @Insert("INSERT IGNORE INTO agent_wake_events "
            + "(agent_id, event_type, source_type, source_id, actor_type, actor_id, "
            + " status, dedup_key, created_at) "
            + "VALUES (#{agentId}, #{eventType}, #{sourceType}, #{sourceId}, #{actorType}, "
            + " #{actorId}, 'PENDING', #{dedupKey}, #{createdAt})")
    int insertIgnoringDuplicate(AgentWakeEvent event);

    /**
     * Agents with at least one pending event, oldest interaction first so nobody's
     * conversation is starved by a chattier agent.
     */
    @Select("SELECT e.agent_id FROM agent_wake_events e "
            + "JOIN agents a ON a.id = e.agent_id AND a.status = 1 AND a.deleted = 0 "
            + "WHERE e.status = 'PENDING' AND e.deleted = 0 "
            + "GROUP BY e.agent_id ORDER BY MIN(e.created_at) ASC LIMIT #{limit}")
    List<Long> findAgentIdsWithPendingEvents(@Param("limit") int limit);

    /**
     * Retire the pending events of agents that are no longer alive.
     *
     * Without this a dead agent's events sit at the front of the queue for ever - they are
     * the oldest, so they win the ordering, and they can never be consumed. A handful of
     * dead agents would starve every living one.
     *
     * @return number of events expired
     */
    @Update("UPDATE agent_wake_events e "
            + "JOIN agents a ON a.id = e.agent_id "
            + "SET e.status = 'EXPIRED', e.processed_at = #{now} "
            + "WHERE e.status = 'PENDING' AND e.deleted = 0 "
            + "AND (a.status <> 1 OR a.deleted = 1)")
    int expirePendingForInactiveAgents(@Param("now") LocalDateTime now);

    /**
     * One agent's pending events, oldest first. All of them are answered by a single
     * wake-up: five replies are one conversation, not five LLM calls.
     */
    @Select("SELECT * FROM agent_wake_events "
            + "WHERE agent_id = #{agentId} AND status = 'PENDING' AND deleted = 0 "
            + "ORDER BY created_at ASC, id ASC LIMIT #{limit}")
    List<AgentWakeEvent> findPendingByAgent(@Param("agentId") Long agentId,
                                            @Param("limit") int limit);

    /**
     * Mark events consumed, but only if they are still PENDING.
     *
     * The status predicate is what makes a second instance (or a tick that overran its
     * ShedLock lease) unable to double-consume: the loser updates 0 rows.
     *
     * @return number of events actually consumed by this caller
     */
    @Update({"<script>",
            "UPDATE agent_wake_events SET status = 'PROCESSED', processed_at = #{now}",
            "WHERE status = 'PENDING' AND deleted = 0 AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    int markProcessed(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    /**
     * Retire events nobody got to in time, so a backlog cannot turn into a wake-up storm
     * the day a budget frees up.
     *
     * @return number of events expired
     */
    @Update("UPDATE agent_wake_events SET status = 'EXPIRED', processed_at = #{now} "
            + "WHERE status = 'PENDING' AND deleted = 0 AND created_at < #{cutoff}")
    int expirePendingOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
}
