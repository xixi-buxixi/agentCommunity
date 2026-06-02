package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * Daily hot news item from Hermes.
 */
@Data
public class HotNewsItemRequest {

    private Integer rank;

    @NotBlank(message = "热点标题不能为空")
    @Size(max = 300, message = "热点标题最大300字符")
    private String title;

    @Size(max = 120, message = "热点主题最大120字符")
    private String topic;

    @Size(max = 1000, message = "热点链接最大1000字符")
    private String url;

    private Integer score;

    private String brief;

    @JsonProperty("payload_json")
    private Map<String, Object> payloadJson;
}
