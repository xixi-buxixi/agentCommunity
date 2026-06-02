<script setup>
/**
 * Daily Hot News detail page.
 */
import { computed, onMounted, ref, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getHotNewsDetail } from '@/api/hotNews'
import { renderMarkdown } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()
const report = ref(null)
const loading = ref(false)
const error = ref(null)

const hasStructuredSections = computed(() => (report.value?.sections || []).some(section => (section.items || []).length > 0))
const renderedMarkdown = computed(() => renderMarkdown(report.value?.raw_markdown || ''))

const loadReport = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getHotNewsDetail(route.params.id)
    report.value = data
    await nextTick()
    if (route.hash) {
      document.querySelector(route.hash)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
    report.value = null
  } finally {
    loading.value = false
  }
}

const backToSquare = () => {
  router.push('/square')
}

const openExternal = (url) => {
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}

watch(() => route.params.id, loadReport)

onMounted(() => {
  loadReport()
})
</script>

<template>
  <div class="min-h-screen pb-safe">
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <button
          @click="backToSquare"
          class="border border-pulse-border text-pulse-muted hover:text-pulse-white px-3 py-2 text-[10px] sm:text-xs transition min-h-[44px]"
        >
          ← SQUARE
        </button>
        <div class="text-right min-w-0">
          <div class="text-pulse-accent text-xs sm:text-sm font-bold tracking-wider truncate">TECH_DAILY_DETAIL</div>
          <div class="text-pulse-muted text-[10px] truncate">HERMES_REPORT_VIEW</div>
        </div>
      </div>
    </header>

    <main class="max-w-5xl mx-auto p-3 sm:p-4">
      <div v-if="loading" class="border border-pulse-border bg-pulse-card p-8 text-center">
        <span class="text-pulse-muted text-xs">LOADING_DAILY_REPORT...</span>
      </div>

      <div v-else-if="error" class="border border-pulse-dead/30 bg-pulse-dead/10 p-4">
        <div class="text-pulse-dead text-xs break-words">> ERROR: {{ error }}</div>
        <button @click="loadReport" class="text-pulse-dead text-xs mt-3 hover:underline">[RETRY]</button>
      </div>

      <article v-else-if="report" class="space-y-3 sm:space-y-4">
        <section class="border border-pulse-border bg-pulse-card p-4 sm:p-5">
          <div class="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-3">
            <div class="min-w-0">
              <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">
                {{ report.source || 'hermes' }} // {{ report.published_at || report.report_date }}
              </div>
              <h1 class="text-pulse-white text-lg sm:text-2xl font-bold leading-tight">
                {{ report.title || 'TECH_DAILY' }}
              </h1>
            </div>
            <div class="flex gap-2 text-[10px] sm:text-xs shrink-0">
              <span class="border border-pulse-border text-pulse-muted px-2 py-1">SECTIONS {{ report.section_count || 0 }}</span>
              <span class="border border-pulse-border text-pulse-muted px-2 py-1">ITEMS {{ report.item_count || 0 }}</span>
            </div>
          </div>
          <p v-if="report.summary" class="text-pulse-text text-xs sm:text-sm leading-relaxed mt-4">
            {{ report.summary }}
          </p>
        </section>

        <section v-if="hasStructuredSections" class="space-y-3">
          <div
            v-for="section in report.sections"
            :id="`section-${section.section}`"
            :key="section.section"
            class="border border-pulse-border bg-pulse-card scroll-mt-20"
          >
            <div class="border-b border-pulse-border px-3 sm:px-4 py-2 flex items-center justify-between gap-2">
              <h2 class="text-pulse-accent text-sm sm:text-base font-bold truncate">
                {{ section.section_label || section.section }}
              </h2>
              <span class="text-pulse-muted text-[10px] sm:text-xs shrink-0">{{ section.items?.length || 0 }} ITEMS</span>
            </div>

            <div class="divide-y divide-pulse-border">
              <div
                v-for="item in section.items"
                :key="item.item_id || `${section.section}-${item.rank}-${item.title}`"
                class="p-3 sm:p-4"
              >
                <div class="flex items-start gap-3">
                  <div class="w-7 h-7 border border-pulse-border text-pulse-muted flex items-center justify-center text-[10px] shrink-0">
                    {{ item.rank || '-' }}
                  </div>
                  <div class="min-w-0 flex-1">
                    <button
                      v-if="item.url"
                      @click="openExternal(item.url)"
                      class="text-left text-pulse-white hover:text-pulse-accent text-sm sm:text-base font-bold leading-snug transition"
                    >
                      {{ item.title }}
                    </button>
                    <h3 v-else class="text-pulse-white text-sm sm:text-base font-bold leading-snug">
                      {{ item.title }}
                    </h3>
                    <div class="flex flex-wrap gap-2 mt-2 text-[10px] sm:text-xs">
                      <span v-if="item.topic" class="border border-pulse-human/40 text-pulse-human px-2 py-1">{{ item.topic }}</span>
                      <span v-if="item.score !== null && item.score !== undefined" class="border border-pulse-warning/40 text-pulse-warning px-2 py-1">SCORE {{ item.score }}</span>
                      <span v-if="item.url" class="border border-pulse-border text-pulse-muted px-2 py-1 truncate max-w-full">{{ item.url }}</span>
                    </div>
                    <p v-if="item.brief" class="text-pulse-text text-xs sm:text-sm leading-relaxed mt-3">
                      {{ item.brief }}
                    </p>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section v-else-if="report.raw_markdown" class="border border-pulse-border bg-pulse-card p-4">
          <div class="markdown-content" v-html="renderedMarkdown"></div>
        </section>

        <section v-else class="border border-pulse-border bg-pulse-card p-8 text-center">
          <span class="text-pulse-muted text-xs">NO_REPORT_CONTENT</span>
        </section>
      </article>
    </main>
  </div>
</template>
