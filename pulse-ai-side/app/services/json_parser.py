"""
JSON Parser Service

Handles parsing and validation of LLM JSON responses.
Extracts structured action decisions from potentially malformed output.
"""

import json
import logging
import re
from typing import Optional

from app.exceptions.errors import JSONParseError
from app.models.response import ActionDecision

logger = logging.getLogger(__name__)


class JSONParser:
    """
    JSON Parser for LLM responses.

    Handles:
    - Markdown code block extraction
    - Malformed JSON repair
    - Schema validation
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

        Raises: JSONParseError (caught by handler, returns ignore)
        """
        if not raw_content or not raw_content.strip():
            logger.warning("Empty content from LLM")
            return ActionDecision(action="ignore")

        # Step 1: Try to extract from markdown code blocks
        extracted = self._extract_from_markdown(raw_content)

        # Step 2: Try to find JSON object if extraction failed
        if not extracted:
            extracted = self._find_json_object(raw_content)

        # Step 3: Parse the extracted JSON
        try:
            parsed = json.loads(extracted)
        except json.JSONDecodeError as e:
            # Try repair before giving up
            repaired = self._attempt_repair(extracted)
            try:
                parsed = json.loads(repaired)
            except json.JSONDecodeError:
                raise JSONParseError(
                    raw_content=raw_content[:500],
                    parse_error=str(e),
                    response_time_ms=response_time_ms,
                )

        # Step 4: Create ActionDecision from parsed JSON
        return self._create_decision(parsed)

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
        # Find the first complete JSON object
        match = self.JSON_OBJECT_PATTERN.search(content)
        if match:
            return match.group(0)

        # Fallback: look for content starting with { and ending with }
        start_idx = content.find("{")
        end_idx = content.rfind("}")

        if start_idx != -1 and end_idx != -1 and end_idx > start_idx:
            return content[start_idx:end_idx + 1]

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

    def _create_decision(self, parsed: dict) -> ActionDecision:
        """
        Create ActionDecision from parsed JSON dict.

        Validates required fields and defaults to ignore for invalid.
        """
        action = parsed.get("action", "ignore")

        # Ensure action is valid
        if action not in ["post", "reply", "ignore"]:
            logger.warning(f"Unknown action '{action}', defaulting to ignore")
            action = "ignore"

        target_post_id = parsed.get("target_post_id")

        # Handle target_post_id - can be string or int
        if target_post_id is not None:
            try:
                target_post_id = int(target_post_id)
            except (TypeError, ValueError):
                target_post_id = None

        content = parsed.get("content")

        # Ensure content is string
        if content is not None:
            content = str(content).strip()

        decision = ActionDecision(
            action=action,
            target_post_id=target_post_id,
            content=content,
        )

        # Validate decision
        if not decision.is_valid():
            logger.warning(f"Invalid decision: {decision.model_dump()}, defaulting to ignore")
            return ActionDecision(action="ignore")

        return decision