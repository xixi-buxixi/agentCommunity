<script setup>
/**
 * 通知中心列表面板。
 *
 * 由 NotificationBell 以下拉方式渲染：移动端固定在头部下方铺满一行，
 * sm 以上锚定在铃铛右下。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useNotificationStore } from '@/stores/notification'
import {
  NOTIFICATIONS_UNAVAILABLE_TEXT,
  formatNotificationTime,
  resolveNotificationRoute
} from '@/utils/notification'

const emit = defineEmits(['close'])

const router = useRouter()
const store = useNotificationStore()

const items = computed(() => store.items)

const toggleUnreadOnly = () => {
  store.setUnreadOnly(!store.unreadOnly)
}

/**
 * 点击条目：标记已读并跳转。
 *
 * 标记已读不等待结果——通知的价值在于把用户送到目标页面，为一次记录状态的
 * 往返推迟跳转没有必要；失败时该行仍是未读，下次打开面板可以再点。
 * link_type 为空或 link_id 非法时只标记已读，面板保持打开。
 */
const openItem = (item) => {
  const target = resolveNotificationRoute(item)
  store.markRead(item.id)
  if (!target) return
  emit('close')
  router.push(target)
}

const markAll = async () => {
  await store.markAllRead()
}
</script>

<template>
  <section class="border border-pulse-border bg-pulse-card shadow-lg">
    <!-- 面板头部 -->
    <div class="border-b border-pulse-border px-3 py-2 flex items-center justify-between gap-2">
      <div class="min-w-0">
        <div class="text-pulse-accent text-[10px] sm:text-xs font-bold tracking-wider">NOTIFICATIONS</div>
        <div class="text-pulse-muted text-[10px] truncate">
          UNREAD: <span class="text-pulse-warning">{{ store.unreadCount }}</span>
        </div>
      </div>
      <div class="flex items-center gap-1 shrink-0">
        <button
          @click="toggleUnreadOnly"
          class="border px-2 py-1 text-[10px] transition min-h-[32px]"
          :class="store.unreadOnly
            ? 'border-pulse-accent text-pulse-accent bg-pulse-accent/10'
            : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
        >
          [仅未读]
        </button>
        <button
          @click="markAll"
          :disabled="store.unavailable || store.unreadCount === 0"
          class="border border-pulse-border px-2 py-1 text-[10px] text-pulse-muted hover:text-pulse-white hover:border-pulse-accent transition min-h-[32px] disabled:opacity-40 disabled:hover:text-pulse-muted disabled:hover:border-pulse-border"
        >
          [全部已读]
        </button>
        <button
          @click="emit('close')"
          class="border border-pulse-border px-2 py-1 text-[10px] text-pulse-muted hover:text-pulse-dead transition min-h-[32px]"
        >
          [X]
        </button>
      </div>
    </div>

    <!-- 缺表：后端四个读接口返回 90001 -->
    <div v-if="store.unavailable" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
      {{ NOTIFICATIONS_UNAVAILABLE_TEXT }}
    </div>

    <template v-else>
      <div v-if="store.error" class="p-3 bg-pulse-dead/5 border-b border-pulse-border">
        <div class="text-pulse-dead text-[10px] sm:text-xs break-words">&gt; {{ store.error }}</div>
        <button @click="store.fetchList()" class="text-pulse-dead text-[10px] mt-2 hover:underline">[RETRY]</button>
      </div>

      <div v-if="store.loading" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
        LOADING_NOTIFICATIONS...
      </div>

      <div v-else-if="items.length === 0" class="p-4 text-center text-pulse-muted text-[10px] sm:text-xs">
        {{ store.unreadOnly ? 'NO_UNREAD_NOTIFICATIONS' : 'NO_NOTIFICATIONS' }}
      </div>

      <div v-else class="max-h-[60vh] sm:max-h-96 overflow-y-auto divide-y divide-pulse-border">
        <button
          v-for="item in items"
          :key="item.id"
          @click="openItem(item)"
          class="w-full text-left px-3 py-2 hover:bg-pulse-surface/50 transition flex gap-2"
        >
          <!-- 未读点 -->
          <span
            class="mt-1.5 w-1.5 h-1.5 shrink-0 rounded-full"
            :class="item.is_read ? 'bg-transparent' : 'bg-pulse-warning'"
          ></span>
          <span class="min-w-0 flex-1">
            <span class="flex items-baseline justify-between gap-2">
              <span
                class="text-[11px] sm:text-xs truncate"
                :class="item.is_read ? 'text-pulse-muted' : 'text-pulse-white font-bold'"
              >{{ item.title || item.type_text || item.type }}</span>
              <span class="text-pulse-muted text-[10px] shrink-0">{{ formatNotificationTime(item.created_at) }}</span>
            </span>
            <span v-if="item.body" class="block text-pulse-muted text-[10px] sm:text-xs leading-relaxed mt-1 line-clamp-2">
              {{ item.body }}
            </span>
            <span class="block text-pulse-muted text-[10px] mt-1 truncate">
              <span v-if="item.actor_name" :class="item.actor_type === 'AGENT' ? 'text-pulse-agent' : 'text-pulse-human'">
                {{ item.actor_name }}
              </span>
              <span v-if="item.actor_name && item.type_text"> · </span>
              <span v-if="item.type_text">{{ item.type_text }}</span>
            </span>
          </span>
        </button>
      </div>

      <div v-if="store.hasMore && !store.loading" class="border-t border-pulse-border p-2">
        <button
          @click="store.loadMore()"
          :disabled="store.loadingMore"
          class="w-full border border-pulse-border text-pulse-muted hover:text-pulse-white hover:border-pulse-accent py-2 text-[10px] sm:text-xs transition min-h-[36px] disabled:opacity-40"
        >
          {{ store.loadingMore ? 'LOADING...' : `[LOAD_MORE] ${items.length}/${store.total}` }}
        </button>
      </div>
    </template>
  </section>
</template>
