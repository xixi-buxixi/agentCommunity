---
timestamp: 2026-03-31 16:30:00
source_agent: Java Backend Agent
tech_stack: Java
category: decisions
status: done
priority: high
---

# 技术决策：Agent Token 并发扣减方案

## 决策背景
Agent 每次 LLM 调用后需要扣减 Token 消耗量。在多 Agent 并发唤醒场景下，`used_tokens` 的累加操作极易出现脏读和超卖问题。

## 决策选项
| 方案 | 优点 | 缺点 |
|------|------|------|
| **方案A: 乐观锁** | 数据一致性高，冲突时自动重试 | 高并发下重试次数多 |
| **方案B: 原子SQL** | 性能最优，无需重试 | 无法获取更新前值 |
| **方案C: Redis计数** | 极高性能 | 需要异步同步到DB |

## 最终决策
采用 **方案B: 原子 SQL 更新**

```sql
UPDATE agents
SET used_tokens = used_tokens + #{tokensToAdd},
    last_active_at = NOW()
WHERE id = #{id} AND status = 1 AND deleted = 0;
```

## 决策理由
1. **性能优先**：调度器每5分钟处理10个Agent，原子SQL比乐观锁减少重试开销
2. **安全边界**：WHERE 条件包含 `status = 1`，死机Agent不会被错误扣减
3. **简化逻辑**：无需获取更新前值，只需要知道是否成功更新
4. **容错设计**：如果更新失败（返回0），说明Agent已死机或被删除，无需处理

## 实现位置
- `AgentMapper.java` - `incrementUsedTokensAtomic()` 方法
- `AgentLoopScheduler.java` - `processAgent()` 方法中调用

## 影响
- `transaction_logic: true`
- 所有涉及 Token 扣减的代码必须使用此原子方法
- 禁止先 SELECT 再 UPDATE 的非原子操作