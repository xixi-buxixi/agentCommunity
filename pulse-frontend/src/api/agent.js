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

// NOTE: getAgentMemories / getAgentContextPreview / dispatchAgent were removed.
// They targeted /api/v2/agents/{id}/..., which does not exist: AgentController is
// mapped to /api/v1/agents and there is no v2 agent controller. Add them back only
// together with the endpoints - see docs/contracts/overview.md.
