# Summary Agent Memory Index

> This file tracks all memories saved by the Summary Agent.

## Project Status

- [Phase 1 Progress Tracker](project_phase1_progress.md) - Overall progress: 90%, ALL agents DONE
- [Phase 1 Final Summary](phase1_final_summary.md) - Complete Phase 1 summary, 94 files, architecture, decisions
- [Phase 1 Java Backend Complete](project_phase1_java_complete.md) - Java backend finished 60 files, core engine implemented
- [Phase 1 Python AI Side Complete](project_phase1_python_complete.md) - Python AI side finished 14 files, LLM service ready
- [Phase 1 Frontend Complete](project_frontend_complete.md) - Vue 3 frontend finished ~20 files, industrial UI theme

## Phase 2 Planning

- [Phase 2 Task List](phase2_tasks.md) - Integration testing, Docker orchestration, production readiness
- [Startup Guide](startup_guide.md) - How to run the entire project (Docker + Manual)

## Feature Design (Phase 2 Extension)

- [Dislike & View Count Feature](dislike_view_feature_design.md) - 踩功能与浏览量功能设计 (2026-04-02)

## Development Progress (Phase 2)

- [Agent Behavior Optimization](../../done/2026-04-07-agent-behavior-optimization.md) - LIKE/DISLIKE动作实现，浏览量逻辑验证 (2026-04-07)
- [Pagination & Sorting Optimization](../../pulse-summary/OPTIMIZATION_REPORT_2026-04-11.md) - Square分页改进，Bounty/Post排序功能，过期显示优化 (2026-04-11)

## Health Check Reports

- [2026-04-11 Health Check](done/health-check-2026-04-11.md) - 24 issues found: 4 CRITICAL, 8 HIGH, 7 MEDIUM, 5 LOW
- [2026-04-11 Fix Completed](done/fix-completed-2026-04-11.md) - 11 issues fixed: 4 CRITICAL, 7 HIGH, 2 SQL migrations pending

## Cross-Agent Dependencies

- [Cross-Agent Dependency Status](project_cross_agent_dependencies.md) - Integration points, risk assessment, responsibility matrix

## Blocked Items

- ~~BLK-001: Python AI Side integration~~ - **RESOLVED** (2026-03-31)
- ~~BLK-002: Frontend Agent Lab page~~ - **RESOLVED** (2026-03-31)

**All blockers resolved. Phase 1 complete.**

## Key Decisions

1. Atomic token deduction SQL for concurrency safety
2. AES encryption for API key storage
3. 150-char truncation for context protection
4. Pre-flight death check before LLM calls
5. 30-second timeout with `ignore` fallback (Python)
6. JSON forced output via OpenAI response_format (Python)
7. 8-pattern injection detection (Python)

## Cross-Platform Bug Fix (2026-04-14)

- [Cross-Platform Fix Record](project_cross_platform_fix_2026-04-14.md) - Frontend + Python bug fix based on problem analysis, 10 issues resolved
- [Full Fix Report](../../docs/done/2026-04-14_20-10_done_cross-platform-bug-fix.md) - Detailed fix report with technical decisions
- [Executive Summary](../../docs/done/2026-04-14_summary_cross-platform-fix.md) - Quick overview of fixes and verification results

## Phase 1 Summary

| Agent | Files | Status |
|-------|-------|--------|
| Java-Backend-Agent | 60 | DONE |
| Python-AI-Side-Agent | 14 | DONE |
| Frontend-Agent | ~20 | DONE |

**Total: ~94 files**

**Phase 1 Status: Fixed - Ready for Deployment**
- All CRITICAL and HIGH issues fixed (2026-04-11)
- Remaining: 2 SQL migrations to execute manually before deployment
- Phase 2: Integration testing + Docker orchestration

**Next Action: Execute SQL migrations, then deploy services in order (Python -> Java -> Frontend)**

---
*Last updated: 2026-04-11 19:30*