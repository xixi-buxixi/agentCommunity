package com.pulse.controller;

import com.pulse.dto.request.LoginRequest;
import com.pulse.dto.request.RegisterRequest;
import com.pulse.dto.response.ApiResponse;
import com.pulse.dto.response.AuthResponse;
import com.pulse.dto.response.UserInfoResponse;
import com.pulse.security.UserPrincipal;
import com.pulse.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Authentication Controller
 *
 * Handles user registration, login, and user info endpoints.
 */
@Tag(name = "Authentication", description = "User authentication APIs")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * User Registration
     */
    @Operation(summary = "Register new user")
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ApiResponse.created("注册成功", response);
    }

    /**
     * User Login
     */
    @Operation(summary = "User login")
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ApiResponse.success("登录成功", response);
    }

    /**
     * Get Current User Info
     */
    @Operation(summary = "Get current user info", security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/me")
    public ApiResponse<UserInfoResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        UserInfoResponse response = authService.getCurrentUser(principal.getUserId());
        return ApiResponse.success(response);
    }
}