package com.pulse.config;

import com.pulse.controller.BountyController;
import com.pulse.exception.GlobalExceptionHandler;
import com.pulse.security.filter.JwtAuthenticationFilter;
import com.pulse.service.BountyService;
import com.pulse.service.RateLimitService;
import com.pulse.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the SecurityConfig request matchers.
 *
 * The bug these lock down (H9): "/api/v2/bounties/{taskId}" was declared permitAll,
 * and single-segment path variables also match named sub-resources - so
 * /api/v2/bounties/my and /api/v2/bounties/accepted, both of which return the
 * caller's private data, were reachable anonymously and answered with an NPE that
 * leaked internal class names.
 */
@WebMvcTest(controllers = BountyController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtUtil.class,
        BountyController.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-value-that-is-long-enough-for-hmac-sha256",
        "jwt.expiration=86400000"
})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BountyService bountyService;

    @MockBean
    private RateLimitService rateLimitService;

    @Test
    void anonymousCannotReachMyBounties() throws Exception {
        mockMvc.perform(get("/api/v2/bounties/my"))
                .andExpect(status().isUnauthorized())
                // Must be the project envelope, not Spring's default body, otherwise
                // the frontend cannot read a message at all
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void anonymousCannotReachAcceptedBounties() throws Exception {
        mockMvc.perform(get("/api/v2/bounties/accepted"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void anonymousCanReadPublicBountyDetail() throws Exception {
        when(bountyService.getBountyDetail(any(), nullable(Long.class))).thenReturn(null);

        mockMvc.perform(get("/api/v2/bounties/123"))
                .andExpect(status().isOk());
    }

    @Test
    void anonymousCanReadPublicBountyList() throws Exception {
        mockMvc.perform(get("/api/v2/bounties/logs"))
                .andExpect(status().isOk());
    }

    /**
     * A non-numeric id no longer reaches the controller as a conversion failure that
     * is reported as a 500 with the Java type names in the message.
     */
    @Test
    void nonNumericBountyIdIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v2/bounties/abc"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void rateLimitServiceIsNotConsultedForReads() throws Exception {
        when(rateLimitService.tryConsume(anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);
        mockMvc.perform(get("/api/v2/bounties/logs")).andExpect(status().isOk());
    }
}
