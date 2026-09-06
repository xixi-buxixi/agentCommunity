import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'

/**
 * Guards the unread badge arithmetic in the notification store.
 *
 * The badge is a local number kept in step with the server's count by hand, so every
 * path that changes it has to be able to justify the change. `markRead` could not:
 * it decremented on any successful request, including one for an id that is not in
 * the list at all - where nothing is known about whether that row was unread. The
 * badge then drifted below the server's count and stayed there until the next poll.
 *
 * The store imports 'pinia' and several '@/…' aliases, none of which resolve under
 * plain `node --test`, so the source is loaded with those imports stubbed - the same
 * approach as auth.test.mjs.
 */

let markReadCalls = []
let markReadShouldThrow = null

globalThis.__notificationStubs = {
  defineStore: (_name, options) => options,
  getNotifications: async () => ({ data: { list: [], total: 0 } }),
  getUnreadCount: async () => ({ data: { count: 0 } }),
  markNotificationRead: async (id) => {
    markReadCalls.push(id)
    if (markReadShouldThrow) throw markReadShouldThrow
  },
  markAllNotificationsRead: async () => ({ data: { count: 0 } }),
  useAuthStore: () => ({ token: 'tok', isGuest: false }),
  unwrapPage: (data) => ({ items: data?.list ?? [], total: data?.total ?? 0 }),
  NOTIFICATION_PAGE_SIZE: 20,
  appendNotifications: (existing, incoming) => existing.concat(incoming),
  describeNotificationError: (err) => err?.message ?? null,
  formatUnreadBadge: (count) => (count > 0 ? String(count) : ''),
  isNotificationsUnavailable: (err) => err?.code === 90001
}

const source = readFileSync(new URL('./notification.js', import.meta.url), 'utf8')
  .replace("import { defineStore } from 'pinia'", 'const { defineStore } = globalThis.__notificationStubs')
  .replace(
    /import \{[\s\S]*?\} from '@\/api\/notification'/,
    'const { getNotifications, getUnreadCount, markNotificationRead, markAllNotificationsRead } = globalThis.__notificationStubs'
  )
  .replace(
    "import { useAuthStore } from '@/stores/auth'",
    'const { useAuthStore } = globalThis.__notificationStubs'
  )
  .replace(
    "import { unwrapPage } from '@/utils/page'",
    'const { unwrapPage } = globalThis.__notificationStubs'
  )
  .replace(
    /import \{[\s\S]*?\} from '@\/utils\/notification'/,
    'const { NOTIFICATION_PAGE_SIZE, appendNotifications, describeNotificationError, formatUnreadBadge, isNotificationsUnavailable } = globalThis.__notificationStubs'
  )

const { useNotificationStore } = await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)

const makeStore = () => {
  const options = useNotificationStore
  const instance = options.state()
  Object.entries(options.actions).forEach(([name, fn]) => {
    instance[name] = fn.bind(instance)
  })
  return instance
}

const reset = () => {
  markReadCalls = []
  markReadShouldThrow = null
}

test('markRead decrements the badge for an unread row in the list', async () => {
  reset()
  const store = makeStore()
  store.items = [{ id: 1, is_read: false }, { id: 2, is_read: false }]
  store.unreadCount = 2

  assert.equal(await store.markRead(1), true)

  assert.deepEqual(markReadCalls, [1])
  assert.equal(store.items[0].is_read, true)
  assert.equal(store.unreadCount, 1)
})

test('markRead on an already-read row sends nothing and leaves the badge alone', async () => {
  reset()
  const store = makeStore()
  store.items = [{ id: 1, is_read: true }]
  store.unreadCount = 3

  assert.equal(await store.markRead(1), true)

  assert.deepEqual(markReadCalls, [])
  assert.equal(store.unreadCount, 3)
})

test('markRead does not decrement the badge for an id that is not in the list', async () => {
  reset()
  const store = makeStore()
  store.items = [{ id: 1, is_read: false }]
  store.unreadCount = 1

  assert.equal(await store.markRead(99), true)

  // The request still goes out - the row may exist server-side - but nothing here
  // knows whether it was unread, so the badge must not move.
  assert.deepEqual(markReadCalls, [99])
  assert.equal(store.unreadCount, 1)
})

test('a failed markRead leaves both the row and the badge untouched', async () => {
  reset()
  markReadShouldThrow = Object.assign(new Error('NETWORK'), { code: 500 })
  const store = makeStore()
  store.items = [{ id: 1, is_read: false }]
  store.unreadCount = 1

  assert.equal(await store.markRead(1), false)

  assert.equal(store.items[0].is_read, false)
  assert.equal(store.unreadCount, 1)
})
