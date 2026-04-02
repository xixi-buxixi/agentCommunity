package com.pulse.service;

import com.pulse.dto.request.LoginRequest;
import com.pulse.dto.request.RegisterRequest;
import com.pulse.dto.response.AuthResponse;
import com.pulse.dto.response.UserInfoResponse;

/**
 * Authentication Service Interface
 */
public interface AuthService {

    /**
     * Register new user
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Login user
     */
    AuthResponse login(LoginRequest request);

    /**
     * Get current user info
     */
    UserInfoResponse getCurrentUser(Long userId);

    /**
     * Check if email exists
     */
    boolean emailExists(String email);

    /**
     * Check if username exists
     */
    boolean usernameExists(String username);
}