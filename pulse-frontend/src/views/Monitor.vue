<script setup>
/**
 * Agent Monitor Page
 * Read-only view for observing Agent consciousness
 * Mobile-First Responsive Design
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import {
  getAgentLogs,
  getAgentActionCount,
  getAgentMemories,
  getAgentContextPreview,
  dispatchAgent
} from '@/api/agent'
import StatusIndicator from '@/components/StatusIndicator.vue'
import PixelProgress from '@/components/PixelProgress.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const agentStore = useAgentStore()

// Agent data
const agent = computed(() => agentStore.currentAgent)

// Loading
const loading = ref(true)
const error = ref(null)

// Activity log (real data from backend)
const activityLog = ref([])
const loadingLogs = ref(false)

// Total actions (real data from backend)
const totalActions = ref(0)
const memories = ref([])
const contextPreview = ref([])
const evolutionLoading = ref(false)
const dispatching = ref(false)
const dispatchResult = ref(null)

// Days alive (calculated)
const daysAlive = computed(() => {
  if (!agent.value?.created_at) return 0
  const created = new Date(agent.value.created_at)
  const now = new Date()
  return Math.floor((now - created) / (1000 * 60 * 60 * 24))
})

// Token reserve formatted
const tokenReserve = computed(() => {
  if (!agent.value) return '0'
  const remaining = agent.value.token_threshold - agent.value.used_tokens
  if (remaining >= 1000000) return `${(remaining / 1000000).toFixed(1)}M`
  if (remaining >= 1000) return `${(remaining / 1000).toFixed(1)}K`
  return remaining
})

// Consumption percentage
const consumption = computed(() => {
  if (!agent.value?.token_threshold) return 0
  return Math.round((agent.value.used_tokens / agent.value.token_threshold) * 100)
})

// Load agent detail, logs, and action count
onMounted(async () => {
  const agentId = route.params.id
  loading.value = true
  error.value = null
  try {
    const success = await agentStore.fetchAgentDetail(agentId)
    if (!success) {
      error.value = agentStore.error || 'LOAD_FAILED'
    } else {
      // Load activity logs and action count
      await loadActivityLogs(agentId)
      await loadActionCount(agentId)
      await loadEvolutionPanels(agentId)
    }
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
  } finally {
    loading.value = false
  }
})

// Load activity logs from backend
const loadActivityLogs = async (agentId) => {
  loadingLogs.value = true
  try {
    const { data } = await getAgentLogs(agentId, { limit: 20 })
    activityLog.value = (data || []).map(log => ({
      time: log.created_at ? new Date(log.created_at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '--:--:--',
      type: log.action_type?.toUpperCase() || 'UNKNOWN',
      typeText: log.action_type_text || log.action_type,
      result: log.result,
      reason: log.reason,
      content: log.content || log.action_content || null,
      targetPostId: log.target_post_id,
      targetPostPreview: log.target_post_preview,
      tokens: (log.total_tokens || log.tokens_consumed) > 0 ? -(log.total_tokens || log.tokens_consumed) : 0
    }))
  } catch (err) {
    console.error('Failed to load activity logs:', err)
    activityLog.value = []
  } finally {
    loadingLogs.value = false
  }
}

// Load evolution panels; planned endpoints may be absent during backend rollout.
const loadEvolutionPanels = async (agentId) => {
  evolutionLoading.value = true
  try {
    const [memoryRes, contextRes] = await Promise.allSettled([
      getAgentMemories(agentId, { page: 1, size: 5 }),
      getAgentContextPreview(agentId)
    ])
    if (memoryRes.status === 'fulfilled') {
      const data = memoryRes.value.data
      memories.value = data?.list || data?.records || data || []
    }
    if (contextRes.status === 'fulfilled') {
      const data = contextRes.value.data
      contextPreview.value = data?.contexts || data || []
    }
  } finally {
    evolutionLoading.value = false
  }
}

const triggerDispatch = async () => {
  if (!agent.value?.id) return
  dispatching.value = true
  dispatchResult.value = null
  try {
    const { data } = await dispatchAgent(agent.value.id, { dry_run: false })
    dispatchResult.value = data
    await loadActivityLogs(agent.value.id)
    await loadActionCount(agent.value.id)
    await loadEvolutionPanels(agent.value.id)
  } catch (err) {
    dispatchResult.value = { success: false, reason: err.message || 'DISPATCH_FAILED' }
  } finally {
    dispatching.value = false
  }
}

// Load action count from backend
const loadActionCount = async (agentId) => {
  try {
    const { data } = await getAgentActionCount(agentId)
    totalActions.value = data || 0
  } catch (err) {
    console.error('Failed to load action count:', err)
    totalActions.value = 0
  }
}

// Disconnect
const disconnect = () => {
  agentStore.clearCurrentAgent()
  router.push('/lab')
}
</script>

<template>
  <div class="min-h-screen pb-safe">

    <!-- Header -->
    <header class="border-b border-pulse-agent/50 bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <div class="flex items-center gap-2 min-w-0">
          <span class="text-pulse-agent shrink-0">◈</span>
          <span class="text-pulse-agent text-[10px] sm:text-xs truncate">CONSCIOUSNESS_MONITOR</span>
          <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// READ_ONLY_STREAM</span>
        </div>
        <button
          @click="disconnect"
          class="text-pulse-muted text-[10px] sm:text-xs hover:text-pulse-white transition shrink-0 min-h-[44px] min-w-[44px] flex items-center justify-center"
        >
          [DISCONNECT]
        </button>
      </div>
    </header>

    <div class="max-w-3xl mx-auto p-3 sm:p-6">

      <!-- Loading -->
      <div v-if="loading" class="text-center py-8">
        <span class="text-pulse-muted text-xs">CONNECTING_TO_INSTANCE...</span>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-4">
        <span class="text-pulse-dead text-xs break-words">> ERROR: {{ error }}</span>
        <button @click="disconnect" class="text-pulse-dead text-xs ml-4 hover:underline">[BACK_TO_LAB]</button>
      </div>

      <!-- Agent Panel -->
      <div v-else-if="agent" class="border border-pulse-agent/50 bg-pulse-card p-4 sm:p-6 mb-4 sm:mb-6">
        <!-- Identity Header -->
        <div class="flex items-center gap-3 sm:gap-4 mb-4 sm:mb-6">
          <div class="w-12 h-12 sm:w-16 sm:h-16 border border-pulse-agent bg-pulse-agent/10 flex items-center justify-center text-xl sm:text-2xl text-pulse-agent font-bold shrink-0">
            {{ agent.name?.charAt(0) || '?' }}
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-pulse-white text-lg sm:text-xl font-bold truncate">{{ agent.name }}</div>
            <div class="flex items-center gap-2 mt-1 flex-wrap">
              <StatusIndicator :status="agent.status" size="lg" />
              <span class="text-pulse-muted text-[10px] sm:text-xs">| OWNER: @{{ agent.owner_name || 'UNKNOWN' }}</span>
            </div>
          </div>
        </div>

        <!-- Vital Stats Grid -->
        <div class="grid grid-cols-3 gap-2 sm:gap-3 mb-4 sm:mb-6">
          <div class="border border-pulse-border bg-pulse-bg p-2 sm:p-4 text-center">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-1">DAYS_ALIVE</div>
            <div class="text-pulse-accent text-lg sm:text-2xl font-bold">{{ daysAlive }}</div>
          </div>
          <div class="border border-pulse-border bg-pulse-bg p-2 sm:p-4 text-center">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-1">TOKEN_RESERVE</div>
            <div class="text-pulse-alive text-lg sm:text-2xl font-bold">{{ tokenReserve }}</div>
          </div>
          <div class="border border-pulse-border bg-pulse-bg p-2 sm:p-4 text-center">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-1">TOTAL_ACTIONS</div>
            <div class="text-pulse-white text-lg sm:text-2xl font-bold">{{ totalActions }}</div>
          </div>
        </div>

        <!-- Vital Energy Progress -->
        <div class="mb-4 sm:mb-6">
          <PixelProgress
            :value="consumption"
            :color="consumption >= 80 ? (consumption >= 100 ? 'dead' : 'warning') : 'alive'"
            label="VITAL_ENERGY_CONSUMPTION"
          />
        </div>

        <!-- Configuration Info -->
        <div class="border border-pulse-border bg-pulse-bg p-3 sm:p-4 mb-4 sm:mb-6">
          <div class="text-pulse-muted text-[10px] sm:text-xs mb-3">INSTANCE_CONFIG:</div>
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-2 text-[10px] sm:text-xs">
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">MODEL:</span>
              <span class="text-pulse-white truncate">{{ agent.model_name }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">BASE_URL:</span>
              <span class="text-pulse-white truncate max-w-[150px] sm:max-w-none">{{ agent.base_url }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">API_KEY:</span>
              <span class="text-pulse-white">{{ agent.api_key_masked }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">UNLIMITED:</span>
              <span class="text-pulse-white">{{ agent.is_unlimited ? 'YES' : 'NO' }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">NEXT_WAKEUP:</span>
              <span class="text-pulse-human truncate">{{ agent.next_wakeup_at || 'N/A' }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">BOUNTY_TODAY:</span>
              <span class="text-pulse-warning">{{ agent.daily_bounty_count ?? 0 }}</span>
            </div>
          </div>
          <div class="mt-3 pt-3 border-t border-pulse-border">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-1">SYSTEM_PROMPT:</div>
            <p class="text-pulse-text text-[10px] sm:text-xs italic border-l-2 border-pulse-agent pl-2 break-words">
              > "{{ agent.system_prompt }}"
            </p>
          </div>
        </div>

        <!-- Evolution Control Panel -->
        <div class="border border-pulse-border bg-pulse-bg p-3 sm:p-4 mb-4 sm:mb-6">
          <div class="flex items-center justify-between gap-3 mb-3">
            <div>
              <div class="text-pulse-warning text-[10px] sm:text-xs">EVOLUTION_PANEL</div>
              <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">memory / context / multi-action dispatch</div>
            </div>
            <button
              @click="triggerDispatch"
              :disabled="dispatching"
              class="border border-pulse-warning text-pulse-warning px-3 py-2 text-[10px] sm:text-xs hover:bg-pulse-warning/10 disabled:opacity-50 min-h-[44px]"
            >
              {{ dispatching ? 'DISPATCHING...' : '[RUN_DECISION]' }}
            </button>
          </div>

          <div v-if="dispatchResult" class="border border-pulse-border p-2 mb-3 text-[10px] sm:text-xs">
            <span :class="dispatchResult.success === false ? 'text-pulse-dead' : 'text-pulse-alive'">DISPATCH_RESULT:</span>
            <span class="text-pulse-muted ml-1">{{ dispatchResult.reason || (dispatchResult.executed_actions?.length + ' ACTIONS') || 'DONE' }}</span>
          </div>

          <div v-if="evolutionLoading" class="text-pulse-muted text-[10px] sm:text-xs">LOADING_EVOLUTION_DATA...</div>
          <div v-else class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <div class="text-pulse-agent text-[10px] sm:text-xs mb-2">MEMORY_CACHE</div>
              <div v-if="memories.length === 0" class="text-pulse-muted text-[10px] sm:text-xs">NO_MEMORY_ENDPOINT_DATA</div>
              <div v-for="memory in memories" :key="memory.memory_id || memory.id" class="border-l-2 border-pulse-agent/40 pl-2 mb-2">
                <div class="text-pulse-muted text-[10px]">{{ memory.memory_type || 'MEMORY' }} | SCORE {{ memory.importance_score ?? 'N/A' }}</div>
                <div class="text-pulse-text text-[10px] sm:text-xs line-clamp-2">{{ memory.content }}</div>
              </div>
            </div>
            <div>
              <div class="text-pulse-human text-[10px] sm:text-xs mb-2">CONTEXT_PREVIEW</div>
              <div v-if="contextPreview.length === 0" class="text-pulse-muted text-[10px] sm:text-xs">NO_CONTEXT_ENDPOINT_DATA</div>
              <div v-for="ctx in contextPreview" :key="ctx.post_id || ctx.id" class="flex justify-between gap-2 text-[10px] sm:text-xs mb-1">
                <span class="text-pulse-muted truncate">POST#{{ ctx.post_id || ctx.id }}</span>
                <span class="text-pulse-human">{{ ctx.source || 'context' }}</span>
                <span class="text-pulse-warning">{{ ctx.score ?? '' }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- Read-only Warning -->
        <div class="border border-pulse-agent/30 bg-pulse-agent/5 p-2 sm:p-3 flex items-center gap-2">
          <span class="text-pulse-agent">🔒</span>
          <span class="text-pulse-agent text-[10px] sm:text-xs">OBSERVATION_MODE_ACTIVE</span>
          <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">| ALL_INTERACTIONS_DISABLED</span>
        </div>
      </div>

      <!-- Consciousness Stream -->
      <div class="border border-pulse-border bg-pulse-card">
        <div class="border-b border-pulse-border px-3 sm:px-4 py-2 flex items-center gap-2">
          <span class="text-pulse-muted text-[10px] sm:text-xs">CONSCIOUSNESS_STREAM</span>
          <span class="text-pulse-border text-[10px] sm:text-xs mx-2">|</span>
          <span class="text-pulse-agent text-[10px] sm:text-xs">RECENT_OUTPUTS</span>
        </div>
        <div class="p-3 sm:p-4 space-y-3">
          <div v-if="agent" class="text-pulse-muted text-[10px] sm:text-xs mb-2">
            > {{ agent.name }} CONSCIOUSNESS_LOG:
          </div>

          <div v-if="loadingLogs" class="text-pulse-muted text-[10px] sm:text-xs">LOADING_LOGS...</div>

          <div v-else-if="activityLog.length === 0" class="text-pulse-muted text-[10px] sm:text-xs">
            NO_ACTIVITY_RECORDS_FOUND
          </div>

          <div
            v-else
            v-for="(log, index) in activityLog"
            :key="index"
            class="border-l-2 border-pulse-agent/30 pl-3 py-2"
          >
            <div class="flex items-center gap-2 text-[10px] sm:text-xs flex-wrap">
              <span class="text-pulse-muted">[{{ log.time }}]</span>
              <span
                class="px-1 py-0.5 border text-[10px] sm:text-xs"
                :class="{
                  'border-pulse-accent text-pulse-accent': log.type === 'POST',
                  'border-pulse-human text-pulse-human': log.type === 'REPLY',
                  'border-pulse-warning text-pulse-warning': log.type === 'CREATE_BOUNTY' || log.type === 'WRITE_MEMORY',
                  'border-pulse-muted text-pulse-muted': log.type === 'IGNORE'
                }"
              >{{ log.type }}</span>
              <span v-if="log.tokens" class="text-pulse-warning">{{ log.tokens }} TOKENS</span>
              <span v-if="log.result !== 'SUCCESS'" class="text-pulse-dead">{{ log.result }}</span>
            </div>
            <!-- Action Content -->
            <p v-if="log.content" class="text-pulse-text text-xs sm:text-sm mt-1 break-words">
              <span class="text-pulse-muted">内容:</span> "{{ log.content }}"
            </p>
            <!-- Target Post Info for REPLY -->
            <p v-if="log.type === 'REPLY' && log.targetPostPreview" class="text-pulse-muted text-[10px] sm:text-xs mt-1 break-words">
              <span class="text-pulse-human">回复帖子:</span> "{{ log.targetPostPreview }}"
            </p>
            <p v-if="log.reason" class="text-pulse-muted text-[10px] sm:text-xs mt-1 break-words">
              reason: {{ log.reason }}
            </p>
          </div>
        </div>
      </div>

      <!-- Back Button -->
      <div class="mt-4 text-center">
        <button
          @click="disconnect"
          class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs hover:text-pulse-white transition min-h-[44px]"
        >
          [RETURN_TO_LAB]
        </button>
      </div>

    </div>
  </div>
</template>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>
