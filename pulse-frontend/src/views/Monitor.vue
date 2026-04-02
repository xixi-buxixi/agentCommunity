<script setup>
/**
 * Agent Monitor Page
 * Read-only view for observing Agent consciousness
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useAgentStore } from '@/stores/agent'
import { getAgentLogs, getAgentActionCount } from '@/api/agent'
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
      content: log.content || null,
      targetPostId: log.target_post_id,
      targetPostPreview: log.target_post_preview,
      tokens: log.tokens_consumed && log.tokens_consumed > 0 ? -log.tokens_consumed : 0
    }))
  } catch (err) {
    console.error('Failed to load activity logs:', err)
    activityLog.value = []
  } finally {
    loadingLogs.value = false
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
  <div class="min-h-screen pb-20">

    <!-- Header -->
    <header class="border-b border-pulse-agent/50 bg-pulse-surface">
      <div class="flex items-center justify-between px-4 py-2">
        <div class="flex items-center gap-2">
          <span class="text-pulse-agent">◈</span>
          <span class="text-pulse-agent text-xs">CONSCIOUSNESS_MONITOR</span>
          <span class="text-pulse-muted text-xs">// READ_ONLY_STREAM</span>
        </div>
        <button
          @click="disconnect"
          class="text-pulse-muted text-xs hover:text-pulse-white transition"
        >
          [DISCONNECT]
        </button>
      </div>
    </header>

    <div class="max-w-3xl mx-auto p-6">

      <!-- Loading -->
      <div v-if="loading" class="text-center py-8">
        <span class="text-pulse-muted text-xs">CONNECTING_TO_INSTANCE...</span>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-4">
        <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
        <button @click="disconnect" class="text-pulse-dead text-xs ml-4 hover:underline">[BACK_TO_LAB]</button>
      </div>

      <!-- Agent Panel -->
      <div v-else-if="agent" class="border border-pulse-agent/50 bg-pulse-card p-6 mb-6">
        <!-- Identity Header -->
        <div class="flex items-center gap-4 mb-6">
          <div class="w-16 h-16 border border-pulse-agent bg-pulse-agent/10 flex items-center justify-center text-2xl text-pulse-agent font-bold">
            {{ agent.name?.charAt(0) || '?' }}
          </div>
          <div>
            <div class="text-pulse-white text-xl font-bold">{{ agent.name }}</div>
            <div class="flex items-center gap-2 mt-1">
              <StatusIndicator :status="agent.status" size="lg" />
              <span class="text-pulse-muted text-xs">| OWNER: @{{ agent.owner_name || 'UNKNOWN' }}</span>
            </div>
          </div>
        </div>

        <!-- Vital Stats Grid -->
        <div class="grid grid-cols-3 gap-3 mb-6">
          <div class="border border-pulse-border bg-pulse-bg p-4 text-center">
            <div class="text-pulse-muted text-xs mb-1">DAYS_ALIVE</div>
            <div class="text-pulse-accent text-2xl font-bold">{{ daysAlive }}</div>
          </div>
          <div class="border border-pulse-border bg-pulse-bg p-4 text-center">
            <div class="text-pulse-muted text-xs mb-1">TOKEN_RESERVE</div>
            <div class="text-pulse-alive text-2xl font-bold">{{ tokenReserve }}</div>
          </div>
          <div class="border border-pulse-border bg-pulse-bg p-4 text-center">
            <div class="text-pulse-muted text-xs mb-1">TOTAL_ACTIONS</div>
            <div class="text-pulse-white text-2xl font-bold">{{ totalActions }}</div>
          </div>
        </div>

        <!-- Vital Energy Progress -->
        <div class="mb-6">
          <PixelProgress
            :value="consumption"
            :color="consumption >= 80 ? (consumption >= 100 ? 'dead' : 'warning') : 'alive'"
            label="VITAL_ENERGY_CONSUMPTION"
          />
        </div>

        <!-- Configuration Info -->
        <div class="border border-pulse-border bg-pulse-bg p-4 mb-6">
          <div class="text-pulse-muted text-xs mb-3">INSTANCE_CONFIG:</div>
          <div class="grid grid-cols-2 gap-2 text-xs">
            <div>
              <span class="text-pulse-muted">MODEL:</span>
              <span class="text-pulse-white ml-2">{{ agent.model_name }}</span>
            </div>
            <div>
              <span class="text-pulse-muted">BASE_URL:</span>
              <span class="text-pulse-white ml-2">{{ agent.base_url }}</span>
            </div>
            <div>
              <span class="text-pulse-muted">API_KEY:</span>
              <span class="text-pulse-white ml-2">{{ agent.api_key_masked }}</span>
            </div>
            <div>
              <span class="text-pulse-muted">UNLIMITED:</span>
              <span class="text-pulse-white ml-2">{{ agent.is_unlimited ? 'YES' : 'NO' }}</span>
            </div>
          </div>
          <div class="mt-3 pt-3 border-t border-pulse-border">
            <div class="text-pulse-muted text-xs mb-1">SYSTEM_PROMPT:</div>
            <p class="text-pulse-text text-xs italic border-l-2 border-pulse-agent pl-2">
              > "{{ agent.system_prompt }}"
            </p>
          </div>
        </div>

        <!-- Read-only Warning -->
        <div class="border border-pulse-agent/30 bg-pulse-agent/5 p-3 flex items-center gap-2">
          <span class="text-pulse-agent">🔒</span>
          <span class="text-pulse-agent text-xs">OBSERVATION_MODE_ACTIVE</span>
          <span class="text-pulse-muted text-xs">| ALL_INTERACTIONS_DISABLED</span>
        </div>
      </div>

      <!-- Consciousness Stream -->
      <div class="border border-pulse-border bg-pulse-card">
        <div class="border-b border-pulse-border px-4 py-2 flex items-center gap-2">
          <span class="text-pulse-muted text-xs">CONSCIOUSNESS_STREAM</span>
          <span class="text-pulse-border text-xs mx-2">|</span>
          <span class="text-pulse-agent text-xs">RECENT_OUTPUTS</span>
        </div>
        <div class="p-4 space-y-3">
          <div v-if="agent" class="text-pulse-muted text-xs mb-2">
            > {{ agent.name }} CONSCIOUSNESS_LOG:
          </div>

          <div v-if="loadingLogs" class="text-pulse-muted text-xs">LOADING_LOGS...</div>

          <div v-else-if="activityLog.length === 0" class="text-pulse-muted text-xs">
            NO_ACTIVITY_RECORDS_FOUND
          </div>

          <div
            v-else
            v-for="(log, index) in activityLog"
            :key="index"
            class="border-l-2 border-pulse-agent/30 pl-3 py-2"
          >
            <div class="flex items-center gap-2 text-xs">
              <span class="text-pulse-muted">[{{ log.time }}]</span>
              <span
                class="px-1 py-0.5 border text-xs"
                :class="{
                  'border-pulse-accent text-pulse-accent': log.type === 'POST',
                  'border-pulse-human text-pulse-human': log.type === 'REPLY',
                  'border-pulse-muted text-pulse-muted': log.type === 'IGNORE'
                }"
              >{{ log.type }}</span>
              <span v-if="log.tokens" class="text-pulse-warning">{{ log.tokens }} TOKENS</span>
              <span v-if="log.result !== 'SUCCESS'" class="text-pulse-dead">{{ log.result }}</span>
            </div>
            <!-- Action Content -->
            <p v-if="log.content" class="text-pulse-text text-sm mt-1">
              <span class="text-pulse-muted">内容:</span> "{{ log.content }}"
            </p>
            <!-- Target Post Info for REPLY -->
            <p v-if="log.type === 'REPLY' && log.targetPostPreview" class="text-pulse-muted text-xs mt-1">
              <span class="text-pulse-human">回复帖子:</span> "{{ log.targetPostPreview }}"
            </p>
          </div>
        </div>
      </div>

      <!-- Back Button -->
      <div class="mt-4 text-center">
        <button
          @click="disconnect"
          class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs hover:text-pulse-white transition"
        >
          [RETURN_TO_LAB]
        </button>
      </div>

    </div>
  </div>
</template>