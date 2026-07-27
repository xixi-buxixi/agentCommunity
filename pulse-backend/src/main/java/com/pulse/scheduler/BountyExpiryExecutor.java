package com.pulse.scheduler;

import com.pulse.entity.BountyTask;
import com.pulse.enums.BountyStatus;
import com.pulse.mapper.BountyTaskMapper;
import com.pulse.service.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Transactional unit of work for a single expired bounty.
 *
 * Deliberately a separate bean from {@link BountyExpiryScheduler}: calling a
 * {@code @Transactional} method through {@code this} inside the scheduler bypasses
 * the Spring proxy, so the annotation would have no effect and the status update
 * could commit without the matching refund.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BountyExpiryExecutor {

    private final BountyTaskMapper bountyTaskMapper;
    private final PointsService pointsService;

    /**
     * Expire one task and release its frozen reward.
     *
     * @return true when this call expired the task, false when another actor
     *         (cancel, audit, a concurrent scheduler run) already moved it on
     */
    @Transactional
    public boolean expire(BountyTask task) {
        // Compare-and-set first: the refund below must run at most once per task.
        int expired = bountyTaskMapper.updateStatusIfIn(
                task.getId(),
                BountyStatus.EXPIRED.getCode(),
                List.of(BountyStatus.PENDING.getCode(),
                        BountyStatus.ACCEPTED.getCode(),
                        BountyStatus.REVIEWING.getCode()));
        if (expired == 0) {
            log.info("Skipping expiry, task already moved on: taskId={}", task.getId());
            return false;
        }

        // Route the refund through PointsService so expiry writes a sys_ledger row,
        // exactly like the cancel path. A direct mapper call would change the balance
        // with no audit trail, leaving the user's ledger inconsistent with reality.
        pointsService.refundPoints(task.getOwnerId(), task.getRewardPoints(), task.getId(),
                "悬赏超时未完成，释放冻结积分");

        log.info("Bounty expired: taskId={}, ownerId={}, released={}",
                task.getId(), task.getOwnerId(), task.getRewardPoints());
        return true;
    }
}
