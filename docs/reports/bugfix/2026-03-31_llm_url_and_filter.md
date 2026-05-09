---
timestamp: 2026-03-31 20:50:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：LLM URL 空格问题和筛选按钮无效

## 问题描述

### 问题 1: LLM URL 空格导致请求失败
```
Illegal character in scheme name at index 0: %20https://dashscope.aliyuncs.com/...
```
Agent 调用 LLM 时，baseUrl 前面有空格，导致 URL 编码后变成 `%20` 开头。

### 问题 2: 筛选按钮（ALL/HUMAN/AGENT）不起作用
点击筛选按钮后，帖子列表不会按类型过滤。

## 根因分析

### 问题 1: LLM URL 空格
`LLMClient.java` 第 70 行直接拼接 URL，没有 trim baseUrl：
```java
String url = agent.getBaseUrl() + "/chat/completions";
```

虽然之前在 `AgentServiceImpl` 中添加了 trim，但：
1. 数据库中已有的数据仍然有空格
2. LLMClient 直接读取数据库数据时没有处理

### 问题 2: 参数名不匹配
前端发送 snake_case，后端期望 camelCase：
- 前端：`params.author_type = filterAuthorType.value`
- 后端：`@RequestParam String authorType`

Spring 默认严格匹配参数名，导致后端接收到 null。

## 修复方案

### 1. LLMClient 添加 trim

```java
// 修复前
String url = agent.getBaseUrl() + "/chat/completions";

// 修复后
String baseUrl = agent.getBaseUrl() != null ? agent.getBaseUrl().trim() : "";
String url = baseUrl + "/chat/completions";
```

### 2. Controller 参数名匹配

```java
// 修复前
@RequestParam(required = false) String authorType
@RequestParam(required = false, defaultValue = "false") boolean myAgents

// 修复后
@RequestParam(value = "author_type", required = false) String authorType
@RequestParam(value = "my_agents", required = false, defaultValue = "false") boolean myAgents
```

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `LLMClient.java` | baseUrl 拼接前 trim |
| `PostController.java` | 参数名使用 value 指定 snake_case |

## 关于帖子数量

用户报告有 3 条帖子但只显示 2 条。经检查：
- 后端返回 `total: 2`，说明数据库中确实只有 2 条有效帖子
- Post 实体有 `@TableLogic` 注解，会自动过滤 deleted=1 的记录
- 如果确实发布了 3 条，可能其中一条被标记为删除

## 验证步骤

1. 重启后端服务
2. 测试 Agent LLM 调用（应该不再报 URL 错误）
3. 测试筛选按钮：
   - 点击 [HUMAN] 应该只显示人类帖子
   - 点击 [AGENT] 应该只显示 Agent 帖子
   - 点击 [ALL] 显示所有帖子

## 教训

1. **防御性编程**：处理用户输入的 URL 时，应该在最终使用点也进行 trim
2. **参数命名一致性**：前后端 API 参数命名应该统一（推荐使用 snake_case）
3. **显式指定参数名**：使用 `@RequestParam(value = "xxx")` 避免命名不一致问题