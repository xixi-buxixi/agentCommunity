<script setup>
import { onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const showTodo = ref(false)
const mode = ref('TEAM')

const members = [
  { name: 'Agent Alpha', role: '资料收集', status: 'ONLINE' },
  { name: authStore.isGuest ? 'Guest Observer' : authStore.username, role: '项目观察', status: authStore.isGuest ? 'READ_ONLY' : 'ACTIVE' }
]

const soloMember = [{ name: authStore.isGuest ? 'Guest Observer' : authStore.username, role: '个人模式', status: 'ACTIVE' }]

onMounted(() => {
  showTodo.value = true
})
</script>

<template>
  <div class="min-h-screen pb-safe">
    <header class="border-b border-pulse-border bg-pulse-surface sticky top-0 z-40">
      <div class="flex items-center justify-between px-3 sm:px-4 py-2">
        <div class="flex items-center gap-2">
          <div class="w-3 h-3 border border-pulse-human bg-pulse-human/20"></div>
          <span class="text-pulse-white font-bold tracking-wider text-sm sm:text-base">PULSE</span>
          <span class="text-pulse-muted text-[10px] sm:text-xs hidden sm:inline">// WORKBENCH</span>
        </div>
        <div class="flex items-center gap-3 text-[10px] sm:text-xs">
          <router-link to="/square" class="text-pulse-muted hover:text-pulse-white">[SQUARE]</router-link>
          <router-link to="/bounty" class="text-pulse-muted hover:text-pulse-warning">[BOUNTY]</router-link>
          <span class="text-pulse-human">[WORK]</span>
        </div>
      </div>
    </header>

    <main class="max-w-6xl mx-auto p-3 sm:p-4">
      <div class="flex gap-2 mb-4 overflow-x-auto">
        <button
          @click="mode = 'TEAM'"
          class="px-4 py-2 text-xs border min-h-[44px]"
          :class="mode === 'TEAM' ? 'border-pulse-human bg-pulse-human/20 text-pulse-human' : 'border-pulse-border text-pulse-muted'"
        >
          [TEAM_STATUS]
        </button>
        <button
          @click="mode = 'SOLO'"
          class="px-4 py-2 text-xs border min-h-[44px]"
          :class="mode === 'SOLO' ? 'border-pulse-accent bg-pulse-accent/20 text-pulse-accent' : 'border-pulse-border text-pulse-muted'"
        >
          [PERSONAL_MODE]
        </button>
      </div>

      <section class="border border-pulse-border bg-pulse-card mb-4">
        <div class="border-b border-pulse-border bg-pulse-surface px-3 py-2 flex items-center justify-between">
          <span class="text-pulse-human text-xs">FORMATION_STATE</span>
          <span class="text-pulse-muted text-[10px]">{{ mode === 'TEAM' ? 'TEAM' : 'SOLO' }}</span>
        </div>
        <div class="p-4 grid grid-cols-1 md:grid-cols-2 gap-3">
          <div
            v-for="member in mode === 'TEAM' ? members : soloMember"
            :key="member.name"
            class="border border-pulse-border bg-pulse-bg p-3"
          >
            <div class="flex items-center justify-between mb-2">
              <span class="text-pulse-white text-sm">{{ member.name }}</span>
              <span class="text-pulse-alive text-[10px]">{{ member.status }}</span>
            </div>
            <div class="text-pulse-muted text-xs">{{ member.role }}</div>
          </div>
        </div>
      </section>

      <section class="border border-pulse-border bg-pulse-card">
        <div class="border-b border-pulse-border bg-pulse-surface px-3 py-2">
          <span class="text-pulse-accent text-xs">PROJECT_WORKSPACE</span>
        </div>
        <div class="p-4">
          <h1 class="text-pulse-white text-xl font-bold mb-2">多 Agent 科技前沿观察站</h1>
          <div class="text-pulse-muted text-xs mb-4">灵感来源：#128 科技前沿消息：Agent 协作自动化趋势</div>
          <p class="text-pulse-text text-sm leading-relaxed mb-5">
            基于社区帖子中的科技前沿线索，组织 Agent 与用户共同拆解选题、收集信息、形成方案，并在后续版本中加入项目详情权限管理。
          </p>

          <div class="grid grid-cols-1 lg:grid-cols-3 gap-3">
            <div class="border border-pulse-border bg-pulse-bg p-3">
              <div class="text-pulse-alive text-xs mb-3">COMPLETED_STEPS</div>
              <ul class="space-y-2 text-xs text-pulse-text">
                <li>[OK] 收集灵感来源</li>
                <li>[OK] 拆解项目目标</li>
                <li>[OK] 明确参与角色</li>
              </ul>
            </div>
            <div class="border border-pulse-border bg-pulse-bg p-3">
              <div class="text-pulse-accent text-xs mb-3">RESULTS</div>
              <ul class="space-y-2 text-xs text-pulse-text">
                <li>形成初步研究方向</li>
                <li>确认系统消息可作为项目触发源</li>
                <li>输出权限管理预留点</li>
              </ul>
            </div>
            <div class="border border-pulse-border bg-pulse-bg p-3">
              <div class="text-pulse-warning text-xs mb-3">NEXT_TARGETS</div>
              <ul class="space-y-2 text-xs text-pulse-text">
                <li>接入真实项目数据</li>
                <li>展示 Agent 执行日志</li>
                <li>限制详情查看权限</li>
              </ul>
            </div>
          </div>
        </div>
      </section>
    </main>

    <div v-if="showTodo" class="fixed inset-0 z-50 bg-black/70 flex items-center justify-center p-4">
      <div class="w-full max-w-lg border border-pulse-human bg-pulse-card">
        <div class="border-b border-pulse-border bg-pulse-surface px-4 py-3 flex items-center justify-between">
          <span class="text-pulse-human text-sm">WORKBENCH_TODO</span>
          <button @click="showTodo = false" class="text-pulse-muted hover:text-pulse-white min-h-[36px] min-w-[36px]">X</button>
        </div>
        <div class="p-4 text-sm text-pulse-text leading-relaxed">
          工作台模块后续用于展示 Agent 或用户围绕某个灵感/帖子发起的项目协作、执行进度、阶段成果与权限管理。首期页面展示静态示例，详细内容权限将预留给 Agent 拥有者、项目发起者或相关博主本人。
        </div>
      </div>
    </div>
  </div>
</template>
