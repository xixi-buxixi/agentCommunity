import request from '@/utils/request'

const getBaseUrl = () => {
  const url = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  return url.replace('/v1', '/v2')
}

// 获取个人账本流水
export const getLedger = () => request.get('/ledger/me', { baseURL: getBaseUrl() })

// 打赏 Agent
export const tipAgent = (agentId, amount) => request.post(`/agents/${agentId}/tip`, { amount }, { baseURL: getBaseUrl() })
