#!/usr/bin/env python3
"""
Hanson Mobile Agent Studio v4.0 — Desktop Coding-CLI & Workspace Bridge (:18889)
================================================================================
Lets the on-phone Hanson Mobile-Use Android app delegate multi-step GUI reasoning
to your laptop's existing coding CLI subscription (`claude`, `gemini`, `codex`)
or local/OpenAI-compatible endpoint over USB (`adb reverse tcp:18889 tcp:18889`)
with $0 extra API cost, AND exposes a path-sandboxed `/workspace/*` bridge for
remote file inspection, LCS unified diff editing, search, and safe diagnostics.

Supported Backends (`--backend auto|claude|gemini|codex|api|heuristic`):
1. `claude`    : Claude Code CLI (`claude -p --output-format text`)
2. `gemini`    : Gemini CLI (`gemini -p`)
3. `codex`     : OpenAI Codex CLI (`codex exec --skip-git-repo-check --ephemeral`)
                 (Automatically attaches the phone's Set-of-Marks JPEG via `-i` when present!)
4. `api`       : OpenAI-compatible (`OPENAI_BASE_URL` / Ollama `:11434` / `OPENAI_API_KEY`)
                 or `GEMINI_API_KEY` / `ANTHROPIC_API_KEY`
5. `heuristic` : Sub-10ms offline deterministic trajectory + self-healing state machine

Quickstart:
  adb reverse tcp:18889 tcp:18889
  python laptop-cli-bridge.py --backend auto --workspace-root ./agent_workspace
"""

from __future__ import annotations

import argparse
import base64
import difflib
import hmac
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path, PurePosixPath, PureWindowsPath
from typing import Any, Dict, List, Optional, Tuple

SERVER_VERSION = "6.0.0"
HOST = "127.0.0.1"
DEFAULT_PORT = 18889
DEFAULT_WORKSPACE_DIR = "./agent_workspace"
PHONE_BRIDGE_URL = os.environ.get("ARTEMIS_URL", "http://127.0.0.1:18888").rstrip("/")
ARTEMIS_TOKEN = os.environ.get("ARTEMIS_TOKEN", "").strip()
BRIDGE_AUTH_TOKEN = os.environ.get("HANSON_BRIDGE_TOKEN", "").strip()
LOOPBACK_HOST_RE = re.compile(r"^(?:127\.0\.0\.1|localhost|\[::1\])(?::\d{1,5})?$", re.IGNORECASE)
FORBIDDEN_BROWSER_OR_PROXY_HEADERS = (
    "origin",
    "referer",
    "sec-fetch-site",
    "sec-fetch-mode",
    "sec-fetch-dest",
    "x-forwarded-for",
    "x-forwarded-host",
    "x-real-ip",
    "cf-connecting-ip",
    "forwarded",
    "ngrok-trace-id",
    "tailscale-user-login",
)

# All 45 registered on-device tools (42 ToolSpecRegistry + set_timer + execute_lifestyle_routine + observe_screen)
VALID_TOOLS = {
    # 1. Google Artemis GUI Operator Tools (11)
    "click",
    "long_press",
    "input_text",
    "swipe",
    "drag_and_drop",
    "haptic_trajectory",
    "click_sequence",
    "press_key",
    "manage_app",
    "wait_for_delay",
    "open_link",
    # 2. Screen-Off Notification & Quick-Reply Tools (2)
    "list_notifications",
    "reply_notification",
    # 3. OS Fast-Lane, Everyday & Lifestyle Tools (11)
    "control_device_setting",
    "make_call",
    "send_sms",
    "search_contacts",
    "manage_calendar",
    "set_alarm",
    "set_timer",
    "execute_lifestyle_routine",
    "save_note",
    "read_note",
    "observe_screen",
    # 4. Native OS CLI, UARP, Browser-Use, Multimodal & Cross-Device Tools (8)
    "os_cli_exec",
    "reverse_engineer_app",
    "learn_app_skill",
    "render_generative_ui",
    "browser_use",
    "cross_device_control",
    "draw_with_code",
    "generate_visual_media",
    # 5. Task Lifecycle & Human-in-the-Loop Control (2)
    "ask_user",
    "complete_task",
    # 6. Local Sandbox Workspace & CLI Bridge Tools (6)
    "workspace_list",
    "workspace_read",
    "workspace_write",
    "workspace_edit",
    "workspace_search",
    "workspace_exec",
    # 7. Blind Vault, Watchers, A2A & Scratchpad Tools (5)
    "vault_blind_inject",
    "vault_list_handles",
    "watcher_manage",
    "a2a_negotiate_schedule",
    "scratchpad_note",
}

TOOL_NAME_ALIASES: Dict[str, str] = {
    "open_url": "open_link",
    "wait": "wait_for_delay",
}

DEVICE_SETTING_ALIASES: Dict[str, str] = {
    "wifi": "wifi_panel",
    "bluetooth": "bluetooth_panel",
}


class BackendUnavailableError(RuntimeError):
    """Raised when no configured CLI or env API reasoning backend is available or succeeds."""

APP_MAP = {
    "배달의민족": "com.sampleapp",
    "배민": "com.sampleapp",
    "카카오톡": "com.kakao.talk",
    "카톡": "com.kakao.talk",
    "유튜브": "com.google.android.youtube",
    "설정": "com.android.settings",
    "크롬": "com.android.chrome",
    "네이버": "com.nhn.android.search",
    "갤러리": "com.sec.android.gallery3d",
    "카메라": "com.sec.android.app.camera",
    "시계": "com.sec.android.app.clockpackage",
    "토스": "viva.republica.toss",
    "쿠팡": "com.coupang.mobile",
    "인스타그램": "com.instagram.android",
}

SYNCED_CLIPBOARD_QUEUE: List[Dict[str, Any]] = []
CONFIGURED_BACKEND = os.environ.get("HANSON_BRIDGE_BACKEND", "auto").strip().lower()
CONFIGURED_MODEL = os.environ.get("HANSON_BRIDGE_MODEL", "").strip()
WORKSPACE_IGNORE_DIRS = {".git", "__pycache__", ".checkpoints"}
WORKSPACE_ROOT: Path = Path(os.environ.get("HANSON_WORKSPACE_ROOT", DEFAULT_WORKSPACE_DIR)).resolve()
ALLOW_WORKSPACE_EXEC: bool = False
SHELL_CHAINING_RE = re.compile(r"(&&|\|\||[;&|><`$]|\r|\n)")
MAX_HTTP_BODY_BYTES = 10 * 1024 * 1024


class WorkspaceSecurityError(PermissionError):
    """Raised when a workspace path or command violates sandbox containment."""


class WorkspaceValidationError(ValueError):
    """Raised when a workspace payload is missing required fields or malformed."""


def configure_workspace_root(raw_path: Optional[str], create: bool = True) -> Path:
    global WORKSPACE_ROOT
    chosen = (raw_path or DEFAULT_WORKSPACE_DIR).strip() or DEFAULT_WORKSPACE_DIR
    candidate = Path(chosen)
    if create:
        candidate.mkdir(parents=True, exist_ok=True)
    resolved = candidate.resolve()
    WORKSPACE_ROOT = resolved
    return resolved


def get_safe_workspace_label() -> str:
    """Returns a non-PII workspace identifier suitable for /health telemetry."""
    name = WORKSPACE_ROOT.name
    return name if name else "agent_workspace"


def _is_subpath(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def _resolve_workspace_path(rel_path: str, allow_root: bool = False) -> Path:
    """
    Strictly resolves `rel_path` inside canonical `WORKSPACE_ROOT`.
    Rejects empty paths, absolute paths, drive-qualified paths, home shortcuts,
    parent traversal (`..`), symlink escapes, and prefix collisions.
    """
    if not isinstance(rel_path, str):
        raise WorkspaceSecurityError("Workspace path must be a string.")
    if "\x00" in rel_path:
        raise WorkspaceSecurityError("Null byte in path is forbidden.")

    raw = rel_path.strip()
    if not raw:
        if allow_root:
            return WORKSPACE_ROOT.resolve()
        raise WorkspaceSecurityError("Workspace path must not be empty.")
    if raw.startswith("~"):
        raise WorkspaceSecurityError("Home-relative paths are forbidden inside workspace sandbox.")
    if raw.startswith("/") or raw.startswith("\\"):
        raise WorkspaceSecurityError("Absolute paths are forbidden inside workspace sandbox.")
    if re.match(r"^[A-Za-z]:", raw):
        raise WorkspaceSecurityError("Drive-qualified paths are forbidden inside workspace sandbox.")
    if PurePosixPath(raw).is_absolute() or PureWindowsPath(raw).is_absolute():
        raise WorkspaceSecurityError("Absolute paths are forbidden inside workspace sandbox.")

    normalized_slashes = raw.replace("\\", "/")
    parts = [part for part in normalized_slashes.split("/") if part not in ("", ".")]
    if any(part == ".." for part in parts):
        raise WorkspaceSecurityError("Parent directory traversal ('..') is forbidden.")

    root_canonical = WORKSPACE_ROOT.resolve()
    if not parts:
        if allow_root:
            return root_canonical
        raise WorkspaceSecurityError("Workspace path must reference a file or subdirectory.")

    probe = root_canonical
    for part in parts:
        probe = probe / part
        if probe.exists() or probe.is_symlink():
            if not _is_subpath(probe.resolve(), root_canonical):
                raise WorkspaceSecurityError("Symlink escape outside workspace root is forbidden.")

    resolved = root_canonical.joinpath(*parts).resolve()
    if not _is_subpath(resolved, root_canonical):
        raise WorkspaceSecurityError("Resolved path escapes workspace root.")
    if not allow_root and resolved == root_canonical:
        raise WorkspaceSecurityError("Path must not resolve to workspace root itself.")
    return resolved


def compute_unified_diff(rel_posix: str, old_text: str, new_text: str) -> Dict[str, Any]:
    old_lines = old_text.splitlines() if old_text else []
    new_lines = new_text.splitlines() if new_text else []
    diff_lines = list(
        difflib.unified_diff(
            old_lines,
            new_lines,
            fromfile=f"a/{rel_posix}",
            tofile=f"b/{rel_posix}",
            lineterm="",
            n=3,
        )
    )
    added = sum(1 for ln in diff_lines if ln.startswith("+") and not ln.startswith("+++"))
    removed = sum(1 for ln in diff_lines if ln.startswith("-") and not ln.startswith("---"))
    return {
        "unified_diff": "\n".join(diff_lines),
        "added_lines": added,
        "removed_lines": removed,
    }


def _iter_workspace_files(base_dir: Path, root_canonical: Path) -> List[Path]:
    collected: List[Path] = []
    if base_dir.is_file():
        resolved = base_dir.resolve()
        if _is_subpath(resolved, root_canonical):
            collected.append(resolved)
        return collected
    if not base_dir.exists() or not base_dir.is_dir():
        return collected
    for dirpath, dirnames, filenames in os.walk(base_dir):
        dirnames[:] = sorted(d for d in dirnames if d not in WORKSPACE_IGNORE_DIRS)
        for fname in sorted(filenames):
            candidate = Path(dirpath) / fname
            try:
                resolved = candidate.resolve()
            except OSError:
                continue
            if not _is_subpath(resolved, root_canonical) or not resolved.is_file():
                continue
            collected.append(resolved)
    collected.sort(key=lambda p: p.relative_to(root_canonical).as_posix())
    return collected


def _is_binary_bytes(raw: bytes) -> bool:
    return b"\x00" in raw[:8192]


def workspace_list(sub_dir: str = "") -> Dict[str, Any]:
    root_canonical = WORKSPACE_ROOT.resolve()
    base_dir = _resolve_workspace_path(sub_dir, allow_root=True) if sub_dir.strip() else root_canonical
    if not base_dir.exists():
        raise FileNotFoundError(f"Workspace path not found: {sub_dir or '.'}")
    if not base_dir.is_dir():
        raise WorkspaceValidationError(f"Expected a directory for workspace/list: {sub_dir}")

    entries: List[Dict[str, Any]] = []
    for fpath in _iter_workspace_files(base_dir, root_canonical):
        rel_posix = fpath.relative_to(root_canonical).as_posix()
        raw = fpath.read_bytes()
        line_count = 0 if _is_binary_bytes(raw) else len(raw.decode("utf-8", errors="replace").splitlines())
        size = len(raw)
        entries.append(
            {
                "path": rel_posix,
                "size": size,
                "size_bytes": size,
                "line_count": line_count,
            }
        )
    return {
        "ok": True,
        "workspace_root": get_safe_workspace_label(),
        "count": len(entries),
        "files": entries,
    }


def workspace_read(
    rel_path: str,
    start_line: Optional[int] = None,
    end_line: Optional[int] = None,
) -> Dict[str, Any]:
    root_canonical = WORKSPACE_ROOT.resolve()
    target = _resolve_workspace_path(rel_path, allow_root=False)
    if not target.exists():
        raise FileNotFoundError(f"File not found: {rel_path}")
    if target.is_dir():
        raise WorkspaceValidationError(f"Path is a directory: {rel_path}")

    text = target.read_text(encoding="utf-8", errors="replace")
    all_lines = text.splitlines()
    total_lines = len(all_lines)
    s_line = 1 if start_line is None else max(1, int(start_line))
    e_line = total_lines if end_line is None else int(end_line)

    if total_lines == 0:
        selected: List[str] = []
        eff_start, eff_end = 1, 0
    elif s_line > total_lines:
        selected = []
        eff_start, eff_end = s_line, total_lines
    else:
        eff_start = s_line
        eff_end = min(total_lines, max(s_line - 1, e_line))
        selected = all_lines[eff_start - 1 : eff_end]

    rel_posix = target.relative_to(root_canonical).as_posix()
    return {
        "ok": True,
        "path": rel_posix,
        "start_line": eff_start,
        "end_line": eff_end,
        "total_lines": total_lines,
        "lines": selected,
        "content": "\n".join(selected),
    }


def workspace_write(rel_path: str, content: str) -> Dict[str, Any]:
    root_canonical = WORKSPACE_ROOT.resolve()
    target = _resolve_workspace_path(rel_path, allow_root=False)
    if target.exists() and target.is_dir():
        raise WorkspaceValidationError(f"Cannot overwrite directory as file: {rel_path}")

    existed = target.exists()
    old_text = target.read_text(encoding="utf-8", errors="replace") if existed else ""
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")

    rel_posix = target.relative_to(root_canonical).as_posix()
    diff_info = compute_unified_diff(rel_posix, old_text, content)
    return {
        "ok": True,
        "path": rel_posix,
        "created": not existed,
        "bytes_written": len(content.encode("utf-8")),
        "unified_diff": diff_info["unified_diff"],
        "added_lines": diff_info["added_lines"],
        "removed_lines": diff_info["removed_lines"],
    }


def workspace_edit(
    rel_path: str,
    old_text: str,
    new_text: str,
    replace_all: bool = False,
) -> Dict[str, Any]:
    root_canonical = WORKSPACE_ROOT.resolve()
    target = _resolve_workspace_path(rel_path, allow_root=False)
    if not target.exists():
        raise FileNotFoundError(f"File not found: {rel_path}")
    if target.is_dir():
        raise WorkspaceValidationError(f"Path is a directory: {rel_path}")
    if not old_text:
        raise WorkspaceValidationError("old_text must not be empty.")

    current_text = target.read_text(encoding="utf-8", errors="replace")
    match_count = current_text.count(old_text)
    if match_count == 0:
        raise WorkspaceValidationError("old_text not found in file.")
    if match_count > 1 and not replace_all:
        raise WorkspaceValidationError(
            f"old_text matched {match_count} locations; provide more surrounding context for a unique edit."
        )

    updated_text = current_text.replace(old_text, new_text) if replace_all else current_text.replace(old_text, new_text, 1)
    target.write_text(updated_text, encoding="utf-8")

    rel_posix = target.relative_to(root_canonical).as_posix()
    diff_info = compute_unified_diff(rel_posix, current_text, updated_text)
    return {
        "ok": True,
        "path": rel_posix,
        "replacements": match_count if replace_all else 1,
        "unified_diff": diff_info["unified_diff"],
        "added_lines": diff_info["added_lines"],
        "removed_lines": diff_info["removed_lines"],
    }


def workspace_search(
    query: str,
    path_prefix: str = "",
    case_sensitive: bool = False,
    max_results: int = 200,
) -> Dict[str, Any]:
    if not query:
        raise WorkspaceValidationError("Search query must not be empty.")

    root_canonical = WORKSPACE_ROOT.resolve()
    prefix_posix = ""
    clean_prefix = (path_prefix or "").strip()
    if clean_prefix:
        resolved_prefix = _resolve_workspace_path(clean_prefix, allow_root=True)
        if resolved_prefix != root_canonical:
            prefix_posix = resolved_prefix.relative_to(root_canonical).as_posix()

    needle = query if case_sensitive else query.lower()
    matches: List[str] = []
    results: List[Dict[str, Any]] = []

    for fpath in _iter_workspace_files(root_canonical, root_canonical):
        rel_posix = fpath.relative_to(root_canonical).as_posix()
        if prefix_posix and not (rel_posix == prefix_posix or rel_posix.startswith(prefix_posix + "/")):
            continue
        raw = fpath.read_bytes()
        if _is_binary_bytes(raw):
            continue
        lines = raw.decode("utf-8", errors="replace").splitlines()
        for lineno, line_text in enumerate(lines, start=1):
            hay = line_text if case_sensitive else line_text.lower()
            if needle in hay:
                formatted = f"{rel_posix}:{lineno}: {line_text}"
                matches.append(formatted)
                results.append(
                    {
                        "path": rel_posix,
                        "line": lineno,
                        "content": line_text,
                        "match": formatted,
                    }
                )
                if len(matches) >= max_results:
                    break
        if len(matches) >= max_results:
            break

    return {
        "ok": True,
        "query": query,
        "path_prefix": prefix_posix,
        "count": len(matches),
        "matches": matches,
        "results": results,
    }


def _tokenize_command(command: str) -> List[str]:
    if not isinstance(command, str) or not command.strip():
        raise WorkspaceValidationError("Command must not be empty.")
    cmd_str = command.strip()
    if SHELL_CHAINING_RE.search(cmd_str):
        raise WorkspaceSecurityError(
            "Forbidden shell chaining or metacharacters (&&, ||, ;, |, >, <, $, `)."
        )
    try:
        raw_tokens = shlex.split(cmd_str, posix=False)
    except ValueError as exc:
        raise WorkspaceValidationError(f"Malformed command quoting: {exc}") from exc
    if not raw_tokens:
        raise WorkspaceValidationError("Command must not be empty.")
    tokens: List[str] = []
    for tok in raw_tokens:
        if len(tok) >= 2 and tok[0] == tok[-1] and tok[0] in ("'", '"'):
            tokens.append(tok[1:-1])
        else:
            tokens.append(tok)
    return tokens


def _is_short_slash_flag(tok: str, prog: str) -> bool:
    return prog in ("dir", "findstr") and bool(re.fullmatch(r"/[A-Za-z0-9:_-]{1,8}", tok))


def _assert_safe_path_token(tok: str, allow_root: bool = True) -> Path:
    return _resolve_workspace_path(tok, allow_root=allow_root)


def _run_builtin_diagnostic(prog: str, args: List[str]) -> Dict[str, Any]:
    root_canonical = WORKSPACE_ROOT.resolve()

    if prog == "pwd":
        for a in args:
            if not a.startswith("-"):
                raise WorkspaceSecurityError("Unexpected path argument to pwd.")
        return {"ok": True, "exit_code": 0, "stdout": f"{root_canonical}\n", "stderr": ""}

    if prog in ("ls", "dir"):
        targets: List[Path] = []
        show_hidden = False
        for a in args:
            if a.startswith("-") or _is_short_slash_flag(a, prog):
                if "a" in a.lower():
                    show_hidden = True
                continue
            targets.append(_assert_safe_path_token(a, allow_root=True))
        if not targets:
            targets = [root_canonical]
        out_lines: List[str] = []
        for t in targets:
            if not t.exists():
                return {"ok": False, "exit_code": 1, "stdout": "", "stderr": f"Path not found: {t.name}"}
            if t.is_file():
                out_lines.append(t.relative_to(root_canonical).as_posix())
            else:
                for child in sorted(t.iterdir(), key=lambda p: p.name.lower()):
                    if child.name in WORKSPACE_IGNORE_DIRS:
                        continue
                    if not show_hidden and child.name.startswith("."):
                        continue
                    suffix = "/" if child.is_dir() else ""
                    out_lines.append(f"{child.name}{suffix}")
        stdout_text = ("\n".join(out_lines) + "\n") if out_lines else ""
        return {"ok": True, "exit_code": 0, "stdout": stdout_text, "stderr": ""}

    if prog in ("cat", "type"):
        file_args = [a for a in args if not a.startswith("-") and not _is_short_slash_flag(a, prog)]
        if not file_args:
            raise WorkspaceValidationError(f"'{prog}' requires at least one relative file path.")
        chunks: List[str] = []
        for fa in file_args:
            target = _assert_safe_path_token(fa, allow_root=False)
            if not target.exists() or not target.is_file():
                return {"ok": False, "exit_code": 1, "stdout": "", "stderr": f"File not found: {fa}"}
            chunks.append(target.read_text(encoding="utf-8", errors="replace"))
        return {"ok": True, "exit_code": 0, "stdout": "".join(chunks), "stderr": ""}

    if prog in ("head", "tail"):
        n_lines = 10
        file_args: List[str] = []
        i = 0
        while i < len(args):
            a = args[i]
            if a == "-n" and i + 1 < len(args) and args[i + 1].isdigit():
                n_lines = int(args[i + 1])
                i += 2
                continue
            if re.fullmatch(r"-\d+", a):
                n_lines = int(a[1:])
                i += 1
                continue
            if a.startswith("-"):
                i += 1
                continue
            file_args.append(a)
            i += 1
        if not file_args:
            raise WorkspaceValidationError(f"'{prog}' requires at least one relative file path.")
        outputs: List[str] = []
        for fa in file_args:
            target = _assert_safe_path_token(fa, allow_root=False)
            if not target.exists() or not target.is_file():
                return {"ok": False, "exit_code": 1, "stdout": "", "stderr": f"File not found: {fa}"}
            lines = target.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
            sliced = lines[:n_lines] if prog == "head" else (lines[-n_lines:] if n_lines > 0 else [])
            outputs.append("".join(sliced))
        return {"ok": True, "exit_code": 0, "stdout": "".join(outputs), "stderr": ""}

    if prog == "wc":
        flags = {ch for a in args if a.startswith("-") for ch in a[1:]}
        file_args = [a for a in args if not a.startswith("-")]
        if not file_args:
            raise WorkspaceValidationError("'wc' requires at least one relative file path.")
        out_lines: List[str] = []
        for fa in file_args:
            target = _assert_safe_path_token(fa, allow_root=False)
            if not target.exists() or not target.is_file():
                return {"ok": False, "exit_code": 1, "stdout": "", "stderr": f"File not found: {fa}"}
            raw = target.read_bytes()
            txt = raw.decode("utf-8", errors="replace")
            lc = len(txt.splitlines())
            wc = len(txt.split())
            bc = len(raw)
            rel_posix = target.relative_to(root_canonical).as_posix()
            if flags:
                parts = []
                if "l" in flags:
                    parts.append(str(lc))
                if "w" in flags:
                    parts.append(str(wc))
                if "c" in flags:
                    parts.append(str(bc))
                out_lines.append(" ".join(parts) + f" {rel_posix}")
            else:
                out_lines.append(f"{lc} {wc} {bc} {rel_posix}")
        return {"ok": True, "exit_code": 0, "stdout": "\n".join(out_lines) + "\n", "stderr": ""}

    if prog in ("grep", "findstr"):
        case_sensitive = True
        positional: List[str] = []
        for a in args:
            if a.startswith("-") or _is_short_slash_flag(a, prog):
                if "i" in a.lower():
                    case_sensitive = False
                continue
            positional.append(a)
        if not positional:
            raise WorkspaceValidationError(f"'{prog}' requires a search query pattern.")
        query = positional[0]
        path_args = positional[1:]
        if not path_args:
            res = workspace_search(query, path_prefix="", case_sensitive=case_sensitive)
            matches = res["matches"]
        else:
            matches = []
            for pa in path_args:
                resolved_target = _assert_safe_path_token(pa, allow_root=True)
                if not resolved_target.exists():
                    return {"ok": False, "exit_code": 2, "stdout": "", "stderr": f"Path not found: {pa}"}
                if resolved_target.is_file():
                    rel_p = resolved_target.relative_to(root_canonical).as_posix()
                    raw = resolved_target.read_bytes()
                    if not _is_binary_bytes(raw):
                        needle = query if case_sensitive else query.lower()
                        for idx, ln in enumerate(raw.decode("utf-8", errors="replace").splitlines(), start=1):
                            hay = ln if case_sensitive else ln.lower()
                            if needle in hay:
                                matches.append(f"{rel_p}:{idx}: {ln}")
                else:
                    rel_prefix = "" if resolved_target == root_canonical else resolved_target.relative_to(root_canonical).as_posix()
                    sub_res = workspace_search(query, path_prefix=rel_prefix, case_sensitive=case_sensitive)
                    matches.extend(sub_res["matches"])
        stdout_text = ("\n".join(matches) + "\n") if matches else ""
        return {"ok": len(matches) > 0, "exit_code": 0 if matches else 1, "stdout": stdout_text, "stderr": ""}

    raise WorkspaceSecurityError(f"Unsupported built-in diagnostic command: {prog}")


def workspace_exec(command: str, timeout_sec: float = 15.0) -> Dict[str, Any]:
    tokens = _tokenize_command(command)
    prog = tokens[0].lower()
    args = tokens[1:]
    root_canonical = WORKSPACE_ROOT.resolve()

    builtin_progs = {"ls", "dir", "pwd", "cat", "type", "head", "tail", "wc", "findstr", "grep"}

    if not ALLOW_WORKSPACE_EXEC:
        if prog in builtin_progs:
            return _run_builtin_diagnostic(prog, args)

        if prog in ("python", "python3") and args in (["--version"], ["-V"]):
            exe = shutil.which(tokens[0]) or sys.executable
            argv = [exe, *args]
        elif prog == "pytest" and args in (["--version"], ["-V"]):
            exe = shutil.which("pytest")
            if not exe:
                return {"ok": False, "exit_code": 127, "stdout": "", "stderr": "pytest executable not found"}
            argv = [exe, *args]
        elif prog == "git" and args and args[0] in ("status", "diff"):
            exe = shutil.which("git")
            if not exe:
                return {"ok": False, "exit_code": 127, "stdout": "", "stderr": "git executable not found"}
            forbidden_git_flags = ("--ext-diff", "--exec", "--upload-pack", "--receive-pack", "--output", "-c")
            for a in args[1:]:
                if a == "-c" or any(a.startswith(f) for f in forbidden_git_flags):
                    raise WorkspaceSecurityError(f"Git flag '{a}' is forbidden in read-only mode.")
                if a != "--" and not a.startswith("-"):
                    _assert_safe_path_token(a, allow_root=True)
            argv = [exe, *args]
        else:
            raise WorkspaceSecurityError(
                f"Command '{tokens[0]}' is not allowed in read-only diagnostic mode "
                "(start bridge with --allow-workspace-exec to permit custom workspace commands)."
            )
    else:
        for a in args:
            if not a.startswith("-") and not _is_short_slash_flag(a, prog):
                if ".." in a.replace("\\", "/").split("/") or a.startswith(("/", "\\", "~")) or re.match(r"^[A-Za-z]:", a):
                    raise WorkspaceSecurityError(f"Path outside workspace sandbox is forbidden: {a}")
        if prog in builtin_progs and shutil.which(tokens[0]) is None:
            return _run_builtin_diagnostic(prog, args)
        exe = shutil.which(tokens[0])
        if not exe:
            return {"ok": False, "exit_code": 127, "stdout": "", "stderr": f"Executable not found: {tokens[0]}"}
        argv = [exe, *args]

    try:
        proc = subprocess.run(
            argv,
            cwd=str(root_canonical),
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=max(1.0, min(60.0, float(timeout_sec))),
            check=False,
            shell=False,
        )
        return {
            "ok": proc.returncode == 0,
            "exit_code": proc.returncode,
            "stdout": proc.stdout,
            "stderr": proc.stderr,
        }
    except subprocess.TimeoutExpired:
        return {
            "ok": False,
            "exit_code": -1,
            "stdout": "",
            "stderr": f"Command timed out after {timeout_sec}s",
        }


ALLOWED_CLIS = ("claude", "gemini", "codex")


def resolve_cli_executable(cli_name: str) -> Optional[str]:
    """
    Safely resolves `claude`, `gemini`, or `codex` across Windows (`.exe`, `.cmd`, `.bat`, `.ps1`)
    and POSIX platforms without invoking a shell.
    """
    name = (cli_name or "").strip().lower()
    if name not in ALLOWED_CLIS:
        return None

    if os.name == "nt":
        for candidate in (f"{name}.exe", f"{name}.cmd", f"{name}.bat", f"{name}.ps1", name):
            found = shutil.which(candidate)
            if found:
                return found

        extra_dirs: List[Path] = []
        appdata = os.environ.get("APPDATA", "").strip()
        if appdata:
            extra_dirs.append(Path(appdata) / "npm")
        local_appdata = os.environ.get("LOCALAPPDATA", "").strip()
        if local_appdata:
            extra_dirs.append(Path(local_appdata) / "Programs" / name)
        try:
            extra_dirs.append(Path.home() / ".local" / "bin")
        except Exception:
            pass

        for d in extra_dirs:
            if not d.is_dir():
                continue
            for ext in (".exe", ".cmd", ".bat", ".ps1"):
                cand_path = d / f"{name}{ext}"
                if cand_path.is_file():
                    return str(cand_path)
        return None

    return shutil.which(name)


def build_cli_argv(exe_path: str, args: List[str]) -> List[str]:
    """
    Constructs a shell=False argv vector for executing a resolved CLI path safely.
    Handles Windows `.ps1` via PowerShell (`-NoProfile -ExecutionPolicy Bypass -File`)
    and `.cmd`/`.bat` via `cmd.exe /d /c` without shell string interpolation.
    """
    ext = Path(exe_path).suffix.lower()
    if ext == ".ps1":
        ps_exe = shutil.which("pwsh") or shutil.which("powershell") or "powershell.exe"
        return [ps_exe, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", exe_path, *args]
    if ext in (".cmd", ".bat"):
        cmd_exe = os.environ.get("COMSPEC") or shutil.which("cmd") or "cmd.exe"
        return [cmd_exe, "/d", "/c", exe_path, *args]
    return [exe_path, *args]


def detect_available_clis() -> List[str]:
    found: List[str] = []
    for cli in ALLOWED_CLIS:
        if resolve_cli_executable(cli):
            found.append(cli)
    return found


def has_configured_env_api() -> bool:
    return any(
        os.environ.get(k, "").strip()
        for k in ("GEMINI_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "OPENAI_BASE_URL")
    )


def resolve_active_backend(preferred: str) -> str:
    pref = (preferred or "auto").strip().lower()
    if pref in ("claude", "gemini", "codex", "api", "heuristic"):
        return pref
    # Auto-detect: prefer installed coding CLIs first ($0 extra API cost), then env API keys.
    # Never silently fall back to heuristic hallucinations when no real backend is available.
    clis = detect_available_clis()
    if clis:
        return clis[0]
    if has_configured_env_api():
        return "api"
    return "none"


def sanitize_spoken_reply(raw_text: str, fallback: str = "요청을 처리하고 있어요.") -> str:
    """Strips internal technical bracket tags (e.g. [Self-Healing], [CLAUDE CLI]) for clean Korean speech."""
    text = str(raw_text or "")
    text = re.sub(r"\[[^\]]*\]", " ", text)
    text = re.sub(r"<[^>]+>", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        clean_fb = re.sub(r"\[[^\]]*\]", " ", str(fallback or ""))
        clean_fb = re.sub(r"\s+", " ", clean_fb).strip()
        return clean_fb or "요청을 처리하고 있어요."
    return text[:220]


def normalize_tool_call(tool_name: str, tool_args: Optional[Dict[str, Any]]) -> Tuple[str, Dict[str, Any]]:
    """
    Normalizes legacy/alias tool names and argument keys to canonical on-device schemas:
    - open_url -> open_link
    - wait -> wait_for_delay
    - input_text: submit_enter -> press_enter
    - control_device_setting: wifi -> wifi_panel, bluetooth -> bluetooth_panel
    """
    raw_name = str(tool_name or "").strip()
    canonical_name = TOOL_NAME_ALIASES.get(raw_name, raw_name)
    args: Dict[str, Any] = dict(tool_args) if isinstance(tool_args, dict) else {}

    if canonical_name == "input_text":
        if "submit_enter" in args:
            if "press_enter" not in args:
                args["press_enter"] = bool(args["submit_enter"])
            args.pop("submit_enter", None)
    elif canonical_name == "control_device_setting":
        raw_setting = str(args.get("setting") or "").strip().lower()
        if raw_setting in DEVICE_SETTING_ALIASES:
            args["setting"] = DEVICE_SETTING_ALIASES[raw_setting]
        elif raw_setting:
            args["setting"] = raw_setting
    elif canonical_name == "wait_for_delay":
        if "time_in_ms" not in args:
            if "duration_ms" in args:
                args["time_in_ms"] = int(args.pop("duration_ms"))
            elif "ms" in args:
                args["time_in_ms"] = int(args.pop("ms"))
            elif "seconds" in args:
                args["time_in_ms"] = int(float(args.pop("seconds")) * 1000)

    return canonical_name, args


def _format_chat_history_block(chat_history: Optional[List[Dict[str, Any]]]) -> str:
    if not chat_history:
        return "(none)"
    lines: List[str] = []
    for item in chat_history[-8:]:
        if not isinstance(item, dict):
            continue
        role = str(item.get("sender") or item.get("role") or "USER").strip().upper()
        text = str(item.get("text") or item.get("content") or "").replace("\n", " ").strip()
        if text:
            lines.append(f"  - [{role}]: {text[:240]}")
    return "\n".join(lines) if lines else "(none)"


def parse_ui_elements(prompt_context: str) -> List[Dict[str, Any]]:
    """
    Parses `[idx] Class text="..." desc="..." id="..." norm=[x,y] px=(cx,cy) flags={clickable,editable}`
    (as well as legacy single-quoted `text='...'` and `norm=(x,y)` formats) from `snapshot.toPromptContext()`.
    """
    elements: List[Dict[str, Any]] = []
    for line in prompt_context.splitlines():
        s = line.strip()
        m = re.match(r"^\[(\d+)\]\s+(\S+)(.*)$", s)
        if not m:
            continue
        idx = int(m.group(1))
        cls_name = m.group(2)
        rest = m.group(3)
        text_m = re.search(r'text=(?:"([^"]*)"|\'([^\']*)\')', rest)
        desc_m = re.search(r'desc=(?:"([^"]*)"|\'([^\']*)\')', rest)
        res_m = re.search(r'id=(?:"([^"]*)"|\'([^\']*)\')', rest)
        norm_m = re.search(r"norm=[\[\(](\d+)\s*,\s*(\d+)[\]\)]", rest)
        lower_rest = rest.lower()
        elements.append(
            {
                "index": idx,
                "class": cls_name,
                "text": (text_m.group(1) if text_m and text_m.group(1) is not None else (text_m.group(2) if text_m else "")) or "",
                "desc": (desc_m.group(1) if desc_m and desc_m.group(1) is not None else (desc_m.group(2) if desc_m else "")) or "",
                "res": (res_m.group(1) if res_m and res_m.group(1) is not None else (res_m.group(2) if res_m else "")) or "",
                "norm_x": int(norm_m.group(1)) if norm_m else 500,
                "norm_y": int(norm_m.group(2)) if norm_m else 500,
                "editable": ("editable" in lower_rest) or ("edittext" in cls_name.lower()),
                "clickable": "clickable" in lower_rest,
                "scrollable": "scrollable" in lower_rest,
                "focused": "focused" in lower_rest,
                "raw": s,
            }
        )
    return elements


def build_deliberative_prompt(
    goal: str,
    iteration: int,
    active_pkg: str,
    prompt_context: str,
    history_steps: List[Dict[str, Any]],
    chat_history: Optional[List[Dict[str, Any]]] = None,
) -> str:
    history_txt = "(none — first turn)"
    if history_steps:
        lines = []
        for s in history_steps[-6:]:
            ok = "OK" if s.get("succeeded", True) else "FAILED/NO-DELTA"
            lines.append(
                f"  - Step {s.get('step')}: {s.get('tool')}({s.get('args', '')}) -> [{ok}] {str(s.get('result', '')).strip()[:160]}"
            )
        history_txt = "\n".join(lines)

    chat_txt = _format_chat_history_block(chat_history)

    return f"""You are an autonomous Android Mobile-Use Agent controlling a real Android smartphone.
Follow an empirical Observe -> Decide -> Act -> Verify loop:
1. NEVER call `complete_task` until the user's goal postcondition is visibly satisfied on the CURRENT screen (for example, if the user asked to search '족발' in 배달의민족, launching the app or clicking the search icon is NOT completion — you must focus/type '족발' with `press_enter=true` AND verify the search results screen before calling `complete_task`).
2. NEVER click elements belonging to `com.hanson.mobileuse` when operating an external app.
3. Prefer `element_index` from Visible UI Elements (matching the `[index]` badges on the Set-of-Marks screenshot when attached).
4. If the previous step had `FAILED/NO-DELTA`, do NOT repeat the exact same ineffective action; dismiss any blocking popup, tap the search container/input box explicitly, or swipe to reveal the target.
5. Always provide a clean, natural Korean `spokenReply` without technical bracket tags.

Available canonical `toolName` & `toolArgs` schemas (45 registered tools):
- `"manage_app"`: `{{"action": "launch"|"search"|"list_games"|"list_all", "app_name": "<Korean/English app name or package>"}}`
- `"click"`: `{{"element_index": <int>, "target_description": "<why>"}}` (or `"norm_x": 0..1000, "norm_y": 0..1000`)
- `"long_press"`: `{{"element_index": <int>, "target_description": "<why>", "duration_ms": 700}}`
- `"click_sequence"`: `{{"indices_csv": "1,2,3", "description": "<why>"}}`
- `"input_text"`: `{{"element_index": <optional int>, "text": "<query>", "clear_existing": false, "press_enter": true}}`
- `"swipe"`: `{{"direction": "up"|"down"|"left"|"right", "duration_ms": 350}}`
- `"drag_and_drop"`: `{{"start_norm_x": 0..1000, "start_norm_y": 0..1000, "end_norm_x": 0..1000, "end_norm_y": 0..1000, "description": "<why>"}}`
- `"haptic_trajectory"`: `{{"waypoints_csv": "200,500;500,450;850,500", "description": "<why>"}}`
- `"press_key"`: `{{"key": "BACK"|"HOME"|"ENTER"|"RECENTS"|"NOTIFICATIONS"|"QUICK_SETTINGS"}}`
- `"open_link"`: `{{"url": "https://..."}}`
- `"wait_for_delay"`: `{{"time_in_ms": 1500}}`
- `"observe_screen"`: `{{}}`
- `"list_notifications"`: `{{"only_replyable": false, "limit": 10}}`
- `"reply_notification"`: `{{"target": "<sender>", "message": "<reply>"}}`
- `"control_device_setting"`: `{{"setting": "brightness"|"volume_media"|"volume_ring"|"volume_alarm"|"dnd"|"clipboard"|"wifi_panel"|"bluetooth_panel", "value": "<val>"}}`
- `"set_alarm"`: `{{"hour": 7, "minute": 30, "label": "<label>"}}`
- `"set_timer"`: `{{"seconds": 180, "label": "<label>"}}`
- `"manage_calendar"`: `{{"action": "list"|"add", "title": "<title>", "offset_minutes_from_now": 60, "duration_minutes": 60}}`
- `"make_call"`: `{{"recipient": "<name or number>", "direct_call": false}}`
- `"send_sms"`: `{{"recipient": "<name or number>", "message": "<text>"}}`
- `"search_contacts"`: `{{"query": "<name>"}}`
- `"save_note"` / `"read_note"`: `{{"key": "<title>", "content": "<text>"}}`
- `"execute_lifestyle_routine"`: `{{"routine_id": "<id>"}}`
- `"browser_use"`: `{{"action": "fetch_clean_dom"|"open_interactive_board", "url": "https://...", "goal": "<extract>"}}`
- `"render_generative_ui"` / `"draw_with_code"` / `"generate_visual_media"` / `"cross_device_control"` / `"os_cli_exec"` / `"reverse_engineer_app"` / `"learn_app_skill"`
- `"workspace_list"` / `"workspace_read"` / `"workspace_write"` / `"workspace_edit"` / `"workspace_search"` / `"workspace_exec"`
- `"vault_blind_inject"` / `"vault_list_handles"` / `"watcher_manage"` / `"a2a_negotiate_schedule"` / `"scratchpad_note"`
- `"ask_user"`: `{{"question": "<concise Korean clarification question>"}}`
- `"complete_task"`: `{{"summary": "<concise Korean summary of verified result>"}}`

User Goal: {goal}
Current Iteration: {iteration}
Foreground Package: {active_pkg or "unknown"}
Recent Conversation History:
{chat_txt}
Recent Step History:
{history_txt}

Current Screen (`Visible UI Elements`):
{prompt_context}

Respond with ONLY a single valid JSON object (no markdown fences, no commentary outside JSON):
{{
  "observation": "Brief Korean observation of current screen & delta from last step",
  "spokenReply": "Short natural Korean status or final answer",
  "subGoals": [{{"title": "Step name", "status": "COMPLETED|IN_PROGRESS|PENDING"}}],
  "toolName": "<one of the canonical toolName values above>",
  "toolArgs": {{}}
}}"""


def extract_json_object(raw_text: str) -> Optional[Dict[str, Any]]:
    if not raw_text:
        return None
    cleaned = raw_text.strip()
    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", cleaned, flags=re.S)
    if fence:
        try:
            return json.loads(fence.group(1))
        except Exception:
            pass
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start != -1 and end != -1 and end > start:
        candidate = cleaned[start : end + 1]
        try:
            return json.loads(candidate)
        except Exception:
            pass
    return None


def validate_decision_dict(d: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not isinstance(d, dict):
        return None
    raw_tool = str(d.get("toolName", "")).strip()
    raw_args = d.get("toolArgs")
    tool_name, tool_args = normalize_tool_call(raw_tool, raw_args if isinstance(raw_args, dict) else {})
    if tool_name not in VALID_TOOLS:
        return None
    obs = str(d.get("observation") or "화면 분석 완료").strip()
    raw_spoken = str(d.get("spokenReply") or "").strip()
    spoken = sanitize_spoken_reply(raw_spoken, fallback=obs)
    raw_sub = d.get("subGoals")
    sub_goals: List[Dict[str, str]] = []
    if isinstance(raw_sub, list):
        for item in raw_sub[:6]:
            if isinstance(item, dict) and item.get("title"):
                st = str(item.get("status", "IN_PROGRESS")).upper()
                if st not in ("PENDING", "IN_PROGRESS", "COMPLETED", "FAILED"):
                    st = "IN_PROGRESS"
                sub_goals.append({"title": str(item["title"]), "status": st})
    return {
        "observation": obs,
        "spokenReply": spoken,
        "subGoals": sub_goals,
        "toolName": tool_name,
        "toolArgs": tool_args,
    }


def invoke_coding_cli_text(
    cli_name: str,
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 28.0,
) -> Optional[str]:
    """Invokes a resolved desktop coding CLI (`claude`, `gemini`, `codex`) safely and returns raw stdout text."""
    exe = resolve_cli_executable(cli_name)
    if not exe:
        return None

    temp_img_path: Optional[str] = None
    try:
        if som_screenshot_b64 and cli_name == "codex":
            fd, temp_img_path = tempfile.mkstemp(prefix="hanson_som_", suffix=".jpg")
            os.close(fd)
            with open(temp_img_path, "wb") as f:
                f.write(base64.b64decode(som_screenshot_b64))

        if cli_name == "claude":
            cli_args = ["-p", "--output-format", "text"]
            if CONFIGURED_MODEL:
                cli_args.extend(["--model", CONFIGURED_MODEL])
        elif cli_name == "gemini":
            cli_args = ["-p", "Follow the instructions in stdin and respond concisely."]
            if CONFIGURED_MODEL:
                cli_args.extend(["-m", CONFIGURED_MODEL])
        elif cli_name == "codex":
            cli_args = ["exec", "--skip-git-repo-check", "--ephemeral"]
            if CONFIGURED_MODEL:
                cli_args.extend(["-m", CONFIGURED_MODEL])
            if temp_img_path:
                cli_args.extend(["-i", temp_img_path])
            cli_args.append("-")
        else:
            return None

        argv = build_cli_argv(exe, cli_args)
        proc = subprocess.run(
            argv,
            input=prompt.encode("utf-8"),
            capture_output=True,
            timeout=timeout_sec,
            check=False,
            shell=False,
        )
        stdout_text = proc.stdout.decode("utf-8", errors="replace").strip()
        if proc.returncode != 0 and not stdout_text:
            stderr_preview = proc.stderr.decode("utf-8", errors="replace").strip()[:200]
            print(f"[LaptopCLI] {cli_name} exited {proc.returncode}: {stderr_preview}", flush=True)
            return None
        return stdout_text or None
    except Exception as exc:
        print(f"[LaptopCLI] {cli_name} invocation error: {exc}", flush=True)
        return None
    finally:
        if temp_img_path and os.path.exists(temp_img_path):
            try:
                os.remove(temp_img_path)
            except OSError:
                pass


def invoke_coding_cli_backend(
    cli_name: str,
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 28.0,
) -> Optional[Dict[str, Any]]:
    stdout_text = invoke_coding_cli_text(
        cli_name=cli_name,
        prompt=prompt,
        som_screenshot_b64=som_screenshot_b64,
        timeout_sec=timeout_sec,
    )
    if not stdout_text:
        return None
    parsed = extract_json_object(stdout_text)
    validated = validate_decision_dict(parsed)
    if validated is not None:
        validated["observation"] = f"[{cli_name.upper()} CLI] {validated['observation']}"
        validated["spokenReply"] = sanitize_spoken_reply(validated["spokenReply"], fallback=validated["observation"])
    return validated


def _extract_gemini_grounding_citations(data: Dict[str, Any]) -> List[Dict[str, str]]:
    citations: List[Dict[str, str]] = []
    seen_urls = set()
    candidates = data.get("candidates") or []
    for cand in candidates:
        gm = (cand or {}).get("groundingMetadata") or {}
        for chunk in gm.get("groundingChunks") or []:
            web = (chunk or {}).get("web") or {}
            url = str(web.get("uri") or "").strip()
            title = str(web.get("title") or "").strip() or url
            if url.startswith(("https://", "http://")) and url not in seen_urls:
                seen_urls.add(url)
                citations.append({"title": title, "url": url})
    return citations


def _invoke_gemini_rest_api(
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 25.0,
    enable_google_search: bool = True,
) -> Optional[Tuple[str, List[Dict[str, str]]]]:
    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not gemini_key:
        return None

    model = (
        CONFIGURED_MODEL
        if CONFIGURED_MODEL and CONFIGURED_MODEL.lower().startswith("gemini")
        else os.environ.get("GEMINI_MODEL", "gemini-3.8-flash").strip() or "gemini-3.8-flash"
    )
    if model.startswith("models/"):
        model = model[len("models/") :]

    endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{urllib.parse.quote(model, safe='.-_')}:generateContent"
    parts: List[Dict[str, Any]] = [{"text": prompt}]
    if som_screenshot_b64:
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": som_screenshot_b64}})

    def _call(with_search: bool) -> Dict[str, Any]:
        req_payload: Dict[str, Any] = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0.1},
        }
        if with_search:
            req_payload["tools"] = [{"google_search": {}}]
        body = json.dumps(req_payload, ensure_ascii=False).encode("utf-8")
        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "x-goog-api-key": gemini_key,
        }
        req = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            return json.loads(resp.read().decode("utf-8", errors="replace"))

    try:
        try:
            data = _call(with_search=enable_google_search)
        except urllib.error.HTTPError as http_err:
            if enable_google_search and http_err.code == 400:
                data = _call(with_search=False)
            else:
                raise
        text_chunks: List[str] = []
        for cand in data.get("candidates") or []:
            content = (cand or {}).get("content") or {}
            for p in content.get("parts") or []:
                t = (p or {}).get("text")
                if t:
                    text_chunks.append(str(t))
        combined = "\n".join(text_chunks).strip()
        if not combined:
            return None
        return combined, _extract_gemini_grounding_citations(data)
    except Exception as exc:
        print(f"[LaptopCLI] Gemini REST API error: {exc}", flush=True)
        return None


def _invoke_anthropic_messages_api(
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 25.0,
    max_tokens: int = 1200,
) -> Optional[str]:
    anthropic_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
    if not anthropic_key:
        return None

    endpoint = os.environ.get("ANTHROPIC_BASE_URL", "https://api.anthropic.com/v1/messages").strip()
    model = (
        CONFIGURED_MODEL
        if CONFIGURED_MODEL and CONFIGURED_MODEL.lower().startswith("claude")
        else os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-4-20250514").strip() or "claude-sonnet-4-20250514"
    )
    user_content: Any = prompt
    if som_screenshot_b64:
        user_content = [
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": "image/jpeg",
                    "data": som_screenshot_b64,
                },
            },
            {"type": "text", "text": prompt},
        ]
    body = json.dumps(
        {
            "model": model,
            "max_tokens": max_tokens,
            "temperature": 0.1,
            "messages": [{"role": "user", "content": user_content}],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {
        "Content-Type": "application/json; charset=utf-8",
        "x-api-key": anthropic_key,
        "anthropic-version": "2023-06-01",
    }
    req = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
            blocks = [
                str(b.get("text", ""))
                for b in (data.get("content") or [])
                if isinstance(b, dict) and b.get("type") == "text" and b.get("text")
            ]
            combined = "\n".join(blocks).strip()
            return combined or None
    except Exception as exc:
        print(f"[LaptopCLI] Anthropic Messages API error: {exc}", flush=True)
        return None


def _invoke_openai_compatible_api(
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 25.0,
) -> Optional[str]:
    base_url = os.environ.get("OPENAI_BASE_URL", "").strip()
    openai_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if not (base_url or openai_key):
        return None

    endpoint = base_url.rstrip("/") if base_url else "https://api.openai.com/v1/chat/completions"
    if not endpoint.endswith("/chat/completions"):
        endpoint = endpoint + ("/chat/completions" if endpoint.endswith("/v1") else "/v1/chat/completions")
    model = (
        CONFIGURED_MODEL
        if CONFIGURED_MODEL and not CONFIGURED_MODEL.lower().startswith(("gemini", "claude"))
        else os.environ.get("OPENAI_MODEL", "gpt-4o-mini").strip() or "gpt-4o-mini"
    )
    user_content: Any = prompt
    if som_screenshot_b64:
        user_content = [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{som_screenshot_b64}"}},
        ]
    body = json.dumps(
        {
            "model": model,
            "temperature": 0.1,
            "messages": [{"role": "user", "content": user_content}],
        },
        ensure_ascii=False,
    ).encode("utf-8")
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if openai_key:
        headers["Authorization"] = f"Bearer {openai_key}"
    req = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
            content = data["choices"][0]["message"]["content"]
            return str(content).strip() or None
    except Exception as exc:
        print(f"[LaptopCLI] OpenAI-compatible API error: {exc}", flush=True)
        return None


def _ordered_env_api_providers() -> List[str]:
    hint = CONFIGURED_MODEL.lower()
    if hint.startswith("claude"):
        return ["anthropic", "gemini", "openai"]
    if hint.startswith(("gpt", "o1", "o3", "o4")):
        return ["openai", "gemini", "anthropic"]
    return ["gemini", "anthropic", "openai"]


def invoke_env_api_backend(
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 25.0,
) -> Optional[Dict[str, Any]]:
    """
    Calls real configured environment API backends in priority order:
    - GEMINI_API_KEY (Gemini REST v1beta with x-goog-api-key header + google_search grounding)
    - ANTHROPIC_API_KEY (Anthropic Messages API with x-api-key header)
    - OPENAI_API_KEY / OPENAI_BASE_URL (OpenAI-compatible Chat Completions API)
    """
    for provider in _ordered_env_api_providers():
        raw_text: Optional[str] = None
        if provider == "gemini":
            res = _invoke_gemini_rest_api(
                prompt,
                som_screenshot_b64=som_screenshot_b64,
                timeout_sec=timeout_sec,
                enable_google_search=True,
            )
            if res is not None:
                raw_text = res[0]
        elif provider == "anthropic":
            raw_text = _invoke_anthropic_messages_api(
                prompt,
                som_screenshot_b64=som_screenshot_b64,
                timeout_sec=timeout_sec,
            )
        elif provider == "openai":
            raw_text = _invoke_openai_compatible_api(
                prompt,
                som_screenshot_b64=som_screenshot_b64,
                timeout_sec=timeout_sec,
            )

        if raw_text:
            validated = validate_decision_dict(extract_json_object(raw_text))
            if validated is not None:
                return validated
    return None


def extract_query_from_goal(goal: str) -> Optional[str]:
    quoted = re.search(r'"([^"]+)"|\'([^\']+)\'', goal)
    if quoted:
        return (quoted.group(1) or quoted.group(2)).strip()

    m = re.search(
        r"(?:열어서|열고|에서|창에|에)?\s*([가-힣a-zA-Z0-9_\s]{1,18}?)\s*(?:검색|입력|찾고|찾아|타이핑|쳐서|치고)",
        goal,
    )
    if m:
        candidate = m.group(1)
        for noise in ["검색창", "배달의민족", "배민", "카카오톡", "카톡", "유튜브", "설정", "토스", "네이버", "크롬", "쿠팡", "그리고", "해서"]:
            candidate = candidate.replace(noise, "")
        candidate = candidate.strip()
        if candidate:
            return candidate
    return None


def _resolve_swipe_direction(lower_goal: str) -> str:
    """Explicit scroll/swipe direction mapping: '아래로' -> 'down', '위로' -> 'up'."""
    if "아래로" in lower_goal or "아래" in lower_goal or "내려" in lower_goal:
        return "down"
    if "위로" in lower_goal or "위" in lower_goal or "올려" in lower_goal:
        return "up"
    if "왼쪽" in lower_goal or "좌로" in lower_goal:
        return "left"
    if "오른쪽" in lower_goal or "우로" in lower_goal:
        return "right"
    return "down"


def local_cli_decide(
    goal: str,
    iteration: int,
    active_pkg: str,
    prompt_context: str,
    history_steps: List[Dict[str, Any]],
) -> Dict[str, Any]:
    """
    Deterministic offline heuristic mode — ONLY invoked when `--backend heuristic` is explicitly selected.
    Never used as a silent hallucination fallback when real CLI/API backends fail.
    """
    g = goal.strip()
    lower = g.lower()
    elements = parse_ui_elements(prompt_context)

    def _finalize(decision: Dict[str, Any]) -> Dict[str, Any]:
        validated = validate_decision_dict(decision)
        if validated is not None:
            return validated
        obs = str(decision.get("observation") or "요청을 처리하고 있어요.")
        return {
            "observation": obs,
            "spokenReply": sanitize_spoken_reply(decision.get("spokenReply") or obs),
            "subGoals": list(decision.get("subGoals") or []),
            "toolName": str(decision.get("toolName") or "complete_task"),
            "toolArgs": dict(decision.get("toolArgs") or {}),
        }

    # 0. Recovery if previous step failed or stalled (clean Korean spokenReply, no [Self-Healing] tag leak)
    if history_steps:
        last_step = history_steps[-1]
        last_ok = bool(last_step.get("succeeded", True))
        last_tool = last_step.get("tool", "")
        last_result = str(last_step.get("result", ""))
        recent_failures = sum(1 for s in history_steps[-2:] if not s.get("succeeded", True))
        if (not last_ok) and recent_failures <= 1 and iteration <= 6:
            modal_btn = next(
                (
                    el
                    for el in elements
                    if any(kw in (el["text"] + " " + el["desc"]) for kw in ["허용", "닫기", "나중에", "확인", "건너뛰기", "오늘 하루 보지 않기"])
                ),
                None,
            )
            if modal_btn:
                btn_label = modal_btn["text"] or modal_btn["desc"]
                return _finalize(
                    {
                        "observation": f"팝업 요소('{btn_label}')를 먼저 닫고 재시도합니다.",
                        "spokenReply": f"화면에 뜬 '{btn_label}' 팝업을 먼저 닫고 다시 진행할게요.",
                        "subGoals": [
                            {"title": "팝업 닫기 복구", "status": "IN_PROGRESS"},
                            {"title": "원래 목표 재실행", "status": "PENDING"},
                        ],
                        "toolName": "click",
                        "toolArgs": {"element_index": modal_btn["index"], "target_description": "팝업 닫기"},
                    }
                )
            if last_tool == "click":
                return _finalize(
                    {
                        "observation": f"이전 클릭 후 화면 변화가 없어({last_result[:45]}) 스크롤 후 재탐색합니다.",
                        "spokenReply": "화면 변화가 없어서 스크롤하며 다시 찾아볼게요.",
                        "subGoals": [
                            {"title": "화면 스와이프 재노출", "status": "IN_PROGRESS"},
                            {"title": "타깃 재실행", "status": "PENDING"},
                        ],
                        "toolName": "swipe",
                        "toolArgs": {"direction": "down", "duration_ms": 360},
                    }
                )

    matched_app_name = next((name for name in APP_MAP if name in lower), None)
    target_pkg = APP_MAP.get(matched_app_name) if matched_app_name else None
    search_query = extract_query_from_goal(g)

    wants_drag_drop = any(k in lower for k in ["드래그", "슬라이더", "옮겨", "drag"])
    wants_swipe = any(k in lower for k in ["스크롤", "스와이프", "내려", "올려", "아래로", "위로"])
    wants_post_click = (search_query is not None) and any(
        k in lower for k in ["클릭", "틀어", "재생", "선택", "들어가", "열어", "눌러"]
    )
    is_multi_step = (
        (search_query is not None)
        or wants_drag_drop
        or wants_swipe
        or (matched_app_name is not None and any(k in lower for k in ["열어서", "그리고", "해서", "누르고", "찾고", "찾아"]))
    )

    if is_multi_step and iteration <= 8:
        has_launched = any(s.get("tool") == "manage_app" and s.get("succeeded", True) for s in history_steps)
        has_typed = any(s.get("tool") == "input_text" and s.get("succeeded", True) for s in history_steps)
        has_clicked_search = any(s.get("tool") == "click" for s in history_steps)
        has_dragged = any(s.get("tool") in ("drag_and_drop", "haptic_trajectory") for s in history_steps)
        has_swiped = any(s.get("tool") == "swipe" for s in history_steps)

        seen_input = False
        clicks_after_input = 0
        for s in history_steps:
            if s.get("tool") == "input_text" and s.get("succeeded", True):
                seen_input = True
            elif seen_input and s.get("tool") == "click":
                clicks_after_input += 1

        ledger: List[Dict[str, str]] = []
        if matched_app_name:
            app_done = (active_pkg == target_pkg) or has_launched
            ledger.append({"title": f"1단계: '{matched_app_name}' 앱 진입", "status": "COMPLETED" if app_done else "IN_PROGRESS"})
        if search_query:
            ledger.append({"title": f"2단계: '{search_query}' 검색어 입력 및 제출", "status": "COMPLETED" if has_typed else "IN_PROGRESS"})
        if wants_post_click:
            ledger.append(
                {
                    "title": f"3단계: '{search_query}' 검색 결과 선택",
                    "status": "COMPLETED" if clicks_after_input >= 1 else ("IN_PROGRESS" if has_typed else "PENDING"),
                }
            )

        if matched_app_name and active_pkg != target_pkg and not has_launched:
            return _finalize(
                {
                    "observation": f"목표 앱 '{matched_app_name}'을(를) 실행합니다.",
                    "spokenReply": f"{matched_app_name} 앱을 열게요.",
                    "subGoals": ledger,
                    "toolName": "manage_app",
                    "toolArgs": {"action": "launch", "app_name": matched_app_name},
                }
            )

        if search_query and not has_typed:
            editable_el = next((el for el in elements if el["editable"]), None)
            if editable_el is not None:
                return _finalize(
                    {
                        "observation": f"입력창([{editable_el['index']}])에 '{search_query}'을(를) 입력하고 검색을 제출합니다.",
                        "spokenReply": f"'{search_query}'를 검색할게요.",
                        "subGoals": ledger,
                        "toolName": "input_text",
                        "toolArgs": {
                            "element_index": editable_el["index"],
                            "text": search_query,
                            "clear_existing": False,
                            "press_enter": True,
                        },
                    }
                )

            if not has_clicked_search:
                search_btn = next(
                    (
                        el
                        for el in elements
                        if any(kw in (el["text"] + " " + el["desc"] + " " + el["res"]).lower() for kw in ["검색", "search", "찾기", "찾아"])
                    ),
                    None,
                )
                if search_btn is None:
                    search_btn = next(
                        (el for el in elements if el["clickable"] and 35 <= el["norm_y"] <= 180 and el["norm_x"] >= 600),
                        None,
                    )
                if search_btn is not None:
                    label = search_btn["text"] or search_btn["desc"] or "검색창"
                    return _finalize(
                        {
                            "observation": f"화면에서 검색 진입 요소([{search_btn['index']}] '{label}')를 탭합니다.",
                            "spokenReply": "검색창을 먼저 열게요.",
                            "subGoals": ledger,
                            "toolName": "click",
                            "toolArgs": {"element_index": search_btn["index"], "target_description": f"검색창 열기 ({label})"},
                        }
                    )

            return _finalize(
                {
                    "observation": f"'{search_query}' 검색어를 입력하고 제출합니다.",
                    "spokenReply": f"'{search_query}'를 입력할게요.",
                    "subGoals": ledger,
                    "toolName": "input_text",
                    "toolArgs": {"text": search_query, "press_enter": True},
                }
            )

        if wants_drag_drop and not has_dragged:
            slider_el = next(
                (el for el in elements if any(kw in (el["class"] + " " + el["text"] + " " + el["desc"]).lower() for kw in ["seekbar", "slider", "밝기", "볼륨"])),
                None,
            )
            norm_y = slider_el["norm_y"] if slider_el else 500
            return _finalize(
                {
                    "observation": f"좌표 (250, {norm_y}) -> (780, {norm_y}) 드래그 앤 드롭을 실행합니다.",
                    "spokenReply": "슬라이더를 드래그해서 조절할게요.",
                    "subGoals": ledger,
                    "toolName": "drag_and_drop",
                    "toolArgs": {
                        "start_norm_x": 250,
                        "start_norm_y": norm_y,
                        "end_norm_x": 780,
                        "end_norm_y": norm_y,
                        "hold_ms": 480,
                        "duration_ms": 680,
                        "description": "슬라이더 드래그",
                    },
                }
            )

        if wants_swipe and not has_swiped:
            direction = _resolve_swipe_direction(lower)
            dir_ko = {"down": "아래로", "up": "위로", "left": "왼쪽으로", "right": "오른쪽으로"}.get(direction, direction)
            return _finalize(
                {
                    "observation": f"'{direction}' 방향으로 스크롤합니다.",
                    "spokenReply": f"화면을 {dir_ko} 스크롤할게요.",
                    "subGoals": ledger,
                    "toolName": "swipe",
                    "toolArgs": {"direction": direction, "duration_ms": 380},
                }
            )

        if wants_post_click and clicks_after_input == 0:
            result_el = next(
                (
                    el
                    for el in elements
                    if (not el["editable"])
                    and el["norm_y"] >= 160
                    and (
                        search_query.lower() in (el["text"] + " " + el["desc"]).lower()
                        or (el["clickable"] and (el["text"] or el["desc"]))
                    )
                ),
                None,
            )
            if result_el is not None:
                label = (result_el["text"] or result_el["desc"])[:28]
                return _finalize(
                    {
                        "observation": f"검색 결과 요소([{result_el['index']}] '{label}')를 탭합니다.",
                        "spokenReply": f"'{label}' 항목을 선택할게요.",
                        "subGoals": ledger,
                        "toolName": "click",
                        "toolArgs": {"element_index": result_el["index"], "target_description": f"검색 결과 선택 ({label})"},
                    }
                )

        if history_steps:
            step_lines = [
                f"• Step {s.get('step')} [{s.get('tool')}]: {(str(s.get('result') or '').splitlines() or [''])[0]}"
                for s in history_steps
            ]
            return _finalize(
                {
                    "observation": f"총 {len(history_steps)}단계의 실행 및 사후 화면 검증을 완료했습니다.",
                    "spokenReply": "요청하신 작업을 모두 마쳤어요.",
                    "subGoals": [{**item, "status": "COMPLETED"} for item in ledger],
                    "toolName": "complete_task",
                    "toolArgs": {"summary": f"✅ 목표 완료 ({len(history_steps)}스텝 검증됨)\n" + "\n".join(step_lines)},
                }
            )

    if iteration >= 2 and history_steps:
        last = history_steps[-1]
        return _finalize(
            {
                "observation": f"'{last.get('tool', '')}' 실행 및 화면 전이 확인을 마쳤습니다.",
                "spokenReply": "실행 결과를 확인하고 작업을 마쳤어요.",
                "subGoals": [{"title": "실행 및 검증 완료", "status": "COMPLETED"}],
                "toolName": "complete_task",
                "toolArgs": {"summary": str(last.get("result", "완료"))},
            }
        )

    for kw in APP_MAP:
        if kw in lower and any(v in lower for v in ["열어", "실행", "켜", "가자", "틀어"]):
            return _finalize(
                {
                    "observation": f"'{kw}' 앱을 실행합니다.",
                    "spokenReply": f"{kw} 앱을 실행할게요.",
                    "subGoals": [{"title": f"'{kw}' 앱 실행", "status": "IN_PROGRESS"}],
                    "toolName": "manage_app",
                    "toolArgs": {"action": "launch", "app_name": kw},
                }
            )

    matched_el = next(
        (
            el
            for el in elements
            if (el["text"] and el["text"].lower() in lower) or (el["desc"] and el["desc"].lower() in lower)
        ),
        None,
    )
    if matched_el is not None:
        label = matched_el["text"] or matched_el["desc"]
        return _finalize(
            {
                "observation": f"현재 화면에서 '[{matched_el['index']}] {label}' 요소를 탭합니다.",
                "spokenReply": f"'{label}' 버튼을 누를게요.",
                "subGoals": [{"title": f"'{label}' 탭", "status": "IN_PROGRESS"}],
                "toolName": "click",
                "toolArgs": {"element_index": matched_el["index"], "target_description": label},
            }
        )

    if "홈" in lower:
        return _finalize(
            {
                "observation": "홈 화면으로 이동합니다.",
                "spokenReply": "홈 화면으로 이동할게요.",
                "subGoals": [{"title": "HOME 이동", "status": "IN_PROGRESS"}],
                "toolName": "press_key",
                "toolArgs": {"key": "HOME"},
            }
        )

    return _finalize(
        {
            "observation": f"'{g}' 요청을 확인했습니다.",
            "spokenReply": "요청을 확인했어요.",
            "subGoals": [{"title": "응답 완료", "status": "COMPLETED"}],
            "toolName": "complete_task",
            "toolArgs": {"summary": f"요청 확인됨: '{g}'"},
        }
    )


def decide_turn(
    goal: str,
    iteration: int,
    active_pkg: str,
    prompt_context: str,
    history_steps: List[Dict[str, Any]],
    som_screenshot_b64: Optional[str] = None,
    chat_history: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    configured = (CONFIGURED_BACKEND or "auto").strip().lower()
    if configured == "heuristic":
        return local_cli_decide(goal, iteration, active_pkg, prompt_context, history_steps)

    prompt = build_deliberative_prompt(
        goal,
        iteration,
        active_pkg,
        prompt_context,
        history_steps,
        chat_history=chat_history,
    )

    attempted: List[str] = []
    if configured in ALLOWED_CLIS:
        attempted.append(configured)
        cli_decision = invoke_coding_cli_backend(configured, prompt, som_screenshot_b64=som_screenshot_b64)
        if cli_decision is not None:
            return cli_decision
    elif configured == "api":
        attempted.append("api")
        api_decision = invoke_env_api_backend(prompt, som_screenshot_b64=som_screenshot_b64)
        if api_decision is not None:
            return api_decision
    else:
        # Auto mode: try all available desktop CLIs ($0 extra API cost), then env API keys
        for cli in detect_available_clis():
            attempted.append(cli)
            cli_decision = invoke_coding_cli_backend(cli, prompt, som_screenshot_b64=som_screenshot_b64)
            if cli_decision is not None:
                return cli_decision
        if has_configured_env_api():
            attempted.append("api")
            api_decision = invoke_env_api_backend(prompt, som_screenshot_b64=som_screenshot_b64)
            if api_decision is not None:
                return api_decision

    tried_desc = ", ".join(attempted) if attempted else "none detected"
    raise BackendUnavailableError(
        f"No reasoning backend succeeded (configured={configured}, attempted={tried_desc}). "
        "Install/authenticate claude, gemini, or codex CLI, or set GEMINI_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY."
    )


def build_web_research_prompt(
    query: str,
    system_context: str = "",
    chat_history: Optional[List[Dict[str, Any]]] = None,
) -> str:
    ctx_block = system_context.strip() or "(none)"
    chat_block = _format_chat_history_block(chat_history)
    return f"""You are Hanson's desktop AI web & knowledge researcher.
Answer the user's question accurately, concisely, and naturally in Korean (`answer_ko`).
Include up to 5 verified web reference links (`citations`) when available (only real `https://` or `http://` URLs; never invent fake URLs).

System Context:
{ctx_block}

Recent Conversation History:
{chat_block}

User Query:
{query.strip()}

Respond with ONLY a single valid JSON object (no markdown fences):
{{
  "answer_ko": "자연스럽고 정확한 한국어 답변 (핵심 요약)",
  "citations": [{{"title": "출처 제목", "url": "https://example.com"}}]
}}"""


def _sanitize_citations(
    raw_citations: Any,
    fallback_text: str = "",
    extra_citations: Optional[List[Dict[str, str]]] = None,
) -> List[Dict[str, str]]:
    cleaned: List[Dict[str, str]] = []
    seen = set()

    def _add(title: str, url: str) -> None:
        u = (url or "").strip().rstrip(").,;\"'")
        if not u.startswith(("https://", "http://")):
            return
        if u in seen:
            return
        seen.add(u)
        t = (title or "").strip() or u
        cleaned.append({"title": t[:160], "url": u[:1024]})

    if isinstance(raw_citations, list):
        for item in raw_citations:
            if isinstance(item, dict):
                _add(str(item.get("title") or ""), str(item.get("url") or item.get("uri") or ""))
            elif isinstance(item, str):
                _add(item, item)

    if extra_citations:
        for item in extra_citations:
            if isinstance(item, dict):
                _add(str(item.get("title") or ""), str(item.get("url") or ""))

    if fallback_text:
        for m in re.finditer(r"\[([^\]]{1,120})\]\((https?://[^\s)]+)\)", fallback_text):
            _add(m.group(1), m.group(2))
        for m in re.finditer(r"(?<![(\"])(https?://[^\s<>)\"']+)", fallback_text):
            _add(m.group(1), m.group(1))

    return cleaned[:8]


def _parse_web_research_output(
    raw_text: str,
    backend_label: str,
    extra_citations: Optional[List[Dict[str, str]]] = None,
) -> Optional[Dict[str, Any]]:
    if not raw_text or not raw_text.strip():
        return None
    parsed = extract_json_object(raw_text)
    if isinstance(parsed, dict):
        answer = str(parsed.get("answer_ko") or parsed.get("answer") or parsed.get("summary") or "").strip()
        if answer:
            citations = _sanitize_citations(
                parsed.get("citations"),
                fallback_text=answer,
                extra_citations=extra_citations,
            )
            return {
                "ok": True,
                "answer_ko": answer,
                "citations": citations,
                "backend": backend_label,
            }
    plain = re.sub(r"^```[a-zA-Z]*\s*|\s*```$", "", raw_text.strip()).strip()
    if not plain:
        return None
    citations = _sanitize_citations([], fallback_text=plain, extra_citations=extra_citations)
    return {
        "ok": True,
        "answer_ko": plain,
        "citations": citations,
        "backend": backend_label,
    }


def run_web_research(
    query: str,
    system_context: str = "",
    chat_history: Optional[List[Dict[str, Any]]] = None,
    timeout_sec: float = 32.0,
) -> Dict[str, Any]:
    clean_query = (query or "").strip()
    if not clean_query:
        raise WorkspaceValidationError("Field 'query' must not be empty.")

    configured = (CONFIGURED_BACKEND or "auto").strip().lower()
    prompt = build_web_research_prompt(clean_query, system_context=system_context, chat_history=chat_history)
    attempted: List[str] = []

    def _try_cli(cli_name: str) -> Optional[Dict[str, Any]]:
        attempted.append(cli_name)
        raw = invoke_coding_cli_text(cli_name, prompt, som_screenshot_b64=None, timeout_sec=timeout_sec)
        if not raw:
            return None
        return _parse_web_research_output(raw, backend_label=f"{cli_name}_cli")

    def _try_env_apis() -> Optional[Dict[str, Any]]:
        for provider in _ordered_env_api_providers():
            if provider == "gemini" and os.environ.get("GEMINI_API_KEY", "").strip():
                attempted.append("gemini_api")
                res = _invoke_gemini_rest_api(
                    prompt,
                    som_screenshot_b64=None,
                    timeout_sec=timeout_sec,
                    enable_google_search=True,
                )
                if res is not None:
                    parsed = _parse_web_research_output(
                        res[0],
                        backend_label="gemini_api_google_search",
                        extra_citations=res[1],
                    )
                    if parsed is not None:
                        return parsed
            elif provider == "anthropic" and os.environ.get("ANTHROPIC_API_KEY", "").strip():
                attempted.append("anthropic_api")
                raw = _invoke_anthropic_messages_api(
                    prompt,
                    som_screenshot_b64=None,
                    timeout_sec=timeout_sec,
                    max_tokens=1600,
                )
                if raw:
                    parsed = _parse_web_research_output(raw, backend_label="anthropic_api")
                    if parsed is not None:
                        return parsed
            elif provider == "openai" and (
                os.environ.get("OPENAI_API_KEY", "").strip() or os.environ.get("OPENAI_BASE_URL", "").strip()
            ):
                attempted.append("openai_api")
                raw = _invoke_openai_compatible_api(prompt, som_screenshot_b64=None, timeout_sec=timeout_sec)
                if raw:
                    parsed = _parse_web_research_output(raw, backend_label="openai_api")
                    if parsed is not None:
                        return parsed
        return None

    if configured in ALLOWED_CLIS:
        hit = _try_cli(configured)
        if hit is not None:
            return hit
    elif configured == "api":
        hit = _try_env_apis()
        if hit is not None:
            return hit
    elif configured != "heuristic":
        for cli in detect_available_clis():
            hit = _try_cli(cli)
            if hit is not None:
                return hit
        if has_configured_env_api():
            hit = _try_env_apis()
            if hit is not None:
                return hit

    tried_desc = ", ".join(attempted) if attempted else "none detected"
    raise BackendUnavailableError(
        f"No live web research backend succeeded (configured={configured}, attempted={tried_desc}). "
        "Authenticate claude/gemini/codex CLI or configure GEMINI_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY."
    )


class LaptopCliBridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[LaptopCLI :{self.server.server_port}] {fmt % args}", flush=True)

    def _send_json(self, data: Dict[str, Any], status: int = 200) -> None:
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _check_request_security(self, require_json_content_type: bool = False) -> bool:
        # 1. Block browser cross-origin CSRF & reverse-proxy/tunnel hijacking unconditionally
        for hdr in FORBIDDEN_BROWSER_OR_PROXY_HEADERS:
            if self.headers.get(hdr):
                self._send_json(
                    {"ok": False, "error": f"Forbidden browser or proxy header: {hdr}"},
                    status=403,
                )
                return False

        # 2. Prevent DNS rebinding by allowing strictly loopback Host headers
        host_hdr = (self.headers.get("Host") or "").strip()
        if host_hdr and not LOOPBACK_HOST_RE.match(host_hdr):
            self._send_json(
                {"ok": False, "error": "Forbidden non-loopback Host header"},
                status=403,
            )
            return False

        # 3. Require application/json on POST to block HTML form / text/plain Simple POST CSRF
        if require_json_content_type:
            ct = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            if ct and ct != "application/json":
                self._send_json(
                    {"ok": False, "error": "Content-Type must be application/json"},
                    status=415,
                )
                return False

        # 4. Constant-time token verification when HANSON_BRIDGE_TOKEN / --token is configured
        if BRIDGE_AUTH_TOKEN:
            provided = (self.headers.get("X-Bridge-Token") or "").strip()
            if not provided:
                auth_hdr = (self.headers.get("Authorization") or "").strip()
                if auth_hdr.lower().startswith("bearer "):
                    provided = auth_hdr[7:].strip()
            if not provided or not hmac.compare_digest(provided.encode("utf-8"), BRIDGE_AUTH_TOKEN.encode("utf-8")):
                self._send_json(
                    {"ok": False, "error": "Unauthorized: valid X-Bridge-Token or Bearer token required"},
                    status=401,
                )
                return False

        return True

    def _handle_workspace_route(self, route: str, payload: Dict[str, Any]) -> None:
        is_exec = route == "/workspace/exec"
        try:
            if route == "/workspace/list":
                sub_dir = str(payload.get("path") or payload.get("sub_dir") or payload.get("prefix") or "")
                self._send_json(workspace_list(sub_dir=sub_dir), status=200)
                return

            if route == "/workspace/read":
                if "path" not in payload or not isinstance(payload.get("path"), str):
                    raise WorkspaceValidationError("Field 'path' (string) is required.")
                start_line = payload.get("start_line")
                end_line = payload.get("end_line")
                self._send_json(
                    workspace_read(
                        rel_path=payload["path"],
                        start_line=int(start_line) if start_line is not None else None,
                        end_line=int(end_line) if end_line is not None else None,
                    ),
                    status=200,
                )
                return

            if route == "/workspace/write":
                if "path" not in payload or not isinstance(payload.get("path"), str):
                    raise WorkspaceValidationError("Field 'path' (string) is required.")
                if "content" not in payload or payload.get("content") is None:
                    raise WorkspaceValidationError("Field 'content' is required.")
                self._send_json(
                    workspace_write(rel_path=payload["path"], content=str(payload["content"])),
                    status=200,
                )
                return

            if route == "/workspace/edit":
                if "path" not in payload or not isinstance(payload.get("path"), str):
                    raise WorkspaceValidationError("Field 'path' (string) is required.")
                if "old_text" not in payload or payload.get("old_text") is None:
                    raise WorkspaceValidationError("Field 'old_text' is required.")
                if "new_text" not in payload or payload.get("new_text") is None:
                    raise WorkspaceValidationError("Field 'new_text' is required.")
                self._send_json(
                    workspace_edit(
                        rel_path=payload["path"],
                        old_text=str(payload["old_text"]),
                        new_text=str(payload["new_text"]),
                        replace_all=bool(payload.get("replace_all", False)),
                    ),
                    status=200,
                )
                return

            if route == "/workspace/search":
                if "query" not in payload or payload.get("query") is None:
                    raise WorkspaceValidationError("Field 'query' is required.")
                self._send_json(
                    workspace_search(
                        query=str(payload["query"]),
                        path_prefix=str(payload.get("path_prefix") or ""),
                        case_sensitive=bool(payload.get("case_sensitive", False)),
                    ),
                    status=200,
                )
                return

            if route == "/workspace/exec":
                if "command" not in payload or payload.get("command") is None:
                    raise WorkspaceValidationError("Field 'command' is required.")
                timeout_val = float(payload.get("timeout_sec") or 15.0)
                self._send_json(
                    workspace_exec(command=str(payload["command"]), timeout_sec=timeout_val),
                    status=200,
                )
                return

            self._send_json({"ok": False, "error": f"Unknown workspace route: {route}"}, status=404)
        except WorkspaceSecurityError as exc:
            err_payload: Dict[str, Any] = {"ok": False, "error": str(exc)}
            if is_exec:
                err_payload.update({"exit_code": -1, "stdout": "", "stderr": str(exc)})
            self._send_json(err_payload, status=403)
        except FileNotFoundError as exc:
            self._send_json({"ok": False, "error": str(exc)}, status=404)
        except (WorkspaceValidationError, ValueError, TypeError) as exc:
            err_payload = {"ok": False, "error": str(exc)}
            if is_exec:
                err_payload.update({"exit_code": -1, "stdout": "", "stderr": str(exc)})
            self._send_json(err_payload, status=400)

    def do_GET(self) -> None:
        if not self._check_request_security(require_json_content_type=False):
            return
        parsed_url = urllib.parse.urlparse(self.path)
        route = parsed_url.path

        if route in ("/health", "/ping"):
            self._send_json(
                {
                    "status": "ok",
                    "bridge": "Hanson-Laptop-CLI-Bridge-v6.0",
                    "version": SERVER_VERSION,
                    "port": self.server.server_port,
                    "configuredBackend": CONFIGURED_BACKEND,
                    "activeBackend": resolve_active_backend(CONFIGURED_BACKEND),
                    "availableClis": detect_available_clis(),
                    "workspace_root": get_safe_workspace_label(),
                    "workspace_routes": True,
                    "web_research_route": True,
                    "allow_workspace_exec": ALLOW_WORKSPACE_EXEC,
                    "pickle_sota_capabilities": [
                        "blind_vault_no_llm_plaintext",
                        "financial_checkout_and_price_drift_guard",
                        "captcha_2fa_human_takeover",
                        "proactive_watchers",
                        "a2a_schedule_negotiation",
                        "notetaker_scratchpad",
                        "metacognitive_loop_breaker",
                        "human_memory_bubbles",
                        "d_artemis_tac_aca_alignment",
                        "one_tap_reversible_receipts",
                        "zero_cost_desktop_web_research",
                    ],
                    "valid_tools_count": len(VALID_TOOLS),
                }
            )
            return

        if route == "/pickle/capabilities":
            self._send_json(
                {
                    "version": SERVER_VERSION,
                    "bridge": "Hanson-Laptop-CLI-Bridge-v6.0",
                    "validTools": sorted(VALID_TOOLS),
                    "validToolsCount": len(VALID_TOOLS),
                    "nonBypassPolicy": "CAPTCHA, Turnstile, 2FA, biometric, and secure-keypad gates always trigger AWAITING_TAKEOVER for human completion.",
                    "financialGatePolicy": "Irreversible payment/booking/transfer and price-drift require explicit user approval regardless of unrestricted mode.",
                }
            )
            return

        if route == "/workspace/list":
            qs = urllib.parse.parse_qs(parsed_url.query)
            sub_dir = (qs.get("path") or qs.get("sub_dir") or qs.get("prefix") or [""])[0]
            self._handle_workspace_route("/workspace/list", {"path": sub_dir})
            return

        if route.startswith("/bridge"):
            self._send_json({"status": "ok", "items": SYNCED_CLIPBOARD_QUEUE[-20:]})
            return

        self._send_json({"error": "Not found"}, status=404)

    def do_POST(self) -> None:
        if not self._check_request_security(require_json_content_type=True):
            return
        parsed_url = urllib.parse.urlparse(self.path)
        route = parsed_url.path

        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._send_json({"ok": False, "error": "Invalid Content-Length header"}, status=400)
            return

        if length < 0 or length > MAX_HTTP_BODY_BYTES:
            self._send_json({"ok": False, "error": "Payload too large"}, status=413)
            return

        raw_body = self.rfile.read(length).decode("utf-8", errors="replace") if length > 0 else "{}"
        try:
            parsed_json = json.loads(raw_body) if raw_body.strip() else {}
        except Exception:
            if route.startswith("/workspace/") or route == "/web/research":
                self._send_json({"ok": False, "error": "Invalid JSON body"}, status=400)
                return
            parsed_json = {}

        if not isinstance(parsed_json, dict):
            if route.startswith("/workspace/") or route == "/web/research":
                self._send_json({"ok": False, "error": "JSON payload must be an object"}, status=400)
                return
            parsed_json = {}
        payload = parsed_json

        if route in (
            "/workspace/list",
            "/workspace/read",
            "/workspace/write",
            "/workspace/edit",
            "/workspace/search",
            "/workspace/exec",
        ):
            self._handle_workspace_route(route, payload)
            return

        if route == "/web/research":
            query = str(payload.get("query") or "").strip()
            if not query:
                self._send_json({"ok": False, "error": "Field 'query' is required."}, status=400)
                return
            system_context = str(payload.get("system_context") or payload.get("systemContext") or "")
            chat_history = list(payload.get("chat_history") or payload.get("chatHistory") or [])
            try:
                result = run_web_research(
                    query=query,
                    system_context=system_context,
                    chat_history=chat_history,
                )
                self._send_json(result, status=200)
            except WorkspaceValidationError as exc:
                self._send_json({"ok": False, "error": str(exc)}, status=400)
            except BackendUnavailableError as exc:
                self._send_json({"ok": False, "error": str(exc)}, status=503)
            return

        if route.startswith("/decide"):
            goal = str(payload.get("goal", ""))
            iteration = int(payload.get("iteration", 1))
            active_pkg = str(payload.get("activePackage", ""))
            prompt_context = str(payload.get("promptContext", ""))
            history_steps = list(payload.get("historySteps") or [])
            chat_history = list(payload.get("chatHistory") or payload.get("chat_history") or [])
            som_b64 = payload.get("somScreenshot")
            backend = resolve_active_backend(CONFIGURED_BACKEND)
            print(
                f"[LaptopCLI v6.0] backend={backend} | iter={iteration} | pkg={active_pkg} | som={bool(som_b64)} | chat={len(chat_history)} | goal='{goal}'",
                flush=True,
            )
            try:
                decision = decide_turn(
                    goal,
                    iteration,
                    active_pkg,
                    prompt_context,
                    history_steps,
                    som_screenshot_b64=som_b64,
                    chat_history=chat_history,
                )
                self._send_json(decision, status=200)
            except BackendUnavailableError as exc:
                self._send_json({"ok": False, "error": str(exc)}, status=503)
            return

        if route.startswith("/bridge"):
            SYNCED_CLIPBOARD_QUEUE.append(payload)
            print(f"[LaptopCLI Sync] Received from phone: {str(payload)[:160]}", flush=True)
            self._send_json({"status": "ok", "queued": len(SYNCED_CLIPBOARD_QUEUE)})
            return

        self._send_json({"error": f"Unknown endpoint {route}"}, status=404)


def main() -> None:
    global CONFIGURED_BACKEND, CONFIGURED_MODEL, ALLOW_WORKSPACE_EXEC, BRIDGE_AUTH_TOKEN

    if len(sys.argv) > 1 and sys.argv[1] in ("send", "goal"):
        is_goal = sys.argv[1] == "goal"
        msg = " ".join(sys.argv[2:]) or "노트북 CLI에서 보낸 실시간 메시지입니다."
        body = json.dumps(
            {
                "role": "user" if is_goal else "assistant",
                "text": msg,
                "executeGoal": is_goal,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        headers = {"Content-Type": "application/json; charset=utf-8"}
        if ARTEMIS_TOKEN:
            headers["X-Artemis-Token"] = ARTEMIS_TOKEN
        req = urllib.request.Request(f"{PHONE_BRIDGE_URL}/chat", data=body, headers=headers, method="POST")
        with urllib.request.urlopen(req, timeout=8) as resp:
            print(resp.read().decode("utf-8"))
        return

    parser = argparse.ArgumentParser(description="Hanson Mobile Agent Studio v4.0 Coding-CLI & Workspace Bridge (:18889)")
    parser.add_argument(
        "--backend",
        choices=["auto", "claude", "gemini", "codex", "api", "heuristic"],
        default=CONFIGURED_BACKEND if CONFIGURED_BACKEND in ("auto", "claude", "gemini", "codex", "api", "heuristic") else "auto",
        help="Reasoning backend to use (default: auto-detect claude/gemini/codex CLI, then env API, then heuristic).",
    )
    parser.add_argument("--model", default=CONFIGURED_MODEL, help="Optional model override passed to the selected CLI/API.")
    parser.add_argument("--host", default=HOST, help="Bind host (default: 127.0.0.1).")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Bind port (default: 18889).")
    parser.add_argument(
        "--workspace-root",
        default=os.environ.get("HANSON_WORKSPACE_ROOT", DEFAULT_WORKSPACE_DIR),
        help="Sandbox root directory for /workspace/* routes (default: ./agent_workspace).",
    )
    parser.add_argument(
        "--allow-workspace-exec",
        action="store_true",
        default=False,
        help="Allow arbitrary non-chained commands inside /workspace/exec (default: False, safe read-only diagnostics only).",
    )
    parser.add_argument(
        "--token",
        default=BRIDGE_AUTH_TOKEN,
        help="Optional shared secret token enforced via X-Bridge-Token or Authorization: Bearer (or HANSON_BRIDGE_TOKEN env).",
    )
    parsed = parser.parse_args()

    CONFIGURED_BACKEND = parsed.backend
    CONFIGURED_MODEL = parsed.model
    ALLOW_WORKSPACE_EXEC = bool(parsed.allow_workspace_exec)
    BRIDGE_AUTH_TOKEN = (parsed.token or "").strip()
    configure_workspace_root(parsed.workspace_root, create=True)
    active = resolve_active_backend(CONFIGURED_BACKEND)
    clis = detect_available_clis()

    server = ThreadingHTTPServer((parsed.host, parsed.port), LaptopCliBridgeHandler)
    print(
        f"[LaptopCLI v4.0] Listening on http://{parsed.host}:{server.server_port} | backend={CONFIGURED_BACKEND} (resolved={active}) | detected_clis={clis} | workspace={get_safe_workspace_label()} | allow_workspace_exec={ALLOW_WORKSPACE_EXEC}",
        flush=True,
    )
    print("  Tip: Run `adb reverse tcp:18889 tcp:18889` so your Android phone reaches this bridge over USB.", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
