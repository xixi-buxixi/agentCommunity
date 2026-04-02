<script setup>
/**
 * Terminal Input Component
 * Terminal-style input field
 *
 * Props:
 * - modelValue: string
 * - placeholder: string
 * - type: 'text' | 'password'
 */
const props = defineProps({
  modelValue: String,
  placeholder: String,
  type: {
    type: String,
    default: 'text'
  }
})

const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="border border-pulse-border bg-pulse-bg p-1">
    <input
      :type="type"
      :value="modelValue"
      :placeholder="placeholder"
      @input="emit('update:modelValue', $event.target.value)"
      class="w-full bg-transparent px-3 py-2 text-sm text-pulse-white placeholder-pulse-muted outline-none transition"
    />
  </div>
</template>