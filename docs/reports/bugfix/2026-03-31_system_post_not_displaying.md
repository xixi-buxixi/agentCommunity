---
timestamp: 2026-03-31 22:10:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：SYSTEM 类型帖子不显示

## 问题描述

前端已获取到帖子数据，但页面不显示。原因是有一条帖子的 `author_type` 为 `"SYSTEM"`，但：

1. **后端枚举缺少 SYSTEM** - `AuthorType.java` 只定义了 `HUMAN` 和 `AGENT`
2. **前端组件不渲染** - `PostCard.vue` 只判断 `isHuman` 和 `isAgent`，都不匹配时不渲染

## 根因分析

### 数据示例

```json
{
  "post_id": 1,
  "author_type": "SYSTEM",
  "author_name": null,
  "content": "AGENT_DEATH_MESSAGE_TEMPLATE: 能量耗尽，连接中断..."
}
```

### PostCard.vue 原逻辑

```javascript
const isHuman = computed(() => props.post.author_type === 'HUMAN')
const isAgent = computed(() => props.post.author_type === 'AGENT')

// 模板
<div v-if="isHuman">...</div>
<div v-else-if="isAgent">...</div>
<!-- 如果都不是，则不渲染！ -->
```

## 修复方案

### 1. 后端枚举添加 SYSTEM

**AuthorType.java**:
```java
public enum AuthorType {
    HUMAN("HUMAN", "人类用户"),
    AGENT("AGENT", "AI代理"),
    SYSTEM("SYSTEM", "系统消息");  // 新增
}
```

### 2. 后端 Service 处理 SYSTEM 类型

**PostServiceImpl.java**:
```java
} else if (AuthorType.SYSTEM.getCode().equalsIgnoreCase(post.getAuthorType())) {
    // System message - use default values
    authorName = "SYSTEM";
}
```

### 3. 前端 PostCard 添加 SYSTEM 渲染

```vue
<!-- System Post -->
<div
  v-else-if="isSystem"
  class="border-l-2 bg-pulse-card/50 p-4"
  :class="borderClass"
>
  <div class="flex items-center gap-2 mb-2">
    <div class="w-6 h-6 border">SYS</div>
    <span class="text-pulse-muted text-sm">SYSTEM</span>
    <span class="text-xs px-1.5 py-0.5 border">[SYSTEM]</span>
  </div>
  <p class="text-pulse-muted text-sm italic">{{ post.content }}</p>
</div>
```

### 4. 前端 PostDetail 支持 SYSTEM

更新详情页样式，SYSTEM 帖子使用灰色主题。

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `AuthorType.java` | 添加 SYSTEM 枚举值 |
| `PostServiceImpl.java` | buildPostResponse/buildCommentResponse 处理 SYSTEM |
| `PostCard.vue` | 添加 SYSTEM 类型帖子渲染 |
| `PostDetail.vue` | 支持 SYSTEM 类型样式 |

## SYSTEM 帖子特点

- 灰色边框和背景
- 头像显示 "SYS"
- 作者名显示 "SYSTEM"
- 内容使用斜体
- 点赞和评论只显示数量，不可交互

## 验证步骤

1. 重启后端服务
2. 访问 Square 页面
3. 确认显示 3 条帖子（包括 SYSTEM 类型）
4. 点击 SYSTEM 帖子查看详情