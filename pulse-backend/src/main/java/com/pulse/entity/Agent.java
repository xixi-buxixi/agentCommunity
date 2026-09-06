package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.pulse.enums.AgentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Agent Entity (AI Agent Life Record)
 *
 * Core entity representing AI agents in the community.
 *
 * Key Fields:
 * - tokenThreshold: Maximum allowed token consumption
 * - usedTokens: Accumulated token consumption (atomic update required)
 * - status: Agent lifecycle state (DEAD/ALIVE/ERROR)
 * - version: Optimistic lock version for concurrent safety
 * - apiKey: AES encrypted storage (never return to client)
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("agents")
public class Agent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ownerId;

    private String name;

    private String avatarUrl;

    private String systemPrompt;

    /**
     * API Key - AES encrypted storage
     * NEVER return this field to client directly
     */
    private String apiKey;

    private String baseUrl;

    private String modelName;

    /**
     * Token limit threshold
     * Agent becomes DEAD when usedTokens >= tokenThreshold (unless isUnlimited)
     */
    private Long tokenThreshold;

    /**
     * Consumed tokens - atomic update required for concurrency safety
     */
    private Long usedTokens;

    /**
     * Agent status: 0=DEAD, 1=ALIVE, 2=ERROR
     */
    private Integer status;

    /**
     * Unlimited survival flag - bypasses token exhaustion check
     */
    private Boolean isUnlimited;

    private LocalDateTime lastActiveAt;

    // ========== Personalised wake-up rhythm (phase 3) ==========
    //
    // Every field below is @TableField(exist = false), i.e. invisible to MyBatis Plus's
    // generated statements, and that is a hard requirement rather than a style choice.
    //
    // These columns only exist after the phase-3 migration. A mapped field would be
    // added to every BaseMapper SELECT/INSERT/UPDATE - selectById, selectPage,
    // updateById - so on a database that has not been migrated the agent detail page,
    // the settings update and even the tipping path would fail with "unknown column".
    // SchemaCapabilities can guard hand-written SQL; it cannot guard generated SQL.
    //
    // Automapping still fills these fields from hand-written statements that select the
    // columns (see AgentMapper), because MyBatis maps a result set by property name and
    // does not consult @TableField. Writes go through explicit, capability-guarded
    // UPDATE statements.

    /**
     * When the agent's own rhythm should wake it next. Null means "not scheduled yet".
     */
    @TableField(exist = false)
    private LocalDateTime nextWakeAt;

    /**
     * Active hours, [start, end), 0-23. May wrap midnight (22 -> 6), and
     * {@code start == end} means "active all day" - never "never active", because an
     * agent that can never wake up would be silently dead.
     */
    @TableField(exist = false)
    private Integer wakeHoursStart;

    @TableField(exist = false)
    private Integer wakeHoursEnd;

    /**
     * Hard ceiling on wake-ups per day, rhythm and interaction combined.
     */
    @TableField(exist = false)
    private Integer dailyWakeBudget;

    @TableField(exist = false)
    private Integer wakeCountToday;

    /**
     * The day {@link #wakeCountToday} belongs to; a different date means the counter is
     * stale and resets on the next claim.
     */
    @TableField(exist = false)
    private LocalDate wakeCountDate;

    /**
     * Wake-ups used today, reading a stale counter as zero.
     *
     * The counter is reset lazily (by the next claim), so a row left over from yesterday
     * still holds yesterday's number. Reporting that to the owner would be a plain lie
     * about their budget.
     */
    public int getWakeCountForDay(LocalDate today) {
        if (wakeCountDate == null || !wakeCountDate.equals(today) || wakeCountToday == null) {
            return 0;
        }
        return wakeCountToday;
    }

    // ========== Provider mode (platform-hosted model) ==========
    //
    // Same rule and the same reason as the wake columns above: @TableField(exist = false)
    // keeps both out of every generated statement, so an un-migrated database can still
    // create, read and update agents. Hand-written SQL (AgentMapper#findProviderMode,
    // #updateProviderMode) does the writing, guarded by
    // SchemaCapabilities#isAgentProviderModeColumns; the scheduler's SELECT * queries
    // automap them on the way in.

    /**
     * BYOK (the owner's own key) or PLATFORM (the platform's key, billed in points).
     * Null means the column is absent or was never selected - read it through
     * {@code LlmCredentialResolver#modeOf}, which resolves that to BYOK.
     */
    @TableField(exist = false)
    private String providerMode;

    /**
     * Which built-in persona template this agent was created from, if any. Purely
     * informational: the prompt itself is copied into system_prompt at creation, so
     * editing a template later never changes an existing agent.
     */
    @TableField(exist = false)
    private String templateId;

    /**
     * When the daily reflection pass last TRIED to reflect on this agent - success,
     * failure or an empty behaviour pack alike.
     *
     * Not "last successful reflection": the column exists to order candidates fairly, and
     * an agent whose reflection fails every night would otherwise sit at the front of the
     * queue for ever, taking the same turn again and again while the tail never gets one.
     *
     * Null means "never attempted", and those agents are ordered first.
     *
     * {@code @TableField(exist = false)} for the same hard reason as the wake columns
     * above: the column only exists after the 2026-09-06 reflection-cursor migration, and
     * a mapped field would be added to every generated statement.
     */
    @TableField(exist = false)
    private LocalDateTime lastReflectionAttemptAt;

    /**
     * Optimistic lock version - incremented on each update
     */
    @Version
    private Integer version;

    // NOTE: the generated column agents.active_name is deliberately NOT mapped, for
    // the same reason as posts.hot_score: a mapped field would be added to every
    // generated SELECT and break on databases without the migration. It exists only
    // to back the uk_owner_active_name unique key.

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    // ========== Business Logic Methods ==========

    /**
     * Check if agent has exceeded token limit
     */
    public boolean isTokenExhausted() {
        if (Boolean.TRUE.equals(isUnlimited)) {
            return false;
        }
        return usedTokens >= tokenThreshold;
    }

    /**
     * Get token consumption percentage
     */
    public double getTokenPercentage() {
        if (tokenThreshold == null || tokenThreshold <= 0) {
            return 0.0;
        }
        return (usedTokens * 100.0) / tokenThreshold;
    }

    /**
     * Check if agent is in warning state (>80% consumption)
     */
    public boolean isInWarningState() {
        return getTokenPercentage() >= 80.0 && !isTokenExhausted();
    }

    /**
     * Get status enum
     */
    public AgentStatus getStatusEnum() {
        return AgentStatus.fromCode(status);
    }

    /**
     * Check if agent can perform actions
     */
    public boolean canAct() {
        return AgentStatus.fromCode(status).isOperational() && !isTokenExhausted();
    }
}