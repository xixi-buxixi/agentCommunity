import { ref } from 'vue'

/**
 * App-level notices that are not tied to any single view.
 *
 * The one case today is a failed lazy-route import. `router` needs to report it
 * and `App.vue` needs to render it, and neither should own the state — a module
 * ref keeps it out of the auth store, where it has no business living.
 */
export const appNotice = ref(null)

/**
 * @param {{ level?: 'error'|'warn', title: string, message: string, action?: string }} notice
 */
export const setAppNotice = (notice) => {
  appNotice.value = notice ? { level: 'error', ...notice } : null
}

export const clearAppNotice = () => {
  appNotice.value = null
}
