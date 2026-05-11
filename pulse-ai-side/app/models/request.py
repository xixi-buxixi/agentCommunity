"""
LLM Request Model

Pydantic model for incoming requests from Java backend.
Matches the payload structure from LLMClient.java.
"""

from typing import Optional
from pydantic import BaseModel, Field, field_validator


class LLMRequest(BaseModel):
    """
    Request payload from Java backend.

    Java sends:
    - api_key: Decrypted API Key (already decrypted by Java's AesUtil)
    - base_url: LLM provider API endpoint
    - model_name: Model to use (e.g., gpt-4o-mini)
    - system_prompt: Agent's personality/behavior prompt
    - context: Concatenated posts context (from AgentContext.buildFullPrompt())
    """

    api_key: str = Field(
        ...,
        description="Decrypted API Key for LLM provider",
        min_length=1,
    )
    base_url: str = Field(
        default="https://api.openai.com/v1",
        description="LLM provider API base URL",
    )
    model_name: str = Field(
        default="gpt-4o-mini",
        description="Model name to use",
    )
    system_prompt: str = Field(
        ...,
        description="Agent's system prompt defining personality",
        min_length=1,
    )
    context: str = Field(
        ...,
        description="Community posts context for decision making",
        min_length=1,
    )

    # Optional overrides
    max_tokens: Optional[int] = Field(
        default=None,
        description="Override max tokens for response",
        ge=1,
        le=4000,
    )
    temperature: Optional[float] = Field(
        default=None,
        description="Override temperature for response",
        ge=0.0,
        le=2.0,
    )

    @field_validator("api_key")
    @classmethod
    def validate_api_key(cls, v: str) -> str:
        """
        Basic API key format validation.
        Does NOT check actual validity (that's provider's job).
        """
        # Strip whitespace
        v = v.strip()
        if not v:
            raise ValueError("API key cannot be empty")
        # Basic format check (most keys start with sk- or similar)
        if len(v) < 10:
            raise ValueError("API key seems too short")
        return v

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, v: str) -> str:
        """
        Ensure base_url is valid URL format.
        """
        v = v.strip()
        if not v.startswith("http://") and not v.startswith("https://"):
            raise ValueError("base_url must start with http:// or https://")
        # Remove trailing slash for consistency
        return v.rstrip("/")

    @field_validator("model_name")
    @classmethod
    def validate_model_name(cls, v: str) -> str:
        """
        Ensure model name is not empty.
        """
        v = v.strip()
        if not v:
            raise ValueError("model_name cannot be empty")
        return v

    model_config = {
        "extra": "forbid",  # Reject unknown fields
        "str_strip_whitespace": True,
    }


class SummarizeRequest(BaseModel):
    text: str = Field(..., min_length=1, description="Text to summarize")
    max_length: int = Field(default=500, ge=50, le=1000)

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }


class ClassifyPostRequest(BaseModel):
    content: str = Field(..., min_length=1, description="Post content to classify")
    allowed_tags: list[str] = Field(default_factory=list)

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }
