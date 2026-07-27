"""
Custom Exceptions

Domain-specific exceptions for LLM operations.
Each exception type maps to a specific error response.
"""

from typing import Optional


class LLMBaseError(Exception):
    """
    Base exception for all LLM-related errors.
    """

    def __init__(
        self,
        message: str,
        error_code: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ):
        self.message = message
        self.error_code = error_code or "LLM_ERROR"
        self.response_time_ms = response_time_ms
        super().__init__(self.message)


class LLMTimeoutError(LLMBaseError):
    """
    Timeout error when LLM call exceeds configured limit.
    Returns action: "ignore" without consuming tokens.
    """

    def __init__(self, timeout_seconds: int, response_time_ms: Optional[int] = None):
        message = f"LLM call timed out after {timeout_seconds} seconds"
        super().__init__(
            message=message,
            error_code="LLM_TIMEOUT",
            response_time_ms=response_time_ms,
        )
        self.timeout_seconds = timeout_seconds


class LLMAPIError(LLMBaseError):
    """
    API error from LLM provider.
    Covers: 401 (invalid key), 403 (rate limit), 500 (provider error), etc.
    """

    def __init__(
        self,
        message: str,
        status_code: Optional[int] = None,
        provider: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ):
        # A finite set of codes.
        #
        # The previous f"LLM_API_ERROR_{status_code}" produced an open-ended family
        # of codes (LLM_API_ERROR_418, ..._529, ..._UNKNOWN), which cannot be
        # enumerated in an alert rule or switched on by the caller. The numeric
        # status travels separately as upstream_status.
        error_code = self._classify(status_code)
        full_message = f"LLM API error: {message}"
        # The provider (a caller-supplied base_url) stays out of the message that is
        # returned to the client; handlers log it separately.
        if provider:
            full_message = f"[{provider}] {full_message}"
        super().__init__(
            message=full_message,
            error_code=error_code,
            response_time_ms=response_time_ms,
        )
        self.status_code = status_code
        self.provider = provider

    @staticmethod
    def _classify(status_code: Optional[int]) -> str:
        """Map an upstream HTTP status onto one of a fixed set of error codes."""
        if status_code is None:
            return "LLM_UPSTREAM_UNREACHABLE"
        if status_code == 401:
            return "LLM_UPSTREAM_UNAUTHORIZED"
        if status_code == 403:
            return "LLM_UPSTREAM_FORBIDDEN"
        if status_code == 404:
            return "LLM_UPSTREAM_NOT_FOUND"
        if status_code == 429:
            return "LLM_UPSTREAM_RATE_LIMITED"
        if 500 <= status_code < 600:
            return "LLM_UPSTREAM_SERVER_ERROR"
        return "LLM_UPSTREAM_ERROR"


class JSONParseError(LLMBaseError):
    """
    Error when LLM response cannot be parsed as JSON.
    Returns action: "ignore" - we don't trust malformed output.
    """

    def __init__(
        self,
        raw_content: Optional[str] = None,
        parse_error: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ):
        message = "Failed to parse LLM response as valid JSON"
        if parse_error:
            message = f"{message}: {parse_error}"
        super().__init__(
            message=message,
            error_code="JSON_PARSE_ERROR",
            response_time_ms=response_time_ms,
        )
        self.raw_content = raw_content


class PromptInjectionDetected(LLMBaseError):
    """
    Error when potential prompt injection is detected.
    Security safeguard - returns action: "ignore".
    """

    def __init__(self, detection_reason: str):
        message = f"Potential prompt injection detected: {detection_reason}"
        super().__init__(
            message=message,
            error_code="INJECTION_DETECTED",
        )
        self.detection_reason = detection_reason


class ValidationError(LLMBaseError):
    """
    Error when request validation fails beyond Pydantic validation.
    """

    def __init__(self, field: str, reason: str):
        message = f"Validation error for '{field}': {reason}"
        super().__init__(
            message=message,
            error_code="VALIDATION_ERROR",
        )
        self.field = field
        self.reason = reason