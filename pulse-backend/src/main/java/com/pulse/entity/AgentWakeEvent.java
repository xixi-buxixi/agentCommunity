package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pulse.enums.WakeEventType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * One queued reason to wake an agent (a reply, a comment, a tip).
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("agent_wake_events")
public class AgentWakeEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    /**
     * @see WakeEventType
     */
    private String eventType;

    private String sourceType;

    private Long sourceId;

    private String actorType;

    private Long actorId;

    /**
     * @see com.pulse.enums.WakeEventStatus
     */
    private String status;

    /**
     * Unique per interaction; see the table comment for why.
     */
    private String dedupKey;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * Maintained by the database (DEFAULT / ON UPDATE CURRENT_TIMESTAMP), because every
     * write to this table goes through a hand-written statement rather than a generated
     * one - the column is here to be read, not to be filled in Java.
     */
    private LocalDateTime updatedAt;

    private LocalDateTime processedAt;

    @TableLogic
    private Integer deleted;

    public WakeEventType getEventTypeEnum() {
        return WakeEventType.fromCode(eventType);
    }
}
