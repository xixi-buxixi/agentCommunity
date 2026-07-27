"""
Regression tests for the JSON repair path (M8).

The old repair step ran two substitutions over every candidate string:
    re.sub(r"'([^']*)'", ...)   -> apostrophes became string delimiters
    re.sub(r"(\\w+)(?=:)", ...) -> a URL scheme became a quoted key
Both corrupted content that had parsed perfectly well, and because posting links
is routine in this community, that made them a frequent production failure.
"""

import json

import pytest

from app.exceptions.errors import JSONParseError
from app.services.json_parser import JSONParser


class TestNoLongerCorruptsValidJson:
    def setup_method(self):
        self.parser = JSONParser()

    def test_content_with_url_survives(self):
        raw = json.dumps({"action": "post", "content": "see https://example.com/x?y=1"})
        decision = self.parser.parse(raw)
        assert decision.content == "see https://example.com/x?y=1"

    def test_content_with_url_inside_code_fence_survives(self):
        raw = "```json\n" + json.dumps(
            {"action": "post", "content": "docs at https://x.com/a"}
        ) + "\n```"
        decision = self.parser.parse(raw)
        assert decision.content == "docs at https://x.com/a"

    def test_apostrophes_survive(self):
        raw = "```json\n" + json.dumps(
            {"action": "post", "content": "it's fine, don't worry"}
        ) + "\n```"
        decision = self.parser.parse(raw)
        assert decision.content == "it's fine, don't worry"


class TestStructuralRobustness:
    def setup_method(self):
        self.parser = JSONParser()

    def test_python_dict_literal_still_parses(self):
        decision = self.parser.parse("{'action': 'post', 'content': 'test'}")
        assert decision.action == "post"
        assert decision.content == "test"

    def test_trailing_comma_is_repaired(self):
        decision = self.parser.parse('```json\n{"action": "like", "target_post_id": 5,}\n```')
        assert decision.action == "like"
        assert decision.target_post_id == 5

    def test_truncated_json_is_closed(self):
        # DEFAULT_MAX_TOKENS is 200, so truncation mid-object is the normal case
        decision = self.parser.parse('{"action": "post", "content": "half a thought')
        assert decision.action == "post"
        assert decision.content.startswith("half a thought")

    def test_empty_object_is_not_treated_as_a_parse_failure(self):
        # `{}` is falsy: `if not parsed` used to report "no JSON found"
        decision = self.parser.parse("{}")
        assert decision.action == "ignore"

    def test_top_level_array_does_not_raise_attribute_error(self):
        # The `[...]` extraction pattern can yield a list; dict access on it
        # used to raise AttributeError and surface as a 500
        with pytest.raises(JSONParseError):
            self.parser.parse("[1, 2, 3]")

    def test_zero_post_id_is_dropped_instead_of_failing_validation(self):
        # response.py requires ge=1, so a hallucinated 0 must not reach it
        decision = self.parser.parse('{"action": "reply", "target_post_id": 0, "content": "hi"}')
        assert decision.target_post_id is None

    def test_negative_post_id_is_dropped(self):
        decision = self.parser.parse('{"action": "reply", "target_post_id": -5, "content": "hi"}')
        assert decision.target_post_id is None

    def test_overlong_content_is_truncated_not_rejected(self):
        long_text = "x" * 5000
        decision = self.parser.parse(json.dumps({"action": "post", "content": long_text}))
        assert decision.content is not None
        # Clamped to the response model's own max_length for `content`
        assert len(decision.content) <= JSONParser.MAX_TEXT_LENGTH

    def test_overlong_title_and_description_are_clamped_separately(self):
        # A bounty needs reward + deadline too, otherwise the response model
        # (correctly) degrades the whole action to ignore.
        decision = self.parser.parse(json.dumps({
            "action": "create_bounty",
            "title": "t" * 400,
            "description": "d" * 4000,
            "reward": 20,
            "deadline_hours": 24,
        }))
        assert decision.action == "create_bounty"
        assert len(decision.title) <= JSONParser.MAX_TITLE_LENGTH
        assert len(decision.description) <= JSONParser.MAX_DESCRIPTION_LENGTH

    def test_one_invalid_action_does_not_discard_the_whole_batch(self):
        raw = json.dumps({
            "actions": [
                {"type": "like", "target_post_id": 7},
                {"type": "reply", "target_post_id": "not-a-number", "content": "ok"},
            ]
        })
        decision = self.parser.parse(raw)
        assert decision.actions
        assert any(action.type == "like" for action in decision.actions)

    def test_empty_content_raises_nothing_and_ignores(self):
        assert self.parser.parse("").action == "ignore"
        assert self.parser.parse("   ").action == "ignore"

    def test_prose_without_json_raises_parse_error(self):
        with pytest.raises(JSONParseError):
            self.parser.parse("I think I will not act today.")
