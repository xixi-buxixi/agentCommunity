package com.pulse.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulse.entity.BountyTask;
import com.pulse.enums.BountyStatus;
import com.pulse.mapper.BountyTaskMapper;
import com.pulse.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounty Expiry Scheduler
 *
 * Periodically scans for expired bounty tasks and handles cleanup:
 * 1. Update status to EXPIRED
 * 2. Unfreeze publisher's frozen points
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BountyExpiryScheduler {

    private final BountyTaskMapper bountyTaskMapper;
    private final UserMapper userMapper;

    @Value("${scheduler.bounty-expiry.enabled:true}")
    private boolean schedulerEnabled;

    /**
     * Execute expiry check every hour
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void checkExpiredBounties() {
        if (!schedulerEnabled) {
            log.debug("Bounty expiry scheduler is disabled");
            return;
        }

        log.info("=== Bounty Expiry Check Started ===");

        // Find expired active bounties
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w
                .eq(BountyTask::getStatus, BountyStatus.PENDING.getCode())
                .or()
                .eq(BountyTask::getStatus, BountyStatus.ACCEPTED.getCode())
                .or()
                .eq(BountyTask::getStatus, BountyStatus.REVIEWING.getCode())
        );
        wrapper.lt(BountyTask::getDeadline, LocalDateTime.now());

        List<BountyTask> expiredBounties = bountyTaskMapper.selectList(wrapper);

        log.info("Found {} expired bounty tasks", expiredBounties.size());

        for (BountyTask task : expiredBounties) {
            try {
                handleExpiredBounty(task);
            } catch (Exception e) {
                log.error("Failed to handle expired bounty: taskId={}, error={}",
                    task.getId(), e.getMessage());
            }
        }

        log.info("=== Bounty Expiry Check Completed ===");
    }

    /**
     * Handle single expired bounty task
     */
    @Transactional
    public void handleExpiredBounty(BountyTask task) {
        log.info("Handling expired bounty: taskId={}, title={}, reward={}",
            task.getId(), task.getTitle(), task.getRewardPoints());

        // Update status to EXPIRED
        task.setStatus(BountyStatus.EXPIRED.getCode());
        bountyTaskMapper.updateById(task);

        // Release publisher's frozen points. The reward was never deducted from
        // total points, so expiry only decreases pending_bounty.
        int released = userMapper.refundPointsAtomic(task.getOwnerId(), task.getRewardPoints());
        if (released == 0) {
            log.warn("Failed to release expired bounty points: userId={}, taskId={}, amount={}",
                task.getOwnerId(), task.getId(), task.getRewardPoints());
        } else {
            log.info("Released expired bounty points: userId={}, taskId={}, amount={}",
                task.getOwnerId(), task.getId(), task.getRewardPoints());
        }

        log.info("Bounty expired: taskId={}", task.getId());
    }
}
