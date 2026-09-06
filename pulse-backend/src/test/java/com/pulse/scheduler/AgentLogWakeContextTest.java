package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.WakeLogContext;
import com.pulse.dto.response.AgentLogResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.entity.Post;
import com.pulse.enums.ActionType;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.DislikeMapper;
import com.pulse.mapper.LikeMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.service.AgentWakeEventService;
import com.pulse.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recording WHY an agent was awake.
 *
 * Two things have to hold at once: the reason reaches the audit row, and a database
 * without the 2026-09-06 migration keeps writing rows exactly as before. The second is
 * the reason the columns are outside the generated INSERT at all, so it is tested as
 * carefully as the first.
 */
class AgentLogWakeContextTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final LikeMapper likeMapper = mock(LikeMapper.class);
    private final DislikeMapper dislikeMapper = mock(DislikeMapper.class);
    private final AgentBountyExecutor agentBountyExecutor = mock(AgentBountyExecutor.class);
    private final AgentWakeEventService agentWakeEventService = mock(AgentWakeEventService.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);

    private final AgentActionExecutor executor = new AgentActionExecutor(
            agentMapper, postMapper, commentMapper, agentLogMapper,
            likeMapper, dislikeMapper, agentBountyExecutor, agentWakeEventService,
            notificationService, schemaCapabilities);

    // ========== write path: capability on ==========

    @Test
    void withTheColumnsPresentTheReasonIsWrittenThroughTheExplicitInsert() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(true);

        executor.applyDecisions(agent(), List.of(ignoreDecision()), 300, Set.of(),
                WakeLogContext.of(WakeReason.EVENT, List.of(event("COMMENTED"), event("TIPPED"))));

        AgentLog written = capturedExplicitInsert();
        assertThat(written.getWakeReason()).isEqualTo("EVENT");
        assertThat(written.getWakeEventTypes()).isEqualTo("COMMENTED,TIPPED");
        // the generated statement must not have been used as well
        verify(agentLogMapper, never()).insert(any(AgentLog.class));
    }

    /**
     * The explicit statement names created_at, which MyBatis Plus's insert fill does not
     * reach. Without this the column would be written as NULL on every wake-up.
     */
    @Test
    void theExplicitInsertCarriesItsOwnTimestamp() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(true);

        executor.applyDecisions(agent(), List.of(ignoreDecision()), 300, Set.of(),
                WakeLogContext.of(WakeReason.RHYTHM, List.of()));

        assertThat(capturedExplicitInsert().getCreatedAt()).isNotNull();
    }

    @Test
    void aRhythmWakeRecordsTheReasonWithoutEventTypes() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(true);

        executor.applyDecisions(agent(), List.of(ignoreDecision()), 300, Set.of(),
                WakeLogContext.of(WakeReason.RHYTHM, List.of()));

        AgentLog written = capturedExplicitInsert();
        assertThat(written.getWakeReason()).isEqualTo("RHYTHM");
        assertThat(written.getWakeEventTypes()).isNull();
    }

    /**
     * Reflection is a nightly job, not a wake-up. Its audit row goes through the explicit
     * statement too (the columns exist) but records no reason - inventing one would put a
     * wake reason on a row no wake-up produced.
     */
    @Test
    void aReflectionRowRecordsNoReason() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(true);

        executor.chargeReflectionTokens(agent(), 100,
                AgentActionExecutor.REFLECTION_SUCCESS, "distilled 2 traits");

        AgentLog written = capturedExplicitInsert();
        assertThat(written.getWakeReason()).isNull();
        assertThat(written.getWakeEventTypes()).isNull();
    }

    @Test
    void anErrorRowCarriesTheReasonOfTheWakeItHappenedIn() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(true);

        executor.chargeTokensOnly(agent(), 200, "LLM_CALL_FAILED: timeout",
                WakeLogContext.of(WakeReason.EVENT, List.of(event("REPLIED"))));

        AgentLog written = capturedExplicitInsert();
        assertThat(written.getWakeReason()).isEqualTo("EVENT");
        assertThat(written.getWakeEventTypes()).isEqualTo("REPLIED");
        assertThat(written.getActionResult()).startsWith("ERROR:");
    }

    // ========== write path: capability off ==========

    @Test
    void withoutTheColumnsTheRowIsWrittenTheOldWay() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(false);

        executor.applyDecisions(agent(), List.of(ignoreDecision()), 300, Set.of(),
                WakeLogContext.of(WakeReason.EVENT, List.of(event("COMMENTED"))));

        verify(agentLogMapper, never()).insertWithWakeContext(any(AgentLog.class));
        ArgumentCaptor<AgentLog> captor = ArgumentCaptor.forClass(AgentLog.class);
        verify(agentLogMapper).insert(captor.capture());
        // nothing is stamped onto the entity either: the fields would be silently dropped,
        // and a half-populated entity in a log would be misleading
        assertThat(captor.getValue().getWakeReason()).isNull();
        assertThat(captor.getValue().getWakeEventTypes()).isNull();
    }

    @Test
    void withoutTheColumnsAnErrorRowIsStillWritten() {
        when(schemaCapabilities.isAgentLogWakeColumns()).thenReturn(false);

        executor.logAgentError(agent(), "boom", 0,
                WakeLogContext.of(WakeReason.RHYTHM, List.of()));

        verify(agentLogMapper).insert(any(AgentLog.class));
        verify(agentLogMapper, never()).insertWithWakeContext(any(AgentLog.class));
    }

    // ========== event type rendering ==========

    @Test
    void eventTypesAreDeduplicatedAndSortedAlphabetically() {
        WakeLogContext context = WakeLogContext.of(WakeReason.EVENT, List.of(
                event("TIPPED"), event("COMMENTED"), event("TIPPED"), event("REPLIED")));

        assertThat(context.getEventTypes()).isEqualTo("COMMENTED,REPLIED,TIPPED");
    }

    @Test
    void anUnknownEventTypeKeepsItsRawCode() {
        WakeLogContext context = WakeLogContext.of(WakeReason.EVENT,
                List.of(event("mentioned"), event("COMMENTED")));

        assertThat(context.getEventTypes()).isEqualTo("COMMENTED,MENTIONED");
    }

    @Test
    void aNonEventWakeHasNoEventTypes() {
        assertThat(WakeLogContext.of(WakeReason.RHYTHM, List.of()).getEventTypes()).isNull();
        assertThat(WakeLogContext.of(WakeReason.LEGACY_BATCH, null).getEventTypes()).isNull();
    }

    @Test
    void aNullReasonYieldsNoContextAtAll() {
        assertThat(WakeLogContext.of(null, List.of(event("REPLIED")))).isNull();
    }

    /** The stored list must never grow past the column, or the insert fails outright. */
    @Test
    void theRenderedListStaysInsideTheColumn() {
        List<AgentWakeEvent> many = List.of(
                event("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), event("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                event("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"));

        assertThat(WakeLogContext.of(WakeReason.EVENT, many).getEventTypes()).hasSizeLessThanOrEqualTo(64);
    }

    /**
     * The cut lands on a comma, never inside an entry.
     *
     * A blind substring at 64 characters would store "AAA...,BBB...,CC" - a third entry
     * that never occurred, and a column that can no longer be split on commas. Dropping
     * the entry that does not fit leaves the list short, which is honest.
     */
    @Test
    void anOverlongListIsCutOnAnEntryBoundary() {
        List<AgentWakeEvent> many = List.of(
                event("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), event("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                event("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"));

        String rendered = WakeLogContext.of(WakeReason.EVENT, many).getEventTypes();

        assertThat(rendered).isEqualTo(
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA,BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB");
        // Every surviving entry is a whole entry
        assertThat(rendered.split(",")).allSatisfy(entry -> assertThat(entry).hasSize(30));
        assertThat(rendered).doesNotEndWith(",");
    }

    /**
     * An entry is either stored whole or not at all, never squeezed in half.
     */
    @Test
    void aShortListThatOverflowsOnItsLastEntryKeepsTheEarlierOnesWhole() {
        List<AgentWakeEvent> many = List.of(
                event("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), event("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                event("CC"));

        // "A*30,B*30" is 61 chars; ",CC" would make 64 exactly, so it fits
        assertThat(WakeLogContext.of(WakeReason.EVENT, many).getEventTypes())
                .hasSize(64)
                .endsWith(",CC");
    }

    /**
     * An entry that does not fit is skipped, not treated as the end of the list.
     *
     * Entries arrive alphabetically, not by length, so stopping at the first oversized
     * one made the surviving list depend on where the long code happened to sort: here
     * "DD" fits in the three characters left over, and used to be dropped because "CCC..."
     * sorts in front of it.
     */
    @Test
    void anEntryThatDoesNotFitDoesNotDiscardTheShorterOnesBehindIt() {
        List<AgentWakeEvent> many = List.of(
                event("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), event("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
                event("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"), event("DD"));

        // "A*30,B*30" is 61 chars, so C*30 cannot fit but ",DD" makes exactly 64
        assertThat(WakeLogContext.of(WakeReason.EVENT, many).getEventTypes())
                .hasSize(64)
                .isEqualTo("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA,BBBBBBBBBBBBBBBBBBBBBBBBBBBBBB,DD");
    }

    // ========== response rendering ==========

    @Test
    void theResponseRendersTheReasonAndTheInteractionsInChinese() {
        AgentLogResponse response = new AgentLogResponse()
                .applyWakeContext("EVENT", "COMMENTED,TIPPED");

        assertThat(response.getWakeReason()).isEqualTo("EVENT");
        assertThat(response.getWakeEventTypes()).containsExactly("COMMENTED", "TIPPED");
        assertThat(response.getWakeReasonText()).isEqualTo("因互动醒来（被评论、被打赏）");
    }

    @Test
    void everyReasonHasItsOwnText() {
        assertThat(new AgentLogResponse().applyWakeContext("RHYTHM", null).getWakeReasonText())
                .isEqualTo("按作息醒来");
        assertThat(new AgentLogResponse().applyWakeContext("EVENT", null).getWakeReasonText())
                .isEqualTo("因互动醒来");
        assertThat(new AgentLogResponse().applyWakeContext("LEGACY_BATCH", null).getWakeReasonText())
                .isEqualTo("定时批次");
        assertThat(new AgentLogResponse().applyWakeContext("REPLIED", "REPLIED").getWakeReasonText())
                .isEqualTo("REPLIED（被回复）");
    }

    /**
     * A row written before the migration: all three fields stay null rather than becoming
     * an empty array and a placeholder text the UI would have to special-case.
     */
    @Test
    void aRowWithoutAReasonRendersNothing() {
        AgentLogResponse response = new AgentLogResponse().applyWakeContext(null, "COMMENTED");

        assertThat(response.getWakeReason()).isNull();
        assertThat(response.getWakeEventTypes()).isNull();
        assertThat(response.getWakeReasonText()).isNull();
    }

    @Test
    void anUnknownEventTypeIsShownByItsEnumName() {
        AgentLogResponse response = new AgentLogResponse().applyWakeContext("EVENT", "MENTIONED");

        assertThat(response.getWakeReasonText()).isEqualTo("因互动醒来（MENTIONED）");
    }

    @Test
    void anEmptyEventTypeColumnBecomesNullNotAnEmptyList() {
        assertThat(new AgentLogResponse().applyWakeContext("RHYTHM", "").getWakeEventTypes()).isNull();
        assertThat(new AgentLogResponse().applyWakeContext("RHYTHM", " , ").getWakeEventTypes()).isNull();
    }

    // ========== helpers ==========

    private AgentLog capturedExplicitInsert() {
        ArgumentCaptor<AgentLog> captor = ArgumentCaptor.forClass(AgentLog.class);
        verify(agentLogMapper).insertWithWakeContext(captor.capture());
        return captor.getValue();
    }

    private AgentActionDecision ignoreDecision() {
        AgentActionDecision decision = new AgentActionDecision();
        decision.setAction(ActionType.IGNORE);
        return decision;
    }

    private AgentWakeEvent event(String type) {
        AgentWakeEvent event = new AgentWakeEvent();
        event.setEventType(type);
        return event;
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(42L);
        agent.setOwnerId(7L);
        agent.setName("Pulse");
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(100000L);
        agent.setIsUnlimited(false);
        when(agentMapper.selectById(42L)).thenReturn(agent);
        when(postMapper.selectById(any())).thenReturn(new Post());
        return agent;
    }
}
