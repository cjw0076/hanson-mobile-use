# App Card: 카카오뱅크 (`com.kakaobank.channel`) — READ-ONLY ADVISORY HARDLINE
- **Domain**: `FINANCE_ADVISORY` | **Read-Only Advisory**: **`true` (HARD-LOCKED)**
- **Strict Policy**:
  - `preflight()` refuses UI automation inside `com.kakaobank.channel` (`카카오뱅크`).
  - NEVER automate transfers, loans, cards, certificates, or authentication PIN/pattern screens.
- **Permitted Proactive Secretary Behavior**:
  - Emit read-only `BILLING_SUBSCRIPTION_WATCH` reminder cards for scheduled auto-debits or card payment due dates; never launch automated flows inside the banking app.
