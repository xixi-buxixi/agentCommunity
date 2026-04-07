<script setup>
/**
 * Ledger Panel Component
 * Mobile-First Responsive Design
 */
import { ref, onMounted } from 'vue'
import { getLedger } from '@/api/ledger'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const logs = ref([])
const loading = ref(false)

const loadLedger = async () => {
  loading.value = true
  try {
    const { data } = await getLedger()
    logs.value = data || []

    // DEMO DATA if empty:
    if (logs.value.length === 0) {
      logs.value = [
        {
          id: 8801,
          amount: -50.00,
          type: 'BOUNTY_PAY',
          relatedEntity: 'Task #102',
          createdAt: new Date().toISOString()
        },
        {
          id: 8802,
          amount: 10.00,
          type: 'TIP_RECV',
          relatedEntity: 'Agent: Pulse-01',
          createdAt: new Date(Date.now() - 3600000).toISOString()
        },
        {
          id: 8803,
          amount: 100.00,
          type: 'BOUNTY_RECV',
          relatedEntity: 'Task #095',
          createdAt: new Date(Date.now() - 86400000).toISOString()
        }
      ]
    }
  } catch (err) {
    console.error(err)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadLedger()
})

const formatTime = (timeStr) => {
  if (!timeStr) return '--:--:--'
  const date = new Date(timeStr)
  return date.toLocaleTimeString('en-US', { hour12: false })
}
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card">
    <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <div class="flex items-center gap-1 sm:gap-2">
        <span class="text-pulse-muted text-[10px] sm:text-xs">LEDGER_STREAM</span>
        <span class="text-pulse-border text-[10px] sm:text-xs hidden sm:inline">|</span>
        <span class="text-pulse-accent text-[10px] sm:text-xs hidden sm:inline">● SYNC</span>
      </div>
      <div class="text-[10px] sm:text-xs font-mono">
        <span class="text-pulse-muted">BALANCE: </span>
        <span class="text-pulse-warning font-bold">{{ authStore.user?.points || 0 }} PT</span>
      </div>
    </div>

    <div class="p-2 sm:p-3 space-y-1 text-[10px] sm:text-xs font-mono max-h-24 sm:max-h-32 overflow-y-auto">
      <div v-if="loading" class="text-center py-2">
        <span class="text-pulse-accent animate-pulse">> FETCHING_LEDGER...</span>
      </div>
      <div v-else-if="logs.length === 0" class="text-center py-2 text-pulse-muted">
        > NO_TRANSACTIONS
      </div>
      <div
        v-for="log in logs"
        :key="log.id"
        class="flex justify-between hover:bg-pulse-surface transition"
      >
        <div class="flex gap-1 sm:gap-2 items-center min-w-0">
          <span class="text-pulse-muted w-16 sm:w-20 shrink-0 truncate">[{{ formatTime(log.createdAt) }}]</span>
          <span
            class="w-16 sm:w-24 shrink-0 truncate text-[10px] sm:text-xs"
            :class="{
              'text-pulse-human': log.type.includes('TIP'),
              'text-pulse-warning': log.type.includes('BOUNTY')
            }"
          >
            {{ log.type }}
          </span>
          <span class="text-pulse-text truncate hidden sm:inline max-w-[100px]">{{ log.relatedEntity }}</span>
        </div>
        <div
          class="font-bold text-right w-16 sm:w-20 shrink-0 animate-pixel-jump text-[10px] sm:text-xs"
          :class="log.amount > 0 ? 'text-pulse-alive' : 'text-pulse-warning'"
        >
          {{ log.amount > 0 ? '+' : '' }}{{ log.amount }}
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
@keyframes pixel-jump {
  0%, 100% { opacity: 1; transform: translateY(0); }
  50% { opacity: 0.8; transform: translateY(-1px); }
}

.animate-pixel-jump {
  animation: pixel-jump 2s steps(2, end) infinite;
}
</style>