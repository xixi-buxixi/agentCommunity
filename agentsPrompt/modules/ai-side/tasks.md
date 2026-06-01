# Task State: ai-side

## Current
- Task ID: ai-side-2026-06-01-workbench-langgraph-requirements
- Goal: 记录工作台 LangGraph 多智能体协作与 LLM WIKI 需求草案对 AI Side 模块的影响。
- Scope: `/agentsPrompt/modules/ai-side/tasks.md`；需求文档位于 `/docs/requirements/workbench-langgraph-llm-wiki.md`
- Status: done
- Owner: Codex
- Last Updated: 2026-06-01

## Done Summary
- 已确认 AI Side 当前是 FastAPI LLM 网关，核心接口为 `/v1/llm/decision`，负责 Prompt 构建、模型调用、JSON 解析和安全 ignore 降级。
- 新需求草案建议 AI Side 新增与现有决策接口隔离的工作台 LangGraph 编排能力，承担 planner、researcher、analyst、critic、writer、memory_curator、supervisor 等节点。
- 本次未修改 AI Side 业务代码、依赖或接口。

## Previous Done Summary
- 已将 `pulse-ai-side` 确认为 Python + FastAPI LLM 网关模块。

## In Progress
- 无

## Blocked
- Blocker: 无
- Needed input: 无
- Since: 2026-06-01

## Decisions
- 2026-06-01: LangGraph 编排先作为工作台新能力设计，不改变现有 `/v1/llm/decision` 的单 Agent 社区决策语义。
- 2026-06-01: LLM WIKI 记忆写入需保留来源、置信度、范围和版本，且失败时安全降级。
- AI Side 模块拥有 LLM 决策接口、Prompt 构建、防注入、JSON 解析、模型调用和错误降级。
- 失败或超时默认返回安全的忽略动作，避免 Agent 误行为。

## Verification
- Command: `rtk powershell -NoProfile -Command '$paths=@("docs/requirements/workbench-langgraph-llm-wiki.md","agentsPrompt/overview_agent/tasks.md","agentsPrompt/modules/frontend/tasks.md","agentsPrompt/modules/backend/tasks.md","agentsPrompt/modules/ai-side/tasks.md"); $missing=$paths | Where-Object { -not (Test-Path $_) }; if ($missing) { "MISSING:"; $missing; exit 1 } else { "All workbench requirements files exist." }'`
- Result: pass
- Notes: 需求文档和任务状态文件均存在；本次未运行 `pytest tests -v`，因为未修改 AI Side 代码。
- Command: `rtk proxy git diff --check`
- Result: pass
- Notes: 退出码为 0；输出仅包含 `.gitignore` 的 LF/CRLF 提示。
- Command: not_run
- Result: not_run
- Notes: 本次仅初始化协议文档，模块测试验证由具体 AI Side 任务触发。

## Next
- 需求评审通过后，先设计 LangGraph 状态、节点、检查点、失败降级和后端调用契约，再进入实现。
