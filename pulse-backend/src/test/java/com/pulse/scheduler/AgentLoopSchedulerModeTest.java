package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.config.WakeMode;
import com.pulse.entity.Agent;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rollback guarantee: legacy stays the default, and anything unexpected about the
 * configuration or the schema resolves to legacy rather than to silence.
 */
class AgentLoopSchedulerModeTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);
    private final AgentWakeProcessor agentWakeProcessor = mock(AgentWakeProcessor.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentLoopScheduler scheduler = new AgentLoopScheduler(
            agentMapper, agentActionExecutor, agentWakeProcessor, schemaCapabilities);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(scheduler, "schedulerEnabled", true);
        ReflectionTestUtils.setField(scheduler, "batchSize", 10);
        ReflectionTestUtils.setField(scheduler, "mode", "legacy");
        when(schemaCapabilities.isLastDispatchedAtColumn()).thenReturn(true);
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
        when(agentMapper.findRandomActiveAgents(anyInt())).thenReturn(List.of(agent(1L)));
    }

    @Test
    void legacyModeRunsTheGlobalBatchExactlyAsBefore() {
        scheduler.executeAgentLoop();

        verify(agentMapper).findRandomActiveAgents(10);
        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.LEGACY_BATCH), eq(List.of()));
    }

    @Test
    void queueModeKeepsTheLegacyBatchIdle() {
        ReflectionTestUtils.setField(scheduler, "mode", "queue");

        scheduler.executeAgentLoop();

        verifyNoInteractions(agentWakeProcessor);
        verify(agentMapper, org.mockito.Mockito.never()).findRandomActiveAgents(anyInt());
    }

    /**
     * Queue mode requested on a database without the migration: the legacy batch has to
     * take over, or the community goes quiet with only a warning in the log.
     */
    @Test
    void queueModeWithoutTheSchemaRunsTheLegacyBatch() {
        ReflectionTestUtils.setField(scheduler, "mode", "queue");
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        scheduler.executeAgentLoop();

        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.LEGACY_BATCH), eq(List.of()));
    }

    @Test
    void aTypoInTheModeRunsTheLegacyBatch() {
        ReflectionTestUtils.setField(scheduler, "mode", "kueue");

        scheduler.executeAgentLoop();

        verify(agentWakeProcessor).wake(any(Agent.class), eq(WakeReason.LEGACY_BATCH), eq(List.of()));
    }

    @Test
    void aFailingAgentIsLoggedAndTheBatchContinues() {
        when(agentMapper.findRandomActiveAgents(anyInt())).thenReturn(List.of(agent(1L), agent(2L)));
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(agentWakeProcessor).wake(any(Agent.class), any(WakeReason.class), any());

        scheduler.executeAgentLoop();

        verify(agentActionExecutor, org.mockito.Mockito.times(2))
                .logAgentError(any(Agent.class), any(), eq(0L));
    }

    @Test
    void theLegacyFallbackSelectionIsUsedWithoutTheDispatchColumn() {
        when(schemaCapabilities.isLastDispatchedAtColumn()).thenReturn(false);
        when(agentMapper.findRandomActiveAgentsLegacy(anyInt())).thenReturn(List.of(agent(1L)));

        scheduler.executeAgentLoop();

        verify(agentMapper).findRandomActiveAgentsLegacy(10);
    }

    // ========== Mode resolution itself ==========

    @Test
    void modeResolutionFavoursLegacyWheneverInDoubt() {
        assertThat(WakeMode.resolve("queue", true)).isEqualTo(WakeMode.QUEUE);
        assertThat(WakeMode.resolve("QUEUE", true)).isEqualTo(WakeMode.QUEUE);
        assertThat(WakeMode.resolve(" queue ", true)).isEqualTo(WakeMode.QUEUE);
        assertThat(WakeMode.resolve("queue", false)).isEqualTo(WakeMode.LEGACY);
        assertThat(WakeMode.resolve("legacy", true)).isEqualTo(WakeMode.LEGACY);
        assertThat(WakeMode.resolve(null, true)).isEqualTo(WakeMode.LEGACY);
        assertThat(WakeMode.resolve("", true)).isEqualTo(WakeMode.LEGACY);
        assertThat(WakeMode.resolve("nonsense", true)).isEqualTo(WakeMode.LEGACY);
    }

    private Agent agent(Long id) {
        Agent agent = new Agent();
        agent.setId(id);
        agent.setOwnerId(7L);
        agent.setName("Agent" + id);
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        agent.setIsUnlimited(false);
        return agent;
    }
}
