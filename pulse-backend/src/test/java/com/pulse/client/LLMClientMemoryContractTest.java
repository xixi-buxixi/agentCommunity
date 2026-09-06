package com.pulse.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.AgentContext;
import com.pulse.dto.AgentMemoryCard;
import com.pulse.dto.ReflectionContext;
import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.mapper.AgentMapper;
import com.pulse.service.support.LlmCredentialResolver;
import com.pulse.util.AesUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wire-format tests for the two memory-related gateway contracts.
 *
 * These assert the snake_case field names on purpose: the AI side is implemented
 * separately against the same contract document, and a silent rename on either side is
 * exactly the kind of break no other test would catch.
 */
class LLMClientMemoryContractTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AesUtil aesUtil = mock(AesUtil.class);

    // A real resolver over a mocked AesUtil rather than a mocked resolver: these tests
    // assert what actually goes on the wire, and the credential fields are part of that.
    // The agents here have no provider_mode, and the capability probe reports the column
    // absent, so every one of them resolves as BYOK - exactly the path this file covers.
    private final LlmCredentialResolver credentialResolver = new LlmCredentialResolver(
            aesUtil, new PlatformLlmProperties(), mock(SchemaCapabilities.class),
            mock(AgentMapper.class));
    private final LLMClient client = new LLMClient(restTemplate, credentialResolver, new ObjectMapper());

    LLMClientMemoryContractTest() {
        ReflectionTestUtils.setField(client, "pythonGatewayBaseUrl", "http://ai-side:8000");
        ReflectionTestUtils.setField(client, "serviceToken", "test-token");
        when(aesUtil.decrypt(anyString())).thenReturn("sk-decrypted-key");
    }

    // ========== Contract A: decision request ==========

    @Test
    void decisionRequestCarriesMemoriesInTheContractShape() {
        stubGateway("{\"success\": true, \"action\": \"ignore\", \"usage\": {\"total_tokens\": 10}}");

        client.callLLM(agent(), AgentContext.builder()
                .systemPrompt("你是 Pulse")
                .postsContext("[Post#1] [HUMAN alice]: hi\n")
                .memories(List.of(
                        AgentMemoryCard.builder()
                                .memoryType("PERSONA_TRAIT")
                                .content("坚持小模型路线")
                                .confidenceScore(80)
                                .source("REFLECTION 2026-07-26")
                                .build(),
                        AgentMemoryCard.builder()
                                .memoryType("PERSONA_FACT")
                                .content("我发布了帖子《A》")
                                .confidenceScore(90)
                                .source("POST#123")
                                .build()))
                .build());

        Map<String, Object> body = capturedBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> memories = (List<Map<String, Object>>) body.get("memories");
        assertThat(memories).hasSize(2);
        assertThat(memories.get(0)).containsOnlyKeys(
                "memory_type", "content", "confidence_score", "source");
        assertThat(memories.get(0).get("memory_type")).isEqualTo("PERSONA_TRAIT");
        assertThat(memories.get(0).get("confidence_score")).isEqualTo(80);
        assertThat(memories.get(0).get("source")).isEqualTo("REFLECTION 2026-07-26");
        assertThat(memories.get(1).get("content")).isEqualTo("我发布了帖子《A》");
    }

    /**
     * Optional means absent, not null: an older gateway build must keep working for an
     * agent that has no memories yet.
     */
    @Test
    void decisionRequestOmitsMemoriesEntirelyWhenThereAreNone() {
        stubGateway("{\"success\": true, \"action\": \"ignore\"}");

        client.callLLM(agent(), AgentContext.builder()
                .systemPrompt("你是 Pulse")
                .postsContext("")
                .build());

        assertThat(capturedBody()).doesNotContainKey("memories");
    }

    // ========== Contract B: reflection ==========

    @Test
    void reflectionRequestSendsBehaviorsTraitsAndLimits() {
        stubGateway("{\"success\": true}");

        client.callReflection(agent(), ReflectionContext.builder()
                .recentBehaviors(List.of("[记忆] 我发布了帖子《A》"))
                .existingTraits(List.of(ReflectionContext.TraitSnapshot.builder()
                        .id(11L)
                        .content("坚持小模型路线")
                        .importanceScore(60)
                        .confidenceScore(70)
                        .build()))
                .maxNewTraits(5)
                .maxTotalTraits(30)
                .build());

        verify(restTemplate).exchange(eq("http://ai-side:8000/v1/llm/reflection"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class));

        Map<String, Object> body = capturedBody();
        assertThat(body.get("api_key")).isEqualTo("sk-decrypted-key");
        assertThat(body.get("model_name")).isEqualTo("gpt-4o-mini");
        assertThat(body.get("recent_behaviors")).isEqualTo(List.of("[记忆] 我发布了帖子《A》"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traits = (List<Map<String, Object>>) body.get("existing_traits");
        assertThat(traits).hasSize(1);
        assertThat(traits.get(0)).containsOnlyKeys("id", "content", "importance_score", "confidence_score");

        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) body.get("limits");
        assertThat(limits).containsEntry("max_new_traits", 5).containsEntry("max_total_traits", 30);
    }

    @Test
    void reflectionResponseIsParsedIntoTraitChanges() {
        stubGateway("""
                {
                  "success": true,
                  "new_traits": [
                    {"content": "偏爱数据驱动的反驳", "evidence": "近三次回复引用基准",
                     "importance_score": 70, "confidence_score": 65}
                  ],
                  "updated_traits": [
                    {"id": 11, "content": "坚持小模型路线，但接受混合部署",
                     "evidence": "本周两次回复都提到混合部署",
                     "importance_score": 80, "confidence_score": 75},
                    {"id": 12, "content": "没有新证据的修订",
                     "importance_score": 50, "confidence_score": 50}
                  ],
                  "deprecated_trait_ids": [12, 13],
                  "usage": {"total_tokens": 900}
                }
                """);

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getTotalTokens()).isEqualTo(900);
        assertThat(result.safeNewTraits()).hasSize(1);
        assertThat(result.safeNewTraits().get(0).getImportanceScore()).isEqualTo(70);
        assertThat(result.safeUpdatedTraits().get(0).getId()).isEqualTo(11L);
        assertThat(result.safeUpdatedTraits().get(0).getEvidence())
                .isEqualTo("本周两次回复都提到混合部署");
        // absent (or explicitly null) evidence parses to null, which the update statement
        // writes as NULL - a revised trait never keeps a citation for text it no longer has
        assertThat(result.safeUpdatedTraits().get(1).getEvidence()).isNull();
        assertThat(result.safeDeprecatedTraitIds()).containsExactly(12L, 13L);
    }

    @Test
    void aGatewayReportedFailureDegradesToEmptyListsButKeepsTheTokenCount() {
        stubGateway("{\"success\": false, \"error_message\": \"model refused\", \"total_tokens\": 40}");

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).isEqualTo("model refused");
        assertThat(result.getTotalTokens()).isEqualTo(40);
        assertThat(result.safeNewTraits()).isEmpty();
        assertThat(result.safeUpdatedTraits()).isEmpty();
        assertThat(result.safeDeprecatedTraitIds()).isEmpty();
    }

    /**
     * The AI side's request validation (bad base_url and friends) is handled by its
     * global handler: HTTP 400 with a DECISION-shaped body. Nothing in it may be read as
     * traits, but the reported usage still counts - the model may have run before the
     * failure.
     */
    @Test
    void aValidationRejectionDegradesToFailureAndKeepsReportedUsage() {
        String decisionShapedBody = """
                {"success": false, "action": "ignore", "error_code": "INVALID_REQUEST",
                 "error_message": "base_url is not a valid URL", "usage": {"total_tokens": 15}}
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Bad Request",
                        decisionShapedBody.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("400").contains("base_url");
        assertThat(result.getTotalTokens()).isEqualTo(15);
        assertThat(result.safeNewTraits()).isEmpty();
        assertThat(result.safeUpdatedTraits()).isEmpty();
        assertThat(result.safeDeprecatedTraitIds()).isEmpty();
    }

    /**
     * Same envelope, but delivered as a 200 (a lenient error handler, or a gateway that
     * answers validation problems in-band). A body with none of the three reflection
     * arrays is not a reflection answer, so it must not be logged as a successful
     * distillation that happened to change nothing.
     */
    @Test
    void aDecisionShapedTwoHundredIsNotMistakenForAnEmptyReflection() {
        stubGateway("{\"success\": true, \"action\": \"ignore\", \"content\": null, "
                + "\"usage\": {\"total_tokens\": 20}}");

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getErrorMessage()).contains("shape");
        assertThat(result.getTotalTokens()).isEqualTo(20);
        assertThat(result.safeNewTraits()).isEmpty();
    }

    @Test
    void anExplicitlyEmptyReflectionIsStillASuccess() {
        stubGateway("{\"success\": true, \"new_traits\": [], \"updated_traits\": [], "
                + "\"deprecated_trait_ids\": [], \"total_tokens\": 30}");

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.safeNewTraits()).isEmpty();
        assertThat(result.getTotalTokens()).isEqualTo(30);
    }

    @Test
    void aFiveHundredWithNoBodyStillDegradesCleanly() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getTotalTokens()).isNull();
        assertThat(result.safeDeprecatedTraitIds()).isEmpty();
    }

    @Test
    void anUnparsableReflectionAnswerIsAFailureNotAnException() {
        stubGateway("not json at all");

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.safeNewTraits()).isEmpty();
    }

    @Test
    void aNonNumericTraitIdIsDropped() {
        stubGateway("{\"success\": true, \"deprecated_trait_ids\": [12, \"../../etc/passwd\", -3]}");

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.safeDeprecatedTraitIds()).containsExactly(12L);
    }

    @Test
    void anUndecryptableApiKeyNeverReachesTheGateway() {
        when(aesUtil.decrypt(anyString())).thenThrow(new RuntimeException("bad key"));

        ReflectionResult result = client.callReflection(agent(), context());

        assertThat(result.isSuccessful()).isFalse();
        verify(restTemplate, org.mockito.Mockito.never())
                .exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    // ========== Fixtures ==========

    private void stubGateway(String responseBody) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() {
        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        return captor.getValue().getBody();
    }

    private ReflectionContext context() {
        return ReflectionContext.builder()
                .recentBehaviors(List.of("[记忆] 我发布了帖子《A》"))
                .existingTraits(List.of())
                .maxNewTraits(5)
                .maxTotalTraits(30)
                .build();
    }

    private Agent agent() {
        Agent agent = new Agent();
        agent.setId(42L);
        agent.setOwnerId(7L);
        agent.setName("Pulse");
        agent.setApiKey("encrypted");
        agent.setBaseUrl("https://api.openai.com/v1");
        agent.setModelName("gpt-4o-mini");
        agent.setSystemPrompt("你是 Pulse");
        return agent;
    }
}
