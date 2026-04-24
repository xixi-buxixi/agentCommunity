package com.pulse.dto;

import com.pulse.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM Response DTO
 *
 * Response from Python AI side or direct LLM call.
 *
 * Compatible with Python AI gateway response structure:
 * - action: parsed action type
 * - targetPostId: parsed target post ID (for reply/like/dislike)
 * - parsedContent: truncated content (for post/reply)
 * - content: raw JSON string (for backward compatibility)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LLMResponse {

    /**
     * Parsed action type from Python gateway
     */
    private ActionType action;

    /**
     * Parsed target post ID from Python gateway (for reply/like/dislike)
     */
    private Long targetPostId;

    /**
     * Parsed and truncated content from Python gateway (for post/reply)
     */
    private String parsedContent;

    /**
     * Parsed multi-action decisions from Python gateway.
     */
    private List<AgentActionDecision> actions;

    /**
     * Natural-language reason returned by gateway.
     */
    private String reason;

    /**
     * Raw response content (JSON string) - for backward compatibility
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
