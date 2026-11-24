#!/usr/bin/env python3
"""Hanson Proactive Secretary API Sync Bridge (PC / Cloud -> Phone :18888).

Pulls work/life signals from external APIs and local files, enforces strict
secret redaction and prompt-injection blocking BEFORE anything leaves the host,
and pushes sanitized signals into Hanson Mobile-Use `:18888/secretary/ingest`
(or prints structured JSON in `--dry-run` mode).

Supported sources (all 100% Python 3.9+ stdlib, zero external pip deps):
1. ICS Calendar feeds/files (`.ics` RFC 5545): upcoming events, overlapping
   calendar conflicts, tight back-to-back transitions, Meet/Zoom/Teams links,
   and physical-location departure prep.
2. GitHub Notifications (`gh api notifications` with safe offline fallback):
   PR review requests, mentions, and assignments normalized as `DEADLINE_TASK`
   signals.
3. Open-Meteo Weather (`https://api.open-meteo.com/v1/forecast`, zero API key):
   rain/umbrella and cold/coat commute recommendations + offline payload parser.
4. Generic Webhook / JSON Inbox (`--from-json <file>`): Slack, Gmail, Notion,
   Linear, Jira, or custom JSON exports.

Security guarantees:
- Strips API keys, tokens, OTP/2FA/door PINs, card numbers, and Korean RRNs.
- Blocks pure OTP/verification notifications and prompt-injection payloads.
- Never writes tokens/secrets to disk or prints `X-Artemis-Token`.
- Refuses non-loopback plaintext `http://` targets (`https://` or loopback only).
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone, tzinfo
from pathlib import Path
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Set, Tuple

try:
    from zoneinfo import ZoneInfo
except Exception:  # pragma: no cover
    ZoneInfo = None  # type: ignore[assignment]

_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

import hanson_harness  # noqa: E402
import hanson_script_guard as safety_guard  # noqa: E402

KST = timezone(timedelta(hours=9), name="Asia/Seoul")

_KNOWN_TZ_OFFSETS: Dict[str, timezone] = {
    "UTC": timezone.utc,
    "ETC/UTC": timezone.utc,
    "GMT": timezone.utc,
    "ASIA/SEOUL": KST,
    "KOREA STANDARD TIME": KST,
    "ASIA/TOKYO": timezone(timedelta(hours=9), name="Asia/Tokyo"),
    "AMERICA/LOS_ANGELES": timezone(timedelta(hours=-8), name="America/Los_Angeles"),
    "PACIFIC STANDARD TIME": timezone(timedelta(hours=-8), name="America/Los_Angeles"),
    "AMERICA/NEW_YORK": timezone(timedelta(hours=-5), name="America/New_York"),
    "EASTERN STANDARD TIME": timezone(timedelta(hours=-5), name="America/New_York"),
    "EUROPE/LONDON": timezone(timedelta(hours=0), name="Europe/London"),
    "EUROPE/BERLIN": timezone(timedelta(hours=1), name="Europe/Berlin"),
}

# =============================================================================
# Strict Security Hygiene: Endpoint Validator, Secret Redactor & Injection Guard
# =============================================================================

_SECRET_PATTERNS: Tuple[Tuple[str, re.Pattern[str], str], ...] = (
    ("google_api_key", re.compile(r"\bAIza[0-9A-Za-z_\-]{20,}\b"), "[REDACTED_API_KEY]"),
    ("openai_or_sk_key", re.compile(r"\bsk-(?:proj-|ant-|live-|test-)?[0-9A-Za-z_\-]{16,}\b"), "[REDACTED_API_KEY]"),
    ("github_token", re.compile(r"\b(?:ghp|gho|ghu|ghs|ghr)_[0-9A-Za-z]{20,}\b"), "[REDACTED_TOKEN]"),
    ("github_pat", re.compile(r"\bgithub_pat_[0-9A-Za-z_]{20,}\b"), "[REDACTED_TOKEN]"),
    ("slack_token", re.compile(r"\bxox[baprs]-[0-9A-Za-z\-]{10,}\b"), "[REDACTED_TOKEN]"),
    ("aws_access_key", re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "[REDACTED_TOKEN]"),
    ("bearer_token", re.compile(r"\bBearer\s+[A-Za-z0-9\-._~+/]{12,}=*\b", re.I), "Bearer [REDACTED_TOKEN]"),
    ("korean_rrn", re.compile(r"\b\d{6}[-\s]?[1-4]\d{6}\b"), "[REDACTED_RRN]"),
    ("payment_card", re.compile(r"\b(?:\d{4}[-\s]?){3}\d{3,4}\b"), "[REDACTED_CARD]"),
    (
        "door_or_locker_pin",
        re.compile(
            r"((?:공동현관|현관\s*비밀번호|문\s*비밀번호|출입\s*비밀번호|보관함\s*비밀번호|도어락|door\s*pin|entry\s*code)\s*[:：#]?\s*)([#*]?\d{4,8}[#*]?)",
            re.I,
        ),
        r"\1[REDACTED_PIN]",
    ),
    (
        "otp_code",
        re.compile(
            r"((?:인증번호|보안코드|확인코드|일회용\s*번호|본인확인|otp|verification\s*code|passcode|2fa\s*code|one[-\s]?time\s*code)\s*[:：\[\(]?\s*)(\d{4,8})([\]\)]?)",
            re.I,
        ),
        r"\1[REDACTED_OTP]\3",
    ),
)

_PURE_OTP_HINT_RE = re.compile(
    r"(인증번호|보안코드|확인코드|본인확인\s*코드|일회용\s*비밀번호|\botp\b|verification\s*code|2fa\s*code|one[-\s]?time\s*(?:code|passcode))",
    re.I,
)

_EXTRA_INJECTION_PATTERNS: Tuple[Tuple[str, re.Pattern[str]], ...] = (
    ("disregard_instructions", re.compile(r"disregard\s+(all\s+)?(previous|prior|above|system)\s+instructions", re.I)),
    ("untrusted_tag_breakout", re.compile(r"</?\s*untrusted_screen_data", re.I)),
    ("role_hijack", re.compile(r"(begin\s+system\s+prompt|act\s+as\s+root|execute\s+without\s+approval)", re.I)),
    ("korean_auto_transfer_attack", re.compile(r"(비밀번호\s*입력|승인\s*절차\s*건너뛰|자동\s*이체\s*실행|토큰(을|를)?\s*전송)")),
)

_MEETING_URL_RE = re.compile(
    r"https://(?:meet\.google\.com/[a-z0-9\-]+|(?:[a-z0-9\-]+\.)?zoom\.us/[jw]/[^\s<>\"')]+|teams\.microsoft\.com/l/meetup-join/[^\s<>\"')]+|whereby\.com/[^\s<>\"')]+|(?:\w+\.)?webex\.com/[^\s<>\"')]+)",
    re.I,
)

_SOURCE_PACKAGE_MAP: Dict[str, Tuple[str, str]] = {
    "slack": ("com.Slack", "Slack"),
    "gmail": ("com.google.android.gm", "Gmail"),
    "email": ("com.google.android.gm", "Email"),
    "outlook": ("com.microsoft.office.outlook", "Outlook"),
    "notion": ("notion.id", "Notion"),
    "linear": ("com.linear", "Linear"),
    "jira": ("com.atlassian.android.jira.core", "Jira"),
    "github": ("com.github.android", "GitHub"),
    "teams": ("com.microsoft.teams", "Microsoft Teams"),
    "discord": ("com.discord", "Discord"),
    "kakaotalk": ("com.kakao.talk", "카카오톡"),
    "calendar": ("com.hanson.sync.calendar", "ICS Calendar Sync"),
    "weather": ("com.hanson.sync.weather", "Open-Meteo Weather"),
    "airquality": ("com.hanson.sync.airquality", "Air Quality Monitor"),
    "trends": ("com.hanson.sync.trends", "Trend Radar"),
    "webhook": ("com.hanson.sync.webhook", "Webhook Sync"),
}

MET_NORWAY_USER_AGENT = "HansonMobileUse/6.7 (+https://github.com/hanson-mobile-use/hanson-mobile-use)"

DEFAULT_TREND_FEEDS: Dict[str, Tuple[str, str]] = {
    "geeknews": ("GeekNews", "https://news.hada.io/rss/news"),
    "hn": ("Hacker News", "https://hnrss.org/frontpage"),
    "github_blog": ("GitHub Changelog", "https://github.blog/changelog/feed/"),
    "naver_kr": ("Korea Top News", "https://news.google.com/rss?hl=ko&gl=KR&ceid=KR:ko"),
}


def is_allowed_endpoint(url: str) -> bool:
    """Allow only HTTPS endpoints or explicit loopback HTTP (`127.0.0.1`, `localhost`, `::1`)."""
    parsed = urllib.parse.urlparse((url or "").strip())
    scheme = parsed.scheme.lower()
    host = (parsed.hostname or "").lower()
    if not scheme or not host:
        return False
    if scheme == "https":
        return True
    if scheme == "http" and host in ("127.0.0.1", "localhost", "::1"):
        return True
    return False


def redact_secrets(text: str) -> Tuple[str, List[str]]:
    """Redact API keys, tokens, OTPs, door PINs, cards, and Korean RRNs."""
    cleaned = str(text or "")
    hits: List[str] = []
    for name, pattern, repl in _SECRET_PATTERNS:
        new_text, count = pattern.subn(repl, cleaned)
        if count > 0:
            hits.append(name)
            cleaned = new_text
    return cleaned, hits


def detect_signal_prompt_injection(text: str) -> List[str]:
    """Detect English and Korean indirect prompt-injection payloads."""
    raw = str(text or "")
    hits = list(safety_guard.detect_prompt_injection(raw))
    for name, pat in _EXTRA_INJECTION_PATTERNS:
        if pat.search(raw) and name not in hits:
            hits.append(name)
    return hits


def sanitize_signal_text(text: str) -> Dict[str, Any]:
    """Run secret redaction + prompt-injection inspection on untrusted text."""
    raw = str(text or "").strip()
    injections = detect_signal_prompt_injection(raw)
    redacted, secret_hits = redact_secrets(raw)
    redacted = redacted.replace("</untrusted_screen_data>", "[/untrusted_screen_data]").replace(
        "<untrusted_screen_data", "[untrusted_screen_data"
    )
    pure_otp = bool(_PURE_OTP_HINT_RE.search(raw)) and ("otp_code" in secret_hits) and len(raw) <= 140
    blocked_reason: Optional[str] = None
    if injections:
        blocked_reason = f"prompt_injection:{','.join(injections)}"
    elif pure_otp:
        blocked_reason = "otp_or_verification_code"
    return {
        "text": redacted,
        "redactions": secret_hits,
        "injections": injections,
        "blocked": blocked_reason is not None,
        "blocked_reason": blocked_reason,
    }


def prepare_safe_signal(
    package_name: str,
    sender: str,
    text: str,
    app_label: str = "",
    room: str = "",
    can_quick_reply: bool = False,
    timestamp_ms: Optional[int] = None,
    meta: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Sanitize a candidate signal into either an accepted `/secretary/ingest` body or a blocked record."""
    combined_for_injection = f"{sender}\n{room}\n{text}"
    inj = detect_signal_prompt_injection(combined_for_injection)
    body_res = sanitize_signal_text(text)
    sender_clean, sender_red = redact_secrets(sender)
    room_clean, room_red = redact_secrets(room)
    all_redactions = sorted(set(body_res["redactions"] + sender_red + room_red))

    if inj:
        return {
            "accepted": False,
            "blocked_reason": f"prompt_injection:{','.join(inj)}",
            "redactions": all_redactions,
            "package_name": package_name,
        }
    if body_res["blocked"]:
        return {
            "accepted": False,
            "blocked_reason": body_res["blocked_reason"],
            "redactions": all_redactions,
            "package_name": package_name,
        }
    if not body_res["text"].strip():
        return {
            "accepted": False,
            "blocked_reason": "empty_text",
            "redactions": all_redactions,
            "package_name": package_name,
        }

    ts = int(timestamp_ms) if timestamp_ms is not None else int(time.time() * 1000)
    signal: Dict[str, Any] = {
        "package_name": package_name.strip(),
        "app_label": (app_label or package_name).strip(),
        "sender": sender_clean.strip() or "Sync Bridge",
        "room": room_clean.strip(),
        "text": body_res["text"].strip(),
        "can_quick_reply": bool(can_quick_reply),
        "timestamp_ms": ts,
    }
    return {
        "accepted": True,
        "signal": signal,
        "redactions": all_redactions,
        "meta": meta or {},
    }


# =============================================================================
# 1. ICS Calendar Feed / File Parser & Conflict Detector (RFC 5545)
# =============================================================================

def _resolve_tz(tz_name: Optional[str], default_tz: tzinfo = KST) -> tzinfo:
    if not tz_name:
        return default_tz
    cleaned = tz_name.strip().strip('"')
    upper = cleaned.upper()
    if upper in _KNOWN_TZ_OFFSETS:
        return _KNOWN_TZ_OFFSETS[upper]
    m = re.match(r"^(?:UTC|GMT)?([+-])(\d{2}):?(\d{2})$", upper)
    if m:
        sign = 1 if m.group(1) == "+" else -1
        return timezone(timedelta(hours=sign * int(m.group(2)), minutes=sign * int(m.group(3))))
    if ZoneInfo is not None:
        try:
            return ZoneInfo(cleaned)
        except Exception:
            pass
    return default_tz


def _unfold_ics_lines(ics_text: str) -> List[str]:
    normalized = (ics_text or "").replace("\r\n", "\n").replace("\r", "\n")
    out: List[str] = []
    for line in normalized.split("\n"):
        if line.startswith((" ", "\t")) and out:
            out[-1] += line[1:]
        else:
            out.append(line)
    return out


def _unescape_ics_text(raw: str) -> str:
    return (
        (raw or "")
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
        .strip()
    )


def _parse_ics_datetime(raw_val: str, params: Dict[str, str], default_tz: tzinfo = KST) -> Tuple[Optional[int], bool]:
    val = (raw_val or "").strip()
    if not val:
        return None, False
    is_date_only = params.get("VALUE", "").upper() == "DATE" or (len(val) == 8 and val.isdigit())
    tz = _resolve_tz(params.get("TZID"), default_tz=default_tz)

    if is_date_only and re.fullmatch(r"\d{8}", val):
        dt = datetime(int(val[0:4]), int(val[4:6]), int(val[6:8]), 0, 0, tzinfo=tz)
        return int(dt.timestamp() * 1000), True

    m = re.fullmatch(r"(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})?(Z)?", val)
    if not m:
        return None, False
    yr, mo, dy, hr, mi = int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
    sec = int(m.group(6) or "0")
    use_tz: tzinfo = timezone.utc if m.group(7) == "Z" else tz
    dt = datetime(yr, mo, dy, hr, mi, sec, tzinfo=use_tz)
    return int(dt.timestamp() * 1000), False


def _parse_ics_duration_ms(raw_dur: str) -> Optional[int]:
    m = re.fullmatch(r"([+-])?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?", (raw_dur or "").strip().upper())
    if not m:
        return None
    days = int(m.group(2) or 0)
    hours = int(m.group(3) or 0)
    mins = int(m.group(4) or 0)
    secs = int(m.group(5) or 0)
    total_sec = (((days * 24) + hours) * 60 + mins) * 60 + secs
    return total_sec * 1000 if total_sec > 0 else None


def extract_meeting_url(*texts: str) -> Optional[str]:
    for chunk in texts:
        if not chunk:
            continue
        m = _MEETING_URL_RE.search(chunk)
        if m:
            return m.group(0).rstrip(".,);")
    return None


def parse_ics_events(ics_text: str, default_tz: tzinfo = KST) -> List[Dict[str, Any]]:
    """Parse RFC 5545 ICS text into normalized upcoming event dictionaries."""
    lines = _unfold_ics_lines(ics_text)
    events: List[Dict[str, Any]] = []
    in_event = False
    props: Dict[str, Tuple[str, Dict[str, str]]] = {}

    for line in lines:
        stripped = line.strip()
        if stripped.upper() == "BEGIN:VEVENT":
            in_event = True
            props = {}
            continue
        if stripped.upper() == "END:VEVENT":
            if in_event:
                status = (props.get("STATUS", ("CONFIRMED", {}))[0] or "CONFIRMED").strip().upper()
                if status != "CANCELLED" and "DTSTART" in props:
                    start_raw, start_params = props["DTSTART"]
                    start_ms, all_day = _parse_ics_datetime(start_raw, start_params, default_tz=default_tz)
                    if start_ms is not None:
                        end_ms: Optional[int] = None
                        if "DTEND" in props:
                            end_raw, end_params = props["DTEND"]
                            end_ms, _ = _parse_ics_datetime(end_raw, end_params, default_tz=default_tz)
                        if end_ms is None and "DURATION" in props:
                            dur_ms = _parse_ics_duration_ms(props["DURATION"][0])
                            if dur_ms:
                                end_ms = start_ms + dur_ms
                        if end_ms is None or end_ms <= start_ms:
                            end_ms = start_ms + (86_400_000 if all_day else 3_600_000)

                        summary = _unescape_ics_text(props.get("SUMMARY", ("(제목 없음)", {}))[0])
                        description = _unescape_ics_text(props.get("DESCRIPTION", ("", {}))[0])
                        location = _unescape_ics_text(props.get("LOCATION", ("", {}))[0])
                        conf = _unescape_ics_text(
                            props.get("X-GOOGLE-CONFERENCE", ("", {}))[0]
                            or props.get("URL", ("", {}))[0]
                        )
                        meeting_url = extract_meeting_url(conf, location, description)
                        uid = _unescape_ics_text(props.get("UID", (f"ics_{len(events)}", {}))[0])
                        events.append({
                            "uid": uid,
                            "summary": summary or "(제목 없음)",
                            "description": description,
                            "location": location,
                            "start_ms": start_ms,
                            "end_ms": end_ms,
                            "all_day": all_day,
                            "meeting_url": meeting_url,
                            "status": status,
                        })
            in_event = False
            props = {}
            continue

        if not in_event or ":" not in line:
            continue
        left, value = line.split(":", 1)
        parts = left.split(";")
        key = parts[0].strip().upper()
        param_map: Dict[str, str] = {}
        for p in parts[1:]:
            if "=" in p:
                pk, pv = p.split("=", 1)
                param_map[pk.strip().upper()] = pv.strip().strip('"')
        props[key] = (value, param_map)

    events.sort(key=lambda e: (e["start_ms"], e["end_ms"]))
    return events


def detect_ics_conflicts(
    events: Sequence[Dict[str, Any]],
    tight_buffer_minutes: int = 10,
) -> List[Dict[str, Any]]:
    """Detect overlapping calendar intervals and tight physical-location transitions."""
    timed = [e for e in events if not e.get("all_day")]
    timed.sort(key=lambda e: (e["start_ms"], e["end_ms"]))
    findings: List[Dict[str, Any]] = []

    for i in range(len(timed)):
        a = timed[i]
        for j in range(i + 1, len(timed)):
            b = timed[j]
            if b["start_ms"] < a["end_ms"]:
                overlap_min = max(1, (min(a["end_ms"], b["end_ms"]) - b["start_ms"]) // 60_000)
                findings.append({
                    "kind": "OVERLAP",
                    "overlap_minutes": int(overlap_min),
                    "first": a,
                    "second": b,
                })
            else:
                gap_min = (b["start_ms"] - a["end_ms"]) // 60_000
                loc_a = (a.get("location") or "").strip()
                loc_b = (b.get("location") or "").strip()
                if (
                    0 <= gap_min <= tight_buffer_minutes
                    and loc_a
                    and loc_b
                    and loc_a.lower() != loc_b.lower()
                    and not a.get("meeting_url")
                    and not b.get("meeting_url")
                ):
                    findings.append({
                        "kind": "TIGHT_TRANSITION",
                        "gap_minutes": int(gap_min),
                        "first": a,
                        "second": b,
                    })
                break
    return findings


def _format_kst_time(epoch_ms: int, tz: tzinfo = KST) -> str:
    return datetime.fromtimestamp(epoch_ms / 1000.0, tz=tz).strftime("%m/%d %H:%M")


def ics_events_to_signals(
    events: Sequence[Dict[str, Any]],
    now_ms: Optional[int] = None,
    horizon_hours: int = 24,
    tz: tzinfo = KST,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Convert upcoming ICS events and detected conflicts into safe secretary signals."""
    ref_ms = int(now_ms) if now_ms is not None else int(time.time() * 1000)
    horizon_ms = ref_ms + max(1, int(horizon_hours)) * 3_600_000
    upcoming = [
        e for e in events
        if e["end_ms"] >= ref_ms and e["start_ms"] <= horizon_ms
    ]
    accepted: List[Dict[str, Any]] = []
    blocked: List[Dict[str, Any]] = []

    for conflict in detect_ics_conflicts(upcoming):
        first, second = conflict["first"], conflict["second"]
        t1 = _format_kst_time(first["start_ms"], tz=tz)
        t2 = _format_kst_time(second["start_ms"], tz=tz)
        if conflict["kind"] == "OVERLAP":
            msg = (
                f"[일정 충돌 {conflict['overlap_minutes']}분 겹침] "
                f"'{first['summary']}' ({t1}) ↔ '{second['summary']}' ({t2}) 일정이 겹칩니다. "
                "참석 조정 또는 이동 확인이 필요합니다."
            )
        else:
            msg = (
                f"[연속 일정 이동 주의 {conflict['gap_minutes']}분 간격] "
                f"'{first['summary']}' ({first['location']}) 직후 '{second['summary']}' ({second['location']}) 예정입니다."
            )
        res = prepare_safe_signal(
            package_name="com.hanson.sync.calendar",
            app_label="ICS Calendar Sync",
            sender="Calendar Conflict Monitor",
            room=f"{first['summary']} / {second['summary']}",
            text=msg,
            timestamp_ms=ref_ms,
            meta={"category": "calendar_conflict", "kind": conflict["kind"]},
        )
        (accepted if res["accepted"] else blocked).append(res)

    for ev in upcoming:
        t_start = _format_kst_time(ev["start_ms"], tz=tz)
        meeting_url = ev.get("meeting_url")
        location = (ev.get("location") or "").strip()
        if meeting_url:
            msg = f"{t_start} 화상회의 '{ev['summary']}' 시작 예정. 회의 링크: {meeting_url}"
            res = prepare_safe_signal(
                package_name="com.hanson.sync.calendar",
                app_label="ICS Calendar Sync",
                sender="Calendar Meeting Prep",
                room=ev["summary"],
                text=msg,
                timestamp_ms=ref_ms,
                meta={"category": "meeting_link_prep", "meeting_url": meeting_url, "start_ms": ev["start_ms"]},
            )
            (accepted if res["accepted"] else blocked).append(res)
        elif location:
            msg = f"{t_start} '{ev['summary']}' 예약/일정 출발 준비 — 장소: {location}"
            res = prepare_safe_signal(
                package_name="com.hanson.sync.calendar",
                app_label="ICS Calendar Sync",
                sender="Calendar Departure Prep",
                room=ev["summary"],
                text=msg,
                timestamp_ms=ref_ms,
                meta={"category": "departure_prep", "location": location, "start_ms": ev["start_ms"]},
            )
            (accepted if res["accepted"] else blocked).append(res)

    return accepted, blocked


def load_ics_source(source: str, timeout: float = 8.0) -> str:
    """Read an ICS calendar from a local file path or an HTTPS URL."""
    src = (source or "").strip()
    if src.startswith(("http://", "https://")):
        if not is_allowed_endpoint(src):
            raise ValueError(f"Blocked insecure ICS URL (HTTPS or loopback only): {src}")
        req = urllib.request.Request(src, headers={"Accept": "text/calendar, text/plain"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read().decode("utf-8", errors="replace")
    return Path(src).read_text(encoding="utf-8")


# =============================================================================
# 2. GitHub Notifications / PR Review Triage (`gh api notifications`)
# =============================================================================

_GH_REASON_KO: Dict[str, str] = {
    "review_requested": "PR 리뷰 부탁 (승인 요청)",
    "mention": "멘션 회신 부탁",
    "team_mention": "팀 멘션 확인 부탁",
    "assign": "담당 이슈 마감 확인",
    "author": "작성 스레드 업데이트",
    "ci_activity": "CI 실패 확인 필요",
    "state_change": "상태 변경 알림",
}


def _parse_iso_ms(raw: Optional[str]) -> int:
    if not raw:
        return int(time.time() * 1000)
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1000)
    except Exception:
        return int(time.time() * 1000)


def normalize_github_notification(
    item: Dict[str, Any],
    include_read: bool = False,
) -> Optional[Dict[str, Any]]:
    """Convert one GitHub `/notifications` JSON object into a safe `DEADLINE_TASK` signal."""
    if not isinstance(item, dict):
        return None
    if not include_read and item.get("unread") is False:
        return None

    reason = str(item.get("reason") or "subscribed").strip().lower()
    if reason not in _GH_REASON_KO and reason not in ("comment", "security_alert"):
        return None

    subject = item.get("subject") or {}
    repo = (item.get("repository") or {}).get("full_name") or "github/repo"
    title = str(subject.get("title") or "").strip()
    subj_type = str(subject.get("type") or "Issue").strip()
    api_url = str(subject.get("url") or "")
    num_match = re.search(r"/(?:pulls|issues)/(\d+)$", api_url)
    ref_suffix = f"#{num_match.group(1)}" if num_match else ""
    room = f"{repo}{ref_suffix}"
    reason_ko = _GH_REASON_KO.get(reason, "확인 및 회신 부탁")
    text = f"[GitHub {subj_type}] {reason_ko}: {title}"
    ts_ms = _parse_iso_ms(item.get("updated_at"))

    return prepare_safe_signal(
        package_name="com.github.android",
        app_label="GitHub",
        sender=repo,
        room=room,
        text=text,
        can_quick_reply=False,
        timestamp_ms=ts_ms,
        meta={"category": "deadline_task", "github_reason": reason, "subject_type": subj_type},
    )


def fetch_github_notifications(
    limit: int = 20,
    participating: bool = True,
    include_read: bool = False,
    runner: Optional[Callable[..., Any]] = None,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]], Optional[str]]:
    """Fetch GitHub notifications via `gh api notifications` with safe offline fallback."""
    exec_fn = runner or subprocess.run
    if runner is None and shutil.which("gh") is None:
        return [], [], "gh CLI not found (offline fallback)"

    endpoint = f"notifications?participating={'true' if participating else 'false'}&per_page={max(1, min(int(limit), 50))}"
    try:
        proc = exec_fn(
            ["gh", "api", endpoint],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
    except Exception as exc:
        return [], [], f"gh api unavailable ({exc.__class__.__name__})"

    if getattr(proc, "returncode", 1) != 0:
        err = (getattr(proc, "stderr", "") or "").strip()
        return [], [], f"gh api returned non-zero ({err[:100] or 'offline/unauthenticated'})"

    try:
        raw_items = json.loads(getattr(proc, "stdout", "") or "[]")
    except Exception:
        return [], [], "invalid JSON returned by gh api notifications"

    if not isinstance(raw_items, list):
        return [], [], "unexpected GitHub notifications response shape"

    accepted: List[Dict[str, Any]] = []
    blocked: List[Dict[str, Any]] = []
    for item in raw_items:
        res = normalize_github_notification(item, include_read=include_read)
        if res is None:
            continue
        (accepted if res["accepted"] else blocked).append(res)
    return accepted, blocked, None


# =============================================================================
# 3. Open-Meteo Weather & Umbrella Check (Zero-Key HTTPS + Pure Offline Parser)
# =============================================================================

_WMO_RAIN_CODES = frozenset({51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82})
_WMO_SNOW_CODES = frozenset({71, 73, 75, 77, 85, 86})
_WMO_THUNDER_CODES = frozenset({95, 96, 99})


def _wmo_label_ko(code: int) -> str:
    if code == 0:
        return "맑음"
    if code in (1, 2, 3):
        return "구름"
    if code in (45, 48):
        return "안개"
    if code in _WMO_RAIN_CODES:
        return "비/소나기"
    if code in _WMO_SNOW_CODES:
        return "눈"
    if code in _WMO_THUNDER_CODES:
        return "뇌우"
    return f"기상코드({code})"


def parse_open_meteo_payload(
    data: Dict[str, Any],
    location_label: str = "현재 위치",
    horizon_hours: int = 12,
    now_ms: Optional[int] = None,
) -> Dict[str, Any]:
    """Parse Open-Meteo forecast JSON into rain/umbrella & coat commute advice."""
    current = data.get("current") or {}
    hourly = data.get("hourly") or {}

    temps = [float(x) for x in (hourly.get("temperature_2m") or [])[:horizon_hours] if x is not None]
    probs = [int(round(float(x))) for x in (hourly.get("precipitation_probability") or [])[:horizon_hours] if x is not None]
    precips = [float(x) for x in (hourly.get("precipitation") or [])[:horizon_hours] if x is not None]
    codes = [int(x) for x in (hourly.get("weather_code") or [])[:horizon_hours] if x is not None]

    cur_temp = current.get("temperature_2m")
    if cur_temp is not None:
        temps_all = [float(cur_temp)] + temps
    else:
        temps_all = temps or [18.0]

    cur_code = int(current["weather_code"]) if current.get("weather_code") is not None else (codes[0] if codes else 0)
    all_codes = [cur_code] + codes
    apparent_temp = float(current["apparent_temperature"]) if current.get("apparent_temperature") is not None else min(temps_all)

    min_temp = round(min(temps_all), 1)
    max_temp = round(max(temps_all), 1)
    max_prob = max(probs) if probs else 0
    max_precip_mm = round(max(precips), 2) if precips else 0.0

    has_precip_code = any(
        c in _WMO_RAIN_CODES or c in _WMO_SNOW_CODES or c in _WMO_THUNDER_CODES
        for c in all_codes
    )
    needs_umbrella = has_precip_code or max_prob >= 40 or max_precip_mm >= 0.5
    needs_coat = min_temp <= 8.0 or apparent_temp <= 6.0
    needs_heat_caution = max_temp >= 31.0

    advice_parts: List[str] = []
    if needs_umbrella:
        advice_parts.append(f"우산을 챙기세요 (강수확률 최대 {max_prob}%, 강수량 {max_precip_mm}mm)")
    if needs_coat:
        advice_parts.append(f"외투를 챙기세요 (최저 {min_temp}°C / 체감 {round(apparent_temp, 1)}°C)")
    if needs_heat_caution:
        advice_parts.append(f"무더위 주의 (최고 {max_temp}°C)")
    if not advice_parts:
        advice_parts.append(f"출근/외출 날씨 양호 ({min_temp}°C~{max_temp}°C, 우산 불필요)")

    weather_label = _wmo_label_ko(cur_code)
    summary_ko = f"[{location_label}] {weather_label} · {min_temp}°C~{max_temp}°C · 강수확률 {max_prob}%"
    advice_ko = " / ".join(advice_parts)

    signal_result = prepare_safe_signal(
        package_name="com.hanson.sync.weather",
        app_label="Open-Meteo Weather",
        sender="Commute Weather Prep",
        room=location_label,
        text=f"{summary_ko} — {advice_ko}",
        can_quick_reply=False,
        timestamp_ms=now_ms,
        meta={
            "category": "weather_prep",
            "needs_umbrella": needs_umbrella,
            "needs_coat": needs_coat,
        },
    )

    return {
        "location_label": location_label,
        "weather_code": cur_code,
        "weather_label_ko": weather_label,
        "min_temp_c": min_temp,
        "max_temp_c": max_temp,
        "max_precip_probability": max_prob,
        "max_precip_mm": max_precip_mm,
        "needs_umbrella": needs_umbrella,
        "needs_coat": needs_coat,
        "needs_heat_caution": needs_heat_caution,
        "summary_ko": summary_ko,
        "advice_ko": advice_ko,
        "signal_result": signal_result,
    }


def fetch_open_meteo_summary(
    lat: float,
    lon: float,
    location_label: str = "현재 위치",
    timeout: float = 5.0,
) -> Dict[str, Any]:
    """Fetch zero-key HTTPS forecast from `api.open-meteo.com` and parse it."""
    qs = urllib.parse.urlencode({
        "latitude": f"{float(lat):.4f}",
        "longitude": f"{float(lon):.4f}",
        "current": "temperature_2m,apparent_temperature,weather_code",
        "hourly": "temperature_2m,precipitation_probability,precipitation,weather_code",
        "forecast_days": 1,
        "timezone": "auto",
    })
    url = f"https://api.open-meteo.com/v1/forecast?{qs}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8", errors="replace"))
    return parse_open_meteo_payload(data, location_label=location_label)


# =============================================================================
# 3B. MET Norway (`api.met.no`) + Open-Meteo Air Quality (PM2.5 Indoor Swap)
# =============================================================================

def parse_met_norway_payload(
    data: Dict[str, Any],
    location_label: str = "현재 위치",
    horizon_hours: int = 12,
    now_ms: Optional[int] = None,
) -> Dict[str, Any]:
    """Parse MET Norway `locationforecast/2.0/compact` JSON into umbrella & coat advice."""
    props = data.get("properties") or {}
    series = (props.get("timeseries") or [])[:max(1, int(horizon_hours))]
    temps: List[float] = []
    precips: List[float] = []
    symbols: List[str] = []

    for entry in series:
        if not isinstance(entry, dict):
            continue
        d = entry.get("data") or {}
        instant = (d.get("instant") or {}).get("details") or {}
        if instant.get("air_temperature") is not None:
            temps.append(float(instant["air_temperature"]))
        for win_key in ("next_1_hours", "next_6_hours"):
            win = d.get(win_key) or {}
            sym = (win.get("summary") or {}).get("symbol_code")
            if sym:
                symbols.append(str(sym).lower())
            det = win.get("details") or {}
            if det.get("precipitation_amount") is not None:
                precips.append(float(det["precipitation_amount"]))

    min_temp = round(min(temps), 1) if temps else 18.0
    max_temp = round(max(temps), 1) if temps else 18.0
    max_precip_mm = round(max(precips), 2) if precips else 0.0
    precip_symbol = any(
        any(tok in sym for tok in ("rain", "sleet", "snow", "drizzle", "thunder"))
        for sym in symbols
    )
    needs_umbrella = precip_symbol or max_precip_mm >= 0.5
    needs_coat = min_temp <= 8.0

    advice_parts: List[str] = []
    if needs_umbrella:
        advice_parts.append(f"우산 지참 권장 (MET Norway 최대 강수량 {max_precip_mm}mm)")
    if needs_coat:
        advice_parts.append(f"외투 권장 (최저 {min_temp}°C)")
    if not advice_parts:
        advice_parts.append(f"날씨 양호 ({min_temp}°C~{max_temp}°C, 우산 불필요)")

    summary_ko = f"[{location_label} · MET Norway] {min_temp}°C~{max_temp}°C · 강수 {max_precip_mm}mm"
    advice_ko = " / ".join(advice_parts)
    signal_result = prepare_safe_signal(
        package_name="com.hanson.sync.weather",
        app_label="MET Norway Weather",
        sender="MET Norway Forecast",
        room=location_label,
        text=f"{summary_ko} — {advice_ko}",
        timestamp_ms=now_ms,
        meta={"category": "met_norway_weather", "needs_umbrella": needs_umbrella, "needs_coat": needs_coat},
    )
    return {
        "provider": "met_norway",
        "location_label": location_label,
        "min_temp_c": min_temp,
        "max_temp_c": max_temp,
        "max_precip_mm": max_precip_mm,
        "symbols": symbols[:6],
        "needs_umbrella": needs_umbrella,
        "needs_coat": needs_coat,
        "summary_ko": summary_ko,
        "advice_ko": advice_ko,
        "signal_result": signal_result,
    }


def fetch_met_norway_summary(
    lat: float,
    lon: float,
    location_label: str = "현재 위치",
    user_agent: str = MET_NORWAY_USER_AGENT,
    timeout: float = 5.0,
) -> Dict[str, Any]:
    """Fetch zero-key HTTPS forecast from `api.met.no` with required polite User-Agent."""
    qs = urllib.parse.urlencode({"lat": f"{float(lat):.4f}", "lon": f"{float(lon):.4f}"})
    url = f"https://api.met.no/weatherapi/locationforecast/2.0/compact?{qs}"
    req = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": user_agent or MET_NORWAY_USER_AGENT},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8", errors="replace"))
    return parse_met_norway_payload(data, location_label=location_label)


def parse_air_quality_payload(
    data: Dict[str, Any],
    location_label: str = "현재 위치",
    horizon_hours: int = 12,
    now_ms: Optional[int] = None,
) -> Dict[str, Any]:
    """Parse Open-Meteo Air Quality JSON and trigger PM2.5 indoor swap alerts."""
    current = data.get("current") or {}
    hourly = data.get("hourly") or {}
    pm25_list = [float(x) for x in (hourly.get("pm2_5") or [])[:horizon_hours] if x is not None]
    pm10_list = [float(x) for x in (hourly.get("pm10") or [])[:horizon_hours] if x is not None]
    if current.get("pm2_5") is not None:
        pm25_list = [float(current["pm2_5"])] + pm25_list
    if current.get("pm10") is not None:
        pm10_list = [float(current["pm10"])] + pm10_list

    max_pm25 = round(max(pm25_list), 1) if pm25_list else 0.0
    max_pm10 = round(max(pm10_list), 1) if pm10_list else 0.0

    if max_pm25 >= 75.0 or max_pm10 >= 150.0:
        grade_ko = "매우 나쁨"
        indoor_swap = True
        advice_ko = f"초미세먼지 매우 나쁨(PM2.5 {max_pm25}㎍/㎥) — 야외 일정 실내 전환 및 KF94 마스크 필수!"
    elif max_pm25 >= 35.0 or max_pm10 >= 80.0:
        grade_ko = "나쁨"
        indoor_swap = True
        advice_ko = f"초미세먼지 나쁨(PM2.5 {max_pm25}㎍/㎥) — 야외 활동 실내 전환·마스크 착용 권장"
    elif max_pm25 >= 15.0:
        grade_ko = "보통"
        indoor_swap = False
        advice_ko = f"대기질 보통(PM2.5 {max_pm25}㎍/㎥) — 야외 일정 무리 없음"
    else:
        grade_ko = "좋음"
        indoor_swap = False
        advice_ko = f"대기질 좋음(PM2.5 {max_pm25}㎍/㎥)"

    summary_ko = f"[{location_label} 대기질] {grade_ko} · PM2.5 {max_pm25} / PM10 {max_pm10} ㎍/㎥"
    signal_result = prepare_safe_signal(
        package_name="com.hanson.sync.airquality",
        app_label="Air Quality Monitor",
        sender="PM2.5 Indoor Swap Advisor",
        room=location_label,
        text=f"{summary_ko} — {advice_ko}",
        timestamp_ms=now_ms,
        meta={"category": "air_quality_pm25", "indoor_swap_recommended": indoor_swap, "grade_ko": grade_ko},
    )
    return {
        "location_label": location_label,
        "max_pm2_5": max_pm25,
        "max_pm10": max_pm10,
        "grade_ko": grade_ko,
        "indoor_swap_recommended": indoor_swap,
        "summary_ko": summary_ko,
        "advice_ko": advice_ko,
        "signal_result": signal_result,
    }


def fetch_air_quality_summary(
    lat: float,
    lon: float,
    location_label: str = "현재 위치",
    timeout: float = 5.0,
) -> Dict[str, Any]:
    """Fetch zero-key HTTPS PM2.5/PM10 air quality forecast from `air-quality-api.open-meteo.com`."""
    qs = urllib.parse.urlencode({
        "latitude": f"{float(lat):.4f}",
        "longitude": f"{float(lon):.4f}",
        "current": "pm2_5,pm10,us_aqi",
        "hourly": "pm2_5,pm10",
        "forecast_days": 1,
        "timezone": "auto",
    })
    url = f"https://air-quality-api.open-meteo.com/v1/air-quality?{qs}"
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8", errors="replace"))
    return parse_air_quality_payload(data, location_label=location_label)


# =============================================================================
# 3C. Zero-Key Trend & Deal Radar (Official RSS 2.0 / Atom XML Parser)
# =============================================================================

def _xml_local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower() if "}" in tag else tag.lower()


def _child_text(parent: ET.Element, names: Sequence[str]) -> str:
    want = {n.lower() for n in names}
    for ch in parent:
        if _xml_local_name(ch.tag) in want:
            if ch.text and ch.text.strip():
                return ch.text.strip()
            href = ch.attrib.get("href")
            if href:
                return href.strip()
    return ""


def parse_rss_or_atom_xml(
    xml_text: str,
    feed_label: str = "Trend Radar",
    max_items: int = 5,
    now_ms: Optional[int] = None,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Parse official RSS 2.0 or Atom XML into sanitized morning A2UI digest signals."""
    root = ET.fromstring((xml_text or "").strip())
    raw_entries: List[Dict[str, str]] = []

    for elem in root.iter():
        lname = _xml_local_name(elem.tag)
        if lname in ("item", "entry"):
            title = _child_text(elem, ("title",))
            link = _child_text(elem, ("link",))
            summary = _child_text(elem, ("description", "summary"))
            if title:
                raw_entries.append({"title": title, "link": link, "summary": summary[:160]})
            if len(raw_entries) >= max(1, int(max_items)):
                break

    accepted: List[Dict[str, Any]] = []
    blocked: List[Dict[str, Any]] = []
    safe_titles: List[str] = []

    for entry in raw_entries:
        body = f"[{feed_label}] {entry['title']}" + (f" ({entry['link']})" if entry["link"] else "")
        res = prepare_safe_signal(
            package_name="com.hanson.sync.trends",
            app_label="Trend Radar",
            sender=feed_label,
            room=f"{feed_label} Digest",
            text=body,
            timestamp_ms=now_ms,
            meta={"category": "trend_radar", "feed_label": feed_label, "link": entry["link"]},
        )
        if res["accepted"]:
            safe_titles.append(entry["title"])
            accepted.append(res)
        else:
            blocked.append(res)

    if len(safe_titles) >= 2:
        digest_lines = " | ".join(f"{i + 1}. {t}" for i, t in enumerate(safe_titles[:5]))
        digest_res = prepare_safe_signal(
            package_name="com.hanson.sync.trends",
            app_label="Trend Radar",
            sender=f"{feed_label} Morning Digest",
            room="Morning A2UI Digest",
            text=f"[아침 트렌드 다이제스트 · {feed_label}] {digest_lines}",
            timestamp_ms=now_ms,
            meta={"category": "trend_digest", "item_count": len(safe_titles)},
        )
        if digest_res["accepted"]:
            accepted.insert(0, digest_res)

    return accepted, blocked


def fetch_feed_signals(
    feed_source: str,
    max_items: int = 5,
    timeout: float = 6.0,
) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Fetch an official RSS/Atom feed (preset key, local `.xml` file, or `https://` URL)."""
    src = (feed_source or "").strip()
    label = src
    if src.lower() in DEFAULT_TREND_FEEDS:
        label, src = DEFAULT_TREND_FEEDS[src.lower()]

    if src.startswith(("http://", "https://")):
        if not is_allowed_endpoint(src):
            raise ValueError(f"Blocked insecure feed URL (HTTPS or loopback only): {src}")
        req = urllib.request.Request(
            src,
            headers={"Accept": "application/rss+xml, application/atom+xml, application/xml", "User-Agent": MET_NORWAY_USER_AGENT},
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            xml_text = resp.read().decode("utf-8", errors="replace")
    else:
        p = Path(src)
        label = p.stem
        xml_text = p.read_text(encoding="utf-8")

    return parse_rss_or_atom_xml(xml_text, feed_label=label, max_items=max_items)


# =============================================================================
# 3D. Opt-In Omnichannel Remote Bridge (Telegram / Slack — Allowlist + Constant-Time Auth)
# =============================================================================

def verify_remote_token(expected_token: str, provided_token: str) -> bool:
    """Constant-time token check (fail-closed when either expected or provided token is empty)."""
    exp = (expected_token or "").strip()
    got = (provided_token or "").strip()
    if not exp or not got:
        return False
    return hmac.compare_digest(exp.encode("utf-8"), got.encode("utf-8"))


class OmnichannelRemoteBridge:
    """Opt-in Telegram Bot API / Slack webhook parser with strict sender-ID allowlist & constant-time auth.

    Never persists tokens or secrets to disk. Fails closed whenever `allowed_sender_ids` is empty.
    """

    def __init__(self, expected_token: str, allowed_sender_ids: Iterable[str]):
        self._expected_token = (expected_token or "").strip()
        self.allowed_sender_ids: Set[str] = {str(s).strip() for s in allowed_sender_ids if str(s).strip()}

    def _authorize(self, sender_id: str, provided_token: str) -> Optional[str]:
        if not verify_remote_token(self._expected_token, provided_token):
            return "unauthorized_token"
        if not self.allowed_sender_ids:
            return "empty_sender_allowlist"
        if str(sender_id).strip() not in self.allowed_sender_ids:
            return "sender_not_allowlisted"
        return None

    def parse_telegram_update(self, update: Dict[str, Any], provided_token: str) -> Dict[str, Any]:
        msg = (update or {}).get("message") or (update or {}).get("edited_message") or {}
        sender_obj = msg.get("from") or {}
        sender_id = str(sender_obj.get("id") or "").strip()
        auth_err = self._authorize(sender_id, provided_token)
        if auth_err:
            return {"accepted": False, "blocked_reason": auth_err, "provider": "telegram"}

        sender_name = str(sender_obj.get("username") or sender_obj.get("first_name") or sender_id).strip()
        chat = msg.get("chat") or {}
        room = str(chat.get("title") or chat.get("id") or "Telegram").strip()
        text = str(msg.get("text") or msg.get("caption") or "").strip()
        ts_sec = msg.get("date")
        ts_ms = int(ts_sec) * 1000 if isinstance(ts_sec, (int, float)) else None
        return prepare_safe_signal(
            package_name="org.telegram.messenger",
            app_label="Telegram Bridge",
            sender=sender_name,
            room=room,
            text=text,
            can_quick_reply=True,
            timestamp_ms=ts_ms,
            meta={"category": "remote_bridge", "provider": "telegram", "sender_id": sender_id},
        )

    def parse_slack_event(self, payload: Dict[str, Any], provided_token: str) -> Dict[str, Any]:
        event = (payload or {}).get("event") or payload or {}
        sender_id = str(event.get("user") or payload.get("user_id") or "").strip()
        auth_err = self._authorize(sender_id, provided_token)
        if auth_err:
            return {"accepted": False, "blocked_reason": auth_err, "provider": "slack"}

        sender_name = str(payload.get("user_name") or sender_id).strip()
        room = str(event.get("channel") or payload.get("channel_name") or "Slack").strip()
        text = str(event.get("text") or payload.get("text") or "").strip()
        return prepare_safe_signal(
            package_name="com.Slack",
            app_label="Slack Bridge",
            sender=sender_name,
            room=room,
            text=text,
            can_quick_reply=True,
            meta={"category": "remote_bridge", "provider": "slack", "sender_id": sender_id},
        )


# =============================================================================
# 4. Generic Webhook / JSON Inbox Ingest (`--from-json <file>`)
# =============================================================================

def load_json_inbox_signals(payload: Any) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """Normalize Slack/Gmail/Notion/Linear/Jira JSON exports into safe secretary signals."""
    if isinstance(payload, dict):
        items = payload.get("items") or payload.get("messages") or payload.get("events") or [payload]
    elif isinstance(payload, list):
        items = payload
    else:
        raise ValueError("JSON inbox payload must be a list or an object containing items/messages/events")

    accepted: List[Dict[str, Any]] = []
    blocked: List[Dict[str, Any]] = []

    for item in items:
        if not isinstance(item, dict):
            continue
        source_key = str(item.get("source") or item.get("provider") or "webhook").strip().lower()
        default_pkg, default_label = _SOURCE_PACKAGE_MAP.get(source_key, ("com.hanson.sync.webhook", source_key or "Webhook"))
        pkg = str(item.get("package_name") or default_pkg).strip()
        app_label = str(item.get("app_label") or default_label).strip()
        sender = str(item.get("sender") or item.get("author") or item.get("from") or app_label).strip()
        room = str(item.get("room") or item.get("channel") or item.get("subject") or item.get("title") or "").strip()
        text = str(item.get("text") or item.get("body") or item.get("message") or "").strip()
        if item.get("subject") and item.get("body") and item["subject"] not in text:
            text = f"{item['subject']}: {text}"
        ts_raw = item.get("timestamp_ms")
        ts_ms = int(ts_raw) if isinstance(ts_raw, (int, float)) else _parse_iso_ms(item.get("timestamp"))
        can_quick_reply = bool(item.get("can_quick_reply", False))

        res = prepare_safe_signal(
            package_name=pkg,
            app_label=app_label,
            sender=sender,
            room=room,
            text=text,
            can_quick_reply=can_quick_reply,
            timestamp_ms=ts_ms,
            meta={"category": "json_inbox", "source": source_key},
        )
        (accepted if res["accepted"] else blocked).append(res)

    return accepted, blocked


def load_json_inbox_file(path: str) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return load_json_inbox_signals(data)


# =============================================================================
# 5. Push Pipeline & CLI Entrypoint
# =============================================================================

def push_signals_to_hanson(
    accepted_records: Sequence[Dict[str, Any]],
    artemis_url: str = hanson_harness.DEFAULT_ARTEMIS_URL,
    artemis_token: str = hanson_harness.DEFAULT_ARTEMIS_TOKEN,
    dry_run: bool = False,
    client: Optional[hanson_harness.HansonPhoneClient] = None,
) -> Dict[str, Any]:
    """Push sanitized signals to `:18888/secretary/ingest` or return dry-run output."""
    target_url = (artemis_url or hanson_harness.DEFAULT_ARTEMIS_URL).rstrip("/")
    if not dry_run and not is_allowed_endpoint(target_url):
        raise ValueError(
            f"Refusing insecure non-loopback HTTP target {target_url!r}; "
            "use http://127.0.0.1:18888 or https://..."
        )

    pushed: List[Dict[str, Any]] = []
    errors: List[Dict[str, Any]] = []
    active_client = client or hanson_harness.HansonPhoneClient(
        artemis_url=target_url,
        artemis_token=artemis_token,
    )

    for rec in accepted_records:
        sig = rec["signal"] if "signal" in rec else rec
        if dry_run:
            pushed.append({"dry_run": True, "signal": sig, "redactions": rec.get("redactions", [])})
            continue
        try:
            resp = active_client.secretary_push_signal(
                package_name=sig["package_name"],
                sender=sig["sender"],
                text=sig["text"],
                app_label=sig.get("app_label", ""),
                room=sig.get("room", ""),
                can_quick_reply=bool(sig.get("can_quick_reply", False)),
                timestamp_ms=sig.get("timestamp_ms"),
            )
            pushed.append({
                "dry_run": False,
                "signal": sig,
                "redactions": rec.get("redactions", []),
                "response": resp,
            })
        except Exception as exc:
            errors.append({
                "package_name": sig.get("package_name"),
                "room": sig.get("room"),
                "error": str(exc),
            })

    return {
        "dry_run": dry_run,
        "target_url": target_url,
        "pushed_count": len(pushed),
        "error_count": len(errors),
        "pushed": pushed,
        "errors": errors,
    }


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="hanson_secretary_sync.py",
        description="PC/Cloud-to-Phone Proactive Secretary Sync Bridge for Hanson Mobile-Use (:18888).",
    )
    p.add_argument("--ics", action="append", default=[], help="Local .ics file or HTTPS calendar feed URL (repeatable)")
    p.add_argument("--horizon-hours", type=int, default=24, help="Upcoming calendar horizon in hours (default: 24)")
    p.add_argument("--github", action="store_true", help="Pull GitHub notifications via `gh api notifications`")
    p.add_argument("--github-limit", type=int, default=20, help="Max GitHub notifications to pull")
    p.add_argument("--weather", action="store_true", help="Check Open-Meteo weather for umbrella/coat advice")
    p.add_argument("--met-norway", action="store_true", help="Check MET Norway (api.met.no) forecast with polite User-Agent")
    p.add_argument("--air-quality", action="store_true", help="Check Open-Meteo Air Quality PM2.5 indoor swap alert")
    p.add_argument("--lat", type=float, default=None, help="Latitude for weather/air-quality check")
    p.add_argument("--lon", type=float, default=None, help="Longitude for weather/air-quality check")
    p.add_argument("--location-label", default="현재 위치", help="Location label for weather brief")
    p.add_argument("--feeds", action="append", default=[], help="Official RSS/Atom feed preset (geeknews|hn|github_blog|naver_kr), local XML, or https:// URL")
    p.add_argument("--feed-limit", type=int, default=5, help="Max items per RSS/Atom feed (default: 5)")
    p.add_argument("--from-json", action="append", default=[], help="Path to Slack/Gmail/Notion/Linear/Jira JSON export")
    p.add_argument("--dry-run", action="store_true", help="Print sanitized signals as JSON without posting to phone")
    p.add_argument("--scan-after", action="store_true", help="Trigger POST /secretary/scan after pushing signals")
    p.add_argument(
        "--artemis-url",
        default=os.environ.get("ARTEMIS_URL", hanson_harness.DEFAULT_ARTEMIS_URL),
        help="Target Hanson loopback/HTTPS base URL (default: http://127.0.0.1:18888)",
    )
    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    accepted: List[Dict[str, Any]] = []
    blocked: List[Dict[str, Any]] = []
    warnings: List[str] = []

    for ics_src in args.ics:
        try:
            ics_text = load_ics_source(ics_src)
            events = parse_ics_events(ics_text)
            acc, blk = ics_events_to_signals(events, horizon_hours=args.horizon_hours)
            accepted.extend(acc)
            blocked.extend(blk)
        except Exception as exc:
            warnings.append(f"ics({ics_src}): {exc}")

    if args.github:
        acc, blk, warn = fetch_github_notifications(limit=args.github_limit)
        accepted.extend(acc)
        blocked.extend(blk)
        if warn:
            warnings.append(warn)

    if args.weather or args.met_norway or args.air_quality:
        if args.lat is None or args.lon is None:
            print("Error: --weather / --met-norway / --air-quality require both --lat and --lon.", file=sys.stderr)
            return 2

    if args.weather:
        try:
            summary = fetch_open_meteo_summary(args.lat, args.lon, location_label=args.location_label)
            sig_res = summary["signal_result"]
            (accepted if sig_res["accepted"] else blocked).append(sig_res)
        except Exception as exc:
            warnings.append(f"weather: {exc}")

    if args.met_norway:
        try:
            met = fetch_met_norway_summary(args.lat, args.lon, location_label=args.location_label)
            sig_res = met["signal_result"]
            (accepted if sig_res["accepted"] else blocked).append(sig_res)
        except Exception as exc:
            warnings.append(f"met_norway: {exc}")

    if args.air_quality:
        try:
            aq = fetch_air_quality_summary(args.lat, args.lon, location_label=args.location_label)
            sig_res = aq["signal_result"]
            (accepted if sig_res["accepted"] else blocked).append(sig_res)
        except Exception as exc:
            warnings.append(f"air_quality: {exc}")

    for feed_src in args.feeds:
        try:
            acc, blk = fetch_feed_signals(feed_src, max_items=args.feed_limit)
            accepted.extend(acc)
            blocked.extend(blk)
        except Exception as exc:
            warnings.append(f"feed({feed_src}): {exc}")

    for json_path in args.from_json:
        try:
            acc, blk = load_json_inbox_file(json_path)
            accepted.extend(acc)
            blocked.extend(blk)
        except Exception as exc:
            warnings.append(f"json({json_path}): {exc}")

    token = os.environ.get("ARTEMIS_TOKEN", hanson_harness.DEFAULT_ARTEMIS_TOKEN)
    push_report = push_signals_to_hanson(
        accepted,
        artemis_url=args.artemis_url,
        artemis_token=token,
        dry_run=args.dry_run,
    )

    scan_result = None
    if args.scan_after and not args.dry_run:
        client = hanson_harness.HansonPhoneClient(artemis_url=args.artemis_url, artemis_token=token)
        scan_result = client.secretary_scan(
            include_weather=bool(args.weather),
            lat=args.lat,
            lon=args.lon,
        )

    output = {
        "status": "ok" if push_report["error_count"] == 0 else "partial_error",
        "dry_run": args.dry_run,
        "accepted_count": len(accepted),
        "blocked_count": len(blocked),
        "blocked": blocked,
        "warnings": warnings,
        "push": push_report,
        "scan": scan_result,
    }
    print(json.dumps(output, ensure_ascii=False, indent=2))
    return 0 if push_report["error_count"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
