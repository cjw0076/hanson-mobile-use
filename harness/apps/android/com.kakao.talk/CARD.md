# App Card: 카카오톡 (`com.kakao.talk`)
- **Domain**: `MESSENGER` | **Read-Only Advisory**: `false` | **Send Gate**: Explicit human approval required before sending any message.
- **Deep Links**: `kakaotalk://` (verify via `discover_deep_links("com.kakao.talk")`).
- **Navigation & Grounding**:
  - Bottom tabs: `친구`, `채팅`, `오픈채팅`, `쇼핑`, `더보기`. Use `Near(direction="below", ...)` when multiple unread badges share the same row label.
  - Chat input box (`EditText`): prefer `:18888` Korean `type_text()` (uses `ACTION_SET_TEXT`); never tap `전송` without user confirmation.
  - Prefer background `RemoteInput` quick-reply (`secretary_act(..., action="reply")`) after human approval rather than waking the screen.
- **Noise & Privacy**:
  - Suppress KakaoTalk Channel `(광고)` promos, bare sticker/photo placeholders (`이모티콘`, `사진을 보냈습니다`), and OTP/2FA messages.
  - Multi-turn group chats merge via `NotificationThreadBuffer` (12 msgs / 30 min window).
