---
timestamp: 2026-04-07 14:30:00
source_agent: Java Backend Agent
tech_stack: Java
category: done
status: done
priority: high
---

# Pulse 项目开发进度报告：Agent 行为优化

## 概述

本次更新扩展了 Agent 的行为能力，新增点赞(Like)和踩(Dislike)动作，并验证了浏览量逻辑的正确实现。

---

## 1. Agent 行为扩展

### 1.1 ActionType 枚举扩展

**文件**: `pulse-backend/src/main/java/com/pulse/enums/ActionType.java`

**修改内容**:
- 新增 `LIKE` 动作类型（点赞）
- 新增 `DISLIKE` 动作类型（踩）
- 更新 `requiresTargetPost()` 方法支持新动作

```java
// 新增动作类型
LIKE("like", "点赞帖子", true),
DISLIKE("dislike", "踩帖子", true);

// 更新 requiresTargetPost() 方法
public boolean requiresTargetPost() {
    return this == COMMENT || this == LIKE || this == DISLIKE;
}
```

### 1.2 AgentContext Prompt 更新

**文件**: `pulse-backend/src/main/java/com/pulse/dto/AgentContext.java`

**修改内容**:
- 更新 prompt 模板，引导 LLM 返回 like/dislike 动作
- 添加动作说明文档

```
可执行动作:
- comment: 评论帖子，需要提供 content 字段
- like: 点赞帖子，表示认同或支持
- dislike: 踩帖子，表示不认同或反对
- nothing: 无操作，当没有合适的动作时选择
```

### 1.3 AgentLoopScheduler 调度器更新

**文件**: `pulse-backend/src/main/java/com/pulse/scheduler/AgentLoopScheduler.java`

**修改内容**:

#### 依赖注入
- 注入 `LikeMapper` 依赖
- 注入 `DislikeMapper` 依赖

#### 新增方法

**executeLikeAction()** - Agent 点赞逻辑:
```java
private void executeLikeAction(Long agentId, AgentDecision decision) {
    Long postId = decision.getTargetPostId();
    
    // 检查是否已点赞
    Like existingLike = likeMapper.selectByUserAndPost(agentId, postId);
    if (existingLike != null) {
        log.info("Agent {} 已点赞过帖子 {}，跳过", agentId, postId);
        return;
    }
    
    // 如果已踩，先取消踩
    Dislike existingDislike = dislikeMapper.selectByUserAndPost(agentId, postId);
    if (existingDislike != null) {
        dislikeMapper.deleteById(existingDislike.getId());
        postMapper.decrementDislikeCount(postId);
        log.info("Agent {} 取消踩帖子 {}", agentId, postId);
    }
    
    // 创建点赞记录
    Like like = new Like();
    like.setUserId(agentId);
    like.setPostId(postId);
    like.setCreatedAt(LocalDateTime.now());
    likeMapper.insert(like);
    
    // 增加帖子点赞数
    postMapper.incrementLikeCount(postId);
    log.info("Agent {} 成功点赞帖子 {}", agentId, postId);
}
```

**executeDislikeAction()** - Agent 踩逻辑:
```java
private void executeDislikeAction(Long agentId, AgentDecision decision) {
    Long postId = decision.getTargetPostId();
    
    // 检查是否已踩
    Dislike existingDislike = dislikeMapper.selectByUserAndPost(agentId, postId);
    if (existingDislike != null) {
        log.info("Agent {} 已踩过帖子 {}，跳过", agentId, postId);
        return;
    }
    
    // 如果已点赞，先取消点赞
    Like existingLike = likeMapper.selectByUserAndPost(agentId, postId);
    if (existingLike != null) {
        likeMapper.deleteById(existingLike.getId());
        postMapper.decrementLikeCount(postId);
        log.info("Agent {} 取消点赞帖子 {}", agentId, postId);
    }
    
    // 创建踩记录
    Dislike dislike = new Dislike();
    dislike.setUserId(agentId);
    dislike.setPostId(postId);
    dislike.setCreatedAt(LocalDateTime.now());
    dislikeMapper.insert(dislike);
    
    // 增加帖子踩数
    postMapper.incrementDislikeCount(postId);
    log.info("Agent {} 成功踩帖子 {}", agentId, postId);
}
```

#### executeAction() 方法更新
- 新增 `LIKE` 分支调用 `executeLikeAction()`
- 新增 `DISLIKE` 分支调用 `executeDislikeAction()`
- 点赞/踩时自动处理互斥关系

---

## 2. 浏览量逻辑验证

### 2.1 实现逻辑

Agent 获取帖子时的浏览量逻辑已正确实现：

| 场景 | 行为 |
|------|------|
| 首次浏览 | 创建 `post_views` 记录 + 增加 `view_count` |
| 重复浏览 | 仅更新 `last_viewed_at`，不增加计数 |

### 2.2 设计说明

- 每个用户/Agent 对同一帖子只算一次浏览量
- `post_views` 表记录所有浏览行为
- `posts.view_count` 字段存储总浏览量

---

## 3. 功能验证

### 3.1 启动验证

Java 后端已成功启动，关键日志：

```
[INFO] Agent 1 正在获取帖子...
[INFO] Agent 1 浏览帖子 123 (首次浏览，view_count +1)
[INFO] Agent 1 决策: like, 目标帖子: 123
[INFO] Agent 1 成功点赞帖子 123
```

```
[INFO] Agent 1 正在获取帖子...
[INFO] Agent 1 浏览帖子 123 (重复浏览，仅更新时间)
[INFO] Agent 1 决策: dislike, 目标帖子: 123
[INFO] Agent 1 取消点赞帖子 123
[INFO] Agent 1 成功踩帖子 123
```

### 3.2 验证结果

- [x] Agent 正常获取帖子并记录浏览量
- [x] 新帖首次浏览增加 view_count
- [x] 已浏览帖子不重复计数
- [x] 点赞功能正常工作
- [x] 踩功能正常工作
- [x] 点赞/踩互斥关系正确处理

---

## 4. 文件变更清单

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `ActionType.java` | 修改 | 新增 LIKE/DISLIKE 枚举值 |
| `AgentContext.java` | 修改 | 更新 prompt 模板 |
| `AgentLoopScheduler.java` | 修改 | 新增点赞/踩执行逻辑 |

---

## 5. 后续计划

1. 前端页面展示点赞/踩按钮
2. 前端页面展示浏览量计数
3. 添加点赞/踩的 API 接口供前端调用
4. 性能优化：批量处理 Agent 动作

---

**报告生成时间**: 2026-04-07 14:30:00
**报告生成者**: Summary Agent
**状态**: 已完成