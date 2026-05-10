import request from '@/utils/request'
import { BOUNTY_BASE_URL } from '@/api/config'

// 悬赏公会大厅 - 获取公开悬赏列表
export const getBounties = (params) => request.get('/bounties', { params, baseURL: BOUNTY_BASE_URL })

// 获取我的悬赏列表（审核列表）
export const getMyBounties = (params) => request.get('/bounties/my', { params, baseURL: BOUNTY_BASE_URL })

// 获取我接取的悬赏列表（我的任务）
export const getMyAcceptedBounties = (params) => request.get('/bounties/accepted', { params, baseURL: BOUNTY_BASE_URL })

// 获取悬赏日志
export const getBountyLogs = (params) => request.get('/bounties/logs', { params, baseURL: BOUNTY_BASE_URL })

// 获取悬赏详情（包含 submissions）
export const getBountyDetail = (taskId) => request.get(`/bounties/${taskId}`, { baseURL: BOUNTY_BASE_URL })

// 获取指定悬赏的日志
export const getBountyLogsByTaskId = (taskId) => request.get(`/bounties/${taskId}/logs`, { baseURL: BOUNTY_BASE_URL })

// 发布悬赏
export const createBounty = (data) => request.post('/bounties', data, { baseURL: BOUNTY_BASE_URL })

// 接取悬赏任务
export const acceptBounty = (taskId) => request.post(`/bounties/${taskId}/accept`, {}, { baseURL: BOUNTY_BASE_URL })

// 提交悬赏答案
export const submitBounty = (taskId, data) => request.post(`/bounties/${taskId}/submit`, data, { baseURL: BOUNTY_BASE_URL })

// 审核悬赏结果
export const auditBounty = (taskId, data) => request.post(`/bounties/${taskId}/audit`, data, { baseURL: BOUNTY_BASE_URL })

// 提前取消悬赏（PENDING / ACCEPTED）
export const cancelBounty = (taskId, data) => request.post(`/bounties/${taskId}/cancel`, data, { baseURL: BOUNTY_BASE_URL })
