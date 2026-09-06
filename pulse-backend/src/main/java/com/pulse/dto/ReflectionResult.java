package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Parsed answer of {@code POST /v1/llm/reflection}.
 *
 * Every field is model output and therefore untrusted: ids may point at another
 * agent's memories, scores may be out of range, content may carry a prompt injection
 * or a secret. Validation happens where it is persisted
 * ({@code AgentMemoryService#applyReflection}), not here - this DTO only reports what
 * the gateway said.
 *
 * A failed call is represented as {@code success=false} with empty lists, so callers
 * can treat "failure" and "nothing to change" identically.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReflectionResult {

    private Boolean success;

    private List<TraitDraft> newTraits;

    private List<TraitUpdate> updatedTraits;

    private List<Long> deprecatedTraitIds;

    private Integer totalTokens;

    private String errorMessage;

    public static ReflectionResult failed(String errorMessage) {
        return ReflectionResult.builder()
                .success(false)
                .newTraits(List.of())
                .updatedTraits(List.of())
                .deprecatedTraitIds(List.of())
                .errorMessage(errorMessage)
                .build();
    }

    public boolean isSuccessful() {
        return Boolean.TRUE.equals(success);
    }

    public List<TraitDraft> safeNewTraits() {
        return newTraits != null ? newTraits : List.of();
    }

    public List<TraitUpdate> safeUpdatedTraits() {
        return updatedTraits != null ? updatedTraits : List.of();
    }

    public List<Long> safeDeprecatedTraitIds() {
        return deprecatedTraitIds != null ? deprecatedTraitIds : List.of();
    }

    /**
     * A trait the model wants to add.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraitDraft {

        private String content;

        private String evidence;

        private Integer importanceScore;

        private Integer confidenceScore;
    }

    /**
     * A revision of an existing trait. The id is claimed by the model and must be
     * verified against the agent's own PERSONA_TRAIT rows before use.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TraitUpdate {

        private Long id;

        private String content;

        /**
         * Optional refreshed evidence. Null means "keep what the card already cites";
         * without it a revised trait would keep quoting the text it no longer says.
         */
        private String evidence;

        private Integer importanceScore;

        private Integer confidenceScore;
    }
}
