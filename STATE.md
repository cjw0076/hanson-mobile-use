# STATE — Hanson Mobile-Use AI (Mobile Agent Studio v6.2.0)

**Updated:** 2026-11-23
**Branch:** `main` (`origin`: `https://github.com/cjw0076/hanson-mobile-use`, `PUBLIC`, signed `v6.2.0` GitHub Release APK published)

## Completed & Verified

1. **v6.2.0 P0+P1 AI-Native Architecture Modernization & Dead-Code Cleanup**
   - **AndroidKeyStore Vault Persistence Fixed (`PickleSotaAbsorptionEngine.kt` + `MobileUseAgentEngine.kt`)**: Fixed `InvalidAlgorithmParameterException` on hardware `AndroidKeyStore` AES-256-GCM keys (omit caller IV for non-`SecretKeySpec` keys and read `cipher.iv` after init); verified read-back before deleting legacy keys; empirically proved encrypted key survival across `am force-stop com.hanson.mobileuse` cold restart on physical Galaxy S25 (Android 16).
   - **Native 2026 Web Grounding & Multi-Provider Parity (`GeminiMultimodalClient.kt` + `UniversalNexusEngine.kt` + `ToolSpecRegistry.kt`)**:
     - Deleted legacy `html.duckduckgo.com` HTML scraper & XSS-prone `buildInteractiveBrowserBoardHtml`; replaced with text-first `answerTextOrGroundedWebQuery()` using **Gemini native `google_search` + `url_context` (`gemini-3.8-flash` / `gemini-3.5-flash-lite`)**, **OpenAI `/v1/responses` (`web_search_preview`)**, **Anthropic (`web_search_20250305`)**, and **`$0` Laptop CLI Bridge (`POST :18889/web/research`)**.
     - Added full **`AQ.`** Google AI Studio key recognition alongside `AIza`, fixed `HTTP 429` vs `402` rate-limit misclassification (45s per-model cooldown instead of 10-min global key blacklist), surfaced `lastCloudApiError` transparently in UI, filtered `thought: true` parts and JSON `null` strings, and added lowercase standard JSON Schema emitters (`toOpenAiToolsJson`, `toAnthropicToolsJson`, `toMcpToolsJson`).
     - Registered all **45 tools** in `ToolSpecRegistry` (`observe_screen`, `set_timer`, `execute_lifestyle_routine`), aligned `resolveTargetPixel()` (`element_index` → `0..1000`), `press_enter`/`submit_enter`, and `open_link`/`open_url` across Harness, Artemis `:18888`, Bridge `:18889`, and MCP `6.0.0`.
   - **Eliminated Pre-LLM Hijacks, Prompt Poisoning & Dead Code (~5,000 lines)**:
     - Separated `userGoal` from `systemContext` (`seed_tone` default disabled so `"답장"` can never hijack questions); gutted 750-line offline regex hijacker and deconstructed 1,675-line `IntentDecomposerAndRouter` to strictly anchored explicit numeric timers/alarms only; removed `InteractiveMorphEngine` chat interception; fixed `AWAITING_APPROVAL` fail-closed approval; hardened `NativeCodeCanvasViewport` WebView with `HansonWebViewClient`; deleted 5 dead `paradigm/` files and 6 orphan UI composables.
   - **Android 15/16 Accessibility, Real Live WebSocket Voice & OS CLI**:
     - Filtered `TYPE_SYSTEM`, bottom nav bar, and Samsung `Edge 패널` + Region occlusion in `HierarchyDumper`, moved `takeScreenshotOnce`/`GestureController` off main looper (no 4–8s deadlock), kept HUD `"중지"` button clickable during runs, upgraded `LiveVoiceSessionManager` to real Gemini Live API v1beta `BidiGenerateContent` WebSocket + `AudioRecord`(AEC/NS)/`AudioTrack` with `RECORD_AUDIO` permission guard, and fixed `HansonOsCliMain`/`inject-adb.ps1` accessibility append & fail-closed UID verification.

2. **v6.2.0 Ahaboard-Style Visual Board UI & Markdown-Free Natural Korean TTS**
   - Modeled directly from live `ahaboard.ai` UI inspection: added [`RichAnswerBoardFormatter.kt`](app/src/main/java/com/hanson/mobileuse/agent/RichAnswerBoardFormatter.kt) + [`AhaboardResponseCard.kt`](app/src/main/java/com/hanson/mobileuse/ui/components/AhaboardResponseCard.kt) rendering animated `.hl-swipe` highlight headlines, compact metric stat pills, staggered numbered `Key takeaways` cards with real `AnnotatedString` bold spans, and clean domain citation pills (hiding `vertexaisearch.cloud.google.com` redirect hosts while preserving verified `https://` links).
   - Unified all TTS readout through `RichAnswerBoardFormatter.formatForNaturalSpeech()` (stripping `**`/`*`/`#`/`` ` ``/URLs and naturalizing `$83.16` → `"83.16달러"`, `$67.05 ~ $84.86` → `"67.05달러에서 84.86달러"`).

3. **v6.2.0 3-Step Consumer Onboarding, Android 16 System Bars & Key-Safe Signed Release APK**
   - Added [`ConsumerOnboardingWizard.kt`](app/src/main/java/com/hanson/mobileuse/ui/components/ConsumerOnboardingWizard.kt) + mirrored interactive wizard in [`docs/studio-preview.html`](docs/studio-preview.html) (Browser QA verified: `0` console/page errors, crisp single-line badges, Step 1→2→3→live `37,200원` morph card).
   - Fixed Paper Light / Carbon Dark system bar icon contrast in [`Theme.kt`](app/src/main/java/com/hanson/mobileuse/ui/theme/Theme.kt) (`isAppearanceLightStatusBars = !isDark`, verified visually on physical Galaxy S25 Android 16).
   - Enforced `buildConfigField("String", "GEMINI_API_KEY", "\"\"")` in `buildTypes.release` (`app/build.gradle.kts`).

4. **Empirical Verification Results**
   - **Android Gradle Unit Tests (`gradlew testDebugUnitTest`)**: **28 suites, 531 tests, 0 failures, 0 errors, 0 skipped** (`ModernAiPipelineRegressionTest`, `RichAnswerBoardFormatterTest`, `ConsumerOnboardingWizardTest`, `SecurityHardeningVerificationTest`).
   - **Signed Release APK (`gradlew assembleRelease`)**: `app/build/outputs/apk/release/app-release.apk` (`166,804,040` bytes, SHA-256 `4e9f3be3ff3057a3342f4b4d8d0d0783385e1d57240e1d3131be4fcf81a7fcfb`, `apksigner verify` Scheme v2 `PASS`, **`RELEASE_APK_SECRET_HITS=0`** across all 4 DEX files and 361 entries).
   - **Physical Galaxy S25 (`SM_S931B`, Android 16) E2E**: Live onboarding Step 2 `✓ Google Gemini (gemini-3.8-flash) 연결 정상 (3646ms)` → encrypted vault save → `am force-stop` cold restart (`163ms`) → live `"SOXS ETF price now"` returned `$83.16` (`$67.05 ~ $84.86`) + 2 verified web citations (`investing.com`, `moomoo.com`), `CRASH_LINES=0`, dark status/nav bars on Paper Light.
   - **Python & Web Security Verification**: `BRIDGE_SECURITY_TESTS=PASS` (`45` `VALID_TOOLS` + `/web/research` CSRF/token checks), `MCP_SECURITY_TESTS=PASS` (`12` tools, `6.0.0`), `PREVIEW_JS_SYNTAX=PASS`, `WORKING_TREE_ZERO_LEAK_HITS=0`, `PYCACHE_DIRS=0`.

## Next Steps

1. Reconnect Galaxy S25 (or download signed `v6.2.0` Release APK directly on phone) to experience the newly compiled Ahaboard visual board cards and natural Korean TTS live on-device (preserving already-stored encrypted vault keys via `-r` update).
2. Optionally exercise live Gemini Live API v1beta WebSocket full-duplex voice mode on-device with microphone permission enabled and `$0` Laptop CLI Bridge (`adb reverse tcp:18889 tcp:18889` + `python laptop-cli-bridge.py`).
