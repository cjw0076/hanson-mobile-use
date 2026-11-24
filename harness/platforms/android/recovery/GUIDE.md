# Android Recovery Guide (`harness/platforms/android/recovery/GUIDE.md`)

Load this guide only when an Android action stalls, fails with `HiddenElementError` / `HansonStepError`, or a device enters a dirty state.

## 1. `HiddenElementError` (`hidden=True, offscreen=False`)
- **Cause**: A modal sheet, permission dialog, snackbar, or keyboard covers the target element.
- **Recovery**:
  1. Inspect `err.ui_tree` attached to the error (no extra round-trip needed).
  2. Dismiss the foreground dialog (`tap_text("닫기")` / `tap_text("허용")` / `back()`) or hide keyboard (`input_reset()`).
  3. Re-check `find_nodes(...)` before tapping the underlying control.

## 2. Scroll Stall / Lazy List Not Moving
- `scroll_until(...)` automatically performs one `1.35x` stronger swipe retry on the first stall.
- If still stalled, inspect whether the target container is a nested horizontal/vertical scrollable (`at=(x, y)` inside the inner list) or whether the end of the list was reached.

## 3. Wedged Accessibility Tree / Stale Compose Hierarchy
- Wait `0.5s` and call `wait_stable()` or `input_reset()`.
- If `:18888` reports `degraded=True` (`a11y_timeout`), avoid rapid polling for `1–2s` or fall back to ADB `uiautomator dump /dev/tty` + `screenshot()`.

## 4. Multi-Device Ambiguity & Dirty Device State
- Never rely on implicit `usb[0]` when 2+ devices are attached; lease a serial via `HansonDevicePool.acquire(session_id)` or set `ANDROID_SERIAL`.
- After a failed run, run `HansonDevicePool.cleanup_dirty_device(serial)` (respects `is_allocated()` lease guard) and inspect `LogcatCrashMonitor` / `OnFailureBugreport` artifacts.
