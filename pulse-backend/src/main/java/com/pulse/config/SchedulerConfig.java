package com.pulse.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

/**
 * Scheduler Configuration
 *
 * Note: {@code @EnableScheduling} lives on PulseApplication only - declaring it
 * in two places registers the annotation post-processor twice.
 */
@Slf4j
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerConfig {

    /**
     * Dedicated scheduler pool.
     *
     * Spring's default {@code ThreadPoolTaskScheduler} has exactly one thread, so the
     * agent loop, the bounty expiry sweep and the ranking refresh ran serially: one
     * slow LLM call froze bounty refunds and ranking updates indefinitely.
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("pulse-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        // Without an error handler an uncaught exception silently cancels a
        // fixedDelay task for the remaining lifetime of the process.
        scheduler.setErrorHandler(throwable -> log.error("Scheduled task failed", throwable));
        return scheduler;
    }

    /**
     * ShedLock provider backed by the existing MySQL instance (shedlock table).
     *
     * Falls back to a permissive provider when the table is absent: without it
     * JdbcTemplateLockProvider throws on every tick and @SchedulerLock then skips the
     * task, so a missing migration would silently stop the agent loop, the bounty
     * expiry sweep and the ranking refresh. Running unlocked is correct for the
     * current single-instance deployment; the trade-off is logged loudly.
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource, SchemaCapabilities schemaCapabilities) {
        if (!schemaCapabilities.isShedlockTable()) {
            log.warn("shedlock table not found - scheduler locking is DISABLED. "
                    + "Do not run more than one backend instance until "
                    + "deploy/migrations/2026-07-27-optimization.sql has been applied.");
            return new NoOpLockProvider();
        }
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }

    /**
     * Grants every lock request. Used only when the shedlock table is missing.
     */
    private static class NoOpLockProvider implements LockProvider {
        @Override
        public java.util.Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            return java.util.Optional.of(new SimpleLock() {
                @Override
                public void unlock() {
                    // nothing to release
                }
            });
        }
    }
}
