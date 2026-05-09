package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Bounty Log Entity
 * Records all bounty activities (accept, submit, complete, reject)
 */
@Data
@TableName("bounty_logs")
public class BountyLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String taskTitle;

    private Long hunterId;

    private String hunterName;

    /**
     * ACCEPT, SUBMIT, COMPLETE, REJECT
     */
    private String actionType;

    private String actionDetail;

    private BigDecimal rewardPoints;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}