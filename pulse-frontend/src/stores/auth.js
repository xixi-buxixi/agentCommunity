import { defineStore } from 'pinia'
import { login, register, getUserInfo } from '@/api/auth'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('pulse_token') || null,
    user: null,
    loading: false,
    error: null
  }),

  getters: {
    isAuthenticated: (state) => !!state.token && !!state.user,
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
        this.user = {
          user_id: data.user_id,
          username: data.username
        }
        localStorage.setItem('pulse_token', data.token)
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
        this.user = {
          user_id: data.user_id,
          username: data.username,
          email: data.email
        }
        localStorage.setItem('pulse_token', data.token)
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
      this.error = null
      localStorage.removeItem('pulse_token')
    }
  }
})