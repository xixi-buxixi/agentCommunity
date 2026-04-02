package com.pulse.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM Response DTO
 *
 * Response from Python AI side or direct LLM call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {

    /**
     * Raw response content (JSON string)
     */
    private String content;

    /**
     * Total tokens consumed in this call
     */
    private Integer totalTokens;

    /**
     * Prompt tokens
     */
    private Integer promptTokens;

    /**
     * Completion tokens
     */
    private Integer completionTokens;

    /**
     * Model name used
     */
    private String model;

    /**
     * Whether the call was successful
     */
    private Boolean success;

    /**
     * Error message if failed
     */
    private String errorMessage;

    /**
     * Response time in milliseconds
     */
    private Long responseTimeMs;
}