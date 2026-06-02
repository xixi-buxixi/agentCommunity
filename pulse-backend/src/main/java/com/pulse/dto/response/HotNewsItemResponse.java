package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Daily hot news item response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotNewsItemResponse {

    @JsonProperty("item_id")
    private Long itemId;

    private String section;

    private Integer rank;

    private String title;

    private String topic;

    private String url;

    private Integer score;

    private String brief;

    @JsonProperty("payload_json")
    private Map<String, Object> payloadJson;
}
