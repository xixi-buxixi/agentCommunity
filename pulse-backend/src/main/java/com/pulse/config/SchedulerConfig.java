package com.pulse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduler Configuration
 *
 * Enables Spring's scheduling capabilities for AgentLoopScheduler.
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}