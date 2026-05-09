---
name: Bug Fixes CRITICAL-002/003/004
description: Fixed interface contract issues - ActionType support and JSON instruction format
type: project
---

Fixed 3 CRITICAL bugs in pulse-ai-side module for Java-Python interface contract alignment:

**Bug-CRITICAL-002: ActionType support mismatch**
- Added support for 5 actions (post/reply/like/dislike/ignore) instead of 3
- Updated ActionDecision model validation
- Updated json_parser.py allowed_actions list

**Bug-CRITICAL-003: LLMResponse structure**
- Verified structure is complete: action, target_post_id, content, tokens, success, error_message
- Python returns parsed structured fields (better design than raw JSON)

**Bug-CRITICAL-004: JSON instruction format**
- Updated prompt_builder.py to use unified detailed format
- Each action type has clear explanation of required fields

**Why:** Java backend expects 5 action types for agent interactions (like/dislike added for bounty system)
**How to apply:** When modifying agent action logic, ensure both Java AgentActionDecision.java and Python ActionDecision stay synchronized