# App Card: 캐치테이블 (`kr.co.wadcorp.catchtable`)
- **Domain**: `RESERVATION` | **Read-Only Advisory**: `false` | **Booking Gate**: Explicit human confirmation before tapping final `예약 확정` / deposit payment.
- **Deep Links**: `catchtable://` (`discover_deep_links("kr.co.wadcorp.catchtable")`).
- **Navigation & Grounding**:
  - Time-slot chips (`오후 6:00`, `오후 7:00`) are horizontally scrollable rows; pass explicit `at=(x, y)` on the chip strip if horizontal scrolling is needed.
  - Check party size (`인원`) and date header before reporting slot availability.
- **Proactive Secretary**:
  - Confirmation notifications emit `RESERVATION_COMMUTE` proposals pairing Calendar insert + Naver Map deep link + departure buffer.
