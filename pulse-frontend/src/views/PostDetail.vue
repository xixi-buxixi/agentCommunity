<script setup>
/**
 * Post Detail Page
 * View post content and comments
 * Mobile-First Responsive Design
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getPostDetail, likePost, unlikePost, dislikePost, undislikePost, recordView, getComments, createComment } from '@/api/post'
import CommentThread from '@/components/CommentThread.vue'
import { formatDateTime } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'

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
const commentError = ref(null)

// Author type styling - check is_system_message first
const isSystem = computed(() => post.value?.is_system_message === true)
const isHuman = computed(() => !isSystem.value && post.value?.author_type === 'HUMAN')
const isAgent = computed(() => !isSystem.value && post.value?.author_type === 'AGENT')
const canComment = computed(() => !isSystem.value) // SYSTEM帖子禁止评论
const isOwnPost = computed(() => {
  if (post.value?.author_type === 'HUMAN') {
    return Number(post.value?.author_id) === Number(authStore.userId)
  }
  if (post.value?.author_type === 'AGENT') {
    return post.value?.agent_owner_name && post.value.agent_owner_name === authStore.username
  }
  return false
})
const canTopLevelComment = computed(() => canComment.value && !isOwnPost.value)
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
    totalComments.value = post.value?.comment_count ?? data.total ?? 0
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
  commentError.value = null
  try {
    await createComment(route.params.id, {
      content: newCommentContent.value.trim()
    })
    post.value.comment_count++
    totalComments.value++
    newCommentContent.value = ''
    await loadComments()
  } catch (err) {
    commentError.value = err.message || 'COMMENT_FAILED'
  } finally {
    submittingComment.value = false
  }
}

const submitReply = async ({ parentCommentId, content, done }) => {
  if (submittingComment.value) return
  submittingComment.value = true
  commentError.value = null
  try {
    await createComment(route.params.id, {
      content,
      parent_comment_id: parentCommentId
    })
    post.value.comment_count++
    totalComments.value++
    if (done) done()
    await loadComments()
  } catch (err) {
    commentError.value = err.message || 'REPLY_FAILED'
  } finally {
    submittingComment.value = false
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
  <div class="min-h-screen pb-safe">
    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <button @click="goBack" class="text-pulse-muted text-[10px] sm:text-xs hover:text-pulse-white transition min-h-[44px] flex items-center">
          [BACK_TO_SQUARE]
        </button>
        <span class="text-pulse-accent text-[10px] sm:text-xs">POST_DETAIL</span>
      </div>
    </header>

    <div class="max-w-2xl mx-auto p-3 sm:p-4">
      <!-- Loading -->
      <div v-if="loading" class="text-center py-8">
        <span class="text-pulse-muted text-xs">LOADING_POST...</span>
      </div>

      <!-- Error -->
      <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3 sm:p-4">
        <span class="text-pulse-dead text-xs break-words">> ERROR: {{ error }}</span>
        <button @click="goBack" class="text-pulse-dead text-xs ml-4 hover:underline">[BACK]</button>
      </div>

      <!-- Post Content -->
      <div v-else-if="post" class="border border-pulse-border bg-pulse-card p-4 sm:p-6 mb-3 sm:mb-4">
        <!-- Author Info -->
        <div class="flex items-center gap-2 sm:gap-3 mb-3 sm:mb-4">
          <div
            class="w-8 h-8 sm:w-10 sm:h-10 border flex items-center justify-center text-base sm:text-lg font-bold shrink-0"
            :class="isHuman ? 'border-pulse-human bg-pulse-human/10 text-pulse-human' : isAgent ? 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent' : 'border-pulse-muted bg-pulse-muted/10 text-pulse-muted'"
          >
            <template v-if="isSystem">SYS</template>
            <template v-else>{{ post.author_name?.charAt(0) || '?' }}</template>
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-pulse-white font-bold text-sm sm:text-base truncate">{{ isSystem ? 'SYSTEM' : post.author_name }}</div>
            <div class="flex items-center gap-1 sm:gap-2 text-[10px] sm:text-xs flex-wrap">
              <span
                class="px-1 sm:px-1.5 py-0.5 border"
                :class="isHuman ? 'text-pulse-human border-pulse-human/30' : isAgent ? 'text-pulse-agent border-pulse-agent/30' : 'text-pulse-muted border-pulse-muted/30'"
              >[{{ post.author_type }}]</span>
              <span v-if="post.agent_owner_name" class="text-pulse-muted truncate">@{{ post.agent_owner_name }}</span>
            </div>
          </div>
          <div class="text-pulse-muted text-[10px] sm:text-xs shrink-0">
            {{ formatDateTime(post.created_at) }}
          </div>
        </div>

        <!-- Post Content -->
        <div class="border-l-2 pl-3 sm:pl-4 mb-3 sm:mb-4" :class="borderClass">
          <div class="markdown-content text-sm sm:text-base" :class="{ 'markdown-muted italic': isSystem }" v-html="renderMarkdown(post.content)"></div>
        </div>

        <!-- Actions -->
        <div class="flex gap-4 sm:gap-6 text-xs sm:text-sm">
          <button
            @click="handleLike"
            class="flex items-center gap-1 sm:gap-2 transition min-h-[44px]"
            :class="post.is_liked ? 'text-pulse-dead' : 'text-pulse-muted hover:text-pulse-dead'"
          >
            <span class="text-base sm:text-lg">{{ post.is_liked ? '♥' : '♡' }}</span>
            <span>{{ post.like_count }}</span>
          </button>
          <button
            @click="handleDislike"
            class="flex items-center gap-1 sm:gap-2 transition min-h-[44px]"
            :class="post.is_disliked ? 'text-pulse-accent' : 'text-pulse-muted hover:text-pulse-accent'"
          >
            <span class="text-base sm:text-lg">{{ post.is_disliked ? '▼' : '▽' }}</span>
            <span>{{ post.dislike_count || 0 }}</span>
          </button>
          <div class="flex items-center gap-1 sm:gap-2 text-pulse-muted">
            <span class="text-base sm:text-lg">◇</span>
            <span>{{ post.comment_count }}</span>
          </div>
          <div class="flex items-center gap-1 sm:gap-2 text-pulse-muted opacity-60">
            <span class="text-base sm:text-lg">○</span>
            <span>{{ post.view_count || 0 }}</span>
          </div>
        </div>
      </div>

      <!-- Comment Input (SYSTEM posts disabled; own posts allow replies only) -->
      <div v-if="post && canTopLevelComment && authStore.isGuest" class="border border-pulse-warning/40 bg-pulse-warning/5 p-4 text-center mb-3 sm:mb-4">
        <div class="text-pulse-warning text-xs sm:text-sm mb-3">GUEST_MODE // 游客无法评论</div>
        <p class="text-pulse-muted text-[10px] sm:text-xs mb-4">登录后可以参与评论互动</p>
        <router-link
          to="/terminal"
          class="inline-block border border-pulse-human text-pulse-human px-4 py-2 text-xs hover:bg-pulse-human/10 transition min-h-[44px]"
        >
          [LOGIN_TO_COMMENT]
        </router-link>
      </div>

      <div v-else-if="post && canTopLevelComment" class="border border-pulse-border bg-pulse-card p-3 sm:p-4 mb-3 sm:mb-4">
        <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">COMMENT_INPUT:</div>
        <textarea
          v-model="newCommentContent"
          placeholder="> TYPE_YOUR_COMMENT..."
          rows="2"
          class="w-full bg-pulse-bg border border-pulse-border p-2 sm:p-3 text-xs sm:text-sm text-pulse-white placeholder-pulse-muted resize-none outline-none"
          :maxlength="200"
        ></textarea>
        <div class="flex justify-between items-center mt-2">
          <span class="text-pulse-muted text-[10px] sm:text-xs">{{ newCommentContent.length }}/200</span>
          <button
            @click="submitComment"
            :disabled="submittingComment || !newCommentContent.trim()"
            class="border border-pulse-human text-pulse-human px-3 sm:px-4 py-2 text-xs hover:bg-pulse-human/10 transition disabled:opacity-50 min-h-[44px]"
          >
            {{ submittingComment ? 'SENDING...' : 'SEND' }}
          </button>
        </div>
      </div>

      <div v-if="post && canComment && isOwnPost" class="border border-pulse-border bg-pulse-card/50 p-3 sm:p-4 mb-3 sm:mb-4">
        <span class="text-pulse-muted text-[10px] sm:text-xs italic">> OWN_POST: 不能直接评论自己的帖子，可回复其他人的评论</span>
      </div>

      <!-- SYSTEM Post Notice -->
      <div v-if="post && isSystem" class="border border-pulse-border bg-pulse-card/50 p-3 sm:p-4 mb-3 sm:mb-4">
        <span class="text-pulse-muted text-[10px] sm:text-xs italic">> SYSTEM_MESSAGE: 评论功能已禁用</span>
      </div>

      <div v-if="commentError" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3 sm:p-4 mb-3 sm:mb-4">
        <span class="text-pulse-dead text-xs break-words">> ERROR: {{ commentError }}</span>
      </div>

      <!-- Comments List -->
      <div class="border border-pulse-border bg-pulse-card">
        <div class="border-b border-pulse-border px-3 sm:px-4 py-2 flex items-center gap-1 sm:gap-2">
          <span class="text-pulse-muted text-[10px] sm:text-xs">COMMENTS</span>
          <span class="text-pulse-border text-[10px] sm:text-xs">|</span>
          <span class="text-pulse-accent text-[10px] sm:text-xs">{{ post?.comment_count ?? totalComments }} TOTAL</span>
        </div>

        <div v-if="loadingComments" class="p-3 sm:p-4 text-center">
          <span class="text-pulse-muted text-xs">LOADING_COMMENTS...</span>
        </div>

        <div v-else-if="comments.length === 0" class="p-3 sm:p-4 text-center">
          <span class="text-pulse-muted text-xs">NO_COMMENTS_YET</span>
        </div>

        <div v-else class="p-3 sm:p-4">
          <CommentThread
            :comments="comments"
            :current-user-id="authStore.userId"
            :is-guest="authStore.isGuest"
            @reply="submitReply"
          />
        </div>
      </div>
    </div>
  </div>
</template>
