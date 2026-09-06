# Decisions And Pending Log

## Decisions

### D-0001: Use Standard Agent Protocol Structure

- Date: 2026-06-01
- Decision: Use the Standard initialization structure from `最优项目初始化结构.md`.
- Reason: The project has clear module boundaries and needs overview/module Agent coordination, but does not currently need Enterprise audit and traceability overhead.

### D-0002: Use Existing Module Roots For Module Navigation

- Date: 2026-06-01
- Decision: Place module `agent.md` files in `pulse-backend`, `pulse-frontend` and `pulse-ai-side`.
- Reason: The repository is already organized as a three-module monorepo; adding a parallel root `src/<module_name>/agent.md` would split stable navigation away from real module roots.

### D-0003: Keep Dynamic State In `agentsPrompt/**/tasks.md`

- Date: 2026-06-01
- Decision: Do not duplicate task boards in root `agent.md` or module `agent.md`.
- Reason: The source structure requires one dynamic state source and stable navigation must stay low-churn.

### D-0004: Do Not Clean Existing Tracked Build Or Cache History In This Task

- Date: 2026-06-01
- Decision: Leave existing tracked vendor, cache, IDE and run-log files untouched.
- Reason: Repository hygiene cleanup is separate from Standard protocol initialization and would increase review risk.

### D-0005: Memory System Before Workbench

- Date: 2026-07-28
- Decision: Build the agent memory system and wake-up overhaul first; the workbench (LangGraph orchestration) is postponed to a later plan. Execution plan: `docs/goal-memory-and-wakeup-plan-2026-07-28.md`.
- Reason: The project's positioning is a self-evolving, human-like agent community. The self-evolution loop (act -> remember -> act differently) depends on memory, not on the workbench; the workbench is one consumer of memory.

### D-0006: Memory Scope And Write Strategy

- Date: 2026-07-28
- Decision: Start with persona-consistency memory (`PERSONA_FACT` + `PERSONA_TRAIT`) on a single extensible `agent_memories` card table (RELATION/LESSON later as new memory_type values; page_id/namespace reserved for the full LLM-WIKI). Hybrid write path: structured facts are code-generated per action (no LLM), persona traits are distilled by a low-frequency batch reflection call. View/disable/correct ships in Phase 1 as a hard brake against memory pollution.
- Reason: Persona memory is the cheapest visible win for the human-like positioning; injected bad memories self-reinforce, so the manual brake cannot come later.

### D-0007: Wake-Up Model - Event Wake Plus Per-Agent Rhythm

- Date: 2026-07-28
- Decision: Replace the global 12h batch with a per-agent wake queue (`next_wake_at` with active hours and jitter) plus event-driven wake (replied/mentioned/tipped, with per-agent debounce and a daily wake budget). Interest-triggered wake is postponed. A `legacy|queue` config switch preserves the old behavior for rollback.
- Amendment 2026-07-28: replied/commented/tipped shipped (including agent-to-agent); MENTIONED (`@AgentName`) was deferred by the coordinator during implementation because mention detection needs name-matching infrastructure. This narrows the decision as originally approved and still needs the project owner's sign-off — see Pending.
- Reason: The metronome batch makes agents feel like punch-clock robots and spends tokens on cold timeline scans instead of live interactions; the budget cap keeps total cost at or below the old model.

### D-0008: Fail Loudly When agent_memories Is Missing

- Date: 2026-07-28
- Decision: If the `agent_memories` table is absent, hot-path writes degrade to a warning (agent actions unaffected) but the memory REST endpoints fail with 500; no SchemaCapabilities-style silent fallback. Production databases without DDL privileges must apply `deploy/migrations/2026-07-28-agent-memories.sql`.
- Reason: An empty-page fallback would mask a real deployment fault for a user-facing management feature whose whole point is trust in what the agent remembers.

### D-0009: Wake Events Are At-Most-Once; Budget Errs Toward Under-Waking

- Date: 2026-07-28
- Decision: A consumed wake event is marked PROCESSED before the LLM call and never returns to PENDING — a crash loses at most one response but can never double-charge the owner. When marking fails after the wake slot was claimed, the slot is released (compensating decrement); when compensation itself fails, the agent simply wakes one time fewer that day. Budget-exhausted events stay PENDING for the next day (interactions are worth delaying, not dropping) and expire after 24h.
- Reason: The wake queue's money gate (claimWakeSlot) is the only thing standing between a popular agent and a drained token budget; every failure path must resolve toward "fewer wakes", never "free or duplicate LLM calls".

### D-0010: Public Profile Uses A Whitelist DTO And Omits Trait Cards This Round

- Date: 2026-09-06
- Decision: The Agent public profile endpoint (`GET /api/v1/agents/{agent_id}/profile`) returns a dedicated `AgentPublicProfileResponse`, never a trimmed `AgentDetailResponse`, and does not return any memory or trait card in this round.
- Reason: `agent_memories` rows are `scope=SELF` by default and must not be exposed to an anonymous caller; whether trait cards should ever be public, and under what opt-in mechanism, is a separate product decision the project owner has not made (see Pending). A dedicated DTO also keeps future owner-console fields from silently leaking into the anonymous response.

### D-0011: Daily-Report Context Injection Defaults Off, Queue-Mode-Only, First Wake-Up Of The Day Only

- Date: 2026-09-06
- Decision: `hot-news.context.enabled` defaults to **false**. Even when enabled, the `[World#N]` block is only injected for a queue-mode wake-up (`wake_reason != LEGACY_BATCH`) that is the Agent's first successful wake-up of the current calendar day; legacy-mode batch wake-ups never inject it.
- Reason: Enabling this by default would change every Agent's prompt and cost immediately without an observation period; capping it to one injection per Agent per day keeps the added cost bounded and predictable. Confirmed by adversarial-review item 8 in the 2026-09-06 plan review.

### D-0012: Wake Reason Is Two Columns, Not One

- Date: 2026-09-06
- Decision: The wake-up reason recorded on `agent_logs` is split into `wake_reason` (`RHYTHM` / `EVENT` / `LEGACY_BATCH`) and `wake_event_types` (a deduplicated, alphabetically sorted, comma-joined list of the event types consumed by that wake-up), rather than one column trying to carry both.
- Reason: A single `wake_reason` column cannot represent "woken by several merged interaction events at once" (an Agent can be woken by a COMMENTED and a TIPPED event in the same tick). Confirmed by adversarial-review item 5 in the 2026-09-06 plan review.

### D-0013: Notifications And Wake Events Are Mutually Exclusive By Recipient Type

- Date: 2026-09-06
- Decision: When a post or comment target of a reply is Agent-authored, only a wake event is queued for the Agent (no notification is written); when the target is human-authored, only a notification is written for the human (no wake event). This applies to Agent-to-Agent replies as well.
- Reason: Agents have no inbox and humans have no wake queue, so the two mechanisms are naturally partitioned by the recipient's type; writing both for the same event would be redundant and would risk the two records drifting out of sync.

### D-0014: Parallel Backend Execution Uses Repository Copies Plus Patch Merge

- Date: 2026-09-06
- Decision: When multiple backend execution sessions work in parallel, each works in its own repository copy (a separate worktree with a baseline snapshot commit) and delivers a `git diff HEAD`; the coordinator merges the diffs serially and runs the full test suite once, rather than having several sessions run `mvn` concurrently against one shared working tree.
- Reason: Concurrent `mvn` runs against a shared, uncommitted working tree produce untrustworthy results — one session's in-flight edits corrupt another's compile/test run. Confirmed by adversarial-review item 2 in the 2026-09-06 plan review.

## Pending

- Decide whether to run a dedicated repository hygiene task to remove tracked generated files and align `.gitignore` with git history.
- Decide whether future cross-module releases need Enterprise traceability, review and regression Agent layers.
- ~~Re-run the backend suite with real Maven~~ — resolved 2026-07-28: Maven 3.9.16 installed, full suite re-run for real (`mvn test`: 236/236, BUILD SUCCESS). The gap it left behind was real: annotation-SQL parse failures (the `<script>` bare `<>` P0) were invisible to the mock-based suite; a mapper-parse smoke test now guards it.
- ~~Add `mvn test` to CI~~ — already there: `.github/workflows/deploy.yml` runs `mvn -B test` as a deploy gate, so the new `MapperAnnotationSqlParseTest` guards production from the next push onward. (Corrected 2026-07-28 after the final review pointed out the entry was wrong.)
- Interest-triggered wake, RELATION/LESSON memory types, wiki pages/semantic retrieval, and the workbench (LangGraph) plan restart — deferred from the 2026-07-28 plan.
- Decide a purge/archive policy for DEPRECATED memory cards: retention only marks cards DEPRECATED (kept for audit), so total `agent_memories` rows grow with agent activity. Acceptable at current scale; revisit before the community grows.
- Aggregate LLM usage across gateway retries: the shared AI Side client retries on timeout, so the provider may bill twice while only the last attempt's usage is reported and charged. Pre-existing behavior affecting both decision and reflection paths (flagged by the 2026-07-28 Phase 2 adversarial review, deferred as a platform-level fix).
- MENTIONED wake events (`@AgentName` in post bodies) were scoped out of the Phase 3 wake queue by the coordinator: mention detection needs name-matching infrastructure, same family as interest-triggered wake. The plan and D-0007 as approved DID include mentions, so this descope **awaits the project owner's explicit decision**: approve the deferral, or schedule the implementation. The event list is otherwise implemented (replied/commented/tipped, including agent-to-agent).
- Reflection fairness rotation: `max-agents-per-run` (500) counts actual reflection attempts, but when the number of sustained-active agents exceeds it, lower agent IDs are always served first across days. Theoretical at current scale; needs a rotating cursor or least-recently-reflected ordering before the community approaches that size (flagged by the 2026-07-28 Phase 2 round-2 review).
- Trait card visibility on the public profile: whether trait (`PERSONA_TRAIT`) cards should ever be shown on `GET /api/v1/agents/{agent_id}/profile`, and if so under what owner opt-in mechanism (per-card, per-Agent, or a global default). Deferred with D-0010; the current response never includes them.
- `completed_bounty_count` methodology: the public profile field is hard-coded to `0` because the current schema lets an Agent act only as a bounty publisher, never as a hunter (`bounty_acceptances.hunter_id` / `bounty_submissions.hunter_id` are both `users(id)` foreign keys). Needs a project-owner decision: keep it `0`, redefine it as the publisher-side "bounties this Agent posted that reached COMPLETED" count, or wait until Agents can accept bounties.
- P9 platform-hosted model: whether to offer a platform-provided LLM credential billed through points, and if so the pricing model. No implementation started.
- Batch B production rollout steps (from `docs/optimization-plan-2026-09-06.md`): confirming the 2026-09-06 migrations have run in production, a dark-launch observation window, switching `AGENT_LOOP_MODE=queue`, enabling `MEMORY_REFLECTION_ENABLED`, and a two-instance ShedLock mutual-exclusion verification. None of this has been executed against production.
- Whether to commit the uncommitted 2026-07-28 (memory/wake-up rework) and 2026-09-06 (this round's optimization work) changes: both rounds were developed and merged locally without any `git commit`; committing, and when, is a project-owner decision.
- C1 retry-usage billing policy: aggregating usage across the AI Side gateway's retries needs a decision on how a timed-out attempt that the provider may still have billed should be charged — deferred pending that policy call (see the existing "Aggregate LLM usage across gateway retries" entry above for the underlying defect).
- C2 reflection ordering needs a new `last_reflection_attempt_at` column plus a composite keyset cursor: switching `MemoryReflectionScheduler`'s candidate order to "longest since last reflection first" cannot be layered onto the existing id-only keyset pagination without one, since sorting by attempt time breaks a cursor defined purely on id. Deferred pending that schema addition (see the existing "Reflection fairness rotation" entry above for the underlying defect).
- Ranking tie-break ordering (D5, `verify-phase1.md`): the Redis-cache read path and the MySQL fallback path for `GET /api/v1/agents/ranking` order same-score members differently — Redis Sorted Set reverse-range ties break by descending member (agent id) string order, MySQL ties break by ascending `agent_id`. The same underlying data can render a different rank order depending on whether the request hit cache or fell back to MySQL. Not fixed this round; needs the response-building step to re-sort `(score desc, agent_id asc)` uniformly regardless of source.
- The six lower-severity items recorded under "其他问题" in `verify-phase1.md` (not fixed this round): (1) `WakeLogContext#renderTypes`'s 64-char cap truncates the joined string as a whole rather than per-entry, which could cut an entry in half — currently unreachable since `agent_wake_events.event_type` is `VARCHAR(32)`; (2) `CommentMapper#countAgentComments` counts comments left on an already-deleted post (only `comments.deleted=0` is checked, not the parent post); (3) the Agent's system death message is counted in `post_count`, `recent_posts`, and the `active` ranking (the first two are an intentional choice per W2, the ranking case was not previously called out); (4) the anonymous profile/ranking endpoints together form an existence-and-owner-username enumeration surface, currently bounded only by the new rate limits, not eliminated; (5) the local (non-AI-Side) fallback prompt builder (`AgentContext#buildFullPrompt`) documents only the `[Post#N]` block format and has no corresponding sentence for `[World#N]` blocks; (6) wake-rhythm fields (`wake_hours_start`/`wake_hours_end`/`daily_wake_budget`) still cannot be cleared back to "unset" once a value has been stored — `buildWakeUpdatePayload` on the frontend skips a field entirely when its value goes back to null, so there is no way to submit an explicit "clear this" request (also recorded under Decisions/Pending from the 2026-07-28 round).
- Notification deduplication and retention: consecutive replies from the same actor on the same target currently produce one notification row each (no `dedup_key`-style collapsing like `agent_wake_events` has); and the write-side 90-day-retention-for-read-rows policy described in `deploy/migrations/2026-09-06-notifications.sql`'s header comment is not implemented as a scheduled job. Both are acceptable at current scale; revisit if the inbox starts to look like spam or the table grows unchecked.
- `AGENT_REPLIED_COMMENT` has no producer: it requires the Agent action-decision format to carry a target comment id (currently `AgentActionDecision` only carries a target post id, so an Agent's reply is always a top-level comment). Needs a decision on whether/how to extend the decision format before this notification type can ever fire.
- The frontend notification center (W8) has not been exercised against a running backend in a real browser: local verification was `npm run lint` / `npm test` / `npm run build` only, because the bell only renders for a logged-in, non-guest session and no local backend was running. Needs a manual regression pass (badge count, pagination, unread-only filter, navigation targets, the 90001 empty-deployment message) once `deploy/migrations/2026-09-06-notifications.sql` has been applied to a real environment.
- Empty-ranking marker expiry stampede (S9, `review2.md`): the 5-minute "refreshed but empty" marker for `GET /api/v1/agents/ranking` has no single-flight guard, so concurrent anonymous requests arriving at the instant it expires each run the two aggregate queries once. Bounded by the rate limit and the in-process short-lived cache added in FIX2; a proper fix is a per-type refresh lock. Not fixed this round.
- Final adversarial review of the 2026-09-06 round was performed by an Opus 5 subagent (`review2.md`), not by Codex: the Codex CLI hit the workspace spend cap on its second invocation of the day. The review is therefore homogeneous (Claude reviewing Claude) and shares blind spots with the executors; a heterogeneous Codex pass over `scratchpad/round-full.diff` remains outstanding once the cap is lifted.
