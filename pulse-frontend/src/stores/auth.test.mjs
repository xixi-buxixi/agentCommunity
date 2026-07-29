import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'

/**
 * Guards the guest/session state machine.
 *
 * These are the two invariants the old code broke:
 *
 *  1. requireLogin() must NOT destroy guest mode. It used to call logout(), which
 *     left the visitor neither guest nor authenticated — every nav link then hit
 *     the auth guard, was redirected to the page it was already on, and silently
 *     did nothing.
 *  2. A successful login must clear guest mode. Otherwise the router guard's guest
 *     branch (checked before requiresAuth) keeps applying read-only restrictions
 *     to a fully authenticated user, and requireLogin() keeps blocking their
 *     actions.
 *
 * The store imports 'pinia' and '@/api/auth', neither of which resolves under
 * plain `node --test`, so the source is loaded with those imports stubbed - the
 * same approach as useReaction.test.mjs.
 */
const store = {}
let loginResult = { data: { token: 'tok', user_id: 1, username: 'u' } }
let loginShouldThrow = false
let userInfoShouldThrow = false

globalThis.__authStubs = {
  defineStore: (_name, options) => options,
  login: async () => {
    if (loginShouldThrow) throw new Error('BAD_CREDENTIALS')
    return loginResult
  },
  register: async () => loginResult,
  getUserInfo: async () => {
    if (userInfoShouldThrow) throw new Error('PROFILE_UNAVAILABLE')
    return { data: { user_id: 1, username: 'u', points: 250, pending_bounty: 40 } }
  }
}

// Minimal localStorage so the store's persistence calls are observable.
globalThis.localStorage = {
  getItem: (k) => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v) },
  removeItem: (k) => { delete store[k] }
}

const source = readFileSync(new URL('./auth.js', import.meta.url), 'utf8')
  .replace("import { defineStore } from 'pinia'", 'const { defineStore } = globalThis.__authStubs')
  .replace(
    "import { login, register, getUserInfo } from '@/api/auth'",
    'const { login, register, getUserInfo } = globalThis.__authStubs'
  )

const { useAuthStore } = await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)

// defineStore is stubbed to return the options object, so build an instance by
// binding the actions to a fresh state.
const makeStore = () => {
  const options = useAuthStore
  const instance = options.state()
  Object.entries(options.actions).forEach(([name, fn]) => {
    instance[name] = fn.bind(instance)
  })
  return instance
}

const reset = () => Object.keys(store).forEach((k) => delete store[k])

test('requireLogin prompts a guest without destroying guest mode', () => {
  reset()
  const auth = makeStore()
  auth.enterGuestMode()

  const blocked = auth.requireLogin('接取悬赏需要登录账号')

  assert.equal(blocked, true, 'caller must be told to stop')
  assert.equal(auth.isGuest, true, 'guest mode must survive a blocked action')
  assert.equal(store.pulse_guest, 'true', 'persisted guest flag must survive too')
  assert.equal(auth.loginPrompt.message, '接取悬赏需要登录账号')
})

test('requireLogin does not block a logged-in user', () => {
  reset()
  const auth = makeStore()
  auth.token = 'tok'

  assert.equal(auth.requireLogin('x'), false)
  assert.equal(auth.loginPrompt, null)
})

test('dismissLoginPrompt clears the prompt and keeps the guest session', () => {
  reset()
  const auth = makeStore()
  auth.enterGuestMode()
  auth.requireLogin()
  auth.dismissLoginPrompt()

  assert.equal(auth.loginPrompt, null)
  assert.equal(auth.isGuest, true)
})

test('a guest who logs in stops being a guest', async () => {
  reset()
  loginShouldThrow = false
  const auth = makeStore()
  auth.enterGuestMode()
  auth.requireLogin('接取悬赏需要登录账号')

  const ok = await auth.login('a@b.com', 'pw')

  assert.equal(ok, true)
  assert.equal(auth.isGuest, false, 'guest mode must not outlive a real session')
  assert.equal(store.pulse_guest, undefined, 'persisted guest flag must be cleared')
  assert.equal(auth.loginPrompt, null, 'a stale prompt must not survive login')
  assert.equal(auth.token, 'tok')
})

test('a guest who registers stops being a guest', async () => {
  reset()
  const auth = makeStore()
  auth.enterGuestMode()

  const ok = await auth.register('u', 'a@b.com', 'pw')

  assert.equal(ok, true)
  assert.equal(auth.isGuest, false)
  assert.equal(store.pulse_guest, undefined)
})

test('a failed login leaves guest mode untouched', async () => {
  reset()
  loginShouldThrow = true
  const auth = makeStore()
  auth.enterGuestMode()

  const ok = await auth.login('a@b.com', 'wrong')

  assert.equal(ok, false)
  assert.equal(auth.isGuest, true, 'a rejected login must not silently end the guest session')
  assert.equal(store.pulse_guest, 'true')
  loginShouldThrow = false
})

test('logout clears both session and guest state', () => {
  reset()
  const auth = makeStore()
  auth.enterGuestMode()
  auth.logout()

  assert.equal(auth.isGuest, false)
  assert.equal(auth.token, null)
  assert.equal(store.pulse_guest, undefined)
  assert.equal(store.pulse_token, undefined)
})

test('login hydrates the points balance the auth response omits', async () => {
  reset()
  loginShouldThrow = false
  userInfoShouldThrow = false
  const auth = makeStore()

  await auth.login('a@b.com', 'pw')

  // The header renders `user.points ?? '--'`. Without hydration this stayed
  // undefined forever, because the router guard only fetches when user is unset.
  assert.equal(auth.user.points, 250)
  assert.equal(auth.user.username, 'u')
})

test('a failed profile fetch does not undo a successful login', async () => {
  reset()
  userInfoShouldThrow = true
  const auth = makeStore()

  const ok = await auth.login('a@b.com', 'pw')

  assert.equal(ok, true, 'login itself succeeded')
  assert.equal(auth.token, 'tok', 'the session must survive a failed /auth/me')
  assert.equal(auth.user.username, 'u', 'the minimal profile from login remains')
  userInfoShouldThrow = false
})

test('a persisted token wins over a stale guest flag at startup', () => {
  reset()
  // The state a browser is left in by the old build: it logged in without ever
  // clearing pulse_guest, so both flags are present.
  store.pulse_token = 'tok'
  store.pulse_guest = 'true'

  const auth = makeStore()

  assert.equal(auth.token, 'tok')
  assert.equal(auth.isGuest, false, 'the guest branch must not capture a real session')
  assert.equal(store.pulse_guest, undefined, 'the stale flag is cleared, not just ignored')
})

test('a guest flag alone still yields guest mode', () => {
  reset()
  store.pulse_guest = 'true'

  const auth = makeStore()

  assert.equal(auth.isGuest, true)
  assert.equal(auth.token, null)
})

test('login reports failure if the session was invalidated during hydration', async () => {
  reset()
  loginShouldThrow = false
  userInfoShouldThrow = false
  const auth = makeStore()

  // Simulate the response interceptor logging the user out mid-hydration, which
  // is what a session-invalid code on /auth/me triggers.
  const realHydrate = auth.hydrateUser
  auth.hydrateUser = async () => { auth.logout(); return false }

  const ok = await auth.login('a@b.com', 'pw')

  assert.equal(ok, false, 'must not claim success for a session that no longer exists')
  assert.equal(auth.error, 'SESSION_REJECTED')
  auth.hydrateUser = realHydrate
})

console.log('auth store guest/session spec passed')
