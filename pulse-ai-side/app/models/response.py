"""
LLM Response Models

Pydantic models for responses to Java backend.
Matches LLMResponse.java and AgentActionDecision.java.
"""

from typing import Optional
from pydantic import BaseModel, Field, field_validator, model_validator


class ActionDecision(BaseModel):
    """
    Parsed action decision from LLM response.
    Matches AgentActionDecision.java structure.

    action: "post" | "reply" | "ignore"
    target_post_id: Required when action = "reply"
    content: Required when action = "post" or "reply" (max 200 chars)
    """

    action: str = Field(
        default="ignore",
        description="Action type: post, reply, or ignore",
    )
    target_post_id: Optional[int] = Field(
        default=None,
        description="Target post ID (required for reply action)",
        ge=1,
    )
    content: Optional[str] = Field(
        default=None,
        description="Content to post/reply",
        max_length=500,  # Allow longer for truncation handling
    )

    @field_validator("action")
    @classmethod
    def validate_action(cls, v: str) -> str:
        """
        Validate action is one of allowed values.
        """
        v = v.lower().strip()
        allowed = ["post", "reply", "ignore"]
        if v not in allowed:
            # Default to ignore for invalid actions
            return "ignore"
        return v

    @model_validator(mode="after")
    def validate_action_requirements(self) -> "ActionDecision":
        """
        Validate action-specific requirements.
        - reply needs target_post_id
        - post/reply need content
        """
        if self.action == "reply":
            if self.target_post_id is None:
                # Invalid reply - ignore
                self.action = "ignore"
                self.content = None

        if self.action in ["post", "reply"]:
            if not self.content or len(self.content.strip()) == 0:
                # No content - ignore
                self.action = "ignore"
                self.target_post_id = None

        return self

    def get_truncated_content(self, max_length: int = 200) -> str:
        """
        Truncate content to max_length with ellipsis.
        Matches AgentActionDecision.getTruncatedContent().
        """
        if not self.content:
            return ""
        if len(self.content) <= max_length:
            return self.content
        return self.content[:max_length] + "..."

    def is_valid(self) -> bool:
        """
        Check if this decision is valid.
        Matches AgentActionDecision.isValid().
        """
        if self.action == "ignore":
            return True

        if self.action in ["post", "reply"]:
            if not self.content:
                return False

        if self.action == "reply":
            if self.target_post_id is None:
                return False

        return True

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }


class LLMResponse(BaseModel):
    """
    Response to Java backend.
    Matches LLMResponse.java structure.

    Contains parsed action decision and token usage info.
    """

    # Core response
    action: str = Field(
        default="ignore",
        description="Agent's decided action",
    )
    target_post_id: Optional[int] = Field(
        default=None,
        description="Target post ID for reply action",
    )
    content: Optional[str] = Field(
        default=None,
        description="Content for post/reply action",
    )

    # Token usage (for Java to deduct from agent's quota)
    total_tokens: Optional[int] = Field(
        default=None,
        description="Total tokens consumed",
        ge=0,
    )
    prompt_tokens: Optional[int] = Field(
        default=None,
        description="Prompt tokens",
        ge=0,
    )
    completion_tokens: Optional[int] = Field(
        default=None,
        description="Completion tokens",
        ge=0,
    )

    # Metadata
    model: Optional[str] = Field(
        default=None,
        description="Model used for this call",
    )
    response_time_ms: Optional[int] = Field(
        default=None,
        description="Response time in milliseconds",
        ge=0,
    )

    # Status
    success: bool = Field(
        default=True,
        description="Whether the LLM call was successful",
    )
    error_message: Optional[str] = Field(
        default=None,
        description="Error message if failed",
    )

    @classmethod
    def create_ignore_response(
        cls,
        error_message: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ) -> "LLMResponse":
        """
        Create a default ignore response for error cases.
        Used when LLM fails or returns invalid JSON.
        """
        return cls(
            action="ignore",
            success=False,
            error_message=error_message,
            response_time_ms=response_time_ms,
            total_tokens=0,
        )

    @classmethod
    def from_decision(
        cls,
        decision: ActionDecision,
        total_tokens: Optional[int] = None,
        prompt_tokens: Optional[int] = None,
        completion_tokens: Optional[int] = None,
        model: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ) -> "LLMResponse":
        """
        Create response from parsed action decision.
        """
        return cls(
            action=decision.action,
            target_post_id=decision.target_post_id,
            content=decision.get_truncated_content(),
            total_tokens=total_tokens,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            model=model,
            response_time_ms=response_time_ms,
            success=True,
        )

    model_config = {
        "extra": "forbid",
        "populate_by_name": True,
    }