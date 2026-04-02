<script setup>
/**
 * Ranking Panel Component
 * Displays top 10 posts by likes or comments
 * Industrial dashboard style with hard edges
 */
import { ref, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getRanking } from '@/api/post'

const router = useRouter()

// Tab state: 'like' | 'comment'
const activeTab = ref('like')

// Ranking data
const rankings = ref([])
const loading = ref(false)
const error = ref(null)

// Load ranking data
const loadRanking = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getRanking({
      type: activeTab.value,
      limit: 10
    })
    rankings.value = data || []
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
    <div class="border-b border-pulse-border px-3 py-2 flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-pulse-accent text-xs">// RANKING_MONITOR</span>
      </div>
      <span class="text-pulse-muted text-xs">TOP_10</span>
    </div>

    <!-- Tabs -->
    <div class="flex border-b border-pulse-border">
      <button
        @click="activeTab = 'like'"
        class="flex-1 px-3 py-2 text-xs border-r border-pulse-border transition"
        :class="activeTab === 'like'
          ? 'bg-pulse-dead/10 text-pulse-dead border-b-2 border-b-pulse-dead'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [LIKES]
      </button>
      <button
        @click="activeTab = 'comment'"
        class="flex-1 px-3 py-2 text-xs transition"
        :class="activeTab === 'comment'
          ? 'bg-pulse-accent/10 text-pulse-accent border-b-2 border-b-pulse-accent'
          : 'text-pulse-muted hover:text-pulse-white'"
      >
        [COMMENTS]
      </button>
    </div>

    <!-- Loading -->
    <div v-if="loading" class="p-4 text-center">
      <span class="text-pulse-muted text-xs">SCANNING...</span>
    </div>

    <!-- Error -->
    <div v-else-if="error" class="p-3 bg-pulse-dead/5 border-b border-pulse-border">
      <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
      <button @click="loadRanking" class="text-pulse-dead text-xs ml-2 hover:underline">[RETRY]</button>
    </div>

    <!-- Empty -->
    <div v-else-if="rankings.length === 0" class="p-4 text-center">
      <span class="text-pulse-muted text-xs">NO_DATA_DETECTED</span>
    </div>

    <!-- Ranking List -->
    <div v-else class="divide-y divide-pulse-border">
      <div
        v-for="(post, index) in rankings"
        :key="post.post_id"
        @click="viewPost(post.post_id)"
        class="px-3 py-2 hover:bg-pulse-surface/50 cursor-pointer transition flex items-start gap-2"
      >
        <!-- Rank Badge -->
        <div
          class="w-5 h-5 border flex items-center justify-center text-xs shrink-0 mt-0.5"
          :class="getRankBadgeClass(index + 1)"
        >
          {{ index + 1 }}
        </div>

        <!-- Content -->
        <div class="flex-1 min-w-0">
          <p class="text-pulse-text text-xs truncate leading-relaxed">
            {{ getAuthorName(post) }}
          </p>
          <div class="flex gap-3 mt-1 text-pulse-muted text-xs">
            <span class="flex items-center gap-1">
              <span class="text-pulse-dead">♥</span> {{ post.like_count || 0 }}
            </span>
            <span class="flex items-center gap-1">
              <span class="text-pulse-accent">◇</span> {{ post.comment_count || 0 }}
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="border-t border-pulse-border px-3 py-2 flex items-center justify-between">
      <span class="text-pulse-muted text-xs">TOTAL: {{ rankings.length }}</span>
      <span class="text-pulse-muted text-xs">UPDATED: REALTIME</span>
    </div>
  </div>
</template>