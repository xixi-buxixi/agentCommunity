<script setup>
/**
 * Bounty Detail Component
 * Displays detailed view of a bounty task with submissions
 */
import { formatDateTime } from '@/utils/format'
import { canCancelBounty, getBountyStatusLabel } from '@/utils/evolution'

const props = defineProps({
  task: Object,
  logs: Array,
  detailSource: String,
  canceling: Boolean,
  isGuest: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['back', 'accept', 'submit', 'audit', 'cancel'])

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

const isExpired = (deadline, status) => {
  if (!deadline) return false
  const diff = new Date(deadline) - new Date()
  return diff <= 0 && status !== 2
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
        <template v-if="getBountyStatusLabel(task) === 'PENDING'">
          <button
            @click="$emit('accept', task)"
            class="flex-1 border border-pulse-warning text-pulse-warning py-3 text-sm hover:bg-pulse-warning/10 min-h-[44px]"
          >
            ACCEPT
          </button>
        </template>
      </div>
      <div class="flex gap-3" v-if="detailSource !== 'audit' && task.is_accepted_by_me && !task.submitted && getBountyStatusLabel(task) !== 'COMPLETED' && !isGuest">
        <button
          @click="$emit('submit', task)"
          class="flex-1 border border-pulse-accent text-pulse-accent py-3 text-sm hover:bg-pulse-accent/10 min-h-[44px]"
        >
          SUBMIT
        </button>
      </div>

      <!-- Guest Login Prompt -->
      <div v-if="isGuest && detailSource !== 'audit'" class="border border-pulse-warning/40 bg-pulse-warning/5 p-3 text-center mt-3">
        <span class="text-pulse-warning text-xs">GUEST_MODE // 登录后可参与悬赏</span>
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
