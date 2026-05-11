import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/main'
import { DEFAULT_VERSION } from '@/api/config'

const request = axios.create({
  baseURL: DEFAULT_VERSION,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

const AUTH_ERROR_CODES = new Set([10004, 10005, 10006, 10007])
const GUEST_NOTICE = '当前为访客模式，功能无法正常使用，如需使用，请登录账号'

// Get auth store instance safely (outside of component setup)
// Must use the pinia instance created in main.js
let authStoreInstance = null
const getAuthStore = () => {
  if (!authStoreInstance && pinia) {
    authStoreInstance = useAuthStore(pinia)
  }
  return authStoreInstance
}

const clearAuthAndRedirect = () => {
  const authStore = getAuthStore()
  if (authStore?.isGuest) {
    return
  }
  if (authStore) {
    authStore.logout()
  }
  if (!window.location.pathname.endsWith('/terminal')) {
    window.location.href = '/pulse/terminal'
  }
}

// Request interceptor - add auth token
request.interceptors.request.use(
  (config) => {
    const authStore = getAuthStore()
    if (authStore && authStore.token) {
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
    if (code === 0 || code === 200 || code === 201) {
      return { data, message }
    }
    if (AUTH_ERROR_CODES.has(code)) {
      const authStore = getAuthStore()
      if (!authStore?.isGuest) {
        clearAuthAndRedirect()
      }
    }
    // Terminal-style error logging
    console.error(`> ERROR: ${message}`)
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error.response?.status === 401) {
      const authStore = getAuthStore()
      if (!authStore?.isGuest) {
        clearAuthAndRedirect()
      } else {
        return Promise.reject(new Error(GUEST_NOTICE))
      }
    }
    const message = error.response?.data?.message || 'CONNECTION_ERROR'
    console.error(`> ERROR: ${message}`)
    return Promise.reject(error)
  }
)

export default request
