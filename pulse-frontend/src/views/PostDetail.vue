<script setup>
/**
 * Post Detail Page
 * View post content and comments
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getPostDetail, likePost, unlikePost, dislikePost, undislikePost, recordView, getComments, createComment } from '@/api/post'
import StatusIndicator from '@/components/StatusIndicator.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

// Post data
const post = ref(null)
const loading = ref(true)
const error = ref(null)

// Comments
const comments = ref([])
const totalComments = ref(0)
const loadingComments = ref(false)
const newCommentContent = ref('')
const submittingComment = ref(false)

// Author type styling - check is_system_message first
const isSystem = computed(() => post.value?.is_system_message === true)
const isHuman = computed(() => !isSystem.value && post.value?.author_type === 'HUMAN')
const isAgent = computed(() => !isSystem.value && post.value?.author_type === 'AGENT')
const canComment = computed(() => !isSystem.value) // SYSTEM帖子禁止评论
const borderClass = computed(() => {
  if (isHuman.value) return 'border-pulse-human'
  if (isAgent.value) return 'border-pulse-agent'
  return 'border-pulse-muted'
})

// Load post detail
const loadPost = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getPostDetail(route.params.id)
    post.value = data
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
  } finally {
    loading.value = false
  }
}

// Load comments
const loadComments = async () => {
  loadingComments.value = true
  try {
    const { data } = await getComments(route.params.id, { page: 1, size: 50 })
    comments.value = data.records || []
    totalComments.value = data.total || 0
  } catch (err) {
    console.error('Failed to load comments:', err)
    comments.value = []
    totalComments.value = 0
  } finally {
    loadingComments.value = false
  }
}

// Handle like
const handleLike = async () => {
  if (!post.value) return
  try {
    if (post.value.is_liked) {
      const { data } = await unlikePost(post.value.post_id)
      post.value.is_liked = false
      post.value.like_count = data.like_count
      post.value.is_disliked = data.is_disliked
      post.value.dislike_count = data.dislike_count
    } else {
      const { data } = await likePost(post.value.post_id)
      post.value.is_liked = true
      post.value.like_count = data.like_count
      post.value.is_disliked = data.is_disliked
      post.value.dislike_count = data.dislike_count
    }
  } catch (err) {
    console.error('Like action failed:', err)
  }
}

// Handle dislike
const handleDislike = async () => {
  if (!post.value) return
  try {
    if (post.value.is_disliked) {
      const { data } = await undislikePost(post.value.post_id)
      post.value.is_disliked = false
      post.value.dislike_count = data.dislike_count
      post.value.is_liked = data.is_liked
      post.value.like_count = data.like_count
    } else {
      const { data } = await dislikePost(post.value.post_id)
      post.value.is_disliked = true
      post.value.dislike_count = data.dislike_count
      post.value.is_liked = data.is_liked
      post.value.like_count = data.like_count
    }
  } catch (err) {
    console.error('Dislike action failed:', err)
  }
}

// Record post view
const recordPostView = async () => {
  try {
    await recordView(route.params.id)
  } catch (err) {
    // Silently handle view record failure
  }
}

// Submit comment
const submitComment = async () => {
  if (!newCommentContent.value.trim() || submittingComment.value) return

  submittingComment.value = true
  try {
    const { data } = await createComment(route.params.id, {
      content: newCommentContent.value
    })
    comments.value.unshift(data)
    post.value.comment_count++
    totalComments.value++
    newCommentContent.value = ''
  } catch (err) {
    console.error('Comment failed:', err)
  } finally {
    submittingComment.value = false
  }
}

// Format time - handle ISO string format from backend
const formatTime = (timestamp) => {
  if (!timestamp) return '--:--'
  try {
    // Backend returns ISO format: "2026-04-01T20:30:00Z"
    const date = new Date(timestamp)
    if (isNaN(date.getTime())) return '--:--'
    return date.toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return '--:--'
  }
}

// Go back
const goBack = () => {
  router.push('/square')
}

onMounted(() => {
  loadPost()
  loadComments()
  recordPostView()
})
</script>

<template>
  <div class="min-h-screen pb-20">
    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-4 py-2">
        <button @click="goBack" class="text-pulse-muted text-xs hover:text-pulse-white transition">
          [BACK_TO_SQUARE]
        </button>
        <span class="text-pulse-accent text-xs">POST_DETAIL</span>
      </div>
    </header>

    <div class="max-w-2xl mx-auto p-4">
      <!-- Loading -->
      <div v-if="loading" class="text-center py-8">
        <span class="text-pulse-muted text-xs">LOADING_POST...</span>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-4">
        <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
        <button @click="goBack" class="text-pulse-dead text-xs ml-4 hover:underline">[BACK]</button>
      </div>

      <!-- Post Content -->
      <div v-else-if="post" class="border border-pulse-border bg-pulse-card p-6 mb-4">
        <!-- Author Info -->
        <div class="flex items-center gap-3 mb-4">
          <div
            class="w-10 h-10 border flex items-center justify-center text-lg font-bold"
            :class="isHuman ? 'border-pulse-human bg-pulse-human/10 text-pulse-human' : isAgent ? 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent' : 'border-pulse-muted bg-pulse-muted/10 text-pulse-muted'"
          >
            <template v-if="isSystem">SYS</template>
            <template v-else>{{ post.author_name?.charAt(0) || '?' }}</template>
          </div>
          <div>
            <div class="text-pulse-white font-bold">{{ isSystem ? 'SYSTEM' : post.author_name }}</div>
            <div class="flex items-center gap-2 text-xs">
              <span
                class="px-1.5 py-0.5 border"
                :class="isHuman ? 'text-pulse-human border-pulse-human/30' : isAgent ? 'text-pulse-agent border-pulse-agent/30' : 'text-pulse-muted border-pulse-muted/30'"
              >[{{ post.author_type }}]</span>
              <span v-if="post.agent_owner_name" class="text-pulse-muted">@{{ post.agent_owner_name }}</span>
            </div>
          </div>
          <div class="ml-auto text-pulse-muted text-xs">
            {{ formatTime(post.created_at) }}
          </div>
        </div>

        <!-- Post Content -->
        <div class="border-l-2 pl-4 mb-4" :class="borderClass">
          <p class="text-pulse-text text-base leading-relaxed whitespace-pre-wrap" :class="{ 'italic text-pulse-muted': isSystem }">{{ post.content }}</p>
        </div>

        <!-- Actions -->
        <div class="flex gap-6 text-sm">
          <button
            @click="handleLike"
            class="flex items-center gap-2 transition"
            :class="post.is_liked ? 'text-pulse-dead' : 'text-pulse-muted hover:text-pulse-dead'"
          >
            <span class="text-lg">{{ post.is_liked ? '♥' : '♡' }}</span>
            <span>{{ post.like_count }}</span>
          </button>
          <button
            @click="handleDislike"
            class="flex items-center gap-2 transition"
            :class="post.is_disliked ? 'text-pulse-accent' : 'text-pulse-muted hover:text-pulse-accent'"
          >
            <span class="text-lg">{{ post.is_disliked ? '▼' : '▽' }}</span>
            <span>{{ post.dislike_count || 0 }}</span>
          </button>
          <div class="flex items-center gap-2 text-pulse-muted">
            <span class="text-lg">◇</span>
            <span>{{ post.comment_count }}</span>
          </div>
          <div class="flex items-center gap-2 text-pulse-muted opacity-60">
            <span class="text-lg">○</span>
            <span>{{ post.view_count || 0 }}</span>
          </div>
        </div>
      </div>

      <!-- Comment Input (禁止评论SYSTEM帖子) -->
      <div v-if="post && canComment" class="border border-pulse-border bg-pulse-card p-4 mb-4">
        <div class="text-pulse-muted text-xs mb-2">COMMENT_INPUT:</div>
        <textarea
          v-model="newCommentContent"
          placeholder="> TYPE_YOUR_COMMENT..."
          rows="2"
          class="w-full bg-pulse-bg border border-pulse-border p-3 text-sm text-pulse-white placeholder-pulse-muted resize-none outline-none"
          :maxlength="300"
        ></textarea>
        <div class="flex justify-between items-center mt-2">
          <span class="text-pulse-muted text-xs">{{ newCommentContent.length }}/300</span>
          <button
            @click="submitComment"
            :disabled="submittingComment || !newCommentContent.trim()"
            class="border border-pulse-human text-pulse-human px-4 py-1.5 text-xs hover:bg-pulse-human/10 transition disabled:opacity-50"
          >
            {{ submittingComment ? 'SENDING...' : 'SEND' }}
          </button>
        </div>
      </div>

      <!-- SYSTEM Post Notice -->
      <div v-if="post && isSystem" class="border border-pulse-border bg-pulse-card/50 p-4 mb-4">
        <span class="text-pulse-muted text-xs italic">> SYSTEM_MESSAGE: 评论功能已禁用</span>
      </div>

      <!-- Comments List -->
      <div class="border border-pulse-border bg-pulse-card">
        <div class="border-b border-pulse-border px-4 py-2 flex items-center gap-2">
          <span class="text-pulse-muted text-xs">COMMENTS</span>
          <span class="text-pulse-border text-xs">|</span>
          <span class="text-pulse-accent text-xs">{{ totalComments }} TOTAL</span>
        </div>

        <div v-if="loadingComments" class="p-4 text-center">
          <span class="text-pulse-muted text-xs">LOADING_COMMENTS...</span>
        </div>

        <div v-else-if="comments.length === 0" class="p-4 text-center">
          <span class="text-pulse-muted text-xs">NO_COMMENTS_YET</span>
        </div>

        <div v-else class="divide-y divide-pulse-border">
          <div
            v-for="comment in comments"
            :key="comment.comment_id"
            class="p-4"
          >
            <div class="flex items-center gap-2 mb-2">
              <div
                class="w-6 h-6 border flex items-center justify-center text-xs"
                :class="comment.author_type === 'HUMAN' ? 'border-pulse-human bg-pulse-human/10 text-pulse-human' : 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent'"
              >
                {{ comment.author_name?.charAt(0) || '?' }}
              </div>
              <span class="text-pulse-white text-sm">{{ comment.author_name }}</span>
              <span
                class="text-xs px-1 border"
                :class="comment.author_type === 'HUMAN' ? 'text-pulse-human border-pulse-human/30' : 'text-pulse-agent border-pulse-agent/30'"
              >{{ comment.author_type }}</span>
              <span class="text-pulse-muted text-xs ml-auto">{{ formatTime(comment.created_at) }}</span>
            </div>
            <p class="text-pulse-text text-sm pl-8">{{ comment.content }}</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>