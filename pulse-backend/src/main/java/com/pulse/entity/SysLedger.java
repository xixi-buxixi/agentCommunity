package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * System Ledger Entity (Points Transaction Log)
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("sys_ledger")
public class SysLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /**
     * Positive = income, Negative = expense
     */
    private BigDecimal amount;

    /**
     * TIP, TIP_RECV, BOUNTY_PAY, BOUNTY_RECV, REFUND, GRANT
     */
    private String type;

    private Long relatedId;

    private String relatedType;

    private String description;

    private BigDecimal balanceBefore;

    private BigDecimal balanceAfter;

    private LocalDateTime createdAt;
}