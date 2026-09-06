package com.pulse.service.impl;

import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentWakeEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Enqueueing runs inside somebody else's transaction (a comment, a tip), so the rules
 * are: never throw, never queue a self-interaction, and do nothing at all when the
 * schema has no queue.
 */
class AgentWakeEventServiceImplTest {

    private static final Long AGENT_ID = 42L;

    private final AgentWakeEventMapper agentWakeEventMapper = mock(AgentWakeEventMapper.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentWakeEventServiceImpl service =
            new AgentWakeEventServiceImpl(agentWakeEventMapper, schemaCapabilities);

    @BeforeEach
    void schemaPresent() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
    }

    @Test
    void aCommentOnAnAgentPostIsQueuedWithATraceableDedupKey() {
        service.recordCommentOnAgentPost(AGENT_ID, 88L, 500L, AuthorType.HUMAN.getCode(), 7L);

        AgentWakeEvent event = captured();
        assertThat(event.getAgentId()).isEqualTo(AGENT_ID);
        assertThat(event.getEventType()).isEqualTo("COMMENTED");
        assertThat(event.getSourceType()).isEqualTo("COMMENT");
        assertThat(event.getSourceId()).isEqualTo(500L);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getDedupKey()).isEqualTo("42:COMMENTED:COMMENT:500:HUMAN:7");
    }

    @Test
    void aReplyToAnAgentCommentIsQueuedAsReplied() {
        service.recordReplyToAgentComment(AGENT_ID, 300L, 501L, AuthorType.HUMAN.getCode(), 7L);

        assertThat(captured().getEventType()).isEqualTo("REPLIED");
    }

    @Test
    void aTipIsKeyedOnTheLedgerRow() {
        service.recordTip(AGENT_ID, 900L, AuthorType.HUMAN.getCode(), 7L);

        AgentWakeEvent event = captured();
        assertThat(event.getEventType()).isEqualTo("TIPPED");
        assertThat(event.getSourceType()).isEqualTo("LEDGER");
        assertThat(event.getDedupKey()).isEqualTo("42:TIPPED:LEDGER:900:HUMAN:7");
    }

    /**
     * The dedup key is what makes a retried transaction enqueue once; hitting it is a
     * normal outcome, not an error.
     */
    @Test
    void aDuplicateEnqueueIsNotAnError() {
        when(agentWakeEventMapper.insertIgnoringDuplicate(any(AgentWakeEvent.class))).thenReturn(0);

        assertThatCode(() -> service.recordTip(AGENT_ID, 900L, AuthorType.HUMAN.getCode(), 7L))
                .doesNotThrowAnyException();
    }

    /**
     * An agent replying under its own post must not queue itself - that is the cheapest
     * possible way to burn an owner's tokens in a loop.
     */
    @Test
    void anAgentCannotQueueItself() {
        service.recordCommentOnAgentPost(AGENT_ID, 88L, 500L, AuthorType.AGENT.getCode(), AGENT_ID);

        verify(agentWakeEventMapper, never()).insertIgnoringDuplicate(any(AgentWakeEvent.class));
    }

    @Test
    void anotherAgentInteractingIsStillQueued() {
        service.recordCommentOnAgentPost(AGENT_ID, 88L, 500L, AuthorType.AGENT.getCode(), 99L);

        assertThat(captured().getActorId()).isEqualTo(99L);
    }

    /**
     * The owner commenting on their own agent's post is a real interaction the agent
     * should answer, so "self" is about the agent identity, not about ownership.
     */
    @Test
    void aHumanWithTheSameIdAsTheAgentIsNotTreatedAsSelf() {
        service.recordCommentOnAgentPost(AGENT_ID, 88L, 500L, AuthorType.HUMAN.getCode(), AGENT_ID);

        verify(agentWakeEventMapper).insertIgnoringDuplicate(any(AgentWakeEvent.class));
    }

    @Test
    void nothingIsQueuedWithoutTheSchema() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        service.recordTip(AGENT_ID, 900L, AuthorType.HUMAN.getCode(), 7L);

        verifyNoInteractions(agentWakeEventMapper);
    }

    /**
     * A queue failure may never surface in the comment or the tip that triggered it.
     */
    @Test
    void aQueueFailureIsSwallowed() {
        when(agentWakeEventMapper.insertIgnoringDuplicate(any(AgentWakeEvent.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.recordCommentOnAgentPost(AGENT_ID, 88L, 500L,
                AuthorType.HUMAN.getCode(), 7L)).doesNotThrowAnyException();
    }

    @Test
    void anIncompleteEventIsIgnored() {
        service.recordTip(null, 900L, AuthorType.HUMAN.getCode(), 7L);
        service.recordTip(AGENT_ID, null, AuthorType.HUMAN.getCode(), 7L);

        verify(agentWakeEventMapper, never()).insertIgnoringDuplicate(any(AgentWakeEvent.class));
    }

    private AgentWakeEvent captured() {
        ArgumentCaptor<AgentWakeEvent> captor = ArgumentCaptor.forClass(AgentWakeEvent.class);
        verify(agentWakeEventMapper).insertIgnoringDuplicate(captor.capture());
        return captor.getValue();
    }
}
