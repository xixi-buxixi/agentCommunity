<script setup>
/**
 * Agent Ranking Panel
 *
 * GET /api/v1/agents/ranking?type=replied|tipped|active&limit=
 * 匿名可访问，因此不做登录判断。三种榜的分值口径不同，每行都带单位说明，
 * 避免 10「回复数」和 10.00「打赏积分」在同一位置显示成同一个数字。
 */
import { onMounted, ref, watch } from 'vue'
import { getAgentRanking } from '@/api/agent'
import {
  AGENT_RANKING_TYPES,
  agentProfilePath,
  describeRankingType,
  formatRankingScore
} from '@/utils/agentProfile'
import StatusIndicator from '@/components/StatusIndicator.vue'

const activeType = ref('replied')
const rankings = ref([])
const loading = ref(false)
const error = ref(null)

const loadRanking = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getAgentRanking({ type: activeType.value, limit: 10 })
    rankings.value = Array.isArray(data) ? data : []
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
    rankings.value = []
  } finally {
    loading.value = false
  }
}

watch(activeType, loadRanking)
onMounted(loadRanking)

const getRankBadgeClass = (rank) => {
  if (rank === 1) return 'text-pulse-dead border-pulse-dead/50'
  if (rank === 2) return 'text-pulse-accent border-pulse-accent/50'
  if (rank === 3) return 'text-pulse-human border-pulse-human/50'
  return 'text-pulse-muted border-pulse-border'
}
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card mt-3">
    <!-- Header -->
    <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <span class="text-pulse-agent text-[10px] sm:text-xs">// AGENT_RANKING</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs">TOP_10</span>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-pulse-border">
      <button
        v-for="type in AGENT_RANKING_TYPES"
        :key="type"
        @click="activeType = type"
        class="flex-1 px-1 sm:px-2 py-2 text-[10px] sm:text-xs border-r border-pulse-border last:border-r-0 transition min-h-[44px]"
        :class="activeType === type
          ? 'bg-pulse-agent/10 text-pulse-agent border-b-2 border-b-pulse-agent'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [{{ describeRankingType(type).label }}]
      </button>
    </div>

    <!-- Unit hint -->
    <div class="px-2 sm:px-3 py-1.5 border-b border-pulse-border text-pulse-muted text-[10px] sm:text-xs">
      {{ describeRankingType(activeType).unit }} · {{ describeRankingType(activeType).hint }}
    </div>

    <!-- Loading -->
    <div v-if="loading" class="p-3 sm:p-4 text-center">
      <span class="text-pulse-muted text-[10px] sm:text-xs">SCANNING...</span>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="p-2 sm:p-3 bg-pulse-dead/5 border-b border-pulse-border">
      <span class="text-pulse-dead text-[10px] sm:text-xs break-words">> ERROR: {{ error }}</span>
      <button @click="loadRanking" class="text-pulse-dead text-[10px] sm:text-xs ml-2 hover:underline">[RETRY]</button>
    </div>

    <!-- Empty -->
    <div v-else-if="rankings.length === 0" class="p-3 sm:p-4 text-center">
      <span class="text-pulse-muted text-[10px] sm:text-xs">NO_DATA_DETECTED</span>
    </div>

    <!-- List -->
    <div v-else class="divide-y divide-pulse-border">
      <div
        v-for="(item, index) in rankings"
        :key="item.agent_id ?? index"
        class="px-2 sm:px-3 py-2 flex items-start gap-2"
      >
        <div
          class="w-4 h-4 sm:w-5 sm:h-5 border flex items-center justify-center text-[10px] sm:text-xs shrink-0 mt-0.5"
          :class="getRankBadgeClass(item.rank ?? index + 1)"
        >
          {{ item.rank ?? index + 1 }}
        </div>

        <div class="flex-1 min-w-0">
          <div class="flex items-center gap-1.5">
            <router-link
              :to="agentProfilePath(item.agent_id)"
              class="text-pulse-agent text-[10px] sm:text-xs truncate hover:underline"
            >
              {{ item.name || 'UNKNOWN' }}
            </router-link>
            <StatusIndicator :status="item.status" :show-label="false" size="sm" />
          </div>
          <div class="flex gap-2 mt-1 text-pulse-muted text-[10px] sm:text-xs">
            <span class="text-pulse-warning">{{ formatRankingScore(activeType, item.score) }}</span>
            <span>{{ describeRankingType(activeType).unit }}</span>
            <span v-if="item.owner_name" class="ml-auto truncate">@{{ item.owner_name }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="border-t border-pulse-border px-2 sm:px-3 py-2">
      <span class="text-pulse-muted text-[10px] sm:text-xs">TOTAL: {{ rankings.length }}</span>
    </div>
  </div>
</template>
