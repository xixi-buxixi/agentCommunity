# Pulse 前端进化报告

## 范围

- 更新了前端 API 封装，支持悬赏取消和 Agent 进化端点。
- 扩展了现有的像素/终端 UI，未进行大型页面重写。
- 更改仅保留在 `pulse-frontend/src` 和本报告内。

## 已实现

- 悬赏取消：
  - 添加了 `cancelBounty(taskId, data)` 用于 `POST /api/v2/bounties/{taskId}/cancel`。
  - 在悬赏详情中添加了所有者侧取消操作，当状态为 `PENDING` 或 `ACCEPTED` 时。
  - 添加了状态辅助函数，同时支持遗留的数字状态值和进化字符串状态。
  - 取消后刷新当前任务日志、我的悬赏列表、公共列表和全局日志面板。

- Agent 进化显示和控制：
  - Agent 卡片现在在存在时显示 `last_wakeup_at`、`next_wakeup_at` 和 `daily_bounty_count`。
  - Agent 监控显示进化字段、最近记忆、上下文预览和手动决策调度按钮。
  - Agent 日志现在显示 `create_bounty`、`write_memory`、`reason` 和 `total_tokens`（如果可用）。
  - 状态组件容忍数字和字符串 Agent 状态。

- 排名 / 响应兼容性：
  - 排名面板现在调用进化排名参数：`hot`、`likes`、`comments`、`time_range=all`。
  - 排名响应被规范化为扁平帖子行和 `{ score, rank, post }` 包装行。
  - Axios 响应拦截器接受现有的 `code: 200/201` 和文档中的 `code: 0`。

- 前端工具覆盖：
  - 添加了 `src/utils/evolution.js` 用于状态/排名/时间辅助函数。
  - 添加了 `src/utils/evolution.spec.mjs` 作为辅助行为的小型 Node 断言规范。

## 后端/API 依赖

- `POST /api/v2/bounties/{taskId}/cancel`
- `GET /api/v2/agents/{agentId}/memories`
- `GET /api/v2/agents/{agentId}/context-preview`
- `POST /api/v2/agents/{agentId}/dispatch`
- `GET /api/v1/posts/ranking` 应接受 `type=hot|likes|comments`，并可能返回扁平行或包装排名行。

如果计划的后端端点尚未就绪，监控进化面板会故意退化为空状态。

## 验证

- `npm --prefix D:\My\Java\project\agentCommunity\pulse-frontend run build` 无法运行，因为当前 shell 中不可用 `npm`。
- `node src/utils/evolution.spec.mjs` 无法从此工作区运行，因为 Node 在解析 `D:\My\Java` 时失败，报错 `EPERM: operation not permitted, lstat 'D:\My\Java'`。
- 通过 stdin 传递模块体运行了 `src/utils/evolution.js` 的内联 Node 断言规范；结果：`evolution utils inline spec passed`。
- 通过 stdin 对 `src/utils/evolution.js`、`src/api/agent.js`、`src/api/bounty.js`、`src/stores/agent.js` 和 `src/utils/request.js` 运行了 Node 模块语法检查；全部通过。
- 使用 PowerShell `Select-String` 对新导入、API 封装和模板入口点进行了静态检查。

## 风险 / 后续

- 如果取消响应仅返回 `task_id`，请确认后端任务 id 字段命名（`id` vs `task_id`）。
- 确认排名后端接受复数形式 `likes/comments`；如果仍使用 `like/comment`，请在服务端保留兼容性别名或添加前端回退。
- 如果取消成为高频操作，请将 `window.prompt` 取消原因输入替换为样式化的模态框。
