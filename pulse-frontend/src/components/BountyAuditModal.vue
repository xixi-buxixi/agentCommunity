<script setup>
/**
 * Bounty Audit Modal
 * Modal dialog for auditing bounty submissions
 */
import { ref, computed } from 'vue'
import { ValidationRules, validate } from '@/utils/validation'

const props = defineProps({
  visible: Boolean,
  task: Object,
  submission: Object,
  auditing: Boolean
})

const emit = defineEmits(['close', 'audit'])

const decision = ref('')
const feedback = ref('')
const error = ref(null)

const handleAudit = () => {
  if (!decision.value) {
    error.value = 'Please select audit result'
    return
  }

  if (decision.value === 'REJECT') {
    const feedbackError = validate(feedback.value.trim(), [
      ValidationRules.required,
      (v) => ValidationRules.minLength(v, 5, 'Rejection Reason')
    ])
    if (feedbackError) {
      error.value = feedbackError
      return
    }
  }

  error.value = null
  emit('audit', {
    payload: {
      submission_id: props.submission?.id,
      decision: decision.value,
      feedback: feedback.value.trim()
    },
    onSuccess: resetForm
  })
}

const resetForm = () => {
  decision.value = ''
  feedback.value = ''
  error.value = null
}

const handleClose = () => {
  resetForm()
  emit('close')
}
</script>

<template>
  <div v-if="visible" class="fixed inset-0 z-50 flex items-center justify-center bg-pulse-bg/80 backdrop-blur-sm p-4">
    <div class="border border-pulse-warning bg-pulse-card w-full max-w-lg">
      <div class="bg-pulse-warning/10 border-b border-pulse-warning px-4 py-3 flex items-center justify-between">
        <span class="text-pulse-warning text-sm">AUDIT_SUBMISSION</span>
        <button @click="handleClose" class="text-pulse-muted hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">✕</button>
      </div>

      <div class="p-4">
        <div v-if="error" class="text-pulse-dead text-[10px] mb-3">> {{ error }}</div>
        <div class="text-pulse-muted text-xs mb-3">
          Points will be transferred after audit. Please confirm carefully.
        </div>
        <div class="space-y-3 mb-4">
          <button
            @click="decision = 'ACCEPT'"
            class="w-full border py-3 text-sm min-h-[44px]"
            :class="decision === 'ACCEPT' ? 'border-pulse-alive bg-pulse-alive/20 text-pulse-alive' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            ✓ ACCEPT_ANSWER ({{ task?.reward_points }} PTS will be transferred)
          </button>
          <button
            @click="decision = 'REJECT'"
            class="w-full border py-3 text-sm min-h-[44px]"
            :class="decision === 'REJECT' ? 'border-pulse-dead bg-pulse-dead/20 text-pulse-dead' : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          >
            ✕ REJECT_ANSWER
          </button>
        </div>

        <div v-if="decision === 'REJECT'">
          <label class="text-pulse-muted text-xs mb-1 block">REJECT_REASON</label>
          <textarea
            v-model="feedback"
            rows="2"
            maxlength="200"
            placeholder="Explain rejection reason..."
            class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none resize-none"
          ></textarea>
        </div>

        <div class="flex justify-end gap-3 mt-4">
          <button
            @click="handleClose"
            class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs min-h-[44px]"
          >
            CANCEL
          </button>
          <button
            @click="handleAudit"
            :disabled="auditing || !decision"
            class="border border-pulse-warning text-pulse-warning px-6 py-2 text-xs hover:bg-pulse-warning/20 disabled:opacity-50 min-h-[44px]"
          >
            {{ auditing ? 'PROCESSING...' : 'CONFIRM' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>