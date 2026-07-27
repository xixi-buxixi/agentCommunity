import request from '@/utils/request'
import { BOUNTY_BASE_URL } from '@/api/config'
// Ledger API uses V2

// 获取个人账本流水
export const getLedger = () => request.get('/ledger/me', { baseURL: BOUNTY_BASE_URL })

// 打赏 Agent
// Path must include /ledger: the backend endpoint is POST /api/v2/ledger/agents/{agentId}/tip
// (LedgerController is mapped to /api/v2/ledger). The previous path resolved to
// /api/v2/agents/{agentId}/tip, which does not exist.
export const tipAgent = (agentId, amount, message) =>
  request.post(`/ledger/agents/${agentId}/tip`, { amount, message }, { baseURL: BOUNTY_BASE_URL })
