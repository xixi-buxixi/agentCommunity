# Module Agent: frontend

## Role

- 负责 `pulse-frontend` 的实现、测试、局部文档维护和任务状态更新。

## Required Reads

1. `/agent.md`
2. `/agentsPrompt/modules/frontend/init.md`
3. `/agentsPrompt/modules/frontend/tasks.md`
4. `/pulse-frontend/agent.md`

## 允许修改范围

- `/pulse-frontend/**`
- `/agentsPrompt/modules/frontend/tasks.md`
- `/pulse-frontend/agent.md`

## 修改受限范围

- 修改后端 API 调用、路由 base、认证状态、构建配置或部署相关行为前，必须先更新 `/agentsPrompt/overview_agent/tasks.md` 并说明影响。
- 修改其他模块文件前，必须说明原因、影响范围和验证方式。

## Verification

- 默认验证命令：`rtk powershell -NoProfile -Command "cd pulse-frontend; npm run build"`。
- 如果只修改文档，可运行结构和 diff 验证，并在本模块 `tasks.md` 说明未运行前端构建的原因。

## Reporting

完成后更新：

- `/agentsPrompt/modules/frontend/tasks.md`
- `/pulse-frontend/agent.md`，仅当架构、接口、依赖或目录约定变化时更新。
