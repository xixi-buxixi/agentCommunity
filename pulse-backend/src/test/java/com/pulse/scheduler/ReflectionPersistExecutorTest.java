package com.pulse.scheduler;

import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.service.AgentMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transaction boundary itself is Spring's job; what this test pins down is the
 * contract around it - the audit note reports what was really written, and a persist
 * failure propagates so the caller can charge separately instead of the row silently
 * claiming success.
 */
class ReflectionPersistExecutorTest {

    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);

    private final ReflectionPersistExecutor executor =
            new ReflectionPersistExecutor(agentMemoryService, agentActionExecutor);

    @Test
    void persistsThenChargesAndReportsTheRowCountInTheAuditNote() {
        when(agentMemoryService.applyReflection(any(Agent.class), any(ReflectionResult.class)))
                .thenReturn(3);

        int changed = executor.applyAndCharge(agent(), result(), 500L,
                AgentActionExecutor.REFLECTION_SUCCESS, "REFLECTION: new=2, updated=1, deprecated=0");

        assertThat(changed).isEqualTo(3);
        InOrder order = inOrder(agentMemoryService, agentActionExecutor);
        order.verify(agentMemoryService).applyReflection(any(Agent.class), any(ReflectionResult.class));
        order.verify(agentActionExecutor).chargeReflectionTokens(any(Agent.class), eq(500L),
                eq(AgentActionExecutor.REFLECTION_SUCCESS), contains("changedRows=3"));
    }

    @Test
    void aPersistFailurePropagatesAndNothingIsCharged() {
        when(agentMemoryService.applyReflection(any(Agent.class), any(ReflectionResult.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> executor.applyAndCharge(agent(), result(), 500L,
                AgentActionExecutor.REFLECTION_SUCCESS, "note"))
                .isInstanceOf(RuntimeException.class);

        verify(agentActionExecutor, never())
                .chargeReflectionTokens(any(Agent.class), anyLong(), anyString(), anyString());
    }

    private ReflectionResult result() {
        return ReflectionResult.builder().success(true).totalTokens(500).build();
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(42L);
        agent.setOwnerId(7L);
        return agent;
    }
}
