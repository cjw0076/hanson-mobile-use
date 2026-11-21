package com.hanson.mobileuse.agent

data class MobileUseSkill(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val goalPrompt: String,
    val badge: String
)

/**
 * Curated Mobile-Use Autonomous Routines & Macros combining Google Artemis GUI automation,
 * Hanson Screen-Off Quick Reply, and EZmap OS Fast-Lane controls.
 */
object SkillCatalog {

    val defaultSkills: List<MobileUseSkill> = listOf(
        MobileUseSkill(
            id = "screen_off_kakao_reply",
            title = "화면 꺼짐 카톡·메신저 0.2초 자동 답장",
            category = "Screen-Off Fast-Lane",
            description = "최근 수신된 카카오톡/SMS/텔레그램 알림을 조회하고 RemoteInput 토큰으로 앱 실행 없이 즉시 답장합니다.",
            goalPrompt = "최근 수신된 알림을 확인하고 가장 최근 답장 가능한 메시지에 \"회의 중이라 확인 후 연락드리겠습니다!\"라고 즉시 답장해줘.",
            badge = "RemoteInput <200ms"
        ),
        MobileUseSkill(
            id = "focus_meeting_mode",
            title = "집중·회의 모드 원탭 세팅 (DND + 볼륨 + 밝기)",
            category = "OS System Control",
            description = "방해금지 모드(DND) 활성화, 미디어 볼륨 20%, 화면 밝기 120으로 즉시 설정하고 회의 시작 메모를 저장합니다.",
            goalPrompt = "방해금지 모드를 켜고, 미디어 볼륨을 20으로 낮추고, 화면 밝기를 120으로 설정한 뒤 '회의_상태' 메모에 '집중 회의 모드 활성화됨'을 저장해줘.",
            badge = "Tier 3 OS Fast-Lane"
        ),
        MobileUseSkill(
            id = "youtube_search_play",
            title = "유튜브 실행 및 영상 자동 검색·재생",
            category = "Artemis GUI Operator",
            description = "유튜브 앱을 실행하고 검색창을 찾아 'Lo-Fi 코딩 음악'을 입력한 뒤 첫 번째 영상을 탭하여 재생합니다.",
            goalPrompt = "유튜브 앱을 실행하고 'Lo-Fi 코딩 음악'을 검색해서 첫 번째 영상을 재생해줘.",
            badge = "Artemis Vision + GUI"
        ),
        MobileUseSkill(
            id = "kakao_chat_gui_send",
            title = "카카오톡 오픈채팅/친구 검색 및 메시지 전송",
            category = "Artemis GUI Operator",
            description = "카카오톡을 실행하고 채팅 탭에서 대상을 검색한 후 입력창에 텍스트를 입력해 전송합니다.",
            goalPrompt = "카카오톡을 열어 현재 화면의 채팅방 목록을 확인하고 나와의 채팅방에 'Hanson Mobile-Use 테스트 완료' 메시지를 보내줘.",
            badge = "Artemis Multi-Step"
        ),
        MobileUseSkill(
            id = "morning_briefing_alarm",
            title = "기상 알람 등록 및 오늘의 캘린더 일정 브리핑",
            category = "PIM & Calendar",
            description = "내일 아침 7시 30분 알람을 설정하고 다가오는 캘린더 일정을 조회하여 요약합니다.",
            goalPrompt = "오전 7시 30분 알람을 설정하고, 다가오는 캘린더 일정을 조회해서 요약해줘.",
            badge = "Tier 4 Calendar/Alarm"
        ),
        MobileUseSkill(
            id = "ui_audit_snapshot",
            title = "현재 화면 멀티윈도우 접근성 트리 정밀 진단",
            category = "Accessibility Inspector",
            description = "현재 화면의 모든 윈도우 레이어와 클릭/입력 가능한 요소를 스캔하여 요약 보고서를 생성합니다.",
            goalPrompt = "현재 화면의 접근성 UI 요소들을 분석하고 주요 버튼과 구성을 요약해줘.",
            badge = "Tier 1 HierarchyDumper"
        )
    )
}
