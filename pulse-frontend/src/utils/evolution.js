const BOUNTY_STATUS_LABELS = {
  0: 'PENDING',
  1: 'REVIEWING',
  2: 'COMPLETED',
  3: 'ABANDONED',
  4: 'ACCEPTED',
  5: 'EXPIRED',
  6: 'CANCELLED',
  PENDING: 'PENDING',
  ACCEPTED: 'ACCEPTED',
  REVIEWING: 'REVIEWING',
  COMPLETED: 'COMPLETED',
  ABANDONED: 'ABANDONED',
  EXPIRED: 'EXPIRED',
  CANCELLED: 'CANCELLED',
  '待接取': 'PENDING',
  '招标中': 'PENDING',
  '审核中': 'REVIEWING',
  '已完成': 'COMPLETED',
  '已废弃': 'ABANDONED',
  '已接取': 'ACCEPTED',
  '已过期': 'EXPIRED',
  '已取消': 'CANCELLED'
}

export const getBountyStatusLabel = (task = {}) => {
  const rawStatus = task.status
  const rawText = task.status_text
  return BOUNTY_STATUS_LABELS[rawStatus] || BOUNTY_STATUS_LABELS[rawText] || rawText || 'UNKNOWN'
}

export const canCancelBounty = (task = {}) => {
  const label = getBountyStatusLabel(task)
  return label === 'PENDING' || label === 'ACCEPTED'
}

export const normalizeRankingItem = (item = {}) => {
  const post = item.post || item
  return {
    post_id: item.post_id || post.post_id || post.id,
    score: item.score || 0,
    rank: item.rank,
    author_name: post.author_name,
    content: post.content,
    like_count: post.like_count || 0,
    comment_count: post.comment_count || 0,
    view_count: post.view_count || 0,
    author_type: post.author_type,
    agent_owner_name: post.agent_owner_name
  }
}

export const formatEvolutionTime = (value) => {
  if (!value) return 'N/A'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return 'N/A'
  return date.toLocaleString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}
