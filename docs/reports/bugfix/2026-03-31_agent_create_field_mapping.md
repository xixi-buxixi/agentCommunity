---
timestamp: 2026-03-31 20:00:00
source_agent: Human (Bug Report)
category: bugfix
priority: critical
status: fixed
---

# Bug 修复：Agent 创建参数验证失败

## 问题描述
前端发送正确的 Agent 创建请求数据，但后端返回 400 错误，提示所有字段为空。

## 错误请求（前端发送）
```json
{
  "name": "暴躁老头",
  "avatar_url": "",
  "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "api_key": "sk-0395559857e04d5c8d9a51812a17fb76",
  "model_name": "qwen3.5-plus",
  "system_prompt": "你是一个非常暴躁的老头...",
  "token_threshold": 100000,
  "is_unlimited": false
}
```

## 错误响应（后端返回）
```json
{
  "code": 400,
  "message": "参数验证失败",
  "data": {
    "systemPrompt": "系统提示词不能为空",
    "modelName": "模型名称不能为空",
    "baseUrl": "API Base URL不能为空",
    "apiKey": "API Key不能为空"
  }
}
```

## 根因分析
**问题：** 前后端 JSON 字段命名风格不一致

- **前端发送：** snake_case（如 `api_key`, `base_url`）
- **后端期望：** camelCase（如 `apiKey`, `baseUrl`）

Jackson 默认使用 Java 字段名（camelCase）进行反序列化，导致前端发送的 snake_case 字段无法映射到后端字段。

## 修复方案
在后端 DTO 字段上添加 `@JsonProperty` 注解，接受 snake_case 格式的 JSON：

```java
@JsonProperty("api_key")
private String apiKey;

@JsonProperty("base_url")
private String baseUrl;

@JsonProperty("model_name")
private String modelName;

@JsonProperty("system_prompt")
private String systemPrompt;

@JsonProperty("avatar_url")
private String avatarUrl;

@JsonProperty("token_threshold")
private Long tokenThreshold;

@JsonProperty("is_unlimited")
private Boolean isUnlimited;
```

## 修改文件
| 文件 | 修改内容 |
|------|----------|
| `AgentCreateRequest.java` | 添加 @JsonProperty 注解 |
| `AgentUpdateRequest.java` | 添加 @JsonProperty 注解 |
| `AgentDeleteRequest.java` | 添加 @JsonProperty("confirm_name") |
| `AgentReviveRequest.java` | 添加 @JsonProperty("new_threshold") |

## 教训
1. **API 设计一致性：** 从一开始就明确定义 JSON 字段命名规范（推荐 snake_case 用于 API）
2. **后端适配：** 在 DTO 上使用 `@JsonProperty` 注解明确指定 JSON 字段名
3. **接口文档同步：** 确保 API 文档、前端代码、后端代码使用相同的字段命名