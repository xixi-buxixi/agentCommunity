package com.pulse.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.AgentContext;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.LLMResponse;
import com.pulse.entity.Agent;
import com.pulse.enums.ActionType;
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM Client
 *
 * Handles communication with Python AI Side Gateway.
 * Calls /v1/llm/decision endpoint to get agent's action decision.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LLMClient {

    private final RestTemplate restTemplate;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    @Value("${pulse-ai-side.base-url:http://localhost:8000}")
    private String pythonGatewayBaseUrl;

    /**
     * Read timeout is applied on the RestTemplate bean (see RestTemplateConfig);
     * kept here only for logging the configured budget.
     */
    @Value("${pulse-ai-side.timeout:30000}")
    private Integer gatewayTimeout;

    @Value("${pulse-ai-side.service-token:}")
    private String serviceToken;

    /**
     * Call Python AI Gateway and get agent's action decision
     *
     * @param agent Agent entity
     * @param context Agent context (posts + system prompt)
     * @return LLMResponse with parsed action decision
     */
    public LLMResponse callLLM(Agent agent, AgentContext context) {
        // Decrypt API Key
        String apiKey;
        try {
            apiKey = aesUtil.decrypt(agent.getApiKey());
        } catch (RuntimeException e) {
            log.error("Failed to decrypt API Key for agent {}", agent.getId());
            apiKey = null;
        }
        if (apiKey == null) {
            return LLMResponse.builder()
                    .success(false)
                    .errorCode("API_KEY_DECRYPTION_FAILED")
                    .errorMessage("API Key decryption failed")
                    .build();
        }

        // Build request payload matching Python LLMRequest structure
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("api_key", apiKey);
        requestBody.put("base_url", agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "https://api.openai.com/v1");
        requestBody.put("model_name", agent.getModelName());
        requestBody.put("system_prompt", context.getSystemPrompt());
        requestBody.put("context", context.getPostsContext());
        requestBody.put("max_tokens", 700);
        requestBody.put("temperature", 0.7);

        // Build headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Service-to-service authentication. Without this header the gateway's
        // auth middleware could only ever be fail-open: enabling SERVICE_TOKEN
        // there used to 401 every call from here.
        if (serviceToken != null && !serviceToken.isBlank()) {
            headers.set("X-Service-Token", serviceToken);
        } else {
            log.warn("pulse-ai-side.service-token is not configured; the AI gateway will reject this call");
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        // Call Python AI Gateway
        String url = pythonGatewayBaseUrl + "/v1/llm/decision";

        long startTime = System.currentTimeMillis();

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, String.class
            );

            long responseTime = System.currentTimeMillis() - startTime;

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return parsePythonGatewayResponse(response.getBody(), responseTime);
            }
            log.warn("Python Gateway call failed: agent={}, status={}", agent.getId(), response.getStatusCode());
            return LLMResponse.builder()
                    .success(false)
                    .errorCode("GATEWAY_NON_2XX")
                    .upstreamStatus(response.getStatusCode().value())
                    .errorMessage("Python Gateway returned non-2xx status")
                    .responseTimeMs(responseTime)
                    .build();

        } catch (HttpStatusCodeException e) {
            // The gateway answers failures with the same envelope (error_code,
            // upstream status, action=ignore). Previously the body was discarded
            // on any non-2xx, so none of those fields ever reached this side.
            long responseTime = System.currentTimeMillis() - startTime;
            log.warn("Python Gateway returned {}: agent={}", e.getStatusCode(), agent.getId());
            return parseGatewayErrorBody(e.getResponseBodyAsString(), e.getStatusCode().value(), responseTime);

        } catch (RestClientException e) {
            long responseTime = System.currentTimeMillis() - startTime;
            log.error("Python Gateway call exception: agent={}, error={}", agent.getId(), e.getMessage());
            return LLMResponse.builder()
                    .success(false)
                    .errorCode("GATEWAY_UNREACHABLE")
                    .errorMessage(e.getMessage())
                    .responseTimeMs(responseTime)
                    .build();
        }
    }

    /**
     * Read the shared error envelope out of a non-2xx gateway response.
     */
    private LLMResponse parseGatewayErrorBody(String body, int httpStatus, long responseTime) {
        String errorCode = "GATEWAY_ERROR";
        String errorMessage = "Python Gateway returned HTTP " + httpStatus;
        Integer upstreamStatus = null;

        if (body != null && !body.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.hasNonNull("error_code")) {
                    errorCode = root.get("error_code").asText();
                }
                if (root.hasNonNull("error_message")) {
                    errorMessage = root.get("error_message").asText();
                }
                JsonNode upstream = root.hasNonNull("upstream_status")
                        ? root.get("upstream_status")
                        : root.path("llm_status_code");
                if (upstream.isNumber()) {
                    upstreamStatus = upstream.intValue();
                }
            } catch (Exception parseError) {
                log.warn("Unable to parse gateway error envelope: {}", parseError.getMessage());
            }
        }

        return LLMResponse.builder()
                .success(false)
                .action(ActionType.IGNORE)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .upstreamStatus(upstreamStatus != null ? upstreamStatus : httpStatus)
                .responseTimeMs(responseTime)
                .build();
    }

    /**
     * Parse Python AI Gateway response (LLMResponse structure)
     *
     * Python returns either the legacy single-action shape or the evolution
     * multi-action shape:
     * - actions: [{type, target_post_id, content, ...}]
     * - usage.total_tokens or total_tokens / totalTokens
     * - model, response_time_ms, success, error_message
     */
    private LLMResponse parsePythonGatewayResponse(String responseBody, long responseTime) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            List<AgentActionDecision> actions = parseActions(root);
            AgentActionDecision firstAction = actions.isEmpty()
                    ? AgentActionDecision.builder().action(ActionType.IGNORE).build()
                    : actions.get(0);

            JsonNode usage = root.path("usage");
            Integer promptTokens = readInt(root, usage, "prompt_tokens", "promptTokens");
            Integer completionTokens = readInt(root, usage, "completion_tokens", "completionTokens");
            Integer totalTokens = readInt(root, usage, "total_tokens", "totalTokens");
            if (totalTokens == null) {
                totalTokens = safeInt(promptTokens) + safeInt(completionTokens);
            }

            // Parse model
            String model = root.path("model").asText(null);

            // Parse success and error_message
            boolean success = root.path("success").asBoolean(true);
            String errorMessage = root.path("error_message").asText(null);
            String errorCode = root.path("error_code").asText(null);
            Integer upstreamStatus = readInteger(root, "upstream_status", "llm_status_code");
            String reason = root.path("reason").asText(null);

            return LLMResponse.builder()
                    .action(firstAction.getAction())
                    .targetPostId(firstAction.getTargetPostId())
                    .parsedContent(firstAction.getContent())
                    .actions(actions)
                    .reason(reason)
                    .content(responseBody)  // Keep raw JSON for backward compatibility
                    .totalTokens(totalTokens)
                    .promptTokens(safeInt(promptTokens))
                    .completionTokens(safeInt(completionTokens))
                    .model(model)
                    .success(success)
                    .errorMessage(errorMessage)
                    .errorCode(errorCode)
                    .upstreamStatus(upstreamStatus)
                    .responseTimeMs(responseTime)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Python Gateway response: {}", e.getMessage());
            return LLMResponse.builder()
                    .success(false)
                    .errorCode("GATEWAY_RESPONSE_UNPARSABLE")
                    .errorMessage("Gateway response could not be parsed")
                    .responseTimeMs(responseTime)
                    .build();
        }
    }

    private List<AgentActionDecision> parseActions(JsonNode root) {
        List<AgentActionDecision> actions = new ArrayList<>();
        JsonNode actionsNode = root.path("actions");
        if (actionsNode.isArray()) {
            for (JsonNode actionNode : actionsNode) {
                actions.add(parseActionNode(actionNode));
            }
        } else {
            actions.add(parseActionNode(root));
        }
        return actions;
    }

    private AgentActionDecision parseActionNode(JsonNode node) {
        String actionStr = node.path("type").asText(node.path("action").asText("ignore"));
        BigDecimal reward = null;
        JsonNode rewardNode = node.has("reward_points") ? node.path("reward_points") : node.path("reward");
        if (!rewardNode.isMissingNode() && !rewardNode.isNull() && rewardNode.isNumber()) {
            reward = rewardNode.decimalValue();
        }

        return AgentActionDecision.builder()
                .action(ActionType.fromCode(actionStr))
                .targetPostId(readLong(node, "target_post_id", "targetPostId"))
                .content(node.path("content").asText(null))
                .title(node.path("title").asText(null))
                .description(node.path("description").asText(null))
                .rewardPoints(reward)
                .deadlineHours(readInteger(node, "deadline_hours", "deadlineHours"))
                .build();
    }

    private Integer readInt(JsonNode root, JsonNode usage, String snakeName, String camelName) {
        Integer value = readInteger(root, snakeName, camelName);
        if (value != null) {
            return value;
        }
        return readInteger(usage, snakeName, camelName);
    }

    /**
     * Strict numeric read.
     *
     * asInt()/asLong() coerce anything unparsable to 0 without complaining, which
     * turned a malformed usage figure into "this cycle was free" and a bogus
     * target_post_id into 0. A non-numeric node is reported and treated as absent.
     */
    private Integer readInteger(JsonNode node, String snakeName, String camelName) {
        JsonNode value = pick(node, snakeName, camelName);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            log.warn("Gateway field {} is not numeric: {}", snakeName, value.asText());
            return null;
        }
        return value.intValue();
    }

    private Long readLong(JsonNode node, String snakeName, String camelName) {
        JsonNode value = pick(node, snakeName, camelName);
        if (value == null) {
            return null;
        }
        if (!value.isNumber()) {
            log.warn("Gateway field {} is not numeric: {}", snakeName, value.asText());
            return null;
        }
        long parsed = value.longValue();
        // Post ids are positive; 0 or negative means the model hallucinated one
        if (parsed < 1) {
            log.warn("Gateway field {} is out of range: {}", snakeName, parsed);
            return null;
        }
        return parsed;
    }

    private JsonNode pick(JsonNode node, String snakeName, String camelName) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode value = node.has(snakeName) ? node.path(snakeName) : node.path(camelName);
        return !value.isMissingNode() && !value.isNull() ? value : null;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    /**
     * Convert LLMResponse to AgentActionDecision
     *
     * After calling Python gateway, convert the parsed response to decision object.
     */
    public AgentActionDecision convertToDecision(LLMResponse llmResponse) {
        List<AgentActionDecision> decisions = convertToDecisions(llmResponse);
        if (!decisions.isEmpty()) {
            return decisions.get(0);
        }
        return AgentActionDecision.builder()
                .action(ActionType.IGNORE)
                .build();
    }

    /**
     * Convert LLMResponse to validated multi-action decisions.
     */
    public List<AgentActionDecision> convertToDecisions(LLMResponse llmResponse) {
        if (llmResponse == null || !Boolean.TRUE.equals(llmResponse.getSuccess())) {
            return List.of(AgentActionDecision.builder().action(ActionType.IGNORE).build());
        }

        List<AgentActionDecision> source = llmResponse.getActions();
        if (source == null || source.isEmpty()) {
            source = List.of(AgentActionDecision.builder()
                    .action(llmResponse.getAction())
                    .targetPostId(llmResponse.getTargetPostId())
                    .content(llmResponse.getParsedContent())
                    .build());
        }

        List<AgentActionDecision> decisions = new ArrayList<>();
        Set<Long> likedTargets = new HashSet<>();
        Set<Long> dislikedTargets = new HashSet<>();
        for (AgentActionDecision decision : source) {
            if (decisions.size() >= 3) {
                break;
            }
            if (decision == null || !decision.isValid()) {
                log.warn("Invalid action decision from Python gateway: {}", decision);
                continue;
            }
            if (decision.getAction() == ActionType.LIKE && dislikedTargets.contains(decision.getTargetPostId())) {
                log.warn("Dropping conflicting like action on post {}", decision.getTargetPostId());
                continue;
            }
            if (decision.getAction() == ActionType.DISLIKE && likedTargets.contains(decision.getTargetPostId())) {
                log.warn("Dropping conflicting dislike action on post {}", decision.getTargetPostId());
                continue;
            }
            if (decision.getAction() == ActionType.LIKE) {
                likedTargets.add(decision.getTargetPostId());
            } else if (decision.getAction() == ActionType.DISLIKE) {
                dislikedTargets.add(decision.getTargetPostId());
            }
            decisions.add(decision);
        }

        boolean hasCreateBounty = decisions.stream()
                .anyMatch(d -> d.getAction() == ActionType.CREATE_BOUNTY);
        if (hasCreateBounty) {
            decisions.removeIf(d -> d.getAction() == ActionType.POST);
            log.info("Removed POST actions because CREATE_BOUNTY is present (bounty self-announces)");
        }

        if (decisions.isEmpty()) {
            return List.of(AgentActionDecision.builder().action(ActionType.IGNORE).build());
        }
        return decisions;
    }
}
