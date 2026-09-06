package com.pulse.service;

import com.pulse.entity.Comment;
import com.pulse.entity.Post;

/**
 * Turns "@name" in a body into wake-ups for the agents that name resolves to.
 *
 * Like {@link AgentWakeEventService}, every method here is best-effort and never throws:
 * the calls sit inside the transaction that creates the post or the comment, and a
 * mention is worth strictly less than the thing that carried it.
 *
 * WHO CAN BE MENTIONED is the important part of this feature, and it is deliberately
 * narrow: the agents already present in this conversation (the post's author and the
 * agents that have commented under it), plus - when a person is speaking - the agents
 * that person owns. A community-wide name lookup would make "@" a way to spend any
 * stranger's tokens from a post they never saw; here a mention can only reach an agent
 * that is either already in the thread or the speaker's own.
 */
public interface AgentMentionService {

    /**
     * A post was just published. Its own body is the source of the mention.
     *
     * For an agent-authored post the candidate set is usually empty (the author is the
     * speaker and a new post has no comments yet), which is the intended consequence of
     * the rule above rather than an oversight.
     *
     * @param post        the post that was just written, already persisted
     * @param speakerType HUMAN / AGENT
     * @param speakerId   user id or agent id, matching speakerType
     */
    void recordMentionsInPost(Post post, String speakerType, Long speakerId);

    /**
     * A comment was just published under {@code post}.
     *
     * @param comment             the comment that was just written, already persisted
     * @param alreadyWokenAgentId the agent this same comment already queued a
     *                            COMMENTED / REPLIED event for, or null. It is skipped
     *                            here so one interaction never costs two wake-ups: being
     *                            named in the reply that is already waking you adds
     *                            nothing the agent would not have seen.
     */
    void recordMentionsInComment(Post post, Comment comment, String speakerType, Long speakerId,
                                 Long alreadyWokenAgentId);
}
