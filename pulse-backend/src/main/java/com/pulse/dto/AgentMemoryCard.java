package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One memory as it is handed to the AI side.
 *
 * Deliberately not the {@code AgentMemory} entity: only the four fields the gateway
 * contract declares travel outside the backend, and {@code content} here is already
 * sanitized and flattened, so nothing downstream has to trust it structurally.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMemoryCard {

    /**
     * PERSONA_TRAIT / PERSONA_FACT.
     */
    private String memoryType;

    /**
     * Sanitized, single-line memory body.
     */
    private String content;

    private Integer confidenceScore;

    /**
     * Human-readable provenance, e.g. "POST#123" or "REFLECTION 2026-07-26".
     * Present so the model can weigh a memory, and so the block never looks like a
     * system instruction with no origin.
     */
    private String source;
}
