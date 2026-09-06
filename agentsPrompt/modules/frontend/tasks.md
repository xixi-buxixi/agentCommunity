# Task State: frontend

## Current
- Task ID: frontend-2026-09-06-notification-centre
- Goal: 接入通知中心：API 客户端、Pinia store（未读数轮询与列表分页）、铃铛与下拉面板，并放入三个页面的头部。
- Scope: `/pulse-frontend/**`、`/agentsPrompt/modules/frontend/tasks.md`
- Status: done
- Owner: Claude
- Last Updated: 2026-09-06

## Done Summary
- 已新增 `src/api/notification.js`，四个方法对应 `GET /api/v1/notifications`、`GET /api/v1/notifications/unread-count`、`POST /api/v1/notifications/{id}/read`、`POST /api/v1/notifications/read-all`，沿用默认 V1 baseURL，查询参数为 snake_case 的 `unread_only` / `page` / `size`。
- 已新增 `src/stores/notification.js`：列表分页（size 20，`loadMore` 按 id 去重追加）、`unreadOnly` 切换、`markRead`（已读行不发请求）、`markAllRead`（后端返回 `{count:0}`，直接赋值不做减法）、未读数轮询。轮询间隔 60 秒，`document.visibilityState` 为 hidden 时清除定时器、恢复可见时先补一次再重建定时器；游客与无 token 时不发请求。
- 已新增 `src/components/NotificationBell.vue`（铃铛按钮 + 未读角标，未登录与游客不渲染）与 `src/components/NotificationPanel.vue`（列表含标题、正文、相对时间、未读点、触发者与类型文案；「仅未读」切换、「全部已读」、加载更多、90001 提示）。
- 已把 `<NotificationBell />` 接入 `src/views/Square.vue`、`src/views/Lab.vue`、`src/views/BountyGuild.vue` 三个页面头部的右侧导航组。三个页面各自维护结构相同的 `<header>`，没有共享头部组件，未在本任务内抽取。
- 已新增纯逻辑模块 `src/utils/notification.js` 与 `notification.test.mjs`（15 条），覆盖 link_type 到路由的映射、未读角标上限 99+、相对时间与占位符、分页去重、90001/90002 文案。
- 已复用 `src/utils/format.js` 的 `formatRelativeTime` 与 `src/utils/page.js` 的 `unwrapPage`，未新增重复实现。

## Previous Done Summary
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
- 2026-06-01: 保持 Vite `base: '/pulse/'` 和现有 Axios `/pulse/api/v1` 默认版本。
- 前端模块拥有页面、组件、Pinia store、API client、样式系统和 Vite 构建配置。

## Verification
- Command: `npm run lint`（在 `pulse-frontend` 目录）
- Result: pass
- Notes: 无输出，无告警。
- Command: `npm test`
- Result: pass
- Notes: 84 tests / 84 pass / 0 fail。基线 69 条，本次新增 15 条。
- Command: `npm run build`
- Result: pass
- Notes: Vite 生产构建完成，新增 `NotificationBell` chunk（9.59 kB）。

## Next
- 通知中心未做浏览器实机验证：本机 8080 无后端进程，铃铛只在有 token 且非游客时渲染，无法在无会话状态下走到列表与面板。后端部署并执行 `deploy/migrations/2026-09-06-notifications.sql` 后需回归角标、分页、仅未读与跳转。
- 三个页面头部结构相同但各自维护，本次是第三处复制。若后续再加头部元素，建议先抽出共享头部组件。
- 通知中心目前没有独立页面，只有下拉面板；通知量增长后需要一个全量列表页（`/notifications`）承载筛选与更长的历史。
- 唤醒原因标签依赖 `AgentLogResponse.applyWakeContext`，后端已在 `AgentServiceImpl.buildLogResponse` 接线；数据库缺少 `agent_logs` 的两个 wake 列时读回 null，标签不显示。
- 公开主页不含特质卡，`completed_bounty_count` 后端恒为 0，页面未展示该字段。两者待后端决策后再补。
- 后端部署到 `AGENT_LOOP_MODE=queue` 且执行 `deploy/migrations/2026-07-28-agent-wake-queue.sql` 与 `2026-07-28-agent-memories.sql` 后，用真实数据回归记忆面板与作息设置。
- 待确认：作息字段目前无法清空（更新接口只有「不传即保留」语义），若产品需要清除已存值，需要后端补显式语义。
