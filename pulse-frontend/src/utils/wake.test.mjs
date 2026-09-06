import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  buildWakeUpdatePayload,
  describeWakeError,
  formatWakeWindow,
  hasWakeChanges,
  normalizeWakeValue,
  validateWakeSettings
} from './wake.js'

test('unset values normalize to null, numeric strings to integers', () => {
  assert.equal(normalizeWakeValue(''), null)
  assert.equal(normalizeWakeValue(null), null)
  assert.equal(normalizeWakeValue(undefined), null)
  assert.equal(normalizeWakeValue('7'), 7)
  assert.equal(normalizeWakeValue(7.9), 7)
})

test('an entirely unset wake form is valid - all three fields are optional', () => {
  assert.equal(validateWakeSettings({}), null)
  assert.equal(
    validateWakeSettings({ wake_hours_start: '', wake_hours_end: null, daily_wake_budget: '' }),
    null
  )
})

test('hours must be 0-23 and the budget 1-24', () => {
  assert.equal(validateWakeSettings({ wake_hours_start: 0, wake_hours_end: 23 }), null)
  assert.ok(validateWakeSettings({ wake_hours_start: -1 }))
  assert.ok(validateWakeSettings({ wake_hours_start: 24 }))
  assert.ok(validateWakeSettings({ wake_hours_end: 24 }))
  assert.equal(validateWakeSettings({ daily_wake_budget: 1 }), null)
  assert.equal(validateWakeSettings({ daily_wake_budget: 24 }), null)
  assert.ok(validateWakeSettings({ daily_wake_budget: 0 }))
  assert.ok(validateWakeSettings({ daily_wake_budget: 25 }))
  assert.ok(validateWakeSettings({ daily_wake_budget: 'abc' }))
})

test('an overnight window and a start == end window are both accepted', () => {
  // 22 -> 6 is a night-owl routine; 8 -> 8 means active all day
  assert.equal(validateWakeSettings({ wake_hours_start: 22, wake_hours_end: 6 }), null)
  assert.equal(validateWakeSettings({ wake_hours_start: 8, wake_hours_end: 8 }), null)
})

test('only changed fields are submitted', () => {
  const original = { wake_hours_start: 8, wake_hours_end: 22, daily_wake_budget: 6 }
  assert.deepEqual(buildWakeUpdatePayload({ ...original }, original), {})
  assert.deepEqual(
    buildWakeUpdatePayload({ ...original, wake_hours_end: 23 }, original),
    { wake_hours_end: 23 }
  )
  assert.equal(hasWakeChanges({ ...original }, original), false)
  assert.equal(hasWakeChanges({ ...original, daily_wake_budget: 9 }, original), true)
})

test('a legacy deployment (all wake fields null) submits nothing', () => {
  const original = { wake_hours_start: null, wake_hours_end: null, daily_wake_budget: null }
  assert.deepEqual(buildWakeUpdatePayload({ ...original }, original), {})
})

test('a first-time setting on a legacy-null field is submitted', () => {
  const original = { wake_hours_start: null, wake_hours_end: null, daily_wake_budget: null }
  assert.deepEqual(
    buildWakeUpdatePayload({ wake_hours_start: 9, wake_hours_end: null, daily_wake_budget: null }, original),
    { wake_hours_start: 9 }
  )
})

test('clearing a field back to unset is not a submitted change', () => {
  // The endpoint has no way to erase a stored bound, so a cleared select is a no-op
  const original = { wake_hours_start: 8, wake_hours_end: 22, daily_wake_budget: 6 }
  assert.deepEqual(
    buildWakeUpdatePayload({ wake_hours_start: null, wake_hours_end: 22, daily_wake_budget: 6 }, original),
    {}
  )
})

test('string form values compare equal to their stored numbers', () => {
  const original = { wake_hours_start: 8, wake_hours_end: 22, daily_wake_budget: 6 }
  assert.deepEqual(buildWakeUpdatePayload({ ...original, daily_wake_budget: '6' }, original), {})
  assert.deepEqual(buildWakeUpdatePayload({ ...original, daily_wake_budget: '7' }, original), {
    daily_wake_budget: 7
  })
})

test('the window is rendered as a padded range, all-day, or a placeholder', () => {
  assert.equal(formatWakeWindow(8, 22), '08:00-22:00')
  assert.equal(formatWakeWindow(22, 6), '22:00-06:00')
  assert.equal(formatWakeWindow(8, 8), '全天')
  assert.equal(formatWakeWindow(null, 22), '--')
  assert.equal(formatWakeWindow(null, null, 'N/A'), 'N/A')
})

test('20009 is reported as the wake queue not being enabled', () => {
  assert.match(describeWakeError({ code: 20009, status: 409 }), /唤醒队列/)
  assert.match(describeWakeError({ code: 99900 }), /不合法/)
  assert.equal(describeWakeError({ code: 30001, message: 'BOOM' }), 'BOOM')
  assert.equal(describeWakeError({}), 'UPDATE_FAILED')
})
