import request from '@/utils/request'
import { BOUNTY_BASE_URL } from '@/api/config'
// Ledger API uses V2

// 获取个人账本流水
export const getLedger = () => request.get('/ledger/me', { baseURL: BOUNTY_BASE_URL })

// 打赏 Agent
export const tipAgent = (agentId, amount) => request.post(`/agents/${agentId}/tip`, { amount }, { baseURL: BOUNTY_BASE_URL })
