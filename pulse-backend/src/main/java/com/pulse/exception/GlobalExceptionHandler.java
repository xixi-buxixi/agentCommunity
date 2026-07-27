package com.pulse.exception;

import com.pulse.dto.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Global Exception Handler
 *
 * Two rules hold for every branch here:
 * 1. Responses carry the real HTTP status (never a blanket 200), so gateways,
 *    monitoring and APM can see the error rate.
 * 2. Internal details (SQL text, table/index names, class names, internal
 *    hostnames) never reach the client. They go to the log, correlated with a
 *    traceId that is echoed to the caller for support lookups.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String GENERIC_MESSAGE = "系统内部错误，请稍后重试";

    /**
     * Handle Business Exception - the only branch allowed to echo its own message,
     * because business messages are authored by us and contain no internals.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        int status = ErrorCode.httpStatusOf(e.getCode());

        // A business code that maps to 5xx describes an internal failure, and callers
        // attach detail to those (e.g. "hot news report id missing after insert").
        // Only 4xx messages - the ones written for the user - are echoed.
        if (status >= 500) {
            String traceId = UUID.randomUUID().toString().substring(0, 8);
            log.error("BusinessException [traceId={}]: code={}, status={}, message={}",
                    traceId, e.getCode(), status, e.getMessage());
            return ResponseEntity
                    .status(status)
                    .body(ApiResponse.error(e.getCode(),
                            GENERIC_MESSAGE + " (traceId=" + traceId + ")"));
        }

        log.warn("BusinessException: code={}, status={}, message={}", e.getCode(), status, e.getMessage());
        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    /**
     * Handle @Valid body / form binding failures
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationException(BindException e) {
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            errors.put(fieldName, error.getDefaultMessage());
        });
        log.warn("ValidationException: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .code(ErrorCode.INVALID_PARAMETER.getCode())
                        .message("参数验证失败")
                        .data(errors)
                        .timestamp(System.currentTimeMillis())
                        .build());
    }

    /**
     * Handle @Validated parameter constraint failures
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        log.warn("ConstraintViolationException: {}", e.getMessage());
        return badRequest("参数验证失败");
    }

    /**
     * Handle path/query parameter type mismatch, e.g. /posts/abc where a Long is expected.
     * The raw conversion message names internal Java types, so it is logged, not returned.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("TypeMismatch: parameter={}, requiredType={}", e.getName(),
                e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "unknown");
        return badRequest("参数格式错误: " + e.getName());
    }

    /**
     * Handle missing required request parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("MissingParameter: {}", e.getParameterName());
        return badRequest("缺少必要参数: " + e.getParameterName());
    }

    /**
     * Handle malformed request body (bad JSON)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadable: {}", e.getMessage());
        return badRequest("请求体格式错误");
    }

    /**
     * Handle wrong HTTP method
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("MethodNotSupported: {}", e.getMethod());
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER.getCode(), "请求方法不支持"));
    }

    /**
     * Handle unknown path
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("NoResourceFound: {}", e.getResourcePath());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "资源不存在"));
    }

    /**
     * Handle unique-key conflicts as real conflicts instead of leaking
     * "Duplicate entry 'alice' for key 'users.username'" as a 500.
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("DuplicateKeyException: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.RESOURCE_CONFLICT.getCode(),
                        ErrorCode.RESOURCE_CONFLICT.getMessage()));
    }

    /**
     * Handle other data integrity violations (FK, not-null, ...)
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolationException: {}", e.getMostSpecificCause().getMessage());
        return badRequest("数据校验失败");
    }

    /**
     * Handle authorization failure raised inside controller/service code
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("AccessDenied: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage()));
    }

    /**
     * Handle authentication failure raised inside controller/service code
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException e) {
        log.warn("AuthenticationException: {}", e.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage()));
    }

    /**
     * Handle Generic Exception
     *
     * The exception message is deliberately NOT returned: it routinely contains
     * SQL fragments, table names and internal addresses.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("UnhandledException [traceId={}]: ", traceId, e);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.SYSTEM_ERROR.getCode(),
                        GENERIC_MESSAGE + " (traceId=" + traceId + ")"));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMETER.getCode(), message));
    }
}
