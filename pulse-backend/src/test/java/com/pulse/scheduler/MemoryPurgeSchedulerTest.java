package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.mapper.AgentMemoryMapper;
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
 * Physical retention for memory cards.
 *
 * Which cards it may touch is the whole safety argument, and it lives in the SQL rather
 * than in this class - com.pulse.mapper.MemoryPurgeSqlTest is where "ACTIVE and DISABLED
 * are never deleted" is actually asserted. What is tested here is everything around that
 * statement: the batching, the cutoff, and the three ways the run refuses to start.
 */
class MemoryPurgeSchedulerTest {

    private final AgentMemoryMapper agentMemoryMapper = mock(AgentMemoryMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final MemoryPurgeScheduler scheduler =
            new MemoryPurgeScheduler(agentMemoryMapper, schemaCapabilities);

    @BeforeEach
    void configure() {
        when(schemaCapabilities.isAgentMemoriesTable()).thenReturn(true);
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "deprecatedPurgeDays", 30);
        ReflectionTestUtils.setField(scheduler, "batchSize", 1000);
    }

    @Test
    void oneShortBatchEndsTheRun() {
        when(agentMemoryMapper.deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(12);

        scheduler.purgeDeprecatedMemories();

        verify(agentMemoryMapper, times(1))
                .deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000));
    }

    @Test
    void fullBatchesAreRepeatedUntilOneComesBackShort() {
        when(agentMemoryMapper.deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(1000, 3);

        scheduler.purgeDeprecatedMemories();

        verify(agentMemoryMapper, times(2))
                .deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000));
    }

    /**
     * The age that matters is when the card was RETIRED (updated_at), not when it was
     * formed - that is when the clock on keeping it should start.
     */
    @Test
    void theCutoffIsTheConfiguredNumberOfDaysAgo() {
        LocalDateTime before = LocalDateTime.now().minusDays(30);
        when(agentMemoryMapper.deleteDeprecatedOlderThan(any(LocalDateTime.class), anyInt()))
                .thenReturn(0);

        scheduler.purgeDeprecatedMemories();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(agentMemoryMapper).deleteDeprecatedOlderThan(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }

    @Test
    void aNonPositiveRetentionIsRefusedRatherThanDeletingEveryRetiredCard() {
        ReflectionTestUtils.setField(scheduler, "deprecatedPurgeDays", 0);

        scheduler.purgeDeprecatedMemories();

        verifyNoInteractions(agentMemoryMapper);
    }

    @Test
    void aMissingTableSkipsTheRun() {
        when(schemaCapabilities.isAgentMemoriesTable()).thenReturn(false);

        scheduler.purgeDeprecatedMemories();

        verifyNoInteractions(agentMemoryMapper);
    }

    @Test
    void beingDisabledSkipsTheRun() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.purgeDeprecatedMemories();

        verifyNoInteractions(agentMemoryMapper);
    }

    @Test
    void aFailingBatchStopsTheRunWithoutThrowing() {
        when(agentMemoryMapper.deleteDeprecatedOlderThan(any(LocalDateTime.class), anyInt()))
                .thenReturn(1000)
                .thenThrow(new RuntimeException("agent_memories is gone"));

        assertThatCode(scheduler::purgeDeprecatedMemories).doesNotThrowAnyException();

        verify(agentMemoryMapper, times(2))
                .deleteDeprecatedOlderThan(any(LocalDateTime.class), anyInt());
    }

    @Test
    void anEndlessBacklogStopsAtThePerRunCeiling() {
        when(agentMemoryMapper.deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000)))
                .thenReturn(1000);

        scheduler.purgeDeprecatedMemories();

        verify(agentMemoryMapper, times(200))
                .deleteDeprecatedOlderThan(any(LocalDateTime.class), eq(1000));
    }
}
