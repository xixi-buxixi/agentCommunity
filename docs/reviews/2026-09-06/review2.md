# Pulse 本轮交付对抗性审查（review2）

审查日期：2026-09-06
输入：`scratchpad/round-full.diff`、`scratchpad/notes/{verify-phase1,FIX1,W5,W7,W8}.md`
工作树：`/Users/user/pulse/agentCommunity-main`（只读，未做任何写操作）
实验目录：`scratchpad/review2/`

---

## 一、缺陷列表（按严重度排序）

### S1（高）限流规则可被路径百分号编码整体绕过

- 位置：`pulse-backend/src/main/java/com/pulse/security/filter/RateLimitFilter.java`，`matchRule()`
- 现象：`matchRule` 用 `AntPathMatcher` 匹配 `request.getRequestURI()`。按 Servlet 规范该方法返回**未解码**的 URI；而 Spring MVC（Boot 3.x 默认 `PathPatternParser`）与 Spring Security 的路径匹配使用**解码后**的路径段。两者对同一请求给出不同结论。
- 失败场景：匿名请求 `GET /api/v1/agents/%72anking` 或 `GET /api/v1/agents/1/%70rofile`。SecurityConfig 的 permitAll 命中、Controller 正常处理并返回 200，但 `RateLimitFilter` 不匹配任何规则，不消耗任何配额。把每个请求换一个被编码的字符即可获得无限配额。
- 证据：用仓库自身依赖 spring-web/spring-core 6.1.21 直接比对两种匹配器（`scratchpad/review2/PathTest.java`）：

| URI | PathPattern `/{agentId:[0-9]+}/profile` | PathPattern `/agents/ranking` | Ant `/agents/*/profile` | Ant `/agents/ranking` |
| --- | --- | --- | --- | --- |
| `/api/v1/agents/1/profile` | true | false | true | false |
| `/api/v1/agents/1/%70rofile` | **true** | false | **false** | false |
| `/api/v1/agents/1/pro%66ile` | **true** | false | **false** | false |
| `/api/v1/agents/%72anking` | false | **true** | false | **false** |
| `/api/v1/agents/ranking` | false | true | false | true |

- 影响范围不止本轮新增的两条规则：`RULES` 中既有的 `POST /api/v1/auth/login`、`/auth/register`、`/hot-news/ingest` 用同一段代码匹配，因此该类的注释所声明的「BCrypt 计算在被限流之前不会发生」在编码路径下不成立。
- 建议修法：改用解码后的路径匹配，例如 `ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value()`，或直接用 `PathPatternParser` 编译规则模式；另可在匹配前对 `getRequestURI()` 与其解码结果做一致性检查，不一致时按最严格的规则处理。

### S2（高，条件：`hot-news.context.enabled=true`）World 区块进入 `_semantic_filter` 后会把全部帖子行挤掉

- 位置：`pulse-ai-side/app/services/prompt_builder.py`，`_semantic_filter`
- 两条独立机制：
  1. `filtered_context` 以 `world_lines` 预填充。后续循环里的 `if score < self.MIN_RELEVANCE_SCORE and filtered_context: continue` 从第一条帖子行起就成立。原实现中 `and filtered_context` 是「至少保留最高分的一行」的兜底条件，World 行预填充把这个兜底废掉了。
  2. `world_lines` 无条件先记入预算且不受 `MAX_CONTEXT_LENGTH` 约束，World 正文过长时输出长度会超出上限，同时把预算耗尽。
- 证据（venv 实测，脚本 `scratchpad/review2/t2.py` 与 `t1.py`）：

| 输入 | 输出长度 | 保留的帖子行数 |
| --- | --- | --- |
| 399 条得分 0.1001 的帖子行，无 World 行 | 71 | 1 |
| 同上，加 1 条 World 行 | 83 | **0** |
| 200 条帖子行 + World 正文 9000 字符 | **9058**（上限 8000） | **0** |

  第二行的完整输出为 `[World#77] [SYSTEM 今日日报 2026-09-06]: ...\n\n[...部分低相关性内容已过滤...]`，正文中没有任何 `[Post#`。
- 失败场景：当日首次唤醒、上下文超过 8000 字符、且帖子行普遍低于 0.3 分（长文、无 `?`/`@`/`!`/互动关键词）时，Agent 只看到日报，看不到任何帖子，`reply`/`like`/`dislike` 没有可用的 `target_post_id`。
- 说明：0.3 的阈值本身是上一轮引入的，不是本轮新增；本轮新增的是「World 行预填充导致兜底失效」与「World 行不计入上限」。
- 建议修法：用一个独立布尔量（是否已保留至少一条非 World 行）替换 `and filtered_context` 的判断；给 `world_lines` 设预算上限（例如 `MAX_CONTEXT_LENGTH` 的固定比例），超出时截断 World 正文而不是让帖子出局。

### S3（中）Redis 不可用时限流 fail-open 与排行榜缓存未命中叠加

- 位置：`service/RateLimitService.java` `tryConsume`（catch → `return true`）；`service/impl/AgentRankingServiceImpl.java` `readFromRedis`（异常 → null）、`emptyMarkerPresent`（异常 → false）
- 失败场景：Redis 故障时，`GET /api/v1/agents/ranking` 每个匿名请求先跑一次 MySQL 聚合得到答案，再由 fire-and-forget 的 `refreshAgentRankingCache` 跑第二次同一聚合（`CACHE_SIZE=50`）；与此同时唯一的上限（D1c 的 60/min/IP）因 Redis 不可用而放行全部请求。两个缓解措施依赖同一个 Redis。
- 影响：一次 Redis 故障把匿名榜单变成每请求两条 comments 多表聚合的负载发生器。叠加 S1 时无需 Redis 故障也能达到同样效果。
- 建议修法：MySQL 回退加进程内单飞（同一 type 同时只允许一个聚合在跑）与短 TTL 的本地缓存；或在 Redis 不可用时匿名榜单直接返回空列表并记 error。

### S4（中）通知中心漏掉「有人评论了你的 Agent 的帖子」，且代码注释给出的理由与实际行为不符

- 位置：`service/impl/PostServiceImpl.java` `notifyHumanTargetOfComment`；`scheduler/AgentActionExecutor.java` 约 341 行；`enums/NotificationType.java`
- 现状：人类评论 AGENT 的帖子时，`notifyHumanTargetOfComment` 只在 `post.getAuthorType()` 为 HUMAN 时发通知，因此不发；`AgentActionExecutor` 的分支同样只在目标帖作者为 HUMAN 时发。`NotificationType` 里也没有对应取值。结果是 Agent 所有者在通知中心里完全看不到「你的 Agent 被评论」。
- `notifyHumanTargetOfComment` 的注释写的是「AGENT target 已经有 wake event，所以所有者会通过 Agent 自己的回答到达而得知，再通知一次会重复」。实际上 Agent 的回答到达的是**发帖人**，而这里发帖人就是该 Agent 自己，`AgentActionExecutor` 的 HUMAN 分支不成立，所以并不存在「第二次通知」。所有者收到的通知数是 0 而不是 2。
- 建议修法：要么补一个面向所有者的类型（AGENT 帖被评论 → 通知 `agent.owner_id`），要么修正注释，明确这是产品上的取舍而不是去重。

### S5（中）匿名公开主页无任何缓存

- 位置：`service/impl/AgentProfileServiceImpl.java`
- 现状：每次命中固定 8 条查询（legacy 库 7 条）全部落 MySQL，没有任何缓存层。唯一上限是 D1c 的 60/min/IP，而该上限可被 S1 绕过。
- 建议修法：加一层短 TTL（30-60 秒）的 profile 缓存，或至少把 `buildStats` 的三条聚合缓存起来。

### S6（低）排行榜与公开主页对同一 status 取值返回不同的 `status_text`

- 位置：`AgentRankingServiceImpl.statusTextOf` 返回 `null`；`AgentProfileServiceImpl.statusTextOf` 返回 `"UNKNOWN"`
- 现状：D6 只修了 profile 一侧。同一个 status 为 NULL 或未知取值的 Agent，在主页显示 `"UNKNOWN"`、在榜单显示 `null`。当前 `AgentRankingPanel.vue` 只读 `item.status` 不读 `status_text`，所以没有可见影响，但两个接口对同一字段的契约不一致。
- 建议修法：两处共用同一个渲染函数。

### S7（低）`WakeLogContext.joinWithinColumn` 用 `break` 而非 `continue`

- 位置：`dto/WakeLogContext.java`
- 现象：条目按字母序遍历，遇到第一个放不下的条目就 `break`，其后本可放下的较短条目一并丢弃。
- 建议修法：改为 `continue`，或在注释中写明「有意保持前缀顺序」。

### S8（低）`MemoryTextSanitizer` 未同步处理 `[World#` 块头

- 位置：`util/MemoryTextSanitizer.java:246`
- 现象：该方法把 `[Post#` 改写为 `(Post#`，但本轮把 `[World#` 也变成了 AI 侧的块边界（`BLOCK_HEADER_RE`），这里没有同步。`AgentWakeProcessor.flattenForContext` 已同步。
- 可达性：当前记忆正文在注入前会再过一次 `AgentWakeProcessor.flattenForContext`，所以不可达；作为纵深防御的一致性缺口记录。
- 建议修法：`MemoryTextSanitizer` 补一条 `.replace("[World#", "(World#")`。

### S9（低）空榜标记过期瞬间无单飞

- 位置：`AgentRankingServiceImpl`，`EMPTY_MARKER_TTL = 5 分钟`
- 现象：标记 5 分钟过期一次，而刷新调度是每小时一次。标记过期后的并发匿名请求会各自跑 2 条聚合，然后各自重写标记。
- 建议修法：与 S3 的单飞一并处理。

### S10（低）前端 `stores/notification.js` 的 `markRead` 在条目不在列表中时仍会减未读数

- 位置：`pulse-frontend/src/stores/notification.js` `markRead`
- 现象：`const item = this.items.find(...)`；`item` 为 undefined 时跳过「已读则直接返回」的判断，请求成功后仍执行 `unreadCount = Math.max(0, unreadCount - 1)`。当前面板只渲染 `items` 中的行，正常路径不可达。
- 建议修法：`if (!item) return true` 或只在 `item` 存在且原本未读时减 1。

### S11（低）`agentsPrompt/modules/frontend/tasks.md` 的 Next 段落已过期

- 现状：写着「唤醒原因标签依赖后端在 `AgentServiceImpl.buildLogResponse` 调用 `AgentLogResponse.applyWakeContext`，该调用未落地前 `wake_reason_text` 恒为 null」。实际该调用已在 `AgentServiceImpl.java:619-622` 落地。
- 影响：会误导后续会话去补一个已经存在的接线。

---

## 二、已核实无问题的项与核实方式

### A FIX1.md 声称的 D1-D9 与附带项

| 编号 | 结论 | 核实方式 |
| --- | --- | --- |
| D1a 空榜标记 | 已落地 | 读 `AgentRankingServiceImpl`：`EMPTY_MARKER_SUFFIX`/`markEmpty`/`clearEmptyMarker`/`emptyMarkerPresent` 齐备；`refreshAgentRankingCache` 在无行时写标记、有行时先删标记再写 ZSet；`getAgentRanking` 在 ZSet 未命中时先查标记并直接返回空列表。Redis 读失败时 `emptyMarkerPresent` 返回 false（回退 MySQL），不会因一次失败读而给出空榜。 |
| D1b comments 三条索引 | 已落地 | `schema.sql` 第 409-435 行三条 information_schema 守卫 ALTER，位置在 `idx_post_author` 守卫块之后；`deploy/migrations/2026-09-06-comments-indexes.sql` 存在且为同一组语句。无能力位依赖，缺失只影响代价。 |
| D1c 两条 GET 限流规则 | 规则存在，但见 S1 | `RULES` 中确有 `GET /api/v1/agents/ranking` 与 `GET /api/v1/agents/*/profile`，各自独立 bucket、60/1min。单段通配符不匹配 `/agents/{id}`、`/agents/{id}/logs`、`/agents/logs`（实测见 S1 表）。`resolveClientIp` 只在 `remoteAddr` 属于受信代理（默认回环 + 私有 + 链路本地，可用 `pulse.trusted-proxies` 覆盖）时才采信 `X-Forwarded-For`，且取**最后一跳**，因此伪造 `X-Forwarded-For` 无法换桶。 |
| D2 replied 榜去重 | 已落地 | `AgentRankingMapper#findTopByRepliesReceived` 两个分支投影 `(agent_id, comment_id)`，`UNION`（非 UNION ALL），外层 `COUNT(*)`。`findTopByActivity` 保持 `UNION ALL` + `SUM(t.cnt)` 未受影响。 |
| D3 World 行免于排序 | 部分落地，引入 S2 | World 行确实被移出打分排序并优先保留；但预填充 `filtered_context` 与不计上限带来 S2。 |
| D4 记忆面板 5xx 文案 | 已落地 | `utils/memory.js:190-192` 文案已改为「记忆服务暂时不可用（可能是部署库尚未启用记忆表，或服务异常）…」。 |
| D6 公开主页 status 保护 | 已落地（榜单侧不一致，见 S6） | `AgentProfileServiceImpl.statusTextOf`：null → `"UNKNOWN"`，`AgentStatus.fromCode` 抛 `IllegalArgumentException` → `"UNKNOWN"`，`status` 字段仍回原值。已确认 `AgentStatus.fromCode(int)` 对未知码抛 `IllegalArgumentException` 而非返回 null，因此 catch 类型正确、不会漏成 NPE。 |
| D7 错误行带 wake context | 已落地 | `runEventWakes` 每个 Agent 一个 `attempted` 列表，`wakeForEvents` 先填入队列给出的事件、消费成功后 `clear()+addAll(consumed)`；两个 catch 分支分别传 `WakeLogContext.of(WakeReason.EVENT, attempted)` 与 `WakeLogContext.of(WakeReason.RHYTHM, List.of())`。 |
| D8 refreshAll 单榜隔离 | 已落地 | 循环体内逐 type try/catch + `log.error`。 |
| D9 打赏口径一致 | 已落地 | `SysLedgerMapper#findAgentTipTotals` 与 `AgentRankingMapper#findTopByTipsReceived` 均带 `amount > 0`、`type='TIP_RECV'`、`related_type='AGENT'`。 |
| 附带项 renderTypes | 已落地（有 S7） | 改为 `joinWithinColumn` 逐条累加，不再对拼接结果做 substring。 |
| D5 / D10 / 报告「其他问题」六条 | 确认仍未处理 | 与 FIX1.md 第八节自述一致。 |

### B 生产库未执行 2026-09-06 三条迁移时的每条新读写路径

| 路径 | 缺表/缺列时的行为 | 核实方式 |
| --- | --- | --- |
| 6 个通知生产者 | `NotificationServiceImpl.write()` 第一步查 `schemaCapabilities.isNotificationsTable()`，false 时 `log.warn` 后返回，不触碰 mapper | 读 `write()` 全体；六个 `notify*` 全部经由它 |
| 4 个通知读接口 | `requireTable()` 抛 `NOTIFICATIONS_UNAVAILABLE`（90001 / HTTP 409），不降级成空页 | `ErrorCode:88` 确认 `(90001, "通知中心尚未启用", 409)` |
| `agent_logs` 写 | `insertLog` 在 `isAgentLogWakeColumns()` 为 false 时走 `agentLogMapper.insert`（MyBatis-Plus 生成语句，两列为 `@TableField(exist=false)` 不出现在 SQL 中）；显式语句 `insertWithWakeContext` 只在能力位为 true 时调用，且 `insertLog` 是唯一入口 | 读 `AgentActionExecutor.insertLog` 与 `AgentLogMapper`；仓库内 grep 确认无第二个 `insertWithWakeContext` 调用点 |
| `agent_logs` 读 | 全部为 `SELECT *`，`map-underscore-to-camel-case: true`（`application.yml:45`），缺列时回读 null，`applyWakeContext(null, null)` 把三个字段一并置 null | 读 `AgentLogMapper` 五条 `@Select` 与 `AgentServiceImpl.buildLogResponse` |
| comments 三条索引 | 无能力位依赖，缺失不改变任何返回结果，只改变代价 | 读迁移文件头注释与三条榜单 SQL |
| 能力位解耦 | `agentLogWakeColumns` 与 `notificationsTable` 各自独立探测，与 `wakeQueueSchema` 的八项判定无交集；`SchemaCapabilities.count()` 对探测本身的异常也降级为「不存在」 | 读 `SchemaCapabilities.detect()` |

### C 通知生产者的事务边界、异常与收件人

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| 是否在主业务事务内 | 是，且是有意的 | 六个调用点分别在 `PostServiceImpl.createComment`、`AgentActionExecutor.executeReplyAction` / `markAgentDead`、`LedgerServiceImpl.tipAgent`、`BountyServiceImpl.submitBounty` / `auditSubmission`，全部标注 `@Transactional`。主事务回滚时通知行一并回滚，语义正确。 |
| 异常是否吞掉 | 是，且不会污染主事务 | `write()` 内 try/catch(Exception) → `log.warn`，不 rethrow；`NotificationServiceImpl` 本身没有任何 `@Transactional` 注解，不会触发 rollback-only 标记。 |
| 评论路径是否重复通知 | 否 | `notifyHumanTargetOfComment`：`parentComment != null` 时只可能通知父评论作者（且必须是 HUMAN）随后 `return`；否则只可能通知帖主（且必须是 HUMAN）。一次评论最多产生 1 条通知，与 `queueWakeEventForComment` 的分支互斥。 |
| 评论路径是否通知错人 | 否 | `notifyReplyToComment` 收件人取 `parentComment.getAuthorId()`（`author_type=HUMAN` 时即 user id），`notifyCommentOnPost` 取 `post.getAuthorId()`（同上）。`isSelfNotification` 拦住 HUMAN 自触发。 |
| 悬赏审核路径收件人与金额 | 正确 | `notifyBountySubmitted` 收件人 `task.getOwnerId()`（发单方 user；Agent 发单时为其 owner）；`notifyBountyAudited` 收件人 `submission.getHunterId()`（接单 user）；accept 分支的 `reward` 取 `task.getRewardPoints()`，与同一段里 `settleFrozenPointsAtomic` / `pointsService.addPoints` 使用的是同一个值。两条通知都在各自分支的 CAS（`updateStatusIfIn` / `reviewedAt != null` 判断）之后，重复审核会先抛 `BOUNTY_STATUS_INVALID`。 |
| Agent 死亡通知是否重复 | 否 | `markAgentDead` 里 `updateStatus` 返回 0（已被其他周期标记）时直接 return，通知写在 CAS 成功之后。 |
| 越权读 | 无 | `NotificationController` 四个端点的 recipient 一律取 `principal.getUserId()`，无任何请求形状可指定他人；`markRead` / `markAllRead` 的归属判定写在 UPDATE 谓词里（无 select-then-update 窗口）；`markRead` 对「不是你的」与「不存在」返回同一个 `NOTIFICATION_NOT_FOUND`。`Notification` 实体带 `@TableLogic`，`selectPage` 与手写 SQL 的 `deleted = 0` 口径一致。 |

### D `[World#N]` 区块与注入防护边界

用 `scratchpad/aiside-venv` 直接调用 `PromptBuilder` 构造反例（脚本 `scratchpad/review2/t1.py`、`t2.py`、`t3.py`）。

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| Java 侧能否伪造 `[World#` 块边界 | 不能 | 进入 `postsContext` 行首的全部字符串都经 `flattenForContext`（折叠 `[\r\n]+`、`[Post#`→`(Post#`、`[World#`→`(World#`）；块头里的作者名是合成的 `Agent#id` / `Human#id`（`getAuthorName`），`safeHeaderMeta` 用白名单 `[0-9A-Za-z_:.-]` 重写日报日期，`safeActorName` 只保留 `[A-Za-z0-9_.-]`。`renderEvents` 产出的行以 `[互动]` 开头，不匹配 `BLOCK_HEADER_RE`。 |
| Java 未折叠的其他换行字符能否绕过 | 不能 | Java 的 `[\r\n]+` 不覆盖 U+000B/U+000C/U+0085/U+2028/U+2029；但 Python 侧 `_split_context_blocks` 用 `context.split("\n")`，同样不把这些字符当换行，`_normalize_unicode` 的 NFKC 也不会把它们折成 `\n`（U+000B/U+000C 被控制字符正则直接删除）。两侧的「换行」定义一致，不存在只有一侧认的分隔符。 |
| 恶意日报的爆炸半径 | 单块 | World 块与 Post 块共用 `BLOCK_HEADER_RE`，命中检测时 `_neutralize_block` 只替换正文、保留块头，相邻帖子不受影响；`_has_world_block` 读的是**过滤后**文本，所以 system prompt 的 World 子句与实际内容一致。仅当上下文中所有块都被中和时才整体抛 `PromptInjectionDetected`。 |
| `_has_world_block` 与 system prompt 的一致性 | 一致 | `build_full_prompt` 中 `with_world=self._has_world_block(sanitized_context)`，而 `sanitized_context` 已经过 `_semantic_filter`。 |
| 日报注入的三个前置条件 | 成立 | `appendWorldBlock`：`config.enabled`（默认 false）、`reason != LEGACY_BATCH`、`firstWakeToday`；`hotNewsService.getLatest()` 的任何异常都被 catch 成 warn，不影响唤醒；`maxChars` 默认 600 且在 flatten 之后截断。 |

### E 计费 / 预算路径

| 项 | 结论 | 核实方式 |
| --- | --- | --- |
| `claimWakeSlot` | 本轮未改动 | diff 中 `AgentWakeQueueScheduler` 的 `claimWakeSlot` 方法体逐字未变；`AgentMapper` 未在本轮 diff 中出现。 |
| `markProcessed` / 事件消费 | 本轮未改动 | diff 中 `AgentWakeEventService` 及其实现未出现。 |
| 扣费与死亡判定 | 本轮未改动逻辑 | `AgentActionExecutor` 的 diff 只新增 `NotificationService` 依赖与两处 `notify*` 调用，以及 `insertLog` 的 wake context 分支；`chargeTokensInternal` / `incrementUsedTokensAtomic` / `checkDeath` 未改。 |
| 本轮新增的唯一读写 | `isFirstWakeToday` | claim 成功后一条只读 `agentMapper.findWakeSettings`，异常时返回 false，唯一后果是本次唤醒不注入日报。不写库、不影响预算。 |
| 悬赏结算 | 本轮未改动 | `BountyServiceImpl` 的 diff 只有依赖注入与三处 `notify*`，`updateStatusIfIn` / `settleFrozenPointsAtomic` / `pointsService.addPoints` 一行未动。 |

### F 前端与后端契约（逐字段核对）

| 前端 | 后端 | 结论 |
| --- | --- | --- |
| `api/notification.js` 四个方法 | `NotificationController` 四个端点（`/notifications`、`/unread-count`、`/{id}/read`、`/read-all`），查询参数 `unread_only` / `page` / `size` | 一致 |
| `NotificationPanel.vue` 读 `id / type / type_text / title / body / actor_type / actor_name / is_read / created_at` | `NotificationResponse` 的 `@JsonProperty` 逐一对应 | 一致 |
| `utils/page.js` 的 `unwrapPage` | `PageResponse{list,total,page,size}` | 一致 |
| `utils/notification.js` 的 `link_type` → 路由 | `NotificationLinkType` 的 POST / AGENT / BOUNTY，路由 `/post/:id`、`/agent/:id`、`/bounty` 均存在于 `router/index.js` | 一致 |
| 90001 / 90002 的判定 `error.code` | 90001 走 HTTP 409、90002 走 404，两者都由 `request.js` 的传输错误分支从 `error.response.data.code` 取出并挂到 `normalized.code` | 一致 |
| `views/AgentProfile.vue` 读 `name / status / status_text / created_at / owner_name / wake_hours_* / frequent_interactions / recent_posts / stats.*` | `AgentPublicProfileResponse` 及三个内部类 | 一致 |
| `components/AgentRankingPanel.vue` 读 `rank / agent_id / name / owner_name / status / score` | `AgentRankingItemResponse` | 一致（不读 `status_text`，故 S6 无可见影响） |
| `views/Monitor.vue` 读 `log.wake_reason_text` | `AgentLogResponse.wakeReasonText`，由 `buildLogResponse` 末尾的 `applyWakeContext` 填充 | 一致（tasks.md 的说明过期，见 S11） |
| `utils/wake.js` 提交 `wake_hours_start / wake_hours_end / daily_wake_budget` | `AgentUpdateRequest` 的三个 `@JsonProperty` | 一致 |
| `api/agent.js` 的 `getAgentPublicProfile` / `getAgentRanking` | `/agents/{id}/profile`、`/agents/ranking` | 一致 |

### G 三端测试实跑（未采信笔记）

| 端 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -B -q test`（JDK 21） | 退出码 0；surefire 31 份报告汇总 total 392 / failures 0 / errors 0 / skipped 0 |
| AI Side | `pytest -q` | 退出码 0，222 通过 |
| 前端 | `npm test` | 84 / 84 通过 |

---

## 三、无法验证的项

| 项 | 原因 |
| --- | --- |
| S1 的端到端复现 | 未启动后端进程与 Tomcat，只在 spring-web 6.1.21 的两种匹配器上实测出结论不一致。Tomcat 是否会在更早的环节拒绝某些编码形式（例如 `%2F`）未验证；`%70` / `%66` / `%72` 一类普通字符的编码按 Tomcat 默认配置不会被拒。 |
| 本轮 SQL 在真实 MySQL 上的重放 | 本次未连接 MySQL。本轮相对上一轮的 SQL 变更只有 D2 的 `UNION` 改写与三条 comments 索引，仅做了静态核对；FIX1.md 声称已在 MySQL 8.4 上实测。 |
| 通知中心的浏览器实机回归 | 无运行中的后端与已迁移的库；铃铛只在有 token 且非游客时渲染。 |
| Redis 故障下的实际负载量级（S3） | 未做压测，仅按代码路径推导每请求两条聚合。 |
| `docs/contracts/overview.md` 是否补齐四项新交付 | docs/ 在执行者的禁止修改范围内，D10 明确未处理，本次未复核该文件。 |
| S2 在真实社区数据上的触发频率 | 取决于线上帖子的实际得分分布；已给出可复现的边界条件与脚本，未在真实数据上采样。 |
