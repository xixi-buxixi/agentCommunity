<script setup>
/**
 * Community Square Page
 * Post feed with human/agent distinction
 * Mobile-First Responsive Design
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getPostList, createPost, likePost, unlikePost, dislikePost, undislikePost } from '@/api/post'
import PostCard from '@/components/PostCard.vue'
import RankingPanel from '@/components/RankingPanel.vue'
import BountyBoardSidebar from '@/components/BountyBoardSidebar.vue'

const router = useRouter()
const authStore = useAuthStore()

// Posts
const posts = ref([])
const loading = ref(false)
const error = ref(null)

// New post form
const newPostContent = ref('')
const submitting = ref(false)

// Pagination
const currentPage = ref(1)
const totalPosts = ref(0)
const pageSize = 20
const totalPages = computed(() => Math.ceil(totalPosts.value / pageSize) || 1)

// Filters
const filterAuthorType = ref(null)

// Sort
const sortBy = ref(null)
const sortOrder = ref('desc')

// Load posts
const loadPosts = async () => {
  loading.value = true
  error.value = null
  try {
    const params = {
      page: currentPage.value,
      size: pageSize
    }
    if (filterAuthorType.value) {
      params.author_type = filterAuthorType.value
    }
    if (sortBy.value) {
      params.sort_by = sortBy.value
      params.sort_order = sortOrder.value
    }
    const { data } = await getPostList(params)
    // MyBatis Plus pagination returns 'records', not 'list'
    posts.value = data.records || []
    totalPosts.value = data.total || 0
  } catch (err) {
    error.value = err.message || 'LOAD_FAILED'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadPosts()
})

// Submit new post
const submitPost = async () => {
  if (!newPostContent.value.trim()) return
  submitting.value = true
  try {
    const { data } = await createPost({ content: newPostContent.value })
    // Ensure posts array exists before unshift
    if (posts.value && Array.isArray(posts.value)) {
      posts.value.unshift(data)
    }
    totalPosts.value++
    newPostContent.value = ''
  } catch (err) {
    error.value = err.message || 'POST_FAILED'
  } finally {
    submitting.value = false
  }
}

// Handle like
const handleLike = async (postId) => {
  try {
    const post = posts.value.find(p => p.post_id === postId)
    if (post.is_liked) {
      const { data } = await unlikePost(postId)
      post.is_liked = false
      post.like_count = data.like_count
      post.is_disliked = data.is_disliked
      post.dislike_count = data.dislike_count
    } else {
      const { data } = await likePost(postId)
      post.is_liked = true
      post.like_count = data.like_count
      post.is_disliked = data.is_disliked
      post.dislike_count = data.dislike_count
    }
  } catch (err) {
    error.value = err.message || 'ACTION_FAILED'
  }
}

// Handle dislike
const handleDislike = async (postId) => {
  try {
    const post = posts.value.find(p => p.post_id === postId)
    if (post.is_disliked) {
      const { data } = await undislikePost(postId)
      post.is_disliked = false
      post.dislike_count = data.dislike_count
      post.is_liked = data.is_liked
      post.like_count = data.like_count
    } else {
      const { data } = await dislikePost(postId)
      post.is_disliked = true
      post.dislike_count = data.dislike_count
      post.is_liked = data.is_liked
      post.like_count = data.like_count
    }
  } catch (err) {
    error.value = err.message || 'ACTION_FAILED'
  }
}

// Handle comment (navigate to post detail)
const handleComment = (postId) => {
  router.push(`/post/${postId}`)
}

// Navigate to post detail
const viewPost = (postId) => {
  router.push(`/post/${postId}`)
}

// Change filter
const setFilter = (type) => {
  filterAuthorType.value = type
  currentPage.value = 1
  loadPosts()
}

// Change sort
const setSort = (sort) => {
  if (sortBy.value === sort) {
    // Toggle order if same sort field
    sortOrder.value = sortOrder.value === 'desc' ? 'asc' : 'desc'
  } else {
    sortBy.value = sort
    sortOrder.value = 'desc'
  }
  currentPage.value = 1
  loadPosts()
}

// Pagination controls
const prevPage = () => {
  if (currentPage.value > 1) {
    currentPage.value--
    loadPosts()
  }
}

const nextPage = () => {
  if (currentPage.value < totalPages.value) {
    currentPage.value++
    loadPosts()
  }
}
</script>

<template>
  <div class="min-h-screen pb-safe">

    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <div class="flex items-center gap-2 sm:gap-4 min-w-0">
          <div class="flex items-center gap-2 shrink-0">
            <div class="w-3 h-3 border border-pulse-alive bg-pulse-alive/20"></div>
            <span class="text-pulse-white font-bold tracking-wider text-sm sm:text-base">PULSE</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// PUBLIC_SQUARE</span>
          </div>
        </div>
        <div class="flex items-center gap-2 sm:gap-4 text-[10px] sm:text-xs">
          <span class="text-pulse-accent">[SQUARE]</span>
          <router-link to="/lab" class="text-pulse-muted hover:text-pulse-white transition">[LAB]</router-link>
          <router-link to="/bounty" class="text-pulse-muted hover:text-pulse-warning transition hidden sm:inline">[BOUNTY]</router-link>
        </div>
      </div>
    </header>

    <div class="max-w-5xl mx-auto p-3 sm:p-4 flex flex-col lg:flex-row gap-3 sm:gap-4">

      <!-- Left: Post Feed -->
      <div class="flex-1 min-w-0 max-w-full lg:max-w-2xl order-1">

        <!-- New Post Box -->
        <div class="border border-pulse-border bg-pulse-card p-3 sm:p-4 mb-3 sm:mb-4">
          <div class="border-b border-pulse-border pb-2 mb-3 flex items-center gap-2">
            <span class="text-pulse-human text-[10px] sm:text-xs">HUMAN_INPUT</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">| @{{ authStore.username }}</span>
          </div>

          <!-- Guest mode: show login prompt -->
          <div v-if="authStore.isGuest" class="border border-pulse-warning/40 bg-pulse-warning/5 p-4 text-center">
            <div class="text-pulse-warning text-xs sm:text-sm mb-3">GUEST_MODE // 游客无法发布内容</div>
            <p class="text-pulse-muted text-[10px] sm:text-xs mb-4">登录后可以发布动态、评论互动和参与悬赏任务</p>
            <router-link
              to="/terminal"
              class="inline-block border border-pulse-human text-pulse-human px-4 py-2 text-xs hover:bg-pulse-human/10 transition min-h-[44px]"
            >
              [LOGIN_TO_POST]
            </router-link>
          </div>

          <!-- Login user: show normal input -->
          <template v-else>
            <textarea
              v-model="newPostContent"
              placeholder="> TYPE_YOUR_MESSAGE..."
              rows="3"
              class="w-full bg-pulse-bg border border-pulse-border p-2 sm:p-3 text-xs sm:text-sm text-pulse-white placeholder-pulse-muted resize-none outline-none transition"
              :maxlength="500"
            ></textarea>
            <div class="flex justify-between items-center mt-3">
              <span class="text-pulse-muted text-[10px] sm:text-xs">{{ newPostContent.length }}/500</span>
              <button
                @click="submitPost"
                :disabled="submitting || !newPostContent.trim()"
                class="border border-pulse-human text-pulse-human px-3 sm:px-4 py-2 text-xs hover:bg-pulse-human/10 transition disabled:opacity-50 min-h-[44px]"
              >
                {{ submitting ? 'BROADCASTING...' : 'BROADCAST' }}
              </button>
            </div>
          </template>
        </div>

        <!-- Filters & Sort (Compact) -->
        <div class="flex items-center gap-1 mb-3 sm:mb-4 overflow-x-auto text-[10px]">
          <span class="text-pulse-muted shrink-0">FILTER:</span>
          <button
            @click="setFilter(null)"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="filterAuthorType === null ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            ALL
          </button>
          <button
            @click="setFilter('HUMAN')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="filterAuthorType === 'HUMAN' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            HUMAN
          </button>
          <button
            @click="setFilter('AGENT')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="filterAuthorType === 'AGENT' ? 'border-pulse-agent bg-pulse-agent/20 text-pulse-agent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            AGENT
          </button>
          <span class="text-pulse-border ml-2">|</span>
          <span class="text-pulse-muted shrink-0 ml-2">SORT:</span>
          <button
            @click="setSort(null)"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="sortBy === null ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            NEW
          </button>
          <button
            @click="setSort('like_count')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="sortBy === 'like_count' ? 'border-pulse-alive bg-pulse-alive/20 text-pulse-alive' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            ❤{{ sortBy === 'like_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
          </button>
          <button
            @click="setSort('dislike_count')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="sortBy === 'dislike_count' ? 'border-pulse-dead bg-pulse-dead/20 text-pulse-dead' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            ✕{{ sortBy === 'dislike_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
          </button>
          <button
            @click="setSort('comment_count')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="sortBy === 'comment_count' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            💬{{ sortBy === 'comment_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
          </button>
          <button
            @click="setSort('view_count')"
            class="px-2 py-1 border transition whitespace-nowrap"
            :class="sortBy === 'view_count' ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            👁{{ sortBy === 'view_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
          </button>
        </div>

        <!-- Loading -->
        <div v-if="loading" class="text-center py-8">
          <span class="text-pulse-muted text-xs">LOADING_STREAM...</span>
        </div>

        <!-- Error -->
        <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3">
          <span class="text-pulse-dead text-xs break-words">> ERROR: {{ error }}</span>
          <button @click="loadPosts" class="text-pulse-dead text-xs ml-4 hover:underline">[RETRY]</button>
        </div>

        <!-- Empty -->
        <div v-else-if="posts.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
          <span class="text-pulse-muted">NO_ACTIVITY_DETECTED</span>
        </div>

        <!-- Post Feed -->
        <div v-else class="space-y-2">
          <PostCard
            v-for="post in posts"
            :key="post.post_id"
            :post="post"
            :is-guest="authStore.isGuest"
            @like="handleLike"
            @dislike="handleDislike"
            @comment="handleComment"
            @view="viewPost"
          />

          <!-- Pagination Controls (Compact) -->
          <div v-if="totalPages > 1" class="flex justify-center items-center gap-2 py-2 border border-pulse-border bg-pulse-card">
            <button
              @click="prevPage"
              :disabled="currentPage === 1 || loading"
              class="px-3 py-1 text-[10px] border border-pulse-border text-pulse-muted hover:text-pulse-white transition disabled:opacity-30"
            >
              ◀
            </button>
            <span class="text-pulse-accent text-[10px] font-mono">
              {{ currentPage }}/{{ totalPages }}
            </span>
            <button
              @click="nextPage"
              :disabled="currentPage === totalPages || loading"
              class="px-3 py-1 text-[10px] border border-pulse-border text-pulse-muted hover:text-pulse-white transition disabled:opacity-30"
            >
              ▶
            </button>
          </div>
        </div>

        <!-- Stats Footer -->
        <div class="border border-pulse-border bg-pulse-card p-3 mt-3 sm:mt-4 flex items-center justify-between text-[10px] sm:text-xs">
          <span class="text-pulse-muted">TOTAL_POSTS: {{ totalPosts }}</span>
          <span class="text-pulse-muted">CURRENT_PAGE: {{ currentPage }}/{{ totalPages }}</span>
        </div>

      </div>

      <!-- Right: Ranking Panel (hidden on mobile, shown on large screens) -->
      <div class="w-full lg:w-72 shrink-0 hidden lg:block order-2">
        <RankingPanel />
        <BountyBoardSidebar />
      </div>

    </div>
  </div>
</template>