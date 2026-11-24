# App Card: 카카오 T (`com.kakao.taxi`)
- **Domain**: `MOBILITY` | **Read-Only Advisory**: `false` | **Checkout Gate**: NEVER tap final `호출하기` / `결제하기` without explicit human confirmation.
- **Deep Links**: `kakaot://` (`discover_deep_links("com.kakao.taxi")`).
- **Navigation & Grounding**:
  - Home tiles (`택시`, `블루`, `벤티`, `대리`, `주차`, `바이크`) frequently shift order with seasonal promos; match by text/contentDesc rather than raw coordinates.
  - Confirm pickup pin (`출발`) and destination (`도착`) separately before presenting fare estimate to the user.
- **Safety**:
  - Stop at the fare/vehicle confirmation sheet and hand off to human for dispatch or payment.
