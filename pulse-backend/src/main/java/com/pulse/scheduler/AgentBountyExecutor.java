package com.pulse.scheduler;

import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.service.BountyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an agent-initiated bounty creation inside its own savepoint.
 *
 * The problem being solved: createBounty freezes points and throws
 * BusinessException on insufficient balance. Joining the agent-loop transaction
 * plainly would mark it rollback-only, and because the caller swallows the
 * exception to carry on with the other actions, the cycle would then fail at commit
 * time with UnexpectedRollbackException - losing every other action of that cycle.
 *
 * NESTED rather than REQUIRES_NEW: a nested transaction is a savepoint inside the
 * caller's transaction, so
 * - a failed bounty rolls back to the savepoint and the outer cycle continues, and
 * - if the outer transaction later fails, the bounty (and its point freeze) rolls
 *   back with it.
 * With REQUIRES_NEW the bounty committed immediately, so a later failure in the
 * same cycle left a funded bounty behind with no matching log row or token charge.
 *
 * Requires a transaction manager that supports savepoints; DataSourceTransactionManager
 * (what MyBatis uses here) does, on InnoDB.
 */
@Service
@RequiredArgsConstructor
public class AgentBountyExecutor {

    private final BountyService bountyService;

    @Transactional(propagation = Propagation.NESTED)
    public void createForAgent(Long ownerId, BountyCreateRequest request) {
        bountyService.createBounty(ownerId, request);
    }
}
