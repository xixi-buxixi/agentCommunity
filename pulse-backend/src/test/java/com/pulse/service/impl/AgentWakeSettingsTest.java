package com.pulse.service.impl;

import com.pulse.config.AgentTemplateCatalog;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.service.support.LlmCredentialResolver;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.dto.request.AgentCreateRequest;
import com.pulse.dto.request.AgentUpdateRequest;
import com.pulse.dto.response.AgentDetailResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.User;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.support.WakeScheduleCalculator;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wake-rhythm columns only exist after the phase-3 migration, so every path that
 * touches them has to behave on a database that has not run it. Getting this wrong meant
 * the agent detail page, the settings update and even tipping would fail with "unknown
 * column" on an un-migrated deployment - the exact opposite of a safe rollout.
 */
class AgentWakeSettingsTest {

    private static final Long OWNER_ID = 7L;
    private static final Long AGENT_ID = 42L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final WakeScheduleCalculator wakeScheduleCalculator =
            new WakeScheduleCalculator(new Random(11));

    // A real resolver over the same mocked capability probe: with provider_mode reported
    // absent, every agent here is BYOK and nothing in the rhythm paths changes.
    private final PlatformLlmProperties platformLlmProperties = new PlatformLlmProperties();
    private final AgentTemplateCatalog agentTemplateCatalog = new AgentTemplateCatalog();
    private final LlmCredentialResolver credentialResolver = new LlmCredentialResolver(
            aesUtil, platformLlmProperties, schemaCapabilities, agentMapper);

    private final AgentServiceImpl service = new AgentServiceImpl(
            agentMapper, agentLogMapper, userMapper, postMapper, aesUtil,
            schemaCapabilities, wakeScheduleCalculator, credentialResolver,
            platformLlmProperties, agentTemplateCatalog);

    @BeforeEach
    void configure() {
        agentTemplateCatalog.load();
        ReflectionTestUtils.setField(service, "targetDailyRhythmWakes", 3);
        ReflectionTestUtils.setField(service, "defaultDailyWakeBudget", 4);
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(true);
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent());
        when(userMapper.selectById(OWNER_ID)).thenReturn(user());
        when(aesUtil.decrypt(anyString())).thenReturn("sk-plain");
        when(aesUtil.maskApiKey(anyString())).thenReturn("sk-****");
        when(aesUtil.encrypt(anyString())).thenReturn("encrypted");
    }

    // ========== Migrated database ==========

    @Test
    void aNewAgentGetsItsOwnRhythmThroughAnExplicitStatement() {
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Agent.class).setId(AGENT_ID);
            return 1;
        });

        service.createAgent(OWNER_ID, createRequest());

        // the rhythm cannot ride along in the generated INSERT, so it is written separately
        verify(agentMapper).updateWakeSettings(eq(AGENT_ID), any(Integer.class), any(Integer.class),
                eq(4), any(LocalDateTime.class));
    }

    /**
     * The rhythm chosen in the creation wizard lands in the same transaction as the
     * agent. It used to be a second request the wizard sent afterwards, so a dropped
     * connection between the two left the owner with an agent quietly running on the
     * random hours the seeding picked, which they never chose and were never shown.
     */
    @Test
    void aRhythmSubmittedWithTheCreationIsWrittenAndReportedBack() {
        givenInsertAssignsId();
        // First read: the seeded row the rhythm write is about to overwrite. Second read:
        // the row the response is built from.
        when(agentMapper.findWakeSettings(AGENT_ID))
                .thenReturn(settings(9, 18, 4, null))
                .thenReturn(settings(22, 6, 8, null));
        AgentCreateRequest request = createRequest();
        request.setWakeHoursStart(22);
        request.setWakeHoursEnd(6);
        request.setDailyWakeBudget(8);

        AgentDetailResponse response = service.createAgent(OWNER_ID, request);

        verify(agentMapper).updateWakeSettings(eq(AGENT_ID), eq(22), eq(6), eq(8),
                any(LocalDateTime.class));
        assertThat(response.getWakeHoursStart()).isEqualTo(22);
        assertThat(response.getWakeHoursEnd()).isEqualTo(6);
        assertThat(response.getDailyWakeBudget()).isEqualTo(8);
    }

    /**
     * Only the budget was chosen, so the hours stay the ones the seeding picked rather
     * than falling back to a shared default.
     */
    @Test
    void aPartialRhythmKeepsTheSeededHours() {
        givenInsertAssignsId();
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(settings(9, 18, 4, null));
        AgentCreateRequest request = createRequest();
        request.setDailyWakeBudget(12);

        service.createAgent(OWNER_ID, request);

        verify(agentMapper).updateWakeSettings(eq(AGENT_ID), eq(9), eq(18), eq(12), any());
    }

    /**
     * The settings endpoint answers 20009 when the columns are missing, because that is
     * all the owner asked for. At creation they asked for an agent: refusing to create it
     * because this deployment cannot store active hours would be the wrong trade, so the
     * three fields are dropped and the rhythm is reported as absent.
     */
    @Test
    void withoutTheColumnsARhythmSubmittedWithTheCreationIsIgnoredRatherThanRefused() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);
        givenInsertAssignsId();
        AgentCreateRequest request = createRequest();
        request.setWakeHoursStart(22);
        request.setWakeHoursEnd(6);
        request.setDailyWakeBudget(8);

        AgentDetailResponse response = service.createAgent(OWNER_ID, request);

        assertThat(response.getId()).isEqualTo(AGENT_ID);
        assertThat(response.getWakeHoursStart()).isNull();
        assertThat(response.getWakeHoursEnd()).isNull();
        assertThat(response.getDailyWakeBudget()).isNull();
        verify(agentMapper, never()).updateWakeSettings(anyLong(), any(), any(), any(), any());
    }

    /**
     * ...and a rhythm write that fails for any other reason must not fail the creation
     * either.
     */
    @Test
    void aFailingRhythmWriteDoesNotFailACreationThatCarriedOne() {
        givenInsertAssignsId();
        when(agentMapper.updateWakeSettings(anyLong(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("unknown column"));
        AgentCreateRequest request = createRequest();
        request.setWakeHoursStart(22);

        assertThat(service.createAgent(OWNER_ID, request).getId()).isEqualTo(AGENT_ID);
    }

    @Test
    void changingTheHoursWritesBothBoundsAndReplansTheNextWake() {
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(settings(22, 6, 4, null));
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setWakeHoursStart(9);

        service.updateAgent(OWNER_ID, AGENT_ID, request);

        // the untouched bound is carried over rather than left NULL, so the scheduler never
        // computes a window from one new and one missing bound
        verify(agentMapper).updateWakeSettings(eq(AGENT_ID), eq(9), eq(6), eq(4),
                any(LocalDateTime.class));
    }

    @Test
    void changingOnlyTheBudgetKeepsTheExistingScheduleAndHours() {
        LocalDateTime scheduled = LocalDateTime.now().plusHours(2);
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(settings(9, 18, 4, scheduled));
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setDailyWakeBudget(8);

        service.updateAgent(OWNER_ID, AGENT_ID, request);

        verify(agentMapper).updateWakeSettings(AGENT_ID, 9, 18, 8, scheduled);
    }

    @Test
    void anUpdateWithoutWakeFieldsTouchesNoRhythm() {
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setModelName("gpt-4o-mini");

        service.updateAgent(OWNER_ID, AGENT_ID, request);

        verify(agentMapper, never()).updateWakeSettings(anyLong(), any(), any(), any(), any());
    }

    /**
     * The counter is reset lazily by the next claim, so a row left from yesterday still
     * holds yesterday's number. Reporting that as today's usage would misstate the owner's
     * remaining budget.
     */
    @Test
    void aStaleWakeCounterIsReportedAsZero() {
        AgentWakeSettings stale = settings(9, 18, 4, null);
        stale.setWakeCountToday(4);
        stale.setWakeCountDate(LocalDate.now().minusDays(1));
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(stale);

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getWakeCountToday()).isZero();
    }

    @Test
    void todaysWakeCounterIsReportedAsIs() {
        AgentWakeSettings today = settings(9, 18, 4, null);
        today.setWakeCountToday(2);
        today.setWakeCountDate(LocalDate.now());
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(today);

        assertThat(service.getAgentDetail(OWNER_ID, AGENT_ID).getWakeCountToday()).isEqualTo(2);
    }

    /**
     * next_wake_at is a clock time the owner reads as "my agent wakes at 21:40". Appending a
     * literal Z would claim server-local time is UTC and show it eight hours out.
     */
    @Test
    void theNextWakeTimeIsReportedAsLocalTimeWithoutAFakeUtcSuffix() {
        LocalDateTime scheduled = LocalDateTime.of(2026, 7, 28, 21, 40, 5);
        when(agentMapper.findWakeSettings(AGENT_ID)).thenReturn(settings(9, 23, 4, scheduled));

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getNextWakeAt()).isEqualTo("2026-07-28T21:40:05");
        assertThat(response.getNextWakeAt()).doesNotEndWith("Z");
    }

    // ========== Un-migrated database ==========

    @Test
    void withoutTheColumnsTheDetailPageStillWorksAndReportsNoRhythm() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getId()).isEqualTo(AGENT_ID);
        assertThat(response.getWakeHoursStart()).isNull();
        assertThat(response.getNextWakeAt()).isNull();
        assertThat(response.getWakeCountToday()).isNull();
        verify(agentMapper, never()).findWakeSettings(anyLong());
    }

    @Test
    void withoutTheColumnsCreatingAnAgentSkipsTheRhythm() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Agent.class).setId(AGENT_ID);
            return 1;
        });

        service.createAgent(OWNER_ID, createRequest());

        verify(agentMapper).insert(any(Agent.class));
        verify(agentMapper, never()).updateWakeSettings(anyLong(), any(), any(), any(), any());
    }

    /**
     * An explicit "not enabled here" beats a 500 from an unknown column.
     */
    @Test
    void withoutTheColumnsASettingsUpdateIsRefusedWithAClearError() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setDailyWakeBudget(6);

        assertThatThrownBy(() -> service.updateAgent(OWNER_ID, AGENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AGENT_WAKE_SETTINGS_UNAVAILABLE.getCode());
    }

    @Test
    void withoutTheColumnsAnOrdinaryUpdateStillSucceeds() {
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setSystemPrompt("你是一个更冷静的 Pulse");

        service.updateAgent(OWNER_ID, AGENT_ID, request);

        verify(agentMapper).updateById(any(Agent.class));
    }

    /**
     * A failing rhythm write must not fail the creation the user asked for.
     */
    @Test
    void aRhythmWriteFailureDoesNotFailAgentCreation() {
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Agent.class).setId(AGENT_ID);
            return 1;
        });
        when(agentMapper.updateWakeSettings(anyLong(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("unknown column"));

        assertThat(service.createAgent(OWNER_ID, createRequest()).getId()).isEqualTo(AGENT_ID);
    }

    @Test
    void aFailingRhythmReadDegradesToNoRhythmRatherThanAnError() {
        when(agentMapper.findWakeSettings(AGENT_ID)).thenThrow(new RuntimeException("unknown column"));

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getId()).isEqualTo(AGENT_ID);
        assertThat(response.getWakeHoursStart()).isNull();
    }

    // ========== Fixtures ==========

    private void givenInsertAssignsId() {
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Agent.class).setId(AGENT_ID);
            return 1;
        });
    }

    private AgentCreateRequest createRequest() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("Pulse");
        request.setBaseUrl("https://api.openai.com/v1");
        request.setApiKey("sk-1234567890");
        request.setModelName("gpt-4o-mini");
        request.setSystemPrompt("你是 Pulse，一个理性的社区居民");
        request.setTokenThreshold(500000L);
        request.setIsUnlimited(false);
        return request;
    }

    private AgentWakeSettings settings(Integer start, Integer end, Integer budget, LocalDateTime next) {
        return AgentWakeSettings.builder()
                .agentId(AGENT_ID)
                .wakeHoursStart(start)
                .wakeHoursEnd(end)
                .dailyWakeBudget(budget)
                .nextWakeAt(next)
                .build();
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setOwnerId(OWNER_ID);
        agent.setName("Pulse");
        agent.setApiKey("encrypted");
        agent.setBaseUrl("https://api.openai.com/v1");
        agent.setModelName("gpt-4o-mini");
        agent.setStatus(1);
        agent.setUsedTokens(0L);
        agent.setTokenThreshold(500000L);
        agent.setIsUnlimited(false);
        return agent;
    }

    private User user() {
        User user = new User();
        user.setId(OWNER_ID);
        user.setUsername("ethan");
        return user;
    }
}
