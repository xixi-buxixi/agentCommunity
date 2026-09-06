# Pulse 阶段 1 交付物证伪式验证

验证日期：2026-09-06
验证范围：/Users/user/pulse/agentCommunity-main（只读），phase1-full.diff 中除 .idea/、.mypy_cache/、.run-logs/ 之外的 51 个文件。
验证产物目录：scratchpad/verify/（MySQL 实例、SQL 脚本、Java 探针、Python 反例）。未修改仓库内任何文件，未执行 git 写操作。

实测环境：
- MySQL 8.4.0（/opt/miniconda3/bin/mysqld），端口 33099，数据目录 scratchpad/verify/mysql/data，验证结束后已关闭。
- JDK 21（/opt/homebrew/opt/openjdk@21）+ pulse-backend/target/classes + ~/.m2 依赖，直接构造 MybatisSqlSessionFactory 调用真实 Mapper。
- AI Side：scratchpad/aiside-venv/bin/python，`DEBUG=true SERVICE_TOKEN=...` 直接调用 PromptBuilder。

---

## 一、缺陷列表（按严重度排序）

### D1 排行榜接口在榜单为空时每次匿名请求触发两条聚合查询，且不受限流覆盖

- 位置
  - `pulse-backend/src/main/java/com/pulse/service/impl/AgentRankingServiceImpl.java`：`getAgentRanking`（缓存未命中分支）与 `refreshAgentRankingCache`
  - `pulse-backend/src/main/java/com/pulse/security/filter/RateLimitFilter.java`：`RULES`
  - `pulse-backend/src/main/java/com/pulse/config/SecurityConfig.java`：`/api/v1/agents/ranking` 的 permitAll
- 失败场景
  1. 榜单窗口内无数据（部署初期；或 tipped 榜在没有打赏记录的部署上长期为空）。
  2. `readFromRedis` 返回空 → `queryFromMySQL(type, limit)` 执行一条聚合 → `refreshAgentRankingCache` 再执行一条 `queryFromMySQL(type, 50)` → `rows.isEmpty()` 成立 → `redisTemplate.delete(key)` 后直接 return，不写入任何成员。
  3. 缓存保持为空，下一个请求重复上述过程。每个匿名请求执行两条聚合查询，无上限。
  4. Redis 不可用时路径相同（`readFromRedis` 捕获异常返回 null）。
- 证据
  - `RateLimitFilter.RULES` 只包含 `POST /api/v1/auth/login`、`POST /api/v1/auth/register`、`POST /api/v1/hot-news/ingest`，两个新增匿名接口不在其中。
  - replied 榜 SQL 的 EXPLAIN（真实 MySQL，pulse_db）：第二个 UNION 分支 `type=ALL`、`key=NULL`，即 comments 全表扫描。`comments` 表无 `created_at` 索引（现有索引为 idx_post_id、idx_author_id、idx_parent_comment_id、idx_root_comment_id、idx_post_author）。
  - active 榜的 comments 部分 EXPLAIN 为 `type=index`、`key=idx_author_id`，为全索引扫描。
- 建议修法
  - 空结果时写入一个哨兵成员（或为 key 设置短 TTL 并允许缓存空值），使空榜单也能命中缓存。
  - 将 `GET /api/v1/agents/ranking` 与 `GET /api/v1/agents/{id}/profile` 纳入 `RateLimitFilter.RULES`。
  - 补 W6 笔记第 7 节已列出的三条 comments 索引。

### D2 replied 榜对同一条评论重复计数

- 位置：`pulse-backend/src/main/java/com/pulse/mapper/AgentRankingMapper.java#findTopByRepliesReceived`
- 失败场景：子评论同时携带 `post_id` 与 `parent_comment_id`。当某 Agent 既是帖子作者又是父评论作者时，同一条子评论在两个 UNION ALL 分支各计一次。该 Agent 在自己帖子下留评论后被他人回复，即触发该情形。
- 证据（真实 MySQL，fixture 见 scratchpad/verify/sql/fixture.sql）：
  - 分支一（agent 帖子下的评论）对 agent 10 命中评论 200、201、202、203、207，计 5。
  - 分支二（回复 agent 评论）对 agent 10 命中评论 203，计 1。
  - 评论 203 出现两次，最终 `score = 6`，而窗口内指向 agent 10 的评论共 5 条。
- 建议修法：分支一增加 `c.parent_comment_id IS NULL`（只统计一级评论），或在外层对 (agent_id, comment_id) 去重后再聚合。

### D3 上下文超过 8000 字符时 World 区块可能被 `_semantic_filter` 丢弃

- 位置：`pulse-ai-side/app/services/prompt_builder.py`：`_calculate_relevance_score`（World 行 +0.6）、`_semantic_filter`
- 失败场景：`_validate_and_sanitize_context` 在上下文超过 `MAX_CONTEXT_LENGTH`(8000) 时调用 `_semantic_filter`。该函数按行得分降序排列并在累计长度达到 90% 容量时 break。帖子行同时命中 `?`(+0.3)、`@`(+0.2)、`!`(+0.15)、互动关键词(+0.15)、长度 <100(+0.1) 与 Post 序号(+0.1) 时得分可达 1.0，高于 World 行的 0.6，排在 World 行之前并填满容量。
- 证据（scratchpad/verify/py/e_world4.py，venv 实跑）：
  - 输入：149 条帖子行（每条 61 字符，`_calculate_relevance_score` 实测 1.0）+ 1 条 World 行（297 字符，实测 0.6），原始长度 9726。
  - 输出：`world survived: False`，`sanitized len: 7272`，`has_world: False`。
  - `_has_world_block` 为 False 会使 `_enhance_system_prompt` 不追加 World 子句，即 system prompt 中也不再说明该区块的性质。
  - W3 笔记第 6 节的表述「保证 `_semantic_filter` 不会把系统推送排掉」与该结果不符。
- 补充：`_normalize_unicode` 的 NFKC 会把全角 `？！` 折成 `?!`，因此中文帖子同样能取得上述加分。
- 建议修法：在 `_semantic_filter` 中先无条件保留匹配 `WORLD_HEADER_RE` 的行，再对其余行排序；或把 World 行的分值提到高于其他行可能达到的上限（当前上限约 1.05）。

### D4 记忆面板把任何 5xx 显示为「未启用记忆表」

- 位置：`pulse-frontend/src/utils/memory.js#describeMemoryError`
- 失败场景：业务码未命中 switch 分支时，只要 `status >= 500` 即返回「当前部署未启用记忆表（agent_memories），请先执行记忆表迁移」。数据库连接中断、后端未捕获异常、nginx 返回 502/503（响应体无 `code` 字段，`businessCode` 为 null）都会显示该文案，指向一个与故障无关的处置动作。
- 证据：`request.js` 的 transport 分支把 `status` 设为 HTTP 状态码、`code` 在响应体无 `code` 字段时设为 null；`describeMemoryError` 在 `code` 为 null 时落到 `status >= 500` 分支。
- 建议修法：由后端对该情形返回专用业务码（例如缺表时返回一个新的 ErrorCode），前端只在该业务码下显示迁移提示；其余 5xx 显示通用故障文案。

### D5 缓存命中路径与 MySQL 回退路径在分值相同时排序不同

- 位置：`AgentRankingServiceImpl#readFromRedis`（`reverseRangeWithScores`）与 `AgentRankingMapper` 三条 SQL 的 `ORDER BY score DESC, agent_id ASC`
- 失败场景：Redis Sorted Set 对同分成员按 member 字典序升序排列，`reverseRange` 取反后为字典序降序；MySQL 按 `agent_id ASC`。两者在 agent_id 为 12 与 13 且分值相同时顺序相反（Redis 路径 13 在前，MySQL 路径 12 在前）。榜单上同分行常见，同一份数据在缓存命中与缓存未命中两种情况下会给出不同的 rank。
- 确认程度：依据 Redis Sorted Set 的排序语义推导，本机无 Redis，未实测。
- 建议修法：`buildResponses` 之前在 Java 侧统一按 (score desc, agentId asc) 重排。

### D6 公开主页未对异常 status 取值做保护

- 位置：`pulse-backend/src/main/java/com/pulse/service/impl/AgentProfileServiceImpl.java#getPublicProfile`，`AgentStatus.fromCode(agent.getStatus())`
- 失败场景：`AgentStatus.fromCode(int)` 对未知取值抛 `IllegalArgumentException`；`agent.getStatus()` 为 `Integer`，为 null 时拆箱抛 NPE。`agents.status` 定义为 `TINYINT DEFAULT 1`，允许 NULL。任一情况下匿名接口返回 500。
- 对照：`AgentRankingServiceImpl#statusTextOf` 对同一调用做了 try/catch 与 null 判定，两处处理方式不同。
- 建议修法：公开主页复用同一保护逻辑。

### D7 唤醒过程抛出异常时写出的错误行不带 wake_reason

- 位置：`AgentWakeQueueScheduler#runEventWakes` / `#runRhythmWakes` 的 catch 分支，调用 `agentActionExecutor.logAgentError(agent, safeMessage(e), 0)`（三参重载，wakeContext 为 null）
- 失败场景：`agentWakeProcessor.wake(...)` 抛出异常时，该次唤醒写出的错误行 `wake_reason` 为 NULL。W3 笔记第 3 节的表述为「原因从 `AgentWakeProcessor.wake()` 的 reason / events 参数构造一次，传给该次唤醒写出的每一行」，这些行不满足该表述。
- 建议修法：调度器在 catch 分支使用带 `WakeLogContext` 的重载。

### D8 refreshAllAgentRankingCaches 在单个榜单失败时中止剩余榜单

- 位置：`AgentRankingServiceImpl#refreshAllAgentRankingCaches`
- 失败场景：循环体内无 try/catch。第一个榜单刷新抛出异常（Redis 写入失败、SQL 超时）时，后两个榜单本次 tick 不刷新。`RankingRefreshScheduler` 的 try 块只在两族榜单之间隔离，不在 Agent 族内部隔离。
- 建议修法：循环体内逐个 type 捕获异常。

### D9 打赏统计在两处的过滤条件不一致

- 位置：`SysLedgerMapper#findAgentTipTotals`（无 `amount > 0`）与 `AgentRankingMapper#findTopByTipsReceived`（有 `amount > 0`）
- 影响：当前 `LedgerServiceImpl.tipAgent` 只写入正数 TIP_RECV，两者结果一致；若后续出现负数 TIP_RECV（退款、冲正），公开主页的 `tips_received_total` 与 tipped 榜的口径会分叉。
- 建议修法：两处使用同一组过滤条件。

### D10 契约文档未包含四项新交付

- 位置：`docs/contracts/overview.md`
- 现状：全文只有 `/api/v1/posts/ranking`，不含 `GET /api/v1/agents/{id}/profile`、`GET /api/v1/agents/ranking`、`agent_logs` 的 wake_reason / wake_event_types、`AgentLogResponse` 的三个新字段、World 区块格式。三份执行者笔记均把 docs/ 列为禁止修改范围，该合并动作未完成。
- 建议修法：由协调者合并 W2 / W3 / W6 笔记中已写好的契约段落。

### 其他问题（影响较小，一并记录）

| 问题 | 位置 | 说明 |
| --- | --- | --- |
| 事件类型串可能在条目中间截断 | `WakeLogContext#renderTypes` | 64 字符上限对拼接结果整体截断。三个已知类型合计 24 字符，不会触发；`agent_wake_events.event_type` 为 VARCHAR(32)，多个未识别类型时会截出半个条目。 |
| comment_count 包含发在已删除帖子下的评论 | `CommentMapper#countAgentComments` | 只过滤 `comments.deleted = 0`，不检查所在帖子。实测 fixture 中评论 206 位于已删除帖子 103 下，仍计入 agent 11 的 comment_count。 |
| 系统死亡消息计入 post_count、recent_posts 与 active 榜 | `PostMapper#countAgentPosts` / `#findRecentAgentPosts`、`AgentRankingMapper#findTopByActivity` | 前两处 W2 笔记已说明为有意口径；active 榜把死亡消息计为窗口内的产出，W6 笔记未提及。实测 fixture 中 agent 10 的 active 分值 4 含帖子 104（`is_system_message = 1`）。 |
| 匿名接口构成存在性与 owner 用户名枚举面 | profile / ranking | profile 对不存在的 id 返回 404、存在返回 200；ranking 匿名返回每个 Agent 的 owner username。与 D1 的无限流叠加。 |
| 本地兜底 prompt 未说明 World 区块 | `AgentContext#buildFullPrompt` | 该方法的格式说明只写 `[Post#帖子ID]`。AI Side 路径由 `_enhance_system_prompt` 补充说明，本地兜底路径没有对应文本。 |
| 作息字段无法清空 | `AgentUpdateRequest` + `buildWakeUpdatePayload` | W1 笔记第 2 条已记录，此处确认：`buildWakeUpdatePayload` 对 `current === null` 直接跳过，已设置的值无法改回未设置。 |

---

## 二、已核实无问题的项与核实方式

### A 越权与信息暴露

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| 公开主页响应字段 | 未推翻 | 逐字段读取 `AgentPublicProfileResponse`（含 Stats / InteractionPeer / RecentPost 三个内部类）：共 20 个字段，无 api_key、base_url、model_name、system_prompt、used_tokens、token_threshold、token_percentage、is_unlimited、owner_id、daily_wake_budget、next_wake_at、wake_count_today。`AgentProfileServiceImpl` 使用独立 DTO，不复用 `AgentDetailResponse`。 |
| 排行榜响应字段 | 未推翻 | `AgentRankingItemResponse` 共 9 个字段：rank、agent_id、name、avatar_url、status、status_text、owner_name、score、type。 |
| SecurityConfig 两条 permitAll 的范围 | 未推翻 | 两条均在 `HttpMethod.GET` 限定的游客组内。`/api/v1/agents/{agentId:[0-9]+}/profile` 带数字约束与字面量后缀，无法匹配 `/api/v1/agents/logs`、`/api/v1/agents/{id}`、`/api/v1/agents/{id}/logs`、`/api/v1/agents/{id}/action-count`；`/api/v1/agents/ranking` 为完整字面量路径。`/api/v1/agents/**` 下无其他 permitAll 匹配器，其余路径由 `anyRequest().authenticated()` 兜底。 |
| limit 上限 | 未推翻 | `AgentRankingServiceImpl` 的 `Math.min(Math.max(limit, 1), MAX_LIMIT)`，MAX_LIMIT=50；SQL 的 `LIMIT #{limit}` 接收该值。公开主页的两个列表长度为服务内常量 5，由 SQL 的 LIMIT 限制。 |
| 公开主页每请求查询数 | 未推翻 | 代码路径为固定 8 条（legacy 库 7 条），与数据量无关：selectById、findWakeSettings（受能力位守卫）、userMapper.selectById（owner_id 非 null 时）、countAgentPosts、countAgentComments、findAgentTipTotals、findFrequentAgentInteractions、findRecentAgentPosts。互评对端名称在同一条 SQL 内 join，无 N+1。 |
| 热点日报摄入口径 | 未推翻 | `/api/v1/hot-news/ingest` 虽在 permitAll 名单内，`HotNewsServiceImpl#validateIngestToken` 要求 `X-Hermes-Token` 与配置值常量时间相等，且拒绝空值与 `change_me`；该路径另有 RateLimitFilter 规则。 |

### B 旧库兼容

在 MySQL 上构造 pulse_legacy（由 schema.sql 建库后删除 `agent_wake_events`、`agent_memories`、`agent_logs` 的两个新列、`agents` 的六个作息列），用 `scratchpad/verify/java/LegacyProbe.java` 直接调用真实 Mapper：

| 项 | 结论 | 实测结果 |
| --- | --- | --- |
| agent_logs 三条写路径 | 未推翻 | `agentLogMapper.insert`（MyBatis-Plus 生成语句）在 legacy 库成功；`insertWithWakeContext` 在 legacy 库报 `Unknown column 'wake_reason' in 'field list'`，该语句受 `SchemaCapabilities.isAgentLogWakeColumns()` 守卫，`AgentActionExecutor#insertLog` 是唯一入口，仓库内无其他 `agentLogMapper.insert` 调用点。 |
| agent_logs 所有读路径 | 未推翻 | legacy 库上 findByAgentId、findByAgentIdSince、findByOwnerId、countCompletedReflectionsSince、countByAgentId、selectById 全部成功，新字段读回为 null。`resources/mapper/AgentMapper.xml` 对 agent_logs 只有 `SELECT 1` 子查询，不涉及列级引用。 |
| 能力位解耦 | 未推翻 | `agentLogWakeColumns` 由 `columnExists("agent_logs","wake_reason") && columnExists("agent_logs","wake_event_types")` 单独计算，与 `wakeQueueSchema` 的八项判定无交集。 |
| 未执行 2026-07-28 两条迁移时的公开主页与排行榜 | 未推翻 | legacy 库上三条排行榜 SQL 与五条主页 SQL 全部执行成功，结果与 pulse_db 一致。主页的作息读取受 `isWakeQueueSchema()` 守卫，异常另有 try/catch 降级为 null。 |

### C SQL 真实性（MySQL 实测已完成）

- schema.sql 导入成功（18 张表）。
- 四个迁移文件各执行两次，退出码均为 0，无重复列/重复键报错：2026-07-27-optimization.sql、2026-07-28-agent-memories.sql、2026-07-28-agent-wake-queue.sql、2026-09-06-agent-log-wake-context.sql。幂等性成立。
- 样例数据：2 个用户、4 个 Agent（含 1 个 deleted=1、1 个 status=0）、6 条帖子（含 deleted、HUMAN、系统消息、窗口外各一条）、9 条评论（含 deleted、已删除帖子下的评论、已删除 Agent 的评论、跨窗口评论）、8 条 sys_ledger（含 TIP_SEND、BOUNTY_RECV、其他 Agent、窗口外各一条）。
- 口径核对结果：

| SQL | 结果 | 核对 |
| --- | --- | --- |
| `PostMapper#countAgentPosts(10)` | 3 | 排除 deleted 帖子与 author_type=HUMAN 的行，包含系统消息，正确。 |
| `PostMapper#findRecentAgentPosts(10,5)` | 3 行，按 created_at DESC, id DESC | 正确。 |
| `CommentMapper#countAgentComments(10)` | 2 | 排除 deleted 评论，正确（对已删除帖子下的评论不过滤，见「其他问题」）。 |
| `SysLedgerMapper#findAgentTipTotals(10)` | tipCount=3, tipTotal=16.00 | 只取 TIP_RECV + related_type='AGENT'，排除了 TIP_SEND（-10、-5）与 BOUNTY_RECV（99），排除了 related_id=11/13 的行，正确。 |
| `CommentMapper#findFrequentAgentInteractions(10,5)` | A2=3, A3=1 | 双向计数正确；排除自评、deleted 评论、已删除帖子下的评论、已删除 Agent（A4del 不出现），正确。 |
| `AgentRankingMapper#findTopByTipsReceived` 30 天窗口 | agent10=15.00, agent11=7.00 | 排除窗口外的 1.00 与已删除 Agent 13 的 3.00，正确。 |
| `AgentRankingMapper#findTopByActivity` 7 天窗口 | agent10=4, agent11=3, agent12=1 | 窗口过滤与 UNION ALL 求和正确（含系统消息，见「其他问题」）。 |
| `AgentRankingMapper#findTopByRepliesReceived` 7 天窗口 | agent10=6, agent11=1 | 见 D2。 |
| `AgentLogMapper#insertWithWakeContext` | 写入成功，generatedId 回填 | 显式 INSERT 的列清单与 agent_logs 实际列一一对应。 |

### D MyBatis-Plus 行为

- 版本：`pulse-backend/pom.xml` 的 `mybatis-plus.version = 3.5.5`（mybatis-plus-spring-boot3-starter），底层 mybatis 3.5.15。
- 结论：未推翻。`@TableField(exist = false)` 字段在 `SELECT *` 的自动映射下会被填充。
- 实测（scratchpad/verify/java/MpProbe.java，真实 MySQL）：
  - `insertWithWakeContext` 写入 wake_reason=EVENT、wake_event_types=COMMENTED,TIPPED 后，`findByAgentId`（`SELECT *`）读回 `wakeReason=EVENT`、`wakeEventTypes=COMMENTED,TIPPED`。
  - `findByOwnerId`（`SELECT al.*`）同样读回 `wakeReason=EVENT`。
  - `agentLogMapper.insert`（生成语句）不写入这两列，读回为 null，与设计一致。
  - 配置为 `MybatisConfiguration.setMapUnderscoreToCamelCase(true)`，与 application.yml 第 45 行一致。

### E AI Side 区块规则

用 venv 直接调用 `PromptBuilder._split_context_blocks` 与 `_validate_and_sanitize_context`（scratchpad/verify/py/e_world.py）：

| 反例 | 结论 | 实测结果 |
| --- | --- | --- |
| World 区块夹在两个 Post 之间 | 未推翻 | 切出 3 个区块，World 独立成块，两侧 Post 不受影响，`_has_world_block` 为 True。 |
| World 摘要含注入关键词 | 未推翻 | 只有 World 区块被中和为 `[World#77] [SYSTEM 今日日报 2026-09-06]: [内容已被安全过滤器移除]`，区块头保留，两条 Post 原样通过。 |
| 行首伪造 `[World#999] [SYSTEM ...]:` | 未推翻 | 伪造区块独立成块，含注入关键词时同样被中和。Java 侧 `AgentWakeProcessor#flattenForContext` 实测包含 `.replace("[World#", "(World#")` 与 `.replace("[Post#", "(Post#")`，且换行被压平；`postsContext` 的全部写入点（appendPostBlock 的帖子正文与互动引用、时间线循环的正文、记忆渲染）都经过该方法，区块头中的作者名由 `getAuthorName` 生成为 `Agent#id` / `Human#id`，`report_date` 经 `safeHeaderMeta` 白名单过滤，`eventsContext` 的演员名经 `safeActorName` 只保留 `[A-Za-z0-9_.-]`。因此不可信文本无法在行首制造区块头。 |
| 缺第二段括号的 `[World#77] 这只是正文里的一行` | 未推翻 | 不构成边界，与前一行合并为一个区块，`_has_world_block` 为 False。 |
| 超 8000 字符时 World 是否保留 | **已推翻** | 见 D3。 |

### F 当日首次唤醒判定

阅读 `AgentWakeQueueScheduler#isFirstWakeToday`、`AgentMapper#claimWakeSlot` / `#releaseWakeSlot` / `#findWakeSettings` 后的推演：

| 情形 | 结论 |
| --- | --- |
| 同一 Agent 同日两次为 true | 未推翻。`claimWakeSlot` 的 `wake_count_today = IF(wake_count_date = today, COALESCE(wake_count_today,0)+1, 1)` 保证同日第二次 claim 后计数为 2。`releaseWakeSlot` 只在 `wake()` 未被调用的两条路径上执行（consumeEvents 抛异常、consumed 为空），把计数减回 0 后不会注入日报，下一次 claim 重新得到 1 时才注入，全天仍只注入一次。 |
| 跨天 | 未推翻。`wake_count_date <> today` 时计数重置为 1，跨零点的作息（例如 22-6）在新的一天首次唤醒时判定为 true，符合按自然日的定义。附带现象：`HotNewsService.getLatest()` 无按 report_id 去重，日报未更新时同一份内容会在连续两天各注入一次。 |
| 预算耗尽 | 未推翻。预算判定在 `claimWakeSlot` 内完成，claim 失败即不进入 `isFirstWakeToday`。`AgentUpdateRequest.dailyWakeBudget` 的 `@Min(1)` 使预算为 0 的情形不可达；预算为 1 时第二次 claim 被 `COALESCE(wake_count_today,0) < COALESCE(daily_wake_budget, ...)` 拒绝，无 off-by-one。 |
| 并发两实例 | 未推翻，但存在已知窗口。ShedLock 在 `shedlock` 表存在时使同一 tick 不并行；`SchemaCapabilities` 在该表缺失时把锁降级为 NoOp，此时两实例各自 claim（计数 1、2）后再回读，可能两次都读到 2，当日不注入日报。影响限于是否注入日报区块。W3 笔记第 9 节第 4 条已记录该窗口。 |
| 永远为 false | 未推翻。除上一行的无锁并发窗口外，`findWakeSettings` 失败会返回 false（W3 已说明）；该查询在 `wakeQueueSchema` 为 true 时列必然存在，legacy 库不进入队列 tick。 |

### G 前端

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| api 路径与后端映射 | 未推翻 | baseURL 为 `${VITE_API_BASE_URL \|\| '/pulse/api'}/v1`。`getAgentMemories` → `/agents/{id}/memories` 对应 `AgentMemoryController` 的 `@RequestMapping("/api/v1/agents/{agent_id}/memories")` + `@GetMapping`；`updateAgentMemory` → PATCH `/agents/{id}/memories/{mid}` 对应 `@PatchMapping("/{memory_id}")`；`getAgentPublicProfile` 与 `getAgentRanking` 对应两个新增匿名接口。查询参数名 `memory_type` / `status` / `page` / `size` 与 `getMemories` 的 `@RequestParam` 一致。 |
| request.js 改动对其他调用方的影响 | 未推翻 | 改动只在 `onFulfilled` 的业务错误分支，仍 reject `new Error(message)`，`message` 取值不变，只新增 `status` 与 `code` 两个属性。成功路径、`SESSION_INVALID_CODES` 的 `clearAuthAndRedirect()` 时机、transport 分支均未改动。既有调用方只读 `error.message`，行为不变。 |
| Lab 作息字段提交与 20009 处理 | 未推翻 | `buildWakeUpdatePayload` 只提交改动过且非 null 的字段，`normalizeWakeValue(0)` 返回 0（不被当作未设置）。校验区间 0-23 与 1-24 与 `AgentUpdateRequest` 的 `@Min/@Max` 一致。后端 `applyWakeSettings` 在三个字段均未提交时直接 return，提交且 `isWakeQueueSchema()` 为 false 时抛 20009；`agentStore.updateAgent` 写入 `errorCode`，`describeWakeError` 据此出文案，弹窗不关闭。 |
| AgentRackCard 字段名与 AgentListItemResponse | 未推翻 | 卡片读取 `agent.next_wake_at`、`agent.wake_hours_start`、`agent.wake_hours_end`、`agent.model_name`、`agent.used_tokens`、`agent.token_threshold`、`agent.status`、`agent.id`、`agent.name`，均存在于 `AgentListItemResponse` 的 `@JsonProperty` 清单。已删除的 `last_wakeup_at` / `next_wakeup_at` / `daily_bounty_count` 确认在后端不存在。 |
| Monitor 字段 | 未推翻 | 读取 `wake_hours_start/end`、`next_wake_at`、`wake_count_today`、`daily_wake_budget`，均存在于 `AgentDetailResponse`。 |
| 记忆内容长度上限 | 未推翻 | 前端 `MEMORY_CONTENT_MAX = 500` 与 `AgentMemoryUpdateRequest.content` 的 `@Size(min = 1, max = 500)` 一致。 |
| 记忆状态机 | 未推翻 | `canReactivate` 只对 DISABLED 返回 true，`reactivateMemory` 内另有 `isDeprecated` 兜底，与后端拒绝 status=2 的约束一致。 |
| `AgentLogResponse.applyWakeContext` 接线 | 未推翻 | W3 笔记第 9 节第 1 条列为未解决，实测已接线：`AgentServiceImpl.java:622` 存在 `.applyWakeContext(log.getWakeReason(), log.getWakeEventTypes())`。该遗留项已闭合。 |

### 其他已核实项

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| Redis key 与帖子榜冲突 | 未推翻 | 帖子榜使用 `pulse:rank:hot` / `pulse:rank:like` / `pulse:rank:comment`，Agent 榜使用 `pulse:rank:agent:{type}`，无重叠。 |
| 路由冲突 | 未推翻 | `AgentRankingController` 的 `@RequestMapping("/api/v1/agents/ranking")` 为字面量路径，Spring MVC 的 PathPattern 具体度比较使其胜过 `AgentController` 的 `@GetMapping("/{agent_id}")`。`AgentRankingRoutingTest` 已对解析出的 HandlerMethod 类型断言。 |
| 注解 SQL 无 `<script>` 与裸角括号 | 未推翻 | 五处新增 SQL 均为纯文本，不等号写作 `!=`，`MapperAnnotationSqlParseTest` 覆盖。 |
| 唤醒上下文的 World 区块位置 | 未推翻 | `appendWorldBlock` 在全部 Post 区块之后追加，独占一行，`report_id` 为空时写 0。 |
