# Pulse 阶段 1 缺陷修复记录（FIX1）

修复日期：2026-09-06
输入：`scratchpad/notes/verify-phase1.md`
工作树：`/Users/user/pulse/agentCommunity-main`（未执行任何 git 写操作）

---

## 一、各编号的修法

| 编号 | 修法 |
| --- | --- |
| D1a | 新增 `pulse:rank:agent:{type}:empty` 标记 key（TTL 5 分钟）：`refreshAgentRankingCache` 在窗口无数据时写入该标记，有数据时先删除标记再写 ZSet；`getAgentRanking` 在 ZSet 未命中时先查标记，命中即返回空列表，不再回退 MySQL。 |
| D1b | schema.sql 在 comments 表既有 `idx_post_author` 守卫块之后新增三条 information_schema 守卫的幂等 ALTER；同时新增 `deploy/migrations/2026-09-06-comments-indexes.sql`。 |
| D1c | `RateLimitFilter.RULES` 新增两条 GET 规则：`/api/v1/agents/ranking`（bucket `agent-ranking:ip`）与 `/api/v1/agents/*/profile`（bucket `agent-profile:ip`），均为每 IP 每分钟 60 次。 |
| D2 | `findTopByRepliesReceived` 两个分支改为投影 `(agent_id, comment_id)` 对，用 `UNION`（非 `UNION ALL`）去重后外层 `COUNT(*)`。 |
| D3 | `_semantic_filter` 把匹配 `WORLD_HEADER_RE` 的行从打分排序中移出，先无条件放入结果并计入预算，其余行再竞争剩余容量；循环开头补一次 90% 容量检查。`_calculate_relevance_score` 的 +0.6 注释同步修正。 |
| D4 | `describeMemoryError` 的 5xx 文案改为「记忆服务暂时不可用（可能是部署库尚未启用记忆表，或服务异常），请稍后重试或联系管理员」。 |
| D6 | `AgentProfileServiceImpl` 新增 `statusTextOf(Integer)`，NULL 与未知取值返回 `"UNKNOWN"`，`status` 字段仍返回原值。 |
| D7 | `runEventWakes` 为每个 Agent 准备一个 `attempted` 列表，`wakeForEvents` 先填入队列给出的事件、消费成功后替换为实际消费的事件；catch 分支改用四参 `logAgentError` 传入 `WakeLogContext.of(WakeReason.EVENT, attempted)`。`runRhythmWakes` 的 catch 分支传入 `WakeLogContext.of(WakeReason.RHYTHM, List.of())`。 |
| D8 | `refreshAllAgentRankingCaches` 循环体内逐个 type 捕获异常并记录 error 日志。 |
| D9 | `SysLedgerMapper#findAgentTipTotals` 增加 `AND amount > 0`。 |
| 附带项 | `WakeLogContext.renderTypes` 改用 `joinWithinColumn`：逐个条目累加，放不下的条目整体丢弃，不再对拼接结果做 substring。 |

未处理：D5（同分排序）、D10（契约文档合并）、报告「其他问题」表格中的六条 —— 本次任务未列入范围。

---

## 二、新增迁移与索引

### 新文件：`deploy/migrations/2026-09-06-comments-indexes.sql`

三条索引，均为 information_schema 守卫的幂等 ALTER：

| 索引 | 列 | 服务的查询 |
| --- | --- | --- |
| `idx_comments_author_created` | `(author_type, author_id, created_at)` | active 榜的 comments 分支 |
| `idx_comments_parent_created` | `(parent_comment_id, created_at)` | replied 榜的父评论分支 |
| `idx_comments_post_created` | `(post_id, created_at)` | replied 榜的帖子分支 |

同一组语句也写入 `pulse-backend/src/main/resources/schema.sql`，位置在 comments 表的 `idx_post_author` 守卫块之后（第 401-437 行区间），文件末尾未改动。

无索引时的影响（迁移文件头部注释已写明）：不影响任何返回结果，也没有能力位依赖该索引。影响的是代价 —— comments 现有索引均不含 `created_at`，replied 榜的父评论分支为全表扫描、active 榜的 comments 分支为全索引扫描，两者在每小时缓存刷新与每次缓存未命中时都会执行，代价随 comments 表总量增长而非随 7 天窗口增长。

应用代价：三条 `ADD INDEX`。MySQL 5.6+ 下为 online DDL，不阻塞写入，但仍需读全表。

实测（MySQL 8.4.0，本地实例）：schema.sql 与该迁移文件各执行两次，退出码均为 0，`SHOW INDEX FROM comments` 显示三条索引均已建立且无重复。

---

## 三、限流规则（供契约文档）

`RateLimitFilter.RULES` 新增两条，语义与既有 POST 规则一致（Redis 固定窗口，按 IP 分桶，Redis 不可用时 fail-open）：

| 方法 | 路径 | bucket | 限额 | 窗口 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/agents/ranking` | `agent-ranking:ip` | 60 | 1 分钟 |
| GET | `/api/v1/agents/*/profile` | `agent-profile:ip` | 60 | 1 分钟 |

补充说明：

- 两条规则使用独立 bucket，一个接口耗尽额度不影响另一个。
- 超限响应：HTTP 429，`Retry-After` 头为当前窗口剩余秒数，响应体为项目统一信封 `{code: RATE_LIMIT_EXCEEDED, message}`。
- 路径匹配由 `AntPathMatcher` 完成。`/api/v1/agents/*/profile` 的单段通配符不匹配 `/api/v1/agents/{id}`、`/api/v1/agents/{id}/logs`、`/api/v1/agents/{id}/memories`、`/api/v1/agents/logs`、`/api/v1/agents`，已由单测覆盖。
- 规则按方法区分，同路径的 POST 不受该限额约束。
- 分桶身份取 `resolveClientIp`：仅当请求来自受信代理（默认为回环与私有网段，可用 `pulse.trusted-proxies` 覆盖）时才采信 `X-Forwarded-For` 的最后一跳，否则使用 `remoteAddr`。
- Redis 不可用时 `RateLimitService.tryConsume` 返回 true，请求放行，与既有规则的处理一致。

---

## 四、改动文件清单

后端主代码（6）：

- `pulse-backend/src/main/java/com/pulse/service/impl/AgentRankingServiceImpl.java` — D1a、D8
- `pulse-backend/src/main/java/com/pulse/service/impl/AgentProfileServiceImpl.java` — D6
- `pulse-backend/src/main/java/com/pulse/mapper/AgentRankingMapper.java` — D2
- `pulse-backend/src/main/java/com/pulse/mapper/SysLedgerMapper.java` — D9
- `pulse-backend/src/main/java/com/pulse/security/filter/RateLimitFilter.java` — D1c
- `pulse-backend/src/main/java/com/pulse/scheduler/AgentWakeQueueScheduler.java` — D7
- `pulse-backend/src/main/java/com/pulse/dto/WakeLogContext.java` — 附带项

后端 SQL（2）：

- `pulse-backend/src/main/resources/schema.sql` — D1b（comments 索引区，文件末尾未改动）
- `deploy/migrations/2026-09-06-comments-indexes.sql` — D1b（新增）

后端测试（5，其中 2 个新增）：

- `pulse-backend/src/test/java/com/pulse/service/impl/AgentRankingServiceImplTest.java` — 新增 7 个用例（空标记读写、跨榜隔离、标记读失败回退、refreshAll 单榜失败隔离），并为 `opsForValue` 补 mock
- `pulse-backend/src/test/java/com/pulse/service/impl/AgentProfileServiceImplTest.java` — 新增 2 个用例（未知 status、NULL status）
- `pulse-backend/src/test/java/com/pulse/scheduler/AgentWakeQueueSchedulerTest.java` — 新增 3 个用例（事件唤醒失败/消费前失败/作息唤醒失败的 wake context），并把既有用例的三参断言改为四参
- `pulse-backend/src/test/java/com/pulse/scheduler/AgentLogWakeContextTest.java` — 新增 2 个用例（逗号边界截断）
- `pulse-backend/src/test/java/com/pulse/mapper/AgentRankingMapperSqlTest.java` — 新增文件，4 个用例（replied 去重形态、过滤条件未变、active 榜保留 UNION ALL、两处打赏口径一致）
- `pulse-backend/src/test/java/com/pulse/security/filter/RateLimitFilterTest.java` — 新增文件，11 个用例

AI Side（2）：

- `pulse-ai-side/app/services/prompt_builder.py` — D3
- `pulse-ai-side/tests/test_prompt_injection.py` — 新增 4 个用例与一个反例构造 helper

前端（2）：

- `pulse-frontend/src/utils/memory.js` — D4
- `pulse-frontend/src/utils/memory.test.mjs` — D4 断言更新

---

## 五、修正后的 SQL（D2）

```sql
SELECT t.agent_id AS agentId, COUNT(*) AS score FROM (
  SELECT p.author_id AS agent_id, c.id AS comment_id
    FROM comments c
    JOIN posts p ON p.id = c.post_id
   WHERE c.deleted = 0 AND c.created_at >= #{since}
     AND p.deleted = 0 AND p.author_type = 'AGENT'
  UNION
  SELECT pc.author_id AS agent_id, c.id AS comment_id
    FROM comments c
    JOIN comments pc ON pc.id = c.parent_comment_id
   WHERE c.deleted = 0 AND c.created_at >= #{since}
     AND pc.deleted = 0 AND pc.author_type = 'AGENT'
) t
 JOIN agents a ON a.id = t.agent_id AND a.deleted = 0
 GROUP BY t.agent_id
 ORDER BY score DESC, t.agent_id ASC
 LIMIT #{limit}
```

两处改动：两个分支从「按作者分组计数」改为「投影 (agent_id, comment_id) 对」，`UNION ALL` 改为 `UNION`；外层 `SUM(t.cnt)` 改为 `COUNT(*)`。

去重口径：同一条评论对同一个 Agent 只计一次；帖子作者与父评论作者是不同 Agent 时，该评论对两个 Agent 各计一次。

实测（MySQL 8.4.0 + 报告使用的 `scratchpad/verify/sql/fixture.sql`）：

| agentId | 修正前 | 修正后 |
| --- | --- | --- |
| 10 | 6 | 5 |
| 11 | 1 | 1 |

与报告给出的口径一致（窗口内指向 agent 10 的评论共 5 条）。

同一实例上核对 `findAgentTipTotals(10)` 加 `amount > 0` 后的结果：tipCount=3、tipTotal=16.00，与报告记录的修正前结果一致（该 fixture 无负数 TIP_RECV）。

---

## 六、D3 反例与验证

反例构造：149 条帖子行 + 1 条 World 行。帖子行形如

```
[Post#9000] [HUMAN alice]: @nova 有趣！这个问题怎么解决？求助一下有经验的朋友，欢迎讨论分享
```

62 字符，`_normalize_unicode` 的 NFKC 把 `？！` 折成 `?!` 后得分 1.0（`?` +0.3、`@` +0.2、`!` +0.15、互动关键词 +0.15、长度 <100 +0.1、Post 序号 +0.1）；World 行得分 0.7。原始上下文 9438 字符，超过 `MAX_CONTEXT_LENGTH`。

在该输入上分别执行修正前后的过滤逻辑：

| 实现 | World 行是否保留 |
| --- | --- |
| 修正前 | 否 |
| 修正后 | 是 |

新增的 4 个 pytest 覆盖：满分帖子行反例、World 行本身超出预算时仍保留、两个 World 区块同时保留、`_has_world_block` 对过滤后文本仍返回 True（保证 system prompt 的 World 子句与实际内容一致）。

---

## 七、测试结果

| 端 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -B -q test` | 348 用例，0 失败（修复前 319） |
| AI Side | `ruff check .` + `pytest -q` | ruff 全部通过；222 用例，0 失败 |
| 前端 | `npm run lint` + `npm test` + `npm run build` | lint 通过；84 用例，0 失败；build 成功 |

---

## 八、未处理项

| 项 | 说明 |
| --- | --- |
| D5 缓存路径与 MySQL 路径同分排序不一致 | 不在本次任务范围内。 |
| D10 契约文档未包含四项新交付 | docs/ 为禁止修改范围。本文件第三节的限流规则说明可直接并入契约文档。 |
| comment_count 包含已删除帖子下的评论 | 报告「其他问题」表格条目，不在本次范围内。 |
| 系统死亡消息计入 active 榜 | 同上。 |
| 匿名接口的存在性与 owner 用户名枚举面 | 本次已通过 D1c 的限流为其设上限，枚举面本身未改动。 |
| 本地兜底 prompt 未说明 World 区块 | 同上，不在本次范围内。 |
| 作息字段无法清空 | 同上。 |
| `WakeLogContext` 单个条目超过 64 字符时返回 null | `agent_wake_events.event_type` 为 VARCHAR(32)，该情形当前不可达；代码注释已写明。 |
