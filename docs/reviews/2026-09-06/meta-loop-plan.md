# Meta-loop 执行计划（2026-09-06）

仓库：/Users/user/pulse/agentCommunity-main（三服务 monorepo：pulse-frontend Vue3、pulse-backend Spring Boot 3.2/Java 21/MyBatis-Plus、pulse-ai-side FastAPI）。
方案来源：docs/optimization-plan-2026-09-06.md（第四节执行顺序）。本计划只覆盖可在本机完成的部分；生产运维（批次 B 步骤 1-4）与需用户决策项统一推后。

## 环境事实
- 后端基线 `mvn test` 240/240 通过（JAVA_HOME=/opt/homebrew/opt/openjdk@21）。
- 前端基线 lint / node --test / build 通过。
- AI Side 使用 scratchpad 内 venv（aiside-venv）跑 ruff + pytest。
- 工作树有 81 个未提交文件：2026-07-28 的记忆系统与唤醒重构全部尚未 commit（含 schema.sql、迁移文件、AgentMemoryController 等）。生产（GitHub Actions 按 push 部署）尚无这些代码。是否提交属于用户决策，本计划不 commit。
- 现有模式：production 数据库可能缺列，读写用 SchemaCapabilities 守卫；契约在 docs/contracts/overview.md；决策在 docs/decisions/decisions-and-pending-log.md；任务看板 agentsPrompt/**/tasks.md。

## 阶段 1（4 个 Opus 执行者并行）

### W1 前端：记忆面板 + 作息设置 + 特质时间线（批次 A + P2）
- 新增 `src/components/AgentMemoryPanel.vue`，接入 `views/Monitor.vue`：按 memory_type / status 筛选；列表显示内容、置信度、来源、创建/过期时间；操作：禁用、恢复、修正内容（PATCH）；错误码 20007/20008/20009/500（记忆表缺失）映射为可读提示。
- 面板内"特质时间线"视图：PERSONA_TRAIT 按创建日期分组，废弃卡片标灰。
- `views/Lab.vue` 编辑弹窗新增活跃时段起止（0-23，结束不含）与每日唤醒上限（1-24）；20009 提示"当前部署未启用唤醒队列"。
- `components/AgentRackCard.vue` 显示下次唤醒时间与今日已唤醒次数，空值显示占位。
- `src/api/agent.js` 新增 getAgentMemories / updateAgentMemory。
- node --test 单测覆盖新增纯逻辑（错误码映射、分组）。
- 验收：npm run lint / npm test / npm run build 通过。
- 禁止编辑：docs/、agentsPrompt/、后端、AI Side。契约或决策类说明写到 scratchpad/notes/W1.md。

### W2 后端：Agent 公开主页接口（P1 + P10 + P11 数据）
- 新增 `GET /api/v1/agents/{agent_id}/profile`，permitAll（含游客）。返回：id、name、avatar_url、status/status_text、created_at、owner_name、wake_hours_start/end、is_active_now（按服务器时区与活跃时段计算，legacy 模式无字段时为 null）、public_traits（ACTIVE 且未过期的 PERSONA_TRAIT：content、confidence_score、created_at）、stats（post_count、comment_count、tips_received_total、tips_count、completed_bounty_count）、frequent_interactions（与该 Agent 互评最多的前 5 个 Agent：id、name、count）、recent_posts（最近 5 条：post_id、content 摘要、created_at）。
- 不返回 api_key、base_url、model_name、system_prompt、token 用量。人设摘要是否公开属于用户决策，本轮不返回。
- deleted=1 或不存在返回 404 语义；DEAD 状态正常返回。
- 记忆表缺失时 public_traits 返回空数组并 warn（只读展示，与 D-0008 的管理接口不同）。
- 单测：service 层 + SecurityConfig 放行。`mvn test` 全绿。
- 禁止编辑：AgentLogMapper.java、AgentLog.java、scheduler/、application.yml、schema.sql、docs/、agentsPrompt/、前端。契约说明写到 scratchpad/notes/W2.md。

### W3 后端 + AI Side：唤醒原因落库 + 日报进入唤醒上下文（P3 + P6）
- P3：`agent_logs` 新增 `wake_reason VARCHAR(32) NULL`（schema.sql + `deploy/migrations/2026-09-06-agent-log-wake-reason.sql`，幂等写法参照现有迁移）；SchemaCapabilities 新增列探测，缺列时不写该字段；AgentWakeProcessor / AgentActionExecutor 把本次唤醒的 WakeReason 写入每条日志；AgentLogResponse 新增 wake_reason 与 wake_reason_text（中文：按作息醒来 / 被回复 / 被评论 / 被打赏 / 定时批次）。
- P6：作息唤醒（RHYTHM）与 legacy 批次唤醒时，把最新一份日报的摘要作为一个独立不可信区块追加到上下文；事件唤醒不追加。配置 `hot-news.context.enabled`（默认 true）、`hot-news.context.max-chars`（默认 600）。区块格式需与 AI Side `_split_context_blocks` / `_validate_and_sanitize_context` 兼容：先读 prompt_builder.py 确认分块规则，如需新增 `[World#N]` 区块类型，同步修改 AI Side 并补 pytest。无日报时不追加任何内容。
- 单测：后端 mvn test 全绿；AI Side ruff + pytest 全绿（venv：/private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/aiside-venv/bin/）。
- 禁止编辑：AgentController、AgentServiceImpl、SecurityConfig、docs/、agentsPrompt/、前端。契约说明写到 scratchpad/notes/W3.md。

### W4 AI Side + 后端：技术债（批次 C 的 C1、C2）
- C1：AI Side llm_client 重试时聚合每次尝试的 usage（prompt/completion/total）后上报；补 pytest。核对后端 LLMClient 扣费读取的字段名不变。
- C2：MemoryReflectionScheduler 候选排序改为"上次反思时间最早优先"（无反思记录的最优先），数据来源可用 agent_memories 中 source_type=REFLECTION 的最大 created_at；补单测。
- 禁止编辑：AgentWakeProcessor、AgentActionExecutor、AgentController、AgentServiceImpl、application.yml、schema.sql、docs/、agentsPrompt/、前端。说明写到 scratchpad/notes/W4.md。

## 阶段 1 验证
- 协调者跑三端全量验证命令。
- 1 个 Opus 验证者对阶段 1 diff 做证伪式审查（越权暴露、schema 守卫遗漏、注入面、并发/计费路径）。

## 阶段 2（阶段 1 通过后并行）
- W5 前端：公开主页 `views/AgentProfile.vue`（路由 /agent/:id，游客可访问）；PostCard 与评论中的 Agent 作者名链接到该页；页内游客 CTA "创建你自己的 Agent"（跳登录并带 redirect）；Monitor 日志显示唤醒原因标签。
- W6 后端：P4 Agent 排行榜 `GET /api/v1/agents/ranking?type=replied|tipped|trait`（复用 RankingRefreshScheduler 刷新与 Redis/MySQL 回退模式）；P8 核对打赏附言是否进入唤醒上下文与 PERSONA_FACT，缺则补齐。
- W7 后端：P7 通知中心数据层与接口（notifications 表 + 迁移 + SchemaCapabilities 守卫；`GET /api/v1/notifications`、`GET /unread-count`、`POST /{id}/read`、`POST /read-all`；生产者：Agent 回复了你的帖子/评论、你的 Agent 被打赏、你的 Agent 死亡）。

## 阶段 3
- W8 前端：通知铃铛与列表；Agent 排行榜面板。
- W9 Sonnet：合并 scratchpad/notes/*.md 到 docs/contracts/overview.md、docs/decisions/decisions-and-pending-log.md、docs/architecture.md、README、agentsPrompt/**/tasks.md。
- 全量验证 + Codex 对抗审查② + 修订。

## 推后至用户统一决策
1. MENTIONED 唤醒范围与名称歧义处理。
2. P9 平台托管模型与积分定价。
3. 公开主页是否公开人设摘要。
4. 批次 B 生产运维步骤（迁移确认、暗置部署、切换 queue、开启反思、ShedLock 双实例验证）。
5. 是否提交 2026-07-28 未提交工作与本轮改动。
6. DEPRECATED 记忆归档保留天数（C3）。

## 对抗审查①（Codex gpt-5.6-sol）处置记录
| # | 审查意见 | 判定 | 处置 |
| --- | --- | --- | --- |
| 1 | 公开主页暴露 scope=SELF 的特质卡 | 属实 | W2 本轮不返回特质卡；是否公开与 opt-in 机制列入用户决策 |
| 2 | 四执行者共享脏工作树并发 mvn | 属实 | 后端执行者各用一份仓库副本（scratchpad/wt-W2、wt-W3，含基线快照 commit），交付为 git diff HEAD；协调者串行合并后跑全量测试 |
| 3 | 重试 usage 聚合无法覆盖超时路径 | 属实 | C1 推后至用户决策（涉及超时计费策略） |
| 4 | 反思排序破坏 id keyset 分页 | 属实 | C2 推后至用户决策（需新增 last_reflection_attempt_at 与复合游标） |
| 5 | 单列 wake_reason 无法表示合并事件 | 属实 | 改为 wake_reason（RHYTHM/EVENT/LEGACY_BATCH）+ wake_event_types（去重逗号分隔） |
| 6 | 新列在旧库上会让 BaseMapper.insert 失败 | 属实 | 实体字段 exist=false，能力探测独立于 wakeQueueSchema，有列时走显式 INSERT |
| 7 | [World#N] 区块是必需契约 | 属实 | W3 必须改 AI Side 分块器与中和逻辑，并补全审查列出的测试用例 |
| 8 | 日报注入频率与默认开启增加成本 | 属实 | 默认关闭；仅队列模式且当日首次唤醒注入；legacy 模式不注入 |
| 附 | tasks.md 延迟登记 | 部分属实 | W1 直接更新 frontend/tasks.md；backend/ai-side tasks.md 由协调者合并后更新 |
阶段 1 执行者由 4 个缩为 3 个（W4 取消）。

## 执行记录（2026-09-06）
- 阶段 1：W1（前端记忆面板/作息）、W2（公开主页接口）、W3（唤醒原因两列 + World 区块）、W6（Agent 排行榜）并行完成并合并；合并交互问题 1 处（路由切片测试缺 AgentProfileService mock）由协调者修复；协调者补 SecurityConfig 排行榜放行、日志响应唤醒字段接线。
- 阶段 1 证伪验证（Opus，含真实 MySQL 8.4 实测）：10 项缺陷；D1-D4、D6-D9 与附带项由 FIX 执行者修复，D5 记入 Pending，D10 交文档合并。
- 阶段 2：W5（前端公开主页/排行榜面板/作者链接/唤醒原因标签）、W7（通知中心后端）、W8（通知中心前端）完成并合并。
- 合并后全量：后端 392/392，AI Side ruff + pytest 全绿，前端 84/84 + build 通过；schema.sql 与六条迁移在真实 MySQL 上各执行两次均成功（19 张表）。
- 对抗审查②：Codex 因工作区花费上限不可用，降级为 Opus 同构证伪审查（review2.md）。
- 阶段 3：W9（Sonnet）合并文档进行中。
