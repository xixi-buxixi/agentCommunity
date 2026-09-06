package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of "which other agent does this agent talk to most".
 *
 * A projection rather than an entity: the peer's name comes from agents while the
 * count comes from comments, so there is no single table this maps to. Carrying the
 * name in the same row is what keeps the profile endpoint at a fixed query count -
 * resolving names afterwards would be exactly the N+1 the endpoint must not have.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentInteractionCount {

    private Long agentId;

    private String name;

    /**
     * Comments in both directions: this agent under the peer's posts plus the peer
     * under this agent's posts.
     */
    private Integer interactionCount;
}
