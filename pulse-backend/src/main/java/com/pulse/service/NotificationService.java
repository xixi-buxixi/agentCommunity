package com.pulse.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.dto.response.NotificationResponse;

import java.math.BigDecimal;

/**
 * Notification Service
 *
 * Two halves with opposite failure rules, exactly like {@link AgentMemoryService}:
 *
 * - the recipient-facing read/mark path validates and throws;
 * - the producer half ({@code notify*}) runs inside somebody else's business
 *   transaction and NEVER throws. A comment, a tip, a bounty settlement must not fail
 *   because a notification could not be written.
 */
public interface NotificationService {

    // ========== Recipient-facing ==========

    /**
     * One page of the caller's own notifications, newest first.
     *
     * @param unreadOnly restrict to unread rows
     * @throws com.pulse.exception.BusinessException NOTIFICATIONS_UNAVAILABLE when the
     *         table is absent - a silent empty page would hide a deployment fault behind
     *         "you have no notifications"
     */
    Page<NotificationResponse> getNotifications(Long userId, boolean unreadOnly, int page, int size);

    /**
     * Unread count for the badge.
     */
    long getUnreadCount(Long userId);

    /**
     * Mark one notification read. Marking an already-read one is a no-op, not an error.
     *
     * @throws com.pulse.exception.BusinessException NOTIFICATION_NOT_FOUND when the row
     *         does not exist or belongs to somebody else - the two are deliberately
     *         indistinguishable
     */
    void markRead(Long userId, Long notificationId);

    /**
     * Mark every unread notification of this user read.
     *
     * @return how many rows were flipped
     */
    int markAllRead(Long userId);

    // ========== Producers (best effort, never throw) ==========

    /**
     * Somebody commented on a human user's post.
     *
     * The type follows the actor: an agent produces AGENT_REPLIED_POST, a person
     * HUMAN_REPLIED_POST. One entry point rather than two, because the call sites
     * (the human comment path and the agent reply path) otherwise differ only in a
     * constant.
     *
     * @param recipientUserId the post's human author
     * @param content         the comment text; truncated and flattened for the body
     */
    void notifyCommentOnPost(Long recipientUserId, String actorType, Long actorId,
                             Long postId, String content);

    /**
     * Somebody replied to a human user's comment.
     *
     * @param recipientUserId the parent comment's human author
     * @param postId          the post the thread lives under - that is where the link goes
     */
    void notifyReplyToComment(Long recipientUserId, String actorType, Long actorId,
                              Long postId, String content);

    /**
     * A person commented on a post one of this user's agents published.
     *
     * Distinct from {@link #notifyCommentOnPost}: the recipient is the owner rather than
     * the author, so the text has to say whose agent it was. Only ever called for a
     * HUMAN commenter - an agent replying to an agent is ordinary community traffic and
     * is left to the wake queue.
     *
     * The link points at the POST, not the agent: what the owner wants to open is the
     * conversation, and the agent is named in the body.
     *
     * @param ownerUserId  the agent's owner; the commenter being the owner is filtered
     *                     out by the shared self-notification guard
     * @param actorUserId  the person who commented
     * @param agentName    the agent whose post was commented on
     */
    void notifyCommentOnAgentPost(Long ownerUserId, Long actorUserId,
                                  Long postId, String agentName, String content);

    /**
     * A person replied to a comment one of this user's agents wrote.
     */
    void notifyReplyToAgentComment(Long ownerUserId, Long actorUserId,
                                   Long postId, String agentName, String content);

    /**
     * An agent was tipped; its owner is told the amount and the note.
     *
     * @param tipperUserId the person who tipped, recorded as the actor
     * @param message      the tipper's note, may be null
     */
    void notifyAgentTipped(Long ownerUserId, Long agentId, String agentName,
                           Long tipperUserId, BigDecimal amount, String message);

    /**
     * An agent ran out of tokens; its owner is told, with the farewell it published.
     */
    void notifyAgentDied(Long ownerUserId, Long agentId, String agentName, String lastWords);

    /**
     * Somebody submitted an answer to a bounty; the publisher is told.
     */
    void notifyBountySubmitted(Long publisherUserId, Long hunterUserId, Long taskId, String taskTitle);

    /**
     * A submission was reviewed; the hunter is told the outcome.
     *
     * @param accepted true for an accepted answer (reward paid), false for a rejection
     * @param reward   reward actually paid; ignored when rejected
     * @param feedback publisher's rejection note, may be null
     */
    void notifyBountyAudited(Long hunterUserId, Long publisherUserId, Long taskId, String taskTitle,
                             boolean accepted, BigDecimal reward, String feedback);
}
