<script setup>
/**
 * Accessible modal shell.
 *
 * All eight dialogs in the app were hand-written `<div v-if>` overlays with none
 * of the behaviour a dialog needs: Escape did nothing, Tab wandered into the page
 * behind, the background scrolled, and screen readers were told nothing at all
 * (no role, no aria-modal, no labelled title). This component provides that once.
 *
 * Usage:
 *   <BaseModal v-if="open" title="CANCEL_BOUNTY" @close="open = false">
 *     ...body...
 *     <template #footer>...buttons...</template>
 *   </BaseModal>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  // Accent colour class for the frame, matching the terminal palette
  accent: { type: String, default: 'border-pulse-border' },
  // Set false for dialogs that must be answered (destructive confirmations)
  closeOnOverlay: { type: Boolean, default: true }
})

const emit = defineEmits(['close'])

const dialog = ref(null)
const titleId = computed(() => `modal-title-${props.title.replace(/\W+/g, '-').toLowerCase()}`)

let previouslyFocused = null

const FOCUSABLE = [
  'a[href]', 'button:not([disabled])', 'textarea:not([disabled])',
  'input:not([disabled]):not([type="hidden"])', 'select:not([disabled])',
  '[tabindex]:not([tabindex="-1"])'
].join(',')

const focusableElements = () =>
  Array.from(dialog.value?.querySelectorAll(FOCUSABLE) ?? [])
    .filter((el) => el.offsetParent !== null || el === document.activeElement)

const close = () => emit('close')

const onKeydown = (event) => {
  if (event.key === 'Escape') {
    event.stopPropagation()
    close()
    return
  }

  if (event.key !== 'Tab') return

  // Focus trap: keep Tab cycling inside the dialog
  const elements = focusableElements()
  if (elements.length === 0) {
    event.preventDefault()
    return
  }
  const first = elements[0]
  const last = elements[elements.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

const onOverlayClick = (event) => {
  if (!props.closeOnOverlay) return
  // Only when the overlay itself was clicked, not a child
  if (event.target === event.currentTarget) close()
}

onMounted(async () => {
  previouslyFocused = document.activeElement
  document.addEventListener('keydown', onKeydown, true)
  // Scroll lock: without it the page behind the dialog scrolls on mobile
  document.body.style.overflow = 'hidden'

  await nextTick()
  const elements = focusableElements()
  ;(elements[0] ?? dialog.value)?.focus()
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown, true)
  document.body.style.overflow = ''
  // Return focus where the user left it
  if (previouslyFocused instanceof HTMLElement) previouslyFocused.focus()
})
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 bg-pulse-bg/80 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4"
      @click="onOverlayClick"
    >
      <div
        ref="dialog"
        role="dialog"
        aria-modal="true"
        :aria-labelledby="titleId"
        tabindex="-1"
        class="bg-pulse-card border w-full sm:max-w-md max-h-[85vh] sm:max-h-none overflow-y-auto outline-none"
        :class="accent"
      >
        <div class="flex items-start justify-between gap-3 p-4 border-b border-pulse-border sticky top-0 bg-pulse-card">
          <div class="min-w-0">
            <h2 :id="titleId" class="text-pulse-white text-sm sm:text-base font-bold truncate">
              {{ title }}
            </h2>
            <p v-if="subtitle" class="text-pulse-muted text-[10px] sm:text-xs mt-1">{{ subtitle }}</p>
          </div>
          <button
            type="button"
            aria-label="关闭对话框"
            class="text-pulse-muted hover:text-pulse-white transition min-h-[44px] min-w-[44px] flex items-center justify-center shrink-0"
            @click="close"
          >
            [X]
          </button>
        </div>

        <div class="p-4 space-y-3">
          <slot />
        </div>

        <div v-if="$slots.footer" class="flex gap-2 p-4 pt-0">
          <slot name="footer" />
        </div>
      </div>
    </div>
  </Teleport>
</template>
