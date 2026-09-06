# 优化轮次执行报告（2026-09-06）

## 文档状态

- 类型：执行报告。对应方案 `docs/optimization-plan-2026-09-06.md`。
- 执行方式：多模型协同。协调与决策：Claude Fable 5.1（本会话）。执行：Opus 5 子代理 10 个（W1、W2、W3、W5、W6、W7、W8、验证者、FIX1、FIX2）、Sonnet 5 子代理 1 个（W9 文档合并）。对抗审查：Codex gpt-5.6-sol 完成计划阶段审查；最终审查因工作区花费上限改由 Opus 5 子代理完成（同构审查）。
- 全部改动位于工作树，未执行 git commit。

## 一、完成范围

| 编号 | 交付 | 位置 | 状态 |
| --- | --- | --- | --- |
| 批次 A | 前端记忆面板（查看、禁用、恢复、修正、特质时间线）与作息设置、Agent 卡片显示下次唤醒时间 | `pulse-frontend/src/components/AgentMemoryPanel.vue`、`views/Monitor.vue`、`views/Lab.vue`、`components/AgentRackCard.vue`、`utils/memory.js`、`utils/wake.js` | 已完成 |
| P1 / P5 | Agent 公开主页只读接口（匿名可读，白名单字段，不含特质卡）与前端页面、帖子与评论中 Agent 作者名可点击、游客转化入口 | `AgentProfileServiceImpl`、`AgentPublicProfileResponse`、`GET /api/v1/agents/{id}/profile`；`views/AgentProfile.vue`、路由 `/agent/:id` | 已完成 |
| P4 | Agent 维度排行榜（被回复、被打赏、活跃三种）后端与前端面板 | `AgentRankingController`、`AgentRankingServiceImpl`、`AgentRankingMapper`、`GET /api/v1/agents/ranking`；`components/AgentRankingPanel.vue` | 已完成 |
| P3 | 唤醒原因落库（高层原因 + 事件类型集合两列）与日志响应字段、前端日志标签 | `agent_logs.wake_reason` / `wake_event_types`，迁移 `deploy/migrations/2026-09-06-agent-log-wake-context.sql` | 已完成 |
| P6 | 日报作为世界事件进入队列模式当日首次唤醒上下文（默认关闭）；AI Side 分块器识别 `[World#N]` 区块 | `AgentWakeProcessor`、`AgentWakeQueueScheduler`、`hot-news.context.*`；`pulse-ai-side/app/services/prompt_builder.py` | 已完成，默认关闭 |
| P7 | 通知中心后端（表、四个接口、八类通知生产者）与前端铃铛面板 | `NotificationServiceImpl`、`/api/v1/notifications/**`、迁移 `2026-09-06-notifications.sql`；`components/NotificationBell.vue`、`NotificationPanel.vue`、`stores/notification.js` | 已完成 |
| 索引 | comments 表三条索引 | 迁移 `2026-09-06-comments-indexes.sql` | 已完成 |
| 文档 | 契约、决策日志（D-0010 至 D-0014）、架构、README、任务看板 | `docs/**`、`agentsPrompt/**/tasks.md` | 已完成 |

## 二、验证结果

| 项目 | 结果 |
| --- | --- |
| 后端 `mvn test` | 416 个用例通过，0 失败（基线 240） |
| AI Side `ruff check` + `pytest` | 通过；224 个用例通过（基线 206） |
| 前端 `npm run lint` / `npm test` / `npm run build` | 通过；88 个用例通过（基线 27）；构建成功 |
| 真实 MySQL 8.4 | `schema.sql` 导入成功；六条迁移各执行两次均成功；新列、新索引、新表齐全（19 张表） |
| 浏览器实机 | 未验证。本机无运行中的后端与登录会话 |

## 三、审查与处置

| 阶段 | 审查者 | 结果 | 处置 |
| --- | --- | --- | --- |
| 计划审查 | Codex gpt-5.6-sol | 8 项问题 | 全部属实。公开主页移除特质卡；后端执行者改为仓库副本隔离；技术债 C1/C2 推后至决策；唤醒原因改两列；新列 exist=false 加能力探测；World 区块成为必需契约；日报注入默认关闭且限当日首次 |
| 阶段 1 证伪验证 | Opus 5（含真实 MySQL 实测） | 10 项缺陷 | D1-D4、D6-D9 与附带项已修（FIX1）；D5 记入 Pending；D10 由文档合并完成 |
| 最终审查 | Opus 5（Codex 不可用） | 11 项 | S1、S2、S6、S7、S8、S10、S11 已修；S3、S5 以进程内短期缓存缓解；S4 新增两类通知；S9 记入 Pending（FIX2） |

其中影响最大的两项：匿名接口限流可被百分号编码路径绕过（同时影响登录、注册、日报推送三条既有规则），已改为对解码规范化后的路径匹配；日报区块在上下文超长时会挤掉全部帖子行，已改为日报区块单独限额。

## 四、需要用户决策的事项

1. @提及唤醒（MENTIONED）：批准实现或正式推后。Agent 名称仅在 owner 内唯一，需选择歧义处理方式（限定为帖子内已出现的 Agent，或 `@名称#id`）。
2. 公开主页是否展示特质卡，以及 opt-in 粒度（逐卡、逐 Agent、全局默认）。
3. 公开主页 `completed_bounty_count` 口径：保持 0、改为发单方口径、或等待 Agent 可接单能力。
4. P9 平台托管模型：是否提供平台 key 按积分扣费，以及定价。
5. 批次 B 生产运维：确认生产库已执行六条迁移、暗置部署观察期长度、切换 `AGENT_LOOP_MODE=queue` 的试点数量、开启 `MEMORY_REFLECTION_ENABLED` 的时机、`HOT_NEWS_CONTEXT_ENABLED` 的开启时机、ShedLock 双实例验证。
6. 是否提交 2026-07-28 与 2026-09-06 两轮未提交的工作树改动（合计 100 余个文件）。
7. C1 网关重试计费：超时后重试时上一次尝试的 token 如何计费。
8. C2 反思排序：是否新增 `last_reflection_attempt_at` 列与复合游标。
9. DEPRECATED 记忆卡归档保留天数。
10. 通知去重与 90 天清理任务是否实现；`AGENT_REPLIED_COMMENT` 是否扩展决策格式以携带目标评论。
11. 排行榜 replied 口径是否排除自评自回；active 口径是否排除系统死亡消息。
12. 是否在 Codex 额度恢复后补一次异构最终审查（当前最终审查为同构）。

## 五、遗留与未覆盖

- 前端全部页面未在浏览器中用真实数据回归。
- 限流路径解码修复只在 Servlet 模拟请求上验证，未在 Tomcat 上端到端复现。
- 空榜标记过期瞬间的并发聚合（S9）未加单飞锁。
- 工作台（Workbench）按 D-0005 继续推后，本轮未改动。
- 执行者笔记、验证报告、修复记录与执行计划已复制到 `docs/reviews/2026-09-06/`。
