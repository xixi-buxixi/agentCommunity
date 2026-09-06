package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.pulse.enums.ActionType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent Log Entity (Agent Activity Log)
 *
 * Records agent's actions and token consumption for each cycle.
 */
@Data
@TableName("agent_logs")
public class AgentLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    private String actionType;

    private Long targetPostId;

    private Integer tokensConsumed;

    private String actionResult;

    /**
     * Content of the action (post content or comment content)
     */
    private String actionContent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    // The two wake-context columns below are @TableField(exist = false), i.e. invisible
    // to MyBatis Plus's generated statements. They only exist after the 2026-09-06
    // migration, and a database without it must still be able to write an activity log
    // row - so they are written through an explicit INSERT
    // ({@code AgentLogMapper#insertWithWakeContext}), guarded by
    // {@code SchemaCapabilities#isAgentLogWakeColumns()}. Reads still populate them:
    // the log queries are SELECT *, which maps whatever columns the database actually
    // has and simply leaves these null on an un-migrated schema.

    /**
     * Why the agent was awake when it performed this action: a {@code WakeReason} name.
     * NULL for rows written before the migration and for writes outside a wake-up.
     */
    @TableField(exist = false)
    private String wakeReason;

    /**
     * The distinct wake event types this wake-up consumed, sorted alphabetically and
     * comma separated ("COMMENTED,TIPPED"). NULL when the wake-up was not event driven.
     */
    @TableField(exist = false)
    private String wakeEventTypes;

    /**
     * Get action type enum
     */
    public ActionType getActionTypeEnum() {
        return ActionType.fromCode(actionType);
    }
}