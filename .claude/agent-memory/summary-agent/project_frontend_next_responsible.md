---
name: frontend_agent_next_responsible
description: Frontend-Agent 是 Phase 1 最后责任人，负责 Agent Lab UI 和状态展示
type: project
---

# Next Responsible Agent: Frontend-Agent

**Date:** 2026-03-31
**Status:** READY TO START (Unblocked)

## Prerequisites Complete

- [x] Java Backend API ready (6 endpoints)
- [x] Python LLM Service ready
- [x] Docker Compose configuration available
- [x] API contracts documented

## Responsibility

Frontend-Agent is the **final agent** for Phase 1 completion. Expected deliverables:

### Core Features

1. **Agent Lab Page**
   - Agent list view with status indicators
   - Create/Edit/Delete agent forms
   - API key input with masked display

2. **Real-Time Status Display**
   - Current loop status (running/paused/stopped)
   - Token consumption meter
   - Recent activity feed

3. **Configuration UI**
   - Agent prompt editor
   - Schedule configuration
   - Token budget settings

## Technical Stack (Suggested)

- Framework: React or Vue (user preference)
- State: Zustand or Pinia
- HTTP: Axios or fetch
- Real-time: WebSocket for loop status
- Styling: Tailwind CSS

## API Endpoints Available

```
Authentication:
  POST /api/auth/login
  POST /api/auth/register
  POST /api/auth/logout

Agents:
  GET    /api/agents
  POST   /api/agents
  GET    /api/agents/{id}
  PUT    /api/agents/{id}
  DELETE /api/agents/{id}
  GET    /api/agents/{id}/status
```

## Integration Test Sequence

1. Start Docker Compose: `docker-compose up -d`
2. Verify Java health: `curl http://localhost:8080/health`
3. Verify Python health: `curl http://localhost:8000/health`
4. Test auth flow in UI
5. Test agent CRUD in UI
6. Test agent loop status display

## Dependencies

| Dependency | Provider | Status |
|------------|----------|--------|
| Auth API | Java-Backend | READY |
| Agent CRUD API | Java-Backend | READY |
| LLM Service | Python-AI-Side | READY |
| WebSocket Status | Java-Backend | NEEDS VERIFICATION |

## How to Start

1. Clone/verify project structure at `D:/My/Java/project/agentCommunity/`
2. Review Java API at `pulse-backend/src/main/java/`
3. Review Python service at `pulse-ai-side/`
4. Create frontend directory: `pulse-frontend/` (or as specified)
5. Begin with authentication flow implementation

## Success Criteria

Phase 1 is complete when:
- [ ] User can log in and see agent list
- [ ] User can create/edit/delete agents
- [ ] User can start/stop agent loop
- [ ] User can see real-time loop status
- [ ] E2E flow works with Java + Python + Frontend