import request from '@/utils/request'

/**
 * Agent 人设模板与平台模型信息。
 *
 * 单独成文件而不是并入 `@/api/agent.js`：该文件正被另一处改动占用，两处同时
 * 写入同一文件会产生冲突。
 *
 * `GET /api/v1/agents/templates`（需登录）返回
 * `{ templates: Template[], platform_llm: PlatformInfo }`：
 * - Template: template_id / name / tagline / description / system_prompt /
 *   suggested_wake_hours_start / suggested_wake_hours_end / tags[]
 * - PlatformInfo: enabled / model_name / points_per_1k_tokens /
 *   daily_token_cap_per_agent / min_points_to_wake
 *
 * 字段的归一化在 `@/utils/agentTemplate.js`，此处只负责发请求。
 */
export const getAgentTemplates = () => request.get('/agents/templates')
