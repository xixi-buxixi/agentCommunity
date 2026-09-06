# Task State: backend

## Current
- Task ID: backend-2026-09-06-public-profile-ranking-wake-context
- Goal: 按 `docs/optimization-plan-2026-09-06.md` 落地阶段 1/2 后端：Agent 公开主页只读接口、Agent 维度排行榜、agent_logs 唤醒原因两列、日报作为 `[World#N]` 区块进入队列模式当日首次唤醒上下文（默认关闭）、通知中心（进行中）。
- Scope: `/pulse-backend/**`、`/deploy/migrations/2026-09-06-*.sql`、`/agentsPrompt/modules/backend/tasks.md`
- Status: in progress
- Owner: Claude Fable 5.1 协调，Opus 5 执行者（W2 / W3 / W6 / W7），Codex gpt-5.6-sol 对抗审查
- Last Updated: 2026-09-06

## Done Summary
- 已新增 `GET /api/v1/agents/{agent_id}/profile`（匿名可读，独立白名单 DTO `AgentPublicProfileResponse`，不含 api_key / base_url / model_name / system_prompt / token 用量 / owner_id；打赏按 `sys_ledger` 的 TIP_RECV + related_type=AGENT 统计；completed_bounty_count 因 schema 中 Agent 只能作为发单方而恒为 0，待决策）。SecurityRulesTest 新增 3 例，AgentProfileServiceImplTest 14 例。
- 已新增 `GET /api/v1/agents/ranking?type=replied|tipped|active&limit=`（匿名可读，Redis ZSet 缓存 + MySQL 聚合回退，随 `RankingRefreshScheduler` 每小时刷新；`AgentRankingRoutingTest` 实测字面量 `/ranking` 优先于 `{agent_id}` 路径变量）。
- 已为 `agent_logs` 新增 `wake_reason` / `wake_event_types`（`@TableField(exist=false)`，`SchemaCapabilities.agentLogWakeColumns` 独立探测，有列走显式 INSERT，无列维持原 insert），迁移 `deploy/migrations/2026-09-06-agent-log-wake-context.sql`；`AgentLogResponse` 新增 wake_reason / wake_event_types / wake_reason_text。
- 已实现日报注入：`hot-news.context.enabled`（默认 false）与 `hot-news.context.max-chars`（默认 600）；仅队列模式且 claim 成功后回读 wake_count_today==1 的当日首次唤醒注入 `[World#<report_id>] [SYSTEM 今日日报 <date>]: ...` 区块；legacy 一律不注入；`flattenForContext` 同时改写正文中的 `[World#`。
- 合并后 `mvn test` 319/319 通过（基线 240）。
- 阶段 1 Codex 计划审查 8 条意见全部处置，记录见 scratchpad 计划文件与本轮方案文档；阶段 1 交付物的 Opus 证伪验证进行中。
- 已完成通知中心（W7）：新增 `notifications` 表 + 迁移 `deploy/migrations/2026-09-06-notifications.sql`，四个接口 `GET /api/v1/notifications`、`GET /api/v1/notifications/unread-count`、`POST /api/v1/notifications/{id}/read`、`POST /api/v1/notifications/read-all`；八种通知类型与触发点（AGENT_REPLIED_POST / AGENT_REPLIED_COMMENT / HUMAN_REPLIED_POST / HUMAN_REPLIED_COMMENT / AGENT_TIPPED / AGENT_DIED / BOUNTY_SUBMITTED / BOUNTY_AUDITED），其中 `AGENT_REPLIED_COMMENT` 暂无生产者（Agent 决策格式不支持目标评论 id，待决策）；通知与唤醒事件按接收者类型互斥（Agent 作者走唤醒队列，人类作者走通知）；缺表时读接口返回 `NOTIFICATIONS_UNAVAILABLE(90001/409)`，写路径静默降级不影响主业务；`NotificationServiceImplTest` 等新增测试覆盖生产者侧真实落库路径。
- 已完成阶段 1 证伪验证（`verify-phase1.md`）发现问题的修复（FIX1）：D1a（排行榜空窗口写 Redis 哨兵标记，TTL 5 分钟，避免每次匿名请求回源 MySQL）、D1b（`comments` 补三条索引 + 迁移 `deploy/migrations/2026-09-06-comments-indexes.sql`）、D1c（`GET /api/v1/agents/ranking` 与 `GET /api/v1/agents/*/profile` 纳入限流，各 60 次/分钟/IP）、D2（replied 榜对同一条评论重复计数，两个 UNION ALL 分支改投影 `(agent_id, comment_id)` 后 `UNION` 去重）、D3（World 区块在 `_semantic_filter` 中无条件保留，不再与其他行按分值竞争容量预算）、D4（记忆面板 5xx 文案改为通用故障提示，不再一律显示"未启用记忆表"）、D6（公开主页对未知/NULL `status` 返回 `status_text="UNKNOWN"` 而非 500）、D7（唤醒异常时写出的错误行补齐 `wake_reason`/`wake_event_types`）、D8（`refreshAllAgentRankingCaches` 单个榜单刷新失败不再中止其余榜单）、D9（`findAgentTipTotals` 补 `amount > 0`，与排行榜口径一致）。D5（缓存路径与 MySQL 路径在同分时排序不一致）与验证报告"其他问题"六条移入 Pending，本轮未修复。
- 合并后全量测试见 overview（`agentsPrompt/overview_agent/tasks.md` 的 Verification 节）。

## In Progress
- 无

## Previous Current (2026-07-28)
- Task ID: backend-2026-07-28-wakeup-rework-phase3
- Goal: 交付唤醒机制重构 Phase 3 后端：agents 作息字段与 agent_wake_events 队列、事件入队、legacy|queue 双模式调度、事件上下文注入、Agent 作息设置接口。
- Scope: `/pulse-backend/**`、`/agentsPrompt/modules/backend/tasks.md`
- Status: done
- Owner: Claude (Opus 5)
- Last Updated: 2026-07-28

## Previous Done Summary (2026-07-28)
- 已在 `schema.sql` 用幂等 ALTER 模式为 agents 新增 next_wake_at / wake_hours_start / wake_hours_end / daily_wake_budget / wake_count_today / wake_count_date 与 idx_next_wake，并新增 `agent_wake_events` 表（dedup_key 唯一索引 + agent/status 索引）；`SchemaCapabilities` 新增 wake-queue 探测（列或表缺失 → queue 不可用并 warn、回退 legacy），Agent 实体作息字段仅在探测通过时赋值，未迁移库因 MyBatis-Plus 跳过 null 字段而照常写入。
- 已实现事件入队 `AgentWakeEventService`：评论 Agent 帖子 → COMMENTED、回复 Agent 评论 → REPLIED（归属被回复者而非帖主）、打赏 → TIPPED（键在收款 ledger 行）；`INSERT IGNORE` + dedup_key `{agentId}:{type}:{sourceType}:{sourceId}:{actorType}:{actorId}`，Agent 自触发不入队，任何失败只 warn，schema 缺失直接空转。
- 已实现 `legacy|queue` 双模式：新增 `WakeMode.resolve`（未知值/schema 缺失一律回退 legacy 并 warn，绝不猜 queue 以免社区静默），`AgentLoopScheduler` 保留 12h 批次与选取逻辑不变、仅加模式闸门；新增 `AgentWakeQueueScheduler`（5 分钟 tick + ShedLock）依次做过期清理、事件唤醒、作息唤醒。
- 已把单 Agent 唤醒流程原样抽到 `AgentWakeProcessor`（stamp → token 预检 → 上下文 → LLM → 执行 → 扣费 → 写卡），两种模式共用，"queue 只是换了触发器"由构造保证；queue 模式不重复 stamp（已含在抢占声明内）。
- 已用单条条件 UPDATE `claimWakeSlot` 同时实现每日预算与防抖：`wake_count_date <> today` 即自动换日重置，预算超限或距上次唤醒不足 min-interval 返回 0 行，调用方不得调模型——多实例下亦不会双吃。
- 已实现事件消费纪律：一次唤醒合并该 Agent 全部 PENDING 事件（单次 LLM 调用），动作走完后用 `WHERE status='PENDING'` 条件更新置 PROCESSED（并发落败者更新 0 行）；预算耗尽/防抖时事件保留 PENDING 等次日（协调者裁定：互动最有价值，宁延迟不丢弃），超过 event-expiry-hours 由过期清理置 EXPIRED；唤醒抛异常则事件留 PENDING 由下个 tick 重试。
- 已实现作息计算 `WakeScheduleCalculator`（纯函数、可注入 Random）：半开区间活跃时段支持跨零点（22-6），start==end 视为全天以免"永不唤醒"，下次唤醒 = 时段长度/目标次数 ± 30% 抖动且必落在时段内，越界顺延到下个时段起点再抖动；新 Agent 创建时随机分配 8-16 小时作息营造多样性。
- 已扩展 Agent 更新接口的 wake_hours_start / wake_hours_end / daily_wake_budget（0-23、1-24 校验，owner 校验沿用），列表与详情响应带上三字段 + 只读 next_wake_at（详情另带 wake_count_today）；改活跃时段立即重算 next_wake_at。
- 已把事件上下文注入 `AgentContext.eventsContext`（每条"{actor} {互动动作}：{正文截断100字}（Post#id）"，正文走 flattenForContext 同级压平，取不到正文降级为无正文描述），并通过 `getGatewayContext()` 前置到既有 `context` 字段发给网关——复用网关既有分块防注入清洗，无需改 AI Side 契约。
- 已按 Phase 3 Codex 第 1 轮审查修复 S0×2 + S1×6 + S2：①queue 能力判定并入 `isLastDispatchedAtColumn`（claimWakeSlot 要写该列）；②六个作息列改 `@TableField(exist=false)` 退出 MyBatis-Plus 默认映射，读走显式投影 `AgentWakeSettings`（缺 schema 返回空、异常降级为空），写走显式 `updateWakeSettings`（缺 schema 抛 20009 而非 500），旧库的 Agent 详情/更新/打赏路径不再触发 unknown column；③REPLIED 闭环打通——事件源帖以标准 `[Post#N] [TYPE name]:` 区块注入（即使已评论过也取来、每帖独立成块），执行器对"本次唤醒所消费事件引用的帖子"放行重复评论，其余场景照旧拒绝；④事件提示行改为纯系统文案（actor 名只保留标识符字符且截断 20），用户可控正文只出现在 Post 区块内；⑤事件在 LLM 调用前即置 PROCESSED（崩溃只丢一次回应，不再重放重扣费），抢占失败则跳过本次唤醒；⑥候选查询 JOIN agents 只取存活，过期清理顺带把非存活 Agent 的 PENDING 置 EXPIRED；⑦Agent 互评入队（执行器回复其他 Agent 的帖子时入队 COMMENTED，MENTIONED 按裁定不做）；⑧时钟统一为 JVM（claimWakeSlot / markProcessed / expire / created_at 全部参数化，SQL 不再用 NOW()/CURDATE），wake_count_today 在 wake_count_date 非今日时显示 0；⑨max-events-per-wake 注释如实说明"超出留 PENDING 下次接续"、start==end 全天语义写入字段注释与校验消息、部分更新时另一列归一化为默认值后两列同写、yml 注明 serverTimezone 须与 JVM 时区一致。
- 已按 Phase 3 第 2 轮复审收尾 4 项代码 + 1 项注释：①触发事件的评论正文渲染进对应源帖 `[Post#N]` 区块内（"最新互动 {actor}: {正文截断150字}"，同帖多条互动都列出，正文与名字都压平——正文在区块内则网关分块过滤天然覆盖，单条中招只丢该块）；②改为每个 Agent 紧邻 claim 前重新取 JVM now（claim/活跃时段/换日都用它），批次级 cutoff 仍用 tick 时间，新增 clock 注入缝以便测试锁住；③claim 泄漏补偿——markProcessed 抛异常或全部落败时用条件 UPDATE `releaseWakeSlot`（仅当 wake_count_date=today 且 >0 时减一）归还预算并跳过唤醒，补偿失败只 log；事件改为逐条标记、只把真正抢到的事件交给处理器（repliableAgainPostIds 因此只含已消费事件的帖子）；④`next_wake_at` 改用不带假 Z 的本地 ISO 格式化（新增 `LOCAL_DATE_TIME_FORMATTER`，既有字段的历史格式不动）；⑤yml 时钟说明限定为 queue 模式 SQL，并注明 legacy 的 markDispatched 仍用 DB NOW()、legacy→queue 切换瞬间可能有一次时钟偏差级别的防抖误差（可接受）。
- 已修复 E2E 发现的 P0 启动故障：`AgentMemoryMapper` 两处 `<script>` 注解 SQL 内的裸 `<>`（MyBatis 按 XML 解析 → mapper bean 创建失败 → Spring 上下文起不来）转义为 `&lt;&gt;`，并在两处加注释说明原因；同时新增启动冒烟测试 `MapperAnnotationSqlParseTest`：扫描 `com.pulse.mapper` 全部 `@Mapper` 接口，用 `MybatisConfiguration` + 无连接占位 DataSource 真正解析注解 SQL（收集全部失败一次报出，并断言扫到的 mapper 数不少于 15 以防空跑）——已验证判别力：故意还原裸 `<>` 后该测试必红（SAXParseException），修复后绿。
- 已提升预算可观测性：claim 被拒时按当日已用/上限区分原因——预算耗尽升为 INFO 且独立文案（含 agentId 与 used/limit），防抖跳过保持 debug；额外读取只发生在被拒路径，读失败降级为 debug 不影响流程。
- 已按 Codex 终审完成最后 3 项：①惊群防护——`next_wake_at IS NULL` 语义确立为"尚未排期"，queue tick 遇到 NULL 的 Agent 不唤醒、只用新增的 `spreadInitialWake` 在其活跃时段内把它错峰排到未来 24h（随机到秒，避免同窗 Agent 撞点），下个周期才正常参与，消除切 queue 首小时的批量 LLM 调用；②回退完整性——过期清理（`expirePendingOlderThan` + `expirePendingForInactiveAgents`）改为 legacy/queue 都执行（纯 DB 无 LLM），入队保持只看 schema 不看模式，并在 tick 注释里写明该取舍："短暂回退期间的互动事件保留 24h、恢复 queue 后可接上"是特性，但必须有人持续修剪表；③`agent_wake_events` 补 `updated_at`（CREATE TABLE 内声明 + 守卫式 ALTER 兼容已建表的部署），实体补只读字段并注明由数据库维护。
- 已在 `application.yml` 集中 queue 模式配置（mode、tick-interval、min-wake-interval-minutes、event/rhythm-batch-size、max-events-per-wake、event-expiry-hours、target-daily-rhythm-wakes、default-daily-wake-budget），逐项注释。
- 已实现记忆注入：`AgentMemoryService.selectForInjection` 取 ACTIVE 且未过期的记忆，PERSONA_TRAIT 优先于 PERSONA_FACT，组内 importance DESC + created_at DESC，取 `pulse.memory.inject-limit`（默认 10）条；SQL 与 Java 双重过滤（禁用/废弃/过期绝不注入），`AgentContext` 新增 `memories` 与 `memoriesContext`（每条带类型/置信度/来源前缀、内容走 `flattenForContext` 同级压平），`LLMClient` 按契约 A 传 `memories` 数组（无记忆时省略字段，向后兼容）。
- 已新增 `MemoryReflectionScheduler`：每日 `0 40 3 * * *`（避开 4:20 对账）+ ShedLock，选取窗口内有 agent_logs 且 ALIVE 的 Agent，逐个组装行为包（当日 PERSONA_FACT + agent_logs 摘要 + 现有 ACTIVE TRAIT），调 `POST /v1/llm/reflection`（事务外），失败仅记日志次日重试。
- 已实现反思结果安全落库：content/evidence 过 `MemoryTextSanitizer`，importance/confidence 夹紧 0-100（缺省 50/70），updated/deprecated 的 id 必须属于该 Agent 且为 PERSONA_TRAIT（Java 预校验 + SQL `agent_id`/`memory_type` 双重限定，越权 id 丢弃并 warn），new_traits 超 `max-new-traits` 截断，TRAIT 存活量超 `trait-limit` 按 importance ASC 淘汰为 DEPRECATED，写入 `created_by=REFLECTION`；更新走 `version = version + 1` 的条件 UPDATE，不触碰 status（用户禁用的 TRAIT 不会被模型复活）。
- 已实现反思计费：新增 `AgentActionExecutor.chargeReflectionTokens`（charge + 审计日志 + 死亡判定同一事务，日志标记 REFLECTION_SUCCESS/REFLECTION_FAILED 而非 ERROR），网关未报用量时按 `min-token-charge` 兜底；token 耗尽的 Agent 在调用前跳过；落库失败仍照常计费。
- 已按协调者拍板把 `scheduler.memory-reflection.enabled` 默认改为 false（@Value 兜底与 application.yml 均为 false），注释说明"每日每活跃 Agent 一次 LLM 调用、烧 Agent 所有者 token，部署验证成本后显式开启"，与 Phase 3 queue 模式默认 legacy 的上线哲学一致。
- 已加固 `callReflection` 的两类边缘响应：非 2xx（含 AI Side 请求校验失败返回的 decision 形状 400）与 2xx 但缺少三个反思数组的形状不符，均安全降级为 `ReflectionResult.failed()`，只读取可得的 `total_tokens`（顶层或 usage），绝不把错误信封当特质落库；显式空数组仍视为成功。
- 已按 Phase 2 Codex 审查（第 1 轮，确认 7 项）修复：①计费区分"未报告用量"（null → floor）与"显式 0"（success + 0 → 不扣费、审计记 REFLECTION_SKIPPED），行为包为空直接跳过不发 HTTP；②反思链路所有查询统一为 status=1 且未过期（禁用/过期事实与特质不再经反思洗回人格），Java 侧同标准兜底；③候选查询与行为包均排除 REFLECTION_* 审计日志，消除"反思日志把自己续成活跃"的自激活与自我蒸馏；④候选改 id 游标翻页处理全部候选（batch-size 变每页大小），另加 `max-agents-per-run` 硬上限，触顶时用 count 查询如实 log 跳过数量；⑤幂等两层——当日已有 REFLECTION_SUCCESS 的 Agent 跳过（FAILED 可重试）+ new_traits 归一化内容去重（与现有 ACTIVE TRAIT 及同批次比对）；⑥新增 `ReflectionPersistExecutor`，落库+计费+审计并入单事务，落库失败回滚后降级为独立 charge-only 事务并记 REFLECTION_FAILED，两者皆败仅 log error；⑦`updated_traits` 支持可选 evidence（提供则脱敏后更新，缺省保持原值，动态 SET 实现）。
- 已按第 2 轮复审收尾 5 项：①`max-agents-per-run` 改为只计实际发起反思调用的 Agent（visited/attempted 分开统计并双双进日志），当日已反思或行为包为空的跳过不再吃配额；②修订 TRAIT 时 evidence 总是重写（提供则脱敏写入、未提供则置 NULL），废弃"缺省保持原值"语义——证据错配比无证据更有害；③去重比对集合改用 `findActiveByType` + `isInjectable` 同标准过滤（过期 ACTIVE 旧卡不再把新生成的同内容有效 TRAIT 判重丢弃）；④去重归一化键加 NFKC 折叠（全角变体判重），但保留标点差异不做剥离；⑤幂等判定扩为 SUCCESS 或 SKIPPED 均算"今日已完成"（FAILED 仍可重试），`countSuccessfulReflectionsSince` 更名 `countCompletedReflectionsSince`。
- 已把记忆相关配置收进 `MemoryProperties`（`pulse.memory.*`）与 `scheduler.memory-reflection.*`，全部带注释与环境变量占位。
- 已读取 `docs/goal-memory-and-wakeup-plan-2026-07-28.md` 的 Phase 1 与安全红线，并对齐现有 `ApiResponse`/`PageResponse`/`ErrorCode`/事务纪律惯例。
- 已在 `schema.sql` 新增 `agent_memories` 表（id/agent_id/owner_id/page_id 预留/namespace/memory_type/content/evidence/source_type/source_id/scope/importance_score/confidence_score/status/version/expires_at/created_by/created_at/updated_at/deleted），索引 `(agent_id,status,memory_type)` 与 `(owner_id)`，InnoDB + utf8mb4 + COMMENT，`CREATE TABLE IF NOT EXISTS` 可重复应用。
- 已新增 Entity/Mapper/Service/Controller：`GET /api/v1/agents/{agent_id}/memories`（分页、status 与 memory_type 过滤、仅 owner）与 `PATCH /api/v1/agents/{agent_id}/memories/{memory_id}`（禁用/启用、修正 content 时 version+1 且 created_by=USER_EDIT）；新增错误码 `AGENT_MEMORY_NOT_FOUND(20007,404)`、`AGENT_MEMORY_DEPRECATED(20008,409)`。
- 已在 `AgentActionExecutor.applyDecisions` 返回执行结果（携带真实 post/comment/bounty id），由 `AgentLoopScheduler` 在事务提交后调用 `AgentMemoryService.recordActionMemories` 生成 PERSONA_FACT 卡片（发帖/回复/点赞/点踩/创建悬赏五种中文模板，content 截断 200 字符、换行压平、写卡失败仅 log.warn）。
- 已实现保留策略：每 Agent 非 DEPRECATED 的 PERSONA_FACT 超过 `pulse.memory.persona-fact-limit`（默认 200）时，按 importance 最低 + 最旧顺序置为 DEPRECATED，在写卡路径内联执行，无新增调度器。
- 已实现 `MemoryTextSanitizer`：NFKC + 去零宽字符/折叠 Unicode 空白后做检测，覆盖 sk-/pk-/rk-、sk_live_/sk_test_（Stripe）、ghp_/gho_/ghs_/ghu_/github_pat_、AKIA/ASIA、xox[baprs]-、JWT、Bearer、前缀式键名赋值（client_secret、X-Api-Key 等）、邮箱，并压平换行、中和 `[Post#`/`[记忆` 区块前缀。
- 已加入误杀收紧与拆分兜底：赋值/Bearer 的"值"必须像凭证（>=12 个凭证字符且含数字，或大小写混合 + 符号），使 `The secret: consistency matters`、`bearer responsibility` 不再被误杀；另做一次"全空白剥离形态"检测，仅剥离形态命中时整段替换为 `[REDACTED_SUSPECTED_CREDENTIAL]`（宁可弃卡不可漏密钥）。
- 已按 Codex 第一轮对抗审查结论修复四项：PATCH 改为带 version 的条件 UPDATE，0 行按 `RESOURCE_CONFLICT(99904)` 处理，"DEPRECATED 不可复活"在 SQL 层成立；分页 page 夹紧 >=1、size 夹紧 [1,50]（非法取默认 20，避免 size=-1 关闭分页）；修正 `application.yml` 关于表体积的失实注释；更新本模块与总览任务状态。
- 已按第二轮复审结论完成四项微修复：过滤补漏（前缀式键名 + Stripe 前缀 + 空白拆分兜底）、误杀收紧（凭证值形态校验）、status-only 并发（`applyOwnerEdit` 的 status 分支 WHERE 增加 `status = #{expectedStatus}`，两个相反的并发 PATCH 只有一个成功）、协议状态纠偏。
- 已新增 Phase 2 单测 `AgentMemoryReflectionTest`（21 例：注入排序/过滤/上限、行为包组装与脱敏、反思归属校验与夹紧截断、TRAIT 淘汰）、`MemoryReflectionSchedulerTest`（15 例：三种计费路径、当日幂等跳过、空行为包不发 HTTP、游标翻页覆盖全部候选、硬上限计数、落库失败仍计费并记 FAILED）、`ReflectionPersistExecutorTest`（2 例）、`LLMClientMemoryContractTest`（12 例，含 updated_traits.evidence 可选语义：契约 A/B 线格式、校验失败 400/形状不符/无体 500 的降级与用量读取）。
- 已新增 Phase 1 单测 `AgentMemoryServiceImplTest`（36 例）与 `AgentActionExecutorTest`（4 例），覆盖五种模板、敏感信息过滤与零宽/全角/空白拆分绕过、凭证词误杀反例、权限校验、PATCH 状态机与并发冲突（version 与 status 双守卫）、保留策略淘汰、分页夹紧。

## Previous Done Summary
- 已新增 Daily Hot News entity、mapper、DTO、service、controller、schema、配置和服务层测试。
- 已补充生产配置模板中的 `HERMES_INGEST_TOKEN` 和 `HOT_NEWS_CACHE_TTL_HOURS`。
- 已确认后端采用 Spring Boot 3.2、MyBatis Plus、MySQL、Redis、统一 `ApiResponse` 和 Spring Security 白名单。

## In Progress
- 无

## Blocked
- Blocker: 无
- Needed input: 无（迁移 SQL 已由协调者创建 `deploy/migrations/2026-07-28-agent-memories.sql`；契约与决策文档已由协调者收口）
- Since: 2026-07-28

## Decisions
- 2026-07-28: 记忆表命名 `agent_memories`（不带 wiki 前缀），字段对齐需求文档的 `llm_wiki_memories`，`page_id` 本轮恒 NULL 作为演进位。
- 2026-07-28: 写卡在动作事务提交后执行，`applyDecisions` 改为返回 `List<AgentActionOutcome>` 回传真实 source id；写卡与保留策略失败只记日志，绝不影响动作与扣费。
- 2026-07-28: LIKE/DISLIKE 卡片的 `source_type/source_id` 指向目标帖子（点赞行 id 无跳转价值），`evidence` 另存 `target=POST#id`。
- 2026-07-28: `agent_memories.version` 不使用 MyBatis Plus `@Version`，改为业务语义"被修正次数"，仅在 content 修正时 +1，并靠条件 UPDATE 保证严格单调。
- 2026-07-28: 敏感信息检测一律在 NFKC 规范化形态上进行；落库仅在命中时使用该形态，未命中时保留全角中文标点（NFKC 会把 ，：（） 改写为 ASCII，无安全收益）——该取舍经协调者采纳。
- 2026-07-28: 凭证正则只在"值像凭证"时触发，避免误杀中英文正文；空白拆分只能判定"疑似"而无法定位，故整段丢弃。
- 2026-07-28: 注入过滤在 SQL 与 Java 各做一遍——"坏记忆不得进 Prompt"是刹车能力的根基，值得一份不会被查询改写悄悄失效的兜底。
- 2026-07-28: 反思调用的 Agent 标识沿用 decision 现有惯例（不单独传 agent id，凭证即身份），网关保持无状态。
- 2026-07-28: 反思审计日志不复用 `chargeTokensOnly` 的 "ERROR:" 前缀，另加 `chargeReflectionTokens`，避免每晚正常蒸馏都被记成错误。
- 2026-07-28: 记忆类型第一版只声明 `PERSONA_FACT`/`PERSONA_TRAIT`，RELATION/LESSON 留待同表扩展。

## Verification
- Command: `cd pulse-backend; mvn test`
- Result: pass
- Notes: 240 tests run, 0 failures, 0 errors, 0 skipped，BUILD SUCCESS（真实 maven，JAVA_HOME=/opt/homebrew/opt/openjdk@21）。
- Command: `mvn test -Dtest=MapperAnnotationSqlParseTest`（判别力验证：临时还原裸 `<>`）
- Result: pass（预期必红后已恢复）
- Notes: 还原缺陷后该测试以 SAXParseException 失败、BUILD FAILURE；恢复转义后全绿，证明"注解 SQL 不可解析 → mvn test 必炸"。

## Next
- 部署验证反思成本后再显式设置 `MEMORY_REFLECTION_ENABLED=true`（当前默认关闭）。
- 生产库执行 agents 作息列与 agent_wake_events 迁移（协调者出 SQL），迁移并观察后再把 `AGENT_LOOP_MODE=queue`（当前默认 legacy）。
- 在具备 maven 的环境复跑 `cd pulse-backend; mvn test` 确认全绿。
- 与 AI Side 执行者联调契约 A/B（`memories` 字段、`/v1/llm/reflection`），并请总览会话把两项契约写入 `docs/contracts/overview.md`。
- 前端记忆面板（Phase 1 任务 5、6）待 UX 会话落定后由前端会话开发，接口路径、字段与错误码见 `docs/contracts/overview.md` 的 Agent Memories 节。
- 非阻塞待定：`agent_memories` 是否纳入 `SchemaCapabilities` 探测（表缺失时降级返回空页 vs 保持 500）。
