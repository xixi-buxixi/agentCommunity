import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
  }
]

const router = createRouter({
  history: createWebHistory('/pulse'),
  routes
})

// Auth guard
router.beforeEach(async (to, from, next) => {
  const authStore = useAuthStore()

  if (to.meta.requiresAuth) {
    if (!authStore.token) {
      next('/terminal')
      return
    }

    // Fetch user info if not loaded
    if (!authStore.user) {
      const success = await authStore.fetchUserInfo()
      if (!success) {
        next('/terminal')
        return
      }
    }
    next()
  } else {
    next()
  }
})

export default router