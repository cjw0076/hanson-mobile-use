# App Card: 코레일톡 (`com.korail.talk`)
- **Domain**: `RESERVATION` | **Read-Only Advisory**: `false` | **Checkout Gate**: NEVER tap `결제하기` / `발권` without explicit user confirmation.
- **Deep Links**: `korailtalk://` (`discover_deep_links("com.korail.talk")`).
- **Navigation & Grounding**:
  - Departure (`출발`) and arrival (`도착`) station selectors open full-screen lists; verify exact KTX/ITX train number and seat class (`일반실`/`특실`) before pausing at checkout.
  - Ticket lists show multiple trains with identical `예약하기` buttons — always ground with `Near(direction="right")` anchored to the exact departure timestamp.
- **Proactive Secretary**:
  - Ticket confirmations emit `RESERVATION_COMMUTE` with departure station Naver Map link and buffer alert.
