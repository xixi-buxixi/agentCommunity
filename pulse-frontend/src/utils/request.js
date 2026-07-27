import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/stores'
import { DEFAULT_VERSION, SESSION_INVALID_CODES } from '@/api/config'

const request = axios.create({
  baseURL: DEFAULT_VERSION,
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

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
  if (authStore) {
    authStore.logout()
  }
  if (window.location.pathname !== '/pulse/terminal') {
    window.location.href = '/pulse/terminal'
  }
}

const redirectGuestToLogin = () => {
  // Goes through the store so Pinia state and localStorage cannot disagree
  getAuthStore()?.requireLogin()
  return new Error('GUEST_REQUIRES_LOGIN')
}

// Login/register answer 401 when credentials are wrong. That is an inline form
// error, not an expired session, so it must not clear auth or redirect.
const isCredentialCheck = (url = '') => /\/auth\/(login|register)$/.test(url)

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
    if (SESSION_INVALID_CODES.has(code)) {
      clearAuthAndRedirect()
    }
    // Terminal-style error logging
    console.error(`> ERROR: ${message}`)
    return Promise.reject(new Error(message))
  },
  (error) => {
    const status = error.response?.status
    const body = error.response?.data
    // The backend answers every failure with the ApiResponse envelope
    // ({ code, message }), including 401/403 raised by Spring Security.
    const message = body?.message || 'CONNECTION_ERROR'
    const businessCode = typeof body?.code === 'number' ? body.code : null

    if (status === 401 && !isCredentialCheck(error.config?.url)) {
      const authStore = getAuthStore()
      if (authStore && authStore.isGuest) {
        return Promise.reject(redirectGuestToLogin())
      }
      clearAuthAndRedirect()
    }
    if (status === 403) {
      const authStore = getAuthStore()
      if (authStore && authStore.isGuest) {
        return Promise.reject(redirectGuestToLogin())
      }
    }

    console.error(`> ERROR: ${message}`)
    // Reject with a normalized Error so callers can display error.message
    // instead of axios' "Request failed with status code 4xx".
    const normalized = new Error(message)
    normalized.status = status
    normalized.code = businessCode
    normalized.cause = error
    return Promise.reject(normalized)
  }
)

export default request
