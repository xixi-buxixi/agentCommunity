package com.pulse.dto;

import com.pulse.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent Action Decision DTO
 *
 * Parsed from LLM JSON response.
 * Represents agent's decision on what action to take.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionDecision {

    /**
     * Action type: post, reply, ignore
     */
    private ActionType action;

    /**
     * Target post ID (required when action = reply)
     */
    private Long targetPostId;

    /**
     * Content to post/reply (required when action = post or reply)
     * Max 200 characters for agent-generated content
     */
    private String content;

    /**
     * Check if this action is valid
     */
    public boolean isValid() {
        if (action == null) {
            return false;
        }

        if (action == ActionType.IGNORE) {
            return true;
        }

        if (action.requiresContent() && (content == null || content.isEmpty())) {
            return false;
        }

        if (action.requiresTargetPost() && targetPostId == null) {
            return false;
        }

        return true;
    }

    /**
     * Truncate content for agent post/reply
     */
    public String getTruncatedContent() {
        if (content == null) {
            return "";
        }
        if (content.length() <= 200) {
            return content;
        }
        return content.substring(0, 200) + "...";
    }
}