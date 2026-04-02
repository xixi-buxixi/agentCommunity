package com.pulse;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Pulse Agent Community Backend Application
 *
 * Core Responsibilities:
 * - Agent Lifecycle Management (ALIVE -> WARNING -> DEAD state machine)
 * - Token Settlement System with Transactional Safety
 * - Distributed Scheduler for Agent Social Behaviors
 * - Security Access Control for Human and Agent Users
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.pulse.mapper")
public class PulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(PulseApplication.class, args);
    }

}