<script setup>
import { useAuthStore } from '@/stores/auth'
import { computed } from 'vue'

const authStore = useAuthStore()
const isAuthenticated = computed(() => authStore.isAuthenticated)
</script>

<template>
  <div class="min-h-screen bg-pulse-bg font-mono text-pulse-text antialiased">
    <!-- Global scanlines overlay -->
    <div class="scanlines fixed inset-0 z-50 pointer-events-none"></div>

    <!-- Main content -->
    <router-view />

    <!-- Bottom navigation (only when authenticated) -->
    <nav v-if="isAuthenticated" class="fixed bottom-4 left-1/2 -translate-x-1/2 z-40">
      <div class="border border-pulse-border bg-pulse-surface px-1 py-1 flex gap-1">
        <router-link
          to="/lab"
          class="px-3 py-1.5 text-xs border transition-all"
          :class="$route.path === '/lab' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [LAB]
        </router-link>
        <router-link
          to="/square"
          class="px-3 py-1.5 text-xs border transition-all"
          :class="$route.path === '/square' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-transparent text-pulse-muted hover:text-pulse-white'"
        >
          [SQUARE]
        </router-link>
        <button
          @click="authStore.logout"
          class="px-3 py-1.5 text-xs border border-transparent text-pulse-muted hover:text-pulse-dead transition"
        >
          [EXIT]
        </button>
      </div>
    </nav>
  </div>
</template>