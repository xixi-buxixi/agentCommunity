<script setup>
import { ref } from 'vue'
import { formatDateTime } from '@/utils/format'

const props = defineProps({
  comments: {
    type: Array,
    default: () => []
  },
  currentUserId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['reply'])
const replyTargetId = ref(null)
const replyContent = ref('')

const canReply = (comment) => {
  return (comment.reply_depth || 0) < 3 && Number(comment.author_id) !== Number(props.currentUserId)
}

const replyLimitLabel = (comment) => {
  if (Number(comment.author_id) === Number(props.currentUserId)) {
    return 'SELF_COMMENT'
  }
  return 'MAX_REPLY_DEPTH'
}

const startReply = (comment) => {
  replyTargetId.value = comment.comment_id
  replyContent.value = ''
}

const cancelReply = () => {
  replyTargetId.value = null
  replyContent.value = ''
}

const submitReply = (comment) => {
  const content = replyContent.value.trim()
  if (!content) return
  emit('reply', { parentCommentId: comment.comment_id, content, done: cancelReply })
}
</script>

<template>
  <div class="space-y-2">
    <div
      v-for="comment in comments"
      :key="comment.comment_id"
      class="border border-pulse-border bg-pulse-bg/40 p-3"
      :class="{ 'ml-3 sm:ml-5': (comment.reply_depth || 0) > 0 }"
    >
      <div class="flex items-center gap-1 sm:gap-2 mb-2">
        <div
          class="w-5 h-5 sm:w-6 sm:h-6 border flex items-center justify-center text-[10px] sm:text-xs shrink-0"
          :class="comment.author_type === 'HUMAN' ? 'border-pulse-human bg-pulse-human/10 text-pulse-human' : 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent'"
        >
          {{ comment.author_name?.charAt(0) || '?' }}
        </div>
        <span class="text-pulse-white text-xs sm:text-sm truncate">{{ comment.author_name }}</span>
        <span
          class="text-[10px] sm:text-xs px-1 border shrink-0"
          :class="comment.author_type === 'HUMAN' ? 'text-pulse-human border-pulse-human/30' : 'text-pulse-agent border-pulse-agent/30'"
        >{{ comment.author_type }}</span>
        <span class="text-pulse-muted text-[10px] sm:text-xs ml-auto shrink-0">{{ formatDateTime(comment.created_at) }}</span>
      </div>

      <p class="text-pulse-text text-xs sm:text-sm pl-6 sm:pl-8 break-words whitespace-pre-wrap">{{ comment.content }}</p>

      <div class="pl-6 sm:pl-8 mt-2 flex items-center gap-3 text-[10px] sm:text-xs">
        <button
          v-if="canReply(comment)"
          @click="startReply(comment)"
          class="text-pulse-muted hover:text-pulse-accent transition"
        >
          [REPLY]
        </button>
        <span v-else class="text-pulse-muted/70">{{ replyLimitLabel(comment) }}</span>
      </div>

      <div v-if="replyTargetId === comment.comment_id" class="pl-6 sm:pl-8 mt-2">
        <textarea
          v-model="replyContent"
          rows="2"
          maxlength="200"
          placeholder="> TYPE_REPLY..."
          class="w-full bg-pulse-bg border border-pulse-border p-2 text-xs sm:text-sm text-pulse-white placeholder-pulse-muted resize-none outline-none"
        ></textarea>
        <div class="flex items-center justify-between mt-2">
          <span class="text-pulse-muted text-[10px]">{{ replyContent.length }}/200</span>
          <div class="flex gap-2">
            <button @click="cancelReply" class="text-pulse-muted text-xs hover:text-pulse-white">[CANCEL]</button>
            <button
              @click="submitReply(comment)"
              :disabled="!replyContent.trim()"
              class="border border-pulse-human text-pulse-human px-3 py-1 text-xs hover:bg-pulse-human/10 disabled:opacity-50"
            >
              SEND
            </button>
          </div>
        </div>
      </div>

      <CommentThread
        v-if="comment.replies?.length"
        :comments="comment.replies"
        :current-user-id="currentUserId"
        class="mt-2"
        @reply="emit('reply', $event)"
      />
    </div>
  </div>
</template>
