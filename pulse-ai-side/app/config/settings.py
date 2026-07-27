"""
Application Settings

Configuration loaded from environment variables and (optionally) a .env file.

Uses pydantic-settings rather than a hand-rolled dataclass so that
- the .env file is actually read (python-dotenv was declared but never called, so a
  bare-metal deployment silently fell back to every default),
- a malformed numeric value produces a clear validation error instead of a
  ValueError during module import,
- security-critical settings can be validated in one place.
"""

import logging
from typing import Optional

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

logger = logging.getLogger(__name__)


class Settings(BaseSettings):
    """Application settings from environment variables / .env."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        frozen=True,
    )

    # Service settings
    DEBUG: bool = False
    SERVICE_PORT: int = 8000
    # Bind to loopback by default: the gateway handles decrypted API keys and has
    # no business being reachable from the internet. Override explicitly when the
    # backend runs on another host (and firewall the port).
    SERVICE_HOST: str = "127.0.0.1"

    # Timeout settings (critical for LLM calls), per attempt.
    # Keep attempts x timeout + backoff BELOW the caller's read timeout
    # (pulse-ai-side.timeout on the Java side) so the caller observes this
    # service's structured fallback instead of aborting the connection itself.
    REQUEST_TIMEOUT_SECONDS: int = 20
    CONNECT_TIMEOUT_SECONDS: int = 5

    # LLM defaults (used when client doesn't specify)
    DEFAULT_MAX_TOKENS: int = 200
    DEFAULT_TEMPERATURE: float = 0.7

    # Prompt protection
    CONTEXT_MARKER: str = "<!-- CONTEXT_ONLY -->"
    SYSTEM_INSTRUCTION_SEPARATOR: str = "\n\n=== 请根据你的设定决定是否互动 ===\n"

    # JSON schema for structured output
    RESPONSE_FORMAT_TYPE: str = "json_object"

    # Retry settings
    MAX_RETRIES: int = 1
    RETRY_DELAY_SECONDS: float = 1.0
    # Cap for exponential backoff between retries
    RETRY_MAX_DELAY_SECONDS: float = 4.0

    # Logging
    LOG_LEVEL: str = "INFO"

    # Security: shared secret required from the Java backend (X-Service-Token).
    # Mandatory unless DEBUG - see the validator below.
    SERVICE_TOKEN: Optional[str] = None

    # SSRF protection for the caller-supplied base_url.
    # Allow plain http only when explicitly enabled (loopback development).
    ALLOW_INSECURE_LLM_HTTP: bool = False
    # Reject private / loopback / link-local / cloud-metadata destinations
    BLOCK_PRIVATE_LLM_TARGETS: bool = True
    # Optional comma-separated host allowlist, e.g. "api.openai.com,api.deepseek.com"
    LLM_HOST_ALLOWLIST: str = ""

    # Rate limiting configuration
    RATE_LIMIT_REQUESTS_PER_MINUTE: int = 60
    RATE_LIMIT_REQUESTS_PER_HOUR: int = 1000
    RATE_LIMIT_BURST: int = Field(default=10)

    @model_validator(mode="after")
    def _validate(self) -> "Settings":
        if self.REQUEST_TIMEOUT_SECONDS <= 0:
            raise ValueError("REQUEST_TIMEOUT_SECONDS must be > 0")
        if self.CONNECT_TIMEOUT_SECONDS <= 0:
            raise ValueError("CONNECT_TIMEOUT_SECONDS must be > 0")
        if self.DEFAULT_MAX_TOKENS <= 0:
            raise ValueError("DEFAULT_MAX_TOKENS must be > 0")
        if not 0 <= self.DEFAULT_TEMPERATURE <= 2:
            raise ValueError("DEFAULT_TEMPERATURE must be within [0, 2]")

        # Fail closed. Previously a missing SERVICE_TOKEN skipped the whole auth
        # block, turning this service into an open LLM proxy that anyone able to
        # reach the port could use to spend credits or probe internal hosts.
        if not self.DEBUG and not (self.SERVICE_TOKEN or "").strip():
            raise ValueError(
                "SERVICE_TOKEN is required when DEBUG is false. "
                "Generate one with `openssl rand -hex 32` and configure the same "
                "value for the backend (SERVICE_TOKEN in /opt/pulse/backend/.env)."
            )
        if self.DEBUG and not (self.SERVICE_TOKEN or "").strip():
            logger.warning(
                "DEBUG mode with no SERVICE_TOKEN: service-to-service "
                "authentication is disabled. Never do this outside development."
            )
        return self

    @property
    def host_allowlist(self) -> list:
        """Parsed LLM_HOST_ALLOWLIST (empty list means 'no allowlist')."""
        return [h.strip().lower() for h in self.LLM_HOST_ALLOWLIST.split(",") if h.strip()]

    @property
    def total_request_budget_seconds(self) -> float:
        """
        Worst-case wall clock for one upstream call: every attempt timing out plus
        the backoff between them. Used to sanity-check against the caller budget.
        """
        attempts = self.MAX_RETRIES + 1
        backoff = sum(
            min(self.RETRY_DELAY_SECONDS * (2 ** i), self.RETRY_MAX_DELAY_SECONDS)
            for i in range(self.MAX_RETRIES)
        )
        return attempts * self.REQUEST_TIMEOUT_SECONDS + backoff

    def validate(self) -> bool:
        """
        Kept for callers that probe configuration. Validation now happens during
        construction, so reaching this method means the settings are valid.
        """
        return True

    @property
    def timeout_config(self) -> dict:
        """
        Get timeout configuration for HTTP client.
        """
        return {
            "connect": self.CONNECT_TIMEOUT_SECONDS,
            "read": self.REQUEST_TIMEOUT_SECONDS,
            "write": self.REQUEST_TIMEOUT_SECONDS,
            "pool": self.CONNECT_TIMEOUT_SECONDS,
        }

    @property
    def rate_limit_config(self) -> dict:
        """
        Get rate limit configuration.
        """
        return {
            "requests_per_minute": self.RATE_LIMIT_REQUESTS_PER_MINUTE,
            "requests_per_hour": self.RATE_LIMIT_REQUESTS_PER_HOUR,
            "burst_limit": self.RATE_LIMIT_BURST,
        }


# Global settings instance (immutable)
settings = Settings()
