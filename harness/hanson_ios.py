#!/usr/bin/env python3
"""Hanson iOS Control Driver — macOS iPhone Mirroring + Apple Vision OCR.

Provides the exact flat transport contract shared with `hanson_harness.py`
(`bounds`, `screen`, `screenshot`, `tap`, `long_press`, `drag`, `scroll_no_fling`,
`type_text`, `press`, `home`, `preflight`, `wait_for_text`, `wait_stable`) while
staying 100% stdlib-importable and unit-testable on Windows, Linux, and macOS.

Architecture notes (aligned with `phone-harness/phone-harness` upstream):
- **Eyes**: Captures the macOS `iPhone Mirroring` (`com.apple.ScreenContinuity`)
  window via `CGWindowListCreateImage` (background) or `screencapture -x -o -l`,
  then runs Apple Vision `VNRecognizeTextRequest` (`VNImageRequestHandler` with
  `options=None`, fixing upstream `#178/#179`) to extract tap-ready screen boxes
  with `source="pixels"`.
- **Hands**: Dispatches mouse/scroll/keyboard events via macOS `Quartz` CGEvents
  (foreground mode) or private SkyLight `SLPSPostEventRecordTo` + `CGEventPostToPid`
  (background mode when `HANSON_IOS_BACKGROUND=1`).
- **Honest Platform Limits**: iPhone Mirroring is an opaque video stream with no
  system Back button, no accessibility tree into iOS apps, and no app inventory.
  Unsupported operations raise `UnsupportedIosOperationError` rather than faking
  unreliable guesses.

Zero external dependencies on import (Python 3.9+ stdlib only; `Quartz`, `Vision`,
`Foundation`, and `AppKit` are lazily imported only on macOS at runtime).
"""
from __future__ import annotations

import ctypes
import ctypes.util
import importlib.util
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Protocol, Sequence, Tuple

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import hanson_script_guard as safety_guard  # noqa: E402

APP_NAME = "iPhone Mirroring"
BUNDLE_ID = "com.apple.ScreenContinuity"
APP_PATH = "/System/Applications/iPhone Mirroring.app"

_DIRECTIONS: Dict[str, Tuple[int, int]] = {
    "down": (0, 1),
    "up": (0, -1),
    "right": (1, 0),
    "left": (-1, 0),
}

# macOS virtual keycodes (Carbon / HIToolbox Events.h)
_KEYCODES: Dict[str, int] = {
    "return": 36, "enter": 36, "tab": 48, "space": 49,
    "delete": 51, "backspace": 51, "escape": 53, "esc": 53,
    "left": 123, "right": 124, "down": 125, "up": 126,
    "1": 18, "2": 19, "3": 20, "4": 21, "5": 23,
    "6": 22, "7": 26, "8": 28, "9": 25, "0": 29,
    "a": 0, "s": 1, "d": 2, "f": 3, "h": 4, "g": 5,
    "z": 6, "x": 7, "c": 8, "v": 9, "b": 11, "q": 12,
    "w": 13, "e": 14, "r": 15, "y": 16, "t": 17, "o": 31,
    "u": 32, "i": 34, "p": 35, "l": 37, "j": 38, "k": 40,
    "n": 45, "m": 46,
    "-": 27, "=": 24, "[": 33, "]": 30, "\\": 42, ";": 41,
    "'": 39, ",": 43, ".": 47, "/": 44, "`": 50,
}

_MOD_KEYCODES: Dict[str, int] = {
    "cmd": 55,
    "command": 55,
    "shift": 56,
    "alt": 58,
    "option": 58,
    "ctrl": 59,
    "control": 59,
}

_MOD_CANONICAL: Dict[str, str] = {
    "cmd": "cmd",
    "command": "cmd",
    "shift": "shift",
    "alt": "alt",
    "option": "alt",
    "ctrl": "ctrl",
    "control": "ctrl",
}

_SHIFTED_CHARS: Dict[str, str] = {
    "!": "1", "@": "2", "#": "3", "$": "4", "%": "5",
    "^": "6", "&": "7", "*": "8", "(": "9", ")": "0",
    "_": "-", "+": "=", "{": "[", "}": "]", "|": "\\",
    ":": ";", '"': "'", "<": ",", ">": ".", "?": "/", "~": "`",
}

_INTERSTITIAL_CONNECT_BUTTONS = frozenset({
    "connect", "try again", "retry", "continue", "reconnect",
})


class UnsupportedIosOperationError(NotImplementedError):
    """Raised when an operation does not exist on macOS iPhone Mirroring."""


class IosHostCapabilityError(RuntimeError):
    """Raised when the current host lacks macOS iPhone Mirroring / Vision prerequisites."""


# =============================================================================
# Pure Cross-Platform Geometry, Keycode, Session & Capability Helpers
# =============================================================================

def check_ios_capabilities(
    platform_name: Optional[str] = None,
    has_quartz: Optional[bool] = None,
    has_vision: Optional[bool] = None,
    has_foundation: Optional[bool] = None,
    has_appkit: Optional[bool] = None,
    mirror_app_exists: Optional[bool] = None,
    has_screencapture: Optional[bool] = None,
    background_requested: Optional[bool] = None,
) -> Dict[str, Any]:
    """Inspect whether this host can drive iOS via macOS iPhone Mirroring.

    All checks support explicit overrides so unit tests can exercise every
    platform branch deterministically on Windows, Linux, or macOS.
    """
    plat = platform_name or sys.platform
    is_macos = plat == "darwin"
    quartz_ok = (importlib.util.find_spec("Quartz") is not None) if has_quartz is None else bool(has_quartz)
    vision_ok = (importlib.util.find_spec("Vision") is not None) if has_vision is None else bool(has_vision)
    foundation_ok = (importlib.util.find_spec("Foundation") is not None) if has_foundation is None else bool(has_foundation)
    appkit_ok = (importlib.util.find_spec("AppKit") is not None) if has_appkit is None else bool(has_appkit)
    mirror_ok = Path(APP_PATH).exists() if mirror_app_exists is None else bool(mirror_app_exists)
    screencap_ok = (shutil.which("screencapture") is not None) if has_screencapture is None else bool(has_screencapture)
    want_bg = (
        os.environ.get("HANSON_IOS_BACKGROUND", "1").strip().lower() not in ("0", "false", "no")
        if background_requested is None
        else bool(background_requested)
    )

    missing: List[str] = []
    if not is_macos:
        missing.append("macOS 15+ host with Apple Silicon or T2 chip")
    else:
        if not mirror_ok:
            missing.append(f"{APP_NAME}.app ({APP_PATH})")
        if not quartz_ok:
            missing.append("pyobjc-framework-Quartz")
        if not vision_ok:
            missing.append("pyobjc-framework-Vision")
        if not foundation_ok:
            missing.append("pyobjc-framework-Cocoa (Foundation)")
        if not screencap_ok:
            missing.append("screencapture CLI")

    supported = is_macos and mirror_ok and quartz_ok and vision_ok and foundation_ok and screencap_ok
    if not is_macos:
        mode = "unsupported_host"
        guidance = (
            f"iOS iPhone Mirroring requires a macOS 15+ host (current host: {plat}). "
            "On non-macOS hosts, use `--platform android` for ADB/Artemis control or "
            "pair the native SwiftUI companion (`ios/HansonMobileUseIOS/`) over SSE."
        )
    elif not supported:
        mode = "missing_prerequisites"
        guidance = (
            "Install macOS PyObjC bindings (`pip install pyobjc-framework-Quartz "
            "pyobjc-framework-Vision pyobjc-framework-Cocoa`) and ensure macOS 15+ "
            "iPhone Mirroring is paired with your iPhone."
        )
    else:
        mode = "background_skylight" if (want_bg and appkit_ok) else "foreground_quartz"
        guidance = (
            "Ready. Grant Accessibility & Screen Recording permissions to your terminal "
            "in macOS System Settings -> Privacy & Security."
        )

    return {
        "platform": "ios",
        "host_platform": plat,
        "supported": supported,
        "mode": mode,
        "checks": {
            "macos_host": is_macos,
            "iphone_mirroring_app": mirror_ok,
            "quartz": quartz_ok,
            "vision_ocr": vision_ok,
            "foundation": foundation_ok,
            "appkit": appkit_ok,
            "screencapture": screencap_ok,
        },
        "missing": missing,
        "guidance": guidance,
    }


def vision_bbox_to_screen_point(
    norm_x: float,
    norm_y: float,
    norm_w: float,
    norm_h: float,
    img_w: int,
    img_h: int,
    window: Dict[str, Any],
) -> Dict[str, float]:
    """Convert Apple Vision bottom-left normalized bbox into top-left global screen points.

    Vision coordinates have origin at bottom-left (`y=0` is bottom of image);
    macOS CGEvent screen points have origin at top-left.
    """
    if img_w <= 0 or img_h <= 0:
        raise ValueError(f"Invalid image dimensions: {img_w}x{img_h}")
    win_w = float(window["w"])
    win_h = float(window["h"])
    if win_w <= 0 or win_h <= 0:
        raise ValueError(f"Invalid window bounds: {window!r}")

    sx = win_w / float(img_w)
    sy = win_h / float(img_h)
    px = norm_x * img_w
    py_top = (1.0 - norm_y - norm_h) * img_h
    pw = norm_w * img_w
    ph = norm_h * img_h
    return {
        "x": round(float(window["x"]) + (px + pw / 2.0) * sx, 1),
        "y": round(float(window["y"]) + (py_top + ph / 2.0) * sy, 1),
        "w": round(pw * sx, 1),
        "h": round(ph * sy, 1),
    }


def parse_key_combo(combo: str) -> Tuple[int, Tuple[str, ...], Tuple[int, ...]]:
    """Parse key combo like `'cmd+1'`, `'return'`, `'shift+a'` into `(keycode, mods, mod_keycodes)`."""
    raw = (combo or "").strip().lower()
    if not raw:
        raise ValueError("Key combo cannot be empty")
    parts = [p.strip() for p in raw.split("+") if p.strip()]
    if not parts:
        raise ValueError(f"Invalid key combo: {combo!r}")

    key_part = parts[-1]
    mod_parts = parts[:-1]

    canonical_mods: List[str] = []
    mod_codes: List[int] = []
    for mod in mod_parts:
        canon = _MOD_CANONICAL.get(mod)
        if canon is None:
            raise ValueError(f"Unsupported macOS modifier {mod!r} in {combo!r}")
        if canon not in canonical_mods:
            canonical_mods.append(canon)
            mod_codes.append(_MOD_KEYCODES[canon])

    if key_part in _KEYCODES:
        return _KEYCODES[key_part], tuple(canonical_mods), tuple(mod_codes)
    if key_part in _SHIFTED_CHARS:
        base = _SHIFTED_CHARS[key_part]
        if "shift" not in canonical_mods:
            canonical_mods.append("shift")
            mod_codes.append(_MOD_KEYCODES["shift"])
        return _KEYCODES[base], tuple(canonical_mods), tuple(mod_codes)
    raise ValueError(f"Unsupported iOS/macOS key: {combo!r}")


def compute_scroll_wheel_deltas(
    direction: str,
    amount: float,
    window: Dict[str, Any],
    steps: int = 6,
) -> Tuple[int, int, int]:
    """Compute pixel-unit scroll deltas `(dy, dx, steps)` without uncontrolled momentum fling.

    Direction semantics match `hanson_harness.py`: `'down'` reveals content further down.
    """
    if direction not in _DIRECTIONS:
        raise ValueError(f"direction must be one of {sorted(_DIRECTIONS)}, got {direction!r}")
    sx, sy = _DIRECTIONS[direction]
    clamped = max(0.05, min(float(amount), 2.0))
    step_count = max(2, int(steps))
    dy = int(round(sy * float(window["h"]) * clamped * 0.65))
    dx = int(round(sx * float(window["w"]) * clamped * 0.65))
    return dy, dx, step_count


def classify_ax_session_state(
    app_running: bool,
    window: Optional[Dict[str, Any]],
    ax_nodes: Sequence[Tuple[str, str]],
) -> Dict[str, Any]:
    """Classify iPhone Mirroring session health structurally via macOS AX nodes.

    Because the live phone stream is opaque video, a healthy live session exposes
    zero AXStaticText / AXButton children inside the window, whereas every Mac
    interstitial (Connect, iPhone in Use, Timed Out, Mac Login) exposes native
    AX labels/buttons.
    """
    if not app_running:
        return {"state": "not-running", "detail": f"{APP_NAME} is not running", "connect_buttons": []}
    if not window:
        return {"state": "no-window", "detail": f"{APP_NAME} is running with no visible window", "connect_buttons": []}

    texts: List[str] = []
    buttons: List[str] = []
    for role, text in ax_nodes:
        label = " ".join((text or "").split())
        if not label:
            continue
        if role == "AXButton":
            buttons.append(label)
        elif role == "AXStaticText":
            texts.append(label)

    if not texts and not buttons:
        return {"state": "ready", "detail": "", "connect_buttons": []}

    connect_hits = [b for b in buttons if b.lower() in _INTERSTITIAL_CONNECT_BUTTONS]
    parts: List[str] = []
    if texts:
        parts.append(" ".join(texts))
    if buttons:
        parts.append("buttons: " + ", ".join(f"'{b}'" for b in buttons))
    return {
        "state": "blocked",
        "detail": " ".join(parts),
        "connect_buttons": connect_hits,
    }


# =============================================================================
# Pluggable Mirror Driver Protocol & Lazy macOS Quartz/Vision/SkyLight Driver
# =============================================================================

class IosMirrorDriver(Protocol):
    def find_window(self) -> Optional[Dict[str, Any]]: ...
    def capture(self, path: Optional[str] = None) -> Tuple[str, Dict[str, Any]]: ...
    def recognize_text(self, path: str, window: Dict[str, Any]) -> List[Dict[str, Any]]: ...
    def tap(self, x: float, y: float) -> None: ...
    def long_press(self, x: float, y: float, duration: float = 0.8) -> None: ...
    def drag(self, x1: float, y1: float, x2: float, y2: float, duration: float = 0.35, steps: int = 14) -> None: ...
    def scroll_wheel(self, dy: int, dx: int, x: float, y: float, steps: int = 6) -> None: ...
    def press(self, combo: str) -> None: ...
    def type_text(self, text: str, delay: float = 0.03, keystrokes: bool = False) -> None: ...
    def session_status(self) -> Dict[str, Any]: ...


class _MacOsMirrorDriver:
    """Lazy macOS iPhone Mirroring driver (Quartz + Vision + optional SkyLight background)."""

    def __init__(self, background: Optional[bool] = None):
        caps = check_ios_capabilities(background_requested=background)
        if not caps["supported"]:
            raise IosHostCapabilityError(caps["guidance"])
        self.capabilities = caps
        self.background_mode = caps["mode"] == "background_skylight"
        self._quartz: Any = None
        self._vision: Any = None
        self._foundation: Any = None
        self._appkit: Any = None
        self._skylight: Any = None
        self._appserv: Any = None

    def _load_frameworks(self) -> None:
        if self._quartz is not None:
            return
        import Quartz  # type: ignore
        import Vision  # type: ignore
        import Foundation  # type: ignore
        self._quartz = Quartz
        self._vision = Vision
        self._foundation = Foundation
        try:
            import AppKit  # type: ignore
            self._appkit = AppKit
        except Exception:
            self._appkit = None
        if self.background_mode:
            try:
                self._skylight = ctypes.CDLL("/System/Library/PrivateFrameworks/SkyLight.framework/SkyLight")
                self._appserv = ctypes.CDLL(ctypes.util.find_library("ApplicationServices"))
            except Exception:
                self.background_mode = False

    def _modifier_flags(self, mod_names: Sequence[str]) -> int:
        self._load_frameworks()
        mapping = {
            "cmd": self._quartz.kCGEventFlagMaskCommand,
            "shift": self._quartz.kCGEventFlagMaskShift,
            "alt": self._quartz.kCGEventFlagMaskAlternate,
            "ctrl": self._quartz.kCGEventFlagMaskControl,
        }
        flags = 0
        for name in mod_names:
            flags |= mapping.get(name, 0)
        return flags

    def _running_pid(self) -> Optional[int]:
        self._load_frameworks()
        if self._appkit is None:
            return None
        apps = self._appkit.NSRunningApplication.runningApplicationsWithBundleIdentifier_(BUNDLE_ID)
        if apps:
            return int(apps[0].processIdentifier())
        return None

    def find_window(self) -> Optional[Dict[str, Any]]:
        self._load_frameworks()
        opts = (
            self._quartz.kCGWindowListExcludeDesktopElements
            if self.background_mode
            else (self._quartz.kCGWindowListOptionOnScreenOnly | self._quartz.kCGWindowListExcludeDesktopElements)
        )
        windows = self._quartz.CGWindowListCopyWindowInfo(opts, self._quartz.kCGNullWindowID) or []
        best: Optional[Dict[str, Any]] = None
        for win in windows:
            owner = str(win.get("kCGWindowOwnerName", ""))
            if owner != APP_NAME:
                continue
            if int(win.get("kCGWindowLayer", 0)) != 0:
                continue
            b = win.get("kCGWindowBounds") or {}
            w, h = float(b.get("Width", 0)), float(b.get("Height", 0))
            if w < 150 or h < 250:
                continue
            cand = {
                "x": int(b.get("X", 0)),
                "y": int(b.get("Y", 0)),
                "w": int(w),
                "h": int(h),
                "id": int(win.get("kCGWindowNumber", 0)),
                "pid": int(win.get("kCGWindowOwnerPID", 0)),
            }
            if best is None or (cand["w"] * cand["h"] > best["w"] * best["h"]):
                best = cand
        return best

    def _activate(self) -> None:
        self._load_frameworks()
        if self._appkit is not None:
            apps = self._appkit.NSRunningApplication.runningApplicationsWithBundleIdentifier_(BUNDLE_ID)
            if apps:
                apps[0].activateWithOptions_(self._appkit.NSApplicationActivateIgnoringOtherApps)
                time.sleep(0.15)
                return
        subprocess.run(["open", "-a", APP_NAME], check=False, capture_output=True)
        time.sleep(0.35)

    def _require_window(self) -> Dict[str, Any]:
        win = self.find_window()
        if win is None:
            raise RuntimeError(
                f"{APP_NAME} window not found. Open '{APP_PATH}' on macOS and unlock your paired iPhone."
            )
        return win

    def session_status(self) -> Dict[str, Any]:
        pid = self._running_pid()
        win = self.find_window()
        return classify_ax_session_state(app_running=pid is not None or win is not None, window=win, ax_nodes=[])

    def capture(self, path: Optional[str] = None) -> Tuple[str, Dict[str, Any]]:
        self._load_frameworks()
        win = self._require_window()
        if path is None:
            fd, path = tempfile.mkstemp(prefix="hanson-ios-", suffix=".png")
            os.close(fd)
        out_path = str(path)

        # Prefer window-targeted screencapture (-l <wid>) which works without focus theft
        proc = subprocess.run(
            ["screencapture", "-x", "-o", "-l", str(win["id"]), out_path],
            capture_output=True,
            timeout=15,
        )
        if proc.returncode == 0 and Path(out_path).is_file() and Path(out_path).stat().st_size > 0:
            return out_path, win

        # Fallback: activate and capture region
        self._activate()
        win = self._require_window()
        region = f"{win['x']},{win['y']},{win['w']},{win['h']}"
        proc2 = subprocess.run(
            ["screencapture", "-x", "-R", region, out_path],
            capture_output=True,
            timeout=15,
        )
        if proc2.returncode != 0 or not Path(out_path).is_file() or Path(out_path).stat().st_size == 0:
            raise RuntimeError(f"iPhone Mirroring capture failed: {(proc2.stderr or b'').decode('utf-8', errors='replace')}")
        return out_path, win

    def recognize_text(self, path: str, window: Dict[str, Any]) -> List[Dict[str, Any]]:
        """Run Apple Vision VNRecognizeTextRequest (Accurate) with options=None."""
        self._load_frameworks()
        url = self._foundation.NSURL.fileURLWithPath_(path)
        src = self._quartz.CGImageSourceCreateWithURL(url, None)
        if src is None:
            raise RuntimeError(f"Unable to read captured image for Vision OCR: {path}")
        props = self._quartz.CGImageSourceCopyPropertiesAtIndex(src, 0, None)
        img_w, img_h = int(props["PixelWidth"]), int(props["PixelHeight"])

        # Note: options=None fixes upstream phone-harness #178/#179 PyObjC bridge bug
        handler = self._vision.VNImageRequestHandler.alloc().initWithURL_options_(url, None)
        request = self._vision.VNRecognizeTextRequest.alloc().init()
        request.setRecognitionLevel_(self._vision.VNRequestTextRecognitionLevelAccurate)
        request.setRecognitionLanguages_(["ko-KR", "en-US"])
        ok, err = handler.performRequests_error_([request], None)
        if not ok:
            raise RuntimeError(f"Apple Vision OCR failed: {err}")

        boxes: List[Dict[str, Any]] = []
        for obs in request.results() or []:
            cands = obs.topCandidates_(1)
            if not cands:
                continue
            bb = obs.boundingBox()
            pt = vision_bbox_to_screen_point(
                float(bb.origin.x),
                float(bb.origin.y),
                float(bb.size.width),
                float(bb.size.height),
                img_w,
                img_h,
                window,
            )
            boxes.append({
                "text": str(cands[0].string()),
                "confidence": round(float(cands[0].confidence()), 3),
                "source": "pixels",
                **pt,
            })
        return boxes

    def _post_mouse(self, etype: int, x: float, y: float) -> None:
        ev = self._quartz.CGEventCreateMouseEvent(
            None, etype, self._quartz.CGPointMake(float(x), float(y)), self._quartz.kCGMouseButtonLeft
        )
        self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev)

    def _post_skylight_mouse(self, pid: int, wid: int, etype: int, gx: float, gy: float, lx: float, ly: float) -> bool:
        if not self.background_mode or self._skylight is None or self._appserv is None or pid <= 0 or wid <= 0:
            return False
        try:
            class _PSN(ctypes.Structure):
                _fields_ = [("hi", ctypes.c_uint32), ("lo", ctypes.c_uint32)]

            psn = _PSN()
            self._appserv.GetProcessForPID.argtypes = [ctypes.c_int, ctypes.POINTER(_PSN)]
            self._appserv.GetProcessForPID.restype = ctypes.c_int
            if self._appserv.GetProcessForPID(pid, ctypes.byref(psn)) != 0:
                return False
            buf = bytearray(0xF8)
            struct.pack_into("<I", buf, 0x04, 0xF8)
            struct.pack_into("<I", buf, 0x08, int(etype))
            struct.pack_into("<d", buf, 0x10, float(gx))
            struct.pack_into("<d", buf, 0x18, float(gy))
            struct.pack_into("<I", buf, 0x3C, int(wid))
            struct.pack_into("<d", buf, 0x40, float(lx))
            struct.pack_into("<d", buf, 0x48, float(ly))
            raw = (ctypes.c_char * len(buf)).from_buffer(buf)
            self._skylight.SLPSPostEventRecordTo.argtypes = [ctypes.POINTER(_PSN), ctypes.c_void_p]
            self._skylight.SLPSPostEventRecordTo(ctypes.byref(psn), ctypes.byref(raw))
            return True
        except Exception:
            return False

    def tap(self, x: float, y: float) -> None:
        self._load_frameworks()
        win = self._require_window()
        if self.background_mode and self._post_skylight_mouse(
            win.get("pid", 0), win["id"], 1, x, y, x - win["x"], y - win["y"]
        ):
            time.sleep(0.06)
            self._post_skylight_mouse(win.get("pid", 0), win["id"], 2, x, y, x - win["x"], y - win["y"])
            return
        self._activate()
        self._post_mouse(self._quartz.kCGEventMouseMoved, x, y)
        time.sleep(0.08)
        self._post_mouse(self._quartz.kCGEventLeftMouseDown, x, y)
        time.sleep(0.06)
        self._post_mouse(self._quartz.kCGEventLeftMouseUp, x, y)

    def long_press(self, x: float, y: float, duration: float = 0.8) -> None:
        self._load_frameworks()
        self._activate()
        self._post_mouse(self._quartz.kCGEventMouseMoved, x, y)
        time.sleep(0.08)
        self._post_mouse(self._quartz.kCGEventLeftMouseDown, x, y)
        time.sleep(max(0.1, float(duration)))
        self._post_mouse(self._quartz.kCGEventLeftMouseUp, x, y)

    def drag(self, x1: float, y1: float, x2: float, y2: float, duration: float = 0.35, steps: int = 14) -> None:
        self._load_frameworks()
        self._activate()
        n = max(2, int(steps))
        self._post_mouse(self._quartz.kCGEventMouseMoved, x1, y1)
        time.sleep(0.08)
        self._post_mouse(self._quartz.kCGEventLeftMouseDown, x1, y1)
        for i in range(1, n + 1):
            t = i / n
            self._post_mouse(
                self._quartz.kCGEventLeftMouseDragged,
                x1 + (x2 - x1) * t,
                y1 + (y2 - y1) * t,
            )
            time.sleep(max(0.005, float(duration) / n))
        self._post_mouse(self._quartz.kCGEventLeftMouseUp, x2, y2)

    def scroll_wheel(self, dy: int, dx: int, x: float, y: float, steps: int = 6) -> None:
        self._load_frameworks()
        self._activate()
        n = max(1, int(steps))
        self._post_mouse(self._quartz.kCGEventMouseMoved, x, y)
        time.sleep(0.08)
        for _ in range(n):
            ev = self._quartz.CGEventCreateScrollWheelEvent(
                None, self._quartz.kCGScrollEventUnitPixel, 2, int(dy / n), int(dx / n)
            )
            self._quartz.CGEventSetLocation(ev, self._quartz.CGPointMake(float(x), float(y)))
            self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev)
            time.sleep(0.03)

    def press(self, combo: str) -> None:
        self._load_frameworks()
        keycode, mods, mod_codes = parse_key_combo(combo)
        flags = self._modifier_flags(mods)
        self._activate()
        for mc in mod_codes:
            ev = self._quartz.CGEventCreateKeyboardEvent(None, mc, True)
            self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev)
        ev_down = self._quartz.CGEventCreateKeyboardEvent(None, keycode, True)
        if flags:
            self._quartz.CGEventSetFlags(ev_down, flags)
        self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev_down)
        time.sleep(0.03)
        ev_up = self._quartz.CGEventCreateKeyboardEvent(None, keycode, False)
        if flags:
            self._quartz.CGEventSetFlags(ev_up, flags)
        self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev_up)
        for mc in reversed(mod_codes):
            ev = self._quartz.CGEventCreateKeyboardEvent(None, mc, False)
            self._quartz.CGEventPost(self._quartz.kCGHIDEventTap, ev)

    def type_text(self, text: str, delay: float = 0.03, keystrokes: bool = False) -> None:
        self._load_frameworks()
        if not keystrokes and shutil.which("pbcopy"):
            subprocess.run(["pbcopy"], input=text.encode("utf-8"), check=True)
            self.press("cmd+v")
            time.sleep(0.15)
            return
        for ch in text:
            if ch == "\n":
                self.press("return")
            elif ch == " ":
                self.press("space")
            elif ch == "\t":
                self.press("tab")
            elif ch.isupper():
                self.press(f"shift+{ch.lower()}")
            else:
                self.press(ch)
            time.sleep(max(0.01, float(delay)))


# =============================================================================
# Unified Hanson iOS Client (Exact Flat Transport Parity with HansonPhoneClient)
# =============================================================================

class HansonIosMirrorClient:
    """iOS Mirroring client matching `HansonPhoneClient`'s flat transport contract."""

    platform = "ios"

    def __init__(
        self,
        driver: Optional[IosMirrorDriver] = None,
        background: Optional[bool] = None,
        companion_url: Optional[str] = None,
        companion_token: Optional[str] = None,
        sleep_fn: Callable[[float], None] = time.sleep,
    ):
        self._driver: Optional[IosMirrorDriver] = driver
        self._background = background
        self.companion_url = (companion_url or os.environ.get("HANSON_IOS_COMPANION_URL", "")).strip().rstrip("/")
        self.companion_token = (companion_token or os.environ.get("HANSON_IOS_COMPANION_TOKEN", "")).strip()
        self._sleep = sleep_fn

    def capabilities(self) -> Dict[str, Any]:
        return check_ios_capabilities(background_requested=self._background)

    def _get_driver(self) -> IosMirrorDriver:
        if self._driver is None:
            self._driver = _MacOsMirrorDriver(background=self._background)
        return self._driver

    def bounds(self) -> Dict[str, int]:
        win = self._get_driver().find_window()
        if win is None:
            raise RuntimeError(f"{APP_NAME} window not found. Launch {APP_NAME} and connect your iPhone.")
        return {
            "x": int(win["x"]),
            "y": int(win["y"]),
            "w": int(win["w"]),
            "h": int(win["h"]),
            **({"id": int(win["id"])} if "id" in win else {}),
        }

    def screen(self, min_confidence: float = 0.3) -> List[Dict[str, Any]]:
        drv = self._get_driver()
        path, win = drv.capture()
        boxes = drv.recognize_text(path, win)
        out: List[Dict[str, Any]] = []
        for box in boxes:
            conf = float(box.get("confidence", 1.0))
            if conf < min_confidence:
                continue
            out.append({
                "text": str(box.get("text", "")),
                "confidence": round(conf, 3),
                "source": "pixels",
                "x": float(box["x"]),
                "y": float(box["y"]),
                "w": float(box.get("w", 0.0)),
                "h": float(box.get("h", 0.0)),
            })
        return out

    def ui(self) -> List[Dict[str, Any]]:
        raise UnsupportedIosOperationError(
            "ui() accessibility tree is unsupported on iOS iPhone Mirroring: the mirrored phone "
            "surface is a video stream. Use screen() (Apple Vision OCR) instead."
        )

    def screenshot(self, path: Optional[str] = None) -> str:
        out_path, _ = self._get_driver().capture(path)
        print(out_path)
        return out_path

    def tap(self, x: float, y: float) -> None:
        self._get_driver().tap(float(x), float(y))

    def long_press(self, x: float, y: float, duration: float = 0.8) -> None:
        self._get_driver().long_press(float(x), float(y), duration=float(duration))

    def drag(self, x1: float, y1: float, x2: float, y2: float, duration: float = 0.35, steps: int = 14) -> None:
        self._get_driver().drag(float(x1), float(y1), float(x2), float(y2), duration=float(duration), steps=int(steps))

    def scroll_no_fling(self, direction: str = "down", amount: float = 0.55, at: Optional[Any] = None, steps: int = 6) -> None:
        b = self.bounds()
        if at is None:
            px, py = b["x"] + b["w"] / 2.0, b["y"] + b["h"] / 2.0
        elif isinstance(at, dict):
            px, py = float(at["x"]), float(at["y"])
        else:
            px, py = float(at[0]), float(at[1])
        dy, dx, step_count = compute_scroll_wheel_deltas(direction, amount, b, steps=steps)
        self._get_driver().scroll_wheel(dy=dy, dx=dx, x=px, y=py, steps=step_count)

    def type_text(self, text: str, delay: float = 0.03, keystrokes: bool = False) -> None:
        self._get_driver().type_text(text, delay=float(delay), keystrokes=bool(keystrokes))

    def press(self, key: str) -> None:
        k = key.strip().lower()
        if k == "home":
            self.home()
            return
        if k == "recents":
            self.app_switcher()
            return
        if k == "back":
            self.back()
            return
        parse_key_combo(k)
        self._get_driver().press(k)

    def home(self) -> None:
        self._get_driver().press("cmd+1")
        self._sleep(0.5)

    def app_switcher(self) -> None:
        self._get_driver().press("cmd+2")
        self._sleep(0.5)

    def back(self) -> None:
        raise UnsupportedIosOperationError(
            "back() is unsupported on iOS: iPhone has no system Back button. "
            "Tap the on-screen back label via tap_text() or swipe from the left edge explicitly."
        )

    def open_app(self, name: str) -> str:
        """Launch an iOS app via iPhone Mirroring Spotlight (`cmd+3`)."""
        drv = self._get_driver()
        drv.press("cmd+3")
        self._sleep(0.6)
        drv.type_text(name, keystrokes=True)
        self._sleep(0.8)
        drv.press("return")
        return name

    def current_app(self) -> Optional[str]:
        raise UnsupportedIosOperationError(
            "current_app() is unsupported on iOS iPhone Mirroring: macOS exposes no iOS app inventory."
        )

    def list_apps(self, include_system: bool = False) -> List[str]:
        raise UnsupportedIosOperationError(
            "list_apps() is unsupported on iOS iPhone Mirroring: macOS exposes no installed iOS app inventory."
        )

    def preflight(self) -> Dict[str, Any]:
        """Run Hanson's financial/denylist & prompt-injection preflight over Vision OCR screen text."""
        boxes = self.screen()
        text = "\n".join(o["text"] for o in boxes)
        report = dict(safety_guard.evaluate_screen_preflight(text, package_name="ios.mirroring"))
        report["platform"] = "ios"
        report["source"] = "pixels"
        report["ocr_box_count"] = len(boxes)
        return report

    def wait_for_text(self, query: str, timeout: float = 10.0, exact: bool = False, interval: float = 0.4) -> Optional[Dict[str, Any]]:
        deadline = time.time() + timeout
        q = query.lower()
        while True:
            hits = [o for o in self.screen() if (o["text"].lower() == q if exact else q in o["text"].lower())]
            if hits:
                return hits[0]
            if time.time() >= deadline:
                return None
            self._sleep(interval)

    def wait_stable(self, timeout: float = 5.0, interval: float = 0.35, settle: int = 2) -> bool:
        prev, same, deadline = None, 0, time.time() + timeout
        b = self.bounds()
        top = b["y"] + b["h"] * 0.06
        bot = b["y"] + b["h"] * 0.92
        while time.time() < deadline:
            cur = frozenset(o["text"] for o in self.screen() if top < o["y"] < bot)
            same = same + 1 if cur == prev else 0
            if same >= settle - 1:
                return True
            prev = cur
            self._sleep(interval)
        return False

    # --- Companion SSE / Co-Draw Extensions (Optional HTTP Loopback/HTTPS Bridge) ---

    def _companion_post(self, path: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        if not self.companion_url:
            raise UnsupportedIosOperationError(
                "No iOS Companion SSE bridge URL configured. Set HANSON_IOS_COMPANION_URL "
                "(e.g. http://127.0.0.1:18888) to stream A2UI/Co-Draw events to HansonMobileUseIOS."
            )
        headers = {"Content-Type": "application/json; charset=utf-8", "Accept": "application/json"}
        if self.companion_token:
            headers["X-Artemis-Token"] = self.companion_token
        req = urllib.request.Request(
            f"{self.companion_url}{path}",
            data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
            headers=headers,
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=8.0) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))

    def push_board_event(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self._companion_post("/board/events", payload)

    def codraw_ring(self, nx: float, ny: float, radius: float = 0.10, label: str = "", step_badge: str = "") -> Dict[str, Any]:
        return self._companion_post("/codraw/command", {
            "author": "AI", "kind": "SPOTLIGHT_RING",
            "nx": nx, "ny": ny, "radius": radius, "label": label, "stepBadge": step_badge,
        })

    def codraw_arrow(self, x1: float, y1: float, x2: float, y2: float, label: str = "", step_badge: str = "") -> Dict[str, Any]:
        return self._companion_post("/codraw/command", {
            "author": "AI", "kind": "ARROW",
            "points": [{"nx": x1, "ny": y1}, {"nx": x2, "ny": y2}],
            "label": label, "stepBadge": step_badge,
        })

    def codraw_clear(self) -> Dict[str, Any]:
        return self._companion_post("/codraw/command", {"action": "clear"})

    def vdisplay_start(self, width: int = 1080, height: int = 1920, dpi: int = 320) -> Dict[str, Any]:
        raise UnsupportedIosOperationError("VirtualDisplay is an Android-only surface; use macOS iPhone Mirroring background mode on iOS.")

    def vdisplay_snapshot(self) -> Dict[str, Any]:
        raise UnsupportedIosOperationError("VirtualDisplay is an Android-only surface; use screenshot() on iOS.")

    def vdisplay_stop(self) -> Dict[str, Any]:
        raise UnsupportedIosOperationError("VirtualDisplay is an Android-only surface.")


if __name__ == "__main__":
    print(json.dumps(check_ios_capabilities(), indent=2, ensure_ascii=False))
