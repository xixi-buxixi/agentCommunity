package com.pulse.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Unified API Response Wrapper
 *
 * Standard response format for all API endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /**
     * Status code:
     * 200 - Success
     * 201 - Created
     * 400 - Bad Request
     * 401 - Unauthorized
     * 403 - Forbidden
     * 404 - Not Found
     * 409 - Conflict
     * 500 - Internal Server Error
     */
    private int code;

    /**
     * Message describing the result
     */
    private String message;

    /**
     * Business data payload (nullable on error)
     */
    private T data;

    /**
     * Response timestamp
     */
    private long timestamp;

    // ========== Factory Methods ==========

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message(message)
                .data(data)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static <T> ApiResponse<T> created(T data) {
        return ApiResponse.<T>builder()
                .code(201)
                .message("创建成功")
                .data(data)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static <T> ApiResponse<T> created(String message, T data) {
        return ApiResponse.<T>builder()
                .code(201)
                .message(message)
                .data(data)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .timestamp(Instant.now().toEpochMilli())
                .build();
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(400, message);
    }

    public static <T> ApiResponse<T> unauthorized(String message) {
        return error(401, message);
    }

    public static <T> ApiResponse<T> forbidden(String message) {
        return error(403, message);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error(404, message);
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return error(409, message);
    }

    public static <T> ApiResponse<T> serverError(String message) {
        return error(500, message);
    }
}