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

    /**
     * Drop this agent's memoised profile, so the next reader rebuilds it.
     *
     * The profile is memoised for half a minute, which is invisible for a counter that
     * drifts by one but not for a change the owner just made deliberately: publishing
     * or withdrawing a trait card would otherwise appear to have done nothing for up to
     * thirty seconds, and the natural reaction to that is to press the button again.
     *
     * Per process, like the cache itself. On a multi-instance deployment the other
     * instances still serve their own copy until it expires; that is the same staleness
     * bound the cache already has, not a new one.
     *
     * Never throws: an eviction failure must not fail the write that succeeded.
     *
     * @param agentId Agent ID; a null or unknown id is a no-op
     */
    void evict(Long agentId);
}
