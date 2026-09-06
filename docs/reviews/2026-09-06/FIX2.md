# review2 缺陷修复记录（FIX2）

日期：2026-09-06
工作树：`/Users/user/pulse/agentCommunity-main`（未做任何 git 写操作）
输入：`scratchpad/notes/review2.md`

---

## 1. 每项修法

| 编号 | 修法 |
| --- | --- |
| S1 | `RateLimitFilter` 改用 `UrlPathHelper.defaultInstance.getPathWithinApplication(request)` 取解码后、去掉 context path 与路径参数的路径，再经 `StringUtils.cleanPath` 解析 `.` / `..` 段并把重复斜杠折为一个，然后匹配规则；解码抛 `IllegalArgumentException`（非法百分号序列）时直接返回 400（`INVALID_PARAMETER`），不放行。 |
| S2 | `_semantic_filter` 中 World 行先经新增的 `_fit_world_lines` 按 `WORLD_CONTEXT_RATIO = 0.125`（即 `MAX_CONTEXT_LENGTH` 的 1/8 = 1000 字符）截断后再计入预算；「至少保留一条帖子行」的判断改用独立计数 `kept_post_lines`，不再读 `filtered_context`。 |
| S3 + S5 | 新增 `service/support/ExpiringCache`（`ConcurrentHashMap` + `System.nanoTime()` 时间戳，无新依赖）。`AgentRankingServiceImpl` 只在 MySQL 回退路径上按 `type:limit` 缓存渲染后的榜单 60 秒；`AgentProfileServiceImpl` 按 agent id 缓存公开主页 30 秒。Redis 命中路径不读也不写该缓存。 |
| S4 | 新增两个 `NotificationType`，在 `PostServiceImpl` 的人类评论路径上，当被评论帖或被回复评论的作者为 AGENT 时通知该 Agent 的所有者；`AgentActionExecutor` 的注释改为说明这是产品取舍。原方法 `notifyHumanTargetOfComment` 更名为 `notifyTargetOfComment`，注释中「所有者会通过 Agent 的回答得知」的说法已删除。 |
| S6 | `AgentRankingServiceImpl.statusTextOf` 对 null 与未知状态返回 `"UNKNOWN"`，与 `AgentProfileServiceImpl` 一致。 |
| S7 | `WakeLogContext.joinWithinColumn` 中放不下的条目改为 `continue`，后续较短条目仍可入列。 |
| S8 | `MemoryTextSanitizer.flatten` 增加 `.replace("[World#", "(World#")`。 |
| S10 | `stores/notification.js` 的 `markRead` 只在目标行存在于 `items` 时把 `is_read` 置真并减未读数；不在列表中的 id 仍发请求但不动角标。 |
| S11 | `agentsPrompt/modules/frontend/tasks.md` 第 87 行改为「后端已在 `AgentServiceImpl.buildLogResponse` 接线」。 |

---

## 2. 新增通知类型与触发条件（供契约文档）

| type | type_text | 接收者 | actor | link | 触发点 |
| --- | --- | --- | --- | --- | --- |
| `AGENT_POST_COMMENTED_BY_HUMAN` | 有人评论了你的 Agent 的帖子 | Agent 所有者（`agents.owner_id`） | HUMAN / 评论者 userId | POST / postId | `PostServiceImpl.createComment`，`parent_comment_id` 为空且帖子 `author_type = AGENT` |
| `AGENT_COMMENT_REPLIED_BY_HUMAN` | 有人回复了你的 Agent 的评论 | Agent 所有者 | HUMAN / 评论者 userId | POST / postId | 同上，`parent_comment_id` 有值且父评论 `author_type = AGENT` |

body 形如 `Agent [Nova] 的帖子收到新评论：{评论正文}` 与 `Agent [Nova] 的评论收到回复：{评论正文}`，正文经 `excerpt`（120 字上限 + flatten）处理。

约束：

- link 指向 POST 而不是 AGENT：所有者要打开的是对话本身。
- 只有人类评论触发。`notifyTargetOfComment` 只在人类评论路径上运行，Agent 的评论走 `AgentActionExecutor`，因此 Agent 之间互评不产生通知。
- 所有者评论自己的 Agent 不产生通知：顶层评论在上游被 `SELF_POST_DIRECT_COMMENT_FORBIDDEN` 拒绝，回复自己 Agent 的评论由 `NotificationServiceImpl.isSelfNotification` 拦截。
- Agent 无法解析（`agentMapper.selectById` 返回 null）时不写通知，评论本身不受影响。
- 缺 `notifications` 表时与其余六个生产者一样由 `write()` 的能力位判断丢弃。
- `scratchpad/notes/W7.md` 第 3 节的类型清单与判断依据已同步。

---

## 3. 进程内缓存参数

`com.pulse.service.support.ExpiringCache<K, V>`：

- 固定 TTL，读时判过期并删除过期项；写时若达到容量上限，先删除全部已过期项，仍达上限则清空整个缓存。
- 时间源为 `System.nanoTime()`，可通过构造参数注入 `LongSupplier` 以便测试。
- 值被多个读者共享，调用方需存入不可变对象；该类不做防御性拷贝。
- 进程内，非集群共享。

| 使用点 | key | TTL | 容量上限 | 生效条件 |
| --- | --- | --- | --- | --- |
| `AgentRankingServiceImpl` | `type:limit` | 60 秒 | 1000 | 仅 MySQL 回退路径（Redis 未命中或读失败，且无空榜标记）。空结果同样缓存。Redis 命中时不读不写。 |
| `AgentProfileServiceImpl` | agentId | 30 秒 | 1000 | 仅成功响应。`AGENT_NOT_FOUND` 不缓存。 |

榜单缓存键含 limit，因此 limit=10 的结果不会用于 limit=25 的请求。

---

## 4. 改动文件清单

后端主代码：

- `pulse-backend/src/main/java/com/pulse/security/filter/RateLimitFilter.java`
- `pulse-backend/src/main/java/com/pulse/service/support/ExpiringCache.java`（新增）
- `pulse-backend/src/main/java/com/pulse/service/impl/AgentRankingServiceImpl.java`
- `pulse-backend/src/main/java/com/pulse/service/impl/AgentProfileServiceImpl.java`
- `pulse-backend/src/main/java/com/pulse/enums/NotificationType.java`
- `pulse-backend/src/main/java/com/pulse/service/NotificationService.java`
- `pulse-backend/src/main/java/com/pulse/service/impl/NotificationServiceImpl.java`
- `pulse-backend/src/main/java/com/pulse/service/impl/PostServiceImpl.java`
- `pulse-backend/src/main/java/com/pulse/scheduler/AgentActionExecutor.java`（仅注释）
- `pulse-backend/src/main/java/com/pulse/dto/WakeLogContext.java`
- `pulse-backend/src/main/java/com/pulse/util/MemoryTextSanitizer.java`

后端测试：

- `pulse-backend/src/test/java/com/pulse/security/filter/RateLimitFilterTest.java`（+6）
- `pulse-backend/src/test/java/com/pulse/service/impl/AgentRankingServiceImplTest.java`（+5）
- `pulse-backend/src/test/java/com/pulse/service/impl/AgentProfileServiceImplTest.java`（+3）
- `pulse-backend/src/test/java/com/pulse/service/impl/NotificationServiceImplTest.java`（+3）
- `pulse-backend/src/test/java/com/pulse/service/impl/PostServiceImplTest.java`（改 2，+2）
- `pulse-backend/src/test/java/com/pulse/scheduler/AgentLogWakeContextTest.java`（+1，改 1 处注释）
- `pulse-backend/src/test/java/com/pulse/util/MemoryTextSanitizerTest.java`（新增，4 条）

AI Side：

- `pulse-ai-side/app/services/prompt_builder.py`
- `pulse-ai-side/tests/test_prompt_injection.py`（+2，改 1 处注释）

前端：

- `pulse-frontend/src/stores/notification.js`
- `pulse-frontend/src/stores/notification.test.mjs`（新增，4 条）

文档：

- `agentsPrompt/modules/frontend/tasks.md`（S11 指定的一句）
- `scratchpad/notes/W7.md`（通知类型清单同步）

---

## 5. 测试结果

| 端 | 命令 | 结果 |
| --- | --- | --- |
| 后端 | `mvn -B test` | 416 通过，0 失败 0 错误（原 392） |
| AI Side | `ruff check .` + `pytest` | ruff 通过；224 通过（原 222） |
| 前端 | `npm run lint` + `npm test` + `npm run build` | lint 无输出；88 通过（原 84）；build 成功 |

---

## 6. 未处理项

| 项 | 说明 |
| --- | --- |
| S9 空榜标记过期瞬间无单飞 | 未实现单飞。S3 的进程内缓存把该窗口内的重复聚合降为每 60 秒一次，但同一进程内并发到达的多个请求仍可能各自跑一次聚合，跨进程亦然。 |
| S1 端到端复现 | 未启动 Tomcat 与后端进程。修法在 `MockHttpServletRequest` 上验证；Tomcat 对某些编码形式（如 `%2F`）的更早拒绝未验证。 |
| 榜单缓存与 Redis 恢复 | `refreshAgentRankingCache` 成功后不清进程内回退缓存。Redis 恢复后读路径直接走 Redis，不再读该缓存，因此无可见影响，但缓存项会保留到过期。 |
| 公开主页缓存的可变性 | `AgentPublicProfileResponse` 是带 setter 的 POJO，缓存值被多个请求共享。当前无调用方修改它，未加不可变包装。 |
| 缓存跨实例不一致 | 进程内缓存，多实例部署时两个实例的回退答案可能相差一个 TTL。 |
| review2 第三节列出的其他无法验证项 | 真实 MySQL 重放、通知中心浏览器实机回归、Redis 故障下的负载量级、S2 在真实数据上的触发频率，均未进行。 |
| D5 / D10 与上一轮报告「其他问题」六条 | 本次任务范围之外，仍未处理。`docs/` 在禁止修改范围内，`docs/contracts/overview.md` 未补新增的两个通知类型。 |
