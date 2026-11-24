# iOS Platform Guide (`harness/platforms/ios/GUIDE.md`)

Hanson supports **two zero-jailbreak iOS Python backends** plus the native SwiftUI Companion (`ios/HansonMobileUseIOS/`).

## Backend 1 — Local iOS Portal HTTP (`HansonIosPortalClient`, `backend="local-ios-http"`)
Auto-probes local developer portals on loopback:
- **USB iPhone (`mobilerun-ios` / WDA portal)**: `127.0.0.1:8080..8089` `GET /version` returning `iosportal(...)`.
- **iOS Simulator (`ios-portal`)**: `127.0.0.1:6643..6652` `GET /device/date`.

Capabilities unlocked (`device.supports(...)`):
- `ui_tree = True`: real iOS Accessibility tree via `ui()`.
- `open_deep_link = True`: direct URI navigation via `open_deep_link(uri)`.
- `start_app(bundle_id)` / `open_app(name)` and `clipboard(text=None)`.

## Backend 2 — macOS iPhone Mirroring (`HansonIosMirrorClient`)
Used automatically on macOS 15+ when no local iOS HTTP portal is running:
- **Localized Window Detection**: matches `iPhone Mirroring`, `iPhone 미러링`, and `아이폰 미러링` + Korean interstitial buttons (`연결`, `다시 시도`, `계속`, `다시 연결`).
- **Non-Focus-Stealing Input**: set `HANSON_IOS_NO_FOCUS_STEAL=1` (or `HANSON_IOS_BACKGROUND=1`) to post clicks without stealing macOS foreground focus.
- **Vision OCR**: `VNRecognizeTextRequest` (`ko-KR`, `en-US`, `options=None`).
- **Honest Limits**: Mirroring has no iOS Accessibility tree or system Back button; unsupported methods raise `UnsupportedIosOperationError`.

## Proactive Secretary on iOS
Stock iOS forbids third-party `NotificationListenerService`. Combine `EventKit` (`ProactiveEventKitCalendarBridge`), iOS Share Sheet / `AppIntents`, and `harness/hanson_secretary_sync.py` pushing sanitized work/calendar/weather/trend signals over HTTPS/loopback A2UI SSE.
