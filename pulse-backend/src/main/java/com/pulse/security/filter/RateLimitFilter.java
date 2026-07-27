package com.pulse.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.response.ApiResponse;
import com.pulse.exception.ErrorCode;
import com.pulse.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * IP-based rate limiting for unauthenticated, abuse-prone endpoints.
 *
 * Runs before the Spring Security chain (HIGHEST_PRECEDENCE) so that credential
 * stuffing is rejected before any password hashing happens - BCrypt verification is
 * deliberately expensive, which makes an unthrottled login endpoint a CPU
 * amplification target as well as an account-takeover one.
 *
 * Per-account limits live in AuthServiceImpl, and per-user limits (tipping) in the
 * services, because only they know the identity behind the request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private record Rule(String method, String pathPattern, String bucket, int limit, Duration window) {}

    private static final List<Rule> RULES = List.of(
            new Rule("POST", "/api/v1/auth/login", "login:ip", 20, Duration.ofMinutes(5)),
            new Rule("POST", "/api/v1/auth/register", "register:ip", 5, Duration.ofHours(1)),
            new Rule("POST", "/api/v1/hot-news/ingest", "ingest:ip", 30, Duration.ofHours(1))
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Rule rule = matchRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        if (!rateLimitService.tryConsume(rule.bucket(), clientIp, rule.limit(), rule.window())) {
            long retryAfter = rateLimitService.retryAfterSeconds(rule.bucket(), clientIp, rule.window());
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.error(ErrorCode.RATE_LIMIT_EXCEEDED.getCode(),
                            ErrorCode.RATE_LIMIT_EXCEEDED.getMessage()));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private Rule matchRule(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        for (Rule rule : RULES) {
            if (rule.method().equalsIgnoreCase(method) && pathMatcher.match(rule.pathPattern(), path)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Client IP behind the nginx reverse proxy.
     *
     * X-Forwarded-For is caller-controlled, so only the FIRST entry is used and only
     * as a bucketing hint; the value is never trusted for authorization.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
