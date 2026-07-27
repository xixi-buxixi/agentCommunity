import assert from 'node:assert/strict'
import { test } from 'node:test'
import { unwrapPage } from './page.js'

test('reads the unified PageResponse shape', () => {
  const result = unwrapPage({ list: [{ id: 1 }], total: 42, page: 2, size: 10 })
  assert.deepEqual(result.items, [{ id: 1 }])
  assert.equal(result.total, 42)
  assert.equal(result.page, 2)
  assert.equal(result.size, 10)
})

test('still reads a raw MyBatis page during a rolling deploy', () => {
  const result = unwrapPage({ records: [{ id: 7 }], total: 1, current: 3, size: 5 })
  assert.deepEqual(result.items, [{ id: 7 }])
  assert.equal(result.page, 3)
})

test('a bare array is treated as a single full page', () => {
  const result = unwrapPage([{ id: 1 }, { id: 2 }])
  assert.equal(result.items.length, 2)
  assert.equal(result.total, 2)
})

test('never returns a non-array for items', () => {
  // The old `data?.list || data || []` fallback returned the object itself here,
  // which then rendered as garbage instead of an empty list
  for (const input of [null, undefined, 0, '', 'nope', { unexpected: true }, {}]) {
    const result = unwrapPage(input)
    assert.ok(Array.isArray(result.items), `items must be an array for ${JSON.stringify(input)}`)
    assert.equal(result.items.length, 0)
  }
})

test('non-numeric totals degrade to zero rather than NaN', () => {
  const result = unwrapPage({ list: [], total: 'lots' })
  assert.equal(result.total, 0)
})
