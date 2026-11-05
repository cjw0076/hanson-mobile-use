# Hanson Mobile-Use AI — Project State (`STATE.md`)

**Last Updated:** 2026-11-05
**Repository:** https://github.com/cjw0076/hanson-mobile-use
**Latest Release:** https://github.com/cjw0076/hanson-mobile-use/releases/tag/v1.0.0
**Figma Design & Live Code Layer:** https://www.figma.com/design/h30hXdpyD38CmUTTnt6NQ7?node-id=1-3&autoplay-code-preview=1-3

## 완료된 작업 (Completed)
1. **모바일 전용 네이티브 OS CLI (`hanson` — `UID 2000 shell` / `app_process` 바인더 데몬)**
   - [`HansonOsCliMain.kt`](app/src/main/java/com/hanson/mobileuse/oscli/HansonOsCliMain.kt): `/system/bin/app_process`를 통해 일반 앱 샌드박스(`u0_aXXX`)를 벗어나 `uid=2000(shell)`(커널 그룹 `input`, `log`, `uhid`, `readproc`, `inet`) 권한으로 구동되는 모바일 전용 CLI 및 로컬호스트 RPC 데몬(`127.0.0.1:18890`).
   - [`hanson-os-cli.sh`](hanson-os-cli.sh): `/data/local/tmp/hanson`에 설치되는 온디바이스 전용 CLI (`hanson status|top|tree|tap|swipe|text|key|app|setting|notif|provider|dump|self-heal|sh`).
   - [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml): Tier 6 영구 개발자/시스템 권한 (`WRITE_SECURE_SETTINGS`, `DUMP`, `READ_LOGS`) 추가.
   - [`ToolSpecRegistry.kt`](app/src/main/java/com/hanson/mobileuse/agent/ToolSpecRegistry.kt) & [`AgentToolExecutionHarness.kt`](app/src/main/java/com/hanson/mobileuse/agent/AgentToolExecutionHarness.kt): 22번째 에이전트 도구 `os_cli_exec` 탑재.
2. **상시 라이브 채팅 런타임 (Always-On Live Chat & Floating Chat Drawer)**
   - [`HansonRuntimeHolder.kt`](app/src/main/java/com/hanson/mobileuse/agent/HansonRuntimeHolder.kt): 프로세스 전역 싱글톤으로 앱 화면 전환/백그라운드 이동 시에도 라이브 채팅 세션 영구 유지.
   - [`FloatingHudService.kt`](app/src/main/java/com/hanson/mobileuse/service/FloatingHudService.kt): 카카오톡·유튜브 등 타 앱 위에서 **[채팅]** 버튼을 누르면 즉시 열리는 플로팅 라이브 채팅창 + 키보드 입력기.
   - [`CommandCenterScreen.kt`](app/src/main/java/com/hanson/mobileuse/ui/screens/CommandCenterScreen.kt): 메신저형 실시간 채팅 UI, 한국어 TTS 음성 브리핑 토글, 플로팅 채팅창 토글.
3. **노트북 CLI 양방향 브리지 (`:18888` ↔ `:18889`)**
   - [`laptop-cli-bridge.py`](laptop-cli-bridge.py): `adb reverse tcp:18889 tcp:18889` 및 `adb forward tcp:18888 tcp:18888`을 통한 노트북 CLI 실시간 추론 및 메시지 주입 데몬.
4. **빌드·테스트·배포 검증**
   - `testDebugUnitTest` (22개 도구 스키마 및 하네스 검증 통과) 및 `assembleRelease` 성공 (`hanson-mobile-use-v1.0.0-release.apk`).
   - GitHub `main` 브랜치 푸시 및 `v1.0.0` 릴리스 에셋(`hanson-mobile-use-v1.0.0-release.apk`, `hanson-os-cli.sh`) 업로드 완료.

## 다음 할 일 (Next Steps)
- 휴대폰 USB(또는 무선 디버깅 ADB) 연결 시 `adb install -r -g` 및 `/data/local/tmp/hanson` (`UID 2000` 데몬) 즉시 주입
- 필요 시 온디바이스 무선 ADB(Android 11~16 Wireless Debugging 페어링) 자동 부트스트랩 UI 추가
