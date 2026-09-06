package com.pulse.service.support;

import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Agent;
import com.pulse.enums.ProviderMode;
import com.pulse.mapper.AgentMapper;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which credentials a call goes out with, and - more importantly - which ones it does not.
 *
 * The failure this file exists to prevent is a PLATFORM agent falling back to something:
 * to an empty key, to its own null columns, or to BYOK. Every one of those looks like a
 * working agent right up to the moment it spends the wrong account's money.
 */
class LlmCredentialResolverTest {

    private final AesUtil aesUtil = mock(AesUtil.class);
    private final SchemaCapabilities schemaCapabilities = mock(SchemaCapabilities.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final PlatformLlmProperties properties = new PlatformLlmProperties();

    private final LlmCredentialResolver resolver =
            new LlmCredentialResolver(aesUtil, properties, schemaCapabilities, agentMapper);

    @BeforeEach
    void platformIsConfiguredAndMigrated() {
        properties.setEnabled(true);
        properties.setApiKey("sk-platform-secret");
        properties.setBaseUrl("https://platform.example/v1");
        properties.setModelName("platform-model");
        properties.setPointsPer1kTokens(BigDecimal.ONE);
        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(true);
        when(aesUtil.decrypt(anyString())).thenReturn("sk-owner-key");
    }

    // ========== BYOK ==========

    @Test
    void byokAgentUsesItsOwnDecryptedKey() {
        LlmCredentialResolver.Credentials credentials = resolver.resolve(byokAgent());

        assertThat(credentials).isNotNull();
        assertThat(credentials.getMode()).isEqualTo(ProviderMode.BYOK);
        assertThat(credentials.getApiKey()).isEqualTo("sk-owner-key");
        assertThat(credentials.getBaseUrl()).isEqualTo("https://owner.example/v1");
        assertThat(credentials.getModelName()).isEqualTo("gpt-4o-mini");
    }

    /**
     * The pre-existing default, kept: an agent stored without a base URL still reaches
     * OpenAI rather than a null host.
     */
    @Test
    void byokAgentWithoutABaseUrlFallsBackToTheProviderDefault() {
        Agent agent = byokAgent();
        agent.setBaseUrl(null);

        assertThat(resolver.resolve(agent).getBaseUrl())
                .isEqualTo(LlmCredentialResolver.DEFAULT_BASE_URL);
    }

    @Test
    void anUndecryptableKeyResolvesToNothingRatherThanAnEmptyKey() {
        when(aesUtil.decrypt(anyString())).thenThrow(new RuntimeException("bad ciphertext"));

        assertThat(resolver.resolve(byokAgent())).isNull();
    }

    // ========== PLATFORM ==========

    @Test
    void platformAgentUsesThePlatformCredentialsAndNeverItsOwnColumns() {
        Agent agent = platformAgent();
        // Deliberately populated, as a row written by an older build might be: the
        // resolver must ignore them entirely rather than prefer whichever is non-null.
        agent.setApiKey("stale-encrypted");
        agent.setBaseUrl("https://stale.example/v1");
        agent.setModelName("stale-model");

        LlmCredentialResolver.Credentials credentials = resolver.resolve(agent);

        assertThat(credentials.getMode()).isEqualTo(ProviderMode.PLATFORM);
        assertThat(credentials.getApiKey()).isEqualTo("sk-platform-secret");
        assertThat(credentials.getBaseUrl()).isEqualTo("https://platform.example/v1");
        assertThat(credentials.getModelName()).isEqualTo("platform-model");
        verify(aesUtil, never()).decrypt(anyString());
    }

    @Test
    void platformAgentResolvesToNothingWhenTheFeatureIsOff() {
        properties.setEnabled(false);

        assertThat(resolver.resolve(platformAgent())).isNull();
    }

    @Test
    void platformAgentResolvesToNothingWhenTheKeyIsMissing() {
        properties.setApiKey("");

        assertThat(resolver.resolve(platformAgent())).isNull();
    }

    @Test
    void platformAgentResolvesToNothingWhenTheModelIsMissing() {
        properties.setModelName("  ");

        assertThat(resolver.resolve(platformAgent())).isNull();
    }

    /**
     * The one thing that must never happen: an unusable platform configuration quietly
     * turning a PLATFORM agent into a BYOK one and spending the owner's stored key.
     */
    @Test
    void anUnusablePlatformDoesNotDowngradeTheAgentToByok() {
        properties.setEnabled(false);
        Agent agent = platformAgent();
        agent.setApiKey("stale-encrypted");

        assertThat(resolver.resolve(agent)).isNull();
        verify(aesUtil, never()).decrypt(anyString());
    }

    // ========== Mode resolution ==========

    /**
     * The agent came from selectById, whose generated statement never selects
     * provider_mode - so the field is null and one explicit lookup has to fill it in.
     */
    @Test
    void anUnsetModeIsReadFromTheDatabaseOnce() {
        Agent agent = byokAgent();
        agent.setProviderMode(null);
        when(agentMapper.findProviderMode(42L)).thenReturn("PLATFORM");

        assertThat(resolver.modeOf(agent)).isEqualTo(ProviderMode.PLATFORM);
        // Cached on the instance, so a second consumer in the same wake-up is free
        assertThat(resolver.modeOf(agent)).isEqualTo(ProviderMode.PLATFORM);
        verify(agentMapper, org.mockito.Mockito.times(1)).findProviderMode(42L);
    }

    @Test
    void noProviderModeColumnMeansEveryAgentIsByok() {
        when(schemaCapabilities.isAgentProviderModeColumns()).thenReturn(false);
        Agent agent = byokAgent();
        agent.setProviderMode(null);

        assertThat(resolver.modeOf(agent)).isEqualTo(ProviderMode.BYOK);
        assertThat(resolver.isPlatformAvailable()).isFalse();
        verify(agentMapper, never()).findProviderMode(anyLong());
    }

    /**
     * A failing probe must not be able to promote an agent to PLATFORM: BYOK is the mode
     * that spends nobody else's money.
     */
    @Test
    void aFailedModeProbeFallsBackToByok() {
        Agent agent = byokAgent();
        agent.setProviderMode(null);
        when(agentMapper.findProviderMode(anyLong())).thenThrow(new RuntimeException("no column"));

        assertThat(resolver.modeOf(agent)).isEqualTo(ProviderMode.BYOK);
    }

    @Test
    void aStoredNullReadsAsByok() {
        Agent agent = byokAgent();
        agent.setProviderMode(null);
        when(agentMapper.findProviderMode(anyLong())).thenReturn(null);

        assertThat(resolver.modeOf(agent)).isEqualTo(ProviderMode.BYOK);
    }

    /**
     * Credentials must never be able to reach a log line through string interpolation.
     */
    @Test
    void credentialsDoNotPrintTheKey() {
        String rendered = resolver.resolve(platformAgent()).toString();

        assertThat(rendered).doesNotContain("sk-platform-secret");
        assertThat(rendered).contains("PLATFORM");
    }

    private Agent byokAgent() {
        Agent agent = new Agent();
        agent.setId(42L);
        agent.setOwnerId(7L);
        agent.setProviderMode("BYOK");
        agent.setApiKey("encrypted");
        agent.setBaseUrl("https://owner.example/v1");
        agent.setModelName("gpt-4o-mini");
        return agent;
    }

    private Agent platformAgent() {
        Agent agent = new Agent();
        agent.setId(43L);
        agent.setOwnerId(7L);
        agent.setProviderMode("PLATFORM");
        return agent;
    }
}
