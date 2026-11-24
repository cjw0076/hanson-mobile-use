# App Card: Slack (`com.Slack`)
- **Domain**: `WORK_CHAT` | **Read-Only Advisory**: `false` | **Send Gate**: Explicit human approval before posting to public/shared channels.
- **Deep Links**: `slack://open`, `slack://channel?team=...&id=...` (`discover_deep_links("com.Slack")`).
- **Navigation & Grounding**:
  - Bottom tabs: `Home`, `DMs`, `Activity`, `Later` / `More`.
  - Use `Near(direction="right")` or `parent_of` when disambiguating identical channel/thread reply buttons.
  - Thread composer often sits behind an animated soft keyboard; if `HiddenElementError` occurs, call `input_reset()` or dismiss sheet first.
- **Proactive Secretary**:
  - Watch for `"오늘까지"`, `"리뷰 부탁"`, `"회신 부탁"`, `"승인 요청"`, and Meet/Zoom/Huddle URLs (`DEADLINE_TASK`, `MEETING_LINK_PREP`).
  - Redact `xoxb-`/`xoxp-` tokens pasted in engineering threads before storing or syncing.
