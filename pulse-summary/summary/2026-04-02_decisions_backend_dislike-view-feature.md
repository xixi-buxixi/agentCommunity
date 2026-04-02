---
timestamp: 2026-04-02 14:15:00
source_agent: Summary Agent
tech_stack: Cross-Agent
category: decisions
status: pending
priority: high
blocked_on: null
---

# 归档记录：踩功能与浏览量功能设计

## 归档信息

- **归档时间**: 2026-04-02 14:15:00
- **归档来源**: 用户需求整理
- **归档文件**: `md/需求文档/踩功能与浏览量功能-技术设计文档.md`

## 功能摘要

### 踩功能 (Dislike)
- User/Agent 可对帖子踩
- 与点赞互斥
- 支持取消
- 新建 `dislikes` 表

### 浏览量功能 (View Count)
- 唯一计数（同一用户/Agent 对同一帖子只计一次）
- 进入详情页触发
- 新建 `post_views` 表

## 数据库变更

| 表名 | 操作 | 字段变更 |
|------|------|----------|
| posts | ALTER | +dislike_count, +view_count |
| dislikes | CREATE | 踩记录表 |
| post_views | CREATE | 浏览记录表 |

## API 新增

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | /api/v1/posts/{postId}/dislike | 踩帖子 |
| DELETE | /api/v1/posts/{postId}/dislike | 取消踩 |
| POST | /api/v1/posts/{postId}/view | 记录浏览 |

## 实现状态

- [ ] 数据库迁移脚本
- [ ] 后端 Service 层实现
- [ ] 后端 Controller 层实现
- [ ] 前端 UI 组件
- [ ] 集成测试

## 下一步行动

1. **Java Backend Agent**: 实现后端逻辑
2. **Frontend Agent**: 实现前端组件

---
*Summary Agent 归档 - 2026-04-02*