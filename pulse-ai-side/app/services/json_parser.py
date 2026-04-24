"""
JSON Parser Service

Handles parsing and validation of LLM JSON responses.
Extracts structured action decisions from potentially malformed output.

Enhanced features:
- Full raw output logging on parse failure
- Better error context for debugging
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
    JSON Parser for LLM responses.

    Handles:
    - Markdown code block extraction
    - Malformed JSON repair
    - Schema validation
    - Full logging of original output on failure
    """

    # Regex patterns for extracting JSON from various formats
    MARKDOWN_JSON_PATTERN = re.compile(
        r"```(?:json)?\s*\n?(.*?)\n?```",
        re.DOTALL | re.IGNORECASE,
    )

    # Pattern for finding JSON object boundaries
    JSON_OBJECT_PATTERN = re.compile(
        r"\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}",
        re.DOTALL,
    )

    def parse(self, raw_content: str, response_time_ms: Optional[int] = None) -> ActionDecision:
        """
        Parse raw LLM content into ActionDecision.

        Steps:
        1. Extract JSON from markdown code blocks (if present)
        2. Find JSON object boundaries (if not cleanly formatted)
        3. Parse JSON
        4. Validate and create ActionDecision

        Returns: ActionDecision (defaults to ignore on any error)

        Raises: JSONParseError (caught by handler, returns appropriate status)
        """
        if not raw_content or not raw_content.strip():
            logger.warning("Empty content from LLM")
            return ActionDecision(action="ignore")

        # Log the raw input for traceability
        logger.debug(f"Parsing LLM response, length={len(raw_content)}")

        # Step 1: Try to extract from markdown code blocks
        extracted = self._extract_from_markdown(raw_content)

        # Step 2: Try to find JSON object if extraction failed
        if not extracted:
            extracted = self._find_json_object(raw_content)

        # If we still can't find anything, raise with full content
        if not extracted:
            logger.error(
                f"JSON extraction failed - no JSON object found in response:\n"
                f"---RAW OUTPUT---\n{raw_content}\n---END---"
            )
            raise JSONParseError(
                raw_content=raw_content,  # Full content for debugging
                parse_error="No JSON object found in response",
                response_time_ms=response_time_ms,
            )

        # Step 3: Parse the extracted JSON
        try:
            parsed = json.loads(extracted)
        except json.JSONDecodeError as e:
            # Log the extraction result and original content
            logger.error(
                f"JSON decode failed:\n"
                f"Parse error: {str(e)}\n"
                f"Extracted content:\n---BEGIN---\n{extracted}\n---END---\n"
                f"Original raw content:\n---RAW---\n{raw_content[:1000]}\n---END---"
            )

            # Try repair before giving up
            repaired = self._attempt_repair(extracted)
            try:
                parsed = json.loads(repaired)
                logger.info("JSON repair successful")
            except json.JSONDecodeError as repair_error:
                # Final failure - log everything and raise with full context
                logger.error(
                    f"JSON repair also failed:\n"
                    f"Repair error: {str(repair_error)}\n"
                    f"Attempted repair:\n---REPAIR---\n{repaired}\n---END---"
                )
                raise JSONParseError(
                    raw_content=raw_content,  # Keep full original for debugging
                    parse_error=f"{str(e)} | repair attempt: {str(repair_error)}",
                    response_time_ms=response_time_ms,
                )

        # Step 4: Create ActionDecision from parsed JSON
        return self._create_decision(parsed, raw_content)

    def _extract_from_markdown(self, content: str) -> Optional[str]:
        """
        Extract JSON from markdown code blocks.

        Handles:
        - ```json ... ```
        - ``` ... ```
        """
        match = self.MARKDOWN_JSON_PATTERN.search(content)
        if match:
            return match.group(1).strip()

        # Fallback: look for triple backticks without 'json' label
        if "```" in content:
            parts = content.split("```")
            if len(parts) >= 2:
                # Content between first pair of backticks
                candidate = parts[1].strip()
                # Skip language label if present (e.g., "json\n{...}")
                if "\n" in candidate:
                    candidate = candidate.split("\n", 1)[1].strip()
                if candidate.startswith("{"):
                    return candidate

        return None

    def _find_json_object(self, content: str) -> Optional[str]:
        """
        Find JSON object in content without markdown wrapper.

        Handles cases where LLM outputs JSON directly but may have
        extra text before/after.
        """
        # Prefer broad boundaries so nested actions[] objects stay intact.
        start_idx = content.find("{")
        end_idx = content.rfind("}")

        if start_idx != -1 and end_idx != -1 and end_idx > start_idx:
            return content[start_idx:end_idx + 1]

        # Fallback: find the first simple JSON object.
        match = self.JSON_OBJECT_PATTERN.search(content)
        if match:
            return match.group(0)

        return None

    def _attempt_repair(self, malformed: str) -> str:
        """
        Attempt basic repairs on malformed JSON.

        Common issues:
        - Missing quotes on keys
        - Single quotes instead of double
        - Trailing commas
        - Unquoted string values
        """
        repaired = malformed

        # Replace single quotes with double quotes (for simple cases)
        # Be careful not to break escaped quotes
        repaired = re.sub(r"'([^']*)'", r'"\1"', repaired)

        # Remove trailing commas before } or ]
        repaired = re.sub(r",\s*([}\]])", r"\1", repaired)

        # Add quotes to unquoted keys (common LLM mistake)
        # Pattern: {key: value} -> {"key": value}
        repaired = re.sub(r"\{(\w+):", r'{"\1":', repaired)
        repaired = re.sub(r",(\w+):", r',"\1":', repaired)

        return repaired

    def _create_decision(self, parsed: dict, raw_content: Optional[str] = None) -> ActionDecision:
        """
        Create ActionDecision from parsed JSON dict.

        Supports evolved {"actions": [...]} and legacy {"action": "..."}.
        Pydantic validators normalize invalid actions to ignore.
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
                    f"Invalid target_post_id '{target_post_id}', setting to None"
                )
        return None

    def _coerce_int(self, value: object) -> Optional[int]:
        if value is not None:
            try:
                return int(value)
            except (TypeError, ValueError):
                logger.warning(f"Invalid integer value '{value}', setting to None")
        return None

    def _coerce_text(self, value: object) -> Optional[str]:
        if value is None:
            return None
        return str(value).strip()
