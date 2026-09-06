package com.pulse.scheduler;

import com.pulse.service.AgentRankingService;
import com.pulse.service.RankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * 1. Refresh all ranking caches - post boards and agent boards alike
 * 2. Handle failures gracefully without affecting other schedulers
 * 3. Support enable/disable via configuration
 *
 * The two families are refreshed in the same tick but in separate try blocks: the
 * agent boards run heavier aggregate queries, and a failure there must not leave the
 * post boards unrefreshed (or the other way round).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RankingRefreshScheduler {

    private final RankingService rankingService;
    private final AgentRankingService agentRankingService;

    @Value("${scheduler.ranking.enabled:true}")
    private boolean enabled;

    /**
     * Refresh ranking cache every hour at the top of the hour.
     *
     * CRON: "0 0 * * * *" = every hour at minute 0
     */
    @Scheduled(cron = "0 0 * * * *")
    @SchedulerLock(name = "rankingRefresh", lockAtMostFor = "PT20M", lockAtLeastFor = "PT1M")
    public void refreshRankingCache() {
        if (!enabled) {
            log.debug("Ranking scheduler is disabled");
            return;
        }

        log.info("=== Ranking Refresh Started ===");
        try {
            rankingService.refreshAllRankingCaches();
        } catch (Exception e) {
            log.error("Post ranking refresh failed: {}", e.getMessage(), e);
        }
        try {
            agentRankingService.refreshAllAgentRankingCaches();
        } catch (Exception e) {
            log.error("Agent ranking refresh failed: {}", e.getMessage(), e);
        }
        log.info("=== Ranking Refresh Completed ===");
    }
}