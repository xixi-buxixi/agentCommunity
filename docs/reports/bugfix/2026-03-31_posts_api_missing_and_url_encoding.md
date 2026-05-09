---
timestamp: 2026-03-31 20:15:00
source_agent: Human (Bug Report)
category: bugfix
priority: critical
status: fixed
---

# Bug 修复：Posts API 缺失和 URL 编码错误

## 问题描述
1. **Posts API 不存在**：前端请求 `GET /api/v1/posts` 返回 404
2. **URL 编码错误**：Agent 调用 LLM 时 baseUrl 前有空格，导致 `%20` 编码错误

## 错误日志
```
# Posts API 404
org.springframework.web.servlet.resource.NoResourceFoundException: No static resource api/v1/posts

# URL 编码错误
Illegal character in scheme name at index 0: %20https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

## 根因分析

### 问题 1: Posts API 未实现
后端缺少 `PostController`、`PostService` 和相关 DTO，导致前端请求 404。

### 问题 2: baseUrl 未 trim
用户输入的 `base_url` 可能包含前导/尾随空格，存储时未处理，导致 URL 拼接时出现 `%20` 编码错误。

## 修复方案

### 1. 创建完整的 Posts API

**新增文件：**
| 文件 | 说明 |
|------|------|
| `PostService.java` | 服务接口 |
| `PostServiceImpl.java` | 服务实现 |
| `PostController.java` | REST 控制器 |
| `PostCreateRequest.java` | 创建请求 DTO |
| `CommentCreateRequest.java` | 评论请求 DTO |
| `PostResponse.java` | 帖子响应 DTO |
| `CommentResponse.java` | 评论响应 DTO |

**API 端点：**
| Method | Endpoint | 功能 |
|--------|----------|------|
| GET | `/api/v1/posts` | 获取动态列表 |
| GET | `/api/v1/posts/{id}` | 获取动态详情 |
| POST | `/api/v1/posts` | 发布动态 |
| POST | `/api/v1/posts/{id}/like` | 点赞 |
| DELETE | `/api/v1/posts/{id}/like` | 取消点赞 |
| GET | `/api/v1/posts/{id}/comments` | 获取评论列表 |
| POST | `/api/v1/posts/{id}/comments` | 发表评论 |

### 2. 修复 URL 编码问题

在 `AgentServiceImpl.java` 中添加 `trim()` 处理：

```java
// 创建时
agent.setBaseUrl(request.getBaseUrl() != null ? request.getBaseUrl().trim() : null);
agent.setModelName(request.getModelName() != null ? request.getModelName().trim() : null);

// 更新时
if (request.getBaseUrl() != null) {
    agent.setBaseUrl(request.getBaseUrl().trim());
}
```

### 3. 完善 PostMapper

添加 `@Update` 注解实现原子操作：

```java
@Update("UPDATE posts SET like_count = like_count + 1 WHERE id = #{postId}")
int incrementLikeCount(@Param("postId") Long postId);

@Update("UPDATE posts SET like_count = GREATEST(0, like_count - 1) WHERE id = #{postId}")
int decrementLikeCount(@Param("postId") Long postId);

@Update("UPDATE posts SET comment_count = comment_count + 1 WHERE id = #{postId}")
int incrementCommentCount(@Param("postId") Long postId);
```

### 4. 添加错误码

在 `ErrorCode.java` 中添加：
```java
INVALID_PARAMETER(99900, "参数错误")
```

## 修改文件

| 文件 | 修改类型 |
|------|----------|
| `PostService.java` | 新增 |
| `PostServiceImpl.java` | 新增 |
| `PostController.java` | 新增 |
| `PostCreateRequest.java` | 新增 |
| `CommentCreateRequest.java` | 新增 |
| `PostResponse.java` | 新增 |
| `CommentResponse.java` | 新增 |
| `AgentServiceImpl.java` | 修改 (添加 trim) |
| `PostMapper.java` | 修改 (添加 @Update) |
| `ErrorCode.java` | 修改 (添加 INVALID_PARAMETER) |

## 验证步骤

1. 重启后端服务
2. 测试 Posts API：
   ```bash
   curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/posts
   ```
3. 测试 Agent LLM 调用（确保 baseUrl 无空格）

## 教训

1. **API 完整性**：需要在开发前确保所有 API 端点都已实现
2. **输入清理**：用户输入的 URL 和其他字符串应该 trim() 处理
3. **测试覆盖**：需要端到端测试覆盖所有用户操作流程