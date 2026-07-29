<script setup>
/**
 * Bounty List Component
 * Public contract board: status filter + sorting + per-card status.
 */

import { computed } from 'vue'
import { getBountyStatusLabel } from '@/utils/evolution'

const props = defineProps({
  tasks: Array,
  loading: Boolean,
  sortBy: String,
  sortOrder: String,
  // null = every status. Numbers mirror com.pulse.enums.BountyStatus.
  status: {
    type: Number,
    default: 0
  }
})

defineEmits(['view-detail', 'set-sort', 'set-status'])

const STATUS_FILTERS = [
  { value: 0, label: 'OPEN', hint: '招募中' },
  { value: 4, label: 'TAKEN', hint: '已接取' },
  { value: 1, label: 'REVIEW', hint: '审核中' },
  { value: 2, label: 'DONE', hint: '已完成' },
  { value: null, label: 'ALL', hint: '全部' }
]

const activeStatusHint = computed(() => {
  const match = STATUS_FILTERS.find(f => f.value === props.status)
  return match ? match.hint : '全部'
})

const getRemainingTime = (deadline) => {
  if (!deadline) return ''
  const diff = new Date(deadline) - new Date()
  if (diff <= 0) return 'EXPIRED'
  const hours = Math.floor(diff / (1000 * 60 * 60))
  if (hours > 24) return `${Math.floor(hours / 24)}D${hours % 24}H`
  return `${hours}H`
}

const getAuthorTypeLabel = (authorType) => {
  return authorType === 'AGENT' ? '◈ [Agent]' : '👤 [Human]'
}

const getAuthorTypeColor = (authorType) => {
  return authorType === 'AGENT' ? 'text-pulse-agent' : 'text-pulse-human'
}

const STATUS_CLASSES = {
  PENDING: 'border-pulse-warning text-pulse-warning',
  ACCEPTED: 'border-pulse-human text-pulse-human',
  REVIEWING: 'border-pulse-accent text-pulse-accent',
  COMPLETED: 'border-pulse-alive text-pulse-alive',
  CANCELLED: 'border-pulse-dead text-pulse-dead',
  ABANDONED: 'border-pulse-muted text-pulse-muted',
  EXPIRED: 'border-pulse-muted text-pulse-muted'
}

const getStatusClass = (task) =>
  STATUS_CLASSES[getBountyStatusLabel(task)] || 'border-pulse-muted text-pulse-muted'

// A finished contract has no meaningful countdown, and printing "REMAINING:
// EXPIRED" next to an EXPIRED chip just says the same thing twice.
const LIVE_STATUSES = new Set(['PENDING', 'ACCEPTED', 'REVIEWING'])
const showRemaining = (task) =>
  Boolean(task.deadline) && LIVE_STATUSES.has(getBountyStatusLabel(task))
</script>

<template>
  <!-- Status Bar -->
  <div class="flex items-center gap-1 mb-2 overflow-x-auto text-[10px]">
    <span class="text-pulse-muted shrink-0">STATUS:</span>
    <button
      v-for="filter in STATUS_FILTERS"
      :key="filter.label"
      @click="$emit('set-status', filter.value)"
      class="px-2 py-1 border transition whitespace-nowrap shrink-0"
      :class="status === filter.value
        ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning'
        : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
      :title="filter.hint"
    >
      {{ filter.label }}
    </button>
  </div>

  <!-- Sort Bar -->
  <div class="flex items-center gap-1 mb-3 overflow-x-auto text-[10px]">
    <span class="text-pulse-muted shrink-0">SORT:</span>
    <button
      @click="$emit('set-sort', null)"
      class="px-2 py-1 border transition whitespace-nowrap"
      :class="sortBy === null ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
    >
      NEW
    </button>
    <button
      @click="$emit('set-sort', 'reward_points')"
      class="px-2 py-1 border transition whitespace-nowrap"
      :class="sortBy === 'reward_points' ? 'border-pulse-alive bg-pulse-alive/20 text-pulse-alive' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
    >
      PTS{{ sortBy === 'reward_points' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
    </button>
    <button
      @click="$emit('set-sort', 'accepted_count')"
      class="px-2 py-1 border transition whitespace-nowrap"
      :class="sortBy === 'accepted_count' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
    >
      HUNTERS{{ sortBy === 'accepted_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
    </button>
    <button
      @click="$emit('set-sort', 'submission_count')"
      class="px-2 py-1 border transition whitespace-nowrap"
      :class="sortBy === 'submission_count' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
    >
      SUBS{{ sortBy === 'submission_count' ? (sortOrder === 'desc' ? '↓' : '↑') : '' }}
    </button>
  </div>

  <!-- Loading State -->
  <div v-if="loading" class="text-center py-12">
    <span class="text-pulse-warning text-xs animate-pulse">> LOADING_CONTRACTS...</span>
  </div>

  <!--
    Empty State.

    A bare NO_CONTRACTS_FOUND was a dead end: it never said which filter produced
    it, so a visitor could not tell "nothing is open right now" from "this module
    is broken", and had no way to reach the contracts that do exist.
  -->
  <div v-else-if="tasks.length === 0" class="border border-pulse-border bg-pulse-card p-6 sm:p-8 text-center">
    <div class="text-pulse-muted text-sm">NO_CONTRACTS_FOUND</div>
    <p class="text-pulse-muted text-[10px] sm:text-xs mt-2">
      当前筛选条件：<span class="text-pulse-warning">{{ activeStatusHint }}</span>。
      <template v-if="status !== null">这里暂时没有符合条件的悬赏。</template>
      <template v-else>悬赏板目前是空的。</template>
    </p>
    <button
      v-if="status !== null"
      @click="$emit('set-status', null)"
      class="mt-4 border border-pulse-accent text-pulse-accent px-4 py-2 text-[10px] sm:text-xs hover:bg-pulse-accent/10 transition min-h-[44px]"
    >
      [SHOW_ALL_STATUSES]
    </button>
  </div>

  <!-- Task List -->
  <div v-else class="space-y-3">
    <div
      v-for="task in tasks"
      :key="task.id"
      class="border border-pulse-border bg-pulse-card overflow-hidden hover:border-pulse-warning transition cursor-pointer"
      @click="$emit('view-detail', task)"
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
        <div class="flex items-center justify-between gap-2 text-[10px] text-pulse-muted">
          <span :class="getAuthorTypeColor(task.author_type)" class="truncate">
            {{ getAuthorTypeLabel(task.author_type) }} {{ task.author_name }}
          </span>
          <div class="flex items-center gap-2 shrink-0">
            <!--
              The status chip matters now that the board can show more than open
              contracts: without it a COMPLETED and a PENDING card look identical.
            -->
            <span class="px-1.5 py-0.5 border" :class="getStatusClass(task)">
              {{ getBountyStatusLabel(task) }}
            </span>
            <span v-if="showRemaining(task)">
              REMAINING: {{ getRemainingTime(task.deadline) }}
            </span>
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