import request from '@/utils/request'
// Notification API uses V1 (default version)

/**
 * 通知列表（分页，按 created_at DESC, id DESC）
 * @param {{unread_only?: boolean, page?: number, size?: number}} params
 */
export const getNotifications = (params) => request.get('/notifications', { params })

// 未读数，响应为 { count }
export const getUnreadCount = () => request.get('/notifications/unread-count')

// 单条标记已读
export const markNotificationRead = (id) => request.post(`/notifications/${id}/read`)

// 全部标记已读，响应为 { count: 0 }
export const markAllNotificationsRead = () => request.post('/notifications/read-all')
