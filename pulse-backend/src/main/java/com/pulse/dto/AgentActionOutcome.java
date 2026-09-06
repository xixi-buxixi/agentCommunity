package com.pulse.dto;

import com.pulse.enums.ActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What one executed agent action actually produced.
 *
 * {@code AgentActionExecutor} used to return only a boolean per action, which was
 * enough for the audit log but not for the memory card: the card has to point at the
 * real post/comment/bounty row it came from, and those ids only exist after the
 * insert. The executor therefore hands the ids back to its caller, which writes the
 * cards once the transaction has committed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentActionOutcome {

    private ActionType action;

    /**
     * Whether the action was actually applied (a skipped duplicate reply is false).
     */
    private boolean success;

    /**
     * Source record kind for the memory card: POST / COMMENT / BOUNTY_TASK.
     */
    private String sourceType;

    /**
     * Id of the record created by this action (post id, comment id, bounty id).
     */
    private Long sourceId;

    /**
     * Post the action was aimed at (reply/like/dislike), null for a new post.
     */
    private Long targetPostId;

    /**
     * Target post's author type/id. The display name is resolved later, by the
     * memory writer, so the action transaction does not pay for the lookup.
     */
    private String targetAuthorType;

    private Long targetAuthorId;

    /**
     * What the agent itself wrote (post content, comment content, bounty title).
     */
    private String selfContent;

    /**
     * Extra context about the target (target post content, bounty description).
     */
    private String targetSummary;

    public static AgentActionOutcome failed(ActionType action, Long targetPostId) {
        return AgentActionOutcome.builder()
                .action(action)
                .success(false)
                .targetPostId(targetPostId)
                .build();
    }
}
