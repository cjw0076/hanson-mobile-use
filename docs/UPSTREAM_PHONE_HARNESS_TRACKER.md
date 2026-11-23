# Upstream Phone Harness Tracker (`phone-harness/phone-harness`)

Continuous parity ledger comparing Hanson Mobile-Use (`harness/hanson_harness.py`, `harness/hanson_ios.py`, `harness/hanson_script_guard.py`) against upstream [`phone-harness/phone-harness`](https://github.com/phone-harness/phone-harness).

Re-generate at any time (zero pip dependencies):

```bash
python -B harness/track_upstream_phone_harness.py --write-doc
```

---

## 1. Live Upstream Snapshot

| Field | Value |
| :--- | :--- |
| Upstream Repository | [`phone-harness/phone-harness`](https://github.com/phone-harness/phone-harness) |
| Default Branch | `main` |
| GitHub Stars | `5,574` |
| Latest Commit (`HEAD`) | `f509660` (`f5096606b10eb757d03467944bccd82f8aba385f`) |
| Latest Commit Date (UTC) | `2026-11-21T20:06:56Z` |
| Latest Release | `0.3.3` (2026-11-02T07:26:42Z) |
| Tracker Run Mode | `gh_api` |
| Synced At (UTC) | `2026-11-23T17:28:01Z` |
| Transport Op Parity | **22 / 25 (88.0%)** |
| Helper Function Parity | **30 / 46 (65.2%)** |

---

## 2. Recent Upstream Commits

| SHA | Date (UTC) | Upstream Commit | Hanson Alignment |
| :--- | :--- | :--- | :--- |
| `f509660` | `2026-11-21T20:06:56Z` | PHO-335: wake a cloud iPhone that is asleep or locked (wake(), device.wake) (#208) | Tracked in backlog (cloud iPhone wake); local devices enforce manual unlock |
| `b16b37f` | `2026-11-20T05:09:53Z` | PHO-316: reserve a cloud phone from the CLI (cloud reserve, cloud reservations) (#206) | Upstream cloud-hosted device rental CLI (out of scope for local-first Hanson) |
| `4bebff1` | `2026-11-03T19:51:53Z` | fix(ocr): pass None instead of empty dict to VNImageRequestHandler options (#178) (#179) | Adopted in `hanson_ios.py` (`options=None` passed to `VNImageRequestHandler`) |
| `792632f` | `2026-11-02T09:16:13Z` | OpenClaw plugin 0.1.2: confirm a Windows stop before freeing the phone (PHO-99) (#175) | Aligned with fail-closed `hanson_script_guard.py` AST guard |
| `61bffa0` | `2026-11-02T08:32:58Z` | OpenClaw plugin 0.1.1: whole-script approval, full stop on timeout, new summary (PHO-99, PHO-90) (#174) | Aligned with fail-closed `hanson_script_guard.py` AST guard |
| `811f75b` | `2026-11-02T07:41:16Z` | openclaw: say when to pass --accept-capabilities (#173) | Aligned with fail-closed `hanson_script_guard.py` AST guard |
| `549d300` | `2026-11-02T07:30:25Z` | 0.3.3 follow-ups: Hermes pin and phone_exec fix, OpenClaw manifest, push_file approval (PHO-11) (#169) | Aligned with fail-closed `hanson_script_guard.py` AST guard |
| `9da3387` | `2026-11-02T06:46:24Z` | Make Hermes telemetry detection more robust (also check HERMES_REAL_HOME, honor DO_NOT_TRACK) (#172) | Monitored via automated parity tracker |
| `ddf2a74` | `2026-11-02T03:10:42Z` | Release 0.3.3: version bump and PyPI Documentation link (#163) | Upstream release milestone |
| `31a4331` | `2026-11-02T03:00:42Z` | Point repo links at phone-harness/phone-harness (PHO-95) (#171) | Monitored via automated parity tracker |

### Latest Releases & Tags

| Tag | Published At (UTC) | Release Title |
| :--- | :--- | :--- |
| `0.3.3` | `2026-11-02T07:26:42Z` | 0.3.3 - Phone Harness MCP, OpenClaw Integration, Discord, and more! |
| `0.3.3rc1` | `2026-10-17T18:30:31Z` | 0.3.3rc1 |
| `0.3.2` | `2026-10-12T21:57:41Z` | 0.3.2 |
| `0.3.1` | `2026-10-12T05:40:09Z` | 0.3.1 |
| `0.3.1rc3` | `2026-10-11T23:34:05Z` | 0.3.1rc3 |

---

## 3. Tracked Upstream Modules (`src/phone_harness/`)

| Module | Lines | Size (Bytes) | AST Public Functions / Ops |
| :--- | ---: | ---: | :--- |
| `helpers.py` | 684 | 25,592 | 46 public fns · 2 ops · classes: `module-level` |
| `android.py` | 1097 | 47,575 | 1 public fns · 25 ops · classes: `Android, PrimaryUnreachable, TreeUnavailable` |
| `ios.py` | 756 | 30,536 | 0 public fns · 23 ops · classes: `IPhone, _AxWatch, _ObserverUnavailable` |
| `mirror.py` | 807 | 33,642 | 24 public fns · 0 ops · classes: `module-level` |
| `background.py` | 393 | 16,460 | 10 public fns · 0 ops · classes: `_PSN` |
| `ocr.py` | 56 | 2,126 | 2 public fns · 0 ops · classes: `module-level` |
| `transport.py` | 189 | 8,765 | 1 public fns · 27 ops · classes: `Backend, Unsupported` |

---

## 4. Transport Op Parity Matrix (Android & iOS)

| Upstream Op | Upstream Android | Upstream iOS | Hanson Android (`hanson_harness.py`) | Hanson iOS (`hanson_ios.py`) | Status | Notes |
| :--- | :---: | :---: | :--- | :--- | :---: | :--- |
| `screen.bounds` | Yes | Yes | Implemented (`bounds`) | Implemented (`bounds`) | **PARITY** |  |
| `screen.require` | Yes | Yes | Implemented (`bounds`) | Implemented (`bounds`) | **PARITY** |  |
| `screen.capture` | Yes | Yes | Implemented (`screenshot`) | Implemented (`screenshot`) | **PARITY** |  |
| `screen.text` | Yes | Yes | Implemented (`screen`) | Implemented (`screen`) | **PARITY** | Apple Vision OCR (`source='pixels'`) |
| `screen.text_pixels` | No | Yes | N/A (iOS OCR) | Implemented (`screen`) | **PARITY** | Android uses native A11y tree; iOS always uses Vision OCR |
| `input.tap` | Yes | Yes | Implemented (`tap`) | Implemented (`tap`) | **PARITY** |  |
| `input.press` | Yes | Yes | Implemented (`long_press`) | Implemented (`long_press`) | **PARITY** |  |
| `input.drag` | Yes | Yes | Implemented (`drag`) | Implemented (`drag`) | **PARITY** |  |
| `input.scroll` | Yes | Yes | Implemented (`scroll_no_fling`) | Implemented (`scroll_no_fling`) | **PARITY** | Zero-fling pixel wheel scroll |
| `input.keys` | Yes | Yes | Implemented (`press`) | Implemented (`press`) | **PARITY** |  |
| `input.text` | Yes | Yes | Implemented (`type_text`) | Implemented (`type_text`) | **PARITY** | Korean/Unicode paste + keystroke mode |
| `nav.home` | Yes | Yes | Implemented (`home`) | Implemented (`home`) | **PARITY** | `cmd+1` via iPhone Mirroring |
| `nav.back` | Yes | Yes | Implemented (`back`) | Honest Unsupported | **PARITY** | Honest unsupported: iOS has no system Back button |
| `nav.recents` | Yes | Yes | Implemented (`app_switcher`) | Implemented (`app_switcher`) | **PARITY** | `cmd+2` via iPhone Mirroring |
| `apps.launch` | Yes | Yes | Implemented (`open_app`) | Implemented (`open_app`) | **PARITY** | `cmd+3` Spotlight launch |
| `apps.current` | Yes | Yes | Implemented (`current_app`) | Honest Unsupported | **PARITY** | Honest unsupported: iOS Mirroring exposes no app inventory |
| `apps.list` | Yes | Yes | Implemented (`list_apps`) | Honest Unsupported | **PARITY** | Honest unsupported: iOS Mirroring exposes no app inventory |
| `session.state` | Yes | Yes | Implemented (`capabilities`) | Implemented (`capabilities`) | **PARITY** | Host & Mirroring capability diagnostics |
| `session.require` | Yes | Yes | Implemented (`preflight`) | Implemented (`preflight`) | **PARITY** | Financial/prompt-injection preflight gate |
| `device.wake` | No | Unsupported | Implemented (`_lock_gate`) | Backlog | **PARITY** | Cloud iPhone wake (`PHO-335`) tracked in backlog; local devices require manual unlock |
| `focus.probe` | Yes | Yes | Backlog | Backlog | **BACKLOG** | Backlog: focus-steal telemetry probe |
| `focus.diff` | Yes | Yes | Backlog | Backlog | **BACKLOG** | Backlog: focus-steal diff |
| `url.open` | No | Unsupported | Backlog | Backlog | **BACKLOG** | Backlog: direct URL handoff |
| `tree` | Yes | Unsupported | Implemented (`ui`) | Honest Unsupported | **PARITY** | Honest unsupported: iOS Mirroring is a video stream |
| `raw` | Yes | Unsupported | Implemented (`_adb`) | Honest Unsupported | **PARITY** | Honest unsupported: iOS substrate is Quartz, not a shell |

---

## 5. Flat Helper Function Parity (`helpers.py` vs Hanson Namespace)

Hanson exposes **38** flat script helpers in `build_script_namespace()` across both `--platform android` and `--platform ios`.

| Upstream Helper (`helpers.py`) | Hanson Equivalent | Status |
| :--- | :--- | :---: |
| `activate()` | — | **BACKLOG** |
| `app_switcher()` | `app_switcher()` | **PARITY** |
| `back()` | `back()` | **PARITY** |
| `connection_detail()` | — | **BACKLOG** |
| `connection_state()` | — | **BACKLOG** |
| `current_app()` | `current_app()` | **PARITY** |
| `drag()` | `drag()` | **PARITY** |
| `ensure_device()` | `bounds()` | **COVERED_VIA_CLIENT** |
| `find_nodes()` | `find_nodes()` | **PARITY** |
| `find_text()` | `find_text()` | **PARITY** |
| `find_window()` | `bounds()` | **COVERED_VIA_CLIENT** |
| `focus_probe()` | — | **BACKLOG** |
| `home()` | `home()` | **PARITY** |
| `image_point()` | — | **BACKLOG** |
| `interruption()` | — | **BACKLOG** |
| `list_apps()` | `list_apps()` | **PARITY** |
| `long_press()` | `long_press()` | **PARITY** |
| `ocr_pixels()` | — | **BACKLOG** |
| `open_app()` | `open_app()` | **PARITY** |
| `open_url()` | — | **BACKLOG** |
| `ops()` | — | **BACKLOG** |
| `press()` | `press()` | **PARITY** |
| `push_file()` | — | **BACKLOG** |
| `screen()` | `screen()` | **PARITY** |
| `screen_info()` | — | **BACKLOG** |
| `screenshot()` | `screenshot()` | **PARITY** |
| `scroll()` | `scroll()` | **PARITY** |
| `scroll_collect()` | `scroll_collect()` | **PARITY** |
| `scroll_screen()` | `scroll_screen()` | **PARITY** |
| `scroll_until()` | `scroll_until()` | **PARITY** |
| `send()` | — | **BACKLOG** |
| `shell()` | — | **BACKLOG** |
| `supports()` | — | **BACKLOG** |
| `swipe()` | `swipe()` | **PARITY** |
| `tap()` | `tap()` | **PARITY** |
| `tap_image_point()` | — | **BACKLOG** |
| `tap_node()` | `tap_ui()` | **COVERED_VIA_HELPER** |
| `tap_text()` | `tap_text()` | **PARITY** |
| `tap_ui()` | `tap_ui()` | **PARITY** |
| `type_text()` | `type_text()` | **PARITY** |
| `ui()` | `ui()` | **PARITY** |
| `wait()` | `wait()` | **PARITY** |
| `wait_for_app()` | `wait_for_app()` | **PARITY** |
| `wait_for_text()` | `wait_for_text()` | **PARITY** |
| `wait_stable()` | `wait_stable()` | **PARITY** |
| `wake()` | — | **BACKLOG** |

---

## 6. Hanson-Exclusive Capabilities Beyond Upstream

| Capability | Architectural Advantage |
| :--- | :--- |
| **Hanson Native `:18888` Artemis Engine** | <50ms Accessibility tree + full Korean/Unicode `ACTION_SET_TEXT` without ADB keyboard limits |
| **Background `VirtualDisplay` Execution** | `vdisplay_start/snapshot/stop` off-screen Android execution without stealing the user's screen |
| **A2UI v0.9/v1.0 Streaming UI (Android + iOS)** | Official A2UI JSONL/SSE surface renderer in both Jetpack Compose & SwiftUI (`ios/HansonMobileUseIOS/`) |
| **Human + AI Normalized Co-Drawing** | Cross-app Android overlay + native SwiftUI `Canvas` with `0..1` spotlight rings, arrows, and undo/redo |
| **Korean + Global Financial Preflight & Injection Guard** | `hanson_script_guard.py` blocks banking/wallet screens and wraps untrusted screen text |
| **Pure Achromatic Trace Viewer** | `hanson_trace_viewer.py` renders XSS-safe `#FAFAFA/#09090B` HTML run journals |

---

## 7. Prioritized Upstream Watchlist & Backlog

1. **`open_url(url)` / `push_file(path)`**: Add sanitized HTTPS-only URL handoff (`am start -a android.intent.action.VIEW` on Android; Safari/Shortcuts bridge on iOS).
2. **`focus_probe()` / `interruption()`**: Add macOS frontmost-window delta verification to `hanson_ios.py` background SkyLight mode.
3. **`ocr_pixels()` fallback on Android**: Allow optional VLM/OCR fallback on canvas/game views where Android Accessibility tree returns sparse nodes.
4. **Cloud Device Wake (`PHO-335`)**: Explicitly kept out of local device paths (Hanson never enters user PINs/passcodes on personal phones).
