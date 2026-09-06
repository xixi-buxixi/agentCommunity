/**
 * Agent 记忆面板的纯逻辑。
 *
 * 与后端的对应关系：
 * - 状态码 com.pulse.enums.MemoryStatus（0 已禁用 / 1 生效中 / 2 已废弃）
 * - 类型 PERSONA_FACT（结构化事实）/ PERSONA_TRAIT（每日反思蒸馏出的特质）
 * - 错误码 com.pulse.exception.ErrorCode
 *
 * 放在 utils 而不是组件内，是为了让筛选取值、错误码文案与特质分组可以被
 * node --test 直接覆盖（组件本身依赖 Vue 运行时，测试不加载）。
 */

export const MEMORY_STATUS = {
  DISABLED: 0,
  ACTIVE: 1,
  DEPRECATED: 2
}

export const MEMORY_STATUS_LABELS = {
  [MEMORY_STATUS.DISABLED]: '已禁用',
  [MEMORY_STATUS.ACTIVE]: '生效中',
  [MEMORY_STATUS.DEPRECATED]: '已废弃'
}

// Wording follows com.pulse.enums.MemoryType so the filter labels and the
// backend-supplied memory_type_text cannot disagree.
export const MEMORY_TYPE_LABELS = {
  PERSONA_FACT: '行为事实',
  PERSONA_TRAIT: '人格特质'
}

/** 类型筛选项，value 为空字符串表示不带该查询参数。 */
export const MEMORY_TYPE_FILTERS = [
  { value: '', label: '全部' },
  { value: 'PERSONA_FACT', label: '行为事实' },
  { value: 'PERSONA_TRAIT', label: '人格特质' }
]

/** 状态筛选项，value 为空字符串表示不带该查询参数。 */
export const MEMORY_STATUS_FILTERS = [
  { value: '', label: '全部' },
  { value: String(MEMORY_STATUS.ACTIVE), label: '生效中' },
  { value: String(MEMORY_STATUS.DISABLED), label: '已禁用' },
  { value: String(MEMORY_STATUS.DEPRECATED), label: '已废弃' }
]

export const MEMORY_CONTENT_MAX = 500

/**
 * 构造列表请求参数，空筛选值不作为查询参数发出。
 *
 * 后端对非法筛选值返回 99900/400 而不是空页，所以这里不发出空字符串。
 *
 * @param {{type?: string, status?: string, page?: number, size?: number}} filters
 * @returns {object}
 */
export const buildMemoryQuery = ({ type = '', status = '', page = 1, size = 20 } = {}) => {
  const params = {
    page: Number.isFinite(Number(page)) && Number(page) >= 1 ? Math.floor(Number(page)) : 1,
    size: Number.isFinite(Number(size)) && Number(size) >= 1 ? Math.min(Math.floor(Number(size)), 50) : 20
  }
  if (type) params.memory_type = type
  if (status !== '' && status !== null && status !== undefined) params.status = Number(status)
  return params
}

export const isDeprecated = (memory) => Number(memory?.status) === MEMORY_STATUS.DEPRECATED

/** 已废弃是终态，只有已禁用的记忆可以恢复。 */
export const canReactivate = (memory) => Number(memory?.status) === MEMORY_STATUS.DISABLED

/** 生效中的记忆可以禁用；已废弃的记忆不需要再禁用。 */
export const canDisable = (memory) => Number(memory?.status) === MEMORY_STATUS.ACTIVE

export const statusLabel = (memory) =>
  memory?.status_text || MEMORY_STATUS_LABELS[Number(memory?.status)] || '未知'

export const typeLabel = (memory) =>
  memory?.memory_type_text || MEMORY_TYPE_LABELS[memory?.memory_type] || memory?.memory_type || '未知'

/**
 * 内容修正的本地校验，避免把注定 400 的请求发出去。
 * @param {string} content
 * @returns {string|null} 错误文案，通过时为 null
 */
export const validateMemoryContent = (content) => {
  const value = typeof content === 'string' ? content.trim() : ''
  if (value.length === 0) return '记忆内容不能为空'
  if (value.length > MEMORY_CONTENT_MAX) return `记忆内容长度为1-${MEMORY_CONTENT_MAX}字符`
  return null
}

/**
 * 把后端的时间字符串解析为 Date。
 *
 * 后端返回的形式可能是 "2026-07-28T10:00:00" 或 "2026-07-28 10:00:00"，
 * 后者在 Safari 上直接 new Date() 会得到 Invalid Date。
 *
 * @param {string|Date|null|undefined} value
 * @returns {Date|null}
 */
export const parseBackendTime = (value) => {
  if (!value) return null
  if (value instanceof Date) return Number.isNaN(value.getTime()) ? null : value
  if (typeof value !== 'string') return null
  const direct = new Date(value)
  if (!Number.isNaN(direct.getTime())) return direct
  const normalized = new Date(value.replace(' ', 'T'))
  return Number.isNaN(normalized.getTime()) ? null : normalized
}

/**
 * 取本地日期键（YYYY-MM-DD）。无法解析时退回原字符串的前 10 个字符。
 * @param {string|Date|null|undefined} value
 * @returns {string}
 */
export const dateKeyOf = (value) => {
  const date = parseBackendTime(value)
  if (date) {
    const pad = (n) => String(n).padStart(2, '0')
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  }
  if (typeof value === 'string' && value.length >= 10) return value.slice(0, 10)
  return '未知日期'
}

/**
 * 特质时间线分组：只取 PERSONA_TRAIT，按创建日期分组，日期倒序，
 * 组内按创建时间倒序。
 *
 * @param {Array<object>} memories
 * @returns {Array<{date: string, items: Array<object>}>}
 */
export const groupTraitsByDate = (memories) => {
  const list = Array.isArray(memories) ? memories : []
  const buckets = new Map()

  for (const memory of list) {
    if (!memory || memory.memory_type !== 'PERSONA_TRAIT') continue
    const key = dateKeyOf(memory.created_at)
    if (!buckets.has(key)) buckets.set(key, [])
    buckets.get(key).push(memory)
  }

  const timeOf = (memory) => {
    const date = parseBackendTime(memory?.created_at)
    return date ? date.getTime() : 0
  }

  return Array.from(buckets.entries())
    .map(([date, items]) => ({
      date,
      items: items.slice().sort((a, b) => timeOf(b) - timeOf(a))
    }))
    .sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0))
}

/**
 * 置信度展示。后端为 0-100 的整数，缺失时显示 `--`。
 * @param {{confidence_score?: number|null}} memory
 * @returns {string}
 */
export const confidenceText = (memory) => {
  const value = Number(memory?.confidence_score)
  return Number.isFinite(value) && memory?.confidence_score !== null ? String(value) : '--'
}

/** 面板顶部的说明，说明公开的影响范围。 */
export const PUBLIC_TRAIT_HINT = '公开的特质会显示在该 Agent 的公开主页上'

/** 公开主页最多展示的特质条数，与后端 public_traits 的上限一致。 */
export const PUBLIC_TRAIT_LIMIT = 20

/**
 * 该记忆是否已公开。
 *
 * `is_public` 是后端对 `agent_memories.scope` 的渲染，同一响应里两个字段都在。
 * 以 `is_public` 为准，缺失时读 `scope`；两者都没有（legacy 部署）时按未公开处理。
 *
 * @param {{is_public?: boolean|null, scope?: string|null}} memory
 * @returns {boolean}
 */
export const isPublicTrait = (memory) => {
  if (typeof memory?.is_public === 'boolean') return memory.is_public
  return memory?.scope === 'PUBLIC'
}

/** 只有特质卡可以公开。 */
export const isTrait = (memory) => memory?.memory_type === 'PERSONA_TRAIT'

/**
 * 是否提供公开状态的切换。
 *
 * 事实卡设 is_public 会被后端拒为 99900；已废弃的卡不可公开。两种情况都不显示按钮，
 * 不发出注定被拒绝的请求。
 *
 * @param {{memory_type?: string, status?: number}} memory
 * @returns {boolean}
 */
export const canTogglePublic = (memory) => isTrait(memory) && !isDeprecated(memory)

/** 公开状态标记文案。 */
export const publicStateLabel = (memory) => (isPublicTrait(memory) ? '公开' : '私有')

/** 切换按钮文案，动作方向与当前状态相反。 */
export const publicToggleLabel = (memory) => (isPublicTrait(memory) ? '设为私有' : '设为公开')

/**
 * 切换公开状态的请求体。
 * @param {{is_public?: boolean|null}} memory
 * @returns {{is_public: boolean}}
 */
export const buildPublicTogglePayload = (memory) => ({ is_public: !isPublicTrait(memory) })

/**
 * 公开状态切换失败的提示文案。
 *
 * 两个码在这条路径上单独处理，其余沿用 describeMemoryError：
 * - 99900：更新接口用同一个业务码表示「请求内容无效」，而本次请求只带 is_public
 *   一个字段，唯一会触发该码的原因是对事实卡设公开。
 * - 20008：后端对「公开一张已废弃的卡」复用了这个码（X2），共用文案会显示成
 *   「不可恢复」，与本次操作无关。正常路径下按钮已隐藏，该文案只在读取列表之后、
 *   写入之前被系统废弃的时间窗内出现。
 *
 * @param {Error & {code?: number|null, status?: number}} error
 * @returns {string}
 */
export const describePublicToggleError = (error) => {
  if (error?.code === 99900) return '只有特质卡可以公开'
  if (error?.code === 20008) return '已废弃的记忆不可公开'
  return describeMemoryError(error)
}

/**
 * 业务错误码到提示文案的映射。
 *
 * 5xx 单独处理：按 D-0008，部署库缺少 agent_memories 表时后端明确返回 500 而不做
 * 静默回退。但缺表不是 5xx 的唯一来源——数据库连接中断、后端未捕获的异常、
 * nginx 返回的 502/503 都会走到这里，且响应体没有 code 字段，无法与缺表区分。
 * 因此文案同时给出两种可能，不指向单一处置动作。
 *
 * @param {Error & {code?: number|null, status?: number}} error
 * @returns {string}
 */
export const describeMemoryError = (error) => {
  const code = error?.code
  const status = error?.status

  switch (code) {
    case 20002:
      return 'Agent 不存在'
    case 20003:
      return '无权查看该 Agent 的记忆'
    case 20007:
      return '记忆不存在或不属于该 Agent'
    case 20008:
      return '已废弃的记忆不可恢复'
    case 99900:
      return '请求内容无效：至少要修改状态或内容，且状态只能为生效中/已禁用'
    case 99904:
      return '该记忆已被其他操作更新，请刷新后重试'
    default:
      break
  }

  if (typeof status === 'number' && status >= 500) {
    return '记忆服务暂时不可用（可能是部署库尚未启用记忆表，或服务异常），请稍后重试或联系管理员'
  }
  return error?.message || 'MEMORY_REQUEST_FAILED'
}
