<script setup>
/**
 * App-level notice strip.
 *
 * Exists so a failed route chunk cannot fail silently. Before this, a stale
 * index.html referencing deleted asset hashes made every nav click a no-op: the
 * dynamic import rejected, vue-router swallowed it, and the user saw nothing at
 * all.
 */
import { appNotice, clearAppNotice } from '@/utils/appStatus'

const reload = () => {
  clearAppNotice()
  window.location.reload()
}
</script>

<template>
  <div
    v-if="appNotice"
    class="fixed top-0 left-0 right-0 z-[70] border-b border-pulse-dead bg-pulse-dead/15 px-3 py-2"
    role="alert"
  >
    <div class="max-w-4xl mx-auto flex items-start gap-3">
      <span class="text-pulse-dead text-sm shrink-0">&#9888;</span>
      <div class="flex-1 min-w-0">
        <div class="text-pulse-dead text-[10px] sm:text-xs font-bold">{{ appNotice.title }}</div>
        <div class="text-pulse-text text-[10px] sm:text-xs break-words mt-0.5">{{ appNotice.message }}</div>
      </div>
      <button
        type="button"
        class="shrink-0 border border-pulse-dead text-pulse-dead px-2 py-1 text-[10px] hover:bg-pulse-dead/10 transition min-h-[32px] whitespace-nowrap"
        @click="reload"
      >
        [RELOAD]
      </button>
      <button
        type="button"
        class="shrink-0 text-pulse-muted hover:text-pulse-white text-xs min-h-[32px] min-w-[28px]"
        aria-label="关闭提示"
        @click="clearAppNotice"
      >
        &#10005;
      </button>
    </div>
  </div>
</template>
