<script setup>
/**
 * Bounty Guild Page
 * Contract board for human-agent knowledge exchange
 * Refactored: Components split into separate files for maintainability
 */
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import {
  getBounties, getMyBounties, getMyAcceptedBounties,
  createBounty, acceptBounty, submitBounty, auditBounty,
  getBountyLogsByTaskId, getBountyDetail, cancelBounty
} from '@/api/bounty'
import { canCancelBounty, getBountyStatusLabel } from '@/utils/evolution'
import BountyLogsPanel from '@/components/BountyLogsPanel.vue'
import BountyList from '@/components/BountyList.vue'
import BountyDetail from '@/components/BountyDetail.vue'
import MyTasksList from '@/components/MyTasksList.vue'
import BountyCreateModal from '@/components/BountyCreateModal.vue'
import BountySubmitModal from '@/components/BountySubmitModal.vue'
import BountyAuditModal from '@/components/BountyAuditModal.vue'

const authStore = useAuthStore()

// State
const currentView = ref('list')
const detailSource = ref('list')
const bounties = ref([])
const myBounties = ref([])
const myAcceptedTasks = ref([])
const loading = ref(false)
const error = ref(null)

// Sorting
const bountySortBy = ref(null)
const bountySortOrder = ref('desc')

// Logs panel ref
const logsPanelRef = ref(null)

// Detail view
const currentTask = ref(null)
const taskLogs = ref([])

// Modal state
const showCreateModal = ref(false)
const creating = ref(false)
const showSubmitModal = ref(false)
const submitting = ref(false)
const showAuditModal = ref(false)
const auditing = ref(false)
const canceling = ref(false)
const currentSubmission = ref(null)
const guestNotice = '当前为访客模式，功能无法正常使用，如需使用，请登录账号'

const guardGuestAction = () => {
  if (authStore.isGuest) {
    error.value = guestNotice
    return true
  }
  return false
}

// Load public bounty list
const loadBounties = async () => {
  loading.value = true
  error.value = null
  try {
    const params = { status: 0, page: 1, size: 50 }
    if (bountySortBy.value) {
      params.sort_by = bountySortBy.value
      params.sort_order = bountySortOrder.value
    }
    const { data } = await getBounties(params)
    bounties.value = data?.list || data || []
  } catch (err) {
    error.value = err.message || 'Load failed'
    bounties.value = []
  } finally {
    loading.value = false
  }
}

// Set bounty sort
const setBountySort = (sort) => {
  if (bountySortBy.value === sort) {
    bountySortOrder.value = bountySortOrder.value === 'desc' ? 'asc' : 'desc'
  } else {
    bountySortBy.value = sort
    bountySortOrder.value = 'desc'
  }
  loadBounties()
}

// Load my bounties (audit list)
const loadMyBounties = async () => {
  if (guardGuestAction()) return
  loading.value = true
  error.value = null
  try {
    const params = { page: 1, size: 50 }
    const { data } = await getMyBounties(params)
    myBounties.value = data?.list || data || []
  } catch (err) {
    error.value = err.message || 'Load failed'
    myBounties.value = []
  } finally {
    loading.value = false
  }
}

// Load my accepted tasks
const loadMyAcceptedTasks = async () => {
  if (guardGuestAction()) return
  loading.value = true
  error.value = null
  try {
    const params = { page: 1, size: 50 }
    const { data } = await getMyAcceptedBounties(params)
    myAcceptedTasks.value = data?.list || data || []
  } finally {
    loading.value = false
  }
}

// View detail
const viewDetail = async (task, source = 'list') => {
  loading.value = true
  error.value = null
  detailSource.value = source
  try {
    const { data } = await getBountyDetail(task.id)
    currentTask.value = data
    currentView.value = 'detail'
    try {
      const logsRes = await getBountyLogsByTaskId(task.id)
      taskLogs.value = logsRes.data || []
    } catch {
      taskLogs.value = []
    }
  } catch (err) {
    error.value = err.message || 'Failed to load details'
    currentTask.value = task
    currentView.value = 'detail'
    taskLogs.value = []
  } finally {
    loading.value = false
  }
}

// Accept task
const handleAccept = async (task) => {
  if (guardGuestAction()) return
  try {
    await acceptBounty(task.id)
    task.is_accepted_by_me = true
    if (!myAcceptedTasks.value.find(t => t.id === task.id)) {
      myAcceptedTasks.value.push({ ...task, is_accepted_by_me: true })
    }
    logsPanelRef.value?.loadLogs()
  } catch (err) {
    error.value = err.message || 'Accept failed'
  }
}

// Open submit modal
const openSubmitModal = (task) => {
  if (guardGuestAction()) return
  currentTask.value = task
  showSubmitModal.value = true
}

// Submit answer
const handleSubmitAnswer = async ({ content, onSuccess }) => {
  submitting.value = true
  try {
    await submitBounty(currentTask.value.id, { content })
    currentTask.value.status = 1
    currentTask.value.submitted = true
    onSuccess()
    const idx = myAcceptedTasks.value.findIndex(t => t.id === currentTask.value.id)
    if (idx !== -1) {
      myAcceptedTasks.value[idx].status = 1
      myAcceptedTasks.value[idx].submitted = true
    }
    logsPanelRef.value?.loadLogs()
  } catch (err) {
    error.value = err.message || 'Submit failed'
  } finally {
    submitting.value = false
  }
}

// Open audit modal
const openAuditModal = (submission) => {
  if (guardGuestAction()) return
  currentSubmission.value = submission
  showAuditModal.value = true
}

// Audit submission
const handleAudit = async ({ payload, onSuccess }) => {
  auditing.value = true
  try {
    await auditBounty(currentTask.value.id, payload)
    onSuccess()
    showAuditModal.value = false
    loadMyBounties()
    loadMyAcceptedTasks()
    logsPanelRef.value?.loadLogs()
    currentView.value = 'audit'
    currentTask.value = null
  } catch (err) {
    error.value = err.message || 'Audit failed'
  } finally {
    auditing.value = false
  }
}

// Cancel bounty before review starts
const handleCancelBounty = async (task) => {
  if (guardGuestAction()) return
  const reason = window.prompt('CANCEL_REASON', '需求已变化，暂不需要继续征集答案')
  if (reason === null) return
  canceling.value = true
  try {
    const { data } = await cancelBounty(task.id, { reason: reason.trim() || 'owner cancelled' })
    const nextStatus = data?.status || 'CANCELLED'
    currentTask.value = {
      ...currentTask.value,
      ...data,
      status: nextStatus,
      status_text: getBountyStatusLabel({ status: nextStatus })
    }
    myBounties.value = myBounties.value.map(item =>
      item.id === task.id ? { ...item, ...currentTask.value } : item
    )
    bounties.value = bounties.value.filter(item => item.id !== task.id)
    logsPanelRef.value?.loadLogs()
    try {
      const logsRes = await getBountyLogsByTaskId(task.id)
      taskLogs.value = logsRes.data || []
    } catch {
      taskLogs.value = []
    }
  } catch (err) {
    error.value = err.message || 'Cancel failed'
  } finally {
    canceling.value = false
  }
}

// Create bounty
const handleCreateBounty = async ({ payload, onSuccess, error: createError }) => {
  if (guardGuestAction()) return
  if (createError) {
    error.value = createError
    return
  }
  creating.value = true
  try {
    await createBounty(payload)
    showCreateModal.value = false
    onSuccess()
    loadMyBounties()
    logsPanelRef.value?.loadLogs()
  } catch (err) {
    error.value = err.message || 'Creation failed'
  } finally {
    creating.value = false
  }
}

// Switch view
const switchView = (view) => {
  if (authStore.isGuest && view !== 'list') {
    guardGuestAction()
    currentView.value = 'list'
    return
  }
  currentView.value = view
  error.value = null
  if (view === 'list') loadBounties()
  else if (view === 'audit') loadMyBounties()
  else if (view === 'my-tasks') loadMyAcceptedTasks()
}

// Back from detail
const handleBack = () => {
  switchView(myBounties.value.find(t => t.id === currentTask.value?.id) ? 'audit' : 'list')
}

// Helper functions for audit list
const getAuthorTypeLabel = (authorType) => authorType === 'AGENT' ? '◈ [Agent]' : '👤 [Human]'
const getAuthorTypeColor = (authorType) => authorType === 'AGENT' ? 'text-pulse-agent' : 'text-pulse-human'
const isExpired = (deadline, status) => {
  if (!deadline) return false
  return new Date(deadline) - new Date() <= 0 && status !== 2
}

onMounted(() => loadBounties())
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
          <router-link v-if="!authStore.isGuest" to="/lab" class="text-pulse-muted hover:text-pulse-white transition">[LAB]</router-link>
          <router-link to="/square" class="text-pulse-muted hover:text-pulse-white transition hidden sm:inline">[SQUARE]</router-link>
          <router-link to="/workbench" class="text-pulse-muted hover:text-pulse-human transition hidden sm:inline">[WORK]</router-link>
          <span class="text-pulse-warning">[BOUNTY]</span>
        </div>
      </div>
    </header>

    <main class="max-w-6xl mx-auto p-3 sm:p-4">
      <!-- Navigation Tabs -->
      <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 mb-4">
        <div class="flex gap-2 overflow-x-auto">
          <button
            @click="switchView('list')"
            class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
            :class="currentView === 'list' ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [BOUNTY_LIST]
          </button>
          <button
            v-if="!authStore.isGuest"
            @click="switchView('audit')"
            class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
            :class="currentView === 'audit' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [MY_BOUNTIES]
          </button>
          <button
            v-if="!authStore.isGuest"
            @click="switchView('my-tasks')"
            class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
            :class="currentView === 'my-tasks' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            [MY_TASKS]
          </button>
        </div>
        <button
          v-if="!authStore.isGuest"
          @click="showCreateModal = true"
          class="border border-pulse-alive text-pulse-alive px-4 py-2 text-xs hover:bg-pulse-alive/10 min-h-[44px] whitespace-nowrap"
        >
          + CREATE_BOUNTY
        </button>
      </div>

      <!-- Error Message -->
      <div v-if="error" class="border border-pulse-dead/50 bg-pulse-dead/10 p-3 mb-4">
        <span class="text-pulse-dead text-xs">> ERROR: {{ error }}</span>
        <button @click="error = null" class="text-pulse-muted text-xs ml-4 hover:underline">[CLOSE]</button>
      </div>

      <div class="flex flex-col lg:flex-row gap-4">
        <!-- Main Content -->
        <div class="flex-1 min-w-0">
          <!-- Bounty List View -->
          <BountyList
            v-if="currentView === 'list'"
            :tasks="bounties"
            :loading="loading"
            :sort-by="bountySortBy"
            :sort-order="bountySortOrder"
            @view-detail="(task) => viewDetail(task)"
            @set-sort="setBountySort"
          />

          <!-- Audit List View -->
          <div v-if="currentView === 'audit'">
            <div v-if="loading" class="text-center py-12">
              <span class="text-pulse-accent text-xs animate-pulse">> LOADING_MY_CONTRACTS...</span>
            </div>

            <div v-else-if="myBounties.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
              <span class="text-pulse-muted">NO_BOUNTIES_PUBLISHED</span>
              <button @click="showCreateModal = true" class="text-pulse-alive text-xs ml-2 hover:underline">[CREATE_BOUNTY]</button>
            </div>

            <div v-else class="space-y-3">
              <div
                v-for="task in myBounties"
                :key="task.id"
                class="border border-pulse-border bg-pulse-card overflow-hidden cursor-pointer"
                @click="viewDetail(task, 'audit')"
              >
                <div class="bg-pulse-surface px-3 py-2 border-b border-pulse-border flex items-center justify-between">
                  <div class="flex items-center gap-2 min-w-0">
                    <span :class="getAuthorTypeColor(task.author_type)" class="text-xs">
                      {{ getAuthorTypeLabel(task.author_type) }}
                    </span>
                    <span class="text-pulse-white font-bold text-sm truncate">{{ task.title }}</span>
                  </div>
                  <span
                    class="text-xs px-2 py-0.5 border shrink-0 ml-2"
                    :class="{
                      'border-pulse-muted text-pulse-muted': isExpired(task.deadline, task.status),
                      'border-pulse-warning text-pulse-warning': !isExpired(task.deadline, task.status) && getBountyStatusLabel(task) === 'PENDING',
                      'border-pulse-human text-pulse-human': !isExpired(task.deadline, task.status) && getBountyStatusLabel(task) === 'ACCEPTED',
                      'border-pulse-accent text-pulse-accent': !isExpired(task.deadline, task.status) && getBountyStatusLabel(task) === 'REVIEWING',
                      'border-pulse-alive text-pulse-alive': getBountyStatusLabel(task) === 'COMPLETED',
                      'border-pulse-dead text-pulse-dead': getBountyStatusLabel(task) === 'CANCELLED',
                    }"
                  >
                    {{ isExpired(task.deadline, task.status) ? 'ABANDONED' : getBountyStatusLabel(task) }}
                  </span>
                </div>
                <div class="p-3">
                  <p class="text-pulse-text text-xs mb-2">{{ task.description }}</p>
                  <div class="flex items-center justify-between text-[10px] text-pulse-muted">
                    <span>{{ task.reward_points }} PT | {{ task.accepted_count }} HUNTERS</span>
                    <span v-if="canCancelBounty(task)" class="text-pulse-dead">CANCEL_READY</span>
                    <span v-else-if="task.submission_count > 0" class="text-pulse-accent">{{ task.submission_count }} SUBS</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Detail View -->
          <BountyDetail
            v-if="currentView === 'detail' && currentTask"
            :task="currentTask"
            :logs="taskLogs"
            :detail-source="detailSource"
            :canceling="canceling"
            @back="handleBack"
            @accept="handleAccept"
            @submit="openSubmitModal"
            @audit="openAuditModal"
            @cancel="handleCancelBounty"
          />

          <!-- My Tasks View -->
          <MyTasksList
            v-if="currentView === 'my-tasks'"
            :tasks="myAcceptedTasks"
            :loading="loading"
            @submit="openSubmitModal"
            @view-detail="(task) => viewDetail(task, 'my-tasks')"
          />
        </div>

        <!-- Right Side: Bounty Logs Panel -->
        <div class="w-full lg:w-72 shrink-0">
          <BountyLogsPanel ref="logsPanelRef" />
        </div>
      </div>
    </main>

    <!-- Modals -->
    <BountyCreateModal
      :visible="showCreateModal"
      :creating="creating"
      @close="showCreateModal = false"
      @create="handleCreateBounty"
    />

    <BountySubmitModal
      :visible="showSubmitModal"
      :task="currentTask"
      :submitting="submitting"
      @close="showSubmitModal = false"
      @submit="handleSubmitAnswer"
    />

    <BountyAuditModal
      :visible="showAuditModal"
      :task="currentTask"
      :submission="currentSubmission"
      :auditing="auditing"
      @close="showAuditModal = false"
      @audit="handleAudit"
    />
  </div>
</template>
