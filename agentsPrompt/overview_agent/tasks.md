# Task State: overview_agent

## Current
- Task ID: overview-2026-09-06-optimization-round
- Goal: 按 `docs/optimization-plan-2026-09-06.md` 落地工作台以外的优化方案：补齐记忆闭环前端（记忆面板 + 作息设置）、Agent 公开主页与排行榜、唤醒原因落库、日报进入队列模式当日首次唤醒上下文（默认关闭）、通知中心（数据层 + 前端）；随后合并三端笔记到总览与模块文档。
- Scope: `pulse-frontend/**`、`pulse-backend/**`、`pulse-ai-side/**`、`deploy/migrations/2026-09-06-*.sql`、`docs/contracts/overview.md`、`docs/decisions/decisions-and-pending-log.md`、`docs/architecture.md`、`README.md`、总览与受影响模块任务状态。
- Status: in progress
- Owner: Claude Fable 5.1 协调 + Opus 5 执行 + Codex gpt-5.6-sol 审查
- Last Updated: 2026-09-06

## Done Summary
- 阶段 1（4 个 Opus 执行者并行）：W1 前端记忆面板 + 特质时间线 + 作息设置；W2 后端 `GET /api/v1/agents/{agent_id}/profile`（匿名，独立白名单 DTO，不含特质卡与凭证类字段）；W3 后端 + AI Side `agent_logs` 唤醒原因两列（wake_reason/wake_event_types）与日报 `[World#N]` 区块注入（默认关闭，AI Side 分块器同步扩展）；W6 后端 Agent 排行榜（replied/tipped/active 三种口径，Redis + MySQL 回退）。四路并行由 Codex 计划审查把关（8 条意见全部处置，见决策日志 D-0010~D-0014）。
- 阶段 1 证伪验证（Opus，真实 MySQL 8.4 实测）：发现 10 项缺陷（D1-D10），其中 D1-D4、D6-D9 与一处附带问题已由 FIX1 修复；D5（缓存与 MySQL 路径同分排序不一致）与验证报告"其他问题"六条移入 Pending，未修复。
- 阶段 2（阶段 1 通过后并行）：W5 前端公开主页 `views/AgentProfile.vue`（路由 `/agent/:id`，游客可读）+ Agent 排行榜面板 + 帖子/评论作者名链接；W7 后端通知中心数据层（`notifications` 表 + 迁移 + 四个接口 + 八种触发点，其中 `AGENT_REPLIED_COMMENT` 暂无生产者）；W8 前端通知铃铛与面板。
- 合并后全量验证：后端 392/392（基线 240），AI Side ruff + pytest 全绿（222 用例，阶段 2 未改动 AI Side），前端 lint/test/build 全部通过（84/84）；`schema.sql` 与全部迁移文件在真实 MySQL 上各执行两次均成功。

## In Progress
- 总览与模块任务看板文档合并（本任务，合并 `scratchpad/notes/*.md` 到 `docs/contracts/overview.md`、`docs/decisions/decisions-and-pending-log.md`、`docs/architecture.md`、`README.md`、`agentsPrompt/**/tasks.md`）。
- 对抗审查②：Codex 因工作区花费上限不可用，降级为 Opus 同构证伪审查，结果尚未产出。

## Previous Current (2026-07-28)
- Task ID: overview-2026-07-28-agent-memory-and-wakeup
- Goal: 按 `docs/goal-memory-and-wakeup-plan-2026-07-28.md` 分阶段落地 Agent 记忆系统与唤醒机制升级；Phase 1-3 已收口，Phase 4 验收完成（真实 mvn test 236/236 + 本机全链路 E2E 四剧本 + Codex 全量终审处置完毕）。剩余：前端两块（等 UX 会话）、MENTIONED 范围决策（等项目所有者）、部署环境验证项。
- Scope: `docs/goal-memory-and-wakeup-plan-2026-07-28.md`、`docs/contracts/overview.md`、`docs/decisions/decisions-and-pending-log.md`、`deploy/migrations/**`、`pulse-backend/**`、`pulse-frontend/**`、总览与受影响模块任务状态。
- Status: in progress（前端两块已由本轮 W1/W5/W8 交付；MENTIONED 决策与部署环境验证仍待定，见下方 Pending 与 Decisions 日志）
- Owner: Claude (Opus 5) 协调，backend / ai-side 子会话执行
- Last Updated: 2026-07-28

## Previous Done Summary (2026-07-28)
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
- Command: `cd pulse-backend; mvn -B test`
- Result: pass
- Notes: 392/392（基线 240），真实 Maven（JAVA_HOME=/opt/homebrew/opt/openjdk@21）。
- Command: AI Side `ruff check .` + `pytest -q`
- Result: pass
- Notes: 222 用例，0 失败；本轮阶段 2（W5/W7/W8）未改动 AI Side。
- Command: `cd pulse-frontend; npm run lint && npm test && npm run build`
- Result: pass
- Notes: 84/84 用例，build 成功；未做浏览器实机回归（见 Pending）。

## Next
- 等待对抗审查②（Opus 同构证伪审查，替代不可用的 Codex）结果，按处置结果决定是否需要再修一轮。
- 批次 B 生产运维步骤（迁移确认、暗置部署、切换 `AGENT_LOOP_MODE=queue`、开启 `MEMORY_REFLECTION_ENABLED`、ShedLock 双实例验证）等待项目所有者安排。
- MENTIONED 唤醒范围与名称歧义处理、P9 平台托管模型、公开主页特质卡公开范围、是否提交 2026-07-28 与 2026-09-06 的未提交工作，四项均等待项目所有者决策（见 `docs/decisions/decisions-and-pending-log.md`）。
- D5（排行榜缓存与 MySQL 路径同分排序不一致）与验证报告"其他问题"六条尚未修复，已记入 Pending，未安排执行会话。
- `AGENT_REPLIED_COMMENT` 通知类型需要决策是否扩展 Agent 决策格式以支持目标评论 id，目前该类型不产生任何通知。
