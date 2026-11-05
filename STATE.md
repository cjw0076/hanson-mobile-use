# STATE — hanson-mobile-use

**Last Updated:** 2026-11-05
**Repo:** `https://github.com/cjw0076/hanson-mobile-use`
**Release:** `https://github.com/cjw0076/hanson-mobile-use/releases/tag/v1.0.0`

## Completed
- **5-Tier Unrestricted Android Permissions & Accessibility Control Architecture**:
  - Tier 1: `HansonAccessibilityService` + `HierarchyDumper` (multi-window Z-layer traversal, API 33+ hybrid prefetch, scrollable clipping, atomic `takeScreenshot()`, `0..1000` normalized coordinates) + `GestureController` (`tap`, `doubleTap`, `longPress`, directional `swipe` with 40% overlap, `clickSequence` burst, `inputText`, `performGlobalKey`).
  - Tier 2: `HansonNotificationListenerService` + `RemoteInputSessionCache` (screen-off `<200ms` quick reply for KakaoTalk, SMS, Telegram, Slack, Discord) + `FloatingHudService` (`SYSTEM_ALERT_WINDOW` live agent overlay pill with 1-tap emergency stop) + `HansonForegroundService` (`specialUse` immortal daemon) + `BootCompletedReceiver`.
  - Tier 3 & 4: `PermissionHubManager` & `AgentToolExecutionHarness` (21 tools across GUI automation, `QUERY_ALL_PACKAGES` app launcher, brightness/volume/DND settings, direct phone call, SMS send, contacts search, calendar & alarm management, persistent memory).
  - Tier 5: `ArtemisCommandServer` (`127.0.0.1:18888` HTTP & JSON-RPC bridge `/ping`, `/snapshot`, `/dump_xml`, `/action`, `/rpc`).
- **Obsidian Design System v2 UI (5 Tabs)**:
  - Command Center, Permissions & Accessibility Hub, Live UI Tree Inspector & Artemis Bridge, Skills & Action Receipts, Settings.
- **Verification & Release**:
  - `testDebugUnitTest` (7/7 tests passing), `assembleDebug`, and signed `assembleRelease` (`hanson-mobile-use-v1.0.0-release.apk`).
  - Published open-source repository and v1.0.0 Release APK on GitHub.
