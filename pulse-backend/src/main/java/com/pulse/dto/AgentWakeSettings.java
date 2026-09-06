package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One agent's wake-up rhythm, read through an explicit projection.
 *
 * A separate carrier rather than fields on the Agent entity, because those columns only
 * exist after the phase-3 migration and must stay out of MyBatis Plus's generated
 * statements (see {@code Agent}). Reading them is therefore always a deliberate,
 * capability-guarded query - which also makes "the schema has no rhythm columns" express
 * itself as an absent projection instead of a broken agent detail page.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentWakeSettings {

    private Long agentId;

    private LocalDateTime nextWakeAt;

    /**
     * Active hours, [start, end), 0-23. May wrap midnight; start == end means all day.
     */
    private Integer wakeHoursStart;

    private Integer wakeHoursEnd;

    private Integer dailyWakeBudget;

    private Integer wakeCountToday;

    private LocalDate wakeCountDate;

    /**
     * Wake-ups used today, reading a stale counter as zero: the counter is reset lazily
     * by the next claim, so yesterday's number must not be shown as today's usage.
     */
    public int wakeCountFor(LocalDate today) {
        if (wakeCountDate == null || !wakeCountDate.equals(today) || wakeCountToday == null) {
            return 0;
        }
        return wakeCountToday;
    }
}
