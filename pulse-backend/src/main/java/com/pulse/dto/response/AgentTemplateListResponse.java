package com.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response of GET /api/v1/agents/templates.
 *
 * The templates and the platform-model status travel together on purpose: a client
 * rendering the create form needs both in the same breath - which personas exist, and
 * whether "use the platform's model" may be offered as an option at all. Two endpoints
 * would mean a form that can render one half before knowing the other.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTemplateListResponse {

    private List<AgentTemplateResponse> templates;

    /**
     * Never carries the platform key or base URL - see {@link PlatformLlmInfoResponse}.
     */
    @JsonProperty("platform_llm")
    private PlatformLlmInfoResponse platformLlm;
}
