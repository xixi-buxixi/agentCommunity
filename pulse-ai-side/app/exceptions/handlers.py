"""
Exception Handlers

FastAPI exception handlers that convert exceptions to
structured JSON responses matching Java's expected format.
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
)
from app.models.response import LLMResponse

logger = logging.getLogger(__name__)


def register_exception_handlers(app: FastAPI) -> None:
    """
    Register all exception handlers to FastAPI app.
    """

    @app.exception_handler(LLMTimeoutError)
    async def llm_timeout_handler(request: Request, exc: LLMTimeoutError):
        """
        Handle LLM timeout - return ignore action without token consumption.
        """
        logger.warning(f"LLM timeout: {exc.message}")

        return JSONResponse(
            status_code=200,  # Return 200 so Java doesn't retry
            content=LLMResponse.create_ignore_response(
                error_message=exc.message,
                response_time_ms=exc.response_time_ms,
            ).model_dump(),
        )

    @app.exception_handler(LLMAPIError)
    async def llm_api_error_handler(request: Request, exc: LLMAPIError):
        """
        Handle LLM API errors - return ignore action.
        """
        logger.error(f"LLM API error: {exc.message}, status={exc.status_code}")

        return JSONResponse(
            status_code=200,
            content=LLMResponse.create_ignore_response(
                error_message=exc.message,
                response_time_ms=exc.response_time_ms,
            ).model_dump(),
        )

    @app.exception_handler(JSONParseError)
    async def json_parse_error_handler(request: Request, exc: JSONParseError):
        """
        Handle JSON parse errors - return ignore action.
        """
        logger.warning(f"JSON parse error: {exc.message}")
        if exc.raw_content:
            logger.debug(f"Raw content (truncated): {exc.raw_content[:200]}...")

        return JSONResponse(
            status_code=200,
            content=LLMResponse.create_ignore_response(
                error_message=exc.message,
                response_time_ms=exc.response_time_ms,
            ).model_dump(),
        )

    @app.exception_handler(PromptInjectionDetected)
    async def prompt_injection_handler(request: Request, exc: PromptInjectionDetected):
        """
        Handle prompt injection detection - return ignore action.
        Security safeguard.
        """
        logger.warning(f"Prompt injection detected: {exc.detection_reason}")

        return JSONResponse(
            status_code=200,
            content=LLMResponse.create_ignore_response(
                error_message="Security check failed",
            ).model_dump(),
        )

    @app.exception_handler(LLMBaseError)
    async def llm_base_error_handler(request: Request, exc: LLMBaseError):
        """
        Handle any other LLM base errors.
        """
        logger.error(f"LLM error: {exc.message}, code={exc.error_code}")

        return JSONResponse(
            status_code=200,
            content=LLMResponse.create_ignore_response(
                error_message=exc.message,
                response_time_ms=exc.response_time_ms,
            ).model_dump(),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        """
        Handle request validation errors from FastAPI.
        Return structured error response.
        """
        errors = exc.errors()
        error_messages = [f"{e.get('loc', [])}: {e.get('msg', '')}" for e in errors]
        combined_message = "; ".join(error_messages)

        logger.warning(f"Request validation failed: {combined_message}")

        return JSONResponse(
            status_code=400,
            content={
                "action": "ignore",
                "success": False,
                "error_message": f"Invalid request: {combined_message}",
                "total_tokens": 0,
            },
        )

    @app.exception_handler(Exception)
    async def generic_exception_handler(request: Request, exc: Exception):
        """
        Catch-all for unexpected exceptions.
        Return ignore action with generic error message.
        """
        logger.exception(f"Unexpected error: {type(exc).__name__}: {str(exc)}")

        return JSONResponse(
            status_code=500,
            content={
                "action": "ignore",
                "success": False,
                "error_message": "Internal service error",
                "total_tokens": 0,
            },
        )