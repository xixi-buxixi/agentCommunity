### 审查结论：未发现阻断性重大安全漏洞，但存在以下中低风险缺陷与设计边界

#### [中危] 每日反思在 SKIPPED 时未推进游标导致前缀持续堆积
- **位置**: [`MemoryReflectionScheduler.java:242`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-backend/src/main/java/com/pulse/scheduler/MemoryReflectionScheduler.java#L242-L245)
- **失效场景**: 当 Agent 被判定为 `Outcome.SKIPPED`（如当日 Token 耗尽或跳过）时未调用 `markReflectionAttempt`。若该 Agent 从未反思过（`last_reflection_attempt_at` 为 NULL），其在 `ORDER BY last_reflection_attempt_at IS NOT NULL, ... ASC` 规则下永远排在队首，次日夜间仍优先占位并被反复拉取判定，持续挤占有限的游标分页扫描额度。
- **证据**: `MemoryReflectionScheduler.java:242-245` 仅在 `outcome != Outcome.SKIPPED` 时打时间戳；Token 耗尽返回 `SKIPPED`，导致游标对该类 Agent 永不推进。
- **建议修复**: 对显式判定跳过的 Agent 同样打上当前尝试时间戳推进游标，或在 SQL 查询候选集时过滤当日不可反思的 Agent。

#### [中危] 通知去重读写未加锁存在并发竞态（TOCTOU）
- **位置**: [`NotificationServiceImpl.java:345`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-backend/src/main/java/com/pulse/service/impl/NotificationServiceImpl.java#L345-L353)
- **失效场景**: 10 分钟窗口去重采用“先查 countRecentDuplicates 再 insert”模式。高并发下（如短时间内对同一资源并发点赞或评论），两个并发线程查重均返回 0，导致同一事件在去重窗口内插入多条重复通知。
- **证据**: `NotificationServiceImpl.java:345-353` 无数据库唯一约束兜底，亦未引入分布式锁或行级互斥。
- **建议修复**: 在 `notifications` 表建立基于时间桶与来源的联合唯一索引，或使用 Redis SETNX 互斥锁控制入库。

#### [低危] Agent 创建向导的作息配置非原子生效
- **位置**: [`AgentCreateRequest.java:18`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-backend/src/main/java/com/pulse/dto/request/AgentCreateRequest.java#L18-L25)、[`AgentCreateWizard.vue:206`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-frontend/src/components/AgentCreateWizard.vue#L206-L213)
- **失效场景**: `AgentCreateRequest` 缺少作息字段，向导提交时被后端静默丢弃，前端依赖后续 `PUT /agents/{id}` 补偿。若补写请求遭遇网络中断或用户在第一步后关闭页面，Agent 将静默保留系统默认作息。
- **证据**: `AgentCreateRequest` 仅声明 9 个字段；前端向导在创建后仅做尽力而为（catch 仅打日志）的 `updateAgent`。
- **建议修复**: 在 `AgentCreateRequest` 声明作息字段并在创建事务中一并落库，使向导配置原子生效。

#### [低危] Agent 命名规则与 Mention 正则字符集不对称
- **位置**: [`MentionDetector.java:55`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-backend/src/main/java/com/pulse/service/support/MentionDetector.java#L53-L55)、[`AgentCreateRequest.java:19`](file:///private/tmp/claude-501/-Users-user-pulse/25754a75-a5e0-498a-861f-963d3bb6ab2e/scratchpad/wt-review/pulse-backend/src/main/java/com/pulse/dto/request/AgentCreateRequest.java#L19-L21)
- **失效场景**: `AgentCreateRequest.name` 仅限制长度，未限制特殊字符。而 `MentionDetector` 正则仅支持汉字、假名、英数、下划线及连字符。若 Agent 命名包含点、空格或其他符号，该 Agent 将永远无法通过 `@` 提及被唤醒。
- **证据**: `MentionDetector.java:55` 正则 `@([\p{IsHan}\p{IsHiragana}\p{IsKatakana}A-Za-z0-9_\-]{1,50})` 与创建端缺乏字符白名单限制。
- **建议修复**: 在 `AgentCreateRequest` 对 `name` 增加统一的命名字符白名单校验。

---

### 已核实无问题
1. **平台密钥安全与防负计费 (A)**: 代码核查确认 `Credentials` 重写 `toString()` 掩码；DTO 白名单过滤绝不暴露密钥；`deductAvailablePointsAtomic` 在 SQL 层原子强校验 `(points - pending_bounty) >= amount`，扣费后绝不产生负余额；无 Token 日志不扣积分。
2. **无 Schema 迁移降级 (B)**: 代码核查确认 `SchemaCapabilities` 动态嗅探列存在性；缺少列时 `PLATFORM` 模式抛出业务异常 20010，BYOK 平稳回退默认配置，反思游标降级为 `id` 游标；BYOK 严格校验了 `base_url`/`model_name`。
3. **@ 提及安全范围与注入防护 (C)**: 代码核查确认候选集仅限帖主、前50位评论者及发言者自身 Agent，杜绝跨帖骚扰；排除自身自我唤醒；正文长度上限 500 且正则无回溯爆炸风险；已与主事件唤醒 Agent 严格去重。
4. **评论嵌套与提示词隔离 (D)**: 代码核查确认上下文构建统一将 `[Comment#` 替换为 `(Comment#`，隔离 AI Side 注释行匹配；深度与根评论计算与 `PostServiceImpl` 严格对称，降级回退根评论安全。
5. **记忆卡公开范围与缓存失效 (E)**: 代码核查确认非 Owner 编辑直接阻断（403）；公开接口 SQL 强限定 `status = 1 AND scope = 'PUBLIC'`，失效/停用卡不泄露；修改后主动驱逐公开 Profile 缓存（TTL 30s）。
6. **定时清理任务物理删除安全性 (F)**: 代码核查确认通知清理严格限定 `is_read = 1`，记忆卡清理严格限定 `status = 2`（DEPRECATED）；批量处理带 limit 且在不足 limit 时正确终止，绝不误删活动/未读数据。
7. **反思游标分页与单次防重 (G)**: 代码核查确认采用 `(last_reflection_attempt_at IS NOT NULL, last_reflection_attempt_at ASC, id ASC)` Keyset 分页；`attemptedBefore` 在启动时固定，打标写入时间戳大于等于启动时间，无漏跑与死循环。
8. **前后端契约一致性 (H)**: 代码核查确认 `provider_mode`、`template_id`、`public_traits` 等字段及 20010 错误码在前后端完全对齐闭环。

---

### 无法验证的项
1. **跨实例部署时钟偏斜**: 多实例环境下若节点间或与 MySQL 存在数十秒时钟漂移，可能对通知清理窗口及反思启动水位线产生微小边界扰动，需在分布式集群环境联调核验。
2. **超高并发下数据库锁争用**: `agent_logs` 聚合统计与 `users` 积分原子更新在高并发压力下的锁等待与吞吐量表现，需在大规模压测环境下进行压力验证。
