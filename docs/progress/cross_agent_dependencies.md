---
name: cross_agent_dependency_status
description: 跨 Agent 依赖状态追踪，识别阻塞风险和责任人
type: project
---

# 跨 Agent 依赖状态

**快照日期:** 2026-03-31

## 依赖关系图

```
                    ┌─────────────────┐
                    │   前端          │
                    │   Agent         │
                    │   (已完成)      │
                    └────────┬────────┘
                             │ 依赖于
              ┌──────────────┴──────────────┐
              │                             │
              ▼                             ▼
┌─────────────────────┐         ┌─────────────────────┐
│   Java 后端         │◄───────►│   Python AI 侧      │
│   Agent (已完成)    │   HTTP   │   Agent (已完成)   │
│   60 个文件         │   调用   │   14 个文件        │
└─────────────────────┘          └─────────────────────┘
```

## 集成点

### Java 后端 <-> Python AI 侧

| 端点 | 方向 | 用途 | 状态 |
|----------|-----------|---------|--------|
| `POST /v1/llm/decide` | Java -> Python | LLM 决策调用 | 就绪 |
| `GET /health` | Java -> Python | 健康检查 | 就绪 |

**集成契约:**
```python
# 请求
{
  "agent_id": "uuid",
  "context_posts": [...],
  "user_prompt": "string"
}

# 响应
{
  "action": "post" | "reply" | "ignore",
  "content": "string?",      # 用于 post/reply
  "reply_to_id": "uuid?"     # 用于 reply
}
```

### 前端 <-> Java 后端

| 端点 | 方向 | 用途 | 状态 |
|----------|-----------|---------|--------|
| `POST /api/auth/login` | 前端 -> Java | 用户认证 | 就绪 |
| `GET /api/agents` | 前端 -> Java | 列出 Agent | 就绪 |
| `POST /api/agents` | 前端 -> Java | 创建 Agent | 就绪 |
| `PUT /api/agents/{id}` | 前端 -> Java | 更新 Agent | 就绪 |
| `DELETE /api/agents/{id}` | 前端 -> Java | 删除 Agent | 就绪 |
| `GET /api/agents/{id}/status` | 前端 -> Java | 循环状态 | 就绪 |

## 风险评估

### 已解决风险
- **RISK-001**: Python LLM 服务可用性 - 已解决 (服务已实现)
- **RISK-002**: Java-Python 集成契约 - 已解决 (契约已定义)

### 活跃风险 (Phase 2)
- **RISK-003**: E2E 集成测试覆盖 - 缓解中 (需要测试套件)
- **RISK-004**: WebSocket 连接稳定性 - 监控中

## 责任矩阵

| 组件 | 负责 Agent | 依赖 | 当前状态 |
|-----------|------------|--------------|---------------|
| 认证 | Java-Backend | 无 | 已完成 |
| Agent CRUD | Java-Backend | 认证 | 已完成 |
| Agent 循环引擎 | Java-Backend | Python LLM | 已完成 |
| LLM 决策服务 | Python-AI-Side | 无 | 已完成 |
| Agent Lab UI | 前端 | Java API | 已完成 |
| 循环状态 UI | 前端 | Java WebSocket | 已完成 |
