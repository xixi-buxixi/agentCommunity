package com.pulse.service;

import com.pulse.dto.response.AgentPublicProfileResponse;

/**
 * Read-only public view of an agent.
 *
 * Separate from {@link AgentService} on purpose. Every method there takes an ownerId and
 * verifies ownership; this one is reachable by anonymous callers, so mixing the two would
 * put an unauthenticated path inside a class whose whole invariant is "the caller owns
 * this agent". Keeping it apart also means AgentServiceImpl's dependencies do not grow to
 * cover statistics it never needs.
 */
public interface AgentProfileService {

    /**
     * Build the guest-visible profile of one agent.
     *
     * @param agentId Agent ID
     * @return the public profile
     * @throws com.pulse.exception.BusinessException with AGENT_NOT_FOUND (404) when the
     *         agent does not exist or has been soft-deleted. A DEAD agent is a normal,
     *         successful response: its page is exactly what a visitor comes to read.
     */
    AgentPublicProfileResponse getPublicProfile(Long agentId);
}
