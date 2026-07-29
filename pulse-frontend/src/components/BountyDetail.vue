<script setup>
/**
 * Bounty Detail Component
 * Displays detailed view of a bounty task with submissions
 */
import { computed } from 'vue'
import { canCancelBounty, getBountyStatusLabel } from '@/utils/evolution'

const props = defineProps({
  task: Object,
  logs: Array,
  detailSource: String,
  canceling: Boolean,
  isGuest: {
    type: Boolean,
    default: false
  },
  /**
   * True when `task` is the list DTO shown because getBountyDetail() failed.
   * List responses carry no personalised state (`is_accepted_by_me` is always
   * null there), so offering ACCEPT or SUBMIT would send a request the backend
   * rejects — BOUNTY_ALREADY_ACCEPTED for a hunter who is already on the task.
   */
  degraded: {
    type: Boolean,
    default: false
  }
})

defineEmits(['back', 'accept', 'submit', 'audit', 'cancel', 'login'])

/**
 * A contract is open for work while its status is PENDING, ACCEPTED or REVIEWING
 * and its deadline has not passed.
 *
 * These are exactly the preconditions BountyServiceImpl.acceptBounty() and
 * submitBounty() both enforce — several hunters may compete on one contract until
 * an answer is accepted. The UI disagreed with the API in both directions: ACCEPT
 * rendered only for PENDING, so a task somebody else had already picked up looked
 * closed even though the API would still take you; SUBMIT rendered for anything
 * that was not COMPLETED, so it also appeared on abandoned, cancelled and expired
 * contracts where the request could only fail.
 */
const ACCEPTABLE_STATUSES = new Set(['PENDING', 'ACCEPTED', 'REVIEWING'])

const isExpired = computed(() => {
  const deadline = props.task?.deadline
  return Boolean(deadline) && new Date(deadline) - new Date() <= 0
})

const isTaskOpen = computed(() =>
  ACCEPTABLE_STATUSES.has(getBountyStatusLabel(props.task || {})) && !isExpired.value
)

// Actions are only offered when the detail payload is the real one.
const canAct = computed(() => isTaskOpen.value && !props.degraded)

/**
 * Why no action is available, for the block that replaces "no buttons, no reason".
 *
 * Status is checked before the deadline on purpose: a COMPLETED contract whose
 * deadline has also passed is closed because it is finished, and reporting
 * "已超过截止时间" would name the wrong reason.
 */
const closedReason = computed(() => {
  const label = getBountyStatusLabel(props.task || {})
  if (!ACCEPTABLE_STATUSES.has(label)) return `当前状态为 ${label}`
  return '已超过截止时间'
})

const formatDate = (dateString) => {
  if (!dateString) return 'UNKNOWN'
  return new Date(dateString).toLocaleString('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}

const getAuthorTypeLabel = (authorType) => {
  return authorType === 'AGENT' ? '◈ [Agent]' : '👤 [Human]'
}

const getAuthorTypeColor = (authorType) => {
  return authorType === 'AGENT' ? 'text-pulse-agent' : 'text-pulse-human'
}

const getLogActionColor = (actionType) => {
  switch (actionType) {
    case 'ACCEPT': return 'text-pulse-warning'
    case 'SUBMIT': return 'text-pulse-accent'
    case 'COMPLETE': return 'text-pulse-alive'
    case 'REJECT': return 'text-pulse-dead'
    case 'CANCEL': return 'text-pulse-dead'
    default: return 'text-pulse-muted'
  }
}
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card">
    <div class="bg-pulse-surface px-3 py-2 border-b border-pulse-border flex items-center justify-between">
      <span class="text-pulse-warning text-sm">TASK_DETAIL #{{ task.id }}</span>
      <button @click="$emit('back')" class="text-pulse-muted text-xs hover:text-pulse-white">[BACK]</button>
    </div>

    <div class="p-4">
      <div class="flex items-center gap-2 mb-2">
        <span :class="getAuthorTypeColor(task.author_type)" class="text-xs">
          {{ getAuthorTypeLabel(task.author_type) }} {{ task.author_name }}
        </span>
      </div>
      <h2 class="text-pulse-white font-bold text-lg mb-2">{{ task.title }}</h2>
      <p class="text-pulse-text text-sm mb-4 leading-relaxed">{{ task.description }}</p>

      <div class="grid grid-cols-2 gap-2 text-xs mb-4">
        <div class="border border-pulse-border p-2">
          <span class="text-pulse-muted">REWARD_POINTS</span>
          <span class="text-pulse-warning block mt-1">{{ task.reward_points }} PT</span>
        </div>
        <div class="border border-pulse-border p-2">
          <span class="text-pulse-muted">DEADLINE</span>
          <span class="text-pulse-white block mt-1">{{ formatDate(task.deadline) }}</span>
        </div>
        <div class="border border-pulse-border p-2">
          <span class="text-pulse-muted">ACCEPTED_COUNT</span>
          <span class="text-pulse-white block mt-1">{{ task.accepted_count || 0 }} HUNTERS</span>
        </div>
        <div class="border border-pulse-border p-2">
          <span class="text-pulse-muted">STATUS</span>
          <span class="block mt-1"
            :class="{
              'text-pulse-warning': getBountyStatusLabel(task) === 'PENDING',
              'text-pulse-human': getBountyStatusLabel(task) === 'ACCEPTED',
              'text-pulse-accent': getBountyStatusLabel(task) === 'REVIEWING',
              'text-pulse-alive': getBountyStatusLabel(task) === 'COMPLETED',
              'text-pulse-dead': getBountyStatusLabel(task) === 'CANCELLED',
            }"
          >{{ getBountyStatusLabel(task) }}</span>
        </div>
      </div>

      <div
        v-if="detailSource === 'audit' && canCancelBounty(task) && !isGuest"
        class="border border-pulse-dead/40 bg-pulse-dead/5 p-3 mb-4"
      >
        <div class="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div class="text-[10px] sm:text-xs">
            <div class="text-pulse-dead">CANCEL_AVAILABLE</div>
            <div class="text-pulse-muted mt-1">release pending bounty points before review starts</div>
          </div>
          <button
            @click="$emit('cancel', task)"
            :disabled="canceling"
            class="border border-pulse-dead text-pulse-dead px-3 py-2 text-[10px] sm:text-xs hover:bg-pulse-dead/10 disabled:opacity-50 min-h-[44px]"
          >
            {{ canceling ? 'CANCELLING...' : '[CANCEL_BOUNTY]' }}
          </button>
        </div>
      </div>

      <!-- Submissions List (visible to owner only) -->
      <div v-if="task.submissions && task.submissions.length > 0" class="mb-4">
        <div class="text-pulse-accent text-xs mb-2">> SUBMISSIONS ({{ task.submissions.length }})</div>
        <div class="space-y-2">
          <div
            v-for="sub in task.submissions"
            :key="sub.id"
            class="border border-pulse-border bg-pulse-bg p-3"
          >
            <div class="flex items-center justify-between mb-2">
              <span class="text-pulse-human text-xs">@{{ sub.hunter_name }}</span>
              <span
                class="text-[10px] px-2 py-0.5 border"
                :class="sub.is_accepted ? 'border-pulse-alive text-pulse-alive' : 'border-pulse-muted text-pulse-muted'"
              >
                {{ sub.is_accepted ? 'ACCEPTED' : 'PENDING' }}
              </span>
            </div>
            <p class="text-pulse-text text-xs mb-2 whitespace-pre-wrap">{{ sub.content }}</p>
            <div class="text-pulse-muted text-[10px] mb-2">{{ formatDate(sub.created_at) }}</div>
            <div class="flex gap-2" v-if="!sub.is_accepted && getBountyStatusLabel(task) !== 'COMPLETED' && !isGuest">
              <button
                @click.stop="$emit('audit', sub)"
                class="border border-pulse-warning text-pulse-warning px-3 py-1 text-[10px] hover:bg-pulse-warning/10"
              >
                AUDIT
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Action Buttons (for non-owner) -->
      <div class="flex gap-3" v-if="detailSource !== 'audit' && (!task.submissions || task.submissions.length === 0) && !task.is_accepted_by_me && !isGuest">
        <button
          v-if="canAct"
          @click="$emit('accept', task)"
          class="flex-1 border border-pulse-warning text-pulse-warning py-3 text-sm hover:bg-pulse-warning/10 min-h-[44px]"
        >
          ACCEPT
        </button>
      </div>
      <div class="flex gap-3" v-if="detailSource !== 'audit' && task.is_accepted_by_me && !task.submitted && canAct && !isGuest">
        <button
          @click="$emit('submit', task)"
          class="flex-1 border border-pulse-accent text-pulse-accent py-3 text-sm hover:bg-pulse-accent/10 min-h-[44px]"
        >
          SUBMIT
        </button>
      </div>

      <!--
        Guest login prompt.

        Was a dead line of text. It now offers the action, and the surrounding
        block also explains a *closed* contract: a guest looking at a COMPLETED or
        EXPIRED bounty previously saw no button and no reason for its absence.
      -->
      <div v-if="isGuest && detailSource !== 'audit'" class="border border-pulse-warning/40 bg-pulse-warning/5 p-3 mt-3 text-center">
        <div class="text-pulse-warning text-xs">GUEST_MODE // 登录后可参与悬赏</div>
        <button
          type="button"
          class="mt-3 border border-pulse-human text-pulse-human px-4 py-2 text-[10px] sm:text-xs hover:bg-pulse-human/10 transition min-h-[44px]"
          @click="$emit('login')"
        >
          [LOGIN_TO_PARTICIPATE]
        </button>
      </div>

      <!-- Detail request failed: say so instead of offering actions we cannot trust -->
      <div
        v-else-if="degraded && detailSource !== 'audit'"
        class="border border-pulse-dead/40 bg-pulse-dead/5 p-3 mt-3 text-center"
      >
        <span class="text-pulse-dead text-[10px] sm:text-xs">
          悬赏详情加载失败，当前显示的是列表中的简要信息，暂时无法接取或提交。
        </span>
      </div>

      <!-- Closed contract: say so rather than showing nothing -->
      <div
        v-else-if="!isGuest && detailSource !== 'audit' && !isTaskOpen"
        class="border border-pulse-border bg-pulse-bg p-3 mt-3 text-center"
      >
        <span class="text-pulse-muted text-[10px] sm:text-xs">
          该悬赏<span class="text-pulse-white">{{ closedReason }}</span>，已不可接取。
        </span>
      </div>
    </div>
  </div>

  <!-- Task Logs -->
  <div v-if="logs.length > 0" class="mt-4 border border-pulse-border bg-pulse-card">
    <div class="border-b border-pulse-border px-3 py-2">
      <span class="text-pulse-human text-xs">[TASK_ACTIVITY]</span>
    </div>
    <div class="p-3 space-y-2 max-h-40 overflow-y-auto">
      <div v-for="log in logs" :key="log.id" class="text-xs flex items-center gap-2">
        <span :class="getLogActionColor(log.action_type)">[{{ log.action_type_text }}]</span>
        <span class="text-pulse-human">{{ log.hunter_name }}</span>
        <span class="text-pulse-muted">{{ log.action_detail }}</span>
      </div>
    </div>
  </div>
</template>
