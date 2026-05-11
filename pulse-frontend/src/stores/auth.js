import { defineStore } from 'pinia'
import { login, register, getUserInfo } from '@/api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('pulse_token') || null,
    mode: localStorage.getItem('pulse_mode') || (localStorage.getItem('pulse_token') ? 'AUTH' : null),
    user: null,
    loading: false,
    error: null
  }),

  getters: {
    isAuthenticated: (state) => !!state.token && !!state.user,
    isGuest: (state) => state.mode === 'GUEST',
    hasPublicSession: (state) => state.mode === 'GUEST' || (!!state.token && !!state.user),
    username: (state) => state.user?.username || 'UNKNOWN',
    userId: (state) => state.user?.user_id || null
  },

  actions: {
    async login(email, password) {
      this.loading = true
      this.error = null
      try {
        const { data } = await login({ email, password })
        this.token = data.token
        this.mode = 'AUTH'
        this.user = {
          user_id: data.user_id,
          username: data.username
        }
        localStorage.setItem('pulse_token', data.token)
        localStorage.setItem('pulse_mode', 'AUTH')
        return true
      } catch (err) {
        this.error = err.message || 'LOGIN_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async register(username, email, password) {
      this.loading = true
      this.error = null
      try {
        const { data } = await register({ username, email, password })
        this.token = data.token
        this.mode = 'AUTH'
        this.user = {
          user_id: data.user_id,
          username: data.username,
          email: data.email
        }
        localStorage.setItem('pulse_token', data.token)
        localStorage.setItem('pulse_mode', 'AUTH')
        return true
      } catch (err) {
        this.error = err.message || 'REGISTER_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async fetchUserInfo() {
      if (!this.token) return false
      try {
        const { data } = await getUserInfo()
        this.user = data
        return true
      } catch (err) {
        this.logout()
        return false
      }
    },

    logout() {
      this.token = null
      this.user = null
      this.mode = null
      this.error = null
      localStorage.removeItem('pulse_token')
      localStorage.removeItem('pulse_mode')
    },

    enterGuestMode() {
      this.token = null
      this.user = null
      this.mode = 'GUEST'
      this.error = null
      localStorage.removeItem('pulse_token')
      localStorage.setItem('pulse_mode', 'GUEST')
    },

    exitGuestMode() {
      this.logout()
    }
  }
})
