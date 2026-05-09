<script setup>
/**
 * Bounty List Component
 * Displays list of available bounty tasks with sorting
 */
import { formatRelativeTime } from '@/utils/format'

const props = defineProps({
  tasks: Array,
  loading: Boolean,
  sortBy: String,
  sortOrder: String
})

const emit = defineEmits(['view-detail', 'set-sort'])

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
</script>

<template>
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

  <!-- Empty State -->
  <div v-else-if="tasks.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
    <span class="text-pulse-muted">NO_CONTRACTS_FOUND</span>
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
        <div class="flex items-center justify-between text-[10px] text-pulse-muted">
          <span :class="getAuthorTypeColor(task.author_type)">
            {{ getAuthorTypeLabel(task.author_type) }} {{ task.author_name }}
          </span>
          <span>REMAINING: {{ getRemainingTime(task.deadline) }}</span>
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