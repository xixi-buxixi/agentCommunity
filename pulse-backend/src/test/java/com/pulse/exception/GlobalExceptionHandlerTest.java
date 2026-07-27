package com.pulse.exception;

import com.pulse.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for H18.
 *
 * Two properties must hold for every branch:
 * - the HTTP status reflects the failure (a blanket 200 hid every error from
 *   gateways, monitoring and APM, keeping the error rate pinned at zero),
 * - the response body never contains internal detail (SQL text, table or index
 *   names, Java type names, internal addresses).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessExceptionUsesTheStatusDeclaredOnTheErrorCode() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.BOUNTY_NOT_FOUND));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(ErrorCode.BOUNTY_NOT_FOUND.getCode());
        assertThat(response.getBody().getMessage()).isEqualTo(ErrorCode.BOUNTY_NOT_FOUND.getMessage());
    }

    @Test
    void insufficientPointsIsAConflictNotAServerError() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.INSUFFICIENT_VITALITY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void loginFailureIsUnauthorized() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBusinessException(new BusinessException(ErrorCode.LOGIN_FAILED));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingTheMessage() {
        String leak = "Table 'pulse_db.secret_table' doesn't exist; "
                + "Connection refused: localhost/127.0.0.1:8000";

        ResponseEntity<ApiResponse<Void>> response =
                handler.handleException(new IllegalStateException(leak));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String message = response.getBody().getMessage();
        assertThat(message).doesNotContain("pulse_db");
        assertThat(message).doesNotContain("127.0.0.1");
        assertThat(message).doesNotContain("secret_table");
        // A traceId is returned so the real cause can be found in the log
        assertThat(message).contains("traceId=");
    }

    @Test
    void nullPointerIsAlsoASanitized500() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleException(
                new NullPointerException(
                        "Cannot invoke \"com.pulse.security.UserPrincipal.getUserId()\" because \"principal\" is null"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).doesNotContain("UserPrincipal");
        assertThat(response.getBody().getMessage()).doesNotContain("com.pulse");
    }

    @Test
    void duplicateKeyBecomesConflictWithoutTheIndexName() {
        DuplicateKeyException exception = new DuplicateKeyException(
                "Duplicate entry 'alice' for key 'users.username'",
                new SQLException("Duplicate entry 'alice' for key 'users.username'"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleDuplicateKey(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getMessage()).doesNotContain("users.username");
        assertThat(response.getBody().getMessage()).doesNotContain("alice");
    }

    @Test
    void typeMismatchBecomes400WithoutJavaTypeNames() throws Exception {
        MethodArgumentTypeMismatchException exception = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "postId", null,
                new IllegalArgumentException(
                        "Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleTypeMismatch(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).doesNotContain("java.lang");
        assertThat(response.getBody().getMessage()).contains("postId");
    }

    @Test
    void malformedBodyBecomes400() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleUnreadableBody(
                new HttpMessageNotReadableException("Unexpected end-of-input at [Source: (String)\"{\"", (org.springframework.http.HttpInputMessage) null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).doesNotContain("Source:");
    }

    @Test
    void everyDeclaredErrorCodeMapsToASensibleStatus() {
        for (ErrorCode code : ErrorCode.values()) {
            assertThat(code.getHttpStatus())
                    .as("http status for %s", code.name())
                    .isBetween(400, 599);
            assertThat(ErrorCode.httpStatusOf(code.getCode())).isEqualTo(code.getHttpStatus());
        }
        // Unknown codes are treated as client errors, never as 500
        assertThat(ErrorCode.httpStatusOf(123456)).isEqualTo(400);
    }
}
