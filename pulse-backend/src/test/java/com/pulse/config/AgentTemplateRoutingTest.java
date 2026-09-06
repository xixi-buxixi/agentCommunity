package com.pulse.config;

import com.pulse.controller.AgentController;
import com.pulse.controller.AgentTemplateController;
import com.pulse.exception.GlobalExceptionHandler;
import com.pulse.service.AgentProfileService;
import com.pulse.service.AgentService;
import com.pulse.service.support.LlmCredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing regression test for GET /api/v1/agents/templates, and the redaction of the
 * platform-model block it returns.
 *
 * Same collision as {@code AgentRankingRoutingTest}: AgentController maps
 * GET /api/v1/agents/{agent_id} onto a Long, so the literal segment "templates" and that
 * path variable both match this URL. Spring MVC prefers the literal pattern - but "is
 * expected to" is exactly the assumption that turns into a 400 after a mapping is
 * reordered. Both controllers are registered in one slice and the assertion is made on
 * the resolved handler, so a regression names the wrong controller instead of leaving a
 * bare 400 to interpret.
 *
 * The second half of the file asserts on the raw response body rather than on the DTO,
 * because the wire format is where a leaked key would actually appear.
 */
@WebMvcTest(controllers = {AgentTemplateController.class, AgentController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({AgentTemplateController.class, AgentController.class, GlobalExceptionHandler.class,
        AgentTemplateCatalog.class})
class AgentTemplateRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    @MockBean
    private AgentProfileService agentProfileService;

    @MockBean
    private LlmCredentialResolver credentialResolver;

    @MockBean
    private PlatformLlmProperties platformLlmProperties;

    @BeforeEach
    void platformIsAvailable() {
        when(credentialResolver.isPlatformAvailable()).thenReturn(true);
        when(platformLlmProperties.getModelName()).thenReturn("platform-model");
        when(platformLlmProperties.effectivePointsPer1kTokens()).thenReturn(BigDecimal.ONE);
        when(platformLlmProperties.getDailyTokenCapPerAgent()).thenReturn(50000L);
        when(platformLlmProperties.effectiveMinPointsToWake()).thenReturn(BigDecimal.ONE);
        // Present on the bean, and expected NOT to reach the response
        when(platformLlmProperties.getApiKey()).thenReturn("sk-platform-secret");
        when(platformLlmProperties.getBaseUrl()).thenReturn("https://platform.example/v1");
    }

    // ========== Routing ==========

    @Test
    void templatesPathIsHandledByTheTemplateController() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/agents/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        assertThat(handlerTypeOf(result)).isEqualTo(AgentTemplateController.class);
    }

    /**
     * The failure mode this rules out: "templates" bound to a Long path variable, which
     * surfaces as a type-conversion 400 rather than as a routing error.
     */
    @Test
    void templatesPathDoesNotReachTheAgentDetailHandler() throws Exception {
        mockMvc.perform(get("/api/v1/agents/templates")).andExpect(status().isOk());

        verify(agentService, never()).getAgentDetail(any(), any());
    }

    /**
     * The other half of the collision: a numeric id must still reach the agent detail
     * handler rather than being swallowed by the new controller.
     */
    @Test
    void numericAgentIdIsStillHandledByTheAgentController() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/agents/123")).andReturn();

        assertThat(handlerTypeOf(result)).isEqualTo(AgentController.class);
    }

    // ========== Payload ==========

    @Test
    void theResponseCarriesTheShippedTemplates() throws Exception {
        mockMvc.perform(get("/api/v1/agents/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.templates.length()").value(6))
                .andExpect(jsonPath("$.data.templates[0].template_id").isNotEmpty())
                .andExpect(jsonPath("$.data.templates[0].system_prompt").isNotEmpty())
                .andExpect(jsonPath("$.data.templates[0].suggested_wake_hours_start").isNumber());
    }

    @Test
    void theAvailablePlatformIsReportedWithItsTermsAndNoSecrets() throws Exception {
        mockMvc.perform(get("/api/v1/agents/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform_llm.enabled").value(true))
                .andExpect(jsonPath("$.data.platform_llm.model_name").value("platform-model"))
                .andExpect(jsonPath("$.data.platform_llm.points_per_1k_tokens").value(1.00))
                .andExpect(jsonPath("$.data.platform_llm.daily_token_cap_per_agent").value(50000))
                .andExpect(jsonPath("$.data.platform_llm.min_points_to_wake").value(1.00))
                // The two fields that must never appear, asserted on the raw body: a
                // field added to the DTO later would show up here.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("sk-platform-secret"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("platform.example"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("api_key"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("base_url"))));
    }

    /**
     * An unavailable platform reports enabled=false and quotes no price: a client that
     * rendered the option anyway would be offering something that cannot be bought.
     */
    @Test
    void anUnavailablePlatformReportsNothingButTheFlag() throws Exception {
        when(credentialResolver.isPlatformAvailable()).thenReturn(false);

        mockMvc.perform(get("/api/v1/agents/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.platform_llm.enabled").value(false))
                .andExpect(jsonPath("$.data.platform_llm.model_name").doesNotExist())
                .andExpect(jsonPath("$.data.platform_llm.points_per_1k_tokens").doesNotExist())
                // The templates are still offered: an owner can always bring their own key
                .andExpect(jsonPath("$.data.templates.length()").value(6));
    }

    private Class<?> handlerTypeOf(MvcResult result) {
        assertThat(result.getHandler())
                .as("no handler was resolved for the request at all")
                .isInstanceOf(HandlerMethod.class);
        return ((HandlerMethod) result.getHandler()).getBeanType();
    }
}
