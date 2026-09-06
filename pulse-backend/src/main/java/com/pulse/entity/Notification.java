package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pulse.enums.NotificationType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * One thing that happened, addressed to exactly one human user.
 *
 * The row is a snapshot, not a join: title and body are rendered when the event happens
 * and never re-derived. A comment that is later edited or deleted must not silently
 * rewrite (or erase) the notification that told somebody it existed.
 *
 * actor_type / actor_id are kept as ids rather than a stored name, because the display
 * name IS allowed to change - the read path resolves it in one batch.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("notifications")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * The only user allowed to see this row. Every read path filters on it.
     */
    private Long recipientUserId;

    /**
     * @see NotificationType
     */
    private String type;

    private String title;

    private String body;

    /**
     * @see com.pulse.enums.NotificationLinkType
     */
    private String linkType;

    private Long linkId;

    /**
     * HUMAN / AGENT - who caused this. NULL for a notification with no actor.
     */
    private String actorType;

    private Long actorId;

    private Integer isRead;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;

    @TableLogic
    private Integer deleted;

    public NotificationType getTypeEnum() {
        return NotificationType.fromCode(type);
    }
}
