---
timestamp: 2026-04-11 14:30:00
source_agent: Summary-Agent
tech_stack: Cross-Agent
category: done
status: done
priority: high
---

# 项目健康检查报告

**检查时间**: 2026-04-11
**检查范围**: Java Backend, Python AI Side, Frontend (Vue 3)
**检查类型**: 跨模块集成健康检查

---

## 一、问题统计概览

| 严重程度 | 数量 | 占比 |
|----------|------|------|
| CRITICAL | 4 | 17.4% |
| HIGH | 8 | 34.8% |
| MEDIUM | 7 | 30.4% |
| LOW | 5 | 21.7% |
| **总计** | **24** | **100%** |

---

## 二、CRITICAL 级别问题 (4个)

### Bug-CRITICAL-001: Java 后端未调用 Python AI 网关

| 属性 | 值 |
|------|-----|
| 位置 | `LLMClient.java` (整个文件) |
| 问题描述 | Java 直接调用 OpenAI API，完全绕过 Python 网关 |
| 影响范围 | Python 网关的安全增强、Prompt 注入检测、JSON 解析等功能完全失效 |
| 跨模块联动 | Java <-> Python 架构断裂 |
| 建议修复 | 重构 LLMClient 调用 Python 网关 HTTP 接口 |

### Bug-CRITICAL-002: ActionType 支持范围不匹配

| 属性 | 值 |
|------|-----|
| 位置 | `Java AgentContext.java` vs `Python prompt_builder.py` |
| 问题描述 | Java 支持 5 种 action (post/reply/like/dislike/ignore)，Python 只支持 3 种 |
| 影响范围 | 如果切换到 Python 网关，like/dislike 无法执行 |
| 跨模块联动 | Java <-> Python 接口契约不匹配 |
| 建议修复 | 统一两端 ActionType 枚举定义 |

### Bug-CRITICAL-003: LLMResponse 结构不匹配

| 属性 | 值 |
|------|-----|
| 位置 | `Java LLMResponse.java` vs `Python response.py` |
| 问题描述 | Java 期望原始 JSON content，Python 返回已解析的结构化字段 |
| 影响范围 | 两端无法直接互换，数据结构不兼容 |
| 跨模块联动 | Java <-> Python 数据结构不兼容 |
| 建议修复 | 定义统一的响应 DTO 结构 |

### Bug-CRITICAL-004: JSON 指令格式不一致

| 属性 | 值 |
|------|-----|
| 位置 | `Java AgentContext.java` vs `Python prompt_builder.py` |
| 问题描述 | 两端给 LLM 的 JSON 格式指令完全不同 |
| 影响范围 | LLM 返回的 JSON 格式会不匹配 |
| 跨模块联动 | Java <-> Python Prompt 逻辑冲突 |
| 建议修复 | 抽取统一的 Prompt 模板到共享配置 |

---

## 三、HIGH 级别问题 (8个)

### Bug-HIGH-001: userId 字段名不匹配

| 属性 | 值 |
|------|-----|
| 位置 | `前端 auth.js` vs `Java AuthResponse.java` |
| 问题描述 | 前端使用 `user_id` (snake_case)，后端返回 `userId` (camelCase) |
| 影响范围 | 登录后用户信息无法正确解析 |
| 跨模块联动 | 前端 <-> Java |
| 建议修复 | 前端适配 camelCase 或后端配置 JSON 序列化为 snake_case |

### Bug-HIGH-002: Ledger API 缺失

| 属性 | 值 |
|------|-----|
| 位置 | `前端 ledger.js` vs `Java 后端` |
| 问题描述 | 前端调用 `/api/v2/ledger/me`，后端不存在此 API |
| 影响范围 | 积分账本页面 404 |
| 跨模块联动 | 前端 <-> Java |
| 建议修复 | 实现缺失的 Ledger API 端点 |

### Bug-HIGH-003: 数据库密码明文存储

| 属性 | 值 |
|------|-----|
| 位置 | `Java application.yml` line 15 |
| 问题描述 | 密码 `password: 200575` 明文存储 |
| 影响范围 | 安全漏洞 |
| 建议修复 | 使用环境变量或加密配置 |

### Bug-HIGH-004: 积分冻结逻辑错误

| 属性 | 值 |
|------|-----|
| 位置 | `Java BountyServiceImpl.java` |
| 问题描述 | 完成悬赏不解冻发布者的冻结积分 |
| 影响范围 | 用户积分被永久冻结 |
| 建议修复 | 在悬赏完成逻辑中添加冻结积分解冻 |

### Bug-HIGH-005: 悬赏过期未处理

| 属性 | 值 |
|------|-----|
| 位置 | `Java BountyServiceImpl.java` |
| 问题描述 | 无定时任务处理过期悬赏 |
| 影响范围 | 过期悬赏积分无法释放 |
| 建议修复 | 实现定时任务扫描并处理过期悬赏 |

### Bug-HIGH-006: Like 表缺少唯一索引

| 属性 | 值 |
|------|-----|
| 位置 | `Java Like.java` entity |
| 问题描述 | 应建立 `(author_type, author_id, post_id)` 唯一约束 |
| 影响范围 | 可能重复点赞 |
| 建议修复 | 添加数据库唯一索引 |

### Bug-HIGH-007: Dislike 表缺少唯一索引

| 属性 | 值 |
|------|-----|
| 位置 | `Java Dislike.java` entity |
| 问题描述 | 同上 |
| 影响范围 | 可能重复踩 |
| 建议修复 | 添加数据库唯一索引 |

### Bug-HIGH-008: Agent Token 耗尽边界检查

| 属性 | 值 |
|------|-----|
| 位置 | `Java Agent.java` |
| 问题描述 | `getTokenPercentage()` 缺少 `usedTokens` null 检查 |
| 影响范围 | 可能 NullPointerException |
| 建议修复 | 添加 null 检查和默认值处理 |

---

## 四、MEDIUM 级别问题 (7个)

### Bug-MEDIUM-001: 时间格式不一致

| 属性 | 值 |
|------|-----|
| 位置 | `Java AgentServiceImpl` vs `PostServiceImpl` |
| 问题描述 | 时间格式有 'Z' 和无 'Z' 两种 |
| 影响范围 | 前端解析不一致 |
| 跨模块联动 | Java <-> 前端 |
| 建议修复 | 统一时间格式为 ISO 8601 |

### Bug-MEDIUM-002: Prompt 构建逻辑重复

| 属性 | 值 |
|------|-----|
| 位置 | `Java AgentContext.java` vs `Python prompt_builder.py` |
| 问题描述 | Prompt 被双重增强 |
| 影响范围 | 资源浪费、可能冲突 |
| 跨模块联动 | Java <-> Python |
| 建议修复 | 确定单一 Prompt 构建责任方 |

### Bug-MEDIUM-003: Bounty Create 缺少长度验证

| 属性 | 值 |
|------|-----|
| 位置 | `前端 bounty.js` |
| 问题描述 | title/description 无长度限制 |
| 影响范围 | 可能发送超长数据 |
| 建议修复 | 前端添加表单验证 |

### Bug-MEDIUM-004: BountySubmission 缺少 deleted 字段

| 属性 | 值 |
|------|-----|
| 位置 | `Java BountySubmission.java` |
| 问题描述 | 物理删除而非逻辑删除 |
| 影响范围 | 与其他 entity 不一致 |
| 建议修复 | 添加 deleted 字段实现软删除 |

### Bug-MEDIUM-005: API Key 加密可能返回 null

| 属性 | 值 |
|------|-----|
| 位置 | `Java AesUtil.java` |
| 问题描述 | `encrypt()` 失败返回 null，未处理 |
| 影响范围 | API Key 可能为 null |
| 建议修复 | 抛出异常或返回空字符串 |

### Bug-MEDIUM-006: Agent Watch Mode 未实现

| 属性 | 值 |
|------|-----|
| 位置 | `前端 TerminalLogin.vue` |
| 问题描述 | AGENT_WATCH 协议未实现 |
| 影响范围 | 功能缺失 |
| 建议修复 | 实现 WebSocket AGENT_WATCH 消息处理 |

### Bug-MEDIUM-007: Bounty baseURL 硬编码

| 属性 | 值 |
|------|-----|
| 位置 | `前端 bounty.js` |
| 问题描述 | 环境变量替换逻辑不完整 |
| 影响范围 | 开发环境可能出错 |
| 建议修复 | 使用 VITE_API_BASE_URL 环境变量 |

---

## 五、LOW 级别问题 (5个)

| ID | 位置 | 问题描述 | 建议 |
|----|------|----------|------|
| LOW-001 | `RankingController.java` | 参数验证不严格 | 添加 @Valid 注解 |
| LOW-002 | 测试覆盖 | 缺少 LLMRequest 测试覆盖 | 添加单元测试 |
| LOW-003 | `LLMClient.java` | Timeout 配置可能不足 | 评估并调整超时值 |
| LOW-004 | 日志模块 | 缺少 API 日志敏感信息过滤 | 实现日志脱敏 |
| LOW-005 | 计算逻辑 | BigDecimal 精度问题 | 使用统一精度配置 |

---

## 六、跨模块联动问题汇总

| 模块交互 | 问题ID | 问题描述 |
|----------|--------|----------|
| Java <-> Python | CRITICAL-001 | Java 未调用 Python AI 网关，架构断裂 |
| Java <-> Python | CRITICAL-002 | ActionType 枚举不匹配 |
| Java <-> Python | CRITICAL-003 | LLMResponse 结构不兼容 |
| Java <-> Python | CRITICAL-004 | JSON 指令格式冲突 |
| Java <-> Python | MEDIUM-002 | Prompt 构建逻辑重复 |
| 前端 <-> Java | HIGH-001 | userId 字段命名不一致 |
| 前端 <-> Java | HIGH-002 | Ledger API 缺失 |
| Java <-> 前端 | MEDIUM-001 | 时间格式不一致 |

**跨模块问题占比**: 8/24 = 33.3%

---

## 七、建议修复顺序

### 第一优先级: CRITICAL 跨模块问题 (阻塞集成)

1. **CRITICAL-001**: 实现 Java -> Python 网关调用
2. **CRITICAL-002**: 统一 ActionType 枚举
3. **CRITICAL-003**: 统一 LLMResponse 数据结构
4. **CRITICAL-004**: 统一 Prompt 模板格式

### 第二优先级: HIGH 跨模块问题

5. **HIGH-001**: 统一字段命名规范
6. **HIGH-002**: 实现 Ledger API

### 第三优先级: HIGH 单模块问题

7. **HIGH-003**: 数据库密码加密
8. **HIGH-004**: 修复积分冻结逻辑
9. **HIGH-005**: 实现悬赏过期处理
10. **HIGH-006/007**: 添加唯一索引
11. **HIGH-008**: Token 边界检查

### 第四优先级: MEDIUM 问题

12. 按影响范围排序修复

### 第五优先级: LOW 问题

13. 持续改进阶段处理

---

## 八、检查结论

### 整体评估

项目存在 **严重的跨模块集成问题**，Java 后端与 Python AI 网关之间的集成基本断裂。这导致：

1. Python 侧的安全增强功能完全失效
2. 数据结构和接口契约不匹配
3. 前后端字段命名规范不统一

### 核心风险

- **安全风险**: 密码明文存储、绕过安全网关
- **数据风险**: 积分冻结逻辑错误可能导致用户资金损失
- **兼容风险**: 跨模块数据结构不兼容

### 建议行动

1. **立即**: 召集三方 Agent 评审会，确定统一的数据契约
2. **本周**: 修复所有 CRITICAL 级别问题
3. **下周**: 修复 HIGH 级别问题
4. **持续**: 建立 API 契约测试，防止回归

---

**报告生成**: Summary-Agent
**生成时间**: 2026-04-11 14:30:00