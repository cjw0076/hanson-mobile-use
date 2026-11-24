package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Semantic app domain classification for Hanson's 20-App Detail Intelligence &
 * Proactive Chief-of-Staff AI Secretary.
 */
enum class AppDomainCategory(val labelKo: String) {
    MESSENGER("메신저"),
    WORK_CHAT("업무 채팅"),
    EMAIL("이메일"),
    RESERVATION("예약·교통"),
    DELIVERY("쇼핑·배달·택배"),
    FINANCE_ADVISORY("금융·결제(읽기전용)"),
    MOBILITY("지도·이동"),
    PRODUCTIVITY("생산성·협업"),
    GENERAL("일반")
}

/**
 * Per-app operational intelligence playbook describing safe GUI navigation tips,
 * notification intelligence hints, RemoteInput reply support, and strict read-only
 * finance boundaries.
 */
data class AppDetailPlaybook(
    val id: String,
    val appLabelKo: String,
    val domainCategory: AppDomainCategory,
    val packagePatterns: List<String>,
    val goalKeywords: List<String>,
    val supportsRemoteInputReply: Boolean = false,
    val readOnlyAdvisoryOnly: Boolean = false,
    val requiresConfirmationBeforeSend: Boolean = true,
    val defaultProposalTypes: List<String> = emptyList(),
    val guiGuidanceKo: String,
    val notificationExtractionHintKo: String = "",
    val deepLinkScheme: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("appLabelKo", appLabelKo)
        put("domainCategory", domainCategory.name)
        put("packagePatterns", JSONArray(packagePatterns))
        put("goalKeywords", JSONArray(goalKeywords))
        put("supportsRemoteInputReply", supportsRemoteInputReply)
        put("readOnlyAdvisoryOnly", readOnlyAdvisoryOnly)
        put("requiresConfirmationBeforeSend", requiresConfirmationBeforeSend)
        put("defaultProposalTypes", JSONArray(defaultProposalTypes))
        put("guiGuidanceKo", guiGuidanceKo)
        put("notificationExtractionHintKo", notificationExtractionHintKo)
        put("deepLinkScheme", deepLinkScheme)
    }
}

/**
 * Registry of 20+ real Korean & global app playbooks powering:
 * - Screen-off notification triage & noise/ad filtering
 * - Multi-turn Chief-of-Staff proactive proposal routing
 * - Strict banking/payment read-only advisory boundaries
 * - Context-aware GUI guidance (delegated from `AppSpecificTipBank`)
 */
object AppDetailPlaybookRegistry {

    val allPlaybooks: List<AppDetailPlaybook> = listOf(
        // 1. KakaoTalk
        AppDetailPlaybook(
            id = "kakaotalk",
            appLabelKo = "카카오톡",
            domainCategory = AppDomainCategory.MESSENGER,
            packagePatterns = listOf("com.kakao.talk"),
            goalKeywords = listOf("카카오톡", "카톡", "kakaotalk"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("CALENDAR_EVENT", "UNANSWERED_FOLLOWUP", "DEADLINE_TASK", "MEETING_LINK_PREP"),
            guiGuidanceKo = "카카오톡 팁: 상단 돋보기(검색) 아이콘으로 친구·대화방 이름을 찾은 뒤, 채팅방 하단 메시지 입력창을 눌러 전송하세요. 가능하면 화면을 켜지 않는 빠른 답장(reply_notification)을 우선 활용하세요.",
            notificationExtractionHintKo = "그룹 채팅방(conversationTitle)과 발신자(person)를 분리하고, 연속 메시지로 확정되는 약속·장소·시간을 통합 추출합니다."
        ),
        // 2. Slack
        AppDetailPlaybook(
            id = "slack",
            appLabelKo = "Slack",
            domainCategory = AppDomainCategory.WORK_CHAT,
            packagePatterns = listOf("com.Slack", "com.slack"),
            goalKeywords = listOf("슬랙", "slack"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK", "MEETING_LINK_PREP", "CALENDAR_EVENT", "UNANSWERED_FOLLOWUP"),
            guiGuidanceKo = "Slack 팁: 하단 홈/DM 탭에서 채널 또는 동료를 선택하고, 스레드 답장 여부를 확인한 뒤 메시지를 전송하세요. 업무 요청·리뷰·회의 링크는 즉시 일정·할일로 정리할 수 있습니다.",
            notificationExtractionHintKo = "채널명(#channel)과 멘션(@user), 리뷰·승인·마감 요청 및 Huddle/Zoom/Meet 링크를 우선 감지합니다."
        ),
        // 3. Microsoft Teams
        AppDetailPlaybook(
            id = "teams",
            appLabelKo = "Microsoft Teams",
            domainCategory = AppDomainCategory.WORK_CHAT,
            packagePatterns = listOf("com.microsoft.teams"),
            goalKeywords = listOf("팀즈", "teams", "microsoft teams"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("MEETING_LINK_PREP", "DEADLINE_TASK", "CALENDAR_EVENT", "UNANSWERED_FOLLOWUP"),
            guiGuidanceKo = "Microsoft Teams 팁: 하단 채팅·팀·일정 탭을 구분해 대화방에 진입하고, 화상회의 참가 전 마이크·카메라 상태를 확인하세요.",
            notificationExtractionHintKo = "Teams 회의 초대 링크(teams.microsoft.com)와 업무 마감·승인 요청을 우선 추출합니다."
        ),
        // 4. Discord
        AppDetailPlaybook(
            id = "discord",
            appLabelKo = "Discord",
            domainCategory = AppDomainCategory.WORK_CHAT,
            packagePatterns = listOf("com.discord"),
            goalKeywords = listOf("디스코드", "discord"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("CALENDAR_EVENT", "DEADLINE_TASK", "UNANSWERED_FOLLOWUP"),
            guiGuidanceKo = "Discord 팁: 좌측 서버 목록과 채널을 확인한 뒤 하단 입력창에 메시지를 작성하세요. 음성 채널 입장 버튼은 오탭하지 않도록 주의하세요.",
            notificationExtractionHintKo = "DM 및 멘션 알림에서 모임 시간, 협업 마감, 미답장 질문을 추출합니다."
        ),
        // 5. Telegram
        AppDetailPlaybook(
            id = "telegram",
            appLabelKo = "텔레그램",
            domainCategory = AppDomainCategory.MESSENGER,
            packagePatterns = listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"),
            goalKeywords = listOf("텔레그램", "telegram"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("CALENDAR_EVENT", "UNANSWERED_FOLLOWUP", "MEETING_LINK_PREP"),
            guiGuidanceKo = "텔레그램 팁: 상단 돋보기로 대화방을 검색해 진입하고, 봇/채널 공지보다 1:1·그룹 대화의 일정과 답장 요청을 우선 처리하세요.",
            notificationExtractionHintKo = "일반 대화방의 약속·미팅 링크·질문을 추출하고 홍보 채널 메시지는 억제합니다."
        ),
        // 6. LINE
        AppDetailPlaybook(
            id = "line",
            appLabelKo = "LINE",
            domainCategory = AppDomainCategory.MESSENGER,
            packagePatterns = listOf("jp.naver.line.android"),
            goalKeywords = listOf("라인", "line"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("CALENDAR_EVENT", "UNANSWERED_FOLLOWUP"),
            guiGuidanceKo = "LINE 팁: 하단 대화 탭에서 상단 검색바로 친구·대화방을 찾은 뒤 하단 입력란을 사용해 답장하세요.",
            notificationExtractionHintKo = "공식 계정 프로모션을 제외하고 실제 인물 대화의 약속·질문을 감지합니다."
        ),
        // 7. WhatsApp
        AppDetailPlaybook(
            id = "whatsapp",
            appLabelKo = "WhatsApp",
            domainCategory = AppDomainCategory.MESSENGER,
            packagePatterns = listOf("com.whatsapp", "com.whatsapp.w4b"),
            goalKeywords = listOf("왓츠앱", "whatsapp"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("CALENDAR_EVENT", "UNANSWERED_FOLLOWUP", "MEETING_LINK_PREP"),
            guiGuidanceKo = "WhatsApp 팁: 상단 검색 아이콘으로 대화 상대를 찾은 뒤 하단 입력창에서 메시지를 전송하고, 빠른 답장 알림이 있으면 RemoteInput으로 바로 응답하세요.",
            notificationExtractionHintKo = "한국어·영어 혼합 일정 표현(today/tomorrow/7:30pm)과 화상회의 링크를 함께 파싱합니다."
        ),
        // 8. Samsung / Google SMS
        AppDetailPlaybook(
            id = "sms",
            appLabelKo = "메시지(SMS)",
            domainCategory = AppDomainCategory.MESSENGER,
            packagePatterns = listOf(
                "com.samsung.android.messaging",
                "com.google.android.apps.messaging",
                "com.android.mms"
            ),
            goalKeywords = listOf("문자", "메시지", "sms", "mms"),
            supportsRemoteInputReply = true,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("RESERVATION_COMMUTE", "DELIVERY_TRACKING", "BILLING_SUBSCRIPTION_WATCH", "CALENDAR_EVENT", "UNANSWERED_FOLLOWUP"),
            guiGuidanceKo = "메시지(SMS) 팁: 발신번호와 대화 스레드를 확인한 뒤 답장하고, 광고·인증번호 문자는 자동 제안에서 제외합니다.",
            notificationExtractionHintKo = "OTP·광고 문자는 즉시 차단하고, 예약 확정·택배 도착·청구 마감·지인 약속 문자만 선별합니다."
        ),
        // 9. Gmail
        AppDetailPlaybook(
            id = "gmail",
            appLabelKo = "Gmail",
            domainCategory = AppDomainCategory.EMAIL,
            packagePatterns = listOf("com.google.android.gm"),
            goalKeywords = listOf("지메일", "gmail"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK", "MEETING_LINK_PREP", "RESERVATION_COMMUTE", "BILLING_SUBSCRIPTION_WATCH"),
            guiGuidanceKo = "Gmail 팁: 상단 검색창에서 보낸 사람이나 제목 키워드로 메일을 찾고, 답장·전달 전송 버튼은 반드시 사용자 확인 후 누르세요.",
            notificationExtractionHintKo = "제목과 본문 미리보기에서 회신 마감, Google Meet 초대, 항공·호텔 예약, 청구서 안내를 분류합니다."
        ),
        // 10. Outlook
        AppDetailPlaybook(
            id = "outlook",
            appLabelKo = "Outlook",
            domainCategory = AppDomainCategory.EMAIL,
            packagePatterns = listOf("com.microsoft.office.outlook"),
            goalKeywords = listOf("아웃룩", "outlook"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK", "MEETING_LINK_PREP", "CALENDAR_EVENT"),
            guiGuidanceKo = "Outlook 팁: 하단 메일·일정 탭을 전환해 업무 메일과 초대장을 확인하고, 외부 발송 메일은 전송 전 수신자와 본문을 재확인하세요.",
            notificationExtractionHintKo = "업무 일정 초대, Teams 회의 링크, 승인·견적 회신 요청을 우선 포착합니다."
        ),
        // 11. Naver Mail
        AppDetailPlaybook(
            id = "naver_mail",
            appLabelKo = "네이버 메일",
            domainCategory = AppDomainCategory.EMAIL,
            packagePatterns = listOf("com.nhn.android.mail"),
            goalKeywords = listOf("네이버 메일", "네이버메일", "naver mail"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK", "RESERVATION_COMMUTE", "BILLING_SUBSCRIPTION_WATCH"),
            guiGuidanceKo = "네이버 메일 팁: 받은메일함 상단 돋보기로 메일을 검색하고, 청구서·예약 확인 메일은 일정이나 메모로 정리하세요.",
            notificationExtractionHintKo = "프로모션·뉴스레터를 제외하고 마감 요청·예약 확정·청구 안내 메일을 분류합니다."
        ),
        // 12. Notion
        AppDetailPlaybook(
            id = "notion",
            appLabelKo = "Notion",
            domainCategory = AppDomainCategory.PRODUCTIVITY,
            packagePatterns = listOf("notion.id"),
            goalKeywords = listOf("노션", "notion"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK", "CALENDAR_EVENT"),
            guiGuidanceKo = "Notion 팁: 하단 검색 탭으로 문서·태스크를 빠르게 찾고, 댓글 멘션이나 마감일 변경 알림을 확인하세요.",
            notificationExtractionHintKo = "문서 멘션, 댓글 확인 요청, 회의록·마감일 지정 알림을 할일 제안으로 변환합니다."
        ),
        // 13. GitHub / Jira / Linear
        AppDetailPlaybook(
            id = "dev_tracker",
            appLabelKo = "GitHub·Jira·Linear",
            domainCategory = AppDomainCategory.PRODUCTIVITY,
            packagePatterns = listOf(
                "com.github.android",
                "com.atlassian.android.jira.core",
                "com.linear"
            ),
            goalKeywords = listOf("깃허브", "github", "지라", "jira", "리니어", "linear", "pr 리뷰"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DEADLINE_TASK"),
            guiGuidanceKo = "GitHub·Jira·Linear 팁: 인박스/알림 탭에서 할당된 이슈나 PR 리뷰 요청을 확인하고, 머지·상태 변경 전 변경 내용을 검토하세요.",
            notificationExtractionHintKo = "PR 리뷰 요청, 이슈 할당, 멘션 및 CI 실패 알림을 마감·할일 제안으로 정리합니다."
        ),
        // 14. CatchTable
        AppDetailPlaybook(
            id = "catchtable",
            appLabelKo = "캐치테이블",
            domainCategory = AppDomainCategory.RESERVATION,
            packagePatterns = listOf("catchtable", "co.kr.catchtable.android.catchtable_app"),
            goalKeywords = listOf("캐치테이블", "catchtable", "식당 예약"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("RESERVATION_COMMUTE", "CALENDAR_EVENT"),
            guiGuidanceKo = "캐치테이블 팁: 매장 검색 후 날짜·인원·시간 슬롯을 차례로 선택하고, 예약금 결제나 최종 예약 확정 직전에 사용자 확인을 받으세요.",
            notificationExtractionHintKo = "식당 예약 확정·방문 리마인더에서 매장명·시간을 추출해 캘린더와 네이버 지도 길찾기를 연결합니다.",
            deepLinkScheme = "nmap://search?query={query}&appname=com.hanson.mobileuse"
        ),
        // 15. Korail / SRT
        AppDetailPlaybook(
            id = "rail_korail_srt",
            appLabelKo = "코레일톡·SRT",
            domainCategory = AppDomainCategory.RESERVATION,
            packagePatterns = listOf("com.korail.talk", "com.srail.srt", "kr.co.srail.newapp"),
            goalKeywords = listOf("코레일톡", "코레일", "ktx", "srt", "기차표"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("RESERVATION_COMMUTE", "CALENDAR_EVENT"),
            guiGuidanceKo = "코레일톡·SRT 팁: 출발역·도착역·날짜·인원을 정확히 맞춘 뒤 열차 조회를 누르고, 매진 구간은 잔여좌석 감시 또는 선택 화면에서 확인하세요. 결제 확정 전 반드시 사용자 승인을 받으세요.",
            notificationExtractionHintKo = "승차권 예매 확정·출발 안내에서 출발역·시간을 추출해 캘린더, 출발 알람, 지도 경로를 제안합니다.",
            deepLinkScheme = "nmap://search?query={query}&appname=com.hanson.mobileuse"
        ),
        // 16. Naver Map
        AppDetailPlaybook(
            id = "naver_map",
            appLabelKo = "네이버 지도",
            domainCategory = AppDomainCategory.MOBILITY,
            packagePatterns = listOf("com.nhn.android.nmap"),
            goalKeywords = listOf("네이버 지도", "네이버지도", "길찾기"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = false,
            defaultProposalTypes = listOf("RESERVATION_COMMUTE"),
            guiGuidanceKo = "네이버 지도 팁: 상단 검색창에 장소·주소를 입력한 뒤 '도착'과 '길찾기' 버튼을 눌러 대중교통·도보·자동차 경로를 비교하세요.",
            notificationExtractionHintKo = "장소 딥링크(nmap://search)로 즉시 목적지 검색과 소요시간 확인을 연결합니다.",
            deepLinkScheme = "nmap://search?query={query}&appname=com.hanson.mobileuse"
        ),
        // 17. Kakao T
        AppDetailPlaybook(
            id = "kakao_t",
            appLabelKo = "카카오 T",
            domainCategory = AppDomainCategory.MOBILITY,
            packagePatterns = listOf("com.kakao.taxi"),
            goalKeywords = listOf("카카오 t", "카카오t", "카카오택시", "택시"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("RESERVATION_COMMUTE"),
            guiGuidanceKo = "카카오 T 팁: 출도착지가 맞는지 확인하고, 호출·결제 확정 버튼 직전에는 금액과 차량 종류를 사용자에게 꼭 확인받으세요.",
            notificationExtractionHintKo = "배차·예약 탑승 알림에서 출발 준비 상태를 추적합니다."
        ),
        // 18. Baemin / Coupang Eats (placed before Coupang for explicit food-delivery priority)
        AppDetailPlaybook(
            id = "food_delivery",
            appLabelKo = "배달의민족·쿠팡이츠",
            domainCategory = AppDomainCategory.DELIVERY,
            packagePatterns = listOf("com.sampleapp", "com.coupang.mobile.eats", "baemin"),
            goalKeywords = listOf("배달의민족", "배달의 민족", "배민", "쿠팡이츠", "쿠팡 이츠"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DELIVERY_TRACKING"),
            guiGuidanceKo = "배달의민족·쿠팡이츠 팁: 배달 주소 배지를 먼저 확인하고 첫 화면 배너·혜택 팝업을 닫은 뒤 상단 검색바에 메뉴를 입력하세요. 장바구니·최종 주문서 결제 단계에서는 반드시 멈추세요.",
            notificationExtractionHintKo = "배달 시작·도착 완료 알림만 수령 메모로 정리하며 공동현관/도어락 비밀번호는 절대 저장하지 않습니다."
        ),
        // 19. Coupang
        AppDetailPlaybook(
            id = "coupang",
            appLabelKo = "쿠팡",
            domainCategory = AppDomainCategory.DELIVERY,
            packagePatterns = listOf("com.coupang.mobile"),
            goalKeywords = listOf("쿠팡", "로켓배송"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("DELIVERY_TRACKING"),
            guiGuidanceKo = "쿠팡 팁: 상단 검색창에 상품명을 입력할 때 자동완성 추천을 활용하고, 바로구매·결제하기 버튼은 사용자 승인 후 진행하세요.",
            notificationExtractionHintKo = "로켓배송·배송완료 알림을 수령 리마인더로 정리하며 현관·보관함 비밀번호는 무조건 마스킹합니다."
        ),
        // 20. CJ / Hanjin / Lotte Parcel Apps
        AppDetailPlaybook(
            id = "parcel_tracking",
            appLabelKo = "택배(CJ·한진·롯데)",
            domainCategory = AppDomainCategory.DELIVERY,
            packagePatterns = listOf(
                "com.cj.logistics",
                "com.doortodoor",
                "com.hanjin",
                "com.lotteglogis",
                "com.sweettracker",
                "kr.co.cjlogistics"
            ),
            goalKeywords = listOf("택배", "cj대한통운", "한진택배", "롯데택배", "배송조회"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = false,
            requiresConfirmationBeforeSend = false,
            defaultProposalTypes = listOf("DELIVERY_TRACKING"),
            guiGuidanceKo = "택배 앱 팁: 운송장 배송 현황과 수령 위치만 확인하고, 무인택배함·공동현관 비밀번호는 메모나 외부 로그에 저장하지 마세요.",
            notificationExtractionHintKo = "배송 출발·배송 완료·보관함 도착 알림에서 수령 위치만 안전하게 기록하고 PIN 번호는 제거합니다."
        ),
        // 21. Finance Advisory (STRICT READ-ONLY HARDLINE)
        AppDetailPlaybook(
            id = "finance_advisory",
            appLabelKo = "금융·결제(읽기전용)",
            domainCategory = AppDomainCategory.FINANCE_ADVISORY,
            packagePatterns = listOf(
                "viva.republica.toss",
                "com.kakaobank.channel",
                "com.shinhan",
                "com.kbcard",
                "com.samsung.android.spay"
            ),
            goalKeywords = listOf("토스", "카카오뱅크", "카뱅", "신한", "kb카드", "삼성페이", "삼성월렛"),
            supportsRemoteInputReply = false,
            readOnlyAdvisoryOnly = true,
            requiresConfirmationBeforeSend = true,
            defaultProposalTypes = listOf("BILLING_SUBSCRIPTION_WATCH"),
            guiGuidanceKo = "토스·금융·결제 읽기 전용 팁: 은행·카드·간편결제 앱에서는 절대 자동 클릭이나 송금·결제를 실행하지 마세요. 청구 예정·구독 갱신·고액 알림은 리마인더나 메모 제안으로만 안내하고 인증·조작은 반드시 사용자에게 넘기세요.",
            notificationExtractionHintKo = "청구일·정기구독 갱신·고액 결제 알림을 읽기 전용 메모·리마인더로만 제안하며 카드번호·OTP는 즉시 폐기합니다."
        )
    )

    private val EXTRA_FINANCE_PACKAGE_PATTERNS: List<String> = listOf(
        "viva.republica.toss",
        "com.kakaobank",
        "com.kakaopay",
        "com.shinhan",
        "com.kbcard",
        "com.kbstar",
        "com.wooribank",
        "com.hanabank",
        "com.hanaskcard",
        "com.hyundaicard",
        "com.lottecard",
        "com.samsung.android.spay",
        "com.samsung.android.samsungpass",
        "nh.smart",
        "com.kbankwith",
        "com.upbit",
        "com.bithumb",
        "com.paypal",
        "com.venmo",
        "com.coinbase"
    )

    private val EXACT_PACKAGE_LABELS_KO: Map<String, String> = mapOf(
        "com.kakao.talk" to "카카오톡",
        "com.slack" to "Slack",
        "com.microsoft.teams" to "Microsoft Teams",
        "com.discord" to "Discord",
        "org.telegram.messenger" to "텔레그램",
        "org.telegram.messenger.web" to "텔레그램",
        "jp.naver.line.android" to "LINE",
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.samsung.android.messaging" to "메시지(SMS)",
        "com.google.android.apps.messaging" to "메시지(SMS)",
        "com.android.mms" to "메시지(SMS)",
        "com.google.android.gm" to "Gmail",
        "com.microsoft.office.outlook" to "Outlook",
        "com.nhn.android.mail" to "네이버 메일",
        "notion.id" to "Notion",
        "com.github.android" to "GitHub",
        "com.atlassian.android.jira.core" to "Jira",
        "com.linear" to "Linear",
        "co.kr.catchtable.android.catchtable_app" to "캐치테이블",
        "com.korail.talk" to "코레일톡",
        "com.srail.srt" to "SRT",
        "kr.co.srail.newapp" to "SRT",
        "com.nhn.android.nmap" to "네이버 지도",
        "com.kakao.taxi" to "카카오 T",
        "com.coupang.mobile" to "쿠팡",
        "com.sampleapp" to "배달의민족",
        "com.coupang.mobile.eats" to "쿠팡이츠",
        "com.cj.logistics" to "CJ대한통운",
        "com.doortodoor" to "CJ대한통운",
        "com.hanjin.parcel" to "한진택배",
        "com.lotteglogis" to "롯데택배",
        "viva.republica.toss" to "토스",
        "com.kakaobank.channel" to "카카오뱅크",
        "com.shinhan.sbanking" to "신한 SOL뱅크",
        "com.kbcard.cxh.appcard" to "KB Pay",
        "com.samsung.android.spay" to "삼성 월렛"
    )

    private val AD_MARKER_REGEX = Regex(
        "(?i)(?:\\(광고\\)|\\[광고\\]|^광고\\)|\\(ad\\)|\\[ad\\]|무료\\s*수신\\s*거부|수신\\s*거부|광고성\\s*정보|특가\\s*세일|선착순\\s*쿠폰|할인\\s*쿠폰|포인트\\s*소멸|카카오톡\\s*채널\\s*추가|이벤트\\s*당첨)"
    )
    private val WEB_PROMO_PREFIX_REGEX = Regex("(?:\\[\\s*web발신\\s*\\]|\\[\\s*국외발신\\s*\\])", RegexOption.IGNORE_CASE)
    private val PROMO_KEYWORD_REGEX = Regex(
        "(?i)(?:할인|특가|세일|쿠폰|프로모션|이벤트|혜택|적립|무료체험|지금\\s*신청|바로가기|대출\\s*한도|최저\\s*금리|마케팅|뉴스레터|unsubscribe|promo\\s*code)"
    )
    private val KAKAO_CHANNEL_SENDER_REGEX = Regex(
        "(?:채널|플러스친구|공식몰|공식\\s*스토어|쇼핑라이브|브랜드\\s*스토어|마케팅|이벤트\\s*알림|혜택\\s*알림)"
    )
    private val OTP_NOISE_REGEX = Regex(
        "(?i)(?:\\botp\\b|인증번호|인증\\s*코드|보안코드|보안\\s*번호|본인확인\\s*번호|확인코드|verification\\s*code|one[-\\s]?time\\s*(?:pass)?code|passcode|\\b2fa\\b|^\\s*\\d{4,8}\\s*$)"
    )
    private val PLACEHOLDER_MESSAGE_REGEX = Regex(
        "(?i)^(?:이모티콘(?:을\\s*보냈습니다\\.?|입니다\\.?)?|사진(?:을\\s*보냈습니다\\.?|\\s*\\d+장)?|동영상(?:을\\s*보냈습니다\\.?)?|음성\\s*메시지(?:를\\s*보냈습니다\\.?)?|스티커(?:를\\s*보냈습니다\\.?)?|파일(?:을\\s*보냈습니다\\.?)?|연락처(?:를\\s*보냈습니다\\.?)?|선물(?:을\\s*보냈습니다\\.?)?|sticker|photo|image|video|gif|voice\\s*message|[ㅋㅎㅠㅜㅇ네응넵]+)[!?.~\\s]*$"
    )
    private val MEANINGFUL_CHAR_REGEX = Regex("[\\p{L}\\p{N}]")

    /**
     * Finds the best matching playbook for an Android package name using exact match first,
     * then longest substring match (so `com.coupang.mobile.eats` never shadows behind `com.coupang.mobile`).
     */
    fun findByPackage(pkg: String): AppDetailPlaybook? {
        val clean = pkg.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null

        // 1. Exact pattern match
        for (playbook in allPlaybooks) {
            if (playbook.packagePatterns.any { it.lowercase(Locale.ROOT) == clean }) {
                return playbook
            }
        }

        // 2. Longest substring match
        var best: AppDetailPlaybook? = null
        var bestLen = -1
        for (playbook in allPlaybooks) {
            for (pattern in playbook.packagePatterns) {
                val pLower = pattern.lowercase(Locale.ROOT)
                if (pLower.isNotEmpty() && clean.contains(pLower) && pLower.length > bestLen) {
                    best = playbook
                    bestLen = pLower.length
                }
            }
        }
        return best
    }

    /**
     * Resolves playbook by active package first, falling back to longest keyword match in user goal.
     */
    fun findByGoalOrPackage(pkg: String, goal: String): AppDetailPlaybook? {
        findByPackage(pkg)?.let { return it }

        val cleanGoal = goal.trim().lowercase(Locale.ROOT)
        if (cleanGoal.isEmpty()) return null

        var best: AppDetailPlaybook? = null
        var bestLen = -1
        for (playbook in allPlaybooks) {
            for (kw in playbook.goalKeywords) {
                val kwLower = kw.lowercase(Locale.ROOT)
                if (kwLower.isNotEmpty() && cleanGoal.contains(kwLower) && kwLower.length > bestLen) {
                    best = playbook
                    bestLen = kwLower.length
                }
            }
        }
        return best
    }

    /**
     * Returns a friendly Korean app label for notifications and UI badges, or null if unknown.
     */
    fun resolveAppLabelKo(pkg: String): String? {
        val clean = pkg.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return null
        EXACT_PACKAGE_LABELS_KO[clean]?.let { return it }
        return when {
            clean.contains("shinhan") -> "신한"
            clean.contains("kbcard") -> "KB국민카드"
            clean.contains("kakaobank") -> "카카오뱅크"
            clean.contains("toss") -> "토스"
            clean.contains("hanjin") -> "한진택배"
            clean.contains("lotteglogis") -> "롯데택배"
            clean.contains("cj.logistics") || clean.contains("doortodoor") || clean.contains("cjlogistics") -> "CJ대한통운"
            clean.contains("catchtable") -> "캐치테이블"
            clean.contains("srail") -> "SRT"
            else -> findByPackage(clean)?.appLabelKo
        }
    }

    /**
     * Hardline check: returns true for ALL banking, fintech, card, and payment apps.
     * These apps are strictly read-only advisory (NEVER auto-click or execute financial actions).
     */
    fun isFinanceReadOnlyPackage(pkg: String): Boolean {
        val clean = pkg.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty()) return false
        if (findByPackage(clean)?.readOnlyAdvisoryOnly == true) return true
        return EXTRA_FINANCE_PACKAGE_PATTERNS.any { clean.contains(it) }
    }

    /**
     * Filters out advertising, promo SMS, KakaoTalk channel spam, OTP/verification codes,
     * and pure emoji/sticker/photo placeholders before reaching the Chief-of-Staff engine.
     */
    fun isNoiseNotification(pkg: String, sender: String, text: String): Boolean {
        val trimmedText = text.trim()
        val trimmedSender = sender.trim()
        if (trimmedText.isEmpty()) return true

        // 1. Pure emoji / symbols / punctuation with no letters or digits
        if (!MEANINGFUL_CHAR_REGEX.containsMatchIn(trimmedText)) return true

        // 2. Placeholder stickers / photos / trivial laughs
        if (PLACEHOLDER_MESSAGE_REGEX.matches(trimmedText)) return true

        // 3. OTP / verification codes
        if (OTP_NOISE_REGEX.containsMatchIn(trimmedText)) return true

        // 4. Explicit ad markers in sender or body
        if (AD_MARKER_REGEX.containsMatchIn(trimmedText) || AD_MARKER_REGEX.containsMatchIn(trimmedSender)) {
            return true
        }

        // 5. [Web발신] promotional SMS (preserve transactional reservation/delivery/billing SMS)
        if (WEB_PROMO_PREFIX_REGEX.containsMatchIn(trimmedText) && PROMO_KEYWORD_REGEX.containsMatchIn(trimmedText)) {
            return true
        }

        // 6. KakaoTalk channel / brand promo notifications
        val pkgLower = pkg.trim().lowercase(Locale.ROOT)
        if (pkgLower.contains("com.kakao.talk")) {
            if (KAKAO_CHANNEL_SENDER_REGEX.containsMatchIn(trimmedSender)) return true
            if (PROMO_KEYWORD_REGEX.containsMatchIn(trimmedText) && trimmedText.contains("무료")) return true
        }

        return false
    }

    /**
     * Builds a safe URL-encoded Naver Map search deep link for one-tap commute / location lookup.
     */
    fun buildDeepLinkForPlace(place: String): String {
        val cleaned = place.trim()
        if (cleaned.isEmpty()) return ""
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.name())
        return "nmap://search?query=$encoded&appname=com.hanson.mobileuse"
    }

    /**
     * Builds an HTTPS web fallback Naver Map search URL for devices without Naver Map installed.
     */
    fun buildWebFallbackMapUrl(place: String): String {
        val cleaned = place.trim()
        if (cleaned.isEmpty()) return ""
        val encoded = URLEncoder.encode(cleaned, StandardCharsets.UTF_8.name())
        return "https://map.naver.com/p/search/$encoded"
    }

    fun toSummaryJson(): JSONObject = JSONObject().apply {
        put("count", allPlaybooks.size)
        val arr = JSONArray()
        allPlaybooks.forEach { arr.put(it.toJson()) }
        put("playbooks", arr)
    }
}
