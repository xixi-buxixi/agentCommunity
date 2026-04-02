---
timestamp: 2026-04-02 17:00:00
source_agent: Java Backend Agent
tech_stack: Java
category: done
status: done
priority: high
---

# 完成任务：PostController REST API 扩展 (踩功能与浏览量)

## 变更概述
扩展 `PostController.java`，添加踩帖子、取消踩、记录浏览三个 REST API 接口。

## 文件变更清单

### 新建文件
| 文件路径 | 说明 |
|:---------|:-----|
| `pulse-backend/src/main/java/com/pulse/dto/request/DislikeRequest.java` | 踩请求DTO，包含 authorType 和 authorId |
| `pulse-backend/src/main/java/com/pulse/dto/request/ViewRequest.java` | 浏览请求DTO，包含 authorType 和 authorId |

### 修改文件
| 文件路径 | 变更内容 |
|:---------|:---------|
| `pulse-backend/src/main/java/com/pulse/controller/PostController.java` | 添加 AgentMapper 注入；新增 dislikePost、undislikePost、recordView 三个接口 |

## API 接口详情

### 1. 踩帖子接口
- **路径**: `POST /api/v1/posts/{postId}/dislike`
- **权限**: 需登录；Agent表态需校验归属权限
- **请求体**: `DislikeRequest` (可选)
- **响应**: `{ like_count, dislike_count, is_liked, is_disliked }`

### 2. 取消踩接口
- **路径**: `DELETE /api/v1/posts/{postId}/dislike`
- **权限**: 需登录；Agent表态需校验归属权限
- **请求体**: `DislikeRequest` (可选)
- **响应**: `{ like_count, dislike_count, is_liked, is_disliked }`

### 3. 记录浏览接口
- **路径**: `POST /api/v1/posts/{postId}/view`
- **权限**: 需登录；Agent浏览需校验归属权限
- **请求体**: `ViewRequest` (可选)
- **响应**: `{ view_count, is_first_view }`

## 关键设计要点

### Agent归属校验逻辑
```java
// Agent表态时的权限校验模式
if (request != null && AuthorType.AGENT.getCode().equals(request.getAuthorType())) {
    Agent agent = agentMapper.selectById(request.getAuthorId());
    if (agent == null || !agent.getOwnerId().equals(principal.getUserId())) {
        throw new BusinessException(ErrorCode.AGENT_NOT_OWNER);
    }
    authorType = request.getAuthorType();
    authorId = request.getAuthorId();
}
```

### RESTful 规范
- 使用 `@PostMapping` 创建踩记录
- 使用 `@DeleteMapping` 删除踩记录
- 使用 `@Operation` 注解添加 Swagger 文档

### 已复用的枚举和错误码
- `AuthorType.HUMAN.getCode()` / `AuthorType.AGENT.getCode()` - 区分人类与Agent表态
- `ErrorCode.AGENT_NOT_OWNER` - Agent归属校验失败时抛出

## 编译验证
`mvn compile` 编译成功，无错误。

## 后端层实现完成状态
| 层级 | 状态 | 备注 |
|:-----|:-----|:-----|
| Entity层 | DONE | Post.java, Like.java, Dislike.java, PostView.java |
| Mapper层 | DONE | PostMapper.java 扩展 |
| Service层 | DONE | PostService.java 接口定义 + PostServiceImpl.java 实现 |
| Controller层 | DONE | PostController.java REST API |

## 下一步建议
- **Frontend Agent**: 可开始对接这三个新接口，实现前端踩按钮和浏览量显示