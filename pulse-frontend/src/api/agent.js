import request from '@/utils/request'
import { API_VERSIONS } from '@/api/config'
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

// Evolution: Agent long-term memories
export const getAgentMemories = (id, params) => request.get(`/agents/${id}/memories`, { params, baseURL: API_VERSIONS.V2 })

// Evolution: preview mixed context selection before dispatch
export const getAgentContextPreview = (id) => request.get(`/agents/${id}/context-preview`, { baseURL: API_VERSIONS.V2 })

// Evolution: manually trigger one decision cycle for demo/debug
export const dispatchAgent = (id, data = { dry_run: false }) => request.post(`/agents/${id}/dispatch`, data, { baseURL: API_VERSIONS.V2 })
