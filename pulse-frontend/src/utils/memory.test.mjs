import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  MEMORY_STATUS,
  buildMemoryQuery,
  canDisable,
  canReactivate,
  dateKeyOf,
  describeMemoryError,
  groupTraitsByDate,
  isDeprecated,
  statusLabel,
  typeLabel,
  validateMemoryContent
} from './memory.js'

test('empty filters are omitted so the backend does not see an invalid value', () => {
  const params = buildMemoryQuery({ type: '', status: '', page: 1, size: 10 })
  assert.deepEqual(params, { page: 1, size: 10 })
})

test('status 0 is a real filter, not an absent one', () => {
  const params = buildMemoryQuery({ status: '0' })
  assert.equal(params.status, 0)
})

test('paging values are clamped locally', () => {
  assert.equal(buildMemoryQuery({ page: 0 }).page, 1)
  assert.equal(buildMemoryQuery({ page: 'x' }).page, 1)
  assert.equal(buildMemoryQuery({ size: 500 }).size, 50)
  assert.equal(buildMemoryQuery().size, 20)
})

test('a memory type filter is passed through', () => {
  assert.equal(buildMemoryQuery({ type: 'PERSONA_TRAIT' }).memory_type, 'PERSONA_TRAIT')
})

test('DEPRECATED is terminal: it can neither be disabled nor restored', () => {
  const deprecated = { id: 1, status: MEMORY_STATUS.DEPRECATED }
  assert.equal(isDeprecated(deprecated), true)
  assert.equal(canReactivate(deprecated), false)
  assert.equal(canDisable(deprecated), false)
})

test('only an ACTIVE memory can be disabled and only a DISABLED one restored', () => {
  assert.equal(canDisable({ status: MEMORY_STATUS.ACTIVE }), true)
  assert.equal(canReactivate({ status: MEMORY_STATUS.ACTIVE }), false)
  assert.equal(canDisable({ status: MEMORY_STATUS.DISABLED }), false)
  assert.equal(canReactivate({ status: MEMORY_STATUS.DISABLED }), true)
})

test('labels prefer the backend text and fall back to the local table', () => {
  assert.equal(statusLabel({ status: 1, status_text: '生效中' }), '生效中')
  assert.equal(statusLabel({ status: 0 }), '已禁用')
  assert.equal(statusLabel({}), '未知')
  assert.equal(typeLabel({ memory_type: 'PERSONA_TRAIT' }), '人格特质')
  assert.equal(typeLabel({ memory_type: 'PERSONA_FACT' }), '行为事实')
  assert.equal(typeLabel({ memory_type: 'PERSONA_FACT', memory_type_text: '行为事实' }), '行为事实')
})

test('content validation matches the backend 1-500 constraint', () => {
  assert.equal(validateMemoryContent('ok'), null)
  assert.ok(validateMemoryContent(''))
  assert.ok(validateMemoryContent('   '))
  assert.ok(validateMemoryContent(null))
  assert.equal(validateMemoryContent('a'.repeat(500)), null)
  assert.ok(validateMemoryContent('a'.repeat(501)))
})

test('date keys handle both the ISO and the space-separated backend format', () => {
  assert.equal(dateKeyOf('2026-07-28T09:00:00'), '2026-07-28')
  assert.equal(dateKeyOf('2026-07-28 09:00:00'), '2026-07-28')
  assert.equal(dateKeyOf(null), '未知日期')
})

test('trait timeline keeps only PERSONA_TRAIT and orders dates newest first', () => {
  const groups = groupTraitsByDate([
    { id: 1, memory_type: 'PERSONA_TRAIT', created_at: '2026-07-26 10:00:00' },
    { id: 2, memory_type: 'PERSONA_FACT', created_at: '2026-07-28 10:00:00' },
    { id: 3, memory_type: 'PERSONA_TRAIT', created_at: '2026-07-28 08:00:00' },
    { id: 4, memory_type: 'PERSONA_TRAIT', created_at: '2026-07-28 20:00:00' }
  ])
  assert.deepEqual(groups.map((g) => g.date), ['2026-07-28', '2026-07-26'])
  // Within a day the newest card comes first
  assert.deepEqual(groups[0].items.map((m) => m.id), [4, 3])
  assert.deepEqual(groups[1].items.map((m) => m.id), [1])
})

test('trait timeline tolerates a non-array payload', () => {
  for (const input of [null, undefined, {}, 'nope']) {
    assert.deepEqual(groupTraitsByDate(input), [])
  }
})

test('business codes map to their own message', () => {
  assert.match(describeMemoryError({ code: 20007, status: 404 }), /不存在|不属于/)
  assert.match(describeMemoryError({ code: 20008, status: 409 }), /不可恢复/)
  assert.match(describeMemoryError({ code: 20003, status: 403 }), /无权/)
  assert.match(describeMemoryError({ code: 99900, status: 400 }), /无效/)
  assert.match(describeMemoryError({ code: 99904, status: 409 }), /刷新/)
})

test('a 5xx names both possible causes instead of asserting the migration is missing', () => {
  // 502/503 from a proxy and a dropped database connection reach the client with the
  // same shape as the missing-table 500 (no code in the body), so the text may not
  // point at a single remedy.
  const message = describeMemoryError({ code: null, status: 500, message: 'CONNECTION_ERROR' })
  assert.match(message, /记忆表/)
  assert.match(message, /服务异常/)
  assert.match(message, /稍后重试/)
  assert.equal(describeMemoryError({ code: null, status: 503, message: 'CONNECTION_ERROR' }), message)
})

test('an unknown failure falls back to the server message', () => {
  assert.equal(describeMemoryError({ code: 12345, status: 400, message: 'BOOM' }), 'BOOM')
  assert.equal(describeMemoryError({}), 'MEMORY_REQUEST_FAILED')
})
