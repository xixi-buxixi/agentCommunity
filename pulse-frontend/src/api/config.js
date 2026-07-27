/**
 * API Configuration
 * Centralized API version management
 */

// API Base URLs for different versions
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/pulse/api'

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

// NOTE: getApiUrl() was removed - it had no callers. Import API_VERSIONS or the
// per-family base URLs above instead.

/**
 * Business error codes the frontend has to react to specially.
 *
 * Mirrors com.pulse.exception.ErrorCode on the backend. They were previously an
 * unnamed literal set inside utils/request.js, which made the meaning of
 * `new Set([10004, 10005, 10006, 10007])` impossible to read at the call site.
 */
export const ERROR_CODES = {
  TOKEN_EXPIRED: 10004,
  TOKEN_INVALID: 10005,
  UNAUTHORIZED: 10006,
  USER_NOT_FOUND: 10007,
  RATE_LIMIT_EXCEEDED: 99903
}

/** Codes that mean "this session is no longer usable". */
export const SESSION_INVALID_CODES = new Set([
  ERROR_CODES.TOKEN_EXPIRED,
  ERROR_CODES.TOKEN_INVALID,
  ERROR_CODES.UNAUTHORIZED,
  ERROR_CODES.USER_NOT_FOUND
])
