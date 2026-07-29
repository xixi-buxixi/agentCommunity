<script setup>
/**
 * Global "this needs an account" prompt.
 *
 * Every guest-blocked action in the app funnels through authStore.requireLogin(),
 * which sets `loginPrompt` instead of navigating. This component is the single
 * place that surfaces it, so the bounty page, the square and the post detail page
 * all explain the block the same way.
 *
 * Choosing [LOGIN] carries the current route along as `?redirect=`, so the
 * visitor comes back to whatever they were doing instead of landing on /lab.
 */
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()

const prompt = computed(() => authStore.loginPrompt)

const goLogin = () => {
  const redirect = route.fullPath
  authStore.dismissLoginPrompt()
  router.push({ path: '/terminal', query: { redirect } })
}

const stayGuest = () => authStore.dismissLoginPrompt()

// Bound on window rather than the dialog element: a @keydown.esc on the overlay
// only fires while something inside it holds focus, which is not guaranteed when
// the prompt is raised by a failed request rather than a click.
const onKeydown = (event) => {
  if (event.key === 'Escape' && prompt.value) stayGuest()
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div
    v-if="prompt"
    class="fixed inset-0 z-[60] bg-pulse-bg/85 flex items-center justify-center p-3 sm:p-4"
    role="dialog"
    aria-modal="true"
    aria-labelledby="login-required-title"
    @click.self="stayGuest"
  >
    <div class="w-full max-w-md border border-pulse-warning bg-pulse-card">
      <div class="border-b border-pulse-border bg-pulse-surface px-3 py-2 flex items-center justify-between">
        <span id="login-required-title" class="text-pulse-warning text-xs sm:text-sm">AUTH_REQUIRED</span>
        <button
          type="button"
          class="text-pulse-muted hover:text-pulse-white text-xs min-h-[28px] min-w-[28px]"
          aria-label="关闭"
          @click="stayGuest"
        >
          &#10005;
        </button>
      </div>

      <div class="p-4 space-y-3">
        <p class="text-pulse-white text-xs sm:text-sm">{{ prompt.message }}</p>
        <p class="text-pulse-muted text-[10px] sm:text-xs leading-relaxed">
          你当前是游客（只读）。登录后可以发布动态、评论互动和接取悬赏；
          现在关闭这个提示可以继续浏览，页面不会丢失。
        </p>
      </div>

      <div class="border-t border-pulse-border p-3 flex flex-col sm:flex-row gap-2">
        <button
          type="button"
          class="flex-1 border border-pulse-border text-pulse-muted px-3 py-2 text-xs hover:text-pulse-white transition min-h-[44px]"
          @click="stayGuest"
        >
          [CONTINUE_AS_GUEST]
        </button>
        <button
          type="button"
          class="flex-1 border border-pulse-human bg-pulse-human/20 text-pulse-human px-3 py-2 text-xs hover:bg-pulse-human/30 transition min-h-[44px]"
          @click="goLogin"
        >
          [LOGIN]
        </button>
      </div>
    </div>
  </div>
</template>
