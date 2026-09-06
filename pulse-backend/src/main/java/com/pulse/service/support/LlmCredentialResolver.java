package com.pulse.service.support;

import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Agent;
import com.pulse.enums.ProviderMode;
import com.pulse.mapper.AgentMapper;
import com.pulse.util.AesUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Where one agent's model credentials come from, resolved in one place.
 *
 * Both LLM entry points - the decision call and the nightly reflection call - used to
 * decrypt {@code agent.getApiKey()} inline and default the base URL inline, twice, with
 * the failure handling copied between them. With two provider modes that duplication
 * stops being cosmetic: it would be two places where a PLATFORM agent could accidentally
 * be sent an empty key, and two places to keep the platform key out of a log line.
 *
 * The resolved credentials never reach a log statement, a response DTO or an exception
 * message. A failure is reported as {@code null} with a reason the caller can put in its
 * own envelope, exactly as the previous inline code did for a decryption failure.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCredentialResolver {

    /** Provider endpoint assumed when a BYOK agent stored none. Pre-existing behaviour. */
    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final AesUtil aesUtil;
    private final PlatformLlmProperties platformLlmProperties;
    private final SchemaCapabilities schemaCapabilities;
    private final AgentMapper agentMapper;

    /**
     * Credentials for one call. Immutable, short lived, never serialised.
     */
    @Getter
    public static final class Credentials {
        private final String apiKey;
        private final String baseUrl;
        private final String modelName;
        private final ProviderMode mode;

        private Credentials(String apiKey, String baseUrl, String modelName, ProviderMode mode) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelName = modelName;
            this.mode = mode;
        }

        /**
         * Deliberately overridden: the default Lombok/record-style toString would put the
         * key into any log statement that ever interpolated this object.
         */
        @Override
        public String toString() {
            return "Credentials(mode=" + mode + ", model=" + modelName + ", apiKey=***)";
        }
    }

    /**
     * Whether PLATFORM mode can be offered and used at all.
     *
     * Three independent conditions, and all three have to hold: the feature is switched
     * on, the key and model are actually configured, and the database can record which
     * mode an agent is in. The last one matters as much as the others - without the
     * column every agent reads back as BYOK, so a "PLATFORM" agent created against an
     * un-migrated schema would silently be a BYOK agent with no key at all.
     */
    public boolean isPlatformAvailable() {
        return platformLlmProperties.isUsable() && schemaCapabilities.isAgentProviderModeColumns();
    }

    /**
     * The mode this agent runs in.
     *
     * Reads the field the scheduler's {@code SELECT *} queries automapped. When it is
     * null the row came from a generated statement (provider_mode is
     * {@code @TableField(exist = false)}, so {@code selectById} never selects it) and one
     * explicit lookup fills it in - but only when the column exists, because on an
     * un-migrated database null legitimately means BYOK and a query would just fail.
     */
    public ProviderMode modeOf(Agent agent) {
        if (agent == null) {
            return ProviderMode.BYOK;
        }
        if (agent.getProviderMode() != null) {
            return ProviderMode.ofStored(agent.getProviderMode());
        }
        if (!schemaCapabilities.isAgentProviderModeColumns() || agent.getId() == null) {
            return ProviderMode.BYOK;
        }
        try {
            String stored = agentMapper.findProviderMode(agent.getId());
            // Cache it on the instance so a second consumer in the same wake-up does not
            // repeat the query.
            agent.setProviderMode(stored);
            return ProviderMode.ofStored(stored);
        } catch (Exception e) {
            // A failed probe must not turn into a PLATFORM call on unknown terms; BYOK is
            // the mode that spends nobody else's money.
            log.warn("Could not read provider_mode for agent {}, assuming BYOK: {}",
                    agent.getId(), e.getMessage());
            return ProviderMode.BYOK;
        }
    }

    public boolean isPlatformAgent(Agent agent) {
        return modeOf(agent) == ProviderMode.PLATFORM;
    }

    /**
     * Credentials for a call, or null when this agent cannot make one.
     *
     * Null is a normal outcome with two causes, and both are already handled identically
     * by the callers: a BYOK key that will not decrypt, and a PLATFORM agent on a
     * deployment where the platform model is not usable. Neither is an exception - the
     * cycle is charged its floor and the agent tries again next time.
     */
    public Credentials resolve(Agent agent) {
        if (isPlatformAgent(agent)) {
            if (!isPlatformAvailable()) {
                log.warn("Agent {} runs on the platform model, which is not available on this "
                        + "deployment; the call is skipped", agent.getId());
                return null;
            }
            return new Credentials(platformLlmProperties.getApiKey(),
                    normalizeBaseUrl(platformLlmProperties.getBaseUrl()),
                    platformLlmProperties.getModelName(),
                    ProviderMode.PLATFORM);
        }

        String apiKey;
        try {
            apiKey = aesUtil.decrypt(agent.getApiKey());
        } catch (RuntimeException e) {
            // The message may embed ciphertext; only the agent id is logged.
            log.error("Failed to decrypt API Key for agent {}", agent.getId());
            return null;
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.error("Agent {} has no usable API Key", agent.getId());
            return null;
        }
        return new Credentials(apiKey, normalizeBaseUrl(agent.getBaseUrl()),
                agent.getModelName(), ProviderMode.BYOK);
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return raw.trim();
    }
}
