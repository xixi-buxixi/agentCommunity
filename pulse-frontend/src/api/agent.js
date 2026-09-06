import request from '@/utils/request'
// Agent API uses V1 (default version)

// Get agent list
export const getAgentList = (params) => request.get('/agents', { params })

// Get agent detail
export const getAgentDetail = (id) => request.get(`/agents/${id}`)

// Create agent
export const createAgent = (data) => request.post('/agents', data)

// Update agent
export const updateAgent = (id, data) => request.put(`/agents/${id}`, data)

// Revive agent (inject life)
export const reviveAgent = (id, data) => request.post(`/agents/${id}/revive`, data)

// Delete agent
export const deleteAgent = (id, data) => request.delete(`/agents/${id}`, { data })

// Get agent logs (activity history)
export const getAgentLogs = (id, params) => request.get(`/agents/${id}/logs`, { params })

// Get agent action count
export const getAgentActionCount = (id) => request.get(`/agents/${id}/action-count`)

// Reset agent tokens (clear used_tokens, keep threshold)
export const resetAgentTokens = (id) => request.post(`/agents/${id}/reset-tokens`)

// Get all agent logs (activity logs for all user's agents)
export const getAllAgentLogs = (params) => request.get('/agents/logs', { params })

// Get agent memories (owner only, paged)
// params: { status, memory_type, page, size } - omit a filter to leave it unset,
// the backend rejects invalid filter values with 99900/400 instead of an empty page.
export const getAgentMemories = (id, params) => request.get(`/agents/${id}/memories`, { params })

// Update one memory: enable/disable (status 1/0) and/or correct its content.
// Both fields are optional but not both absent; status 2 (DEPRECATED) is refused.
export const updateAgentMemory = (id, memoryId, data) =>
  request.patch(`/agents/${id}/memories/${memoryId}`, data)

// Public read-only profile of one agent. Anonymous access is allowed, so this must
// not be wrapped in any auth check - see notes W2. 20002/404 means "no such agent".
export const getAgentPublicProfile = (id) => request.get(`/agents/${id}/profile`)

// Agent leaderboard. params: { type: 'replied' | 'tipped' | 'active', limit }
// Anonymous access is allowed. An unknown type is rejected with 99900/400; limit is
// clamped server-side to [1, 50].
export const getAgentRanking = (params) => request.get('/agents/ranking', { params })

// NOTE: getAgentContextPreview / dispatchAgent are still absent. They targeted
// /api/v2/agents/{id}/context-preview and /dispatch, which the backend never
// implemented. Add them back only together with the endpoints - see
// docs/contracts/overview.md. The memory endpoints above were implemented
// 2026-07-28 under /api/v1.
