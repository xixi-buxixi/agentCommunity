/**
 * Agent 作息（活跃时段 + 每日唤醒预算）的纯逻辑。
 *
 * 对应后端 com.pulse.dto.request.AgentUpdateRequest 的三个可选字段：
 * wake_hours_start / wake_hours_end（0-23，终点不含该小时，可跨零点）
 * daily_wake_budget（1-24）。
 *
 * legacy 模式（AGENT_LOOP_MODE 非 queue 或缺少唤醒队列 schema）下，读取接口
 * 返回 null，更新接口返回 20009/409。
 */

export const WAKE_HOUR_MIN = 0
export const WAKE_HOUR_MAX = 23
export const WAKE_BUDGET_MIN = 1
export const WAKE_BUDGET_MAX = 24

/** 下拉可选的小时取值。 */
export const WAKE_HOUR_OPTIONS = Array.from({ length: 24 }, (_, hour) => hour)

/** 表单里的三个字段名，顺序即提交顺序。 */
export const WAKE_FIELDS = ['wake_hours_start', 'wake_hours_end', 'daily_wake_budget']

/**
 * 把表单里的值归一化：空字符串 / null / undefined 视为未设置，返回 null；
 * 其余转为整数（非法值保留原样交给校验函数报错）。
 * @param {*} value
 * @returns {number|null|*}
 */
export const normalizeWakeValue = (value) => {
  if (value === '' || value === null || value === undefined) return null
  const num = Number(value)
  if (!Number.isFinite(num)) return value
  return Math.trunc(num)
}

/**
 * 表单校验。三个字段都是可选的，但一旦填写就必须在合法区间内。
 *
 * 注意不校验「起点必须小于终点」：跨零点（22 到 6）是合法配置，
 * start === end 表示全天活跃。
 *
 * @param {{wake_hours_start?: *, wake_hours_end?: *, daily_wake_budget?: *}} form
 * @returns {string|null} 错误文案，通过时为 null
 */
export const validateWakeSettings = (form = {}) => {
  const start = normalizeWakeValue(form.wake_hours_start)
  const end = normalizeWakeValue(form.wake_hours_end)
  const budget = normalizeWakeValue(form.daily_wake_budget)

  const hourInvalid = (value) =>
    value !== null && (!Number.isInteger(value) || value < WAKE_HOUR_MIN || value > WAKE_HOUR_MAX)

  if (hourInvalid(start)) return `活跃时段起点为 ${WAKE_HOUR_MIN}-${WAKE_HOUR_MAX}`
  if (hourInvalid(end)) return `活跃时段终点为 ${WAKE_HOUR_MIN}-${WAKE_HOUR_MAX}（不含该小时）`
  if (
    budget !== null &&
    (!Number.isInteger(budget) || budget < WAKE_BUDGET_MIN || budget > WAKE_BUDGET_MAX)
  ) {
    return `每日唤醒上限为 ${WAKE_BUDGET_MIN}-${WAKE_BUDGET_MAX}`
  }
  return null
}

/**
 * 只把用户改动过的字段挑出来。
 *
 * 未改动的字段不提交，原因有二：legacy 部署下这三个字段读出来是 null，
 * 原样回填会把 null 写成一次更新；以及后端「只发一个边界时保留另一个」的语义
 * 只有在不重复提交时才有意义。
 *
 * @param {object} form 当前表单值
 * @param {object} original 打开弹窗时的原始值
 * @returns {object} 仅包含改动过且已设置的字段
 */
export const buildWakeUpdatePayload = (form = {}, original = {}) => {
  const payload = {}
  for (const field of WAKE_FIELDS) {
    const current = normalizeWakeValue(form[field])
    const before = normalizeWakeValue(original[field])
    if (current === null) continue
    if (current === before) continue
    payload[field] = current
  }
  return payload
}

/** 是否有作息字段被改动。 */
export const hasWakeChanges = (form, original) =>
  Object.keys(buildWakeUpdatePayload(form, original)).length > 0

/**
 * 活跃时段的展示文案。
 * @param {number|null|undefined} start
 * @param {number|null|undefined} end
 * @param {string} placeholder 未设置时的占位符
 * @returns {string}
 */
export const formatWakeWindow = (start, end, placeholder = '--') => {
  const s = normalizeWakeValue(start)
  const e = normalizeWakeValue(end)
  if (!Number.isInteger(s) || !Number.isInteger(e)) return placeholder
  const pad = (n) => String(n).padStart(2, '0')
  if (s === e) return '全天'
  return `${pad(s)}:00-${pad(e)}:00`
}

/**
 * 作息更新的错误码文案。
 * @param {Error & {code?: number|null, status?: number}} error
 * @returns {string}
 */
export const describeWakeError = (error) => {
  switch (error?.code) {
    case 20002:
      return 'Agent 不存在'
    case 20003:
      return '无权修改该 Agent'
    case 20009:
      return '当前部署未启用唤醒队列，作息设置暂不可用'
    case 99900:
      return '作息参数不合法，请检查活跃时段与每日唤醒上限'
    default:
      return error?.message || 'UPDATE_FAILED'
  }
}
