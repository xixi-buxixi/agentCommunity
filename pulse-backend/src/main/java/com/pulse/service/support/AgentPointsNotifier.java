package com.pulse.service.support;

import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Notification;
import com.pulse.enums.AuthorType;
import com.pulse.enums.NotificationLinkType;
import com.pulse.enums.NotificationType;
import com.pulse.mapper.NotificationMapper;
import com.pulse.util.MemoryTextSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The one producer of {@link NotificationType#AGENT_POINTS_INSUFFICIENT}.
 *
 * Separate from {@code NotificationServiceImpl} for a reason that is scheduling, not
 * design: that file is owned by another change in flight, so this notification is added
 * without touching it. It obeys the same four producer rules stated there - never throw,
 * never write without the table, never notify about your own action (not reachable here:
 * the actor is the agent, never a person), and store a rendered snapshot - and it adds a
 * fifth that the existing producers do not need.
 *
 * The fifth rule is the reason this producer is not shaped like the others. Every existing
 * notification reports an EVENT: somebody commented, somebody tipped, an agent died -
 * each happens once and is worth one row. This one reports a CONDITION: the owner's
 * balance is empty, and it will still be empty on the next tick, and the one after that.
 * Written per occurrence it would produce a notification every fifteen minutes for as
 * long as the balance stayed empty. Hence the once-per-day gate, which asks the
 * notification table itself what the owner has already been told today.
 *
 * <p>Merging this into {@code NotificationService} once that file is free would be a
 * strict improvement; the daily gate would move with it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentPointsNotifier {

    /** Column widths of notifications.title / .body, matching NotificationServiceImpl. */
    private static final int TITLE_MAX_LENGTH = 200;
    private static final int BODY_MAX_LENGTH = 500;
    private static final int NAME_MAX_LENGTH = 30;

    private final NotificationMapper notificationMapper;
    private final SchemaCapabilities schemaCapabilities;

    /**
     * Tell an owner their platform agent is paused for lack of points, at most once a day.
     *
     * @param ownerUserId the agent's owner
     * @param agentId     the paused agent; also the notification's link target, so the
     *                    owner lands on the agent rather than on a generic wallet page
     * @return true when a row was actually written
     */
    public boolean notifyPointsInsufficient(Long ownerUserId, Long agentId, String agentName) {
        if (ownerUserId == null || agentId == null) {
            return false;
        }
        if (!schemaCapabilities.isNotificationsTable()) {
            log.warn("notifications table is absent, dropping an AGENT_POINTS_INSUFFICIENT "
                    + "notification for user {}", ownerUserId);
            return false;
        }

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        try {
            int alreadySent = notificationMapper.countByTypeAndLinkSince(ownerUserId,
                    NotificationType.AGENT_POINTS_INSUFFICIENT.getCode(),
                    NotificationLinkType.AGENT.getCode(), agentId, startOfToday);
            if (alreadySent > 0) {
                log.debug("Owner {} was already told about agent {} today", ownerUserId, agentId);
                return false;
            }

            Notification notification = new Notification();
            notification.setRecipientUserId(ownerUserId);
            notification.setType(NotificationType.AGENT_POINTS_INSUFFICIENT.getCode());
            notification.setTitle(MemoryTextSanitizer.truncate(MemoryTextSanitizer.flatten(
                    NotificationType.AGENT_POINTS_INSUFFICIENT.getText()), TITLE_MAX_LENGTH));
            notification.setBody(MemoryTextSanitizer.truncate(MemoryTextSanitizer.flatten(
                    "Agent [" + shortName(agentName) + "] 使用平台模型，当前可用积分不足，"
                            + "已暂停唤醒。补充积分后会自动恢复。"), BODY_MAX_LENGTH));
            notification.setLinkType(NotificationLinkType.AGENT.getCode());
            notification.setLinkId(agentId);
            // The agent is the actor: nobody did this to it, and the owner's list should
            // name the agent before the body is read. Same choice as AGENT_DIED.
            notification.setActorType(AuthorType.AGENT.getCode());
            notification.setActorId(agentId);
            notification.setIsRead(0);
            notification.setCreatedAt(LocalDateTime.now());

            notificationMapper.insert(notification);
            log.info("Owner notified that a platform agent is out of points: userId={}, agentId={}",
                    ownerUserId, agentId);
            return true;
        } catch (Exception e) {
            // Runs inside a wake-up. A notification is worth less than the wake-up, and
            // far less than the caller's ability to continue with the next agent.
            log.warn("Failed to write an AGENT_POINTS_INSUFFICIENT notification: userId={}, error={}",
                    ownerUserId, e.getMessage());
            return false;
        }
    }

    private String shortName(String name) {
        return MemoryTextSanitizer.truncate(MemoryTextSanitizer.flatten(name), NAME_MAX_LENGTH);
    }
}
