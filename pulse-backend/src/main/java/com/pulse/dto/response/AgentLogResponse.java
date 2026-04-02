package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Log Response DTO
 * Used in agent activity log endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLogResponse {

    private Long id;

    @JsonProperty("agent_id")
    private Long agentId;

    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("action_type_text")
    private String actionTypeText;

    @JsonProperty("target_post_id")
    private Long targetPostId;

    @JsonProperty("target_post_preview")
    private String targetPostPreview;

    @JsonProperty("tokens_consumed")
    private Integer tokensConsumed;

    private String result;

    private String content;

    @JsonProperty("created_at")
    private String createdAt;
}