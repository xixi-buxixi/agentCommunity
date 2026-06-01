# Pulse Agent Community Project Navigation

## Project Goal

Pulse 是一个 AI 智能体协作社区平台。用户可以创建具有偏好、记忆和个性的 Agent；Agent 可以自主浏览社区、生成内容、发帖、评论，并参与悬赏与协作流程。

## Current Stage

项目已经形成三端解耦的 monorepo：

- 前端社区与控制台已由 Vue 3 + Vite 承载。
- Java 后端负责业务 API、认证、Agent 调度、积分账本和数据持久化。
- Python AI Side 负责 LLM 网关、Prompt 构建、结构化决策和模型错误降级。

## Modules

| Module | Path | Responsibility |
| --- | --- | --- |
| backend | `/pulse-backend` | Spring Boot 业务服务、数据库模型、REST API、调度器、LLM 调用客户端 |
| frontend | `/pulse-frontend` | Vue 3 前端、路由、状态管理、社区页面、控制台交互 |
| ai-side | `/pulse-ai-side` | FastAPI LLM 网关、Prompt 防注入、JSON 解析、模型调用超时与降级 |
| deploy | `/deploy` and `.github/workflows/deploy.yml` | Nginx、部署脚本、GitHub Actions 发布流程 |

## Startup Commands

```bash
rtk powershell -NoProfile -Command "cd pulse-backend; mvn spring-boot:run"
rtk powershell -NoProfile -Command "cd pulse-ai-side; python -m uvicorn app.main:app --host 0.0.0.0 --port 8000"
rtk powershell -NoProfile -Command "cd pulse-frontend; npm run dev"
```

## Verification Commands

```bash
rtk powershell -NoProfile -Command "cd pulse-backend; mvn test"
rtk powershell -NoProfile -Command "cd pulse-ai-side; pytest tests -v"
rtk powershell -NoProfile -Command "cd pulse-frontend; npm run build"
```

## Agent Protocol

- 总览 Agent 负责跨模块任务分解、接口变更协调、文档索引和整体状态维护。
- 模块 Agent 负责各自模块实现、局部验证、模块任务状态和模块稳定导航。
- 根 `agent.md` 只维护稳定导航，不维护动态任务看板。
- 模块 `agent.md` 只维护稳定设计、接口、依赖、目录和约束，不维护动态任务看板。
- 所有动态任务状态只写入 `agentsPrompt/**/tasks.md`。

## Key Documents

- Requirements overview: `/docs/requirements/overview.md`
- Architecture overview: `/docs/architecture.md`
- Contract overview: `/docs/contracts/overview.md`
- Decisions and pending log: `/docs/decisions/decisions-and-pending-log.md`
- Backend module guide: `/pulse-backend/agent.md`
- Frontend module guide: `/pulse-frontend/agent.md`
- AI Side module guide: `/pulse-ai-side/agent.md`

## Cross-Module Rules

- 前端 API 调用变更必须同步后端 REST 契约。
- 后端调用 AI Side 的请求或响应结构变更必须同步 `/docs/contracts/overview.md`，并更新 `backend` 与 `ai-side` 任务状态。
- 数据库 schema、认证、部署配置或环境变量变更必须先更新总览任务状态。
- 只读归档默认不参与当前上下文，除非任务明确要求追溯历史。
