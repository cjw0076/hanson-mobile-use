#!/usr/bin/env python3
"""Unit tests for Hanson iOS Driver (`hanson_ios`), Platform Routing (`hanson_harness`),
Upstream Tracker (`track_upstream_phone_harness`), and SwiftUI Companion contracts.

Zero external dependencies (Python 3.9+ stdlib only). Always run with `python -B`.
"""
from __future__ import annotations

import base64
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

HARNESS_DIR = Path(__file__).resolve().parent
REPO_ROOT = HARNESS_DIR.parent
if str(HARNESS_DIR) not in sys.path:
    sys.path.insert(0, str(HARNESS_DIR))

import hanson_harness  # noqa: E402
import hanson_ios  # noqa: E402
import track_upstream_phone_harness as tracker  # noqa: E402


class _MockIosDriver:
    """In-memory mock implementation of `hanson_ios.IosMirrorDriver` for cross-OS testing."""

    def __init__(self, boxes: Optional[List[Dict[str, Any]]] = None):
        self.events: List[Tuple[str, Any]] = []
        self.window = {"x": 100, "y": 50, "w": 390, "h": 844, "id": 4201, "pid": 9001}
        self.boxes = boxes if boxes is not None else [
            {"text": "Settings", "confidence": 0.99, "x": 210.0, "y": 300.0, "w": 88.0, "h": 24.0},
            {"text": "Wi-Fi", "confidence": 0.95, "x": 210.0, "y": 380.0, "w": 52.0, "h": 22.0},
            {"text": "Noise", "confidence": 0.12, "x": 120.0, "y": 700.0, "w": 20.0, "h": 12.0},
        ]

    def find_window(self) -> Optional[Dict[str, Any]]:
        return dict(self.window)

    def capture(self, path: Optional[str] = None) -> Tuple[str, Dict[str, Any]]:
        out = path or "mock-ios-screen.png"
        self.events.append(("capture", out))
        return out, dict(self.window)

    def recognize_text(self, path: str, window: Dict[str, Any]) -> List[Dict[str, Any]]:
        self.events.append(("recognize_text", path))
        return [dict(b) for b in self.boxes]

    def tap(self, x: float, y: float) -> None:
        self.events.append(("tap", (x, y)))

    def long_press(self, x: float, y: float, duration: float = 0.8) -> None:
        self.events.append(("long_press", (x, y, duration)))

    def drag(self, x1: float, y1: float, x2: float, y2: float, duration: float = 0.35, steps: int = 14) -> None:
        self.events.append(("drag", (x1, y1, x2, y2, duration, steps)))

    def scroll_wheel(self, dy: int, dx: int, x: float, y: float, steps: int = 6) -> None:
        self.events.append(("scroll_wheel", (dy, dx, x, y, steps)))

    def press(self, combo: str) -> None:
        self.events.append(("press", combo))

    def type_text(self, text: str, delay: float = 0.03, keystrokes: bool = False) -> None:
        self.events.append(("type_text", (text, keystrokes)))

    def session_status(self) -> Dict[str, Any]:
        return {"state": "ready", "detail": "", "connect_buttons": []}


class TestHansonIosDriver(unittest.TestCase):
    def test_capabilities_non_macos_host_reports_clear_guidance(self):
        caps = hanson_ios.check_ios_capabilities(
            platform_name="win32",
            has_quartz=False,
            has_vision=False,
            has_foundation=False,
            has_appkit=False,
            mirror_app_exists=False,
            has_screencapture=False,
        )
        self.assertFalse(caps["supported"])
        self.assertEqual(caps["mode"], "unsupported_host")
        self.assertIn("macOS 15+", caps["guidance"])
        self.assertTrue(caps["missing"])

    def test_capabilities_macos_background_and_foreground_modes(self):
        bg = hanson_ios.check_ios_capabilities(
            platform_name="darwin",
            has_quartz=True,
            has_vision=True,
            has_foundation=True,
            has_appkit=True,
            mirror_app_exists=True,
            has_screencapture=True,
            background_requested=True,
        )
        self.assertTrue(bg["supported"])
        self.assertEqual(bg["mode"], "background_skylight")

        fg = hanson_ios.check_ios_capabilities(
            platform_name="darwin",
            has_quartz=True,
            has_vision=True,
            has_foundation=True,
            has_appkit=True,
            mirror_app_exists=True,
            has_screencapture=True,
            background_requested=False,
        )
        self.assertTrue(fg["supported"])
        self.assertEqual(fg["mode"], "foreground_quartz")

    def test_vision_bbox_to_screen_point_flips_y_axis_accurately(self):
        # Vision box near top-left (y=0.80, h=0.10 => top edge at 0.10 from top)
        win = {"x": 100, "y": 50, "w": 400, "h": 800}
        pt = hanson_ios.vision_bbox_to_screen_point(
            norm_x=0.25, norm_y=0.80, norm_w=0.50, norm_h=0.10,
            img_w=800, img_h=1600, window=win,
        )
        self.assertEqual(pt["x"], 300.0)  # 100 + 0.50 * 400
        self.assertEqual(pt["y"], 170.0)  # 50 + (0.10 + 0.05) * 800
        self.assertEqual(pt["w"], 200.0)
        self.assertEqual(pt["h"], 80.0)

    def test_parse_key_combo_and_scroll_deltas(self):
        code, mods, mod_codes = hanson_ios.parse_key_combo("cmd+1")
        self.assertEqual(code, 18)
        self.assertEqual(mods, ("cmd",))
        self.assertEqual(mod_codes, (55,))

        bang_code, bang_mods, _ = hanson_ios.parse_key_combo("!")
        self.assertEqual(bang_code, 18)
        self.assertIn("shift", bang_mods)

        with self.assertRaises(ValueError):
            hanson_ios.parse_key_combo("super+z")

        dy, dx, steps = hanson_ios.compute_scroll_wheel_deltas("down", 0.5, {"w": 400, "h": 800}, steps=6)
        self.assertGreater(dy, 0)
        self.assertEqual(dx, 0)
        self.assertEqual(steps, 6)

        with self.assertRaises(ValueError):
            hanson_ios.compute_scroll_wheel_deltas("diagonal", 0.5, {"w": 400, "h": 800})

    def test_classify_ax_session_state(self):
        self.assertEqual(hanson_ios.classify_ax_session_state(False, None, [])["state"], "not-running")
        self.assertEqual(hanson_ios.classify_ax_session_state(True, None, [])["state"], "no-window")
        self.assertEqual(
            hanson_ios.classify_ax_session_state(True, {"id": 1}, [])["state"],
            "ready",
        )
        blocked = hanson_ios.classify_ax_session_state(
            True,
            {"id": 1},
            [("AXStaticText", "iPhone in Use"), ("AXButton", "Try Again")],
        )
        self.assertEqual(blocked["state"], "blocked")
        self.assertEqual(blocked["connect_buttons"], ["Try Again"])

    def test_ios_client_full_flat_contract_and_preflight(self):
        drv = _MockIosDriver()
        client = hanson_ios.HansonIosMirrorClient(driver=drv, sleep_fn=lambda _: None)

        self.assertEqual(client.bounds()["w"], 390)
        boxes = client.screen(min_confidence=0.3)
        self.assertEqual(len(boxes), 2)
        self.assertTrue(all(b["source"] == "pixels" for b in boxes))

        client.tap(210, 300)
        client.long_press(210, 300, duration=0.9)
        client.drag(200, 600, 200, 200, duration=0.3, steps=10)
        client.scroll_no_fling("down", amount=0.5)
        client.type_text("한손 테스트")
        client.press("return")
        client.home()
        client.app_switcher()
        launched = client.open_app("Safari")
        self.assertEqual(launched, "Safari")

        self.assertIsNotNone(client.wait_for_text("Settings", timeout=0.5))
        self.assertTrue(client.wait_stable(timeout=0.5, interval=0.01, settle=2))

        pf_ok = client.preflight()
        self.assertTrue(pf_ok["safe"])
        self.assertEqual(pf_ok["platform"], "ios")

        # Banking screen must fail preflight on iOS as well
        bank_drv = _MockIosDriver(boxes=[
            {"text": "토스 송금 확인", "confidence": 0.99, "x": 200.0, "y": 400.0, "w": 120.0, "h": 24.0}
        ])
        bank_client = hanson_ios.HansonIosMirrorClient(driver=bank_drv, sleep_fn=lambda _: None)
        pf_bank = bank_client.preflight()
        self.assertFalse(pf_bank["safe"])
        self.assertIn("토스", pf_bank["denylist_hits"])

    def test_ios_client_honest_unsupported_operations(self):
        client = hanson_ios.HansonIosMirrorClient(driver=_MockIosDriver(), sleep_fn=lambda _: None)
        for fn in (
            client.ui,
            client.back,
            lambda: client.press("back"),
            client.current_app,
            client.list_apps,
            client.vdisplay_start,
        ):
            with self.assertRaises(hanson_ios.UnsupportedIosOperationError):
                fn()

    def test_platform_routing_and_shared_script_namespace(self):
        self.assertEqual(hanson_harness.resolve_platform("ios"), "ios")
        self.assertEqual(hanson_harness.resolve_platform("android"), "android")
        self.assertEqual(hanson_harness.resolve_platform(None, env={"HANSON_PLATFORM": "ios"}), "ios")
        self.assertEqual(
            hanson_harness.resolve_platform("auto", env={}, host_platform="win32", android_ready=False, ios_ready=False),
            "android",
        )
        self.assertEqual(
            hanson_harness.resolve_platform("auto", env={}, host_platform="darwin", android_ready=False, ios_ready=True),
            "ios",
        )
        self.assertEqual(
            hanson_harness.resolve_platform("auto", env={}, host_platform="darwin", android_ready=True, ios_ready=True),
            "android",
        )
        with self.assertRaises(ValueError):
            hanson_harness.resolve_platform("blackberry")

        safe_mode, plat, rest = hanson_harness._parse_cli_flags(["prog", "--safe", "--platform", "ios", "doctor"])
        self.assertTrue(safe_mode)
        self.assertEqual(plat, "ios")
        self.assertEqual(rest, ["doctor"])

        drv = _MockIosDriver()
        ios_client = hanson_harness.create_client(platform="ios", ios_driver=drv)
        ios_client._sleep = lambda _: None
        ns = hanson_harness.build_script_namespace(ios_client)
        script = """
assert platform == "ios"
assert preflight()["safe"]
home()
hit = tap_text("Settings")
assert hit["source"] == "pixels"
scroll("down", amount=0.4)
"""
        exec(compile(script, "<ios-flat-test>", "exec"), ns, ns)
        event_names = [e[0] for e in drv.events]
        self.assertIn("press", event_names)
        self.assertIn("tap", event_names)
        self.assertIn("scroll_wheel", event_names)


class TestUpstreamTrackerAndSwiftCompanion(unittest.TestCase):
    def test_ast_extractor_and_tracker_pipeline_with_mock_upstream(self):
        helpers_src = "def tap(x, y): pass\ndef screen(): pass\ndef scroll_collect(): pass\n"
        ios_src = "class IPhone:\n    def _input_tap(self): pass\n    def _screen_text(self): pass\n"
        android_src = "class Android:\n    def _input_tap(self): pass\n    def _tree(self): pass\n"
        transport_src = '"""\n    screen.text -> boxes\n    input.tap -> None\n    tree -> nodes\n"""\n'

        def b64(text: str) -> Dict[str, Any]:
            return {
                "content": base64.b64encode(text.encode("utf-8")).decode("ascii"),
                "size": len(text.encode("utf-8")),
                "sha": "abc123456789",
            }

        def fake_fetcher(endpoint: str) -> Any:
            if endpoint == f"repos/{tracker.UPSTREAM_REPO}":
                return {
                    "full_name": tracker.UPSTREAM_REPO,
                    "html_url": tracker.UPSTREAM_URL,
                    "default_branch": "main",
                    "stargazers_count": 5600,
                    "pushed_at": "2026-11-21T20:07:34Z",
                }
            if "/commits?" in endpoint:
                return [
                    {
                        "sha": "f5096606b10eb757d03467944bccd82f8aba385f",
                        "commit": {
                            "committer": {"date": "2026-11-21T20:06:56Z"},
                            "message": "PHO-335: wake a cloud iPhone\n\nDetails",
                        },
                    }
                ]
            if "/releases?" in endpoint:
                return [{"tag_name": "0.3.3", "name": "0.3.3", "published_at": "2026-11-02T07:26:42Z"}]
            if "/tags?" in endpoint:
                return [{"name": "0.3.3", "commit": {"sha": "9da3387"}}]
            if endpoint.endswith("/helpers.py"):
                return b64(helpers_src)
            if endpoint.endswith("/ios.py"):
                return b64(ios_src)
            if endpoint.endswith("/android.py"):
                return b64(android_src)
            if endpoint.endswith("/transport.py"):
                return b64(transport_src)
            return b64("# stub module\n")

        upstream = tracker.fetch_upstream_snapshot(fetcher=fake_fetcher)
        local = tracker.inspect_local_harness(HARNESS_DIR)
        self.assertTrue(local["has_platform_routing"])
        self.assertTrue(local["has_ios_driver"])

        report = tracker.compute_parity_report(upstream, local)
        self.assertGreaterEqual(report["summary"]["transport_parity_pct"], 80.0)
        cli_table = tracker.format_cli_table(report)
        self.assertIn("f509660", cli_table)
        self.assertIn("screen.text", cli_table)

        md = tracker.render_tracker_markdown(report)
        self.assertIn("# Upstream Phone Harness Tracker", md)
        self.assertIn("`f509660`", md)
        self.assertIn("Honest Unsupported", md)

        # Offline mode should succeed cleanly without touching network
        with tempfile.TemporaryDirectory() as tmp:
            out_doc = Path(tmp) / "tracker.md"
            rc = tracker.main(["--offline", "--write-doc", str(out_doc)])
            self.assertEqual(rc, 0)
            self.assertTrue(out_doc.is_file())
            self.assertIn("offline_baseline", out_doc.read_text(encoding="utf-8"))

    def test_live_generated_tracker_doc_is_present_and_sanitized(self):
        doc_path = REPO_ROOT / "docs" / "UPSTREAM_PHONE_HARNESS_TRACKER.md"
        self.assertTrue(doc_path.is_file(), "Expected docs/UPSTREAM_PHONE_HARNESS_TRACKER.md to be generated")
        text = doc_path.read_text(encoding="utf-8")
        self.assertRegex(text, r"`[0-9a-f]{7}`")
        self.assertIn("phone-harness/phone-harness", text)
        self.assertIn("Transport Op Parity Matrix", text)
        self.assertNotRegex(text, r"[A-Za-z]:[\\/]+Users[\\/]")
        self.assertNotIn("/Users/", text)

    def test_ios_swiftui_companion_structure_and_zero_saturation_palette(self):
        ios_root = REPO_ROOT / "ios" / "HansonMobileUseIOS"
        required_files = [
            REPO_ROOT / "ios" / "README.md",
            ios_root / "Package.swift",
            ios_root / "Sources" / "HansonMobileUseIOS" / "A2uiSwiftCore.swift",
            ios_root / "Sources" / "HansonMobileUseIOS" / "A2uiSwiftUIRenderer.swift",
            ios_root / "Sources" / "HansonMobileUseIOS" / "CoDrawingSwiftUICanvas.swift",
            ios_root / "Sources" / "HansonMobileUseIOS" / "HansonSseAndAppIntents.swift",
            ios_root / "Tests" / "HansonMobileUseIOSTests" / "HansonMobileUseIOSTests.swift",
        ]
        for path in required_files:
            self.assertTrue(path.is_file(), f"Missing required iOS file: {path.name}")

        core = (ios_root / "Sources" / "HansonMobileUseIOS" / "A2uiSwiftCore.swift").read_text(encoding="utf-8")
        for symbol in ("createSurface", "updateComponents", "updateDataModel", "deleteSurface", "parsePointerTokens", "A2uiUserAction"):
            self.assertIn(symbol, core)

        renderer = (ios_root / "Sources" / "HansonMobileUseIOS" / "A2uiSwiftUIRenderer.swift").read_text(encoding="utf-8")
        self.assertIn("0xFAFAFA", renderer)
        self.assertIn("0x09090B", renderer)
        # Strict 0%-saturation check on every hex color literal across Swift sources
        allowed_obsidian_hexes = {
            "FAFAFA", "09090B", "FFFFFF", "121214", "F4F4F5", "18181B",
            "E4E4E7", "27272A", "D4D4D8", "3F3F46", "A1A1AA", "52525B", "71717A",
        }
        for swift_file in (ios_root / "Sources" / "HansonMobileUseIOS").glob("*.swift"):
            src = swift_file.read_text(encoding="utf-8")
            for hex_match in re.findall(r"0x([0-9A-Fa-f]{6})\b", src):
                upper = hex_match.upper()
                self.assertIn(
                    upper, allowed_obsidian_hexes,
                    f"Color 0x{upper} is outside ObsidianColors monochrome tokens in {swift_file.name}",
                )
                r = int(upper[0:2], 16)
                g = int(upper[2:4], 16)
                b = int(upper[4:6], 16)
                self.assertLessEqual(max(r, g, b) - min(r, g, b), 10)

        codraw = (ios_root / "Sources" / "HansonMobileUseIOS" / "CoDrawingSwiftUICanvas.swift").read_text(encoding="utf-8")
        for symbol in ("NormalizedPoint", "spotlightRing", "undo()", "redo()", "CoDrawingSwiftUICanvas"):
            self.assertIn(symbol, codraw)

        sse = (ios_root / "Sources" / "HansonMobileUseIOS" / "HansonSseAndAppIntents.swift").read_text(encoding="utf-8")
        for symbol in ("HansonSseParser", "Last-Event-ID", "AppIntent", "HansonIosCapabilityMatrix", "sandboxForbidden"):
            self.assertIn(symbol, sse)


if __name__ == "__main__":
    unittest.main(verbosity=2)
