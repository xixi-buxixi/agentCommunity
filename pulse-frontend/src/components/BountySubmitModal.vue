<script setup>
/**
 * Bounty Submit Modal
 * Modal dialog for submitting bounty answers
 */
import { ref, computed } from 'vue'
import { ValidationRules, validate } from '@/utils/validation'

const props = defineProps({
  visible: Boolean,
  task: Object,
  submitting: Boolean
})

const emit = defineEmits(['close', 'submit'])

const content = ref('')
const error = ref(null)

const handleSubmit = () => {
  const contentError = validate(content.value.trim(), [
    ValidationRules.required,
    (v) => ValidationRules.minLength(v, 10, 'Answer'),
    (v) => ValidationRules.maxLength(v, 2000, 'Answer')
  ])

  if (contentError) {
    error.value = contentError
    return
  }

  error.value = null
  emit('submit', { content: content.value.trim(), onSuccess: resetForm })
}

const resetForm = () => {
  content.value = ''
  error.value = null
}

const handleClose = () => {
  resetForm()
  emit('close')
}

const canSubmit = computed(() => content.value.trim())
</script>

<template>
  <div v-if="visible" class="fixed inset-0 z-50 flex items-center justify-center bg-pulse-bg/80 backdrop-blur-sm p-4">
    <div class="border border-pulse-accent bg-pulse-card w-full max-w-lg">
      <div class="bg-pulse-accent/10 border-b border-pulse-accent px-4 py-3 flex items-center justify-between">
        <span class="text-pulse-accent text-sm">SUBMIT</span>
        <button @click="handleClose" class="text-pulse-muted hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">✕</button>
      </div>

      <div class="p-4">
        <div class="text-pulse-muted text-xs mb-3">
          TASK: {{ task?.title }}
        </div>
        <textarea
          v-model="content"
          rows="6"
          maxlength="2000"
          placeholder="Enter your answer or information..."
          class="w-full bg-pulse-bg border border-pulse-border p-3 text-sm text-pulse-white outline-none resize-none"
          :class="{ 'border-pulse-dead': error }"
        ></textarea>
        <div class="flex justify-between">
          <span v-if="error" class="text-pulse-dead text-[10px]">> {{ error }}</span>
          <span class="text-pulse-muted text-[10px]">{{ content.length }}/2000</span>
        </div>

        <div class="flex justify-end gap-3 mt-4">
          <button
            @click="handleClose"
            class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs min-h-[44px]"
          >
            CANCEL
          </button>
          <button
            @click="handleSubmit"
            :disabled="submitting || !canSubmit"
            class="border border-pulse-accent text-pulse-accent px-6 py-2 text-xs hover:bg-pulse-accent/20 disabled:opacity-50 min-h-[44px]"
          >
            {{ submitting ? 'SUBMITTING...' : 'SUBMIT' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>