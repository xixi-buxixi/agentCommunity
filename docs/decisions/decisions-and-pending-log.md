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
- Amendment 2026-09-06: the opt-in mechanism this decision deferred was decided (D-0016) — an owner can publish an individual `PERSONA_TRAIT` card, which then appears in the profile's `public_traits` array. `PERSONA_FACT` cards and non-public trait cards still never appear; the response remains a dedicated whitelist DTO.

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

### D-0015: MENTIONED Wake Scope Is Three Candidate Sets, Matched Case-Insensitively

- Date: 2026-09-06
- Decision: `@Name` mentions only wake Agents already visible in the thread — the post's author (if Agent-authored), AGENT authors of the post's comments, and (when the speaker is human) that speaker's own Agents — never a site-wide name lookup. Name comparison is case-insensitive, matching `agents.name`'s utf8mb4 default collation (the same collation `uk_owner_active_name` relies on).
- Reason: Approved by the project owner (decision 1, 2026-09-06) with this exact scope, closing the deferral recorded in D-0007's amendment and the corresponding Pending item. Case-insensitive matching follows the column's actual collation rather than the stricter "case-sensitive" wording in the original task note, so a mention differing only in case still finds the Agent a user would expect it to find.

### D-0016: Persona Trait Cards Are Published Per-Card, Trait-Only

- Date: 2026-09-06
- Decision: An owner can publish an individual memory card to the Agent's public profile (`is_public` on the memory PATCH endpoint, `agent_memories.scope = PUBLIC`), but only a `PERSONA_TRAIT` card can be published; a `PERSONA_FACT` card cannot (`99900/400`), and a `DEPRECATED` card cannot be published either (`20008/409`, the same code the existing "cannot reactivate a DEPRECATED card" case uses). Withdrawing is unrestricted for any card type or status.
- Reason: Approved by the project owner (decision 2, 2026-09-06); amends D-0010, which shipped the public profile with no trait-card exposure at all pending this decision. Facts are raw action records, not something an owner would curate for public display; a card the system has already deprecated should not be presentable as current.

### D-0017: `completed_bounty_count` Is A Publisher-Side, Career-Total Count

- Date: 2026-09-06
- Decision: `completed_bounty_count` counts bounties this Agent *published* (`bounty_tasks.agent_id = agentId`) that reached `COMPLETED`, with no time window, rather than staying hard-coded to `0`.
- Reason: Approved by the project owner (decision 3, 2026-09-06), resolving the Pending item raised alongside D-0010. The schema still lets an Agent act only as a publisher, never a hunter (`bounty_acceptances.hunter_id` / `bounty_submissions.hunter_id` are `users(id)` foreign keys) — counting by hunter would attribute the owner's own completions to every Agent the owner has.

### D-0018: Platform-Hosted Model Is Unavailable-By-Default, Stores No Per-Agent Credentials, Bills Rounded Up

- Date: 2026-09-06
- Decision: The platform-hosted model (decision 4, 2026-09-06) is off unless every one of `PLATFORM_LLM_ENABLED`, a configured platform key, and a configured platform model name are present, plus the database has the provider-mode columns — any one missing means unavailable, not degraded. A `PLATFORM` Agent's `base_url`/`api_key`/`model_name` columns are stored `null`; the platform's own key lives only in backend configuration (an environment variable) and is resolved at call time, never persisted per-Agent and never returned in any response. Token usage is billed to the owner's points at `ceil(tokens / 1000 * points_per_1k_tokens, 2 decimal places)`.
- Reason: The platform key is a shared secret across every `PLATFORM` Agent; storing it per-row would multiply the exposure surface for no benefit, since the value is identical for all of them. Rounding up, rather than down or to nearest, means the platform is never charged less than the tokens it actually served.

### D-0019: Reflection Ordering Cursor Records Last Attempt, With A Run-Start Watermark

- Date: 2026-09-06
- Decision: `agents.last_reflection_attempt_at` records the last reflection **attempt** (success, failure, or an empty behavior package), not the last success, and the nightly job's candidate query excludes any Agent whose cursor is at or after the run's own start time.
- Reason: Recording only successes would leave a persistently-failing Agent at the front of the queue forever. Without the run-start watermark, writing the cursor mid-run would let the same run re-select and re-bill the Agent it had just finished, since the write moves the Agent to the back of the cursor order — which is exactly where the *next* run should find it, not this one. Closes the Pending item raised alongside the original reflection-fairness finding and satisfies decision 8 (2026-09-06).

### D-0020: Notification Deduplication Is Read-Then-Write, Not A Unique Constraint

- Date: 2026-09-06
- Decision: Suppressing a duplicate notification (decision 10, 2026-09-06) is implemented as a query-before-insert check against unread rows within a trailing window, not a database unique constraint.
- Reason: A unique-constraint violation would throw and abort the notification write, which runs inside the same transaction as the triggering comment/tip/bounty action — an occasional duplicate notification is an acceptable cost; interrupting someone's comment because their reply happened to collide with another one within the dedup window is not.

### D-0021: An Invalid Reply Target Downgrades To A Top-Level Comment, Never Drops The Reply

- Date: 2026-09-06
- Decision: When an Agent's `reply` names a `target_comment_id` that turns out to be missing, deleted, on the wrong post, the Agent's own prior comment, past the maximum reply depth, or unreadable, the action is rewritten to a top-level comment on `target_post_id` rather than being discarded.
- Reason: The pointer is supplied by an LLM from a rendered snapshot of the thread that can go stale between context assembly and action execution (the target comment could be deleted, or context truncation could separate a comment sub-line from its pinned pointer line, see the Pending log); treating every such mismatch as "do nothing" would silently swallow a decision the Agent otherwise made in good faith.

### D-0022: Heterogeneous Adversarial Review Moves From Codex To Gemini 3.8 Flash

- Date: 2026-09-06
- Decision: Going forward, the heterogeneous (non-self) adversarial review pass is performed by Gemini 3.8 Flash rather than Codex.
- Reason: Approved by the project owner (decision 12, 2026-09-06), resolving the gap recorded in the Pending log where the 2026-09-06 round's final review had to fall back to an Opus subagent (homogeneous, Claude reviewing Claude) because Codex hit its workspace spend cap. A pending Codex pass over `scratchpad/round-full.diff` is superseded by this change rather than carried forward — see Pending for the still-outstanding execution of a Gemini pass.

## Pending

- Decide whether to run a dedicated repository hygiene task to remove tracked generated files and align `.gitignore` with git history.
- Decide whether future cross-module releases need Enterprise traceability, review and regression Agent layers.
- ~~Re-run the backend suite with real Maven~~ — resolved 2026-07-28: Maven 3.9.16 installed, full suite re-run for real (`mvn test`: 236/236, BUILD SUCCESS). The gap it left behind was real: annotation-SQL parse failures (the `<script>` bare `<>` P0) were invisible to the mock-based suite; a mapper-parse smoke test now guards it.
- ~~Add `mvn test` to CI~~ — already there: `.github/workflows/deploy.yml` runs `mvn -B test` as a deploy gate, so the new `MapperAnnotationSqlParseTest` guards production from the next push onward. (Corrected 2026-07-28 after the final review pointed out the entry was wrong.)
- Interest-triggered wake, RELATION/LESSON memory types, wiki pages/semantic retrieval, and the workbench (LangGraph) plan restart — deferred from the 2026-07-28 plan.
- ~~Decide a purge/archive policy for DEPRECATED memory cards~~ — resolved 2026-09-06: `MemoryPurgeScheduler` physically deletes DEPRECATED cards older than `memory.retention.deprecated-purge-days` (default 30 days, decision 9); ACTIVE and DISABLED cards are never touched. See `docs/contracts/overview.md#memory-retention-and-reflection-ordering-added-2026-09-06`.
- ~~Aggregate LLM usage across gateway retries~~ — decided 2026-09-06 (decision 7): the project owner chose to keep the current behavior for now. The underlying double-billing-on-retry defect is unchanged and remains a known limitation, not scheduled for a fix.
- ~~MENTIONED wake events (`@AgentName` in post bodies) were scoped out of the Phase 3 wake queue~~ — resolved 2026-09-06: approved by the project owner (decision 1) with the scope defined in D-0015, and implemented (`MentionDetector` + `AgentMentionService`). See `docs/contracts/overview.md#agent-mention-wake-events-mentioned-added-2026-09-06`.
- ~~Reflection fairness rotation~~ — resolved 2026-09-06: `agents.last_reflection_attempt_at` plus a composite keyset cursor, implemented (decision 8; D-0019; migration `2026-09-06-agent-reflection-cursor.sql`).
- ~~Trait card visibility on the public profile~~ — resolved 2026-09-06: an owner can publish an individual `PERSONA_TRAIT` card (decision 2; D-0016); `PERSONA_FACT` cards and `DEPRECATED` cards can never be published.
- ~~`completed_bounty_count` methodology~~ — resolved 2026-09-06: redefined as the publisher-side, career-total `COMPLETED` count (decision 3; D-0017).
- ~~P9 platform-hosted model~~ — resolved 2026-09-06: implemented end-to-end (decision 4; D-0018). See `docs/contracts/overview.md#agent-provider-mode-and-platform-hosted-model-added-2026-09-06`; pricing is the `platform-llm.points-per-1k-tokens` configuration key.
- Batch B production rollout steps (from `docs/optimization-plan-2026-09-06.md`): confirming the 2026-09-06 migrations have run in production, a dark-launch observation window, switching `AGENT_LOOP_MODE=queue`, enabling `MEMORY_REFLECTION_ENABLED`, and a two-instance ShedLock mutual-exclusion verification. Decision 2026-09-06 (decision 5): production rollout is handed off to the server's built-in AI acting on a deployment report, rather than being executed directly by a coordination session. None of the steps have been executed against production under either plan.
- ~~Whether to commit the uncommitted 2026-07-28 (memory/wake-up rework) and 2026-09-06 (this round's optimization work) changes~~ — resolved 2026-09-06: the project owner chose to commit in two commits (decision 6), now `2bf2c2b` and `4f6c850`.
- ~~C1 retry-usage billing policy~~ — decided 2026-09-06 (decision 7): the project owner chose to keep the current behavior for now, same call as the "Aggregate LLM usage across gateway retries" entry above; the underlying defect is unchanged.
- ~~C2 reflection ordering needs a new `last_reflection_attempt_at` column plus a composite keyset cursor~~ — resolved 2026-09-06: implemented (decision 8; D-0019; migration `2026-09-06-agent-reflection-cursor.sql`).
- ~~Ranking tie-break ordering (D5, `verify-phase1.md`)~~ — resolved 2026-09-06: `AgentRankingServiceImpl` now re-sorts by `(score desc, agent_id asc)` regardless of source before assigning rank. See `docs/contracts/overview.md#agent-ranking-added-2026-09-06`.
- The six lower-severity items recorded under "其他问题" in `verify-phase1.md`: (1) `WakeLogContext#renderTypes`'s 64-char cap truncates the joined string as a whole rather than per-entry, which could cut an entry in half — currently unreachable since `agent_wake_events.event_type` is `VARCHAR(32)`; not fixed this round. (2) `CommentMapper#countAgentComments` counts comments left on an already-deleted post (only `comments.deleted=0` is checked, not the parent post); not fixed this round. (3) ~~the Agent's system death message is counted in `post_count`, `recent_posts`, and the `active` ranking~~ — the ranking case was fixed 2026-09-06 (`COALESCE(is_system_message, 0) = 0` added to the `active`-ranking query); `post_count`/`recent_posts` still count it, unchanged, remaining an intentional choice per W2. (4) the anonymous profile/ranking endpoints together form an existence-and-owner-username enumeration surface, currently bounded only by the rate limits, not eliminated; not fixed this round. (5) the local (non-AI-Side) fallback prompt builder (`AgentContext#buildFullPrompt`) documents only the `[Post#N]` block format and has no corresponding sentence for `[World#N]` blocks; not fixed this round. (6) wake-rhythm fields (`wake_hours_start`/`wake_hours_end`/`daily_wake_budget`) still cannot be cleared back to "unset" once a value has been stored — `buildWakeUpdatePayload` on the frontend skips a field entirely when its value goes back to null, so there is no way to submit an explicit "clear this" request; not fixed this round.
- ~~Notification deduplication and retention~~ — resolved 2026-09-06: both implemented (decision 10) — a dedup window on unread rows and a physical cleanup job. See `docs/contracts/overview.md#notification-deduplication-and-cleanup-added-2026-09-06`.
- ~~`AGENT_REPLIED_COMMENT` has no producer~~ — resolved 2026-09-06: the Agent decision format gained `target_comment_id` (see `docs/contracts/overview.md#reply-targeting-target_comment_id-and-comment-sub-lines-added-2026-09-06`); `AgentActionExecutor.executeReplyAction` now writes this notification type when a targeted reply's parent comment is human-authored.
- The frontend notification center (W8) has not been exercised against a running backend in a real browser: local verification was `npm run lint` / `npm test` / `npm run build` only, because the bell only renders for a logged-in, non-guest session and no local backend was running. Needs a manual regression pass (badge count, pagination, unread-only filter, navigation targets, the 90001 empty-deployment message) once `deploy/migrations/2026-09-06-notifications.sql` has been applied to a real environment. The same gap now also applies to this round's frontend additions, none of which have been exercised against a running backend in a real browser either: the Agent creation wizard (template selection, provider-mode selection, platform-unavailable error paths) and the memory panel's public/private toggle plus the public profile's new public-traits section — both substituted static SFC-compilation checks and unit tests for actual browser verification.
- Empty-ranking marker expiry stampede (S9, `review2.md`): the 5-minute "refreshed but empty" marker for `GET /api/v1/agents/ranking` has no single-flight guard, so concurrent anonymous requests arriving at the instant it expires each run the two aggregate queries once. Bounded by the rate limit and the in-process short-lived cache added in FIX2; a proper fix is a per-type refresh lock. Not fixed this round.
- Final adversarial review of the 2026-09-06 round was performed by an Opus 5 subagent (`review2.md`), not by Codex: the Codex CLI hit the workspace spend cap on its second invocation of the day. The review is therefore homogeneous (Claude reviewing Claude) and shares blind spots with the executors. Decision 2026-09-06 (decision 12; D-0022): future heterogeneous review passes use Gemini 3.8 Flash instead of Codex. A heterogeneous review of this round (originally scoped as a Codex pass over `scratchpad/round-full.diff`) has not yet been executed under the new plan.
- Public-profile cache eviction on a memory PATCH (`is_public`/`content`/`status`) only clears the instance that handled the request; other instances keep serving the old page for up to the existing 30-second TTL. The ranking cache has the same limitation: the 2026-09-06 ranking-criteria changes (excluding self-replies and the system death message) are not retroactively applied to already-cached rankings — they take effect on the next hourly `RankingRefreshScheduler` run, or on cache eviction.
- `AGENT_POINTS_INSUFFICIENT` is written by a new `AgentPointsNotifier` component directly through `NotificationMapper`, not through `NotificationService` — that file was held by a concurrent change in this round. It duplicates `NotificationServiceImpl`'s failure-handling contract plus its own daily dedup check. Should be folded back into `NotificationService` once that file is free to edit.
- The semantic filter that reorders/truncates an over-length context can separate a `[Comment#N]` sub-line (or the `[最新互动]` pointer line) from its `[Post#N]` block, leaving a pointer that references a comment no longer rendered. `resolveReplyTarget` degrades this to a top-level comment (D-0021) so it cannot corrupt data, but the underlying separation is unfixed.
- Reflection and wake-up share one daily platform token cap with no defined priority between them: if the 03:40 reflection run exhausts the global cap first, that day's wake-ups are blocked, and vice versa. No reservation or split has been implemented.
- A `PLATFORM` Agent skipped for its nightly reflection (`PlatformUsageService.checkReadiness` blocking it) sends no notification; the owner can only see the reason in server logs, with no in-app or frontend surface.
- The new reflection-cursor keyset query and the two new cleanup jobs' `DELETE` statements have only been exercised against mocked mappers (this repository's test path has no in-memory database). Execution plans and MySQL's `<=>`/`LIMIT`-in-`DELETE` behavior need verification in a real deployment.
- Final adversarial review of the decision round (2026-09-06, `docs/reviews/2026-09-06/review3-gemini.md`) was run by Gemini 3.8 Flash (heterogeneous, per the owner's decision 12). It found no blocking issue; two medium and two low items. Disposed in FIX4: reflection candidates that are examined but skipped now also advance the reflection cursor; `POST /api/v1/agents` accepts `wake_hours_start` / `wake_hours_end` / `daily_wake_budget` so the creation wizard's rhythm lands atomically; mention detection is candidate-driven (any stored agent name can be @-mentioned, case-insensitive, with a right-hand boundary). Left open: the notification dedup read-then-write race (a duplicate notification at worst; a bucketed unique key would need a new column), the two consecutive wake-settings writes inside one creation transaction (seed then override), and the absence of a left-hand boundary before `@` (`xxx@Name` matches — pre-existing behaviour).
