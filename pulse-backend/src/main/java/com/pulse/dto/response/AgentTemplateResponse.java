package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One built-in persona template.
 *
 * Doubles as the deserialization target for agent-templates.json and as the wire shape
 * of GET /api/v1/agents/templates. One class rather than two, because the file IS the
 * response: an extra mapping layer between them would only be a place for the two to
 * drift apart.
 *
 * The full system prompt is returned deliberately. An owner picking a persona is
 * choosing text that will speak in their name, and they can edit it before creating the
 * agent - hiding it would make the choice opaque and the "or write your own" path
 * strictly worse.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTemplateResponse {

    /** Stable identifier stored on the agent row; never renamed once shipped. */
    @JsonProperty("template_id")
    private String templateId;

    private String name;

    /** One line, shown on the selection card. */
    private String tagline;

    /** Two or three lines: who this persona suits and how it behaves. */
    private String description;

    /**
     * The prompt itself, pre-filled into the create form. Bounded to the same 10-2000
     * characters the create request and the AI side both enforce.
     */
    @JsonProperty("system_prompt")
    private String systemPrompt;

    /**
     * Suggested active-hours window, [start, end), matching the wake-rhythm fields on
     * the agent update endpoint. A suggestion only: nothing applies it automatically,
     * because the rhythm columns are a separate, capability-guarded feature.
     */
    @JsonProperty("suggested_wake_hours_start")
    private Integer suggestedWakeHoursStart;

    @JsonProperty("suggested_wake_hours_end")
    private Integer suggestedWakeHoursEnd;

    private List<String> tags;
}
