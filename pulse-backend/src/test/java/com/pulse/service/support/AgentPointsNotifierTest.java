package com.pulse.service.support;

import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Notification;
import com.pulse.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one notification that reports a CONDITION rather than an event.
 *
 * Everything else in the notification centre fires once per thing that happened. This one
 * fires while a balance is empty, and the balance stays empty - so without the daily gate
 * it would produce a notification on every tick, for hours, burying the first one.
 */
class AgentPointsNotifierTest {

    private static final Long OWNER_ID = 7L;
    private static final Long AGENT_ID = 42L;

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentPointsNotifier notifier =
            new AgentPointsNotifier(notificationMapper, schemaCapabilities);

    @BeforeEach
    void tableExists() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(true);
    }

    @Test
    void theFirstSkipOfTheDayNotifiesTheOwner() {
        when(notificationMapper.countByTypeAndLinkSince(anyLong(), anyString(), anyString(),
                anyLong(), any(LocalDateTime.class))).thenReturn(0);

        assertThat(notifier.notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse")).isTrue();

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(saved.capture());
        Notification row = saved.getValue();
        assertThat(row.getRecipientUserId()).isEqualTo(OWNER_ID);
        assertThat(row.getType()).isEqualTo("AGENT_POINTS_INSUFFICIENT");
        assertThat(row.getLinkType()).isEqualTo("AGENT");
        assertThat(row.getLinkId()).isEqualTo(AGENT_ID);
        assertThat(row.getActorType()).isEqualTo("AGENT");
        assertThat(row.getBody()).contains("Pulse").contains("积分");
        assertThat(row.getIsRead()).isZero();
    }

    @Test
    void aSecondSkipTheSameDayIsNotRepeated() {
        when(notificationMapper.countByTypeAndLinkSince(anyLong(), anyString(), anyString(),
                anyLong(), any(LocalDateTime.class))).thenReturn(1);

        assertThat(notifier.notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse")).isFalse();

        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /**
     * Per agent, not per owner: an owner running two platform agents needs to know which
     * one went quiet.
     */
    @Test
    void theGateIsScopedToOneAgent() {
        when(notificationMapper.countByTypeAndLinkSince(anyLong(), anyString(), anyString(),
                anyLong(), any(LocalDateTime.class))).thenReturn(0);

        notifier.notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse");

        verify(notificationMapper).countByTypeAndLinkSince(eq(OWNER_ID),
                eq("AGENT_POINTS_INSUFFICIENT"), eq("AGENT"), eq(AGENT_ID),
                any(LocalDateTime.class));
    }

    @Test
    void anAbsentTableDropsTheNotificationWithoutQuerying() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(false);

        assertThat(notifier.notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse")).isFalse();

        verify(notificationMapper, never()).countByTypeAndLinkSince(anyLong(), anyString(),
                anyString(), anyLong(), any(LocalDateTime.class));
        verify(notificationMapper, never()).insert(any(Notification.class));
    }

    /**
     * This runs inside a wake-up. A notification is worth less than the wake-up, and far
     * less than the caller's ability to move on to the next agent.
     */
    @Test
    void aFailingInsertNeverThrows() {
        when(notificationMapper.countByTypeAndLinkSince(anyLong(), anyString(), anyString(),
                anyLong(), any(LocalDateTime.class))).thenReturn(0);
        when(notificationMapper.insert(any(Notification.class)))
                .thenThrow(new RuntimeException("deadlock"));

        assertThat(notifier.notifyPointsInsufficient(OWNER_ID, AGENT_ID, "Pulse")).isFalse();
    }

    @Test
    void anIncompleteTargetIsIgnored() {
        assertThat(notifier.notifyPointsInsufficient(null, AGENT_ID, "Pulse")).isFalse();
        assertThat(notifier.notifyPointsInsufficient(OWNER_ID, null, "Pulse")).isFalse();
    }
}
