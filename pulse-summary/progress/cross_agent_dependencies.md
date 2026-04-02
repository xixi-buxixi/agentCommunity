---
name: cross_agent_dependency_status
description: 跨 Agent 依赖状态追踪，识别阻塞风险和责任人
type: project
---

# Cross-Agent Dependency Status

**Snapshot Date:** 2026-03-31

## Dependency Graph

```
                    ┌─────────────────┐
                    │   Frontend      │
                    │   Agent         │
                    │   (DONE)        │
                    └────────┬────────┘
                             │ depends on
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
┌─────────────────────┐         ┌─────────────────────┐
│   Java Backend      │◄───────►│   Python AI Side    │
│   Agent (DONE)      │   HTTP   │   Agent (DONE)     │
│   60 files          │   calls  │   14 files         │
└─────────────────────┘          └─────────────────────┘
```

## Integration Points

### Java Backend <-> Python AI Side

| Endpoint | Direction | Purpose | Status |
|----------|-----------|---------|--------|
| `POST /v1/llm/decide` | Java -> Python | LLM decision call | READY |
| `GET /health` | Java -> Python | Health check | READY |

**Integration Contract:**
```python
# Request
{
  "agent_id": "uuid",
  "context_posts": [...],
  "user_prompt": "string"
}

# Response
{
  "action": "post" | "reply" | "ignore",
  "content": "string?",      # for post/reply
  "reply_to_id": "uuid?"     # for reply
}
```

### Frontend <-> Java Backend

| Endpoint | Direction | Purpose | Status |
|----------|-----------|---------|--------|
| `POST /api/auth/login` | Frontend -> Java | User auth | READY |
| `GET /api/agents` | Frontend -> Java | List agents | READY |
| `POST /api/agents` | Frontend -> Java | Create agent | READY |
| `PUT /api/agents/{id}` | Frontend -> Java | Update agent | READY |
| `DELETE /api/agents/{id}` | Frontend -> Java | Delete agent | READY |
| `GET /api/agents/{id}/status` | Frontend -> Java | Loop status | READY |

## Risk Assessment

### Resolved Risks
- **RISK-001**: Python LLM service availability - RESOLVED (service implemented)
- **RISK-002**: Java-Python integration contract - RESOLVED (contract defined)

### Active Risks (Phase 2)
- **RISK-003**: E2E integration testing coverage - MITIGATE (need test suite)
- **RISK-004**: WebSocket connection stability - MONITORING

## Responsibility Matrix

| Component | Owner Agent | Dependencies | Current State |
|-----------|------------|--------------|---------------|
| Authentication | Java-Backend | None | COMPLETE |
| Agent CRUD | Java-Backend | Authentication | COMPLETE |
| Agent Loop Engine | Java-Backend | Python LLM | COMPLETE |
| LLM Decision Service | Python-AI-Side | None | COMPLETE |
| Agent Lab UI | Frontend | Java API | COMPLETE |
| Loop Status UI | Frontend | Java WebSocket | COMPLETE |