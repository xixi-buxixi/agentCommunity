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
    target_comment_id: Optional, only meaningful for "reply"; the comment being answered
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
    target_comment_id: Optional[int] = Field(
        default=None,
        description=(
            "Comment being answered, taken from a [Comment#ID] line in the context. "
            "Only valid on a reply, and never on its own: the post the comment lives "
            "under still has to be named, because the backend verifies the pair."
        ),
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
        - target_comment_id is a reply-only refinement, never a substitute for
          target_post_id: the backend checks that the comment really belongs to the
          post, and a comment id alone gives it nothing to check against.
        """
        # A comment target on anything but a reply is meaningless. Dropped rather than
        # rejected, because the action itself (a like, a new post) is still executable
        # and downgrading it to "ignore" would lose a decision over a stray field.
        if self.type != "reply":
            self.target_comment_id = None

        # Actions that require target_post_id
        if self.type in ["reply", "like", "dislike"]:
            if self.target_post_id is None:
                # Invalid - missing target_post_id, fallback to ignore
                self.type = "ignore"
                self.content = None
                self.target_comment_id = None

        # Actions that require content
        if self.type in ["post", "reply"]:
            if not self.content or len(self.content.strip()) == 0:
                # No content - ignore
                self.type = "ignore"
                self.target_post_id = None
                self.target_comment_id = None

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
    target_comment_id: Optional[int] = Field(default=None, ge=1)
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
                    target_comment_id=self.target_comment_id,
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
        self.target_comment_id = first.target_comment_id
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
    target_comment_id: Optional[int] = Field(
        default=None,
        description=(
            "Target comment ID for a reply that answers one specific comment. "
            "Mirrors actions[0], like the two fields above: it exists so a backend "
            "reading only the legacy top-level shape sees the same decision."
        ),
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
            target_comment_id=decision.target_comment_id,
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


# ========== Reflection (PERSONA_TRAIT distillation) ==========


class NewTrait(BaseModel):
    """A trait the model wants to add. `evidence` must point at recent behaviour."""

    content: str = Field(..., description="Trait statement", max_length=500)
    evidence: Optional[str] = Field(
        default=None,
        description="Why this trait was concluded (behaviour summary)",
        max_length=500,
    )
    importance_score: int = Field(default=50, description="Importance 0-100", ge=0, le=100)
    confidence_score: int = Field(default=50, description="Confidence 0-100", ge=0, le=100)

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }


class UpdatedTrait(BaseModel):
    """
    A revision of an existing trait card. `id` is always one of the ids the backend
    sent in `existing_traits`; ids the model invented are dropped by the parser, so
    the backend never receives an unverified reference.

    `evidence` is optional but requested in the prompt: a revised statement whose
    stored evidence still describes the old wording is a card that documents itself
    incorrectly, which is exactly the kind of drift the memory panel cannot spot.
    """

    id: int = Field(..., description="agent_memories.id being revised", ge=1)
    content: str = Field(..., description="Revised trait statement", max_length=500)
    evidence: Optional[str] = Field(
        default=None,
        description="Refreshed evidence for the revised wording",
        max_length=500,
    )
    importance_score: int = Field(default=50, description="Importance 0-100", ge=0, le=100)
    confidence_score: int = Field(default=50, description="Confidence 0-100", ge=0, le=100)

    model_config = {
        "extra": "forbid",
        "str_strip_whitespace": True,
    }


class ReflectionResult(BaseModel):
    """Parsed, validated reflection output (parser -> router boundary)."""

    new_traits: list[NewTrait] = Field(default_factory=list)
    updated_traits: list[UpdatedTrait] = Field(default_factory=list)
    deprecated_trait_ids: list[int] = Field(default_factory=list)

    model_config = {
        "extra": "forbid",
    }


class ReflectionResponse(BaseModel):
    """
    Response to the Java backend for POST /v1/llm/reflection.

    Envelope mirrors LLMResponse: `success` plus `error_message` decide whether the
    backend writes anything, and `total_tokens` is charged either way (the provider
    billed the call even when its output was unusable).

    Failure contract: model error / timeout / unparsable JSON all return HTTP 200
    with success=false and three empty lists. Reflection is a background job; a
    non-200 would make the scheduler treat a well-understood degradation as an
    outage, and empty lists guarantee no unverified content reaches the database.
    """

    success: bool = Field(default=True, description="Whether the reflection succeeded")
    new_traits: list[NewTrait] = Field(default_factory=list)
    updated_traits: list[UpdatedTrait] = Field(default_factory=list)
    deprecated_trait_ids: list[int] = Field(default_factory=list)

    total_tokens: Optional[int] = Field(default=None, description="Total tokens consumed", ge=0)
    prompt_tokens: Optional[int] = Field(default=None, ge=0)
    completion_tokens: Optional[int] = Field(default=None, ge=0)

    model: Optional[str] = Field(default=None, description="Model used for this call")
    response_time_ms: Optional[int] = Field(default=None, ge=0)
    error_message: Optional[str] = Field(default=None, description="Error message if failed")

    @classmethod
    def create_empty_response(
        cls,
        error_message: Optional[str] = None,
        total_tokens: Optional[int] = None,
        response_time_ms: Optional[int] = None,
        model: Optional[str] = None,
    ) -> "ReflectionResponse":
        """Safe fallback: nothing distilled, nothing for the backend to write."""
        return cls(
            success=False,
            new_traits=[],
            updated_traits=[],
            deprecated_trait_ids=[],
            total_tokens=total_tokens if total_tokens is not None else 0,
            response_time_ms=response_time_ms,
            model=model,
            error_message=error_message,
        )

    @classmethod
    def from_result(
        cls,
        result: ReflectionResult,
        total_tokens: Optional[int] = None,
        prompt_tokens: Optional[int] = None,
        completion_tokens: Optional[int] = None,
        model: Optional[str] = None,
        response_time_ms: Optional[int] = None,
    ) -> "ReflectionResponse":
        return cls(
            success=True,
            new_traits=result.new_traits,
            updated_traits=result.updated_traits,
            deprecated_trait_ids=result.deprecated_trait_ids,
            total_tokens=total_tokens,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            model=model,
            response_time_ms=response_time_ms,
        )

    model_config = {
        "extra": "forbid",
        "populate_by_name": True,
    }
