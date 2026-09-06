"""
LLM Request Model

Pydantic model for incoming requests from Java backend.
Matches the payload structure from LLMClient.java.
"""

from typing import List, Optional

from pydantic import BaseModel, Field, field_validator

from app.utils.url_guard import UrlNotAllowed, validate_base_url


class MemoryItem(BaseModel):
    """
    One memory card injected by the backend (agent_memories row).

    Memory content is UNTRUSTED: it originates from earlier model output and from
    user edits, so PromptBuilder puts it through the same sanitisation chain as
    community posts before it is allowed anywhere near the prompt.

    extra="ignore" rather than "forbid" on purpose: the backend row carries more
    columns than the prompt needs (importance_score, evidence, ...). Forbidding
    them would turn an additive backend change into a 400 on every decision call,
    i.e. the whole community stops acting. Unknown keys are simply not rendered.
    """

    memory_type: str = Field(
        default="PERSONA_FACT",
        description="Memory card type, e.g. PERSONA_FACT / PERSONA_TRAIT",
        max_length=40,
    )
    content: str = Field(
        default="",
        description="Memory body (sanitised before entering the prompt)",
    )
    confidence_score: Optional[int] = Field(
        default=None,
        description="Confidence 0-100; rendered so the model can discount weak memories",
    )
    source: Optional[str] = Field(
        default=None,
        description="Human-readable provenance, e.g. POST#12 / REFLECTION",
        max_length=80,
    )

    model_config = {
        "extra": "ignore",
        "str_strip_whitespace": True,
    }


class LLMRequest(BaseModel):
    """
    Request payload from Java backend.

    Java sends:
    - api_key: Decrypted API Key (already decrypted by Java's AesUtil)
    - base_url: LLM provider API endpoint
    - model_name: Model to use (e.g., gpt-4o-mini)
    - system_prompt: Agent's personality/behavior prompt
    - context: Concatenated posts context (from AgentContext.buildFullPrompt())
    - memories: Optional memory cards (Phase 2). Absent/empty => no memory block,
      which is exactly the pre-Phase-2 behaviour, so an older backend keeps working.
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
    memories: Optional[List[MemoryItem]] = Field(
        default=None,
        description="Agent memory cards to inject into the prompt (optional)",
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
        Validate base_url against the SSRF guard.

        A prefix check alone accepted http:// (API key in cleartext) as well as
        loopback, private-range and cloud-metadata targets, which made this service
        usable as an internal scanner on behalf of whoever created the agent.
        """
        try:
            return validate_base_url(v)
        except UrlNotAllowed as exc:
            raise ValueError(str(exc)) from exc

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


class ExistingTrait(BaseModel):
    """
    One PERSONA_TRAIT card the agent already has, passed in so the model can revise
    or deprecate it instead of appending a near-duplicate.

    extra="ignore" for the same reason as MemoryItem: the backend row is wider than
    the prompt needs and must be free to grow.
    """

    id: int = Field(..., description="agent_memories.id of the existing trait", ge=1)
    content: str = Field(default="", description="Trait body (sanitised before use)")
    importance_score: Optional[int] = Field(default=None, description="Importance 0-100")
    confidence_score: Optional[int] = Field(default=None, description="Confidence 0-100")

    model_config = {
        "extra": "ignore",
        "str_strip_whitespace": True,
    }


class ReflectionLimits(BaseModel):
    """
    Output budget for one reflection run. Defaults match the plan's suggestions
    (<=5 new traits per run, <=30 traits per agent in total).
    """

    max_new_traits: int = Field(default=5, ge=0, le=20)
    max_total_traits: int = Field(default=30, ge=0, le=200)

    model_config = {
        "extra": "ignore",
    }


class ReflectionRequest(BaseModel):
    """
    Request payload for POST /v1/llm/reflection (internal, backend-only).

    Credential fields are identical to LLMRequest so the backend can reuse the same
    decrypt-and-forward path; auth is the shared X-Service-Token handled by the
    middleware, exactly like /v1/llm/decision.

    - agent_id: identifier for correlating logs (never used in the prompt)
    - system_prompt: the agent persona, optional. Reflection works without it, but
      with it the model can tell "already part of my persona" from "newly learned".
    - recent_behaviors: recent PERSONA_FACT / log summaries (untrusted text)
    - existing_traits: current ACTIVE PERSONA_TRAIT cards (untrusted text)
    - limits: output budget
    """

    api_key: str = Field(..., description="Decrypted API Key for LLM provider", min_length=1)
    base_url: str = Field(
        default="https://api.openai.com/v1",
        description="LLM provider API base URL",
    )
    model_name: str = Field(default="gpt-4o-mini", description="Model name to use")

    agent_id: Optional[int] = Field(
        default=None,
        description="Agent id, used for log correlation only",
        ge=1,
    )
    system_prompt: Optional[str] = Field(
        default=None,
        description="Agent persona; optional background for the distillation",
    )
    recent_behaviors: List[str] = Field(
        default_factory=list,
        description="Recent behaviour summaries to distil traits from",
    )
    existing_traits: List[ExistingTrait] = Field(
        default_factory=list,
        description="Traits the agent already has, for revision / deprecation",
    )
    limits: ReflectionLimits = Field(
        default_factory=ReflectionLimits,
        description="Output budget for this reflection run",
    )

    max_tokens: Optional[int] = Field(default=None, ge=1, le=4000)
    temperature: Optional[float] = Field(default=None, ge=0.0, le=2.0)

    @field_validator("api_key")
    @classmethod
    def validate_api_key(cls, v: str) -> str:
        """Same shape check as LLMRequest.validate_api_key."""
        v = v.strip()
        if not v:
            raise ValueError("API key cannot be empty")
        if len(v) < 10:
            raise ValueError("API key seems too short")
        return v

    @field_validator("base_url")
    @classmethod
    def validate_base_url(cls, v: str) -> str:
        """Same SSRF guard as LLMRequest: reflection is a normal outbound LLM call."""
        try:
            return validate_base_url(v)
        except UrlNotAllowed as exc:
            raise ValueError(str(exc)) from exc

    @field_validator("model_name")
    @classmethod
    def validate_model_name(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("model_name cannot be empty")
        return v

    model_config = {
        # Tolerant here, unlike LLMRequest: the reflection contract is brand new and
        # the backend scheduler may start sending additional diagnostics. A rejected
        # reflection request would silently cost the agent a day of self-evolution.
        "extra": "ignore",
        "str_strip_whitespace": True,
    }