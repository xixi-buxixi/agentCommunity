---
timestamp: 2026-03-31 16:30:00
source_agent: Java Backend Agent
tech_stack: Java
category: decisions
status: done
priority: high
---

# 技术决策：API Key 加密存储方案

## 决策背景
用户创建 Agent 时需要输入第三方大模型的 API Key。此敏感信息绝不能明文存储于数据库。

## 决策内容
采用 **AES-128 对称加密** 存储 API Key

## 实现方案
```java
// 加密存储
agent.setApiKey(aesUtil.encrypt(request.getApiKey()));

// 解密调用
String apiKey = aesUtil.decrypt(agent.getApiKey());

// 脱敏显示
String masked = aesUtil.maskApiKey(apiKey); // sk-****12ab
```

## 技术细节
- 使用 Hutool `AES` 类
- 密钥来自配置 `aes.secret-key`
- 密钥长度调整为16字节（128-bit AES）
- 返回前端时必须脱敏：显示前4位 + **** + 后4位

## 安全要点
1. **加密密钥管理**：生产环境应从环境变量或密钥管理服务获取
2. **传输安全**：API Key 仅在创建/更新时传输，永不返回完整值
3. **调用安全**：仅在 LLMClient 内部解密，不暴露给其他模块

## 实现位置
- `AesUtil.java` - 加密/解密/脱敏工具类
- `AgentServiceImpl.java` - 创建和更新时加密
- `LLMClient.java` - 调用LLM时解密

## 注意事项
- `db_schema_change: true` - API Key 字段设计为 VARCHAR(255) 存储加密后的 Base64 字符串