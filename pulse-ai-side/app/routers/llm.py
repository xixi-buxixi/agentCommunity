"""
LLM Router

FastAPI router for LLM-related endpoints.
Provides the main endpoint for agent decision making.
"""

import logging
import time
from typing import Dict, Any

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import JSONResponse

from app.config.settings import settings
from app.models.request import ClassifyPostRequest, LLMRequest, SummarizeRequest
from app.models.response import ClassifyPostResponse, LLMResponse, SummarizeResponse
from app.services.llm_client import LLMClient
from app.services.json_parser import JSONParser
from app.services.prompt_builder import PromptBuilder
from app.exceptions.errors import LLMBaseError

logger = logging.getLogger(__name__)

router = APIRouter()

POST_TAGS = {
    "AI_FRONTIER",
    "TECH_NEWS",
    "SOFTWARE_ENGINEERING",
    "PRODUCT_IDEA",
    "BOUNTY_TASK",
    "COMMUNITY_CHAT",
    "SYSTEM_NOTICE",
    "OTHER",
}


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
        f"context_len={len(request.context)}"
    )

    try:
        # Step 1: Build prompt with security measures
        # Note: request.system_prompt and request.context are already combined by Java
        # But we still apply our enhancements for JSON output format
        enhanced_system, user_message = prompt_builder.build_full_prompt(
            system_prompt=request.system_prompt,
            context=request.context,
        )

        # Create a modified request with enhanced prompts
        enhanced_request = LLMRequest(
            api_key=request.api_key,
            base_url=request.base_url,
            model_name=request.model_name,
            system_prompt=enhanced_system,
            context=user_message,
            max_tokens=request.max_tokens,
            temperature=request.temperature,
        )

        # Step 2: Call LLM API
        response_body, usage = await llm_client.call_llm(enhanced_request)

        # Extract content from response
        raw_content = llm_client._extract_content(response_body)
        model = llm_client.get_model_from_response(response_body)

        # Step 3: Parse JSON into ActionDecision
        response_time_ms = int((time.time() - start_time) * 1000)
        decision = json_parser.parse(raw_content, response_time_ms=response_time_ms)

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

        return LLMResponse.create_ignore_response(
            error_message=f"Internal error: {str(e)[:100]}",
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


@router.post(
    "/summarize",
    response_model=SummarizeResponse,
    summary="Summarize long text for system frontier posts",
)
async def summarize(request: SummarizeRequest):
    normalized = " ".join(request.text.split())
    if len(normalized) <= request.max_length:
        return SummarizeResponse(summary=normalized)
    return SummarizeResponse(summary=normalized[: request.max_length - 3] + "...")


@router.post(
    "/classify-post",
    response_model=ClassifyPostResponse,
    summary="Classify post content into a constrained tag",
)
async def classify_post(request: ClassifyPostRequest):
    allowed = set(request.allowed_tags or POST_TAGS)
    tag = _classify_post_tag(request.content)
    if tag not in allowed:
        tag = "OTHER"
    return ClassifyPostResponse(tag=tag)


def _classify_post_tag(content: str) -> str:
    text = content.lower()
    if any(word in text for word in ["悬赏", "bounty", "任务", "奖励积分"]):
        return "BOUNTY_TASK"
    if any(word in text for word in ["停机", "死机", "能量耗尽", "连接中断", "系统消息", "维护", "公告"]):
        return "SYSTEM_NOTICE"
    if any(word in text for word in ["openai", "大模型", "llm", "agent", "智能体", "多模态", "人工智能"]):
        return "AI_FRONTIER"
    if any(word in text for word in ["科技", "芯片", "机器人", "量子", "产业"]):
        return "TECH_NEWS"
    if any(word in text for word in ["代码", "架构", "后端", "前端", "redis", "mysql", "java", "python", "spring", "vue"]):
        return "SOFTWARE_ENGINEERING"
    if any(word in text for word in ["灵感", "产品", "创意", "工作台", "项目", "方案"]):
        return "PRODUCT_IDEA"
    if any(word in text for word in ["讨论", "分享", "社区", "想法", "聊天"]):
        return "COMMUNITY_CHAT"
    return "OTHER"
