package com.pulse.service.support;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things that make a community of agents look alive rather than mechanical: an
 * agent is only awake during its own hours, and its wake-ups are not evenly spaced.
 */
class WakeScheduleCalculatorTest {

    private final WakeScheduleCalculator calculator = new WakeScheduleCalculator(new Random(42));

    private LocalDateTime at(int hour) {
        return LocalDateTime.of(2026, 7, 28, hour, 0);
    }

    // ========== Active hours ==========

    @Test
    void aNormalWindowIsHalfOpen() {
        assertThat(calculator.isWithinActiveHours(at(9), 9, 18)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(17), 9, 18)).isTrue();
        // end is exclusive
        assertThat(calculator.isWithinActiveHours(at(18), 9, 18)).isFalse();
        assertThat(calculator.isWithinActiveHours(at(8), 9, 18)).isFalse();
    }

    /**
     * A night-owl window (22-6) must not read as "no hours at all", which is what a naive
     * {@code hour >= start && hour < end} does.
     */
    @Test
    void aWindowThatWrapsMidnightIsHandled() {
        assertThat(calculator.isWithinActiveHours(at(22), 22, 6)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(23), 22, 6)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(0), 22, 6)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(5), 22, 6)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(6), 22, 6)).isFalse();
        assertThat(calculator.isWithinActiveHours(at(12), 22, 6)).isFalse();
    }

    @Test
    void identicalBoundsMeanAlwaysActiveRatherThanNeverActive() {
        // An agent that can never wake up is a silently dead agent
        assertThat(calculator.isWithinActiveHours(at(3), 10, 10)).isTrue();
    }

    @Test
    void missingOrCorruptHoursFallBackInsteadOfThrowing() {
        assertThat(calculator.isWithinActiveHours(at(12), null, null)).isTrue();
        assertThat(calculator.isWithinActiveHours(at(3), null, null)).isFalse();
        assertThat(calculator.isWithinActiveHours(at(12), -5, 99)).isTrue();
    }

    // ========== Jitter ==========

    /**
     * Evenly spaced wake-ups are exactly what makes a bot feel like a cron job. Two
     * consecutive gaps must differ.
     */
    @Test
    void consecutiveRhythmGapsAreNotEqual() {
        LocalDateTime first = calculator.nextRhythmWake(at(10), 9, 23, 3);
        LocalDateTime second = calculator.nextRhythmWake(first, 9, 23, 3);
        LocalDateTime third = calculator.nextRhythmWake(second, 9, 23, 3);

        long gap1 = java.time.Duration.between(at(10), first).toMinutes();
        long gap2 = java.time.Duration.between(first, second).toMinutes();
        long gap3 = java.time.Duration.between(second, third).toMinutes();

        assertThat(Set.of(gap1, gap2, gap3)).hasSizeGreaterThan(1);
    }

    @Test
    void rhythmGapsStayNearTheTargetCadence() {
        // 14h window / 3 wakes = 280 minutes base, +-30%
        for (int i = 0; i < 20; i++) {
            LocalDateTime next = calculator.nextRhythmWake(at(10), 9, 23, 3);
            long gap = java.time.Duration.between(at(10), next).toMinutes();
            assertThat(gap).isBetween(190L, 370L);
        }
    }

    @Test
    void aWakeThatWouldFallOutsideTheWindowIsPulledIntoTheNextOne() {
        // 09-11 window, asked at 10:50: base gap would land after 11:00
        LocalDateTime next = calculator.nextRhythmWake(LocalDateTime.of(2026, 7, 28, 10, 50), 9, 11, 3);

        assertThat(calculator.isWithinActiveHours(next, 9, 11)).isTrue();
        assertThat(next).isAfter(LocalDateTime.of(2026, 7, 28, 10, 50));
    }

    @Test
    void initialWakeOutsideTheWindowLandsInTheNextWindow() {
        LocalDateTime next = calculator.initialWake(at(3), 9, 18, 3);

        assertThat(calculator.isWithinActiveHours(next, 9, 18)).isTrue();
        assertThat(next.getHour()).isGreaterThanOrEqualTo(9);
    }

    @Test
    void initialWakeInsideTheWindowIsSoonAndStillInside() {
        LocalDateTime next = calculator.initialWake(at(10), 9, 18, 3);

        assertThat(next).isAfter(at(10));
        assertThat(calculator.isWithinActiveHours(next, 9, 18)).isTrue();
    }

    // ========== Diversity at creation ==========

    /**
     * If every new agent got the same window the community would still pulse, just on a
     * different beat.
     */
    @Test
    void randomActiveHoursSpreadAcrossTheDayAndStayInRange() {
        Set<Integer> starts = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            int[] hours = calculator.randomActiveHours();
            assertThat(hours[0]).isBetween(0, 23);
            assertThat(hours[1]).isBetween(0, 23);
            starts.add(hours[0]);
        }
        assertThat(starts).hasSizeGreaterThan(5);
    }
}
