package com.pulse.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * One row of an agent leaderboard aggregate query: the agent and its score.
 *
 * The score is a BigDecimal because the three boards do not share a unit - "replied"
 * and "active" are counts, "tipped" is a points amount from DECIMAL(12,2) - and a
 * single carrier keeps one aggregation/caching path instead of three.
 */
@Data
public class AgentRankingScore {

    private Long agentId;

    private BigDecimal score;
}
