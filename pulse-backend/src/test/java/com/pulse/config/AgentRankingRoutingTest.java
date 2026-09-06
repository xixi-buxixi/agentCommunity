package com.pulse.config;

import com.pulse.controller.AgentController;
import com.pulse.controller.AgentRankingController;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.exception.GlobalExceptionHandler;
import com.pulse.service.AgentRankingService;
import com.pulse.service.AgentProfileService;
import com.pulse.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Routing regression test for GET /api/v1/agents/ranking.
 *
 * AgentController maps GET /api/v1/agents/{agent_id} onto a Long, so the literal
 * segment "ranking" and that path variable both match the same URL. Spring MVC is
 * expected to prefer the literal pattern - but "expected to" is exactly the kind of
 * assumption that turns into a 400 on a public endpoint after a mapping is reordered
 * or a controller is renamed. Both controllers are registered in one slice here and
 * the assertion is made on the resolved handler itself, not on the status code, so a
 * regression names the wrong controller instead of leaving a bare 400 to interpret.
 *
 * Security filters are deliberately off: this test is about handler selection. The
 * matching SecurityConfig entry (the guest read-only group) is verified separately.
 */
@WebMvcTest(controllers = {AgentRankingController.class, AgentController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import({AgentRankingController.class, AgentController.class, GlobalExceptionHandler.class})
class AgentRankingRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentRankingService agentRankingService;

    @MockBean
    private AgentService agentService;

    // AgentController also depends on the public-profile service (added with the
    // agent public home page); the slice must satisfy every constructor argument.
    @MockBean
    private AgentProfileService agentProfileService;

    @Test
    void rankingPathIsHandledByTheRankingController() throws Exception {
        when(agentRankingService.getAgentRanking(anyString(), anyInt())).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/api/v1/agents/ranking?type=replied&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        assertThat(handlerTypeOf(result)).isEqualTo(AgentRankingController.class);
        verify(agentRankingService).getAgentRanking("replied", 5);
    }

    @Test
    void rankingPathDoesNotReachTheAgentDetailHandler() throws Exception {
        when(agentRankingService.getAgentRanking(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agents/ranking")).andExpect(status().isOk());

        // The failure mode this rules out: "ranking" bound to a Long path variable,
        // which surfaces as a type-conversion 400 rather than a routing error.
        verify(agentService, never()).getAgentDetail(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void defaultsAreAppliedWhenParametersAreOmitted() throws Exception {
        when(agentRankingService.getAgentRanking(anyString(), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/agents/ranking")).andExpect(status().isOk());

        verify(agentRankingService).getAgentRanking("replied", 10);
    }

    @Test
    void invalidTypeIsReportedAsInvalidParameter() throws Exception {
        when(agentRankingService.getAgentRanking(eq("popular"), anyInt()))
                .thenThrow(new BusinessException(ErrorCode.INVALID_PARAMETER));

        mockMvc.perform(get("/api/v1/agents/ranking?type=popular"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_PARAMETER.getCode()));
    }

    /**
     * The other half of the collision: a numeric id must still reach the agent detail
     * handler rather than being swallowed by the new controller.
     */
    @Test
    void numericAgentIdIsStillHandledByTheAgentController() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/agents/123")).andReturn();

        assertThat(handlerTypeOf(result)).isEqualTo(AgentController.class);
        verify(agentRankingService, never()).getAgentRanking(anyString(), anyInt());
    }

    private Class<?> handlerTypeOf(MvcResult result) {
        assertThat(result.getHandler())
                .as("no handler was resolved for the request at all")
                .isInstanceOf(HandlerMethod.class);
        return ((HandlerMethod) result.getHandler()).getBeanType();
    }
}
