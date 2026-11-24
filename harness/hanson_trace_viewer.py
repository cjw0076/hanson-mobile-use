#!/usr/bin/env python3
"""Self-contained Offline HTML Trace Replay Viewer & JUnit XML Exporter (0% Saturation).

Absorbed from `PhoneHarness/PhoneHarness` (`scripts/trace2html.py` + `journal.py`),
`google/device-infra`, `quern-dev/quern`, and `mobile-dev-harness`.

Converts a Hanson `AgentRunJournal` JSON or `.jsonl` trace into:
- An auditable, XSS-escaped 0%-saturation Paper/Carbon monochrome HTML timeline
  with embedded grayscale step screenshots, correlated logcat crash/ANR frames,
  per-action CPU/Memory/Jank gauges, and safety gate badges.
- Optional CI-ready JUnit XML (`render_junit_xml` / `--junit <out.xml>`).

Zero external dependencies (Python 3.9+ stdlib only).
"""
from __future__ import annotations

import html
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Dict, List, Optional

_SAFE_DATA_URI_RE = re.compile(r"^data:image/(?:png|jpeg|jpg|webp);base64,[A-Za-z0-9+/=\s]+$")
_RAW_B64_RE = re.compile(r"^[A-Za-z0-9+/=\s]{16,}$")


def load_journal(path: Path) -> Dict[str, Any]:
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        raise ValueError(f"Empty trace file: {path}")
    if path.suffix.lower() == ".jsonl" or ("\n" in raw and not raw.startswith("{")):
        steps: List[Dict[str, Any]] = []
        for idx, line in enumerate(raw.splitlines()):
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            obj.setdefault("index", idx)
            steps.append(obj)
        return {
            "schemaVersion": "hanson-journal-v1",
            "runId": path.stem,
            "userGoal": steps[0].get("userGoal", path.stem) if steps else path.stem,
            "providerModel": steps[0].get("providerModel", "unknown") if steps else "unknown",
            "succeeded": all(s.get("succeeded", True) for s in steps),
            "calledTools": sorted({s.get("toolName", "") for s in steps if s.get("toolName")}),
            "steps": steps,
            "finalConclusion": steps[-1].get("observationSummary", "") if steps else "",
        }
    return json.loads(raw)


def _safe_screenshot_src(step: Dict[str, Any]) -> Optional[str]:
    uri = str(step.get("screenshotDataUri") or "").strip()
    if uri and _SAFE_DATA_URI_RE.match(uri):
        return uri
    b64 = str(step.get("screenshotBase64") or "").strip()
    if b64 and _RAW_B64_RE.match(b64):
        mime = str(step.get("screenshotMimeType") or "image/png").strip().lower()
        if mime not in ("image/png", "image/jpeg", "image/webp"):
            mime = "image/png"
        return f"data:{mime};base64,{''.join(b64.split())}"
    return None


def _render_gauge_bar(gauge: Any) -> str:
    if not isinstance(gauge, dict):
        return ""
    cpu = html.escape(str(gauge.get("cpuPct", gauge.get("cpu_pct", 0))))
    mem = html.escape(str(gauge.get("memPssMb", gauge.get("mem_pss_mb", 0))))
    jank = html.escape(str(gauge.get("jankyPct", gauge.get("janky_pct", 0))))
    return (
        '<div class="gauge-row">'
        f'<span class="pill">CPU {cpu}%</span>'
        f'<span class="pill">MEM {mem} MB</span>'
        f'<span class="pill">JANK {jank}%</span>'
        "</div>"
    )


def _render_crash_box(crash: Any) -> str:
    if not isinstance(crash, dict):
        return ""
    kind = html.escape(str(crash.get("kind", "CRASH")))
    summary = html.escape(str(crash.get("summary", "")))
    lines = "\n".join(str(x) for x in (crash.get("lines") or []))
    lines_html = f'<pre class="code">{html.escape(lines)}</pre>' if lines else ""
    return f'<div class="crash-box"><div class="crash-title">[{kind}] {summary}</div>{lines_html}</div>'


def render_journal_html(journal: Dict[str, Any]) -> str:
    run_id = html.escape(str(journal.get("runId", "run")))
    goal = html.escape(str(journal.get("userGoal", "")))
    model = html.escape(str(journal.get("providerModel", "")))
    succeeded = bool(journal.get("succeeded", False))
    conclusion = html.escape(str(journal.get("finalConclusion", "")))
    tools = [html.escape(str(t)) for t in (journal.get("calledTools") or [])]
    skills = [html.escape(str(s)) for s in (journal.get("activeSkills") or [])]
    steps = journal.get("steps") or []
    top_crashes = journal.get("crashes") or []

    crashes_section = ""
    if top_crashes:
        crashes_section = (
            '<section class="card card-fail">'
            '<div class="row"><span class="pill pill-ink">CRASH / ANR FRAMES</span>'
            f'<span class="meta">{len(top_crashes)} detected</span></div>'
            + "".join(_render_crash_box(c) for c in top_crashes)
            + "</section>"
        )

    step_cards: List[str] = []
    for step in steps:
        idx = html.escape(str(step.get("index", "")))
        phase = html.escape(str(step.get("phase", "STEP")))
        tool = html.escape(str(step.get("toolName", "")))
        args = html.escape(str(step.get("argsSummary", "")))
        obs = html.escape(str(step.get("observationSummary", "")))
        delta = "DELTA CHANGED" if step.get("deltaChanged") else "NO DELTA"
        ok = bool(step.get("succeeded", True))
        fail_cat = html.escape(str(step.get("failureCategory", "NONE")))
        safety = html.escape(str(step.get("safetyNote", "")))
        status_badge = "PASS" if ok else f"FAIL · {fail_cat}"

        safety_html = f'<div class="safety">SAFETY: {safety}</div>' if safety else ""
        args_html = f'<pre class="code">{args}</pre>' if args else ""
        gauge_html = _render_gauge_bar(step.get("gauge"))
        crash_html = _render_crash_box(step.get("crashEvent"))
        shot_src = _safe_screenshot_src(step)
        shot_html = (
            f'<figure class="shot"><img src="{shot_src}" alt="Step #{idx} screenshot" loading="lazy"></figure>'
            if shot_src
            else ""
        )

        step_cards.append(
            f"""
            <article class="card {'card-fail' if not ok else ''}">
              <div class="row">
                <span class="badge-num">#{idx}</span>
                <span class="phase">{phase}</span>
                <span class="tool">{tool or 'observe'}</span>
                <span class="pill">{delta}</span>
                <span class="pill {'pill-ink' if not ok else ''}">{status_badge}</span>
              </div>
              {gauge_html}
              {args_html}
              <p class="obs">{obs}</p>
              {shot_html}
              {crash_html}
              {safety_html}
            </article>
            """
        )

    tools_html = "".join(f'<span class="pill">{t}</span>' for t in tools) or '<span class="muted">none</span>'
    skills_html = "".join(f'<span class="pill">{s}</span>' for s in skills) or '<span class="muted">default</span>'
    status_text = "SUCCEEDED" if succeeded else "FAILED / BLOCKED"

    return f"""<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hanson Trace Journal · {run_id}</title>
<style>
  :root {{
    --bg: #FAFAFA; --surface: #FFFFFF; --sub: #F4F4F5; --line: #E4E4E7;
    --ink: #09090B; --muted: #52525B;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg: #09090B; --surface: #121214; --sub: #18181B; --line: #27272A;
      --ink: #FAFAFA; --muted: #A1A1AA;
    }}
  }}
  * {{ box-sizing: border-box; }}
  body {{
    margin: 0; padding: 24px 16px 48px; background: var(--bg); color: var(--ink);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    line-height: 1.5;
  }}
  .wrap {{ max-width: 820px; margin: 0 auto; }}
  header {{
    background: var(--surface); border: 1.5px solid var(--ink); border-radius: 16px;
    padding: 18px 20px; margin-bottom: 16px;
  }}
  h1 {{ margin: 0 0 6px; font-size: 18px; letter-spacing: -0.02em; }}
  .meta {{ font-size: 12px; color: var(--muted); font-family: ui-monospace, SFMono-Regular, monospace; }}
  .chips {{ display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px; }}
  .pill {{
    display: inline-block; padding: 2px 9px; border-radius: 999px; font-size: 11px;
    font-weight: 700; background: var(--sub); border: 1px solid var(--line); color: var(--ink);
  }}
  .pill-ink {{ background: var(--ink); color: var(--bg); border-color: var(--ink); }}
  .card {{
    background: var(--surface); border: 1px solid var(--line); border-radius: 14px;
    padding: 14px 16px; margin-bottom: 10px;
  }}
  .card-fail {{ border: 1.5px solid var(--ink); }}
  .row {{ display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 8px; }}
  .gauge-row {{ display: flex; flex-wrap: wrap; gap: 6px; margin: 4px 0 8px; }}
  .badge-num {{
    background: var(--ink); color: var(--bg); border-radius: 999px; padding: 2px 8px;
    font-size: 11px; font-weight: 800; font-family: ui-monospace, monospace;
  }}
  .phase {{ font-size: 12px; font-weight: 800; text-transform: uppercase; }}
  .tool {{ font-family: ui-monospace, monospace; font-size: 12px; color: var(--muted); }}
  .code {{
    margin: 6px 0; padding: 8px 10px; border-radius: 10px; background: var(--sub);
    border: 1px solid var(--line); font-size: 11.5px; overflow-x: auto;
    font-family: ui-monospace, SFMono-Regular, monospace;
  }}
  .obs {{ margin: 4px 0 0; font-size: 13px; color: var(--ink); }}
  .shot {{ margin: 10px 0 4px; }}
  .shot img {{
    max-width: 280px; width: 100%; border-radius: 10px; border: 1px solid var(--line);
    filter: grayscale(100%);
  }}
  .crash-box {{
    margin-top: 8px; padding: 8px 10px; border-radius: 10px; background: var(--sub);
    border: 1.5px solid var(--ink);
  }}
  .crash-title {{ font-size: 12px; font-weight: 800; font-family: ui-monospace, monospace; }}
  .safety {{
    margin-top: 8px; padding: 6px 10px; border-radius: 8px; background: var(--sub);
    border-left: 3px solid var(--ink); font-size: 11.5px; font-weight: 700;
  }}
  .muted {{ color: var(--muted); font-size: 12px; }}
</style>
</head>
<body>
<main class="wrap">
  <header>
    <div class="row">
      <span class="pill pill-ink">{status_text}</span>
      <span class="meta">run={run_id} · model={model} · steps={len(steps)} · crashes={len(top_crashes)}</span>
    </div>
    <h1>{goal}</h1>
    <div class="meta">Conclusion: {conclusion or '(none)'}</div>
    <div class="chips"><span class="muted">Tools:</span> {tools_html}</div>
    <div class="chips"><span class="muted">Skills:</span> {skills_html}</div>
  </header>
  {crashes_section}
  {''.join(step_cards)}
</main>
</body>
</html>
"""


def render_junit_xml(journal: Dict[str, Any]) -> str:
    """Export a single `AgentRunJournal` as valid CI JUnit XML (including crash/ANR stderr)."""
    run_id = str(journal.get("runId", "run"))
    succeeded = bool(journal.get("succeeded", False))
    steps = journal.get("steps") or []
    crashes = journal.get("crashes") or []
    duration_s = float(journal.get("durationSeconds", len(steps) * 0.5))

    root = ET.Element("testsuites")
    suite = ET.SubElement(
        root,
        "testsuite",
        attrib={
            "name": "hanson-trace",
            "tests": "1",
            "failures": "0" if succeeded else "1",
            "time": f"{duration_s:.3f}",
        },
    )
    tc = ET.SubElement(
        suite,
        "testcase",
        attrib={
            "classname": str(journal.get("providerModel", "hanson")),
            "name": run_id,
            "time": f"{duration_s:.3f}",
        },
    )
    if not succeeded:
        fl = ET.SubElement(tc, "failure")
        fl.set("message", str(journal.get("finalConclusion") or "Run failed"))
        failed_steps = [
            f"#{s.get('index')} {s.get('toolName')}: {s.get('failureCategory')} - {s.get('observationSummary')}"
            for s in steps
            if not s.get("succeeded", True)
        ]
        if failed_steps:
            fl.text = "\n".join(failed_steps)

    if crashes:
        se = ET.SubElement(tc, "system-err")
        se.text = "\n---\n".join(
            f"[{c.get('kind', 'CRASH')}] {c.get('summary', '')}\n" + "\n".join(c.get("lines") or [])
            for c in crashes
            if isinstance(c, dict)
        )
    return '<?xml version="1.0" encoding="utf-8"?>\n' + ET.tostring(root, encoding="unicode")


def main(argv: List[str]) -> int:
    if len(argv) < 2:
        print("Usage: python harness/hanson_trace_viewer.py <journal.json|trace.jsonl> [output.html] [--junit out.xml]")
        return 2
    args = list(argv[1:])
    junit_out: Optional[Path] = None
    if "--junit" in args:
        j_idx = args.index("--junit")
        if j_idx + 1 >= len(args):
            print("Error: --junit requires an output path", file=sys.stderr)
            return 2
        junit_out = Path(args[j_idx + 1])
        del args[j_idx : j_idx + 2]

    src = Path(args[0])
    dst = Path(args[1]) if len(args) >= 2 else src.with_suffix(".html")
    journal = load_journal(src)
    dst.write_text(render_journal_html(journal), encoding="utf-8")
    print(f"Wrote trace viewer: {dst}")
    if junit_out is not None:
        junit_out.write_text(render_junit_xml(journal), encoding="utf-8")
        print(f"Wrote JUnit XML: {junit_out}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
