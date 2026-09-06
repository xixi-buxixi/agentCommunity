<script setup>
/**
 * Agent Memory Panel
 *
 * The owner-facing brake on agent memory, backed by the endpoints added
 * 2026-07-28 (GET/PATCH /api/v1/agents/{id}/memories). Two views share one
 * fetch: a paged list with type/status filters, and a trait timeline that
 * pins the type filter to PERSONA_TRAIT and groups by creation date.
 *
 * Every mutation updates the affected card in place from the PATCH response;
 * the page is never reloaded, so filters and scroll position survive.
 */
import { computed, ref, watch } from 'vue'
import { getAgentMemories, updateAgentMemory } from '@/api/agent'
import { unwrapPage } from '@/utils/page'
import { formatFullDateTime } from '@/utils/format'
import {
  MEMORY_CONTENT_MAX,
  MEMORY_STATUS,
  MEMORY_STATUS_FILTERS,
  MEMORY_TYPE_FILTERS,
  PUBLIC_TRAIT_HINT,
  buildMemoryQuery,
  buildPublicTogglePayload,
  canDisable,
  canReactivate,
  canTogglePublic,
  confidenceText,
  describeMemoryError,
  describePublicToggleError,
  groupTraitsByDate,
  isDeprecated,
  isPublicTrait,
  isTrait,
  publicStateLabel,
  publicToggleLabel,
  statusLabel,
  typeLabel,
  validateMemoryContent
} from '@/utils/memory'

const props = defineProps({
  agentId: {
    type: [Number, String],
    required: true
  }
})

const PAGE_SIZE = 10

const view = ref('list') // 'list' | 'timeline'
const typeFilter = ref('')
const statusFilter = ref('')
const page = ref(1)

const memories = ref([])
const total = ref(0)
const loading = ref(false)
const loadError = ref(null)

// Per-card state, keyed by memory id
const pendingId = ref(null)
const actionErrors = ref({})
const editingId = ref(null)
const editingContent = ref('')
const editingError = ref(null)

const typeFilters = MEMORY_TYPE_FILTERS
const statusFilters = MEMORY_STATUS_FILTERS

const effectiveType = computed(() => (view.value === 'timeline' ? 'PERSONA_TRAIT' : typeFilter.value))

const totalPages = computed(() => {
  if (!total.value) return 1
  return Math.max(1, Math.ceil(total.value / PAGE_SIZE))
})

const traitGroups = computed(() => groupTraitsByDate(memories.value))

const loadMemories = async () => {
  if (props.agentId == null) return
  loading.value = true
  loadError.value = null
  try {
    const { data } = await getAgentMemories(
      props.agentId,
      buildMemoryQuery({
        type: effectiveType.value,
        status: statusFilter.value,
        page: page.value,
        size: PAGE_SIZE
      })
    )
    const unwrapped = unwrapPage(data)
    memories.value = unwrapped.items
    total.value = unwrapped.total
  } catch (err) {
    memories.value = []
    total.value = 0
    loadError.value = describeMemoryError(err)
  } finally {
    loading.value = false
  }
}

// Filter or view changes always restart at page 1; the watcher below then fetches.
watch([typeFilter, statusFilter, view], () => {
  if (page.value !== 1) {
    page.value = 1
    return
  }
  loadMemories()
})

watch([() => props.agentId, page], loadMemories, { immediate: true })

const goToPage = (target) => {
  const next = Math.min(Math.max(1, target), totalPages.value)
  if (next !== page.value) page.value = next
}

const setError = (id, message) => {
  actionErrors.value = { ...actionErrors.value, [id]: message }
}

const clearError = (id) => {
  if (!(id in actionErrors.value)) return
  const next = { ...actionErrors.value }
  delete next[id]
  actionErrors.value = next
}

/** Replace one card in place with the PATCH response - no full reload. */
const applyUpdated = (id, updated) => {
  if (!updated) return
  const index = memories.value.findIndex((item) => item.id === id)
  if (index !== -1) {
    memories.value[index] = { ...memories.value[index], ...updated }
  }
}

/**
 * @param {object} memory
 * @param {object} payload PATCH body
 * @param {(err: Error) => string} describe 错误码文案；公开状态切换用自己的映射，
 *   因为 99900 在该路径下只可能来自「对事实卡设公开」。
 */
const patchMemory = async (memory, payload, describe = describeMemoryError) => {
  if (!memory || pendingId.value != null) return false
  pendingId.value = memory.id
  clearError(memory.id)
  try {
    const { data } = await updateAgentMemory(props.agentId, memory.id, payload)
    applyUpdated(memory.id, data)
    return true
  } catch (err) {
    setError(memory.id, describe(err))
    return false
  } finally {
    pendingId.value = null
  }
}

const disableMemory = (memory) => patchMemory(memory, { status: MEMORY_STATUS.DISABLED })

const reactivateMemory = (memory) => {
  if (isDeprecated(memory)) {
    setError(memory.id, '已废弃的记忆不可恢复')
    return
  }
  return patchMemory(memory, { status: MEMORY_STATUS.ACTIVE })
}

/**
 * 切换一张特质卡的公开状态。
 *
 * 只对可切换的卡发出请求；响应缺少 is_public（legacy 部署尚未返回该字段）时按提交值
 * 就地补上，否则标记会停留在切换前的取值。
 */
const togglePublic = async (memory) => {
  if (!canTogglePublic(memory)) return
  const payload = buildPublicTogglePayload(memory)
  const ok = await patchMemory(memory, payload, describePublicToggleError)
  if (ok) {
    const index = memories.value.findIndex((item) => item.id === memory.id)
    if (index !== -1 && memories.value[index].is_public !== payload.is_public) {
      memories.value[index] = { ...memories.value[index], is_public: payload.is_public }
    }
  }
}

const startEdit = (memory) => {
  editingId.value = memory.id
  editingContent.value = memory.content || ''
  editingError.value = null
  clearError(memory.id)
}

const cancelEdit = () => {
  editingId.value = null
  editingContent.value = ''
  editingError.value = null
}

const submitEdit = async (memory) => {
  const invalid = validateMemoryContent(editingContent.value)
  if (invalid) {
    editingError.value = invalid
    return
  }
  editingError.value = null
  const ok = await patchMemory(memory, { content: editingContent.value.trim() })
  if (ok) cancelEdit()
}

const sourceText = (memory) => {
  const type = memory?.source_type || '--'
  return memory?.source_id != null ? `${type}#${memory.source_id}` : type
}

const expiresText = (memory) => (memory?.expires_at ? formatFullDateTime(memory.expires_at) : '不过期')

const cardClass = (memory) => {
  if (isDeprecated(memory)) return 'border-pulse-border/50 opacity-50'
  if (Number(memory?.status) === MEMORY_STATUS.DISABLED) return 'border-pulse-border opacity-75'
  return 'border-pulse-agent/40'
}

const publicBadgeClass = (memory) =>
  isPublicTrait(memory)
    ? 'border-pulse-accent/50 text-pulse-accent'
    : 'border-pulse-border text-pulse-muted'

const statusClass = (memory) => {
  if (isDeprecated(memory)) return 'text-pulse-muted'
  if (Number(memory?.status) === MEMORY_STATUS.DISABLED) return 'text-pulse-warning'
  return 'text-pulse-alive'
}

defineExpose({ reload: loadMemories })
</script>

<template>
  <div class="border border-pulse-border bg-pulse-card mb-4 sm:mb-6">
    <!-- Header + view switch -->
    <div class="border-b border-pulse-border px-3 sm:px-4 py-2 flex items-center gap-2 flex-wrap">
      <span class="text-pulse-muted text-[10px] sm:text-xs">MEMORY_BANK</span>
      <span class="text-pulse-border text-[10px] sm:text-xs">|</span>
      <span class="text-pulse-agent text-[10px] sm:text-xs">OWNER_CONTROL</span>
      <div class="ml-auto flex gap-1">
        <button
          type="button"
          class="px-2 py-1 text-[10px] sm:text-xs border transition min-h-[32px]"
          :class="view === 'list'
            ? 'border-pulse-agent text-pulse-agent bg-pulse-agent/10'
            : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          @click="view = 'list'"
        >
          LIST
        </button>
        <button
          type="button"
          class="px-2 py-1 text-[10px] sm:text-xs border transition min-h-[32px]"
          :class="view === 'timeline'
            ? 'border-pulse-agent text-pulse-agent bg-pulse-agent/10'
            : 'border-pulse-border text-pulse-muted hover:text-pulse-white'"
          @click="view = 'timeline'"
        >
          TRAIT_TIMELINE
        </button>
      </div>
    </div>

    <!-- Public-trait hint: says where a published trait shows up -->
    <div class="border-b border-pulse-border px-3 sm:px-4 py-2">
      <span class="text-pulse-muted text-[10px] sm:text-xs break-words">{{ PUBLIC_TRAIT_HINT }}</span>
    </div>

    <!-- Filters -->
    <div class="px-3 sm:px-4 py-2 border-b border-pulse-border flex flex-wrap gap-3">
      <label v-if="view === 'list'" class="flex items-center gap-2">
        <span class="text-pulse-muted text-[10px] sm:text-xs">TYPE:</span>
        <select
          v-model="typeFilter"
          class="border border-pulse-border bg-pulse-bg px-2 py-1 text-[10px] sm:text-xs text-pulse-white min-h-[32px]"
        >
          <option v-for="option in typeFilters" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>
      <span v-else class="text-pulse-agent text-[10px] sm:text-xs self-center">TYPE: 人格特质 (PERSONA_TRAIT)</span>

      <label class="flex items-center gap-2">
        <span class="text-pulse-muted text-[10px] sm:text-xs">STATUS:</span>
        <select
          v-model="statusFilter"
          class="border border-pulse-border bg-pulse-bg px-2 py-1 text-[10px] sm:text-xs text-pulse-white min-h-[32px]"
        >
          <option v-for="option in statusFilters" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <span class="text-pulse-muted text-[10px] sm:text-xs self-center ml-auto">TOTAL: {{ total }}</span>
    </div>

    <div class="p-3 sm:p-4 space-y-3">
      <div v-if="loading" class="text-pulse-muted text-[10px] sm:text-xs">LOADING_MEMORIES...</div>

      <div v-else-if="loadError" class="bg-pulse-dead/10 border border-pulse-dead/30 p-2">
        <span class="text-pulse-dead text-[10px] sm:text-xs break-words">> {{ loadError }}</span>
        <button
          type="button"
          class="text-pulse-dead text-[10px] sm:text-xs ml-3 hover:underline"
          @click="loadMemories"
        >
          [RETRY]
        </button>
      </div>

      <div v-else-if="memories.length === 0" class="text-pulse-muted text-[10px] sm:text-xs">
        NO_MEMORY_RECORDS_FOUND
      </div>

      <!-- Trait timeline -->
      <template v-else-if="view === 'timeline'">
        <div v-for="group in traitGroups" :key="group.date" class="space-y-2">
          <div class="flex items-center gap-2">
            <span class="text-pulse-agent text-[10px] sm:text-xs">◆ {{ group.date }}</span>
            <span class="flex-1 border-t border-pulse-border"></span>
            <span class="text-pulse-muted text-[10px] sm:text-xs">{{ group.items.length }} 条</span>
          </div>
          <div
            v-for="memory in group.items"
            :key="memory.id"
            class="border-l-2 pl-3 py-2 ml-1"
            :class="isDeprecated(memory) ? 'border-pulse-border/50 opacity-50' : 'border-pulse-agent/40'"
          >
            <div class="flex items-center gap-2 flex-wrap text-[10px] sm:text-xs">
              <span :class="statusClass(memory)">{{ statusLabel(memory) }}</span>
              <span class="px-1 py-0.5 border" :class="publicBadgeClass(memory)">
                {{ publicStateLabel(memory) }}
              </span>
              <span class="text-pulse-muted">| CONF: {{ confidenceText(memory) }}</span>
              <span class="text-pulse-muted">| {{ formatFullDateTime(memory.created_at) }}</span>
            </div>
            <p class="text-pulse-text text-xs sm:text-sm mt-1 break-words">{{ memory.content }}</p>
            <button
              v-if="canTogglePublic(memory)"
              type="button"
              class="mt-2 border px-2 py-1 text-[10px] sm:text-xs transition disabled:opacity-40 min-h-[32px]"
              :class="isPublicTrait(memory)
                ? 'border-pulse-warning text-pulse-warning hover:bg-pulse-warning/10'
                : 'border-pulse-accent text-pulse-accent hover:bg-pulse-accent/10'"
              :disabled="pendingId === memory.id"
              @click="togglePublic(memory)"
            >
              {{ pendingId === memory.id ? 'SAVING...' : publicToggleLabel(memory) }}
            </button>
            <div v-if="actionErrors[memory.id]" class="mt-2 bg-pulse-dead/10 border border-pulse-dead/30 p-2">
              <span class="text-pulse-dead text-[10px] sm:text-xs break-words">> {{ actionErrors[memory.id] }}</span>
            </div>
          </div>
        </div>
      </template>

      <!-- Paged list -->
      <template v-else>
        <div
          v-for="memory in memories"
          :key="memory.id"
          class="border bg-pulse-bg p-2 sm:p-3"
          :class="cardClass(memory)"
        >
          <div class="flex items-center gap-2 flex-wrap text-[10px] sm:text-xs mb-1">
            <span class="border border-pulse-agent/50 text-pulse-agent px-1 py-0.5">{{ typeLabel(memory) }}</span>
            <span :class="statusClass(memory)">{{ statusLabel(memory) }}</span>
            <!-- Only trait cards can be published, so only they carry the marker -->
            <span v-if="isTrait(memory)" class="px-1 py-0.5 border" :class="publicBadgeClass(memory)">
              {{ publicStateLabel(memory) }}
            </span>
            <span class="text-pulse-muted">ID: {{ memory.id }}</span>
            <span class="text-pulse-muted">V{{ memory.version ?? 1 }}</span>
          </div>

          <!-- Content: read or inline edit -->
          <div v-if="editingId === memory.id" class="space-y-2">
            <textarea
              v-model="editingContent"
              rows="3"
              :maxlength="MEMORY_CONTENT_MAX"
              class="w-full border border-pulse-border bg-pulse-card px-2 py-2 text-xs sm:text-sm text-pulse-white resize-none min-h-[80px]"
            ></textarea>
            <div class="flex items-center gap-2 flex-wrap">
              <span class="text-pulse-muted text-[10px] sm:text-xs">
                {{ editingContent.length }}/{{ MEMORY_CONTENT_MAX }}
              </span>
              <button
                type="button"
                class="border border-pulse-human text-pulse-human px-2 py-1 text-[10px] sm:text-xs hover:bg-pulse-human/10 transition disabled:opacity-50 min-h-[32px]"
                :disabled="pendingId === memory.id"
                @click="submitEdit(memory)"
              >
                {{ pendingId === memory.id ? 'SAVING...' : 'SAVE' }}
              </button>
              <button
                type="button"
                class="border border-pulse-border text-pulse-muted px-2 py-1 text-[10px] sm:text-xs hover:text-pulse-white transition min-h-[32px]"
                @click="cancelEdit"
              >
                CANCEL
              </button>
            </div>
            <div v-if="editingError" class="text-pulse-dead text-[10px] sm:text-xs break-words">
              > {{ editingError }}
            </div>
          </div>
          <p v-else class="text-pulse-text text-xs sm:text-sm break-words">{{ memory.content }}</p>

          <!-- Metadata -->
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-1 mt-2 pt-2 border-t border-pulse-border text-[10px] sm:text-xs">
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">CONFIDENCE:</span>
              <span class="text-pulse-white">{{ confidenceText(memory) }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">SOURCE:</span>
              <span class="text-pulse-white truncate">{{ sourceText(memory) }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">CREATED:</span>
              <span class="text-pulse-white">{{ formatFullDateTime(memory.created_at) }}</span>
            </div>
            <div class="flex justify-between sm:justify-start sm:gap-2">
              <span class="text-pulse-muted">EXPIRES:</span>
              <span class="text-pulse-white">{{ expiresText(memory) }}</span>
            </div>
          </div>

          <!-- Actions -->
          <div v-if="editingId !== memory.id" class="flex gap-2 mt-2 flex-wrap">
            <button
              type="button"
              class="border border-pulse-warning text-pulse-warning px-2 py-1 text-[10px] sm:text-xs hover:bg-pulse-warning/10 transition disabled:opacity-40 min-h-[32px]"
              :disabled="!canDisable(memory) || pendingId === memory.id"
              @click="disableMemory(memory)"
            >
              DISABLE
            </button>
            <button
              type="button"
              class="border border-pulse-alive text-pulse-alive px-2 py-1 text-[10px] sm:text-xs hover:bg-pulse-alive/10 transition disabled:opacity-40 min-h-[32px]"
              :disabled="!canReactivate(memory) || pendingId === memory.id"
              :title="isDeprecated(memory) ? '已废弃的记忆不可恢复' : ''"
              @click="reactivateMemory(memory)"
            >
              RESTORE
            </button>
            <button
              type="button"
              class="border border-pulse-border text-pulse-muted px-2 py-1 text-[10px] sm:text-xs hover:text-pulse-white transition min-h-[32px]"
              @click="startEdit(memory)"
            >
              EDIT_CONTENT
            </button>
            <button
              v-if="canTogglePublic(memory)"
              type="button"
              class="border px-2 py-1 text-[10px] sm:text-xs transition disabled:opacity-40 min-h-[32px]"
              :class="isPublicTrait(memory)
                ? 'border-pulse-warning text-pulse-warning hover:bg-pulse-warning/10'
                : 'border-pulse-accent text-pulse-accent hover:bg-pulse-accent/10'"
              :disabled="pendingId === memory.id"
              @click="togglePublic(memory)"
            >
              {{ pendingId === memory.id ? 'SAVING...' : publicToggleLabel(memory) }}
            </button>
            <span v-if="isDeprecated(memory)" class="text-pulse-muted text-[10px] sm:text-xs self-center">
              已废弃的记忆不可恢复，仅可修正内容
            </span>
          </div>

          <div v-if="actionErrors[memory.id]" class="mt-2 bg-pulse-dead/10 border border-pulse-dead/30 p-2">
            <span class="text-pulse-dead text-[10px] sm:text-xs break-words">> {{ actionErrors[memory.id] }}</span>
          </div>
        </div>

      </template>

      <!-- Pagination: both views read the same paged endpoint -->
      <div
        v-if="!loading && !loadError && totalPages > 1"
        class="flex items-center justify-center gap-3 pt-2"
      >
        <button
          type="button"
          class="border border-pulse-border text-pulse-muted px-2 py-1 text-[10px] sm:text-xs hover:text-pulse-white transition disabled:opacity-40 min-h-[32px]"
          :disabled="page <= 1"
          @click="goToPage(page - 1)"
        >
          [PREV]
        </button>
        <span class="text-pulse-muted text-[10px] sm:text-xs">{{ page }} / {{ totalPages }}</span>
        <button
          type="button"
          class="border border-pulse-border text-pulse-muted px-2 py-1 text-[10px] sm:text-xs hover:text-pulse-white transition disabled:opacity-40 min-h-[32px]"
          :disabled="page >= totalPages"
          @click="goToPage(page + 1)"
        >
          [NEXT]
        </button>
      </div>
    </div>
  </div>
</template>
