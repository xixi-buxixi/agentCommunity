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
                        return response_body, self._extract_usage(
                            response_body,
                            prompt_text=self._prompt_text_for_estimate(request),
                        )

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
            "tools": [
                {
                    "type": "function",
                    "function": {
                        "name": "submit_decision",
                        "description": "Submit the agent's action decision based on community context.",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "actions": {
                                    "type": "array",
                                    "description": "List of actions to take. Max 3.",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "type": {
                                                "type": "string",
                                                "description": "Action type: post, reply, like, dislike, ignore, or create_bounty",
                                                "enum": ["post", "reply", "like", "dislike", "ignore", "create_bounty"]
                                            },
                                            "target_post_id": {
                                                "type": "integer",
                                                "description": "Target post ID for reply, like, dislike"
                                            },
                                            "content": {
                                                "type": "string",
                                                "description": "Content for post or reply (max 200 chars)"
                                            },
                                            "title": {
                                                "type": "string",
                                                "description": "Title for create_bounty"
                                            },
                                            "description": {
                                                "type": "string",
                                                "description": "Description for create_bounty"
                                            },
                                            "reward": {
                                                "type": "integer",
                                                "description": "Reward amount for create_bounty"
                                            },
                                            "deadline_hours": {
                                                "type": "integer",
                                                "description": "Deadline in hours for create_bounty"
                                            }
                                        },
                                        "required": ["type"]
                                    }
                                },
                                "reason": {
                                    "type": "string",
                                    "description": "Brief explanation for the chosen actions"
                                }
                            },
                            "required": ["actions", "reason"]
                        }
                    }
                }
            ],
            "tool_choice": {
                "type": "function",
                "function": {"name": "submit_decision"}
            }
        }

    def _build_headers(self, api_key: str) -> Dict[str, str]:
        """
        Build HTTP headers for LLM API request.
        """
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        }

    def _extract_usage(
        self,
        response_body: Dict[str, Any],
        prompt_text: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Extract token usage from response.
        """
        usage = response_body.get("usage", {})
        prompt_tokens = usage.get("prompt_tokens")
        completion_tokens = usage.get("completion_tokens")
        total_tokens = usage.get("total_tokens")

        if total_tokens is None and prompt_tokens is not None and completion_tokens is not None:
            total_tokens = prompt_tokens + completion_tokens

        if prompt_tokens is None or completion_tokens is None or total_tokens is None:
            estimated_prompt = self._estimate_tokens(prompt_text or "")
            estimated_completion = self._estimate_tokens(self._safe_extract_content(response_body))
            prompt_tokens = prompt_tokens if prompt_tokens is not None else estimated_prompt
            completion_tokens = (
                completion_tokens if completion_tokens is not None else estimated_completion
            )
            total_tokens = total_tokens if total_tokens is not None else prompt_tokens + completion_tokens

        return {
            "total_tokens": max(int(total_tokens), 0),
            "prompt_tokens": max(int(prompt_tokens), 0),
            "completion_tokens": max(int(completion_tokens), 0),
        }

    def _prompt_text_for_estimate(self, request: LLMRequest) -> str:
        return f"{request.system_prompt}\n{request.context}"

    def _safe_extract_content(self, response_body: Dict[str, Any]) -> str:
        try:
            return self._extract_content(response_body)
        except LLMAPIError:
            return ""

    def _estimate_tokens(self, text: str) -> int:
        if not text:
            return 0
        chinese_chars = sum(1 for c in text if "\u4e00" <= c <= "\u9fff")
        other_chars = len(text) - chinese_chars
        return max(int((chinese_chars / 2) + (other_chars / 4)), 1)

    def _extract_content(self, response_body: Dict[str, Any]) -> str:
        """
        Extract content string from OpenAI response.
        """
        choices = response_body.get("choices", [])
        if not choices:
            raise LLMAPIError(message="No choices in response")

        message = choices[0].get("message", {})

        # Check if model returned tool_calls (function calling)
        tool_calls = message.get("tool_calls", [])
        if tool_calls:
            # We enforce `submit_decision` function calling
            return tool_calls[0].get("function", {}).get("arguments", "")

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
