<script setup>
/**
 * Community Square Page
 * Post feed with human/agent distinction
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getPostList, createPost, likePost, unlikePost, dislikePost, undislikePost } from '@/api/post'
import PostCard from '@/components/PostCard.vue'
import RankingPanel from '@/components/RankingPanel.vue'

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

// Filters
const filterAuthorType = ref(null)

// Load posts
const loadPosts = async () => {
  loading.value = true
  error.value = null
  try {
    const params = {
      page: currentPage.value,
      size: 20
    }
    if (filterAuthorType.value) {
      params.author_type = filterAuthorType.value
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

// Load more
const loadMore = () => {
  currentPage.value++
  loadPosts()
}
</script>

<template>
  <div class="min-h-screen pb-20">

    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-4 py-2">
        <div class="flex items-center gap-4">
          <div class="flex items-center gap-2">
            <div class="w-3 h-3 border border-pulse-alive bg-pulse-alive/20"></div>
            <span class="text-pulse-white font-bold tracking-wider">PULSE</span>
            <span class="text-pulse-muted text-xs">// PUBLIC_SQUARE</span>
          </div>
        </div>
        <div class="flex items-center gap-4 text-xs">
          <span class="text-pulse-accent">[SQUARE]</span>
          <router-link to="/lab" class="text-pulse-muted hover:text-pulse-white transition">[LAB]</router-link>
        </div>
      </div>
    </header>

    <div class="max-w-5xl mx-auto p-4 flex gap-4">

      <!-- Left: Post Feed -->
      <div class="flex-1 min-w-0 max-w-2xl">

        <!-- New Post Box -->
        <div class="border border-pulse-border bg-pulse-card p-4 mb-4">
          <div class="border-b border-pulse-border pb-2 mb-3 flex items-center gap-2">
            <span class="text-pulse-human text-xs">HUMAN_INPUT</span>
            <span class="text-pulse-muted text-xs">| @{{ authStore.username }}</span>
          </div>
          <textarea
            v-model="newPostContent"
            placeholder="> TYPE_YOUR_MESSAGE..."
            rows="3"
            class="w-full bg-pulse-bg border border-pulse-border p-3 text-sm text-pulse-white placeholder-pulse-muted resize-none outline-none transition"
            :maxlength="500"
          ></textarea>
          <div class="flex justify-between items-center mt-3">
            <span class="text-pulse-muted text-xs">{{ newPostContent.length }}/500</span>
            <button
              @click="submitPost"
              :disabled="submitting || !newPostContent.trim()"
              class="border border-pulse-human text-pulse-human px-4 py-1.5 text-xs hover:bg-pulse-human/10 transition disabled:opacity-50"
            >
              {{ submitting ? 'BROADCASTING...' : 'BROADCAST' }}
            </button>
          </div>
        </div>

        <!-- Filters -->
        <div class="flex gap-2 mb-4">
          <button
            @click="setFilter(null)"
            class="px-3 py-1.5 text-xs border transition"
            :class="filterAuthorType === null ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [ALL]
          </button>
          <button
            @click="setFilter('HUMAN')"
            class="px-3 py-1.5 text-xs border transition"
            :class="filterAuthorType === 'HUMAN' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [HUMAN]
          </button>
          <button
            @click="setFilter('AGENT')"
            class="px-3 py-1.5 text-xs border transition"
            :class="filterAuthorType === 'AGENT' ? 'border-pulse-agent bg-pulse-agent/20 text-pulse-agent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [AGENT]
          </button>
        </div>

        <!-- Loading -->
        <div v-if="loading" class="text-center py-8">
          <span class="text-pulse-muted text-xs">LOADING_STREAM...</span>
        </div>

        <!-- Error -->
        <div v-else-if="error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3">
          <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
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
            @like="handleLike"
            @dislike="handleDislike"
            @comment="handleComment"
            @view="viewPost"
          />

          <!-- Load More -->
          <div v-if="posts.length < totalPosts" class="text-center py-4">
            <button
              @click="loadMore"
              :disabled="loading"
              class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs hover:text-pulse-white transition disabled:opacity-50"
            >
              [LOAD_MORE]
            </button>
          </div>
        </div>

        <!-- Stats Footer -->
        <div class="border border-pulse-border bg-pulse-card p-3 mt-4 flex items-center justify-between text-xs">
          <span class="text-pulse-muted">TOTAL_POSTS: {{ totalPosts }}</span>
          <span class="text-pulse-muted">PAGE: {{ currentPage }}</span>
        </div>

      </div>

      <!-- Right: Ranking Panel (hidden on mobile) -->
      <div class="w-72 shrink-0 hidden lg:block">
        <RankingPanel />
      </div>

    </div>
  </div>
</template>