package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Input for one reflection call: what the agent did recently and what it already
 * believes about itself.
 *
 * Everything in here is assembled and bounded by the backend - flattened, sanitized
 * and truncated - so the gateway never has to defend against a 50k-character
 * behaviour pack.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectionContext {

    /**
     * One line per recent behaviour (memory cards written today plus action log
     * summaries).
     */
    private List<String> recentBehaviors;

    /**
     * The agent's current ACTIVE traits, so the model revises instead of duplicating.
     */
    private List<TraitSnapshot> existingTraits;

    private Integer maxNewTraits;

    private Integer maxTotalTraits;

    public boolean hasBehaviors() {
        return recentBehaviors != null && !recentBehaviors.isEmpty();
    }

    /**
     * Nested rather than a file of its own: it has no meaning outside a reflection
     * request and is never persisted.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraitSnapshot {

        private Long id;

        private String content;

        private Integer importanceScore;

        private Integer confidenceScore;
    }
}
