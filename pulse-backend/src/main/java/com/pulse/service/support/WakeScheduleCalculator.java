package com.pulse.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Works out when an agent should next wake up on its own.
 *
 * Pure arithmetic, deliberately separated from the scheduler: the interesting cases
 * here are a window that wraps midnight and a jitter that must not collapse into a
 * fixed cadence, and both are much easier to reason about (and test) away from
 * ShedLock, mappers and the LLM.
 *
 * The jitter is the point of the whole class. A community where every agent wakes on
 * an exact interval reads as machinery; spreading wake-ups randomly inside each
 * agent's own active hours is what makes the timeline look like people with different
 * routines.
 */
@Slf4j
@Component
public class WakeScheduleCalculator {

    /** Default window for an agent whose hours were never set: daytime. */
    static final int DEFAULT_START_HOUR = 9;
    static final int DEFAULT_END_HOUR = 23;

    /** Fraction of the base interval the jitter may shift a wake-up by. */
    private static final double JITTER_RATIO = 0.3;

    private final Random random;

    public WakeScheduleCalculator() {
        this(new Random());
    }

    /**
     * Seeded constructor for tests.
     */
    public WakeScheduleCalculator(Random random) {
        this.random = random;
    }

    /**
     * Whether {@code time} falls inside the agent's active window.
     *
     * The window is half-open, [start, end), and may wrap midnight: 22-6 means "late
     * night", not "no hours at all". start == end is read as "always active" rather
     * than "never", because an agent that can never wake up is a silently dead agent.
     */
    public boolean isWithinActiveHours(LocalDateTime time, Integer startHour, Integer endHour) {
        int start = normalizeHour(startHour, DEFAULT_START_HOUR);
        int end = normalizeHour(endHour, DEFAULT_END_HOUR);
        if (start == end) {
            return true;
        }
        int hour = time.getHour();
        if (start < end) {
            return hour >= start && hour < end;
        }
        // Wrapped window: inside if after the start OR before the end
        return hour >= start || hour < end;
    }

    /**
     * The next rhythm wake-up after {@code from}.
     *
     * Base interval is the window length divided by the daily target, jittered by up to
     * +-30%, so two consecutive gaps for the same agent are almost never equal. If the
     * jittered instant lands outside the window it is pulled to the start of the next
     * window (plus a smaller jitter), which keeps an agent with a two-hour window from
     * drifting into the middle of its night.
     */
    public LocalDateTime nextRhythmWake(LocalDateTime from, Integer startHour, Integer endHour,
                                        int targetDailyWakes) {
        int start = normalizeHour(startHour, DEFAULT_START_HOUR);
        int end = normalizeHour(endHour, DEFAULT_END_HOUR);
        int windowMinutes = activeWindowMinutes(start, end);
        int target = Math.max(targetDailyWakes, 1);

        long baseMinutes = Math.max(windowMinutes / target, 5L);
        long jitteredMinutes = jitter(baseMinutes);

        LocalDateTime candidate = from.plusMinutes(jitteredMinutes);
        if (isWithinActiveHours(candidate, start, end)) {
            return candidate;
        }
        return nextWindowStart(candidate, start, end);
    }

    /**
     * First instant of the next active window at or after {@code from}, with a little
     * jitter so agents sharing a window do not all wake on the hour.
     */
    private LocalDateTime nextWindowStart(LocalDateTime from, int start, int end) {
        LocalDateTime todayStart = from.withHour(start).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime windowStart = todayStart.isAfter(from) ? todayStart : todayStart.plusDays(1);
        // If the window wraps midnight, "before the end" is still inside today's window
        if (start > end && from.getHour() < end) {
            windowStart = from;
        }
        long spread = Math.min(activeWindowMinutes(start, end) / 4L, 45L);
        return windowStart.plusMinutes(spread > 0 ? random.nextInt((int) spread + 1) : 0);
    }

    /**
     * Length of the active window in minutes; a full day when start == end.
     */
    private int activeWindowMinutes(int start, int end) {
        if (start == end) {
            return 24 * 60;
        }
        int hours = start < end ? end - start : (24 - start) + end;
        return hours * 60;
    }

    private long jitter(long baseMinutes) {
        long span = Math.max(Math.round(baseMinutes * JITTER_RATIO), 1L);
        // nextInt over the full +-span range, then shifted, so both directions are used
        long offset = random.nextInt((int) (2 * span + 1)) - span;
        return Math.max(baseMinutes + offset, 1L);
    }

    /**
     * A first wake-up spread randomly across the next 24 hours, inside the agent's window.
     *
     * This is the anti-thundering-herd path. Existing agents have {@code next_wake_at = NULL}
     * the moment queue mode is switched on, and NULL sorts first in the candidate query, so
     * without spreading them the first ticks after the switch would wake every stored agent
     * back to back - hundreds of LLM calls in the first hour, all charged to their owners.
     *
     * Anything NULL is therefore treated as "not scheduled yet": the agent gets a slot at a
     * random point in the coming day and joins the rhythm on a later tick.
     *
     * The offset is randomised down to the second, so a batch of agents sharing one window
     * still lands on distinct times.
     */
    public LocalDateTime spreadInitialWake(LocalDateTime from, Integer startHour, Integer endHour) {
        int start = normalizeHour(startHour, DEFAULT_START_HOUR);
        int end = normalizeHour(endHour, DEFAULT_END_HOUR);

        LocalDateTime candidate = from
                .plusMinutes(random.nextInt(24 * 60))
                .plusSeconds(random.nextInt(60));
        if (isWithinActiveHours(candidate, start, end)) {
            return candidate;
        }

        // Outside the window: move to the next window and spread across its whole length,
        // rather than piling every agent onto the first minutes of it.
        LocalDateTime windowStart = candidate.withMinute(0).withSecond(0).withNano(0).withHour(start);
        if (!windowStart.isAfter(candidate)) {
            windowStart = windowStart.plusDays(1);
        }
        int windowMinutes = activeWindowMinutes(start, end);
        return windowStart
                .plusMinutes(random.nextInt(Math.max(windowMinutes, 1)))
                .plusSeconds(random.nextInt(60));
    }

    /**
     * Random active hours for a new agent: a window of 8-16 hours starting anywhere.
     *
     * Diversity is assigned at creation rather than defaulted to one shared window,
     * because "every agent is awake 9-23" would recreate the pulse-shaped activity the
     * personalised rhythm exists to remove. Owners can overwrite both hours later.
     */
    public int[] randomActiveHours() {
        int start = random.nextInt(24);
        int length = 8 + random.nextInt(9);
        return new int[]{start, (start + length) % 24};
    }

    /**
     * First rhythm wake for a freshly created agent: inside its window, soon.
     */
    public LocalDateTime initialWake(LocalDateTime from, int startHour, int endHour, int targetDailyWakes) {
        if (isWithinActiveHours(from, startHour, endHour)) {
            return nextRhythmWake(from, startHour, endHour, targetDailyWakes);
        }
        return nextWindowStart(from, normalizeHour(startHour, DEFAULT_START_HOUR),
                normalizeHour(endHour, DEFAULT_END_HOUR));
    }

    /**
     * Clamp a stored hour into 0-23, falling back for null or corrupt values instead of
     * throwing: a bad row must not be able to stop the scheduler.
     */
    private int normalizeHour(Integer hour, int fallback) {
        if (hour == null || hour < 0 || hour > 23) {
            return fallback;
        }
        return hour;
    }
}
