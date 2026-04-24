"""
Application Settings

Configuration loaded from environment variables.
All settings have sensible defaults for development.

Enhanced with security settings for production:
- Service token for authentication
- Rate limiting configuration
"""

import os
from dataclasses import dataclass, field
from typing import Optional


@dataclass(frozen=True)
class Settings:
    """
    Application settings from environment variables.
    Frozen for immutability.
    """

    # Service settings
    DEBUG: bool = field(default_factory=lambda: os.getenv("DEBUG", "false").lower() == "true")
    SERVICE_PORT: int = field(default_factory=lambda: int(os.getenv("SERVICE_PORT", "8000")))
    SERVICE_HOST: str = field(default_factory=lambda: os.getenv("SERVICE_HOST", "0.0.0.0"))

    # Timeout settings (critical for LLM calls)
    REQUEST_TIMEOUT_SECONDS: int = field(default_factory=lambda: int(os.getenv("REQUEST_TIMEOUT_SECONDS", "30")))
    CONNECT_TIMEOUT_SECONDS: int = field(default_factory=lambda: int(os.getenv("CONNECT_TIMEOUT_SECONDS", "5")))

    # LLM defaults (used when client doesn't specify)
    DEFAULT_MAX_TOKENS: int = field(default_factory=lambda: int(os.getenv("DEFAULT_MAX_TOKENS", "200")))
    DEFAULT_TEMPERATURE: float = field(default_factory=lambda: float(os.getenv("DEFAULT_TEMPERATURE", "0.7")))

    # Prompt protection
    CONTEXT_MARKER: str = "<!-- CONTEXT_ONLY -->"
    SYSTEM_INSTRUCTION_SEPARATOR: str = "\n\n=== 请根据你的设定决定是否互动 ===\n"

    # JSON schema for structured output
    RESPONSE_FORMAT_TYPE: str = "json_object"

    # Retry settings
    MAX_RETRIES: int = field(default_factory=lambda: int(os.getenv("MAX_RETRIES", "2")))
    RETRY_DELAY_SECONDS: float = field(default_factory=lambda: float(os.getenv("RETRY_DELAY_SECONDS", "1.0")))

    # Logging
    LOG_LEVEL: str = field(default_factory=lambda: os.getenv("LOG_LEVEL", "INFO"))

    # Security settings (NEW)
    # Service token for authentication between Java backend and Python service
    # In production, set this via environment variable
    SERVICE_TOKEN: Optional[str] = field(
        default_factory=lambda: os.getenv("SERVICE_TOKEN", None)
    )

    # Rate limiting configuration
    RATE_LIMIT_REQUESTS_PER_MINUTE: int = field(
        default_factory=lambda: int(os.getenv("RATE_LIMIT_REQUESTS_PER_MINUTE", "60"))
    )
    RATE_LIMIT_REQUESTS_PER_HOUR: int = field(
        default_factory=lambda: int(os.getenv("RATE_LIMIT_REQUESTS_PER_HOUR", "1000"))
    )
    RATE_LIMIT_BURST: int = field(
        default_factory=lambda: int(os.getenv("RATE_LIMIT_BURST", "10"))
    )

    def validate(self) -> bool:
        """
        Validate settings.
        Returns True if all critical settings are valid.
        """
        valid = True

        if self.REQUEST_TIMEOUT_SECONDS <= 0:
            valid = False
        if self.CONNECT_TIMEOUT_SECONDS <= 0:
            valid = False
        if self.DEFAULT_MAX_TOKENS <= 0:
            valid = False
        if self.DEFAULT_TEMPERATURE < 0 or self.DEFAULT_TEMPERATURE > 2:
            valid = False

        # Warn if no service token in production mode
        if not self.DEBUG and not self.SERVICE_TOKEN:
            import logging
            logging.getLogger(__name__).warning(
                "SERVICE_TOKEN not set in production mode - "
                "authentication will be disabled!"
            )

        return valid

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