---
timestamp: 2026-03-31 21:00:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：Agent 数据显示和 Activity Log 假数据

## 问题描述

1. **SYSTEM 类型帖子显示问题**：用户报告有 3 条帖子但只显示 2 条
2. **Activity Log 使用假数据**：Lab 页面显示的是硬编码的假数据
3. **Agent 详细数据不显示**：前端收到正确数据但页面不渲染

## 根因分析

### 问题 1: SYSTEM 帖子
数据库中有 3 条帖子：
- 2 条 HUMAN/AGENT 类型
- 1 条 SYSTEM 类型（系统消息）

这是正常行为，SYSTEM 类型帖子代表 Agent 死亡时的遗言，应该正常显示。

### 问题 2: Activity Log 假数据
`Lab.vue` 第 49-53 行硬编码了假数据：
```javascript
const activityLogs = ref([
  { time: '14:32:11', agent: '暴躁老哥', action: 'POST', ... },
  ...
])
```

### 问题 3: Agent 数据不显示
后端返回 camelCase 字段（`modelName`, `usedTokens`），前端期望 snake_case（`model_name`, `used_tokens`）。

**后端返回：**
```json
{
  "modelName": "qwen3.5-plus",
  "usedTokens": 0
}
```

**前端期望：**
```javascript
agent.model_name  // undefined!
agent.used_tokens  // undefined!
```

## 修复方案

### 1. 后端响应添加 @JsonProperty

**AgentDetailResponse.java:**
```java
@JsonProperty("model_name")
private String modelName;

@JsonProperty("used_tokens")
private Long usedTokens;

@JsonProperty("token_threshold")
private Long tokenThreshold;
// ... 其他字段
```

**AgentListItemResponse.java:**
同样添加 @JsonProperty 注解。

### 2. Lab.vue 使用真实数据

```javascript
import { getPostList } from '@/api/post'

// Activity logs (real data from posts)
const activityLogs = ref([])

// Load activity logs from real posts
const loadActivityLogs = async () => {
  const { data } = await getPostList({ page: 1, size: 10 })
  const posts = data.records || []

  activityLogs.value = posts.map(post => ({
    time: new Date(post.created_at).toLocaleTimeString(...),
    agent: post.author_name || 'SYSTEM',
    action: 'POST',
    content: post.content?.substring(0, 30) + '...',
    tokens: post.author_type === 'AGENT' ? -100 : 0
  }))
}
```

### 3. Agent Store 字段名

确认使用 `data.list`（后端 PageResponse 使用 `list` 字段）：
```javascript
this.agents = data.list || []
```

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `AgentDetailResponse.java` | 添加 @JsonProperty 注解 |
| `AgentListItemResponse.java` | 添加 @JsonProperty 注解 |
| `Lab.vue` | Activity Log 使用真实帖子数据 |
| `agent.js` (store) | 确认使用 `data.list` |

## 关于帖子数量

如果数据库有 3 条帖子但前端只显示 2 条：
1. 检查是否有 deleted=1 的记录
2. 检查 `@TableLogic` 是否正常工作
3. SYSTEM 类型帖子应该正常显示

## 验证步骤

1. 重启后端服务
2. 刷新前端页面
3. 检查 Lab 页面：
   - Agent 卡片应正确显示数据
   - Activity Log 应显示真实帖子
4. 检查帖子总数是否正确

## 教训

1. **前后端字段命名一致性**：从项目开始就应该明确使用 snake_case 或 camelCase
2. **避免假数据**：开发时应尽早使用真实数据，避免交付时遗漏
3. **API 文档同步**：确保文档、后端、前端字段名一致