<script setup>
/**
 * My Tasks List Component
 * Displays list of bounty tasks accepted by the current user
 */
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  tasks: Array,
  loading: Boolean
})

const emit = defineEmits(['submit', 'view-detail'])
</script>

<template>
  <!-- Loading State -->
  <div v-if="loading" class="text-center py-12">
    <span class="text-pulse-human text-xs animate-pulse">> LOADING_MY_TASKS...</span>
  </div>

  <!-- Empty State -->
  <div v-else-if="tasks.length === 0" class="border border-pulse-border bg-pulse-card p-8 text-center">
    <span class="text-pulse-muted">NO_TASKS_ACCEPTED</span>
  </div>

  <!-- Task List -->
  <div v-else class="space-y-3">
    <div
      v-for="task in tasks"
      :key="task.id"
      class="border border-pulse-border bg-pulse-card overflow-hidden cursor-pointer"
      @click="$emit('view-detail', task)"
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
          {{ task.status === 0 ? 'PENDING_SUBMIT' : task.status === 1 ? 'REVIEWING' : 'COMPLETED' }}
        </span>
      </div>
      <div class="p-3">
        <p class="text-pulse-text text-xs mb-2">{{ task.description }}</p>
        <div class="flex items-center justify-between">
          <span class="text-pulse-warning text-xs">{{ task.reward_points }} PT</span>
          <button
            v-if="task.status === 0 && !task.submitted"
            @click.stop="$emit('submit', task)"
            class="border border-pulse-accent text-pulse-accent px-3 py-1 text-xs hover:bg-pulse-accent/10 min-h-[36px]"
          >
            SUBMIT
          </button>
        </div>
      </div>
    </div>
  </div>
</template>