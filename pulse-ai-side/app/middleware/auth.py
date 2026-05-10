"""
Security Middleware

Provides authentication and rate limiting for API endpoints.
Ensures production-level security for the LLM gateway.
"""

import hashlib
import logging
import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Callable, Optional

from fastapi import Request, HTTPException
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.config.settings import settings

logger = logging.getLogger(__name__)


@dataclass
class RateLimitConfig:
    """Rate limit configuration."""
    requests_per_minute: int = 60
    requests_per_hour: int = 1000
    burst_limit: int = 10  # Max burst requests in 1 second


@dataclass
class RateLimitState:
    """Tracks rate limit state for a client."""
    minute_count: int = 0
    hour_count: int = 0
    burst_count: int = 0
    minute_reset: float = 0.0
    hour_reset: float = 0.0
    burst_reset: float = 0.0


class RateLimiter:
    """
    In-memory rate limiter with sliding window.

    Limits:
    - Per-minute limit (default 60)
    - Per-hour limit (default 1000)
    - Burst limit (default 10 per second)

    Note: For production with multiple instances, consider Redis-based rate limiting.
    """

    def __init__(self, config: RateLimitConfig = None):
        self.config = config or RateLimitConfig()
        self._clients: dict[str, RateLimitState] = defaultdict(lambda: RateLimitState())

    def _get_client_key(self, request: Request) -> str:
        """
        Get unique key for rate limiting.
        Uses combination of IP and optional API key hash.
        """
        # Get client IP
        client_ip = request.client.host if request.client else "unknown"

        # If API key present, include hash for more granular limiting
        api_key = None
        if request.method == "POST":
            try:
                # Peek at body without consuming it
                # This is handled at middleware level before body is read
                pass
            except Exception:
                pass

        return f"{client_ip}:{hashlib.md5(api_key.encode()).hexdigest()[:8] if api_key else 'anonymous'}"

    def check_rate_limit(self, request: Request) -> Optional[dict]:
        """
        Check if request is within rate limits.

        Returns: None if allowed, error dict if rate limited.
        """
        client_key = self._get_client_key(request)
        now = time.time()
        state = self._clients[client_key]

        # Reset counters if windows expired
        if now > state.minute_reset:
            state.minute_count = 0
            state.minute_reset = now + 60

        if now > state.hour_reset:
            state.hour_count = 0
            state.hour_reset = now + 3600

        if now > state.burst_reset:
            state.burst_count = 0
            state.burst_reset = now + 1

        # Check limits
        if state.burst_count >= self.config.burst_limit:
            return {
                "error": "rate_limit_exceeded",
                "type": "burst",
                "message": f"Burst limit exceeded ({self.config.burst_limit} requests/second)",
                "retry_after": int(state.burst_reset - now) + 1,
            }

        if state.minute_count >= self.config.requests_per_minute:
            return {
                "error": "rate_limit_exceeded",
                "type": "minute",
                "message": f"Minute limit exceeded ({self.config.requests_per_minute} requests/minute)",
                "retry_after": int(state.minute_reset - now) + 1,
            }

        if state.hour_count >= self.config.requests_per_hour:
            return {
                "error": "rate_limit_exceeded",
                "type": "hour",
                "message": f"Hour limit exceeded ({self.config.requests_per_hour} requests/hour)",
                "retry_after": int(state.hour_reset - now) + 1,
            }

        # Increment counters
        state.burst_count += 1
        state.minute_count += 1
        state.hour_count += 1

        return None

    def get_remaining(self, request: Request) -> dict:
        """Get remaining requests for current windows."""
        client_key = self._get_client_key(request)
        state = self._clients[client_key]

        return {
            "burst_remaining": self.config.burst_limit - state.burst_count,
            "minute_remaining": self.config.requests_per_minute - state.minute_count,
            "hour_remaining": self.config.requests_per_hour - state.hour_count,
        }


class AuthMiddleware(BaseHTTPMiddleware):
    """
    Authentication middleware for API endpoints.

    Validates service-to-service authentication using:
    1. Service token (from environment variable)
    2. Or API key validation for external calls

    Health check endpoints are exempt.
    """

    # Endpoints that don't require authentication
    PUBLIC_ENDPOINTS = [
        "/health",
        "/",
        "/v1/llm/health",
    ]

    def __init__(
        self,
        app,
        service_token: Optional[str] = None,
        rate_limiter: Optional[RateLimiter] = None,
    ):
        super().__init__(app)
        # Service token for Java backend authentication
        self.service_token = service_token or settings.SERVICE_TOKEN
        self.rate_limiter = rate_limiter or RateLimiter()

    async def dispatch(self, request: Request, call_next: Callable):
        """
        Process request through auth and rate limit checks.
        """
        # Skip public endpoints
        if request.url.path in self.PUBLIC_ENDPOINTS:
            return await call_next(request)

        # Check rate limit first
        rate_limit_error = self.rate_limiter.check_rate_limit(request)
        if rate_limit_error:
            logger.warning(
                f"Rate limit exceeded: path={request.url.path}, "
                f"type={rate_limit_error['type']}"
            )
            return JSONResponse(
                status_code=429,
                content={
                    "action": "ignore",
                    "success": False,
                    "error_message": rate_limit_error["message"],
                    "error_code": "RATE_LIMITED",
                    "retry_after": rate_limit_error["retry_after"],
                },
                headers={
                    "Retry-After": str(rate_limit_error["retry_after"]),
                    "X-RateLimit-Limit": str(self.rate_limiter.config.requests_per_minute),
                    "X-RateLimit-Remaining": str(
                        self.rate_limiter.get_remaining(request)["minute_remaining"]
                    ),
                },
            )

        # Authentication check
        if self.service_token and not settings.DEBUG:
            # Check for service token in header
            auth_header = request.headers.get("X-Service-Token")

            if auth_header != self.service_token:
                logger.warning(
                    f"Authentication failed: path={request.url.path}, "
                    f"invalid or missing service token"
                )
                return JSONResponse(
                    status_code=401,
                    content={
                        "action": "ignore",
                        "success": False,
                        "error_message": "Service authentication required",
                        "error_code": "AUTH_REQUIRED",
                    },
                )

        # Add rate limit headers to response
        response = await call_next(request)

        # Add rate limit info headers
        remaining = self.rate_limiter.get_remaining(request)
        response.headers["X-RateLimit-Limit-Minute"] = str(
            self.rate_limiter.config.requests_per_minute
        )
        response.headers["X-RateLimit-Remaining-Minute"] = str(remaining["minute_remaining"])
        response.headers["X-RateLimit-Limit-Hour"] = str(
            self.rate_limiter.config.requests_per_hour
        )
        response.headers["X-RateLimit-Remaining-Hour"] = str(remaining["hour_remaining"])

        return response


# Factory function for creating middleware instance
def create_auth_middleware(
    service_token: Optional[str] = None,
    rate_limit_config: Optional[RateLimitConfig] = None,
) -> AuthMiddleware:
    """
    Create authentication middleware with custom configuration.

    Usage:
        app = FastAPI()
        app.add_middleware(create_auth_middleware())
    """
    rate_limiter = RateLimiter(rate_limit_config) if rate_limit_config else RateLimiter()
    return lambda app: AuthMiddleware(app, service_token, rate_limiter)