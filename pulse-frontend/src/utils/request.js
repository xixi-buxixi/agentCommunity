import axios from 'axios'
import { useAuthStore } from '@/stores/auth'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api/v1',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// Request interceptor - add auth token
request.interceptors.request.use(
  (config) => {
    const authStore = useAuthStore()
    if (authStore.token) {
      config.headers.Authorization = `Bearer ${authStore.token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// Response interceptor - handle errors
request.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 200 || code === 201) {
      return { data, message }
    }
    // Terminal-style error logging
    console.error(`> ERROR: ${message}`)
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error.response?.status === 401) {
      const authStore = useAuthStore()
      authStore.logout()
      window.location.href = '/terminal'
    }
    const message = error.response?.data?.message || 'CONNECTION_ERROR'
    console.error(`> ERROR: ${message}`)
    return Promise.reject(error)
  }
)

export default request