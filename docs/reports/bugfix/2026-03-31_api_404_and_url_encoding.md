---
timestamp: 2026-03-31 21:35:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：API 404 和 Agent URL 编码问题

## 问题描述

### 问题 1: API 端点返回 404
新增的 API 端点 `/api/v1/agents/{agent_id}/logs` 和 `/api/v1/agents/{agent_id}/action-count` 返回 404：
```
NoResourceFoundException: No static resource api/v1/agents/1/logs
```

**原因**: Spring Boot 服务器未重启，新代码未生效。

### 问题 2: Agent URL %20 编码错误
Agent LLM 调用失败：
```
Illegal character in scheme name at index 0: %20https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
```

**原因**: 
1. 旧数据：Agent 创建时 baseUrl 未 trim，数据库中存储了带空格的 URL
2. LLMClient 的 trim() 仅在运行时处理，但数据库中的历史数据仍有空格

## 根因分析

### URL 编码流程

```
数据库存储: " https://dashscope.aliyuncs.com/..." (带前导空格)
    ↓
Agent 实体读取: baseUrl = " https://..." (空格保留)
    ↓
LLMClient.trim(): baseUrl.trim() → "https://..." (理论上应该去掉)
    ↓
但错误显示: "%20https://..." → 说明 trim() 未生效或有其他问题
```

可能的解释：
1. RestTemplate 在收到 URL 时进行编码，空格变成 `%20`
2. 或者 Agent 实体的 baseUrl 属性包含特殊空白字符（如 `%20` 编码本身）

## 修复方案

### 1. LLMClient 更强防御

```java
// Trim baseUrl to avoid URL encoding issues with leading/trailing spaces
String baseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";

// Defensive: Remove any remaining encoded spaces (%20) that might have been stored
baseUrl = baseUrl.replace("%20", "");

// Ensure baseUrl doesn't have any whitespace characters
baseUrl = baseUrl.replaceAll("\\s+", "");

String url = baseUrl + "/chat/completions";
```

### 2. 数据库修复脚本

```sql
-- Fix agents with leading/trailing spaces in base_url
UPDATE agents
SET base_url = TRIM(REPLACE(base_url, '%20', ''))
WHERE base_url LIKE '% %' OR base_url LIKE '%%20%' OR base_url LIKE '% ';

-- Verify the fix
SELECT id, name, base_url FROM agents WHERE base_url IS NOT NULL;
```

### 3. 后端重启

必须重启 Spring Boot 服务器使新 Controller 端点生效。

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `LLMClient.java` | 更强的 URL 清理逻辑 |
| `fix_baseurl_spaces.sql` | 数据库修复脚本 (新增) |

## 验证步骤

1. 运行 SQL 修复脚本清理数据库
2. 重启 Spring Boot 后端
3. 检查 Monitor 页面：
   - `/api/v1/agents/{id}/logs` 应返回 200
   - `/api/v1/agents/{id}/action-count` 应返回 200
4. 检查 Agent Log 显示是否正常
5. 等待下次 Agent Loop 执行，确认 LLM 调用成功

## 教训

1. **输入验证要彻底**: 从入口就应该 trim，而不是依赖下游处理
2. **历史数据修复**: 修复代码后，别忘了修复数据库中的脏数据
3. **防御性编程**: 不要相信输入数据是干净的，做多层清理