import { defineStore } from 'pinia'
import { login, register, getUserInfo } from '@/api/auth'

/**
 * Read a boolean flag from localStorage.
 *
 * A bare JSON.parse threw on any polluted value (another tab, an extension, a
 * half-written string), and because that happens during store construction the
 * exception took the whole app down instead of degrading to "not a guest".
 */
const readBooleanFlag = (key) => {
  try {
    return JSON.parse(localStorage.getItem(key) || 'false') === true
  } catch {
    localStorage.removeItem(key)
    return false
  }
}

/**
 * Reconcile the two persisted flags at startup.
 *
 * A session and guest mode are mutually exclusive, but browsers that used the
 * earlier build can hold `pulse_guest=true` next to a valid `pulse_token` — back
 * then login never cleared the guest flag. Loading both would leave the visitor
 * permanently in the router's guest branch, and nothing would ever clear it
 * because that only happens on a fresh login. The token wins: it is the stronger
 * claim, and it is what the API will actually honour.
 */
const resolvePersistedIdentity = () => {
  const token = localStorage.getItem('pulse_token') || null
  const isGuest = readBooleanFlag('pulse_guest')
  if (token && isGuest) {
    localStorage.removeItem('pulse_guest')
    return { token, isGuest: false }
  }
  return { token, isGuest }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    ...resolvePersistedIdentity(),
    user: null,
    loading: false,
    error: null,
    /**
     * Set when a guest attempts something that needs an account.
     *
     * `null` means no prompt. Otherwise `{ message }`, rendered by the global
     * LoginRequiredModal. This replaces the old behaviour of logging the guest
     * out and hard-navigating to /terminal — see requireLogin() below.
     */
    loginPrompt: null
  }),

  getters: {
    isAuthenticated: (state) => !!state.token && !!state.user,
    isGuestMode: (state) => state.isGuest,
    username: (state) => {
      if (state.isGuest) return 'Guest'
      return state.user?.username || 'UNKNOWN'
    },
    userId: (state) => state.user?.user_id || null
  },

  actions: {
    /**
     * Clear read-only guest state once a real session exists.
     *
     * Guest mode and an authenticated session are mutually exclusive, but nothing
     * enforced that: `isGuest` outlives a successful login, and because the router
     * guard checks `isGuest` *before* `requiresAuth`, the freshly logged-in user
     * kept every guest restriction — /lab bounced to /square and requireLogin()
     * still blocked accepting a bounty. It used to be masked by the old gate,
     * which logged the guest out on the way to the login page.
     */
    exitGuestMode() {
      this.isGuest = false
      this.loginPrompt = null
      localStorage.removeItem('pulse_guest')
    },

    async login(email, password) {
      this.loading = true
      this.error = null
      try {
        const { data } = await login({ email, password })
        this.token = data.token
        this.user = {
          user_id: data.user_id,
          username: data.username
        }
        localStorage.setItem('pulse_token', data.token)
        this.exitGuestMode()
        await this.hydrateUser()

        // hydrateUser() hits /auth/me, and the response interceptor logs the user
        // out on a session-invalid code. Returning true after that made Terminal
        // announce "SESSION ACTIVE" and push to /lab, where the guard immediately
        // bounced them back for having no token — a success message followed by a
        // silent trip to the login page.
        if (!this.token) {
          this.error = 'SESSION_REJECTED'
          return false
        }
        return true
      } catch (err) {
        this.error = err.message || 'LOGIN_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    /**
     * Fill in profile fields the auth response does not carry (points balance,
     * avatar, agent count).
     *
     * Needed because the router guard only calls fetchUserInfo() when `user` is
     * unset, and login() sets a minimal user immediately — so the full profile was
     * never fetched on the login path and the points balance stayed undefined.
     *
     * Unlike fetchUserInfo(), a failure here does NOT log the user out: they have
     * just authenticated successfully, and losing the session over a secondary
     * request would turn a good login into a silent failure.
     */
    async hydrateUser() {
      try {
        const { data } = await getUserInfo()
        this.user = { ...this.user, ...data }
        return true
      } catch {
        return false
      }
    },

    async register(username, email, password) {
      this.loading = true
      this.error = null
      try {
        const { data } = await register({ username, email, password })
        this.token = data.token
        this.user = {
          user_id: data.user_id,
          username: data.username,
          email: data.email
        }
        localStorage.setItem('pulse_token', data.token)
        this.exitGuestMode()
        await this.hydrateUser()
        if (!this.token) {
          this.error = 'SESSION_REJECTED'
          return false
        }
        return true
      } catch (err) {
        this.error = err.message || 'REGISTER_FAILED'
        return false
      } finally {
        this.loading = false
      }
    },

    async fetchUserInfo() {
      if (!this.token) return false
      try {
        const { data } = await getUserInfo()
        this.user = data
        return true
      } catch {
        this.logout()
        return false
      }
    },

    enterGuestMode() {
      this.isGuest = true
      this.token = null
      this.user = null
      this.error = null
      this.loginPrompt = null
      localStorage.removeItem('pulse_token')
      localStorage.setItem('pulse_guest', 'true')
    },

    logout() {
      this.token = null
      this.user = null
      this.isGuest = false
      this.error = null
      this.loginPrompt = null
      localStorage.removeItem('pulse_token')
      localStorage.removeItem('pulse_guest')
    },

    /**
     * Guard for actions a guest cannot perform.
     *
     * This used to call logout() and then `window.location.href = '/terminal'`.
     * Three things went wrong with that:
     *
     *  1. logout() cleared `isGuest`, so after one blocked click the visitor was
     *     neither guest nor authenticated. The bottom nav stayed rendered but
     *     every link hit the `requiresAuth` guard, which redirected to
     *     /terminal — where the visitor already was. The result was a nav bar of
     *     buttons that did nothing at all, with no message.
     *  2. The hard navigation threw away the SPA and the page the visitor was
     *     reading, to say "please log in".
     *  3. Nothing recorded where they came from, so logging in landed them on
     *     /lab instead of the bounty they were trying to accept.
     *
     * Now it only raises a prompt. Guest mode survives, the current page stays
     * put, and the modal offers to log in while remembering the return path.
     *
     * @param {string} [message] What the visitor was trying to do.
     * @returns {boolean} true when the caller must stop (guest was blocked)
     */
    requireLogin(message) {
      if (!this.isGuest) return false
      this.loginPrompt = { message: message || '该操作需要登录账号' }
      return true
    },

    dismissLoginPrompt() {
      this.loginPrompt = null
    }
  }
})