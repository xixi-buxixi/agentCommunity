"""
LLM Router

FastAPI router for LLM-related endpoints.
Provides the main endpoint for agent decision making.
"""

import logging
import time

from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.config.settings import settings
from app.exceptions.errors import LLMBaseError
from app.models.request import LLMRequest, ReflectionRequest
from app.models.response import LLMResponse, ReflectionResponse
from app.services.json_parser import JSONParser
from app.services.llm_client import LLMClient
from app.services.prompt_builder import PromptBuilder

logger = logging.getLogger(__name__)

router = APIRouter()


def get_llm_client() -> LLMClient:
    """Dependency injection for LLM client."""
    return LLMClient()


def get_json_parser() -> JSONParser:
    """Dependency injection for JSON parser."""
    return JSONParser()


def get_prompt_builder() -> PromptBuilder:
    """Dependency injection for Prompt builder."""
    return PromptBuilder()


@router.post(
    "/decision",
    response_model=LLMResponse,
    summary="Get agent action decision from LLM",
    description="Main endpoint for agent decision making. Calls LLM API and returns structured action decision.",
    responses={
        200: {
            "description": "Successful LLM call with action decision",
            "model": LLMResponse,
        },
        400: {
            "description": "Invalid request payload",
        },
    },
)
async def get_decision(
    request: LLMRequest,
    llm_client: LLMClient = Depends(get_llm_client),
    json_parser: JSONParser = Depends(get_json_parser),
    prompt_builder: PromptBuilder = Depends(get_prompt_builder),
):
    """
    Get agent's action decision from LLM.

    Flow:
    1. Build enhanced prompt with context isolation
    2. Call LLM API with structured output request
    3. Parse JSON response into ActionDecision
    4. Return structured response with token usage

    Returns: LLMResponse with action, target_post_id, content, and token stats
    """
    start_time = time.time()

    logger.info(
        f"Processing decision request: model={request.model_name}, "
        f"prompt_len={len(request.system_prompt)}, "
        f"context_len={len(request.context)}, "
        f"memories={len(request.memories) if request.memories else 0}"
    )

    try:
        # Step 1: Build prompt with security measures
        # Note: request.system_prompt and request.context are already combined by Java
        # But we still apply our enhancements for JSON output format
        # Prompt building runs ~26 regexes over the whole context. On a single
        # uvicorn worker that CPU work blocks every other in-flight request, so it
        # is pushed to the threadpool.
        enhanced_system, user_message = await run_in_threadpool(
            prompt_builder.build_full_prompt,
            system_prompt=request.system_prompt,
            context=request.context,
            memories=request.memories,
        )

        # Reuse the validated request instead of rebuilding it: constructing a new
        # LLMRequest re-ran every validator (including the DNS-resolving SSRF guard)
        # on data that was already checked.
        enhanced_request = request.model_copy(
            update={"system_prompt": enhanced_system, "context": user_message}
        )

        # Step 2: Call LLM API
        response_body, usage = await llm_client.call_llm(enhanced_request)

        # Extract content from response
        raw_content = llm_client._extract_content(response_body)
        model = llm_client.get_model_from_response(response_body)

        # Step 3: Parse JSON into ActionDecision
        response_time_ms = int((time.time() - start_time) * 1000)
        decision = await run_in_threadpool(
            json_parser.parse, raw_content, response_time_ms=response_time_ms
        )

        # Step 4: Build response
        response = LLMResponse.from_decision(
            decision=decision,
            total_tokens=usage.get("total_tokens"),
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            model=model,
            response_time_ms=response_time_ms,
        )

        logger.info(
            f"Decision complete: action={response.action}, "
            f"tokens={response.total_tokens}, "
            f"time={response_time_ms}ms"
        )

        return response

    except LLMBaseError as e:
        # Handled by exception handlers
        raise e

    except Exception as e:
        # Unexpected error - return ignore
        response_time_ms = int((time.time() - start_time) * 1000)
        logger.exception(f"Unexpected error in decision processing: {str(e)}")

        # The exception text can contain internal paths, hostnames or echoed
        # credentials; it belongs in the log above, not in the response.
        return LLMResponse.create_ignore_response(
            error_message="Internal service error",
            response_time_ms=response_time_ms,
        )


@router.post(
    "/decision/direct",
    response_model=LLMResponse,
    summary="Direct LLM call (bypass prompt enhancement)",
    description="Call LLM directly without prompt enhancement. Used for testing.",
)
async def get_decision_direct(
    request: LLMRequest,
    llm_client: LLMClient = Depends(get_llm_client),
    json_parser: JSONParser = Depends(get_json_parser),
):
    """
    Direct LLM call without prompt enhancement.

    Used for testing or when prompts are already prepared.
    """
    start_time = time.time()

    try:
        response_body, usage = await llm_client.call_llm(request)
        raw_content = llm_client._extract_content(response_body)
        model = llm_client.get_model_from_response(response_body)

        response_time_ms = int((time.time() - start_time) * 1000)
        decision = json_parser.parse(raw_content, response_time_ms=response_time_ms)

        return LLMResponse.from_decision(
            decision=decision,
            total_tokens=usage.get("total_tokens"),
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            model=model,
            response_time_ms=response_time_ms,
        )

    except LLMBaseError as e:
        raise e


@router.post(
    "/reflection",
    response_model=ReflectionResponse,
    summary="Distill persona traits from recent agent behaviour",
    description=(
        "Internal endpoint for the daily reflection job. Same authentication and "
        "credential passing as /decision. Always returns HTTP 200: on any failure "
        "(model error, timeout, unparsable output) success=false with empty lists."
    ),
)
async def get_reflection(
    request: ReflectionRequest,
    llm_client: LLMClient = Depends(get_llm_client),
    json_parser: JSONParser = Depends(get_json_parser),
    prompt_builder: PromptBuilder = Depends(get_prompt_builder),
):
    """
    Distill PERSONA_TRAIT cards from an agent's recent behaviour.

    Flow:
    1. Sanitize behaviours / existing traits and build the reflection prompt
    2. Call the LLM with a forced `submit_reflection` tool call
    3. Parse and validate the output (ids checked against existing_traits, counts
       clamped to limits)
    4. Return new / updated / deprecated traits plus token usage

    Failure policy: this is a background job, so every failure degrades to
    success=false with three empty lists instead of an HTTP error. The backend then
    writes nothing and retries the next day, and no unverified content can reach the
    database.
    """
    start_time = time.time()
    usage: dict = {}
    model = None

    logger.info(
        "Processing reflection request: agent_id=%s, model=%s, behaviors=%d, "
        "existing_traits=%d, max_new=%d",
        request.agent_id,
        request.model_name,
        len(request.recent_behaviors),
        len(request.existing_traits),
        request.limits.max_new_traits,
    )

    try:
        prompt = await run_in_threadpool(
            prompt_builder.build_reflection_prompt,
            recent_behaviors=request.recent_behaviors,
            existing_traits=request.existing_traits,
            limits=request.limits,
            system_prompt=request.system_prompt,
        )

        if prompt.behavior_count == 0:
            # Nothing survived sanitisation (or nothing was sent). Distilling traits
            # from an empty behaviour list can only hallucinate, so the call is
            # skipped: a successful, empty, free answer.
            logger.info(
                "Reflection skipped: no usable recent behaviours (agent_id=%s)",
                request.agent_id,
            )
            return ReflectionResponse(
                success=True,
                total_tokens=0,
                response_time_ms=int((time.time() - start_time) * 1000),
            )

        # Reuse the already-validated credentials without re-running the validators
        # (the SSRF guard resolves DNS; ReflectionRequest ran the same check).
        upstream_request = LLMRequest.model_construct(
            api_key=request.api_key,
            base_url=request.base_url,
            model_name=request.model_name,
            system_prompt=prompt.system_prompt,
            context=prompt.user_message,
            memories=None,
            max_tokens=request.max_tokens,
            temperature=request.temperature,
        )

        response_body, usage = await llm_client.call_llm(
            upstream_request,
            request_body=llm_client.build_reflection_body(
                upstream_request,
                max_new_traits=request.limits.max_new_traits,
            ),
        )

        raw_content = llm_client._extract_content(response_body)
        model = llm_client.get_model_from_response(response_body)

        response_time_ms = int((time.time() - start_time) * 1000)
        result = await run_in_threadpool(
            json_parser.parse_reflection,
            raw_content,
            allowed_trait_ids=[trait.id for trait in request.existing_traits],
            max_new_traits=request.limits.max_new_traits,
            max_total_traits=request.limits.max_total_traits,
            existing_trait_count=len(request.existing_traits),
            response_time_ms=response_time_ms,
        )

        response = ReflectionResponse.from_result(
            result=result,
            total_tokens=usage.get("total_tokens"),
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            model=model,
            response_time_ms=response_time_ms,
        )

        logger.info(
            "Reflection complete: agent_id=%s new=%d updated=%d deprecated=%d "
            "tokens=%s time=%dms",
            request.agent_id,
            len(response.new_traits),
            len(response.updated_traits),
            len(response.deprecated_trait_ids),
            response.total_tokens,
            response_time_ms,
        )

        return response

    except LLMBaseError as exc:
        # Timeout / upstream API error / unparsable JSON. Tokens are reported when
        # the provider already billed the call (usage is set once the HTTP call
        # returned 200), so the backend can still charge the agent.
        response_time_ms = int((time.time() - start_time) * 1000)
        logger.warning(
            "Reflection failed for agent_id=%s: code=%s message=%s",
            request.agent_id,
            exc.error_code,
            exc.message,
        )
        # Only the fixed error code leaves the service: exc.message can quote the
        # provider's response body, which may echo the API key.
        return ReflectionResponse.create_empty_response(
            error_message=f"Reflection failed ({exc.error_code})",
            total_tokens=usage.get("total_tokens", 0),
            response_time_ms=response_time_ms,
            model=model,
        )

    except Exception as exc:  # noqa: BLE001 - background job must never 500
        response_time_ms = int((time.time() - start_time) * 1000)
        logger.exception(f"Unexpected error in reflection processing: {str(exc)}")
        return ReflectionResponse.create_empty_response(
            error_message="Internal service error",
            total_tokens=usage.get("total_tokens", 0),
            response_time_ms=response_time_ms,
            model=model,
        )


@router.get(
    "/health",
    summary="LLM service health check",
)
async def health():
    """
    Health check for LLM service.
    """
    return {
        "status": "healthy",
        "timeout_seconds": settings.REQUEST_TIMEOUT_SECONDS,
        "default_max_tokens": settings.DEFAULT_MAX_TOKENS,
        "default_temperature": settings.DEFAULT_TEMPERATURE,
    }