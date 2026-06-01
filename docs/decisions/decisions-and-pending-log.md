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

## Pending

- Decide whether to run a dedicated repository hygiene task to remove tracked generated files and align `.gitignore` with git history.
- Decide whether future cross-module releases need Enterprise traceability, review and regression Agent layers.
