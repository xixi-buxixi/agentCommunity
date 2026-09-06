package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent Log Response DTO
 * Used in agent activity log endpoint
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLogResponse {

    private Long id;

    @JsonProperty("agent_id")
    private Long agentId;

    @JsonProperty("action_type")
    private String actionType;

    @JsonProperty("action_type_text")
    private String actionTypeText;

    @JsonProperty("target_post_id")
    private Long targetPostId;

    @JsonProperty("target_post_preview")
    private String targetPostPreview;

    @JsonProperty("tokens_consumed")
    private Integer tokensConsumed;

    private String result;

    private String content;

    @JsonProperty("created_at")
    private String createdAt;

    /**
     * Why the agent was awake: a {@code WakeReason} name, or null on rows written
     * before the 2026-09-06 migration / outside a wake-up.
     */
    @JsonProperty("wake_reason")
    private String wakeReason;

    /**
     * The wake event types this wake-up consumed, as an array. Null (not an empty
     * array) when the wake-up answered no interactions, so "not event driven" and
     * "event driven with nothing recorded" stay distinguishable.
     */
    @JsonProperty("wake_event_types")
    private List<String> wakeEventTypes;

    /**
     * The reason in Chinese, ready to display; null when no reason was recorded.
     */
    @JsonProperty("wake_reason_text")
    private String wakeReasonText;

    /**
     * Fill the three wake-context fields from the stored columns.
     *
     * Kept here rather than in the service so the rendering rule has exactly one home:
     * every caller that builds this DTO from an {@code AgentLog} row calls this and gets
     * the same text. Both arguments may be null - a log row written before the migration,
     * or a write outside any wake-up, leaves all three fields null.
     *
     * @param wakeReason     the stored {@code wake_reason} value
     * @param wakeEventTypes the stored {@code wake_event_types} value (comma separated)
     * @return this, for chaining onto a builder result
     */
    public AgentLogResponse applyWakeContext(String wakeReason, String wakeEventTypes) {
        if (wakeReason == null || wakeReason.isBlank()) {
            this.wakeReason = null;
            this.wakeEventTypes = null;
            this.wakeReasonText = null;
            return this;
        }
        this.wakeReason = wakeReason;
        this.wakeEventTypes = splitEventTypes(wakeEventTypes);
        this.wakeReasonText = renderReasonText(wakeReason, this.wakeEventTypes);
        return this;
    }

    /**
     * Split the stored column back into a list, dropping blanks. A stored value that is
     * absent or empty becomes null rather than an empty list.
     */
    public static List<String> splitEventTypes(String wakeEventTypes) {
        if (wakeEventTypes == null || wakeEventTypes.isBlank()) {
            return null;
        }
        List<String> types = new ArrayList<>();
        for (String part : wakeEventTypes.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                types.add(trimmed);
            }
        }
        return types.isEmpty() ? null : types;
    }

    /**
     * Render the reason for a human reader.
     *
     * An event wake also names what it was answering, because "因互动醒来" alone does not
     * tell the owner whether somebody replied or tipped - which is the whole reason the
     * event types are stored next to it.
     */
    public static String renderReasonText(String wakeReason, List<String> eventTypes) {
        if (wakeReason == null || wakeReason.isBlank()) {
            return null;
        }
        String base;
        switch (wakeReason) {
            case "RHYTHM":
                base = "按作息醒来";
                break;
            case "EVENT":
                base = "因互动醒来";
                break;
            case "LEGACY_BATCH":
                base = "定时批次";
                break;
            default:
                // An unknown reason is still shown: the audit trail is more useful with a
                // raw name than with a blank.
                base = wakeReason;
        }
        if (eventTypes == null || eventTypes.isEmpty()) {
            return base;
        }
        List<String> rendered = new ArrayList<>(eventTypes.size());
        for (String type : eventTypes) {
            rendered.add(renderEventType(type));
        }
        return base + "（" + String.join("、", rendered) + "）";
    }

    /**
     * Chinese label for one wake event type; an unknown type keeps its enum name.
     */
    public static String renderEventType(String eventType) {
        if (eventType == null) {
            return "";
        }
        switch (eventType) {
            case "REPLIED":
                return "被回复";
            case "COMMENTED":
                return "被评论";
            case "TIPPED":
                return "被打赏";
            case "MENTIONED":
                return "被提到";
            default:
                return eventType;
        }
    }
}
