import { ref } from 'vue'
import { likePost, unlikePost, dislikePost, undislikePost } from '@/api/post'

/**
 * Like / dislike handling shared by the feed and the post detail page.
 *
 * Replaces four near-identical handlers that were duplicated across two views
 * (~80 lines each). Beyond deduplication it fixes three defects those handlers had:
 *
 * 1. `posts.value.find(...)` could return undefined (post filtered out, list
 *    reloaded mid-click) and the next property write threw.
 * 2. No in-flight guard, so rapid double clicks fired several requests and the
 *    counts ended up wherever the last response happened to land.
 * 3. No optimistic update, so every tap waited a full round trip before the UI
 *    moved - very noticeable on mobile.
 *
 * The server response remains the source of truth: counts are overwritten with the
 * returned values on success and rolled back to the pre-click snapshot on failure.
 */
export function useReaction() {
  // Keyed by post id: prevents concurrent requests for the same post while still
  // allowing different posts to be reacted to in parallel.
  const pending = ref(new Set())

  const isPending = (postId) => pending.value.has(postId)

  const applyServerCounts = (post, data) => {
    if (!data) return
    if (typeof data.like_count === 'number') post.like_count = data.like_count
    if (typeof data.dislike_count === 'number') post.dislike_count = data.dislike_count
    if (typeof data.is_liked === 'boolean') post.is_liked = data.is_liked
    if (typeof data.is_disliked === 'boolean') post.is_disliked = data.is_disliked
  }

  const snapshot = (post) => ({
    is_liked: post.is_liked,
    is_disliked: post.is_disliked,
    like_count: post.like_count,
    dislike_count: post.dislike_count
  })

  const restore = (post, saved) => Object.assign(post, saved)

  const clamp = (value) => (typeof value === 'number' && value > 0 ? value : 0)

  /**
   * @param {object|null|undefined} post reactive post object (may be missing)
   * @param {'like'|'dislike'} kind
   * @returns {Promise<{ok: boolean, error?: Error}>}
   */
  const react = async (post, kind) => {
    if (!post) return { ok: false }

    const postId = post.post_id ?? post.id
    if (postId == null || isPending(postId)) return { ok: false }

    const saved = snapshot(post)
    const wasActive = kind === 'like' ? post.is_liked : post.is_disliked

    // Optimistic update, including the mutually exclusive counterpart
    if (kind === 'like') {
      post.is_liked = !wasActive
      post.like_count = clamp(saved.like_count) + (wasActive ? -1 : 1)
      if (!wasActive && saved.is_disliked) {
        post.is_disliked = false
        post.dislike_count = clamp(saved.dislike_count) - 1
      }
    } else {
      post.is_disliked = !wasActive
      post.dislike_count = clamp(saved.dislike_count) + (wasActive ? -1 : 1)
      if (!wasActive && saved.is_liked) {
        post.is_liked = false
        post.like_count = clamp(saved.like_count) - 1
      }
    }

    pending.value = new Set(pending.value).add(postId)
    try {
      const call = kind === 'like'
        ? (wasActive ? unlikePost : likePost)
        : (wasActive ? undislikePost : dislikePost)
      const { data } = await call(postId)
      applyServerCounts(post, data)
      return { ok: true }
    } catch (err) {
      restore(post, saved)
      return { ok: false, error: err }
    } finally {
      const next = new Set(pending.value)
      next.delete(postId)
      pending.value = next
    }
  }

  return {
    isPending,
    toggleLike: (post) => react(post, 'like'),
    toggleDislike: (post) => react(post, 'dislike')
  }
}
