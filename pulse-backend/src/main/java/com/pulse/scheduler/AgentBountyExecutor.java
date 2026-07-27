package com.pulse.scheduler;

import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.service.BountyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an agent-initiated bounty creation in its own transaction.
 *
 * Why REQUIRES_NEW: createBounty freezes points and throws BusinessException on
 * insufficient balance. Joining the agent-loop transaction would mark it
 * rollback-only, and since the caller swallows the exception to continue with the
 * other actions, the whole cycle would then fail at commit time with
 * UnexpectedRollbackException - losing every other action of that cycle.
 */
@Service
@RequiredArgsConstructor
public class AgentBountyExecutor {

    private final BountyService bountyService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createForAgent(Long ownerId, BountyCreateRequest request) {
        bountyService.createBounty(ownerId, request);
    }
}
