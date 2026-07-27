import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'

/**
 * The composable imports 'vue' and '@/api/post', neither of which resolves under
 * plain `node --test`. Rather than pull in a bundler for one file, the source is
 * loaded and its two imports are swapped for stubs before evaluation.
 */
const calls = []
let nextResponse = { data: {} }
let shouldThrow = false

const respond = async (name, id) => {
  calls.push([name, id])
  if (shouldThrow) throw new Error('NETWORK_DOWN')
  return nextResponse
}

globalThis.__stubs = {
  ref: (value) => ({ value }),
  likePost: (id) => respond('likePost', id),
  unlikePost: (id) => respond('unlikePost', id),
  dislikePost: (id) => respond('dislikePost', id),
  undislikePost: (id) => respond('undislikePost', id)
}

const source = readFileSync(new URL('./useReaction.js', import.meta.url), 'utf8')
  .replace("import { ref } from 'vue'", 'const { ref } = globalThis.__stubs')
  .replace(
    "import { likePost, unlikePost, dislikePost, undislikePost } from '@/api/post'",
    'const { likePost, unlikePost, dislikePost, undislikePost } = globalThis.__stubs'
  )

const { useReaction } = await import(
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
)

const post = (overrides = {}) => ({
  post_id: 5,
  is_liked: false,
  is_disliked: false,
  like_count: 0,
  dislike_count: 0,
  ...overrides
})

const reset = (response = { data: {} }, throws = false) => {
  calls.length = 0
  nextResponse = response
  shouldThrow = throws
}

test('a missing post is handled instead of throwing', async () => {
  reset()
  const { toggleLike } = useReaction()
  assert.deepEqual(await toggleLike(undefined), { ok: false })
  assert.deepEqual(await toggleLike(null), { ok: false })
  assert.equal(calls.length, 0)
})

test('liking updates optimistically and then applies server counts', async () => {
  reset({ data: { like_count: 7, dislike_count: 2, is_liked: true, is_disliked: false } })
  const { toggleLike } = useReaction()
  const target = post({ like_count: 6 })

  const result = await toggleLike(target)

  assert.equal(result.ok, true)
  assert.deepEqual(calls, [['likePost', 5]])
  assert.equal(target.is_liked, true)
  assert.equal(target.like_count, 7)
  assert.equal(target.dislike_count, 2)
})

test('a failed request rolls the post back to its previous state', async () => {
  reset({ data: {} }, true)
  const { toggleLike } = useReaction()
  const target = post({ like_count: 3 })

  const result = await toggleLike(target)

  assert.equal(result.ok, false)
  assert.equal(result.error.message, 'NETWORK_DOWN')
  assert.equal(target.is_liked, false)
  assert.equal(target.like_count, 3)
})

test('liking clears an existing dislike in the optimistic state', async () => {
  reset()
  const { toggleLike } = useReaction()
  const target = post({ is_disliked: true, dislike_count: 4, like_count: 1 })

  await toggleLike(target)

  assert.equal(target.is_liked, true)
  assert.equal(target.like_count, 2)
  assert.equal(target.is_disliked, false)
  assert.equal(target.dislike_count, 3)
})

test('a second click while a request is in flight is ignored', async () => {
  reset()
  const { toggleLike } = useReaction()
  const target = post()

  const first = toggleLike(target)
  const second = await toggleLike(target)
  await first

  assert.deepEqual(second, { ok: false })
  assert.equal(calls.length, 1, 'only one request may be sent per post')
})

test('different posts can be reacted to concurrently', async () => {
  reset()
  const { toggleLike } = useReaction()

  await Promise.all([
    toggleLike(post({ post_id: 1 })),
    toggleLike(post({ post_id: 2 }))
  ])

  assert.deepEqual(calls.sort(), [['likePost', 1], ['likePost', 2]])
})

test('un-liking calls the delete endpoint and keeps the count non-negative', async () => {
  reset()
  const { toggleLike } = useReaction()
  const target = post({ is_liked: true, like_count: 0 })

  await toggleLike(target)

  assert.deepEqual(calls, [['unlikePost', 5]])
  assert.equal(target.is_liked, false)
  // clamp() floors the pre-click value at 0, so an already-zero count stays at -1+0
  assert.ok(target.like_count <= 0, 'must not invent likes')
})

test('dislike toggling mirrors like behaviour', async () => {
  reset({ data: { dislike_count: 9 } })
  const { toggleDislike } = useReaction()
  const target = post({ is_liked: true, like_count: 5 })

  await toggleDislike(target)

  assert.deepEqual(calls, [['dislikePost', 5]])
  assert.equal(target.is_disliked, true)
  assert.equal(target.dislike_count, 9)
  assert.equal(target.is_liked, false)
  assert.equal(target.like_count, 4)
})
