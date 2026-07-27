"""
Tests for the secret redaction helpers.

These guard the most direct credential-leak path in the service: a validation error
on the api_key field used to echo the submitted key back to the caller.
"""

from app.utils.redaction import REDACTED, redact_text, safe_validation_errors


class TestRedactText:
    def test_openai_style_key_is_removed(self):
        result = redact_text("Incorrect API key provided: sk-abcdef1234567890")
        assert "sk-abcdef1234567890" not in result
        assert REDACTED in result

    def test_bearer_token_is_removed(self):
        result = redact_text("header Authorization: Bearer abcdef1234567890xyz")
        assert "abcdef1234567890xyz" not in result

    def test_labelled_secret_is_removed(self):
        result = redact_text('{"api_key": "zzzzzzzzzzzzzzzz"}')
        assert "zzzzzzzzzzzzzzzz" not in result

    def test_plain_text_is_untouched(self):
        assert redact_text("upstream returned 503") == "upstream returned 503"

    def test_non_string_passes_through(self):
        assert redact_text(None) is None
        assert redact_text(42) == 42


class TestSafeValidationErrors:
    def test_input_value_is_dropped(self):
        raw = [
            {
                "type": "string_too_short",
                "loc": ("body", "api_key"),
                "msg": "String should have at least 10 characters",
                "input": "sk-secret-value",
                "ctx": {"min_length": 10},
            }
        ]
        safe = safe_validation_errors(raw)
        assert safe == [
            {"loc": ["body", "api_key"], "msg": REDACTED, "type": "string_too_short"}
        ]
        # Nothing anywhere in the output may carry the submitted value
        assert "sk-secret-value" not in str(safe)

    def test_non_sensitive_field_keeps_its_message(self):
        raw = [
            {
                "type": "missing",
                "loc": ("body", "model_name"),
                "msg": "Field required",
                "input": {},
            }
        ]
        safe = safe_validation_errors(raw)
        assert safe[0]["msg"] == "Field required"
        assert safe[0]["loc"] == ["body", "model_name"]

    def test_message_containing_a_key_is_redacted(self):
        raw = [
            {
                "type": "value_error",
                "loc": ("body", "base_url"),
                "msg": "rejected token sk-livekey123456789",
                "input": "x",
            }
        ]
        safe = safe_validation_errors(raw)
        assert "sk-livekey123456789" not in safe[0]["msg"]
