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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

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
            new Rule("POST", "/api/v1/hot-news/ingest", "ingest:ip", 30, Duration.ofHours(1)),
            // The two anonymous GET boards. Both are cached, but a cache miss on the
            // leaderboard is a multi-table aggregate over the whole comments table, and
            // the profile is a fixed eight queries per hit that no cache absorbs - so an
            // unthrottled crawler turns either one into a database load generator. The
            // profile is also an enumeration surface (404 vs 200 per id, and the owner's
            // username on the leaderboard), which the same ceiling bounds.
            //
            // 60/minute per IP: far above any human reading the page (the frontend loads
            // each once per view), far below what a scraper wants.
            new Rule("GET", "/api/v1/agents/ranking", "agent-ranking:ip", 60, Duration.ofMinutes(1)),
            // Single-segment wildcard, so it covers /agents/{id}/profile and nothing
            // deeper.
            new Rule("GET", "/api/v1/agents/*/profile", "agent-profile:ip", 60, Duration.ofMinutes(1))
    );

    /**
     * Comma-separated proxy addresses whose X-Forwarded-For may be trusted.
     * Empty means "loopback and private ranges".
     */
    @Value("${pulse.trusted-proxies:}")
    private String trustedProxies;

    /**
     * Decoding path helper, configured exactly like the one Spring MVC and Spring
     * Security use: percent-decode once, strip the context path, drop path parameters.
     */
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    /** Collapses the repeated slashes a normalized path must not keep. */
    private static final Pattern DUPLICATE_SLASHES = Pattern.compile("/{2,}");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path;
        try {
            path = resolvePath(request);
        } catch (IllegalArgumentException e) {
            // A path this filter cannot decode is a path it cannot bucket either, and
            // "cannot decide" must not mean "let it through": that is exactly the shape
            // of the bypass this method exists to close. Nothing downstream can serve
            // such a URI anyway.
            logger.warn("Rejecting a request whose path cannot be decoded: " + e.getMessage());
            writeError(response, ErrorCode.INVALID_PARAMETER, null);
            return;
        }

        Rule rule = matchRule(request.getMethod(), path);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);
        if (!rateLimitService.tryConsume(rule.bucket(), clientIp, rule.limit(), rule.window())) {
            long retryAfter = rateLimitService.retryAfterSeconds(rule.bucket(), clientIp, rule.window());
            writeError(response, ErrorCode.RATE_LIMIT_EXCEEDED, retryAfter);
            return;
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The path the rules are matched against.
     *
     * NOT {@code request.getRequestURI()}: per the Servlet spec that value is the raw,
     * still percent-encoded URI, while Spring MVC routes on the decoded path. The two
     * disagree on every encoded request, so GET /api/v1/agents/%72anking reached the
     * leaderboard controller while matching no rule here - one encoded character per
     * request bought an attacker unlimited quota, on the login rule as much as on the
     * anonymous boards.
     *
     * Decoding once is the right amount: Spring decodes once too, so a double-encoded
     * "%2570rofile" stays "%70rofile" on both sides and reaches no controller either.
     * The result is then normalized - "." / ".." segments resolved, repeated slashes
     * collapsed - so a path that Tomcat would still route cannot slip a rule by
     * spelling itself differently. Normalization only ever makes MORE paths match a
     * rule, which is the safe direction for a limiter.
     *
     * @throws IllegalArgumentException when the URI carries a malformed escape sequence
     */
    private String resolvePath(HttpServletRequest request) {
        String decoded = PATH_HELPER.getPathWithinApplication(request);
        if (decoded == null || decoded.isEmpty()) {
            return "/";
        }
        String cleaned = StringUtils.cleanPath(decoded);
        return DUPLICATE_SLASHES.matcher(cleaned).replaceAll("/");
    }

    private Rule matchRule(String method, String path) {
        for (Rule rule : RULES) {
            if (rule.method().equalsIgnoreCase(method) && pathMatcher.match(rule.pathPattern(), path)) {
                return rule;
            }
        }
        return null;
    }

    private void writeError(HttpServletResponse response, ErrorCode error, Long retryAfterSeconds)
            throws IOException {
        response.setStatus(error.getHttpStatus());
        if (retryAfterSeconds != null) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(error.getCode(), error.getMessage()));
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
