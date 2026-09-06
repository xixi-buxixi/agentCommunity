package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Retention for the notification centre. The job deletes rows, so what it must NOT delete
 * is the part worth pinning down - the rest is loop mechanics.
 */
class NotificationCleanupSchedulerTest {

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final NotificationCleanupScheduler scheduler =
            new NotificationCleanupScheduler(notificationMapper, schemaCapabilities);

    @BeforeEach
    void configure() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "retentionDays", 90);
        ReflectionTestUtils.setField(scheduler, "batchSize", 1000);
    }

    @Test
    void oneShortBatchEndsTheRun() {
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(40);

        scheduler.purgeOldReadNotifications();

        verify(notificationMapper, times(1)).deleteReadOlderThan(any(LocalDateTime.class), eq(1000));
    }

    /**
     * A full batch means there may be more; the run keeps going until one comes back
     * short. Deleting in bounded batches is what keeps a large backlog from holding one
     * long transaction.
     */
    @Test
    void fullBatchesAreRepeatedUntilOneComesBackShort() {
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(1000, 1000, 7);

        scheduler.purgeOldReadNotifications();

        verify(notificationMapper, times(3)).deleteReadOlderThan(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void theCutoffIsTheConfiguredNumberOfDaysAgo() {
        LocalDateTime before = LocalDateTime.now().minusDays(90);
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), anyInt())).thenReturn(0);

        scheduler.purgeOldReadNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteReadOlderThan(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }

    /**
     * "Keep nothing" is never what an operator means by a retention setting, and the
     * mistake is unrecoverable - so a non-positive value is refused rather than obeyed.
     */
    @Test
    void aNonPositiveRetentionIsRefusedRatherThanDeletingEverything() {
        ReflectionTestUtils.setField(scheduler, "retentionDays", 0);

        scheduler.purgeOldReadNotifications();

        verifyNoInteractions(notificationMapper);
    }

    @Test
    void aMissingTableSkipsTheRun() {
        when(schemaCapabilities.isNotificationsTable()).thenReturn(false);

        scheduler.purgeOldReadNotifications();

        verifyNoInteractions(notificationMapper);
    }

    @Test
    void beingDisabledSkipsTheRunWithoutEvenProbingTheSchema() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.purgeOldReadNotifications();

        verifyNoInteractions(notificationMapper);
    }

    /**
     * Whatever has been deleted is committed and correct; the rest waits for tomorrow.
     * Retention is never urgent enough to take the scheduler thread down with it.
     */
    @Test
    void aFailingBatchStopsTheRunWithoutThrowing() {
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), anyInt()))
                .thenReturn(1000)
                .thenThrow(new RuntimeException("database is having a bad night"));

        assertThatCode(scheduler::purgeOldReadNotifications).doesNotThrowAnyException();

        verify(notificationMapper, times(2)).deleteReadOlderThan(any(LocalDateTime.class), anyInt());
    }

    /**
     * A batch size of 0 would make every batch "short" and the run a no-op forever; it is
     * clamped to at least one row.
     */
    @Test
    void aBatchSizeOfZeroIsClampedRatherThanDisablingTheRun() {
        ReflectionTestUtils.setField(scheduler, "batchSize", 0);
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), eq(1))).thenReturn(0);

        scheduler.purgeOldReadNotifications();

        verify(notificationMapper).deleteReadOlderThan(any(LocalDateTime.class), eq(1));
    }

    /**
     * A run that never sees a short batch stops at the ceiling instead of looping for
     * ever - the backlog is picked up by the next run.
     */
    @Test
    void anEndlessBacklogStopsAtThePerRunCeiling() {
        when(notificationMapper.deleteReadOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(1000);

        scheduler.purgeOldReadNotifications();

        verify(notificationMapper, times(200))
                .deleteReadOlderThan(any(LocalDateTime.class), eq(1000));
    }
}
