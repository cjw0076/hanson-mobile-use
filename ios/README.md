# Hanson Mobile-Use for iOS — Architecture & Honest Platform Guide

Hanson Mobile-Use brings **dual-platform Android + iOS support** with zero misleading claims. Because iOS enforces a strict app sandbox that differs fundamentally from Android, Hanson delivers iOS capabilities across **three explicit, Apple-compliant tracks** and clearly documents what iOS forbids without jailbreak.

---

## Three Supported iOS Tracks

### Track A — macOS iPhone Mirroring (Zero-Jailbreak Full Device Control)

Powered by [`harness/hanson_ios.py`](../harness/hanson_ios.py) and selectable via `python -B harness/hanson_harness.py --platform ios`:

- **How it works**: Drives Apple's built-in macOS 15+ **iPhone Mirroring** (`com.apple.ScreenContinuity`) window over Wi-Fi/Continuity without jailbreaking or modifying the iPhone.
- **Eyes (Perception)**: Captures the mirrored iPhone window (`screencapture -x -o -l <wid>`) and runs on-Mac **Apple Vision OCR** (`VNRecognizeTextRequest`, accurate level, `ko-KR` + `en-US`, `VNImageRequestHandler` with `options=None`), converting bottom-left Vision bounding boxes into top-left tap-ready screen points (`source="pixels"`).
- **Hands (Actuation)**: Sends clicks, long-presses, drags, non-fling pixel scroll-wheel events (`CGEventCreateScrollWheelEvent`), Korean/Unicode paste (`pbcopy` + `Cmd+V`), Home (`Cmd+1`), App Switcher (`Cmd+2`), and Spotlight app launch (`Cmd+3`) via macOS `Quartz` CGEvents or background SkyLight event records (`HANSON_IOS_BACKGROUND=1`).
- **Safety**: Reuses `hanson_script_guard.py` (`--safe` fail-closed AST guard + financial/banking & prompt-injection `preflight()` over Vision OCR text).

```bash
# Check macOS iPhone Mirroring prerequisites (safe to run on Windows/Linux too)
python -B harness/hanson_harness.py --platform ios doctor

# Run the exact same flat Hanson script on a mirrored iPhone
python -B harness/hanson_harness.py --platform ios --safe <<'PY'
assert preflight()["safe"]
home()
open_app("Settings")
wait_stable()
print([box["text"] for box in screen()[:8]])
PY
```

### Track B — Native iOS SwiftUI Companion (`ios/HansonMobileUseIOS/`)

A standalone Swift 5.9+ / iOS 17+ / macOS 14+ Swift Package with **zero third-party dependencies**:

| File | Responsibility |
| :--- | :--- |
| [`Package.swift`](HansonMobileUseIOS/Package.swift) | SwiftPM manifest targeting iOS 17+ and macOS 14+ with unit test target |
| [`A2uiSwiftCore.swift`](HansonMobileUseIOS/Sources/HansonMobileUseIOS/A2uiSwiftCore.swift) | A2UI v0.9/v1.0 (plus v0.8 compat) streaming JSONL processor (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`), RFC 6901 JSON Pointer resolver/mutator, and `userAction` emitter |
| [`A2uiSwiftUIRenderer.swift`](HansonMobileUseIOS/Sources/HansonMobileUseIOS/A2uiSwiftUIRenderer.swift) | Native SwiftUI `Basic` catalog renderer (`Column`, `Row`, `Card`, `Box`, `Text`, `Button`, `TextField`, `CheckBox`, `MultipleChoice`, `Slider`, `Divider`, `Icon`, `Image`) strictly in **0%-saturation** Obsidian Paper (`#FAFAFA`/`#09090B`) & Carbon (`#09090B`/`#FAFAFA`) |
| [`CoDrawingSwiftUICanvas.swift`](HansonMobileUseIOS/Sources/HansonMobileUseIOS/CoDrawingSwiftUICanvas.swift) | Human + AI normalized `0..1` SwiftUI `Canvas` co-drawing (Bezier pen, directional arrow, spotlight ring + step badge, undo/redo, spatial prompt grounding) |
| [`HansonSseAndAppIntents.swift`](HansonMobileUseIOS/Sources/HansonMobileUseIOS/HansonSseAndAppIntents.swift) | RFC 8895 SSE client (`Last-Event-ID`, HTTPS/loopback-only URL guard), iOS `AppIntents` / Siri / Shortcuts bridge, honest capability matrix, and drop-in `HansonIosCompanionView` |

### Track C — WDA / XCUITest Developer Automation

For owned engineering devices and CI test labs, standard Apple Developer-mode USB automation (`WebDriverAgent` / `XCUITest` via `xcodebuild test-without-building`) provides direct XCTest accessibility-hierarchy inspection and synthetic touch injection under a valid Apple Developer signing profile.

---

## Hard iOS Sandbox Boundaries (What iOS Strictly Forbids Without Jailbreak)

Hanson will **never** claim Android-only OS privileges on stock iOS. When an operation does not exist on iOS, `hanson_ios.py` raises `UnsupportedIosOperationError` rather than faking unreliable heuristics.

| Capability | Android (`app/` + `harness/hanson_harness.py`) | iOS On-Device (`HansonMobileUseIOS`) | iOS via macOS Mirroring (`harness/hanson_ios.py`) |
| :--- | :--- | :--- | :--- |
| Native A2UI v0.9/v1.0 Rendering | Yes (Jetpack Compose) | **Yes (Native SwiftUI)** | Via Companion SSE |
| Human + AI Co-Drawing (`0..1`) | Yes (In-App + Cross-App Overlay) | **Yes (In-App SwiftUI Canvas)** | Via Companion SSE |
| Cross-App Floating Overlay (`SYSTEM_ALERT_WINDOW`) | Yes (`DynamicCanvasOverlayService`) | **Forbidden by iOS Sandbox** | macOS host overlay only |
| Third-Party `AccessibilityService` over Arbitrary Apps | Yes (`MobileUseAccessibilityService`) | **Forbidden by iOS Sandbox** | Replaced by Apple Vision OCR over Mirroring |
| Global Background Touch Injection On-Device | Yes (`dispatchGesture` + `VirtualDisplay`) | **Forbidden by iOS Sandbox** | **Yes** (macOS Quartz / SkyLight CGEvents) |
| System `back()` Button | Yes (`KEYCODE_BACK`) | N/A (iOS has no system Back) | Explicit `UnsupportedIosOperationError` |
| Foreground/Installed App Inventory (`current_app`, `list_apps`) | Yes (A11y + `pm list`) | **Forbidden by iOS Sandbox** | Explicit `UnsupportedIosOperationError` |
| Voice / Shortcut / Action Button Triggers | Yes (Tile / Assistant) | **Yes (`AppIntents` / Siri / Shortcuts)** | macOS Shortcuts / CLI |
