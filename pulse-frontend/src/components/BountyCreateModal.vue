<script setup>
/**
 * Bounty Create Modal
 * Modal dialog for creating new bounty tasks
 */
import { ref, computed } from 'vue'
import { ValidationRules, validateObject, hasErrors, getErrorMessages } from '@/utils/validation'

const props = defineProps({
  visible: Boolean,
  creating: Boolean
})

const emit = defineEmits(['close', 'create'])

const form = ref({
  agentId: null,
  title: '',
  description: '',
  rewardPoints: 50,
  deadlineHours: 72
})

const errors = ref({})

const createSchema = {
  title: [
    ValidationRules.required,
    (v) => ValidationRules.minLength(v, 5, 'Title'),
    (v) => ValidationRules.maxLength(v, 50, 'Title')
  ],
  description: [
    ValidationRules.required,
    (v) => ValidationRules.minLength(v, 10, 'Description'),
    (v) => ValidationRules.maxLength(v, 500, 'Description')
  ],
  rewardPoints: [
    ValidationRules.required,
    (v) => ValidationRules.numberRange(v, 10, 500, 'Reward Points')
  ],
  deadlineHours: [
    ValidationRules.required,
    (v) => ValidationRules.numberRange(v, 1, 168, 'Valid Hours')
  ]
}

const handleCreate = () => {
  errors.value = validateObject({
    title: form.value.title,
    description: form.value.description,
    rewardPoints: form.value.rewardPoints,
    deadlineHours: form.value.deadlineHours
  }, createSchema)

  if (hasErrors(errors.value)) {
    emit('create', { error: getErrorMessages(errors.value)[0] })
    return
  }

  const payload = {
    title: form.value.title.trim(),
    description: form.value.description.trim(),
    reward_points: Math.max(10, Math.min(500, form.value.rewardPoints)),
    deadline_hours: Math.max(1, Math.min(168, form.value.deadlineHours)),
    agent_id: form.value.agentId
  }
  emit('create', { payload, onSuccess: resetForm })
}

const resetForm = () => {
  form.value = {
    agentId: null,
    title: '',
    description: '',
    rewardPoints: 50,
    deadlineHours: 72
  }
  errors.value = {}
}

const handleClose = () => {
  resetForm()
  emit('close')
}

const canSubmit = computed(() => form.value.title && form.value.description)
</script>

<template>
  <div v-if="visible" class="fixed inset-0 z-50 flex items-center justify-center bg-pulse-bg/80 backdrop-blur-sm p-4">
    <div class="border border-pulse-alive bg-pulse-card w-full max-w-lg">
      <div class="bg-pulse-alive/10 border-b border-pulse-alive px-4 py-3 flex items-center justify-between">
        <span class="text-pulse-alive text-sm">CREATE_BOUNTY</span>
        <button @click="handleClose" class="text-pulse-muted hover:text-pulse-white min-h-[44px] min-w-[44px] flex items-center justify-center">✕</button>
      </div>

      <div class="p-4">
        <div class="space-y-4">
          <div>
            <label class="text-pulse-muted text-xs mb-1 block">TITLE *</label>
            <input
              v-model="form.title"
              type="text"
              maxlength="50"
              placeholder="Enter title..."
              class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              :class="{ 'border-pulse-dead': errors.title }"
            />
            <div v-if="errors.title" class="text-pulse-dead text-[10px] mt-1">> {{ errors.title }}</div>
          </div>

          <div>
            <label class="text-pulse-muted text-xs mb-1 block">DESCRIPTION *</label>
            <textarea
              v-model="form.description"
              rows="4"
              maxlength="500"
              placeholder="Describe what help you need..."
              class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none resize-none"
              :class="{ 'border-pulse-dead': errors.description }"
            ></textarea>
            <div class="flex justify-between">
              <span v-if="errors.description" class="text-pulse-dead text-[10px]">> {{ errors.description }}</span>
              <span class="text-pulse-muted text-[10px]">{{ form.description.length }}/500</span>
            </div>
          </div>

          <div>
            <label class="text-pulse-muted text-xs mb-1 block">REWARD_POINTS (10-500)</label>
            <input
              v-model.number="form.rewardPoints"
              type="number"
              min="10"
              max="500"
              class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              :class="{ 'border-pulse-dead': errors.rewardPoints }"
            />
            <div v-if="errors.rewardPoints" class="text-pulse-dead text-[10px] mt-1">> {{ errors.rewardPoints }}</div>
          </div>

          <div>
            <label class="text-pulse-muted text-xs mb-1 block">VALID_HOURS (max 168)</label>
            <input
              v-model.number="form.deadlineHours"
              type="number"
              min="1"
              max="168"
              class="w-full bg-pulse-bg border border-pulse-border p-2 text-sm text-pulse-white outline-none"
              :class="{ 'border-pulse-dead': errors.deadlineHours }"
            />
            <div v-if="errors.deadlineHours" class="text-pulse-dead text-[10px] mt-1">> {{ errors.deadlineHours }}</div>
          </div>

          <div class="flex justify-end gap-3 pt-2">
            <button
              @click="handleClose"
              class="border border-pulse-border text-pulse-muted px-4 py-2 text-xs min-h-[44px]"
            >
              CANCEL
            </button>
            <button
              @click="handleCreate"
              :disabled="creating || !canSubmit"
              class="border border-pulse-alive text-pulse-alive px-6 py-2 text-xs hover:bg-pulse-alive/20 disabled:opacity-50 min-h-[44px]"
            >
              {{ creating ? 'CREATING...' : 'CREATE_BOUNTY' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>