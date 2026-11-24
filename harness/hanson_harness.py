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
import urllib.parse
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
VALID_SECRETARY_ACTIONS = ("approve", "dismiss", "snooze", "reply", "open_map", "a2ui")
VALID_ROTATIONS: Dict[str, int] = {
    "portrait": 0,
    "landscape": 1,
    "reverse_portrait": 2,
    "reverse_landscape": 3,
    "auto": -1,
}
_SAFE_PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")


# =============================================================================
# Progressive-Disclosure App Cards, Structured Step Errors & Relational Grounding
# =============================================================================

class HansonStepError(RuntimeError):
    """Structured step failure carrying inline UI tree & diff debug context."""

    def __init__(
        self,
        message: str,
        ui_tree: Optional[List[Dict[str, Any]]] = None,
        diff: Optional[Dict[str, Any]] = None,
    ):
        super().__init__(message)
        self.message = message
        self.ui_tree: List[Dict[str, Any]] = list(ui_tree or [])
        self.diff: Dict[str, Any] = dict(diff or {})


class HiddenElementError(HansonStepError):
    """Raised when a matched UI node is hidden behind another view (hidden=True, offscreen=False)."""


class Near:
    """Directional spatial relation matcher (`below`, `above`, `left`, `right`, `any`)."""

    VALID_DIRECTIONS = ("below", "above", "left", "right", "any")

    def __init__(self, target: Any, direction: str = "below", max_distance: float = 360.0):
        d = (direction or "below").strip().lower()
        if d not in self.VALID_DIRECTIONS:
            raise ValueError(f"Near direction must be one of {self.VALID_DIRECTIONS}, got {direction!r}")
        self.target = target
        self.direction = d
        self.max_distance = float(max_distance)

    def _matches_anchor(self, node: Dict[str, Any], all_nodes: List[Dict[str, Any]]) -> bool:
        if isinstance(self.target, Predicate):
            return self.target.matches(node, all_nodes=all_nodes)
        if isinstance(self.target, dict):
            return node is self.target or (
                node.get("x") == self.target.get("x")
                and node.get("y") == self.target.get("y")
                and node.get("text") == self.target.get("text")
            )
        needle = str(self.target or "").lower()
        if not needle:
            return False
        hay = " ".join(str(node.get(k) or "") for k in ("text", "desc", "id")).lower()
        return needle in hay

    def satisfied(self, candidate: Dict[str, Any], all_nodes: List[Dict[str, Any]]) -> bool:
        cx, cy = float(candidate.get("x", 0)), float(candidate.get("y", 0))
        anchors = [n for n in all_nodes if n is not candidate and self._matches_anchor(n, all_nodes)]
        for anchor in anchors:
            ax, ay = float(anchor.get("x", 0)), float(anchor.get("y", 0))
            dx, dy = cx - ax, cy - ay
            dist = (dx * dx + dy * dy) ** 0.5
            if dist > self.max_distance:
                continue
            if self.direction == "any":
                return True
            if self.direction == "below" and dy > 0:
                return True
            if self.direction == "above" and dy < 0:
                return True
            if self.direction == "right" and dx > 0:
                return True
            if self.direction == "left" and dx < 0:
                return True
        return False


def _node_bbox(node: Dict[str, Any]) -> Tuple[float, float, float, float]:
    if all(k in node for k in ("left", "top", "right", "bottom")):
        return float(node["left"]), float(node["top"]), float(node["right"]), float(node["bottom"])
    x, y = float(node.get("x", 0)), float(node.get("y", 0))
    w, h = float(node.get("w", 0)), float(node.get("h", 0))
    return x - w / 2.0, y - h / 2.0, x + w / 2.0, y + h / 2.0


class Predicate:
    """Typed composable UI node matcher with `Near` spatial and `parent_of` hierarchy rules."""

    def __init__(
        self,
        *,
        text: Optional[str] = None,
        text_contains: Optional[str] = None,
        desc_contains: Optional[str] = None,
        id_contains: Optional[str] = None,
        class_contains: Optional[str] = None,
        clickable: Optional[bool] = None,
        scrollable: Optional[bool] = None,
        editable: Optional[bool] = None,
        exclude_offscreen: bool = False,
        near: Optional[Near] = None,
        parent_of: Optional[Any] = None,
    ):
        self.text = text
        self.text_contains = text_contains
        self.desc_contains = desc_contains
        self.id_contains = id_contains
        self.class_contains = class_contains
        self.clickable = clickable
        self.scrollable = scrollable
        self.editable = editable
        self.exclude_offscreen = exclude_offscreen
        self.near = near
        self.parent_of = parent_of

    def _matches_child(self, child: Dict[str, Any], all_nodes: List[Dict[str, Any]]) -> bool:
        if isinstance(self.parent_of, Predicate):
            return self.parent_of.matches(child, all_nodes=all_nodes)
        needle = str(self.parent_of or "").lower()
        hay = " ".join(str(child.get(k) or "") for k in ("text", "desc", "id")).lower()
        return bool(needle) and needle in hay

    def matches(self, node: Dict[str, Any], all_nodes: Optional[List[Dict[str, Any]]] = None) -> bool:
        nodes = all_nodes if all_nodes is not None else [node]
        ntext = str(node.get("text") or "")
        ndesc = str(node.get("desc") or "")
        nid = str(node.get("id") or "")
        ncls = str(node.get("class") or "")

        if self.text is not None and ntext.lower() != self.text.lower():
            return False
        if self.text_contains is not None and self.text_contains.lower() not in ntext.lower():
            return False
        if self.desc_contains is not None and self.desc_contains.lower() not in ndesc.lower():
            return False
        if self.id_contains is not None and self.id_contains.lower() not in nid.lower():
            return False
        if self.class_contains is not None and self.class_contains.lower() not in ncls.lower():
            return False
        if self.clickable is not None and bool(node.get("clickable", False)) != self.clickable:
            return False
        if self.scrollable is not None and bool(node.get("scrollable", False)) != self.scrollable:
            return False
        if self.editable is not None and bool(node.get("editable", False)) != self.editable:
            return False
        if self.exclude_offscreen and bool(node.get("offscreen", False)):
            return False
        if self.near is not None and not self.near.satisfied(node, nodes):
            return False
        if self.parent_of is not None:
            pl, pt, pr, pb = _node_bbox(node)
            p_area = max(0.0, pr - pl) * max(0.0, pb - pt)
            p_idx = node.get("index")
            has_matching_child = False
            for other in nodes:
                if other is node:
                    continue
                if not self._matches_child(other, nodes):
                    continue
                if p_idx is not None and other.get("parent_index") == p_idx:
                    has_matching_child = True
                    break
                cl, ct, cr, cb = _node_bbox(other)
                c_area = max(0.0, cr - cl) * max(0.0, cb - ct)
                if pl <= cl and pt <= ct and pr >= cr and pb >= cb and p_area >= c_area:
                    has_matching_child = True
                    break
            if not has_matching_child:
                return False
        return True

    def filter(self, nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        return [n for n in nodes if self.matches(n, all_nodes=nodes)]


def load_app_card(package_name: str, harness_dir: Optional[Path] = None) -> Optional[Dict[str, str]]:
    """Load an Android app card, letting `harness/local/apps/<pkg>/CARD.md` override shipped cards."""
    pkg = (package_name or "").strip()
    if not _SAFE_PACKAGE_RE.match(pkg):
        raise ValueError(f"Invalid Android package name for app card lookup: {package_name!r}")
    root = Path(harness_dir) if harness_dir is not None else _HERE
    local_card = root / "local" / "apps" / pkg / "CARD.md"
    if local_card.is_file():
        return {
            "package": pkg,
            "source": "local",
            "path": str(local_card),
            "content": local_card.read_text(encoding="utf-8"),
        }
    shipped_card = root / "apps" / "android" / pkg / "CARD.md"
    if shipped_card.is_file():
        return {
            "package": pkg,
            "source": "shipped",
            "path": str(shipped_card),
            "content": shipped_card.read_text(encoding="utf-8"),
        }
    return None


def pick_launchable_package(
    candidates: List[str],
    resolver: Callable[[str], Optional[str]],
) -> Tuple[Optional[str], Optional[str]]:
    """Pre-absorbed phone-harness PR #204: skip non-launcher packages and return first launchable (pkg, component)."""
    for pkg in candidates:
        comp = resolver(pkg)
        if comp and "/" in comp:
            return pkg, comp
    return None, None


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
            screen_w = int(data.get("screenWidth") or 0)
            screen_h = int(data.get("screenHeight") or 0)
            nodes = []
            for el in data.get("elements") or []:
                b = el.get("bounds") or {}
                x1 = int(el.get("left", b.get("left", 0)))
                y1 = int(el.get("top", b.get("top", 0)))
                x2 = int(el.get("right", b.get("right", 0)))
                y2 = int(el.get("bottom", b.get("bottom", 0)))
                cx = int(el.get("centerX", (x1 + x2) // 2))
                cy = int(el.get("centerY", (y1 + y2) // 2))
                offscreen = el.get("offscreen")
                if offscreen is None and screen_w > 0 and screen_h > 0:
                    offscreen = x2 <= 0 or y2 <= 0 or x1 >= screen_w or y1 >= screen_h
                hidden = el.get("hidden")
                if hidden is None:
                    hidden = el.get("visibleToUser") is False
                nodes.append({
                    "index": el.get("index"),
                    "text": el.get("text") or "",
                    "desc": el.get("contentDesc") or el.get("contentDescription") or el.get("desc") or "",
                    "id": el.get("resourceId") or el.get("id") or "",
                    "class": el.get("className") or "",
                    "clickable": bool(el.get("clickable", False)),
                    "scrollable": bool(el.get("scrollable", False)),
                    "editable": bool(el.get("editable", False)),
                    "offscreen": bool(offscreen),
                    "hidden": bool(hidden),
                    "left": x1, "top": y1, "right": x2, "bottom": y2,
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
        parent_map = {child: parent for parent in root.iter() for child in parent}
        el_to_idx: Dict[ET.Element, int] = {}
        nodes = []
        for idx, el in enumerate(root.iter("node")):
            el_to_idx[el] = idx
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", el.get("bounds", ""))
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            parent_el = parent_map.get(el)
            cls_name = el.get("class", "")
            nodes.append({
                "index": idx,
                "parent_index": el_to_idx.get(parent_el) if parent_el is not None else None,
                "text": el.get("text", ""),
                "desc": el.get("content-desc", ""),
                "id": el.get("resource-id", ""),
                "class": cls_name,
                "clickable": el.get("clickable") == "true",
                "scrollable": el.get("scrollable") == "true",
                "editable": "EditText" in cls_name,
                "offscreen": x2 <= 0 or y2 <= 0,
                "hidden": el.get("visible-to-user") == "false",
                "left": x1, "top": y1, "right": x2, "bottom": y2,
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

    def supports(self, capability: str) -> bool:
        """Capability matrix (`stop_app`, `open_deep_link`, `grant_permission`, `vdisplay`, `codraw`, `secretary`, `ui_tree`, `deep_link_inspect`, `ios_portal`)."""
        artemis_ok = self.is_artemis_ready()
        matrix = {
            "stop_app": True,
            "open_deep_link": True,
            "grant_permission": True,
            "vdisplay": artemis_ok,
            "codraw": artemis_ok,
            "secretary": artemis_ok,
            "ui_tree": True,
            "deep_link_inspect": artemis_ok,
            "ios_portal": False,
        }
        return bool(matrix.get((capability or "").strip().lower(), False))

    def capabilities(self) -> Dict[str, Any]:
        artemis_ok = self.is_artemis_ready()
        return {
            "platform": "android",
            "host_platform": sys.platform,
            "supported": True,
            "mode": "hanson_artemis" if artemis_ok else "adb_fallback",
            "supports": {
                cap: self.supports(cap)
                for cap in (
                    "stop_app", "open_deep_link", "grant_permission",
                    "vdisplay", "codraw", "secretary",
                    "ui_tree", "deep_link_inspect", "ios_portal",
                )
            },
            "checks": {
                "adb_binary": shutil.which(ADB_BIN) is not None,
                "artemis_ready": artemis_ok,
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

    def _resolve_launcher_component(self, pkg: str) -> Optional[str]:
        out = self._sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {shlex.quote(pkg)}")
        for ln in reversed(out.splitlines()):
            cand = ln.strip()
            if "/" in cand and not cand.lower().startswith("no activity"):
                return cand
        return None

    def open_app(self, name: str) -> str:
        if self.is_artemis_ready():
            res = self._artemis_request("POST", "/action", {"cmd": "launch_app", "app_name": name}, timeout=12.0)
            if res.get("success"):
                return str(res.get("output") or name)
        self._lock_gate()
        pkgs = self.list_apps(include_system=True)
        want = re.sub(r"[^a-z0-9]+", "", name.lower())
        candidates: List[str] = []
        if name in pkgs:
            candidates.append(name)
        for p in _APP_IDS.get(want, ()):
            if p in pkgs and p not in candidates:
                candidates.append(p)
        if want:
            hits = sorted(
                (p for p in pkgs if want in p.lower().replace(".", "") and p not in candidates),
                key=len,
            )
            candidates.extend(hits)
        if not candidates:
            raise RuntimeError(f"No installed Android app matches {name!r}")
        pkg, comp = pick_launchable_package(candidates, self._resolve_launcher_component)
        if not pkg or not comp:
            raise RuntimeError(f"No launchable Android activity found among candidates for {name!r}: {candidates[:5]}")
        self._sh(f"am start --user 0 -W -n {shlex.quote(comp)}")
        return pkg

    def input_reset(self) -> Dict[str, Any]:
        """Pre-absorbed phone-harness PR #214: clear stuck touch/key/IME state cleanly."""
        if self.is_artemis_ready():
            try:
                res = self._artemis_request("POST", "/action", {"cmd": "input_reset"}, timeout=4.0)
                if res.get("success") or res.get("status") == "ok":
                    return res
            except Exception:
                pass
        self._lock_gate()
        self._sh("input motionevent CANCEL 0 0 || true", timeout=8)
        self._sh("input keyevent KEYCODE_ESCAPE", timeout=8)
        return {"status": "ok", "mode": "adb_reset"}

    def rotate(self, orientation: str = "portrait") -> str:
        """Pre-absorbed phone-harness PR #209: set Android screen orientation cleanly."""
        key = (orientation or "portrait").strip().lower()
        if key not in VALID_ROTATIONS:
            raise ValueError(f"orientation must be one of {sorted(VALID_ROTATIONS)}, got {orientation!r}")
        code = VALID_ROTATIONS[key]
        self._lock_gate()
        if code == -1:
            self._sh("settings put system accelerometer_rotation 1", timeout=8)
        else:
            self._sh(f"settings put system accelerometer_rotation 0 && settings put system user_rotation {code}", timeout=8)
        return key

    def wait_for_notification(
        self,
        query: str,
        timeout: float = 10.0,
        interval: float = 0.4,
    ) -> Optional[Dict[str, Any]]:
        """Pre-absorbed phone-harness PR #213: wait until a notification matches `query`."""
        needle = (query or "").strip().lower()
        if not needle:
            raise ValueError("query must be non-empty")
        deadline = time.time() + max(0.0, float(timeout))
        while True:
            if self.is_artemis_ready():
                try:
                    res = self._artemis_request(
                        "POST",
                        "/rpc",
                        {"cmd": "notifications", "params": {"only_replyable": False, "limit": 25}},
                        timeout=4.0,
                    )
                    output = str(res.get("output") or "")
                    for line in output.splitlines():
                        if needle in line.lower():
                            return {"matched": True, "text": line.strip(), "source": "hanson_artemis"}
                except Exception:
                    pass
            else:
                out = self._sh("dumpsys notification --noredact", timeout=10)
                for line in out.splitlines():
                    if needle in line.lower():
                        return {"matched": True, "text": line.strip()[:240], "source": "adb_dumpsys"}
            if time.time() >= deadline:
                return None
            time.sleep(max(0.05, float(interval)))

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

    def vdisplay_launch(
        self,
        package_name: str,
        activity: str = "",
        display_id: Optional[int] = None,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"package": package_name.strip()}
        if activity.strip():
            payload["activity"] = activity.strip()
        if display_id is not None:
            payload["displayId"] = int(display_id)
        return self._artemis_request("POST", "/vdisplay/launch", payload)

    def vdisplay_input(
        self,
        action: str,
        x: Optional[int] = None,
        y: Optional[int] = None,
        x2: Optional[int] = None,
        y2: Optional[int] = None,
        text: str = "",
        key: str = "",
        duration_ms: int = 300,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"action": (action or "").strip().lower(), "durationMs": int(duration_ms)}
        if x is not None:
            payload["x"] = int(x)
        if y is not None:
            payload["y"] = int(y)
        if x2 is not None:
            payload["x2"] = int(x2)
        if y2 is not None:
            payload["y2"] = int(y2)
        if text:
            payload["text"] = text
        if key:
            payload["key"] = key
        return self._artemis_request("POST", "/vdisplay/input", payload)

    def vdisplay_mode(self, mode: str = "background") -> Dict[str, Any]:
        norm = (mode or "background").strip().lower()
        if norm not in ("foreground", "background", "idle"):
            raise ValueError(f"vdisplay mode must be foreground|background|idle, got {mode!r}")
        return self._artemis_request("POST", "/vdisplay/mode", {"mode": norm})

    def discover_deep_links(self, package_name: str) -> Dict[str, Any]:
        pkg = (package_name or "").strip()
        if not _SAFE_PACKAGE_RE.match(pkg):
            raise ValueError(f"Invalid Android package name: {package_name!r}")
        qs = urllib.parse.urlencode({"package": pkg})
        return self._artemis_request("GET", f"/portal/deep-links?{qs}")

    def resolve_deep_link(self, uri: str) -> Dict[str, Any]:
        clean = (uri or "").strip()
        if not clean:
            raise ValueError("uri must be non-empty")
        return self._artemis_request("POST", "/portal/deep-link/resolve", {"uri": clean})

    # --- Proactive Chief-of-Staff AI Secretary (:18888/secretary/*) ---

    def secretary_proposals(self, status: str = "PENDING", limit: int = 20) -> Dict[str, Any]:
        norm_status = (status or "PENDING").strip().upper()
        norm_limit = max(1, min(int(limit), 200))
        query = urllib.parse.urlencode({"status": norm_status, "limit": norm_limit})
        return self._artemis_request("GET", f"/secretary/proposals?{query}")

    def secretary_scan(
        self,
        include_weather: bool = False,
        lat: Optional[float] = None,
        lon: Optional[float] = None,
    ) -> Dict[str, Any]:
        payload: Dict[str, Any] = {"include_weather": bool(include_weather)}
        if lat is not None and lon is not None:
            payload["lat"] = float(lat)
            payload["lon"] = float(lon)
        return self._artemis_request("POST", "/secretary/scan", payload, timeout=15.0)

    def secretary_act(
        self,
        proposal_id: str,
        action: str = "approve",
        reply_text: str = "",
        snooze_minutes: int = 60,
    ) -> Dict[str, Any]:
        pid = (proposal_id or "").strip()
        if not pid:
            raise ValueError("proposal_id must be non-empty")
        act = (action or "approve").strip().lower()
        if act not in VALID_SECRETARY_ACTIONS:
            raise ValueError(f"action must be one of {VALID_SECRETARY_ACTIONS}, got {action!r}")
        payload: Dict[str, Any] = {
            "proposal_id": pid,
            "action": act,
            "reply_text": str(reply_text or ""),
            "snooze_minutes": max(1, int(snooze_minutes)),
        }
        return self._artemis_request("POST", "/secretary/act", payload)

    def secretary_push_signal(
        self,
        package_name: str,
        sender: str,
        text: str,
        app_label: str = "",
        room: str = "",
        can_quick_reply: bool = False,
        timestamp_ms: Optional[int] = None,
    ) -> Dict[str, Any]:
        pkg = (package_name or "").strip()
        if not pkg:
            raise ValueError("package_name must be non-empty")
        ts = int(timestamp_ms) if timestamp_ms is not None else int(time.time() * 1000)
        payload: Dict[str, Any] = {
            "package_name": pkg,
            "app_label": (app_label or pkg).strip(),
            "sender": str(sender or "").strip(),
            "room": str(room or "").strip(),
            "text": str(text or ""),
            "can_quick_reply": bool(can_quick_reply),
            "timestamp_ms": ts,
        }
        return self._artemis_request("POST", "/secretary/ingest", payload)


HansonPhone = HansonPhoneClient


def _default_secretary_client(client: Optional[Any] = None) -> HansonPhoneClient:
    if isinstance(client, HansonPhoneClient):
        return client
    url = (
        getattr(client, "companion_url", None)
        or os.environ.get("ARTEMIS_URL")
        or DEFAULT_ARTEMIS_URL
    )
    token = (
        getattr(client, "companion_token", None)
        or os.environ.get("ARTEMIS_TOKEN")
        or DEFAULT_ARTEMIS_TOKEN
    )
    return HansonPhoneClient(artemis_url=str(url), artemis_token=str(token))


def secretary_proposals(
    status: str = "PENDING",
    limit: int = 20,
    *,
    client: Optional[Any] = None,
) -> Dict[str, Any]:
    return _default_secretary_client(client).secretary_proposals(status=status, limit=limit)


def secretary_scan(
    include_weather: bool = False,
    lat: Optional[float] = None,
    lon: Optional[float] = None,
    *,
    client: Optional[Any] = None,
) -> Dict[str, Any]:
    return _default_secretary_client(client).secretary_scan(
        include_weather=include_weather, lat=lat, lon=lon,
    )


def secretary_act(
    proposal_id: str,
    action: str = "approve",
    reply_text: str = "",
    snooze_minutes: int = 60,
    *,
    client: Optional[Any] = None,
) -> Dict[str, Any]:
    return _default_secretary_client(client).secretary_act(
        proposal_id=proposal_id,
        action=action,
        reply_text=reply_text,
        snooze_minutes=snooze_minutes,
    )


def secretary_push_signal(
    package_name: str,
    sender: str,
    text: str,
    app_label: str = "",
    room: str = "",
    can_quick_reply: bool = False,
    timestamp_ms: Optional[int] = None,
    *,
    client: Optional[Any] = None,
) -> Dict[str, Any]:
    return _default_secretary_client(client).secretary_push_signal(
        package_name=package_name,
        sender=sender,
        text=text,
        app_label=app_label,
        room=room,
        can_quick_reply=can_quick_reply,
        timestamp_ms=timestamp_ms,
    )


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
        boxes = screen()
        q = query.lower()
        hits = [o for o in boxes if (o["text"].lower() == q if exact else q in o["text"].lower())]
        if not hits:
            visible = [o["text"] for o in boxes][:25]
            raise HansonStepError(
                f"no visible text matches {query!r}; saw: {visible}",
                ui_tree=boxes[:25],
                diff={"query": query, "exact": exact, "visible_count": len(boxes)},
            )
        hit = hits[index]
        tap(hit["x"], hit["y"])
        return hit

    def find_nodes(query=None, exact=False, clickable_only=False):
        nodes = ui()
        if isinstance(query, Predicate):
            hits = query.filter(nodes)
            return [n for n in hits if n.get("clickable")] if clickable_only else hits
        if clickable_only:
            nodes = [n for n in nodes if n.get("clickable")]
        if query is None:
            return nodes
        q = query.lower()
        return [n for n in nodes if any((f.lower() == q if exact else q in f.lower()) for f in (n.get("text", ""), n.get("desc", ""), n.get("id", "")) if f)]

    def tap_node(node_or_pred, index=0):
        nodes = ui()
        if isinstance(node_or_pred, Predicate):
            hits = node_or_pred.filter(nodes)
            if not hits:
                raise HansonStepError(
                    f"no UI node matches Predicate; saw {len(nodes)} nodes",
                    ui_tree=nodes[:25],
                    diff={"matched": 0, "total_nodes": len(nodes)},
                )
            node = hits[index]
        elif isinstance(node_or_pred, dict):
            node = node_or_pred
        else:
            raise TypeError("tap_node expects a UI node dict or Predicate")
        if node.get("hidden") and not node.get("offscreen"):
            raise HiddenElementError(
                f"Refusing tap on hidden (covered, non-offscreen) UI element: {node.get('text') or node.get('desc') or node.get('id') or node.get('index')}",
                ui_tree=nodes[:25],
                diff={"blocked_node": node},
            )
        tap(node["x"], node["y"])
        return node

    def tap_ui(query, index=0, exact=False, clickable_only=False):
        nodes = ui()
        if isinstance(query, Predicate):
            hits = query.filter(nodes)
            if clickable_only:
                hits = [n for n in hits if n.get("clickable")]
        else:
            pool = [n for n in nodes if n.get("clickable")] if clickable_only else nodes
            q = str(query).lower()
            hits = [n for n in pool if any((f.lower() == q if exact else q in f.lower()) for f in (n.get("text", ""), n.get("desc", ""), n.get("id", "")) if f)]
        if not hits:
            visible = [n.get("text") or n.get("desc") or n.get("id") for n in nodes][:25]
            raise HansonStepError(
                f"no UI node matches {query!r}; saw: {visible}",
                ui_tree=nodes[:25],
                diff={"query": repr(query), "total_nodes": len(nodes)},
            )
        node = hits[index]
        if node.get("hidden") and not node.get("offscreen"):
            raise HiddenElementError(
                f"Refusing tap on hidden (covered, non-offscreen) UI element matching {query!r}",
                ui_tree=nodes[:25],
                diff={"blocked_node": node},
            )
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
        boosted_retry_used = False
        for _ in range(max_scrolls):
            res = scroll_screen(direction, amount, settle, at=at)
            hit = done(res["boxes"])
            if hit:
                return hit
            if res["after"] != res["before"]:
                stale = 0
                boosted_retry_used = False
            else:
                if not boosted_retry_used:
                    boosted_retry_used = True
                    stronger = min(0.95, float(amount) * 1.35)
                    res2 = scroll_screen(direction, stronger, settle, at=at)
                    hit2 = done(res2["boxes"])
                    if hit2:
                        return hit2
                    if res2["after"] != res2["before"]:
                        stale = 0
                        continue
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

    def _sec_proposals(status="PENDING", limit=20):
        return (
            client.secretary_proposals(status=status, limit=limit)
            if hasattr(client, "secretary_proposals")
            else secretary_proposals(status=status, limit=limit, client=client)
        )

    def _sec_scan(include_weather=False, lat=None, lon=None):
        return (
            client.secretary_scan(include_weather=include_weather, lat=lat, lon=lon)
            if hasattr(client, "secretary_scan")
            else secretary_scan(include_weather=include_weather, lat=lat, lon=lon, client=client)
        )

    def _sec_act(proposal_id, action="approve", reply_text="", snooze_minutes=60):
        return (
            client.secretary_act(
                proposal_id=proposal_id,
                action=action,
                reply_text=reply_text,
                snooze_minutes=snooze_minutes,
            )
            if hasattr(client, "secretary_act")
            else secretary_act(
                proposal_id=proposal_id,
                action=action,
                reply_text=reply_text,
                snooze_minutes=snooze_minutes,
                client=client,
            )
        )

    def _sec_push_signal(
        package_name,
        sender,
        text,
        app_label="",
        room="",
        can_quick_reply=False,
        timestamp_ms=None,
    ):
        return (
            client.secretary_push_signal(
                package_name=package_name,
                sender=sender,
                text=text,
                app_label=app_label,
                room=room,
                can_quick_reply=can_quick_reply,
                timestamp_ms=timestamp_ms,
            )
            if hasattr(client, "secretary_push_signal")
            else secretary_push_signal(
                package_name=package_name,
                sender=sender,
                text=text,
                app_label=app_label,
                room=room,
                can_quick_reply=can_quick_reply,
                timestamp_ms=timestamp_ms,
                client=client,
            )
        )

    def _supports(cap):
        return client.supports(cap) if hasattr(client, "supports") else False

    ns: Dict[str, Any] = {
        "device": client,
        "supports": _supports,
        "load_app_card": load_app_card,
        "Predicate": Predicate,
        "Near": Near,
        "HansonStepError": HansonStepError,
        "HiddenElementError": HiddenElementError,
        "screen": screen, "ui": ui, "screenshot": screenshot,
        "find_text": find_text, "tap_text": tap_text, "find_nodes": find_nodes, "tap_ui": tap_ui, "tap_node": tap_node,
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
        "vdisplay_launch": getattr(client, "vdisplay_launch", None),
        "vdisplay_input": getattr(client, "vdisplay_input", None),
        "vdisplay_mode": getattr(client, "vdisplay_mode", None),
        "discover_deep_links": getattr(client, "discover_deep_links", None),
        "resolve_deep_link": getattr(client, "resolve_deep_link", None),
        "input_reset": getattr(client, "input_reset", None),
        "wait_for_notification": getattr(client, "wait_for_notification", None),
        "rotate": getattr(client, "rotate", None),
        "secretary_proposals": _sec_proposals,
        "secretary_scan": _sec_scan,
        "secretary_act": _sec_act,
        "secretary_push_signal": _sec_push_signal,
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


def cmd_secretary(client: Any, args: List[str]) -> int:
    """CLI handler for `secretary list|scan|act|push` against `:18888/secretary/*`."""
    import argparse

    sec_client = _default_secretary_client(client)
    parser = argparse.ArgumentParser(
        prog="hanson_harness.py secretary",
        description="Proactive Chief-of-Staff AI Secretary commands (:18888/secretary/*)",
    )
    sub = parser.add_subparsers(dest="subcmd")

    p_list = sub.add_parser("list", help="List secretary proposals")
    p_list.add_argument("--status", default="PENDING", help="PENDING | ALL | APPROVED | DISMISSED | SNOOZED")
    p_list.add_argument("--limit", type=int, default=20, help="Max proposals to fetch")

    p_scan = sub.add_parser("scan", help="Trigger proactive calendar/thread/weather scan")
    p_scan.add_argument("--weather", action="store_true", help="Include Open-Meteo weather check (opt-in)")
    p_scan.add_argument("--lat", type=float, default=None, help="Latitude for weather check")
    p_scan.add_argument("--lon", type=float, default=None, help="Longitude for weather check")

    p_act = sub.add_parser("act", help="Act on a proactive proposal")
    p_act.add_argument("proposal_id", help="Target proposal ID")
    p_act.add_argument(
        "--action",
        default="approve",
        choices=list(VALID_SECRETARY_ACTIONS),
        help="Action to execute on the proposal",
    )
    p_act.add_argument("--reply", dest="reply_text", default="", help="Reply text (when --action reply)")
    p_act.add_argument("--snooze-minutes", type=int, default=60, help="Snooze duration in minutes")

    p_push = sub.add_parser("push", help="Push a signal to /secretary/ingest")
    p_push.add_argument("--package", dest="package_name", required=True, help="Source package name")
    p_push.add_argument("--sender", required=True, help="Sender name or service")
    p_push.add_argument("--text", required=True, help="Message body text")
    p_push.add_argument("--app-label", default="", help="Human-readable app label")
    p_push.add_argument("--room", default="", help="Room, thread, or title")
    p_push.add_argument("--quick-reply", action="store_true", help="Mark signal as quick-reply capable")
    p_push.add_argument("--timestamp-ms", type=int, default=None, help="Event timestamp in epoch ms")

    if not args or args[0] in ("-h", "--help"):
        parser.print_help()
        return 0

    try:
        ns = parser.parse_args(args)
    except SystemExit as exc:
        return int(exc.code) if isinstance(exc.code, int) else 2

    if ns.subcmd == "list":
        res = sec_client.secretary_proposals(status=ns.status, limit=ns.limit)
    elif ns.subcmd == "scan":
        res = sec_client.secretary_scan(include_weather=ns.weather, lat=ns.lat, lon=ns.lon)
    elif ns.subcmd == "act":
        res = sec_client.secretary_act(
            proposal_id=ns.proposal_id,
            action=ns.action,
            reply_text=ns.reply_text,
            snooze_minutes=ns.snooze_minutes,
        )
    elif ns.subcmd == "push":
        res = sec_client.secretary_push_signal(
            package_name=ns.package_name,
            sender=ns.sender,
            text=ns.text,
            app_label=ns.app_label,
            room=ns.room,
            can_quick_reply=ns.quick_reply,
            timestamp_ms=ns.timestamp_ms,
        )
    else:
        parser.print_help()
        return 2

    print(json.dumps(res, ensure_ascii=False, indent=2))
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
        print("Usage: python harness/hanson_harness.py [--safe] [--platform android|ios|auto] [doctor|pair|awake|preflight|secretary <list|scan|act|push>] | <<'PY'")
        return 0

    client = create_client(
        platform=resolved_platform,
        artemis_url=os.environ.get("ARTEMIS_URL", DEFAULT_ARTEMIS_URL),
        artemis_token=os.environ.get("ARTEMIS_TOKEN", DEFAULT_ARTEMIS_TOKEN),
    )

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
    if args and args[0] == "secretary":
        return cmd_secretary(client, args[1:])

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
