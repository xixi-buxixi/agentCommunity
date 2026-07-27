package com.pulse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.dto.response.ApiResponse;
import com.pulse.exception.ErrorCode;
import com.pulse.security.filter.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security Configuration
 *
 * Configures JWT-based authentication with stateless session management.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    /**
     * Password encoder for user passwords (BCrypt)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Security filter chain configuration
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF (stateless JWT authentication)
            .csrf(AbstractHttpConfigurer::disable)

            // Stateless session (no session storage)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // Public endpoints (no auth required)
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/hot-news/ingest",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/error",
                    // Liveness for the deploy pipeline; details are never exposed
                    // (management.endpoint.health.show-details=never)
                    "/actuator/health",
                    "/actuator/health/**"
                ).permitAll()

                // Private bounty endpoints. Declared BEFORE the guest whitelist because
                // matchers are evaluated in order: "/api/v2/bounties/{taskId}" would
                // otherwise swallow these two paths and expose them anonymously.
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/api/v2/bounties/my",
                    "/api/v2/bounties/accepted"
                ).authenticated()

                // Guest read-only access: community posts, bounties, ranking.
                // Numeric constraints keep single-segment wildcards from matching
                // named sub-resources such as /my or /accepted.
                .requestMatchers(
                    org.springframework.http.HttpMethod.GET,
                    "/api/v1/posts",
                    "/api/v1/posts/{postId:[0-9]+}",
                    "/api/v1/posts/{postId:[0-9]+}/comments",
                    "/api/v1/posts/ranking",
                    "/api/v2/bounties",
                    "/api/v2/bounties/{taskId:[0-9]+}",
                    "/api/v2/bounties/logs",
                    "/api/v2/bounties/{taskId:[0-9]+}/logs",
                    "/api/v1/hot-news/latest",
                    "/api/v1/hot-news/{reportId:[0-9]+}"
                ).permitAll()

                // All other endpoints require authentication
                .anyRequest().authenticated()
            )

            // Authentication/authorization failures must use the same ApiResponse
            // envelope as every other error, otherwise the frontend cannot read
            // them (Spring's default body has no "message" field).
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(this::writeUnauthorized)
                .accessDeniedHandler((request, response, deniedException) -> writeForbidden(request, response)))

            // Add JWT filter before UsernamePasswordAuthenticationFilter
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeUnauthorized(HttpServletRequest request, HttpServletResponse response,
                                   org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    private void writeError(HttpServletResponse response, int status, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                ApiResponse.error(errorCode.getCode(), errorCode.getMessage()));
    }
}
