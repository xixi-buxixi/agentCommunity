"""
JSON 解析服务

处理 LLM JSON 响应的解析和验证。
从可能格式不正确的输出中提取结构化的动作决策。

增强功能：
- 解析失败时记录完整的原始输出
- 提供更好的错误上下文以便调试
"""

import ast
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

        # 首先尝试直接解析（函数调用通常返回干净的 JSON）
        try:
            parsed = json.loads(raw_content)
        except json.JSONDecodeError:
            pass

        # 如果直接解析失败或原始内容包含杂质，则使用正则模式。
        # 注意用 `is None` 而不是 `not parsed`：`{}` 与 `[]` 都是 falsy，
        # 原写法会把"成功解析出空对象"当成解析失败。
        if parsed is None:
            for pattern in self.PATTERNS:
                matches = re.findall(pattern, raw_content)
                for match in matches:
                    try:
                        # 清理并解析
                        cleaned = self._attempt_repair(match)
                        parsed = json.loads(cleaned)
                        break
                    except json.JSONDecodeError:
                        # 有些模型直接输出 Python 字典字面量（单引号），
                        # 用 literal_eval 精确处理，而不是用正则换引号
                        parsed = self._try_python_literal(match)
                        if parsed is not None:
                            break
                        continue
                if parsed is not None:
                    break

        # 最后兜底：所有模式都没匹配到，说明输出很可能被 max_tokens 截断，
        # 连闭合括号都没有。对整段内容做一次保守修复再试。
        if parsed is None:
            fenced = re.sub(r'^\s*```(?:json|JSON)?\s*', '', raw_content.strip())
            try:
                parsed = json.loads(self._attempt_repair(fenced))
            except json.JSONDecodeError:
                parsed = self._try_python_literal(fenced)

        # `[...]` 模式可能提取出 list，而后续逻辑按 dict 访问；
        # 没有这个守卫会抛 AttributeError 并变成 500。
        if isinstance(parsed, list):
            logger.warning("LLM 返回了 JSON 数组而非对象，按无动作处理")
            parsed = None

        if parsed is None or not isinstance(parsed, dict):
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
        对畸形 JSON 做保守修复。

        原实现有两条替换会破坏本来合法的 JSON：
          re.sub(r"'([^']*)'", ...)   -> 把英文撇号当字符串定界符：
                                          "it's fine, don't" 被改成 "it"s fine, don"t"
          re.sub(r"(\w+)(?=:)", ...)   -> 把 URL 的 scheme 当裸键加引号：
                                          "see https://x.com" 变成 "see "https"://x.com"
        社区内容里贴链接、写缩写都极常见，这两条是高频线上故障源，因此删除。
        这里只保留安全的修复：去掉尾随逗号、去掉零宽字符、补齐被截断的括号。
        """
        text = malformed.strip()
        # 去掉可能夹带的零宽字符
        text = text.replace("\u200b", "").replace("\ufeff", "")
        # 尾随逗号：{"a": 1,} / [1,2,]
        text = re.sub(r',\s*([}\]])', r'\1', text)
        return self._close_truncated(text)

    @staticmethod
    def _try_python_literal(text: str) -> Optional[dict]:
        """
        把 Python 字典字面量（{'action': 'post'}）解析为 dict。

        用 ast.literal_eval 而不是"把单引号替换成双引号"的正则：后者会把
        合法双引号字符串里的英文撇号也当成定界符，破坏本来能解析的内容。
        literal_eval 只做字面量求值，不执行任意代码。
        """
        try:
            value = ast.literal_eval(text.strip())
        except (ValueError, SyntaxError, MemoryError, RecursionError):
            return None
        return value if isinstance(value, dict) else None

    @staticmethod
    def _close_truncated(text: str) -> str:
        """
        补齐被 max_tokens 截断的 JSON。

        DEFAULT_MAX_TOKENS 只有 200，截断是常态而不是例外，而原实现完全没有
        处理截断的能力。这里在字符串外统计括号层级并补上缺失的闭合符号。
        """
        if not text:
            return text

        stack = []
        in_string = False
        escaped = False
        for char in text:
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char in "{[":
                stack.append(char)
            elif char in "}]":
                if stack and stack[-1] == ("{" if char == "}" else "["):
                    stack.pop()

        if not stack and not in_string:
            return text

        repaired = text
        # 截断可能停在键名或逗号后面，先去掉不完整的尾巴
        if in_string:
            repaired += '"'
        repaired = re.sub(r',\s*$', '', repaired)
        repaired = re.sub(r'"\s*:\s*$', '": null', repaired)
        for opener in reversed(stack):
            repaired += "}" if opener == "{" else "]"
        return repaired

    def _create_decision(self, parsed: dict, raw_content: Optional[str] = None) -> ActionDecision:
        """
        从解析的 JSON 字典创建 ActionDecision。

        支持演进版的 {"actions": [...]} 和旧版的 {"action": "..."}。
        Pydantic 验证器会将无效的动作规范化为 ignore。
        """
        reason = parsed.get("reason")
        actions_payload = parsed.get("actions")

        if isinstance(actions_payload, list):
            # 单条动作校验失败时只丢弃该条，其余继续执行；
            # 原实现让一条坏动作把整批决策一起废掉。
            actions = []
            for action_payload in actions_payload:
                if not isinstance(action_payload, dict):
                    continue
                try:
                    actions.append(self._create_action(action_payload))
                except Exception as exc:
                    logger.warning(f"丢弃无效动作：{exc}")
            return ActionDecision.from_actions(
                actions, reason=self._coerce_text(reason, self.MAX_DESCRIPTION_LENGTH)
            )

        return ActionDecision(
            action=str(parsed.get("action", "ignore")),
            target_post_id=self._coerce_post_id(parsed.get("target_post_id")),
            content=self._coerce_text(parsed.get("content")),
            title=self._coerce_text(parsed.get("title"), self.MAX_TITLE_LENGTH),
            description=self._coerce_text(parsed.get("description"), self.MAX_DESCRIPTION_LENGTH),
            reward=self._coerce_int(parsed.get("reward")),
            deadline_hours=self._coerce_int(parsed.get("deadline_hours")),
            reason=self._coerce_text(reason, self.MAX_DESCRIPTION_LENGTH),
        )

    def _create_action(self, parsed: dict) -> AgentAction:
        action_type = parsed.get("type", parsed.get("action", "ignore"))
        return AgentAction(
            type=str(action_type),
            target_post_id=self._coerce_post_id(parsed.get("target_post_id")),
            content=self._coerce_text(parsed.get("content")),
            title=self._coerce_text(parsed.get("title"), self.MAX_TITLE_LENGTH),
            description=self._coerce_text(parsed.get("description"), self.MAX_DESCRIPTION_LENGTH),
            reward=self._coerce_int(parsed.get("reward")),
            deadline_hours=self._coerce_int(parsed.get("deadline_hours")),
        )

    def _coerce_post_id(self, target_post_id: object) -> Optional[int]:
        """
        规范化 target_post_id。

        响应模型要求 ge=1，所以 0 和负数必须在这里被挡掉：模型幻觉出
        target_post_id=0 时，原实现会原样传下去，导致整批决策校验失败。
        """
        if target_post_id is not None:
            try:
                value = int(target_post_id)
            except (TypeError, ValueError):
                logger.warning(
                    f"无效的 target_post_id '{target_post_id}'，设置为 None"
                )
                return None
            if value < 1:
                logger.warning(f"target_post_id 超出范围 '{value}'，设置为 None")
                return None
            return value
        return None

    def _coerce_int(self, value: object) -> Optional[int]:
        if value is not None:
            try:
                return int(value)
            except (TypeError, ValueError):
                logger.warning(f"无效的整数值 '{value}'，设置为 None")
        return None

    # 与 response.py 中各字段的 max_length 对齐，超长时主动截断
    MAX_TEXT_LENGTH = 500
    MAX_TITLE_LENGTH = 100
    MAX_DESCRIPTION_LENGTH = 1000

    def _coerce_text(self, value: object, max_length: int = MAX_TEXT_LENGTH) -> Optional[str]:
        """
        规范化文本字段。

        主动截断而不是把超长内容交给 pydantic：那样会抛 max_length 校验错误，
        把"内容偏长"变成"整条决策丢失"。上限与 response.py 中对应字段一致。
        """
        if value is None:
            return None
        text = str(value).strip()
        if len(text) > max_length:
            logger.warning(f"内容超长（{len(text)} 字符），截断到 {max_length}")
            return text[:max_length]
        return text
