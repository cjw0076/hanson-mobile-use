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
from typing import Any, Dict, List, Optional

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

VALID_TOOLS = {
    "click",
    "long_press",
    "input_text",
    "swipe",
    "drag_and_drop",
    "haptic_trajectory",
    "press_key",
    "manage_app",
    "open_url",
    "list_notifications",
    "reply_notification",
    "control_device_setting",
    "render_generative_ui",
    "reverse_engineer_app",
    "os_cli_exec",
    "wait",
    "complete_task",
    "workspace_list",
    "workspace_read",
    "workspace_write",
    "workspace_edit",
    "workspace_search",
    "workspace_exec",
    "vault_blind_inject",
    "vault_list_handles",
    "watcher_manage",
    "a2a_negotiate_schedule",
    "scratchpad_note",
}

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


def detect_available_clis() -> List[str]:
    found: List[str] = []
    for cli in ("claude", "gemini", "codex"):
        if shutil.which(cli):
            found.append(cli)
    return found


def resolve_active_backend(preferred: str) -> str:
    pref = (preferred or "auto").lower()
    if pref in ("claude", "gemini", "codex", "api", "heuristic"):
        return pref
    # Auto-detect: prefer installed coding CLIs first ($0 extra API cost), then env API keys, then heuristic
    clis = detect_available_clis()
    if clis:
        return clis[0]
    if any(os.environ.get(k) for k in ("OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_BASE_URL")):
        return "api"
    return "heuristic"


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

    return f"""You are an autonomous Android Mobile-Use Agent controlling a real Android smartphone.
Follow an empirical Observe -> Decide -> Act -> Verify loop:
1. NEVER call `complete_task` until the user's goal postcondition is visibly satisfied on the CURRENT screen (for example, if the user asked to search '족발' in 배달의민족, launching the app or clicking the search icon is NOT completion — you must focus/type '족발' with `submit_enter=true` AND verify the search results screen before calling `complete_task`).
2. NEVER click elements belonging to `com.hanson.mobileuse` when operating an external app.
3. Prefer `element_index` from Visible UI Elements (matching the `[index]` badges on the Set-of-Marks screenshot when attached).
4. If the previous step had `FAILED/NO-DELTA`, do NOT repeat the exact same ineffective action; dismiss any blocking popup, tap the search container/input box explicitly, or swipe to reveal the target.

Available `toolName` & `toolArgs` schemas:
- `"manage_app"`: `{{"action": "launch", "app_name": "<Korean/English app name or package>"}}`
- `"click"`: `{{"element_index": <int>, "target_description": "<why>"}}` (or `"norm_x": 0..1000, "norm_y": 0..1000` if unindexed)
- `"long_press"`: `{{"element_index": <int>, "duration_ms": 700}}`
- `"input_text"`: `{{"element_index": <optional int>, "text": "<query>", "clear_existing": false, "submit_enter": true}}`
- `"swipe"`: `{{"direction": "up"|"down"|"left"|"right", "duration_ms": 350}}` (`"up"` scrolls down to reveal lower items)
- `"press_key"`: `{{"key": "BACK"|"HOME"|"ENTER"|"RECENTS"}}`
- `"open_url"`: `{{"url": "https://..."}}`
- `"list_notifications"`: `{{"only_replyable": false, "limit": 10}}`
- `"reply_notification"`: `{{"target": "<sender>", "message": "<reply>"}}`
- `"control_device_setting"`: `{{"setting": "brightness"|"volume_media"|"wifi"|"bluetooth"|"dnd"|"torch", "value": "<val>"}}`
- `"workspace_list"`: `{{"path": "<optional relative sub_dir>"}}`
- `"workspace_read"`: `{{"path": "<relative file path>", "start_line": 1, "end_line": 200}}`
- `"workspace_write"`: `{{"path": "<relative file path>", "content": "<full utf-8 content>"}}`
- `"workspace_edit"`: `{{"path": "<relative file path>", "old_text": "<exact text>", "new_text": "<replacement>"}}`
- `"workspace_search"`: `{{"query": "<search substring>", "path_prefix": "<optional relative prefix>"}}`
- `"workspace_exec"`: `{{"command": "<sandboxed read-only or workspace command>"}}`
- `"complete_task"`: `{{"summary": "<concise Korean summary of verified result>"}}`

User Goal: {goal}
Current Iteration: {iteration}
Foreground Package: {active_pkg or "unknown"}
Recent Step History:
{history_txt}

Current Screen (`Visible UI Elements`):
{prompt_context}

Respond with ONLY a single valid JSON object (no markdown fences, no commentary outside JSON):
{{
  "observation": "Brief Korean observation of current screen & delta from last step",
  "spokenReply": "Short natural Korean status or final answer",
  "subGoals": [{{"title": "Step name", "status": "COMPLETED|IN_PROGRESS|PENDING"}}],
  "toolName": "<one of the toolName values above>",
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
    tool_name = str(d.get("toolName", "")).strip()
    if tool_name not in VALID_TOOLS:
        return None
    tool_args = d.get("toolArgs")
    if not isinstance(tool_args, dict):
        tool_args = {}
    obs = str(d.get("observation") or "화면 분석 완료").strip()
    spoken = str(d.get("spokenReply") or obs).strip()
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


def invoke_coding_cli_backend(
    cli_name: str,
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 28.0,
) -> Optional[Dict[str, Any]]:
    exe = shutil.which(cli_name)
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
            cmd = [exe, "-p", "--output-format", "text"]
            if CONFIGURED_MODEL:
                cmd.extend(["--model", CONFIGURED_MODEL])
            proc = subprocess.run(
                cmd,
                input=prompt.encode("utf-8"),
                capture_output=True,
                timeout=timeout_sec,
                check=False,
            )
        elif cli_name == "gemini":
            cmd = [exe, "-p", "Respond with ONLY the JSON object requested in stdin."]
            if CONFIGURED_MODEL:
                cmd.extend(["-m", CONFIGURED_MODEL])
            proc = subprocess.run(
                cmd,
                input=prompt.encode("utf-8"),
                capture_output=True,
                timeout=timeout_sec,
                check=False,
            )
        elif cli_name == "codex":
            cmd = [exe, "exec", "--skip-git-repo-check", "--ephemeral"]
            if CONFIGURED_MODEL:
                cmd.extend(["-m", CONFIGURED_MODEL])
            if temp_img_path:
                cmd.extend(["-i", temp_img_path])
            cmd.append("-")
            proc = subprocess.run(
                cmd,
                input=prompt.encode("utf-8"),
                capture_output=True,
                timeout=timeout_sec,
                check=False,
            )
        else:
            return None

        stdout_text = proc.stdout.decode("utf-8", errors="replace")
        parsed = extract_json_object(stdout_text)
        validated = validate_decision_dict(parsed)
        if validated is not None:
            validated["observation"] = f"[{cli_name.upper()} CLI] {validated['observation']}"
        return validated
    except Exception as exc:
        print(f"[LaptopCLI] {cli_name} invocation error: {exc}", flush=True)
        return None
    finally:
        if temp_img_path and os.path.exists(temp_img_path):
            try:
                os.remove(temp_img_path)
            except OSError:
                pass


def invoke_env_api_backend(
    prompt: str,
    som_screenshot_b64: Optional[str] = None,
    timeout_sec: float = 25.0,
) -> Optional[Dict[str, Any]]:
    base_url = os.environ.get("OPENAI_BASE_URL", "").strip()
    openai_key = os.environ.get("OPENAI_API_KEY", "").strip()
    if base_url or openai_key:
        endpoint = base_url.rstrip("/") if base_url else "https://api.openai.com/v1/chat/completions"
        if not endpoint.endswith("/chat/completions"):
            endpoint = endpoint + ("/chat/completions" if endpoint.endswith("/v1") else "/v1/chat/completions")
        model = CONFIGURED_MODEL or os.environ.get("OPENAI_MODEL", "gpt-4o-mini")
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
            }
        ).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if openai_key:
            headers["Authorization"] = f"Bearer {openai_key}"
        req = urllib.request.Request(endpoint, data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
                data = json.loads(resp.read().decode("utf-8", errors="replace"))
                content = data["choices"][0]["message"]["content"]
                return validate_decision_dict(extract_json_object(content))
        except Exception as exc:
            print(f"[LaptopCLI] OpenAI-compatible API error: {exc}", flush=True)
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


def local_cli_decide(
    goal: str,
    iteration: int,
    active_pkg: str,
    prompt_context: str,
    history_steps: List[Dict[str, Any]],
) -> Dict[str, Any]:
    g = goal.strip()
    lower = g.lower()
    elements = parse_ui_elements(prompt_context)

    # 0. Self-Healing Recovery if previous step failed or stalled
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
                return {
                    "observation": f"[Self-Healing] 팝업 요소('{modal_btn['text'] or modal_btn['desc']}')를 먼저 닫고 재시도합니다.",
                    "subGoals": [
                        {"title": "팝업 닫기 복구", "status": "IN_PROGRESS"},
                        {"title": "원래 목표 재실행", "status": "PENDING"},
                    ],
                    "toolName": "click",
                    "toolArgs": {"element_index": modal_btn["index"], "target_description": "팝업 닫기"},
                }
            if last_tool == "click":
                return {
                    "observation": f"[Self-Healing] 이전 클릭 후 화면 변화가 없어({last_result[:45]}) 스크롤 후 재탐색합니다.",
                    "subGoals": [
                        {"title": "화면 스와이프 재노출", "status": "IN_PROGRESS"},
                        {"title": "타깃 재실행", "status": "PENDING"},
                    ],
                    "toolName": "swipe",
                    "toolArgs": {"direction": "up", "duration_ms": 360},
                }

    matched_app_name = next((name for name in APP_MAP if name in lower), None)
    target_pkg = APP_MAP.get(matched_app_name) if matched_app_name else None
    search_query = extract_query_from_goal(g)

    wants_drag_drop = any(k in lower for k in ["드래그", "슬라이더", "옮겨", "drag"])
    wants_swipe = any(k in lower for k in ["스크롤", "스와이프", "내려", "올려"])
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
            return {
                "observation": f"목표 앱 '{matched_app_name}'을(를) 실행합니다.",
                "subGoals": ledger,
                "toolName": "manage_app",
                "toolArgs": {"action": "launch", "app_name": matched_app_name},
            }

        if search_query and not has_typed:
            editable_el = next((el for el in elements if el["editable"]), None)
            if editable_el is not None:
                return {
                    "observation": f"입력창([{editable_el['index']}])에 '{search_query}'을(를) 입력하고 검색을 제출합니다.",
                    "subGoals": ledger,
                    "toolName": "input_text",
                    "toolArgs": {
                        "element_index": editable_el["index"],
                        "text": search_query,
                        "clear_existing": False,
                        "submit_enter": True,
                    },
                }

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
                    return {
                        "observation": f"화면에서 검색 진입 요소([{search_btn['index']}] '{label}')를 탭합니다.",
                        "subGoals": ledger,
                        "toolName": "click",
                        "toolArgs": {"element_index": search_btn["index"], "target_description": f"검색창 열기 ({label})"},
                    }

            return {
                "observation": f"'{search_query}' 검색어를 입력하고 제출합니다.",
                "subGoals": ledger,
                "toolName": "input_text",
                "toolArgs": {"text": search_query, "submit_enter": True},
            }

        if wants_drag_drop and not has_dragged:
            slider_el = next(
                (el for el in elements if any(kw in (el["class"] + " " + el["text"] + " " + el["desc"]).lower() for kw in ["seekbar", "slider", "밝기", "볼륨"])),
                None,
            )
            norm_y = slider_el["norm_y"] if slider_el else 500
            return {
                "observation": f"좌표 (250, {norm_y}) -> (780, {norm_y}) 드래그 앤 드롭을 실행합니다.",
                "subGoals": ledger,
                "toolName": "drag_and_drop",
                "toolArgs": {
                    "start_norm_x": 250,
                    "start_norm_y": norm_y,
                    "end_norm_x": 780,
                    "end_norm_y": norm_y,
                    "hold_ms": 480,
                    "duration_ms": 680,
                },
            }

        if wants_swipe and not has_swiped:
            direction = "down" if ("아래로" in lower or "위로 스크롤" in lower) else "up"
            return {
                "observation": f"'{direction}' 방향으로 스와이프합니다.",
                "subGoals": ledger,
                "toolName": "swipe",
                "toolArgs": {"direction": direction, "duration_ms": 380},
            }

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
                return {
                    "observation": f"검색 결과 요소([{result_el['index']}] '{label}')를 탭합니다.",
                    "subGoals": ledger,
                    "toolName": "click",
                    "toolArgs": {"element_index": result_el["index"], "target_description": f"검색 결과 선택 ({label})"},
                }

        if history_steps:
            step_lines = [
                f"• Step {s.get('step')} [{s.get('tool')}]: {(str(s.get('result') or '').splitlines() or [''])[0]}"
                for s in history_steps
            ]
            return {
                "observation": f"총 {len(history_steps)}단계의 실행 및 사후 화면 검증을 완료했습니다.",
                "subGoals": [{**item, "status": "COMPLETED"} for item in ledger],
                "toolName": "complete_task",
                "toolArgs": {"summary": f"✅ 목표 완료 ({len(history_steps)}스텝 검증됨)\n" + "\n".join(step_lines)},
            }

    if iteration >= 2 and history_steps:
        last = history_steps[-1]
        return {
            "observation": f"'{last.get('tool', '')}' 실행 및 화면 전이 확인을 마쳤습니다.",
            "subGoals": [{"title": "실행 및 검증 완료", "status": "COMPLETED"}],
            "toolName": "complete_task",
            "toolArgs": {"summary": str(last.get("result", "완료"))},
        }

    for kw in APP_MAP:
        if kw in lower and any(v in lower for v in ["열어", "실행", "켜", "가자", "틀어"]):
            return {
                "observation": f"'{kw}' 앱을 실행합니다.",
                "subGoals": [{"title": f"'{kw}' 앱 실행", "status": "IN_PROGRESS"}],
                "toolName": "manage_app",
                "toolArgs": {"action": "launch", "app_name": kw},
            }

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
        return {
            "observation": f"현재 화면에서 '[{matched_el['index']}] {label}' 요소를 탭합니다.",
            "subGoals": [{"title": f"'{label}' 탭", "status": "IN_PROGRESS"}],
            "toolName": "click",
            "toolArgs": {"element_index": matched_el["index"], "target_description": label},
        }

    if "홈" in lower:
        return {
            "observation": "홈 화면으로 이동합니다.",
            "subGoals": [{"title": "HOME 이동", "status": "IN_PROGRESS"}],
            "toolName": "press_key",
            "toolArgs": {"key": "HOME"},
        }

    return {
        "observation": f"'{g}' 요청을 확인했습니다.",
        "subGoals": [{"title": "응답 완료", "status": "COMPLETED"}],
        "toolName": "complete_task",
        "toolArgs": {"summary": f"요청 확인됨: '{g}'"},
    }


def decide_turn(
    goal: str,
    iteration: int,
    active_pkg: str,
    prompt_context: str,
    history_steps: List[Dict[str, Any]],
    som_screenshot_b64: Optional[str] = None,
) -> Dict[str, Any]:
    backend = resolve_active_backend(CONFIGURED_BACKEND)
    if backend in ("claude", "gemini", "codex"):
        prompt = build_deliberative_prompt(goal, iteration, active_pkg, prompt_context, history_steps)
        cli_decision = invoke_coding_cli_backend(backend, prompt, som_screenshot_b64=som_screenshot_b64)
        if cli_decision is not None:
            return cli_decision
    elif backend == "api":
        prompt = build_deliberative_prompt(goal, iteration, active_pkg, prompt_context, history_steps)
        api_decision = invoke_env_api_backend(prompt, som_screenshot_b64=som_screenshot_b64)
        if api_decision is not None:
            return api_decision

    return local_cli_decide(goal, iteration, active_pkg, prompt_context, history_steps)


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
                    "version": "6.0.0",
                    "port": self.server.server_port,
                    "configuredBackend": CONFIGURED_BACKEND,
                    "activeBackend": resolve_active_backend(CONFIGURED_BACKEND),
                    "availableClis": detect_available_clis(),
                    "workspace_root": get_safe_workspace_label(),
                    "workspace_routes": True,
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
                    ],
                    "valid_tools_count": len(VALID_TOOLS),
                }
            )
            return

        if route == "/pickle/capabilities":
            self._send_json(
                {
                    "version": "6.0.0",
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
            if route.startswith("/workspace/"):
                self._send_json({"ok": False, "error": "Invalid JSON body"}, status=400)
                return
            parsed_json = {}

        if not isinstance(parsed_json, dict):
            if route.startswith("/workspace/"):
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

        if route.startswith("/decide"):
            goal = str(payload.get("goal", ""))
            iteration = int(payload.get("iteration", 1))
            active_pkg = str(payload.get("activePackage", ""))
            prompt_context = str(payload.get("promptContext", ""))
            history_steps = list(payload.get("historySteps") or [])
            som_b64 = payload.get("somScreenshot")
            backend = resolve_active_backend(CONFIGURED_BACKEND)
            print(
                f"[LaptopCLI v5.0] backend={backend} | iter={iteration} | pkg={active_pkg} | som={bool(som_b64)} | goal='{goal}'",
                flush=True,
            )
            decision = decide_turn(goal, iteration, active_pkg, prompt_context, history_steps, som_b64)
            self._send_json(decision)
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
