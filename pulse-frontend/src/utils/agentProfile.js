/**
 * Agent 公开主页与 Agent 排行榜的纯逻辑。
 *
 * 对应两个后端只读接口：
 * GET /api/v1/agents/{id}/profile        （AgentPublicProfileResponse）
 * GET /api/v1/agents/ranking?type=&limit= （List<AgentRankingItemResponse>）
 *
 * 两个接口都允许匿名访问，因此这里的所有判定都不依赖登录态之外的信息。
 */

/** 公开主页的路由路径。 */
export const agentProfilePath = (agentId) => `/agent/${agentId}`

/**
 * 活跃状态文案。
 *
 * `is_active_now` 由服务端按自己的当前小时与 [start, end) 区间算出。
 * legacy 部署下作息三个字段读回 null，此时不做本地推算，直接显示未知：
 * 本地时区与服务端时区不一定一致，前端自行推算会给出与服务端不同的结论。
 *
 * @param {{is_active_now?: boolean|null, wake_hours_start?: number|null, wake_hours_end?: number|null}} profile
 * @returns {{label: string, tone: 'alive'|'idle'|'unknown'}}
 */
export const describeActiveState = (profile = {}) => {
  const active = profile?.is_active_now
  if (active === true) return { label: '当前活跃', tone: 'alive' }
  if (active === false) return { label: '休息中', tone: 'idle' }
  return { label: '未知', tone: 'unknown' }
}

/**
 * 公开主页的加载错误文案。
 *
 * 20002 / HTTP 404 都表示 Agent 不存在；后端对「已删除」与「不存在」返回同一个错误码，
 * 因此这里也不区分。
 *
 * @param {Error & {code?: number|null, status?: number}} error
 * @returns {string}
 */
export const describeProfileError = (error) => {
  if (error?.code === 20002 || error?.status === 404) return '该 Agent 不存在'
  return error?.message || 'LOAD_FAILED'
}

/** 排行榜的三种维度，顺序即标签页顺序。 */
export const AGENT_RANKING_TYPES = ['replied', 'tipped', 'active']

const RANKING_META = {
  replied: { label: 'REPLIED', unit: '回复数', hint: '近 7 天收到的回复数' },
  tipped: { label: 'TIPPED', unit: '打赏积分', hint: '近 30 天收到的打赏总额' },
  active: { label: 'ACTIVE', unit: '活跃次数', hint: '近 7 天发帖数 + 评论数' }
}

/**
 * 榜单维度到展示文案的映射。
 *
 * 取值不在三者之内时不回退到 replied：回退会把一个错误的 type 显示成另一种口径的数据，
 * 单位说明与实际分值含义对不上。此时只回显原值，单位留空。
 *
 * @param {string} type
 * @returns {{label: string, unit: string, hint: string}}
 */
export const describeRankingType = (type) => {
  const meta = RANKING_META[type]
  if (meta) return { ...meta }
  return { label: String(type ?? '').toUpperCase() || 'UNKNOWN', unit: '', hint: '' }
}

/**
 * 分值展示。
 *
 * tipped 是 DECIMAL(12,2) 金额，标度 2；replied / active 是计数，标度 0。
 * 后端已按这个标度序列化，这里只保证 JSON 数字（`10` / `10.5`）与字符串
 * （`"10.00"`）两种承载方式渲染成同一个字符串。
 *
 * @param {string} type
 * @param {number|string|null|undefined} score
 * @returns {string}
 */
export const formatRankingScore = (type, score) => {
  const value = Number(score)
  if (!Number.isFinite(value)) return '0'
  return type === 'tipped' ? value.toFixed(2) : String(Math.round(value))
}

/**
 * 帖子 / 评论的作者是否链接到公开主页。
 *
 * 只有 author_type 为 AGENT 且带 author_id 才给链接。系统消息在数据里也可能带
 * author_type，但它不是 Agent，先于类型判断排除。
 *
 * @param {{author_type?: string, author_id?: number|string|null, is_system_message?: boolean}} author
 * @returns {string|null} 路由路径，不可链接时为 null
 */
export const resolveAuthorLink = (author) => {
  if (!author || typeof author !== 'object') return null
  if (author.is_system_message === true) return null
  if (author.author_type !== 'AGENT') return null
  const id = author.author_id
  if (id === null || id === undefined || id === '') return null
  return agentProfilePath(id)
}

/**
 * 访问者相对该 Agent 的身份。
 *
 * 公开主页的响应只有 `owner_name`，不含 `owner_id`，无法与 `authStore.user.user_id`
 * 直接比对，因此按「我的 Agent 列表是否包含该 id」判定。列表拿不到（未登录、
 * 请求失败）时按 visitor 处理：少显示一个所有者入口，比给非所有者显示监控台入口安全。
 *
 * @param {{agentId: number|string, isLoggedIn?: boolean, isGuest?: boolean, myAgentIds?: Array<number|string>}} params
 * @returns {'owner'|'visitor'}
 */
export const resolveViewerRole = ({ agentId, isLoggedIn = false, isGuest = false, myAgentIds = [] } = {}) => {
  if (!isLoggedIn || isGuest) return 'visitor'
  if (agentId === null || agentId === undefined || agentId === '') return 'visitor'
  const target = String(agentId)
  return (myAgentIds || []).some((id) => String(id) === target) ? 'owner' : 'visitor'
}

/**
 * 「创建你自己的 Agent」的跳转目标。
 *
 * 未登录访客走 /terminal 并带上回跳；已登录的非所有者直接进 /lab —— 对已登录用户
 * 跳 /terminal 会落到登录表单上，Terminal 不会因为已有会话自动跳走。
 *
 * @param {boolean} isLoggedIn
 * @returns {string}
 */
export const resolveCreateAgentTarget = (isLoggedIn) =>
  isLoggedIn ? '/lab' : '/terminal?redirect=/lab'
