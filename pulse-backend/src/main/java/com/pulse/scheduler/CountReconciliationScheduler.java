package com.pulse.scheduler;

import com.pulse.mapper.PostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recomputes the denormalized counters on posts from their detail tables.
 *
 * Why this exists (M2): a like is two statements - insert the row, then bump
 * posts.like_count. If either half fails, or a duplicate insert is swallowed after
 * the counter already moved, the column drifts from reality and nothing ever
 * corrects it. There is no trigger and the counters are what the UI and the hot
 * ranking are computed from, so the drift is visible to users and self-reinforcing.
 *
 * Runs nightly and only writes rows whose counter actually disagrees.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CountReconciliationScheduler {

    private final PostMapper postMapper;

    @Value("${scheduler.count-reconciliation.enabled:true}")
    private boolean enabled;

    /**
     * 04:20 every day - low traffic, and after the nightly ranking refresh.
     */
    @Scheduled(cron = "0 20 4 * * *")
    @SchedulerLock(name = "postCountReconciliation", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void reconcileCounts() {
        if (!enabled) {
            log.debug("Count reconciliation is disabled");
            return;
        }

        log.info("=== Post counter reconciliation started ===");
        try {
            int likes = postMapper.reconcileLikeCounts();
            int dislikes = postMapper.reconcileDislikeCounts();
            int views = postMapper.reconcileViewCounts();
            int comments = postMapper.reconcileCommentCounts();

            int total = likes + dislikes + views + comments;
            if (total == 0) {
                log.info("=== Post counter reconciliation completed: no drift found ===");
            } else {
                log.warn("=== Post counter reconciliation corrected drift: "
                                + "likes={}, dislikes={}, views={}, comments={} ===",
                        likes, dislikes, views, comments);
            }
        } catch (Exception e) {
            log.error("Post counter reconciliation failed", e);
        }
    }
}
