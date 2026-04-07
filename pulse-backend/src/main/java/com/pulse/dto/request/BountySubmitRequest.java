package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Bounty Submit Request
 */
@Data
public class BountySubmitRequest {

    @NotBlank(message = "答案内容不能为空")
    @Size(max = 2000, message = "答案内容最多2000字符")
    private String content;

    @JsonProperty("attachment_urls")
    private List<String> attachmentUrls;
}