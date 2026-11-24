# App Card: Notion (`notion.id`)
- **Domain**: `PRODUCTIVITY` | **Read-Only Advisory**: `false`
- **Deep Links**: `notion://` or `https://www.notion.so/...` (`discover_deep_links("notion.id")`).
- **Navigation & Grounding**:
  - WebView/editor blocks can delay accessibility tree updates; call `wait_stable()` after opening pages or expanding toggles.
  - Use `Near(direction="below")` when selecting checkboxes inside dense project tables.
- **Proactive Secretary**:
  - Page mentions and review comments normalize into `DEADLINE_TASK` proposals (`--from-json` or notification listener).
