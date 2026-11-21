# Security Policy & Threat Model

Hanson Mobile-Use exercises high-privilege Android APIs (`AccessibilityService`, `NotificationListenerService`, `SYSTEM_ALERT_WINDOW`, system intents, and optional ADB `UID 2000` helpers) to enable full-device automation. Because these capabilities are powerful, strict security boundaries are enforced by default.

---

## 1. On-Device RPC Server (`127.0.0.1:18888` — `ArtemisCommandServer`)

- **Loopback Binding Only**: Binds exclusively to `127.0.0.1` (`InetAddress.getLoopbackAddress()`), never `0.0.0.0`.
- **256-Bit Random Token Authentication**:
  - Generates a cryptographically random 256-bit token (`SecureRandom`) stored in app-private storage.
  - Validates `X-Artemis-Token` or `Authorization: Bearer <token>` using constant-time byte comparison (`MessageDigest.isEqual`) to prevent timing side-channels.
- **Anti-CSRF / Anti-Tunnel Hijacking**:
  - Wildcard CORS (`Access-Control-Allow-Origin: *`) is prohibited.
  - If any browser cross-origin header (`Origin`, `Referer`, `Sec-Fetch-Site`) or reverse-proxy/tunnel header (`X-Forwarded-For`, `CF-Connecting-IP`, `Ngrok-Trace-Id`, `Forwarded`) is present, token authentication is unconditionally enforced regardless of local developer toggles.
- **Operational Rule**: Never expose `:18888` or `:18890` to public internet tunnels (ngrok, Cloudflare Tunnel, frp, etc.) without a mutually authenticated private mesh (e.g., WireGuard / Tailscale) **and** `X-Artemis-Token` enabled.

---

## 2. Optional UID-2000 Helper Daemon (`127.0.0.1:18890` — `HansonOsCliMain`)

- **No Arbitrary Shell Execution**: Arbitrary `sh -c` execution is completely disabled. Only an explicit allowlist of structured actions (`ALLOWED_ACTIONS`) executed via `ProcessBuilder` argument arrays (with shell metacharacter rejection) is permitted.
- **Dual Caller Verification**: Every request must present both:
  1. `X-Hanson-Cli-Token` matching the `0600` secret file in `/data/data/com.hanson.mobileuse/files/.hanson_cli_token`.
  2. Kernel `/proc/net/tcp` socket UID verification confirming the client socket belongs to `root (0)`, `shell (2000)`, or the `com.hanson.mobileuse` app UID. Other installed Android apps sharing the network namespace receive `403 Forbidden`.

---

## 3. Desktop Bridge (`127.0.0.1:18889` — `laptop-cli-bridge.py`) & MCP Server (`mcp/mobile_use_mcp_server.py`)

- **Anti-CSRF & Anti-DNS-Rebinding (`:18889`)**:
  - Unconditionally rejects requests containing browser cross-origin or reverse-proxy headers (`Origin`, `Referer`, `Sec-Fetch-*`, `X-Forwarded-*`, `CF-Connecting-IP`, `Forwarded`, `Ngrok-Trace-Id`) with `403 Forbidden`.
  - Enforces strictly loopback `Host` headers (`127.0.0.1`, `localhost`, `[::1]`) and `Content-Type: application/json` on `POST` routes (`415 Unsupported Media Type` otherwise).
  - Supports constant-time (`hmac.compare_digest`) `X-Bridge-Token` / `Authorization: Bearer` authentication via `--token` or `HANSON_BRIDGE_TOKEN`.
  - `/workspace/*` enforces canonical path containment (rejecting `..`, absolute/drive paths, symlink escapes) and defaults to read-only diagnostics (`--allow-workspace-exec` disabled by default).
- **Shell-Safe MCP ADB Fallback**:
  - Every ADB fallback path in `mcp/mobile_use_mcp_server.py` applies POSIX `shlex.quote()`, strict Android package/keycode regex allowlists, and URL scheme filtering (blocking `file://`, `content://`, `javascript:`, `data:`).

---

## 4. Android App Surface, Credential Vault, WebView & Cloud Sync Hardening

- **Backup & Transport Security (`AndroidManifest.xml` & `network_security_config.xml`)**:
  - `android:allowBackup="false"` prevents ADB/cloud extraction of app-private credentials and state.
  - `android:usesCleartextTraffic="false"` enforces HTTPS globally while scoping cleartext exclusively to `127.0.0.1` and `localhost` loopback bridges.
- **API Keys & Blind Credential Vault (`BlindCredentialVault`)**:
  - Cloud VLM API keys and BYO Firestore keys are encrypted on-device via `AndroidKeyStore` AES-256-GCM (with PBKDF2-HMAC-SHA256 `210,000`-iteration fallback, handle-bound AAD, zeroed byte buffers, and SHA-256 hash-chained audit logs) — never written to plaintext `SharedPreferences`, never read from world-readable `/sdcard/Download`, and transmitted strictly via `x-goog-api-key` / `Authorization` headers (never `?key=` URL query strings).
- **Privileged WebView (`window.HansonOS`) & Exported Intent Boundaries**:
  - `GenerativeUiRenderer` disables file/content access (`allowFileAccess = false`, `allowContentAccess = false`), locks `HansonWebViewClient` navigation to `https://hanson.local/`, restricts `openDeepLink` to an explicit URI scheme allowlist, and escapes HTML attributes/JS strings in `InteractiveMorphEngine`.
  - `HansonSystemNexusReceiverActivity` (`ACTION_SEND`, `ACTION_PROCESS_TEXT`) blocks sensitive secrets and hands incoming text to `MainActivity` for explicit user confirmation — never auto-executing autonomous runs from untrusted external Intents.
- **Optional BYO Cloud Firestore Sync (`firestore.rules`)**:
  - Zero bundled Firebase credentials or hardcoded project IDs; `/users/{userId}/vault/**` is unconditionally denied (`allow read, write: if false`).
  - Strict owner-only access (`request.auth.uid == userId`), path-to-field `id` binding, strict `int` timestamps, bounded field lengths/lists, forbidden secret-key rejection, and immutable undo payload protection on receipts.
- **Release Signing & High-Stakes Governance**:
  - No `.jks` or `.keystore` files or fallback passwords exist in the repository (`HANSON_KEYSTORE_*` env vars or gitignored `keystore.properties` only).
  - CAPTCHA/2FA/secure keypads unconditionally pause in `AWAITING_TAKEOVER` for human completion; irreversible payment/transfer/price-drift actions require explicit single-shot user approval.

---

## Reporting a Vulnerability

Please **do not** open a public GitHub issue for exploitable security vulnerabilities (such as auth bypasses on `:18888`/`:18890` or cross-origin escalation). Instead, use [GitHub Private Vulnerability Reporting](https://github.com/cjw0076/hanson-mobile-use/security/advisories/new) on this repository so a fix can be coordinated before disclosure.
