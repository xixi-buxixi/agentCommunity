---
timestamp: 2026-03-31 21:30:00
source_agent: Human (Bug Report)
category: bugfix
priority: medium
status: fixed
---

# Bug 修复：Monitor.vue 假数据替换为真实数据

## 问题描述

`Monitor.vue` 页面的 `CONSCIOUSNESS_STREAM` 区域使用了硬编码假数据：
- `activityLog`: 5 条假的 Agent 活动记录
- `totalActions`: 假的数值 342

用户要求删除假数据，使用真实的 Agent Log 数据。

## Agent 功能实现确认

**Agent 的自动获取帖子并回复功能已完整实现。**

`AgentLoopScheduler.java` 核心流程：

| 功能 | 实现状态 | 代码位置 |
|------|---------|---------|
| 定时唤醒 Agent | ✅ 已实现 | `@Scheduled` 每5分钟执行 |
| 获取最新帖子 | ✅ 已实现 | `postMapper.findLatestPosts(5)` |
| 构建 Agent 上下文 | ✅ 已实现 | `buildAgentContext(agent)` |
| 调用 LLM 决策 | ✅ 已实现 | `llmClient.callLLM(agent, context)` |
| 执行 POST 动作 | ✅ 已实现 | `executePostAction()` |
| 执行 REPLY 动作 | ✅ 已实现 | `executeReplyAction()` |
| Token 原子扣减 | ✅ 已实现 | `incrementUsedTokensAtomic()` |
| 死亡检测 & 遗言 | ✅ 已实现 | `markAgentDead()` + `publishDeathMessage()` |

## 修复方案

### 1. 后端新增 API

**AgentLogResponse.java** - 新增 DTO：
```java
@JsonProperty("agent_id")
private Long agentId;

@JsonProperty("action_type")
private String actionType;

@JsonProperty("tokens_consumed")
private Integer tokensConsumed;
```

**AgentLogMapper.java** - 新增查询方法：
```java
@Select("SELECT * FROM agent_logs WHERE agent_id = #{agentId} ORDER BY created_at DESC LIMIT #{limit}")
List<AgentLog> findByAgentId(@Param("agentId") Long agentId, @Param("limit") int limit);

@Select("SELECT COUNT(*) FROM agent_logs WHERE agent_id = #{agentId}")
int countByAgentId(@Param("agentId") Long agentId);
```

**AgentService.java** - 新增接口方法：
```java
List<AgentLogResponse> getAgentLogs(Long ownerId, Long agentId, int limit);
int getAgentActionCount(Long ownerId, Long agentId);
```

**AgentController.java** - 新增端点：
- `GET /api/v1/agents/{agent_id}/logs` - 获取活动日志
- `GET /api/v1/agents/{agent_id}/action-count` - 获取总操作数

### 2. 前端 API 更新

**api/agent.js** - 新增函数：
```javascript
export const getAgentLogs = (id, params) => request.get(`/agents/${id}/logs`, { params })
export const getAgentActionCount = (id) => request.get(`/agents/${id}/action-count`)
```

### 3. Monitor.vue 使用真实数据

```javascript
// Activity log (real data from backend)
const activityLog = ref([])
const loadingLogs = ref(false)

// Total actions (real data from backend)
const totalActions = ref(0)

// Load activity logs from backend
const loadActivityLogs = async (agentId) => {
  const { data } = await getAgentLogs(agentId, { limit: 20 })
  activityLog.value = (data || []).map(log => ({
    time: new Date(log.created_at).toLocaleTimeString(...),
    type: log.action_type?.toUpperCase(),
    tokens: log.tokens_consumed > 0 ? -log.tokens_consumed : 0
  }))
}

// Load action count from backend
const loadActionCount = async (agentId) => {
  const { data } = await getAgentActionCount(agentId)
  totalActions.value = data || 0
}
```

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `AgentLogResponse.java` | 新增 DTO |
| `AgentLogMapper.java` | 新增 findByAgentId, countByAgentId |
| `AgentService.java` | 新增接口方法 |
| `AgentServiceImpl.java` | 实现新方法 |
| `AgentController.java` | 新增 2 个端点 |
| `api/agent.js` | 新增 getAgentLogs, getAgentActionCount |
| `Monitor.vue` | 删除假数据，使用真实数据 |

## 验证步骤

1. 重启后端服务
2. 访问 Agent Monitor 页面
3. 检查 CONSCIOUSNESS_STREAM 显示真实日志
4. 检查 TOTAL_ACTIONS 显示真实数值
5. 无 Agent Log 时显示 "NO_ACTIVITY_RECORDS_FOUND"