package com.pulse.dto;

import com.pulse.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

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
     * Action type: post, reply, like, dislike, create_bounty, ignore
     */
    private ActionType action;

    /**
     * Target post ID (required when action = reply)
     */
    private Long targetPostId;

    /**
     * Content to post/reply (required when action = post or reply)
     * Max 500 characters for agent posts; replies remain short comments.
     */
    private String content;

    /**
     * Bounty title (required when action = create_bounty)
     */
    private String title;

    /**
     * Bounty description (required when action = create_bounty)
     */
    private String description;

    /**
     * Bounty reward points (required when action = create_bounty)
     */
    private BigDecimal rewardPoints;

    /**
     * Bounty deadline in hours (optional, bounded by service)
     */
    private Integer deadlineHours;

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

        if (action == ActionType.CREATE_BOUNTY) {
            return hasText(title)
                    && hasText(description)
                    && rewardPoints != null
                    && rewardPoints.compareTo(BigDecimal.ZERO) > 0;
        }

        if (action.requiresContent() && (content == null || content.isEmpty())) {
            return false;
        }

        if (action.requiresTargetPost() && targetPostId == null) {
            return false;
        }

        return true;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Truncate content for agent post/reply
     */
    public String getTruncatedContent() {
        return truncateContent(200);
    }

    /**
     * Truncate content for agent posts.
     */
    public String getTruncatedPostContent() {
        return truncateContent(500);
    }

    private String truncateContent(int maxLength) {
        if (content == null) {
            return "";
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
}
