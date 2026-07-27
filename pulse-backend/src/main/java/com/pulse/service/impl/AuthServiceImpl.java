package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulse.dto.request.LoginRequest;
import com.pulse.dto.request.RegisterRequest;
import com.pulse.dto.response.AuthResponse;
import com.pulse.dto.response.UserInfoResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.User;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.AuthService;
import com.pulse.service.RateLimitService;
import com.pulse.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Authentication Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /**
     * Login attempts counted per account. The counter is reset on success, so a
     * legitimate user typing a wrong password a few times is unaffected.
     */
    private static final String LOGIN_FAILURE_BUCKET = "login:account";
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final Duration LOGIN_FAILURE_WINDOW = Duration.ofMinutes(15);

    private final UserMapper userMapper;
    private final AgentMapper agentMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RateLimitService rateLimitService;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if email exists
        if (emailExists(request.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        // Check if username exists
        if (usernameExists(request.getUsername())) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }

        // Create user
        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        userMapper.insert(user);

        // Generate token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getEmail());

        log.info("User registered: userId={}, username={}", user.getId(), user.getUsername());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        String account = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";

        // Per-account throttle, complementing the per-IP limit in RateLimitFilter:
        // a distributed attempt from many IPs against one account is invisible to
        // an IP-only counter.
        if (!rateLimitService.tryConsume(LOGIN_FAILURE_BUCKET, account,
                MAX_LOGIN_FAILURES, LOGIN_FAILURE_WINDOW)) {
            log.warn("Login temporarily locked after repeated failures: account={}", account);
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }

        // Find user by email
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, request.getEmail());
        User user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // Successful login clears the failure budget for this account
        rateLimitService.reset(LOGIN_FAILURE_BUCKET, account);

        // Generate token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getEmail());

        log.info("User logged in: userId={}, username={}", user.getId(), user.getUsername());

        return AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .build();
    }

    @Override
    public UserInfoResponse getCurrentUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Count agents
        LambdaQueryWrapper<Agent> agentQuery = new LambdaQueryWrapper<>();
        agentQuery.eq(Agent::getOwnerId, userId);
        Long agentCount = agentMapper.selectCount(agentQuery);

        return UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(formatDateTime(user.getCreatedAt()))
                .agentCount(agentCount.intValue())
                .build();
    }

    @Override
    public boolean emailExists(String email) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getEmail, email);
        return userMapper.selectCount(queryWrapper) > 0;
    }

    @Override
    public boolean usernameExists(String username) {
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, username);
        return userMapper.selectCount(queryWrapper) > 0;
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
        return dateTime.format(formatter);
    }
}