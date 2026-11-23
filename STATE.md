# STATE — Hanson Mobile-Use AI (Mobile Agent Studio v6.5.0)

**Updated:** 2026-11-24
**Branch:** `main` (`origin`: `https://github.com/cjw0076/hanson-mobile-use`, `PUBLIC`, signed `v6.5.0` GitHub Release APK)

## Completed & Verified

1. **v6.5.0 `phone-harness` & `PhoneHarness` SOTA Absorption + Hardening**
   - **Non-Fling Precision Scroll & Settle-Verified Wait Primitives ([`GestureController.kt`](app/src/main/java/com/hanson/mobileuse/accessibility/GestureController.kt) + [`ArtemisCommandServer.kt`](app/src/main/java/com/hanson/mobileuse/accessibility/ArtemisCommandServer.kt))**:
     - Added 2-phase chained `scrollWithoutFling()` (smooth move with `willContinue=true` + stationary `200ms` brake-hold before finger-up so Android `VelocityTracker` drops to `0 px/s` with zero fling overshoot).
     - Added token-authenticated `:18888` routes: `POST /scroll_no_fling`, `/wait_for_text`, `/wait_stable` (using `6%..92%` `ContentBandScreenFilter` excluding status clock & bottom nav), and `GET /preflight`.
   - **Prompt-Injection Screen Isolation, Denylist Preflight & Auditable Run Journal ([`PhoneHarnessAbsorptionEngine.kt`](app/src/main/java/com/hanson/mobileuse/agent/PhoneHarnessAbsorptionEngine.kt) + [`GeminiMultimodalClient.kt`](app/src/main/java/com/hanson/mobileuse/agent/GeminiMultimodalClient.kt))**:
     - Wrapped all VLM UI tree observations inside `<untrusted_screen_data>` trust boundaries with delimiter sanitization and EN/KO indirect prompt-injection detection (`UntrustedScreenDataGuard`).
     - Added global + Korean banking/wallet/password-manager app denylist preflight and risky tap/field classification, plus `AgentRunJournal` (`schemaVersion: "hanson-journal-v1"`) with structured failure taxonomy.
   - **Security-Hardened Background VirtualDisplay ([`HansonVirtualDisplayManager.kt`](app/src/main/java/com/hanson/mobileuse/service/HansonVirtualDisplayManager.kt))**:
     - Independent `DisplayManager.createVirtualDisplay` + `ImageReader` exposed via token-authenticated `:18888` `/vdisplay/start`, `/vdisplay/status`, `/vdisplay/snapshot`, `/vdisplay/stop` — storing frames strictly in-memory / app-private `cacheDir` (zero world-readable `/sdcard/` leaks).
   - **Composable Python Phone Harness CLI, AST Guard, Agent Skill & HTML Trace Viewer (`harness/`)**:
     - [`harness/hanson_harness.py`](harness/hanson_harness.py): zero-dependency Python 3.9+ flat helper script runner (`screen`, `tap_text`, `tap_ui`, `type_text`, `scroll`, `scroll_until`, `scroll_collect`, `wait_for_text`, `wait_stable`, `preflight`, `push_board_event`, `codraw_ring`, `codraw_arrow`, `vdisplay_*`), `:18888` native Korean IME priority + zero-app ADB fallback (`adb mdns` wireless `pair`, Windows/macOS/Linux ADB auto-discovery, `isKeyguardShowing` lock refusal gate, rotation-aware `dumpsys window displays` bounds, zero-settings-mutation `awake` via `KEYCODE_UNKNOWN`, persistent `~/.hanson-mobile-use/agent-workspace/agent_helpers.py`).
     - [`harness/hanson_script_guard.py`](harness/hanson_script_guard.py): fail-closed Python AST static safety classifier (`--safe` mode) + untrusted screen wrapper & preflight.
     - [`harness/hanson_trace_viewer.py`](harness/hanson_trace_viewer.py): offline XSS-escaped 0%-saturation monochrome HTML replay viewer for `AgentRunJournal` JSON/JSONL traces.
     - [`harness/SKILL.md`](harness/SKILL.md): ready-to-drop skill instructions for Claude Code, Codex, Gemini CLI, Cursor, and Antigravity.
   - **Cold-Start SSE Bug Fixed ([`SseDynamicUiEngine.kt`](app/src/main/java/com/hanson/mobileuse/agent/SseDynamicUiEngine.kt))**:
     - Fixed `DynamicUiEventBus.beginStream()` to publish `stream_start` BEFORE updating `_surfaceState.lastSequenceId` so cold-start `Last-Event-ID` tracking is strictly `>= 1`.

2. **Empirical Verification Results (`v6.5.0`)**
   - **Android Gradle Unit Tests (`gradlew testDebugUnitTest`)**: **31 suites, 542 tests, 0 failures, 0 errors, 0 skipped** (including new `PhoneHarnessAbsorptionTest` 4 tests + `SseDynamicUiAndCoDrawTest` 5 tests + `PureAchromaticDesignTest` + `SecurityHardeningVerificationTest`).
   - **Python Harness Unit Tests (`python -B harness/test_hanson_harness.py`)**: **5/5 tests PASS** (`0.009s`), `doctor` (`[PASS] adb binary`), `--safe` AST block (`exit 3`).
   - **Signed Release APK (`gradlew assembleRelease`)**: `app/build/outputs/apk/release/app-release.apk` (`167,098,992` bytes, SHA-256 `bffc33f5d914274fdef37052fea04502a4db5c59fa8491181e2e69a0d09d0f24`, `apksigner verify` Scheme v2 `PASS`, **`RELEASE_APK_SECRET_HITS=0`** across 4 DEX files / 361 entries).
   - **Security & Hygiene Gates**: `ZERO_LEAK_HITS=0`, `PYCACHE_DIRS=0`, `PREVIEW_HTTP=200`.

## Next Steps

1. Reconnect Galaxy S25 (or download signed `v6.5.0` Release APK directly) to exercise live `:18888` `hanson_harness.py` batch scripts, non-fling `scroll_collect`, background `vdisplay_start()`, and cross-app SSE Co-Drawing on physical Android 16 hardware.
2. Optionally publish `harness/SKILL.md` as a standalone Codex/Claude skill pack referencing the public GitHub repo.
