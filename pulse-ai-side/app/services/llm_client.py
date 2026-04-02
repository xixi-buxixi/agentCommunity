"""
LLM Client Service

Handles communication with LLM providers (OpenAI-compatible APIs).
Supports multiple providers via base_url configuration.
"""

import asyncio
import json
import logging
import time
from typing import Any, Dict, Optional, Tuple

import httpx

from app.config.settings import settings
from app.exceptions.errors import LLMTimeoutError, LLMAPIError
from app.models.request import LLMRequest

logger = logging.getLogger(__name__)


class LLMClient:
    """
    LLM API Client.

    Handles:
    - OpenAI-compatible API calls
    - Timeout management
    - Response parsing
    - Retry logic
    """

    def __init__(self):
        """
        Initialize HTTP client with timeout configuration.
        """
        self.timeout = httpx.Timeout(
            connect=settings.CONNECT_TIMEOUT_SECONDS,
            read=settings.REQUEST_TIMEOUT_SECONDS,
            write=settings.REQUEST_TIMEOUT_SECONDS,
            pool=settings.CONNECT_TIMEOUT_SECONDS,
        )

    async def call_llm(
        self,
        request: LLMRequest,
    ) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        """
        Call LLM API and return raw response.

        Returns: (response_body_dict, usage_info_dict)

        Raises: LLMTimeoutError, LLMAPIError
        """
        start_time = time.time()

        # Build request body (OpenAI-compatible format)
        request_body = self._build_request_body(request)

        # Build headers
        headers = self._build_headers(request.api_key)

        # Full URL
        url = f"{request.base_url}/chat/completions"

        logger.info(f"Calling LLM: model={request.model_name}, url={request.base_url}")

        # Use async HTTP client
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            for attempt in range(settings.MAX_RETRIES + 1):
                try:
                    response = await client.post(
                        url,
                        json=request_body,
                        headers=headers,
                    )

                    response_time_ms = int((time.time() - start_time) * 1000)

                    if response.status_code == 200:
                        response_body = response.json()
                        logger.info(
                            f"LLM call successful: "
                            f"model={request.model_name}, "
                            f"time={response_time_ms}ms, "
                            f"attempt={attempt + 1}"
                        )
                        return response_body, self._extract_usage(response_body)

                    # Handle error status codes
                    await self._handle_error_status(
                        response.status_code,
                        response.text,
                        request.base_url,
                        response_time_ms,
                    )

                except httpx.TimeoutException as e:
                    response_time_ms = int((time.time() - start_time) * 1000)
                    logger.warning(
                        f"LLM timeout: model={request.model_name}, "
                        f"time={response_time_ms}ms, "
                        f"attempt={attempt + 1}"
                    )

                    if attempt < settings.MAX_RETRIES:
                        await asyncio.sleep(settings.RETRY_DELAY_SECONDS)
                        continue

                    raise LLMTimeoutError(
                        timeout_seconds=settings.REQUEST_TIMEOUT_SECONDS,
                        response_time_ms=response_time_ms,
                    )

                except httpx.RequestError as e:
                    response_time_ms = int((time.time() - start_time) * 1000)
                    logger.error(
                        f"LLM request error: {str(e)}, "
                        f"attempt={attempt + 1}"
                    )

                    if attempt < settings.MAX_RETRIES:
                        await asyncio.sleep(settings.RETRY_DELAY_SECONDS)
                        continue

                    raise LLMAPIError(
                        message=str(e),
                        status_code=None,
                        provider=request.base_url,
                        response_time_ms=response_time_ms,
                    )

        # Should not reach here
        raise LLMAPIError(message="Max retries exceeded", provider=request.base_url)

    def _build_request_body(self, request: LLMRequest) -> Dict[str, Any]:
        """
        Build OpenAI-compatible request body.

        Forces JSON output via response_format.
        """
        return {
            "model": request.model_name,
            "messages": [
                {
                    "role": "system",
                    "content": request.system_prompt,
                },
                {
                    "role": "user",
                    "content": request.context,
                },
            ],
            "max_tokens": request.max_tokens or settings.DEFAULT_MAX_TOKENS,
            "temperature": request.temperature or settings.DEFAULT_TEMPERATURE,
            "response_format": {"type": settings.RESPONSE_FORMAT_TYPE},
        }

    def _build_headers(self, api_key: str) -> Dict[str, str]:
        """
        Build HTTP headers for LLM API request.
        """
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        }

    def _extract_usage(self, response_body: Dict[str, Any]) -> Dict[str, Any]:
        """
        Extract token usage from response.
        """
        usage = response_body.get("usage", {})
        return {
            "total_tokens": usage.get("total_tokens", 0),
            "prompt_tokens": usage.get("prompt_tokens", 0),
            "completion_tokens": usage.get("completion_tokens", 0),
        }

    def _extract_content(self, response_body: Dict[str, Any]) -> str:
        """
        Extract content string from OpenAI response.
        """
        choices = response_body.get("choices", [])
        if not choices:
            raise LLMAPIError(message="No choices in response")

        message = choices[0].get("message", {})
        content = message.get("content", "")

        if not content:
            raise LLMAPIError(message="Empty content in response")

        return content

    async def _handle_error_status(
        self,
        status_code: int,
        response_text: str,
        provider: str,
        response_time_ms: int,
    ) -> None:
        """
        Handle non-200 status codes.
        Raises LLMAPIError with appropriate message.
        """
        # Try to parse error message from response
        try:
            error_body = json.loads(response_text)
            error_msg = error_body.get("error", {}).get("message", response_text)
        except json.JSONDecodeError:
            error_msg = response_text[:200]  # Truncate long error

        # Log based on status code
        if status_code == 401:
            logger.error(f"LLM authentication failed: {error_msg}")
        elif status_code == 403:
            logger.error(f"LLM rate limit or forbidden: {error_msg}")
        elif status_code == 404:
            logger.error(f"LLM endpoint not found: {error_msg}")
        elif status_code >= 500:
            logger.error(f"LLM provider error: {error_msg}")
        else:
            logger.warning(f"LLM unexpected status: {status_code}")

        raise LLMAPIError(
            message=error_msg,
            status_code=status_code,
            provider=provider,
            response_time_ms=response_time_ms,
        )

    def get_model_from_response(self, response_body: Dict[str, Any]) -> str:
        """
        Get model name from response (may differ from request if using aliases).
        """
        return response_body.get("model", "unknown")