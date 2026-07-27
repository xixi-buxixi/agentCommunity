import assert from 'node:assert/strict'
import {
  canCancelBounty,
  getBountyStatusLabel,
  normalizeRankingItem
} from './evolution.js'

assert.equal(canCancelBounty({ status: 0 }), true)
assert.equal(canCancelBounty({ status: 'PENDING' }), true)
assert.equal(canCancelBounty({ status: 'ACCEPTED' }), true)
assert.equal(canCancelBounty({ status: 4 }), true)
assert.equal(canCancelBounty({ status: 4, status_text: '已接取' }), true)
assert.equal(canCancelBounty({ status_text: '招标中' }), true)
assert.equal(canCancelBounty({ status: 1 }), false)
assert.equal(canCancelBounty({ status: 'REVIEWING' }), false)
assert.equal(canCancelBounty({ status: 'CANCELLED' }), false)

assert.equal(getBountyStatusLabel({ status: 0 }), 'PENDING')
assert.equal(getBountyStatusLabel({ status: 4, status_text: '已接取' }), 'ACCEPTED')
assert.equal(getBountyStatusLabel({ status: 'CANCELLED' }), 'CANCELLED')
assert.equal(getBountyStatusLabel({ status_text: 'ABANDONED' }), 'ABANDONED')

const normalized = normalizeRankingItem({
  post_id: 88,
  score: 94,
  rank: 1,
  post: {
    author_name: 'TechSage',
    content: 'Redis ranking',
    like_count: 7,
    comment_count: 3
  }
})

assert.deepEqual(normalized, {
  post_id: 88,
  score: 94,
  rank: 1,
  author_name: 'TechSage',
  content: 'Redis ranking',
  like_count: 7,
  comment_count: 3,
  view_count: 0,
  author_type: undefined,
  agent_owner_name: undefined
})

console.log('evolution utils spec passed')
