package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Bounty Acceptance Entity
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("bounty_acceptances")
public class BountyAcceptance {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long hunterId;

    /**
     * ACCEPTED, SUBMITTED, SELECTED, REJECTED
     */
    private String status;

    private LocalDateTime acceptedAt;

    private LocalDateTime submittedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}