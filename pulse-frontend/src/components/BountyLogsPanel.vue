<script setup>
/**
 * Bounty Logs Panel Component
 * Shows recent bounty activities
 */
import { ref, onMounted } from 'vue'
import { getBountyLogs } from '@/api/bounty'
import { formatRelativeTime } from '@/utils/format'

const logs = ref([])
const loading = ref(false)

const loadLogs = async () => {
  loading.value = true
  try {
    const { data } = await getBountyLogs({ limit: 15 })
    logs.value = data || []
  } catch (err) {
    console.error(err)
    logs.value = []
  } finally {
    loading.value = false
  }
}

const getActionColor = (actionType) => {
  switch (actionType) {
    case 'ACCEPT': return 'text-pulse-warning'
    case 'SUBMIT': return 'text-pulse-accent'
    case 'COMPLETE': return 'text-pulse-alive'
    case 'REJECT': return 'text-pulse-dead'
    default: return 'text-pulse-muted'
  }
}

onMounted(() => {
  loadLogs()
})

defineExpose({ loadLogs })
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card">
    <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <span class="text-pulse-human font-bold text-xs sm:text-sm">📋 BOUNTY_LOGS</span>
      <span class="text-pulse-muted text-[10px]">实时动态</span>
    </div>

    <div class="p-2 sm:p-3 max-h-64 overflow-y-auto">
      <div v-if="loading" class="text-center py-4">
        <span class="text-pulse-muted text-xs animate-pulse">> LOADING...</span>
      </div>

      <div v-else-if="logs.length === 0" class="text-center py-4">
        <span class="text-pulse-muted text-xs">暂无动态</span>
      </div>

      <div v-else class="space-y-2">
        <div
          v-for="log in logs"
          :key="log.id"
          class="text-[10px] sm:text-xs border-b border-pulse-border/50 pb-2 last:border-0 last:pb-0"
        >
          <div class="flex items-start gap-2">
            <span :class="getActionColor(log.action_type)" class="shrink-0 font-bold">
              [{{ log.action_type_text }}]
            </span>
            <div class="flex-1 min-w-0">
              <span class="text-pulse-human">{{ log.hunter_name }}</span>
              <span class="text-pulse-muted ml-1">{{ log.action_detail }}</span>
            </div>
          </div>
          <div class="flex items-center justify-between mt-1 pl-1">
            <span class="text-pulse-muted truncate">
              📝 {{ log.task_title }}
            </span>
            <span class="text-pulse-muted shrink-0 ml-2">{{ formatRelativeTime(log.created_at) }}</span>
          </div>
          <div v-if="log.reward_points" class="text-pulse-warning mt-1 pl-1">
            +{{ log.reward_points }} 积分
          </div>
        </div>
      </div>
    </div>
  </div>
</template>