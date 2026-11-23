#!/usr/bin/env python3
"""Hanson Phone Harness — Composable Android Control CLI & Flat Script Runner.

Absorbs the strongest innovations of `phone-harness/phone-harness` (★5,572),
`PhoneHarness/PhoneHarness`, and `coopersimson96/phone-harness-safe` while
upgrading them with Hanson Mobile-Use's native on-device engine:

1. **Dual-Engine Transport**:
   - Fast Native Path (`127.0.0.1:18888`, `X-Artemis-Token`): `<50ms` Accessibility tree,
     full Korean/Unicode `type_text()`, non-fling `POST /scroll_no_fling`, `/wait_for_text`,
     `/wait_stable`, `/preflight`, background VirtualDisplay (`/vdisplay/*`), and live
     SSE Dynamic UI + Cross-App Co-Drawing (`push_board_event`, `codraw_ring`, `codraw_arrow`).
   - Automatic Zero-App ADB Fallback: mDNS wireless debugging pairing (`pair`, `connect`,
     `use`, `forget`), rotation-aware display bounds (`dumpsys window displays`), lock-screen
     refusal gate (`isKeyguardShowing=true`), zero-fling `input motionevent` scroll, and
     zero-settings-mutation `awake` session (`KEYCODE_UNKNOWN` pokes + optional `scrcpy`).
2. **Flat Pre-Imported Script Runner (`python harness/hanson_harness.py [--safe] <<'PY'`)**:
   - Batch multi-step phone workflows in one script; supports persistent agent-written
     helpers in `~/.hanson-mobile-use/agent-workspace/agent_helpers.py`.
   - `--safe` enforces the fail-closed AST safety classifier (`hanson_script_guard.py`)
     before running any code.

Zero external dependencies (Python 3.9+ stdlib only).
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import hanson_ios  # noqa: E402
import hanson_script_guard as safety_guard  # noqa: E402

CONFIG_DIR = Path(os.environ.get("HANSON_HARNESS_HOME") or (Path.home() / ".hanson-mobile-use"))
DEVICES_FILE = CONFIG_DIR / "devices.json"
AGENT_WORKSPACE = Path(os.environ.get("HANSON_AGENT_WORKSPACE") or (CONFIG_DIR / "agent-workspace"))
DEFAULT_ARTEMIS_URL = os.environ.get("ARTEMIS_URL", "http://127.0.0.1:18888").rstrip("/")
DEFAULT_ARTEMIS_TOKEN = os.environ.get("ARTEMIS_TOKEN", "").strip()
VALID_PLATFORMS = ("android", "ios", "auto")


def _resolve_adb_bin() -> str:
    env_adb = os.environ.get("HANSON_ADB", "").strip()
    if env_adb:
        return env_adb
    which_adb = shutil.which("adb")
    if which_adb:
        return which_adb
    candidates = []
    for sdk_env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(sdk_env, "").strip()
        if root:
            candidates.append(Path(root) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
    if os.name == "nt":
        local_app = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
        candidates.append(Path(local_app) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
    else:
        candidates.append(Path.home() / "Library" / "Android" / "sdk" / "platform-tools" / "adb")
        candidates.append(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb")
    for cand in candidates:
        if cand.is_file():
            return str(cand)
    return "adb"


ADB_BIN = _resolve_adb_bin()

_DIRECTIONS = {"down": (0, -1), "up": (0, 1), "right": (-1, 0), "left": (1, 0)}

_KEYS = {
    "enter": "ENTER", "return": "ENTER", "tab": "TAB", "space": "SPACE",
    "delete": "DEL", "backspace": "DEL", "escape": "ESCAPE", "esc": "ESCAPE",
    "home": "HOME", "back": "BACK", "recents": "APP_SWITCH", "menu": "MENU",
    "up": "DPAD_UP", "down": "DPAD_DOWN", "left": "DPAD_LEFT", "right": "DPAD_RIGHT",
    "power": "POWER", "volumeup": "VOLUME_UP", "volumedown": "VOLUME_DOWN", "search": "SEARCH",
}

_APP_IDS = {
    "kakaotalk": ("com.kakao.talk",),
    "kakao": ("com.kakao.talk",),
    "youtube": ("com.google.android.youtube",),
    "chrome": ("com.android.chrome",),
    "settings": ("com.android.settings",),
    "gmail": ("com.google.android.gm",),
    "maps": ("com.google.android.apps.maps",),
    "googlemaps": ("com.google.android.apps.maps",),
    "naver": ("com.nhn.android.search",),
    "navermap": ("com.nhn.android.nmap",),
    "playstore": ("com.android.vending",),
    "tiktok": ("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
    "x": ("com.twitter.android",),
    "twitter": ("com.twitter.android",),
    "threads": ("com.instagram.barcelona",),
    "instagram": ("com.instagram.android",),
    "messages": ("com.google.android.apps.messaging", "com.samsung.android.messaging"),
    "phone": ("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
    "clock": ("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
}

POKE_CMD = "input keyevent KEYCODE_UNKNOWN"


# =============================================================================
# Device Registry & mDNS Wireless ADB Discovery
# =============================================================================

def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _load_devices() -> Dict[str, Any]:
    try:
        if DEVICES_FILE.is_file():
            return json.loads(DEVICES_FILE.read_text(encoding="utf-8"))
    except Exception:
        pass
    return {"primary": None, "phones": {}}


def _save_devices(cfg: Dict[str, Any]) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    DEVICES_FILE.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


def _adb_raw(*args: str, binary: bool = False, timeout: int = 45, check: bool = True):
    proc = subprocess.run([ADB_BIN, *args], capture_output=True, timeout=timeout)
    if check and proc.returncode != 0:
        err = (proc.stderr or proc.stdout).decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"adb {' '.join(args)} failed: {err}")
    return proc.stdout if binary else proc.stdout.decode("utf-8", errors="replace")


def parse_mdns_services(raw_output: str, kind: str = "connect") -> List[Dict[str, Any]]:
    """Parse `adb mdns services` lines into [{serial, host, port, addr}]."""
    found: List[Dict[str, Any]] = []
    marker = f"_adb-tls-{kind}"
    for line in (raw_output or "").splitlines():
        parts = line.split("\t") if "\t" in line else line.split()
        if len(parts) < 3 or marker not in parts[1]:
            continue
        m = re.match(r"adb-(.+)-[^-]+$", parts[0])
        host, _, port = parts[2].rpartition(":")
        if m and host and port.isdigit():
            found.append({
                "serial": m.group(1),
                "host": host,
                "port": int(port),
                "addr": parts[2],
            })
    return found


def parse_rotation_aware_bounds(displays_output: str, wm_size_output: str = "") -> Optional[Dict[str, int]]:
    """Parse current rotated display bounds (`Display: mDisplayId=0 ... cur=WxH`), falling back to `wm size`."""
    m = re.search(r"Display:\s*mDisplayId=0\b[^\n]*\n[^\n]*\bcur=(\d+)x(\d+)", displays_output or "")
    if not m:
        m = (
            re.search(r"Override size:\s*(\d+)x(\d+)", wm_size_output or "")
            or re.search(r"Physical size:\s*(\d+)x(\d+)", wm_size_output or "")
        )
    if not m:
        return None
    return {"x": 0, "y": 0, "w": int(m.group(1)), "h": int(m.group(2))}


def _attached_devices() -> List[Tuple[str, str, str]]:
    out = _adb_raw("devices", check=False)
    rows = [ln.split() for ln in out.splitlines()[1:] if ln.strip()]
    return [
        (r[0], r[1], "wifi" if (":" in r[0] or r[0].startswith("adb-")) else "usb")
        for r in rows if len(r) >= 2
    ]


def _remember_device(serial: str, model: str, host: Optional[str] = None, name: Optional[str] = None, primary: bool = False) -> str:
    cfg = _load_devices()
    phones = cfg.setdefault("phones", {})
    key = next((k for k, p in phones.items() if p.get("serial") == serial), None)
    if key is None:
        base = name or re.sub(r"[^a-z0-9]+", "-", (model or "android").lower()).strip("-") or "android"
        key, n = base, 2
        while key in phones:
            key, n = f"{base}-{n}", n + 1
        phones[key] = {"serial": serial, "model": model, "paired": _now_iso()}
    phones[key]["last_seen"] = _now_iso()
    if host:
        phones[key]["last_host"] = host
    if primary and not cfg.get("primary"):
        cfg["primary"] = key
    _save_devices(cfg)
    return key


# =============================================================================
# Dual-Engine Transport (Hanson :18888 Native Preferred -> Zero-App ADB Fallback)
# =============================================================================

class HansonPhoneClient:
    platform = "android"

    def __init__(
        self,
        artemis_url: str = DEFAULT_ARTEMIS_URL,
        artemis_token: str = DEFAULT_ARTEMIS_TOKEN,
        serial: Optional[str] = None,
    ):
        self.artemis_url = artemis_url.rstrip("/")
        self.artemis_token = artemis_token.strip()
        self.serial = serial or os.environ.get("ANDROID_SERIAL") or None
        self._gate_at = 0.0
        self._motionevents: Optional[bool] = None
        self._artemis_checked_at = 0.0
        self._artemis_available = False

    def _artemis_request(self, method: str, path: str, payload: Optional[Dict[str, Any]] = None, timeout: float = 10.0) -> Dict[str, Any]:
        url = f"{self.artemis_url}{path}"
        headers = {"Accept": "application/json"}
        if self.artemis_token:
            headers["X-Artemis-Token"] = self.artemis_token
        data = None
        if payload is not None:
            data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        req = urllib.request.Request(url, data=data, headers=headers, method=method.upper())
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))

    def is_artemis_ready(self, force_recheck: bool = False) -> bool:
        now = time.time()
        if not force_recheck and (now - self._artemis_checked_at) < 5.0:
            return self._artemis_available
        self._artemis_checked_at = now
        try:
            res = self._artemis_request("GET", "/ping", timeout=1.5)
            self._artemis_available = res.get("status") == "ok"
        except Exception:
            self._artemis_available = False
        return self._artemis_available

    def _pin_adb_serial(self) -> str:
        if self.serial:
            return self.serial
        ready = [r for r in _attached_devices() if r[1] == "device"]
        usb = [r[0] for r in ready if r[2] == "usb"]
        if usb:
            self.serial = usb[0]
            return self.serial
        wifi = [r[0] for r in ready if r[2] == "wifi"]
        if wifi:
            self.serial = wifi[0]
            return self.serial
        raise RuntimeError(
            "No Android device reachable via ADB. Plug in USB with USB Debugging enabled, "
            "or pair over Wi-Fi with `python harness/hanson_harness.py pair <CODE>`."
        )

    def _adb(self, *args: str, binary: bool = False, timeout: int = 45):
        dev = self._pin_adb_serial()
        return _adb_raw("-s", dev, *args, binary=binary, timeout=timeout)

    def _sh(self, cmd: str, timeout: int = 45) -> str:
        return self._adb("shell", cmd, timeout=timeout)

    def _screen_status(self) -> Tuple[bool, bool]:
        awake = "Awake" in self._sh("dumpsys power | grep -m1 mWakefulness=", timeout=10)
        locked = "isKeyguardShowing=true" in self._sh("dumpsys window | grep -m1 isKeyguardShowing", timeout=10)
        return awake, locked

    def _lock_gate(self) -> None:
        """Refuse to drive a locked phone (never attempts PIN entry); harmlessly wake sleeping screens."""
        if time.time() - self._gate_at < 2.0:
            return
        awake, locked = self._screen_status()
        if not awake:
            self._sh("input keyevent KEYCODE_WAKEUP", timeout=10)
            time.sleep(0.45)
            awake, locked = self._screen_status()
        if locked:
            raise RuntimeError(
                "Android phone is locked (isKeyguardShowing=true). Unlock it manually first — "
                "Hanson Phone Harness never enters PINs or lock-screen passcodes."
            )
        self._gate_at = time.time()

    def bounds(self) -> Dict[str, int]:
        if self.is_artemis_ready():
            try:
                pf = self._artemis_request("GET", "/preflight", timeout=3.0)
                w, h = int(pf.get("screenWidth", 0)), int(pf.get("screenHeight", 0))
                if w > 0 and h > 0:
                    return {"x": 0, "y": 0, "w": w, "h": h}
            except Exception:
                pass
        disp = self._sh("dumpsys window displays", timeout=10)
        wm = ""
        parsed = parse_rotation_aware_bounds(disp)
        if parsed is None:
            wm = self._sh("wm size", timeout=10)
            parsed = parse_rotation_aware_bounds(disp, wm)
        if parsed is None:
            raise RuntimeError("Unable to read Android screen dimensions")
        return parsed

    def ui(self) -> List[Dict[str, Any]]:
        if self.is_artemis_ready():
            data = self._artemis_request("GET", "/elements", timeout=8.0)
            nodes = []
            for el in data.get("elements") or []:
                b = el.get("bounds") or {}
                x1 = int(el.get("left", b.get("left", 0)))
                y1 = int(el.get("top", b.get("top", 0)))
                x2 = int(el.get("right", b.get("right", 0)))
                y2 = int(el.get("bottom", b.get("bottom", 0)))
                cx = int(el.get("centerX", (x1 + x2) // 2))
                cy = int(el.get("centerY", (y1 + y2) // 2))
                nodes.append({
                    "index": el.get("index"),
                    "text": el.get("text") or "",
                    "desc": el.get("contentDesc") or el.get("contentDescription") or el.get("desc") or "",
                    "id": el.get("resourceId") or el.get("id") or "",
                    "class": el.get("className") or "",
                    "clickable": bool(el.get("clickable", False)),
                    "x": cx,
                    "y": cy,
                    "w": max(0, x2 - x1),
                    "h": max(0, y2 - y1),
                    "source": "hanson_a11y",
                })
            return nodes

        self._lock_gate()
        raw = self._adb("exec-out", "uiautomator", "dump", "/dev/tty", binary=True, timeout=25)
        start = raw.find(b"<?xml")
        if start < 0:
            raise RuntimeError(f"uiautomator dump unavailable: {raw.decode('utf-8', errors='replace').strip()[:160]}")
        xml_bytes = raw[start:raw.rfind(b">") + 1]
        root = ET.fromstring(xml_bytes)
        nodes = []
        for idx, el in enumerate(root.iter("node")):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            nodes.append({
                "index": idx,
                "text": el.get("text", ""),
                "desc": el.get("content-desc", ""),
                "id": el.get("resource-id", ""),
                "class": el.get("class", ""),
                "clickable": el.get("clickable") == "true",
                "x": (x1 + x2) // 2,
                "y": (y1 + y2) // 2,
                "w": max(0, x2 - x1),
                "h": max(0, y2 - y1),
                "source": "adb_tree",
            })
        return nodes

    def screen(self) -> List[Dict[str, Any]]:
        out = []
        for n in self.ui():
            label = (n["text"] or n["desc"]).strip()
            if label:
                out.append({
                    "text": label,
                    "confidence": 1.0,
                    "source": n["source"],
                    "x": n["x"],
                    "y": n["y"],
                    "w": n["w"],
                    "h": n["h"],
                })
        return out

    def screenshot(self, path: Optional[str] = None) -> str:
        png = self._adb("exec-out", "screencap", "-p", binary=True, timeout=20)
        if path is None:
            fd, path = tempfile.mkstemp(prefix="hanson-phone-", suffix=".png")
            os.close(fd)
        Path(path).write_bytes(png)
        print(path)
        return path

    def tap(self, x: float, y: float) -> None:
        if self.is_artemis_ready():
            self._artemis_request("POST", "/action", {"cmd": "tap", "x": int(x), "y": int(y)})
            return
        self._lock_gate()
        self._sh(f"input tap {int(x)} {int(y)}")

    def long_press(self, x: float, y: float, duration: float = 0.8) -> None:
        if self.is_artemis_ready():
            self._artemis_request("POST", "/action", {"cmd": "long_press", "x": int(x), "y": int(y), "duration": int(duration * 1000)})
            return
        self._lock_gate()
        ix, iy = int(x), int(y)
        self._sh(f"input swipe {ix} {iy} {ix} {iy} {int(duration * 1000)}")

    def drag(self, x1: float, y1: float, x2: float, y2: float, duration: float = 0.35) -> None:
        if self.is_artemis_ready():
            self._artemis_request("POST", "/action", {
                "cmd": "swipe",
                "x1": int(x1), "y1": int(y1), "x2": int(x2), "y2": int(y2),
                "duration": int(duration * 1000),
            })
            return
        self._lock_gate()
        self._sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(duration * 1000)}")

    def scroll_no_fling(self, direction: str = "down", amount: float = 0.55, at: Optional[Any] = None, steps: int = 6) -> None:
        if direction not in _DIRECTIONS:
            raise ValueError(f"direction must be one of {sorted(_DIRECTIONS)}, got {direction!r}")
        b = self.bounds()
        if at is None:
            px, py = b["w"] / 2.0, b["h"] / 2.0
        elif isinstance(at, dict):
            px, py = float(at["x"]), float(at["y"])
        else:
            px, py = float(at[0]), float(at[1])

        if self.is_artemis_ready():
            self._artemis_request("POST", "/scroll_no_fling", {
                "direction": direction,
                "amount": float(amount),
                "x": px,
                "y": py,
            })
            return

        self._lock_gate()
        sx, sy = _DIRECTIONS[direction]
        dx, dy = sx * b["w"] * amount, sy * b["h"] * amount
        keep = lambda v, sz: min(max(v, sz * 0.06), sz * 0.94)
        x1, x2 = keep(px - dx / 2, b["w"]), keep(px + dx / 2, b["w"])
        y1, y2 = keep(py - dy / 2, b["h"]), keep(py + dy / 2, b["h"])
        n = max(2, int(steps))
        if self._motionevents is not False:
            moves = " && ".join(
                f"input motionevent MOVE {int(x1 + (x2 - x1) * i / n)} {int(y1 + (y2 - y1) * i / n)}"
                for i in range(1, n + 1)
            )
            try:
                self._sh(f"input motionevent DOWN {int(x1)} {int(y1)} && {moves} && sleep 0.2 && input motionevent UP {int(x2)} {int(y2)}")
                self._motionevents = True
                return
            except RuntimeError:
                if self._motionevents:
                    raise
                self._motionevents = False
        travel = max(abs(x2 - x1), abs(y2 - y1))
        self._sh(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {max(600, int(travel * 4))}")

    def type_text(self, text: str) -> None:
        if self.is_artemis_ready():
            # Native Hanson Accessibility supports full Korean/Unicode via ACTION_SET_TEXT + clipboard
            self._artemis_request("POST", "/action", {"cmd": "input_text", "text": text})
            return
        self._lock_gate()
        for i, line in enumerate(text.split("\n")):
            if i:
                self._sh("input keyevent KEYCODE_ENTER")
            if line:
                self._sh("input text " + shlex.quote(line.replace(" ", "%s")))

    def press(self, key: str) -> None:
        k = key.strip().lower()
        code = _KEYS.get(k) or (k.upper() if len(k) == 1 and k.isalnum() else None)
        if not code:
            raise ValueError(f"Unsupported Android key: {key!r}")
        if self.is_artemis_ready() and code in ("HOME", "BACK", "APP_SWITCH"):
            self._artemis_request("POST", "/action", {"cmd": "global", "action": code})
            return
        self._lock_gate()
        self._sh(f"input keyevent KEYCODE_{code}")

    def home(self) -> None:
        self.press("home")

    def back(self) -> None:
        self.press("back")

    def app_switcher(self) -> None:
        self.press("recents")

    def capabilities(self) -> Dict[str, Any]:
        return {
            "platform": "android",
            "host_platform": sys.platform,
            "supported": True,
            "mode": "hanson_artemis" if self.is_artemis_ready() else "adb_fallback",
            "checks": {
                "adb_binary": shutil.which(ADB_BIN) is not None,
                "artemis_ready": self.is_artemis_ready(),
                "scrcpy": shutil.which("scrcpy") is not None,
            },
        }

    def current_app(self) -> Optional[str]:
        if self.is_artemis_ready():
            try:
                pf = self._artemis_request("GET", "/preflight", timeout=3.0)
                pkg = pf.get("packageName")
                if pkg:
                    return str(pkg)
            except Exception:
                pass
        out = self._sh("dumpsys activity activities", timeout=12)
        m = (
            re.search(r"topResumedActivity=ActivityRecord\{[^}]*?\bu\d+ ([\w.]+)/", out)
            or re.search(r"mResumedActivity: ActivityRecord\{[^}]*?\bu\d+ ([\w.]+)/", out)
        )
        return m.group(1) if m else None

    def list_apps(self, include_system: bool = False) -> List[str]:
        out = self._sh("pm list packages" + ("" if include_system else " -3"), timeout=15)
        return sorted(ln[len("package:"):].strip() for ln in out.splitlines() if ln.startswith("package:"))

    def open_app(self, name: str) -> str:
        if self.is_artemis_ready():
            res = self._artemis_request("POST", "/action", {"cmd": "launch_app", "app_name": name}, timeout=12.0)
            if res.get("success"):
                return str(res.get("output") or name)
        self._lock_gate()
        pkgs = self.list_apps(include_system=True)
        want = re.sub(r"[^a-z0-9]+", "", name.lower())
        pkg = name if name in pkgs else next((p for p in _APP_IDS.get(want, ()) if p in pkgs), None)
        if pkg is None:
            hits = [p for p in pkgs if want and want in p.lower().replace(".", "")]
            pkg = sorted(hits, key=len)[0] if hits else None
        if pkg is None:
            raise RuntimeError(f"No installed Android app matches {name!r}")
        out = self._sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {shlex.quote(pkg)}")
        comp = next((ln.strip() for ln in reversed(out.splitlines()) if "/" in ln), None)
        if not comp:
            raise RuntimeError(f"{pkg} has no launchable activity")
        self._sh(f"am start --user 0 -W -n {shlex.quote(comp)}")
        return pkg

    def preflight(self) -> Dict[str, Any]:
        if self.is_artemis_ready():
            return self._artemis_request("GET", "/preflight", timeout=5.0)
        text = "\n".join(o["text"] for o in self.screen())
        pkg = self.current_app() or ""
        return safety_guard.evaluate_screen_preflight(text, pkg)

    def wait_for_text(self, query: str, timeout: float = 10.0, exact: bool = False, interval: float = 0.4) -> Optional[Dict[str, Any]]:
        if self.is_artemis_ready():
            res = self._artemis_request("POST", "/wait_for_text", {
                "query": query, "exact": exact, "timeoutMs": int(timeout * 1000),
            }, timeout=timeout + 4.0)
            if res.get("found"):
                return res
            return None
        deadline = time.time() + timeout
        q = query.lower()
        while True:
            hits = [o for o in self.screen() if (o["text"].lower() == q if exact else q in o["text"].lower())]
            if hits:
                return hits[0]
            if time.time() >= deadline:
                return None
            time.sleep(interval)

    def wait_stable(self, timeout: float = 5.0, interval: float = 0.35, settle: int = 2) -> bool:
        if self.is_artemis_ready():
            res = self._artemis_request("POST", "/wait_stable", {
                "timeoutMs": int(timeout * 1000), "settle": settle,
            }, timeout=timeout + 4.0)
            return bool(res.get("settled", False))
        prev, same, deadline = None, 0, time.time() + timeout
        while time.time() < deadline:
            cur = frozenset(o["text"] for o in _content_band_boxes(self))
            same = same + 1 if cur == prev else 0
            if same >= settle - 1:
                return True
            prev = cur
            time.sleep(interval)
        return False

    # --- Hanson Exclusive Extensions (:18888 SSE Dynamic UI, Co-Drawing & VirtualDisplay) ---

    def push_board_event(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return self._artemis_request("POST", "/board/events", payload)

    def codraw_ring(self, nx: float, ny: float, radius: float = 0.10, label: str = "", step_badge: str = "") -> Dict[str, Any]:
        return self._artemis_request("POST", "/codraw/command", {
            "author": "AI", "kind": "SPOTLIGHT_RING",
            "nx": nx, "ny": ny, "radius": radius, "label": label, "stepBadge": step_badge,
        })

    def codraw_arrow(self, x1: float, y1: float, x2: float, y2: float, label: str = "", step_badge: str = "") -> Dict[str, Any]:
        return self._artemis_request("POST", "/codraw/command", {
            "author": "AI", "kind": "ARROW",
            "points": [{"nx": x1, "ny": y1}, {"nx": x2, "ny": y2}],
            "label": label, "stepBadge": step_badge,
        })

    def codraw_clear(self) -> Dict[str, Any]:
        return self._artemis_request("POST", "/codraw/command", {"action": "clear"})

    def vdisplay_start(self, width: int = 1080, height: int = 1920, dpi: int = 320) -> Dict[str, Any]:
        return self._artemis_request("POST", "/vdisplay/start", {"width": width, "height": height, "dpi": dpi})

    def vdisplay_snapshot(self) -> Dict[str, Any]:
        return self._artemis_request("GET", "/vdisplay/snapshot")

    def vdisplay_stop(self) -> Dict[str, Any]:
        return self._artemis_request("POST", "/vdisplay/stop", {})


def resolve_platform(
    platform_arg: Optional[str] = None,
    env: Optional[Dict[str, str]] = None,
    host_platform: Optional[str] = None,
    android_ready: Optional[bool] = None,
    ios_ready: Optional[bool] = None,
) -> str:
    """Resolve target mobile platform (`'android'` or `'ios'`) from CLI/env/host."""
    env_map = os.environ if env is None else env
    raw = (platform_arg or env_map.get("HANSON_PLATFORM") or "auto").strip().lower()
    if raw not in VALID_PLATFORMS:
        raise ValueError(f"platform must be one of {VALID_PLATFORMS}, got {raw!r}")
    if raw in ("android", "ios"):
        return raw

    host = host_platform or sys.platform
    if android_ready is True:
        return "android"
    if host == "darwin":
        if ios_ready is None:
            ios_ready = bool(hanson_ios.check_ios_capabilities(platform_name=host)["supported"])
        if android_ready is None:
            try:
                android_ready = bool(HansonPhoneClient().is_artemis_ready() or any(r[1] == "device" for r in _attached_devices()))
            except Exception:
                android_ready = False
        if ios_ready and not android_ready:
            return "ios"
    return "android"


def create_client(
    platform: Optional[str] = None,
    artemis_url: str = DEFAULT_ARTEMIS_URL,
    artemis_token: str = DEFAULT_ARTEMIS_TOKEN,
    serial: Optional[str] = None,
    ios_driver: Optional[Any] = None,
) -> Any:
    """Instantiate the platform-appropriate Hanson client (`HansonPhoneClient` or `HansonIosMirrorClient`)."""
    resolved = resolve_platform(platform_arg=platform)
    if resolved == "ios":
        return hanson_ios.HansonIosMirrorClient(driver=ios_driver)
    return HansonPhoneClient(artemis_url=artemis_url, artemis_token=artemis_token, serial=serial)


def _content_band_boxes(client: Any, top_frac: float = 0.06, bot_frac: float = 0.92) -> List[Dict[str, Any]]:
    b = client.bounds()
    origin_y = float(b.get("y", 0))
    top, bot = origin_y + b["h"] * top_frac, origin_y + b["h"] * bot_frac
    return [o for o in client.screen() if top < o["y"] < bot]


# =============================================================================
# Flat Pre-Imported Script Namespace Builder
# =============================================================================

def build_script_namespace(client: Any) -> Dict[str, Any]:
    def screen(): return client.screen()
    def ui(): return client.ui()
    def screenshot(path=None): return client.screenshot(path)
    def tap(x, y): return client.tap(x, y)
    def long_press(x, y, duration=0.8): return client.long_press(x, y, duration)
    def drag(x1, y1, x2, y2, duration=0.35): return client.drag(x1, y1, x2, y2, duration)
    def type_text(text): return client.type_text(text)
    def press(key): return client.press(key)
    def home(): return client.home() if hasattr(client, "home") else client.press("home")
    def back(): return client.back() if hasattr(client, "back") else client.press("back")
    def app_switcher(): return client.app_switcher() if hasattr(client, "app_switcher") else client.press("recents")
    def open_app(name):
        pkg = client.open_app(name)
        client.wait_stable(timeout=4.0)
        return pkg
    def current_app(): return client.current_app()
    def list_apps(include_system=False): return client.list_apps(include_system)
    def preflight(): return client.preflight()
    def wait(seconds=1.0): time.sleep(seconds)
    def wait_for_text(query, timeout=10.0, exact=False): return client.wait_for_text(query, timeout=timeout, exact=exact)
    def wait_stable(timeout=5.0, settle=2): return client.wait_stable(timeout=timeout, settle=settle)
    def wait_for_app(app_id, timeout=10.0, interval=0.3):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if client.current_app() == app_id:
                return True
            time.sleep(interval)
        return False

    def find_text(query, exact=False):
        q = query.lower()
        return [o for o in screen() if (o["text"].lower() == q if exact else q in o["text"].lower())]

    def tap_text(query, index=0, exact=False):
        hits = find_text(query, exact=exact)
        if not hits:
            visible = [o["text"] for o in screen()][:25]
            raise RuntimeError(f"no visible text matches {query!r}; saw: {visible}")
        hit = hits[index]
        tap(hit["x"], hit["y"])
        return hit

    def find_nodes(query=None, exact=False, clickable_only=False):
        nodes = ui()
        if clickable_only:
            nodes = [n for n in nodes if n["clickable"]]
        if query is None:
            return nodes
        q = query.lower()
        return [n for n in nodes if any((f.lower() == q if exact else q in f.lower()) for f in (n["text"], n["desc"], n["id"]) if f)]

    def tap_ui(query, index=0, exact=False, clickable_only=False):
        hits = find_nodes(query, exact=exact, clickable_only=clickable_only)
        if not hits:
            visible = [n["text"] or n["desc"] or n["id"] for n in ui()][:25]
            raise RuntimeError(f"no UI node matches {query!r}; saw: {visible}")
        node = hits[index]
        tap(node["x"], node["y"])
        return node

    def scroll(direction="down", amount=0.55, at=None):
        return client.scroll_no_fling(direction=direction, amount=amount, at=at)

    def swipe(direction="up", distance=0.4, at=None):
        b = client.bounds()
        ox, oy = float(b.get("x", 0)), float(b.get("y", 0))
        cx, cy = (ox + b["w"] / 2.0, oy + b["h"] / 2.0) if at is None else ((at["x"], at["y"]) if isinstance(at, dict) else at)
        dx = {"left": -1, "right": 1}.get(direction, 0) * b["w"] * distance
        dy = {"up": -1, "down": 1}.get(direction, 0) * b["h"] * distance
        if not dx and not dy:
            raise ValueError(f"Invalid swipe direction: {direction!r}")
        return drag(cx - dx / 2, cy - dy / 2, cx + dx / 2, cy + dy / 2, duration=0.14)

    def scroll_screen(direction="down", amount=0.55, settle=2.0, at=None):
        before = frozenset(o["text"].strip() for o in _content_band_boxes(client))
        scroll(direction=direction, amount=amount, at=at)
        time.sleep(0.35)
        boxes, prev_set, deadline = _content_band_boxes(client), None, time.time() + settle
        while time.time() < deadline:
            cur = frozenset(o["text"].strip() for o in boxes)
            if cur == prev_set:
                break
            prev_set = cur
            time.sleep(0.25)
            boxes = _content_band_boxes(client)
        return {"before": before, "after": frozenset(o["text"].strip() for o in boxes), "boxes": boxes}

    def scroll_until(done: Callable[[List[Dict[str, Any]]], Any], direction="down", amount=0.55, max_scrolls=40, settle=2.0, at=None):
        hit = done(_content_band_boxes(client))
        if hit:
            return hit
        stale = 0
        for _ in range(max_scrolls):
            res = scroll_screen(direction, amount, settle, at=at)
            hit = done(res["boxes"])
            if hit:
                return hit
            if res["after"] != res["before"]:
                stale = 0
            else:
                stale += 1
                if stale >= 2:
                    return None
        return None

    def scroll_collect(extract=None, key=None, direction="down", amount=0.55, max_scrolls=120, end_after=3, settle=2.0, at=None):
        extract = extract or (lambda boxes: [o["text"].strip() for o in boxes if o["text"].strip()])
        key = key or (lambda x: x)
        seen, order = set(), []

        def ingest(boxes):
            added = 0
            for item in extract(boxes):
                k = key(item)
                if k in seen:
                    continue
                seen.add(k)
                order.append(item)
                added += 1
            return added

        ingest(_content_band_boxes(client))
        stale = 0
        for i in range(1, max_scrolls + 1):
            res = scroll_screen(direction, amount, settle, at=at)
            if ingest(res["boxes"]):
                stale = 0
            else:
                stale += 1
                if stale >= end_after:
                    return {"items": order, "stop": "reached-end", "scrolls": i}
        return {"items": order, "stop": "max-scrolls", "scrolls": max_scrolls}

    ns: Dict[str, Any] = {
        "screen": screen, "ui": ui, "screenshot": screenshot,
        "find_text": find_text, "tap_text": tap_text, "find_nodes": find_nodes, "tap_ui": tap_ui,
        "tap": tap, "long_press": long_press, "drag": drag, "type_text": type_text, "press": press,
        "home": home, "back": back, "app_switcher": app_switcher,
        "open_app": open_app, "current_app": current_app, "list_apps": list_apps,
        "scroll": scroll, "swipe": swipe, "scroll_screen": scroll_screen,
        "scroll_until": scroll_until, "scroll_collect": scroll_collect,
        "wait": wait, "wait_for_text": wait_for_text, "wait_for_app": wait_for_app, "wait_stable": wait_stable,
        "preflight": preflight,
        "platform": getattr(client, "platform", "android"),
        "capabilities": client.capabilities if hasattr(client, "capabilities") else (lambda: {"platform": getattr(client, "platform", "android")}),
        "wrap_untrusted_screen_data": safety_guard.wrap_untrusted_screen_data,
        "push_board_event": client.push_board_event,
        "codraw_ring": client.codraw_ring,
        "codraw_arrow": client.codraw_arrow,
        "codraw_clear": client.codraw_clear,
        "vdisplay_start": client.vdisplay_start,
        "vdisplay_snapshot": client.vdisplay_snapshot,
        "vdisplay_stop": client.vdisplay_stop,
    }

    # Auto-load reusable agent-written helpers from AGENT_WORKSPACE/agent_helpers.py
    agent_helpers_file = AGENT_WORKSPACE / "agent_helpers.py"
    if agent_helpers_file.is_file():
        spec = importlib.util.spec_from_file_location("hanson_agent_helpers", agent_helpers_file)
        if spec and spec.loader:
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            for k, v in vars(mod).items():
                if not k.startswith("_"):
                    ns[k] = v
    return ns


# =============================================================================
# CLI Subcommands: doctor, pair, connect, awake, exec (stdin heredoc)
# =============================================================================

def cmd_doctor(client: Any) -> int:
    plat = getattr(client, "platform", "android")
    print(f"Hanson Phone Harness Doctor (platform={plat})")
    ios_caps = hanson_ios.check_ios_capabilities()
    if plat == "ios":
        print(f"  [{'PASS' if ios_caps['supported'] else 'FAIL'}] macOS iPhone Mirroring: {ios_caps['mode']}")
        for key, ok in ios_caps["checks"].items():
            print(f"    - {key}: {'OK' if ok else 'MISSING'}")
        print(f"  [INFO] guidance: {ios_caps['guidance']}")
        print(f"  [INFO] agent workspace: {AGENT_WORKSPACE}")
        return 0 if ios_caps["supported"] else 1

    adb_ok = shutil.which(ADB_BIN) is not None
    print(f"  [{'PASS' if adb_ok else 'FAIL'}] adb binary: {ADB_BIN}")
    if adb_ok:
        devs = _attached_devices()
        print(f"  [{'PASS' if devs else 'WARN'}] attached devices: {devs or 'none'}")
        mdns = parse_mdns_services(_adb_raw("mdns", "services", check=False), "connect")
        if mdns:
            print(f"  [INFO] mDNS wireless phones: {[m['addr'] for m in mdns]}")
    artemis_ok = client.is_artemis_ready(force_recheck=True)
    print(f"  [{'PASS' if artemis_ok else 'INFO'}] Hanson native :18888 Artemis: {'READY' if artemis_ok else 'offline (ADB fallback available)'}")
    scrcpy_ok = shutil.which("scrcpy") is not None
    print(f"  [{'PASS' if scrcpy_ok else 'INFO'}] scrcpy mirror: {'installed' if scrcpy_ok else 'not installed (optional)'}")
    print(f"  [{'PASS' if ios_caps['supported'] else 'INFO'}] iOS Mirroring host: {ios_caps['mode']} ({ios_caps['host_platform']})")
    print(f"  [INFO] agent workspace: {AGENT_WORKSPACE}")
    return 0 if (adb_ok or artemis_ok) else 1


def cmd_pair(args: List[str]) -> int:
    if not args:
        print("Usage: python harness/hanson_harness.py pair [IP:PORT] <6-DIGIT-CODE> [NAME]")
        return 2
    if ":" in args[0]:
        if len(args) < 2:
            print("Missing 6-digit pairing code")
            return 2
        addr, code, name = args[0], args[1], (args[2] if len(args) > 2 else None)
    else:
        code, name = args[0], (args[1] if len(args) > 1 else None)
        print("Scanning Wi-Fi mDNS for Wireless Debugging pairing dialog...")
        addr = None
        for _ in range(30):
            hits = parse_mdns_services(_adb_raw("mdns", "services", check=False), "pairing")
            if hits:
                addr = hits[0]["addr"]
                break
            time.sleep(1)
        if not addr:
            print("No mDNS pairing device found. Pass IP:PORT explicitly.", file=sys.stderr)
            return 1
    out = _adb_raw("pair", addr, code, timeout=30, check=False)
    if "Successfully paired" not in out:
        print(out.strip() or "Pairing failed", file=sys.stderr)
        return 1
    host = addr.rpartition(":")[0]
    adb_id = None
    for _ in range(10):
        hits = parse_mdns_services(_adb_raw("mdns", "services", check=False), "connect")
        hit = next((h for h in hits if h["host"] == host), None)
        if hit:
            conn = _adb_raw("connect", hit["addr"], timeout=15, check=False)
            if "connected" in conn and "cannot" not in conn:
                adb_id = hit["addr"]
                break
        time.sleep(1)
    if not adb_id:
        print("Paired! Connect using `adb connect <IP:PORT>` from Wireless Debugging screen.")
        return 0
    serial = _adb_raw("-s", adb_id, "shell", "getprop", "ro.serialno", timeout=10).strip()
    model = _adb_raw("-s", adb_id, "shell", "getprop", "ro.product.model", timeout=10).strip()
    key = _remember_device(serial, model, host=host, name=name, primary=True)
    print(f"Paired & connected: {model} as '{key}' ({adb_id})")
    return 0


def cmd_awake(client: HansonPhoneClient, mirror: bool = True, poke_every: int = 12) -> int:
    """Zero-settings-mutation awake companion (`KEYCODE_UNKNOWN` pokes + optional `scrcpy`)."""
    dev = client._pin_adb_serial()
    proc = None
    if mirror and shutil.which("scrcpy"):
        proc = subprocess.Popen(
            ["scrcpy", "-s", dev, "--stay-awake", "--no-audio", "--always-on-top", "--window-width", "360"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
    print(f"Hanson awake active for {dev} (Ctrl-C to let phone sleep naturally)...", flush=True)
    try:
        while True:
            if proc and proc.poll() is not None:
                break
            awake, locked = client._screen_status()
            if not awake:
                client._sh("input keyevent KEYCODE_WAKEUP", timeout=10)
            elif not locked:
                client._sh(POKE_CMD, timeout=10)
            time.sleep(max(5, poke_every) if not locked else 3)
    except KeyboardInterrupt:
        pass
    finally:
        if proc and proc.poll() is None:
            proc.terminate()
    return 0


def _parse_cli_flags(argv: List[str]) -> Tuple[bool, Optional[str], List[str]]:
    safe_mode = False
    platform_arg: Optional[str] = None
    rest: List[str] = []
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok == "--safe":
            safe_mode = True
            i += 1
        elif tok == "--platform":
            if i + 1 >= len(argv):
                raise ValueError("--platform requires a value: android, ios, or auto")
            platform_arg = argv[i + 1]
            i += 2
        elif tok.startswith("--platform="):
            platform_arg = tok.split("=", 1)[1]
            i += 1
        else:
            rest.append(tok)
            i += 1
    return safe_mode, platform_arg, rest


def main(argv: List[str]) -> int:
    try:
        safe_mode, platform_arg, args = _parse_cli_flags(argv)
        resolved_platform = resolve_platform(platform_arg=platform_arg)
    except ValueError as exc:
        print(f"Argument error: {exc}", file=sys.stderr)
        return 2

    if args and args[0] in ("-h", "--help"):
        print("Usage: python harness/hanson_harness.py [--safe] [--platform android|ios|auto] [doctor|pair|awake|preflight] | <<'PY'")
        return 0

    client = create_client(platform=resolved_platform)

    if args and args[0] == "doctor":
        return cmd_doctor(client)
    if args and args[0] == "pair":
        if resolved_platform == "ios":
            print("iOS pairing uses macOS System Settings -> iPhone Mirroring (no ADB mDNS pairing).", file=sys.stderr)
            return 2
        return cmd_pair(args[1:])
    if args and args[0] == "awake":
        if resolved_platform == "ios":
            print("iOS awake mode is managed by macOS iPhone Mirroring.", file=sys.stderr)
            return 2
        return cmd_awake(client, mirror="--no-mirror" not in args)
    if args and args[0] == "preflight":
        print(json.dumps(client.preflight(), ensure_ascii=False, indent=2))
        return 0

    code = Path(args[0]).read_text(encoding="utf-8") if args else sys.stdin.read()
    if safe_mode:
        reason = safety_guard.classify_script(code)
        if reason is not None:
            print(json.dumps({"blocked": True, "reason": reason}, ensure_ascii=False), file=sys.stderr)
            return 3
    ns = build_script_namespace(client)
    exec(compile(code, "<hanson-harness>", "exec"), ns, ns)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
