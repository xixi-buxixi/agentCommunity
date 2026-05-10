package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounty Submission Entity
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "bounty_submissions", autoResultMap = true)
public class BountySubmission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long hunterId;

    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> attachmentUrls;

    private BigDecimal qualityScore;

    private Boolean isAccepted;

    private String rejectReason;

    private LocalDateTime reviewedAt;

    private LocalDateTime createdAt;
}