"""
LLM Response Models

Pydantic models for responses to Java backend.
Matches LLMResponse.java and AgentActionDecision.java.
"""

from typing import Optional, Union
from pydantic import BaseModel, Field, field_validator, model_validator


class AgentAction(BaseModel):
    """
    One executable action from LLM response.

    type/action: "post" | "reply" | "like" | "dislike" | "ignore" | "create_bounty"
    target_post_id: Required when action = "reply" | "like" | "dislike"
    content: Required when action = "post" or "reply" (max 200 chars)
    create_bounty requires title, description, reward, and deadline_hours
    """

    type: str = Field(
        default="ignore",
        description="Action type",
    )
    target_post_id: Optional[int] = Field(
        default=None,
        description="Target post ID (required for reply/like/dislike actions)",
        ge=1,
    )
    content: Optional[str] = Field(
        default=None,
        description="Content to post/reply",
        max_length=500,  # Allow longer for truncation handling
    )
    title: Optional[str] = Field(default=None, max_length=100)
    description: Optional[str] = Field(default=None, max_length=1000)
    reward: Optional[int] = Field(default=None, ge=1)
    deadline_hours: Optional[int] = Field(default=None, ge=1)

    @property
    def action(self) -> str:
        """Backward-compatible Python attribute used by existing tests/services."""
        return self.type

    @field_validator("type")
    @classmethod
    def validate_action(cls, v: str) -> str:
        """
        Validate action is one of allowed values.
        """
        v = v.lower().strip()
        allowed = ["post", "reply", "like", "dislike", "ignore", "create_bounty"]
        if v not in allowed:
            # Default to ignore for invalid actions
            return "ignore"
        return v

    @model_validator(mode="after")
    def validate_action_requirements(self) -> "ActionDecision":
        """
        Validate action-specific requirements.
        - reply/like/dislike need target_post_id
        - post/reply need content
        - create_bounty needs title, description, reward, deadline_hours
        """
        # Actions that require target_post_id
        if self.type in ["reply", "like", "dislike"]:
            if self.target_post_id is None:
                # Invalid - missing target_post_id, fallback to ignore
                self.type = "ignore"
                self.content = None

        # Actions that require content
        if self.type in ["post", "reply"]:
            if not self.content or len(self.content.strip()) == 0:
                # No content - ignore
                self.type = "ignore"
                self.target_post_id = None

        if self.type == "create_bounty":
            missing_text = not self.title or not self.description
            missing_numbers = self.reward is None or self.deadline_hours is None
            if missing_text or missing_numbers:
                self.type = "ignore"
                self.title = None
                self.description = None
                self.reward = None
                self.deadline_hours = None

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
        if self.type == "ignore":
            return True

        # Actions that require target_post_id
        if self.type in ["reply", "like", "dislike"]:
            if self.target_post_id is None:
                return False

        # Actions that require content
        if self.type in ["post", "reply"]:
            if not self.content:
                return False

        if self.type == "create_bounty":
            if not self.title or not self.description:
                return False
            if self.reward is None or self.deadline_hours is None:
                return False

        return True

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }


class ActionDecision(BaseModel):
    """
    Parsed decision from LLM response.

    Keeps legacy single-action fields while adding evolved actions[].
    """

    action: str = Field(default="ignore")
    target_post_id: Optional[int] = Field(default=None, ge=1)
    content: Optional[str] = Field(default=None, max_length=500)
    title: Optional[str] = Field(default=None, max_length=100)
    description: Optional[str] = Field(default=None, max_length=1000)
    reward: Optional[int] = Field(default=None, ge=1)
    deadline_hours: Optional[int] = Field(default=None, ge=1)
    actions: list[AgentAction] = Field(default_factory=list)
    reason: Optional[str] = Field(default=None, max_length=1000)

    @model_validator(mode="after")
    def normalize_actions(self) -> "ActionDecision":
        """
        Populate actions[] from legacy fields when needed and mirror the first
        action back to legacy fields for old Java consumers.
        """
        if not self.actions:
            self.actions = [
                AgentAction(
                    type=self.action,
                    target_post_id=self.target_post_id,
                    content=self.content,
                    title=self.title,
                    description=self.description,
                    reward=self.reward,
                    deadline_hours=self.deadline_hours,
                )
            ]

        self.actions = self._normalize_action_list(self.actions)
        first = self.actions[0]
        self.action = first.type
        self.target_post_id = first.target_post_id
        self.content = first.content
        self.title = first.title
        self.description = first.description
        self.reward = first.reward
        self.deadline_hours = first.deadline_hours
        return self

    @classmethod
    def from_actions(
        cls,
        actions: list[Union[AgentAction, "ActionDecision"]],
        reason: Optional[str] = None,
    ) -> "ActionDecision":
        normalized = []
        for action in actions:
            if isinstance(action, ActionDecision):
                normalized.extend(action.actions)
            else:
                normalized.append(action)
        return cls(actions=normalized, reason=reason)

    @staticmethod
    def _normalize_action_list(actions: list[AgentAction]) -> list[AgentAction]:
        valid_actions = [action for action in actions if action.type != "ignore" and action.is_valid()]

        if ActionDecision._has_like_dislike_conflict(valid_actions):
            return [AgentAction(type="ignore")]

        if not valid_actions:
            return [AgentAction(type="ignore")]

        return valid_actions[:3]

    @staticmethod
    def _has_like_dislike_conflict(actions: list[AgentAction]) -> bool:
        likes = {
            action.target_post_id
            for action in actions
            if action.type == "like" and action.target_post_id is not None
        }
        dislikes = {
            action.target_post_id
            for action in actions
            if action.type == "dislike" and action.target_post_id is not None
        }
        return bool(likes.intersection(dislikes))

    def get_truncated_content(self, max_length: int = 200) -> str:
        return self.actions[0].get_truncated_content(max_length=max_length)

    def is_valid(self) -> bool:
        return all(action.is_valid() for action in self.actions)

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
    actions: list[AgentAction] = Field(
        default_factory=lambda: [AgentAction(type="ignore")],
        description="Evolved multi-action decision list",
        max_length=3,
    )
    reason: Optional[str] = Field(
        default=None,
        description="Reason for the decision",
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
            actions=[AgentAction(type="ignore")],
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
            actions=decision.actions,
            reason=decision.reason,
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


class SummarizeResponse(BaseModel):
    summary: str = Field(..., description="Compressed text")
    success: bool = True


class ClassifyPostResponse(BaseModel):
    tag: str = Field(..., description="Enum-constrained post tag")
    success: bool = True
