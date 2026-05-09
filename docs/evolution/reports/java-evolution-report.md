# Java Evolution Report

## Scope

- Updated only `pulse-backend/src` Java backend files and this report.
- Continued the existing Controller / Service / Mapper / DTO style.
- Did not touch frontend, Python, or generated `target` files.

## Implemented

1. Agent multi-action decision support
   - `LLMClient` now parses the evolution gateway shape with top-level `actions`.
   - Keeps backward compatibility with legacy single `action`.
   - Limits executable decisions to 3.
   - Filters invalid decisions.
   - Drops same-target like/dislike conflicts in the same decision batch.

2. Unified token usage
   - Java reads `usage.total_tokens`, `total_tokens`, or `totalTokens`.
   - If total is missing, Java falls back to `prompt_tokens + completion_tokens`.
   - `AgentLoopScheduler` updates `used_tokens` once per LLM call using unified `totalTokens`.

3. Agent `create_bounty` action
   - Added `CREATE_BOUNTY` action enum and DTO fields.
   - Scheduler executes `create_bounty` through `BountyService`.
   - Owner funds are frozen through existing bounty creation semantics.
   - Added first-phase Agent limits: max 3 bounty creations per Agent per day, max 100 points per Agent bounty.

4. Bounty cancellation
   - Added `POST /api/v2/bounties/{taskId}/cancel`.
   - Only owner can cancel.
   - Allows `PENDING` and `ACCEPTED`.
   - Rejects `REVIEWING`, `COMPLETED`, `ABANDONED`, `EXPIRED`, and `CANCELLED`.
   - Cancellation sets status to `CANCELLED`, releases `pending_bounty`, writes bounty log, and writes ledger via frozen-point release.

5. Schema / DTO / enum updates
   - Added bounty statuses: `ACCEPTED`, `EXPIRED`, `CANCELLED`.
   - Added ledger type `BOUNTY_RELEASE`.
   - Added `BountyCancelRequest`.
   - Updated `schema.sql` comments for new bounty states and log action.
   - Expiry scheduler now marks expired active bounties as `EXPIRED`.

## Tests Added

- `LLMClientTest`
  - Multi-action parse and `usage.total_tokens`.
  - Fallback to `prompt_tokens + completion_tokens`.

- `BountyServiceImplTest`
  - Pending bounty cancellation releases frozen points and logs.
  - Reviewing bounty cancellation is rejected.

## Verification

- `mvn` and `mvn.cmd` are not available in this environment.
- Maven Wrapper is not present in `pulse-backend`.
- Java and javac exist, but dependency resolution/build execution requires Maven or an equivalent project runner.

## Risks / Follow-up

- Existing working tree already contained many backend modifications before this pass; this report only describes the evolution implementation added here.
- Agent bounty limits are constants in `BountyServiceImpl`; they can be moved to config later.
- Expiry release still uses the existing atomic user update path and does not add a bounty log entry in this pass.
