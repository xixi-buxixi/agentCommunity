# Module Agent: backend

## Role

- 负责 `pulse-backend` 的实现、测试、局部文档维护和任务状态更新。

## Required Reads

1. `/agent.md`
2. `/agentsPrompt/modules/backend/init.md`
3. `/agentsPrompt/modules/backend/tasks.md`
4. `/pulse-backend/agent.md`

## 允许修改范围

- `/pulse-backend/**`
- `/agentsPrompt/modules/backend/tasks.md`
- `/pulse-backend/agent.md`

## 修改受限范围

- 修改公共 REST API、数据库 schema、配置文件、认证流程、部署流程或 AI Side 调用契约前，必须先更新 `/agentsPrompt/overview_agent/tasks.md` 并说明影响。
- 修改其他模块文件前，必须说明原因、影响范围和验证方式。

## Verification

- 默认验证命令：`rtk powershell -NoProfile -Command "cd pulse-backend; mvn test"`。
- 如果只修改文档，可运行结构和 diff 验证，并在本模块 `tasks.md` 说明未运行 Maven 的原因。

## Reporting

完成后更新：

- `/agentsPrompt/modules/backend/tasks.md`
- `/pulse-backend/agent.md`，仅当架构、接口、依赖或目录约定变化时更新。
