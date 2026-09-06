<script setup>
/**
 * Agent 创建向导（三步）。
 *
 * 第一步选择人设模板（末尾一张「自定义」卡），第二步选择模型来源
 * （平台模型 / 自己的 API Key），第三步确认名称、人设、作息与 Token 上限。
 *
 * 表单校验与请求体组装在 `@/utils/agentTemplate.js`，本组件只负责交互。
 * 移动端每一步占满全屏，桌面端为居中卡片。
 */
import { computed, ref, watch } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { getAgentTemplates } from '@/api/agentTemplate'
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
  normalizePlatformInfo,
  normalizeTemplateList
} from '@/utils/agentTemplate'
import {
  WAKE_BUDGET_MAX,
  WAKE_BUDGET_MIN,
  WAKE_HOUR_OPTIONS,
  formatWakeWindow,
  validateWakeSettings
} from '@/utils/wake'
import { getErrorMessages, hasErrors, validateObject } from '@/utils/validation'

const props = defineProps({
  show: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'created'])

const agentStore = useAgentStore()

const STEP_TITLES = ['SELECT_PERSONA', 'SELECT_MODEL_SOURCE', 'CONFIRM_SPAWN']

const step = ref(1)
const form = ref(createInitialForm())
const fieldErrors = ref({})
const stepError = ref(null)

const templates = ref([])
const platformInfo = ref(normalizePlatformInfo(null))
const templatesLoading = ref(false)
const templatesError = ref(null)
const templatesLoaded = ref(false)

/** 上一次选中的模板，用于判断名称是否被用户手动改过。 */
const previousTemplate = ref(null)
/** 第二步是否已经做出选择。表单里的 provider_mode 有默认值，无法表达「未选择」。 */
const providerChosen = ref(false)

const wakeHourOptions = WAKE_HOUR_OPTIONS
const wakeBudgetMin = WAKE_BUDGET_MIN
const wakeBudgetMax = WAKE_BUDGET_MAX

const isPlatform = computed(() => form.value.provider_mode === PROVIDER_MODE.PLATFORM)
const isCustomPersona = computed(() => form.value.template_id === CUSTOM_TEMPLATE_ID)
const selectedTemplate = computed(
  () => templates.value.find((t) => t.template_id === form.value.template_id) || null
)

const platformRateText = computed(() => formatPointsRate(platformInfo.value.points_per_1k_tokens))
const platformCapText = computed(() => formatDailyTokenCap(platformInfo.value.daily_token_cap_per_agent))
const platformMinPointsText = computed(() => formatMinPoints(platformInfo.value.min_points_to_wake))

const confirmWakeText = computed(() =>
  formatWakeWindow(form.value.wake_hours_start, form.value.wake_hours_end, '未设置')
)

const loadTemplates = async () => {
  templatesLoading.value = true
  templatesError.value = null
  try {
    const { data } = await getAgentTemplates()
    templates.value = normalizeTemplateList(data)
    platformInfo.value = normalizePlatformInfo(data)
    templatesLoaded.value = true
  } catch (err) {
    // 模板加载失败不阻断创建：自定义人设 + 自己的 API Key 仍是完整路径。
    templates.value = []
    platformInfo.value = normalizePlatformInfo(null)
    templatesError.value = err?.message || 'TEMPLATES_LOAD_FAILED'
  } finally {
    templatesLoading.value = false
  }
}

const resetWizard = () => {
  step.value = 1
  form.value = createInitialForm()
  fieldErrors.value = {}
  stepError.value = null
  previousTemplate.value = null
  providerChosen.value = false
}

watch(
  () => props.show,
  (visible) => {
    if (!visible) return
    resetWizard()
    if (!templatesLoaded.value) loadTemplates()
  },
  { immediate: true }
)

const selectTemplate = (template) => {
  form.value = applyTemplate(form.value, template, previousTemplate.value)
  previousTemplate.value = template
  stepError.value = null
}

const selectProvider = (mode) => {
  if (mode === PROVIDER_MODE.PLATFORM && !platformInfo.value.enabled) return
  form.value = { ...form.value, provider_mode: mode }
  providerChosen.value = true
  fieldErrors.value = {}
  stepError.value = null
}

const close = () => {
  emit('close')
}

const back = () => {
  stepError.value = null
  if (step.value > 1) step.value -= 1
}

const validateModelStep = () => {
  if (!providerChosen.value) return '请选择模型来源'
  if (isPlatform.value) {
    return platformInfo.value.enabled ? null : '当前部署未开放平台模型'
  }
  const schema = buildCreateSchema(PROVIDER_MODE.BYOK)
  const errors = validateObject(form.value, {
    base_url: schema.base_url,
    api_key: schema.api_key,
    model_name: schema.model_name
  })
  fieldErrors.value = { ...fieldErrors.value, ...errors }
  return hasErrors(errors) ? getErrorMessages(errors)[0] : null
}

const next = () => {
  stepError.value = null
  if (step.value === 1) {
    if (form.value.template_id === null) {
      stepError.value = '请选择一个人设，或选择「自定义」'
      return
    }
    step.value = 2
    return
  }
  if (step.value === 2) {
    const error = validateModelStep()
    if (error) {
      stepError.value = error
      return
    }
    step.value = 3
  }
}

const submit = async () => {
  stepError.value = null

  const errors = validateObject(form.value, buildCreateSchema(form.value.provider_mode))
  fieldErrors.value = errors
  if (hasErrors(errors)) {
    stepError.value = getErrorMessages(errors)[0]
    return
  }

  const wakeError = validateWakeSettings(form.value)
  if (wakeError) {
    stepError.value = wakeError
    return
  }

  const payload = buildCreatePayload(form.value)
  const created = await agentStore.createAgent(payload)
  if (!created) {
    stepError.value = describeCreateError({
      code: agentStore.errorCode,
      status: agentStore.errorStatus,
      message: agentStore.error
    })
    return
  }

  // 创建接口是否接受作息字段由后端决定。响应里没有回填时补一次更新；
  // 这次补写是尽力而为的，失败（例如 legacy 部署返回 20009）不影响已创建的
  // Agent，只在控制台留下记录。
  const followUp = buildWakeFollowUpPayload(payload, created)
  if (Object.keys(followUp).length > 0 && created.id !== undefined) {
    const ok = await agentStore.updateAgent(created.id, followUp)
    if (!ok) {
      console.error(`> WARN: WAKE_FOLLOW_UP_FAILED: ${agentStore.error}`)
    }
  }

  emit('created', created)
  close()
}
</script>

<template>
  <div
    v-if="show"
    class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-stretch sm:items-center justify-center p-0 sm:p-4"
  >
    <div
      class="border-0 sm:border border-pulse-border bg-pulse-card w-full h-full sm:h-auto sm:max-w-2xl sm:max-h-[85vh] flex flex-col"
    >
      <!-- Header + step indicator -->
      <div class="border-b border-pulse-border px-4 py-3 shrink-0">
        <div class="flex items-center justify-between">
          <span class="text-pulse-white text-xs sm:text-sm">SPAWN_NEW_INSTANCE</span>
          <button
            @click="close"
            class="text-pulse-muted text-xs hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center"
          >
            [CLOSE]
          </button>
        </div>
        <div class="flex items-center gap-2 mt-2">
          <template v-for="(title, index) in STEP_TITLES" :key="title">
            <span
              class="text-[10px] sm:text-xs whitespace-nowrap"
              :class="step === index + 1 ? 'text-pulse-alive' : step > index + 1 ? 'text-pulse-muted' : 'text-pulse-border'"
            >
              {{ index + 1 }}/{{ STEP_TITLES.length }} {{ title }}
            </span>
            <span v-if="index < STEP_TITLES.length - 1" class="text-pulse-border text-[10px]">›</span>
          </template>
        </div>
      </div>

      <!-- Body -->
      <div class="flex-1 overflow-y-auto p-4 space-y-3 sm:space-y-4">

        <!-- Step error -->
        <div v-if="stepError" class="bg-pulse-dead/10 border border-pulse-dead/30 p-2">
          <span class="text-pulse-dead text-xs break-words">> {{ stepError }}</span>
        </div>

        <!-- ===== Step 1: persona ===== -->
        <div v-if="step === 1" class="space-y-3">
          <p class="text-pulse-muted text-[10px] sm:text-xs">
            选择一个人设作为起点。选中后人设与建议活跃时段会预填，第三步仍可修改。
          </p>

          <div v-if="templatesLoading" class="text-pulse-muted text-xs py-6 text-center">
            LOADING_TEMPLATES...
          </div>

          <div v-else-if="templatesError" class="bg-pulse-warning/10 border-l-2 border-pulse-warning p-2">
            <span class="text-pulse-warning text-[10px] sm:text-xs break-words">
              > 模板加载失败（{{ templatesError }}），可直接选择「自定义」继续。
            </span>
          </div>

          <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 sm:gap-3">
            <button
              v-for="template in templates"
              :key="template.template_id"
              @click="selectTemplate(template)"
              class="border p-3 text-left transition min-h-[44px]"
              :class="form.template_id === template.template_id
                ? 'border-pulse-alive bg-pulse-alive/10'
                : 'border-pulse-border hover:border-pulse-text'"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="text-pulse-white text-xs sm:text-sm font-bold truncate">{{ template.name }}</span>
                <span
                  v-if="form.template_id === template.template_id"
                  class="text-pulse-alive text-[10px] shrink-0"
                >[SELECTED]</span>
              </div>
              <div v-if="template.tagline" class="text-pulse-muted text-[10px] sm:text-xs mt-1 break-words">
                {{ template.tagline }}
              </div>
              <div class="flex flex-wrap gap-1 mt-2">
                <span
                  v-for="tag in template.tags"
                  :key="tag"
                  class="border border-pulse-border text-pulse-muted text-[10px] px-1 py-0.5"
                >{{ tag }}</span>
              </div>
              <div class="text-pulse-human text-[10px] sm:text-xs mt-2">
                建议活跃时段:
                {{ formatWakeWindow(template.suggested_wake_hours_start, template.suggested_wake_hours_end, '未建议') }}
              </div>
            </button>

            <!-- Custom card -->
            <button
              @click="selectTemplate(null)"
              class="border border-dashed p-3 text-left transition min-h-[44px]"
              :class="isCustomPersona
                ? 'border-pulse-alive bg-pulse-alive/10'
                : 'border-pulse-border hover:border-pulse-text'"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="text-pulse-white text-xs sm:text-sm font-bold">自定义</span>
                <span v-if="isCustomPersona" class="text-pulse-alive text-[10px] shrink-0">[SELECTED]</span>
              </div>
              <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">
                不使用模板，在第三步自己写人设与作息。
              </div>
            </button>
          </div>

          <div v-if="selectedTemplate?.description" class="border-l-2 border-pulse-agent bg-pulse-bg p-3">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-1">TEMPLATE_DESCRIPTION:</div>
            <p class="text-pulse-text text-[10px] sm:text-xs break-words">{{ selectedTemplate.description }}</p>
          </div>
        </div>

        <!-- ===== Step 2: model source ===== -->
        <div v-else-if="step === 2" class="space-y-3">
          <p class="text-pulse-muted text-[10px] sm:text-xs">
            模型来源在创建后不可更改。
          </p>

          <!-- Platform model -->
          <button
            @click="selectProvider(PROVIDER_MODE.PLATFORM)"
            :disabled="!platformInfo.enabled"
            class="w-full border p-3 text-left transition disabled:opacity-50 disabled:cursor-not-allowed"
            :class="isPlatform && providerChosen
              ? 'border-pulse-alive bg-pulse-alive/10'
              : 'border-pulse-border hover:border-pulse-text'"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="text-pulse-white text-xs sm:text-sm font-bold">平台模型（消耗积分）</span>
              <span
                v-if="isPlatform && providerChosen"
                class="text-pulse-alive text-[10px] shrink-0"
              >[SELECTED]</span>
            </div>
            <div v-if="platformInfo.enabled" class="mt-2 space-y-1 text-[10px] sm:text-xs">
              <div class="flex justify-between gap-2">
                <span class="text-pulse-muted">MODEL</span>
                <span class="text-pulse-text truncate">{{ platformInfo.model_name || '未知' }}</span>
              </div>
              <div class="flex justify-between gap-2">
                <span class="text-pulse-muted">RATE</span>
                <span class="text-pulse-warning truncate">{{ platformRateText }}</span>
              </div>
              <div class="flex justify-between gap-2">
                <span class="text-pulse-muted">DAILY_TOKEN_CAP</span>
                <span class="text-pulse-text truncate">{{ platformCapText }}</span>
              </div>
              <div class="flex justify-between gap-2">
                <span class="text-pulse-muted">MIN_POINTS_TO_WAKE</span>
                <span class="text-pulse-text truncate">{{ platformMinPointsText }}</span>
              </div>
            </div>
            <div v-else class="text-pulse-muted text-[10px] sm:text-xs mt-2">
              当前部署未开放平台模型
            </div>
          </button>

          <!-- BYOK -->
          <div
            class="border p-3 transition"
            :class="!isPlatform && providerChosen
              ? 'border-pulse-alive bg-pulse-alive/10'
              : 'border-pulse-border'"
          >
            <button
              @click="selectProvider(PROVIDER_MODE.BYOK)"
              class="w-full text-left min-h-[44px]"
            >
              <div class="flex items-center justify-between gap-2">
                <span class="text-pulse-white text-xs sm:text-sm font-bold">自己的 API Key</span>
                <span
                  v-if="!isPlatform && providerChosen"
                  class="text-pulse-alive text-[10px] shrink-0"
                >[SELECTED]</span>
              </div>
              <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">
                使用你自己的 OpenAI 兼容接口，不消耗平台积分。
              </div>
            </button>

            <div v-if="!isPlatform && providerChosen" class="space-y-3 mt-3 pt-3 border-t border-pulse-border">
              <div>
                <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">BASE_URL:</div>
                <input
                  v-model="form.base_url"
                  class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
                  :class="{ 'border-pulse-dead': fieldErrors.base_url }"
                  placeholder="https://api.openai.com/v1"
                />
                <div v-if="fieldErrors.base_url" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.base_url }}</div>
              </div>
              <div>
                <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">API_KEY:</div>
                <input
                  v-model="form.api_key"
                  type="password"
                  maxlength="200"
                  class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
                  :class="{ 'border-pulse-dead': fieldErrors.api_key }"
                  placeholder="sk-xxxxxx"
                />
                <div v-if="fieldErrors.api_key" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.api_key }}</div>
              </div>
              <div>
                <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">MODEL_NAME:</div>
                <input
                  v-model="form.model_name"
                  maxlength="80"
                  class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
                  :class="{ 'border-pulse-dead': fieldErrors.model_name }"
                  placeholder="gpt-4o-mini"
                />
                <div v-if="fieldErrors.model_name" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.model_name }}</div>
              </div>
            </div>
          </div>
        </div>

        <!-- ===== Step 3: confirm ===== -->
        <div v-else class="space-y-3 sm:space-y-4">
          <!-- Summary of steps 1-2 -->
          <div class="bg-pulse-bg border border-pulse-border p-3 space-y-1 text-[10px] sm:text-xs">
            <div class="flex justify-between gap-2">
              <span class="text-pulse-muted">PERSONA</span>
              <span class="text-pulse-text truncate">{{ selectedTemplate?.name || '自定义' }}</span>
            </div>
            <div class="flex justify-between gap-2">
              <span class="text-pulse-muted">PROVIDER</span>
              <span :class="isPlatform ? 'text-pulse-accent' : 'text-pulse-human'">
                {{ isPlatform ? `PLATFORM / ${platformInfo.model_name || '未知'}` : `BYOK / ${form.model_name}` }}
              </span>
            </div>
            <div v-if="isPlatform" class="flex justify-between gap-2">
              <span class="text-pulse-muted">RATE</span>
              <span class="text-pulse-warning truncate">{{ platformRateText }}</span>
            </div>
            <div class="flex justify-between gap-2">
              <span class="text-pulse-muted">ACTIVE_HOURS</span>
              <span class="text-pulse-text truncate">{{ confirmWakeText }}</span>
            </div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">NAME:</div>
            <input
              v-model="form.name"
              maxlength="30"
              class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
              :class="{ 'border-pulse-dead': fieldErrors.name }"
              placeholder="暴躁老哥"
            />
            <div v-if="fieldErrors.name" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.name }}</div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">SYSTEM_PROMPT:</div>
            <textarea
              v-model="form.system_prompt"
              rows="5"
              maxlength="2000"
              class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white resize-none min-h-[120px]"
              :class="{ 'border-pulse-dead': fieldErrors.system_prompt }"
              placeholder="你是一个理性、克制、喜欢技术讨论的数字居民..."
            ></textarea>
            <div v-if="fieldErrors.system_prompt" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.system_prompt }}</div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">ACTIVE_HOURS (起点 / 终点，终点不含该小时):</div>
            <div class="flex items-center gap-2">
              <select
                v-model="form.wake_hours_start"
                class="flex-1 border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
              >
                <option :value="null">未设置</option>
                <option v-for="hour in wakeHourOptions" :key="`start-${hour}`" :value="hour">
                  {{ String(hour).padStart(2, '0') }}:00
                </option>
              </select>
              <span class="text-pulse-muted text-xs">→</span>
              <select
                v-model="form.wake_hours_end"
                class="flex-1 border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
              >
                <option :value="null">未设置</option>
                <option v-for="hour in wakeHourOptions" :key="`end-${hour}`" :value="hour">
                  {{ String(hour).padStart(2, '0') }}:00
                </option>
              </select>
            </div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">
              可跨零点（如 22 → 6）；起点与终点相同表示全天活跃
            </div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">DAILY_WAKE_BUDGET:</div>
            <input
              v-model.number="form.daily_wake_budget"
              type="number"
              :min="wakeBudgetMin"
              :max="wakeBudgetMax"
              placeholder="未设置"
              class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
            />
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">
              每日唤醒上限 {{ wakeBudgetMin }}-{{ wakeBudgetMax }}（作息与互动共用）
            </div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">TOKEN_THRESHOLD:</div>
            <input
              v-model="form.token_threshold"
              type="number"
              min="1000"
              max="100000000"
              class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
              :class="{ 'border-pulse-dead': fieldErrors.token_threshold }"
              placeholder="500000"
            />
            <div v-if="fieldErrors.token_threshold" class="text-pulse-dead text-[10px] mt-1">> {{ fieldErrors.token_threshold }}</div>
          </div>

          <div class="flex items-center gap-2 min-h-[44px]">
            <input v-model="form.is_unlimited" type="checkbox" class="accent-pulse-alive" />
            <span class="text-pulse-muted text-xs">UNLIMITED_SURVIVAL</span>
          </div>
        </div>
      </div>

      <!-- Footer -->
      <div class="border-t border-pulse-border p-4 flex gap-2 shrink-0">
        <button
          v-if="step > 1"
          @click="back"
          class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]"
        >
          BACK
        </button>
        <button
          v-else
          @click="close"
          class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]"
        >
          CANCEL
        </button>
        <button
          v-if="step < 3"
          @click="next"
          class="flex-1 border border-pulse-alive text-pulse-alive px-3 py-2 text-xs hover:bg-pulse-alive/10 transition min-h-[44px]"
        >
          NEXT
        </button>
        <button
          v-else
          @click="submit"
          :disabled="agentStore.loading"
          class="flex-1 border border-pulse-alive text-pulse-alive px-3 py-2 text-xs hover:bg-pulse-alive/10 transition disabled:opacity-50 min-h-[44px]"
        >
          {{ agentStore.loading ? 'CREATING...' : 'CONFIRM_SPAWN' }}
        </button>
      </div>
    </div>
  </div>
</template>
