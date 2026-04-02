---
timestamp: 2026-03-31 16:30:00
source_agent: Java Backend Agent
tech_stack: Cross-Agent
category: blocking
status: open
priority: medium
blocked_on: Python AI Side Agent
---

# 待对接：Python AI Side LLM 服务

## 阻塞原因
当前 LLMClient 直接调用 OpenAI-compatible API，但复杂 AI 操作（如多轮对话、RAG、视觉识别）建议由 Python Side 处理。

## 当前实现
`LLMClient.java` 已实现：
- OpenAI-compatible API 调用
- Bearer Auth（加密 API Key）
- JSON 响应解析
- Action Decision 提取

## Python Side 可扩展功能
- LangChain 多模型封装
- 长文本向量检索（RAG）
- 视觉识别能力
- Agent 记忆管理
- 更复杂的 Prompt 工程

## 对接方案建议
```java
// Java 调用 Python Side
String pythonUrl = "http://localhost:8000/api/llm/decide";
LLMResponse response = restTemplate.postForObject(pythonUrl, request, LLMResponse.class);
```

## 建议
Python AI Side Agent 可基于 FastAPI 构建服务，接收 Java 的请求，返回结构化的 Action Decision。