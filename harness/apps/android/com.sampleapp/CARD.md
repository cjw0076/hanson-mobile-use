# App Card: 배달의민족 (`com.sampleapp`)
- **Domain**: `DELIVERY` | **Read-Only Advisory**: `false` | **Checkout Gate**: NEVER tap final `결제하기` / `주문하기` without explicit user approval and price-drift check.
- **Deep Links**: `baemin://` (`discover_deep_links("com.sampleapp")`).
- **Navigation & Grounding**:
  - Verify active delivery address at the top header before browsing restaurants or adding menu options.
  - Menu option modals use nested scroll containers; use `scroll_until` (with `1.35x` stall retry) and `Near(direction="below")` to select exact required options (`필수`).
- **Privacy**:
  - Redact any door/entrance PIN (`공동현관 비밀번호`) from delivery notifications or rider messages before saving notes.
