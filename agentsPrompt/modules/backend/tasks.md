# Task State: backend

## Current
- Task ID: backend-2026-06-01-workbench-langgraph-requirements
- Goal: 记录工作台 LangGraph 多智能体协作与 LLM WIKI 需求草案对后端模块的影响。
- Scope: `/agentsPrompt/modules/backend/tasks.md`；需求文档位于 `/docs/requirements/workbench-langgraph-llm-wiki.md`
- Status: done
- Owner: Codex
- Last Updated: 2026-06-01

## Done Summary
- 已确认后端当前通过 `AgentLoopScheduler` 执行单 Agent 社区调度，并通过 `LLMClient` 调用 AI Side `/v1/llm/decision`。
- 新需求草案建议后端作为工作台项目、运行记录、权限、来源关联、产物和 LLM WIKI 记忆的事实源。
- 本次未修改后端业务代码、数据库 schema 或 REST 契约。

## Previous Done Summary
- 已将 `pulse-backend` 确认为 Java 21 + Spring Boot 3.2 后端模块。

## In Progress
- 无

## Blocked
- Blocker: 无
- Needed input: 无
- Since: 2026-06-01

## Decisions
- 2026-06-01: 工作台编排应作为显式触发能力接入，不直接替换现有 `AgentLoopScheduler`。
- 2026-06-01: 后端仍负责权限校验、密钥解密、运行记录、产物持久化和稳定 REST API。
- 后端模块拥有 REST API、认证、Agent 调度、账本、悬赏、排行、数据库 schema 和 LLM 客户端。
- 修改 AI Side 调用请求或响应结构时，必须同步 `ai-side` 模块任务和契约文档。

## Verification
- Command: `rtk powershell -NoProfile -Command '$paths=@("docs/requirements/workbench-langgraph-llm-wiki.md","agentsPrompt/overview_agent/tasks.md","agentsPrompt/modules/frontend/tasks.md","agentsPrompt/modules/backend/tasks.md","agentsPrompt/modules/ai-side/tasks.md"); $missing=$paths | Where-Object { -not (Test-Path $_) }; if ($missing) { "MISSING:"; $missing; exit 1 } else { "All workbench requirements files exist." }'`
- Result: pass
- Notes: 需求文档和任务状态文件均存在；本次未运行 `mvn test`，因为未修改后端代码。
- Command: `rtk proxy git diff --check`
- Result: pass
- Notes: 退出码为 0；输出仅包含 `.gitignore` 的 LF/CRLF 提示。
- Command: not_run
- Result: not_run
- Notes: 本次仅初始化协议文档，模块运行验证由具体后端任务触发。

## Next
- 需求评审通过后，先设计工作台 API、schema、运行日志和后端到 AI Side 编排契约，再进入实现。
