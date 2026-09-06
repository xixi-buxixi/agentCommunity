package com.pulse.scheduler;

import com.pulse.client.LLMClient;
import com.pulse.config.HotNewsProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentContext;
import com.pulse.dto.LLMResponse;
import com.pulse.dto.WakeLogContext;
import com.pulse.entity.Agent;
import com.pulse.enums.ActionType;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.PostViewMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.AgentMemoryService;
import com.pulse.service.HotNewsService;
import com.pulse.service.support.AuthorResolver;
import com.pulse.service.support.PlatformUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The platform gate inside a wake-up: who gets turned away, what the activity log says
 * about it, and who pays for the calls that do happen.
 *
 * The gate sits next to the token pre-check because both answer "may this agent spend
 * anything at all", and both are shared by the legacy batch and the queue. What matters
 * most in these tests is what a skip does NOT do: no model call, no token charge, no
 * points movement, and no change to the agent's DEAD condition. Being out of points is a
 * pause, not a death.
 */
class AgentWakePlatformGateTest {

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final CommentMapper commentMapper = mock(CommentMapper.class);
    private final PostViewMapper postViewMapper = mock(PostViewMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final AgentActionExecutor agentActionExecutor = mock(AgentActionExecutor.class);
    private final AgentMemoryService agentMemoryService = mock(AgentMemoryService.class);
    private final AuthorResolver authorResolver = new AuthorResolver(userMapper, agentMapper);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final HotNewsService hotNewsService = mock(HotNewsService.class);
    private final HotNewsProperties hotNewsProperties = new HotNewsProperties();
    private final PlatformUsageService platformUsageService = mock(PlatformUsageService.class);

    private final AgentWakeProcessor processor = new AgentWakeProcessor(
            agentMapper, postMapper, commentMapper, postViewMapper, llmClient,
            agentActionExecutor, agentMemoryService, authorResolver, schemaCapabilities,
            hotNewsService, hotNewsProperties, platformUsageService);

    @BeforeEach
    void configure() {
        ReflectionTestUtils.setField(processor, "minTokenCharge", 200L);
        when(postMapper.findLatestPostsForAgent(anyInt(), anyLong())).thenReturn(List.of());
        when(agentMemoryService.selectForInjection(anyLong())).thenReturn(List.of());
        when(platformUsageService.charge(any(), anyLong(), anyString())).thenReturn(BigDecimal.ZERO);
    }

    // ========== The four skip reasons ==========

    @Test
    void aPlatformAgentWithoutAnAvailablePlatformIsSkipped() {
        assertSkipped(PlatformUsageService.SkipReason.PLATFORM_UNAVAILABLE);
    }

    @Test
    void aPlatformAgentWhoseOwnerHasNoPointsIsSkipped() {
        assertSkipped(PlatformUsageService.SkipReason.OWNER_POINTS_INSUFFICIENT);
    }

    @Test
    void aPlatformAgentAtItsOwnDailyCapIsSkipped() {
        assertSkipped(PlatformUsageService.SkipReason.AGENT_DAILY_CAP);
    }

    @Test
    void aPlatformAgentAtTheGlobalDailyCapIsSkipped() {
        assertSkipped(PlatformUsageService.SkipReason.GLOBAL_DAILY_CAP);
    }

    /**
     * The IGNORE row is what an owner reads when their agent goes quiet, so it has to name
     * the reason - and charge nothing, because nothing reached a model.
     */
    @Test
    void theSkipRowNamesTheReasonAndChargesNoTokens() {
        when(platformUsageService.checkReadiness(any(Agent.class)))
                .thenReturn(PlatformUsageService.SkipReason.OWNER_POINTS_INSUFFICIENT);

        processor.wake(agent(), WakeReason.RHYTHM, List.of());

        ArgumentCaptor<String> result = ArgumentCaptor.forClass(String.class);
        verify(agentActionExecutor).logAgentError(any(Agent.class), result.capture(),
                eq(0L), any(WakeLogContext.class));
        assertThat(result.getValue())
                .contains("PLATFORM_SKIPPED")
                .contains("OWNER_POINTS_INSUFFICIENT");
    }

    @Test
    void aSkippedAgentIsNotMarkedDead() {
        when(platformUsageService.checkReadiness(any(Agent.class)))
                .thenReturn(PlatformUsageService.SkipReason.AGENT_DAILY_CAP);

        processor.wake(agent(), WakeReason.RHYTHM, List.of());

        verify(agentActionExecutor, never()).markAgentDead(any(Agent.class));
        verify(agentActionExecutor, never()).chargeTokensOnly(any(), anyLong(), anyString(), any());
    }

    @Test
    void onlyTheOwnerActionableSkipIsPassedToTheNotifier() {
        when(platformUsageService.checkReadiness(any(Agent.class)))
                .thenReturn(PlatformUsageService.SkipReason.GLOBAL_DAILY_CAP);

        processor.wake(agent(), WakeReason.RHYTHM, List.of());

        // The processor always asks; the service decides. Keeping the rule in one place is
        // what stops a second call site from inventing its own answer.
        verify(platformUsageService).notifyIfActionable(any(Agent.class),
                eq(PlatformUsageService.SkipReason.GLOBAL_DAILY_CAP));
    }

    // ========== Cleared agents ==========

    @Test
    void aClearedAgentWakesUpAndIsChargedForTheCall() {
        when(platformUsageService.checkReadiness(any(Agent.class))).thenReturn(null);
        when(llmClient.callLLM(any(Agent.class), any(AgentContext.class)))
                .thenReturn(LLMResponse.builder().success(true).totalTokens(2000)
                        .model("platform-model").build());
        when(llmClient.convertToDecisions(any(LLMResponse.class)))
                .thenReturn(List.of(AgentActionDecision.builder().action(ActionType.IGNORE).build()));
        when(agentActionExecutor.applyDecisions(any(), any(), anyLong(), any(), any()))
                .thenReturn(List.of());

        WakeOutcome outcome = processor.wake(agent(), WakeReason.RHYTHM, List.of());

        assertThat(outcome).isEqualTo(WakeOutcome.PROCESSED);
        verify(platformUsageService).charge(any(Agent.class), eq(2000L), eq("platform-model"));
    }

    /**
     * A failure envelope does not prove the provider did not bill, so the owner pays the
     * same floor the agent's own token budget was charged.
     */
    @Test
    void aFailedGatewayCallStillChargesTheFloor() {
        when(platformUsageService.checkReadiness(any(Agent.class))).thenReturn(null);
        when(llmClient.callLLM(any(Agent.class), any(AgentContext.class)))
                .thenReturn(LLMResponse.builder().success(false).errorMessage("upstream 500").build());

        WakeOutcome outcome = processor.wake(agent(), WakeReason.RHYTHM, List.of());

        assertThat(outcome).isEqualTo(WakeOutcome.PROCESSED);
        verify(agentActionExecutor).chargeTokensOnly(any(), eq(200L), anyString(), any());
        verify(platformUsageService).charge(any(Agent.class), eq(200L), any());
    }

    /**
     * An exhausted agent is dead before the platform gate is ever consulted: dying of a
     * spent token budget is not a platform condition and must not be reported as one.
     */
    @Test
    void anExhaustedAgentDiesWithoutConsultingThePlatformGate() {
        Agent agent = agent();
        agent.setUsedTokens(500000L);

        WakeOutcome outcome = processor.wake(agent, WakeReason.RHYTHM, List.of());

        assertThat(outcome).isEqualTo(WakeOutcome.PROCESSED);
        verify(agentActionExecutor).markAgentDead(agent);
        verifyNoInteractions(platformUsageService);
    }

    private void assertSkipped(PlatformUsageService.SkipReason reason) {
        when(platformUsageService.checkReadiness(any(Agent.class))).thenReturn(reason);

        WakeOutcome outcome = processor.wake(agent(), WakeReason.RHYTHM, List.of());

        assertThat(outcome).isEqualTo(WakeOutcome.SKIPPED);
        // Never reaches the model, and never builds the context that would cost queries
        verifyNoInteractions(llmClient);
        verify(postMapper, never()).findLatestPostsForAgent(anyInt(), anyLong());
        verify(platformUsageService, never()).charge(any(), anyLong(), anyString());
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(42L);
        agent.setOwnerId(7L);
        agent.setName("Pulse");
        agent.setStatus(1);
        agent.setUsedTokens(1000L);
        agent.setTokenThreshold(500000L);
        agent.setIsUnlimited(false);
        agent.setProviderMode("PLATFORM");
        agent.setSystemPrompt("你是 Pulse");
        return agent;
    }
}
