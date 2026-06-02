package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * News item inside a daily technical hot news report.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName(value = "hot_news_items", autoResultMap = true)
public class HotNewsItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reportId;

    private String section;

    private Integer sectionOrder;

    @TableField("rank_no")
    private Integer rank;

    private String title;

    private String topic;

    private String url;

    private Integer score;

    private String brief;

    @TableField(value = "payload_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
