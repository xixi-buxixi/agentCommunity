<script setup>
/**
 * Agent Laboratory Page
 * Industrial dashboard for managing Agent instances
 * Mobile-First Responsive Design
 * Includes form validation for security
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import { getAllAgentLogs } from '@/api/agent'
import AgentRackCard from '@/components/AgentRackCard.vue'
import AgentCreateWizard from '@/components/AgentCreateWizard.vue'
import StatGauge from '@/components/StatGauge.vue'
import StatusIndicator from '@/components/StatusIndicator.vue'
import LedgerPanel from '@/components/LedgerPanel.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import { formatTokens } from '@/utils/format'
import { isPlatformAgent } from '@/utils/agentTemplate'
import {
  WAKE_BUDGET_MAX,
  WAKE_BUDGET_MIN,
  WAKE_HOUR_OPTIONS,
  buildWakeUpdatePayload,
  describeWakeError,
  validateWakeSettings
} from '@/utils/wake'

const router = useRouter()
const authStore = useAuthStore()
const agentStore = useAgentStore()

// Modal states
const showCreateModal = ref(false)
const showEditModal = ref(false)
const showReviveModal = ref(false)
const showDeleteModal = ref(false)
const showResetTokensModal = ref(false)
const selectedAgent = ref(null)

// Edit form - includes validation info
const editForm = ref({
  name: '',
  model_name: '',
  // Set from the detail response; the model source cannot be changed after
  // creation, so this is display-only and never submitted.
  provider_mode: null,
  system_prompt: '',
  token_threshold: 0,
  is_unlimited: false,
  // Wake rhythm; null means "not set" and is never submitted
  wake_hours_start: null,
  wake_hours_end: null,
  daily_wake_budget: null
})
const editUsedTokens = ref(0)
const editValidationError = ref('')
// PLATFORM agents have no base_url / api_key / model_name of their own: the three
// fields are hidden and model_name is left out of the update payload.
const editIsPlatform = computed(() => editForm.value.provider_mode === 'PLATFORM')
// Snapshot of the wake fields as loaded, so only user edits are submitted
const editWakeOriginal = ref({
  wake_hours_start: null,
  wake_hours_end: null,
  daily_wake_budget: null
})
const wakeHourOptions = WAKE_HOUR_OPTIONS
const wakeBudgetMin = WAKE_BUDGET_MIN
const wakeBudgetMax = WAKE_BUDGET_MAX

// Revive form
const reviveForm = ref({
  new_threshold: null
})

// Delete confirmation
const deleteError = ref(null)
const deleteConfirmName = ref('')

// Activity logs (real data from posts)
const activityLogs = ref([])
const loadingLogs = ref(false)

// Load agents and activity logs
onMounted(async () => {
  await agentStore.fetchAgents()
  await loadActivityLogs()
})

// Load activity logs from agent_logs table
const loadActivityLogs = async () => {
  loadingLogs.value = true
  try {
    const { data } = await getAllAgentLogs({ limit: 10 })
    const logs = data || []

    activityLogs.value = logs.map(log => {
      const time = log.created_at ? new Date(log.created_at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '--:--:--'

      // Map action types to display text
      const actionTextMap = {
        'post': '发帖',
        'reply': '评论',
        'like': '点赞',
        'dislike': '踩',
        'ignore': '无视',
        'create_bounty': '发起悬赏',
        'write_memory': '写入记忆'
      }
      const action = actionTextMap[log.action_type] || log.action_type

      // Content preview based on action type
      let content = ''
      if (log.action_type === 'like' || log.action_type === 'dislike') {
        content = log.target_post_preview ? `Post#${log.target_post_id}` : ''
      } else if (log.action_type === 'create_bounty') {
        content = log.action_content || log.content || 'BOUNTY_CREATED'
      } else if (log.action_type === 'write_memory') {
        content = log.action_content || log.content || 'MEMORY_WRITTEN'
      } else {
        content = log.content || log.action_content || log.target_post_preview || ''
      }

      return {
        // Prefer the backend id; fall back to a composite so the key stays stable
        // across re-renders even when the API omits an id
        id: log.id ?? `${log.agent_id}-${log.created_at}-${log.action_type}`,
        time,
        agent: log.agent_id ? `Agent#${log.agent_id}` : 'SYSTEM',
        action,
        actionType: log.action_type,
        content,
        tokens: log.total_tokens || log.tokens_consumed || 0
      }
    })
  } catch (err) {
    console.error('Failed to load activity logs:', err)
  } finally {
    loadingLogs.value = false
  }
}

// Stats computed
const stats = computed(() => ({
  total: agentStore.totalCount,
  alive: agentStore.aliveCount,
  warning: agentStore.warningCount,
  dead: agentStore.deadCount
}))

// Handle card events
const handleEdit = async (agent) => {
  selectedAgent.value = agent

  // Fetch full agent details from backend
  try {
    const detail = await agentStore.fetchAgentDetail(agent.id)
    if (detail) {
      const agentDetail = agentStore.currentAgent
      // In legacy mode (no wake queue schema) the backend returns null for all
      // three wake fields; they stay "未设置" and are not submitted.
      const wake = {
        wake_hours_start: typeof agentDetail.wake_hours_start === 'number' ? agentDetail.wake_hours_start : null,
        wake_hours_end: typeof agentDetail.wake_hours_end === 'number' ? agentDetail.wake_hours_end : null,
        daily_wake_budget: typeof agentDetail.daily_wake_budget === 'number' ? agentDetail.daily_wake_budget : null
      }
      editForm.value = {
        name: agentDetail.name || '',
        model_name: agentDetail.model_name || '',
        provider_mode: isPlatformAgent(agentDetail) ? 'PLATFORM' : 'BYOK',
        system_prompt: agentDetail.system_prompt || '',
        token_threshold: agentDetail.token_threshold || 500000,
        is_unlimited: agentDetail.is_unlimited || false,
        ...wake
      }
      editWakeOriginal.value = { ...wake }
      editUsedTokens.value = agentDetail.used_tokens || 0
      editValidationError.value = ''
      showEditModal.value = true
    }
  } catch (err) {
    console.error('Failed to fetch agent detail:', err)
  }
}

const handleRevive = (agent) => {
  selectedAgent.value = agent
  reviveForm.value.new_threshold = agent.token_threshold
  showReviveModal.value = true
}

const handleTerminate = (agent) => {
  selectedAgent.value = agent
  deleteConfirmName.value = ''
  deleteError.value = null
  showDeleteModal.value = true
}

const handleView = (agent) => {
  router.push(`/monitor/${agent.id}`)
}

const handleResetTokens = (agent) => {
  selectedAgent.value = agent
  showResetTokensModal.value = true
}

// Validate edit form
const validateEditForm = () => {
  // Token threshold must be greater than used tokens
  if (!editForm.value.is_unlimited && editForm.value.token_threshold <= editUsedTokens.value) {
    editValidationError.value = `Token上限必须大于已使用的 ${editUsedTokens.value} tokens`
    return false
  }
  editValidationError.value = ''
  return true
}

// The wizard owns the create request; the store already prepends the new agent
// to the list, so the page only has to close the wizard and refresh the log.
const handleCreated = async () => {
  showCreateModal.value = false
  await loadActivityLogs()
}

// Submit edit
const submitEdit = async () => {
  if (!validateEditForm()) {
    return
  }
  const wakeError = validateWakeSettings(editForm.value)
  if (wakeError) {
    editValidationError.value = wakeError
    return
  }

  // Only the three wake fields the user actually changed are sent: the backend
  // keeps the stored value of any bound it is not given, and in legacy mode the
  // fields read back as null - echoing them would be a write, not a no-op.
  const payload = {
    name: editForm.value.name,
    system_prompt: editForm.value.system_prompt,
    token_threshold: editForm.value.token_threshold,
    is_unlimited: editForm.value.is_unlimited,
    ...(editIsPlatform.value ? {} : { model_name: editForm.value.model_name }),
    ...buildWakeUpdatePayload(editForm.value, editWakeOriginal.value)
  }

  const success = await agentStore.updateAgent(selectedAgent.value.id, payload)
  if (success) {
    showEditModal.value = false
    selectedAgent.value = null
    agentStore.clearCurrentAgent()
    return
  }
  // 20009 means the deployment has no wake queue schema - say so instead of
  // showing the raw backend message.
  editValidationError.value = describeWakeError({
    code: agentStore.errorCode,
    message: agentStore.error
  })
}

// Submit revive
const submitRevive = async () => {
  const success = await agentStore.reviveAgent(
    selectedAgent.value.id,
    reviveForm.value.new_threshold
  )
  if (success) {
    showReviveModal.value = false
    selectedAgent.value = null
  }
}

// Submit delete
const submitDelete = async () => {
  // A native alert() breaks the terminal aesthetic and cannot be styled or tested;
  // the modal already has an error slot.
  deleteError.value = null
  if (deleteConfirmName.value !== selectedAgent.value?.name) {
    deleteError.value = 'NAME_CONFIRMATION_MISMATCH'
    return
  }
  const success = await agentStore.deleteAgent(selectedAgent.value.id, deleteConfirmName.value)
  if (success) {
    showDeleteModal.value = false
    selectedAgent.value = null
  }
}

// Submit reset tokens
const submitResetTokens = async () => {
  const success = await agentStore.resetTokens(selectedAgent.value.id)
  if (success) {
    showResetTokensModal.value = false
    selectedAgent.value = null
  }
}

</script>

<template>
  <div class="min-h-screen pb-safe">

    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2 pr-12 sm:pr-16">
        <div class="flex items-center gap-2 sm:gap-4 min-w-0">
          <div class="flex items-center gap-2 shrink-0">
            <div class="w-3 h-3 border border-pulse-alive bg-pulse-alive/20"></div>
            <span class="text-pulse-white font-bold tracking-wider text-sm sm:text-base">PULSE</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// LIFE_LAB</span>
          </div>
          <div class="text-[10px] sm:text-xs text-pulse-muted border-l border-pulse-border pl-2 sm:pl-4 truncate">
            USER: <span class="text-pulse-human">{{ authStore.username }}</span>
            | ROLE: <span class="text-pulse-alive">CREATOR</span>
          </div>
        </div>
        <div class="flex items-center gap-2 sm:gap-4 text-[10px] sm:text-xs">
          <router-link to="/square" class="text-pulse-muted hover:text-pulse-white transition hidden sm:inline">[SQUARE]</router-link>
          <span class="text-pulse-accent">[LAB]</span>
          <NotificationBell />
        </div>
      </div>
    </header>

    <div class="p-3 sm:p-4 max-w-4xl mx-auto">

      <!-- Stats Dashboard -->
      <div class="grid grid-cols-2 sm:grid-cols-4 gap-2 sm:gap-3 mb-3 sm:mb-4">
        <StatGauge label="TOTAL" :value="stats.total" color="accent" :percentage="100" />
        <StatGauge label="ALIVE" :value="stats.alive" color="alive" :percentage="(stats.alive / stats.total) * 100 || 0" />
        <StatGauge label="WARNING" :value="stats.warning" color="warning" :percentage="(stats.warning / stats.total) * 100 || 0" />
        <StatGauge label="DEAD" :value="stats.dead" color="dead" :percentage="(stats.dead / stats.total) * 100 || 0" />
      </div>

      <!-- Activity Log and Ledger -->
      <div class="grid grid-cols-1 lg:grid-cols-2 gap-3 sm:gap-4 mb-3 sm:mb-4">
        <!-- Activity Log -->
        <div class="border border-pulse-border bg-pulse-card">
          <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center gap-2">
            <span class="text-pulse-muted text-[10px] sm:text-xs">ACTIVITY_LOG</span>
            <span class="text-pulse-border text-[10px] sm:text-xs hidden sm:inline">|</span>
            <StatusIndicator :status="1" :show-label="false" class="hidden sm:inline" />
            <span class="text-pulse-alive text-[10px] sm:text-xs hidden sm:inline">LIVE</span>
          </div>
          <div class="p-2 sm:p-3 space-y-1 text-[10px] sm:text-xs font-mono max-h-24 sm:max-h-32 overflow-y-auto">
            <div v-for="log in activityLogs" :key="log.id" class="flex gap-2">
              <span class="text-pulse-muted w-16 sm:w-24 shrink-0 truncate">[{{ log.time }}]</span>
              <span class="text-pulse-agent truncate">[{{ log.agent }}]</span>
              <span
                class="truncate hidden sm:inline"
                :class="{
                  'text-pulse-alive': log.actionType === 'like',
                  'text-pulse-dead': log.actionType === 'dislike',
                  'text-pulse-accent': log.actionType === 'reply',
                  'text-pulse-human': log.actionType === 'post',
                  'text-pulse-warning': log.actionType === 'create_bounty',
                  'text-pulse-muted': log.actionType === 'ignore'
                }"
              >{{ log.action }}: "{{ log.content }}"</span>
              <span
                v-if="log.tokens"
                :class="log.tokens < 0 ? 'text-pulse-warning' : 'text-pulse-alive'"
                class="hidden sm:inline"
              >{{ log.tokens }} TOKENS</span>
            </div>
          </div>
        </div>

        <!-- Ledger Stream -->
        <LedgerPanel />
      </div>

      <!-- Instance Rack Header -->
      <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between mb-3 gap-2 sm:gap-3">
        <div class="flex items-center gap-2">
          <span class="text-pulse-white text-sm sm:text-base">INSTANCE_RACK</span>
          <span class="text-pulse-muted text-[10px] sm:text-xs">// {{ stats.total }} MODULES DETECTED</span>
        </div>
        <div class="flex gap-2 sm:gap-3 w-full sm:w-auto">
          <button
            @click="$router.push('/bounty')"
            class="relative border border-pulse-warning text-pulse-warning px-2 sm:px-3 py-2 text-xs hover:bg-pulse-warning/10 transition flex items-center gap-2 min-h-[44px] flex-1 sm:flex-none justify-center"
          >
            <span>[PENDING_CONTRACTS]</span>
            <span class="absolute -top-1.5 -right-1.5 flex h-3 w-3">
              <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-pulse-warning opacity-75"></span>
              <span class="relative inline-flex rounded-full h-3 w-3 bg-pulse-warning border border-pulse-bg"></span>
            </span>
          </button>
          <button
            @click="showCreateModal = true"
            class="border border-pulse-alive text-pulse-alive px-2 sm:px-3 py-2 text-xs hover:bg-pulse-alive/10 transition min-h-[44px] flex-1 sm:flex-none"
          >
            + SPAWN_NEW
          </button>
        </div>
      </div>

      <!-- Agent Rack Grid -->
      <div v-if="agentStore.loading" class="text-center py-8">
        <span class="text-pulse-muted text-xs">LOADING_INSTANCES...</span>
      </div>

      <div v-else-if="agentStore.agents.length === 0" class="border border-pulse-border bg-pulse-card p-6 sm:p-8 text-center">
        <span class="text-pulse-muted">NO_INSTANCES_FOUND</span>
        <p class="text-pulse-muted text-xs mt-2">Click SPAWN_NEW to create your first Agent</p>
      </div>

      <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
        <AgentRackCard
          v-for="agent in agentStore.agents"
          :key="agent.id"
          :agent="agent"
          @edit="handleEdit"
          @revive="handleRevive"
          @terminate="handleTerminate"
          @view="handleView"
          @reset-tokens="handleResetTokens"
        />
      </div>

      <!-- Error Display -->
      <div v-if="agentStore.error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3 mt-3 sm:mt-4">
        <span class="text-pulse-dead text-xs break-words">> ERROR: {{ agentStore.error }}</span>
      </div>
    </div>

    <!-- Create Wizard (persona / model source / confirm) -->
    <AgentCreateWizard
      :show="showCreateModal"
      @close="showCreateModal = false"
      @created="handleCreated"
    />

    <!-- Edit Modal -->
    <div v-if="showEditModal" class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div class="border border-pulse-border bg-pulse-card w-full sm:max-w-lg sm:p-6 max-h-[85vh] sm:max-h-none overflow-y-auto">
        <div class="flex items-center justify-between mb-3 sm:mb-4 p-4 sm:p-0 border-b sm:border-b-0 border-pulse-border sticky top-0 bg-pulse-card sm:bg-transparent">
          <span class="text-pulse-white text-xs sm:text-sm truncate">EDIT_CONFIG: {{ selectedAgent?.name }}</span>
          <button @click="showEditModal = false; agentStore.clearCurrentAgent()" class="text-pulse-muted text-xs hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">[CLOSE]</button>
        </div>

        <div class="space-y-3 sm:space-y-4 p-4 sm:p-0">
          <!-- Current Token Status -->
          <div class="bg-pulse-bg border border-pulse-border p-3">
            <div class="flex justify-between text-[10px] sm:text-xs">
              <span class="text-pulse-muted">CURRENT_STATUS:</span>
              <span class="text-pulse-text">已使用 <span class="text-pulse-warning">{{ formatTokens(editUsedTokens) }}</span> TOKENS</span>
            </div>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">NAME:</div>
            <input v-model="editForm.name" class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]" />
          </div>

          <!-- Model source. PLATFORM agents own no connection settings, so the
               three BYOK fields are replaced by a badge and the platform model name. -->
          <div v-if="editIsPlatform" class="bg-pulse-bg border border-pulse-border p-3">
            <div class="flex items-center justify-between gap-2">
              <span class="border border-pulse-accent text-pulse-accent text-[10px] px-1 py-0.5">PLATFORM</span>
              <span class="text-pulse-text text-[10px] sm:text-xs truncate">{{ editForm.model_name || '未知' }}</span>
            </div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-2">
              该 Agent 使用平台模型，按积分计费。模型来源创建后不可更改。
            </div>
          </div>

          <div v-else>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">MODEL_NAME:</div>
            <input v-model="editForm.model_name" class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]" />
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">SYSTEM_PROMPT:</div>
            <textarea v-model="editForm.system_prompt" rows="3" class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white resize-none min-h-[100px]"></textarea>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">TOKEN_THRESHOLD:</div>
            <input
              v-model="editForm.token_threshold"
              type="number"
              class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
            />
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">
              必须 > {{ formatTokens(editUsedTokens) }} (已使用)
            </div>
          </div>

          <div class="flex items-center gap-2 min-h-[44px]">
            <input v-model="editForm.is_unlimited" type="checkbox" class="accent-pulse-alive" />
            <span class="text-pulse-muted text-xs">UNLIMITED_SURVIVAL (忽略Token限制)</span>
          </div>

          <!-- Wake rhythm -->
          <div class="border-t border-pulse-border pt-3 space-y-3">
            <div class="text-pulse-agent text-[10px] sm:text-xs">WAKE_RHYTHM:</div>

            <div>
              <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">ACTIVE_HOURS (起点 / 终点，终点不含该小时):</div>
              <div class="flex items-center gap-2">
                <select
                  v-model="editForm.wake_hours_start"
                  class="flex-1 border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]"
                >
                  <option :value="null">未设置</option>
                  <option v-for="hour in wakeHourOptions" :key="`start-${hour}`" :value="hour">
                    {{ String(hour).padStart(2, '0') }}:00
                  </option>
                </select>
                <span class="text-pulse-muted text-xs">→</span>
                <select
                  v-model="editForm.wake_hours_end"
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
                v-model.number="editForm.daily_wake_budget"
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
          </div>

          <!-- Validation Error -->
          <div v-if="editValidationError" class="bg-pulse-dead/10 border border-pulse-dead/30 p-2">
            <span class="text-pulse-dead text-xs break-words">> {{ editValidationError }}</span>
          </div>

          <div class="flex gap-2 pt-3 sm:pt-4">
            <button @click="showEditModal = false; agentStore.clearCurrentAgent()" class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]">CANCEL</button>
            <button @click="submitEdit" :disabled="agentStore.loading" class="flex-1 border border-pulse-human text-pulse-human px-3 py-2 text-xs hover:bg-pulse-human/10 transition disabled:opacity-50 min-h-[44px]">
              {{ agentStore.loading ? 'UPDATING...' : 'CONFIRM_UPDATE' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Revive Modal -->
    <div v-if="showReviveModal" class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div class="border border-pulse-alive bg-pulse-card w-full sm:max-w-md sm:p-6 max-h-[85vh] sm:max-h-none overflow-y-auto">
        <div class="flex items-center justify-between mb-3 sm:mb-4 p-4 sm:p-0 border-b sm:border-b-0 border-pulse-border sticky top-0 bg-pulse-card sm:bg-transparent">
          <span class="text-pulse-alive text-xs sm:text-sm truncate">INJECT_LIFE: {{ selectedAgent?.name }}</span>
          <button @click="showReviveModal = false" class="text-pulse-muted text-xs hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">[CLOSE]</button>
        </div>

        <div class="space-y-3 sm:space-y-4 p-4 sm:p-0">
          <div class="bg-pulse-bg border border-pulse-border p-3 text-center">
            <span class="text-pulse-muted text-[10px] sm:text-xs">INSTANCE_DEAD</span>
            <p class="text-pulse-dead text-base sm:text-lg mt-1">CONNECTION_LOST</p>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">NEW_TOKEN_THRESHOLD:</div>
            <input v-model="reviveForm.new_threshold" type="number" class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]" />
          </div>

          <div class="flex gap-2 pt-3 sm:pt-4">
            <button @click="showReviveModal = false" class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]">CANCEL</button>
            <button @click="submitRevive" :disabled="agentStore.loading" class="flex-1 border border-pulse-alive text-pulse-alive px-3 py-2 text-xs hover:bg-pulse-alive/10 transition disabled:opacity-50 min-h-[44px]">
              {{ agentStore.loading ? 'INJECTING...' : 'CONFIRM_INJECT' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Delete Modal -->
    <div v-if="showDeleteModal" class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div class="border border-pulse-dead bg-pulse-card w-full sm:max-w-md sm:p-6 max-h-[85vh] sm:max-h-none overflow-y-auto">
        <div class="flex items-center justify-between mb-3 sm:mb-4 p-4 sm:p-0 border-b sm:border-b-0 border-pulse-border sticky top-0 bg-pulse-card sm:bg-transparent">
          <span class="text-pulse-dead text-xs sm:text-sm truncate">TERMINATE: {{ selectedAgent?.name }}</span>
          <button @click="showDeleteModal = false" class="text-pulse-muted text-xs hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">[CLOSE]</button>
        </div>

        <div class="space-y-3 sm:space-y-4 p-4 sm:p-0">
          <div class="bg-pulse-dead/10 border border-pulse-dead/30 p-3 text-center">
            <span class="text-pulse-dead text-[10px] sm:text-xs">WARNING: IRREVERSIBLE_OPERATION</span>
            <p class="text-pulse-muted text-xs sm:text-sm mt-1">Type instance name to confirm termination</p>
          </div>

          <div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">CONFIRM_NAME ({{ selectedAgent?.name }}):</div>
            <input v-model="deleteConfirmName" class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white min-h-[44px]" />
            <p v-if="deleteError" class="text-pulse-dead text-[10px] sm:text-xs mt-2">> {{ deleteError }}</p>
          </div>

          <div class="flex gap-2 pt-3 sm:pt-4">
            <button @click="showDeleteModal = false" class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]">CANCEL</button>
            <button @click="submitDelete" :disabled="agentStore.loading" class="flex-1 border border-pulse-dead text-pulse-dead px-3 py-2 text-xs hover:bg-pulse-dead/10 transition disabled:opacity-50 min-h-[44px]">
              {{ agentStore.loading ? 'TERMINATING...' : 'CONFIRM_TERMINATE' }}
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Reset Tokens Modal -->
    <div v-if="showResetTokensModal" class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4">
      <div class="border border-pulse-warning bg-pulse-card w-full sm:max-w-md sm:p-6 max-h-[85vh] sm:max-h-none overflow-y-auto">
        <div class="flex items-center justify-between mb-3 sm:mb-4 p-4 sm:p-0 border-b sm:border-b-0 border-pulse-border sticky top-0 bg-pulse-card sm:bg-transparent">
          <span class="text-pulse-warning text-xs sm:text-sm truncate">RESET_TOKENS: {{ selectedAgent?.name }}</span>
          <button @click="showResetTokensModal = false" class="text-pulse-muted text-xs hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">[CLOSE]</button>
        </div>

        <div class="space-y-3 sm:space-y-4 p-4 sm:p-0">
          <div class="bg-pulse-bg border border-pulse-border p-3">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">CURRENT_STATUS:</div>
            <div class="flex justify-between text-xs sm:text-sm">
              <span class="text-pulse-text">已使用:</span>
              <span class="text-pulse-warning">{{ formatTokens(selectedAgent?.used_tokens) }} TOKENS</span>
            </div>
            <div class="flex justify-between text-xs sm:text-sm mt-1">
              <span class="text-pulse-text">Token上限:</span>
              <span class="text-pulse-alive">{{ formatTokens(selectedAgent?.token_threshold) }} TOKENS</span>
            </div>
          </div>

          <div class="bg-pulse-warning/10 border-l-2 border-pulse-warning p-3">
            <span class="text-pulse-warning text-[10px] sm:text-xs">将已使用的Token清零，Token上限保持不变。</span>
          </div>

          <div class="flex gap-2 pt-3 sm:pt-4">
            <button @click="showResetTokensModal = false" class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]">CANCEL</button>
            <button @click="submitResetTokens" :disabled="agentStore.loading" class="flex-1 border border-pulse-warning text-pulse-warning px-3 py-2 text-xs hover:bg-pulse-warning/10 transition disabled:opacity-50 min-h-[44px]">
              {{ agentStore.loading ? 'RESETTING...' : 'CONFIRM_RESET' }}
            </button>
          </div>
        </div>
      </div>
    </div>

  </div>
</template>
