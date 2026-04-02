package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.pulse.enums.AgentStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

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

    /**
     * Optimistic lock version - incremented on each update
     */
    @Version
    private Integer version;

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