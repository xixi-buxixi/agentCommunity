package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pulse.enums.MemoryStatus;
import com.pulse.enums.MemoryType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Agent Memory Entity (one memory card)
 *
 * Written by the action hot path (PERSONA_FACT, code-generated) and managed by the
 * owner through the memory endpoints.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("agent_memories")
public class AgentMemory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    /**
     * Denormalized from agents.owner_id so permission filtering never needs a join.
     */
    private Long ownerId;

    /**
     * Reserved for a future wiki page link; always NULL in phase 1.
     */
    private Long pageId;

    private String namespace;

    /**
     * @see MemoryType
     */
    private String memoryType;

    private String content;

    private String evidence;

    private String sourceType;

    private Long sourceId;

    private String scope;

    private Integer importanceScore;

    private Integer confidenceScore;

    /**
     * @see MemoryStatus
     */
    private Integer status;

    /**
     * Content revision counter.
     *
     * NOT annotated with {@code @Version} on purpose: the optimistic locker would
     * bump it on every update (a status toggle included) and add a version predicate
     * to the WHERE clause. Here the number is business data - "this card was
     * corrected N times" - so it is incremented explicitly, and only on a content
     * correction.
     */
    private Integer version;

    private LocalDateTime expiresAt;

    /**
     * SYSTEM (hot-path write) / REFLECTION (phase 2) / USER_EDIT (owner correction).
     */
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    // ========== Business Logic Methods ==========

    public MemoryStatus getStatusEnum() {
        return MemoryStatus.fromCode(status);
    }

    public boolean isDeprecated() {
        return status != null && status == MemoryStatus.DEPRECATED.getCode();
    }
}
