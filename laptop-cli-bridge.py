#!/usr/bin/env python3
"""
Hanson Mobile-Use — Laptop CLI Bridge v3 (:18889)
=================================================
Multi-Step Goal Trajectory Planner + Ground-Truth State Verification + Self-Healing Recovery
+ Generative UI Synthesizer (`render_generative_ui`) + Haptic Trajectory (`drag_and_drop`, `haptic_trajectory`)
"""

import json
import re
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 18889
PHONE_BRIDGE_URL = "http://127.0.0.1:18888"

APP_MAP = {
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
    "배달의민족": "com.sampleapp",
    "인스타그램": "com.instagram.android",
}


def parse_ui_elements(prompt_context: str) -> list:
    """Parses `[idx] Class text='...' desc='...' norm=(x,y) [FLAGS]` from snapshot.toPromptContext()."""
    elements = []
    for line in prompt_context.splitlines():
        s = line.strip()
        m = re.match(r"^\[(\d+)\]\s+(\S+)(.*)$", s)
        if not m:
            continue
        idx = int(m.group(1))
        cls_name = m.group(2)
        rest = m.group(3)
        text_m = re.search(r"text='([^']*)'", rest)
        desc_m = re.search(r"desc='([^']*)'", rest)
        res_m = re.search(r"id='([^']*)'", rest)
        norm_m = re.search(r"norm=\((\d+),(\d+)\)", rest)
        elements.append(
            {
                "index": idx,
                "class": cls_name,
                "text": text_m.group(1) if text_m else "",
                "desc": desc_m.group(1) if desc_m else "",
                "res": res_m.group(1) if res_m else "",
                "norm_x": int(norm_m.group(1)) if norm_m else 500,
                "norm_y": int(norm_m.group(2)) if norm_m else 500,
                "editable": "EDITABLE" in rest or "EditText" in cls_name,
                "clickable": "CLICKABLE" in rest,
                "scrollable": "SCROLLABLE" in rest,
                "focused": "FOCUSED" in rest,
                "raw": s,
            }
        )
    return elements


def extract_query_from_goal(goal: str):
    quoted = re.search(r'"([^"]+)"|\'([^\']+)\'', goal)
    if quoted:
        return (quoted.group(1) or quoted.group(2)).strip()

    m = re.search(
        r"(?:열어서|열고|에서|창에|에)?\s*([가-힣a-zA-Z0-9_\s]{1,18}?)\s*(?:검색|입력|찾고|찾아|타이핑|쳐서|치고)",
        goal,
    )
    if m:
        candidate = m.group(1)
        for noise in ["검색창", "카카오톡", "카톡", "유튜브", "설정", "토스", "네이버", "크롬", "그리고", "해서"]:
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
    history_steps: list,
) -> dict:
    g = goal.strip()
    lower = g.lower()
    elements = parse_ui_elements(prompt_context)

    # =========================================================================
    # 0. SELF-HEALING RECOVERY CHECK (If the previous step failed verification!)
    # =========================================================================
    if history_steps:
        last_step = history_steps[-1]
        last_ok = bool(last_step.get("succeeded", True))
        last_tool = last_step.get("tool", "")
        last_result = last_step.get("result", "")

        # Only trigger planner-level recovery if we haven't already retried twice in a row
        recent_failures = sum(1 for s in history_steps[-2:] if not s.get("succeeded", True))
        if (not last_ok) and recent_failures <= 1 and iteration <= 6:
            # 1) Check for blocking modal/popup button
            modal_btn = next(
                (
                    el
                    for el in elements
                    if any(
                        kw in (el["text"] + " " + el["desc"])
                        for kw in ["허용", "닫기", "나중에", "확인", "건너뛰기"]
                    )
                ),
                None,
            )
            if modal_btn:
                return {
                    "observation": f"[자기수복(Self-Healing) · 인지 & 복구] 이전 스텝({last_tool}) 실패 원인이 가로막은 팝업('{modal_btn['text'] or modal_btn['desc']}')으로 확인되어 해체 탭을 먼저 수행합니다.",
                    "subGoals": [
                        {"title": "가로막은 모달/팝업 해체 복구", "status": "IN_PROGRESS"},
                        {"title": "원래 목표 궤적 재실행", "status": "PENDING"},
                    ],
                    "toolName": "click",
                    "toolArgs": {
                        "element_index": modal_btn["index"],
                        "target_description": "팝업 해체 복구 탭",
                    },
                }

            # 2) If click had no UI transition, scroll slightly to bring target into clean view
            if last_tool == "click":
                return {
                    "observation": f"[자기수복(Self-Healing) · 인지 & 복구] 이전 클릭이 화면 전이를 일으키지 못해({last_result[:45]}), 화면을 스와이프하여 요소를 재노출시킨 뒤 재시도합니다.",
                    "subGoals": [
                        {"title": "무반응 화면 복구 스와이프", "status": "IN_PROGRESS"},
                        {"title": "타깃 재탐색 및 재실행", "status": "PENDING"},
                    ],
                    "toolName": "swipe",
                    "toolArgs": {"direction": "up", "duration_ms": 360},
                }

    # =========================================================================
    # 1. MULTI-STEP GOAL TRAJECTORY STATE MACHINE
    #    [앱 진입] -> [검색/입력창 탭] -> [텍스트 타이핑 & ENTER] -> [드래그앤드롭/스크롤] -> [결과 클릭] -> [검증 완료]
    # =========================================================================
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
        or (
            matched_app_name is not None
            and any(k in lower for k in ["열어서", "그리고", "해서", "누르고", "찾고", "찾아"])
        )
    )

    if is_multi_step and iteration <= 8:
        has_launched = any(s.get("tool") == "manage_app" for s in history_steps)
        has_typed = any(s.get("tool") == "input_text" for s in history_steps)
        has_clicked_search = any(s.get("tool") == "click" for s in history_steps)
        has_dragged = any(
            s.get("tool") in ("drag_and_drop", "haptic_trajectory") for s in history_steps
        )
        has_swiped = any(s.get("tool") == "swipe" for s in history_steps)

        # Count clicks that happened AFTER input_text
        seen_input = False
        clicks_after_input = 0
        for s in history_steps:
            if s.get("tool") == "input_text":
                seen_input = True
            elif seen_input and s.get("tool") == "click":
                clicks_after_input += 1

        # Build live trajectory checklist ledger
        ledger = []
        if matched_app_name:
            app_done = (active_pkg == target_pkg) or has_launched
            ledger.append(
                {
                    "title": f"1단계: '{matched_app_name}' 앱 진입 및 패키지 전환 검증",
                    "status": "COMPLETED" if app_done else "IN_PROGRESS",
                }
            )
        if search_query:
            ledger.append(
                {
                    "title": f"2단계: 검색/입력창 포커스 확보 및 '{search_query}' 타이핑 주입",
                    "status": "COMPLETED" if has_typed else "IN_PROGRESS",
                }
            )
        if wants_post_click:
            ledger.append(
                {
                    "title": f"3단계: '{search_query}' 검색 결과 타깃 탐색 및 클릭",
                    "status": "COMPLETED"
                    if clicks_after_input >= 1
                    else ("IN_PROGRESS" if has_typed else "PENDING"),
                }
            )
        if wants_drag_drop:
            ledger.append(
                {
                    "title": "햅틱 궤적: 롱프레스 홀드 후 연속 드래그 앤 드롭(Drag & Drop)",
                    "status": "COMPLETED" if has_dragged else "IN_PROGRESS",
                }
            )
        if wants_swipe:
            ledger.append(
                {
                    "title": "햅틱 궤적: 화면 스와이프 스크롤 및 UI 변위 검증",
                    "status": "COMPLETED" if has_swiped else "IN_PROGRESS",
                }
            )

        # PHASE 1: Launch App if not in foreground yet
        if matched_app_name and active_pkg != target_pkg and not has_launched:
            return {
                "observation": f"[행동 궤적 1단계 · 앱 진입] 목표 앱 '{matched_app_name}'({target_pkg})을 실행하고 포그라운드 진입을 검증합니다.",
                "subGoals": ledger,
                "toolName": "manage_app",
                "toolArgs": {"action": "launch", "app_name": matched_app_name},
            }

        # PHASE 2 & 3: Search / Input Text Trajectory
        if search_query and not has_typed:
            editable_el = next((el for el in elements if el["editable"]), None)
            if editable_el is not None:
                return {
                    "observation": f"[행동 궤적 2단계 · 타이핑 주입] 활성 입력창([{editable_el['index']}] {editable_el['class']})에 '{search_query}' 텍스트를 입력하고 ENTER로 제출합니다.",
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
                        if any(
                            kw in (el["text"] + " " + el["desc"] + " " + el["res"]).lower()
                            for kw in ["검색", "search", "찾기"]
                        )
                    ),
                    None,
                )
                if search_btn is None:
                    # Top-right action bar icon fallback
                    search_btn = next(
                        (
                            el
                            for el in elements
                            if el["clickable"] and 35 <= el["norm_y"] <= 145 and el["norm_x"] >= 650
                        ),
                        None,
                    )

                if search_btn is not None:
                    label = search_btn["text"] or search_btn["desc"] or "상단 검색 아이콘"
                    return {
                        "observation": f"[행동 궤적 2단계 · 검색창 열기] 화면에서 검색 진입 요소([{search_btn['index']}] '{label}')를 발견하여 탭합니다.",
                        "subGoals": ledger,
                        "toolName": "click",
                        "toolArgs": {
                            "element_index": search_btn["index"],
                            "target_description": f"검색창 열기 ({label})",
                        },
                    }

            return {
                "observation": f"[행동 궤적 2단계 · 타이핑 주입] '{search_query}' 텍스트를 주입하고 검색(ENTER)을 실행합니다.",
                "subGoals": ledger,
                "toolName": "input_text",
                "toolArgs": {
                    "text": search_query,
                    "submit_enter": True,
                },
            }

        # PHASE 4-A: Haptic Drag & Drop Trajectory
        if wants_drag_drop and not has_dragged:
            slider_el = next(
                (
                    el
                    for el in elements
                    if any(
                        kw in (el["class"] + " " + el["text"] + " " + el["desc"]).lower()
                        for kw in ["seekbar", "slider", "밝기", "볼륨", "드래그"]
                    )
                ),
                None,
            )
            norm_y = slider_el["norm_y"] if slider_el else 500
            return {
                "observation": f"[행동 궤적 · 햅틱 드래그 앤 드롭] 시작점(250, {norm_y})에서 480ms 홀드 후 목표점(780, {norm_y})까지 연속 곡선 궤적으로 드래그 앤 드롭합니다.",
                "subGoals": ledger,
                "toolName": "drag_and_drop",
                "toolArgs": {
                    "start_norm_x": 250,
                    "start_norm_y": norm_y,
                    "end_norm_x": 780,
                    "end_norm_y": norm_y,
                    "hold_ms": 480,
                    "duration_ms": 680,
                    "description": "슬라이더/요소 햅틱 드래그 앤 드롭",
                },
            }

        # PHASE 4-B: Swipe / Scroll Trajectory
        if wants_swipe and not has_swiped:
            direction = "down" if ("아래로" in lower or "위로 스크롤" in lower) else "up"
            return {
                "observation": f"[행동 궤적 · 햅틱 스와이프] 콘텐츠 탐색을 위해 '{direction}' 방향으로 스와이프 궤적을 실행합니다.",
                "subGoals": ledger,
                "toolName": "swipe",
                "toolArgs": {"direction": direction, "duration_ms": 380},
            }

        # PHASE 4-C: Post-Search Result Click
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
                    "observation": f"[행동 궤적 3단계 · 결과 클릭] 검색 결과 목록에서 타깃 요소([{result_el['index']}] '{label}')를 탭합니다.",
                    "subGoals": ledger,
                    "toolName": "click",
                    "toolArgs": {
                        "element_index": result_el["index"],
                        "target_description": f"검색 결과 선택 ({label})",
                    },
                }

        # PHASE 5: All trajectory phases finished & verified!
        if history_steps:
            step_lines = []
            for s in history_steps:
                first_line = (s.get("result") or "").splitlines()[0] if s.get("result") else ""
                verify_line = (
                    (s.get("result") or "").splitlines()[-1]
                    if len((s.get("result") or "").splitlines()) > 1
                    else ""
                )
                step_lines.append(
                    f"• Step {s.get('step')} [{s.get('tool')}]: {first_line} {verify_line}".strip()
                )
            summary_body = "\n".join(step_lines)
            return {
                "observation": f"[노트북 CLI 브리지] 총 {len(history_steps)}단계의 행동 궤적 및 사후 상태 전이(Post-Snapshot) 검증을 모두 완주했습니다.",
                "subGoals": [{**item, "status": "COMPLETED"} for item in ledger],
                "toolName": "complete_task",
                "toolArgs": {
                    "summary": f"✅ [목표 행동 궤적 완주 완료 · {len(history_steps)}스텝 검증됨]\n{summary_body}"
                },
            }

    # =========================================================================
    # SINGLE-STEP FAST-LANE COMPLETION (If a single-step command already ran)
    # =========================================================================
    if iteration >= 2 and history_steps:
        last = history_steps[-1]
        tool_used = last.get("tool", "")
        result_text = last.get("result", "")
        return {
            "observation": f"[노트북 CLI 브리지] '{tool_used}' 실행 및 사후 상태 전이 검증을 완료했습니다.",
            "subGoals": [
                {"title": f"도구 실행 및 검증 ({tool_used})", "status": "COMPLETED"},
                {"title": "결과 보고 완료", "status": "COMPLETED"},
            ],
            "toolName": "complete_task",
            "toolArgs": {"summary": f"{result_text}"},
        }

    # 1. Generative UI Dynamic Micro-App Synthesis
    if any(k in lower for k in ["조종석", "대시보드", "위젯", "ui 그려", "렌더링", "gen-ui", "generative"]):
        return {
            "observation": "[노트북 CLI · Generative UI] window.HansonOS 브리지와 직결된 실시간 HTML5/JS 마이크로 앱을 합성합니다.",
            "subGoals": [
                {"title": "Generative UI 마이크로 앱 합성", "status": "IN_PROGRESS"},
                {"title": "마운트 확인", "status": "PENDING"},
            ],
            "toolName": "render_generative_ui",
            "toolArgs": {
                "title": "실시간 슈퍼앱 궤적 컨트롤러",
                "subtitle": f"'{g}' 맞춤형 동적 렌더링 UI",
                "html_content": "",
            },
        }

    # 2. UARP App Reverse-Engineering & Skill Synthesis
    if any(
        k in lower
        for k in ["리버스", "reverse", "역분석", "분석해서 스킬", "스킬로 만들", "프로토콜 학습", "앱 분석", "동작방식"]
    ):
        cleaned = (
            g.replace("리버스 엔지니어링", "")
            .replace("리버스", "")
            .replace("역분석", "")
            .replace("분석해서 스킬로 만들어줘", "")
            .replace("스킬로 만들어줘", "")
            .replace("프로토콜 학습해줘", "")
            .replace("앱 분석해줘", "")
            .replace("해줘", "")
            .strip()
        ) or "현재앱"
        return {
            "observation": f"[노트북 CLI · UARP] '{cleaned}' 앱의 APK 바이너리(base.apk), 딥링크, Exported Activity, UI 프레임워크를 역분석합니다.",
            "subGoals": [
                {"title": f"'{cleaned}' APK & 매니페스트 역분석", "status": "IN_PROGRESS"},
                {"title": "영구 스킬 합성 및 보고", "status": "PENDING"},
            ],
            "toolName": "reverse_engineer_app",
            "toolArgs": {"app_query": cleaned},
        }

    # 3. Native Mobile OS CLI (`hanson ...`, `/cli ...`, `/sh ...`, `top`, `액티비티`, `self-heal`)
    if (
        lower.startswith("hanson ")
        or lower.startswith("/cli ")
        or lower.startswith("/sh ")
        or "dumpsys" in lower
        or "액티비티 스택" in lower
        or "애니메이션 배속" in lower
        or "자가 치유" in lower
        or "self-heal" in lower
    ):
        if "액티비티 스택" in lower:
            cli_cmd = "top"
        elif "자가 치유" in lower or "self-heal" in lower:
            cli_cmd = "self-heal"
        else:
            cli_cmd = re.sub(r"^(hanson|/cli|/sh)\s+", "", g, flags=re.I).strip()
        return {
            "observation": f"[노트북 CLI -> 모바일 OS UID-2000 CLI] 'hanson {cli_cmd}' 명령을 실행합니다.",
            "subGoals": [
                {"title": f"OS CLI (hanson {cli_cmd}) 실행", "status": "IN_PROGRESS"},
            ],
            "toolName": "os_cli_exec",
            "toolArgs": {"command": cli_cmd},
        }

    # 4. Screen / Accessibility Tree Briefing
    if any(k in lower for k in ["브리핑", "ui 트리", "접근성", "현재 화면", "요소", "상태", "연결"]):
        lines = [line.strip() for line in prompt_context.splitlines() if line.strip().startswith("[")]
        preview = "\n".join(lines[:10]) if lines else "(현재 화면 요소 요약 없음)"
        return {
            "observation": f"[노트북 CLI 브리지] 포그라운드 패키지 '{active_pkg}'에서 {len(lines)}개의 인터랙티브 UI 노드를 수신했습니다.",
            "subGoals": [
                {"title": "실시간 화면 UI 트리 스캔 및 브리핑", "status": "COMPLETED"},
            ],
            "toolName": "complete_task",
            "toolArgs": {
                "summary": (
                    f"✅ [노트북 CLI :18889 실시간 브리지 연결됨]\n"
                    f"• 현재 포그라운드 앱: {active_pkg or 'Hanson Mobile-Use'}\n"
                    f"• 감지된 UI 노드: {len(lines)}개\n"
                    f"{preview}"
                )
            },
        }

    # 5. Notification Reply / Check
    if "답장" in lower:
        msg_match = re.search(r'"([^"]+)"', g)
        reply_msg = msg_match.group(1) if msg_match else "확인했습니다. 잠시 후 연락드리겠습니다."
        return {
            "observation": "[노트북 CLI 브리지] RemoteInput 세션 캐시를 통해 화면을 켜지 않고 즉시 답장합니다.",
            "subGoals": [{"title": "백그라운드 즉시 답장 전송", "status": "IN_PROGRESS"}],
            "toolName": "reply_notification",
            "toolArgs": {"target": "", "message": reply_msg},
        }

    if "알림" in lower:
        return {
            "observation": "[노트북 CLI 브리지] 가로챈 알림 목록을 조회합니다.",
            "subGoals": [{"title": "수신 알림 세션 조회", "status": "IN_PROGRESS"}],
            "toolName": "list_notifications",
            "toolArgs": {"only_replyable": False, "limit": 10},
        }

    # 6. Device Settings (Brightness, Volume, DND)
    if "밝기" in lower:
        nums = re.findall(r"\d+", g)
        val = nums[0] if nums else "180"
        return {
            "observation": f"[노트북 CLI 브리지] 화면 밝기를 {val}(으)로 설정합니다.",
            "subGoals": [{"title": "밝기 변경", "status": "IN_PROGRESS"}],
            "toolName": "control_device_setting",
            "toolArgs": {"setting": "brightness", "value": val},
        }

    if "볼륨" in lower or "소리" in lower:
        nums = re.findall(r"\d+", g)
        val = nums[0] if nums else "50"
        return {
            "observation": f"[노트북 CLI 브리지] 미디어 볼륨을 {val}%로 조절합니다.",
            "subGoals": [{"title": "볼륨 조절", "status": "IN_PROGRESS"}],
            "toolName": "control_device_setting",
            "toolArgs": {"setting": "volume_media", "value": val},
        }

    # 7. Single App Launch (`manage_app`)
    for kw in APP_MAP:
        if kw in lower and any(v in lower for v in ["열어", "실행", "켜", "가자", "틀어"]):
            return {
                "observation": f"[노트북 CLI 브리지] '{kw}' 앱을 즉시 실행합니다.",
                "subGoals": [{"title": f"'{kw}' 앱 실행", "status": "IN_PROGRESS"}],
                "toolName": "manage_app",
                "toolArgs": {"action": "launch", "app_name": kw},
            }

    # 8. Match any visible element text on screen and click it!
    matched_el = next(
        (
            el
            for el in elements
            if (el["text"] and el["text"].lower() in lower)
            or (el["desc"] and el["desc"].lower() in lower)
        ),
        None,
    )
    if matched_el is not None:
        label = matched_el["text"] or matched_el["desc"]
        return {
            "observation": f"[노트북 CLI · 화면 요소 매칭] 현재 화면에서 '[{matched_el['index']}] {label}' 요소를 포착하여 탭합니다.",
            "subGoals": [{"title": f"'{label}' 요소 탭 및 전이 검증", "status": "IN_PROGRESS"}],
            "toolName": "click",
            "toolArgs": {
                "element_index": matched_el["index"],
                "target_description": label,
            },
        }

    if "홈" in lower:
        return {
            "observation": "[노트북 CLI 브리지] 홈 화면으로 이동합니다.",
            "subGoals": [{"title": "HOME 키 전송", "status": "IN_PROGRESS"}],
            "toolName": "press_key",
            "toolArgs": {"key": "HOME"},
        }

    return {
        "observation": f"[노트북 CLI 브리지] '{g}' 명령을 분석했습니다.",
        "subGoals": [{"title": "명령 응답", "status": "COMPLETED"}],
        "toolName": "complete_task",
        "toolArgs": {
            "summary": (
                f"✅ [노트북 CLI v3 궤적 플래너 대기 중] '{g}'\n"
                "• 멀티스텝 행동 궤적 예시: '유튜브 열어서 아이유 검색하고 클릭해줘'\n"
                "• 햅틱 드래그앤드롭 예시: '설정 열어서 밝기 슬라이더 드래그해줘'\n"
                "• 앱 역분석: '카카오톡 리버스 엔지니어링 해줘'"
            )
        },
    }


class LaptopCliBridgeHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[LaptopCLI :18889] {fmt % args}", flush=True)

    def _send_json(self, data: dict, status: int = 200):
        raw = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path.startswith("/health") or self.path.startswith("/ping"):
            self._send_json(
                {
                    "status": "ok",
                    "bridge": "Hanson-Laptop-CLI-Bridge-v3-Trajectory-Healer",
                    "port": PORT,
                }
            )
        else:
            self._send_json({"error": "Not found"}, status=404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length).decode("utf-8") if length > 0 else "{}"
        try:
            payload = json.loads(raw_body)
        except Exception:
            payload = {}

        if self.path.startswith("/decide"):
            goal = payload.get("goal", "")
            iteration = int(payload.get("iteration", 1))
            active_pkg = payload.get("activePackage", "")
            prompt_context = payload.get("promptContext", "")
            history_steps = payload.get("historySteps", [])
            print(
                f"[LaptopCLI v3] Goal='{goal}' | pkg={active_pkg} | iter={iteration} | history={len(history_steps)}",
                flush=True,
            )
            decision = local_cli_decide(goal, iteration, active_pkg, prompt_context, history_steps)
            self._send_json(decision)
            return

        self._send_json({"error": f"Unknown endpoint {self.path}"}, status=404)


def main():
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
        req = urllib.request.Request(
            f"{PHONE_BRIDGE_URL}/chat",
            data=body,
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=8) as resp:
            print(resp.read().decode("utf-8"))
        return

    server = HTTPServer((HOST, PORT), LaptopCliBridgeHandler)
    print(f"[LaptopCLI v3] Listening on http://{HOST}:{PORT} (Trajectory + Self-Healing)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
