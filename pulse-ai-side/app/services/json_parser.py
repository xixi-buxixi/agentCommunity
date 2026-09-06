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
from typing import Iterable, Optional

from app.exceptions.errors import JSONParseError
from app.models.response import (
    ActionDecision,
    AgentAction,
    NewTrait,
    ReflectionResult,
    UpdatedTrait,
)

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

        parsed = self._extract_json_object(raw_content)

        if parsed is None:
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

    def _extract_json_object(self, raw_content: str) -> Optional[dict]:
        """
        从可能夹带 markdown、前后文、单引号或被截断的输出里提取一个 JSON 对象。

        决策与反思两条链路共用同一套提取/修复逻辑：模型的畸形输出方式与它在回答
        什么问题无关，两边各写一套只会让其中一套逐渐落后。
        """
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

        if not isinstance(parsed, dict):
            return None

        return parsed

    def parse_reflection(
        self,
        raw_content: str,
        allowed_trait_ids: Optional[Iterable[int]] = None,
        max_new_traits: int = 5,
        max_total_traits: int = 30,
        existing_trait_count: int = 0,
        response_time_ms: Optional[int] = None,
    ) -> ReflectionResult:
        """
        将反思输出解析为 ReflectionResult（复用与决策相同的提取/修复框架）。

        安全约束：
        - 提取失败抛 JSONParseError，由路由降级为 success=false + 空列表；
        - updated_traits / deprecated_trait_ids 的 id 必须出现在 allowed_trait_ids
          里（即后端本次传入的 existing_traits），模型编造的 id 直接丢弃——
          否则后端会拿一个未经校验的主键去 UPDATE 别人的记忆卡片；
        - 单条格式非法只丢该条，不让整次反思白跑；
        - 条数超过 limits 时截断，而不是原样交给后端。
        """
        allowed = {int(i) for i in (allowed_trait_ids or [])}

        if not raw_content or not raw_content.strip():
            logger.warning("反思调用返回空内容")
            raise JSONParseError(
                raw_content=raw_content,
                parse_error="反思响应为空",
                response_time_ms=response_time_ms,
            )

        parsed = self._extract_json_object(raw_content)
        if parsed is None:
            logger.error(
                "反思 JSON 提取失败 - 未找到 JSON 对象：\n"
                f"---原始输出---\n{raw_content}\n---结束---"
            )
            raise JSONParseError(
                raw_content=raw_content,
                parse_error="未找到 JSON 对象",
                response_time_ms=response_time_ms,
            )

        self._require_reflection_shape(parsed, raw_content, response_time_ms)

        deprecated_ids = self._coerce_trait_ids(parsed.get("deprecated_trait_ids"), allowed)
        deprecated_set = set(deprecated_ids)

        updated = self._coerce_updated_traits(parsed.get("updated_traits"), allowed, deprecated_set)
        new_traits = self._coerce_new_traits(parsed.get("new_traits"))

        # 总量上限：废弃的卡片会腾出位置，所以按"废弃后的存量"计算剩余额度。
        surviving = max(existing_trait_count - len(deprecated_ids), 0)
        room_left = max(max_total_traits - surviving, 0)
        allowance = max(min(max_new_traits, room_left), 0)
        if len(new_traits) > allowance:
            logger.warning(
                "反思返回 %d 条新特质，超出额度 %d（max_new=%d, 总量上限=%d），已截断",
                len(new_traits),
                allowance,
                max_new_traits,
                max_total_traits,
            )
            new_traits = new_traits[:allowance]

        return ReflectionResult(
            new_traits=new_traits,
            updated_traits=updated,
            deprecated_trait_ids=deprecated_ids,
        )

    # 反思输出的三个契约键
    REFLECTION_KEYS = ("new_traits", "updated_traits", "deprecated_trait_ids")

    def _require_reflection_shape(
        self,
        parsed: dict,
        raw_content: str,
        response_time_ms: Optional[int] = None,
    ) -> None:
        """
        在把任何键默认成 [] 之前先校验形状，键存在性与类型都要查。

        为什么必须两者都查：
        - `{"reason":"我无法反思"}` 三个键全无——若默认成三个空列表，后端拿到的是与
          "确实没什么可提炼"完全无法区分的"空成功"：照常计费、记 REFLECTION_SUCCESS、
          当日再也不会重试。
        - `{"new_traits":"none"}` / `{"updated_traits":null}` 键在但类型不对——下游的
          `isinstance(value, list)` 守卫会安静地把它当空列表，于是同一个"伪装成功"
          从另一条路回来了。类型不符同样判形状失败。

        存在且类型正确的键有至少一个时，模型确实回答了这个问题，其余键允许省略。
        """
        present = [key for key in self.REFLECTION_KEYS if key in parsed]

        if not present:
            self._raise_reflection_shape_error(
                raw_content,
                f"反思输出缺少 {'/'.join(self.REFLECTION_KEYS)}",
                "反思输出缺少全部三个契约键",
                response_time_ms,
            )

        malformed = [key for key in present if not isinstance(parsed.get(key), list)]
        if malformed:
            self._raise_reflection_shape_error(
                raw_content,
                f"反思输出的 {'/'.join(malformed)} 不是数组",
                f"反思输出的 {'/'.join(malformed)} 不是数组（类型不符）",
                response_time_ms,
            )

    def _raise_reflection_shape_error(
        self,
        raw_content: str,
        parse_error: str,
        log_reason: str,
        response_time_ms: Optional[int],
    ) -> None:
        logger.error(
            f"{log_reason}，按解析失败处理：\n"
            f"---原始输出---\n{raw_content}\n---结束---"
        )
        raise JSONParseError(
            raw_content=raw_content,
            parse_error=parse_error,
            response_time_ms=response_time_ms,
        )

    def _coerce_trait_ids(self, value: object, allowed: set) -> list:
        """把 deprecated_trait_ids 规范化为去重、已校验的 id 列表。"""
        if not isinstance(value, list):
            return []
        result: list = []
        seen = set()
        for item in value:
            trait_id = self._coerce_int(item)
            if trait_id is None or trait_id < 1:
                continue
            if trait_id not in allowed:
                logger.warning("丢弃未在 existing_traits 中出现的 deprecated id=%s", trait_id)
                continue
            if trait_id in seen:
                continue
            seen.add(trait_id)
            result.append(trait_id)
        return result

    def _coerce_updated_traits(
        self,
        value: object,
        allowed: set,
        deprecated: set,
    ) -> list:
        if not isinstance(value, list):
            return []
        result: list = []
        seen = set()
        for item in value:
            if not isinstance(item, dict):
                continue
            trait_id = self._coerce_int(item.get("id"))
            if trait_id is None or trait_id < 1 or trait_id not in allowed:
                logger.warning("丢弃无效的 updated_traits.id=%s", item.get("id"))
                continue
            if trait_id in deprecated:
                # 同一条卡片既要修订又要废弃：以废弃为准，修订丢弃。
                logger.warning("id=%s 同时出现在废弃与修订列表，按废弃处理", trait_id)
                continue
            if trait_id in seen:
                continue
            content = self._coerce_text(item.get("content"), self.MAX_TRAIT_CONTENT_LENGTH)
            if not content:
                logger.warning("丢弃缺少 content 的 updated_traits.id=%s", trait_id)
                continue
            seen.add(trait_id)
            result.append(
                UpdatedTrait(
                    id=trait_id,
                    content=content,
                    # Explicit None (never "" and never dropped): the backend treats a
                    # missing evidence on a revision as "clear the old evidence",
                    # because evidence describing the previous wording would document
                    # the revised card incorrectly. A blank string is normalised to
                    # None so both spellings of "not provided" reach it identically.
                    evidence=self._coerce_evidence(item.get("evidence")),
                    importance_score=self._coerce_score(item.get("importance_score")),
                    confidence_score=self._coerce_score(item.get("confidence_score")),
                )
            )
        return result

    def _coerce_evidence(self, value: object) -> Optional[str]:
        """Normalize evidence: absent, null or blank all become None."""
        text = self._coerce_text(value, self.MAX_TRAIT_CONTENT_LENGTH)
        return text or None

    def _coerce_new_traits(self, value: object) -> list:
        if not isinstance(value, list):
            return []
        result: list = []
        for item in value:
            if not isinstance(item, dict):
                continue
            content = self._coerce_text(item.get("content"), self.MAX_TRAIT_CONTENT_LENGTH)
            if not content:
                logger.warning("丢弃缺少 content 的 new_traits 条目")
                continue
            result.append(
                NewTrait(
                    content=content,
                    evidence=self._coerce_evidence(item.get("evidence")),
                    importance_score=self._coerce_score(item.get("importance_score")),
                    confidence_score=self._coerce_score(item.get("confidence_score")),
                )
            )
        return result

    def _coerce_score(self, value: object, default: int = 50) -> int:
        """把评分夹到 0-100；缺失或非法时取中位默认值而不是让整条被丢弃。"""
        score = self._coerce_int(value)
        if score is None:
            return default
        return max(0, min(100, score))

    def _attempt_repair(self, malformed: str) -> str:
        r"""
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

    @classmethod
    def _close_truncated(cls, text: str) -> str:
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

        if in_string:
            # 停在字符串中间：内容本身可能被截断，但至少是完整的 token 边界，
            # 补上引号即可（内容偏短由上层的截断逻辑负责）。
            repaired += '"'
        else:
            # 停在字符串外面时，尾巴可能是被截断的**数字或字面量**——
            # 例如 target_post_id 原本是 123，截断成 12 后如果直接补括号，
            # 会得到一个语法合法但语义错误的 JSON（去操作 12 号帖子），
            # reward 被截成 10 同理会创建错误金额的悬赏。
            # 因此这里把不完整的尾部键值整段丢掉，而不是猜它的值。
            repaired = cls._drop_incomplete_tail(repaired)

        repaired = re.sub(r',\s*$', '', repaired)
        repaired = re.sub(r'"\s*:\s*$', '": null', repaired)
        for opener in reversed(stack):
            repaired += "}" if opener == "{" else "]"
        return repaired

    # 结尾疑似被截断的裸 token（数字 / true / false / null 的前缀）
    _TRAILING_TOKEN = re.compile(r'(-?\d[\d.eE+-]*|t(?:r(?:u(?:e)?)?)?|f(?:a(?:l(?:s(?:e)?)?)?)?|n(?:u(?:l(?:l)?)?)?)$')

    @classmethod
    def _drop_incomplete_tail(cls, text: str) -> str:
        """
        丢弃结尾那个可能被截断的裸 token 及其键。

        无法从文本判断 `12` 是完整的 12 还是被截断的 123，所以只能整段丢弃：
        宁可少一个字段（上层会退化为 ignore 或用默认值），也不能拿一个错误的
        帖子 id 或悬赏金额去执行动作。
        """
        stripped = text.rstrip()
        if not cls._TRAILING_TOKEN.search(stripped):
            return text

        # 回退到上一个逗号或容器起始处，把 "key": <被截断的值> 一起去掉
        cut = max(stripped.rfind(','), stripped.rfind('{'), stripped.rfind('['))
        if cut < 0:
            return stripped
        if stripped[cut] == ',':
            return stripped[:cut]
        return stripped[: cut + 1]

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
            target_comment_id=self._coerce_post_id(
                parsed.get("target_comment_id"), field="target_comment_id"
            ),
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
            # Same normalisation as the post id: the response model requires ge=1, and a
            # hallucinated 0 would fail the whole action rather than the one field.
            target_comment_id=self._coerce_post_id(
                parsed.get("target_comment_id"), field="target_comment_id"
            ),
            content=self._coerce_text(parsed.get("content")),
            title=self._coerce_text(parsed.get("title"), self.MAX_TITLE_LENGTH),
            description=self._coerce_text(parsed.get("description"), self.MAX_DESCRIPTION_LENGTH),
            reward=self._coerce_int(parsed.get("reward")),
            deadline_hours=self._coerce_int(parsed.get("deadline_hours")),
        )

    def _coerce_post_id(
        self, target_post_id: object, field: str = "target_post_id"
    ) -> Optional[int]:
        """
        规范化行号型 id（target_post_id / target_comment_id）。

        响应模型要求 ge=1，所以 0 和负数必须在这里被挡掉：模型幻觉出
        target_post_id=0 时，原实现会原样传下去，导致整批决策校验失败。

        `field` 只用于日志：两个字段的规则完全相同，但日志需要说明是哪一个被丢弃的。
        """
        if target_post_id is not None:
            try:
                value = int(target_post_id)
            except (TypeError, ValueError):
                logger.warning(
                    f"无效的 {field} '{target_post_id}'，设置为 None"
                )
                return None
            if value < 1:
                logger.warning(f"{field} 超出范围 '{value}'，设置为 None")
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
    # NewTrait/UpdatedTrait 的 content/evidence 上限
    MAX_TRAIT_CONTENT_LENGTH = 500

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
