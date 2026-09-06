# Task State: frontend

## Current
- Task ID: frontend-2026-09-06-public-traits
- Goal: 记忆面板支持特质卡的公开/私有切换，公开主页展示 `public_traits` 区块并按新口径显示悬赏统计。
- Scope: `/pulse-frontend/**`、`/agentsPrompt/modules/frontend/tasks.md`
- Status: done
- Owner: Claude
- Last Updated: 2026-09-06

## Done Summary
- 已在 `src/utils/memory.js` 增加公开状态的纯逻辑：`isTrait` / `isPublicTrait` / `canTogglePublic` / `publicStateLabel` / `publicToggleLabel` / `buildPublicTogglePayload` / `describePublicToggleError` / `confidenceText`，以及 `PUBLIC_TRAIT_HINT` 与 `PUBLIC_TRAIT_LIMIT` 两个常量；`memory.test.mjs` 新增 4 条用例（合计 107 条）。
- 已在 `src/components/AgentMemoryPanel.vue` 的列表视图与时间线视图为特质卡显示「公开 / 私有」标记并提供切换按钮，切换用 `PATCH /agents/{id}/memories/{memory_id}` 只带 `is_public`，成功后就地更新该卡；事实卡不显示标记与按钮，DEPRECATED 特质卡不显示按钮。面板顶部新增一行说明「公开的特质会显示在该 Agent 的公开主页上」，时间线视图补上了原先缺失的按卡错误提示区。
- 已把 `patchMemory` 的错误文案改为可传入的参数，公开切换传 `describePublicToggleError`，其中 99900 显示「只有特质卡可以公开」、20008 显示「已废弃的记忆不可公开」，其余码沿用 `describeMemoryError`。
- 已在 `src/views/AgentProfile.vue` 新增「公开特质」区块，逐条显示内容、置信度与日期，为空显示「该 Agent 尚未公开任何特质」；所有者视角在该区块下方显示到 `/monitor/{id}` 的「到监控台管理公开特质」链接，复用既有的 `resolveViewerRole` 判定。
- 已在该页统计区新增第五格「发布并完成的悬赏」，读 `stats.completed_bounty_count`，栅格由 `sm:grid-cols-4` 改为 `sm:grid-cols-5`。
- 已在 `src/api/agent.js` 的两处注释补上 `is_public` 与 `public_traits` / `completed_bounty_count` 的说明，未新增方法（`updateAgentMemory` 已接受任意 body）。

## Previous Done Summary
### Agent 创建向导
- 已新增 `src/api/agentTemplate.js`，仅含 `getAgentTemplates()`，对应 `GET /api/v1/agents/templates`。未改动 `src/api/agent.js`（该文件由另一处改动占用）。
- 已新增 `src/components/AgentCreateWizard.vue`（三步向导）并替换 `src/views/Lab.vue` 原有的创建弹窗。第一步为模板卡片网格（名称、tagline、tags、建议活跃时段）加一张「自定义」卡；第二步为「平台模型（消耗积分）」与「自己的 API Key」两张卡，前者显示模型名、费率、每日 token 上限与最低积分要求，`platform_llm.enabled` 为 false 时禁用并显示「当前部署未开放平台模型」，后者选中后展开 base_url / api_key / model_name 三个字段；第三步为名称、人设 textarea、活跃时段、每日唤醒上限、Token 上限与无限模式。移动端每步占满全屏（`inset-0` + `h-full`），桌面端为居中卡片。
- 已新增纯逻辑模块 `src/utils/agentTemplate.js` 与 `agentTemplate.test.mjs`（15 条），覆盖模板与 `platform_llm` 归一化、模板预填合并、按 `provider_mode` 组装创建请求体、按 `provider_mode` 组装校验 schema、费率与配额文案、模型来源徽标判定、平台不可用的 409 判定与创建失败文案。
- 已在 `src/views/Lab.vue` 编辑弹窗按模型来源分支：PLATFORM 隐藏 base_url / api_key / model_name 三项，改为 PLATFORM 徽标加平台模型名，且更新请求体不带 `model_name`；BYOK 与改动前一致。
- 已在 `src/components/AgentRackCard.vue` 标题行与 `src/views/Monitor.vue` 身份区加模型来源徽标，Monitor 的 `INSTANCE_CONFIG` 增加 PROVIDER 行，PLATFORM 时不再渲染 BASE_URL 与 API_KEY 两行。
- 已在 `src/stores/agent.js` 增加 `errorStatus`，`createAgent` 与 `updateAgent` 失败时记录 HTTP 状态码与业务码，供平台不可用的 409 兜底判定使用。

### 更早：通知中心
- 已新增 `src/api/notification.js`，四个方法对应 `GET /api/v1/notifications`、`GET /api/v1/notifications/unread-count`、`POST /api/v1/notifications/{id}/read`、`POST /api/v1/notifications/read-all`，沿用默认 V1 baseURL，查询参数为 snake_case 的 `unread_only` / `page` / `size`。
- 已新增 `src/stores/notification.js`：列表分页（size 20，`loadMore` 按 id 去重追加）、`unreadOnly` 切换、`markRead`（已读行不发请求）、`markAllRead`（后端返回 `{count:0}`，直接赋值不做减法）、未读数轮询。轮询间隔 60 秒，`document.visibilityState` 为 hidden 时清除定时器、恢复可见时先补一次再重建定时器；游客与无 token 时不发请求。
- 已新增 `src/components/NotificationBell.vue`（铃铛按钮 + 未读角标，未登录与游客不渲染）与 `src/components/NotificationPanel.vue`（列表含标题、正文、相对时间、未读点、触发者与类型文案；「仅未读」切换、「全部已读」、加载更多、90001 提示）。
- 已把 `<NotificationBell />` 接入 `src/views/Square.vue`、`src/views/Lab.vue`、`src/views/BountyGuild.vue` 三个页面头部的右侧导航组。三个页面各自维护结构相同的 `<header>`，没有共享头部组件，未在本任务内抽取。
- 已新增纯逻辑模块 `src/utils/notification.js` 与 `notification.test.mjs`（15 条），覆盖 link_type 到路由的映射、未读角标上限 99+、相对时间与占位符、分页去重、90001/90002 文案。
- 已复用 `src/utils/format.js` 的 `formatRelativeTime` 与 `src/utils/page.js` 的 `unwrapPage`，未新增重复实现。

### 更早：Agent 公开主页与榜单
- 已新增 `src/api/agent.js` 的 `getAgentPublicProfile(id)` 与 `getAgentRanking(params)`，对应 `GET /api/v1/agents/{id}/profile` 与 `GET /api/v1/agents/ranking`，两者匿名可访问。
- 已新增 `src/views/AgentProfile.vue` 与路由 `/agent/:id`（name `AgentProfile`，`meta.requiresAuth: false`），并把 `/agent` 加入路由守卫的 `guestAllowed`。页面含头部（首字母头像、名称、状态呼吸灯、所有者、创建时间、活跃时段与活跃状态文案）、四格统计、互评最多的 Agent 列表、最近帖子列表；20002/404 显示「该 Agent 不存在」。
- 已按「我的 Agent 列表是否包含该 id」判定所有者：所有者看到「进入监控台」链接到 `/monitor/:id`，其余访问者看到「创建你自己的 Agent」CTA。
- 已新增 `src/components/AgentRankingPanel.vue` 并接入 `src/views/Square.vue` 右侧栏，三种榜（replied / tipped / active）可切换，每行显示名次、名称（链接公开主页）、状态、分值与单位说明。
- 已把 `src/components/PostCard.vue`、`src/components/CommentThread.vue`、`src/views/PostDetail.vue` 中 `author_type` 为 `AGENT` 的作者名改为链接到 `/agent/{author_id}`，PostCard 上带 `@click.stop` 以免触发卡片自身的 `view` 事件。
- 已在 `src/views/Monitor.vue` 的日志条目中增加唤醒原因标签，取 `wake_reason_text`，为 null 时不渲染。
- 已新增纯逻辑模块 `src/utils/agentProfile.js` 与 `agentProfile.test.mjs`（17 条），覆盖活跃状态文案、404 文案、榜单单位与分值格式、作者链接判定、所有者判定、CTA 目标。

### 更早：记忆面板与作息设置
- 已新增 `src/api/agent.js` 的 `getAgentMemories(id, params)` 与 `updateAgentMemory(id, memoryId, data)`，路径为 `/api/v1/agents/{id}/memories`，对应后端 2026-07-28 实现的 `AgentMemoryController`。
- 已新增 `src/components/AgentMemoryPanel.vue`：类型与状态筛选、分页、每条展示内容/置信度/来源/创建时间/过期时间/状态，操作为禁用、恢复、修正内容；操作成功后用 PATCH 响应就地更新该卡片，不重新加载整页。含「特质时间线」切换视图，只取 PERSONA_TRAIT，按创建日期分组倒序，DEPRECATED 卡片灰显。
- 已在 `src/views/Monitor.vue` 接入该面板（Monitor 仅 owner 可访问，未加额外权限判断），并把该页 `INSTANCE_CONFIG` 区块里后端不存在的 `next_wakeup_at` / `daily_bounty_count` 改为 `next_wake_at`、`wake_hours_start/end`、`wake_count_today` 与 `daily_wake_budget`。
- 已在 `src/views/Lab.vue` 编辑弹窗新增 WAKE_RHYTHM 区块：活跃时段起止为两个 0-23 下拉，每日唤醒上限为 1-24 数字输入；提交时只带上用户改动过的字段，20009 显示「当前部署未启用唤醒队列」。
- 已修正 `src/components/AgentRackCard.vue`，删除 `last_wakeup_at` / `next_wakeup_at` / `daily_bounty_count` 三个后端响应中不存在的字段，改为 `NEXT_WAKE` 与 `ACTIVE_HOURS`，空值显示 `N/A`。
- 已新增纯逻辑模块 `src/utils/memory.js` 与 `src/utils/wake.js`，并新增 `memory.test.mjs`（13 条）与 `wake.test.mjs`（12 条），覆盖错误码到提示文案的映射、特质按日期分组、作息表单校验与增量提交。
- 已对 `src/utils/request.js` 做最小改动：业务错误分支的 Error 补上 `code` 与 `status`，成功路径与会话失效处理不变。
- 已在 `src/stores/agent.js` 的 state 增加 `errorCode`，`updateAgent` 失败时写入业务码，供作息设置区分 20009。

### 更早：日报与基础设施
- 已新增 `src/api/hotNews.js`、`DailyHotNewsPanel.vue`、`DailyHotDetail.vue`、`/hot-news/:id` 路由，并接入社区页。
- 已确认前端采用 Vue 3 + Vite + Vue Router + Pinia + Axios，部署 base 保持 `/pulse/`。

## In Progress
- 无

## Blocked
- Blocker: 无
- Needed input: 无
- Since: 2026-09-06

## Decisions
- 2026-09-06: 模型来源在向导第二步选择，创建后不可改，编辑弹窗只展示不提供切换。后端 `provider_mode` 创建后不可改，提供一个注定被拒绝的切换控件不产生任何效果。
- 2026-09-06: 「自定义」用哨兵值 `__custom__` 而不是 null 表示。`template_id` 为 null 表示第一步尚未做出选择，两者需要在「下一步」的可用性上区分；组装请求体时该哨兵值不会出现在 `template_id` 里。
- 2026-09-06: 选中模板只预填人设与建议作息，名称仅在当前为空或仍是上一张模板带入的值时才覆盖。用户手输过的名称不应因为换一张模板卡而丢失。
- 2026-09-06: 创建请求体带上作息字段，并在创建响应未回填时补一次更新接口调用。创建接口是否接受作息字段以后端为准，比较提交值与响应值即可判断，前端无需预先知道后端行为；该补写为尽力而为，失败（如 legacy 部署返回 20009）只记录到控制台，不影响已创建的 Agent。
- 2026-09-06: 平台模型不可用的判定优先按业务码（`PLATFORM_UNAVAILABLE_CODES`），并保留「HTTP 409 且消息含『平台』」的兜底路径。后端最终采用的码值尚未核对，只按码判定会让这条路径在码值不一致时完全失效。
- 2026-09-06: `platform_llm.enabled` 缺失时按不可用处理。接口未明确说明可用时不应展示一张可点击的平台模型卡片。
- 2026-09-06: 模板加载失败不阻断创建。第一步显示告警并保留「自定义」卡，自定义人设加自己的 API Key 仍是一条完整路径。
- 2026-09-06: 模型来源徽标优先读 `provider_mode`，缺失时用 `api_key_masked === 'PLATFORM'` 兜底。后端尚未回填 `provider_mode` 的旧数据否则会显示成 BYOK。
- 2026-09-06: 创建请求体的组装与校验 schema 放在 `src/utils/agentTemplate.js`，不放在组件内，以便 `node --test` 在不加载 Vue 运行时的情况下覆盖。
- 2026-09-06: `link_type` 为 BOUNTY 时跳 `/bounty`，不带 `link_id`。悬赏详情是 BountyGuild 页内的视图状态，没有独立路由，`link_id` 无法通过 URL 表达；带上一个页面不读取的查询参数不会产生任何效果。
- 2026-09-06: 未读数轮询间隔取 60 秒，`document.visibilityState` 为 hidden 时清除定时器，恢复可见时先立即请求一次再重建定时器。只重建定时器会让切回标签页的用户最多等 60 秒才看到新角标。
- 2026-09-06: 读接口返回 90001 后停止轮询并置位 `unavailable`。缺表是部署形态而不是瞬时故障，继续每 60 秒请求只会重复产生 409；用户再次打开面板或点 RETRY 时仍会重试，但迁移执行后需要刷新页面才能恢复轮询。
- 2026-09-06: 轮询的启停用模块作用域的订阅计数而不是布尔值。路由切换时新页面的铃铛先挂载、旧页面后卸载，布尔值会在切换瞬间把轮询停掉。
- 2026-09-06: 登录判定用 `authStore.token && !isGuest`，不用 `isAuthenticated`。后者还要求 `user` 已加载，而路由守卫只在 `user` 为空时才拉取，铃铛挂载时机可能早于那次请求完成。
- 2026-09-06: 点击条目时不等待标记已读的响应就跳转。通知的作用是把用户送到目标页面，为一次记录状态的往返推迟跳转没有必要；失败时该行保持未读。
- 2026-09-06: `loadMore` 按 id 去重追加。后端为 offset 分页，两次请求之间新写入的通知会把已展示的行挤到下一页，直接拼接会出现重复行。
- 2026-09-06: 面板外点击的监听注册在捕获阶段。条目点击会触发路由跳转并把面板从 DOM 中移除，冒泡阶段再判断 `contains` 时该节点已脱离文档。
- 2026-09-06: 所有者判定用「我的 Agent 列表是否包含该 id」。公开主页响应只有 `owner_name`、没有 `owner_id`，无法与 `authStore.user.user_id` 直接比对；列表拿不到（未登录、请求失败）时按非所有者处理，只少显示一个监控台入口。
- 2026-09-06: 「创建你自己的 Agent」CTA 对未登录访客跳 `/terminal?redirect=/lab`，对已登录的非所有者直接跳 `/lab`。Terminal 不会因为已有会话自动跳走，已登录用户跳过去会落在登录表单上。
- 2026-09-06: 活跃状态只读 `is_active_now`，不在前端按 `wake_hours_start/end` 本地推算。本地时区与服务端时区不一定一致，本地推算会给出与服务端不同的结论；三个字段为 null 时显示「未知」。
- 2026-09-06: 榜单 `type` 取值不在三者之内时不回退到 replied，只回显原值且单位留空。回退会把错误的 type 显示成另一种口径的数据。
- 2026-09-06: Agent 榜单独做 `AgentRankingPanel.vue`，不并入 `RankingPanel.vue`。两者的数据源、行结构与分值单位都不同，合并后组件内会出现两套互斥的渲染分支。
- 2026-09-06: 记忆面板的错误码文案与作息校验抽到 `src/utils/memory.js` 与 `src/utils/wake.js`，不放在组件内，以便 `node --test` 在不加载 Vue 运行时的情况下覆盖。
- 2026-09-06: 记忆接口返回 500 时显示「当前部署未启用记忆表」，不做静默回退（对应后端 D-0008）。
- 2026-09-06: 作息三个字段只在用户改动过时才提交。后端「只发一个边界时保留另一个」的语义要求不重复提交；legacy 部署下三个字段读回 null，原样回填会变成一次写入。
- 2026-09-06: DEPRECATED 记忆的 RESTORE 按钮置为 disabled 并带提示，正常路径不发出注定 20008 的请求；内容修正对 DEPRECATED 仍开放。
- 2026-09-06: 记忆类型的中文措辞采用 `com.pulse.enums.MemoryType` 的「行为事实」「人格特质」，与后端返回的 `memory_type_text` 一致。
- 2026-09-06: 公开已废弃卡的失败文案单独写「已废弃的记忆不可公开」。后端（X2）对该情况复用 20008，共用文案会显示成「不可恢复」，与本次操作无关。
- 2026-09-06: `is_public` 缺失时回退读 `scope === 'PUBLIC'`。后端响应同时返回 `scope` 与 `is_public`，前者是存储值、后者是它的渲染；两者都缺失时按未公开处理。
- 2026-09-06: 公开状态的切换按钮只对 PERSONA_TRAIT 且非 DEPRECATED 的卡显示，事实卡连标记一起不显示。对事实卡设公开必定返回 99900，提供该控件不会产生任何效果。
- 2026-09-06: 已禁用（status 0）的特质卡仍可切换公开状态。契约只排除事实卡与 DEPRECATED 卡，未排除已禁用卡；公开主页只取启用中的卡，因此对已禁用卡的设置在重新启用后才生效。
- 2026-09-06: 公开切换的失败文案单独走 `describePublicToggleError`。更新接口用同一个 99900 表示「请求内容无效」，而该请求只带 `is_public` 一个字段，唯一会触发该码的原因是对事实卡设公开，共用文案会显示与状态和内容有关的提示。
- 2026-09-06: 切换成功后若响应缺少 `is_public`，按提交值就地补上。legacy 部署可能不回该字段，只合并响应会让标记停留在切换前的取值。
- 2026-09-06: `is_public` 缺失按未公开渲染，不显示第三种「未知」状态。公开与否是一个二值开关，缺省即未公开。
- 2026-09-06: 公开特质区块对空列表与字段缺失渲染同一个空态。前端无法区分「后端尚未返回该字段」与「一条都没公开」，两者对访问者的含义相同。
- 2026-09-06: 置信度文案 `confidenceText` 从 `AgentMemoryPanel.vue` 提到 `src/utils/memory.js`，供公开主页复用。
- 2026-06-01: 保持 Vite `base: '/pulse/'` 和现有 Axios `/pulse/api/v1` 默认版本。
- 前端模块拥有页面、组件、Pinia store、API client、样式系统和 Vite 构建配置。

## Verification
- Command: `npm run lint`（在 `pulse-frontend` 目录）
- Result: pass
- Notes: 无输出，无告警。
- Command: `npm test`
- Result: pass
- Notes: 107 tests / 107 pass / 0 fail。基线 103 条，本次新增 4 条。
- Command: `npm run build`
- Result: pass
- Notes: Vite 生产构建完成，`memory` chunk 2.98 kB，`AgentProfile` chunk 10.30 kB。
- Command: 用 `@vue/compiler-sfc` 编译 `AgentMemoryPanel.vue` 与 `AgentProfile.vue`，检查模板标识符是否全部解析到 setup 绑定
- Result: pass
- Notes: 两个文件均无未解析标识符。

## Next
- 创建向导未做浏览器实机验证：本机 8080 无后端进程，`GET /agents/templates` 需登录，无法在无会话状态下走到模板列表与提交。后端实现该接口后需回归三步流程、平台卡禁用态、409 提示与作息补写。
- 平台模型不可用的业务码尚未与后端核对。`src/utils/agentTemplate.js` 的 `PLATFORM_UNAVAILABLE_CODES` 当前取 `{20010, 20011}` 为推测值，后端确定后需替换为实际码值；在此之前依赖 409 加消息含「平台」的兜底判定。
- 创建接口是否接受 `wake_hours_start` / `wake_hours_end` / `daily_wake_budget` 尚未与后端核对。当前实现两种情况都能工作，但后端接受这三个字段时会多一次无谓的比较，不接受时会多一次更新请求。
- 平台 Agent 的积分余额与当日 token 消耗未在任何页面展示。积分账本已有 `LedgerPanel`，但与单个 Agent 的平台用量没有关联口径，待后端提供按 Agent 的用量字段后再补。
- 通知中心未做浏览器实机验证：本机 8080 无后端进程，铃铛只在有 token 且非游客时渲染，无法在无会话状态下走到列表与面板。后端部署并执行 `deploy/migrations/2026-09-06-notifications.sql` 后需回归角标、分页、仅未读与跳转。
- 三个页面头部结构相同但各自维护，本次是第三处复制。若后续再加头部元素，建议先抽出共享头部组件。
- 通知中心目前没有独立页面，只有下拉面板；通知量增长后需要一个全量列表页（`/notifications`）承载筛选与更长的历史。
- 唤醒原因标签依赖 `AgentLogResponse.applyWakeContext`，后端已在 `AgentServiceImpl.buildLogResponse` 接线；数据库缺少 `agent_logs` 的两个 wake 列时读回 null，标签不显示。
- 三处字段名已与后端笔记 X2 核对一致：`is_public`、`public_traits`（`memory_id` / `content` / `confidence_score` / `created_at`）、`stats.completed_bounty_count`。后端改动尚未合入本工作树（`pulse-backend/src` 中仍无 `is_public` / `public_traits`，`AgentProfileServiceImpl` 仍写死 0），两侧合并后需回归三处展示。
- `public_traits` 对所有者与访问者返回同一份数据（只含已公开的卡），所有者在自己的公开主页上看不到未公开的特质。X2 未解决问题 2 记录了这一点，若产品要求所有者直接在主页看到全量，需要后端在该匿名端点引入身份判断。
- 公开状态在多实例部署下最多 30 秒后才在公开主页可见（X2 未解决问题 4，缓存失效只作用于处理该 PATCH 的实例）。前端切换后未提示这一延迟。
- 公开特质区块与切换按钮未做浏览器实机验证：本机 8080 无后端进程，记忆面板需登录且需要 `agent_memories` 表。
- 后端部署到 `AGENT_LOOP_MODE=queue` 且执行 `deploy/migrations/2026-07-28-agent-wake-queue.sql` 与 `2026-07-28-agent-memories.sql` 后，用真实数据回归记忆面板与作息设置。
- 待确认：作息字段目前无法清空（更新接口只有「不传即保留」语义），若产品需要清除已存值，需要后端补显式语义。
