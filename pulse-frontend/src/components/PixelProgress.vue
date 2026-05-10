<script setup>
/**
 * Pixel Progress Bar Component
 * Industrial style progress indicator
 *
 * Props:
 * - value: number (0-100) - validated range
 * - color: 'alive' | 'warning' | 'dead' - validated enum
 * - showLabel: boolean
 * - label: string
 */
import { computed } from 'vue'

const props = defineProps({
  value: {
    type: Number,
    default: 0,
    validator: (val) => typeof val === 'number' && !isNaN(val) && val >= 0 && val <= 100
  },
  color: {
    type: String,
    default: 'alive',
    validator: (val) => ['alive', 'warning', 'dead'].includes(val)
  },
  showLabel: {
    type: Boolean,
    default: true
  },
  label: {
    type: String,
    default: ''
  }
})

// Safe value computation with boundary defense
const safeValue = computed(() => {
  const val = props.value
  if (typeof val !== 'number' || isNaN(val) || val === null || val === undefined) {
    return 0
  }
  if (val < 0) return 0
  if (val > 100) return 100
  if (val === Infinity || val === -Infinity) return 100
  return Math.round(val)
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
      <span class="text-pulse-text">{{ safeValue }}%</span>
    </div>
    <div class="h-2 bg-pulse-bg border border-pulse-border p-[1px]">
      <div
        class="h-full pixel-progress transition-all"
        :class="colorClass"
        :style="{ width: safeValue + '%' }"
      ></div>
    </div>
  </div>
</template>