# Pulse Evolution Overview Report

## Scope

- Read the evolution requirement, roadmap, and API documents in `docs/evolution`.
- Dispatched four agents: frontend, Java backend, Python AI-side, and overview review.
- Integrated the three implementation reports and ran available verification.
- Branch creation was attempted but blocked by `.git/refs/heads` permission denial in the current sandbox, so the work remains on the current working tree.

## Implemented By Area

### Python AI-side

- Upgraded the LLM decision contract to support top-level `actions`.
- Kept legacy `action`, `target_post_id`, and `content` fields for backward compatibility.
- Added validation for `post`, `reply`, `like`, `dislike`, `ignore`, and `create_bounty`.
- Added token usage normalization: provider `usage.total_tokens`, then prompt plus completion tokens, then local estimate.
- Updated prompt instructions and parser tests.

### Java Backend

- Added multi-action parsing and execution support in the Agent loop.
- Added `create_bounty` execution for Agent-generated bounty intent.
- Added first-phase Agent bounty limits: daily count and single reward cap.
- Added bounty cancellation API: `POST /api/v2/bounties/{taskId}/cancel`.
- Extended bounty statuses with `ACCEPTED`, `EXPIRED`, and `CANCELLED`.
- Preserved the fixed points model: `points` as total balance and `pending_bounty` as frozen amount.
- Added Java unit tests for LLM parsing and bounty cancellation, but they could not be executed because Maven is unavailable.

### Frontend

- Added bounty cancellation API wrapper and owner-side cancel control.
- Extended Agent card and monitor panels for evolution fields.
- Added helper utilities for bounty status normalization, ranking normalization, and evolution time formatting.
- Updated ranking panel compatibility with `hot`, `likes`, and `comments`.
- Added local helper tests and fixed status compatibility for backend numeric states and Chinese `status_text`.

## Cross-system Checks

- `actions[]` is now produced by Python and consumed by Java.
- Legacy single-action shape remains supported across Python and Java.
- Token usage is normalized on Python and read defensively on Java.
- Bounty cancellation is exposed by Java and wired in the frontend.
- `CANCELLED`, `EXPIRED`, and `ACCEPTED` status values are present in backend enum and frontend helpers.
- `BOUNTY_RELEASE` is present in Java enum and `schema.sql` ledger comments.

## Fixes Applied During Final Review

- Frontend status helper now maps backend numeric statuses `3/4/5/6`.
- Frontend status helper now maps backend Chinese status texts including `招标中` and `已接取`.
- `schema.sql` now documents `BOUNTY_RELEASE` in `sys_ledger.type`.

## Verification

- Python tests: `52 passed`.
- Frontend evolution helper inline Node spec: passed.
- Git whitespace check over changed implementation/report paths: passed.
- Java tests: not run because `mvn` is not installed and the project has no Maven wrapper.
- Frontend Vite build: not run because `npm` is not available; direct Node execution under `D:\My\Java` is blocked by `EPERM` realpath permissions, so helper tests were run through stdin.

## Remaining Risks

- Java compilation still needs to be run in an environment with Maven.
- Frontend build still needs to be run in an environment with npm.
- Frontend Monitor includes planned evolution endpoints for memories, context preview, and manual dispatch; these degrade to empty states if backend endpoints are not yet implemented.
- Expired bounty release uses the existing atomic release path, but the current pass did not add a bounty log entry for expiry.
- Agent bounty daily/reward limits are constants and should later move to configuration.
