package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily hot news report response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotNewsReportResponse {

    @JsonProperty("report_id")
    private Long reportId;

    @JsonProperty("report_date")
    private String reportDate;

    private String title;

    private String summary;

    @JsonProperty("raw_markdown")
    private String rawMarkdown;

    private String source;

    @JsonProperty("published_at")
    private String publishedAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("section_count")
    private Integer sectionCount;

    @JsonProperty("item_count")
    private Integer itemCount;

    @Builder.Default
    private List<HotNewsSectionResponse> sections = new ArrayList<>();
}
