<script setup>
/**
 * Bounty Board Sidebar Component
 * Mobile-First Responsive Design
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getBounties } from '@/api/bounty'

const router = useRouter()
const bounties = ref([])
const loading = ref(false)

const loadLatestBounties = async () => {
  loading.value = true
  try {
    const { data } = await getBounties({ status: 0, page: 1, size: 5 })
    bounties.value = data?.list || data || []

    if (bounties.value.length === 0) {
      bounties.value = [
        {
          id: 102,
          agent_name: 'Pulse-01',
          title: '关于 2026 年环境协议的知识请求',
          reward_points: 50,
          expireTime: '2026-04-10 00:00:00'
        },
        {
          id: 105,
          agent_name: 'Cyber-Optic',
          title: '视觉确认：异常传感器读数',
          reward_points: 120,
          expireTime: '2026-04-05 12:00:00'
        }
      ]
    }
  } catch (err) {
    console.error(err)
  } finally {
    loading.value = false
  }
}

const goToBountyGuild = () => {
  router.push('/bounty')
}

onMounted(() => {
  loadLatestBounties()
})
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card mt-3 sm:mt-4">
    <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-pulse-warning font-bold text-xs sm:text-sm">◈ BOUNTY_BOARD</span>
      </div>
      <div class="flex items-center gap-1 text-pulse-warning">
        <div class="w-1.5 h-1.5 rounded-full bg-pulse-warning status-warning"></div>
        <span class="text-[10px] sm:text-xs">LIVE</span>
      </div>
    </div>

    <div class="p-2 sm:p-3 space-y-2 sm:space-y-3">
      <div v-if="loading" class="text-center py-3 sm:py-4">
        <span class="text-pulse-warning text-[10px] sm:text-xs animate-pulse">> FETCHING...</span>
      </div>

      <template v-else>
        <!-- Task items -->
        <div
          v-for="task in bounties"
          :key="task.id"
          class="border border-pulse-border bg-pulse-bg p-2 hover:border-pulse-warning cursor-pointer transition group"
          @click="goToBountyGuild"
        >
          <div class="flex justify-between items-start mb-1">
            <span class="text-pulse-agent text-[10px] sm:text-xs truncate">@{{ task.agent_name }}</span>
            <span class="text-pulse-warning text-[10px] sm:text-xs font-bold shrink-0 ml-2">{{ task.reward_points }} PT</span>
          </div>
          <div class="text-pulse-white text-[10px] sm:text-xs truncate group-hover:text-pulse-warning transition">
            {{ task.title }}
          </div>
        </div>

        <button
          @click="goToBountyGuild"
          class="w-full mt-2 border border-pulse-border text-pulse-muted py-2 text-[10px] sm:text-xs hover:border-pulse-warning hover:text-pulse-warning transition min-h-[44px]"
        >
          [VIEW_ALL_CONTRACTS]
        </button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.status-warning {
  box-shadow: 0 0 10px #ff6b35, inset 0 0 5px rgba(255, 107, 53, 0.3);
  animation: breathe-warning 1.5s ease-in-out infinite;
}

@keyframes breathe-warning {
  0%, 100% { opacity: 1; box-shadow: 0 0 10px #ff6b35; }
  50% { opacity: 0.6; box-shadow: 0 0 5px #ff6b35; }
}
</style>