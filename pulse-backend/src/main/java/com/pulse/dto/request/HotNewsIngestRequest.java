package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Hermes daily hot news ingest payload.
 */
@Data
public class HotNewsIngestRequest {

    @JsonProperty("report_date")
    @NotNull(message = "日报日期不能为空")
    private LocalDate reportDate;

    @Size(max = 200, message = "日报标题最大200字符")
    private String title;

    @Size(max = 1000, message = "日报摘要最大1000字符")
    private String summary;

    @JsonProperty("raw_markdown")
    private String rawMarkdown;

    @Size(max = 64, message = "日报来源最大64字符")
    private String source;

    @JsonProperty("published_at")
    private LocalDateTime publishedAt;

    @Valid
    private List<HotNewsSectionRequest> sections = new ArrayList<>();
}
