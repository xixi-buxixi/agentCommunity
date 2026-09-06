import { defineStore } from 'pinia'
import {
  getNotifications,
  getUnreadCount,
  markNotificationRead,
  markAllNotificationsRead
} from '@/api/notification'
import { useAuthStore } from '@/stores/auth'
import { unwrapPage } from '@/utils/page'
import {
  NOTIFICATION_PAGE_SIZE,
  appendNotifications,
  describeNotificationError,
  formatUnreadBadge,
  isNotificationsUnavailable
} from '@/utils/notification'

/** 未读数轮询间隔。 */
export const POLL_INTERVAL_MS = 60000

/**
 * 定时器与监听器放在模块作用域而不是 state。
 *
 * 它们不参与渲染，放进 state 会被 Pinia 代理成响应式对象，且在 devtools 里
 * 显示为一个无意义的数字。subscribers 是挂载中的铃铛数量：路由切换时新页面的
 * 铃铛先挂载、旧页面后卸载，用计数而不是布尔值才不会在切换瞬间停掉轮询。
 */
let pollTimer = null
let visibilityHandler = null
let subscribers = 0

const clearPollTimer = () => {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

export const useNotificationStore = defineStore('notification', {
  state: () => ({
    items: [],
    total: 0,
    page: 1,
    size: NOTIFICATION_PAGE_SIZE,
    unreadCount: 0,
    /** 「仅未读」开关，对应查询参数 unread_only。 */
    unreadOnly: false,
    loading: false,
    loadingMore: false,
    error: null,
    /** 后端返回 90001（缺表）后置位，用于展示未启用提示而不是空列表。 */
    unavailable: false
  }),

  getters: {
    /** 角标文案，0 时为空串。 */
    badge: (state) => formatUnreadBadge(state.unreadCount),
    hasMore: (state) => state.items.length < state.total,
    isEmpty: (state) => !state.loading && state.items.length === 0
  },

  actions: {
    /**
     * 通知接口需要登录，游客不调用。
     *
     * 判定用 token 而不是 isAuthenticated：后者还要求 user 已加载，而路由守卫
     * 只在 user 为空时才拉取，铃铛挂载时机可能早于那次请求完成。
     */
    canQuery() {
      const authStore = useAuthStore()
      return !!authStore.token && !authStore.isGuest
    },

    /**
     * 记录失败并在缺表时停止轮询。
     *
     * 缺表是部署形态而不是瞬时故障，继续每 60 秒请求一次只会重复产生 409。
     * 用户再次打开面板或手动刷新时仍会重试。
     */
    handleFailure(err) {
      this.error = describeNotificationError(err)
      if (isNotificationsUnavailable(err)) {
        this.unavailable = true
        clearPollTimer()
      }
      return false
    },

    async fetchUnreadCount() {
      if (!this.canQuery()) {
        this.unreadCount = 0
        return false
      }
      try {
        const { data } = await getUnreadCount()
        this.unreadCount = Number(data?.count) || 0
        this.unavailable = false
        return true
      } catch (err) {
        return this.handleFailure(err)
      }
    },

    /**
     * 拉取第一页，覆盖当前列表。
     */
    async fetchList() {
      if (!this.canQuery()) return false
      this.loading = true
      this.error = null
      try {
        const { data } = await getNotifications({
          unread_only: this.unreadOnly,
          page: 1,
          size: this.size
        })
        const { items, total } = unwrapPage(data)
        this.items = items
        this.total = total
        this.page = 1
        this.unavailable = false
        return true
      } catch (err) {
        this.items = []
        this.total = 0
        return this.handleFailure(err)
      } finally {
        this.loading = false
      }
    },

    async loadMore() {
      if (!this.canQuery() || this.loadingMore || !this.hasMore) return false
      this.loadingMore = true
      this.error = null
      const nextPage = this.page + 1
      try {
        const { data } = await getNotifications({
          unread_only: this.unreadOnly,
          page: nextPage,
          size: this.size
        })
        const { items, total } = unwrapPage(data)
        this.items = appendNotifications(this.items, items)
        this.total = total
        this.page = nextPage
        return true
      } catch (err) {
        return this.handleFailure(err)
      } finally {
        this.loadingMore = false
      }
    },

    /** 切换「仅未读」并重新拉取第一页。 */
    async setUnreadOnly(value) {
      const next = !!value
      if (this.unreadOnly === next) return true
      this.unreadOnly = next
      return this.fetchList()
    },

    /** 打开面板时调用：拉一次列表，同时刷新角标。 */
    async openPanel() {
      const listed = await this.fetchList()
      if (listed) await this.fetchGuardedUnreadCount()
      return listed
    },

    /**
     * 列表成功后再刷新角标，缺表时不重复请求。
     */
    async fetchGuardedUnreadCount() {
      if (this.unavailable) return false
      return this.fetchUnreadCount()
    },

    /**
     * 单条标记已读。
     *
     * 已读的行直接返回，不发请求——后端把重复标记视为成功，但没有必要为一次
     * 无变化的点击往返一趟。
     *
     * 未读数只在目标行存在于当前列表、且原本未读时才减 1。不在列表中的 id
     * 无从判断它原本是否未读，减 1 会让角标与后端计数偏离。
     */
    async markRead(id) {
      if (!this.canQuery()) return false
      const item = this.items.find((n) => n.id === id)
      if (item && item.is_read) return true
      try {
        await markNotificationRead(id)
      } catch (err) {
        return this.handleFailure(err)
      }
      if (item) {
        item.is_read = true
        this.unreadCount = Math.max(0, this.unreadCount - 1)
      }
      return true
    },

    /**
     * 全部标记已读。
     *
     * 后端返回 { count: 0 }，直接赋值而不是做减法。「仅未读」视图下重新拉取
     * 列表，否则会留下一页全部已读、却处于未读筛选下的行。
     */
    async markAllRead() {
      if (!this.canQuery()) return false
      try {
        await markAllNotificationsRead()
      } catch (err) {
        return this.handleFailure(err)
      }
      this.unreadCount = 0
      this.items.forEach((item) => { item.is_read = true })
      if (this.unreadOnly) await this.fetchList()
      return true
    },

    /**
     * 开始轮询未读数。
     *
     * 页面隐藏时暂停，重新可见时立即补一次再恢复计时——只恢复计时会让用户在
     * 切回标签页后最多等 60 秒才看到新角标。
     */
    startPolling() {
      subscribers += 1
      if (!this.canQuery()) return
      if (pollTimer !== null || visibilityHandler !== null) return

      const tick = () => {
        if (document.visibilityState === 'hidden') return
        this.fetchUnreadCount()
      }

      visibilityHandler = () => {
        if (document.visibilityState === 'hidden') {
          clearPollTimer()
          return
        }
        if (this.unavailable) return
        this.fetchUnreadCount()
        if (pollTimer === null) pollTimer = setInterval(tick, POLL_INTERVAL_MS)
      }
      document.addEventListener('visibilitychange', visibilityHandler)

      this.fetchUnreadCount()
      if (document.visibilityState !== 'hidden') {
        pollTimer = setInterval(tick, POLL_INTERVAL_MS)
      }
    },

    /** 最后一个订阅者卸载时才真正停掉。 */
    stopPolling() {
      subscribers = Math.max(0, subscribers - 1)
      if (subscribers > 0) return
      clearPollTimer()
      if (visibilityHandler) {
        document.removeEventListener('visibilitychange', visibilityHandler)
        visibilityHandler = null
      }
    },

    reset() {
      this.items = []
      this.total = 0
      this.page = 1
      this.unreadCount = 0
      this.unreadOnly = false
      this.error = null
      this.unavailable = false
    }
  }
})
