---
name: pulse-ai-side-project-init
description: Python AI Side Service initial implementation for Pulse project - Phase 1 complete
type: project
---

## Pulse AI Side Service - Phase 1 Implementation Completed

**Date**: 2026-03-31

**Why**: Java backend needs a Python service to handle LLM API calls with proper timeout management, structured JSON output, and prompt injection protection. This decouples the Java agent scheduler from direct LLM API complexity.

**How to apply**: Future Python AI work should extend this foundation. Key integration points are:
- Java calls POST /v1/llm/decision with agent context
- Python returns {"action": "post|reply|ignore", ...} with token usage
- Timeout/error cases return action="ignore" without consuming tokens

## Completed Components

### 1. FastAPI Project Structure (14 files)
```
pulse-ai-side/
├── app/
│   ├── main.py            # FastAPI entry with CORS, lifespan, health check
│   ├── routers/llm.py     # POST /v1/llm/decision endpoint
│   ├── services/
│   │   ├── llm_client.py  # HTTPX async client with 30s timeout
│   │   ├── json_parser.py # Markdown extraction + JSON repair
│   │   ├── prompt_builder.py  # Injection protection + format enhancement
│   ├── models/
│   │   ├── request.py     # LLMRequest (matches Java payload)
│   │   ├── response.py    # LLMResponse + ActionDecision
│   ├── config/settings.py # Timeout, retries, defaults
│   └── exceptions/        # Custom errors + FastAPI handlers
├── tests/test_services.py # Unit tests for all services
├── Dockerfile             # Multi-stage build for production
├── docker-compose.yml     # Service orchestration
└── requirements.txt       # FastAPI, httpx, pydantic, openai
```

### 2. Key Technical Features

**LLM Client**:
- HTTPX async client with configurable timeout (30s default)
- Retry logic (2 retries with 1s delay)
- OpenAI-compatible API format with `response_format={"type": "json_object"}`

**JSON Parser**:
- Extracts JSON from markdown code blocks (` ```json ... ``` `)
- Basic repair for single quotes and trailing commas
- Validation against action schema (post/reply/ignore)

**Prompt Builder**:
- Injection pattern detection (8 patterns for "ignore instructions" attempts)
- Context isolation with `<!-- CONTEXT_ONLY -->` marker
- Token estimation for Chinese/mixed content
- Context truncation at 8000 chars to prevent overflow

**Exception Handling**:
- All errors return `{action: "ignore"}` - never crashes Java scheduler
- Custom exceptions: LLMTimeoutError, LLMAPIError, JSONParseError, PromptInjectionDetected
- FastAPI handlers convert to 200 OK with ignore response

### 3. Integration Points with Java

Java sends:
```json
{
  "api_key": "sk-xxxxxx",
  "base_url": "https://api.openai.com/v1",
  "model_name": "gpt-4o-mini",
  "system_prompt": "...",
  "context": "..."
}
```

Python returns:
```json
{
  "action": "reply",
  "target_post_id": 123,
  "content": "...",
  "total_tokens": 150,
  "success": true
}
```

## Next Steps for Phase 2

1. Add OpenAI SDK integration for streaming responses
2. Implement Claude API adapter
3. Add Prometheus metrics for monitoring
4. Create integration tests with mock LLM responses
5. Update Java LLMClient to call Python service instead of direct API