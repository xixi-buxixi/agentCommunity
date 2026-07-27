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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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

    /**
     * Comma-separated proxy addresses whose X-Forwarded-For may be trusted.
     * Empty means "loopback and private ranges".
     */
    @Value("${pulse.trusted-proxies:}")
    private String trustedProxies;

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
     * Client IP for rate-limit bucketing.
     *
     * Forwarding headers are only honoured when the request actually arrived from a
     * trusted proxy, and then the LAST entry is used - that is the value our own
     * proxy appended. Taking the first entry would hand the bucket key to the
     * caller: X-Forwarded-For is client-supplied, so rotating it defeats the limit
     * entirely. (The nginx config also overwrites the header rather than appending,
     * so normally there is exactly one value.)
     */
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr != null ? remoteAddr : "unknown";
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!hop.isEmpty()) {
                    return hop;
                }
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    /**
     * Whether forwarding headers from this peer may be believed.
     *
     * Defaults to loopback plus the private ranges, which covers "nginx on the same
     * host" and "nginx on the same private network". Override with
     * pulse.trusted-proxies when the reverse proxy sits on a public address.
     */
    private boolean isTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        if (!trustedProxies.isBlank()) {
            for (String candidate : trustedProxies.split(",")) {
                if (remoteAddr.equals(candidate.trim())) {
                    return true;
                }
            }
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
