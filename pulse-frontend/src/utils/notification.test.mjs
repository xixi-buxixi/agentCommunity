import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  NOTIFICATIONS_UNAVAILABLE_TEXT,
  NOTIFICATION_ERROR_CODES,
  UNREAD_BADGE_MAX,
  appendNotifications,
  describeNotificationError,
  formatNotificationTime,
  formatUnreadBadge,
  isNotificationsUnavailable,
  resolveNotificationRoute
} from './notification.js'

test('POST 与 AGENT 按 link_id 拼路由', () => {
  assert.equal(resolveNotificationRoute({ link_type: 'POST', link_id: 88 }), '/post/88')
  assert.equal(resolveNotificationRoute({ link_type: 'AGENT', link_id: 7 }), '/agent/7')
})

test('BOUNTY 只跳板块页，link_id 不参与', () => {
  // 悬赏详情是 BountyGuild 页内的视图状态，没有独立路由
  assert.equal(resolveNotificationRoute({ link_type: 'BOUNTY', link_id: 12 }), '/bounty')
  assert.equal(resolveNotificationRoute({ link_type: 'BOUNTY', link_id: null }), '/bounty')
})

test('link_id 为字符串数字时仍可解析', () => {
  assert.equal(resolveNotificationRoute({ link_type: 'POST', link_id: '88' }), '/post/88')
})

test('link_type 大小写不敏感', () => {
  assert.equal(resolveNotificationRoute({ link_type: 'post', link_id: 3 }), '/post/3')
})

test('link 缺失或非法时返回 null，调用方只标记已读', () => {
  const cases = [
    null,
    undefined,
    {},
    { link_type: 'POST' },
    { link_type: 'POST', link_id: null },
    { link_type: 'POST', link_id: 0 },
    { link_type: 'POST', link_id: -1 },
    { link_type: 'POST', link_id: 1.5 },
    { link_type: 'POST', link_id: 'abc' },
    { link_type: 'UNKNOWN', link_id: 5 },
    { link_type: 12, link_id: 5 }
  ]
  for (const input of cases) {
    assert.equal(resolveNotificationRoute(input), null, `应为 null: ${JSON.stringify(input)}`)
  }
})

test('未读数为 0 或非法值时角标为空串', () => {
  for (const input of [0, -3, null, undefined, NaN, 'lots', {}]) {
    assert.equal(formatUnreadBadge(input), '')
  }
})

test('未读数在上限内按原值显示', () => {
  assert.equal(formatUnreadBadge(1), '1')
  assert.equal(formatUnreadBadge(42), '42')
  assert.equal(formatUnreadBadge(UNREAD_BADGE_MAX), '99')
})

test('超过上限显示 99+', () => {
  assert.equal(formatUnreadBadge(UNREAD_BADGE_MAX + 1), '99+')
  assert.equal(formatUnreadBadge(3000), '99+')
})

test('相对时间按分钟、小时、天分档', () => {
  const now = Date.now()
  assert.equal(formatNotificationTime(new Date(now - 30 * 1000)), '刚刚')
  assert.equal(formatNotificationTime(new Date(now - 5 * 60 * 1000)), '5分钟前')
  assert.equal(formatNotificationTime(new Date(now - 3 * 3600 * 1000)), '3小时前')
  assert.equal(formatNotificationTime(new Date(now - 2 * 86400 * 1000)), '2天前')
})

test('时间缺失或不可解析时显示占位符而不是空串', () => {
  for (const input of [null, undefined, '', 'not-a-date']) {
    assert.equal(formatNotificationTime(input), '--')
  }
})

test('不带时区的 created_at 按本地时间解析', () => {
  // 后端格式为 yyyy-MM-ddTHH:mm:ss
  const local = new Date()
  local.setMinutes(local.getMinutes() - 10)
  const pad = (n) => String(n).padStart(2, '0')
  const text = `${local.getFullYear()}-${pad(local.getMonth() + 1)}-${pad(local.getDate())}`
    + `T${pad(local.getHours())}:${pad(local.getMinutes())}:${pad(local.getSeconds())}`
  assert.equal(formatNotificationTime(text), '10分钟前')
})

test('追加下一页时按 id 去重', () => {
  // offset 分页下，两次请求之间新写入的通知会把已展示的行挤到下一页
  const merged = appendNotifications(
    [{ id: 3 }, { id: 2 }],
    [{ id: 2 }, { id: 1 }]
  )
  assert.deepEqual(merged.map((n) => n.id), [3, 2, 1])
})

test('追加不修改原数组，且能容忍非数组入参', () => {
  const existing = [{ id: 1 }]
  const merged = appendNotifications(existing, [{ id: 2 }])
  assert.equal(existing.length, 1)
  assert.equal(merged.length, 2)
  assert.deepEqual(appendNotifications(null, null), [])
  assert.deepEqual(appendNotifications(undefined, [{ id: 9 }]).map((n) => n.id), [9])
})

test('90001 显示通知中心未启用', () => {
  const err = Object.assign(new Error('通知中心尚未启用'), {
    code: NOTIFICATION_ERROR_CODES.NOTIFICATIONS_UNAVAILABLE,
    status: 409
  })
  assert.equal(isNotificationsUnavailable(err), true)
  assert.equal(describeNotificationError(err), NOTIFICATIONS_UNAVAILABLE_TEXT)
})

test('90002 与其他失败各自显示自己的文案', () => {
  const notFound = Object.assign(new Error('通知不存在'), {
    code: NOTIFICATION_ERROR_CODES.NOTIFICATION_NOT_FOUND,
    status: 404
  })
  assert.equal(isNotificationsUnavailable(notFound), false)
  assert.equal(describeNotificationError(notFound), '该通知不存在或已被移除')

  const network = Object.assign(new Error('CONNECTION_ERROR'), { code: null, status: undefined })
  assert.equal(describeNotificationError(network), 'CONNECTION_ERROR')
  assert.equal(describeNotificationError(null), null)
})
