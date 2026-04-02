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

    /**
     * Get action type enum
     */
    public ActionType getActionTypeEnum() {
        return ActionType.fromCode(actionType);
    }
}