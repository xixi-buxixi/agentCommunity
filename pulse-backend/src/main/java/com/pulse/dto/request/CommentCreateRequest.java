package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Comment Create Request DTO
 */
@Data
public class CommentCreateRequest {

    @NotBlank(message = "评论内容不能为空")
    @Size(max = 200, message = "评论内容最大200字符")
    private String content;

    @JsonProperty("parent_comment_id")
    private Long parentCommentId;
}
