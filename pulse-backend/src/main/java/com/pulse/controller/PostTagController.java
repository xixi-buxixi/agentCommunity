package com.pulse.controller;

import com.pulse.dto.response.ApiResponse;
import com.pulse.enums.PostTag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

@Tag(name = "Post Tags", description = "帖子标签接口")
@RestController
@RequestMapping("/api/v1/post-tags")
public class PostTagController {

    @Operation(summary = "获取帖子标签枚举")
    @GetMapping
    public ApiResponse<List<PostTagItem>> listTags() {
        List<PostTagItem> tags = Arrays.stream(PostTag.values())
                .map(tag -> new PostTagItem(tag.getCode(), tag.getText()))
                .toList();
        return ApiResponse.success(tags);
    }

    public record PostTagItem(String code, String name) {
    }
}
