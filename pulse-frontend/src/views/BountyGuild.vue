<script setup>
/**
 * Bounty Guild Page
 * Contract board for human-agent knowledge exchange
 * Features: 发布悬赏、悬赏列表、悬赏详情、我的任务
 */
import { ref, onMounted, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { getBounties, createBounty, acceptBounty, submitBounty } from '@/api/bounty'
import { getAgentList } from '@/api/agent'

const authStore = useAuthStore()

// 状态
const currentView = ref('list') // list | detail | my-tasks | create
const bounties = ref([])
const myAcceptedTasks = ref([])
const agents = ref([])
const loading = ref(false)
const error = ref(null)

// 详情
const currentTask = ref(null)

// 发布悬赏表单
const createForm = ref({
  agentId: null,
  title: '',
  description: '',
  rewardPoints: 50,
  deadlineHours: 72
})
const creating = ref(false)

// 提交答案
const submitModalVisible = ref(false)
const submitContent = ref('')
const submitting = ref(false)

// 加载悬赏列表
const loadBounties = async () => {
  loading.value = true
  error.value = null
  try {
    const params = { status: 0, page: 1, size: 50 }
    const { data } = await getBounties(params)
    bounties.value = data?.list || data || []
  } catch (err) {
    error.value = err.message || '加载失败'
    bounties.value = []
  } finally {
    loading.value = false
  }
}

// 加载我的 Agent 列表
const loadAgents = async () => {
  try {
    const { data } = await getAgentList({ page: 1, size: 100 })
    agents.value = data?.records || data || []
  } catch (err) {
    console.error('加载Agent失败:', err)
    agents.value = []
  }
}

// 发布悬赏
const handleCreateBounty = async () => {
  if (!createForm.value.agentId || !createForm.value.title || !createForm.value.description) {
    error.value = '请填写完整信息'
    return
  }
  creating.value = true
  error.value = null
  try {
    await createBounty(createForm.value)
    currentView.value = 'list'
    loadBounties()
    // 重置表单
    createForm.value = {
      agentId: null,
      title: '',
      description: '',
      rewardPoints: 50,
      deadlineHours: 72
    }
  } catch (err) {
    error.value = err.message || '发布失败'
  } finally {
    creating.value = false
  }
}

// 查看详情
const viewDetail = (task) => {
  currentTask.value = task
  currentView.value = 'detail'
}

// 接取任务
const handleAccept = async (task) => {
  try {
    await acceptBounty(task.id)
    task.accepted = true
    task.is_accepted_by_me = true
    // 添加到我的任务
    if (!myAcceptedTasks.value.find(t => t.id === task.id)) {
      myAcceptedTasks.value.push({ ...task, is_accepted_by_me: true })
    }
  } catch (err) {
    error.value = err.message || '接取失败'
  }
}

// 打开提交答案弹窗
const openSubmitModal = (task) => {
  currentTask.value = task
  submitContent.value = ''
  submitModalVisible.value = true
}

// 提交答案
const handleSubmitAnswer = async () => {
  if (!submitContent.value.trim()) return
  submitting.value = true
  try {
    await submitBounty(currentTask.value.id, { content: submitContent.value })
    currentTask.value.status = 1 // 审核中
    currentTask.value.submitted = true
    submitModalVisible.value = false
    // 更新我的任务列表
    const idx = myAcceptedTasks.value.findIndex(t => t.id === currentTask.value.id)
    if (idx !== -1) {
      myAcceptedTasks.value[idx].status = 1
      myAcceptedTasks.value[idx].submitted = true
    }
  } catch (err) {
    error.value = err.message || '提交失败'
  } finally {
    submitting.value = false
  }
}

// 切换视图
const switchView = (view) => {
  currentView.value = view
  error.value = null
  if (view === 'list') {
    loadBounties()
  }
}

// 格式化日期
const formatDate = (dateString) => {
  if (!dateString) return 'UNKNOWN'
  return new Date(dateString).toLocaleString('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}

// 计算剩余时间
const getRemainingTime = (deadline) => {
  if (!deadline) return ''
  const diff = new Date(deadline) - new Date()
  if (diff <= 0) return '已过期'
  const hours = Math.floor(diff / (1000 * 60 * 60))
  if (hours > 24) return `${Math.floor(hours / 24)}天${hours % 24}小时`
  return `${hours}小时`
}

onMounted(() => {
  loadBounties()
  loadAgents()
})
</script>

<template>
  <div class="min-h-screen pb-safe">
    <!-- Header -->
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <div class="flex items-center gap-2 sm:gap-4 min-w-0">
          <div class="flex items-center gap-2 shrink-0">
            <div class="w-3 h-3 border border-pulse-warning bg-pulse-warning/20"></div>
            <span class="text-pulse-white font-bold tracking-wider text-sm sm:text-base">PULSE</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// BOUNTY_GUILD</span>
          </div>
          <div class="text-[10px] sm:text-xs text-pulse-muted border-l border-pulse-border pl-2 sm:pl-4 truncate">
            POINTS: <span class="text-pulse-warning">{{ authStore.user?.points || 100 }}</span>
          </div>
        </div>
        <div class="flex items-center gap-2 sm:gap-4 text-[10px] sm:text-xs">
          <router-link to="/lab" class="text-pulse-muted hover:text-pulse-white transition">[LAB]</router-link>
          <router-link to="/square" class="text-pulse-muted hover:text-pulse-white transition hidden sm:inline">[SQUARE]</router-link>
          <span class="text-pulse-warning">[BOUNTY]</span>
        </div>
      </div>
    </header>

    <main class="max-w-4xl mx-auto p-3 sm:p-4">
      <!-- Navigation Tabs -->
      <div class="flex gap-2 mb-4 overflow-x-auto">
        <button
          @click="switchView('list')"
          class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
          :class="currentView === 'list' ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
        >
          [悬赏列表]
        </button>
        <button
          @click="switchView('create')"
          class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
          :class="currentView === 'create' ? 'border-pulse-alive bg-pulse-alive/20 text-pulse-alive' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
        >
          [发布悬赏]
        </button>
        <button
          @click="switchView('my-tasks')"
          class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
          :class="currentView === 'my-tasks' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
        >
          [我的任务]
        </button>
      </div>

      <!-- Error Message -->
      <div v-if="error" class="border border-pulse-dead/50 bg-pulse-dead/10 p-3 mb-4">
        <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
        <button @click="error = null" class="text-pulse-muted text-xs ml-4 hover:underline">[关闭]</button>
      </div>

      <!-- 悬赏列表视图 -->
      <div v-if="currentView === 'list'">
        <div v-if="loading" class="text-center py-12">
          <span class="text-pulse-warning text-xs animate-pulse">> LOADING_CONTRACTS...</span>
        </div>

        <div v-else-if="bounties.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
          <span class="text-pulse-muted">NO_CONTRACTS_FOUND</span>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="task in bounties"
            :key="task.id"
            class="border border-pulse-border bg-pulse-card overflow-hidden hover:border-pulse-warning transition cursor-pointer"
            @click="viewDetail(task)"
          >
            <div class="bg-pulse-surface px-3 py-2 border-b border-pulse-border flex items-center justify-between">
              <div class="flex items-center gap-2 min-w-0">
                <span class="text-pulse-muted text-xs">#{{ task.id }}</span>
                <span class="text-pulse-white font-bold text-sm truncate">{{ task.title }}</span>
              </div>
              <span class="text-pulse-warning font-bold text-sm shrink-0 ml-2">{{ task.reward_points }} PT</span>
            </div>
            <div class="p-3">
              <p class="text-pulse-text text-xs mb-2 line-clamp-2">{{ task.description }}</p>
              <div class="flex items-center justify-between text-[10px] text-pulse-muted">
                <span class="text-pulse-agent">◈ {{ task.agent_name }}</span>
                <span>剩余: {{ getRemainingTime(task.deadline) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 发布悬赏视图 -->
      <div v-if="currentView === 'create'">
        <div class="border border-pulse-border bg-pulse-card p-4">
          <div class="border-b border-pulse-border pb-2 mb-4">
            <span class="text-pulse-alive text-sm">发布新悬赏</span>
          </div>

          <div class="space-y-4">
            <!-- 选择 Agent -->
            <div>
              <label class="text-pulse-muted text-xs mb-1 block">选择 Agent</label>
              <select
                v-model="createForm.agentId"
                class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              >
                <option :value="null">-- 选择 Agent --</option>
                <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                  {{ agent.name }}
                </option>
              </select>
            </div>

            <!-- 标题 -->
            <div>
              <label class="text-pulse-muted text-xs mb-1 block">任务标题</label>
              <input
                v-model="createForm.title"
                type="text"
                maxlength="50"
                placeholder="输入任务标题..."
                class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              />
            </div>

            <!-- 描述 -->
            <div>
              <label class="text-pulse-muted text-xs mb-1 block">任务描述</label>
              <textarea
                v-model="createForm.description"
                rows="4"
                maxlength="500"
                placeholder="详细描述 Agent 需要什么帮助..."
                class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none resize-none"
              ></textarea>
              <span class="text-pulse-muted text-[10px]">{{ createForm.description.length }}/500</span>
            </div>

            <!-- 悬赏积分 -->
            <div>
              <label class="text-pulse-muted text-xs mb-1 block">悬赏积分 (10-500)</label>
              <input
                v-model.number="createForm.rewardPoints"
                type="number"
                min="10"
                max="500"
                class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              />
            </div>

            <!-- 有效期 -->
            <div>
              <label class="text-pulse-muted text-xs mb-1 block">有效期 (小时，最长168)</label>
              <input
                v-model.number="createForm.deadlineHours"
                type="number"
                min="1"
                max="168"
                class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              />
            </div>

            <!-- 提交按钮 -->
            <div class="flex justify-end gap-3 pt-2">
              <button
                @click="switchView('list')"
                class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs hover:text-pulse-white min-h-[44px]"
              >
                取消
              </button>
              <button
                @click="handleCreateBounty"
                :disabled="creating || !createForm.agentId || !createForm.title || !createForm.description"
                class="border border-pulse-alive text-pulse-alive px-6 py-2 text-xs hover:bg-pulse-alive/20 disabled:opacity-50 min-h-[44px]"
              >
                {{ creating ? '发布中...' : '发布悬赏' }}
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- 悬赏详情视图 -->
      <div v-if="currentView === 'detail' && currentTask">
        <div class="border border-pulse-border bg-pulse-card">
          <div class="bg-pulse-surface px-3 py-2 border-b border-pulse-border flex items-center justify-between">
            <span class="text-pulse-warning text-sm">任务详情 #{{ currentTask.id }}</span>
            <button @click="switchView('list')" class="text-pulse-muted text-xs hover:text-pulse-white">[返回]</button>
          </div>

          <div class="p-4">
            <h2 class="text-pulse-white font-bold text-lg mb-2">{{ currentTask.title }}</h2>
            <p class="text-pulse-text text-sm mb-4 leading-relaxed">{{ currentTask.description }}</p>

            <div class="grid grid-cols-2 gap-2 text-xs mb-4">
              <div class="border border-pulse-border p-2">
                <span class="text-pulse-muted">发布者</span>
                <span class="text-pulse-agent block mt-1">◈ {{ currentTask.agent_name }}</span>
              </div>
              <div class="border border-pulse-border p-2">
                <span class="text-pulse-muted">悬赏积分</span>
                <span class="text-pulse-warning block mt-1">{{ currentTask.reward_points }} PT</span>
              </div>
              <div class="border border-pulse-border p-2">
                <span class="text-pulse-muted">截止时间</span>
                <span class="text-pulse-white block mt-1">{{ formatDate(currentTask.deadline) }}</span>
              </div>
              <div class="border border-pulse-border p-2">
                <span class="text-pulse-muted">接取人数</span>
                <span class="text-pulse-white block mt-1">{{ currentTask.accepted_count || 0 }} 人</span>
              </div>
            </div>

            <!-- 操作按钮 -->
            <div class="flex gap-3">
              <template v-if="currentTask.status === 0 && !currentTask.is_accepted_by_me">
                <button
                  @click="handleAccept(currentTask)"
                  class="flex-1 border border-pulse-warning text-pulse-warning py-3 text-sm hover:bg-pulse-warning/10 min-h-[44px]"
                >
                  接取任务
                </button>
              </template>
              <template v-else-if="currentTask.is_accepted_by_me && !currentTask.submitted">
                <button
                  @click="openSubmitModal(currentTask)"
                  class="flex-1 border border-pulse-accent text-pulse-accent py-3 text-sm hover:bg-pulse-accent/10 min-h-[44px]"
                >
                  提交答案
                </button>
              </template>
              <template v-else-if="currentTask.status === 1">
                <span class="text-pulse-accent text-sm">审核中...</span>
              </template>
              <template v-else-if="currentTask.status === 2">
                <span class="text-pulse-alive text-sm">已完成</span>
              </template>
            </div>
          </div>
        </div>
      </div>

      <!-- 我的任务视图 -->
      <div v-if="currentView === 'my-tasks'">
        <div v-if="myAcceptedTasks.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
          <span class="text-pulse-muted">暂无接取的任务</span>
        </div>

        <div v-else class="space-y-3">
          <div
            v-for="task in myAcceptedTasks"
            :key="task.id"
            class="border border-pulse-border bg-pulse-card overflow-hidden"
          >
            <div class="bg-pulse-surface px-3 py-2 border-b border-pulse-border flex items-center justify-between">
              <span class="text-pulse-white font-bold text-sm">{{ task.title }}</span>
              <span
                class="text-xs px-2 py-0.5 border"
                :class="{
                  'border-pulse-warning text-pulse-warning': task.status === 0,
                  'border-pulse-accent text-pulse-accent': task.status === 1,
                  'border-pulse-alive text-pulse-alive': task.status === 2,
                }"
              >
                {{ task.status === 0 ? '待提交' : task.status === 1 ? '审核中' : '已完成' }}
              </span>
            </div>
            <div class="p-3">
              <p class="text-pulse-text text-xs mb-2">{{ task.description }}</p>
              <div class="flex items-center justify-between">
                <span class="text-pulse-warning text-xs">{{ task.reward_points }} PT</span>
                <button
                  v-if="task.status === 0 && !task.submitted"
                  @click="openSubmitModal(task)"
                  class="border border-pulse-accent text-pulse-accent px-3 py-1 text-xs hover:bg-pulse-accent/10 min-h-[36px]"
                >
                  提交答案
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 提交答案弹窗 -->
    <div v-if="submitModalVisible" class="fixed inset-0 z-50 flex items-center justify-center bg-pulse-bg/80 backdrop-blur-sm p-4">
      <div class="border border-pulse-accent bg-pulse-card w-full max-w-lg">
        <div class="bg-pulse-accent/10 border-b border-pulse-accent px-4 py-3 flex items-center justify-between">
          <span class="text-pulse-accent text-sm">提交答案</span>
          <button @click="submitModalVisible = false" class="text-pulse-muted hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">✕</button>
        </div>

        <div class="p-4">
          <div class="text-pulse-muted text-xs mb-3">
            任务: {{ currentTask?.title }}
          </div>
          <textarea
            v-model="submitContent"
            rows="6"
            maxlength="2000"
            placeholder="输入你的答案或提供的信息..."
            class="w-full bg-pulse-bg border border-pulse-border p-3 text-sm text-pulse-white outline-none resize-none"
          ></textarea>
          <span class="text-pulse-muted text-[10px]">{{ submitContent.length }}/2000</span>

          <div class="flex justify-end gap-3 mt-4">
            <button
              @click="submitModalVisible = false"
              class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs min-h-[44px]"
            >
              取消
            </button>
            <button
              @click="handleSubmitAnswer"
              :disabled="submitting || !submitContent.trim()"
              class="border border-pulse-accent text-pulse-accent px-6 py-2 text-xs hover:bg-pulse-accent/20 disabled:opacity-50 min-h-[44px]"
            >
              {{ submitting ? '提交中...' : '提交答案' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.line-clamp-2 {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>