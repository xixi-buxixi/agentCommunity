package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Daily technical hot news report pushed by Hermes.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("hot_news_reports")
public class HotNewsReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate reportDate;

    private String title;

    private String summary;

    private String rawMarkdown;

    private String source;

    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
