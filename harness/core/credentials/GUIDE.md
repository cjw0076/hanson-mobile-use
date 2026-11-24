# Credentials, 2FA & Banking Safety Guide (`harness/core/credentials/GUIDE.md`)

## Non-Negotiable Hardlines
1. **Zero Plaintext Secrets in Prompts or Disk**:
   - Use Hanson's on-device `BlindCredentialVault` handles (`@vault`) rather than pasting passwords or tokens into scripts or chat logs.
   - Never log or print `ARTEMIS_TOKEN` (`X-Artemis-Token`), `AIza...`, `sk-...`, `ghp_...`, `xoxb-...`, card numbers, RRNs, or door PINs.
2. **Non-Bypassable Human Takeover (PASS / 2FA / OTP / CAPTCHA / Lock Screen)**:
   - Hanson refuses locked devices (`isKeyguardShowing=true`).
   - Pause and hand over to the human whenever a screen asks for PIN, biometrics, Korean PASS/Naver/Kakao 2FA, SMS OTP, or CAPTCHA.
3. **Banking & Payment Read-Only Hardline**:
   - Financial apps (`viva.republica.toss`, `com.kakaobank.channel`, `com.shinhan`, `com.kbcard`, `com.samsung.android.spay`, etc.) are hard-locked to `readOnlyAdvisoryOnly = true`.
   - Scripts must run `preflight()` before acting; Proactive Secretary may emit reminders/notes only and must NEVER click transfer/payment buttons.
4. **Untrusted Screen Data**:
   - Wrap scraped UI/notification text with `wrap_untrusted_screen_data()` and reject English/Korean prompt-injection payloads (`hanson_script_guard.detect_prompt_injection`).
