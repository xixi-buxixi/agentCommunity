<script setup>
/**
 * 顶部导航的通知铃铛。
 *
 * 各页面头部各自渲染一个（Square / Lab / BountyGuild 没有共享头部组件）。
 * 轮询由 store 用订阅计数管理，路由切换时新旧铃铛交替不会中断轮询。
 * 游客与未登录状态不渲染，也不发请求。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotificationStore } from '@/stores/notification'
import NotificationPanel from '@/components/NotificationPanel.vue'

const authStore = useAuthStore()
const store = useNotificationStore()

const open = ref(false)
const rootRef = ref(null)
const polling = ref(false)

const visible = computed(() => !!authStore.token && !authStore.isGuest)
const badge = computed(() => store.badge)

const close = () => { open.value = false }

const toggle = () => {
  open.value = !open.value
  if (open.value) store.openPanel()
}

/**
 * 面板外点击关闭。
 *
 * 监听在捕获阶段：条目自身的点击会触发路由跳转并把面板从 DOM 里移除，
 * 冒泡阶段再判断 `contains` 时该节点已经脱离文档，结果恒为 false。
 */
const onDocumentClick = (event) => {
  if (!open.value) return
  if (rootRef.value && !rootRef.value.contains(event.target)) close()
}

const onKeydown = (event) => {
  if (event.key === 'Escape') close()
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick, true)
  document.addEventListener('keydown', onKeydown)
  if (!visible.value) return
  polling.value = true
  store.startPolling()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick, true)
  document.removeEventListener('keydown', onKeydown)
  if (polling.value) store.stopPolling()
})
</script>

<template>
  <div v-if="visible" ref="rootRef" class="relative shrink-0">
    <button
      @click="toggle"
      class="relative border px-2 py-1 text-[10px] sm:text-xs transition min-h-[32px] min-w-[32px] flex items-center justify-center"
      :class="open
        ? 'border-pulse-accent text-pulse-accent bg-pulse-accent/10'
        : 'border-pulse-border text-pulse-muted hover:text-pulse-white hover:border-pulse-accent'"
      :title="badge ? `未读通知 ${badge}` : '通知中心'"
      aria-label="通知中心"
    >
      <span aria-hidden="true">&#9788;</span>
      <span
        v-if="badge"
        class="absolute -top-1.5 -right-1.5 bg-pulse-warning text-pulse-bg text-[9px] leading-none px-1 py-0.5 min-w-[16px] text-center font-bold"
      >{{ badge }}</span>
    </button>

    <!--
      移动端固定在头部下方铺满一行：面板锚在铃铛右侧时，窄屏下左边缘会被推出
      视口。sm 以上恢复为下拉。
    -->
    <div
      v-if="open"
      class="fixed left-2 right-2 top-14 z-50 sm:absolute sm:left-auto sm:right-0 sm:top-full sm:mt-2 sm:w-96"
    >
      <NotificationPanel @close="close" />
    </div>
  </div>
</template>
