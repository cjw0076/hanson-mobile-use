#!/usr/bin/env python3
"""Self-contained Offline HTML Trace Replay Viewer (0% Saturation Paper & Carbon).

Absorbed from `PhoneHarness/PhoneHarness` (`scripts/trace2html.py` + `journal.py`).
Converts a Hanson `AgentRunJournal` JSON or `.jsonl` trace into an auditable, XSS-escaped
monochrome HTML timeline showing tool calls, screen delta verdicts, failure taxonomy badges,
and safety gate decisions.

Zero external dependencies (Python 3.9+ stdlib only).
"""
from __future__ import annotations

import html
import json
import sys
from pathlib import Path
from typing import Any, Dict, List


def load_journal(path: Path) -> Dict[str, Any]:
    raw = path.read_text(encoding="utf-8").strip()
    if not raw:
        raise ValueError(f"Empty trace file: {path}")
    if path.suffix.lower() == ".jsonl" or "\n" in raw and not raw.startswith("{"):
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


def render_journal_html(journal: Dict[str, Any]) -> str:
    run_id = html.escape(str(journal.get("runId", "run")))
    goal = html.escape(str(journal.get("userGoal", "")))
    model = html.escape(str(journal.get("providerModel", "")))
    succeeded = bool(journal.get("succeeded", False))
    conclusion = html.escape(str(journal.get("finalConclusion", "")))
    tools = [html.escape(str(t)) for t in (journal.get("calledTools") or [])]
    skills = [html.escape(str(s)) for s in (journal.get("activeSkills") or [])]
    steps = journal.get("steps") or []

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
              {args_html}
              <p class="obs">{obs}</p>
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
      <span class="meta">run={run_id} · model={model} · steps={len(steps)}</span>
    </div>
    <h1>{goal}</h1>
    <div class="meta">Conclusion: {conclusion or '(none)'}</div>
    <div class="chips"><span class="muted">Tools:</span> {tools_html}</div>
    <div class="chips"><span class="muted">Skills:</span> {skills_html}</div>
  </header>
  {''.join(step_cards)}
</main>
</body>
</html>
"""


def main(argv: List[str]) -> int:
    if len(argv) < 2:
        print("Usage: python harness/hanson_trace_viewer.py <journal.json|trace.jsonl> [output.html]")
        return 2
    src = Path(argv[1])
    dst = Path(argv[2]) if len(argv) >= 3 else src.with_suffix(".html")
    journal = load_journal(src)
    dst.write_text(render_journal_html(journal), encoding="utf-8")
    print(f"Wrote trace viewer: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
