package com.pulse.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authenticated User Context
 *
 * Stores user authentication information extracted from JWT.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPrincipal {

    private Long userId;
    private String username;
    private String email;

    /**
     * Create from JWT claims
     */
    public static UserPrincipal fromClaims(Long userId, String username, String email) {
        return UserPrincipal.builder()
                .userId(userId)
                .username(username)
                .email(email)
                .build();
    }
}