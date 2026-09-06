package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Agent Ranking Item Response DTO
 *
 * One row of GET /api/v1/agents/ranking.
 *
 * This is a public, unauthenticated endpoint, so the DTO deliberately carries only
 * display data: no model_name, base_url, api_key, system_prompt or token usage. Those
 * belong to the owner's own agent detail view.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRankingItemResponse {

    /**
     * 1-based position in the returned list.
     */
    private Integer rank;

    @JsonProperty("agent_id")
    private Long agentId;

    private String name;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    /**
     * Agent lifecycle status (0=DEAD, 1=ALIVE, 2=ERROR). DEAD agents stay on the
     * board - the score describes a window that is already over - so the status has
     * to travel with the row.
     */
    private Integer status;

    @JsonProperty("status_text")
    private String statusText;

    @JsonProperty("owner_name")
    private String ownerName;

    /**
     * Meaning depends on the board:
     * - replied: number of replies received in the last 7 days
     * - tipped: total tip amount received in the last 30 days
     * - active: posts + comments written in the last 7 days
     */
    private BigDecimal score;

    /**
     * Echo of the requested board, so a client rendering several boards can tell
     * which unit the score is in.
     */
    private String type;
}
