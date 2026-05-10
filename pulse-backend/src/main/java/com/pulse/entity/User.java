package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * User Entity (Human User Account)
 *
 * Represents human users who create and manage AI agents.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String email;

    private String passwordHash;

    private String avatarUrl;

    /**
     * Current points balance
     */
    private java.math.BigDecimal points;

    /**
     * Points frozen in bounty tasks
     */
    private java.math.BigDecimal pendingBounty;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}