package com.pulse.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.config.AgentTemplateCatalog;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentProviderSettings;
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
import com.pulse.service.support.LlmCredentialResolver;
import com.pulse.service.support.WakeScheduleCalculator;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating and reading an agent in either provider mode.
 *
 * Two rules carry the whole feature and each has a failure mode worth naming:
 * - which credential fields are required depends on provider_mode, a rule bean validation
 *   cannot express, so it lives in the service and must keep producing the SAME error a
 *   BYOK client saw before;
 * - a PLATFORM agent must never be created where it cannot run, because a keyless agent
 *   looks created and then fails on every wake-up.
 */
class AgentProviderModeTest {

    private static final Long OWNER_ID = 7L;
    private static final Long AGENT_ID = 42L;

    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final AgentLogMapper agentLogMapper = mock(AgentLogMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final PostMapper postMapper = mock(PostMapper.class);
    private final AesUtil aesUtil = mock(AesUtil.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final WakeScheduleCalculator wakeScheduleCalculator =
            new WakeScheduleCalculator(new Random(3));
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

        platformLlmProperties.setEnabled(true);
        platformLlmProperties.setApiKey("sk-platform-secret");
        platformLlmProperties.setBaseUrl("https://platform.example/v1");
        platformLlmProperties.setModelName("platform-model");
        platformLlmProperties.setPointsPer1kTokens(BigDecimal.ONE);

        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(true);
        when(schemaCapabilities.isWakeQueueSchema()).thenReturn(false);
        when(userMapper.selectById(OWNER_ID)).thenReturn(user());
        when(aesUtil.encrypt(anyString())).thenReturn("encrypted");
        when(aesUtil.decrypt(anyString())).thenReturn("sk-plain");
        when(aesUtil.maskApiKey(anyString())).thenReturn("sk-****");
        when(agentMapper.selectCount(any())).thenReturn(0L);
        when(agentMapper.insert(any(Agent.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Agent.class).setId(AGENT_ID);
            return 1;
        });
    }

    // ========== Mode validation on create ==========

    @Test
    void byokIsTheDefaultAndStillRequiresAllThreeCredentialFields() {
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("BYOK", null));

        AgentDetailResponse response = service.createAgent(OWNER_ID, byokRequest());

        assertThat(response.getProviderMode()).isEqualTo("BYOK");
        verify(agentMapper).updateProviderMode(AGENT_ID, "BYOK", null);
    }

    @Test
    void aByokRequestWithoutAnApiKeyIsAParameterError() {
        AgentCreateRequest request = byokRequest();
        request.setApiKey(null);

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
    }

    @Test
    void aByokRequestWithoutABaseUrlIsAParameterError() {
        AgentCreateRequest request = byokRequest();
        request.setBaseUrl("   ");

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
    }

    @Test
    void aByokRequestWithoutAModelNameIsAParameterError() {
        AgentCreateRequest request = byokRequest();
        request.setModelName(null);

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
    }

    @Test
    void aPlatformRequestNeedsNoCredentialFieldsAtAll() {
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", "tech-critic"));

        AgentDetailResponse response = service.createAgent(OWNER_ID, platformRequest());

        assertThat(response.getProviderMode()).isEqualTo("PLATFORM");
        assertThat(response.getTemplateId()).isEqualTo("tech-critic");
        verify(agentMapper).updateProviderMode(AGENT_ID, "PLATFORM", "tech-critic");
    }

    /**
     * The stored row keeps NULL rather than a copy of the platform settings, so rotating
     * the platform key or model can never leave agent rows holding a stale one.
     */
    @Test
    void aPlatformAgentStoresNoCredentialsOfItsOwn() {
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", null));
        AgentCreateRequest request = platformRequest();
        // Sent anyway, as a careless client might
        request.setBaseUrl("https://attacker.example/v1");
        request.setApiKey("sk-should-be-ignored");
        request.setModelName("some-other-model");

        service.createAgent(OWNER_ID, request);

        ArgumentCaptor<Agent> saved = ArgumentCaptor.forClass(Agent.class);
        verify(agentMapper).insert(saved.capture());
        assertThat(saved.getValue().getApiKey()).isNull();
        assertThat(saved.getValue().getBaseUrl()).isNull();
        assertThat(saved.getValue().getModelName()).isNull();
        verify(aesUtil, never()).encrypt(anyString());
    }

    @Test
    void creatingAPlatformAgentIsRefusedWhenTheFeatureIsOff() {
        platformLlmProperties.setEnabled(false);

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, platformRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.PLATFORM_MODEL_UNAVAILABLE.getCode());
        verify(agentMapper, never()).insert(any(Agent.class));
    }

    @Test
    void creatingAPlatformAgentIsRefusedWhenTheKeyIsMissing() {
        platformLlmProperties.setApiKey("");

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, platformRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.PLATFORM_MODEL_UNAVAILABLE.getCode());
    }

    /**
     * The un-migrated database case. Without the column the agent would read back as BYOK
     * with no key - created, and broken on its first wake-up.
     */
    @Test
    void creatingAPlatformAgentIsRefusedWithoutTheProviderModeColumns() {
        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(false);

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, platformRequest()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.PLATFORM_MODEL_UNAVAILABLE.getCode());
    }

    /**
     * A BYOK agent must still be creatable on that same un-migrated database - the whole
     * point of the capability gate.
     */
    @Test
    void aByokAgentIsStillCreatableWithoutTheProviderModeColumns() {
        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(false);

        AgentDetailResponse response = service.createAgent(OWNER_ID, byokRequest());

        assertThat(response.getProviderMode()).isEqualTo("BYOK");
        verify(agentMapper, never()).updateProviderMode(anyLong(), anyString(), anyString());
    }

    @Test
    void anUnrecognisedProviderModeIsAParameterErrorRatherThanASilentByok() {
        AgentCreateRequest request = byokRequest();
        request.setProviderMode("PLATFROM");

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
    }

    @Test
    void theModeIsCaseInsensitive() {
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", null));
        AgentCreateRequest request = platformRequest();
        request.setProviderMode("platform");

        assertThat(service.createAgent(OWNER_ID, request).getProviderMode()).isEqualTo("PLATFORM");
    }

    @Test
    void anUnknownTemplateIdIsAParameterError() {
        AgentCreateRequest request = byokRequest();
        request.setTemplateId("not-a-real-template");

        assertThatThrownBy(() -> service.createAgent(OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
    }

    @Test
    void aBlankTemplateIdIsStoredAsNullRatherThanRejected() {
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("BYOK", null));
        AgentCreateRequest request = byokRequest();
        request.setTemplateId("   ");

        service.createAgent(OWNER_ID, request);

        verify(agentMapper).updateProviderMode(AGENT_ID, "BYOK", null);
    }

    // ========== Update ==========

    @Test
    void aPlatformAgentRefusesCredentialEdits() {
        whenStored("PLATFORM");
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setApiKey("sk-my-own-key-now");

        assertThatThrownBy(() -> service.updateAgent(OWNER_ID, AGENT_ID, request))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.INVALID_PARAMETER.getCode());
        verify(agentMapper, never()).updateById(any(Agent.class));
    }

    @Test
    void aPlatformAgentStillAcceptsOrdinaryEdits() {
        whenStored("PLATFORM");
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", null));
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setSystemPrompt("你是 Pulse，一个更安静的社区居民");

        AgentDetailResponse response = service.updateAgent(OWNER_ID, AGENT_ID, request);

        assertThat(response.getProviderMode()).isEqualTo("PLATFORM");
        verify(agentMapper).updateById(any(Agent.class));
    }

    @Test
    void aByokAgentStillAcceptsCredentialEdits() {
        whenStored("BYOK");
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("BYOK", null));
        AgentUpdateRequest request = new AgentUpdateRequest();
        request.setApiKey("sk-rotated-key");

        service.updateAgent(OWNER_ID, AGENT_ID, request);

        verify(aesUtil).encrypt("sk-rotated-key");
    }

    // ========== Response shape ==========

    @Test
    void aPlatformAgentReportsTheSentinelAndNoBaseUrl() {
        whenStored("PLATFORM");
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", "philosopher"));

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getApiKeyMasked()).isEqualTo("PLATFORM");
        assertThat(response.getBaseUrl()).isNull();
        // The stored column is null; the owner still has to know what it runs on
        assertThat(response.getModelName()).isEqualTo("platform-model");
        assertThat(response.getTemplateId()).isEqualTo("philosopher");
    }

    @Test
    void aByokAgentReportsItsOwnMaskedKeyAndBaseUrl() {
        whenStored("BYOK");
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("BYOK", null));

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getApiKeyMasked()).isEqualTo("sk-****");
        assertThat(response.getBaseUrl()).isEqualTo("https://owner.example/v1");
        assertThat(response.getModelName()).isEqualTo("gpt-4o-mini");
    }

    /**
     * An agent stored before this feature, or on a database without the columns, is a BYOK
     * agent - which is exactly what it always was.
     */
    @Test
    void anAgentWithoutProviderColumnsReadsBackAsByok() {
        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(false);
        whenStored(null);

        AgentDetailResponse response = service.getAgentDetail(OWNER_ID, AGENT_ID);

        assertThat(response.getProviderMode()).isEqualTo("BYOK");
        assertThat(response.getApiKeyMasked()).isEqualTo("sk-****");
        assertThat(response.getTemplateId()).isNull();
    }

    /**
     * The serialized response is the actual wire contract, so the redaction is asserted on
     * the JSON rather than on getters: a field added to the DTO later would show up here.
     */
    @Test
    void theSerializedDetailResponseCarriesNoPlatformSecret() throws Exception {
        whenStored("PLATFORM");
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings("PLATFORM", null));

        String json = new ObjectMapper().writeValueAsString(service.getAgentDetail(OWNER_ID, AGENT_ID));

        assertThat(json).doesNotContain("sk-platform-secret");
        assertThat(json).doesNotContain("platform.example");
        assertThat(json).contains("\"provider_mode\":\"PLATFORM\"");
        assertThat(json).contains("\"api_key_masked\":\"PLATFORM\"");
    }

    // ========== Fixtures ==========

    private AgentCreateRequest byokRequest() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("Pulse");
        request.setBaseUrl("https://owner.example/v1");
        request.setApiKey("sk-1234567890");
        request.setModelName("gpt-4o-mini");
        request.setSystemPrompt("你是 Pulse，一个理性的社区居民");
        request.setTokenThreshold(500000L);
        request.setIsUnlimited(false);
        return request;
    }

    private AgentCreateRequest platformRequest() {
        AgentCreateRequest request = new AgentCreateRequest();
        request.setName("Pulse");
        request.setProviderMode("PLATFORM");
        request.setTemplateId("tech-critic");
        request.setSystemPrompt("你是 Pulse，一个理性的社区居民");
        request.setTokenThreshold(500000L);
        request.setIsUnlimited(false);
        return request;
    }

    /**
     * Stand in for a stored row as every read path actually sees it: selectById returns
     * an Agent whose providerMode field is null (the generated statement never selects
     * that column), and the mode is only reachable through the explicit projection.
     */
    private void whenStored(String providerMode) {
        when(agentMapper.selectById(AGENT_ID)).thenReturn(storedAgent(providerMode));
        when(agentMapper.findProviderMode(AGENT_ID)).thenReturn(providerMode);
        when(agentMapper.findProviderSettings(AGENT_ID)).thenReturn(settings(providerMode, null));
    }

    private Agent storedAgent(String providerMode) {
        Agent agent = new Agent();
        agent.setId(AGENT_ID);
        agent.setOwnerId(OWNER_ID);
        agent.setName("Pulse");
        agent.setStatus(1);
        agent.setUsedTokens(1000L);
        agent.setTokenThreshold(500000L);
        agent.setIsUnlimited(false);
        agent.setSystemPrompt("你是 Pulse");
        if ("PLATFORM".equals(providerMode)) {
            agent.setApiKey(null);
            agent.setBaseUrl(null);
            agent.setModelName(null);
        } else {
            agent.setApiKey("encrypted");
            agent.setBaseUrl("https://owner.example/v1");
            agent.setModelName("gpt-4o-mini");
        }
        // Deliberately NOT set on the entity: selectById never selects provider_mode, so
        // the service has to reach it through the explicit projection below.
        return agent;
    }

    private AgentProviderSettings settings(String mode, String templateId) {
        AgentProviderSettings settings = new AgentProviderSettings();
        settings.setAgentId(AGENT_ID);
        settings.setProviderMode(mode);
        settings.setTemplateId(templateId);
        return settings;
    }

    private User user() {
        User user = new User();
        user.setId(OWNER_ID);
        user.setUsername("owner");
        return user;
    }
}
