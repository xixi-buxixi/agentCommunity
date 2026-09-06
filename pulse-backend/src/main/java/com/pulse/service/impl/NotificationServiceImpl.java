package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.response.NotificationResponse;
import com.pulse.entity.Notification;
import com.pulse.enums.AuthorType;
import com.pulse.enums.NotificationLinkType;
import com.pulse.enums.NotificationType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.NotificationMapper;
import com.pulse.service.NotificationService;
import com.pulse.service.support.AuthorResolver;
import com.pulse.util.MemoryTextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Notification Service Implementation.
 *
 * Four rules hold for every producer call, and they are the same three that govern
 * {@link AgentWakeEventServiceImpl} plus one:
 * 1. Never throw. The caller is in the middle of creating a comment, moving points or
 *    settling a bounty; a notification is worth strictly less than any of those.
 * 2. Never notify somebody about their own action. The project already forbids most
 *    self-interactions upstream, but the guard lives here so a new call site cannot
 *    reintroduce it.
 * 3. Do nothing at all when the table is absent, so an un-migrated database keeps
 *    serving the whole community normally.
 * 4. Store a snapshot. Title and body are rendered once, at the moment of the event,
 *    and never re-derived from rows that may since have changed.
 *
 * The read half is the opposite: it validates, it filters on the recipient, and a
 * missing table is reported rather than hidden (see {@link #requireTable()}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    /** Column width of notifications.title. */
    static final int TITLE_MAX_LENGTH = 200;

    /** Column width of notifications.body. */
    static final int BODY_MAX_LENGTH = 500;

    /**
     * Cap for a quoted fragment (a comment, a tip note, a bounty title) inside a body.
     * Well under {@link #BODY_MAX_LENGTH} so the surrounding template always fits.
     */
    static final int EXCERPT_MAX_LENGTH = 120;

    /** Cap for an interpolated display name. */
    static final int NAME_MAX_LENGTH = 30;

    /** Page size ceiling, matching the MyBatis Plus pagination interceptor. */
    private static final int MAX_PAGE_SIZE = 50;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final NotificationMapper notificationMapper;
    private final SchemaCapabilities schemaCapabilities;
    private final AuthorResolver authorResolver;

    // ========== Recipient-facing ==========

    @Override
    public Page<NotificationResponse> getNotifications(Long userId, boolean unreadOnly,
                                                       int page, int size) {
        requireTable();

        Page<Notification> pageRequest = new Page<>(Math.max(page, 1),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        LambdaQueryWrapper<Notification> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Notification::getRecipientUserId, userId);
        if (unreadOnly) {
            queryWrapper.eq(Notification::getIsRead, 0);
        }
        // id as the tiebreaker: two notifications from the same transaction share a
        // created_at to the second, and an unstable order would make page 2 repeat or
        // skip rows.
        queryWrapper.orderByDesc(Notification::getCreatedAt).orderByDesc(Notification::getId);

        Page<Notification> rows = notificationMapper.selectPage(pageRequest, queryWrapper);
        List<Notification> records = rows.getRecords();

        // One batch for every actor on the page (at most three queries in total),
        // instead of one or two per row.
        Map<String, AuthorResolver.AuthorInfo> actors = authorResolver.resolveAll(
                records, Notification::getActorType, Notification::getActorId);

        Page<NotificationResponse> responsePage = new Page<>(rows.getCurrent(), rows.getSize(),
                rows.getTotal());
        responsePage.setRecords(records.stream()
                .map(row -> toResponse(row, actors))
                .toList());
        return responsePage;
    }

    @Override
    public long getUnreadCount(Long userId) {
        requireTable();
        return notificationMapper.countUnread(userId);
    }

    @Override
    public void markRead(Long userId, Long notificationId) {
        requireTable();

        // The recipient predicate is inside the UPDATE, so there is no window between
        // "it is yours" and "it is marked".
        int updated = notificationMapper.markRead(notificationId, userId, LocalDateTime.now());
        if (updated > 0) {
            return;
        }
        // Nothing changed: either it was already read (fine) or it is not the caller's
        // (404). A notification somebody may not see must not be distinguishable from
        // one that does not exist, so both non-existence and another user's row report
        // the same error.
        if (notificationMapper.existsForUser(notificationId, userId) == 0) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }
    }

    @Override
    public int markAllRead(Long userId) {
        requireTable();
        int updated = notificationMapper.markAllRead(userId, LocalDateTime.now());
        log.debug("Marked notifications read: userId={}, count={}", userId, updated);
        return updated;
    }

    // ========== Producers ==========

    @Override
    public void notifyCommentOnPost(Long recipientUserId, String actorType, Long actorId,
                                    Long postId, String content) {
        boolean byAgent = AuthorType.AGENT.getCode().equalsIgnoreCase(actorType);
        NotificationType type = byAgent
                ? NotificationType.AGENT_REPLIED_POST
                : NotificationType.HUMAN_REPLIED_POST;
        write(type, recipientUserId, actorType, actorId,
                NotificationLinkType.POST, postId,
                type.getText(),
                excerpt(content));
    }

    @Override
    public void notifyReplyToComment(Long recipientUserId, String actorType, Long actorId,
                                     Long postId, String content) {
        boolean byAgent = AuthorType.AGENT.getCode().equalsIgnoreCase(actorType);
        NotificationType type = byAgent
                ? NotificationType.AGENT_REPLIED_COMMENT
                : NotificationType.HUMAN_REPLIED_COMMENT;
        write(type, recipientUserId, actorType, actorId,
                NotificationLinkType.POST, postId,
                type.getText(),
                excerpt(content));
    }

    @Override
    public void notifyCommentOnAgentPost(Long ownerUserId, Long actorUserId,
                                         Long postId, String agentName, String content) {
        write(NotificationType.AGENT_POST_COMMENTED_BY_HUMAN, ownerUserId,
                AuthorType.HUMAN.getCode(), actorUserId,
                NotificationLinkType.POST, postId,
                NotificationType.AGENT_POST_COMMENTED_BY_HUMAN.getText(),
                "Agent [" + shortName(agentName) + "] 的帖子收到新评论：" + excerpt(content));
    }

    @Override
    public void notifyReplyToAgentComment(Long ownerUserId, Long actorUserId,
                                          Long postId, String agentName, String content) {
        write(NotificationType.AGENT_COMMENT_REPLIED_BY_HUMAN, ownerUserId,
                AuthorType.HUMAN.getCode(), actorUserId,
                NotificationLinkType.POST, postId,
                NotificationType.AGENT_COMMENT_REPLIED_BY_HUMAN.getText(),
                "Agent [" + shortName(agentName) + "] 的评论收到回复：" + excerpt(content));
    }

    @Override
    public void notifyAgentTipped(Long ownerUserId, Long agentId, String agentName,
                                  Long tipperUserId, BigDecimal amount, String message) {
        String name = shortName(agentName);
        String amountText = amount == null ? "0" : amount.stripTrailingZeros().toPlainString();
        String body = "Agent [" + name + "] 收到 " + amountText + " 积分";
        if (message != null && !message.isBlank()) {
            body = body + "，附言：" + excerpt(message);
        }
        write(NotificationType.AGENT_TIPPED, ownerUserId,
                AuthorType.HUMAN.getCode(), tipperUserId,
                NotificationLinkType.AGENT, agentId,
                NotificationType.AGENT_TIPPED.getText(),
                body);
    }

    @Override
    public void notifyAgentDied(Long ownerUserId, Long agentId, String agentName, String lastWords) {
        // The agent itself is the actor: nobody did this to it, and the owner's list
        // should say which agent died even before the body is read.
        write(NotificationType.AGENT_DIED, ownerUserId,
                AuthorType.AGENT.getCode(), agentId,
                NotificationLinkType.AGENT, agentId,
                NotificationType.AGENT_DIED.getText(),
                "Agent [" + shortName(agentName) + "] 的 token 已耗尽。" + excerpt(lastWords));
    }

    @Override
    public void notifyBountySubmitted(Long publisherUserId, Long hunterUserId, Long taskId,
                                      String taskTitle) {
        write(NotificationType.BOUNTY_SUBMITTED, publisherUserId,
                AuthorType.HUMAN.getCode(), hunterUserId,
                NotificationLinkType.BOUNTY, taskId,
                NotificationType.BOUNTY_SUBMITTED.getText(),
                "悬赏《" + excerpt(taskTitle) + "》收到新的提交，等待你审核");
    }

    @Override
    public void notifyBountyAudited(Long hunterUserId, Long publisherUserId, Long taskId,
                                    String taskTitle, boolean accepted, BigDecimal reward,
                                    String feedback) {
        String title = excerpt(taskTitle);
        String body;
        if (accepted) {
            String rewardText = reward == null ? "0" : reward.stripTrailingZeros().toPlainString();
            body = "你在悬赏《" + title + "》中的答案已被采纳，获得 " + rewardText + " 积分";
        } else {
            body = "你在悬赏《" + title + "》中的答案未被采纳";
            if (feedback != null && !feedback.isBlank()) {
                body = body + "，理由：" + excerpt(feedback);
            }
        }
        write(NotificationType.BOUNTY_AUDITED, hunterUserId,
                AuthorType.HUMAN.getCode(), publisherUserId,
                NotificationLinkType.BOUNTY, taskId,
                NotificationType.BOUNTY_AUDITED.getText(),
                body);
    }

    // ========== Internals ==========

    /**
     * The one write path. Every producer funnels through it so the three invariants
     * (no self-notification, no throw, no write without a table) are stated once.
     */
    private void write(NotificationType type, Long recipientUserId,
                       String actorType, Long actorId,
                       NotificationLinkType linkType, Long linkId,
                       String title, String body) {
        if (recipientUserId == null) {
            return;
        }
        if (!schemaCapabilities.isNotificationsTable()) {
            log.warn("notifications table is absent, dropping a {} notification for user {}. "
                    + "Apply deploy/migrations/2026-09-06-notifications.sql.", type, recipientUserId);
            return;
        }
        if (isSelfNotification(recipientUserId, actorType, actorId)) {
            log.debug("Not notifying a user about their own action: userId={}, type={}",
                    recipientUserId, type);
            return;
        }

        try {
            Notification notification = new Notification();
            notification.setRecipientUserId(recipientUserId);
            notification.setType(type.getCode());
            notification.setTitle(MemoryTextSanitizer.truncate(
                    MemoryTextSanitizer.flatten(title), TITLE_MAX_LENGTH));
            notification.setBody(MemoryTextSanitizer.truncate(
                    MemoryTextSanitizer.flatten(body), BODY_MAX_LENGTH));
            notification.setLinkType(linkType == null ? null : linkType.getCode());
            notification.setLinkId(linkId);
            notification.setActorType(actorType);
            notification.setActorId(actorId);
            notification.setIsRead(0);
            // JVM clock, like every other timestamp the application writes: the list is
            // ordered by it, so a database session timezone must not be able to shift it.
            notification.setCreatedAt(LocalDateTime.now());

            notificationMapper.insert(notification);
            log.info("Notification written: userId={}, type={}, linkType={}, linkId={}",
                    recipientUserId, type, linkType, linkId);
        } catch (Exception e) {
            // The interaction already happened and matters more than telling somebody
            // about it. Never rethrow: this runs inside the caller's transaction.
            log.warn("Failed to write notification: userId={}, type={}, error={}",
                    recipientUserId, type, e.getMessage());
        }
    }

    /**
     * A human actor who is the recipient. An AGENT actor is never "self" even when the
     * recipient owns it: "your agent replied to your post" and "your agent died" are
     * both things the owner wants to be told about.
     */
    private boolean isSelfNotification(Long recipientUserId, String actorType, Long actorId) {
        return AuthorType.HUMAN.getCode().equalsIgnoreCase(actorType)
                && Objects.equals(recipientUserId, actorId);
    }

    /**
     * Read paths refuse to pretend an un-migrated database is an empty inbox.
     *
     * Same reasoning as D-0008 for agent_memories: a silent empty page turns a
     * deployment fault into "you have no notifications", which is indistinguishable
     * from working software until somebody misses something that mattered.
     */
    private void requireTable() {
        if (!schemaCapabilities.isNotificationsTable()) {
            throw new BusinessException(ErrorCode.NOTIFICATIONS_UNAVAILABLE);
        }
    }

    private NotificationResponse toResponse(Notification row,
                                            Map<String, AuthorResolver.AuthorInfo> actors) {
        String actorName = null;
        if (row.getActorType() != null && row.getActorId() != null) {
            AuthorResolver.AuthorInfo info =
                    actors.get(authorResolver.key(row.getActorType(), row.getActorId()));
            actorName = info == null ? null : info.getAuthorName();
        }

        return NotificationResponse.builder()
                .id(row.getId())
                .type(row.getType())
                .typeText(NotificationType.textOf(row.getType()))
                .title(row.getTitle())
                .body(row.getBody())
                .linkType(row.getLinkType())
                .linkId(row.getLinkId())
                .actorType(row.getActorType())
                .actorName(actorName)
                .isRead(row.getIsRead() != null && row.getIsRead() == 1)
                .createdAt(row.getCreatedAt() == null ? null : row.getCreatedAt().format(DATE_FORMATTER))
                .build();
    }

    /**
     * One line, capped. Applied to every fragment that came from a person or a model:
     * a comment body may be several hundred characters of multi-line text, and the
     * notification list renders it inline.
     */
    private String excerpt(String text) {
        return MemoryTextSanitizer.truncate(MemoryTextSanitizer.flatten(text), EXCERPT_MAX_LENGTH);
    }

    private String shortName(String name) {
        return MemoryTextSanitizer.truncate(MemoryTextSanitizer.flatten(name), NAME_MAX_LENGTH);
    }
}
