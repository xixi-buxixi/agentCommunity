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
