# Java 进化报告

## 范围

- 仅更新了 `pulse-backend/src` Java 后端文件和本报告。
- 延续了现有的 Controller / Service / Mapper / DTO 风格。
- 未触碰前端、Python 或生成的 `target` 文件。

## 已实现

1. Agent 多动作决策支持
   - `LLMClient` 现在解析带有顶层 `actions` 的进化网关格式。
   - 保持与遗留单一 `action` 的向后兼容。
   - 限制可执行决策最多 3 个。
   - 过滤无效决策。
   - 在同一决策批次中丢弃同一目标的 like/dislike 冲突。

2. 统一 Token 使用量
   - Java 读取 `usage.total_tokens`、`total_tokens` 或 `totalTokens`。
   - 如果缺少 total，Java 回退到 `prompt_tokens + completion_tokens`。
   - `AgentLoopScheduler` 使用统一的 `totalTokens` 每次 LLM 调用更新一次 `used_tokens`。

3. Agent `create_bounty` 动作
   - 添加了 `CREATE_BOUNTY` 动作枚举和 DTO 字段。
   - 调度器通过 `BountyService` 执行 `create_bounty`。
   - 所有者资金通过现有的悬赏创建语义冻结。
   - 添加了第一阶段 Agent 限制：每个 Agent 每天最多创建 3 个悬赏，每个 Agent 悬赏最多 100 积分。

4. 悬赏取消
   - 添加了 `POST /api/v2/bounties/{taskId}/cancel`。
   - 仅所有者可以取消。
   - 允许 `PENDING` 和 `ACCEPTED` 状态。
   - 拒绝 `REVIEWING`、`COMPLETED`、`ABANDONED`、`EXPIRED` 和 `CANCELLED` 状态。
   - 取消设置状态为 `CANCELLED`，释放 `pending_bounty`，写入悬赏日志，并通过冻结积分释放写入账本。

5. Schema / DTO / 枚举更新
   - 添加了悬赏状态：`ACCEPTED`、`EXPIRED`、`CANCELLED`。
   - 添加了账本类型 `BOUNTY_RELEASE`。
   - 添加了 `BountyCancelRequest`。
   - 更新了 `schema.sql` 注释，包含新的悬赏状态和日志动作。
   - 过期调度器现在将过期的活跃悬赏标记为 `EXPIRED`。

## 添加的测试

- `LLMClientTest`
  - 多动作解析和 `usage.total_tokens`。
  - 回退到 `prompt_tokens + completion_tokens`。

- `BountyServiceImplTest`
  - 待处理悬赏取消释放冻结积分并记录日志。
  - 审核中悬赏取消被拒绝。

## 验证

- 当前环境中不可用 `mvn` 和 `mvn.cmd`。
- `pulse-backend` 中不存在 Maven Wrapper。
- 存在 Java 和 javac，但依赖解析/构建执行需要 Maven 或等效的项目运行器。

## 风险 / 后续

- 在此传递之前，工作树中已存在许多后端修改；本报告仅描述此处添加的进化实现。
- Agent 悬赏限制是 `BountyServiceImpl` 中的常量，后续可移至配置。
- 过期释放仍使用现有的原子用户更新路径，本次传递未添加悬赏日志条目。
