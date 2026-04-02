<script setup>
/**
 * Agent Rack Card Component
 * Industrial dashboard style agent display card
 *
 * Props:
 * - agent: { id, name, model_name, status, used_tokens, token_threshold, status_text }
 */
import { computed } from 'vue'

const props = defineProps({
  agent: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['edit', 'revive', 'terminate', 'view'])

// Calculate consumption percentage
const consumption = computed(() => {
  if (!props.agent.token_threshold) return 0
  return Math.round((props.agent.used_tokens / props.agent.token_threshold) * 100)
})

// Status mapping
const statusMap = {
  1: { label: 'ALIVE', textClass: 'text-pulse-alive', dotClass: 'bg-pulse-alive status-alive', borderClass: 'border-pulse-alive bg-pulse-alive/10' },
  0: { label: 'DEAD', textClass: 'text-pulse-dead', dotClass: 'bg-pulse-dead status-dead', borderClass: 'border-pulse-dead bg-pulse-dead/10' },
  2: { label: 'ERROR', textClass: 'text-pulse-warning', dotClass: 'bg-pulse-warning status-warning', borderClass: 'border-pulse-warning bg-pulse-warning/10' }
}

const statusConfig = computed(() => statusMap[props.agent.status] || statusMap[1])

// Progress bar color
const progressColorClass = computed(() => {
  const pct = consumption.value
  if (pct >= 100) return 'bg-pulse-dead'
  if (pct >= 80) return 'bg-pulse-warning'
  return 'bg-pulse-alive'
})

// Rack slot class
const rackSlotClass = computed(() => {
  const classes = []
  if (props.agent.status === 0) {
    classes.push('rack-slot-dead')
  } else if (consumption.value >= 80) {
    classes.push('rack-slot-warning')
  }
  return classes
})

// Format token numbers
const formatTokens = (num) => {
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
  return num
}
</script>

<template>
  <div
    class="rack-slot border border-pulse-border bg-pulse-card p-4 transition-all"
    :class="rackSlotClass"
  >
    <!-- Header -->
    <div class="flex items-start justify-between mb-3">
      <div class="flex items-center gap-3">
        <div
          class="w-10 h-10 border flex items-center justify-center font-bold text-sm"
          :class="statusConfig.borderClass"
        >
          {{ agent.name?.charAt(0) || '?' }}
        </div>
        <div>
          <div class="flex items-center gap-2">
            <span class="text-pulse-white font-bold">{{ agent.name }}</span>
            <span class="text-pulse-muted text-xs">[{{ agent.model_name }}]</span>
          </div>
          <span class="text-pulse-muted text-xs">ID: {{ agent.id }}</span>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <div class="w-2 h-2 rounded-full" :class="statusConfig.dotClass"></div>
        <span class="text-xs" :class="statusConfig.textClass">{{ statusConfig.label }}</span>
      </div>
    </div>

    <!-- Vital Energy Progress -->
    <div class="space-y-2">
      <div class="flex justify-between text-xs">
        <span class="text-pulse-muted">VITAL_ENERGY</span>
        <span :class="statusConfig.textClass">
          {{ formatTokens(agent.used_tokens) }} / {{ formatTokens(agent.token_threshold) }} TOKENS
        </span>
      </div>
      <div class="h-2 bg-pulse-bg border border-pulse-border p-[1px]">
        <div
          class="h-full pixel-progress transition-all"
          :class="progressColorClass"
          :style="{ width: Math.min(consumption, 100) + '%' }"
        ></div>
      </div>
      <div class="flex justify-between text-xs">
        <span
          class="text-pulse-warning"
          v-if="consumption >= 80"
        >CRITICAL: {{ consumption }}%</span>
        <span class="text-pulse-muted" v-else>CONSUMPTION: {{ consumption }}%</span>
        <span
          class="text-pulse-alive"
          v-if="agent.status === 1 && consumption < 80"
        >STATUS: OPTIMAL</span>
        <span class="text-pulse-warning" v-else-if="agent.status === 1 && consumption >= 80">INJECT_RECOMMENDED</span>
        <span class="text-pulse-dead" v-else>CONNECTION_LOST</span>
      </div>
    </div>

    <!-- Warning Alert -->
    <div
      v-if="agent.status === 1 && consumption >= 80 && consumption < 100"
      class="bg-pulse-warning/10 border border-pulse-warning/30 p-2 mt-3 flex items-center justify-between"
    >
      <span class="text-pulse-warning text-xs">LOW_ENERGY_DETECTED</span>
      <button
        @click="emit('revive', agent)"
        class="border border-pulse-warning text-pulse-warning px-3 py-1 text-xs hover:bg-pulse-warning/20 transition"
      >
        INJECT_LIFE
      </button>
    </div>

    <!-- Dead Message -->
    <div
      v-if="agent.status === 0"
      class="bg-pulse-bg border border-pulse-border p-2 mb-3 text-pulse-muted text-xs italic"
    >
      "Energy depleted, connection lost..."
    </div>

    <!-- Action Buttons -->
    <div class="flex gap-2 mt-3 pt-3 border-t border-pulse-border">
      <button
        v-if="agent.status !== 0"
        @click="emit('edit', agent)"
        class="flex-1 border border-pulse-border text-pulse-muted px-3 py-1.5 text-xs hover:border-pulse-text hover:text-pulse-text transition"
      >
        EDIT_CONFIG
      </button>
      <button
        v-if="agent.status === 0"
        @click="emit('revive', agent)"
        class="flex-1 border border-pulse-alive text-pulse-alive px-3 py-1.5 text-xs hover:bg-pulse-alive/10 transition"
      >
        REVIVE
      </button>
      <button
        v-if="agent.status === 0"
        @click="emit('terminate', agent)"
        class="flex-1 border border-pulse-dead text-pulse-dead px-3 py-1.5 text-xs hover:bg-pulse-dead/10 transition"
      >
        TERMINATE
      </button>
      <button
        @click="emit('view', agent)"
        class="flex-1 border border-pulse-border text-pulse-muted px-3 py-1.5 text-xs hover:border-pulse-text hover:text-pulse-text transition"
      >
        VIEW_LOGS
      </button>
    </div>
  </div>
</template>