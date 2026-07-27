"""
Secret redaction helpers.

Everything that leaves this service - HTTP response bodies and log lines alike -
passes through here first. The request body starts with an `api_key` field, so any
handler that echoes raw input or an upstream error message is one step away from
publishing a paying customer's credential.
"""

import re
from typing import Any, Dict, Iterable, List

# Common API key shapes: OpenAI-style sk-/rk-, Anthropic-style, Bearer tokens,
# and long opaque tokens that follow an explicit key label.
_SECRET_PATTERNS = [
    re.compile(r"\b(?:sk|rk|pk)-[A-Za-z0-9_\-]{8,}", re.IGNORECASE),
    re.compile(r"\bsk-ant-[A-Za-z0-9_\-]{8,}", re.IGNORECASE),
    re.compile(r"\bBearer\s+[A-Za-z0-9._\-]{12,}", re.IGNORECASE),
    re.compile(
        r"(?i)\b(api[_-]?key|apikey|authorization|service[_-]?token|secret|password)"
        r"\b\s*[:=]\s*[\"']?([A-Za-z0-9._\-]{8,})[\"']?"
    ),
]

REDACTED = "[REDACTED]"

# Field names whose values must never appear in a response or log
_SENSITIVE_FIELDS = {
    "api_key",
    "apikey",
    "authorization",
    "service_token",
    "secret",
    "password",
    "token",
}


def redact_text(value: Any) -> Any:
    """Replace anything that looks like a credential inside a string."""
    if not isinstance(value, str):
        return value

    redacted = value
    for pattern in _SECRET_PATTERNS:
        if pattern.groups >= 2:
            redacted = pattern.sub(lambda m: f"{m.group(1)}={REDACTED}", redacted)
        else:
            redacted = pattern.sub(REDACTED, redacted)
    return redacted


def _contains_sensitive_field(loc: Iterable[Any]) -> bool:
    return any(
        isinstance(part, str) and part.lower() in _SENSITIVE_FIELDS for part in loc
    )


def safe_validation_errors(errors: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Build a client-safe view of pydantic validation errors.

    pydantic v2 puts the rejected value in `input`, so returning `exc.errors()`
    verbatim echoes the submitted api_key whenever it fails a length or format
    check. Only location, message and type survive here - and the message itself
    is dropped for sensitive fields, because custom validators may quote the value.
    """
    safe: List[Dict[str, Any]] = []
    for error in errors:
        loc = tuple(error.get("loc", ()))
        sensitive = _contains_sensitive_field(loc)
        safe.append(
            {
                "loc": [str(part) for part in loc],
                "msg": REDACTED if sensitive else redact_text(error.get("msg", "")),
                "type": error.get("type", "value_error"),
            }
        )
    return safe
