#!/usr/bin/env python3
"""
Hanson Mobile Agent Studio v4.0 — Open-Source Android MCP Server (`stdio`)
==========================================================================
Zero-dependency (Python 3.9+ stdlib only) Model Context Protocol (MCP) server
that gives Claude Code, Codex, Gemini CLI, Cursor, and Antigravity full visual
and structural control over any connected Android device (12 MCP tools).

Studio v4.0 Prompt & Bridge Integration:
- On-device Studio modes & composer syntax: prefix goals with `/plan` (generate
  `PLAN.md` blueprint and await approval) or `/build` (execute directly across
  device GUI + workspace sandbox), and reference `@screen`, `@app`, or `@file:<path>`.
- Desktop Workspace Bridge (`laptop-cli-bridge.py --workspace-root ./agent_workspace`):
  exposes path-sandboxed `/workspace/*` routes on `:18889` (`adb reverse tcp:18889 tcp:18889`)
  for read/write/edit/search/exec alongside this `:18888` MCP device control server.

Architecture:
- Primary Fast Path (`http://127.0.0.1:18888`, `ArtemisCommandServer`):
  Sub-50ms multi-window Z-layer Accessibility UI tree + Set-of-Marks (`[index]`)
  annotated JPEG visual grounding + instant Korean/Unicode `ACTION_SET_TEXT` +
  screen-off `RemoteInput` notification quick-reply.
- Automatic Fallback (`adb shell`):
  If the Hanson Android app's `:18888` bridge is not yet running or token auth
  is unconfigured, transparently falls back to `uiautomator dump`, `screencap -p`,
  `input tap/swipe/keyevent`, and `am start` so control works out of the box.

Environment Variables (all optional):
- `ARTEMIS_URL`    : Phone loopback URL (default: `http://127.0.0.1:18888`)
- `ARTEMIS_TOKEN`  : 256-bit session token from the Hanson [Inspector] tab
- `ANDROID_SERIAL` : Specific ADB device serial when multiple phones are attached
- `ADB_PATH`       : Custom path to `adb` executable if not on `PATH`
"""

from __future__ import annotations

import base64
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from typing import Any, Dict, List, Optional, Tuple

SERVER_NAME = "hanson-mobile-use-mcp"
SERVER_VERSION = "6.0.0"
SUPPORTED_PROTOCOL_VERSIONS = ("2025-03-26", "2024-11-05")
STUDIO_INSTRUCTIONS = (
    "Hanson Mobile Agent Studio v6.0 MCP Server (12 tools; on-device engine 42 tools). "
    "Always call mobile_get_screen first to obtain 0-based [element_index] targets and Set-of-Marks visuals, "
    "and verify screen transitions after every action. "
    "v6.0 pairs Human-First Companion & Studio dual-mode UX with transparent Memory Bubbles, "
    "ACL 2026 D-Artemis (TAC+ACA) pre-execution target/input alignment, 1-tap reversible receipts, "
    "Blind Credential Vault handles (`@vault` / `/vault` without LLM plaintext), "
    "financial checkout & price-drift approval gates, non-bypassable CAPTCHA/2FA human takeover, "
    "Proactive Watchers (`/watch`), Notetaker scratchpad (`/notes`), A2A scheduling (`/a2a`), "
    "and Portal v6 endpoints (`/portal/ping`, `/portal/state`, `/portal/brief`, `/portal/undo`). "
    "Prefix prompts with `/plan` or `/build` and attach `@screen`, `@app`, `@vault`, `@notes`, `@watchers`, or `@file:<path>`."
)

DEFAULT_ARTEMIS_URL = os.environ.get("ARTEMIS_URL", "http://127.0.0.1:18888").rstrip("/")
DEFAULT_ARTEMIS_TOKEN = os.environ.get("ARTEMIS_TOKEN", "").strip()
DEFAULT_ANDROID_SERIAL = os.environ.get("ANDROID_SERIAL", "").strip()

PACKAGE_NAME_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$")
KEYCODE_RE = re.compile(r"^[A-Za-z0-9_]{1,48}$")
ALLOWED_URL_SCHEMES = {
    "https",
    "http",
    "kakaotalk",
    "youtube",
    "nmap",
    "kakaomap",
    "baemin",
    "supertoss",
    "coupang",
    "instagram",
    "market",
    "geo",
}


def _validate_safe_url(raw_url: str) -> Optional[str]:
    clean = (raw_url or "").strip()
    if not clean or len(clean) > 2048:
        return None
    if any(ch in clean for ch in ("\x00", "\n", "\r")):
        return None
    parsed = urllib.parse.urlparse(clean)
    if parsed.scheme.lower() not in ALLOWED_URL_SCHEMES:
        return None
    return clean


def _validate_package_name(pkg: str) -> bool:
    return bool(PACKAGE_NAME_RE.match((pkg or "").strip()))


def _validate_keycode(keycode: str) -> bool:
    return bool(KEYCODE_RE.match((keycode or "").strip()))

APP_PACKAGE_ALIASES: Dict[str, str] = {
    "배달의민족": "com.sampleapp",
    "배민": "com.sampleapp",
    "카카오톡": "com.kakao.talk",
    "카톡": "com.kakao.talk",
    "유튜브": "com.google.android.youtube",
    "youtube": "com.google.android.youtube",
    "설정": "com.android.settings",
    "settings": "com.android.settings",
    "크롬": "com.android.chrome",
    "chrome": "com.android.chrome",
    "네이버": "com.nhn.android.search",
    "naver": "com.nhn.android.search",
    "쿠팡": "com.coupang.mobile",
    "토스": "viva.republica.toss",
    "인스타그램": "com.instagram.android",
    "instagram": "com.instagram.android",
    "갤러리": "com.sec.android.gallery3d",
    "카메라": "com.sec.android.app.camera",
    "시계": "com.sec.android.app.clockpackage",
    "gmail": "com.google.android.gm",
    "maps": "com.google.android.apps.maps",
    "구글맵": "com.google.android.apps.maps",
}

KEYEVENT_MAP: Dict[str, str] = {
    "HOME": "3",
    "BACK": "4",
    "CALL": "5",
    "ENDCALL": "6",
    "VOLUME_UP": "24",
    "VOLUME_DOWN": "25",
    "POWER": "26",
    "CAMERA": "27",
    "CLEAR": "28",
    "TAB": "61",
    "SPACE": "62",
    "ENTER": "66",
    "DEL": "67",
    "BACKSPACE": "67",
    "MENU": "82",
    "SEARCH": "84",
    "MEDIA_PLAY_PAUSE": "85",
    "ESCAPE": "111",
    "RECENTS": "187",
    "APP_SWITCH": "187",
}


class MobileBridgeState:
    def __init__(self) -> None:
        self.artemis_url: str = DEFAULT_ARTEMIS_URL
        self.artemis_token: str = DEFAULT_ARTEMIS_TOKEN
        self.android_serial: str = DEFAULT_ANDROID_SERIAL
        self.adb_forwarded: bool = False
        self.screen_width: int = 1080
        self.screen_height: int = 2400
        self.last_elements: Dict[int, Dict[str, Any]] = {}

    def resolve_adb(self) -> Optional[str]:
        explicit = os.environ.get("ADB_PATH", "").strip()
        if explicit and os.path.isfile(explicit):
            return explicit
        found = shutil.which("adb")
        if found:
            return found
        for env_key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
            sdk = os.environ.get(env_key, "").strip()
            if sdk:
                exe = "adb.exe" if os.name == "nt" else "adb"
                cand = os.path.join(sdk, "platform-tools", exe)
                if os.path.isfile(cand):
                    return cand
        if os.name == "nt":
            local_app = os.environ.get("LOCALAPPDATA", "")
            if local_app:
                cand = os.path.join(local_app, "Android", "Sdk", "platform-tools", "adb.exe")
                if os.path.isfile(cand):
                    return cand
        return None

    def run_adb(self, args: List[str], timeout: float = 12.0, binary: bool = False) -> Tuple[int, Any, str]:
        adb = self.resolve_adb()
        if not adb:
            return 127, (b"" if binary else ""), "adb executable not found on PATH or ANDROID_HOME"
        cmd = [adb]
        if self.android_serial:
            cmd.extend(["-s", self.android_serial])
        cmd.extend(args)
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
                check=False,
            )
            stdout = proc.stdout if binary else proc.stdout.decode("utf-8", errors="replace")
            stderr = proc.stderr.decode("utf-8", errors="replace")
            return proc.returncode, stdout, stderr
        except Exception as exc:
            return 1, (b"" if binary else ""), str(exc)

    def ensure_adb_forward(self) -> None:
        if self.adb_forwarded:
            return
        parsed = urllib.parse.urlparse(self.artemis_url)
        port = parsed.port or 18888
        if parsed.hostname in ("127.0.0.1", "localhost"):
            rc, _, _ = self.run_adb(["forward", f"tcp:{port}", f"tcp:{port}"], timeout=4.0)
            if rc == 0:
                self.adb_forwarded = True

    def artemis_request(
        self,
        path: str,
        method: str = "GET",
        payload: Optional[Dict[str, Any]] = None,
        timeout: float = 8.0,
    ) -> Tuple[Optional[Dict[str, Any]], Optional[str]]:
        self.ensure_adb_forward()
        url = f"{self.artemis_url}{path}"
        headers: Dict[str, str] = {"Accept": "application/json"}
        if self.artemis_token:
            headers["X-Artemis-Token"] = self.artemis_token
        data_bytes: Optional[bytes] = None
        if payload is not None:
            if self.artemis_token and "token" not in payload:
                payload = {**payload, "token": self.artemis_token}
            data_bytes = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"

        req = urllib.request.Request(url, data=data_bytes, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
                return json.loads(raw), None
        except urllib.error.HTTPError as http_err:
            err_body = ""
            try:
                err_body = http_err.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            return None, f"HTTP {http_err.code}: {err_body or http_err.reason}"
        except Exception as exc:
            return None, str(exc)

    def update_cached_elements(self, elements: List[Dict[str, Any]], width: int, height: int) -> None:
        if width > 0:
            self.screen_width = width
        if height > 0:
            self.screen_height = height
        self.last_elements = {int(el["index"]): el for el in elements if "index" in el}

    def norm_to_px(self, norm_x: float, norm_y: float) -> Tuple[int, int]:
        px = int(round(max(0.0, min(1000.0, float(norm_x))) * self.screen_width / 1000.0))
        py = int(round(max(0.0, min(1000.0, float(norm_y))) * self.screen_height / 1000.0))
        return px, py


STATE = MobileBridgeState()


def _parse_bounds_str(bounds_str: str) -> Optional[Tuple[int, int, int, int]]:
    m = re.match(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$", bounds_str.strip())
    if not m:
        return None
    return int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))


def _adb_capture_screen_fallback(include_screenshot: bool, max_elements: int) -> Dict[str, Any]:
    w, h = STATE.screen_width, STATE.screen_height
    rc_wm, out_wm, _ = STATE.run_adb(["shell", "wm", "size"], timeout=4.0)
    if rc_wm == 0:
        m = re.search(r"(\d+)x(\d+)", str(out_wm))
        if m:
            w, h = int(m.group(1)), int(m.group(2))

    STATE.run_adb(["shell", "uiautomator", "dump", "/sdcard/hanson_mcp_dump.xml"], timeout=8.0)
    rc_xml, xml_text, err_xml = STATE.run_adb(["shell", "cat", "/sdcard/hanson_mcp_dump.xml"], timeout=6.0)

    elements: List[Dict[str, Any]] = []
    active_pkg = ""
    if rc_xml == 0 and "<hierarchy" in str(xml_text):
        raw_xml = str(xml_text)
        start = raw_xml.find("<hierarchy")
        raw_xml = raw_xml[start:]
        try:
            root = ET.fromstring(raw_xml)
            idx = 0
            for node in root.iter("node"):
                bounds = _parse_bounds_str(node.attrib.get("bounds", ""))
                if not bounds:
                    continue
                left, top, right, bottom = bounds
                if right - left <= 2 or bottom - top <= 2:
                    continue
                text = (node.attrib.get("text") or "").strip()
                desc = (node.attrib.get("content-desc") or "").strip()
                res_id = (node.attrib.get("resource-id") or "").strip()
                cls_name = (node.attrib.get("class") or "android.view.View").strip()
                pkg = (node.attrib.get("package") or "").strip()
                if not active_pkg and pkg and pkg != "com.android.systemui":
                    active_pkg = pkg
                clickable = node.attrib.get("clickable") == "true"
                long_clickable = node.attrib.get("long-clickable") == "true"
                scrollable = node.attrib.get("scrollable") == "true"
                focusable = node.attrib.get("focusable") == "true"
                editable = "EditText" in cls_name
                checkable = node.attrib.get("checkable") == "true"
                checked = node.attrib.get("checked") == "true"
                focused = node.attrib.get("focused") == "true"
                enabled = node.attrib.get("enabled", "true") == "true"

                if not (clickable or long_clickable or scrollable or editable or checkable or text or desc):
                    continue

                cx = (left + right) // 2
                cy = (top + bottom) // 2
                nx = int(round(cx * 1000.0 / max(1, w)))
                ny = int(round(cy * 1000.0 / max(1, h)))
                elements.append(
                    {
                        "index": idx,
                        "shortClass": cls_name.split(".")[-1],
                        "className": cls_name,
                        "packageName": pkg,
                        "text": text,
                        "contentDesc": desc,
                        "resourceId": res_id,
                        "left": left,
                        "top": top,
                        "right": right,
                        "bottom": bottom,
                        "centerX": cx,
                        "centerY": cy,
                        "normX": nx,
                        "normY": ny,
                        "clickable": clickable,
                        "longClickable": long_clickable,
                        "scrollable": scrollable,
                        "editable": editable or (clickable and focusable and "Edit" in cls_name),
                        "checkable": checkable,
                        "checked": checked,
                        "focused": focused,
                        "enabled": enabled,
                    }
                )
                idx += 1
        except Exception:
            pass

    STATE.update_cached_elements(elements[:max_elements], w, h)

    png_b64: Optional[str] = None
    if include_screenshot:
        rc_cap, raw_png, _ = STATE.run_adb(["exec-out", "screencap", "-p"], timeout=8.0, binary=True)
        if rc_cap == 0 and raw_png and bytes(raw_png).startswith(b"\x89PNG"):
            png_b64 = base64.b64encode(bytes(raw_png)).decode("ascii")

    return {
        "transport": "adb_fallback",
        "screenWidth": w,
        "screenHeight": h,
        "activePackage": active_pkg,
        "elements": elements[:max_elements],
        "totalElements": len(elements),
        "imageBase64": png_b64,
        "imageMimeType": "image/png" if png_b64 else None,
        "adbError": err_xml if rc_xml != 0 else None,
    }


def _format_elements_markdown(
    transport: str,
    width: int,
    height: int,
    active_pkg: str,
    window_title: str,
    elements: List[Dict[str, Any]],
    total_count: int,
    note: Optional[str] = None,
) -> str:
    lines = [
        f"Transport: {transport} | Screen: {width}x{height} px (norm 0..1000) | Active Package: {active_pkg or 'unknown'}",
    ]
    if window_title:
        lines.append(f"Window Title: {window_title}")
    if note:
        lines.append(f"Note: {note}")
    lines.append(f"Interactive/Informative UI Elements ({len(elements)} shown of {total_count}):")
    if not elements:
        lines.append("  (No accessibility elements exposed; use screenshot visual coordinates norm_x/norm_y in 0..1000)")
    for el in elements:
        idx = el.get("index")
        short_cls = el.get("shortClass") or str(el.get("className", "View")).split(".")[-1]
        parts = [f"[{idx}] {short_cls}"]
        txt = (el.get("text") or "").replace("\n", " ").strip()
        desc = (el.get("contentDesc") or "").replace("\n", " ").strip()
        res = (el.get("resourceId") or "").split("/")[-1].strip()
        if txt:
            parts.append(f'text="{txt[:80]}"')
        if desc:
            parts.append(f'desc="{desc[:60]}"')
        if res:
            parts.append(f'id="{res}"')
        parts.append(f"norm=[{el.get('normX', 500)},{el.get('normY', 500)}]")
        parts.append(f"px=({el.get('centerX', 0)},{el.get('centerY', 0)})")
        flags = [
            flag
            for flag in ("clickable", "longClickable", "scrollable", "editable", "checked", "focused")
            if el.get(flag)
        ]
        if flags:
            parts.append("flags={" + ",".join(flags) + "}")
        lines.append("  " + " ".join(parts))
    return "\n".join(lines)


# =============================================================================
# MCP TOOL DEFINITIONS (12 TOOLS)
# =============================================================================

TOOLS_SPEC: List[Dict[str, Any]] = [
    {
        "name": "mobile_get_screen",
        "description": (
            "Captures the live Android screen state (`@screen` / `@app` context): 0-based indexed UI elements "
            "([index], text, contentDesc, normalized 0..1000 coordinates, pixel coordinates, flags) plus a "
            "Set-of-Marks annotated screenshot with [index] bounding boxes overlaid on interactive controls. "
            "Always call this before clicking or after an action to verify the post-action screen transition. "
            "Pairs with Studio v4.0 `/plan` (PLAN.md blueprint) and `/build` (workspace + device execution) flows "
            "when coupled with `laptop-cli-bridge.py --workspace-root`."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "include_screenshot": {
                    "type": "boolean",
                    "description": "Include Set-of-Marks screenshot image block (default: true).",
                    "default": True,
                },
                "max_elements": {
                    "type": "integer",
                    "description": "Maximum number of indexed UI elements to return (default: 80).",
                    "default": 80,
                },
            },
        },
    },
    {
        "name": "mobile_click",
        "description": (
            "Clicks/taps an element on the Android screen by [element_index] from mobile_get_screen "
            "(preferred), visible text/description substring, normalized 0..1000 coordinates (norm_x, norm_y), "
            "or pixel coordinates (x, y)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "element_index": {
                    "type": "integer",
                    "description": "0-based [index] from the latest mobile_get_screen output.",
                },
                "text": {
                    "type": "string",
                    "description": "Visible text or contentDescription label to match if element_index is omitted.",
                },
                "norm_x": {
                    "type": "number",
                    "description": "Normalized horizontal coordinate in 0..1000.",
                },
                "norm_y": {
                    "type": "number",
                    "description": "Normalized vertical coordinate in 0..1000.",
                },
                "x": {"type": "number", "description": "Raw pixel X coordinate."},
                "y": {"type": "number", "description": "Raw pixel Y coordinate."},
            },
        },
    },
    {
        "name": "mobile_long_press",
        "description": "Long-presses an element by [element_index], normalized (norm_x, norm_y), or pixel (x, y) coordinates.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "element_index": {"type": "integer"},
                "norm_x": {"type": "number"},
                "norm_y": {"type": "number"},
                "x": {"type": "number"},
                "y": {"type": "number"},
                "duration_ms": {"type": "integer", "default": 700},
            },
        },
    },
    {
        "name": "mobile_input_text",
        "description": (
            "Types Korean, English, or Unicode text into an Android input field. Optionally taps "
            "0-based [element_index] first to focus the field, clears existing text, and sends ENTER/SEARCH. "
            "When typing into the Hanson Studio v4.0 composer, you can prefix goals with `/plan` "
            "(generate PLAN.md & wait for approval) or `/build` (execute directly with workspace + device control) "
            "and reference `@screen`, `@app`, or `@file:<path>`."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type into the focused input field."},
                "element_index": {
                    "type": "integer",
                    "description": "Optional [index] of the EditText/search box to focus before typing.",
                },
                "clear_existing": {"type": "boolean", "default": False},
                "submit_enter": {
                    "type": "boolean",
                    "description": "Press ENTER / IME_ACTION_SEARCH after inserting text (default: true).",
                    "default": True,
                },
            },
            "required": ["text"],
        },
    },
    {
        "name": "mobile_swipe",
        "description": (
            "Swipes/scrolls the screen. Use direction='up' to scroll down (reveal lower content), "
            "'down' to scroll up, 'left', or 'right' — or pass explicit start_norm_x/y and end_norm_x/y (0..1000)."
        ),
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {
                    "type": "string",
                    "enum": ["up", "down", "left", "right"],
                    "description": "Swipe finger movement direction ('up' scrolls down the page).",
                },
                "start_norm_x": {"type": "number"},
                "start_norm_y": {"type": "number"},
                "end_norm_x": {"type": "number"},
                "end_norm_y": {"type": "number"},
                "duration_ms": {"type": "integer", "default": 350},
            },
        },
    },
    {
        "name": "mobile_press_key",
        "description": "Presses a global/system Android key: HOME, BACK, RECENTS, ENTER, NOTIFICATIONS, QUICK_SETTINGS, VOLUME_UP, VOLUME_DOWN.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Key name (HOME, BACK, RECENTS, ENTER, NOTIFICATIONS, QUICK_SETTINGS, VOLUME_UP, VOLUME_DOWN).",
                }
            },
            "required": ["key"],
        },
    },
    {
        "name": "mobile_launch_app",
        "description": "Launches an installed Android app by Korean/English display name (e.g. '배달의민족', '카카오톡', 'YouTube') or package name.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "app_name": {
                    "type": "string",
                    "description": "App display name or Android package name (e.g. '배달의민족' or 'com.kakao.talk').",
                }
            },
            "required": ["app_name"],
        },
    },
    {
        "name": "mobile_list_apps",
        "description": "Lists launchable apps installed on the Android device, optionally filtered by query.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string", "description": "Optional substring filter for app name or package."}
            },
        },
    },
    {
        "name": "mobile_open_url",
        "description": "Opens an https:// URL or Android app deep link (intent://, youtube://, kakaotalk://) directly.",
        "inputSchema": {
            "type": "object",
            "properties": {"url": {"type": "string", "description": "Target URL or deep link URI."}},
            "required": ["url"],
        },
    },
    {
        "name": "mobile_get_notifications",
        "description": "Lists active Android notifications (KakaoTalk, SMS, Slack, system alerts) and whether each supports background quick-reply.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "only_replyable": {"type": "boolean", "default": False},
                "limit": {"type": "integer", "default": 15},
            },
        },
    },
    {
        "name": "mobile_reply_notification",
        "description": "Replies directly to a notification (e.g. KakaoTalk/SMS/Slack) in the background via RemoteInput without waking the screen.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "target": {
                    "type": "string",
                    "description": "Sender name, chat room title, or notification key (leave empty for most recent replyable notification).",
                },
                "message": {"type": "string", "description": "Reply text to send."},
            },
            "required": ["message"],
        },
    },
    {
        "name": "mobile_device_setting",
        "description": "Controls Android hardware/OS settings directly: brightness, volume_media, wifi, bluetooth, dnd, torch, ringer_mode.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "setting": {
                    "type": "string",
                    "description": "Setting key (brightness, volume_media, wifi, bluetooth, dnd, torch, ringer_mode).",
                },
                "value": {
                    "type": "string",
                    "description": "Target value (e.g. '50', 'on', 'off', 'vibrate', 'silent').",
                },
            },
            "required": ["setting", "value"],
        },
    },
]


def _resolve_target_px(args: Dict[str, Any]) -> Tuple[Optional[int], Optional[int], str]:
    if "element_index" in args and args["element_index"] is not None:
        idx = int(args["element_index"])
        cached = STATE.last_elements.get(idx)
        if cached:
            return int(cached["centerX"]), int(cached["centerY"]), f"[{idx}] {cached.get('text') or cached.get('contentDesc') or cached.get('shortClass')}"
    if args.get("text"):
        needle = str(args["text"]).strip().lower()
        for idx, el in STATE.last_elements.items():
            hay = f"{el.get('text', '')} {el.get('contentDesc', '')}".lower()
            if needle in hay:
                return int(el["centerX"]), int(el["centerY"]), f"[{idx}] {el.get('text') or el.get('contentDesc')}"
    if "norm_x" in args and "norm_y" in args and args["norm_x"] is not None and args["norm_y"] is not None:
        px, py = STATE.norm_to_px(float(args["norm_x"]), float(args["norm_y"]))
        return px, py, f"norm=({args['norm_x']},{args['norm_y']})"
    if "x" in args and "y" in args and args["x"] is not None and args["y"] is not None:
        return int(round(float(args["x"]))), int(round(float(args["y"]))), f"px=({args['x']},{args['y']})"
    return None, None, "unresolved coordinates"


def execute_mcp_tool(name: str, args: Dict[str, Any]) -> Dict[str, Any]:
    args = args or {}

    if name == "mobile_get_screen":
        include_shot = bool(args.get("include_screenshot", True))
        max_els = max(1, min(200, int(args.get("max_elements", 80))))
        qs = "true" if include_shot else "false"
        snap, err = STATE.artemis_request(f"/snapshot?screenshot={qs}", method="GET", timeout=10.0)
        if snap is not None and "elements" in snap:
            elements = list(snap.get("elements") or [])
            w = int(snap.get("screenWidth", 1080))
            h = int(snap.get("screenHeight", 2400))
            STATE.update_cached_elements(elements, w, h)
            sliced = elements[:max_els]
            text_block = _format_elements_markdown(
                transport="artemis_18888 (Set-of-Marks active)",
                width=w,
                height=h,
                active_pkg=str(snap.get("activePackage", "")),
                window_title=str(snap.get("activeWindowTitle", "")),
                elements=sliced,
                total_count=len(elements),
            )
            content: List[Dict[str, Any]] = [{"type": "text", "text": text_block}]
            img_b64 = snap.get("somScreenshot") or snap.get("screenshot")
            if include_shot and img_b64:
                content.append({"type": "image", "data": img_b64, "mimeType": "image/jpeg"})
            return {"content": content, "isError": False}

        # Fallback to ADB
        fb = _adb_capture_screen_fallback(include_screenshot=include_shot, max_elements=max_els)
        hint = (
            f"(:18888 unreachable: {err}. Used ADB fallback — open Hanson Mobile-Use or set ARTEMIS_TOKEN for 10x faster Set-of-Marks.)"
            if err
            else None
        )
        text_block = _format_elements_markdown(
            transport="adb_fallback",
            width=fb["screenWidth"],
            height=fb["screenHeight"],
            active_pkg=fb["activePackage"],
            window_title="",
            elements=fb["elements"],
            total_count=fb["totalElements"],
            note=hint,
        )
        content = [{"type": "text", "text": text_block}]
        if include_shot and fb.get("imageBase64"):
            content.append({"type": "image", "data": fb["imageBase64"], "mimeType": fb["imageMimeType"] or "image/png"})
        return {"content": content, "isError": bool(fb.get("adbError") and not fb["elements"])}

    if name == "mobile_click":
        rpc_params: Dict[str, Any] = {}
        if args.get("element_index") is not None:
            rpc_params["element_index"] = int(args["element_index"])
        if args.get("text"):
            rpc_params["target_description"] = str(args["text"])
        if args.get("norm_x") is not None and args.get("norm_y") is not None:
            rpc_params["norm_x"] = int(round(float(args["norm_x"])))
            rpc_params["norm_y"] = int(round(float(args["norm_y"])))

        if rpc_params:
            res, err = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "click_element", "params": rpc_params})
            if res and res.get("success"):
                return {"content": [{"type": "text", "text": res.get("output") or f"Clicked {rpc_params}"}], "isError": False}

        px, py, label = _resolve_target_px(args)
        if px is None or py is None:
            return {
                "content": [{"type": "text", "text": "Error: Provide element_index (call mobile_get_screen first), text, norm_x/norm_y, or x/y."}],
                "isError": True,
            }
        res, _ = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "tap", "params": {"x": px, "y": py}})
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": f"Tapped {label} at px=({px},{py}) via :18888"}], "isError": False}
        rc, _, stderr = STATE.run_adb(["shell", "input", "tap", str(px), str(py)])
        ok = rc == 0
        return {
            "content": [{"type": "text", "text": f"Tapped {label} at px=({px},{py}) via adb" if ok else f"ADB tap failed: {stderr}"}],
            "isError": not ok,
        }

    if name == "mobile_long_press":
        dur = max(200, int(args.get("duration_ms", 700)))
        px, py, label = _resolve_target_px(args)
        if px is None or py is None:
            return {"content": [{"type": "text", "text": "Error: Provide element_index, norm_x/norm_y, or x/y."}], "isError": True}
        res, _ = STATE.artemis_request(
            "/rpc", method="POST", payload={"cmd": "long_press", "params": {"x": px, "y": py, "duration": dur}}
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": f"Long-pressed {label} ({dur}ms) via :18888"}], "isError": False}
        rc, _, stderr = STATE.run_adb(["shell", "input", "touchscreen", "swipe", str(px), str(py), str(px), str(py), str(dur)])
        return {
            "content": [{"type": "text", "text": f"Long-pressed {label} ({dur}ms) via adb" if rc == 0 else f"Failed: {stderr}"}],
            "isError": rc != 0,
        }

    if name == "mobile_input_text":
        text = str(args.get("text", ""))
        clear = bool(args.get("clear_existing", False))
        submit = bool(args.get("submit_enter", True))
        tool_args: Dict[str, Any] = {"text": text, "clear_existing": clear, "submit_enter": submit}
        if args.get("element_index") is not None:
            tool_args["element_index"] = int(args["element_index"])
        res, _ = STATE.artemis_request(
            "/rpc", method="POST", payload={"cmd": "tool", "params": {"tool_name": "input_text", "args": tool_args}}
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or f"Typed '{text}' via :18888"}], "isError": False}

        # ADB fallback: tap element if specified, then shell-quoted input text or clipboard
        if any(ch in text for ch in ("\x00", "\n", "\r")):
            return {
                "content": [{"type": "text", "text": "Security rejection: control characters/newlines are forbidden in ADB text fallback."}],
                "isError": True,
            }
        if args.get("element_index") is not None:
            px, py, _ = _resolve_target_px(args)
            if px is not None and py is not None:
                STATE.run_adb(["shell", "input", "tap", str(px), str(py)])
        if text.isascii():
            escaped = text.replace(" ", "%s")
            rc, _, stderr = STATE.run_adb(["shell", "input", "text", shlex.quote(escaped)])
        else:
            # Non-ASCII Korean fallback via shell-quoted cmd clipboard
            STATE.run_adb(["shell", "cmd", "clipboard", "set-text", shlex.quote(text)])
            rc, _, stderr = STATE.run_adb(["shell", "input", "keyevent", "279"])  # KEYCODE_PASTE
        if submit:
            STATE.run_adb(["shell", "input", "keyevent", "66"])
        return {
            "content": [
                {
                    "type": "text",
                    "text": f"Typed '{text}' (submit_enter={submit}) via adb"
                    if rc == 0
                    else f"Input text fallback warning: {stderr}. Enable Hanson :18888 Accessibility bridge for native Korean ACTION_SET_TEXT.",
                }
            ],
            "isError": rc != 0,
        }

    if name == "mobile_swipe":
        dur = max(100, int(args.get("duration_ms", 350)))
        direction = str(args.get("direction", "")).lower().strip()
        if all(k in args and args[k] is not None for k in ("start_norm_x", "start_norm_y", "end_norm_x", "end_norm_y")):
            x1, y1 = STATE.norm_to_px(float(args["start_norm_x"]), float(args["start_norm_y"]))
            x2, y2 = STATE.norm_to_px(float(args["end_norm_x"]), float(args["end_norm_y"]))
        else:
            presets = {
                "up": (500, 760, 500, 260),
                "down": (500, 260, 500, 760),
                "left": (820, 500, 180, 500),
                "right": (180, 500, 820, 500),
            }
            nx1, ny1, nx2, ny2 = presets.get(direction or "up", (500, 760, 500, 260))
            x1, y1 = STATE.norm_to_px(nx1, ny1)
            x2, y2 = STATE.norm_to_px(nx2, ny2)

        res, _ = STATE.artemis_request(
            "/rpc",
            method="POST",
            payload={"cmd": "swipe", "params": {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration": dur}},
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": f"Swiped ({x1},{y1}) -> ({x2},{y2}) in {dur}ms via :18888"}], "isError": False}
        rc, _, stderr = STATE.run_adb(["shell", "input", "touchscreen", "swipe", str(x1), str(y1), str(x2), str(y2), str(dur)])
        return {
            "content": [{"type": "text", "text": f"Swiped ({x1},{y1}) -> ({x2},{y2}) via adb" if rc == 0 else f"Swipe failed: {stderr}"}],
            "isError": rc != 0,
        }

    if name == "mobile_press_key":
        key = str(args.get("key", "HOME")).strip().upper()
        res, _ = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "press_key", "params": {"key": key}})
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": f"Pressed {key} via :18888"}], "isError": False}
        keycode = KEYEVENT_MAP.get(key, key)
        if not _validate_keycode(keycode):
            return {
                "content": [{"type": "text", "text": f"Security rejection: invalid keycode '{key}'"}],
                "isError": True,
            }
        rc, _, stderr = STATE.run_adb(["shell", "input", "keyevent", keycode])
        return {
            "content": [{"type": "text", "text": f"Pressed {key} (keycode {keycode}) via adb" if rc == 0 else f"Key press failed: {stderr}"}],
            "isError": rc != 0,
        }

    if name == "mobile_launch_app":
        app_name = str(args.get("app_name", "")).strip()
        res, _ = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "launch_app", "params": {"app_name": app_name}})
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or f"Launched {app_name}"}], "isError": False}
        pkg = APP_PACKAGE_ALIASES.get(app_name) or APP_PACKAGE_ALIASES.get(app_name.lower()) or app_name
        if not _validate_package_name(pkg):
            return {
                "content": [{"type": "text", "text": f"Security rejection: invalid Android package name '{pkg}'"}],
                "isError": True,
            }
        rc, out, stderr = STATE.run_adb(["shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"])
        ok = rc == 0 and "No activities found" not in str(out)
        return {
            "content": [{"type": "text", "text": f"Launched package '{pkg}' via adb" if ok else f"Failed to launch '{app_name}' ({pkg}): {out or stderr}"}],
            "isError": not ok,
        }

    if name == "mobile_list_apps":
        query = str(args.get("query", "")).strip()
        res, _ = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "list_apps", "params": {"query": query}})
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or "Listed apps"}], "isError": False}
        rc, out, stderr = STATE.run_adb(["shell", "pm", "list", "packages", "-3"])
        if rc != 0:
            return {"content": [{"type": "text", "text": f"Failed to list packages: {stderr}"}], "isError": True}
        pkgs = [line.replace("package:", "").strip() for line in str(out).splitlines() if line.strip()]
        if query:
            q = query.lower()
            pkgs = [p for p in pkgs if q in p.lower()]
        return {"content": [{"type": "text", "text": f"Installed 3rd-party packages ({len(pkgs)}):\n" + "\n".join(pkgs[:120])}], "isError": False}

    if name == "mobile_open_url":
        url = str(args.get("url", "")).strip()
        safe_url = _validate_safe_url(url)
        if safe_url is None:
            return {
                "content": [{"type": "text", "text": f"Security rejection: disallowed or malformed URL scheme '{url[:80]}'"}],
                "isError": True,
            }
        res, _ = STATE.artemis_request("/rpc", method="POST", payload={"cmd": "open_url", "params": {"url": safe_url}})
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or f"Opened {safe_url}"}], "isError": False}
        rc, out, stderr = STATE.run_adb(["shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", shlex.quote(safe_url)])
        return {
            "content": [{"type": "text", "text": f"Opened URL {safe_url} via adb: {out.strip()}" if rc == 0 else f"Failed: {stderr}"}],
            "isError": rc != 0,
        }

    if name == "mobile_get_notifications":
        res, err = STATE.artemis_request(
            "/rpc",
            method="POST",
            payload={
                "cmd": "notifications",
                "params": {
                    "only_replyable": bool(args.get("only_replyable", False)),
                    "limit": int(args.get("limit", 15)),
                },
            },
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or "No active notifications."}], "isError": False}
        rc, out, stderr = STATE.run_adb(["shell", "dumpsys", "notification", "--noredact"])
        if rc == 0:
            snippet = "\n".join(str(out).splitlines()[:80])
            return {"content": [{"type": "text", "text": f"[ADB dumpsys notification excerpt]\n{snippet}"}], "isError": False}
        return {"content": [{"type": "text", "text": f"Notification read failed ({err or stderr})"}], "isError": True}

    if name == "mobile_reply_notification":
        res, err = STATE.artemis_request(
            "/rpc",
            method="POST",
            payload={
                "cmd": "reply_notification",
                "params": {
                    "target": str(args.get("target", "")),
                    "message": str(args.get("message", "")),
                },
            },
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or "Background reply sent."}], "isError": False}
        return {
            "content": [
                {
                    "type": "text",
                    "text": f"Screen-off RemoteInput reply requires Hanson NotificationListenerService + :18888 bridge ({err or (res or {}).get('output', 'failed')}).",
                }
            ],
            "isError": True,
        }

    if name == "mobile_device_setting":
        setting = str(args.get("setting", "")).strip()
        val = str(args.get("value", "")).strip()
        res, _ = STATE.artemis_request(
            "/rpc",
            method="POST",
            payload={"cmd": "device_setting", "params": {"setting": setting, "value": val}},
        )
        if res and res.get("success"):
            return {"content": [{"type": "text", "text": res.get("output") or f"Updated {setting}={val}"}], "isError": False}
        if setting == "brightness" and val.isdigit():
            rc, _, stderr = STATE.run_adb(["shell", "settings", "put", "system", "screen_brightness", val])
            return {"content": [{"type": "text", "text": f"Set brightness={val} via adb" if rc == 0 else stderr}], "isError": rc != 0}
        if setting in ("wifi", "bluetooth"):
            sub = "enable" if val.lower() in ("on", "true", "1", "enable") else "disable"
            rc, _, stderr = STATE.run_adb(["shell", "svc", setting, sub])
            return {"content": [{"type": "text", "text": f"svc {setting} {sub} via adb" if rc == 0 else stderr}], "isError": rc != 0}
        return {
            "content": [{"type": "text", "text": f"Setting '{setting}' requires Hanson :18888 bridge active on the phone."}],
            "isError": True,
        }

    return {"content": [{"type": "text", "text": f"Unknown tool: {name}"}], "isError": True}


# =============================================================================
# JSON-RPC 2.0 STDIO TRANSPORT (Supports Newline-Delimited & Content-Length)
# =============================================================================

def handle_jsonrpc_message(msg: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    method = msg.get("method", "")
    msg_id = msg.get("id")
    params = msg.get("params") or {}

    # Notifications (no id) must not receive a response per JSON-RPC 2.0
    if msg_id is None:
        return None

    if method == "initialize":
        requested_version = str(params.get("protocolVersion", ""))
        negotiated = requested_version if requested_version in SUPPORTED_PROTOCOL_VERSIONS else SUPPORTED_PROTOCOL_VERSIONS[0]
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": negotiated,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": STUDIO_INSTRUCTIONS,
            },
        }

    if method == "ping":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {}}

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS_SPEC}}

    if method == "resources/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"resources": []}}

    if method == "prompts/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"prompts": []}}

    if method == "tools/call":
        tool_name = str(params.get("name", ""))
        tool_args = params.get("arguments") or {}
        try:
            result = execute_mcp_tool(tool_name, tool_args)
            return {"jsonrpc": "2.0", "id": msg_id, "result": result}
        except Exception as exc:
            return {
                "jsonrpc": "2.0",
                "id": msg_id,
                "result": {
                    "content": [{"type": "text", "text": f"Tool execution exception in {tool_name}: {exc}"}],
                    "isError": True,
                },
            }

    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "error": {"code": -32601, "message": f"Method not found: {method}"},
    }


def _read_next_message(stdin_buf) -> Tuple[Optional[Dict[str, Any]], bool]:
    """Reads one JSON-RPC message. Returns (parsed_dict, used_content_length_framing)."""
    while True:
        first_line = stdin_buf.readline()
        if not first_line:
            return None, False
        stripped = first_line.strip()
        if not stripped:
            continue

        if stripped.lower().startswith(b"content-length:"):
            length = int(stripped.split(b":", 1)[1].strip())
            # Consume remaining headers up to blank line
            while True:
                hdr = stdin_buf.readline()
                if not hdr or hdr in (b"\r\n", b"\n"):
                    break
            body = stdin_buf.read(length)
            if not body:
                return None, True
            return json.loads(body.decode("utf-8")), True

        return json.loads(stripped.decode("utf-8")), False


def _write_message(stdout_buf, payload: Dict[str, Any], use_content_length: bool) -> None:
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if use_content_length:
        header = f"Content-Length: {len(raw)}\r\n\r\n".encode("ascii")
        stdout_buf.write(header)
        stdout_buf.write(raw)
    else:
        stdout_buf.write(raw + b"\n")
    stdout_buf.flush()


def main() -> None:
    if os.name == "nt":
        import msvcrt

        msvcrt.setmode(sys.stdin.fileno(), os.O_BINARY)
        msvcrt.setmode(sys.stdout.fileno(), os.O_BINARY)

    stdin_buf = sys.stdin.buffer
    stdout_buf = sys.stdout.buffer

    while True:
        try:
            msg, use_cl = _read_next_message(stdin_buf)
            if msg is None:
                break
            resp = handle_jsonrpc_message(msg)
            if resp is not None:
                _write_message(stdout_buf, resp, use_cl)
        except json.JSONDecodeError as exc:
            err_resp = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": f"Parse error: {exc}"},
            }
            _write_message(stdout_buf, err_resp, False)
        except KeyboardInterrupt:
            break


if __name__ == "__main__":
    main()
