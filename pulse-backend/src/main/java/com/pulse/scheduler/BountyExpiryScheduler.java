package com.pulse.scheduler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulse.entity.BountyTask;
import com.pulse.enums.BountyStatus;
import com.pulse.mapper.BountyTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounty Expiry Scheduler
 *
 * Periodically scans for expired bounty tasks and handles cleanup:
 * 1. Update status to EXPIRED
 * 2. Unfreeze publisher's frozen points
 *
 * The per-task work lives in {@link BountyExpiryExecutor} so that its
 * {@code @Transactional} boundary is actually applied.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BountyExpiryScheduler {

    private final BountyTaskMapper bountyTaskMapper;
    private final BountyExpiryExecutor bountyExpiryExecutor;

    @Value("${scheduler.bounty-expiry.enabled:true}")
    private boolean schedulerEnabled;

    /**
     * Execute expiry check every hour.
     *
     * fixedDelay (not fixedRate) so a slow run cannot overlap the next one, and
     * ShedLock so two instances cannot release the same freeze twice.
     */
    @Scheduled(fixedDelay = 3600000) // 1 hour after the previous run finished
    @SchedulerLock(name = "bountyExpiryCheck", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void checkExpiredBounties() {
        if (!schedulerEnabled) {
            log.debug("Bounty expiry scheduler is disabled");
            return;
        }

        log.info("=== Bounty Expiry Check Started ===");

        // Find expired active bounties
        LambdaQueryWrapper<BountyTask> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BountyTask::getStatus, List.of(
                BountyStatus.PENDING.getCode(),
                BountyStatus.ACCEPTED.getCode(),
                BountyStatus.REVIEWING.getCode()));
        wrapper.lt(BountyTask::getDeadline, LocalDateTime.now());

        List<BountyTask> expiredBounties = bountyTaskMapper.selectList(wrapper);

        log.info("Found {} expired bounty tasks", expiredBounties.size());

        int released = 0;
        for (BountyTask task : expiredBounties) {
            try {
                if (bountyExpiryExecutor.expire(task)) {
                    released++;
                }
            } catch (Exception e) {
                log.error("Failed to handle expired bounty: taskId={}", task.getId(), e);
            }
        }

        log.info("=== Bounty Expiry Check Completed: {}/{} released ===", released, expiredBounties.size());
    }
}
