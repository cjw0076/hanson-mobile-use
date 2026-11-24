#!/usr/bin/env python3
"""Composable Lifecycle Decorator Pipeline for Hanson Mobile-Use.

Absorbed from `google/device-infra` (`LifecycleDecorator.java`,
`AndroidLogcatMonitoringDecorator.java`, `AndroidBugreportDecorator.java`,
`AndroidHdVideoDecorator.java`), `mobile-dev-harness` (`mdh verify`), and
`quern-dev/quern` (`per-action` gauges).

Features (100% Python 3.9+ stdlib, zero external pip deps):
- `LifecycleDecorator` + `DecoratorChain`: `setUp -> CONTINUE|SKIP -> run -> guaranteed finally tearDown`
  in reverse order, strictly preserving any primary exception while capturing teardown errors.
- `LogcatCrashMonitor`: detects `FATAL EXCEPTION`, `ANR in`, native tombstones
  (`Build fingerprint:` / `*** *** ***`), and crash dialogs.
- `OnFailureBugreport`: captures bugreport artifacts ONLY on primary failure or detected crash.
- `RollingScreenRecord`: ring buffer keeping only the last 3 clips (retained only on failure).
- `PerActionGaugeSampler`: parses CPU %, memory PSS MB, and `dumpsys gfxinfo` jank % per action.
- `export_junit_xml(...)`: CI-ready JUnit XML report with crash/ANR traces.
"""
from __future__ import annotations

import enum
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Deque, Dict, List, Optional, Sequence


class SetupDecision(str, enum.Enum):
    CONTINUE = "CONTINUE"
    SKIP = "SKIP"


@dataclass
class CrashEvent:
    kind: str  # FATAL_EXCEPTION | ANR | NATIVE_TOMBSTONE | CRASH_DIALOG
    summary: str
    lines: List[str] = field(default_factory=list)
    timestamp: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "kind": self.kind,
            "summary": self.summary,
            "lines": list(self.lines),
            "timestamp": self.timestamp,
        }


@dataclass
class ActionGauge:
    step_index: int
    action_name: str
    cpu_pct: float = 0.0
    mem_pss_mb: float = 0.0
    janky_pct: float = 0.0
    total_frames: int = 0
    janky_frames: int = 0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "stepIndex": self.step_index,
            "actionName": self.action_name,
            "cpuPct": round(self.cpu_pct, 2),
            "memPssMb": round(self.mem_pss_mb, 2),
            "jankyPct": round(self.janky_pct, 2),
            "totalFrames": self.total_frames,
            "jankyFrames": self.janky_frames,
        }


@dataclass
class DecoratorContext:
    run_id: str
    serial: Optional[str] = None
    package_name: Optional[str] = None
    output_dir: Optional[Path] = None
    skipped: bool = False
    skip_reason: str = ""
    crashes: List[CrashEvent] = field(default_factory=list)
    gauges: List[ActionGauge] = field(default_factory=list)
    artifacts: Dict[str, Any] = field(default_factory=dict)
    teardown_errors: List[Dict[str, str]] = field(default_factory=list)
    events: List[str] = field(default_factory=list)


class LifecycleDecorator:
    """Base lifecycle decorator (`setUp -> CONTINUE|SKIP -> run -> guaranteed finally tearDown`)."""

    name: str = "LifecycleDecorator"

    def setUp(self, ctx: DecoratorContext) -> SetupDecision:
        return SetupDecision.CONTINUE

    def tearDown(self, ctx: DecoratorContext, primary_error: Optional[BaseException]) -> None:
        return None


class DecoratorChain:
    """Executes decorators around a target callable while preserving primary exceptions."""

    def __init__(self, decorators: Sequence[LifecycleDecorator]):
        self.decorators: List[LifecycleDecorator] = list(decorators)

    def run(self, fn: Callable[[DecoratorContext], Any], ctx: DecoratorContext) -> Any:
        entered: List[LifecycleDecorator] = []
        primary_error: Optional[BaseException] = None
        result: Any = None
        try:
            for dec in self.decorators:
                decision = dec.setUp(ctx)
                entered.append(dec)
                ctx.events.append(f"setUp:{dec.name}:{decision.value}")
                if decision == SetupDecision.SKIP:
                    ctx.skipped = True
                    if not ctx.skip_reason:
                        ctx.skip_reason = f"Skipped by {dec.name}"
                    return None
            result = fn(ctx)
            return result
        except BaseException as exc:
            primary_error = exc
            raise
        finally:
            for dec in reversed(entered):
                try:
                    dec.tearDown(ctx, primary_error)
                    ctx.events.append(f"tearDown:{dec.name}:OK")
                except Exception as td_exc:
                    ctx.teardown_errors.append({
                        "decorator": dec.name,
                        "error": f"{td_exc.__class__.__name__}: {td_exc}",
                    })
                    ctx.events.append(f"tearDown:{dec.name}:ERR")
            # Primary exception (if any) propagates intact without being masked by teardown errors.


# =============================================================================
# 1. Logcat Crash / ANR / Native Tombstone Monitor
# =============================================================================

_FATAL_RE = re.compile(r"FATAL EXCEPTION:\s*(.*)")
_ANR_RE = re.compile(r"ANR in\s+([^\s(]+.*)")
_TOMBSTONE_RE = re.compile(r"(?:\*\*\* \*\*\* \*\*\* \*\*\* \*\*\* \*\*\*|Build fingerprint:\s*'[^']+')")
_CRASH_DIALOG_RE = re.compile(r"(?:Showing crash dialog for package|Force finishing activity)\s+([^\s]+)")
_LOGCAT_TS_RE = re.compile(r"^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})")


def _is_crash_header(line: str) -> bool:
    return bool(
        _FATAL_RE.search(line)
        or _ANR_RE.search(line)
        or _TOMBSTONE_RE.search(line)
        or _CRASH_DIALOG_RE.search(line)
    )


def _collect_crash_snippet(lines: List[str], start_idx: int, max_context_lines: int) -> List[str]:
    snippet = [lines[start_idx]]
    limit = min(len(lines), start_idx + max(1, max_context_lines))
    j = start_idx + 1
    while j < limit:
        if _is_crash_header(lines[j]):
            break
        snippet.append(lines[j])
        j += 1
    return snippet


def parse_logcat_crashes(raw_logcat: str, max_context_lines: int = 8) -> List[CrashEvent]:
    """Parse `adb logcat -v threadtime` text into structured FATAL/ANR/Tombstone/Dialog CrashEvents."""
    lines = (raw_logcat or "").splitlines()
    crashes: List[CrashEvent] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        ts_m = _LOGCAT_TS_RE.match(line.strip())
        ts = ts_m.group(1) if ts_m else ""

        m_fatal = _FATAL_RE.search(line)
        if m_fatal:
            snippet = _collect_crash_snippet(lines, i, max_context_lines)
            crashes.append(CrashEvent(
                kind="FATAL_EXCEPTION",
                summary=f"FATAL EXCEPTION: {m_fatal.group(1).strip()}",
                lines=snippet,
                timestamp=ts,
            ))
            i += len(snippet)
            continue

        m_anr = _ANR_RE.search(line)
        if m_anr:
            snippet = _collect_crash_snippet(lines, i, max_context_lines)
            crashes.append(CrashEvent(
                kind="ANR",
                summary=f"ANR in {m_anr.group(1).strip()}",
                lines=snippet,
                timestamp=ts,
            ))
            i += len(snippet)
            continue

        if _TOMBSTONE_RE.search(line):
            snippet = _collect_crash_snippet(lines, i, max_context_lines)
            crashes.append(CrashEvent(
                kind="NATIVE_TOMBSTONE",
                summary="Native crash tombstone detected",
                lines=snippet,
                timestamp=ts,
            ))
            i += len(snippet)
            continue

        m_dlg = _CRASH_DIALOG_RE.search(line)
        if m_dlg:
            crashes.append(CrashEvent(
                kind="CRASH_DIALOG",
                summary=f"Crash dialog: {m_dlg.group(1).strip()}",
                lines=[line],
                timestamp=ts,
            ))
        i += 1
    return crashes


class LogcatCrashMonitor(LifecycleDecorator):
    """Captures and scans logcat for FATAL EXCEPTION, ANR, native tombstones, and crash dialogs."""

    name = "LogcatCrashMonitor"

    def __init__(self, logcat_provider: Optional[Callable[[Optional[str]], str]] = None):
        self._provider = logcat_provider

    def _fetch_logcat(self, serial: Optional[str]) -> str:
        if self._provider is not None:
            return self._provider(serial)
        cmd = ["adb"]
        if serial:
            cmd.extend(["-s", serial])
        cmd.extend(["logcat", "-d", "-v", "threadtime", "-t", "500"])
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=10, check=False)
        return proc.stdout or ""

    def setUp(self, ctx: DecoratorContext) -> SetupDecision:
        ctx.artifacts["logcat_started_at"] = time.time()
        return SetupDecision.CONTINUE

    def tearDown(self, ctx: DecoratorContext, primary_error: Optional[BaseException]) -> None:
        raw = self._fetch_logcat(ctx.serial)
        found = parse_logcat_crashes(raw)
        if found:
            ctx.crashes.extend(found)
            ctx.artifacts["crash_count"] = len(ctx.crashes)


# =============================================================================
# 2. On-Failure Bugreport Collector
# =============================================================================

class OnFailureBugreport(LifecycleDecorator):
    """Captures a bugreport artifact ONLY when the run fails or a crash/ANR is detected."""

    name = "OnFailureBugreport"

    def __init__(self, collector: Optional[Callable[[Optional[str], Path], Path]] = None):
        self._collector = collector

    def tearDown(self, ctx: DecoratorContext, primary_error: Optional[BaseException]) -> None:
        if primary_error is None and not ctx.crashes:
            return
        out_dir = ctx.output_dir or Path(".")
        out_dir.mkdir(parents=True, exist_ok=True)
        target = out_dir / f"bugreport-{ctx.run_id}.zip"
        if self._collector is not None:
            saved = self._collector(ctx.serial, target)
            ctx.artifacts["bugreport"] = str(saved)
            return
        cmd = ["adb"]
        if ctx.serial:
            cmd.extend(["-s", ctx.serial])
        cmd.extend(["bugreport", str(target)])
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60, check=False)
        if proc.returncode == 0 and target.is_file():
            ctx.artifacts["bugreport"] = str(target)


# =============================================================================
# 3. Rolling Screen Record (Ring Buffer of Last 3 Clips)
# =============================================================================

class RollingScreenRecord(LifecycleDecorator):
    """Maintains a ring buffer of the last `max_clips` (default 3) screen-record clips."""

    name = "RollingScreenRecord"

    def __init__(self, max_clips: int = 3):
        self.max_clips = max(1, int(max_clips))
        self._clips: Deque[Path] = deque()

    @property
    def clips(self) -> List[Path]:
        return list(self._clips)

    def push_clip(self, clip_path: Path) -> Optional[Path]:
        """Record a new clip path; evicts & unlinks the oldest clip if ring exceeds `max_clips`."""
        evicted: Optional[Path] = None
        if len(self._clips) >= self.max_clips:
            evicted = self._clips.popleft()
            try:
                if evicted.is_file():
                    evicted.unlink()
            except OSError:
                pass
        self._clips.append(Path(clip_path))
        return evicted

    def setUp(self, ctx: DecoratorContext) -> SetupDecision:
        ctx.artifacts["rolling_clips_limit"] = self.max_clips
        return SetupDecision.CONTINUE

    def tearDown(self, ctx: DecoratorContext, primary_error: Optional[BaseException]) -> None:
        if primary_error is not None or ctx.crashes:
            ctx.artifacts["retained_clips"] = [str(p) for p in self._clips]
            return
        # Clean run: release temporary clips to avoid disk bloat
        for p in list(self._clips):
            try:
                if p.is_file():
                    p.unlink()
            except OSError:
                pass
        self._clips.clear()
        ctx.artifacts["retained_clips"] = []


# =============================================================================
# 4. Per-Action Gauge Sampler (CPU / Memory PSS / Jank Frames)
# =============================================================================

def parse_dumpsys_gfxinfo(raw: str) -> Dict[str, Any]:
    total_m = re.search(r"Total frames rendered:\s*(\d+)", raw or "")
    janky_m = re.search(r"Janky frames:\s*(\d+)\s*\(([0-9.]+)%\)", raw or "")
    total = int(total_m.group(1)) if total_m else 0
    janky = int(janky_m.group(1)) if janky_m else 0
    pct = float(janky_m.group(2)) if janky_m else (round(janky * 100.0 / total, 2) if total > 0 else 0.0)
    return {"total_frames": total, "janky_frames": janky, "janky_pct": pct}


def parse_dumpsys_meminfo(raw: str) -> float:
    m = re.search(r"TOTAL\s+PSS:\s*(\d+)", raw or "") or re.search(r"TOTAL\s+(\d+)\s+\d+", raw or "")
    if not m:
        return 0.0
    return round(int(m.group(1)) / 1024.0, 2)


class PerActionGaugeSampler(LifecycleDecorator):
    """Samples CPU %, memory PSS MB, and janky-frame % per agent action."""

    name = "PerActionGaugeSampler"

    def __init__(
        self,
        sampler_fn: Optional[Callable[[Optional[str], Optional[str]], Dict[str, Any]]] = None,
    ):
        self._sampler_fn = sampler_fn

    def sample(self, ctx: DecoratorContext, step_index: int, action_name: str) -> ActionGauge:
        if self._sampler_fn is not None:
            raw = self._sampler_fn(ctx.serial, ctx.package_name) or {}
            gauge = ActionGauge(
                step_index=step_index,
                action_name=action_name,
                cpu_pct=float(raw.get("cpu_pct", 0.0)),
                mem_pss_mb=float(raw.get("mem_pss_mb", 0.0)),
                janky_pct=float(raw.get("janky_pct", 0.0)),
                total_frames=int(raw.get("total_frames", 0)),
                janky_frames=int(raw.get("janky_frames", 0)),
            )
        else:
            gauge = ActionGauge(step_index=step_index, action_name=action_name)
        ctx.gauges.append(gauge)
        return gauge

    def tearDown(self, ctx: DecoratorContext, primary_error: Optional[BaseException]) -> None:
        if ctx.gauges:
            ctx.artifacts["max_janky_pct"] = max(g.janky_pct for g in ctx.gauges)
            ctx.artifacts["max_mem_pss_mb"] = max(g.mem_pss_mb for g in ctx.gauges)


# =============================================================================
# 5. JUnit XML CI Exporter
# =============================================================================

def export_junit_xml(
    cases: Sequence[Dict[str, Any]],
    suite_name: str = "hanson-mobile-use",
    path: Optional[Path] = None,
) -> str:
    """Export structured run/eval cases (with optional crash/ANR traces) to valid JUnit XML."""
    total = len(cases)
    failures = sum(1 for c in cases if not c.get("passed", False) and not c.get("skipped", False))
    skipped = sum(1 for c in cases if c.get("skipped", False))
    total_time = sum(float(c.get("duration_s", 0.0)) for c in cases)

    root = ET.Element("testsuites")
    suite = ET.SubElement(
        root,
        "testsuite",
        attrib={
            "name": suite_name,
            "tests": str(total),
            "failures": str(failures),
            "skipped": str(skipped),
            "time": f"{total_time:.3f}",
        },
    )

    for case in cases:
        tc = ET.SubElement(
            suite,
            "testcase",
            attrib={
                "classname": str(case.get("classname", suite_name)),
                "name": str(case.get("name", "unnamed")),
                "time": f"{float(case.get('duration_s', 0.0)):.3f}",
            },
        )
        if case.get("skipped"):
            sk = ET.SubElement(tc, "skipped")
            sk.set("message", str(case.get("skip_reason") or "skipped"))
        elif not case.get("passed", False):
            fl = ET.SubElement(tc, "failure")
            fl.set("message", str(case.get("failure_message") or "step failed"))
            details = str(case.get("failure_detail") or "")
            if details:
                fl.text = details

        crashes = case.get("crashes") or []
        if crashes:
            se = ET.SubElement(tc, "system-err")
            blocks = []
            for cr in crashes:
                if isinstance(cr, CrashEvent):
                    cr = cr.to_dict()
                blocks.append(f"[{cr.get('kind', 'CRASH')}] {cr.get('summary', '')}\n" + "\n".join(cr.get("lines") or []))
            se.text = "\n---\n".join(blocks)

    xml_str = '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode")
    if path is not None:
        Path(path).write_text(xml_str, encoding="utf-8")
    return xml_str
