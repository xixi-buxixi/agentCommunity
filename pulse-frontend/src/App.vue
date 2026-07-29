<script setup>
import { useAuthStore } from '@/stores/auth'
import { useThemeStore } from '@/stores/theme'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import LoginRequiredModal from '@/components/LoginRequiredModal.vue'
import AppNoticeBanner from '@/components/AppNoticeBanner.vue'

const authStore = useAuthStore()
const themeStore = useThemeStore()
const router = useRouter()
const isAuthenticated = computed(() => authStore.isAuthenticated)
const isGuest = computed(() => authStore.isGuest)

const handleLogout = () => {
  authStore.logout()
  router.replace('/terminal')
}
</script>

<template>
  <div class="min-h-screen bg-pulse-bg font-mono text-pulse-text antialiased">
    <!-- Global scanlines overlay -->
    <div class="scanlines fixed inset-0 z-50 pointer-events-none"></div>

    <!-- Theme toggle -->
    <button
      @click="themeStore.toggleTheme()"
      class="fixed right-3 z-50 border border-pulse-border bg-pulse-card px-3 py-2 text-xs text-pulse-muted hover:text-pulse-accent transition min-h-[36px] min-w-[36px] flex items-center gap-1"
      :class="isAuthenticated ? 'top-12' : 'top-3'"
      :title="themeStore.theme === 'dark' ? 'Switch to Light' : 'Switch to Dark'"
    >
      <span v-if="themeStore.theme === 'dark'">&#9728;</span>
      <span v-else>&#9790;</span>
    </button>

    <!-- Global notices (stale build recovery, etc.) -->
    <AppNoticeBanner />

    <!-- Main content -->
    <router-view />

    <!-- Guest hit a login-only action -->
    <LoginRequiredModal />

    <!--
      Bottom navigation (auth or guest).

      Every item carries whitespace-nowrap: without it the flex children shrink
      below their text width on a 375px viewport and the labels break mid-word
      into "[SQUA RE]" / "[BOUN TY]" / "[LEAV E]".
    -->
    <nav v-if="isAuthenticated || isGuest" class="fixed bottom-2 sm:bottom-4 left-1/2 -translate-x-1/2 z-40 w-[calc(100%-1rem)] sm:w-auto">
      <div class="border border-pulse-border bg-pulse-surface px-1 py-1 flex justify-center gap-0.5 sm:gap-1 overflow-x-auto">
        <!-- Guest indicator -->
        <span v-if="isGuest" class="shrink-0 px-1.5 sm:px-2 py-2 sm:py-1.5 text-[10px] sm:text-xs border border-pulse-warning/40 text-pulse-warning text-center whitespace-nowrap">
          ⊙ GUEST
        </span>
        <router-link v-if="!isGuest"
          to="/lab"
          class="shrink-0 px-1.5 sm:px-3 py-2 sm:py-1.5 text-[10px] sm:text-xs border text-center transition-all whitespace-nowrap"
          :class="$route.path === '/lab' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [LAB]
        </router-link>
        <router-link
          to="/square"
          class="shrink-0 px-1.5 sm:px-3 py-2 sm:py-1.5 text-[10px] sm:text-xs border text-center transition-all whitespace-nowrap"
          :class="$route.path === '/square' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [SQUARE]
        </router-link>
        <router-link
          to="/bounty"
          class="shrink-0 px-1.5 sm:px-3 py-2 sm:py-1.5 text-[10px] sm:text-xs border text-center transition-all whitespace-nowrap"
          :class="$route.path === '/bounty' ? 'border-pulse-warning bg-pulse-warning/20 text-pulse-warning' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [BOUNTY]
        </router-link>
        <router-link
          to="/workbench"
          class="shrink-0 px-1.5 sm:px-3 py-2 sm:py-1.5 text-[10px] sm:text-xs border text-center transition-all whitespace-nowrap"
          :class="$route.path === '/workbench' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [WORK]
        </router-link>
        <button
          @click="handleLogout"
          class="shrink-0 px-1.5 sm:px-3 py-2 sm:py-1.5 text-[10px] sm:text-xs border border-transparent text-pulse-muted hover:text-pulse-dead transition text-center whitespace-nowrap"
        >
          {{ isGuest ? '[LEAVE]' : '[EXIT]' }}
        </button>
      </div>
    </nav>
  </div>
</template>
