"""
JSON 解析服务

处理 LLM JSON 响应的解析和验证。
从可能格式不正确的输出中提取结构化的动作决策。

增强功能：
- 解析失败时记录完整的原始输出
- 提供更好的错误上下文以便调试
"""

import json
import logging
import re
from typing import Optional

from app.exceptions.errors import JSONParseError
from app.models.response import ActionDecision, AgentAction

logger = logging.getLogger(__name__)


class JSONParser:
    """
    LLM 响应的 JSON 解析器。

    处理：
    - Markdown 代码块提取
    - 畸形 JSON 修复
    - 模式验证
    - 失败时完整记录原始输出
    """

    # 正则表达式字符串提取优先级
    PATTERNS = [
        r'```json\s*([\s\S]*?)\s*```',
        r'```JSON\s*([\s\S]*?)\s*```',
        r'```\s*([\s\S]*?)\s*```',
        r'\{[\s\S]*\}',
        r'\[[\s\S]*\]',
        r'\{[^{}]*\}',
    ]

    def parse(self, raw_content: str, response_time_ms: Optional[int] = None) -> ActionDecision:
        """
        使用多级正则表达式模式将原始 LLM 内容解析为 ActionDecision。
        针对函数调用输出（直接 JSON）和回退行为进行优化。
        """
        if not raw_content or not raw_content.strip():
            logger.warning("LLM 返回空内容")
            return ActionDecision(action="ignore")

        logger.debug(f"解析 LLM 响应，长度={len(raw_content)}")

        # 步骤 1 和 2：一次性尝试提取和解析
        parsed = None
        extracted = None

        # 首先尝试直接解析（函数调用通常返回干净的 JSON）
        try:
            parsed = json.loads(raw_content)
            extracted = raw_content
        except json.JSONDecodeError:
            pass

        # 如果直接解析失败或原始内容包含杂质，则使用正则模式
        if not parsed:
            for pattern in self.PATTERNS:
                matches = re.findall(pattern, raw_content)
                for match in matches:
                    try:
                        # 清理并解析
                        cleaned = self._attempt_repair(match)
                        parsed = json.loads(cleaned)
                        extracted = match
                        break
                    except json.JSONDecodeError:
                        continue
                if parsed:
                    break

        if not parsed:
            logger.error(
                f"JSON 提取失败 - 在响应中未找到 JSON 对象：\n"
                f"---原始输出---\n{raw_content}\n---结束---"
            )
            raise JSONParseError(
                raw_content=raw_content,
                parse_error="未找到 JSON 对象或解析成功",
                response_time_ms=response_time_ms,
            )

        # 步骤 4：从解析的 JSON 创建 ActionDecision
        return self._create_decision(parsed, raw_content)

    def _attempt_repair(self, malformed: str) -> str:
        """
        尝试对畸形 JSON 进行基本修复。
        """
        text = malformed.strip()
        text = re.sub(r"'([^']*)'", r'"\1"', text)
        text = re.sub(r'(\w+)(?=:)', r'"\1"', text)
        text = re.sub(r',\s*([}\]])', r'\1', text)
        return text

    def _create_decision(self, parsed: dict, raw_content: Optional[str] = None) -> ActionDecision:
        """
        从解析的 JSON 字典创建 ActionDecision。

        支持演进版的 {"actions": [...]} 和旧版的 {"action": "..."}。
        Pydantic 验证器会将无效的动作规范化为 ignore。
        """
        reason = parsed.get("reason")
        actions_payload = parsed.get("actions")

        if isinstance(actions_payload, list):
            actions = [
                self._create_action(action_payload)
                for action_payload in actions_payload
                if isinstance(action_payload, dict)
            ]
            return ActionDecision.from_actions(actions, reason=reason)

        return ActionDecision(
            action=str(parsed.get("action", "ignore")),
            target_post_id=self._coerce_post_id(parsed.get("target_post_id")),
            content=self._coerce_text(parsed.get("content")),
            title=self._coerce_text(parsed.get("title")),
            description=self._coerce_text(parsed.get("description")),
            reward=self._coerce_int(parsed.get("reward")),
            deadline_hours=self._coerce_int(parsed.get("deadline_hours")),
            reason=reason,
        )

    def _create_action(self, parsed: dict) -> AgentAction:
        action_type = parsed.get("type", parsed.get("action", "ignore"))
        return AgentAction(
            type=str(action_type),
            target_post_id=self._coerce_post_id(parsed.get("target_post_id")),
            content=self._coerce_text(parsed.get("content")),
            title=self._coerce_text(parsed.get("title")),
            description=self._coerce_text(parsed.get("description")),
            reward=self._coerce_int(parsed.get("reward")),
            deadline_hours=self._coerce_int(parsed.get("deadline_hours")),
        )

    def _coerce_post_id(self, target_post_id: object) -> Optional[int]:
        if target_post_id is not None:
            try:
                return int(target_post_id)
            except (TypeError, ValueError):
                logger.warning(
                    f"无效的 target_post_id '{target_post_id}'，设置为 None"
                )
        return None

    def _coerce_int(self, value: object) -> Optional[int]:
        if value is not None:
            try:
                return int(value)
            except (TypeError, ValueError):
                logger.warning(f"无效的整数值 '{value}'，设置为 None")
        return None

    def _coerce_text(self, value: object) -> Optional[str]:
        if value is None:
            return None
        return str(value).strip()
