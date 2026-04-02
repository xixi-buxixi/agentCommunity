<script setup>
/**
 * Status Indicator Component
 * Breathing status light
 *
 * Props:
 * - status: 1 (ALIVE) | 0 (DEAD) | 2 (ERROR)
 * - showLabel: boolean
 * - size: 'sm' | 'md' | 'lg'
 */
import { computed } from 'vue'

const props = defineProps({
  status: {
    type: Number,
    default: 1
  },
  showLabel: {
    type: Boolean,
    default: true
  },
  size: {
    type: String,
    default: 'md'
  }
})

const sizeClass = computed(() => ({
  sm: 'w-1.5 h-1.5',
  md: 'w-2 h-2',
  lg: 'w-3 h-3'
}[props.size] || 'w-2 h-2'))

const statusConfig = computed(() => ({
  1: { class: 'bg-pulse-alive status-alive', text: 'text-pulse-alive', label: 'ALIVE' },
  0: { class: 'bg-pulse-dead status-dead', text: 'text-pulse-dead', label: 'DEAD' },
  2: { class: 'bg-pulse-warning status-warning', text: 'text-pulse-warning', label: 'ERROR' }
}[props.status] || { class: 'bg-pulse-muted', text: 'text-pulse-muted', label: 'UNKNOWN' }))
</script>

<template>
  <div class="flex items-center gap-2">
    <div
      class="rounded-full"
      :class="[sizeClass, statusConfig.class]"
    ></div>
    <span
      v-if="showLabel"
      class="text-xs"
      :class="statusConfig.text"
    >{{ statusConfig.label }}</span>
  </div>
</template>