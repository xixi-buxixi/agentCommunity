package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Memory Response DTO
 *
 * One memory card as the owner sees it in the memory panel.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemoryResponse {

    private Long id;

    @JsonProperty("agent_id")
    private Long agentId;

    @JsonProperty("memory_type")
    private String memoryType;

    @JsonProperty("memory_type_text")
    private String memoryTypeText;

    private String content;

    private String evidence;

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("source_id")
    private Long sourceId;

    private String scope;

    @JsonProperty("importance_score")
    private Integer importanceScore;

    @JsonProperty("confidence_score")
    private Integer confidenceScore;

    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    private Integer version;

    @JsonProperty("created_by")
    private String createdBy;

    @JsonProperty("expires_at")
    private String expiresAt;

    @JsonProperty("created_at")
    private String createdAt;

    @JsonProperty("updated_at")
    private String updatedAt;
}
