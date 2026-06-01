# AI Side Module Navigation

## Module Goal

`pulse-ai-side` is the FastAPI LLM gateway for Pulse. It builds prompts, isolates context, calls OpenAI-compatible model providers, parses structured JSON decisions and degrades safely when model calls fail.

## Tech Stack

- Python
- FastAPI
- Uvicorn
- Pydantic
- HTTPX
- OpenAI-compatible provider API
- Pytest

## Owned Interfaces

- `GET /health`
- `GET /v1/llm/health`
- `POST /v1/llm/decision`
- Environment configuration in `.env.example`
- Docker runtime files in `Dockerfile` and `docker-compose.yml`

## Dependencies

- `pulse-backend` calls AI Side for Agent decisions.
- External LLM providers receive OpenAI-compatible requests.
- Environment variables control timeout, retry, logging and service runtime behavior.

## Data Flow

1. Backend sends model credentials, model name, system prompt and community context to `/v1/llm/decision`.
2. Router validates request data and delegates to service logic.
3. Prompt builder isolates context and applies injection-protection framing.
4. LLM client calls the configured provider with timeout and retry settings.
5. JSON parser extracts and validates a structured decision.
6. Errors, timeouts and invalid responses return a safe ignore action.

## Directory Guide

- `app/main.py`: FastAPI app bootstrap.
- `app/routers`: HTTP route handlers.
- `app/models`: request and response schemas.
- `app/services`: prompt building, LLM calls and JSON parsing.
- `app/middleware`: service authentication middleware.
- `app/exceptions`: error types and handlers.
- `app/config`: environment settings.
- `tests`: pytest coverage for service behavior.

## Constraints

- Failure must degrade to safe ignore behavior so backend Agent loops do not perform unsafe actions.
- Prompt injection protections and context isolation markers must remain explicit.
- Request or response shape changes require overview, backend and AI Side task updates.
- This file is stable navigation only; dynamic AI Side task state belongs in `/agentsPrompt/modules/ai-side/tasks.md`.

## Verification

```bash
rtk powershell -NoProfile -Command "cd pulse-ai-side; pytest tests -v"
rtk powershell -NoProfile -Command "cd pulse-ai-side; python -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
```
