package com.pulse.config;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.LockProvider;
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
     */
    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build());
    }
}
