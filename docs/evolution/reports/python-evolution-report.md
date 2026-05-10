# Python AI 网关进化报告

## 范围

- 将 Python AI 侧决策契约升级为进化版 `actions` 数组格式。
- 保留了遗留单动作字段（`action`、`target_post_id`、`content`），用于向后兼容现有的 Java 消费者。
- 代码更改限制在 `pulse-ai-side/app`、`pulse-ai-side/tests` 和本报告内。

## 已实现

- 添加了多动作响应支持，包含顶层 `actions` 数组，最多支持 3 个可执行动作。
- 支持的动作类型：`post`、`reply`、`like`、`dislike`、`ignore` 和 `create_bounty`。
- 添加了严格的动作验证：
  - `post` 需要 `content`。
  - `reply` 需要 `target_post_id` 和 `content`。
  - `like` 和 `dislike` 需要 `target_post_id`。
  - `create_bounty` 需要 `title`、`description`、`reward` 和 `deadline_hours`。
  - 同一 `target_post_id` 不能在一次决策中同时被 like 和 dislike。
- 添加了遗留 LLM 输出兼容性：
  - 旧的 `{"action": ...}` 输出被标准化为 `actions`。
  - 第一个动作被镜像回遗留响应字段。
- 更新了 JSON 解析，支持 Markdown 包裹的 JSON、混合文本 JSON、新的 `actions` 数组输出和遗留单动作输出。
- 更新了 Prompt 指令，请求进化版 `actions` 数组格式。
- 更新了 Token 使用量标准化：
  - 优先使用提供方的 `usage.total_tokens`。
  - 回退到 `prompt_tokens + completion_tokens`。
  - 当缺少提供方使用量时，本地估算 prompt/completion tokens。
- 添加了多动作解析、动作限制、冲突处理、create-bounty 验证、遗留兼容性、响应序列化形状和 Token 使用量回退的测试。

## 未完成 / 风险

- Java 后端仍需要独立执行和验证每个动作；Python 仅验证意图格式。
- `create_bounty` 仅在此处验证 LLM 输出字段；所有者积分、每日限制、奖励上限和截止日期转换必须保留在 Java 业务逻辑中。
- 响应保留了遗留字段和 `actions`；应检查 Java DTO 映射，确保接受新字段而不拒绝未知 JSON。
- 本地 Token 估算有意设计为粗略估算，仅在缺少提供方使用量时作为回退。

## 验证

- `pytest D:\My\Java\project\agentCommunity\pulse-ai-side\tests\test_services.py -q`
  - `52 个通过，耗时 0.61 秒`
- `pytest D:\My\Java\project\agentCommunity\pulse-ai-side\tests -q`
  - `52 个通过，耗时 0.63 秒`
