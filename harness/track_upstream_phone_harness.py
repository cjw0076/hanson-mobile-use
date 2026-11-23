#!/usr/bin/env python3
"""Continuous Upstream Tracker for `https://github.com/phone-harness/phone-harness`.

Fetches live upstream repository metadata, recent commits, releases/tags, and
AST-analyzed helper/transport APIs from `src/phone_harness/` (`helpers.py`,
`android.py`, `ios.py`, `mirror.py`, `background.py`, `ocr.py`, `transport.py`)
via `gh api` (preferred) or public GitHub REST API, with graceful offline/cached
fallback. Diffs upstream ops against Hanson's `harness/hanson_harness.py` and
`harness/hanson_ios.py`, prints a crisp parity table, and optionally updates
`docs/UPSTREAM_PHONE_HARNESS_TRACKER.md` with `--write-doc`.

Zero external dependencies (Python 3.9+ stdlib only). Never embeds personal tokens.
"""
from __future__ import annotations

import argparse
import ast
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Sequence, Set, Tuple

UPSTREAM_REPO = "phone-harness/phone-harness"
UPSTREAM_URL = f"https://github.com/{UPSTREAM_REPO}"
UPSTREAM_MODULES: Tuple[str, ...] = (
    "helpers.py",
    "android.py",
    "ios.py",
    "mirror.py",
    "background.py",
    "ocr.py",
    "transport.py",
)

HARNESS_DIR = Path(__file__).resolve().parent
REPO_ROOT = HARNESS_DIR.parent
DEFAULT_DOC_PATH = REPO_ROOT / "docs" / "UPSTREAM_PHONE_HARNESS_TRACKER.md"

# Mapping from upstream transport op name -> (Hanson Android method/symbol, Hanson iOS method/symbol, iOS honest note)
TRANSPORT_PARITY_MAP: List[Tuple[str, str, str, str]] = [
    ("screen.bounds", "bounds", "bounds", ""),
    ("screen.require", "bounds", "bounds", ""),
    ("screen.capture", "screenshot", "screenshot", ""),
    ("screen.text", "screen", "screen", "Apple Vision OCR (`source='pixels'`)"),
    ("screen.text_pixels", "", "screen", "Android uses native A11y tree; iOS always uses Vision OCR"),
    ("input.tap", "tap", "tap", ""),
    ("input.press", "long_press", "long_press", ""),
    ("input.drag", "drag", "drag", ""),
    ("input.scroll", "scroll_no_fling", "scroll_no_fling", "Zero-fling pixel wheel scroll"),
    ("input.keys", "press", "press", ""),
    ("input.text", "type_text", "type_text", "Korean/Unicode paste + keystroke mode"),
    ("nav.home", "home", "home", "`cmd+1` via iPhone Mirroring"),
    ("nav.back", "back", "UNSUPPORTED", "Honest unsupported: iOS has no system Back button"),
    ("nav.recents", "app_switcher", "app_switcher", "`cmd+2` via iPhone Mirroring"),
    ("apps.launch", "open_app", "open_app", "`cmd+3` Spotlight launch"),
    ("apps.current", "current_app", "UNSUPPORTED", "Honest unsupported: iOS Mirroring exposes no app inventory"),
    ("apps.list", "list_apps", "UNSUPPORTED", "Honest unsupported: iOS Mirroring exposes no app inventory"),
    ("session.state", "capabilities", "capabilities", "Host & Mirroring capability diagnostics"),
    ("session.require", "preflight", "preflight", "Financial/prompt-injection preflight gate"),
    ("device.wake", "_lock_gate", "", "Cloud iPhone wake (`PHO-335`) tracked in backlog; local devices require manual unlock"),
    ("focus.probe", "", "", "Backlog: focus-steal telemetry probe"),
    ("focus.diff", "", "", "Backlog: focus-steal diff"),
    ("url.open", "", "", "Backlog: direct URL handoff"),
    ("tree", "ui", "UNSUPPORTED", "Honest unsupported: iOS Mirroring is a video stream"),
    ("raw", "_adb", "UNSUPPORTED", "Honest unsupported: iOS substrate is Quartz, not a shell"),
]

# Offline baseline snapshot used ONLY when `--offline` or network/gh is unreachable
OFFLINE_FALLBACK_SNAPSHOT: Dict[str, Any] = {
    "source_mode": "offline_baseline",
    "repo": {
        "full_name": UPSTREAM_REPO,
        "html_url": UPSTREAM_URL,
        "default_branch": "main",
        "stargazers_count": 0,
        "pushed_at": "unknown",
    },
    "commits": [],
    "releases": [],
    "tags": [],
    "modules": {},
}


def _now_utc_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# =============================================================================
# Upstream Fetchers (`gh api` -> Public GitHub REST API -> Graceful Offline)
# =============================================================================

def _fetch_via_gh_api(endpoint: str, timeout: int = 20) -> Any:
    if shutil.which("gh") is None:
        raise RuntimeError("gh CLI not installed")
    proc = subprocess.run(
        ["gh", "api", endpoint],
        capture_output=True,
        text=True,
        timeout=timeout,
    )
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout).strip() or f"gh api {endpoint} failed")
    return json.loads(proc.stdout)


def _fetch_via_github_rest(endpoint: str, timeout: int = 20) -> Any:
    url = f"https://api.github.com/{endpoint.lstrip('/')}"
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "hanson-mobile-use-upstream-tracker",
    }
    env_token = os.environ.get("GITHUB_TOKEN", "").strip()
    if env_token:
        headers["Authorization"] = f"Bearer {env_token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="replace"))


def _make_api_fetcher() -> Tuple[str, Callable[[str], Any]]:
    try:
        _fetch_via_gh_api(f"repos/{UPSTREAM_REPO}", timeout=12)
        return "gh_api", _fetch_via_gh_api
    except Exception:
        pass
    try:
        _fetch_via_github_rest(f"repos/{UPSTREAM_REPO}", timeout=12)
        return "github_rest", _fetch_via_github_rest
    except Exception as exc:
        raise RuntimeError(f"Unable to reach GitHub via gh CLI or REST API: {exc}") from exc


def extract_python_module_symbols(source_text: str) -> Dict[str, Any]:
    """Extract public functions, classes, methods, and transport op handlers via AST."""
    lines = source_text.splitlines()
    try:
        tree = ast.parse(source_text)
    except SyntaxError as exc:
        return {
            "line_count": len(lines),
            "parse_error": str(exc),
            "public_functions": [],
            "classes": {},
            "ops": [],
        }

    public_funcs: List[str] = []
    classes: Dict[str, List[str]] = {}
    ops: Set[str] = set()

    for node in tree.body:
        if isinstance(node, ast.FunctionDef):
            if not node.name.startswith("_"):
                public_funcs.append(node.name)
        elif isinstance(node, ast.ClassDef):
            methods = [n.name for n in node.body if isinstance(n, ast.FunctionDef)]
            # Also detect class-level method aliases (e.g. `_screen_text_pixels = _screen_text`)
            aliases: List[str] = []
            for stmt in node.body:
                if isinstance(stmt, ast.Assign):
                    for target in stmt.targets:
                        if isinstance(target, ast.Name):
                            aliases.append(target.id)
            classes[node.name] = methods
            for m in methods + aliases:
                # Upstream Backend subclasses define _<group>_<op> or _tree / _raw
                if m in ("_tree", "_raw"):
                    ops.add(m[1:])
                elif m.startswith("_") and not m.startswith("__"):
                    parts = m[1:].split("_", 1)
                    if len(parts) == 2 and parts[0] in ("screen", "input", "nav", "apps", "device", "session", "focus", "url"):
                        ops.add(f"{parts[0]}.{parts[1]}")

    # Also detect dotted op names documented in transport.py docstring
    doc = ast.get_docstring(tree) or ""
    for match in re.findall(r"\b((?:screen|input|nav|apps|device|session|focus|url)\.[a-z_]+)\b", doc):
        ops.add(match)
    if re.search(r"^\s+tree\s+->", doc, re.M):
        ops.add("tree")
    if re.search(r"^\s+raw\s+", doc, re.M):
        ops.add("raw")

    return {
        "line_count": len(lines),
        "public_functions": sorted(set(public_funcs)),
        "classes": classes,
        "ops": sorted(ops),
    }


def fetch_upstream_snapshot(
    repo: str = UPSTREAM_REPO,
    offline: bool = False,
    snapshot_file: Optional[Path] = None,
    commit_limit: int = 10,
    fetcher: Optional[Callable[[str], Any]] = None,
) -> Dict[str, Any]:
    """Fetch live upstream snapshot (or load cached/offline fallback cleanly)."""
    if offline:
        if snapshot_file and snapshot_file.is_file():
            loaded = json.loads(snapshot_file.read_text(encoding="utf-8"))
            loaded["source_mode"] = "offline_snapshot_file"
            return loaded
        fallback = json.loads(json.dumps(OFFLINE_FALLBACK_SNAPSHOT))
        fallback["fetched_at"] = _now_utc_iso()
        return fallback

    source_mode = "custom_fetcher"
    api_fn = fetcher
    if api_fn is None:
        try:
            source_mode, api_fn = _make_api_fetcher()
        except Exception as exc:
            if snapshot_file and snapshot_file.is_file():
                loaded = json.loads(snapshot_file.read_text(encoding="utf-8"))
                loaded["source_mode"] = f"offline_fallback ({exc})"
                return loaded
            fallback = json.loads(json.dumps(OFFLINE_FALLBACK_SNAPSHOT))
            fallback["source_mode"] = f"offline_fallback ({exc})"
            fallback["fetched_at"] = _now_utc_iso()
            return fallback

    repo_info = api_fn(f"repos/{repo}")
    raw_commits = api_fn(f"repos/{repo}/commits?per_page={commit_limit}") or []
    raw_releases = api_fn(f"repos/{repo}/releases?per_page=5") or []
    raw_tags = api_fn(f"repos/{repo}/tags?per_page=10") or []

    commits = [
        {
            "sha": c["sha"],
            "short_sha": c["sha"][:7],
            "date": c["commit"]["committer"]["date"],
            "message": c["commit"]["message"].splitlines()[0].strip(),
        }
        for c in raw_commits
    ]
    releases = [
        {
            "tag": rel.get("tag_name", ""),
            "name": rel.get("name", ""),
            "published_at": rel.get("published_at", ""),
            "prerelease": bool(rel.get("prerelease", False)),
        }
        for rel in raw_releases
    ]
    tags = [
        {
            "name": t.get("name", ""),
            "short_sha": (t.get("commit") or {}).get("sha", "")[:7],
        }
        for t in raw_tags
    ]

    modules: Dict[str, Any] = {}
    for mod in UPSTREAM_MODULES:
        try:
            content_obj = api_fn(f"repos/{repo}/contents/src/phone_harness/{mod}")
            decoded = base64.b64decode(content_obj["content"]).decode("utf-8", errors="replace")
            info = extract_python_module_symbols(decoded)
            info["size_bytes"] = int(content_obj.get("size", len(decoded.encode("utf-8"))))
            info["sha"] = str(content_obj.get("sha", ""))[:12]
            modules[mod] = info
        except Exception as exc:
            modules[mod] = {"error": str(exc), "line_count": 0, "public_functions": [], "classes": {}, "ops": []}

    return {
        "source_mode": source_mode,
        "fetched_at": _now_utc_iso(),
        "repo": {
            "full_name": repo_info.get("full_name", repo),
            "html_url": repo_info.get("html_url", f"https://github.com/{repo}"),
            "default_branch": repo_info.get("default_branch", "main"),
            "stargazers_count": int(repo_info.get("stargazers_count", 0)),
            "pushed_at": repo_info.get("pushed_at", ""),
        },
        "commits": commits,
        "releases": releases,
        "tags": tags,
        "modules": modules,
    }


# =============================================================================
# Local Hanson Harness AST Inspection
# =============================================================================

def _extract_namespace_keys(source_text: str) -> Set[str]:
    """Extract exported flat script namespace keys from `ns = {...}` in `build_script_namespace`."""
    tree = ast.parse(source_text)
    keys: Set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef) and node.name == "build_script_namespace":
            for stmt in node.body:
                val = None
                if isinstance(stmt, ast.AnnAssign) and isinstance(stmt.target, ast.Name) and stmt.target.id == "ns":
                    val = stmt.value
                elif isinstance(stmt, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "ns" for t in stmt.targets):
                    val = stmt.value
                if isinstance(val, ast.Dict):
                    for k in val.keys:
                        if isinstance(k, ast.Constant) and isinstance(k.value, str):
                            keys.add(k.value)
    return keys


def inspect_local_harness(harness_dir: Path = HARNESS_DIR) -> Dict[str, Any]:
    """Statically inspect Hanson's local harness modules (`hanson_harness.py`, `hanson_ios.py`, etc.)."""
    android_file = harness_dir / "hanson_harness.py"
    ios_file = harness_dir / "hanson_ios.py"
    guard_file = harness_dir / "hanson_script_guard.py"

    android_src = android_file.read_text(encoding="utf-8") if android_file.is_file() else ""
    ios_src = ios_file.read_text(encoding="utf-8") if ios_file.is_file() else ""
    guard_src = guard_file.read_text(encoding="utf-8") if guard_file.is_file() else ""

    android_ast = extract_python_module_symbols(android_src) if android_src else {"classes": {}, "public_functions": []}
    ios_ast = extract_python_module_symbols(ios_src) if ios_src else {"classes": {}, "public_functions": []}
    guard_ast = extract_python_module_symbols(guard_src) if guard_src else {"classes": {}, "public_functions": []}

    android_methods = sorted(set(android_ast.get("classes", {}).get("HansonPhoneClient", [])))
    ios_methods = sorted(set(ios_ast.get("classes", {}).get("HansonIosMirrorClient", [])))
    flat_helpers = sorted(_extract_namespace_keys(android_src)) if android_src else []

    return {
        "android_client_methods": android_methods,
        "ios_client_methods": ios_methods,
        "flat_helpers": flat_helpers,
        "guard_functions": guard_ast.get("public_functions", []),
        "has_platform_routing": "resolve_platform" in android_ast.get("public_functions", []),
        "has_ios_driver": bool(ios_methods),
    }


# =============================================================================
# Parity Diff Engine
# =============================================================================

def _annotate_commit_relevance(message: str) -> str:
    low = message.lower()
    if "vnimagerequesthandler" in low or "ocr" in low:
        return "Adopted in `hanson_ios.py` (`options=None` passed to `VNImageRequestHandler`)"
    if "wake" in low or "pho-335" in low:
        return "Tracked in backlog (cloud iPhone wake); local devices enforce manual unlock"
    if "reserve" in low or "cloud" in low:
        return "Upstream cloud-hosted device rental CLI (out of scope for local-first Hanson)"
    if "approval" in low or "openclaw" in low or "classifier" in low:
        return "Aligned with fail-closed `hanson_script_guard.py` AST guard"
    if "release" in low or "version" in low:
        return "Upstream release milestone"
    return "Monitored via automated parity tracker"


def compute_parity_report(upstream: Dict[str, Any], local: Dict[str, Any]) -> Dict[str, Any]:
    """Compute transport op & helper parity between upstream `phone-harness` and Hanson."""
    modules = upstream.get("modules") or {}
    up_android_ops = set((modules.get("android.py") or {}).get("ops") or [])
    up_ios_ops = set((modules.get("ios.py") or {}).get("ops") or [])
    up_helpers = sorted(set((modules.get("helpers.py") or {}).get("public_functions") or []))

    android_methods = set(local.get("android_client_methods") or [])
    ios_methods = set(local.get("ios_client_methods") or [])
    local_helpers = set(local.get("flat_helpers") or [])

    transport_rows: List[Dict[str, Any]] = []
    implemented_ops = 0
    backlog_ops = 0
    for op, and_sym, ios_sym, note in TRANSPORT_PARITY_MAP:
        has_android = bool(and_sym and and_sym in android_methods)
        if ios_sym == "UNSUPPORTED":
            ios_status = "Honest Unsupported"
        elif ios_sym and ios_sym in ios_methods:
            ios_status = f"Implemented (`{ios_sym}`)"
        else:
            ios_status = "Backlog"

        and_status = f"Implemented (`{and_sym}`)" if has_android else ("N/A (iOS OCR)" if op == "screen.text_pixels" else "Backlog")

        if has_android or ios_status.startswith("Implemented"):
            overall = "PARITY"
            implemented_ops += 1
        else:
            overall = "BACKLOG"
            backlog_ops += 1

        transport_rows.append({
            "op": op,
            "upstream_android": "Yes" if (op in up_android_ops or not up_android_ops) else "No",
            "upstream_ios": "Yes" if op in up_ios_ops else ("Unsupported" if up_ios_ops else "Check live"),
            "hanson_android": and_status,
            "hanson_ios": ios_status,
            "status": overall,
            "note": note,
        })

    # Helper parity diff against upstream helpers.py
    helper_rows: List[Dict[str, Any]] = []
    helper_implemented = 0
    helper_backlog = 0
    for fn in up_helpers:
        # Map upstream aliases to Hanson equivalents where naming is explicit
        mapped_name = fn
        if fn in local_helpers:
            status = "PARITY"
            helper_implemented += 1
        elif fn in ("find_window", "ensure_device"):
            mapped_name = "bounds"
            status = "COVERED_VIA_CLIENT"
            helper_implemented += 1
        elif fn == "tap_node":
            mapped_name = "tap_ui"
            status = "COVERED_VIA_HELPER"
            helper_implemented += 1
        else:
            mapped_name = ""
            status = "BACKLOG"
            helper_backlog += 1
        helper_rows.append({
            "upstream_helper": fn,
            "hanson_symbol": mapped_name,
            "status": status,
        })

    total_ops = len(transport_rows)
    op_parity_pct = round((implemented_ops / total_ops) * 100.0, 1) if total_ops else 0.0
    total_helpers = len(helper_rows)
    helper_parity_pct = round((helper_implemented / total_helpers) * 100.0, 1) if total_helpers else 0.0

    hanson_exclusives = [
        ("Hanson Native `:18888` Artemis Engine", "<50ms Accessibility tree + full Korean/Unicode `ACTION_SET_TEXT` without ADB keyboard limits"),
        ("Background `VirtualDisplay` Execution", "`vdisplay_start/snapshot/stop` off-screen Android execution without stealing the user's screen"),
        ("A2UI v0.9/v1.0 Streaming UI (Android + iOS)", "Official A2UI JSONL/SSE surface renderer in both Jetpack Compose & SwiftUI (`ios/HansonMobileUseIOS/`)"),
        ("Human + AI Normalized Co-Drawing", "Cross-app Android overlay + native SwiftUI `Canvas` with `0..1` spotlight rings, arrows, and undo/redo"),
        ("Korean + Global Financial Preflight & Injection Guard", "`hanson_script_guard.py` blocks banking/wallet screens and wraps untrusted screen text"),
        ("Pure Achromatic Trace Viewer", "`hanson_trace_viewer.py` renders XSS-safe `#FAFAFA/#09090B` HTML run journals"),
    ]

    return {
        "generated_at": _now_utc_iso(),
        "upstream": upstream,
        "local": local,
        "summary": {
            "transport_ops_total": total_ops,
            "transport_ops_parity": implemented_ops,
            "transport_ops_backlog": backlog_ops,
            "transport_parity_pct": op_parity_pct,
            "upstream_helpers_total": total_helpers,
            "helpers_parity": helper_implemented,
            "helpers_backlog": helper_backlog,
            "helpers_parity_pct": helper_parity_pct,
            "hanson_flat_helpers_count": len(local_helpers),
        },
        "transport_rows": transport_rows,
        "helper_rows": helper_rows,
        "hanson_exclusives": hanson_exclusives,
    }


# =============================================================================
# CLI Table & Markdown Doc Renderers
# =============================================================================

def format_cli_table(report: Dict[str, Any]) -> str:
    up = report["upstream"]
    repo = up.get("repo") or {}
    commits = up.get("commits") or []
    releases = up.get("releases") or []
    summary = report["summary"]

    head_sha = commits[0]["short_sha"] if commits else "offline"
    head_date = commits[0]["date"] if commits else "unknown"
    latest_rel = releases[0]["tag"] if releases else "unknown"

    lines = [
        "======================================================================",
        f"Hanson Upstream Tracker :: {repo.get('full_name', UPSTREAM_REPO)}",
        f"Source: {up.get('source_mode')} | Stars: {repo.get('stargazers_count', 0)} | Release: {latest_rel} | HEAD: {head_sha} ({head_date})",
        f"Transport Parity: {summary['transport_ops_parity']}/{summary['transport_ops_total']} ({summary['transport_parity_pct']}%) | "
        f"Helper Parity: {summary['helpers_parity']}/{summary['upstream_helpers_total']} ({summary['helpers_parity_pct']}%)",
        "======================================================================",
        f"{'OP':<20} | {'HANSON ANDROID':<26} | {'HANSON IOS':<28} | {'STATUS'}",
        "-" * 88,
    ]
    for row in report["transport_rows"]:
        lines.append(
            f"{row['op']:<20} | {row['hanson_android']:<26} | {row['hanson_ios']:<28} | {row['status']}"
        )
    if commits:
        lines.append("-" * 88)
        lines.append("Latest Upstream Commits:")
        for c in commits[:5]:
            lines.append(f"  {c['short_sha']}  {c['date']}  {c['message'][:72]}")
    return "\n".join(lines)


def render_tracker_markdown(report: Dict[str, Any]) -> str:
    up = report["upstream"]
    repo = up.get("repo") or {}
    commits = up.get("commits") or []
    releases = up.get("releases") or []
    tags = up.get("tags") or []
    modules = up.get("modules") or {}
    summary = report["summary"]

    head_commit = commits[0] if commits else {"short_sha": "offline", "sha": "offline", "date": "unknown", "message": "Offline mode"}
    latest_rel = releases[0] if releases else {"tag": "n/a", "published_at": "n/a", "name": "n/a"}

    md: List[str] = [
        "# Upstream Phone Harness Tracker (`phone-harness/phone-harness`)",
        "",
        "Continuous parity ledger comparing Hanson Mobile-Use (`harness/hanson_harness.py`, `harness/hanson_ios.py`, `harness/hanson_script_guard.py`) against upstream [`phone-harness/phone-harness`](https://github.com/phone-harness/phone-harness).",
        "",
        "Re-generate at any time (zero pip dependencies):",
        "",
        "```bash",
        "python -B harness/track_upstream_phone_harness.py --write-doc",
        "```",
        "",
        "---",
        "",
        "## 1. Live Upstream Snapshot",
        "",
        "| Field | Value |",
        "| :--- | :--- |",
        f"| Upstream Repository | [`{repo.get('full_name', UPSTREAM_REPO)}`]({repo.get('html_url', UPSTREAM_URL)}) |",
        f"| Default Branch | `{repo.get('default_branch', 'main')}` |",
        f"| GitHub Stars | `{repo.get('stargazers_count', 0):,}` |",
        f"| Latest Commit (`HEAD`) | `{head_commit['short_sha']}` (`{head_commit['sha']}`) |",
        f"| Latest Commit Date (UTC) | `{head_commit['date']}` |",
        f"| Latest Release | `{latest_rel['tag']}` ({latest_rel['published_at']}) |",
        f"| Tracker Run Mode | `{up.get('source_mode', 'unknown')}` |",
        f"| Synced At (UTC) | `{report['generated_at']}` |",
        f"| Transport Op Parity | **{summary['transport_ops_parity']} / {summary['transport_ops_total']} ({summary['transport_parity_pct']}%)** |",
        f"| Helper Function Parity | **{summary['helpers_parity']} / {summary['upstream_helpers_total']} ({summary['helpers_parity_pct']}%)** |",
        "",
        "---",
        "",
        "## 2. Recent Upstream Commits",
        "",
        "| SHA | Date (UTC) | Upstream Commit | Hanson Alignment |",
        "| :--- | :--- | :--- | :--- |",
    ]
    if commits:
        for c in commits:
            msg = c["message"].replace("|", "\\|")
            note = _annotate_commit_relevance(c["message"])
            md.append(f"| `{c['short_sha']}` | `{c['date']}` | {msg} | {note} |")
    else:
        md.append("| `offline` | — | No live commits fetched (offline mode) | Run without `--offline` to refresh |")

    md.extend([
        "",
        "### Latest Releases & Tags",
        "",
        "| Tag | Published At (UTC) | Release Title |",
        "| :--- | :--- | :--- |",
    ])
    if releases:
        for rel in releases:
            title = (rel.get("name") or rel.get("tag") or "").replace("|", "\\|")
            md.append(f"| `{rel['tag']}` | `{rel['published_at']}` | {title} |")
    elif tags:
        for t in tags[:5]:
            md.append(f"| `{t['name']}` | `tag ({t['short_sha']})` | Tag snapshot |")
    else:
        md.append("| `offline` | — | No release data fetched |")

    md.extend([
        "",
        "---",
        "",
        "## 3. Tracked Upstream Modules (`src/phone_harness/`)",
        "",
        "| Module | Lines | Size (Bytes) | AST Public Functions / Ops |",
        "| :--- | ---: | ---: | :--- |",
    ])
    for mod in UPSTREAM_MODULES:
        info = modules.get(mod) or {}
        if info.get("error"):
            md.append(f"| `{mod}` | — | — | Fetch error: `{info['error']}` |")
            continue
        fn_count = len(info.get("public_functions") or [])
        op_count = len(info.get("ops") or [])
        cls_names = ", ".join(sorted((info.get("classes") or {}).keys())) or "module-level"
        md.append(
            f"| `{mod}` | {info.get('line_count', 0)} | {info.get('size_bytes', 0):,} | "
            f"{fn_count} public fns · {op_count} ops · classes: `{cls_names}` |"
        )

    md.extend([
        "",
        "---",
        "",
        "## 4. Transport Op Parity Matrix (Android & iOS)",
        "",
        "| Upstream Op | Upstream Android | Upstream iOS | Hanson Android (`hanson_harness.py`) | Hanson iOS (`hanson_ios.py`) | Status | Notes |",
        "| :--- | :---: | :---: | :--- | :--- | :---: | :--- |",
    ])
    for row in report["transport_rows"]:
        md.append(
            f"| `{row['op']}` | {row['upstream_android']} | {row['upstream_ios']} | "
            f"{row['hanson_android']} | {row['hanson_ios']} | **{row['status']}** | {row['note']} |"
        )

    md.extend([
        "",
        "---",
        "",
        "## 5. Flat Helper Function Parity (`helpers.py` vs Hanson Namespace)",
        "",
        f"Hanson exposes **{summary['hanson_flat_helpers_count']}** flat script helpers in `build_script_namespace()` across both `--platform android` and `--platform ios`.",
        "",
        "| Upstream Helper (`helpers.py`) | Hanson Equivalent | Status |",
        "| :--- | :--- | :---: |",
    ])
    if report["helper_rows"]:
        for hrow in report["helper_rows"]:
            eq = f"`{hrow['hanson_symbol']}()`" if hrow["hanson_symbol"] else "—"
            md.append(f"| `{hrow['upstream_helper']}()` | {eq} | **{hrow['status']}** |")
    else:
        md.append("| *(offline mode)* | — | Run online to diff live `helpers.py` AST |")

    md.extend([
        "",
        "---",
        "",
        "## 6. Hanson-Exclusive Capabilities Beyond Upstream",
        "",
        "| Capability | Architectural Advantage |",
        "| :--- | :--- |",
    ])
    for title, desc in report["hanson_exclusives"]:
        md.append(f"| **{title}** | {desc} |")

    md.extend([
        "",
        "---",
        "",
        "## 7. Prioritized Upstream Watchlist & Backlog",
        "",
        "1. **`open_url(url)` / `push_file(path)`**: Add sanitized HTTPS-only URL handoff (`am start -a android.intent.action.VIEW` on Android; Safari/Shortcuts bridge on iOS).",
        "2. **`focus_probe()` / `interruption()`**: Add macOS frontmost-window delta verification to `hanson_ios.py` background SkyLight mode.",
        "3. **`ocr_pixels()` fallback on Android**: Allow optional VLM/OCR fallback on canvas/game views where Android Accessibility tree returns sparse nodes.",
        "4. **Cloud Device Wake (`PHO-335`)**: Explicitly kept out of local device paths (Hanson never enters user PINs/passcodes on personal phones).",
        "",
    ])
    return "\n".join(md)


# =============================================================================
# CLI Entrypoint
# =============================================================================

def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Track upstream phone-harness/phone-harness commits, ops, and parity against Hanson Mobile-Use."
    )
    parser.add_argument(
        "--write-doc",
        nargs="?",
        const=str(DEFAULT_DOC_PATH),
        default=None,
        help="Write/update Markdown tracker report (default: docs/UPSTREAM_PHONE_HARNESS_TRACKER.md).",
    )
    parser.add_argument("--json", action="store_true", help="Output full parity report as JSON.")
    parser.add_argument("--offline", action="store_true", help="Skip live GitHub network queries and use cached/offline mode.")
    parser.add_argument("--snapshot-file", type=str, default=None, help="Load upstream snapshot JSON from file when offline or fallback triggers.")
    parser.add_argument("--save-snapshot", type=str, default=None, help="Save fetched upstream snapshot JSON to path.")
    parser.add_argument("--commit-limit", type=int, default=10, help="Number of recent upstream commits to fetch (default: 10).")
    args = parser.parse_args(argv)

    snap_path = Path(args.snapshot_file) if args.snapshot_file else None
    upstream = fetch_upstream_snapshot(
        repo=UPSTREAM_REPO,
        offline=args.offline,
        snapshot_file=snap_path,
        commit_limit=max(1, min(args.commit_limit, 30)),
    )
    if args.save_snapshot:
        out_snap = Path(args.save_snapshot)
        out_snap.parent.mkdir(parents=True, exist_ok=True)
        out_snap.write_text(json.dumps(upstream, indent=2, ensure_ascii=False), encoding="utf-8")

    local = inspect_local_harness(HARNESS_DIR)
    report = compute_parity_report(upstream, local)

    if args.write_doc:
        doc_path = Path(args.write_doc)
        doc_path.parent.mkdir(parents=True, exist_ok=True)
        doc_path.write_text(render_tracker_markdown(report), encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(format_cli_table(report))
        if args.write_doc:
            print(f"\nUpdated tracker doc: {args.write_doc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
