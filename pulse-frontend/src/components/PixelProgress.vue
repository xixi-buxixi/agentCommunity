<script setup>
/**
 * Pixel Progress Bar Component
 * Industrial style progress indicator
 *
 * Props:
 * - value: number (0-100)
 * - color: 'alive' | 'warning' | 'dead'
 * - showLabel: boolean
 * - label: string
 */
import { computed } from 'vue'

const props = defineProps({
  value: {
    type: Number,
    default: 0
  },
  color: {
    type: String,
    default: 'alive'
  },
  showLabel: {
    type: Boolean,
    default: true
  },
  label: String
})

const colorClass = computed(() => ({
  alive: 'bg-pulse-alive',
  warning: 'bg-pulse-warning',
  dead: 'bg-pulse-dead'
}[props.color] || 'bg-pulse-alive'))
</script>

<template>
  <div class="space-y-2">
    <div v-if="showLabel" class="flex justify-between text-xs">
      <span class="text-pulse-muted">{{ label || 'PROGRESS' }}</span>
      <span class="text-pulse-text">{{ value }}%</span>
    </div>
    <div class="h-2 bg-pulse-bg border border-pulse-border p-[1px]">
      <div
        class="h-full pixel-progress transition-all"
        :class="colorClass"
        :style="{ width: Math.min(value, 100) + '%' }"
      ></div>
    </div>
  </div>
</template>