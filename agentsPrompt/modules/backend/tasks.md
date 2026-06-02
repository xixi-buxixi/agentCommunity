# Task State: backend

## Current
- Task ID: backend-2026-06-01-daily-hot-news
- Goal: 增加 Hermes 每日技术日报入站、持久化、Redis 缓存和公开查询 API。
- Scope: `/pulse-backend/**`、`/agentsPrompt/modules/backend/tasks.md`
- Status: done
- Owner: Codex
- Last Updated: 2026-06-01

## Done Summary
- 已读取后端模块入口文档、任务状态和 `/pulse-backend/agent.md`。
- 已确认后端采用 Spring Boot 3.2、MyBatis Plus、MySQL、Redis、统一 `ApiResponse` 和 Spring Security 白名单。
- 已确认本次会新增 `/api/v1/hot-news/ingest`、`/api/v1/hot-news/latest`、`/api/v1/hot-news/{reportId}`，并更新 `schema.sql` 和 `application.yml`。
- 已新增 Daily Hot News entity、mapper、DTO、service、controller、schema、配置和服务层测试。
- 已补充生产配置模板中的 `HERMES_INGEST_TOKEN` 和 `HOT_NEWS_CACHE_TTL_HOURS`。

## Previous Done Summary
- 已记录工作台 LangGraph 多智能体协作与 LLM WIKI 需求草案对后端模块的影响。
- 已将 `pulse-backend` 确认为 Java 21 + Spring Boot 3.2 后端模块。

## In Progress
- 无

## Blocked
- Blocker: 无
- Needed input: 无
- Since: 2026-06-01

## Decisions
- 2026-06-01: 入站鉴权使用 `X-Hermes-Token` 与配置项 `hot-news.ingest-token`。
- 2026-06-01: `report_date + source` 建唯一键，重复推送覆盖主表和条目表。
- 2026-06-01: Redis key 使用 `pulse:hot-news:latest` 和 `pulse:hot-news:detail:{id}`，失败降级为 MySQL。
- 后端模块拥有 REST API、认证、Agent 调度、账本、悬赏、排行、数据库 schema 和 LLM 客户端。

## Verification
- Command: `rtk powershell -NoProfile -Command "cd pulse-backend; mvn -Dtest=HotNewsServiceImplTest test"`
- Result: pass
- Notes: 先前红灯为缺少新 HotNews 类型导致编译失败；实现后 3 tests run, 0 failures, 0 errors。
- Command: `rtk powershell -NoProfile -Command "cd pulse-backend; mvn test"`
- Result: pass
- Notes: 13 tests run, 0 failures, 0 errors；输出包含 Mockito/Java 动态 agent 警告，不影响退出码。
- Command: `rtk proxy git diff --check`
- Result: pass
- Notes: 退出码为 0；输出仅包含 Git 的 LF/CRLF 提示。

## Next
- 在 qiniuyun 后端 `.env` 设置 `HERMES_INGEST_TOKEN` 后，Hermes 即可推送结构化 JSON。
