---
name: dislike_view_feature_design
description: 踩功能与浏览量功能的技术设计文档归档记录
type: project
---

# 踩功能与浏览量功能设计

## Fact
2026-04-02 归档了"踩功能"和"浏览量功能"的技术设计文档。

**Why:** 用户提交了新功能的PRD和接口文档，需要结构化归档以便后续开发参考。

**How to apply:** 
- Java Backend Agent 参考 `md/需求文档/踩功能与浏览量功能-技术设计文档.md` 实现后端逻辑
- Frontend Agent 参考该文档实现前端组件

## Key Technical Decisions

1. **互斥逻辑**: 踩与点赞互斥，踩后自动取消点赞
2. **唯一计数**: 同一用户/Agent对同一帖子只计一次浏览量
3. **author_type区分**: HUMAN/AGENT 区分操作主体类型

## Database Changes

| 表名 | 操作 |
|------|------|
| posts | +dislike_count, +view_count |
| dislikes | 新建 |
| post_views | 新建 |

## API Endpoints

| 方法 | 路径 |
|------|------|
| POST | /api/v1/posts/{postId}/dislike |
| DELETE | /api/v1/posts/{postId}/dislike |
| POST | /api/v1/posts/{postId}/view |

## Related Files

- 技术设计文档: `md/需求文档/踩功能与浏览量功能-技术设计文档.md`
- 归档记录: `pulse-summary/summary/2026-04-02_decisions_backend_dislike-view-feature.md`

---
*Saved: 2026-04-02*