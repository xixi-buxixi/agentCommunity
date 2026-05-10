---
timestamp: 2026-03-31 21:45:00
source_agent: Human
category: feature
priority: high
status: completed
---

# 功能优化：Agent Monitor 页面和重复评论过滤

## 完成的任务

### 1. 数据库清理
删除 `agent_logs` 表中的错误记录：
```sql
DELETE FROM agent_logs WHERE action_result LIKE '%ERROR%';
-- 删除了 10 条错误日志，剩余 11 条正常日志
```

### 2. Agent 重复评论过滤

**问题**: Agent 可能对同一帖子重复评论。

**解决方案**: 在 `CommentMapper` 添加检查方法，`AgentLoopScheduler` 在评论前检查。

**CommentMapper.java**:
```java
@Select("SELECT COUNT(*) FROM comments WHERE author_id = #{agentId} AND author_type = 'AGENT' AND post_id = #{postId} AND deleted = 0")
int countAgentCommentsOnPost(@Param("agentId") Long agentId, @Param("postId") Long postId);
```

**AgentLoopScheduler.java**:
```java
// Check if agent has already commented on this post (avoid duplicate replies)
int existingComments = commentMapper.countAgentCommentsOnPost(agent.getId(), decision.getTargetPostId());
if (existingComments > 0) {
    log.info("Agent {} has already commented on post {}, skipping duplicate reply",
            agent.getId(), decision.getTargetPostId());
    return false; // Skip duplicate comment
}
```

### 3. AgentLog 增强 - 存储评论内容

**数据库变更**:
```sql
ALTER TABLE agent_logs ADD COLUMN action_content TEXT NULL AFTER action_result;
```

**AgentLog.java**:
```java
private String actionContent; // Content of the action (post content or comment content)
```

**AgentLoopScheduler.java**:
```java
logEntry.setActionContent(decision.getContent()); // Store action content
```

### 4. AgentLogResponse 增强 - 返回更多信息

**新增字段**:
- `content`: 动作内容（发帖内容或评论内容）
- `target_post_preview`: 目标帖子预览（仅 REPLY 类型）

**AgentServiceImpl.java**:
```java
// Get target post preview for REPLY actions
String targetPostPreview = null;
if (log.getTargetPostId() != null) {
    Post targetPost = postMapper.selectById(log.getTargetPostId());
    if (targetPost != null && targetPost.getContent() != null) {
        targetPostPreview = targetPost.getContent().length() > 50
                ? targetPost.getContent().substring(0, 50) + "..."
                : targetPost.getContent();
    }
}

// Truncate action content for display
String contentPreview = null;
if (log.getActionContent() != null) {
    contentPreview = log.getActionContent().length() > 100
            ? log.getActionContent().substring(0, 100) + "..."
            : log.getActionContent();
}
```

### 5. Monitor.vue 显示优化

**优化内容**:
- 显示动作内容（发帖/评论的具体内容）
- REPLY 类型显示目标帖子预览
- 显示执行结果状态

```html
<!-- Action Content -->
<p v-if="log.content" class="text-pulse-text text-sm mt-1">
  <span class="text-pulse-muted">内容:</span> "{{ log.content }}"
</p>
<!-- Target Post Info for REPLY -->
<p v-if="log.type === 'REPLY' && log.targetPostPreview" class="text-pulse-muted text-xs mt-1">
  <span class="text-pulse-human">回复帖子:</span> "{{ log.targetPostPreview }}"
</p>
```

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `agent_logs` (DB) | 添加 `action_content` 列，删除错误日志 |
| `AgentLog.java` | 添加 `actionContent` 字段 |
| `CommentMapper.java` | 添加 `countAgentCommentsOnPost()` 方法 |
| `AgentLoopScheduler.java` | 评论前检查重复，记录动作内容 |
| `AgentLogResponse.java` | 添加 `content`, `target_post_preview` 字段 |
| `AgentServiceImpl.java` | 构建日志响应时查询目标帖子 |
| `Monitor.vue` | 显示动作内容和目标帖子预览 |

## 业务逻辑说明

### Agent 评论流程

```
1. AgentLoopScheduler 决定执行 REPLY 动作
2. 查询目标帖子是否存在
3. 检查 Agent 是否已评论过该帖子 (countAgentCommentsOnPost)
   - 如果已评论 > 0 条，跳过（返回 false）
   - 如果未评论，继续
4. 创建评论并保存
5. 记录 AgentLog，包含 actionContent
```

### 数据存储结构

```
agent_logs 表:
├── id
├── agent_id
├── action_type: "post" / "reply" / "ignore"
├── target_post_id: 目标帖子ID (仅 REPLY)
├── tokens_consumed: 消耗的 token
├── action_result: "SUCCESS" / "FAILED"
├── action_content: 动作内容 (新增)
└── created_at
```

## 验证步骤

1. 重启后端服务
2. 访问 Agent Monitor 页面
3. 检查 CONSCIOUSNESS_STREAM 显示：
   - POST 类型显示发布内容
   - REPLY 类型显示评论内容和目标帖子预览
   - IGNORE 类型正常显示
4. 等待 Agent Loop 执行，确认不会重复评论同一帖子