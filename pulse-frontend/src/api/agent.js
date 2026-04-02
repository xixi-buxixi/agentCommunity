import request from '@/utils/request'

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