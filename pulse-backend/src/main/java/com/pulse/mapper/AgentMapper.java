package com.pulse.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.entity.Agent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
     * One page of alive agents that really did something since the cut-off - the
     * candidates for the daily reflection pass.
     *
     * EXISTS rather than a join + DISTINCT: it stops at the agent's first log row and
     * uses idx_agent_created on agent_logs.
     *
     * Two things this query must get right:
     * - reflection's own audit rows do not count as activity. They are written by the
     *   reflection job itself, so a window longer than a day would always contain
     *   yesterday's row and every agent that ever acted once would be reflected on
     *   (and billed) every night, for ever.
     * - it is a keyset page, not a top-N. A plain {@code ORDER BY id LIMIT 50} silently
     *   starved every agent after the fiftieth.
     *
     * @param since   cut-off timestamp (start of the reflection window)
     * @param afterId exclusive id cursor; 0 for the first page
     * @param limit   page size
     */
    List<Agent> findAliveAgentsActiveSince(@Param("since") java.time.LocalDateTime since,
                                           @Param("afterId") long afterId,
                                           @Param("limit") int limit);

    /**
     * How many candidates the window holds in total, so a run that hits its hard cap can
     * say how many agents it did not get to instead of truncating silently.
     */
    int countAliveAgentsActiveSince(@Param("since") java.time.LocalDateTime since);

    /**
     * Fallback selection for databases without last_dispatched_at (migration not
     * applied): random order, as before.
     *
     * @param limit Maximum number of agents to return
     * @return List of randomly selected active agents
     */
    List<Agent> findRandomActiveAgentsLegacy(@Param("limit") int limit);

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

    /**
     * Read one agent's rhythm settings.
     *
     * Hand-written because the wake columns are {@code @TableField(exist = false)}: they
     * must stay out of the generated statements so an un-migrated database can still
     * serve every normal agent query. Only call this when
     * {@code SchemaCapabilities.isWakeQueueSchema()} is true.
     */
    @Select("SELECT id AS agentId, next_wake_at AS nextWakeAt, wake_hours_start AS wakeHoursStart, "
            + "wake_hours_end AS wakeHoursEnd, daily_wake_budget AS dailyWakeBudget, "
            + "wake_count_today AS wakeCountToday, wake_count_date AS wakeCountDate "
            + "FROM agents WHERE id = #{id} AND deleted = 0")
    AgentWakeSettings findWakeSettings(@Param("id") Long id);

    /**
     * Batch variant, so a list page costs one extra query rather than one per row.
     */
    @Select({"<script>",
            "SELECT id AS agentId, next_wake_at AS nextWakeAt, wake_hours_start AS wakeHoursStart,",
            "wake_hours_end AS wakeHoursEnd, daily_wake_budget AS dailyWakeBudget,",
            "wake_count_today AS wakeCountToday, wake_count_date AS wakeCountDate",
            "FROM agents WHERE deleted = 0 AND id IN",
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>",
            "</script>"})
    List<AgentWakeSettings> findWakeSettingsByIds(@Param("ids") List<Long> ids);

    /**
     * Write the rhythm settings (creation defaults and owner edits alike).
     *
     * Both hour columns are always written together: a half-updated window would make the
     * scheduler compute against one new bound and one old one.
     */
    @Update("UPDATE agents SET wake_hours_start = #{wakeHoursStart}, wake_hours_end = #{wakeHoursEnd}, "
            + "daily_wake_budget = #{dailyWakeBudget}, next_wake_at = #{nextWakeAt} "
            + "WHERE id = #{id} AND deleted = 0")
    int updateWakeSettings(@Param("id") Long id,
                           @Param("wakeHoursStart") Integer wakeHoursStart,
                           @Param("wakeHoursEnd") Integer wakeHoursEnd,
                           @Param("dailyWakeBudget") Integer dailyWakeBudget,
                           @Param("nextWakeAt") java.time.LocalDateTime nextWakeAt);

    /**
     * Atomically claim one wake slot for an agent: the single gate for both the daily
     * budget and the debounce interval.
     *
     * Everything that could race lives in this one statement:
     * - the daily counter resets when {@code wake_count_date} is not today, so no
     *   separate midnight job is needed and a clock skew cannot double the budget;
     * - the budget predicate makes the claim fail (0 rows) rather than the caller
     *   reading, deciding and being overtaken;
     * - the debounce predicate uses last_dispatched_at, the same column the legacy
     *   round-robin stamps, so a wake from either mode counts;
     * - the stamp and both comparison values come from the JVM clock, not from NOW() /
     *   CURDATE(). Mixing the two would make the debounce window and the day boundary
     *   depend on the database session's timezone, which is exactly the kind of skew that
     *   silently doubles a daily budget across midnight.
     *
     * A 0 return means "not now" - the caller must not call the model.
     *
     * @param today            the current business date
     * @param debounceCutoff   an agent woken after this instant is still cooling down
     * @param defaultBudget    budget to assume when the column is NULL on old rows
     * @return 1 when the slot was claimed, 0 when it was refused
     */
    @Update("UPDATE agents SET "
            + "wake_count_today = IF(wake_count_date = #{today}, COALESCE(wake_count_today, 0) + 1, 1), "
            + "wake_count_date = #{today}, "
            + "last_dispatched_at = #{now} "
            + "WHERE id = #{id} AND deleted = 0 AND status = 1 "
            + "AND (last_dispatched_at IS NULL OR last_dispatched_at <= #{debounceCutoff}) "
            + "AND (wake_count_date IS NULL OR wake_count_date <> #{today} "
            + "     OR COALESCE(wake_count_today, 0) < COALESCE(daily_wake_budget, #{defaultBudget}))")
    int claimWakeSlot(@Param("id") Long id,
                      @Param("now") java.time.LocalDateTime now,
                      @Param("today") java.time.LocalDate today,
                      @Param("debounceCutoff") java.time.LocalDateTime debounceCutoff,
                      @Param("defaultBudget") int defaultBudget);

    /**
     * Give back a wake slot claimed a moment ago.
     *
     * Compensation for the narrow window where the claim succeeded but the wake could not
     * actually start (for example the events could not be marked consumed). Without it the
     * agent is charged a slot it never used, and in the worst case its interactions sit
     * PENDING until they expire - billed silence.
     *
     * Guarded so it can only ever undo today's own increment: never negative, never
     * touching a counter that has already rolled over to another day.
     *
     * @return 1 when a slot was returned, 0 when there was nothing to return
     */
    @Update("UPDATE agents SET wake_count_today = wake_count_today - 1 "
            + "WHERE id = #{id} AND deleted = 0 "
            + "AND wake_count_date = #{today} AND COALESCE(wake_count_today, 0) > 0")
    int releaseWakeSlot(@Param("id") Long id, @Param("today") java.time.LocalDate today);

    /**
     * Store the next rhythm wake-up. Event wakes deliberately do not touch it: answering
     * a reply should not push the agent's own routine around.
     */
    @Update("UPDATE agents SET next_wake_at = #{nextWakeAt} WHERE id = #{id} AND deleted = 0")
    int updateNextWakeAt(@Param("id") Long id, @Param("nextWakeAt") java.time.LocalDateTime nextWakeAt);

    /**
     * Agents whose rhythm is due. Active-hours and budget are evaluated in Java, where
     * the midnight-wrapping window is readable and testable; this query only does what an
     * index can do.
     *
     * A NULL next_wake_at is included so an agent that has never been scheduled (or was
     * created before the migration) gets its first rhythm wake instead of waiting for
     * ever.
     */
    List<Agent> findRhythmWakeCandidates(@Param("now") java.time.LocalDateTime now,
                                         @Param("limit") int limit);

    /**
     * Alive agents by id, for the event-driven wake path.
     */
    List<Agent> findAliveAgentsByIds(@Param("ids") List<Long> ids);

    // NOTE: selectByIds was removed. It declared a batch select with no XML
    // statement and no annotation, so the first caller would have hit
    // BindingException at runtime. Use BaseMapper.selectBatchIds for batch loads.
    // findActiveAgentsWithCapacity and incrementUsedTokensOptimistic were removed
    // for the same reason as other dead code: no callers, and the "optimistic"
    // variant was never wired up even though the agents table has a version column.
}