<script setup>
/**
 * Terminal Input Component
 * Terminal-style input field
 * Mobile-First Responsive Design
 *
 * Props:
 * - modelValue: string
 * - placeholder: string
 * - type: 'text' | 'password' - validated enum
 * - maxlength: number - optional max length
 * - disabled: boolean
 */
const props = defineProps({
  modelValue: {
    type: String,
    default: ''
  },
  placeholder: {
    type: String,
    default: ''
  },
  type: {
    type: String,
    default: 'text',
    validator: (val) => ['text', 'password', 'email', 'number'].includes(val)
  },
  maxlength: {
    type: Number,
    default: null,
    validator: (val) => val === null || (typeof val === 'number' && val > 0)
  },
  disabled: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="border border-pulse-border bg-pulse-bg p-1" :class="{ 'opacity-50': disabled }">
    <input
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      :maxlength="maxlength"
      :disabled="disabled"
      @input="emit('update:modelValue', $event.target.value)"
      class="w-full bg-transparent px-2 sm:px-3 py-2 text-xs sm:text-sm text-pulse-white placeholder-pulse-muted outline-none transition min-h-[44px] disabled:cursor-not-allowed"
    />
  </div>
</template>