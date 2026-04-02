package com.pulse.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.AgentContext;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.LLMResponse;
import com.pulse.entity.Agent;
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * LLM Client
 *
 * Handles communication with external LLM APIs (OpenAI-compatible).
 * Called by AgentLoopScheduler to get agent's action decision.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private final RestTemplate restTemplate;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    /**
     * Call LLM and get agent's action decision
     *
     * @param agent Agent entity
     * @param context Agent context (posts + system prompt)
     * @return LLMResponse with action decision
     */
    public LLMResponse callLLM(Agent agent, AgentContext context) {
        // Decrypt API Key
        String apiKey = aesUtil.decrypt(agent.getApiKey());
        if (apiKey == null) {
            log.error("Failed to decrypt API Key for agent {}", agent.getId());
            return LLMResponse.builder()
                    .success(false)
                    .errorMessage("API Key decryption failed")
                    .build();
        }

        // Build request body (OpenAI-compatible format)
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", agent.getModelName());
        requestBody.put("messages", new Object[]{
                Map.of("role", "system", "content", context.buildFullPrompt())
        });
        requestBody.put("max_tokens", 200);
        requestBody.put("temperature", 0.7);

        // Build headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // Trim baseUrl to avoid URL encoding issues with leading/trailing spaces
        String baseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";

        // Defensive: Remove any remaining encoded spaces (%20) that might have been stored
        baseUrl = baseUrl.replace("%20", "");

        // Ensure baseUrl doesn't have any whitespace characters
        baseUrl = baseUrl.replaceAll("\\s+", "");

        String url = baseUrl + "/chat/completions";

        long startTime = System.currentTimeMillis();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class
            );

            long responseTime = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parseLLMResponse(response.getBody(), responseTime);
            } else {
                log.warn("LLM call failed: agent={}, status={}", agent.getId(), response.getStatusCode());
                return LLMResponse.builder()
                        .success(false)
                        .errorMessage("LLM API returned non-2xx status")
                        .responseTimeMs(responseTime)
                        .build();
            }

        } catch (RestClientException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("LLM call exception: agent={}, error={}", agent.getId(), e.getMessage());
            return LLMResponse.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .responseTimeMs(responseTime)
                    .build();
        }
    }

    /**
     * Parse OpenAI-compatible API response
     */
    private LLMResponse parseLLMResponse(String responseBody, long responseTime) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Extract usage info
            JsonNode usage = root.path("usage");
            int totalTokens = usage.path("total_tokens").asInt(0);
            int promptTokens = usage.path("prompt_tokens").asInt(0);
            int completionTokens = usage.path("completion_tokens").asInt(0);

            // Extract content
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String content = choices.get(0)
                        .path("message")
                        .path("content")
                        .asText();

                String model = root.path("model").asText();

                return LLMResponse.builder()
                        .content(content)
                        .totalTokens(totalTokens)
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .model(model)
                        .success(true)
                        .responseTimeMs(responseTime)
                        .build();
            } else {
                return LLMResponse.builder()
                        .success(false)
                        .errorMessage("No choices in response")
                        .responseTimeMs(responseTime)
                        .build();
            }

        } catch (Exception e) {
            log.error("Failed to parse LLM response: {}", e.getMessage());
            return LLMResponse.builder()
                    .success(false)
                    .errorMessage("JSON parse error: " + e.getMessage())
                    .responseTimeMs(responseTime)
                    .build();
        }
    }

    /**
     * Parse action decision from LLM response content
     */
    public AgentActionDecision parseActionDecision(String content) {
        if (content == null || content.isEmpty()) {
            return AgentActionDecision.builder()
                    .action(com.pulse.enums.ActionType.IGNORE)
                    .build();
        }

        try {
            // Try to extract JSON from content (LLM might wrap it in markdown)
            String jsonContent = extractJson(content);

            JsonNode node = objectMapper.readTree(jsonContent);

            String actionStr = node.path("action").asText("ignore");
            com.pulse.enums.ActionType action = com.pulse.enums.ActionType.fromCode(actionStr);

            Long targetPostId = null;
            if (node.has("target_post_id")) {
                targetPostId = node.path("target_post_id").asLong();
            }

            String actionContent = node.path("content").asText();

            AgentActionDecision decision = AgentActionDecision.builder()
                    .action(action)
                    .targetPostId(targetPostId)
                    .content(actionContent)
                    .build();

            if (!decision.isValid()) {
                log.warn("Invalid action decision: {}", decision);
                return AgentActionDecision.builder()
                        .action(com.pulse.enums.ActionType.IGNORE)
                        .build();
            }

            return decision;

        } catch (Exception e) {
            log.warn("Failed to parse action decision from content: {}", e.getMessage());
            return AgentActionDecision.builder()
                    .action(com.pulse.enums.ActionType.IGNORE)
                    .build();
        }
    }

    /**
     * Extract JSON from potentially markdown-wrapped content
     */
    private String extractJson(String content) {
        // Remove markdown code block wrapper if present
        if (content.contains("```json")) {
            int start = content.indexOf("```json") + 7;
            int end = content.indexOf("```", start);
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }
        if (content.contains("```")) {
            int start = content.indexOf("```") + 3;
            int end = content.lastIndexOf("```");
            if (end > start) {
                return content.substring(start, end).trim();
            }
        }
        return content.trim();
    }
}