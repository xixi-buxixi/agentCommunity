/**
 * Agent 创建向导的纯逻辑：人设模板归一化与预填、按模型来源组装创建请求体、
 * 平台模型费率文案、创建失败的错误码判定。
 *
 * 与 Vue 无关，`node --test` 可直接加载（见 agentTemplate.test.mjs）。
 *
 * 对应后端：
 * - `GET /api/v1/agents/templates` → `{ templates, platform_llm }`
 * - `POST /api/v1/agents` 新增可选字段 `provider_mode`、`template_id`
 */
import { ValidationRules } from './validation.js'
import { formatTokens } from './format.js'
import { normalizeWakeValue, WAKE_FIELDS } from './wake.js'

/** 模型来源。创建后不可修改。 */
export const PROVIDER_MODE = {
  /** 自带 API Key，与改动前的行为一致，为默认值。 */
  BYOK: 'BYOK',
  /** 平台模型，按积分计费，不提交 base_url / api_key / model_name。 */
  PLATFORM: 'PLATFORM'
}

/**
 * 「自定义」卡片的标识。
 *
 * 用一个前端专用的哨兵值而不是 null，是为了让「尚未做出选择」（null）与
 * 「已选择自定义」（本值）在第一步的下一步按钮上可区分。组装请求体时该值
 * 不会出现在 `template_id` 里。
 */
export const CUSTOM_TEMPLATE_ID = '__custom__'

/**
 * 平台模型不可用的业务码。
 *
 * 后端返回 HTTP 409 + PLATFORM_MODEL_UNAVAILABLE。具体数字以后端为准，这里
 * 除了按码判定外还保留一条按 409 + 消息含「平台」的兜底路径，见
 * isPlatformUnavailableError。
 */
export const PLATFORM_UNAVAILABLE_CODES = new Set([20010, 20011])

/** 创建表单的初始值。 */
export const createInitialForm = () => ({
  template_id: null,
  provider_mode: PROVIDER_MODE.BYOK,
  name: '',
  avatar_url: '',
  base_url: 'https://api.openai.com/v1',
  api_key: '',
  model_name: 'gpt-4o-mini',
  system_prompt: '',
  token_threshold: 500000,
  is_unlimited: false,
  wake_hours_start: null,
  wake_hours_end: null,
  daily_wake_budget: null
})

const toText = (value) => (typeof value === 'string' ? value : value == null ? '' : String(value))

const toFiniteNumber = (value) => {
  if (value === '' || value === null || value === undefined) return null
  const num = Number(value)
  return Number.isFinite(num) ? num : null
}

/**
 * 归一化一条模板。缺失字段补为空串 / 空数组 / null，供模板卡片直接渲染。
 * @param {object} raw
 * @returns {object|null} template_id 缺失时返回 null
 */
export const normalizeTemplate = (raw) => {
  if (!raw || typeof raw !== 'object') return null
  const id = raw.template_id ?? raw.id
  if (id === null || id === undefined || id === '') return null
  return {
    template_id: String(id),
    name: toText(raw.name),
    tagline: toText(raw.tagline),
    description: toText(raw.description),
    system_prompt: toText(raw.system_prompt),
    suggested_wake_hours_start: normalizeWakeValue(raw.suggested_wake_hours_start),
    suggested_wake_hours_end: normalizeWakeValue(raw.suggested_wake_hours_end),
    tags: Array.isArray(raw.tags) ? raw.tags.map(toText).filter(Boolean) : []
  }
}

/**
 * 归一化模板列表。丢弃没有 template_id 的条目而不是让卡片渲染出一个无法选中的空位。
 * @param {object} data `GET /agents/templates` 的 data
 * @returns {Array<object>}
 */
export const normalizeTemplateList = (data) => {
  const list = Array.isArray(data) ? data : Array.isArray(data?.templates) ? data.templates : []
  return list.map(normalizeTemplate).filter(Boolean)
}

/**
 * 归一化平台模型信息。
 *
 * enabled 缺失时按 false 处理：接口没有明确说明可用时不应展示一张可点击的
 * 平台模型卡片。
 * @param {object} data `GET /agents/templates` 的 data
 * @returns {{enabled: boolean, model_name: string, points_per_1k_tokens: number|null, daily_token_cap_per_agent: number|null, min_points_to_wake: number|null}}
 */
export const normalizePlatformInfo = (data) => {
  const raw = data?.platform_llm ?? data?.platform ?? data ?? {}
  return {
    enabled: raw.enabled === true,
    model_name: toText(raw.model_name),
    points_per_1k_tokens: toFiniteNumber(raw.points_per_1k_tokens),
    daily_token_cap_per_agent: toFiniteNumber(raw.daily_token_cap_per_agent),
    min_points_to_wake: toFiniteNumber(raw.min_points_to_wake)
  }
}

/**
 * 把模板预填进表单。
 *
 * 预填人设与建议作息，两者之后仍可编辑。名称只在当前为空、或仍是上一个模板
 * 带入的名称时才覆盖：用户手输过的名称不会因为换一张模板卡而丢失。
 *
 * @param {object} form 当前表单
 * @param {object|null} template 归一化后的模板；null 表示「自定义」
 * @param {object|null} previous 上一次选中的模板，用于判断名称是否被用户改过
 * @returns {object} 新的表单对象（不修改入参）
 */
export const applyTemplate = (form = {}, template = null, previous = null) => {
  if (!template) {
    return { ...form, template_id: CUSTOM_TEMPLATE_ID }
  }
  const currentName = toText(form.name).trim()
  const keepName = currentName !== '' && currentName !== toText(previous?.name).trim()
  return {
    ...form,
    template_id: template.template_id,
    name: keepName ? form.name : template.name,
    system_prompt: template.system_prompt,
    wake_hours_start: template.suggested_wake_hours_start,
    wake_hours_end: template.suggested_wake_hours_end
  }
}

/** 表单当前是否选中了平台模型。 */
export const isPlatformMode = (form) => form?.provider_mode === PROVIDER_MODE.PLATFORM

/**
 * 一个 Agent 是否使用平台模型。
 *
 * 主要看 `provider_mode`；后端尚未回填该字段的旧数据用 `api_key_masked === 'PLATFORM'`
 * 兜底，否则一个平台 Agent 会在卡片上显示成 BYOK。
 * @param {object} agent
 * @returns {boolean}
 */
export const isPlatformAgent = (agent) => {
  if (!agent) return false
  if (agent.provider_mode === PROVIDER_MODE.PLATFORM) return true
  if (agent.provider_mode === PROVIDER_MODE.BYOK) return false
  return agent.api_key_masked === 'PLATFORM'
}

/** 模型来源徽标文案。 */
export const providerModeLabel = (agent) =>
  isPlatformAgent(agent) ? PROVIDER_MODE.PLATFORM : PROVIDER_MODE.BYOK

/**
 * 创建表单的校验规则。平台模型下不校验 base_url / api_key / model_name，
 * 这三项不会提交。
 * @param {string} providerMode
 * @returns {object} 供 validateObject 使用的 schema
 */
export const buildCreateSchema = (providerMode) => {
  const schema = {
    name: [
      ValidationRules.required,
      (v) => ValidationRules.minLength(v, 2, 'Name'),
      (v) => ValidationRules.maxLength(v, 30, 'Name')
    ],
    system_prompt: [
      ValidationRules.required,
      (v) => ValidationRules.minLength(v, 10, 'System Prompt'),
      (v) => ValidationRules.maxLength(v, 2000, 'System Prompt')
    ],
    token_threshold: [
      ValidationRules.required,
      (v) => ValidationRules.numberRange(v, 1000, 100000000, 'Token Threshold')
    ]
  }
  if (providerMode === PROVIDER_MODE.PLATFORM) return schema
  return {
    ...schema,
    base_url: [
      ValidationRules.required,
      (v) => {
        if (!v) return null
        try {
          new URL(v)
          return null
        } catch {
          return 'Base URL format is invalid'
        }
      }
    ],
    api_key: [ValidationRules.required, (v) => ValidationRules.minLength(v, 10, 'API Key')],
    model_name: [ValidationRules.required, (v) => ValidationRules.maxLength(v, 80, 'Model Name')]
  }
}

/**
 * 组装 `POST /agents` 的请求体。
 *
 * 平台模型下不提交 base_url / api_key / model_name（后端按平台配置填充），
 * 自定义人设不提交 template_id，作息字段只在填写过时提交。
 *
 * @param {object} form
 * @returns {object}
 */
export const buildCreatePayload = (form = {}) => {
  const providerMode =
    form.provider_mode === PROVIDER_MODE.PLATFORM ? PROVIDER_MODE.PLATFORM : PROVIDER_MODE.BYOK

  const threshold = Number(form.token_threshold)
  const payload = {
    name: toText(form.name).trim(),
    system_prompt: toText(form.system_prompt).trim(),
    token_threshold: Number.isFinite(threshold)
      ? Math.max(1000, Math.min(100000000, Math.trunc(threshold)))
      : 1000,
    is_unlimited: form.is_unlimited === true,
    provider_mode: providerMode
  }

  const avatar = toText(form.avatar_url).trim()
  if (avatar) payload.avatar_url = avatar

  const templateId = toText(form.template_id).trim()
  if (templateId && templateId !== CUSTOM_TEMPLATE_ID) payload.template_id = templateId

  if (providerMode === PROVIDER_MODE.BYOK) {
    payload.base_url = toText(form.base_url).trim()
    payload.api_key = toText(form.api_key).trim()
    payload.model_name = toText(form.model_name).trim()
  }

  for (const field of WAKE_FIELDS) {
    const value = normalizeWakeValue(form[field])
    if (Number.isInteger(value)) payload[field] = value
  }

  return payload
}

/**
 * 创建成功后是否还需要补一次更新写入作息。
 *
 * 创建接口是否接受作息字段由后端决定；不接受时这三个字段会被忽略，创建响应
 * 里读回的值与提交值不同。比较两者即可判断，无需前端预先知道后端行为。
 *
 * @param {object} payload buildCreatePayload 的结果
 * @param {object} created 创建响应
 * @returns {object} 需要补写的字段，空对象表示不需要
 */
export const buildWakeFollowUpPayload = (payload = {}, created = {}) => {
  const followUp = {}
  for (const field of WAKE_FIELDS) {
    const requested = normalizeWakeValue(payload[field])
    if (!Number.isInteger(requested)) continue
    if (normalizeWakeValue(created?.[field]) === requested) continue
    followUp[field] = requested
  }
  return followUp
}

/**
 * 费率文案：每 1000 token 扣多少积分。
 * @param {number|null} pointsPer1k
 * @returns {string}
 */
export const formatPointsRate = (pointsPer1k) => {
  const value = toFiniteNumber(pointsPer1k)
  if (value === null) return '费率未知'
  if (value === 0) return '免费'
  const shown = Number.isInteger(value) ? String(value) : String(Number(value.toFixed(4)))
  return `每 1000 token 扣 ${shown} 积分`
}

/**
 * 每日 token 上限文案。
 * @param {number|null} cap
 * @returns {string}
 */
export const formatDailyTokenCap = (cap) => {
  const value = toFiniteNumber(cap)
  if (value === null) return '未设置'
  if (value <= 0) return '不限'
  return `${formatTokens(value)} tokens / 天`
}

/**
 * 最低积分要求文案。
 * @param {number|null} minPoints
 * @returns {string}
 */
export const formatMinPoints = (minPoints) => {
  const value = toFiniteNumber(minPoints)
  if (value === null) return '未设置'
  return `${value} 积分`
}

/**
 * 是否为「平台模型不可用」。
 *
 * 优先按业务码判定；后端最终采用的码值与 PLATFORM_UNAVAILABLE_CODES 不一致时，
 * 退回 HTTP 409 且消息里出现「平台」的判定，以免这条路径完全失效。
 *
 * @param {Error & {code?: number|null, status?: number|null}} error
 * @returns {boolean}
 */
export const isPlatformUnavailableError = (error) => {
  if (!error) return false
  if (typeof error.code === 'number' && PLATFORM_UNAVAILABLE_CODES.has(error.code)) return true
  return error.status === 409 && toText(error.message).includes('平台')
}

/**
 * 创建失败的提示文案。
 * @param {Error & {code?: number|null, status?: number|null}} error
 * @returns {string}
 */
export const describeCreateError = (error) => {
  if (isPlatformUnavailableError(error)) {
    return '平台模型当前不可用，请改用自己的 API Key 创建'
  }
  if (error?.code === 99900 || error?.status === 400) {
    return `参数不合法：${toText(error.message) || '请检查名称、人设与 Token 上限'}`
  }
  return toText(error?.message) || 'CREATE_FAILED'
}
