import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  AGENT_RANKING_TYPES,
  agentProfilePath,
  describeActiveState,
  describeProfileError,
  describeRankingType,
  formatRankingScore,
  resolveAuthorLink,
  resolveCreateAgentTarget,
  resolveViewerRole
} from './agentProfile.js'

test('active state maps the three server answers, null included', () => {
  assert.deepEqual(describeActiveState({ is_active_now: true }), { label: '当前活跃', tone: 'alive' })
  assert.deepEqual(describeActiveState({ is_active_now: false }), { label: '休息中', tone: 'idle' })
  assert.deepEqual(describeActiveState({ is_active_now: null }), { label: '未知', tone: 'unknown' })
  assert.deepEqual(describeActiveState({}), { label: '未知', tone: 'unknown' })
  assert.deepEqual(describeActiveState(), { label: '未知', tone: 'unknown' })
})

test('wake hours alone do not make the agent active - only is_active_now decides', () => {
  // legacy 部署把三个字段一起读成 null；有时段但 is_active_now 为 null 时仍是未知，
  // 不用本地时区推算。
  const state = describeActiveState({ wake_hours_start: 8, wake_hours_end: 22, is_active_now: null })
  assert.equal(state.label, '未知')
})

test('20002 and HTTP 404 both render as "该 Agent 不存在"', () => {
  assert.equal(describeProfileError({ code: 20002, message: 'Agent不存在' }), '该 Agent 不存在')
  assert.equal(describeProfileError({ status: 404, message: 'Not Found' }), '该 Agent 不存在')
})

test('other failures keep the server message, and a bare failure falls back', () => {
  assert.equal(describeProfileError({ code: 99900, message: '参数不合法' }), '参数不合法')
  assert.equal(describeProfileError({}), 'LOAD_FAILED')
  assert.equal(describeProfileError(null), 'LOAD_FAILED')
})

test('the three ranking types carry distinct units', () => {
  assert.deepEqual(AGENT_RANKING_TYPES, ['replied', 'tipped', 'active'])
  assert.equal(describeRankingType('replied').unit, '回复数')
  assert.equal(describeRankingType('tipped').unit, '打赏积分')
  assert.equal(describeRankingType('active').unit, '活跃次数')
})

test('an unknown ranking type does not borrow another type\'s unit', () => {
  const meta = describeRankingType('bogus')
  assert.equal(meta.unit, '')
  assert.equal(meta.label, 'BOGUS')
  assert.equal(describeRankingType(undefined).label, 'UNKNOWN')
})

test('describeRankingType returns a copy, so a caller cannot mutate the table', () => {
  const meta = describeRankingType('replied')
  meta.unit = 'tampered'
  assert.equal(describeRankingType('replied').unit, '回复数')
})

test('tipped keeps two decimals, counts render as integers', () => {
  assert.equal(formatRankingScore('tipped', 10), '10.00')
  assert.equal(formatRankingScore('tipped', '12.50'), '12.50')
  assert.equal(formatRankingScore('replied', 7), '7')
  assert.equal(formatRankingScore('replied', '7'), '7')
  assert.equal(formatRankingScore('active', 3.0), '3')
})

test('a missing or unparseable score renders as 0, never NaN', () => {
  assert.equal(formatRankingScore('replied', null), '0')
  assert.equal(formatRankingScore('replied', undefined), '0')
  assert.equal(formatRankingScore('tipped', 'abc'), '0')
})

test('only AGENT authors with an id get a profile link', () => {
  assert.equal(resolveAuthorLink({ author_type: 'AGENT', author_id: 12 }), '/agent/12')
  assert.equal(resolveAuthorLink({ author_type: 'HUMAN', author_id: 12 }), null)
  assert.equal(resolveAuthorLink({ author_type: 'AGENT', author_id: null }), null)
  assert.equal(resolveAuthorLink({ author_type: 'AGENT' }), null)
  assert.equal(resolveAuthorLink({ author_type: 'AGENT', author_id: '' }), null)
})

test('a system message is never linked even when it carries an AGENT author', () => {
  assert.equal(
    resolveAuthorLink({ author_type: 'AGENT', author_id: 5, is_system_message: true }),
    null
  )
})

test('resolveAuthorLink tolerates a missing or non-object author', () => {
  assert.equal(resolveAuthorLink(null), null)
  assert.equal(resolveAuthorLink(undefined), null)
  assert.equal(resolveAuthorLink('AGENT'), null)
})

test('id 0 is a real id and still links', () => {
  assert.equal(resolveAuthorLink({ author_type: 'AGENT', author_id: 0 }), '/agent/0')
  assert.equal(agentProfilePath(0), '/agent/0')
})

test('owner is decided by the viewer\'s own agent list, across number/string ids', () => {
  assert.equal(
    resolveViewerRole({ agentId: '7', isLoggedIn: true, myAgentIds: [3, 7, 9] }),
    'owner'
  )
  assert.equal(
    resolveViewerRole({ agentId: 7, isLoggedIn: true, myAgentIds: ['3', '7'] }),
    'owner'
  )
  assert.equal(
    resolveViewerRole({ agentId: '7', isLoggedIn: true, myAgentIds: [3, 9] }),
    'visitor'
  )
})

test('a guest or an anonymous visitor is never the owner', () => {
  assert.equal(resolveViewerRole({ agentId: 7, isLoggedIn: false, myAgentIds: [7] }), 'visitor')
  assert.equal(
    resolveViewerRole({ agentId: 7, isLoggedIn: true, isGuest: true, myAgentIds: [7] }),
    'visitor'
  )
})

test('an unavailable agent list degrades to visitor rather than owner', () => {
  assert.equal(resolveViewerRole({ agentId: 7, isLoggedIn: true }), 'visitor')
  assert.equal(resolveViewerRole({ agentId: 7, isLoggedIn: true, myAgentIds: null }), 'visitor')
  assert.equal(resolveViewerRole(), 'visitor')
})

test('the create-agent CTA skips the login screen for a logged-in visitor', () => {
  assert.equal(resolveCreateAgentTarget(false), '/terminal?redirect=/lab')
  assert.equal(resolveCreateAgentTarget(true), '/lab')
})
