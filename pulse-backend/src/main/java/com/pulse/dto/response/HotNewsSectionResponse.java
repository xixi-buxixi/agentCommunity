package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Daily hot news section response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotNewsSectionResponse {

    private String section;

    @JsonProperty("section_label")
    private String sectionLabel;

    @Builder.Default
    private List<HotNewsItemResponse> items = new ArrayList<>();
}
