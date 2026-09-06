# FIX4：review3-gemini 三项修复

## 1. 反思游标：SKIPPED 也推进

修法：`MemoryReflectionScheduler` 循环内对每个被检视的 Agent 无条件调用
`markReflectionAttempt`，游标记录的语义由「本次反思」改为「本次检视」；
`Outcome` 只再决定是否消耗 `max-agents-per-run`（SKIPPED / EMPTY / BLOCKED 均不计入，未变）。

影响：token 耗尽或当日已结算的 Agent 不再因 `last_reflection_attempt_at IS NULL`
长期排在队首、每晚被重复读取并拒绝，其后的 Agent 得以进入分页视野。

改动文件：
- `pulse-backend/src/main/java/com/pulse/scheduler/MemoryReflectionScheduler.java`
- `pulse-backend/src/test/java/com/pulse/scheduler/MemoryReflectionCursorTest.java`
  （`anAgentSkippedForExhaustionOrIdempotenceIsNotStamped` 改为
  `...IsStampedToo`，断言两个被拒绝的 Agent 均写入时间戳且未发起 LLM 调用）

`MemoryReflectionSchedulerTest` 未改：其用例走 id 游标降级路径（`isReflectionCursorColumn`
未打桩，返回 false），本身不断言打标。`MemoryReflectionPlatformGateTest` 未改：
BLOCKED 原本即已打标。

## 2. 创建请求带作息

修法：`AgentCreateRequest` 新增 `wake_hours_start`、`wake_hours_end`（0-23）、
`daily_wake_budget`（1-24），校验注解与 `AgentUpdateRequest` 逐字一致；
`AgentServiceImpl.createAgent` 在 insert 与 `assignInitialWakeRhythm` 之后、
同一事务内调用新增的 `applyRequestedWakeRhythm`，后者复用 `applyWakeSettings`
（该方法由接收 `AgentUpdateRequest` 改为接收三个 `Integer`，更新路径保留一个同名重载转发）。

降级：创建路径在 schema 缺失时 warn 并忽略三字段（不抛 20009），且整段写入包 try/catch，
作息写入失败不影响创建；更新路径仍抛 `AGENT_WAKE_SETTINGS_UNAVAILABLE`。
`applyWakeSettings` 中未提供的字段回退顺序改为「库中已存行 → agent 对象上的值 → 默认值」，
使创建路径能沿用刚刚随机播种的时段（更新路径这些列是
`@TableField(exist = false)`，回退值恒为 null，行为不变）。

响应：`buildDetailResponse` 照常从库中读回三字段返回。

改动文件：
- `pulse-backend/src/main/java/com/pulse/dto/request/AgentCreateRequest.java`
- `pulse-backend/src/main/java/com/pulse/service/impl/AgentServiceImpl.java`
- `pulse-backend/src/test/java/com/pulse/service/impl/AgentWakeSettingsTest.java`
  （新增 4 条：带作息创建写入并回填；只带预算时保留播种时段；
  缺 schema 时创建成功且三字段为 null 且不写库；作息写入抛异常时创建仍成功）

前端未改动：`AgentCreateWizard.vue` 的补写逻辑已由
`buildWakeFollowUpPayload(payload, created)` 实现——逐字段比较提交值与创建响应，
一致则不发第二次请求；`src/utils/agentTemplate.test.mjs` 已覆盖该语义。
审查报告依据的是较早的 worktree 快照。

## 3. @提及改为候选驱动

修法：`MentionDetector` 不再用固定字符集正则从正文中切出名称，改为对每个候选名称
在正文中查找 `@` + 名称（`regionMatches` 忽略大小写），要求名称之后是文本结尾或
非「字母/数字/下划线/连字符」的字符（空白与标点均满足）。新 API：
`containsMentionMarker(text)`（廉价前置判断，正文无 `@` 时不查候选）、
`mentions(text, name)`、`detect(text, candidateNames)`。
`AgentMentionServiceImpl` 先解析候选集，再把候选名称交给检测器。

边界值保持既有：`MAX_NAME_LENGTH` 50、`MAX_MENTIONS` 20（改为「一条正文最多命中的
不同名称数」，按小写去重）、`MAX_THREAD_AGENTS` 50、`MAX_OWNED_AGENTS` 50；
正文长度上限仍由请求层（`PostCreateRequest` 500 字符）约束，未新增。

行为变化：名称含空格、点、中英混合等任意字符的 Agent 现在可被提及；
`@Alice` 不再命中候选 Alice2，`@Alice2` 不再命中候选 Alice；
`@小明的看法` 不命中候选「小明」（与旧正则一致）。
`@` 左侧不作要求，`alice@example.com` 仍可命中名为 example 的候选（与旧行为一致）。

改动文件：
- `pulse-backend/src/main/java/com/pulse/service/support/MentionDetector.java`（重写）
- `pulse-backend/src/main/java/com/pulse/service/impl/AgentMentionServiceImpl.java`
- `pulse-backend/src/test/java/com/pulse/service/support/MentionDetectorTest.java`
  （原有 12 条按候选驱动改写并保留语义，新增：含空格/点的名称、中英混合名称、
  前缀歧义、大小写、无候选、超长/空候选、`containsMentionMarker`）
- `pulse-backend/src/test/java/com/pulse/service/impl/AgentMentionServiceImplTest.java`
  （新增：名称含空格/点的 Agent 可被提及；Alice 与 Alice2 同为候选时 `@Alice2` 只唤醒后者）

## 测试

- 后端 `mvn -B -q test`：673 通过，0 失败（原 662，新增 11 条）
- 前端 `npm run lint && npm test && npm run build`：107 通过，lint 与 build 无告警

## 未解决项

- 审查报告的[中危]「通知去重读写 TOCTOU」（`NotificationServiceImpl` 先查
  `countRecentDuplicates` 再 insert）不在本次修复范围，未处理。
- 审查报告的[低危]「Agent 命名字符白名单」未采纳：本次改为候选驱动匹配后，
  任意字符的名称均可被提及，加白名单会反过来限制已存在的名称。
- 创建带作息时会先后发生两次 `updateWakeSettings`（播种一次、按请求覆盖一次），
  同一事务内，未合并。
- `@` 左侧不设边界，形如 `xxx@Name` 的文本仍可命中候选 Name，属既有行为，未改。
