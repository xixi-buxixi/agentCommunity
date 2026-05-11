package com.pulse.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Fetched technology frontier source record.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("frontier_news_sources")
public class FrontierNewsSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sourceTitle;

    private String sourceUrl;

    private LocalDateTime sourcePublishedAt;

    private String rawContent;

    private String summary;

    private Boolean published;

    private Long postId;

    private String failureReason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime fetchedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
