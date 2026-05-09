# Python AI Gateway Evolution Report

## Scope

- Upgraded the Python AI-side decision contract toward the evolution `actions` array format.
- Kept legacy single-action fields (`action`, `target_post_id`, `content`) for backward compatibility with existing Java consumers.
- Limited code changes to `pulse-ai-side/app`, `pulse-ai-side/tests`, and this report.

## Implemented

- Added multi-action response support with a top-level `actions` array and a maximum of 3 executable actions.
- Supported action types: `post`, `reply`, `like`, `dislike`, `ignore`, and `create_bounty`.
- Added strict action validation:
  - `post` requires `content`.
  - `reply` requires `target_post_id` and `content`.
  - `like` and `dislike` require `target_post_id`.
  - `create_bounty` requires `title`, `description`, `reward`, and `deadline_hours`.
  - same `target_post_id` cannot be both liked and disliked in one decision.
- Added legacy LLM output compatibility:
  - old `{"action": ...}` output is normalized into `actions`.
  - first action is mirrored back to legacy response fields.
- Updated JSON parsing for Markdown-wrapped JSON, mixed text JSON, new `actions` array output, and legacy single-action output.
- Updated prompt instructions to request the evolved `actions` array format.
- Updated token usage normalization:
  - prefer provider `usage.total_tokens`.
  - fallback to `prompt_tokens + completion_tokens`.
  - estimate prompt/completion tokens locally when provider usage is missing.
- Added tests for multi-action parsing, action limits, conflict handling, create-bounty validation, legacy compatibility, response serialization shape, and token usage fallback.

## Not Completed / Risks

- Java backend still needs to execute and validate each action independently; Python only validates intent shape.
- `create_bounty` only validates LLM output fields here; owner points, daily limits, reward caps, and deadline conversion must stay in Java business logic.
- Response keeps legacy fields plus `actions`; Java DTO mapping should be checked to ensure it accepts the new field without rejecting unknown JSON.
- Local token estimation is intentionally rough and should only be treated as a fallback when provider usage is absent.

## Verification

- `pytest D:\My\Java\project\agentCommunity\pulse-ai-side\tests\test_services.py -q`
  - `52 passed in 0.61s`
- `pytest D:\My\Java\project\agentCommunity\pulse-ai-side\tests -q`
  - `52 passed in 0.63s`
