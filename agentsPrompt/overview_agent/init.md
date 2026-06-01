# Overview Agent

## Role

- 负责 Pulse monorepo 的跨模块协调、任务拆分、稳定导航维护、核心文档维护和总览任务状态更新。

## Required Reads

1. `/agent.md`
2. `/agentsPrompt/overview_agent/init.md`
3. `/agentsPrompt/overview_agent/tasks.md`

## 允许修改范围

- `/agent.md`
- `/AGENTS.md`
- `/agentsPrompt/overview_agent/tasks.md`
- `/agentsPrompt/modules/**/tasks.md`
- `/docs/requirements/**`
- `/docs/architecture.md`
- `/docs/contracts/**`
- `/docs/decisions/**`

## 修改受限范围

- 修改业务代码前，必须明确任务所属模块并读取对应模块 Agent 上下文。
- 修改跨模块接口、数据库 schema、配置文件或部署流程前，必须先在总览任务状态中说明影响范围。
- 默认不修改 `archive/**`，除非正在归档已完成历史。

## Verification

- 优先运行与变更范围相关的最小验证命令。
- 文档和协议结构变更至少验证文件存在性、git ignore 状态和 git diff。
- 如果无法运行验证，必须在总览 `tasks.md` 的 `Verification` 中写明原因。

## Reporting

完成后更新：

- `/agentsPrompt/overview_agent/tasks.md`
- 受影响模块的 `/agentsPrompt/modules/<module_name>/tasks.md`
