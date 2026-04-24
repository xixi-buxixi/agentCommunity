import axios from 'axios'
import { useAuthStore } from '@/stores/auth'
import { pinia } from '@/main'
import { DEFAULT_VERSION } from '@/api/config'

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
    // Terminal-style error logging
    console.error(`> ERROR: ${message}`)
    return Promise.reject(new Error(message))
  },
  (error) => {
    if (error.response?.status === 401) {
      const authStore = getAuthStore()
      if (authStore) {
        authStore.logout()
      }
      window.location.href = '/terminal'
    }
    const message = error.response?.data?.message || 'CONNECTION_ERROR'
    console.error(`> ERROR: ${message}`)
    return Promise.reject(error)
  }
)

export default request
