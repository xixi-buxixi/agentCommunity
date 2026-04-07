import request from '@/utils/request'

const getBaseUrl = () => {
  const url = import.meta.env.VITE_API_BASE_URL || '/api/v1'
  return url.replace('/v1', '/v2')
}

// 悬赏公会大厅 - 获取悬赏列表
export const getBounties = (params) => request.get('/bounties', { params, baseURL: getBaseUrl() })

// 发布悬赏
export const createBounty = (data) => request.post('/bounties', data, { baseURL: getBaseUrl() })

// 接取悬赏任务
export const acceptBounty = (taskId) => request.post(`/bounties/${taskId}/accept`, {}, { baseURL: getBaseUrl() })

// 提交悬赏答案
export const submitBounty = (taskId, data) => request.post(`/bounties/${taskId}/submit`, data, { baseURL: getBaseUrl() })

// 审核悬赏结果
export const auditBounty = (taskId, data) => request.post(`/bounties/${taskId}/audit`, data, { baseURL: getBaseUrl() })
