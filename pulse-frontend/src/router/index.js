import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { appNotice, setAppNotice } from '@/utils/appStatus'

const routes = [
  {
    path: '/',
    redirect: '/terminal'
  },
  {
    path: '/terminal',
    name: 'Terminal',
    component: () => import('@/views/Terminal.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/lab',
    name: 'Lab',
    component: () => import('@/views/Lab.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/square',
    name: 'Square',
    component: () => import('@/views/Square.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/bounty',
    name: 'BountyGuild',
    component: () => import('@/views/BountyGuild.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/monitor/:id',
    name: 'Monitor',
    component: () => import('@/views/Monitor.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/post/:id',
    name: 'PostDetail',
    component: () => import('@/views/PostDetail.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/hot-news/:id',
    name: 'DailyHotDetail',
    component: () => import('@/views/DailyHotDetail.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/workbench',
    name: 'Workbench',
    component: () => import('@/views/Workbench.vue'),
    meta: { requiresAuth: true }
  }
]

const router = createRouter({
  history: createWebHistory('/pulse'),
  routes
})

// Auth guard
router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()

  // Guest mode: allow read-only access to Square, Workbench, Bounty
  if (authStore.isGuest) {
    const guestAllowed = ['/square', '/workbench', '/bounty', '/post', '/hot-news']
    const isGuestAllowed = guestAllowed.some(p => to.path.startsWith(p))
    if (isGuestAllowed || to.path === '/terminal') {
      next()
      return
    }
    next('/square')
    return
  }

  if (to.meta.requiresAuth) {
    // Carry the destination so Terminal can send them back after login instead of
    // dumping everyone on /lab.
    const toTerminal = { path: '/terminal', query: { redirect: to.fullPath } }

    if (!authStore.token) {
      // Guard against redirecting /terminal to itself: vue-router treats that as
      // a duplicate navigation and aborts it, which is what made the bottom-nav
      // links look completely dead.
      next(from.path === '/terminal' ? false : toTerminal)
      return
    }

    // Fetch user info if not loaded
    if (!authStore.user) {
      const success = await authStore.fetchUserInfo()
      if (!success) {
        next(from.path === '/terminal' ? false : toTerminal)
        return
      }
    }
    next()
  } else {
    next()
  }
})

/**
 * Recover from a stale build.
 *
 * Deploys replace the hashed chunks, so a browser holding an old index.html (or a
 * tab that has been open across a deploy) asks for asset names that no longer
 * exist. The lazy `import()` rejects, vue-router reports it here, and without this
 * handler the click produced no navigation, no error, no feedback whatsoever.
 *
 * Reload once *per destination*, to that destination — the fresh index.html points
 * at chunks that do exist. The retry is tracked per path rather than on a plain
 * cooldown timer: a time-only guard let a visitor who clicks the same dead link
 * every 20 seconds reload forever, never seeing why. The second failure for a
 * given path means the asset is genuinely absent server-side, so we stop and say
 * so. The stamp expires after RETRY_TTL_MS so a later, real redeploy can still
 * self-heal.
 */
// "Couldn't resolve component" belongs here too: when the preload error has
// already been neutralised with preventDefault(), the missing chunk resurfaces as
// vue-router failing to resolve the lazy component. Same cause, and leaving it out
// meant the vague NAVIGATION_FAILED banner replaced the accurate BUILD_MISMATCH
// one on a direct page load.
const CHUNK_ERROR_PATTERN = /Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed|Unable to preload CSS|Couldn't resolve component/i
const RETRY_KEY = 'pulse_chunk_retry'
const RETRY_TTL_MS = 10 * 60 * 1000

const readRetries = () => {
  try {
    const parsed = JSON.parse(sessionStorage.getItem(RETRY_KEY) || '{}')
    const now = Date.now()
    // Drop expired entries so a redeploy hours later is not treated as a repeat.
    return Object.fromEntries(
      Object.entries(parsed).filter(([, at]) => typeof at === 'number' && now - at < RETRY_TTL_MS)
    )
  } catch {
    return {}
  }
}

const markRetried = (key) => {
  try {
    sessionStorage.setItem(RETRY_KEY, JSON.stringify({ ...readRetries(), [key]: Date.now() }))
    return true
  } catch {
    // Private-mode sessionStorage can throw. Without a durable stamp we cannot
    // guarantee the loop guard, so report failure and let the caller show the
    // banner rather than risk reloading indefinitely.
    return false
  }
}

/**
 * `vite:preloadError` fires before vue-router sees anything, and the event
 * carries no destination. Tracking the in-flight navigation here means the
 * recovery reload lands on the page the visitor actually clicked instead of
 * silently dumping them back on the current one.
 */
let pendingFullPath = null
router.beforeEach((to, from, next) => {
  pendingFullPath = to.fullPath
  next()
})
router.afterEach((to) => {
  // Only the navigation that actually completed may clear the marker. Clearing it
  // unconditionally meant a fast second navigation finishing first wiped the
  // target of a slower one still resolving its chunk, so that one's recovery
  // reloaded the wrong page and burned its retry stamp doing it.
  if (pendingFullPath === to.fullPath) pendingFullPath = null
})

export const recoverFromStaleBuild = (targetPath) => {
  const destination = targetPath || pendingFullPath || null

  // Key on the path, not the full path: a chunk is shared by every query string
  // that resolves to the same route, so `/terminal?redirect=/lab` and
  // `/terminal?redirect=/bounty` must count as one retry, not two.
  const key = destination ? destination.split('?')[0].split('#')[0] : window.location.pathname

  const showBanner = () => setAppNotice({
    title: 'BUILD_MISMATCH',
    message: '页面资源加载失败，自动刷新后仍未恢复。服务端可能缺少该版本的静态资源，请稍后重试或联系维护者。'
  })

  if (readRetries()[key]) {
    showBanner()
    return
  }
  if (!markRetried(key)) {
    showBanner()
    return
  }

  const base = import.meta.env.BASE_URL.replace(/\/$/, '')
  window.location.assign(destination ? `${base}${destination}` : window.location.href)
}

export const isStaleChunkError = (error) => CHUNK_ERROR_PATTERN.test(error?.message || '')

router.onError((error, to) => {
  if (isStaleChunkError(error)) {
    recoverFromStaleBuild(to?.fullPath)
    return
  }
  // Never downgrade an already-diagnosed build mismatch to the generic message:
  // one missing chunk can surface as several errors, and the last one to arrive
  // was winning the banner.
  if (appNotice.value?.title === 'BUILD_MISMATCH') return
  setAppNotice({
    title: 'NAVIGATION_FAILED',
    message: error?.message || '页面加载失败，请重试。'
  })
})

// Vite raises this for a failed module *preload*, which happens before the router
// ever sees an error. Same stale-build cause, same recovery — and unlike
// router.onError there is no need to pattern-match, the event only fires for a
// preload that could not be fetched.
window.addEventListener('vite:preloadError', (event) => {
  event.preventDefault?.()
  recoverFromStaleBuild()
})

export default router
