# App Card: 쿠팡 (`com.coupang.mobile`)
- **Domain**: `DELIVERY` | **Read-Only Advisory**: `false` | **Checkout Gate**: NEVER tap `결제하기` / `바로구매` / `와우 결제` without explicit human approval.
- **Deep Links**: `coupang://` (`discover_deep_links("com.coupang.mobile")`).
- **Navigation & Grounding**:
  - Promotional popups often overlay search results; dismiss `닫기` / `오늘 하루 보지 않기` if `HiddenElementError` fires.
  - Verify exact price & rocket delivery ETA before reporting or setting a local price-drop watcher.
- **Privacy & Delivery Hygiene**:
  - Extract `DELIVERY_TRACKING` ETA/arrival notes cleanly while **always redacting 공동현관 / 현관 비밀번호 / 보관함 PIN**.
