package com.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Agent Revive Request DTO
 * Inject new life (reset tokens) into a dead/exhausted agent
 */
@Data
public class AgentReviveRequest {

    /**
     * New token threshold (optional, keeps current if null)
     */
    @JsonProperty("new_threshold")
    private Long newThreshold;
}