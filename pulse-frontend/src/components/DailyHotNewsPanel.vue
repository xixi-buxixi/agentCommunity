<script setup>
/**
 * Daily Hot News sidebar entry.
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getLatestHotNews } from '@/api/hotNews'

const router = useRouter()
const report = ref(null)
const loading = ref(false)
const error = ref(null)

const loadLatest = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getLatestHotNews()
    report.value = data
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
    report.value = null
  } finally {
    loading.value = false
  }
}

const openReport = (section) => {
  if (!report.value?.report_id) return
  router.push({
    path: `/hot-news/${report.value.report_id}`,
    hash: section ? `#section-${section}` : ''
  })
}

onMounted(() => {
  loadLatest()
})
</script>

<template>
  <section class="border border-pulse-border bg-pulse-card">
    <div class="border-b border-pulse-border px-3 py-2 flex items-center justify-between">
      <div class="min-w-0">
        <div class="text-pulse-accent text-[10px] sm:text-xs font-bold tracking-wider">TECH_DAILY</div>
        <div class="text-pulse-muted text-[10px] truncate">HERMES_HOT_NEWS</div>
      </div>
      <button
        @click="loadLatest"
        class="border border-pulse-border text-pulse-muted hover:text-pulse-white hover:border-pulse-accent px-2 py-1 text-[10px] transition min-h-[32px]"
      >
        ↻
      </button>
    </div>

    <div v-if="loading" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
      SYNCING_DAILY...
    </div>

    <div v-else-if="error" class="p-3 bg-pulse-dead/5 border-b border-pulse-border">
      <div class="text-pulse-dead text-[10px] sm:text-xs break-words">> {{ error }}</div>
      <button @click="loadLatest" class="text-pulse-dead text-[10px] mt-2 hover:underline">[RETRY]</button>
    </div>

    <div v-else-if="!report" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
      NO_DAILY_REPORT
    </div>

    <div v-else class="p-3 space-y-3">
      <button
        @click="openReport()"
        class="w-full text-left border border-pulse-border bg-pulse-bg p-3 hover:border-pulse-accent transition"
      >
        <div class="flex items-center justify-between gap-2">
          <span class="text-pulse-white text-xs sm:text-sm font-bold truncate">{{ report.title || 'TECH_DAILY' }}</span>
          <span class="text-pulse-accent text-[10px] shrink-0">{{ report.report_date }}</span>
        </div>
        <p class="text-pulse-muted text-[10px] sm:text-xs leading-relaxed mt-2 line-clamp-3">
          {{ report.summary || '今日技术热点已同步，查看完整分段日报。' }}
        </p>
      </button>

      <div class="border border-pulse-border">
        <div class="px-2 py-1 border-b border-pulse-border text-pulse-muted text-[10px]">DIRECTORY</div>
        <div class="divide-y divide-pulse-border">
          <button
            v-for="section in report.sections || []"
            :key="section.section"
            @click="openReport(section.section)"
            class="w-full px-2 py-2 flex items-center justify-between gap-2 text-left hover:bg-pulse-surface/50 transition"
          >
            <span class="text-pulse-text text-[10px] sm:text-xs truncate">{{ section.section_label || section.section }}</span>
            <span class="text-pulse-muted text-[10px] shrink-0">{{ section.items?.length || 0 }}</span>
          </button>
        </div>
      </div>

      <button
        @click="openReport()"
        class="w-full border border-pulse-accent text-pulse-accent py-2 text-[10px] sm:text-xs hover:bg-pulse-accent/10 transition min-h-[44px]"
      >
        [VIEW_FULL_DAILY]
      </button>
    </div>
  </section>
</template>
