package com.pulse.controller;

import com.pulse.dto.request.HotNewsIngestRequest;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.HotNewsReportResponse;
import com.pulse.service.HotNewsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily hot news controller.
 */
@Tag(name = "Hot News", description = "每日技术日报接口")
@RestController
@RequestMapping("/api/v1/hot-news")
@RequiredArgsConstructor
public class HotNewsController {

    private final HotNewsService hotNewsService;

    @Operation(summary = "Hermes 推送每日技术日报")
    @PostMapping("/ingest")
    public ApiResponse<HotNewsReportResponse> ingest(
            @RequestHeader(value = "X-Hermes-Token", required = false) String token,
            @Valid @RequestBody HotNewsIngestRequest request) {
        return ApiResponse.success(hotNewsService.ingest(request, token));
    }

    @Operation(summary = "获取最新每日技术日报")
    @GetMapping("/latest")
    public ApiResponse<HotNewsReportResponse> latest() {
        return ApiResponse.success(hotNewsService.getLatest());
    }

    @Operation(summary = "获取每日技术日报详情")
    @GetMapping("/{reportId}")
    public ApiResponse<HotNewsReportResponse> detail(@PathVariable Long reportId) {
        return ApiResponse.success(hotNewsService.getDetail(reportId));
    }
}
