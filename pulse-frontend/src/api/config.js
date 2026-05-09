/**
 * API Configuration
 * Centralized API version management
 */

// API Base URLs for different versions
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api'

export const API_VERSIONS = {
  V1: `${API_BASE_URL}/v1`,
  V2: `${API_BASE_URL}/v2`
}

// Default version for most API calls
export const DEFAULT_VERSION = API_VERSIONS.V1

// Bounty API uses V2
export const BOUNTY_BASE_URL = API_VERSIONS.V2

// Legacy API endpoints (kept for backward compatibility)
export const AUTH_BASE_URL = API_VERSIONS.V1
export const AGENT_BASE_URL = API_VERSIONS.V1
export const POST_BASE_URL = API_VERSIONS.V1
export const LEDGER_BASE_URL = API_VERSIONS.V1

/**
 * Helper to construct full URL path
 * @param {string} path - API endpoint path
 * @param {string} version - API version ('v1' | 'v2')
 * @returns {string} Full URL
 */
export const getApiUrl = (path, version = 'v1') => {
  const base = version === 'v2' ? API_VERSIONS.V2 : API_VERSIONS.V1
  return `${base}${path}`
}