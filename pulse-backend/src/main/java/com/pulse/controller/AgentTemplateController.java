package com.pulse.controller;

import com.pulse.config.AgentTemplateCatalog;
import com.pulse.config.PlatformLlmProperties;
import com.pulse.dto.response.AgentTemplateListResponse;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.PlatformLlmInfoResponse;
import com.pulse.service.support.LlmCredentialResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Agent Template Controller
 *
 * GET /api/v1/agents/templates - the built-in personas, plus whether the platform-hosted
 * model may be chosen. Requires a login: it is one half of the create form, and there is
 * nothing for a guest to do with it.
 *
 * <p>Its own controller rather than a method on AgentController, for the reason
 * documented on {@code AgentRankingController}: AgentController maps
 * GET /api/v1/agents/{agent_id} onto a Long, so "templates" and that path variable both
 * match this URL. Spring MVC prefers the literal segment, and
 * {@code AgentTemplateRoutingTest} pins that - a routing accident would otherwise show up
 * in production as a type-conversion 400 on an endpoint that looks fine in isolation.
 *
 * <p>No SecurityConfig change is needed: nothing here is in a permitAll matcher, so it
 * falls through to anyRequest().authenticated().
 */
@Tag(name = "Agent Lab", description = "Agent 人设模板与平台模型信息")
@RestController
@RequestMapping("/api/v1/agents/templates")
@RequiredArgsConstructor
public class AgentTemplateController {

    private final AgentTemplateCatalog agentTemplateCatalog;
    private final PlatformLlmProperties platformLlmProperties;
    private final LlmCredentialResolver credentialResolver;

    /**
     * List the persona templates and report the platform model's terms.
     */
    @Operation(summary = "获取 Agent 人设模板与平台模型信息",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping
    public ApiResponse<AgentTemplateListResponse> getTemplates() {
        return ApiResponse.success(AgentTemplateListResponse.builder()
                .templates(agentTemplateCatalog.getTemplates())
                .platformLlm(describePlatformLlm())
                .build());
    }

    /**
     * The platform model, as a client may see it.
     *
     * Assembled field by field rather than mapped from the properties bean: the key and
     * the base URL live on that bean, and any form of automatic copying would put one of
     * them on the wire the day somebody added a field. When the model is unavailable the
     * numbers are omitted entirely - quoting a price for something that cannot be bought
     * only invites a client to render the option anyway.
     */
    private PlatformLlmInfoResponse describePlatformLlm() {
        boolean enabled = credentialResolver.isPlatformAvailable();
        if (!enabled) {
            return PlatformLlmInfoResponse.builder()
                    .enabled(false)
                    .build();
        }
        return PlatformLlmInfoResponse.builder()
                .enabled(true)
                .modelName(platformLlmProperties.getModelName())
                .pointsPer1kTokens(scaled(platformLlmProperties.effectivePointsPer1kTokens()))
                .dailyTokenCapPerAgent(platformLlmProperties.getDailyTokenCapPerAgent())
                .minPointsToWake(scaled(platformLlmProperties.effectiveMinPointsToWake()))
                .build();
    }

    /**
     * Two decimals, the precision every points figure in this system carries.
     */
    private BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
