package com.pulse.dto;

import com.pulse.entity.AgentWakeEvent;
import com.pulse.enums.WakeEventType;
import com.pulse.enums.WakeReason;
import lombok.Getter;

import java.util.List;
import java.util.TreeSet;

/**
 * Why an agent was awake, in the form the activity log stores it.
 *
 * A small carrier rather than two extra parameters on every executor method: the pair
 * always travels together, is always derived the same way, and the derivation (dedupe +
 * alphabetical order + column-width cap) is the part worth testing on its own.
 *
 * Null is a valid absence: reflection runs and scheduler error rows are written outside
 * any wake-up and record no reason at all.
 */
@Getter
public final class WakeLogContext {

    /** agent_logs.wake_reason is VARCHAR(16); the longest name today is LEGACY_BATCH. */
    private static final int REASON_MAX_LENGTH = 16;

    /** agent_logs.wake_event_types is VARCHAR(64). */
    private static final int EVENT_TYPES_MAX_LENGTH = 64;

    /** {@link WakeReason} name, never blank. */
    private final String reason;

    /**
     * Distinct event types consumed by this wake-up, sorted alphabetically and comma
     * separated. Null for a wake-up that answered no interactions.
     */
    private final String eventTypes;

    private WakeLogContext(String reason, String eventTypes) {
        this.reason = reason;
        this.eventTypes = eventTypes;
    }

    /**
     * Build the context for one wake-up.
     *
     * @param reason why the scheduler woke this agent; a null reason yields a null
     *               context, so a caller outside the wake path stays on the old write
     * @param events the events this wake-up actually consumed (may be null or empty)
     */
    public static WakeLogContext of(WakeReason reason, List<AgentWakeEvent> events) {
        if (reason == null) {
            return null;
        }
        return new WakeLogContext(truncate(reason.name(), REASON_MAX_LENGTH), renderTypes(events));
    }

    /**
     * One wake-up merges several events, frequently of different kinds. They are stored
     * as a de-duplicated, alphabetically sorted list so that "COMMENTED,TIPPED" is the
     * same string whatever order the queue handed the events over in - otherwise the
     * column could not be grouped or compared.
     *
     * An event whose type does not map to a known {@link WakeEventType} keeps its raw
     * code (upper-cased): an unknown type is still worth seeing in the audit trail.
     */
    private static String renderTypes(List<AgentWakeEvent> events) {
        if (events == null || events.isEmpty()) {
            return null;
        }
        TreeSet<String> distinct = new TreeSet<>();
        for (AgentWakeEvent event : events) {
            if (event == null) {
                continue;
            }
            WakeEventType type = event.getEventTypeEnum();
            String code = type != null ? type.getCode() : normalizeRaw(event.getEventType());
            if (code != null && !code.isEmpty()) {
                distinct.add(code);
            }
        }
        if (distinct.isEmpty()) {
            return null;
        }
        return joinWithinColumn(distinct);
    }

    /**
     * Join the codes, dropping whole entries once the column is full.
     *
     * A blind substring cut lands wherever the 64th character happens to fall, which
     * for a list is usually inside an entry: "COMMENTED,TIPPED,UNRECOGNI" reads as a
     * fourth event type that never existed, and the column cannot be split on commas
     * any more. Dropping the entry that does not fit keeps every value in the stored
     * string a value that really occurred - the list is then incomplete, which is
     * honest, rather than wrong.
     *
     * A code that does not fit is skipped, not treated as the end of the list: the
     * entries arrive in alphabetical order, not in order of length, so stopping at the
     * first oversized one would discard shorter codes after it that the column had room
     * for. Which entries are kept is then a property of the widths involved rather than
     * of where the long one happened to sort.
     *
     * Returns null in the (unreachable today) case where no entry fits at all:
     * agent_wake_events.event_type is VARCHAR(32), half this column's width.
     */
    private static String joinWithinColumn(TreeSet<String> codes) {
        StringBuilder joined = new StringBuilder();
        for (String code : codes) {
            int needed = joined.length() == 0 ? code.length() : code.length() + 1;
            if (joined.length() + needed > EVENT_TYPES_MAX_LENGTH) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(code);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static String normalizeRaw(String rawType) {
        if (rawType == null) {
            return null;
        }
        String trimmed = rawType.trim().toUpperCase();
        // A raw code is written straight into a VARCHAR column and read back by the UI;
        // a comma inside it would forge an extra entry in the list.
        return trimmed.replace(",", "_");
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
