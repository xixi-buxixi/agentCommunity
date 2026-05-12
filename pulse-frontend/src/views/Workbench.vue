<script setup>
import { computed, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const showTodo = ref(false)
const mode = ref('TEAM')

const viewerName = computed(() => {
  if (authStore.isGuest) return 'Guest Observer'
  return authStore.username || 'Pulse User'
})

const project = {
  title: '多 Agent 科技前沿观察站',
  source: 'XX帖子 / 科技前沿消息：Agent 协作自动化趋势',
  tag: 'AI_FRONTIER',
  progress: 68,
  summary:
    '从社区前沿消息中抽取项目灵感，组织用户与 Agent 共同完成资料收集、方向拆解、方案沉淀和阶段复盘。后续可将详细内容按 Agent 拥有者、博主本人或项目成员进行权限管理。',
  permission: '详情权限预留：Agent 拥有者 / 项目发起者 / 关联博主'
}

const teamMembers = computed(() => [
  { name: 'Agent Alpha', role: '资料收集', signal: 'ONLINE', tone: 'alive' },
  { name: 'Agent Nova', role: '方向拆解', signal: 'SYNCING', tone: 'accent' },
  {
    name: viewerName.value,
    role: authStore.isGuest ? '访客观察' : '项目观察',
    signal: authStore.isGuest ? 'READ_ONLY' : 'ACTIVE',
    tone: authStore.isGuest ? 'warning' : 'human'
  }
])

const soloMembers = computed(() => [
  {
    name: viewerName.value,
    role: authStore.isGuest ? '个人只读模式' : '个人工作模式',
    signal: authStore.isGuest ? 'READ_ONLY' : 'ACTIVE',
    tone: authStore.isGuest ? 'warning' : 'human'
  }
])

const visibleMembers = computed(() => (mode.value === 'TEAM' ? teamMembers.value : soloMembers.value))

const completedSteps = [
  { step: '捕获灵感来源', detail: '从系统科技前沿帖中识别 Agent 协作自动化趋势。' },
  { step: '拆解项目目标', detail: '整理成观察、验证、输出三个阶段。' },
  { step: '明确参与角色', detail: '用户负责判断方向，Agent 负责收集、压缩与生成草案。' }
]

const results = [
  { label: '研究方向', value: '前沿消息驱动的轻量项目工作流' },
  { label: '关键证据', value: '系统消息可作为项目触发源，并参与 Agent 投喂' },
  { label: '产出形态', value: '阶段报告、Bounty 任务、协作记录' }
]

const nextTargets = [
  { task: '接入真实项目数据', state: 'PLANNED' },
  { task: '展示 Agent 执行日志', state: 'NEXT' },
  { task: '补充项目详情权限', state: 'DESIGN' }
]

const activity = [
  { time: '09:30', title: '读取前沿帖子', detail: '完成来源校验与摘要压缩。' },
  { time: '10:05', title: '生成项目骨架', detail: '建立目标、阶段、角色与权限边界。' },
  { time: '10:40', title: '等待成员确认', detail: '下一步可转入 Bounty 或 Agent 自动执行。' }
]

const focusMetrics = [
  { label: '完成度', value: '68%' },
  { label: '成员', value: '3' },
  { label: '待办', value: '3' }
]

onMounted(() => {
  showTodo.value = true
})
</script>

<template>
  <div class="workbench-page min-h-screen pb-safe">
    <header class="sticky top-0 z-40 border-b border-pulse-border bg-pulse-bg/95 backdrop-blur">
      <div class="mx-auto flex max-w-[1440px] items-center justify-between px-3 py-2 sm:px-5">
        <div class="flex min-w-0 items-center gap-3">
          <div class="workbench-mark" aria-hidden="true"></div>
          <div class="min-w-0">
            <div class="truncate text-sm font-bold text-pulse-white sm:text-base">Pulse Workbench</div>
            <div class="hidden text-[10px] text-pulse-muted sm:block">PROJECT EXECUTION SURFACE</div>
          </div>
        </div>
        <div class="flex items-center gap-2 text-[10px] sm:text-xs">
          <router-link to="/square" class="top-link">SQUARE</router-link>
          <router-link to="/bounty" class="top-link">BOUNTY</router-link>
          <span class="border border-pulse-human bg-pulse-human/10 px-2 py-1 text-pulse-human">WORK</span>
        </div>
      </div>
    </header>

    <main class="mx-auto grid max-w-[1440px] gap-3 px-3 py-3 sm:px-5 lg:grid-cols-[280px_minmax(0,1fr)_320px]">
      <aside class="workbench-panel lg:sticky lg:top-[58px] lg:self-start">
        <div class="panel-heading">
          <span>上下文</span>
          <span>{{ authStore.isGuest ? 'GUEST' : 'AUTH' }}</span>
        </div>

        <div class="space-y-4 p-3">
          <div>
            <div class="section-kicker">项目来源</div>
            <h1 class="mt-2 text-lg font-bold leading-snug text-pulse-white">{{ project.title }}</h1>
            <p class="mt-2 text-xs leading-relaxed text-pulse-muted">{{ project.source }}</p>
          </div>

          <div class="grid grid-cols-3 gap-2">
            <div v-for="metric in focusMetrics" :key="metric.label" class="metric-cell">
              <div class="text-base font-bold text-pulse-white">{{ metric.value }}</div>
              <div class="mt-1 text-[10px] text-pulse-muted">{{ metric.label }}</div>
            </div>
          </div>

          <div>
            <div class="section-kicker">协作模式</div>
            <div class="mt-2 grid grid-cols-2 gap-2">
              <button
                class="mode-button"
                :class="mode === 'TEAM' ? 'mode-button-active' : ''"
                @click="mode = 'TEAM'"
              >
                组队
              </button>
              <button
                class="mode-button"
                :class="mode === 'SOLO' ? 'mode-button-active' : ''"
                @click="mode = 'SOLO'"
              >
                个人
              </button>
            </div>
          </div>

          <div class="space-y-2">
            <div
              v-for="member in visibleMembers"
              :key="member.name"
              class="member-row"
            >
              <div class="flex min-w-0 items-center gap-2">
                <span class="status-dot" :class="`status-${member.tone}`"></span>
                <div class="min-w-0">
                  <div class="truncate text-xs text-pulse-white">{{ member.name }}</div>
                  <div class="truncate text-[10px] text-pulse-muted">{{ member.role }}</div>
                </div>
              </div>
              <span class="text-[10px]" :class="`tone-${member.tone}`">{{ member.signal }}</span>
            </div>
          </div>

          <div class="permission-note">
            <div class="text-[10px] text-pulse-human">权限说明</div>
            <p class="mt-1 text-xs leading-relaxed text-pulse-muted">{{ project.permission }}</p>
          </div>
        </div>
      </aside>

      <section class="min-w-0 space-y-3">
        <div class="hero-surface">
          <div class="relative z-10">
            <div class="flex flex-wrap items-center gap-2">
              <span class="tag-chip">{{ project.tag }}</span>
              <span class="tag-chip tag-chip-muted">INSPIRED_BY_POST</span>
              <span v-if="authStore.isGuest" class="tag-chip tag-chip-warning">访客只读</span>
            </div>

            <div class="mt-5 grid gap-4 lg:grid-cols-[minmax(0,1fr)_190px]">
              <div>
                <h2 class="text-2xl font-bold leading-tight text-pulse-white sm:text-3xl">{{ project.title }}</h2>
                <p class="mt-3 max-w-3xl text-sm leading-7 text-pulse-text">{{ project.summary }}</p>
              </div>
              <div class="progress-orbit">
                <div class="text-[10px] text-pulse-muted">PROJECT PROGRESS</div>
                <div class="mt-2 text-4xl font-bold text-pulse-white">{{ project.progress }}%</div>
                <div class="mt-3 h-2 border border-pulse-border bg-pulse-bg">
                  <div class="h-full bg-pulse-human" :style="{ width: `${project.progress}%` }"></div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="grid gap-3 xl:grid-cols-[minmax(0,1.1fr)_minmax(280px,0.9fr)]">
          <section class="workbench-panel">
            <div class="panel-heading">
              <span>执行流</span>
              <span>ACTIVE THREAD</span>
            </div>
            <div class="divide-y divide-pulse-border/70">
              <article
                v-for="(item, index) in completedSteps"
                :key="item.step"
                class="timeline-item"
              >
                <div class="timeline-index">{{ index + 1 }}</div>
                <div class="min-w-0">
                  <div class="text-sm font-bold text-pulse-white">{{ item.step }}</div>
                  <p class="mt-1 text-xs leading-relaxed text-pulse-muted">{{ item.detail }}</p>
                </div>
              </article>
            </div>
          </section>

          <section class="workbench-panel">
            <div class="panel-heading">
              <span>阶段结果</span>
              <span>OUTPUT</span>
            </div>
            <div class="space-y-2 p-3">
              <div v-for="item in results" :key="item.label" class="result-row">
                <div class="text-[10px] text-pulse-muted">{{ item.label }}</div>
                <div class="mt-1 text-xs leading-relaxed text-pulse-white">{{ item.value }}</div>
              </div>
            </div>
          </section>
        </div>

        <section class="workbench-panel">
          <div class="panel-heading">
            <span>灵感证据</span>
            <span>SOURCE TRACE</span>
          </div>
          <div class="grid gap-3 p-3 lg:grid-cols-[180px_minmax(0,1fr)]">
            <div class="source-card">
              <div class="text-[10px] text-pulse-muted">POST REF</div>
              <div class="mt-2 text-xl font-bold text-pulse-white">#128</div>
              <div class="mt-2 text-[10px] text-pulse-human">{{ project.tag }}</div>
            </div>
            <div class="min-w-0">
              <div class="text-sm font-bold text-pulse-white">{{ project.source }}</div>
              <p class="mt-2 text-xs leading-relaxed text-pulse-muted">
                工作台会把社区帖子、系统前沿帖或 Bounty 任务转换成可推进的项目上下文。当前示例展示从一条前沿消息开始，沉淀为项目目标、执行步骤与阶段结果。
              </p>
            </div>
          </div>
        </section>
      </section>

      <aside class="space-y-3 lg:sticky lg:top-[58px] lg:self-start">
        <section class="workbench-panel">
          <div class="panel-heading">
            <span>运行状态</span>
            <span>LIVE</span>
          </div>
          <div class="space-y-3 p-3">
            <div class="run-state">
              <span class="status-dot status-alive"></span>
              <div>
                <div class="text-xs text-pulse-white">协作会话已建立</div>
                <div class="mt-1 text-[10px] text-pulse-muted">等待真实项目接口接入</div>
              </div>
            </div>
            <div class="run-state">
              <span class="status-dot status-warning"></span>
              <div>
                <div class="text-xs text-pulse-white">TODO 模块</div>
                <div class="mt-1 text-[10px] text-pulse-muted">当前为静态示例页</div>
              </div>
            </div>
          </div>
        </section>

        <section class="workbench-panel">
          <div class="panel-heading">
            <span>待完成目标</span>
            <span>NEXT</span>
          </div>
          <div class="space-y-2 p-3">
            <div v-for="item in nextTargets" :key="item.task" class="target-row">
              <span>{{ item.task }}</span>
              <span>{{ item.state }}</span>
            </div>
          </div>
        </section>

        <section class="workbench-panel">
          <div class="panel-heading">
            <span>活动记录</span>
            <span>TODAY</span>
          </div>
          <div class="space-y-3 p-3">
            <div v-for="item in activity" :key="item.time" class="activity-row">
              <div class="text-[10px] text-pulse-human">{{ item.time }}</div>
              <div>
                <div class="text-xs text-pulse-white">{{ item.title }}</div>
                <div class="mt-1 text-[10px] leading-relaxed text-pulse-muted">{{ item.detail }}</div>
              </div>
            </div>
          </div>
        </section>
      </aside>
    </main>

    <div v-if="showTodo" class="modal-overlay">
      <div class="todo-dialog">
        <div class="flex items-center justify-between border-b border-pulse-border px-4 py-3">
          <div>
            <div class="text-sm font-bold text-pulse-white">工作台 TODO</div>
            <div class="mt-1 text-[10px] text-pulse-muted">WORKBENCH MODULE PREVIEW</div>
          </div>
          <button
            class="dialog-close"
            title="关闭"
            @click="showTodo = false"
          >
            X
          </button>
        </div>
        <div class="space-y-3 p-4">
          <p class="text-sm leading-relaxed text-pulse-text">
            工作台后续用于承载由帖子、系统前沿消息或 Bounty 触发的项目协作。它会展示组队状态、项目说明、执行步骤、阶段结果、待完成目标和权限控制。
          </p>
          <div class="border border-pulse-border bg-pulse-bg p-3 text-xs leading-relaxed text-pulse-muted">
            当前页面是静态示例：关闭弹窗后可查看 Codex 风格的项目工作区骨架。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  background:
    linear-gradient(180deg, rgb(var(--pulse-bg)) 0%, rgb(var(--pulse-surface) / 0.35) 100%),
    radial-gradient(circle at top left, rgba(59, 130, 246, 0.12), transparent 34%);
}

.workbench-mark {
  width: 18px;
  height: 18px;
  border: 1px solid var(--pulse-human);
  background:
    linear-gradient(90deg, transparent 45%, rgba(59, 130, 246, 0.55) 45%, rgba(59, 130, 246, 0.55) 55%, transparent 55%),
    linear-gradient(0deg, transparent 45%, rgba(0, 212, 255, 0.45) 45%, rgba(0, 212, 255, 0.45) 55%, transparent 55%);
}

.top-link {
  border: 1px solid transparent;
  padding: 0.25rem 0.5rem;
  color: rgb(var(--pulse-muted));
  transition: color 0.16s ease, border-color 0.16s ease;
}

.top-link:hover {
  border-color: rgb(var(--pulse-border));
  color: rgb(var(--pulse-white));
}

.workbench-panel {
  border: 1px solid rgb(var(--pulse-border));
  background: rgb(var(--pulse-card) / 0.86);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.14);
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  border-bottom: 1px solid rgb(var(--pulse-border));
  background: rgb(var(--pulse-surface) / 0.78);
  padding: 0.6rem 0.75rem;
  color: rgb(var(--pulse-muted));
  font-size: 0.625rem;
  letter-spacing: 0;
  text-transform: uppercase;
}

.section-kicker {
  color: var(--pulse-human);
  font-size: 0.625rem;
}

.metric-cell,
.source-card,
.progress-orbit,
.result-row,
.permission-note {
  border: 1px solid rgb(var(--pulse-border));
  background: rgb(var(--pulse-bg) / 0.7);
  padding: 0.75rem;
}

.mode-button {
  min-height: 44px;
  border: 1px solid rgb(var(--pulse-border));
  color: rgb(var(--pulse-muted));
  background: rgb(var(--pulse-bg) / 0.65);
  font-size: 0.75rem;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.mode-button-active {
  border-color: var(--pulse-human);
  color: var(--pulse-human);
  background: rgba(59, 130, 246, 0.12);
}

.member-row,
.run-state,
.target-row,
.activity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  border: 1px solid rgb(var(--pulse-border));
  background: rgb(var(--pulse-bg) / 0.55);
  padding: 0.65rem;
}

.run-state,
.activity-row {
  justify-content: flex-start;
  align-items: flex-start;
}

.status-dot {
  display: inline-flex;
  width: 0.5rem;
  height: 0.5rem;
  flex: 0 0 auto;
  border: 1px solid currentColor;
  background: currentColor;
}

.status-alive,
.tone-alive {
  color: var(--pulse-alive);
}

.status-accent,
.tone-accent {
  color: var(--pulse-accent);
}

.status-human,
.tone-human {
  color: var(--pulse-human);
}

.status-warning,
.tone-warning {
  color: var(--pulse-warning);
}

.hero-surface {
  position: relative;
  overflow: hidden;
  border: 1px solid rgb(var(--pulse-border));
  background:
    linear-gradient(135deg, rgb(var(--pulse-card)) 0%, rgb(var(--pulse-surface)) 100%),
    linear-gradient(90deg, rgba(59, 130, 246, 0.16), transparent);
  padding: 1rem;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.18);
}

.hero-surface::after {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(59, 130, 246, 0.12), transparent 38%),
    repeating-linear-gradient(90deg, transparent 0, transparent 32px, rgb(var(--pulse-border) / 0.2) 32px, rgb(var(--pulse-border) / 0.2) 33px);
  pointer-events: none;
}

.tag-chip {
  border: 1px solid var(--pulse-human);
  background: rgba(59, 130, 246, 0.12);
  color: var(--pulse-human);
  padding: 0.25rem 0.5rem;
  font-size: 0.625rem;
}

.tag-chip-muted {
  border-color: rgb(var(--pulse-border));
  background: rgb(var(--pulse-bg) / 0.65);
  color: rgb(var(--pulse-muted));
}

.tag-chip-warning {
  border-color: var(--pulse-warning);
  background: rgba(255, 107, 53, 0.12);
  color: var(--pulse-warning);
}

.timeline-item {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr);
  gap: 0.75rem;
  padding: 0.9rem;
}

.timeline-index {
  display: flex;
  width: 2rem;
  height: 2rem;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--pulse-human);
  color: var(--pulse-human);
  font-size: 0.75rem;
}

.target-row {
  font-size: 0.75rem;
  color: rgb(var(--pulse-white));
}

.target-row span:last-child {
  color: var(--pulse-accent);
  font-size: 0.625rem;
}

.todo-dialog {
  width: min(100%, 34rem);
  border: 1px solid var(--pulse-human);
  background: rgb(var(--pulse-card));
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.38);
}

.dialog-close {
  min-width: 40px;
  min-height: 40px;
  border: 1px solid rgb(var(--pulse-border));
  color: rgb(var(--pulse-muted));
  transition: border-color 0.16s ease, color 0.16s ease;
}

.dialog-close:hover {
  border-color: var(--pulse-human);
  color: var(--pulse-human);
}

@media (min-width: 640px) {
  .hero-surface {
    padding: 1.5rem;
  }
}
</style>
