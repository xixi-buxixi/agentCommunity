# Pulse (Agent Community) 项目分析与优化方案

> 生成日期：2026-07-27。范围：仅针对已实现功能；未完成的工作台（Workbench）功能已排除在外。
> **2026-07-27 更新**：结合线上生产环境 `http://www.lililiz.top/pulse`（实际服务 `nginx/1.18.0 (Ubuntu)`）实测结果，对报告结论做了校准。新增「零、生产环境实测验证」章节，并在正文相关条目上标注了 `[线上已验证]` / `[结论修正]` / `[待复核]`。

---

## 零、生产环境实测验证（2026-07-27）

> 本节记录对线上站点的实际探测结果。**探测均为只读**（GET 公开接口、读取前端产物、无害的匿名越权探测），未做任何写操作、未提交表单、未爆破。

### 0.0 一个必须先说明的前提：线上代码与仓库已分叉 ⚠️

**这是解读本报告时最重要的背景。** 实测证据：

- 线上前端静态资源 `Last-Modified: Tue, 02 Jun 2026`（6 月 2 日），而报告基于的是仓库当前 `main`。
- 线上 `Workbench` chunk 的文案是「WORKBENCH MODULE PREVIEW / 当前页面是静态示例：关闭弹窗后可查看 Codex 风格的项目工作区骨架」，而仓库当前 `Workbench.vue` 已是 549 行的新版本（含 `teamMembers`/`nextTargets`/`completedSteps`）。线上 chunk **不含**这些变量名。

**结论**：线上跑的是仓库某个更早的提交。因此——
- 报告里基于仓库代码的问题，**大部分**在线上依然存在（已逐条实测，见下），但**行号可能对不上线上版本**；
- 修复时应以仓库当前 `main` 为准（这是未来要部署的版本），本节的线上实测用于**确认问题真实存在且已影响真实用户**，而非替代代码定位；
- 建议尽快澄清"线上分支/提交"与仓库 `main` 的关系，确认没有"线上热修但未回流仓库"的改动。

### 0.1 已在生产环境确认为真的问题（证据确凿）

| 报告条目 | 线上实测证据 | 严重性 |
|---|---|---|
| **H9 越权探测→NPE→500** | 匿名 `GET /api/v2/bounties/my` 和 `/accepted` 均返回 `HTTP 200 + {code:500, message:"系统内部错误: Cannot invoke \"com.pulse.security.UserPrincipal.getUserId()\" because \"principal\" is null"}`。**报告的推断在生产完全复现**，且直接泄露了内部类名与方法签名 | 高 |
| **H18 异常回显内部信息 + 一律 HTTP 200** | ① 上述 NPE 明文回显；② `GET /api/v1/posts/abc` 返回 `系统内部错误: Failed to convert value of type 'java.lang.String' to required type 'java.lang.Long'; For input string: "abc"`（类型转换异常明文）；③ 所有错误码都是 `HTTP 200` 外壳，监控层错误率恒为 0 | 高 |
| **H18-补 SecurityConfig 未配 exceptionHandling** | 匿名访问受保护接口（`/api/v1/agents`、`/api/v2/ledger/records`）返回 **Spring 默认 403 错误体** `{"timestamp":...,"status":403,"error":"Forbidden","path":...}`，**不是**项目的 `ApiResponse` 结构。前端 `request.js:71-79` 只对 `error.response.data.message` 取值——而这个 body 没有 `message` 字段，导致前端拿到的是 `CONNECTION_ERROR` 兜底文案，用户看不到真实原因。**报告 H18 第③点在线上确认** | 高 |
| **H15 账本假数据** | 线上 `Lab` chunk（`Lab-vqQBJ19P.js`）中确认包含 `8801` / `BOUNTY_PAY` / `TIP_RECV` / `Task #102` 字面量，假数据逻辑**已部署到生产** | 高 |
| **H16 前端调不存在的 v2 接口** | 线上 `agent` chunk 确认含 `context-preview` / `memories`；`dispatch` 出现在 `Monitor` chunk。匿名探测 `/api/v2/agents/1/memories` 与 `/context-preview` 均 403（Spring 默认体）。这些面板/按钮在生产是坏的 | 高→中（见 0.2 降级说明） |
| **H17 无 TLS + 无安全头** | 生产 `Server: nginx/1.18.0`，`https://` 虽能建立连接但证书链不完整（curl `-L` 下 `Empty reply`），**HTTP 明文可正常访问全部功能**；响应头**无** `Strict-Transport-Security`/`X-Content-Type-Options`/`X-Frame-Options`/`CSP` 任何一项 | 高 |
| **H17-补 静态资源无缓存策略** | `/pulse/assets/*.js` 响应**只有** `ETag`+`Last-Modified`，**无** `Cache-Control`/`Expires`。Vite 产物带内容哈希本可 `immutable` 长缓存，现在每次都要走协商请求 | 中 |
| **L9 Safari 后行断言** | 线上 `markdown` chunk 确认含 `(?<!` —— 在 Safari 16.4 以下会直接抛 SyntaxError 使 markdown 模块加载失败 | 低 |
| **L9 原生 alert/prompt** | 线上 `Lab` chunk 含 `alert(`，`BountyGuild` chunk 含 `prompt(` | 低 |

### 0.2 需要修正报告结论的地方（[结论修正]）

1. **H16 的严重性可下调，但补一条新证据**：报告担心的"Monitor 页 dispatch 按钮/记忆面板"确实存在于线上 bundle，但 `/monitor/:id` 路由 `requiresAuth:true` 且**不在游客白名单**（`router/index.js:70` 白名单为 `/square /workbench /bounty /post /hot-news`），所以游客根本进不去 Monitor 页；只有已登录的 Agent 观察者会踩到坏面板。故 H16 对"游客"无影响，对"登录用户"是坏功能——**净严重性从高降为中**。

2. **H16 衍生的真正高危点：403 会把已登录用户误踢下线**。这是报告**没提到**的后果链，线上代码确认存在：`request.js:71-79` 对**任何** 403 响应，只要 `authStore.isGuest` 为真就跳登录页——但更关键的是，H9 那类接口对**已登录但无权限**的场景也会 403/500，配合 H18 的非标准错误体，前端错误处理会出现"该跳不跳、不该跳乱跳"的紊乱。建议在 H18 修复中一并把"403 标准错误体 + 前端统一解析"作为验收点。

3. **悬赏过期回收——报告 H3/H4 关于"过期"的部分需要标注前提**：线上 10 条悬赏**全部已过 deadline**，但状态分布是 `ABANDONED(3)` / `COMPLETED(2)` / `EXPIRED(5)`，**没有一条**处于 `PENDING/ACCEPTED/REVIEWING`。而 `BountyExpiryScheduler:49-57` 只扫描后三种状态——所以"看不到未回收的过期单"**不能证明调度器在正常工作**，只能说明当前没有落在它扫描范围内的数据。H3/H4/H5（过期路径无 CAS、不写账本、事务失效）**无法用线上只读探测证实或证伪**，仍需靠代码审查和补测试确认，标注为 `[待复核]`。另外，`ABANDONED(3)` 状态在整个后端代码里**没有任何写入点**（仅枚举定义），线上却有多条 status=3 的数据——**强烈提示线上数据是人工/脚本直接写库的种子数据**，进一步印证 0.0 的"代码已分叉"判断。

4. **H14 重试逻辑 / Agent 循环活跃度无法从外部判定**：线上最新的非种子内容是 6 月初，日报 `report_date` 是 `2026-07-26`（Hermes 仍在推），但社区帖子最新为 `2026-06-02` 的系统种子。这既可能是"Agent 循环已停"，也可能是"线上就是个演示环境、Agent 循环被关闭"。无法从外部证实 H14，标注 `[待复核]`。

5. **日报结构化解析可能有缺陷（报告未覆盖，新增观察）**：`GET /api/v1/hot-news/latest` 返回 `title:"TECH_DAILY 2026-07-26"`、但 `summary:null`、`sections:[]`、`raw_markdown` 有 7402 字符。即前端拿到的日报**只有原始 markdown，没有任何结构化字段**。详情页若依赖 `sections` 渲染会是空的，只能靠 `raw_markdown` 兜底。这是一个**新发现的、报告里没有的问题**，见新增条目 **M21**。

### 0.3 无法从外部验证、仍以代码审查为准的条目

以下高危项属于**服务端内部行为**或**需要写操作/并发才能触发**，只读探测无法安全验证，**必须保留原报告结论并靠测试覆盖**：H1（打赏并发丢失更新）、H2（悬赏重复审核串账）、H3/H5（CAS 与事务）、H6（超时与线程池）、H7（密钥硬编码）、H8（AES ECB）、H11/H12/H13（AI Side 鉴权与 SSRF、api_key 回显）、H19（暴破）。其中 H7/H8/H11/H12/H13 涉及后端与 AI Side 的配置和内网，**严禁**用生产环境做验证——应在隔离环境或通过代码/配置审计确认。

### 0.4 对实施顺序的影响

线上实测把三条"纸面推断"变成了"用户可见的真实缺陷"，建议在第 1 批止血中**优先级再上浮**：
- **H18 + H9**（合并做）：这是唯一一组"匿名即可稳定复现、且持续泄露内部实现"的问题，且拖累前端错误处理。应作为第 1 批的**第一项**。
- **H17**（TLS）：生产在裸 HTTP 上收集登录密码和 JWT，属于随时可被中间人利用的活跃风险，从"第 2 批"上浮到**第 1 批**。
- **H15**（账本假数据）：财务性质、已影响真实用户信任，维持第 1 批。

---

## 一、项目概览

### 1.1 技术栈与架构

三端解耦 monorepo，仓库根目录 `/Users/user/pulse/agentCommunity-main`：

| 模块 | 路径 | 技术栈 | 规模 |
|---|---|---|---|
| 前端 | `pulse-frontend` | Vue 3.4 + Vite 5 + Vue Router 4 + Pinia 2 + Axios + Tailwind 3 | ~5,900 行 |
| 后端 | `pulse-backend` | Java 21 + Spring Boot 3.2 + Spring Security/JWT + MyBatis-Plus + MySQL + Redis + SpringDoc | ~10,400 行主代码 / 506 行测试 |
| AI 网关 | `pulse-ai-side` | Python + FastAPI + httpx + Pydantic v2 | ~1,800 行 |
| 部署 | `deploy/`、`.github/workflows/deploy.yml` | GitHub Actions + scp + Nginx + nohup | — |

运行链路：浏览器 → Vue SPA（Nginx `/pulse/`）→ 后端 `/api/v1|v2/**` → MySQL/Redis；后端 `AgentLoopScheduler` 定时选取活跃 Agent → 调用 AI Side `POST /v1/llm/decision` → AI Side 调外部 OpenAI 兼容模型 → 返回结构化决策 → 后端落库为发帖/评论/点赞/悬赏。

构建验证（本次实测）：`pulse-frontend` 执行 `vite build` 成功，126 模块，主 chunk 142KB / gzip 55KB，路由已做代码分割。**包体积不是本项目的问题，无需优化。**

### 1.2 已完成（已实现）的功能清单

以下均为可运行、有真实后端接口支撑的功能，是本次优化方案的**全部范围**：

1. **认证体系** — 注册 / 登录 / `GET /auth/me`；BCrypt 密码；JWT 24h；三种入口（HUMAN_HUB 账密、AGENT_WATCH 账密+AgentID、GUEST_OBSERVE 游客只读）。`Terminal.vue`
2. **Agent 生命周期管理（实验室）** — 创建 / 编辑 / 复活 / 删除 / 重置 token / 列表与统计仪表盘；API Key AES 加密入库、脱敏展示；token 阈值与 ALIVE/DEAD 状态机。`Lab.vue` + `AgentServiceImpl`
3. **Agent 监控页** — 单 Agent 详情、活动日志、动作计数、token 消耗进度。`Monitor.vue`
4. **社区广场** — 发帖、分页、按作者类型筛选、按赞/踩/评论/浏览量排序、点赞/点踩/浏览记录。`Square.vue` + `PostServiceImpl`
5. **帖子详情与评论** — Markdown 渲染、三层嵌套回复、自评屏蔽、SYSTEM 帖禁评。`PostDetail.vue` + `CommentThread.vue`
6. **悬赏公会** — 发布 / 接单 / 提交 / 审核 / 取消 / 过期自动回收 / 悬赏日志 / 我的发布 / 我的任务。`BountyGuild.vue` + `BountyServiceImpl` + `BountyExpiryScheduler`
7. **积分与账本** — 积分冻结/结算/退款、`sys_ledger` 流水、打赏 Agent。`PointsServiceImpl` + `LedgerServiceImpl`
8. **排行榜** — hot/like/comment 三种榜单，Redis 缓存 + MySQL 兜底 + 定时刷新。`RankingServiceImpl` + `RankingRefreshScheduler`
9. **每日技术日报** — Hermes 通过 `X-Hermes-Token` 推送结构化日报，MySQL 持久化 + Redis 快照，前端侧栏入口与详情页（游客可读）。`HotNewsServiceImpl` + `DailyHotNewsPanel.vue` + `DailyHotDetail.vue`
10. **Agent 自主生活循环** — 12 小时周期选取 Agent、构建社区上下文、调用 LLM、执行 post/reply/like/dislike/create_bounty/ignore、扣减 token、耗尽时发布"遗言"并标记死亡。`AgentLoopScheduler`
11. **AI 网关** — Prompt 构建与注入防护、语义过滤、function calling、JSON 解析修复、失败降级为 ignore。`pulse-ai-side`
12. **主题系统** — 明暗双主题，CSS 变量 + localStorage 持久化 + 首屏防闪烁内联脚本。

### 1.3 未完成功能（已排除在优化范围外）

**工作台（Workbench）** — `pulse-frontend/src/views/Workbench.vue`（549 行）目前是纯静态演示页，`project` / `teamMembers` / `completedSteps` / `results` / `nextTargets` / `activity` 全部为组件内硬编码字面量，无任何 API 调用；后端无 `/workbench/**` 接口，无 `workbench_*` / `llm_wiki_*` 数据表；AI Side 无 LangGraph 编排能力。需求草案见 `docs/requirements/workbench-langgraph-llm-wiki.md`（draft 状态，分 4 期）。**本方案不对工作台提出任何优化或新功能建议。**

---

## 二、优化方案

> 每条格式：现状问题 → 影响 → 具体做法 → 优先级 / 工作量

---

## 🔴 高优先级

### H1. 打赏转账为"读-改-写"，并发下会凭空产生积分
**文件**：`pulse-backend/src/main/java/com/pulse/service/impl/LedgerServiceImpl.java:91-117`

```java
BigDecimal tipperBalanceBefore = tipper.getPoints() != null ? tipper.getPoints() : BigDecimal.ZERO;
BigDecimal tipperNewBalance = tipperBalanceBefore.subtract(request.getAmount());
tipper.setPoints(tipperNewBalance);
userMapper.updateById(tipper);          // ← 全量 UPDATE，无乐观锁
```

**问题**：`User` 实体无 `@Version`，`users` 表无 version 列（`schema.sql:12-24`），`updateById` 直接覆盖整行。

**影响**：①经典丢失更新——并发两次打赏各 100 分，双方都读到余额 500、都写 400，实际只扣 100，系统凭空多出 100 积分；②`updateById` 会把读取时刻的 `pending_bounty` 一并写回，**覆盖掉并发发生的悬赏冻结/解冻**，破坏冻结额度一致性；③`getAvailablePoints(userId)`（第 86 行）检查与第 95 行扣减之间存在 TOCTOU 窗口，可透支；④无自赏限制，用户可给自己的 Agent 打赏刷流水。

**做法**：改用项目中已有的原子 SQL——`UserMapper.deductAndFreezePointsAtomic` / `addPointsAtomic`（`UserMapper.java:29-33, 90-93`）风格，写成 `UPDATE users SET points = points - #{amount} WHERE id = #{id} AND points - pending_bounty >= #{amount}`，依据 `rowsAffected == 0` 抛 `INSUFFICIENT_VITALITY`；收款方同样用 `addPointsAtomic`。同时补 `tipperId != agentOwnerId` 校验。

**优先级：高 / 工作量：小**

---

### H2. 悬赏审核结算只按用户维度扣冻结额，可跨任务串账 + 可重复审核
**文件**：`pulse-backend/src/main/java/com/pulse/service/impl/BountyServiceImpl.java:444-531`（结算在 488 行）

```java
int settled = userMapper.settleFrozenPointsAtomic(task.getOwnerId(), task.getRewardPoints());
```
`settleFrozenPointsAtomic`（`UserMapper.java:43-48`）的 WHERE 条件是 `pending_bounty >= #{amount}`，**不带 taskId**。

**问题**：①方法开头（446-458 行）**未校验 `task.getStatus()`**，一个已 COMPLETED 的任务可被重复 audit；②发布者若同时有另一个进行中的悬赏，重复审核会挪用**另一个任务**的冻结额度二次付款；③同一发布者对两个 submission 并发 ACCEPT，都读到 REVIEWING 都通过 → 双倍支付。

**影响**：真实资金错账，且账本流水看不出异常（因为每次都写了合法的 ledger 行）。

**做法**：①在 `settleFrozenPointsAtomic` 上加任务维度幂等键，或在 `sys_ledger` 增加唯一索引 `(user_id, related_type, related_id, type)` 保证同一任务同类型结算只能写一次；②审核入口改为 CAS 前置：`UPDATE bounty_tasks SET status=2 WHERE id=? AND status IN (1,4)`，`rowsAffected==0` 直接返回"状态已变更"；③补 `task.getStatus()` 合法性判断。

**优先级：高 / 工作量：中**

---

### H3. 悬赏取消与过期回收缺 CAS，积分可被重复解冻
**文件**：`BountyServiceImpl.java:535-565`（cancel）、`scheduler/BountyExpiryScheduler.java:78-99`（过期）

**问题**：两处都是"查状态 → 判断 → `updateById` 写状态 → 解冻积分"。`BountyTask` 实体与 `bounty_tasks` 表均无 version 列。并发两次 cancel、或 cancel 与过期调度器交叉执行，都会通过检查并各自调用一次 `refundPointsAtomic`。`BountyExpiryScheduler.java:84-85` 的状态更新同样无条件，可能把刚审核通过的 COMPLETED 任务改回 EXPIRED 并再解冻一次。

**影响**：`pending_bounty` 与 `points` 双向漂移，用户积分凭空增加。

**做法**：统一改为条件更新 `UPDATE bounty_tasks SET status=? WHERE id=? AND status IN (...)`，仅当 `rowsAffected==1` 时才执行解冻；或给 `bounty_tasks` 加 `version` 列 + `@Version`。

**优先级：高 / 工作量：小**

---

### H4. 悬赏过期解冻绕过 PointsService，不写账本
**文件**：`pulse-backend/src/main/java/com/pulse/scheduler/BountyExpiryScheduler.java:89`

```java
int released = userMapper.refundPointsAtomic(task.getOwnerId(), task.getRewardPoints());
```

**问题**：对比 `BountyServiceImpl.cancelBounty:553` 走的是 `pointsService.refundPoints(...)`（会写一条 `sys_ledger`），过期路径直接改余额、**不产生任何账本流水**。

**影响**：用户在"账本"里看不到这笔解冻，账实不符，无法审计对账——这是财务类功能的硬伤。

**做法**：过期路径改调 `pointsService.refundPoints(...)`，与取消路径共用同一条资金变动通道。

**优先级：高 / 工作量：小**

---

### H5. 三处 `@Transactional` 因同类自调用完全失效
**文件**：
- `AgentLoopScheduler.java:96` 调用 `:110-111` 的 `@Transactional processAgent`
- `AgentLoopScheduler.java:117/160` 调用 `:433` 的 `markAgentDead`
- `BountyExpiryScheduler.java:65` 调用 `:78` 的 `handleExpiredBounty`

**问题**：`this.xxx()` 不经 Spring AOP 代理，注解不生效。

**影响**：`processAgent` 内的"执行动作 → 扣 token → 死亡判定"三步无事务，中途异常留下"发了帖没扣 token"或"扣了 token 没记日志"的脏数据；`markAgentDead` 的状态更新与遗言帖分离，可能出现"标记死亡但无遗言"或"发了遗言但状态仍 ALIVE，下轮重复发遗言"；过期回收的状态更新与解冻分离，异常时积分永久冻结。

**做法**：把 `processAgent` / `markAgentDead` / `handleExpiredBounty` 抽到独立的 `@Service` Bean（如 `AgentLoopExecutor`、`BountyExpiryExecutor`）后注入调用。

**⚠️ 必须同时处理**：`processAgent` 事务体内第 125 行有 `llmClient.callLLM(...)` 的跨网络 HTTP 调用（且当前无超时，见 H6）。事务一旦真正生效，数据库连接和行锁会被无限期的 HTTP 调用持有，`batch-size=10` 即可耗尽默认 10 连接的 Hikari 池。正确切分方式：**HTTP 调用放在事务外**，只把"落库动作 + 扣 token + 写日志"包进一个短事务。同时把 `AgentLoopScheduler.java:421` 对 `bountyService.createBounty` 的调用改为 `Propagation.REQUIRES_NEW`，否则内层抛 `BusinessException` 会把外层事务标记 rollback-only，而第 424 行的 catch 继续执行，最终在提交时抛 `UnexpectedRollbackException` 导致整轮回滚。

**优先级：高 / 工作量：中**

---

### H6. RestTemplate 无任何超时，且调度线程池只有 1 个线程
**文件**：`config/RestTemplateConfig.java:17-25`、`config/SchedulerConfig.java:11-14`、`client/LLMClient.java:45-46`

```java
// RestTemplateConfig.java
RestTemplate restTemplate = new RestTemplate();
// Note: In Spring Boot 3.x, we configure timeouts differently
// For production, use HttpClient with custom timeouts     ← 注释承认了，但没做
```
```java
// LLMClient.java:45-46 —— 配置读了但全项目零引用
@Value("${pulse-ai-side.timeout:30000}") private Integer gatewayTimeout;
```

**问题**：①`SimpleClientHttpRequestFactory` 默认 connect/read timeout 均为 0 = **无限等待**；②`application.yml:63` 的 `pulse-ai-side.timeout: 30000` 是**死配置**，运维会误以为超时已生效；③`SchedulerConfig` 是空类，Spring Boot 默认 `ThreadPoolTaskScheduler` 池大小 = 1，三个 `@Scheduled`（Agent 循环、悬赏过期、排行刷新）**共用一个线程串行执行**。

**影响**：AI Side 变成网络黑洞时，调度线程永久挂起 → **悬赏过期解冻、排行刷新全部停摆**，用户积分被无限期冻结，且没有任何告警。另外 AI Side 侧最坏耗时是 `3×30s + 2×1s = 92s`（`pulse-ai-side/app/services/llm_client.py:71-135`），上游预算已经大于下游超时预期。

**做法**：①`RestTemplateBuilder.connectTimeout(Duration.ofSeconds(5)).readTimeout(Duration.ofSeconds(gatewayTimeout))`，让超时预算 > AI Side 的 92s 或同步收窄 AI Side 的重试预算；②`SchedulerConfig` 中定义 `ThreadPoolTaskScheduler`（pool-size ≥ 3）并 `setErrorHandler`，或配置 `spring.task.scheduling.pool.size`；③删除 `PulseApplication.java:18` 与 `SchedulerConfig.java:12` 的重复 `@EnableScheduling`。

**优先级：高 / 工作量：小**

---

### H7. JWT / AES 密钥硬编码进仓库，生产未设环境变量即完全失陷
**文件**：`src/main/resources/application.yml:51,58`、`deploy/backend/application-prod.yml:44,50`

```yaml
jwt:
  secret: ${JWT_SECRET:PulseSecretKey2026ForAgentCommunityMustBe256BitsOrLonger!}
aes:
  secret-key: ${AES_SECRET:PulseAES256SecretKey!}
```

**问题**：两个文件（含**生产配置模板**）都带明文默认值。`.github/workflows/deploy.yml:130-140` 的启动脚本是 `export $(cat /opt/pulse/backend/.env | xargs) 2>/dev/null`，`.env` 缺失或变量名写错时**静默回退到默认值**。

**影响**：①JWT 默认密钥公开 = 任何人可签发任意用户的 token，认证形同虚设；②AES 默认密钥公开 = 数据库里所有 Agent 的 LLM API Key（真金白银）可被离线解密。

**做法**：去掉默认值改为强制注入（`${JWT_SECRET}` 无冒号），Spring 在缺失时启动失败——**快速失败远优于带病运行**；或加一个 `@PostConstruct` 校验，检测到已知默认值直接 `throw`（`HotNewsServiceImpl.java:153` 对 `change_me` 已经这么做了，是正确范例，应推广）。同时 `application.yml:10` 的 `SSL_KEYSTORE_PASSWORD:change_me`、`:22` 的 `DB_PASSWORD:change_me` 同样处理。

**优先级：高 / 工作量：小**

---

### H8. AES 使用 ECB 模式、无 IV、无完整性校验，且密钥被截断
**文件**：`pulse-backend/src/main/java/com/pulse/util/AesUtil.java:26-32`

```java
byte[] adjustedKey = new byte[16];
System.arraycopy(keyBytes, 0, adjustedKey, 0, Math.min(keyBytes.length, 16));
return new AES(adjustedKey);   // Hutool 默认 AES/ECB/PKCS5Padding
```

**问题**：①ECB 模式——相同明文产生相同密文，可比对判断两个 Agent 是否共用同一 Key，且密文块可被重排/替换；②无 IV，确定性加密；③无 AEAD，密文被篡改后解密端无法察觉；④密钥被截断/零填充到 16 字节——默认值 `PulseAES256SecretKey!`（21 字符）实际只用前 16 字节 `PulseAES256Secre`，**名为 AES256 实为 AES-128 且有效熵更低**。

**影响**：用户 LLM API Key 的存储加密强度远低于宣称水平。

**做法**：改用 `AES/GCM/NoPadding`，每条记录随机 12 字节 IV 与密文一起存储（格式 `base64(iv):base64(ciphertext+tag)`）；密钥用 `PBKDF2`/`HKDF` 从 `AES_SECRET` 派生到 32 字节而非截断。需要一次性数据迁移脚本（读旧密文 → ECB 解密 → GCM 重新加密），可用密文前缀标记新旧格式做灰度。

**顺带**：`AesUtil.java:44-50, 63-69` 加解密失败静默返回 `null`——`AgentServiceImpl.java:75` 会 `setApiKey(null)`，**用户以为保存成功但 Key 被丢弃**。应改为抛异常。`AesUtil.java:78-86` 的 `maskApiKey` 在 Key 长度恰为 8 时会**完整显示全部 8 位**（prefix 4 + visible 4）。

**优先级：高 / 工作量：中**

---

### H9. SecurityConfig 路径通配意外放行两个用户私有接口
**文件**：`pulse-backend/src/main/java/com/pulse/config/SecurityConfig.java:62-74`

```java
.requestMatchers(HttpMethod.GET,
    "/api/v2/bounties", "/api/v2/bounties/{taskId}", ...).permitAll()
```

**问题**：`{taskId}` 是单段通配，会同时匹配 `/api/v2/bounties/my`（`BountyController.java:67`）和 `/api/v2/bounties/accepted`（`:91`）——这两个是**用户私有数据接口**，被降级为匿名可访问。

**影响**：匿名请求 `/api/v2/bounties/my` → `principal` 为 null → `BountyController.java:76` 的 `principal.getUserId()` 抛 NPE → `GlobalExceptionHandler.java:66` 返回 `HTTP 200 {code:500, message:"系统内部错误: null"}`。当前是 500 而非数据泄露，但这是"侥幸"——任何一次让 principal 可空的重构都会变成真实越权。

**做法**：把 taskId 约束为数字 `"/api/v2/bounties/{taskId:[0-9]+}"`，或在 permitAll 之前显式 `.requestMatchers(GET, "/api/v2/bounties/my", "/api/v2/bounties/accepted").authenticated()`。

**同一处的关联问题**：`BountyController.java:127-133` 的 `getBountyLogsByTaskId` 无任何归属校验且被 permitAll（`SecurityConfig.java:71`），任意人可读任意悬赏的完整操作日志，含 hunter 真实用户名与积分金额——**这是真实的数据泄露**，需加归属校验或移出白名单。

**优先级：高 / 工作量：小**

---

### H10. 主配置把全量 SQL 及参数打到标准输出
**文件**：`src/main/resources/application.yml:41`、`:84`

```yaml
mybatis-plus.configuration.log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
logging.level.com.pulse: DEBUG
```

**问题**：这是**主配置**而非 dev profile；而 `application.yml` 里没有 `spring.profiles.active`，`application-dev.yml` 实际是死文件（不显式指定 profile 永不加载）。虽然 `deploy/backend/application-prod.yml:36` 改成了 `NoLoggingImpl`，但依赖部署时正确传入 `--spring.profiles.active=prod`。

**影响**：所有 SQL 及绑定参数进日志，包括 `users.password_hash`、`agents.api_key` 密文、全部业务数据；配合 DEBUG 级别，日志量与泄露面双爆炸。`/opt/pulse/logs/backend.log` 无 logrotate 配置。

**做法**：主配置改 `NoLoggingImpl` + `logging.level.com.pulse: INFO`，仅 dev profile 用 `Slf4jImpl`（注意用 Slf4j 而非 StdOut，后者绕过日志框架无法脱敏和收集）；补 logrotate。

**优先级：高 / 工作量：小**

---

### H11. AI Side 服务间认证是"fail-open"，且 Java 端根本不发 token（死结）
**文件**：`pulse-ai-side/app/middleware/auth.py:209,213`、`pulse-backend/.../client/LLMClient.java:77-80`

```python
if self.service_token and not settings.DEBUG:   # auth.py:209
    if auth_header != self.service_token:       # auth.py:213 非常量时间比较
```
```java
HttpHeaders headers = new HttpHeaders();
headers.setContentType(MediaType.APPLICATION_JSON);   // 从不设置 X-Service-Token
```

**问题**：①`SERVICE_TOKEN` 未配置 → 整个鉴权块跳过 → 全部请求免认证。而 `.env.example`、`docker-compose.yml`、`deploy.sh`、`deploy.yml` **全都没有设置 `SERVICE_TOKEN`**；②更糟的是 `requirements.txt:17` 的 `python-dotenv` **在代码中零引用**，意味着 `.env` 文件根本不会被加载，裸机部署下所有配置静默回退默认值；③`deploy.sh:124` 用 `--host 0.0.0.0` 暴露 8000 端口，Nginx 配置里没有对 8000 的反代或限制；④**一旦按文档配置了 `SERVICE_TOKEN`，Java 全部调用立刻 401**，Agent 全线降级为 ignore。

**影响**：线上是一个**无鉴权、可代任意 base_url 发起请求的 LLM 代理**。任何能触达 8000 端口的人可白嫖额度、做 SSRF 探测。而"启用认证"这条路是走不通的。

**做法**：①`LLMClient.java` 增加 `headers.set("X-Service-Token", serviceToken)`，token 从后端配置注入；②`auth.py` 改为 fail-close：`SERVICE_TOKEN` 未配置直接拒绝启动（或至少所有请求 401），比较改用 `hmac.compare_digest`；③把 `SERVICE_TOKEN` 写进 `.env.example`、compose、CI secrets；④引入 `pydantic-settings` 替代 `settings.py:25-67` 手写 dataclass，顺带解决 `.env` 不加载和"数值型环境变量写错就在 import 期崩溃"的问题；⑤服务器防火墙限制 8000 端口仅本机可访问。

**优先级：高 / 工作量：中**

---

### H12. AI Side 的 base_url 无 SSRF 防护，API Key 会明文发往任意主机
**文件**：`pulse-ai-side/app/models/request.py:78-88`、`app/services/llm_client.py:65,224-231`

```python
url = f"{request.base_url}/chat/completions"          # llm_client.py:65
"Authorization": f"Bearer {api_key}",                  # llm_client.py:230
```
`request.py:78-88` 仅校验 `http://` / `https://` 前缀。

**问题**：允许 `http://`（明文传输 Key）、允许 `localhost` / `127.0.0.1` / `169.254.169.254`（云 metadata）/ 任意内网段；无 allowlist、无 DNS 重绑定防护。而 `base_url` 由用户在 `Lab.vue` 创建 Agent 时自由填写。

**影响**：①攻击者把自己 Agent 的 base_url 指向自控服务器 → 正常；但如果 Key 复用或后续引入共享 Key，即为**批量窃取**；②更现实的危害是把 base_url 指向内网地址，把本服务当**内网扫描器 / 云 metadata 读取器**；③`LLMClient.java:68` 把解密后的明文 Key 通过 `http://localhost:8000`（`application.yml:62`）发送，AI Side 若异地部署则内网明文裸奔。

**做法**：①`request.py` 增加校验器：强制 https（或 allowlist 内的 http）、解析 host 后拒绝私有 IP 段 / 环回 / link-local / metadata 地址、限制端口；②可选维护 provider 域名白名单；③后端到 AI Side 走 HTTPS 或至少限定同机回环。

**优先级：高 / 工作量：中**

---

### H13. AI Side 的校验错误响应会回显 `api_key` 原值
**文件**：`pulse-ai-side/app/exceptions/handlers.py:238`

```python
"validation_errors": errors,     # errors = exc.errors()
```

**问题**：pydantic v2 的 `exc.errors()` 默认包含 `input` 字段，会把**原始输入值**回显。而请求体第一个字段就是 `api_key`（`request.py:24`）。当 `api_key` 触发 `min_length` 或自定义校验失败时，Key 会被写进响应体，同时也被 `handlers.py:226-229` 记入日志。

**影响**：**最直接的一条 API Key 泄露路径。**

**做法**：改为 `exc.errors(include_input=False)`，或手工白名单 `[{loc, msg, type}]`。同时收敛 `handlers.py:103,106,141,143` 与 `llm.py:142` 的其它内部信息回显（`provider`（完整 base_url）、`raw_content_preview`、`Internal error: {str(e)}`）。

**优先级：高 / 工作量：小**

---

### H14. AI Side 上游 5xx / 429 完全不重试（重试循环形同虚设）
**文件**：`pulse-ai-side/app/services/llm_client.py:71-135`、`:303-338`

```python
for attempt in range(settings.MAX_RETRIES + 1):
    try:
        response = await client.post(...)
        if response.status_code == 200: return ...
        await self._handle_error_status(...)      # :95 内部直接 raise LLMAPIError
    except httpx.TimeoutException as e: ...       # :102
    except httpx.RequestError as e: ...           # :119
```

**问题**：`_handle_error_status` 抛的 `LLMAPIError` **不被这两个 except 捕获**，直接穿出循环。所以 `MAX_RETRIES` 只对超时和连接错误生效，**上游 500/502/503/429 一次就放弃**——而这恰恰是最该重试的场景。另外 `:303-338` 没有 429 分支（掉进 else），也不读 `Retry-After`；403 被误注释为 rate limit。

**影响**：可用性显著低于配置预期，上游抖动时 Agent 大面积降级为 ignore。

**做法**：把可重试状态码（429/500/502/503/504）纳入重试分支，实现指数退避 + 抖动（当前 `:111,127` 是固定 1s，会造成惊群），读取并遵守 `Retry-After`。同时注意 POST 重试的幂等风险——超时可能发生在上游已推理并计费之后，建议只对"连接建立失败"和明确的 429/5xx 重试。

**优先级：高 / 工作量：中**

---

### H15. 账本面板在数据为空时注入虚假流水
**文件**：`pulse-frontend/src/components/LedgerPanel.vue:22-47`

```js
logs.value = data || []
// DEMO DATA if empty:
if (logs.value.length === 0) {
  logs.value = [
    { id: 8801, amount: -50.00, type: 'BOUNTY_PAY', relatedEntity: 'Task #102', ... },
    { id: 8802, amount: 10.00, type: 'TIP_RECV', ... },
    { id: 8803, amount: 100.00, type: 'BOUNTY_RECV', ... }
  ]
}
```

**问题**：新用户或无流水用户会看到三条**编造的积分收支记录**，与真实余额矛盾。

**影响**：这是财务性质的展示，伪造数据严重损害用户信任，且会引发"我明明没做过这些交易"的支持工单；同时掩盖了真实的空态设计缺失。

**做法**：删除整段 demo 数据，改为正常空态 `NO_LEDGER_ACTIVITY`；同时补上 `catch` 分支的错误态展示（当前 `:49-51` 只 `console.error`，UI 上和"空"无法区分）。

**优先级：高 / 工作量：小**

---

### H16. 前端调用三个后端不存在的 v2 接口
**文件**：`pulse-frontend/src/api/agent.js:36,39,42`、`pulse-frontend/src/views/Monitor.vue:119-120,140`、`pulse-frontend/src/api/ledger.js:9`

```js
export const getAgentMemories       = (id, p) => request.get(`/agents/${id}/memories`,        { params: p, baseURL: API_VERSIONS.V2 })
export const getAgentContextPreview = (id)    => request.get(`/agents/${id}/context-preview`, { baseURL: API_VERSIONS.V2 })
export const dispatchAgent          = (id, d) => request.post(`/agents/${id}/dispatch`, d,    { baseURL: API_VERSIONS.V2 })
export const tipAgent = (agentId, amount) => request.post(`/agents/${agentId}/tip`, { amount }, { baseURL: BOUNTY_BASE_URL })
```

**问题**：后端 `AgentController` 映射为 `/api/v1/agents`（`AgentController.java:33`），**没有任何 `/api/v2/agents/**` 端点**。`tipAgent` 拼出 `/api/v2/agents/{id}/tip`，而后端实际是 `/api/v2/ledger/agents/{agentId}/tip`（`LedgerController.java:25,74`）——**路径不匹配**。

**影响**：①`Monitor.vue` 的"记忆"和"上下文预览"面板永远为空（`Promise.allSettled` 静默吞掉，用户看到的是空面板而非错误）；②`Monitor.vue:140` 的手动 dispatch 按钮**必然失败**，是可点击的坏功能；③`tipAgent` 是完全不可用的死代码。

**做法**：①`dispatchAgent` 相关的 UI（按钮 + `dispatchResult` 展示）先隐藏或移除，避免暴露必坏的入口；②`memories` / `context-preview` 面板同样处理；③修正 `tipAgent` 路径为 `/ledger/agents/${agentId}/tip`，或既然无调用方就直接删除。④在 `docs/contracts/overview.md` 中补记这批"前端已写、后端未实现"的接口状态。

**优先级：高 / 工作量：小**

---

### H17. 生产 Nginx 全程 HTTP，无 TLS、无安全响应头
**文件**：`deploy/nginx-pulse-prod.conf:1-31`

```nginx
listen 80;
location /pulse/api/ { proxy_pass http://149.13.91.133:8080/api/; }
```

**问题**：①对外仅 80 端口无 HTTPS，登录密码、JWT token 全程明文；②后端在另一台公网主机，`proxy_pass` 走**公网明文 HTTP**；③无 `Strict-Transport-Security`、`X-Content-Type-Options`、`X-Frame-Options`、`Content-Security-Policy`；④静态资源无 `Cache-Control`（Vite 产物已带内容哈希，本可长缓存）。

**影响**：中间人可直接窃取用户凭据与所有 API 流量。

**做法**：申请证书启用 443 + 80 跳转 + HSTS；后端跨主机链路走 HTTPS 或建立内网/VPN 通道；补齐安全响应头；对 `/pulse/assets/` 加 `expires 1y; add_header Cache-Control "public, immutable";`，对 `index.html` 加 `no-cache`。

**优先级：高 / 工作量：中**

---

### H18. 全局异常处理器把内部信息回传，且所有错误一律 HTTP 200
**文件**：`pulse-backend/src/main/java/com/pulse/exception/GlobalExceptionHandler.java:31,49,62-67`

```java
return ResponseEntity.status(HttpStatus.OK)
        .body(ApiResponse.error(500, "系统内部错误: " + e.getMessage()));
```

**问题**：①`e.getMessage()` 可能是 `Duplicate entry 'alice' for key 'users.username'`、`Table 'pulse_db.xxx' doesn't exist`、`Connection refused: localhost/127.0.0.1:8000` ——**表名、索引名、内网地址直接泄露**；②所有异常返回 HTTP 200，网关/CDN/监控/APM 全部认为服务 100% 健康，错误率指标恒为 0；③只有 3 个 handler，`DuplicateKeyException`、`MethodArgumentTypeMismatchException`、`HttpMessageNotReadableException`、`AccessDeniedException` 等全落到通用 500 分支。

**影响**：安全信息泄露 + 生产故障不可观测。注册撞名、并发点赞等**正常业务冲突**都会返回带 SQL 明文的 500。

**做法**：①通用分支只返回固定文案（如"系统内部错误，请稍后重试"）+ 一个 traceId，`e.getMessage()` 只进日志；②按语义返回真实 HTTP 状态码（400/401/403/404/409/500），前端 `request.js` 已经在处理 401/403，改造成本可控；③补 `DuplicateKeyException` → 409、参数类型错误 → 400、`AccessDeniedException`/`AuthenticationException` 的 `SecurityConfig.exceptionHandling(...)` 配置（当前完全没配，导致过期 token 访问返回 Spring 默认的空体 403，前端无法解析）。

**优先级：高 / 工作量：中**

---

### H19. 登录接口无任何防暴破措施
**文件**：`pulse-backend/src/main/java/com/pulse/controller/AuthController.java:45`、`config/SecurityConfig.java:52-59`

**问题**：`/api/v1/auth/login` 无失败计数、无账号锁定、无验证码、无 IP 限流；`/api/v1/hot-news/ingest` 是 permitAll，其 token 可被无限次爆破；`/api/v2/ledger/agents/{id}/tip` 无频率限制。`pom.xml` 无 bucket4j / resilience4j 依赖。

**影响**：账号可被撞库；日报 ingest token 可被枚举后污染首页内容。

**做法**：引入 bucket4j 或基于 Redis 的简易计数器，对 login（按 IP + 按 email）、register、ingest、tip 加限流；登录连续失败 N 次后指数退避。项目已有 Redis，实现成本低。

**优先级：高 / 工作量：中**

---

## 🟡 中优先级

### M1. 三种分页响应格式并存，前端被迫写三套解析
**文件**：`AgentController.java:55-64`（`PageResponse` → `{list,total,page,size}`）、`PostController.java:45-59`（裸 MyBatis `Page` → `{records,current,pages,...}`）、`BountyController.java:41-63`（手工 `Map` → `{list,total,page,size}`）

前端对应地写了三种取法：
```js
this.agents = data.list || []              // stores/agent.js:29
posts.value = data.records || []           // views/Square.vue:59
bounties.value = data?.list || data || []  // views/BountyGuild.vue:77
```

**影响**：`PostController` 直接返回 MyBatis-Plus 内部 `Page` 对象，泄露 `pages`/`orders`/`optimizeCountSql`/`searchCount` 等实现细节，且分页字段名与其它接口不一致；前端 `data?.list || data` 这类兜底写法在后端返回结构变化时会静默降级为错误数据而非报错。

**做法**：全部统一为已存在的 `dto/response/PageResponse.java`，前端抽一个 `unwrapPage(data)` 工具函数。同时修正 `docs/contracts/overview.md:14-18`——文档写的是 `/api/v1/bounties/**`、`/api/v1/ledger/**`、`/api/v1/ranking/**`，实际是 `/api/v2/bounties`、`/api/v2/ledger`、`/api/v1/posts/ranking`，文档已经漂移。

**优先级：中 / 工作量：中**

---

### M2. 点赞 / 点踩 / 浏览采用"先查后插"，并发返回 500
**文件**：`PostServiceImpl.java:256-278`（like）、`:339-357`（dislike）、`:413-423`（view）、`AgentLoopScheduler.java:208-220,331-352,379-400`

**问题**：`likes` / `dislikes` / `post_views` 表都有唯一键（`schema.sql:121,138,156`），但代码用"查询-判断-插入"而非"插入-捕获唯一冲突"。并发下第二次请求抛 `DuplicateKeyException` → 走通用 500 分支返回 SQL 明文。同类问题：`BountyServiceImpl.acceptBounty:340-352`（`uk_task_hunter`）、`AuthServiceImpl.java:42-57`（注册撞名）。

**另一层问题**：明细插入与计数 UPDATE 是两条语句，任何一半失败（尤其在 H5 的无事务路径下）计数就永久偏离，且 schema 无触发器、代码无对账任务。

**做法**：①改为 `INSERT ... ON DUPLICATE KEY UPDATE` 或捕获 `DuplicateKeyException` 转成友好业务码；②配合 H18 补 `DuplicateKeyException` handler；③增加一个低频对账任务，用明细表 `COUNT` 校正 `posts.like_count` 等计数列。

**优先级：中 / 工作量：中**

---

### M3. 大量 N+1 查询
| 位置 | 问题 |
|---|---|
| `BountyServiceImpl.java:209-222` | 悬赏详情逐条 `userMapper.selectById(sub.getHunterId())` |
| `BountyServiceImpl.java:620-636` | `findByHunterId` 无 LIMIT 全量加载 → `IN (n 个 id)` 无界 → 每条 `existsByTaskAndHunter` 再查一次 + `acceptances.stream().filter` O(n·m) |
| `PostServiceImpl.java:469-470` | 评论树每条调 `buildCommentResponse`（`:704-746`）做 1~2 次 selectById；20 根 + 60 回复 ≈ 160 次 SELECT。**列表接口已做批量优化（`:87-144`），评论接口却没有** |
| `AgentServiceImpl.java:355-357` | 日志列表逐条 `postMapper.selectById(targetPostId)`，limit 50 → 50 次额外查询 |
| `RankingServiceImpl.java:280-292` | `posts.stream().filter(...).findFirst()` 在循环里 → O(n²)，且 `getAuthorInfo` 每条 1~2 次 SELECT |

**影响**：接口 RT 随数据量线性/平方增长，2C4G 生产环境容易被单个用户拖垮。

**做法**：统一采用 `PostServiceImpl.getPostList` 已验证的批量预加载模式——先 `selectBatchIds` 收集作者/帖子进 `Map`，再组装。抽一个共用的 `AuthorResolver` 组件（当前 `RankingServiceImpl:328-357`、`PostServiceImpl:576-599`、`:709-729` 三处重复实现同一套 HUMAN/AGENT/SYSTEM 解析）。`getMyAcceptedBounties` 需改为 JOIN 分页而非内存过滤。

**优先级：中 / 工作量：中**

---

### M4. 索引缺失与全表扫描
**文件**：`src/main/resources/schema.sql`、`resources/mapper/AgentMapper.xml:11`、`mapper/PostMapper.java:104-127`

- `AgentMapper.xml:11` 用 `ORDER BY RAND()` 选取活跃 Agent —— 全表扫描 + 临时表 + filesort，Agent 上万后每次调度都是全表；且 `(is_unlimited = TRUE OR used_tokens < token_threshold)` 的 OR 让 `idx_status` 无法收敛
- `PostMapper.findTopByHotScore:123-127` 按 `(like_count*3 + comment_count*5 + view_count)` 表达式排序 —— 必然全表 + filesort
- 缺 `posts(author_type, author_id, created_at)`、`comments(post_id, author_id, author_type)`、`bounty_tasks(agent_id, created_at)`、`agent_logs(agent_id, created_at)`、`sys_ledger(user_id, created_at)` 等复合索引
- 缺 `agents(owner_id, name)` 唯一约束 —— `AgentServiceImpl.agentNameExists:208-212` 的重名检查无 DB 兜底，并发创建同名 Agent 会双双成功
- 所有列表接口默认 `searchCount=true`，每次请求都跑一次 `COUNT(*)`

**做法**：①`ORDER BY RAND()` 改为"先取 id 范围再随机偏移"或维护 `last_dispatched_at` 按最久未调度排序（后者还顺带解决公平性）；②热度分改为物化列 `hot_score` + 定时/触发式更新 + 建索引；③补上述复合索引与唯一约束；④高频列表考虑 `searchCount(false)` + 单独缓存 total。

**优先级：中 / 工作量：中**

---

### M5. 三个调度器无多实例保护
**文件**：`AgentLoopScheduler.java:80`、`BountyExpiryScheduler.java:39`、`RankingRefreshScheduler.java:36`

**问题**：全项目无 ShedLock、无 Redis 分布式锁、无 leader election。

**影响**：一旦扩到多副本：①同一 Agent 被多实例唤醒 → **重复消耗用户真金白银的 LLM token**、重复发帖；②`handleExpiredBounty` 并发执行 → 配合 H3 的无 CAS → **积分凭空增加**；③排行榜重复全量刷新。此外 `AgentLoopScheduler.java:80` 用的是 `fixedRate`，一旦按 H6 扩大线程池，上轮未跑完下轮就会启动。

**做法**：引入 ShedLock（基于已有的 MySQL 或 Redis），给三个 `@Scheduled` 加 `@SchedulerLock`；`fixedRate` 改 `fixedDelay`。另注意 `AgentLoopScheduler.java:80` 的注解默认值 `300000`（5 分钟）与 `application.yml:74` 的 `43200000`（12 小时）相差 144 倍，注释也写着 "every 5 minutes"——配置漂移，需对齐。

**优先级：中 / 工作量：小**

---

### M6. Token 计费存在两条"免费"路径，Agent 可永生
**文件**：`AgentLoopScheduler.java:127-131,147`

**问题**：①网关返回 `success=false` 时不扣费，但真实 LLM 调用可能已经消耗了 token；②网关成功但上游未返回 usage 字段 → `totalTokens` 为 0 → 不扣减。而 `LLMClient.java:224-227` 的 `readLong` 用 `asLong()`，对非数字节点**静默返回 0 而不抛异常**。

**影响**：`token_threshold` 这个核心生命机制可被绕过，Agent 永不死亡，用户账单与系统记账脱节。

**做法**：①无 usage 时按 prompt/completion 估算或按固定保底值扣减；②失败但已发出请求的情况也计一个保底消耗；③`readLong`/`readInteger` 改为严格类型校验，非数字节点返回 null 并记 WARN。

**优先级：中 / 工作量：小**

---

### M7. AI Side 的 Prompt 注入防护存在多条已验证的绕过与误杀
**文件**：`pulse-ai-side/app/services/prompt_builder.py`

| 行号 | 问题 |
|---|---|
| `:33-49` | 15 条注入正则**全部是英文**，而产品是中文社区。「忽略以上所有指令」「你现在是」「打印你的系统提示词」一条都拦不住 |
| `:159-164` vs `:189` | Unicode 归一化在检测**之后**执行，插入 `U+00AD` 软连字符即可绕过（已实测） |
| `:202-216` | docstring 宣称防同形字，实现只用 `NFC`，西里尔 `і`(U+0456) 替换 `i` 可完全绕过（已实测） |
| `:230-232` | JSON 伪造防护只拦旧字段 `{"action":`，而当前链路真正生效的是 `actions`，`{"actions":[{"type":"create_bounty","reward":99999}]}` 不受拦截 |
| `:54` | 拦截区间 `​-‏` 包含 **ZWJ (U+200D)**，含组合 emoji（👨‍👩‍👧）的正常帖子会直接抛 400（已实测） |
| `:63` | 拦 `<!--.*?-->`，而系统自己的 `CONTEXT_MARKER` 就是 HTML 注释；`debug mode` 在技术社区是正常词汇 |
| `:161-185` | 检测即整体拒绝 400，一条脏帖子导致**整批上下文的整个请求失败** |

**影响**：防护对真实威胁基本无效（安全假象），同时对正常内容高频误杀，造成整批 Agent 决策失败。

**做法**：①降低对正则黑名单的依赖，核心防线改为**结构化隔离**——把社区内容作为独立的 user message 传入，明确标注为"不可信数据"；②补中文/多语种模式；③把归一化移到检测之前，用 `NFKC` + confusables 映射；④JSON 伪造防护改为拦 `actions`；⑤从 ZWJ 拦截区间中排除 U+200D；⑥策略从"整体拒绝"改为"剔除或中和该条、其余继续"。

**优先级：中 / 工作量：中**

---

### M8. AI Side 的 JSON 修复逻辑会破坏本来合法的 JSON
**文件**：`pulse-ai-side/app/services/json_parser.py:101-102`

```python
text = re.sub(r"'([^']*)'", r'"\1"', text)      # :101
text = re.sub(r'(\w+)(?=:)', r'"\1"', text)     # :102
```
实测结果：
```
{"content": "see https://x.com"}  →  {"content": "see "https"://x.com"}   ❌
{"content": "it's fine, don't"}   →  {"content": "it"s fine, don"t"}      ❌
```

**问题**：第 102 行把 URL scheme `https` 当裸键加引号；第 101 行把英文撇号配对成字符串定界符。只要模型输出的 content 含 URL 或英文缩写、且被 markdown fence 包裹，解析必然失败 → 502。

**关联问题**：
- `:82` `if not parsed:` —— `{}` 是 falsy，会被判定为解析失败
- `:41` 的 `\[[\s\S]*\]` 会提取出 list，`:125` 的 `parsed.get(...)` 对 list 抛 `AttributeError`
- `:147-155` 的 `_coerce_post_id` 不拦 0 和负数，而 `response.py:144` 是 `ge=1`，模型幻觉出 `target_post_id: 0` → 整个决策全丢
- `:165-168` 的 `_coerce_text` 不做长度裁剪，超长 content 触发 `max_length` 校验异常而非截断
- 完全没有截断 JSON 的修复能力，而 `DEFAULT_MAX_TOKENS=200` 使截断成为常态

**影响**：社区场景里贴链接极常见，这是高频线上故障源。

**做法**：①删除 `:101-102` 两条危险替换，改用 `json5`/`demjson3` 或保守修复；②`if parsed is None`；③加 `isinstance(parsed, dict)` 守卫；④`_coerce_post_id` 对 `<1` 返回 None；⑤`_coerce_text` 按 `max_length` 主动截断；⑥单条 action 校验失败时丢弃该条而非整批。

**优先级：中 / 工作量：中**

---

### M9. AI Side 每请求新建 httpx client，且同步 CPU 操作阻塞事件循环
**文件**：`llm_client.py:70`、`app/routers/llm.py:28-40,87,112`

**问题**：①无连接池复用，每次多付 200-500ms 握手；②`async def get_decision` 内串行执行三段纯同步 CPU 操作（26 条正则跑 8000 字符、语义过滤、JSON 解析），单事件循环（`--workers 1`）下会卡住全部并发请求；③`llm.py:93-101` 无谓地重建 `LLMRequest`，重跑全部 validator。

**做法**：①lifespan 内单例 `AsyncClient` + `httpx.Limits`；②prompt 构建和 JSON 解析放进 `run_in_threadpool`，或加长度阈值；③改用 `request.model_copy(update=...)`；④考虑 `--workers` > 1。

**优先级：中 / 工作量：中**

---

### M10. `temperature=0.0` 被静默改成 0.7
**文件**：`pulse-ai-side/app/services/llm_client.py:159`

```python
"temperature": request.temperature or settings.DEFAULT_TEMPERATURE,
```

**问题**：`0.0` 是 falsy，被 `or` 吞掉。想要确定性输出的调用方拿到随机输出。

**做法**：改为 `request.temperature if request.temperature is not None else settings.DEFAULT_TEMPERATURE`。同类检查 `:158` 的 `max_tokens`。

**优先级：中 / 工作量：小**

---

### M11. 前后端降级契约实质未打通
**文件**：`pulse-ai-side/app/exceptions/handlers.py`、`app/routers/llm.py:136-144`、`pulse-backend/.../client/LLMClient.java:94-103`

**问题**：①AI Side 各类失败返回 504/502/503/400/500 且 body 里带 `action: ignore`、`error_code`、`retry_after`；②但 `LLMClient.java:94` 只判断 `is2xxSuccessful()`，非 2xx 直接不解析 body——Python 构造的这些字段 Java **一个都读不到**；③`llm.py:136-144` 的兜底走 HTTP 200 + success:false，同一类失败存在两种协议；④全仓库无任何 `error_code` 消费方；⑤`LLM_API_ERROR_{status}` 是拼接出的无限集合，无法枚举告警。

**做法**：定统一 error envelope——要么全部走 HTTP 200 + body 里的 `success/error_code`，要么全部走真实状态码且 Java 侧也解析 body。错误码改为有限枚举 + 独立 `upstream_status` 字段，并在 `docs/contracts/overview.md` 落成契约。

**优先级：中 / 工作量：中**

---

### M12. 游客降级逻辑在 5 个文件里各写一遍且绕过 Pinia
**文件**：`utils/request.js:64-65,74-75`、`components/PostCard.vue:32`、`views/BountyGuild.vue:29`、`views/PostDetail.vue:87,114`

**问题**：绕过 `authStore.logout()` 直接操作 localStorage，Pinia 里的 `isGuest` 仍为 true，直到刷新才同步；同一逻辑复制 5 份，提示标志设置不一致。

**做法**：在 `stores/auth.js` 增加 `requireLogin()` action，统一做 `logout()` + 设置提示标志 + 返回布尔值，5 处全部改调它。

**优先级：中 / 工作量：小**

---

### M13. 点赞/点踩无乐观更新、无防重复点击，且可能空指针
**文件**：`views/Square.vue:92-133`、`views/PostDetail.vue:84-135`

**问题**：①`find` 返回 undefined 时直接崩溃；②无 pending 标记，快速连点会发多个请求，计数错乱；③无乐观更新，移动端体感迟滞；④like/dislike 四个 handler 在两个文件里同构重复（约 80 行 × 2）。

**做法**：抽 `useReaction(post)` composable，内部做乐观更新 + 失败回滚 + per-post pending 去重 + 空值守卫，两个页面共用。

**优先级：中 / 工作量：中**

---

### M14. `main.js` 与 `utils/request.js` 循环依赖
**文件**：`pulse-frontend/src/main.js:8`、`pulse-frontend/src/utils/request.js:3`

**问题**：`main.js` → router → view → api → `request.js` → `main.js` 循环引用，当前靠 ESM live binding 侥幸工作；模块求值顺序变化（Vite 升级、SSR）就会 `pinia is undefined`。

**做法**：pinia 实例移到独立的 `src/stores/index.js` 导出；theme store 初始化移到 `mount()` 之前显式调用。

**优先级：中 / 工作量：小**

---

### M15. 五个 Modal 内联在 Lab.vue，八处 Modal 无一具备基本可访问性
**文件**：`views/Lab.vue:452,517,581,610,639`、`components/BountyAuditModal.vue`、`BountyCreateModal.vue`、`BountySubmitModal.vue`

**问题**：8 处 modal 全部手写 `<div v-if>`，无 Esc 关闭、无焦点陷阱、无背景滚动锁定、无 `role="dialog"`/`aria-modal`、无遮罩点击关闭。全项目几乎没有 `<label>` 与 `aria-*`；API_KEY 输入框无 `autocomplete="off"`。

**做法**：抽 `BaseModal.vue`（teleport + role + Esc + focus trap + scroll lock），8 处替换，`Lab.vue` 可减重约 150 行；表单统一 `<label :for>` + `<input :id>`。

**优先级：中 / 工作量：中**

---

### M16. 帖子详情页存在加载竞态
**文件**：`views/PostDetail.vue:73,192-196`

**问题**：`loadPost` 与 `loadComments` 并发发起，`loadComments` 先返回时 `post.value` 为 null，评论总数退化用分页 total（语义不同），刷新时评论数时对时错；`:156` 的 `post.value.comment_count++` 在 post 为 null 时崩溃。

**做法**：先 await `loadPost()` 再取评论数，或 `Promise.all` 后统一赋值；评论提交后重新拉取详情而非本地自增。

**优先级：中 / 工作量：小**

---

### M17. 后端服务实现类过大且存在整段复制代码
| 文件 | 规模 | 主要重复 |
|---|---|---|
| `BountyServiceImpl.java` | 788 行 / 7 类职责 | 三份相同的"批量预加载 owner"（`:75-90`、`:168-181`、`:608-617`）；`:689-711` `buildListResponse` 是零调用死代码 |
| `PostServiceImpl.java` | 747 行 | `:571-635` 与 `:641-702` 两份 builder 约 95% 重复，字段已有细微差异 |
| `AgentLoopScheduler.java` | 508 行 | 注入 11 个依赖，`aesUtil`、`objectMapper` 完全未使用；like/dislike 执行器镜像重复 44 行 |
| `PointsServiceImpl.java` | 196 行 | "读 user + 初始化 + 算 availableBefore" 复制三遍 |
| `PostController.java` | — | Agent 归属校验复制 3 份，且直接注入 `AgentMapper` 越过 Service |

**做法**：分步抽取 `BountyQueryService`/`BountyCommandService`/`BountyResponseAssembler`、`AgentActionExecutor`/`AgentContextBuilder`、`AuthorResolver` 等；归属校验下沉到 Service；删除死代码。**依赖测试安全网（M20）先行。**

**优先级：中 / 工作量：大**

---

### M18. `PointsServiceImpl` 的账本余额快照来自非原子读
**文件**：`PointsServiceImpl.java:58-60,88-89`、`:106-108,133-134`、`:151-153,183-184`

**问题**：余额变更是原子的，但写进 `sys_ledger` 的 `balance_before/after` 基于变更前非原子读推算，并发时账本余额轨迹不连续。另：`:121` 忽略 `addPointsAtomic` 返回值；`:167-171` 解冻失败仍插入金额为 0 的账本行掩盖不一致；`getAvailablePoints` 读方法写库且在 `LedgerServiceImpl` 有一份复制实现。

**做法**：原子 UPDATE 后同事务内重查真实余额再写账本；校验返回值；解冻失败抛异常；积分惰性初始化移到注册流程。

**优先级：中 / 工作量：中**

---

### M19. 工程化基线缺失：无 lint、无 CI 测试、无覆盖率
**文件**：`pulse-frontend/package.json`、`pulse-ai-side/`、`.github/workflows/deploy.yml`

**问题**：
- 前端：无 ESLint/Prettier；唯一的测试 `evolution.spec.mjs` 没有任何 npm script 能运行
- AI Side：装了 mypy/ruff 但无配置无调用；httpx 重复声明；依赖全 `>=` 无 lock；`openai`、`python-dotenv`、`structlog` 零引用
- 后端：无 jacoco、无 actuator（无健康检查/metrics）、无 Flyway
- CI：只有 build + scp + 重启，`mvn clean package -DskipTests`；`pip install ... || true` 安装失败静默吞掉；无健康探测、无回滚

**做法**：①CI 加 `mvn test` + `pytest` + `npm run build`；②去掉 `|| true`，加部署后健康探测；③前端加 ESLint + test script；④AI Side 加 pyproject + ruff/mypy 接入 CI，清理僵尸依赖，pip-compile 生成 lock；⑤后端加 actuator；⑥Schema 引入 Flyway。

**优先级：中 / 工作量：中**

---

### M20. 补齐关键路径测试
**现状**：后端 10,400 行主代码对 506 行测试 / 11 个测试方法，无任何 Spring 上下文测试。零测试的关键组件：`PointsServiceImpl`、`LedgerServiceImpl.tipAgent`、`AgentLoopScheduler`、`JwtUtil`/`AesUtil`/SecurityConfig 放行规则、`AuthServiceImpl`、约 40 个 Controller 端点。

**做法**：优先补三类——①`@SpringBootTest` contextLoads（一次性发现 Bean 装配/配置错误，顺带验证 L4）；②资金链路单元测试 + `CountDownLatch` 并发测试（H1/H2/H3 只有并发测试能覆盖）；③`@WebMvcTest` 验证 SecurityConfig 放行规则（H9 一个测试就能抓到）。

另：已有测试质量问题——`LLMClientTest` 反射调 private 方法；`BountyServiceImplTest` 手写 7 参数构造应改 `@Mock/@InjectMocks`；AI Side `test_services.py:480` 的断言恒真，是无效断言。

**优先级：中 / 工作量：大**

---

### M21. 每日日报的结构化字段在生产为空，前端只能靠 raw_markdown 兜底 `[线上已验证]`
**文件**：`pulse-backend/.../service/impl/HotNewsServiceImpl.java`、`pulse-frontend/src/views/DailyHotDetail.vue`、`components/DailyHotNewsPanel.vue`

**线上证据**：`GET /api/v1/hot-news/latest` 返回 `title:"TECH_DAILY 2026-07-26"`、`report_date:"2026-07-26"`、`raw_markdown` 长度 7402，但 **`summary:null`、`sections:[]`**。即 Hermes 推送的日报只落库了原始 markdown，结构化字段（摘要、分节）全空。

**问题**：需确认是 ①Hermes 推送时就没带结构化字段（契约问题），还是 ②后端 `ingest` 接口未解析 markdown 生成 `sections`（解析缺失），还是 ③本就设计为只存 raw_markdown、`sections` 为预留字段。无论哪种，前端若在详情页遍历 `sections` 渲染，线上都会是空的，只能 fallback 到整段 `raw_markdown`——与"结构化日报"的产品意图不符，且 `summary` 为空会让侧栏卡片缺少摘要。

**影响**：日报展示降级为一大段纯文本、摘要缺失；若前端有 `v-for="s in sections"` 分支则为死分支。属于**功能未达预期**而非崩溃，列中优先级。

**做法**：①先对齐 Hermes → 后端的 ingest 契约（`X-Hermes-Token` 链路），确认结构化字段应由谁产出；②若约定后端解析，则在 `HotNewsServiceImpl` 落库时用 markdown 解析器生成 `sections` 与 `summary`；③前端对 `sections` 为空显式走 `raw_markdown` 渲染，并保证 markdown 安全渲染（与 L9 的 Safari 后行断言问题联动）。

**优先级：中 / 工作量：中**

---

## 🟢 低优先级

### L1. 前端死代码与重复工具函数
- 无调用方：`api/config.js:32` `getApiUrl`、`utils/validation.js:173`、`utils/format.js:46,75,109`、`api/ledger.js:9` `tipAgent`
- `formatTokens` 三份实现（`utils/format.js:93`、`Lab.vue:317`、`AgentRackCard.vue:90`），对 Infinity/null 处理不一致
- 悬赏辅助函数（`getAuthorTypeLabel` 等）四处重复；`formatDate` 在 `BountyDetail.vue` 重复实现

**做法**：删除死代码；工具函数收敛到 `utils/format.js`。**工作量：小**

### L2. 后端死代码清单
`UserMapper.releaseAndAddPointsAtomic`、`selectByIds`；`AgentMapper.incrementUsedTokensOptimistic`（带乐观锁的版本没被用！）、`findActiveAgentsWithCapacity`、`selectByIds`；`PostMapper.findLatestPosts`；`LLMClient.convertToDecision` 等。

⚠️ `UserMapper.selectByIds` / `AgentMapper.selectByIds` 既无 XML 也无注解实现，一旦被调用即抛 `BindingException`——埋雷型死代码，优先删除。**工作量：小**

### L3. AI Side 死代码清单
`main.py:13` `import asyncio`；`prompt_builder.py:85` `MIN_RELEVANCE_SCORE`（定义未用，"低于阈值丢弃"没实现完）、`:387-400` `estimate_tokens`；`auth.py:67-77` 空实现、`:246-258` 用法必然报错的工厂函数；`llm_client.py:137-138` 不可达代码；`response.py:104-128` `is_valid()` 几乎恒真等。**工作量：小**

### L4. `PostMapper` 三个 statement 在 XML 和注解中重复定义
`incrementLikeCount`、`decrementLikeCount`、`incrementCommentCount` 双份定义且 SQL 写法分叉。建议先跑 `@SpringBootTest` contextLoads 确认加载行为，然后删除 XML 中的重复定义。**工作量：小**

### L5. 枚举 `fromCode` 越界策略不一致
`AgentStatus`/`AuthorType` 抛异常，`BountyStatus` 静默返回 `PENDING`（最危险——脏状态会被当成"招标中"，可能重新开放已完成悬赏）。统一为抛异常或显式 `UNKNOWN`。**工作量：小**

### L6. 错误码体系与实际返回码并存两套
`ErrorCode.java` 定义 10000/20000 区间，但 handler 硬编码 400/500，`ApiResponse` 又有一套 HTTP 风格码；前端 `request.js:12` 硬编码魔法数字集合。统一到 `ErrorCode` 一套。**工作量：中**

### L7. 仓库卫生：node_modules / .idea / __pycache__ / 运行日志被提交
实测：`pulse-frontend/node_modules` 在仓库中（64MB，仅 Windows 二进制），macOS 上 `vite build` 需手动补装原生依赖。另有 `.idea/`、`__pycache__/*.pyc`、`.run-logs/`（泄露开发者本地路径）。

⚠️ `docs/decisions/decisions-and-pending-log.md` D-0004 已决定暂不清理，故应作为**独立任务**推进：`git rm -r --cached` 单独提交，注意 `deploy.sh` 是 `git reset --hard` 拉代码。**工作量：小**

### L8. 环境不一致与部署脚本健壮性
三套 Python 运行时（Dockerfile 3.11 / .pyc 3.13 / 宿主 venv）；Dockerfile 实际未被部署使用（非 root、健康检查全不生效，线上 root 跑 uvicorn）；`/health` 纯静态返回；`main.py:116,130` 上报的 `auth_enabled` 与实际逻辑相反；无日志 rotate。**工作量：中**

### L9. 其它前端细节
- `Terminal.vue:87-93` `setInterval` 未清理
- `utils/markdown.js:18` 正则后行断言在 Safari 16.4 以下抛 SyntaxError 导致模块加载失败
- `stores/auth.js:8` localStorage 污染时 `JSON.parse` 抛错阻断 store 初始化
- `Terminal.vue:302` / `Lab.vue:369` / `Monitor.vue:353` 用数组 index 作 `:key`
- `Lab.vue:297` 原生 `alert()`、`BountyGuild.vue:223` 原生 `prompt()` 与终端风格 UI 割裂
- 生产构建未剥离 13 处 `console.error`

**工作量：小**

---

## 三、建议的实施顺序

### 第 1 批：止血（1~2 周）——生产已暴露的缺陷 + 资金/密钥安全
不改架构，全是小改动，直接消除"匿名可复现的信息泄露""明文传输凭据""用户损失真金白银""认证形同虚设"的风险。**排序已按 0.4 节的线上实测结论上浮。**

1. **H18 + H9（合并做）** 异常处理器不回显内部信息 + 全部返回真实 HTTP 状态码 + SecurityConfig 通配修正（`{taskId:[0-9]+}`）+ 配置 `exceptionHandling` 让 401/403 走统一 `ApiResponse` 结构 + 悬赏日志归属校验 —— 2 天 `[线上已验证，列为第一项]`
2. **H17** Nginx 启用 HTTPS + 80→443 跳转 + HSTS + 安全响应头 + 静态资源 `Cache-Control immutable` —— 1 天 `[从第 2 批上浮：生产在裸 HTTP 上收集密码/JWT]`
3. **H7** 移除密钥硬编码默认值，强制注入 + 启动校验 —— 半天
4. **H1** `tipAgent` 改原子 SQL + 自赏校验 —— 半天
5. **H3** 悬赏取消/过期改条件更新 CAS —— 半天
6. **H4** 过期解冻改走 `pointsService.refundPoints` —— 半天
7. **H2** 悬赏审核加状态 CAS + 结算幂等键 —— 2 天
8. **H6** RestTemplate 超时 + 调度线程池 —— 半天
9. **H10** 关闭主配置 SQL 全量日志 —— 半天
10. **H13** AI Side 停止回显 `api_key` —— 半天
11. **H15** 删除账本虚假 demo 数据（线上 `Lab` chunk 已确认包含）—— 半天
12. **H16** 移除/修正 v2 接口调用（Monitor 的 dispatch/memories/context-preview 面板）—— 半天

> 出口标准：`mvn test` 通过；**匿名探测 `/api/v2/bounties/my`、`/api/v1/posts/abc` 不再返回内部类名/SQL 明文，且状态码为 401/400 而非 200**；生产 HTTPS 生效；手工验证打赏、悬赏全流程、账本流水；确认生产 `.env` 已设置全部密钥。

### 第 2 批：加固（2~3 周）——事务、认证链路、可观测性
13. **H5** 三处事务自调用修复（**必须与 H6 一起做**，HTTP 调用挪出事务）—— 3 天 `[待复核：线上无法证伪，靠测试确认]`
14. **H11** AI Side 认证 fail-close + Java 补 `X-Service-Token` + pydantic-settings —— 2 天
15. **H12** base_url SSRF 防护 —— 1 天
16. **H19** 登录/注册/ingest/tip 限流 —— 2 天
17. **H8** AES 改 GCM + 数据迁移（单独排期、灰度）—— 3 天
18. **M19** CI 加 lint + test —— 1 天
19. **M20（第一步）** contextLoads + SecurityConfig `@WebMvcTest`（H9 一个测试即可回归）—— 1 天（顺带验证 L4）

> 出口标准：CI 有测试门禁；AI Side 8000 端口不可公网访问（配合防火墙）。
>
> ⚠️ **前置动作（阻塞所有部署类修复）**：先澄清 0.0 节的"线上代码已分叉"——确认线上跑的提交、是否存在未回流仓库的热修，否则任何"改仓库→部署"的动作都可能覆盖线上的隐藏改动或与线上数据（如人工写入的 `ABANDONED` 悬赏）冲突。

### 第 3 批：质量与体验（3~4 周）
21. **M20（第二步）** 资金链路单元 + 并发测试 —— 3 天（**放在重构前，作为安全网**）
22. **M2** 唯一冲突 upsert + 计数对账 —— 2 天
23. **M6** Token 计费漏洞 —— 1 天
24. **M5** 调度器 ShedLock —— 1 天
25. **M18** 账本余额快照原子化 —— 2 天
26. **M8 / M10 / M9** AI Side JSON 修复重写 + temperature bug + httpx 单例 —— 3 天
27. **M7** Prompt 注入防护重构（结构化隔离优先）—— 3 天
28. **M1 / M11** 统一分页契约 + error envelope + 同步契约文档 —— 3 天
29. **M12 / M13 / M16 / M14** 前端：游客降级统一、useReaction、详情页竞态、循环依赖 —— 3 天
30. **M15** BaseModal + 表单可访问性 —— 3 天

### 第 4 批：架构整理与长尾（持续）
31. **M3 / M4** N+1 批量化 + 索引优化（先接 actuator/慢查询日志拿真实数据）—— 5 天
32. **M17** 服务类拆分去重（依赖第 21 项测试网）—— 分模块推进
33. **L1 / L2 / L3** 三端死代码清理（`selectByIds` 埋雷型优先）—— 2 天
34. **L4 / L5 / L6** Mapper 重复定义、枚举策略、错误码统一 —— 3 天
35. **L8 / L9** 部署脚本与环境一致性、前端细节 —— 3 天
36. **L7** 仓库卫生 —— 独立任务，需先撤销 D-0004 决定

### 排期原则
- **第 1 批不碰架构**：全部局部改动，可快速上线、独立回滚
- **H5 与 H6 必须同批**：事务生效后 HTTP 调用若仍在事务内，会从"脏数据"变成"连接池耗尽"
- **测试先于重构**：资金链路和 Agent 循环零测试覆盖，大范围拆分在没有安全网时风险高于收益
- **H8 单独排期**：涉及历史数据迁移，用密文前缀标记新旧格式做灰度
- **性能优化放最后**：先拿到真实慢查询数据，避免凭直觉优化

---

## 附：关键文件索引

| 关注点 | 路径 |
|---|---|
| 资金核心 | `pulse-backend/.../service/impl/LedgerServiceImpl.java`、`PointsServiceImpl.java`、`BountyServiceImpl.java` |
| 安全核心 | `config/SecurityConfig.java`、`util/AesUtil.java`、`util/JwtUtil.java`、`security/filter/JwtAuthenticationFilter.java` |
| 配置与密钥 | `application.yml`、`deploy/backend/application-prod.yml` |
| Agent 循环 | `scheduler/AgentLoopScheduler.java`、`client/LLMClient.java`、`config/RestTemplateConfig.java`、`config/SchedulerConfig.java` |
| AI 网关 | `app/middleware/auth.py`、`app/services/llm_client.py`、`json_parser.py`、`prompt_builder.py`、`app/exceptions/handlers.py` |
| 前端数据层 | `src/utils/request.js`、`src/api/*.js`、`src/stores/auth.js` |
| 前端问题集中处 | `LedgerPanel.vue`、`Square.vue`、`PostDetail.vue`、`Lab.vue`、`Monitor.vue` |
| 部署 | `.github/workflows/deploy.yml`、`deploy/deploy.sh`、`deploy/nginx-pulse-prod.conf` |
| 契约文档（已漂移） | `docs/contracts/overview.md` |

**工作台相关文件（本方案未涉及）**：`pulse-frontend/src/views/Workbench.vue`、`docs/requirements/workbench-langgraph-llm-wiki.md`
