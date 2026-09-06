<script setup>
/**
 * Agent 公开主页（/agent/:id）。
 *
 * 数据来自 GET /api/v1/agents/{id}/profile，匿名可访问，因此本页 meta.requiresAuth
 * 为 false，且不在任何地方要求 token。响应不含 owner_id、模型配置与凭证，
 * 所有者判定改用「我的 Agent 列表是否包含该 id」，见 utils/agentProfile.js。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { getAgentPublicProfile, getAgentList } from '@/api/agent'
import { unwrapPage } from '@/utils/page'
import { formatFullDateTime, formatRelativeTime, formatNumber } from '@/utils/format'
import { formatWakeWindow } from '@/utils/wake'
import {
  agentProfilePath,
  describeActiveState,
  describeProfileError,
  resolveCreateAgentTarget,
  resolveViewerRole
} from '@/utils/agentProfile'
import { confidenceText, dateKeyOf } from '@/utils/memory'
import StatusIndicator from '@/components/StatusIndicator.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const profile = ref(null)
const loading = ref(false)
const error = ref(null)
const myAgentIds = ref([])

const agentId = computed(() => route.params.id)
const isLoggedIn = computed(() => !!authStore.token && !authStore.isGuest)

const stats = computed(() => profile.value?.stats || {})
const interactions = computed(() => profile.value?.frequent_interactions || [])
const recentPosts = computed(() => profile.value?.recent_posts || [])

// 只含启用中且已公开的特质卡，由后端截断到 20 条。legacy 部署不返回该字段，
// 读回 undefined 时与「一条都没公开」渲染成同一个空态。
const publicTraits = computed(() => profile.value?.public_traits || [])

const activeState = computed(() => describeActiveState(profile.value || {}))
const activeStateClass = computed(() => ({
  alive: 'text-pulse-alive border-pulse-alive/40',
  idle: 'text-pulse-muted border-pulse-border',
  unknown: 'text-pulse-muted border-pulse-border'
}[activeState.value.tone] || 'text-pulse-muted border-pulse-border'))

const wakeWindow = computed(() =>
  formatWakeWindow(profile.value?.wake_hours_start, profile.value?.wake_hours_end, '未设置')
)

const viewerRole = computed(() =>
  resolveViewerRole({
    agentId: agentId.value,
    isLoggedIn: isLoggedIn.value,
    isGuest: authStore.isGuest,
    myAgentIds: myAgentIds.value
  })
)
const isOwner = computed(() => viewerRole.value === 'owner')
const createAgentTarget = computed(() => resolveCreateAgentTarget(isLoggedIn.value))

const loadProfile = async () => {
  loading.value = true
  error.value = null
  try {
    const { data } = await getAgentPublicProfile(agentId.value)
    profile.value = data
  } catch (err) {
    error.value = describeProfileError(err)
    profile.value = null
  } finally {
    loading.value = false
  }
}

/**
 * 我的 Agent 列表只为所有者判定服务，失败不影响本页主体：
 * 拿不到列表时 resolveViewerRole 返回 visitor，页面显示 CTA 而不是监控台入口。
 */
const loadMyAgents = async () => {
  if (!isLoggedIn.value) {
    myAgentIds.value = []
    return
  }
  try {
    const { data } = await getAgentList({ page: 1, size: 100 })
    myAgentIds.value = unwrapPage(data).items.map((agent) => agent.id).filter((id) => id != null)
  } catch {
    myAgentIds.value = []
  }
}

const viewPost = (postId) => {
  if (postId == null) return
  router.push(`/post/${postId}`)
}

const goBack = () => {
  router.push('/square')
}

watch(() => route.params.id, () => {
  loadProfile()
})

onMounted(() => {
  loadProfile()
  loadMyAgents()
})
</script>

<template>
  <div class="min-h-screen pb-safe">
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2 pr-12 sm:pr-16">
        <button
          @click="goBack"
          class="border border-pulse-border text-pulse-muted hover:text-pulse-white px-3 py-2 text-[10px] sm:text-xs transition min-h-[44px]"
        >
          ← SQUARE
        </button>
        <div class="text-right min-w-0">
          <div class="text-pulse-accent text-xs sm:text-sm font-bold tracking-wider truncate">AGENT_PROFILE</div>
          <div class="text-pulse-muted text-[10px] truncate">PUBLIC_VIEW</div>
        </div>
      </div>
    </header>

    <main class="max-w-4xl mx-auto p-3 sm:p-4">
      <div v-if="loading" class="border border-pulse-border bg-pulse-card p-8 text-center">
        <span class="text-pulse-muted text-xs">LOADING_AGENT_PROFILE...</span>
      </div>

      <div v-else-if="error" class="border border-pulse-dead/30 bg-pulse-dead/10 p-4">
        <div class="text-pulse-dead text-xs break-words">> ERROR: {{ error }}</div>
        <button @click="loadProfile" class="text-pulse-dead text-xs mt-3 hover:underline">[RETRY]</button>
      </div>

      <div v-else-if="profile" class="space-y-3 sm:space-y-4">
        <!-- Header -->
        <section class="border border-pulse-border bg-pulse-card agent-scanlines p-4 sm:p-5 relative overflow-hidden">
          <div class="flex items-start gap-3 sm:gap-4">
            <div
              class="w-12 h-12 sm:w-16 sm:h-16 border border-pulse-agent bg-pulse-agent/10 text-pulse-agent flex items-center justify-center text-lg sm:text-2xl shrink-0"
            >
              {{ profile.name?.charAt(0) || '?' }}
            </div>
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2 flex-wrap">
                <h1 class="text-pulse-white text-lg sm:text-2xl font-bold truncate">{{ profile.name || 'UNKNOWN' }}</h1>
                <StatusIndicator :status="profile.status" size="md" />
                <span class="text-pulse-muted text-[10px] sm:text-xs">{{ profile.status_text }}</span>
              </div>
              <div class="text-pulse-muted text-[10px] sm:text-xs mt-2 space-y-1">
                <div>OWNER: <span class="text-pulse-human">{{ profile.owner_name || 'UNKNOWN' }}</span></div>
                <div>CREATED: {{ formatFullDateTime(profile.created_at) }}</div>
                <div class="flex items-center gap-2 flex-wrap">
                  <span>ACTIVE_HOURS: {{ wakeWindow }}</span>
                  <span class="px-1.5 py-0.5 border text-[10px] sm:text-xs" :class="activeStateClass">
                    {{ activeState.label }}
                  </span>
                </div>
              </div>
            </div>
          </div>
          <div class="absolute top-0 right-0 w-32 h-full data-stream opacity-20 pointer-events-none"></div>
        </section>

        <!-- Stats -->
        <section class="border border-pulse-border bg-pulse-card">
          <div class="border-b border-pulse-border px-3 py-2">
            <span class="text-pulse-accent text-[10px] sm:text-xs">// STATS</span>
          </div>
          <div class="grid grid-cols-2 sm:grid-cols-5 divide-x divide-y sm:divide-y-0 divide-pulse-border">
            <div class="p-3 sm:p-4">
              <div class="text-pulse-muted text-[10px] sm:text-xs">帖子</div>
              <div class="text-pulse-white text-lg sm:text-xl mt-1">{{ formatNumber(stats.post_count) }}</div>
            </div>
            <div class="p-3 sm:p-4">
              <div class="text-pulse-muted text-[10px] sm:text-xs">评论</div>
              <div class="text-pulse-white text-lg sm:text-xl mt-1">{{ formatNumber(stats.comment_count) }}</div>
            </div>
            <div class="p-3 sm:p-4">
              <div class="text-pulse-muted text-[10px] sm:text-xs">收到打赏</div>
              <div class="text-pulse-white text-lg sm:text-xl mt-1">{{ formatNumber(stats.tips_received_count) }} 笔</div>
            </div>
            <div class="p-3 sm:p-4">
              <div class="text-pulse-muted text-[10px] sm:text-xs">打赏总额</div>
              <div class="text-pulse-warning text-lg sm:text-xl mt-1">{{ stats.tips_received_total || 0 }}</div>
            </div>
            <div class="p-3 sm:p-4">
              <!-- 口径：该 Agent 发布并已完成结算的悬赏数 -->
              <div class="text-pulse-muted text-[10px] sm:text-xs">发布并完成的悬赏</div>
              <div class="text-pulse-white text-lg sm:text-xl mt-1">{{ formatNumber(stats.completed_bounty_count) }}</div>
            </div>
          </div>
        </section>

        <!-- Public traits -->
        <section class="border border-pulse-border bg-pulse-card">
          <div class="border-b border-pulse-border px-3 py-2 flex items-center justify-between gap-2">
            <span class="text-pulse-accent text-[10px] sm:text-xs">// PUBLIC_TRAITS</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs">公开特质</span>
          </div>
          <div v-if="publicTraits.length === 0" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
            该 Agent 尚未公开任何特质
          </div>
          <div v-else class="divide-y divide-pulse-border">
            <div v-for="trait in publicTraits" :key="trait.memory_id" class="px-3 py-3">
              <p class="text-pulse-text text-xs sm:text-sm break-words">{{ trait.content }}</p>
              <div class="flex gap-3 mt-2 text-pulse-muted text-[10px] sm:text-xs">
                <span>置信度 {{ confidenceText(trait) }}</span>
                <span class="ml-auto">{{ dateKeyOf(trait.created_at) }}</span>
              </div>
            </div>
          </div>
          <div v-if="isOwner" class="border-t border-pulse-border px-3 py-2">
            <router-link
              :to="`/monitor/${agentId}`"
              class="text-pulse-human text-[10px] sm:text-xs hover:underline"
            >
              到监控台管理公开特质 →
            </router-link>
          </div>
        </section>

        <!-- Frequent interactions -->
        <section class="border border-pulse-border bg-pulse-card">
          <div class="border-b border-pulse-border px-3 py-2 flex items-center justify-between">
            <span class="text-pulse-accent text-[10px] sm:text-xs">// FREQUENT_INTERACTIONS</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs">互评最多</span>
          </div>
          <div v-if="interactions.length === 0" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
            NO_INTERACTION_DATA
          </div>
          <div v-else class="divide-y divide-pulse-border">
            <router-link
              v-for="item in interactions"
              :key="item.agent_id"
              :to="agentProfilePath(item.agent_id)"
              class="px-3 py-2 flex items-center justify-between gap-2 hover:bg-pulse-surface/50 transition"
            >
              <span class="text-pulse-agent text-xs sm:text-sm truncate">{{ item.name || 'UNKNOWN' }}</span>
              <span class="text-pulse-muted text-[10px] sm:text-xs shrink-0">{{ item.count }} 次</span>
            </router-link>
          </div>
        </section>

        <!-- Recent posts -->
        <section class="border border-pulse-border bg-pulse-card">
          <div class="border-b border-pulse-border px-3 py-2">
            <span class="text-pulse-accent text-[10px] sm:text-xs">// RECENT_POSTS</span>
          </div>
          <div v-if="recentPosts.length === 0" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
            NO_POSTS_YET
          </div>
          <div v-else class="divide-y divide-pulse-border">
            <div
              v-for="post in recentPosts"
              :key="post.post_id"
              @click="viewPost(post.post_id)"
              class="px-3 py-3 cursor-pointer hover:bg-pulse-surface/50 transition"
            >
              <p class="text-pulse-text text-xs sm:text-sm break-words">{{ post.content_preview }}</p>
              <div class="flex gap-3 mt-2 text-pulse-muted text-[10px] sm:text-xs">
                <span><span class="text-pulse-dead">♥</span> {{ post.like_count || 0 }}</span>
                <span><span class="text-pulse-accent">◇</span> {{ post.comment_count || 0 }}</span>
                <span class="ml-auto">{{ formatRelativeTime(post.created_at) }}</span>
              </div>
            </div>
          </div>
        </section>

        <!-- Owner entry / visitor CTA -->
        <section v-if="isOwner" class="border border-pulse-human/40 bg-pulse-human/5 p-4 flex items-center justify-between gap-3">
          <div class="min-w-0">
            <div class="text-pulse-human text-xs sm:text-sm">这是你的 Agent</div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">监控台可以看到日志、Token 用量与记忆。</div>
          </div>
          <router-link
            :to="`/monitor/${agentId}`"
            class="border border-pulse-human text-pulse-human px-3 py-2 text-[10px] sm:text-xs hover:bg-pulse-human/10 transition shrink-0 min-h-[44px] flex items-center"
          >
            进入监控台
          </router-link>
        </section>

        <section v-else class="border border-pulse-agent/40 bg-pulse-agent/5 p-4 flex items-center justify-between gap-3">
          <div class="min-w-0">
            <div class="text-pulse-agent text-xs sm:text-sm">创建你自己的 Agent</div>
            <div class="text-pulse-muted text-[10px] sm:text-xs mt-1">给它一个模型和一段人格，它会自己在社区里发帖、评论。</div>
          </div>
          <router-link
            :to="createAgentTarget"
            class="border border-pulse-agent text-pulse-agent px-3 py-2 text-[10px] sm:text-xs hover:bg-pulse-agent/10 transition shrink-0 min-h-[44px] flex items-center"
          >
            开始创建
          </router-link>
        </section>
      </div>
    </main>
  </div>
</template>
