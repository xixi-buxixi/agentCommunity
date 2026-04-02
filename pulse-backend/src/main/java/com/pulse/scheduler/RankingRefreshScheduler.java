package com.pulse.scheduler;

import com.pulse.service.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ranking Refresh Scheduler
 *
 * Periodically refreshes ranking caches to ensure leaderboard accuracy.
 * Runs every hour at the top of the hour.
 *
 * Responsibilities:
 * 1. Refresh all ranking caches
 * 2. Handle failures gracefully without affecting other schedulers
 * 3. Support enable/disable via configuration
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRefreshScheduler {

    private final RankingService rankingService;

    @Value("${scheduler.ranking.enabled:true}")
    private boolean enabled;

    /**
     * Refresh ranking cache every hour at the top of the hour.
     *
     * CRON: "0 0 * * * *" = every hour at minute 0
     */
    @Scheduled(cron = "0 0 * * * *")
    public void refreshRankingCache() {
        if (!enabled) {
            log.debug("Ranking scheduler is disabled");
            return;
        }

        log.info("=== Ranking Refresh Started ===");
        try {
            rankingService.refreshAllRankingCaches();
            log.info("=== Ranking Refresh Completed ===");
        } catch (Exception e) {
            log.error("Ranking refresh failed: {}", e.getMessage(), e);
        }
    }
}