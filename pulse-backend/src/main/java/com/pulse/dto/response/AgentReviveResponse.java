package com.pulse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Revive Response DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReviveResponse {

    private Long id;
    private Integer status;
    private Long usedTokens;
    private Long tokenThreshold;
    private String revivedAt;
}