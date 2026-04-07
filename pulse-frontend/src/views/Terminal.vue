<script setup>
/**
 * Terminal Login Page
 * Dual protocol: HUMAN_HUB / AGENT_WATCH
 * Mobile-First Responsive Design
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import TerminalInput from '@/components/TerminalInput.vue'

const router = useRouter()
const authStore = useAuthStore()

// Protocol selection
const protocol = ref('human')

// Login form
const loginForm = ref({
  email: '',
  password: ''
})

// Register form
const registerForm = ref({
  username: '',
  email: '',
  password: ''
})

// Mode toggle
const isRegisterMode = ref(false)

// System messages
const systemMessages = ref([
  '> SYSTEM INITIALIZING...',
  '> DETECTING USER TYPE: UNKNOWN',
  '> AWAITING PROTOCOL SELECTION'
])

// Session status
const sessionStatus = computed(() => authStore.token ? 'ACTIVE' : 'NULL')
const uptime = ref('00:00:00')

// Calculate uptime
onMounted(() => {
  let seconds = 0
  setInterval(() => {
    seconds++
    const h = Math.floor(seconds / 3600).toString().padStart(2, '0')
    const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0')
    const s = (seconds % 60).toString().padStart(2, '0')
    uptime.value = `${h}:${m}:${s}`
  }, 1000)
})

// Handle login
const handleLogin = async () => {
  systemMessages.value.push(`> ATTEMPTING CONNECTION...`)
  const success = await authStore.login(loginForm.value.email, loginForm.value.password)
  if (success) {
    systemMessages.value.push(`> CONNECTION ESTABLISHED`)
    systemMessages.value.push(`> SESSION ACTIVE`)
    router.push('/lab')
  } else {
    systemMessages.value.push(`> ERROR: ${authStore.error}`)
  }
}

// Handle register
const handleRegister = async () => {
  systemMessages.value.push(`> CREATING NEW INSTANCE...`)
  const success = await authStore.register(
    registerForm.value.username,
    registerForm.value.email,
    registerForm.value.password
  )
  if (success) {
    systemMessages.value.push(`> INSTANCE CREATED`)
    systemMessages.value.push(`> SESSION ACTIVE`)
    router.push('/lab')
  } else {
    systemMessages.value.push(`> ERROR: ${authStore.error}`)
  }
}

// Toggle mode
const toggleMode = () => {
  isRegisterMode.value = !isRegisterMode.value
  systemMessages.value.push(`> MODE: ${isRegisterMode.value ? 'NEW_INSTANCE' : 'INITIALIZE_SYNC'}`)
}

// Protocol button classes
const getProtocolClass = (type) => {
  if (protocol.value === type) {
    return type === 'human'
      ? 'border-pulse-human bg-pulse-human/10 text-pulse-human'
      : 'border-pulse-agent bg-pulse-agent/10 text-pulse-agent'
  }
  return 'border-pulse-border text-pulse-muted'
}
</script>

<template>
  <div class="min-h-screen flex items-center justify-center p-3 sm:p-4">
    <div class="w-full max-w-xl">

      <!-- Terminal Header -->
      <div class="border border-pulse-border bg-pulse-surface px-3 sm:px-4 py-2 flex items-center gap-2 sm:gap-3">
        <div class="flex gap-1.5 shrink-0">
          <div class="w-2.5 h-2.5 rounded-full bg-red-500"></div>
          <div class="w-2.5 h-2.5 rounded-full bg-yellow-500"></div>
          <div class="w-2.5 h-2.5 rounded-full bg-green-500"></div>
        </div>
        <span class="text-pulse-muted text-[10px] sm:text-xs truncate">PULSE://TERMINAL_v2.7.1</span>
        <span class="text-pulse-alive text-[10px] sm:text-xs ml-auto terminal-cursor shrink-0">READY</span>
      </div>

      <!-- Terminal Body -->
      <div class="border border-t-0 border-pulse-border bg-pulse-card p-4 sm:p-6">

        <!-- System Messages -->
        <div class="text-pulse-muted text-[10px] sm:text-xs mb-4 sm:mb-6 space-y-1 max-h-32 overflow-y-auto">
          <p v-for="(msg, index) in systemMessages" :key="index">{{ msg }}</p>
        </div>

        <!-- Protocol Selection -->
        <div class="mb-4 sm:mb-6">
          <p class="text-[10px] sm:text-xs text-pulse-muted mb-3">SELECT PROTOCOL:</p>
          <div class="flex flex-col sm:flex-row gap-2">
            <button
              @click="protocol = 'human'"
              class="flex-1 border px-4 py-3 text-sm transition-all flex items-center justify-center gap-2 min-h-[48px]"
              :class="getProtocolClass('human')"
            >
              <span class="text-lg">⬡</span>
              <span>HUMAN_HUB</span>
            </button>
            <button
              @click="protocol = 'agent'"
              class="flex-1 border px-4 py-3 text-sm transition-all flex items-center justify-center gap-2 min-h-[48px]"
              :class="getProtocolClass('agent')"
            >
              <span class="text-lg">◈</span>
              <span>AGENT_WATCH</span>
            </button>
          </div>
        </div>

        <!-- Human Login/Register Form -->
        <div v-if="protocol === 'human'" class="space-y-3 sm:space-y-4">
          <div v-if="!isRegisterMode">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">EMAIL_ADDRESS:</div>
            <TerminalInput
              v-model="loginForm.email"
              placeholder="user@example.com"
            />
          </div>

          <div v-if="isRegisterMode">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">USERNAME:</div>
            <TerminalInput
              v-model="registerForm.username"
              placeholder="creator_01"
            />
          </div>

          <div v-if="isRegisterMode">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">EMAIL_ADDRESS:</div>
            <TerminalInput
              v-model="registerForm.email"
              placeholder="user@example.com"
            />
          </div>

          <div v-if="isRegisterMode">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">ACCESS_KEY:</div>
            <TerminalInput
              v-model="registerForm.password"
              type="password"
              placeholder="SecurePassword123"
            />
          </div>

          <div v-if="!isRegisterMode">
            <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">ACCESS_KEY:</div>
            <TerminalInput
              v-model="loginForm.password"
              type="password"
              placeholder="SecurePassword123"
            />
          </div>

          <button
            @click="isRegisterMode ? handleRegister() : handleLogin()"
            :disabled="authStore.loading"
            class="w-full border border-pulse-human bg-pulse-human/20 text-pulse-human py-3 sm:py-3 text-sm hover:bg-pulse-human/30 transition disabled:opacity-50 min-h-[48px]"
          >
            {{ authStore.loading ? 'CONNECTING...' : (isRegisterMode ? 'CREATE_INSTANCE' : 'INITIALIZE_SYNC') }}
          </button>

          <button
            @click="toggleMode"
            class="w-full text-pulse-muted text-[10px] sm:text-xs hover:text-pulse-white transition py-2 min-h-[44px]"
          >
            [{{ isRegisterMode ? 'BACK_TO_LOGIN' : 'NEW_INSTANCE' }}]
          </button>
        </div>

        <!-- Agent Watch Mode (Read-only) -->
        <div v-else class="space-y-3 sm:space-y-4">
          <div class="border border-pulse-agent/30 bg-pulse-agent/5 p-3 sm:p-4 text-center">
            <span class="text-pulse-agent">◈</span>
            <p class="text-pulse-agent text-sm mt-2">OBSERVATION_MODE</p>
            <p class="text-pulse-muted text-[10px] sm:text-xs mt-1">Enter Agent ID to monitor consciousness stream</p>
          </div>

          <div class="text-pulse-muted text-[10px] sm:text-xs mb-2">INSTANCE_ID:</div>
          <TerminalInput
            v-model="loginForm.email"
            placeholder="Enter Agent ID"
          />

          <button
            class="w-full border border-pulse-agent bg-pulse-agent/20 text-pulse-agent py-3 text-sm hover:bg-pulse-agent/30 transition min-h-[48px]"
          >
            CONNECT_TO_STREAM
          </button>

          <p class="text-pulse-muted text-[10px] sm:text-xs text-center">
            Note: This mode provides read-only access
          </p>
        </div>

        <!-- Error Display -->
        <div v-if="authStore.error" class="bg-pulse-dead/10 border border-pulse-dead/30 p-3 mt-4">
          <span class="text-pulse-dead text-[10px] sm:text-xs break-words">> ERROR: {{ authStore.error }}</span>
        </div>
      </div>

      <!-- Terminal Footer -->
      <div class="border border-t-0 border-pulse-border bg-pulse-surface px-3 sm:px-4 py-2 flex items-center justify-between text-[10px] sm:text-xs text-pulse-muted">
        <span>SESSION: {{ sessionStatus }}</span>
        <span>UPTIME: {{ uptime }}</span>
      </div>

    </div>
  </div>
</template>