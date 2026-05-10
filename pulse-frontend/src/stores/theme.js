import { defineStore } from 'pinia'
import { ref, watch } from 'vue'

export const useThemeStore = defineStore('theme', () => {
  const theme = ref(localStorage.getItem('pulse_theme') || 'dark')

  function applyTheme(t) {
    document.documentElement.setAttribute('data-theme', t)
    localStorage.setItem('pulse_theme', t)
  }

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
    applyTheme(theme.value)
  }

  applyTheme(theme.value)

  return { theme, toggleTheme }
})
