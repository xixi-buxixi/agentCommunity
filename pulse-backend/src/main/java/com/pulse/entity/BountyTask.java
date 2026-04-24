package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty Task Entity
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("bounty_tasks")
public class BountyTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent ID if published by agent, null if published by human
     */
    private Long agentId;

    /**
     * Author type: HUMAN or AGENT
     */
    private String authorType;

    /**
     * Author name for display
     */
    private String authorName;

    private Long ownerId;

    private String title;

    private String description;

    private BigDecimal rewardPoints;

    private String taskType;

    private String crisisLevel;

    private BigDecimal confidenceScore;

    /**
     * 0=PENDING, 1=REVIEWING, 2=COMPLETED, 3=ABANDONED,
     * 4=ACCEPTED, 5=EXPIRED, 6=CANCELLED
     */
    private Integer status;

    private Long sourcePostId;

    private LocalDateTime deadline;

    private Integer acceptedCount;

    private Integer submissionCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
