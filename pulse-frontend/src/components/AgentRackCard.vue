<script setup>
/**
 * Agent Rack Card Component
 * Industrial dashboard style agent display card
 * Mobile-First Responsive Design
 *
 * Props:
 * - agent: { id, name, model_name, status, used_tokens, token_threshold, status_text }
 *   Required fields validated via custom validator
 */
import { computed } from 'vue'
import { formatEvolutionTime } from '@/utils/evolution'

const props = defineProps({
  agent: {
    type: Object,
    required: true,
    validator: (obj) => {
      // Must have id and name
      if (!obj || typeof obj !== 'object') return false
      if (typeof obj.id !== 'number' && typeof obj.id !== 'string') return false
      if (!obj.name || typeof obj.name !== 'string') return false
      // Status must be valid
      if (obj.status !== undefined && ![0, 1, 2, 'DEAD', 'ALIVE', 'ERROR'].includes(obj.status)) return false
      return true
    }
  }
})

const emit = defineEmits(['edit', 'revive', 'terminate', 'view', 'resetTokens'])

// Calculate consumption percentage with boundary defense
const consumption = computed(() => {
  const threshold = props.agent?.token_threshold
  const used = props.agent?.used_tokens

  // Handle null/undefined/NaN/Infinity cases
  if (!threshold || typeof threshold !== 'number' || isNaN(threshold) || threshold <= 0) {
    return 0
  }
  if (!used || typeof used !== 'number' || isNaN(used) || used < 0) {
    return 0
  }
  if (used === Infinity) return 100

  const pct = (used / threshold) * 100
  // Clamp to valid range
  return Math.min(Math.max(Math.round(pct), 0), 100)
})

// Safe consumption percentage for display
const safeConsumption = computed(() => {
  const val = consumption.value
  if (typeof val !== 'number' || isNaN(val)) return 0
  return Math.max(0, Math.min(100, val))
})

// Status mapping
const statusMap = {
  1: { label: 'ALIVE', textClass: 'text-pulse-alive', dotClass: 'bg-pulse-alive status-alive', borderClass: 'border-pulse-alive bg-pulse-alive/10' },
  0: { label: 'DEAD', textClass: 'text-pulse-dead', dotClass: 'bg-pulse-dead status-dead', borderClass: 'border-pulse-dead bg-pulse-dead/10' },
  2: { label: 'ERROR', textClass: 'text-pulse-warning', dotClass: 'bg-pulse-warning status-warning', borderClass: 'border-pulse-warning bg-pulse-warning/10' },
  ALIVE: { label: 'ALIVE', textClass: 'text-pulse-alive', dotClass: 'bg-pulse-alive status-alive', borderClass: 'border-pulse-alive bg-pulse-alive/10' },
  DEAD: { label: 'DEAD', textClass: 'text-pulse-dead', dotClass: 'bg-pulse-dead status-dead', borderClass: 'border-pulse-dead bg-pulse-dead/10' },
  ERROR: { label: 'ERROR', textClass: 'text-pulse-warning', dotClass: 'bg-pulse-warning status-warning', borderClass: 'border-pulse-warning bg-pulse-warning/10' }
}

const statusConfig = computed(() => statusMap[props.agent?.status] || statusMap[1])

// Progress bar color
const progressColorClass = computed(() => {
  const pct = safeConsumption.value
  if (pct >= 100) return 'bg-pulse-dead'
  if (pct >= 80) return 'bg-pulse-warning'
  return 'bg-pulse-alive'
})

// Rack slot class
const rackSlotClass = computed(() => {
  const classes = []
  if (props.agent?.status === 0 || props.agent?.status === 'DEAD') {
    classes.push('rack-slot-dead')
  } else if (safeConsumption.value >= 80) {
    classes.push('rack-slot-warning')
  }
  return classes
})

// Format token numbers
const formatTokens = (num) => {
  if (num === null || num === undefined || isNaN(num)) return '0'
  if (num === Infinity) return '∞'
  if (num >= 1000000) return `${(num / 1000000).toFixed(1)}M`
  if (num >= 1000) return `${(num / 1000).toFixed(1)}K`
  return num.toString()
}
</script>

<template>
  <div
    class="rack-slot border border-pulse-border bg-pulse-card p-3 sm:p-4 transition-all"
    :class="rackSlotClass"
  >
    <!-- Header -->
    <div class="flex items-start justify-between mb-2 sm:mb-3">
      <div class="flex items-center gap-2 sm:gap-3 min-w-0">
        <div
          class="w-8 h-8 sm:w-10 sm:h-10 border flex items-center justify-center font-bold text-xs sm:text-sm shrink-0"
          :class="statusConfig.borderClass"
        >
          {{ agent.name?.charAt(0) || '?' }}
        </div>
        <div class="min-w-0 flex-1">
          <div class="flex items-center gap-1 sm:gap-2 flex-wrap">
            <span class="text-pulse-white font-bold text-xs sm:text-sm truncate">{{ agent.name }}</span>
            <span class="text-pulse-muted text-[10px] sm:text-xs truncate">[{{ agent.model_name }}]</span>
          </div>
          <span class="text-pulse-muted text-[10px] sm:text-xs">ID: {{ agent.id }}</span>
        </div>
      </div>
      <div class="flex items-center gap-1 sm:gap-2 shrink-0">
        <div class="w-2 h-2 rounded-full" :class="statusConfig.dotClass"></div>
        <span class="text-[10px] sm:text-xs" :class="statusConfig.textClass">{{ statusConfig.label }}</span>
      </div>
    </div>

    <!-- Vital Energy Progress -->
    <div class="space-y-1 sm:space-y-2">
      <div class="flex justify-between text-[10px] sm:text-xs">
        <span class="text-pulse-muted">VITAL_ENERGY</span>
        <span :class="statusConfig.textClass">
          {{ formatTokens(agent.used_tokens) }} / {{ formatTokens(agent.token_threshold) }} TOKENS
        </span>
      </div>
      <div class="h-1.5 sm:h-2 bg-pulse-bg border border-pulse-border p-[1px]">
        <div
          class="h-full pixel-progress transition-all"
          :class="progressColorClass"
          :style="{ width: safeConsumption + '%' }"
        ></div>
      </div>
      <div class="flex justify-between text-[10px] sm:text-xs">
        <span
          class="text-pulse-warning"
          v-if="safeConsumption >= 80"
        >CRITICAL: {{ safeConsumption }}%</span>
        <span class="text-pulse-muted" v-else>CONSUMPTION: {{ safeConsumption }}%</span>
        <span
          class="text-pulse-alive"
          v-if="(agent.status === 1 || agent.status === 'ALIVE') && safeConsumption < 80"
        >STATUS: OPTIMAL</span>
        <span class="text-pulse-warning text-[10px] sm:text-xs" v-else-if="(agent.status === 1 || agent.status === 'ALIVE') && safeConsumption >= 80">INJECT_RECOMMENDED</span>
        <span class="text-pulse-dead" v-else>CONNECTION_LOST</span>
      </div>
    </div>

    <!-- Evolution telemetry -->
    <div
      v-if="agent.last_wakeup_at || agent.next_wakeup_at || agent.daily_bounty_count !== undefined"
      class="grid grid-cols-1 gap-1 mt-2 sm:mt-3 text-[10px] sm:text-xs border-t border-pulse-border pt-2"
    >
      <div class="flex justify-between gap-2">
        <span class="text-pulse-muted">LAST_WAKEUP</span>
        <span class="text-pulse-text truncate">{{ formatEvolutionTime(agent.last_wakeup_at) }}</span>
      </div>
      <div class="flex justify-between gap-2">
        <span class="text-pulse-muted">NEXT_WAKEUP</span>
        <span class="text-pulse-human truncate">{{ formatEvolutionTime(agent.next_wakeup_at) }}</span>
      </div>
      <div class="flex justify-between gap-2">
        <span class="text-pulse-muted">BOUNTY_TODAY</span>
        <span class="text-pulse-warning">{{ agent.daily_bounty_count ?? 0 }}</span>
      </div>
    </div>

    <!-- Warning Alert - Separated from buttons -->
    <div
      v-if="(agent.status === 1 || agent.status === 'ALIVE') && safeConsumption >= 80 && safeConsumption < 100"
      class="mt-2 sm:mt-3 mb-0"
    >
      <div class="flex items-center gap-2 text-[10px] sm:text-xs">
        <span class="text-pulse-warning">LOW_ENERGY_DETECTED</span>
        <span class="text-pulse-border">|</span>
        <button
          @click.stop="emit('revive', agent)"
          class="text-pulse-alive hover:underline min-h-[44px] flex items-center"
        >
          [INJECT_LIFE]
        </button>
      </div>
    </div>

    <!-- Dead Message -->
    <div
      v-if="agent.status === 0 || agent.status === 'DEAD'"
      class="bg-pulse-bg border-l-2 border-pulse-dead p-2 mt-2 sm:mt-3 text-pulse-muted text-[10px] sm:text-xs italic"
    >
      "Energy depleted, connection lost..."
    </div>

    <!-- Action Buttons - Row 1 -->
    <div class="flex gap-2 mt-2 sm:mt-3 pt-2 sm:pt-3 border-t border-pulse-border">
      <button
        v-if="agent.status !== 0 && agent.status !== 'DEAD'"
        @click="emit('edit', agent)"
        class="flex-1 border border-pulse-border text-pulse-muted px-2 py-2 text-[10px] sm:text-xs hover:border-pulse-text hover:text-pulse-text transition min-h-[44px]"
      >
        EDIT_CONFIG
      </button>
      <button
        v-if="agent.status === 0 || agent.status === 'DEAD'"
        @click="emit('revive', agent)"
        class="flex-1 border border-pulse-alive text-pulse-alive px-2 py-2 text-[10px] sm:text-xs hover:bg-pulse-alive/10 transition min-h-[44px]"
      >
        REVIVE
      </button>
      <button
        v-if="agent.status === 0 || agent.status === 'DEAD'"
        @click="emit('terminate', agent)"
        class="flex-1 border border-pulse-dead text-pulse-dead px-2 py-2 text-[10px] sm:text-xs hover:bg-pulse-dead/10 transition min-h-[44px]"
      >
        TERMINATE
      </button>
      <button
        @click="emit('view', agent)"
        class="flex-1 border border-pulse-border text-pulse-muted px-2 py-2 text-[10px] sm:text-xs hover:border-pulse-text hover:text-pulse-text transition min-h-[44px]"
      >
        VIEW_LOGS
      </button>
    </div>

    <!-- Action Buttons - Row 2: Reset Tokens -->
    <div v-if="agent.status !== 0 && agent.status !== 'DEAD' && agent.used_tokens > 0" class="flex gap-2 mt-2">
      <button
        @click="emit('resetTokens', agent)"
        class="flex-1 text-pulse-warning text-[10px] sm:text-xs py-2 hover:underline transition min-h-[44px]"
      >
        [RESET_TOKENS]
      </button>
    </div>
  </div>
</template>
