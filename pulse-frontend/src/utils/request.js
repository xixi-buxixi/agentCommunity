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

  // A guest has no session to expire. Answering a guest's 401/403 by clearing
  // auth used to strip `isGuest` too, which left the visitor in a state that was
  // neither guest nor logged in — bottom-nav links then silently did nothing.
  // Guests get the prompt instead; only real expired sessions are cleared.
  if (authStore?.isGuest) {
    authStore.requireLogin('会话已失效或该内容需要登录')
    return
  }

  if (authStore) {
    authStore.logout()
  }
  if (window.location.pathname !== '/pulse/terminal') {
    const redirect = window.location.pathname.replace(/^\/pulse/, '') + window.location.search
    window.location.href = `/pulse/terminal?redirect=${encodeURIComponent(redirect || '/lab')}`
  }
}

const promptGuestLogin = () => {
  // Goes through the store so Pinia state and localStorage cannot disagree
  getAuthStore()?.requireLogin('该内容需要登录后查看')
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
        return Promise.reject(promptGuestLogin())
      }
      clearAuthAndRedirect()
    }
    if (status === 403) {
      const authStore = getAuthStore()
      if (authStore && authStore.isGuest) {
        return Promise.reject(promptGuestLogin())
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
