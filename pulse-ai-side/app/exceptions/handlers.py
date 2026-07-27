"""
Exception Handlers

FastAPI exception handlers that convert exceptions to
structured JSON responses matching Java's expected format.

Improved error handling:
- Differentiate error types with appropriate HTTP status codes
- Log original LLM output on parse failure for debugging
- Provide detailed but secure error messages
"""

import logging
import time
from typing import Callable

from fastapi import Request, FastAPI
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError

from app.exceptions.errors import (
    LLMBaseError,
    LLMTimeoutError,
    LLMAPIError,
    JSONParseError,
    PromptInjectionDetected,
    ValidationError as CustomValidationError,
)
from app.models.response import LLMResponse
from app.utils.redaction import redact_text, safe_validation_errors

logger = logging.getLogger(__name__)


def register_exception_handlers(app: FastAPI) -> None:
    """
    Register all exception handlers to FastAPI app.
    """

    @app.exception_handler(LLMTimeoutError)
    async def llm_timeout_handler(request: Request, exc: LLMTimeoutError):
        """
        Handle LLM timeout - return 504 Gateway Timeout.
        This helps Java backend distinguish timeout from other errors.
        """
        logger.warning(
            f"LLM timeout after {exc.timeout_seconds}s: "
            f"response_time={exc.response_time_ms}ms"
        )

        return JSONResponse(
            status_code=504,  # Gateway Timeout - indicates upstream timeout
            content={
                "action": "ignore",
                "success": False,
                "error_message": f"LLM timeout after {exc.timeout_seconds}s",
                "error_code": exc.error_code,
                "total_tokens": 0,
                "response_time_ms": exc.response_time_ms,
            },
        )

    @app.exception_handler(LLMAPIError)
    async def llm_api_error_handler(request: Request, exc: LLMAPIError):
        """
        Handle LLM API errors with appropriate status codes.
        Differentiates authentication, rate limit, and provider errors.
        """
        # Log with full context
        logger.error(
            f"LLM API error: provider={exc.provider}, "
            f"status={exc.status_code}, message={exc.message}, "
            f"response_time={exc.response_time_ms}ms"
        )

        # Map LLM status to appropriate HTTP status
        if exc.status_code == 401:
            # Authentication error - return 502 (upstream auth failure)
            http_status = 502
            error_type = "authentication_failed"
        elif exc.status_code == 403:
            # Rate limit or forbidden - return 503 (service unavailable)
            http_status = 503
            error_type = "rate_limited"
        elif exc.status_code == 404:
            # Endpoint not found - return 502
            http_status = 502
            error_type = "endpoint_not_found"
        elif exc.status_code and exc.status_code >= 500:
            # Provider error - return 502
            http_status = 502
            error_type = "provider_error"
        else:
            # Other errors - return 502
            http_status = 502
            error_type = "api_error"

        return JSONResponse(
            status_code=http_status,
            content={
                "action": "ignore",
                "success": False,
                # Redacted: upstream error bodies frequently quote the API key back
                "error_message": redact_text(exc.message)[:200],
                "error_code": exc.error_code,
                "error_type": error_type,
                # "provider" is the caller-supplied base_url; it stays in the log only
                "llm_status_code": exc.status_code,
                "total_tokens": 0,
                "response_time_ms": exc.response_time_ms,
            },
        )

    @app.exception_handler(JSONParseError)
    async def json_parse_error_handler(request: Request, exc: JSONParseError):
        """
        Handle JSON parse errors - return 502.
        Log original LLM output for debugging (CRITICAL FIX).
        """
        # Log the full raw content for debugging
        logger.warning(
            f"JSON parse error: {exc.message}, "
            f"response_time={exc.response_time_ms}ms"
        )

        # CRITICAL: Log the original LLM output for debugging
        if exc.raw_content:
            logger.error(
                f"RAW LLM OUTPUT (parse failed):\n"
                f"---BEGIN---\n{redact_text(exc.raw_content)}\n---END---"
            )

        return JSONResponse(
            status_code=502,  # Bad Gateway - upstream returned invalid data
            content={
                "action": "ignore",
                "success": False,
                "error_message": redact_text(exc.message),
                "error_code": exc.error_code,
                # raw model output stays in the log: it is attacker-influenced text
                # and may contain anything, including echoed credentials
                "total_tokens": 0,
                "response_time_ms": exc.response_time_ms,
            },
        )

    @app.exception_handler(PromptInjectionDetected)
    async def prompt_injection_handler(request: Request, exc: PromptInjectionDetected):
        """
        Handle prompt injection detection - return 400 Bad Request.
        This is a client-side issue, not upstream failure.
        """
        logger.warning(
            f"Prompt injection detected: {exc.detection_reason}, "
            f"request_path={request.url.path}"
        )

        return JSONResponse(
            status_code=400,  # Bad Request - client sent malicious content
            content={
                "action": "ignore",
                "success": False,
                "error_message": "Security check failed: potential injection detected",
                "error_code": exc.error_code,
                "detection_reason": exc.detection_reason,
                "total_tokens": 0,
            },
        )

    @app.exception_handler(CustomValidationError)
    async def custom_validation_error_handler(request: Request, exc: CustomValidationError):
        """
        Handle custom validation errors - return 400 Bad Request.
        """
        logger.warning(
            f"Validation error: field={exc.field}, reason={exc.reason}"
        )

        return JSONResponse(
            status_code=400,
            content={
                "action": "ignore",
                "success": False,
                "error_message": exc.message,
                "error_code": exc.error_code,
                "field": exc.field,
                "total_tokens": 0,
            },
        )

    @app.exception_handler(LLMBaseError)
    async def llm_base_error_handler(request: Request, exc: LLMBaseError):
        """
        Handle any other LLM base errors - return 502.
        """
        logger.error(
            f"LLM error: {exc.message}, code={exc.error_code}, "
            f"response_time={exc.response_time_ms}ms"
        )

        return JSONResponse(
            status_code=502,
            content={
                "action": "ignore",
                "success": False,
                "error_message": exc.message[:200],
                "error_code": exc.error_code,
                "total_tokens": 0,
                "response_time_ms": exc.response_time_ms,
            },
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        """
        Handle request validation errors from FastAPI - return 400.
        Return structured error response.
        """
        # exc.errors() carries the rejected value in "input". The first field of a
        # decision request is api_key, so returning (or logging) the raw error list
        # published the credential whenever it failed validation.
        errors = safe_validation_errors(exc.errors())
        combined_message = "; ".join(
            f"{'.'.join(e['loc'])}: {e['msg']}" for e in errors
        )

        logger.warning(
            f"Request validation failed: {combined_message}, "
            f"path={request.url.path}"
        )

        return JSONResponse(
            status_code=400,
            content={
                "action": "ignore",
                "success": False,
                "error_message": f"Invalid request: {combined_message}",
                "error_code": "REQUEST_VALIDATION_ERROR",
                "validation_errors": errors,
                "total_tokens": 0,
            },
        )

    @app.exception_handler(Exception)
    async def generic_exception_handler(request: Request, exc: Exception):
        """
        Catch-all for unexpected exceptions - return 500 Internal Server Error.
        """
        logger.exception(
            f"Unexpected error: {type(exc).__name__}: {str(exc)}, "
            f"path={request.url.path}"
        )

        return JSONResponse(
            status_code=500,
            content={
                "action": "ignore",
                "success": False,
                "error_message": "Internal service error",
                "error_code": "INTERNAL_ERROR",
                # exception_type named internal classes to the caller; log only
                "total_tokens": 0,
            },
        )