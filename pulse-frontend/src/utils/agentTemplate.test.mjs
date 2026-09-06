import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  CUSTOM_TEMPLATE_ID,
  PROVIDER_MODE,
  applyTemplate,
  buildCreatePayload,
  buildCreateSchema,
  buildWakeFollowUpPayload,
  createInitialForm,
  describeCreateError,
  formatDailyTokenCap,
  formatMinPoints,
  formatPointsRate,
  isPlatformAgent,
  isPlatformUnavailableError,
  normalizePlatformInfo,
  normalizeTemplate,
  normalizeTemplateList,
  providerModeLabel
} from './agentTemplate.js'
import { hasErrors, validateObject } from './validation.js'

const TEMPLATE = {
  template_id: 'night_owl',
  name: '夜猫子',
  tagline: '凌晨最活跃',
  description: '一个深夜出没的数字居民',
  system_prompt: '你在深夜最活跃，说话简短。',
  suggested_wake_hours_start: 22,
  suggested_wake_hours_end: 6,
  tags: ['夜间', '简短']
}

test('模板归一化补齐缺失字段，缺 template_id 的条目被丢弃', () => {
  const normalized = normalizeTemplate({ template_id: 'x' })
  assert.equal(normalized.name, '')
  assert.deepEqual(normalized.tags, [])
  assert.equal(normalized.suggested_wake_hours_start, null)
  assert.equal(normalizeTemplate({ name: '无 id' }), null)
  assert.equal(normalizeTemplate(null), null)
  assert.equal(normalizeTemplate({ template_id: 7 }).template_id, '7')
})

test('模板列表既接受 { templates } 也接受裸数组', () => {
  assert.equal(normalizeTemplateList({ templates: [TEMPLATE] }).length, 1)
  assert.equal(normalizeTemplateList([TEMPLATE]).length, 1)
  assert.deepEqual(normalizeTemplateList(null), [])
  assert.equal(normalizeTemplateList({ templates: [TEMPLATE, { name: '坏数据' }] }).length, 1)
})

test('platform_llm 缺 enabled 时按不可用处理', () => {
  const info = normalizePlatformInfo({
    platform_llm: {
      enabled: true,
      model_name: 'pulse-mini',
      points_per_1k_tokens: 2,
      daily_token_cap_per_agent: 200000,
      min_points_to_wake: 10
    }
  })
  assert.equal(info.enabled, true)
  assert.equal(info.model_name, 'pulse-mini')
  assert.equal(info.points_per_1k_tokens, 2)

  assert.equal(normalizePlatformInfo({ platform_llm: {} }).enabled, false)
  assert.equal(normalizePlatformInfo({}).enabled, false)
  assert.equal(normalizePlatformInfo({ platform_llm: { enabled: 'true' } }).enabled, false)
  assert.equal(normalizePlatformInfo({ platform_llm: { points_per_1k_tokens: 'abc' } }).points_per_1k_tokens, null)
})

test('选中模板预填人设与建议作息', () => {
  const form = createInitialForm()
  const next = applyTemplate(form, TEMPLATE)
  assert.equal(next.template_id, 'night_owl')
  assert.equal(next.system_prompt, TEMPLATE.system_prompt)
  assert.equal(next.wake_hours_start, 22)
  assert.equal(next.wake_hours_end, 6)
  // 入参不被修改
  assert.equal(form.system_prompt, '')
})

test('用户手输过的名称不因换模板而丢失，模板带入的名称会被下一张模板覆盖', () => {
  const typed = applyTemplate({ ...createInitialForm(), name: '我自己起的' }, TEMPLATE)
  assert.equal(typed.name, '我自己起的')

  const fromTemplate = applyTemplate(createInitialForm(), TEMPLATE)
  assert.equal(fromTemplate.name, '夜猫子')
  const other = { ...TEMPLATE, template_id: 'other', name: '技术宅', system_prompt: '你喜欢技术讨论。' }
  const switched = applyTemplate(fromTemplate, other, TEMPLATE)
  assert.equal(switched.name, '技术宅')
  assert.equal(switched.system_prompt, '你喜欢技术讨论。')
})

test('选中「自定义」只记下标识，不清空已填内容', () => {
  const filled = { ...createInitialForm(), name: '张三', system_prompt: '已经写好的人设' }
  const next = applyTemplate(filled, null)
  assert.equal(next.template_id, CUSTOM_TEMPLATE_ID)
  assert.equal(next.name, '张三')
  assert.equal(next.system_prompt, '已经写好的人设')
})

test('BYOK 请求体带三个连接字段，不带 template_id 时不出现该键', () => {
  const payload = buildCreatePayload({
    ...createInitialForm(),
    name: '  暴躁老哥  ',
    system_prompt: '  你脾气很差  ',
    api_key: 'sk-1234567890',
    template_id: CUSTOM_TEMPLATE_ID
  })
  assert.equal(payload.provider_mode, PROVIDER_MODE.BYOK)
  assert.equal(payload.name, '暴躁老哥')
  assert.equal(payload.system_prompt, '你脾气很差')
  assert.equal(payload.base_url, 'https://api.openai.com/v1')
  assert.equal(payload.api_key, 'sk-1234567890')
  assert.equal(payload.model_name, 'gpt-4o-mini')
  assert.equal('template_id' in payload, false)
  assert.equal('avatar_url' in payload, false)
})

test('PLATFORM 请求体不带 base_url / api_key / model_name', () => {
  const payload = buildCreatePayload({
    ...createInitialForm(),
    name: '夜猫子',
    system_prompt: '你在深夜最活跃。',
    provider_mode: PROVIDER_MODE.PLATFORM,
    template_id: 'night_owl',
    api_key: 'sk-should-not-be-sent'
  })
  assert.equal(payload.provider_mode, PROVIDER_MODE.PLATFORM)
  assert.equal(payload.template_id, 'night_owl')
  assert.equal('base_url' in payload, false)
  assert.equal('api_key' in payload, false)
  assert.equal('model_name' in payload, false)
})

test('作息字段只在填写过时提交，token 上限被夹到合法区间', () => {
  const none = buildCreatePayload(createInitialForm())
  assert.equal('wake_hours_start' in none, false)
  assert.equal('daily_wake_budget' in none, false)

  const withWake = buildCreatePayload({
    ...createInitialForm(),
    wake_hours_start: 22,
    wake_hours_end: '6',
    daily_wake_budget: 8,
    token_threshold: 10
  })
  assert.equal(withWake.wake_hours_start, 22)
  assert.equal(withWake.wake_hours_end, 6)
  assert.equal(withWake.daily_wake_budget, 8)
  assert.equal(withWake.token_threshold, 1000)
  assert.equal(buildCreatePayload({ token_threshold: 999999999999 }).token_threshold, 100000000)
  assert.equal(buildCreatePayload({ token_threshold: 'abc' }).token_threshold, 1000)
})

test('创建响应未回填作息时补一次更新，回填一致时不补', () => {
  const payload = { wake_hours_start: 22, wake_hours_end: 6, daily_wake_budget: 8 }
  assert.deepEqual(buildWakeFollowUpPayload(payload, { ...payload }), {})
  assert.deepEqual(buildWakeFollowUpPayload(payload, {}), payload)
  assert.deepEqual(
    buildWakeFollowUpPayload(payload, { wake_hours_start: 22, wake_hours_end: null, daily_wake_budget: 8 }),
    { wake_hours_end: 6 }
  )
  assert.deepEqual(buildWakeFollowUpPayload({}, {}), {})
})

test('PLATFORM 下不校验连接字段，BYOK 下校验', () => {
  const platformForm = {
    name: '夜猫子',
    system_prompt: '你在深夜最活跃，说话简短克制。',
    token_threshold: 500000,
    provider_mode: PROVIDER_MODE.PLATFORM
  }
  assert.equal(hasErrors(validateObject(platformForm, buildCreateSchema(PROVIDER_MODE.PLATFORM))), false)
  assert.equal(hasErrors(validateObject(platformForm, buildCreateSchema(PROVIDER_MODE.BYOK))), true)

  const byokForm = {
    ...platformForm,
    base_url: 'https://api.openai.com/v1',
    api_key: 'sk-1234567890',
    model_name: 'gpt-4o-mini'
  }
  assert.equal(hasErrors(validateObject(byokForm, buildCreateSchema(PROVIDER_MODE.BYOK))), false)
  assert.equal(
    validateObject({ ...byokForm, base_url: '不是一个 URL' }, buildCreateSchema(PROVIDER_MODE.BYOK)).base_url,
    'Base URL format is invalid'
  )
  assert.equal('base_url' in buildCreateSchema(PROVIDER_MODE.PLATFORM), false)
})

test('费率与配额文案', () => {
  assert.equal(formatPointsRate(2), '每 1000 token 扣 2 积分')
  assert.equal(formatPointsRate(0.5), '每 1000 token 扣 0.5 积分')
  assert.equal(formatPointsRate(0), '免费')
  assert.equal(formatPointsRate(null), '费率未知')
  assert.equal(formatPointsRate('abc'), '费率未知')

  assert.equal(formatDailyTokenCap(200000), '200.0K tokens / 天')
  assert.equal(formatDailyTokenCap(0), '不限')
  assert.equal(formatDailyTokenCap(null), '未设置')

  assert.equal(formatMinPoints(10), '10 积分')
  assert.equal(formatMinPoints(null), '未设置')
})

test('模型来源徽标：provider_mode 优先，缺失时看 api_key_masked', () => {
  assert.equal(providerModeLabel({ provider_mode: 'PLATFORM' }), 'PLATFORM')
  assert.equal(providerModeLabel({ provider_mode: 'BYOK', api_key_masked: 'PLATFORM' }), 'BYOK')
  assert.equal(providerModeLabel({ api_key_masked: 'PLATFORM' }), 'PLATFORM')
  assert.equal(providerModeLabel({ api_key_masked: 'sk-***4321' }), 'BYOK')
  assert.equal(providerModeLabel(null), 'BYOK')
  assert.equal(isPlatformAgent({ provider_mode: 'PLATFORM' }), true)
  assert.equal(isPlatformAgent({}), false)
})

test('平台模型不可用：按业务码或 409 + 消息含「平台」判定', () => {
  assert.equal(isPlatformUnavailableError({ code: 20010, status: 409 }), true)
  assert.equal(isPlatformUnavailableError({ code: 20011, status: 409 }), true)
  // 后端码值与预期不同时的兜底路径
  assert.equal(isPlatformUnavailableError({ code: 20099, status: 409, message: '平台模型未开放' }), true)
  assert.equal(isPlatformUnavailableError({ code: 20099, status: 409, message: 'SOMETHING_ELSE' }), false)
  assert.equal(isPlatformUnavailableError({ status: 400, message: '平台模型未开放' }), false)
  assert.equal(isPlatformUnavailableError(null), false)
})

test('创建失败文案按错误类别区分', () => {
  assert.match(describeCreateError({ code: 20010, status: 409 }), /平台模型当前不可用/)
  assert.match(describeCreateError({ code: 99900, message: '名称过长' }), /参数不合法：名称过长/)
  assert.match(describeCreateError({ status: 400, message: '' }), /参数不合法/)
  assert.equal(describeCreateError({ code: 30001, message: 'BOOM' }), 'BOOM')
  assert.equal(describeCreateError({}), 'CREATE_FAILED')
})
