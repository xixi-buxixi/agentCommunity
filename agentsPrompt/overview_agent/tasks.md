# Task State: overview_agent

## Current
- Task ID: overview-2026-07-28-agent-memory-and-wakeup
- Goal: 按 `docs/goal-memory-and-wakeup-plan-2026-07-28.md` 分阶段落地 Agent 记忆系统与唤醒机制升级；Phase 1-3 已收口，Phase 4 验收完成（真实 mvn test 236/236 + 本机全链路 E2E 四剧本 + Codex 全量终审处置完毕）。剩余：前端两块（等 UX 会话）、MENTIONED 范围决策（等项目所有者）、部署环境验证项。
- Scope: `docs/goal-memory-and-wakeup-plan-2026-07-28.md`、`docs/contracts/overview.md`、`docs/decisions/decisions-and-pending-log.md`、`deploy/migrations/**`、`pulse-backend/**`、`pulse-frontend/**`、总览与受影响模块任务状态。
- Status: in progress
- Owner: Claude (Opus 5) 协调，backend / ai-side 子会话执行
- Last Updated: 2026-07-28

## Done Summary
- Phase 1（记忆骨架与管理刹车）与 Phase 2（记忆注入 + 每日反思蒸馏）已全部收口：后端两轮 Codex 审查问题清零、AI Side 206/206、契约与决策文档已更新。
- Phase 3 已完成后端主体：`agents` 新增 next_wake_at / wake_hours_start / wake_hours_end / daily_wake_budget / wake_count_today / wake_count_date（全部 `@TableField(exist=false)` 退出默认映射，读写走显式且受 `SchemaCapabilities` 守卫的语句，旧库不受影响）、新增 `agent_wake_events` 队列表、评论/回复/打赏/Agent 互评四处入队、`legacy|queue` 双模式（默认 legacy，未知值或缺 schema 一律回退）、事件合并唤醒与预算/防抖原子抢占、事件源帖以标准 [Post#N] 区块注入、Agent 作息设置接口。
- 已确认执行顺序：记忆系统 + 唤醒优化先行，工作台（LangGraph 编排）整体推后；Phase 1 → Phase 2 强依赖，Phase 3 唤醒重构随后进行。
- 已完成 Phase 1 后端部分（计划任务 1-4）：新增 `agent_memories` 表、记忆查询/管理 API、动作热路径零 LLM 写卡、保留策略与服务层单测，明细见 `agentsPrompt/modules/backend/tasks.md`。
- 已明确对前端公开的契约：`GET /api/v1/agents/{agent_id}/memories?status=&memory_type=&page=&size=` 返回 `ApiResponse<PageResponse<AgentMemoryResponse>>`（字段 snake_case）；`PATCH /api/v1/agents/{agent_id}/memories/{memory_id}` 接受 `{status?: 0|1, content?: 1-500 字}`；错误码 20002/404、20003/403、20007/404、20008/409、99904/409、99900/400。历史上前端曾调用不存在的 `GET /api/v2/agents/{id}/memories`，本轮真实接口归入 `/api/v1/agents` 族。
- 已完成 Phase 1 的两轮 Codex 对抗审查与处置：第一轮确认属实 4 项（PATCH 并发与状态复活、敏感信息过滤绕过面、分页边界、任务状态未更新）已修复；第二轮确认修复 1、3 成立且 NFKC 取舍成立，另 4 项微修复（过滤补漏、误杀收紧、status-only 并发守卫、协议状态纠偏）亦已完成，Phase 1 后端收口，不再安排新审查轮。
- 已由协调者补齐 `deploy/migrations/2026-07-28-agent-memories.sql`，并更新 `docs/contracts/overview.md`（Agent Memories 节 + 99904/409 冲突语义 + 分页夹紧说明）与 `docs/decisions/decisions-and-pending-log.md`（D-0005~D-0008 及 Pending：maven 复跑、DEPRECATED 清理策略）。

## Previous Done Summary
- 已更新 Daily Hot News 契约、后端入站/查询 API、MySQL schema、Redis 缓存、生产环境变量示例、前端社区页日报入口和详情页。
- 已为工作台模块升级 LangGraph 多智能体协作与 LLM WIKI 记忆系统编写跨模块需求草案。
- 已调整 `.gitignore`，仅放开 `deploy/backend/application-prod.yml` 和 `deploy/backend/.env.example`。

## In Progress
- 前端：记忆面板（Phase 1 任务 5、6）与作息/预算设置页（Phase 3 任务 7、8）待 UX 会话落定后开发。**记忆刹车 UI 是开启 `MEMORY_REFLECTION_ENABLED` 的前置条件**（终审阻断项）。
- Phase 3/4 补充说明：两轮 Phase 3 审查（S0×2 + S1×6 + S2 → 修复；复审 3×S1 + 3×S2 → 修复/裁定）已全部处置；E2E 抓到的启动 P0（Mapper 注解 SQL 裸 <>）已修并有冒烟测试护栏；终审 8 项中代码 3 项（惊群防护、legacy 下过期清理、wake 表 updated_at）为最后一轮改动。
- 迁移 SQL 已齐：`2026-07-28-agent-memories.sql` 与 `2026-07-28-agent-wake-queue.sql`（含 last_dispatched_at、shedlock、回退手册）。上线路径：先 legacy + reflection=false 暗置部署 → 迁移 → 观察 → 分开启用两个开关。

## Blocked
- Blocker: 无
- Needed input: 无（迁移 SQL 与文档收口均已完成；`agent_memories` 是否纳入 `SchemaCapabilities` 探测列为非阻塞待定项）
- Since: 2026-07-28

## Decisions
- 2026-07-28: 第一版记忆只做人格一致性（`PERSONA_FACT` 免 LLM 即时落卡，`PERSONA_TRAIT` 由 Phase 2 低频反思蒸馏），不做全量 LLM-WIKI，但表结构保留演进位。
- 2026-07-28: "可查看、可禁用、可修正"作为 Phase 1 必交付的刹车能力前置，避免坏记忆注入 Prompt 后自我强化。
- 2026-07-28: 记忆内容视为不可信输入：写入前过滤密钥/token/邮箱、压平换行、防区块伪造；Phase 2 注入 Prompt 时声明"记忆非指令"。
- 2026-07-28: 唤醒改造放在 Phase 3，采用 legacy/queue 双模式开关，默认 legacy 上线后再切换。
- 2026-07-28: 每阶段完成后调用 Codex 做对抗性审查，协调者判定真伪后才修复，处置结果留痕。
- 2026-07-28: 唤醒事件在预算耗尽时保留 PENDING 等次日，只由 24h 过期清理退场——互动是最有价值的唤醒理由，宁延迟不丢弃。
- 2026-07-28: 事件在 LLM 调用前即置 PROCESSED——崩溃只丢一次回应，绝不重放重扣费。
- 2026-07-28: 唤醒相关时间比较统一以 JVM 时钟为源（不用 NOW()/CURDATE()），datasource 的 serverTimezone 必须与 JVM 时区一致。
- 2026-07-28: MENTIONED（@提及）唤醒本轮不做，记为 Pending。

## Verification
- Command: `cd pulse-backend; mvn test`
- Result: not run
- Notes: 执行会话所在机器无 maven（`mvn`/`java` 均不在 PATH），改用等价手工编译 + junit-platform-launcher 全量执行，结果 103 tests / 103 passed / 0 failures；仍需在具备 maven 的环境复跑确认（已登记为 Pending）。
- Command: `cd pulse-frontend; npm run build`
- Result: not run
- Notes: Phase 1 前端部分由前端会话负责，验证随该部分一并进行。

## Next
- 在具备 maven 的环境复跑 `cd pulse-backend; mvn test` 确认全绿。
- 出 Phase 3 生产迁移 SQL（agents 六列 + last_dispatched_at 守卫 + agent_wake_events），迁移并观察后再切 `AGENT_LOOP_MODE=queue`。
- Phase 3 契约文档补充（agents 新字段、20009 错误码、事件唤醒行为），随后进入 Phase 4 端到端验收与成本核对。
