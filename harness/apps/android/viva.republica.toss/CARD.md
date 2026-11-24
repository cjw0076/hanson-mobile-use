# App Card: 토스 (`viva.republica.toss`) — READ-ONLY ADVISORY HARDLINE
- **Domain**: `FINANCE_ADVISORY` | **Read-Only Advisory**: **`true` (HARD-LOCKED)**
- **Strict Policy**:
  - `preflight()` blocks unattended UI automation whenever `viva.republica.toss` or `토스` is foregrounded.
  - NEVER click `송금`, `이체`, `결제`, `계좌`, `카드`, or enter PIN/biometrics/2FA codes.
- **Permitted Proactive Secretary Behavior**:
  - Read bill due-date, subscription renewal, or high-amount notifications ONLY to generate read-only `BILLING_SUBSCRIPTION_WATCH` reminders/notes.
  - Strip account numbers, card numbers, and OTP codes before storing any summary.
