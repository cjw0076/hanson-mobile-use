# App Card: 삼성 메시지 (`com.samsung.android.messaging`)
- **Domain**: `MESSENGER` | **Read-Only Advisory**: `false` | **OTP Hardline**: STRICTLY REJECT all verification/2FA codes.
- **Deep Links**: `sms:`, `smsto:` (require explicit human confirmation before sending).
- **Noise & Security Rules**:
  - Suppress `[Web발신]` marketing blasts and `(광고)` promotional texts.
  - Reject any message containing `인증번호`, `보안코드`, `본인확인`, `OTP`, or 4–8 digit verification codes (`0` proposals emitted).
  - Redact door/locker PINs and card numbers from delivery/reservation SMS before creating `DELIVERY_TRACKING` or `RESERVATION_COMMUTE` proposals.
