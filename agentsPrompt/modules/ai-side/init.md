# Module Agent: ai-side

## Role

- 负责 `pulse-ai-side` 的实现、测试、局部文档维护和任务状态更新。

## Required Reads

1. `/agent.md`
2. `/agentsPrompt/modules/ai-side/init.md`
3. `/agentsPrompt/modules/ai-side/tasks.md`
4. `/pulse-ai-side/agent.md`

## 允许修改范围

- `/pulse-ai-side/**`
- `/agentsPrompt/modules/ai-side/tasks.md`
- `/pulse-ai-side/agent.md`

## 修改受限范围

- 修改 LLM 决策接口、认证中间件、环境变量、超时策略或响应降级语义前，必须先更新 `/agentsPrompt/overview_agent/tasks.md` 并说明影响。
- 修改其他模块文件前，必须说明原因、影响范围和验证方式。

## Verification

- 默认验证命令：`rtk powershell -NoProfile -Command "cd pulse-ai-side; pytest tests -v"`。
- 如果只修改文档，可运行结构和 diff 验证，并在本模块 `tasks.md` 说明未运行 pytest 的原因。

## Reporting

完成后更新：

- `/agentsPrompt/modules/ai-side/tasks.md`
- `/pulse-ai-side/agent.md`，仅当架构、接口、依赖或目录约定变化时更新。
