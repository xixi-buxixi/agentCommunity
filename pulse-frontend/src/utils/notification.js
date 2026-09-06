/**
 * 通知中心的纯逻辑：link_type 到路由的映射、未读角标、时间文案、错误码文案、
 * 分页追加时的去重。
 *
 * 抽出到这里而不是留在组件内，是为了让 `node --test` 在不加载 Vue 运行时的情况下
 * 覆盖这几条规则。对应后端 com.pulse.dto.response.NotificationResponse。
 */
import { formatRelativeTime } from './format.js'

/** 后端业务码，对应 com.pulse.exception.ErrorCode 的 90xxx 段。 */
export const NOTIFICATION_ERROR_CODES = {
  NOTIFICATIONS_UNAVAILABLE: 90001,
  NOTIFICATION_NOT_FOUND: 90002
}

/** 缺表时四个读接口都返回 90001，此时展示这句而不是空列表。 */
export const NOTIFICATIONS_UNAVAILABLE_TEXT = '当前部署未启用通知中心'

/** 角标上限，超过后显示 `99+`。 */
export const UNREAD_BADGE_MAX = 99

/** 列表分页大小，后端上限为 50。 */
export const NOTIFICATION_PAGE_SIZE = 20

/**
 * link_id 归一化为正整数，其余一律为 null。
 * @param {*} value
 * @returns {number|null}
 */
const normalizeLinkId = (value) => {
  if (value === null || value === undefined || value === '') return null
  const num = Number(value)
  if (!Number.isFinite(num) || !Number.isInteger(num) || num <= 0) return null
  return num
}

/**
 * 通知点击后的跳转目标。
 *
 * BOUNTY 只返回 `/bounty`：悬赏没有独立详情路由，详情是 BountyGuild 页内的视图
 * 状态，link_id 无法通过 URL 表达。link_type 为空或 link_id 非法时返回 null，
 * 调用方只标记已读、不跳转。
 *
 * @param {{link_type?: string, link_id?: *}} notification
 * @returns {string|null}
 */
export const resolveNotificationRoute = (notification) => {
  if (!notification || typeof notification !== 'object') return null
  const linkType = typeof notification.link_type === 'string'
    ? notification.link_type.toUpperCase()
    : null
  if (linkType === 'BOUNTY') return '/bounty'

  const linkId = normalizeLinkId(notification.link_id)
  if (linkId === null) return null
  if (linkType === 'POST') return `/post/${linkId}`
  if (linkType === 'AGENT') return `/agent/${linkId}`
  return null
}

/**
 * 角标文案。0 与非法值返回空串，由调用方决定是否渲染角标。
 * @param {*} count
 * @returns {string}
 */
export const formatUnreadBadge = (count) => {
  const num = Number(count)
  if (!Number.isFinite(num) || num <= 0) return ''
  if (num > UNREAD_BADGE_MAX) return `${UNREAD_BADGE_MAX}+`
  return String(Math.trunc(num))
}

/**
 * 通知时间文案。
 *
 * created_at 格式为 `yyyy-MM-ddTHH:mm:ss`，不带时区，按本地时间解析。
 * 缺失或不可解析时返回 `--`，不返回空串——列表每行都有时间列，空串会让该行
 * 看起来缺一块。
 *
 * @param {string|Date} createdAt
 * @returns {string}
 */
export const formatNotificationTime = (createdAt) => formatRelativeTime(createdAt) || '--'

/**
 * 追加下一页，按 id 去重。
 *
 * 后端用 offset 分页，两次请求之间新写入的通知会把已展示的行挤到下一页，
 * 直接 concat 会出现重复行。
 *
 * @param {Array} existing 已展示的行
 * @param {Array} incoming 新一页
 * @returns {Array} 新数组
 */
export const appendNotifications = (existing, incoming) => {
  const base = Array.isArray(existing) ? existing : []
  const next = Array.isArray(incoming) ? incoming : []
  const seen = new Set(base.map((item) => item?.id))
  const added = next.filter((item) => item && !seen.has(item.id))
  return base.concat(added)
}

/**
 * 请求失败是否为「通知中心未启用」。
 * @param {{code?: number}} error request.js 归一化后的 Error
 */
export const isNotificationsUnavailable = (error) =>
  error?.code === NOTIFICATION_ERROR_CODES.NOTIFICATIONS_UNAVAILABLE

/**
 * 错误文案。
 * @param {{code?: number, message?: string}} error
 * @returns {string|null}
 */
export const describeNotificationError = (error) => {
  if (!error) return null
  if (isNotificationsUnavailable(error)) return NOTIFICATIONS_UNAVAILABLE_TEXT
  if (error.code === NOTIFICATION_ERROR_CODES.NOTIFICATION_NOT_FOUND) return '该通知不存在或已被移除'
  return error.message || 'LOAD_FAILED'
}
