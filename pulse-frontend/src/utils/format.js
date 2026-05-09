/**
 * 时间格式化工具
 */

/**
 * 格式化相对时间（X分钟前、X小时前）
 * @param {string|Date} dateString - 时间字符串或Date对象
 * @returns {string} 格式化后的相对时间
 */
export function formatRelativeTime(dateString) {
  if (!dateString) return ''
  const date = new Date(dateString)
  if (isNaN(date.getTime())) return ''

  const now = new Date()
  const diff = now - date
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  if (hours < 24) return `${hours}小时前`
  if (days < 7) return `${days}天前`

  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

/**
 * 格式化时间（HH:MM:SS）
 * @param {string|Date} timeStr - 时间字符串或Date对象
 * @returns {string} 格式化后的时间
 */
export function formatTimeOnly(timeStr) {
  if (!timeStr) return '--:--:--'
  const date = new Date(timeStr)
  if (isNaN(date.getTime())) return '--:--:--'
  return date.toLocaleTimeString('en-US', { hour12: false })
}

/**
 * 格式化短时间（HH:MM）
 * @param {string|Date} timestamp - 时间字符串或Date对象
 * @returns {string} 格式化后的时间
 */
export function formatShortTime(timestamp) {
  if (!timestamp) return '--:--'
  const date = new Date(timestamp)
  if (isNaN(date.getTime())) return '--:--'
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

/**
 * 格式化日期时间（MM/DD HH:MM）
 * @param {string|Date} timestamp - 时间字符串或Date对象
 * @returns {string} 格式化后的日期时间
 */
export function formatDateTime(timestamp) {
  if (!timestamp) return '--:--'
  const date = new Date(timestamp)
  if (isNaN(date.getTime())) return '--:--'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

/**
 * 格式化完整日期时间
 * @param {string|Date} timestamp - 时间字符串或Date对象
 * @returns {string} 格式化后的完整日期时间
 */
export function formatFullDateTime(timestamp) {
  if (!timestamp) return '--'
  const date = new Date(timestamp)
  if (isNaN(date.getTime())) return '--'
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

/**
 * Token数量格式化（带单位）
 * @param {number} tokens - Token数量
 * @returns {string} 格式化后的Token字符串
 */
export function formatTokens(tokens) {
  if (!tokens || tokens === 0) return '0'
  if (tokens >= 1000000) {
    return `${(tokens / 1000000).toFixed(1)}M`
  }
  if (tokens >= 1000) {
    return `${(tokens / 1000).toFixed(1)}K`
  }
  return tokens.toString()
}

/**
 * 数字格式化（带单位）
 * @param {number} num - 数字
 * @returns {string} 格式化后的字符串
 */
export function formatNumber(num) {
  if (!num) return '0'
  if (num >= 1000000) {
    return `${(num / 1000000).toFixed(1)}M`
  }
  if (num >= 1000) {
    return `${(num / 1000).toFixed(1)}K`
  }
  return num.toString()
}