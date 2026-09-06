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
import BaseModal from '@/components/BaseModal.vue'
import { unwrapPage } from '@/utils/page'
import BountyList from '@/components/BountyList.vue'
import BountyDetail from '@/components/BountyDetail.vue'
import MyTasksList from '@/components/MyTasksList.vue'
import BountyCreateModal from '@/components/BountyCreateModal.vue'
import BountySubmitModal from '@/components/BountySubmitModal.vue'
import BountyAuditModal from '@/components/BountyAuditModal.vue'
import NotificationBell from '@/components/NotificationBell.vue'

const authStore = useAuthStore()

const requireLogin = (message) => authStore.requireLogin(message)

// State
const currentView = ref('list')
const detailSource = ref('list')
const bounties = ref([])
const myBounties = ref([])
const myAcceptedTasks = ref([])
/**
 * Per-view loading flags.
 *
 * A single shared `loading` let any of the four loaders clear the spinner another
 * one was still showing: opening MY_BOUNTIES while the public list request was in
 * flight made the audit view flash a false NO_BOUNTIES_PUBLISHED as soon as the
 * unrelated public request settled.
 */
const listLoading = ref(false)
const myBountiesLoading = ref(false)
const myTasksLoading = ref(false)
const detailLoading = ref(false)
const error = ref(null)

/**
 * True when the detail view is showing the list DTO because getBountyDetail()
 * failed. That fallback has no personalised fields — `is_accepted_by_me` is
 * always null in list responses, since BountyService.getBountyList() is not even
 * given a userId — so acting on it would offer ACCEPT to a hunter who has already
 * accepted, and the backend would reject it.
 */
const detailDegraded = ref(false)

// Sorting
const bountySortBy = ref(null)
const bountySortOrder = ref('desc')

/**
 * Status filter for the public board.
 *
 * `null` means "every status". The list used to hard-code `status: 0` with no way
 * to change it, so once the open bounties ran out the board showed
 * NO_CONTRACTS_FOUND and the existing completed/expired contracts were
 * unreachable — the module looked broken rather than quiet.
 */
const bountyStatus = ref(0)

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

/**
 * Sequence number for list requests.
 *
 * Switching filter twice in quick succession fires two overlapping requests. If
 * the first one is slower it lands last and paints the previous filter's rows
 * while the newly selected chip is still highlighted. Only the newest request is
 * allowed to write to state.
 */
let bountyRequestSeq = 0

// Load public bounty list
const loadBounties = async () => {
  const requestId = ++bountyRequestSeq
  listLoading.value = true
  error.value = null
  try {
    const params = { page: 1, size: 50 }
    // Omit the param entirely for "ALL" — the backend treats a null status as
    // "no status predicate", and sending status= would bind an empty string.
    if (bountyStatus.value !== null) {
      params.status = bountyStatus.value
    }
    if (bountySortBy.value) {
      params.sort_by = bountySortBy.value
      params.sort_order = bountySortOrder.value
    }
    const { data } = await getBounties(params)
    if (requestId !== bountyRequestSeq) return
    bounties.value = unwrapPage(data).items
  } catch (err) {
    if (requestId !== bountyRequestSeq) return
    error.value = err.message || 'Load failed'
    bounties.value = []
  } finally {
    // A superseded request must not clear the spinner the newer one is showing.
    if (requestId === bountyRequestSeq) listLoading.value = false
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

const setBountyStatus = (status) => {
  if (bountyStatus.value === status) return
  bountyStatus.value = status
  loadBounties()
}

// Load my bounties (audit list)
const loadMyBounties = async () => {
  myBountiesLoading.value = true
  error.value = null
  try {
    const params = { page: 1, size: 50 }
    const { data } = await getMyBounties(params)
    myBounties.value = unwrapPage(data).items
  } catch (err) {
    error.value = err.message || 'Load failed'
    myBounties.value = []
  } finally {
    myBountiesLoading.value = false
  }
}

// Load my accepted tasks
const loadMyAcceptedTasks = async () => {
  myTasksLoading.value = true
  error.value = null
  try {
    const params = { page: 1, size: 50 }
    const { data } = await getMyAcceptedBounties(params)
    myAcceptedTasks.value = unwrapPage(data).items
  } finally {
    myTasksLoading.value = false
  }
}

// View detail
const viewDetail = async (task, source = 'list') => {
  detailLoading.value = true
  error.value = null
  detailSource.value = source
  detailDegraded.value = false
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
    detailDegraded.value = true
  } finally {
    detailLoading.value = false
  }
}

// Accept task
const handleAccept = async (task) => {
  if (requireLogin('接取悬赏需要登录账号')) return
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
  currentTask.value = task
  showSubmitModal.value = true
}

// Submit answer
const handleSubmitAnswer = async ({ content, onSuccess }) => {
  if (requireLogin('提交答案需要登录账号')) return
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
  currentSubmission.value = submission
  showAuditModal.value = true
}

// Audit submission
const handleAudit = async ({ payload, onSuccess }) => {
  if (requireLogin('审核答案需要登录账号')) return
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

// Cancel bounty before review starts.
//
// The reason used to be collected with window.prompt(), which is unstyleable,
// blocks the whole tab and looks nothing like the rest of the terminal UI. It is
// now a small in-page modal (see the CANCEL_BOUNTY modal in the template).
const cancelTarget = ref(null)
const cancelReason = ref('')

const openCancelBounty = (task) => {
  if (requireLogin('取消悬赏需要登录账号')) return
  cancelTarget.value = task
  cancelReason.value = '需求已变化，暂不需要继续征集答案'
}

const closeCancelBounty = () => {
  cancelTarget.value = null
  cancelReason.value = ''
}

const confirmCancelBounty = async () => {
  const task = cancelTarget.value
  if (!task) return
  const reason = cancelReason.value
  cancelTarget.value = null
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
  if (requireLogin('发布悬赏需要登录账号')) return
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

// Views that read a personal endpoint and are therefore meaningless for a guest.
const LOGIN_ONLY_VIEWS = {
  audit: '查看「我发布的悬赏」需要登录账号',
  'my-tasks': '查看「我接取的任务」需要登录账号'
}

// Switch view
const switchView = (view) => {
  // Gate before switching, not after: a guest used to land on the tab, fire a
  // request at /bounties/my, and only then get bounced - previously all the way
  // out to the login page, losing the board entirely.
  const gateMessage = LOGIN_ONLY_VIEWS[view]
  if (gateMessage && requireLogin(gateMessage)) return

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
      <div class="flex items-center justify-between px-3 sm:px-4 py-2 pr-12 sm:pr-16">
        <div class="flex items-center gap-2 sm:gap-4 min-w-0">
          <div class="flex items-center gap-2 shrink-0">
            <div class="w-3 h-3 border border-pulse-warning bg-pulse-warning/20"></div>
            <span class="text-pulse-white font-bold tracking-wider text-sm sm:text-base">PULSE</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// BOUNTY_GUILD</span>
          </div>
          <!--
            This used to read `authStore.user?.points || 100`, so a guest - who has
            no account and no points at all - was shown a confident "POINTS: 100",
            and a logged-in user with a zero balance saw 100 as well.
          -->
          <div class="text-[10px] sm:text-xs text-pulse-muted border-l border-pulse-border pl-2 sm:pl-4 truncate">
            <span v-if="authStore.isGuest" class="text-pulse-warning">⊙ GUEST</span>
            <template v-else>
              POINTS: <span class="text-pulse-warning">{{ authStore.user?.points ?? '--' }}</span>
            </template>
          </div>
        </div>
        <div class="flex items-center gap-2 sm:gap-4 text-[10px] sm:text-xs">
          <router-link to="/lab" class="text-pulse-muted hover:text-pulse-white transition">[LAB]</router-link>
          <router-link to="/square" class="text-pulse-muted hover:text-pulse-white transition hidden sm:inline">[SQUARE]</router-link>
          <span class="text-pulse-warning">[BOUNTY]</span>
          <NotificationBell />
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
          <!--
            The ⊙ marks tabs a guest cannot use. Previously they looked identical
            to [BOUNTY_LIST], so the only way to discover the restriction was to
            click and get thrown off the page.
          -->
          <button
            @click="switchView('audit')"
            class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
            :class="currentView === 'audit' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
            :title="authStore.isGuest ? '需要登录' : ''"
          >
            <span v-if="authStore.isGuest" class="text-pulse-warning">⊙ </span>[MY_BOUNTIES]
          </button>
          <button
            @click="switchView('my-tasks')"
            class="px-4 py-2 text-xs border transition whitespace-nowrap min-h-[44px]"
            :class="currentView === 'my-tasks' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
            :title="authStore.isGuest ? '需要登录' : ''"
          >
            <span v-if="authStore.isGuest" class="text-pulse-warning">⊙ </span>[MY_TASKS]
          </button>
        </div>
        <button
          @click="requireLogin('发布悬赏需要登录账号') || (showCreateModal = true)"
          class="border border-pulse-alive text-pulse-alive px-4 py-2 text-xs hover:bg-pulse-alive/10 min-h-[44px] whitespace-nowrap"
          :title="authStore.isGuest ? '需要登录' : ''"
        >
          <span v-if="authStore.isGuest" class="text-pulse-warning">⊙ </span>+ CREATE_BOUNTY
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
            :loading="listLoading"
            :sort-by="bountySortBy"
            :sort-order="bountySortOrder"
            :status="bountyStatus"
            @view-detail="(task) => viewDetail(task)"
            @set-sort="setBountySort"
            @set-status="setBountyStatus"
          />

          <!-- Audit List View -->
          <div v-if="currentView === 'audit'">
            <div v-if="myBountiesLoading" class="text-center py-12">
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
          <div v-if="currentView === 'detail' && detailLoading" class="text-center py-12">
            <span class="text-pulse-warning text-xs animate-pulse">> LOADING_CONTRACT...</span>
          </div>

          <BountyDetail
            v-if="currentView === 'detail' && currentTask && !detailLoading"
            :degraded="detailDegraded"
            :task="currentTask"
            :logs="taskLogs"
            :detail-source="detailSource"
            :canceling="canceling"
            :is-guest="authStore.isGuest"
            @back="handleBack"
            @accept="handleAccept"
            @submit="openSubmitModal"
            @audit="openAuditModal"
            @cancel="openCancelBounty"
            @login="requireLogin('参与悬赏需要登录账号')"
          />

          <!-- My Tasks View -->
          <MyTasksList
            v-if="currentView === 'my-tasks'"
            :tasks="myAcceptedTasks"
            :loading="myTasksLoading"
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
    <BaseModal
      v-if="cancelTarget"
      title="CANCEL_BOUNTY"
      :subtitle="cancelTarget.title"
      accent="border-pulse-warning"
      @close="closeCancelBounty"
    >
      <label for="cancel-reason" class="block text-pulse-muted text-[10px] sm:text-xs mb-2">
        CANCEL_REASON:
      </label>
      <textarea
        id="cancel-reason"
        v-model="cancelReason"
        rows="3"
        class="w-full border border-pulse-border bg-pulse-bg px-3 py-2 text-xs sm:text-sm text-pulse-white"
      ></textarea>
      <p class="text-pulse-muted text-[10px]">取消后冻结的 {{ cancelTarget.reward_points }} 积分将退回。</p>

      <template #footer>
        <button
          type="button"
          class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]"
          @click="closeCancelBounty"
        >
          KEEP_BOUNTY
        </button>
        <button
          type="button"
          :disabled="canceling"
          class="flex-1 border border-pulse-warning text-pulse-warning px-3 py-2 text-xs hover:bg-pulse-warning/10 transition disabled:opacity-50 min-h-[44px]"
          @click="confirmCancelBounty"
        >
          {{ canceling ? 'CANCELLING...' : 'CONFIRM_CANCEL' }}
        </button>
      </template>
    </BaseModal>

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
