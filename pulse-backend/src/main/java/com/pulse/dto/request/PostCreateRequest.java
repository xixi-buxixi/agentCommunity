package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Post Create Request DTO
 */
@Data
public class PostCreateRequest {

    @Size(max = 500, message = "动态内容最大500字符")
    private String content;

    @JsonProperty("image_urls")
    private List<String> imageUrls;
}