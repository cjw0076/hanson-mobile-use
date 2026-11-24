# Android Platform Guide (`harness/platforms/android/GUIDE.md`)

## Transport Priority
1. **Hanson Native `:18888` (`ArtemisCommandServer`)**: `<50ms` UI tree, Korean/Unicode `ACTION_SET_TEXT`, non-fling `/scroll_no_fling`, `/portal/deep-links`, `/vdisplay/*`, `/secretary/*`, and live SSE Co-Drawing (`X-Artemis-Token`).
2. **Zero-App ADB Fallback**: USB or Android 11+ Wireless Debugging (`pair` / `connect`), `uiautomator dump /dev/tty` (zero `/sdcard/` writes), rotation-aware display bounds, and lock-screen refusal gate (`isKeyguardShowing=true`).

## Grounding & Relational Predicates
- Always check `device.supports(cap)` (`ui_tree`, `open_deep_link`, `deep_link_inspect`, `vdisplay`, `codraw`, `secretary`, `stop_app`, `grant_permission`).
- Use `Predicate` + `Near(direction="below|above|left|right", max_distance=...)` + `parent_of` when a screen has duplicate labels (e.g., multiple "확인" or "주문하기" buttons).
- Nodes expose `offscreen` (outside viewport bounds) and `hidden` (`!visibleToUser`):
  - `offscreen == True`: scroll with `scroll("down")` or `scroll_until(...)` (auto-retries once with `1.35x` stronger swipe on stall).
  - `hidden == True and not offscreen`: covered by a sheet/dialog — `tap_ui()` raises `HiddenElementError`; dismiss the foreground overlay instead of retrying the covered target.

## Deep Links & Background VirtualDisplay
- `discover_deep_links(package)` queries `/portal/deep-links` (read-only APK manifest `ACTION_VIEW` inspection).
- `resolve_deep_link(uri)` dry-run verifies which activity handles a URI via `/portal/deep-link/resolve` without launching it.
- `vdisplay_start()`, `vdisplay_launch(pkg)`, `vdisplay_input(...)`, `vdisplay_mode("foreground|background|idle")`, `vdisplay_snapshot()`, `vdisplay_stop()` manage headless background displays.

## Pre-Absorbed PR Helpers
- `open_app(name)`: skips non-launcher companion packages (only picks launchable `CATEGORY_LAUNCHER` activities).
- `rotate("portrait"|"landscape"|"auto")`: sets orientation cleanly.
- `wait_for_notification(query, timeout=10.0)`: waits for matching notification text via `:18888` or `dumpsys notification`.
- `input_reset()`: clears stuck touch/key/IME state without rebooting.
