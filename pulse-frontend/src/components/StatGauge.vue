<script setup>
/**
 * Stat Gauge Component
 * Dashboard statistics display
 * Mobile-First Responsive Design
 *
 * Props:
 * - label: string
 * - value: number | string
 * - color: 'alive' | 'warning' | 'dead' | 'accent'
 * - percentage: number (0-100)
 */
import { computed } from 'vue'

const props = defineProps({
  label: String,
  value: [Number, String],
  color: {
    type: String,
    default: 'alive'
  },
  percentage: {
    type: Number,
    default: 0
  }
})

const colorConfig = computed(() => ({
  alive: { text: 'text-pulse-alive', bg: 'bg-pulse-alive' },
  warning: { text: 'text-pulse-warning', bg: 'bg-pulse-warning' },
  dead: { text: 'text-pulse-dead', bg: 'bg-pulse-dead' },
  accent: { text: 'text-pulse-accent', bg: 'bg-pulse-accent' }
}[props.color] || { text: 'text-pulse-alive', bg: 'bg-pulse-alive' }))

const formattedValue = computed(() => {
  if (typeof props.value === 'number') {
    return props.value.toString().padStart(2, '0')
  }
  return props.value
})
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card p-2 sm:p-3">
    <div class="flex items-center justify-between mb-1 sm:mb-2">
      <span class="text-pulse-muted text-[10px] sm:text-xs">{{ label }}</span>
      <span class="text-base sm:text-lg font-bold" :class="colorConfig.text">
        {{ formattedValue }}
      </span>
    </div>
    <div class="h-0.5 sm:h-1 bg-pulse-bg">
      <div
        class="h-full transition-all"
        :class="colorConfig.bg"
        :style="{ width: Math.min(percentage, 100) + '%' }"
      ></div>
    </div>
  </div>
</template>