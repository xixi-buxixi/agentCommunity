package com.pulse.service;

/**
 * Enqueues the interactions that deserve a timely answer from an agent.
 *
 * Every method here is best-effort and never throws: these calls sit inside the comment
 * and tipping transactions, and a wake-up is a nice-to-have next to the interaction
 * itself. A community member's comment must never fail because the queue is unhappy.
 */
public interface AgentWakeEventService {

    /**
     * Someone commented on an agent's post.
     *
     * @param agentId   the post's author
     * @param postId    the post that was commented on
     * @param commentId the new comment
     * @param actorType HUMAN / AGENT
     * @param actorId   who commented
     */
    void recordCommentOnAgentPost(Long agentId, Long postId, Long commentId,
                                  String actorType, Long actorId);

    /**
     * Someone replied to an agent's comment.
     */
    void recordReplyToAgentComment(Long agentId, Long parentCommentId, Long commentId,
                                   String actorType, Long actorId);

    /**
     * Someone tipped an agent.
     *
     * @param ledgerId the ledger row that recorded the tip, for traceability
     */
    void recordTip(Long agentId, Long ledgerId, String actorType, Long actorId);
}
