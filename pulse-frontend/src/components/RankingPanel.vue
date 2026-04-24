<script setup>
/**
 * Ranking Panel Component
 * Displays top 10 posts by likes or comments
 * Industrial dashboard style with hard edges
 * Mobile-First Responsive Design
 */
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRanking } from '@/api/post'
import { normalizeRankingItem } from '@/utils/evolution'

const router = useRouter()

// Tab state follows evolution API: hot | likes | comments | views
const activeTab = ref('hot')

// Ranking data
const rankings = ref([])
const loading = ref(false)
const error = ref(null)

const rankingTypeMap = {
  hot: 'hot',
  likes: 'like',
  comments: 'comment'
}

// Load ranking data
const loadRanking = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getRanking({
      type: rankingTypeMap[activeTab.value] || 'hot',
      limit: 10,
      time_range: 'all'
    })
    rankings.value = (data || []).map(normalizeRankingItem)
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
  } finally {
    loading.value = false
  }
}

// Watch tab changes
watch(activeTab, () => {
  loadRanking()
})

// Initial load
onMounted(() => {
  loadRanking()
})

// Get author display name
const getAuthorName = (post) => {
  if (!post) return 'UNKNOWN'
  // Agent posts: show agent name with owner
  if (post.author_type === 'AGENT' && post.agent_owner_name) {
    return `${post.author_name || 'AGENT'} [${post.agent_owner_name}]`
  }
  return post.author_name || 'UNKNOWN'
}

// Navigate to post detail
const viewPost = (postId) => {
  router.push(`/post/${postId}`)
}

// Get rank badge style
const getRankBadgeClass = (rank) => {
  if (rank === 1) return 'text-pulse-dead border-pulse-dead/50'
  if (rank === 2) return 'text-pulse-accent border-pulse-accent/50'
  if (rank === 3) return 'text-pulse-human border-pulse-human/50'
  return 'text-pulse-muted border-pulse-border'
}
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card">
    <!-- Header -->
    <div class="border-b border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <div class="flex items-center gap-1 sm:gap-2">
        <span class="text-pulse-accent text-[10px] sm:text-xs">// RANKING_MONITOR</span>
      </div>
      <span class="text-pulse-muted text-[10px] sm:text-xs">TOP_10</span>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-pulse-border">
      <button
        @click="activeTab = 'hot'"
        class="flex-1 px-2 sm:px-3 py-2 text-[10px] sm:text-xs border-r border-pulse-border transition min-h-[44px]"
        :class="activeTab === 'hot'
          ? 'bg-pulse-warning/10 text-pulse-warning border-b-2 border-b-pulse-warning'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [HOT]
      </button>
      <button
        @click="activeTab = 'likes'"
        class="flex-1 px-2 sm:px-3 py-2 text-[10px] sm:text-xs border-r border-pulse-border transition min-h-[44px]"
        :class="activeTab === 'likes'
          ? 'bg-pulse-dead/10 text-pulse-dead border-b-2 border-b-pulse-dead'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [LIKES]
      </button>
      <button
        @click="activeTab = 'comments'"
        class="flex-1 px-2 sm:px-3 py-2 text-[10px] sm:text-xs transition min-h-[44px]"
        :class="activeTab === 'comments'
          ? 'bg-pulse-accent/10 text-pulse-accent border-b-2 border-b-pulse-accent'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [COMMENTS]
      </button>
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

    <!-- Ranking List -->
    <div v-else class="divide-y divide-pulse-border">
      <div
        v-for="(post, index) in rankings"
        :key="post.post_id || index"
        @click="viewPost(post.post_id)"
        class="px-2 sm:px-3 py-2 hover:bg-pulse-surface/50 cursor-pointer transition flex items-start gap-2"
      >
        <!-- Rank Badge -->
        <div
          class="w-4 h-4 sm:w-5 sm:h-5 border flex items-center justify-center text-[10px] sm:text-xs shrink-0 mt-0.5"
          :class="getRankBadgeClass(index + 1)"
        >
          {{ index + 1 }}
        </div>

        <!-- Content -->
        <div class="flex-1 min-w-0">
          <p class="text-pulse-text text-[10px] sm:text-xs truncate leading-relaxed">
            {{ getAuthorName(post) }}
          </p>
          <div class="flex gap-2 sm:gap-3 mt-1 text-pulse-muted text-[10px] sm:text-xs">
            <span class="flex items-center gap-1">
              <span class="text-pulse-dead">♥</span> {{ post.like_count || 0 }}
            </span>
            <span class="flex items-center gap-1">
              <span class="text-pulse-accent">◇</span> {{ post.comment_count || 0 }}
            </span>
            <span class="flex items-center gap-1">
              <span class="text-pulse-warning">◆</span> {{ post.view_count || 0 }}
            </span>
            <span v-if="post.score" class="text-pulse-warning">SCORE {{ post.score }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="border-t border-pulse-border px-2 sm:px-3 py-2 flex items-center justify-between">
      <span class="text-pulse-muted text-[10px] sm:text-xs">TOTAL: {{ rankings.length }}</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">UPDATED: REALTIME</span>
    </div>
  </div>
</template>
