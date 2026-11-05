#!/usr/bin/env python3
"""
Hanson Mobile-Use — Laptop CLI Bridge Daemon (:18889) & Phone Controller (:18888)
=================================================================================
양방향 ADB 터널 브리지:
  1. Phone -> Laptop CLI (`adb reverse tcp:18889 tcp:18889`)
     휴대폰 라이브 채팅에서 명령을 입력하면 `http://127.0.0.1:18889/decide`로
     현재 UI 트리 + 스크린샷 + 대화 내역이 전달되어 노트북 CLI가 추론 및 액션을 반환합니다.
  2. Laptop CLI -> Phone (`adb forward tcp:18888 tcp:18888`)
     노트북 터미널(또는 Antigravity/Codex 에이전트)에서 `http://127.0.0.1:18888/chat`으로
     직접 휴대폰 라이브 채팅창에 메시지를 주입하거나 자율 에이전트 목표를 실행할 수 있습니다.
"""

import json
import os
import sys
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

HOST = "127.0.0.1"
PORT = 18889
PHONE_BRIDGE_URL = "http://127.0.0.1:18888"


def call_gemini_if_available(goal: str, ui_elements: list, history: list, screenshot_b64: str):
    api_key = os.environ.get("GEMINI_API_KEY", "").strip()
    if not api_key:
        return None
    try:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={api_key}"
        prompt = (
            "You are Hanson Mobile-Use AI running via the Laptop CLI Bridge. "
            "Respond ONLY in JSON with keys: observation (Korean string), subGoals (array of Korean strings), "
            "toolName (string), toolArgs (object).\n"
            f"User Goal: {goal}\n"
            f"UI Elements (top 40): {json.dumps(ui_elements[:40], ensure_ascii=False)}\n"
            f"Recent History: {json.dumps(history[-5:], ensure_ascii=False)}"
        )
        parts = [{"text": prompt}]
        if screenshot_b64:
            parts.append({"inlineData": {"mimeType": "image/jpeg", "data": screenshot_b64}})
        payload = {
            "contents": [{"role": "user", "parts": parts}],
            "generationConfig": {"temperature": 0.1, "responseMimeType": "application/json"},
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            text = (
                body.get("candidates", [{}])[0]
                .get("content", {})
                .get("parts", [{}])[0]
                .get("text", "{}")
            )
            return json.loads(text)
    except Exception as exc:
        print(f"[LaptopCLI] Gemini API fallback error: {exc}", flush=True)
        return None


def local_cli_decide(goal: str, ui_elements: list, history: list) -> dict:
    """Deterministic Laptop CLI planner when no external API key is needed."""
    g = goal.strip()
    lower = g.lower()

    if history:
        last = history[-1]
        return {
            "observation": f"[노트북 CLI 브리지] 이전 액션({last.get('toolName')}) 결과를 확인했습니다: {last.get('resultSummary')}",
            "subGoals": ["노트북 CLI 루프 완료"],
            "toolName": "complete_goal",
            "toolArgs": {
                "summary": f"[노트북 CLI 직결 완료] '{g}' 요청을 수행했습니다. ({last.get('resultSummary')})"
            },
        }

    if any(k in lower for k in ["상태", "연결", "브리", "cli", "노트북", "안녕", "테스트", "체크"]):
        return {
            "observation": "노트북 CLI 브리지(:18889)가 ADB 양방향 터널로 휴대폰과 실시간 연결되어 있습니다.",
            "subGoals": ["연결 상태 응답"],
            "toolName": "complete_goal",
            "toolArgs": {
                "summary": (
                    "노트북 CLI 브리지(127.0.0.1:18889 <-> 127.0.0.1:18888)와 실시간 직결된 상태입니다! "
                    f"현재 화면에서 {len(ui_elements)}개의 UI 노드를 감지하고 있습니다."
                )
            },
        }

    app_map = {
        "카톡": "com.kakao.talk",
        "카카오톡": "com.kakao.talk",
        "유튜브": "com.google.android.youtube",
        "youtube": "com.google.android.youtube",
        "크롬": "com.android.chrome",
        " 설정": "com.android.settings",
        "갤러리": "com.sec.android.gallery3d",
        "카메라": "com.sec.android.app.camera",
    }
    for kw, pkg in app_map.items():
        if kw.strip() in lower:
            return {
                "observation": f"[노트북 CLI 브리지] '{kw.strip()}' 실행 요청을 감지했습니다.",
                "subGoals": [f"{pkg} 실행"],
                "toolName": "open_app",
                "toolArgs": {"package_name": pkg},
            }

    if "홈" in lower:
        return {
            "observation": "[노트북 CLI 브리지] 홈 화면으로 이동합니다.",
            "subGoals": ["홈 이동"],
            "toolName": "navigate_home",
            "toolArgs": {},
        }

    # UI 텍스트 매칭 클릭
    for el in ui_elements:
        label = (el.get("text") or el.get("contentDesc") or "").strip()
        if label and len(label) >= 2 and label.lower() in lower:
            return {
                "observation": f"[노트북 CLI 브리지] 화면 요소 '{label}'({el.get('centerX')}, {el.get('centerY')})를 클릭합니다.",
                "subGoals": [f"'{label}' 탭"],
                "toolName": "click",
                "toolArgs": {"x": el.get("centerX", 500), "y": el.get("centerY", 500)},
            }

    return {
        "observation": f"[노트북 CLI 브리지] '{g}' 명령을 수신했습니다 (화면 UI 노드 {len(ui_elements)}개 분석 완료).",
        "subGoals": ["라이브 채팅 응답 반환"],
        "toolName": "complete_goal",
        "toolArgs": {
            "summary": (
                f"[노트북 CLI 응답] '{g}' 메시지를 수신했습니다. "
                "앱 실행(예: '유튜브 열어줘'), 화면 제어(예: '홈으로 가줘'), 알림 답장('최근 알림에 확인이라고 답장해'), "
                "시스템 제어('밝기 80%', '방해금지 켜줘') 또는 노트북 CLI에서 직접 명령 주입이 가능합니다."
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
                    "bridge": "Hanson-Laptop-CLI-Bridge",
                    "port": PORT,
                    "gemini_api_configured": bool(os.environ.get("GEMINI_API_KEY")),
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
            ui_elements = payload.get("uiElements", [])
            history = payload.get("history", [])
            screenshot_b64 = payload.get("screenshotBase64")
            print(
                f"[LaptopCLI] Phone LiveChat Goal='{goal}' | UI elements={len(ui_elements)} | step={len(history)+1}",
                flush=True,
            )
            decision = call_gemini_if_available(goal, ui_elements, history, screenshot_b64)
            if not decision:
                decision = local_cli_decide(goal, ui_elements, history)
            self._send_json(decision)
            return

        self._send_json({"error": f"Unknown endpoint {self.path}"}, status=404)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "send":
        msg = " ".join(sys.argv[2:]) or "노트북 CLI에서 보낸 실시간 메시지입니다."
        req = urllib.request.Request(
            f"{PHONE_BRIDGE_URL}/chat",
            data=json.dumps({"sender": "CLI", "text": msg}).encode("utf-8"),
            headers={"Content-Type": "application/json; charset=utf-8"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            print(resp.read().decode("utf-8"))
        return

    server = HTTPServer((HOST, PORT), LaptopCliBridgeHandler)
    print(f"[LaptopCLI] Listening on http://{HOST}:{PORT} (Phone -> Laptop CLI Bridge)", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
