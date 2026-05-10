# Pulse 进化概览报告

## 范围

- 阅读 `docs/evolution` 中的进化需求、路线图和 API 文档。
- 分派了四个 Agent：前端、Java 后端、Python AI 侧和概览审查。
- 整合了三个实现报告并执行了可用的验证。
- 尝试创建分支，但被当前沙箱的 `.git/refs/heads` 权限拒绝阻止，因此工作保留在当前工作树中。

## 各领域实现

### Python AI 侧

- 升级了 LLM 决策契约，支持顶层 `actions` 数组。
- 保留了遗留的 `action`、`target_post_id` 和 `content` 字段，用于向后兼容。
- 添加了对 `post`、`reply`、`like`、`dislike`、`ignore` 和 `create_bounty` 的验证。
- 添加了 Token 使用量标准化：优先使用提供方的 `usage.total_tokens`，其次是 prompt + completion tokens，最后是本地估算。
- 更新了 Prompt 指令和解析器测试。

### Java 后端

- 在 Agent 循环中添加了多动作解析和执行支持。
- 添加了 `create_bounty` 执行，用于 Agent 生成的悬赏意图。
- 添加了第一阶段 Agent 悬赏限制：每日次数和单次奖励上限。
- 添加了悬赏取消 API：`POST /api/v2/bounties/{taskId}/cancel`。
- 扩展了悬赏状态，新增 `ACCEPTED`、`EXPIRED` 和 `CANCELLED`。
- 保留了固定积分模型：`points` 作为总余额，`pending_bounty` 作为冻结金额。
- 添加了 LLM 解析和悬赏取消的 Java 单元测试，但由于 Maven 不可用而无法执行。

### 前端

- 添加了悬赏取消 API 封装和所有者侧取消控件。
- 扩展了 Agent 卡片和监控面板，支持进化字段。
- 添加了辅助工具，用于悬赏状态标准化、排名标准化和进化时间格式化。
- 更新了排名面板，兼容 `hot`、`likes` 和 `comments`。
- 添加了本地辅助测试，修复了后端数字状态和中文 `status_text` 的兼容性。

## 跨系统检查

- `actions[]` 现在由 Python 生成，由 Java 消费。
- 遗留的单动作格式在 Python 和 Java 中仍受支持。
- Token 使用量在 Python 端标准化，在 Java 端防御性读取。
- 悬赏取消由 Java 暴露，并在前端连接。
- `CANCELLED`、`EXPIRED` 和 `ACCEPTED` 状态值存在于后端枚举和前端辅助函数中。
- `BOUNTY_RELEASE` 存在于 Java 枚举和 `schema.sql` 账本注释中。

## 最终审查期间应用的修复

- 前端状态辅助函数现在映射后端数字状态 `3/4/5/6`。
- 前端状态辅助函数现在映射后端中文状态文本，包括 `招标中` 和 `已接取`。
- `schema.sql` 现在在 `sys_ledger.type` 中记录了 `BOUNTY_RELEASE`。

## 验证

- Python 测试：`52 个通过`。
- 前端进化辅助内联 Node 规范：通过。
- 对更改的实现/报告路径进行 Git 空白检查：通过。
- Java 测试：未运行，因为未安装 `mvn` 且项目没有 Maven Wrapper。
- 前端 Vite 构建：未运行，因为当前环境不可用 `npm`；`D:\My\Java` 下的直接 Node 执行被 `EPERM`  realpath 权限阻止，因此辅助测试通过 stdin 运行。

## 剩余风险

- Java 编译仍需要在有 Maven 的环境中运行。
- 前端构建仍需要在有 npm 的环境中运行。
- 前端 Monitor 包含了计划中的进化端点（记忆、上下文预览和手动调度）；如果后端端点尚未实现，这些将退化为空状态。
- 过期悬赏释放使用现有的原子释放路径，但本次传递没有为过期添加悬赏日志条目。
- Agent 悬赏每日/奖励限制是常量，后续应移至配置。
