# FIX3a：每日反思接入平台托管模型的计费

## 规则

PLATFORM Agent 的每日反思与唤醒走同一套平台开销规则：调用前经 `PlatformUsageService.checkReadiness` 检查，命中任一原因则跳过本次反思；调用后经 `PlatformUsageService.charge` 按 token 折算积分扣费，ledger 描述追加"反思"；BYOK Agent 行为不变。

## 改动文件

| 文件 | 改动 |
| --- | --- |
| `src/main/java/com/pulse/service/support/PlatformUsageService.java` | 新增 4 参重载 `charge(Agent, long, String modelName, String usageLabel)`，在描述末尾追加场景标签；原 3 参方法委托给它并传 null，费率、取整、`LedgerType.LLM_USAGE` 均未改动 |
| `src/main/java/com/pulse/scheduler/MemoryReflectionScheduler.java` | 注入 `PlatformUsageService`；`Outcome` 新增 `BLOCKED`；`reflectOne` 在构建反思上下文前调用 `checkReadiness`，非 null 则记 info 日志并返回 `BLOCKED`；调用后在失败分支与成功分支各调用一次 `chargePlatformUsage`，标签为"反思" |
| `src/test/java/com/pulse/scheduler/MemoryReflectionPlatformGateTest.java` | 新增，12 个用例 |
| `src/test/java/com/pulse/scheduler/MemoryReflectionSchedulerTest.java` | 仅补构造参数 |
| `src/test/java/com/pulse/scheduler/MemoryReflectionCursorTest.java` | 仅补构造参数 |

未修改 `AgentActionExecutor`、`AgentWakeProcessor`、`LLMClient`、`ReflectionPersistExecutor`、pulse-ai-side、前端、docs。

## 关键设计点

- `BLOCKED` 与 `EMPTY` 在两个维度上处理一致：不计入 `max-agents-per-run`，且写反思游标 `markReflectionAttempt`。原因是被拒绝的 Agent 的 `last_reflection_attempt_at` 若不推进，它会长期排在队首，在上限生效时占满前缀。
- 跳过时不写任何 `agent_logs` 行。写 REFLECTION 行会使该 Agent 当日被 `countCompletedReflectionsSince` 判为已结算，积分补足后当天不再重试。
- 跳过时不调用 `notifyIfActionable`。该通知每 Agent 每日至多一条，反思在 03:40 运行，若在此处消耗通知额度，唤醒链路当日无法再发。
- `charge` 的 `modelName` 传 null，由 `PlatformLlmProperties.getModelName()` 兜底。`ReflectionResult` 无模型名字段，而 `LLMClient` 不在允许修改范围内。
- 反思失败（envelope 失败）时同样按 `min-token-charge` 兜底扣积分，与唤醒链路对 `LLM_CALL_FAILED` 的处理一致；网关明确报告 0 token 时不扣积分。
- 调度器内 `chargePlatformUsage` 自带 try/catch。`PlatformUsageService.charge` 本身不抛异常，此处为防御性处理，避免单个 Agent 的积分失败终止整批。

## 测试

`mvn -B test`：628 用例全绿（基线 616 + 新增 12）。

新增用例覆盖：四种跳过原因各一条；跳过时写游标；跳过不消耗每轮上限；PLATFORM 成功后扣费且描述含"反思"；PLATFORM 失败后扣兜底费；明确 0 token 不扣费；BYOK 不触碰积分服务与平台用量查询；积分服务抛异常不中断批次；`PlatformUsageService` 被 mock 且 `charge` 抛异常时不中断批次。

## 未解决项

- 跳过反思时未向所有者发送通知。所有者只能从日志看到原因，前端与站内信无任何提示。
- 反思与唤醒共用同一份每日 token 上限统计，二者的相对优先级未定义。若反思（03:40）先跑满全局上限，当日唤醒会被拒绝；反之亦然。未做预留或分配。
- `checkReadiness` 中读取积分与用量的查询失败时按"未达上限"放行，该策略沿用唤醒链路，在反思链路未单独评估。
- 反思跳过未计入任何指标或计数器，`skipped` 计数中 `EMPTY` 与 `BLOCKED` 合并，无法从统计上区分。
