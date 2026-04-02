---
timestamp: 2026-03-31 20:35:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：Square 页面数据无法显示

## 问题描述
前端发布帖子后显示错误：`Cannot read properties of undefined (reading 'unshift')`，虽然后端成功插入数据，前端也收到了正确的响应，但页面无法显示帖子列表。

## 错误日志
```
> ERROR: Cannot read properties of undefined (reading 'unshift')
```

## 根因分析

### 问题 1: 字段名不匹配
前端期望 `data.list`，但后端返回的是 `data.records`（MyBatis Plus 分页默认字段名）。

**后端返回：**
```json
{
  "data": {
    "records": [...],  // MyBatis Plus 默认
    "total": 2
  }
}
```

**前端期望：**
```javascript
posts.value = data.list  // undefined!
```

### 问题 2: 点赞返回值缺失
后端 like/unlike API 返回 `void`，前端期望返回 `like_count` 和 `is_liked`。

**原有代码：**
```java
public void likePost(...) { ... }
```

**前端期望：**
```javascript
post.like_count = data.like_count  // undefined!
```

## 修复方案

### 1. 前端字段名修正

**Square.vue - loadPosts 函数：**
```javascript
// 修复前
posts.value = data.list

// 修复后
posts.value = data.records || []
totalPosts.value = data.total || 0
```

### 2. 前端空数组保护

**Square.vue - submitPost 函数：**
```javascript
// 修复前
posts.value.unshift(data)

// 修复后
if (posts.value && Array.isArray(posts.value)) {
  posts.value.unshift(data)
}
totalPosts.value++
```

### 3. 后端点赞返回值

**PostService.java：**
```java
// 修复前
void likePost(Long userId, Long postId);

// 修复后
Map<String, Object> likePost(Long userId, Long postId);
```

**PostServiceImpl.java：**
```java
@Override
public Map<String, Object> likePost(Long userId, Long postId) {
    // ... like logic ...
    
    Post updatedPost = postMapper.selectById(postId);
    return Map.of(
        "post_id", postId,
        "like_count", updatedPost.getLikeCount(),
        "is_liked", true
    );
}
```

**PostController.java：**
```java
@PostMapping("/{postId}/like")
public ApiResponse<Map<String, Object>> likePost(...) {
    Map<String, Object> result = postService.likePost(...);
    return ApiResponse.success(result);
}
```

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `Square.vue` | 修复字段名 `list` → `records`，添加空数组保护 |
| `PostService.java` | 返回类型改为 `Map<String, Object>` |
| `PostServiceImpl.java` | 实现 like/unlike 返回值 |
| `PostController.java` | 返回类型改为 `Map<String, Object>` |

## 验证步骤

1. 重启后端服务
2. 刷新前端页面
3. 测试发布帖子
4. 测试点赞功能

## 教训

1. **API 字段命名一致性**：前后端应该统一字段命名规范
2. **空值保护**：前端操作数组前应检查是否为 null/undefined
3. **返回值完整性**：API 应该返回操作后的完整状态，而非仅成功/失败