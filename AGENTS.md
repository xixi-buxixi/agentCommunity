# Agent Entry Rules

本文件是编码 Agent 的项目入口规则。开始任何任务前，必须先读取根 `/agent.md`，再按任务范围读取对应 Agent 的 `init.md` 和 `tasks.md`。

## Global Rules

- 所有 shell 命令必须使用 `rtk` 前缀；规则来源：`C:\Users\15070\.codex\RTK.md`。
- 必须直接修改目标文件并落盘，不能只输出 diff、补丁文本或“请手动修改”的说明。
- 默认读取最少上下文：先读稳定导航，再读当前任务状态；不要默认读取归档历史。
- 动态任务状态只写入 `agentsPrompt/**/tasks.md`，不要在根 `agent.md` 或模块 `agent.md` 维护任务看板。
- 修改后必须运行可用的验证命令，并把命令、结果和失败摘要写入对应 `tasks.md`。
- 发现阻塞时，立即更新对应 `tasks.md` 的 `Blocked` 和 `Next`。

## Required Reads

### Overview Work

1. `/agent.md`
2. `/agentsPrompt/overview_agent/init.md`
3. `/agentsPrompt/overview_agent/tasks.md`

### Module Work

1. `/agent.md`
2. `/agentsPrompt/modules/<module_name>/init.md`
3. `/agentsPrompt/modules/<module_name>/tasks.md`
4. `/<module_root>/agent.md`

模块名映射：

- `backend` -> `/pulse-backend/agent.md`
- `frontend` -> `/pulse-frontend/agent.md`
- `ai-side` -> `/pulse-ai-side/agent.md`

## State Update Rules

- 完成一个子任务后，更新对应 `tasks.md`。
- 跨模块接口、数据库 schema、配置文件或共享类型变更前，先更新 `/agentsPrompt/overview_agent/tasks.md` 并说明影响。
- 跨模块修改完成后，同时更新总览任务和受影响模块任务。
- 验证完成后，记录验证命令、结果和必要说明。
- 长历史写入对应 `archive/YYYY-MM.md`，默认启动不读取归档。
