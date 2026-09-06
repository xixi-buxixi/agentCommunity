package com.pulse.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.service.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which requests the IP limiter covers, and what it does when Redis cannot answer.
 *
 * The two anonymous GET boards were added after the fact: the leaderboard's cache miss
 * is a multi-table aggregate over the whole comments table and the public profile is a
 * fixed eight queries that no cache absorbs, so an unthrottled crawler on either one is
 * a database load generator that needs no account. They are also the product's
 * enumeration surface (404 vs 200 per agent id, plus the owner's username on the
 * board), which the same ceiling bounds.
 */
class RateLimitFilterTest {

    private RateLimitService rateLimitService;
    private FilterChain chain;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        rateLimitService = mock(RateLimitService.class);
        chain = mock(FilterChain.class);
        filter = new RateLimitFilter(rateLimitService, new ObjectMapper());
        // @Value is not applied outside a context, and the field is read unguarded
        ReflectionTestUtils.setField(filter, "trustedProxies", "");
        when(rateLimitService.tryConsume(anyString(), anyString(), anyInt(), any(Duration.class)))
                .thenReturn(true);
    }

    // ========== Covered endpoints ==========

    @Test
    void theAgentLeaderboardIsRateLimitedPerIp() throws Exception {
        doFilter("GET", "/api/v1/agents/ranking", "203.0.113.9");

        verify(rateLimitService).tryConsume("agent-ranking:ip", "203.0.113.9", 60,
                Duration.ofMinutes(1));
    }

    @Test
    void theQueryStringDoesNotEscapeTheLeaderboardRule() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents/ranking");
        request.setQueryString("type=tipped&limit=50");
        request.setRemoteAddr("203.0.113.9");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryConsume(eq("agent-ranking:ip"), anyString(), anyInt(),
                any(Duration.class));
    }

    @Test
    void thePublicProfileIsRateLimitedPerIp() throws Exception {
        doFilter("GET", "/api/v1/agents/42/profile", "203.0.113.9");

        verify(rateLimitService).tryConsume("agent-profile:ip", "203.0.113.9", 60,
                Duration.ofMinutes(1));
    }

    /**
     * The two boards share a ceiling but not a bucket: hammering the profile must not
     * be able to lock a reader out of the leaderboard.
     */
    @Test
    void theTwoAnonymousBoardsUseSeparateBuckets() throws Exception {
        doFilter("GET", "/api/v1/agents/ranking", "203.0.113.9");
        doFilter("GET", "/api/v1/agents/42/profile", "203.0.113.9");

        verify(rateLimitService).tryConsume(eq("agent-ranking:ip"), anyString(), anyInt(), any());
        verify(rateLimitService).tryConsume(eq("agent-profile:ip"), anyString(), anyInt(), any());
    }

    @Test
    void anExhaustedBucketAnswers429WithRetryAfter() throws Exception {
        when(rateLimitService.tryConsume(eq("agent-ranking:ip"), anyString(), anyInt(), any()))
                .thenReturn(false);
        when(rateLimitService.retryAfterSeconds(eq("agent-ranking:ip"), anyString(), any()))
                .thenReturn(37L);

        MockHttpServletResponse response = doFilter("GET", "/api/v1/agents/ranking", "203.0.113.9");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        // The project envelope, not Spring's default body
        assertThat(response.getContentAsString()).contains("\"code\"");
        verifyNoInteractions(chain);
    }

    /**
     * Redis down is not "everyone is over the limit": the service fails open, and the
     * filter must not add a closed door of its own.
     */
    @Test
    void aRateLimiterOutageStillLetsTheRequestThrough() throws Exception {
        when(rateLimitService.tryConsume(anyString(), anyString(), anyInt(), any()))
                .thenReturn(true);

        MockHttpServletResponse response = doFilter("GET", "/api/v1/agents/ranking", "203.0.113.9");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    // ========== Percent-encoded spellings of a covered path ==========

    /**
     * The rules match the DECODED path, because that is what Spring MVC routes on.
     *
     * request.getRequestURI() is the raw URI, so /api/v1/agents/%72anking used to reach
     * the leaderboard controller while matching no rule at all: one encoded character
     * per request, and the ceiling was gone. Every rule shared the code, so the login
     * rule was bypassable the same way - which is the one the BCrypt comment above
     * depends on.
     */
    @Test
    void anEncodedLeaderboardPathHitsTheSameRule() throws Exception {
        doFilter("GET", "/api/v1/agents/%72anking", "203.0.113.9");

        verify(rateLimitService).tryConsume("agent-ranking:ip", "203.0.113.9", 60,
                Duration.ofMinutes(1));
    }

    @Test
    void anEncodedProfilePathHitsTheSameRule() throws Exception {
        doFilter("GET", "/api/v1/agents/42/%70rofile", "203.0.113.9");
        doFilter("GET", "/api/v1/agents/42/pro%66ile", "203.0.113.9");

        verify(rateLimitService, times(2)).tryConsume("agent-profile:ip", "203.0.113.9", 60,
                Duration.ofMinutes(1));
    }

    @Test
    void anEncodedLoginPathHitsTheSameRule() throws Exception {
        doFilter("POST", "/api/v1/auth/%6Cogin", "203.0.113.9");

        verify(rateLimitService).tryConsume("login:ip", "203.0.113.9", 20, Duration.ofMinutes(5));
    }

    /**
     * Traversal segments and doubled slashes are resolved before the match, so a path
     * that still routes cannot dodge a rule by spelling itself differently.
     */
    @Test
    void aNormalizedPathHitsTheSameRule() throws Exception {
        doFilter("GET", "/api/v1/posts/../agents/ranking", "203.0.113.9");
        doFilter("GET", "/api/v1//agents/ranking", "203.0.113.9");

        verify(rateLimitService, times(2)).tryConsume(eq("agent-ranking:ip"), anyString(),
                anyInt(), any());
    }

    /**
     * Decoding happens ONCE, exactly as Spring does it: %2570rofile decodes to
     * %70rofile, which is not a path any controller answers either. Matching it as the
     * profile rule would be a limiter that invents endpoints.
     */
    @Test
    void aDoubleEncodedPathIsNotTreatedAsTheDecodedOne() throws Exception {
        doFilter("GET", "/api/v1/agents/42/%2570rofile", "203.0.113.9");

        verifyNoInteractions(rateLimitService);
    }

    /**
     * An undecodable path cannot be bucketed, and "cannot decide" must not mean "let it
     * through" - that is the bypass shape all over again.
     */
    @Test
    void aMalformedEscapeSequenceIsRejected() throws Exception {
        MockHttpServletResponse response =
                doFilter("GET", "/api/v1/agents/%zzanking", "203.0.113.9");

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\"");
        verifyNoInteractions(chain);
        verifyNoInteractions(rateLimitService);
    }

    // ========== Endpoints the rules must NOT swallow ==========

    /**
     * A single-segment wildcard, so the rule covers the profile and nothing deeper or
     * shallower - the private agent endpoints keep their own authorisation path rather
     * than picking up an anonymous IP bucket.
     */
    @Test
    void theProfileRuleDoesNotMatchTheOtherAgentEndpoints() throws Exception {
        doFilter("GET", "/api/v1/agents/42", "203.0.113.9");
        doFilter("GET", "/api/v1/agents/42/logs", "203.0.113.9");
        doFilter("GET", "/api/v1/agents/42/memories", "203.0.113.9");
        doFilter("GET", "/api/v1/agents/logs", "203.0.113.9");
        doFilter("GET", "/api/v1/agents", "203.0.113.9");

        verifyNoInteractions(rateLimitService);
    }

    /**
     * The rules are per method: a POST to the same path is a different endpoint and is
     * not covered by the anonymous GET ceiling.
     */
    @Test
    void aPostToTheSamePathIsNotCoveredByTheGetRule() throws Exception {
        doFilter("POST", "/api/v1/agents/ranking", "203.0.113.9");

        verifyNoInteractions(rateLimitService);
    }

    @Test
    void theExistingPostRulesStillApply() throws Exception {
        doFilter("POST", "/api/v1/auth/login", "203.0.113.9");

        verify(rateLimitService).tryConsume("login:ip", "203.0.113.9", 20, Duration.ofMinutes(5));
        verify(rateLimitService, never()).tryConsume(eq("agent-ranking:ip"), anyString(), anyInt(), any());
    }

    // ========== Client identity ==========

    /**
     * The bucket key may not be caller-supplied: an untrusted peer's X-Forwarded-For is
     * ignored, or rotating it would defeat the limit entirely.
     */
    @Test
    void aForwardedHeaderFromAnUntrustedPeerDoesNotChooseTheBucket() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents/ranking");
        request.setRemoteAddr("203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryConsume(anyString(), eq("203.0.113.9"), anyInt(), any());
    }

    @Test
    void aForwardedHeaderFromTheLocalProxyChoosesTheBucket() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/agents/ranking");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        filter.doFilter(request, new MockHttpServletResponse(), chain);

        verify(rateLimitService).tryConsume(anyString(), eq("1.2.3.4"), anyInt(), any());
    }

    // ========== Fixtures ==========

    private MockHttpServletResponse doFilter(String method, String uri, String remoteAddr)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }
}
