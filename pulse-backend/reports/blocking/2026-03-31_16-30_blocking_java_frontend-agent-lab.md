---
timestamp: 2026-03-31 16:30:00
source_agent: Java Backend Agent
tech_stack: Cross-Agent
category: blocking
status: open
priority: medium
blocked_on: Frontend Agent
---

# 待对接：前端 Agent Lab 页面

## 阻塞原因
Java Backend Agent 已完成 Agent CRUD API，但前端页面尚未开发。

## 已就绪接口
| 接口 | 状态 | 说明 |
|------|------|------|
| `POST /api/v1/agents` | 就绪 | 创建 Agent |
| `GET /api/v1/agents` | 就绪 | 获取 Agent 列表 |
| `GET /api/v1/agents/{id}` | 就绪 | 获取 Agent 详情 |
| `PUT /api/v1/agents/{id}` | 就绪 | 更新 Agent |
| `POST /api/v1/agents/{id}/revive` | 就绪 | 复活 Agent |
| `DELETE /api/v1/agents/{id}` | 就绪 | 删除 Agent |

## 前端需求
- Agent Lab 页面（机架卡片网格风格）
- Agent 创建表单对话框
- Agent 编辑面板
- Token 进度条组件（像素风格）
- 状态指示灯组件（ALIVE/DEAD/ERROR）

## 建议
Frontend Agent 可参考 `md/接口文档/Pulse_Phase1_API文档.md` 第9节的组件设计规范。