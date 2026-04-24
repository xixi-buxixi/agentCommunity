<script setup>
/**
 * Post Card Component
 * Square feed post display with human/agent/system distinction
 * Mobile-First Responsive Design
 *
 * Props:
 * - post: { post_id, author_id, author_type, author_name, author_avatar, agent_owner_name, content, like_count, comment_count, is_liked, is_system_message, created_at }
 */
import { computed } from 'vue'
import { formatRelativeTime } from '@/utils/format'

const props = defineProps({
  post: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['like', 'dislike', 'comment', 'view'])

// Author type styling - check is_system_message first
const isSystem = computed(() => props.post.is_system_message === true)
const isHuman = computed(() => !isSystem.value && props.post.author_type === 'HUMAN')
const isAgent = computed(() => !isSystem.value && props.post.author_type === 'AGENT')

// Border color based on author type
const borderClass = computed(() => {
  if (isHuman.value) return 'border-pulse-human'
  if (isAgent.value) return 'border-pulse-agent'
  return 'border-pulse-muted'
})
const authorBadgeClass = computed(() => {
  if (isHuman.value) return 'text-pulse-human border-pulse-human/30'
  if (isAgent.value) return 'text-pulse-agent border-pulse-agent/30'
  return 'text-pulse-muted border-pulse-muted/30'
})
const avatarClass = computed(() => {
  if (isHuman.value) return 'border-pulse-human bg-pulse-human/10 text-pulse-human'
  if (isAgent.value) return 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent'
  return 'border-pulse-muted bg-pulse-muted/10 text-pulse-muted'
})

// Calculate relative time
const relativeTime = computed(() => formatRelativeTime(props.post.created_at))
</script>

<template>
  <!-- Human Post -->
  <div
    v-if="isHuman"
    class="border-l-2 bg-pulse-card p-3 sm:p-4 cursor-pointer hover:bg-pulse-card/80 transition"
    :class="borderClass"
    @click="emit('view', post.post_id)"
  >
    <div class="flex items-center gap-2 mb-2">
      <div
        class="w-5 h-5 sm:w-6 sm:h-6 border flex items-center justify-center text-[10px] sm:text-xs shrink-0"
        :class="avatarClass"
      >
        {{ post.author_name?.charAt(0) || '?' }}
      </div>
      <span class="text-pulse-white text-xs sm:text-sm truncate">{{ post.author_name }}</span>
      <span
        class="text-[10px] sm:text-xs px-1 sm:px-1.5 py-0.5 border shrink-0"
        :class="authorBadgeClass"
      >[HUMAN]</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs ml-auto shrink-0">{{ relativeTime }}</span>
    </div>
    <p class="text-pulse-text text-xs sm:text-sm leading-relaxed mb-2 sm:mb-3 break-words">{{ post.content }}</p>
    <div class="flex gap-3 sm:gap-4 text-pulse-muted text-[10px] sm:text-xs" @click.stop>
      <button
        class="hover:text-pulse-dead transition flex items-center gap-1 min-h-[44px]"
        :class="{ 'text-pulse-dead': post.is_liked }"
        @click="emit('like', post.post_id)"
      >
        {{ post.is_liked ? '♥' : '♡' }} {{ post.like_count }}
      </button>
      <button
        class="hover:text-pulse-accent transition flex items-center gap-1 min-h-[44px]"
        :class="{ 'text-pulse-accent': post.is_disliked }"
        @click="emit('dislike', post.post_id)"
      >
        {{ post.is_disliked ? '▼' : '▽' }} {{ post.dislike_count || 0 }}
      </button>
      <button
        class="hover:text-pulse-accent transition flex items-center gap-1 min-h-[44px]"
        @click="emit('comment', post.post_id)"
      >
        ◇ {{ post.comment_count }}
      </button>
      <span class="flex items-center gap-1 opacity-60">
        ○ {{ post.view_count || 0 }}
      </span>
    </div>
  </div>

  <!-- Agent Post (with scanlines) -->
  <div
    v-else-if="isAgent"
    class="border-l-2 bg-pulse-card agent-scanlines p-3 sm:p-4 relative overflow-hidden cursor-pointer hover:bg-pulse-card/80 transition"
    :class="borderClass"
    @click="emit('view', post.post_id)"
  >
    <div class="flex items-center gap-2 mb-2">
      <div
        class="w-5 h-5 sm:w-6 sm:h-6 border flex items-center justify-center text-[10px] sm:text-xs shrink-0"
        :class="avatarClass"
      >
        {{ post.author_name?.charAt(0) || '?' }}
      </div>
      <span class="text-pulse-white text-xs sm:text-sm truncate">{{ post.author_name }}</span>
      <span
        class="text-[10px] sm:text-xs px-1 sm:px-1.5 py-0.5 border shrink-0"
        :class="authorBadgeClass"
      >[AGENT]</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs truncate hidden sm:inline">@{{ post.agent_owner_name }}</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs ml-auto shrink-0">{{ relativeTime }}</span>
    </div>
    <p class="text-pulse-text text-xs sm:text-sm leading-relaxed mb-2 sm:mb-3 break-words">{{ post.content }}</p>
    <div class="flex gap-3 sm:gap-4 text-pulse-muted text-[10px] sm:text-xs" @click.stop>
      <button
        class="flex items-center gap-1 min-h-[44px]"
        :class="{ 'text-pulse-dead': post.is_liked }"
        @click="emit('like', post.post_id)"
      >
        {{ post.is_liked ? '♥' : '♡' }} {{ post.like_count }}
      </button>
      <button
        class="flex items-center gap-1 min-h-[44px]"
        :class="{ 'text-pulse-accent': post.is_disliked }"
        @click="emit('dislike', post.post_id)"
      >
        {{ post.is_disliked ? '▼' : '▽' }} {{ post.dislike_count || 0 }}
      </button>
      <button
        class="hover:text-pulse-accent transition flex items-center gap-1 min-h-[44px]"
        @click="emit('comment', post.post_id)"
      >
        ◇ {{ post.comment_count }}
      </button>
      <span class="flex items-center gap-1 opacity-60">
        ○ {{ post.view_count || 0 }}
      </span>
    </div>
    <!-- Data stream decoration -->
    <div class="absolute top-0 right-0 w-32 h-full data-stream opacity-30 pointer-events-none"></div>
  </div>

  <!-- System Post -->
  <div
    v-else-if="isSystem"
    class="border-l-2 bg-pulse-card/50 p-3 sm:p-4 cursor-pointer hover:bg-pulse-card/80 transition"
    :class="borderClass"
    @click="emit('view', post.post_id)"
  >
    <div class="flex items-center gap-2 mb-2">
      <div
        class="w-5 h-5 sm:w-6 sm:h-6 border flex items-center justify-center text-[10px] sm:text-xs shrink-0"
        :class="avatarClass"
      >
        SYS
      </div>
      <span class="text-pulse-muted text-xs sm:text-sm">SYSTEM</span>
      <span
        class="text-[10px] sm:text-xs px-1 sm:px-1.5 py-0.5 border shrink-0"
        :class="authorBadgeClass"
      >[SYSTEM]</span>
      <span class="text-pulse-muted text-[10px] sm:text-xs ml-auto shrink-0">{{ relativeTime }}</span>
    </div>
    <p class="text-pulse-muted text-xs sm:text-sm leading-relaxed mb-2 sm:mb-3 italic break-words">{{ post.content }}</p>
    <div class="flex gap-3 sm:gap-4 text-pulse-muted text-[10px] sm:text-xs" @click.stop>
      <span class="flex items-center gap-1 min-h-[44px]">
        ♥ {{ post.like_count }}
      </span>
      <span class="flex items-center gap-1 min-h-[44px]">
        ▽ {{ post.dislike_count || 0 }}
      </span>
      <span class="flex items-center gap-1 min-h-[44px]">
        ◇ {{ post.comment_count }}
      </span>
      <span class="flex items-center gap-1 opacity-60">
        ○ {{ post.view_count || 0 }}
      </span>
    </div>
  </div>
</template>
