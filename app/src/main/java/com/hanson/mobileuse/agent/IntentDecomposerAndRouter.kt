package com.hanson.mobileuse.agent

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import org.json.JSONObject
import java.util.Locale

/**
 * Top-level intent categories emitted by [IntentDecomposerAndRouter].
 *
 * Modernized (B4 / P1 Deconstruction):
 * Almost all natural-language requests route directly to [MULTI_STEP_GUI_OR_CHAT] so the real
 * LLM/VLM handles reasoning, tool selection, and visual grounding. Only two ultra-strict,
 * anchored fast-lanes remain:
 * 1. Single-word external-app navigation (`"뒤로"`, `"홈"` ONLY when `activePackage` is non-blank and not Hanson).
 * 2. Strictly anchored explicit numeric alarm/timer creation (`"3분 타이머 맞춰줘"`, `"오전 7시 30분 알람 맞춰줘"`).
 */
enum class IntentCategory {
    /** Single-word external-app navigation reflex (`"뒤로"`, `"홈"`). */
    REFLEX_MICRO_CONTROL,

    /** Strictly anchored explicit numeric OS alarm/timer creation. */
    EVERYDAY_OS_ACTION,

    /** Retained enum constant for caller compatibility; routed to real LLM. */
    COMPOUND_LIFESTYLE_ROUTINE,

    /** Retained enum constant for caller compatibility; screen clicks handled by real VLM. */
    SCREEN_ELEMENT_TARGET,

    /** Retained enum constant for caller compatibility; media intents handled by real LLM. */
    MEDIA_SEARCH_AND_PLAY,

    /** Retained enum constant for caller compatibility; code/media synthesis invoked by real LLM. */
    GENERATIVE_CODE_OR_MEDIA,

    /** Default lane: full LLM/VLM reasoning, web grounding, and multi-step GUI control. */
    MULTI_STEP_GUI_OR_CHAT
}

enum class ScrollStepSize(val normStartY: Int, val normEndY: Int, val durationMs: Long, val labelKo: String) {
    SMALL_ONE_NOTCH(620, 380, 210L, "한 칸"),
    NORMAL(700, 260, 250L, ""),
    LARGE(820, 140, 220L, "크게")
}

enum class SpatialHint {
    NONE,
    UPPER,
    LOWER,
    FIRST,
    SECOND,
    THIRD,
    FOURTH,
    LAST
}

data class DecomposedUserIntent(
    val rawPrompt: String,
    val primaryCategory: IntentCategory,
    val cleanTargetOrSearchQuery: String = "",
    val spatialHint: SpatialHint = SpatialHint.NONE,
    val scrollDirection: String? = null,
    val scrollStepSize: ScrollStepSize = ScrollStepSize.NORMAL,
    val isContextualFollowUp: Boolean = false,
    val isExplicitInAppMedia: Boolean = false,
    val autoCompleteSingleTurn: Boolean = false,
    val fastDecision: ModelTurnDecision? = null,
    val structuredCard: StructuredUiCard? = null
)

/**
 * Minimal, strictly anchored fast-lane router and UI safety helper (`IntentDecomposerAndRouter`).
 *
 * Non-goals (all routed to the real LLM/VLM):
 * - No substring keyword hijacks (`"내려"`, `"올려"`, `"라이트"`, `"일정"`, `"메모"`, `"유튜브"`, `"차트"`, `"분석"`, `"검색"`).
 * - No fuzzy Jamo screen-element clicks.
 * - No silent defaults for missing alarm times or timer durations.
 */
object IntentDecomposerAndRouter {

    private const val HANSON_PACKAGE_PREFIX = "com.hanson.mobileuse"

    private val NEGATIVE_OR_NON_CREATE_CUE_REGEX = Regex(
        "(?:꺼|끄|해제|취소|삭제|지워|지우|멈춰|멈추|정지|중지|볼륨|음량|소리|몇|목록|리스트|보여|확인|알려|어떻게|왜|남은|그리고|하고\\s|한\\s*뒤|한\\s*다음|\\?)"
    )

    private val EXPLICIT_ALARM_CREATE_SUFFIX_REGEX = Regex(
        "(?:맞춰\\s*줘|맞춰줘|맞춰\\s*주세요|맞춰|설정해\\s*줘|설정해줘|설정해\\s*주세요|설정|걸어\\s*줘|걸어줘|등록해\\s*줘|등록해줘|등록)[.!~]*$"
    )

    private val EXPLICIT_TIMER_CREATE_SUFFIX_REGEX = Regex(
        "(?:맞춰\\s*줘|맞춰줘|맞춰\\s*주세요|맞춰|설정해\\s*줘|설정해줘|설정해\\s*주세요|설정|시작해\\s*줘|시작해줘|시작해\\s*주세요|시작|걸어\\s*줘|걸어줘|돌려\\s*줘|돌려줘)[.!~]*$"
    )

    private val EXACT_APP_ALIAS_CATALOG: List<Triple<String, String, String>> = listOf(
        Triple("배달의민족", "배달의민족", "com.sampleapp"),
        Triple("배민", "배달의민족", "com.sampleapp"),
        Triple("쿠팡이츠", "쿠팡이츠", "com.coupang.mobile.eats"),
        Triple("요기요", "요기요", "com.fineapp.yogiyo"),
        Triple("카카오택시", "카카오T", "com.kakao.taxi"),
        Triple("카카오t", "카카오T", "com.kakao.taxi"),
        Triple("카카오페이", "카카오페이", "com.kakaopay.app"),
        Triple("카카오뱅크", "카카오뱅크", "com.kakaobank.channel"),
        Triple("카뱅", "카카오뱅크", "com.kakaobank.channel"),
        Triple("카카오맵", "카카오맵", "net.daum.android.map"),
        Triple("카카오톡", "카카오톡", "com.kakao.talk"),
        Triple("카톡", "카카오톡", "com.kakao.talk"),
        Triple("유튜브뮤직", "유튜브 뮤직", "com.google.android.apps.youtube.music"),
        Triple("유튜브", "유튜브", "com.google.android.youtube"),
        Triple("youtube", "유튜브", "com.google.android.youtube"),
        Triple("네이버지도", "네이버 지도", "com.nhn.android.nmap"),
        Triple("네이버카페", "네이버 카페", "com.nhn.android.navercafe"),
        Triple("네이버", "네이버", "com.nhn.android.search"),
        Triple("쿠팡", "쿠팡", "com.coupang.mobile"),
        Triple("토스", "토스", "viva.republica.toss"),
        Triple("당근마켓", "당근", "com.towneers.www"),
        Triple("당근", "당근", "com.towneers.www"),
        Triple("인스타그램", "인스타그램", "com.instagram.android"),
        Triple("인스타", "인스타그램", "com.instagram.android"),
        Triple("chatgpt", "ChatGPT", "com.openai.chatgpt"),
        Triple("크롬", "크롬", "com.android.chrome"),
        Triple("chrome", "크롬", "com.android.chrome"),
        Triple("갤러리", "갤러리", "com.sec.android.gallery3d"),
        Triple("카메라", "카메라", "com.sec.android.app.camera"),
        Triple("시계", "시계", "com.sec.android.app.clockpackage"),
        Triple("설정", "설정", "com.android.settings")
    )

    /**
     * Self-blindness shield: returns true if [el] belongs to Hanson itself or Samsung Edge Panel.
     */
    fun isHansonOwnElement(el: IndexedUiElement): Boolean {
        return el.packageName.startsWith(HANSON_PACKAGE_PREFIX) ||
            el.packageName.contains("cocktailbar", ignoreCase = true) ||
            el.resourceId.contains("cocktailbar", ignoreCase = true) ||
            el.contentDesc.equals("Edge 패널", ignoreCase = true) ||
            el.text.equals("Edge 패널", ignoreCase = true)
    }

    /**
     * Returns true ONLY when [snapshot] represents a non-blank external foreground app
     * (never blank and never `com.hanson.mobileuse*`).
     */
    fun isExternalAppSnapshot(snapshot: AtomicSnapshot?): Boolean {
        if (snapshot == null) return false
        val pkg = snapshot.activePackage.trim()
        return pkg.isNotEmpty() && !pkg.startsWith(HANSON_PACKAGE_PREFIX)
    }

    /**
     * Master entry point. Only intercepts:
     * 1. Single-word `"뒤로"` / `"홈"` when viewing an external foreground app.
     * 2. Strictly anchored explicit numeric alarm/timer creation commands.
     * Everything else returns [IntentCategory.MULTI_STEP_GUI_OR_CHAT] with `fastDecision = null`.
     */
    fun decompose(
        rawPrompt: String,
        snapshot: AtomicSnapshot? = null,
        chatHistory: List<LiveChatMessage> = emptyList(),
        isMainActivityInForeground: Boolean = true
    ): DecomposedUserIntent {
        val p = rawPrompt.trim()
        val pLower = p.lowercase(Locale.ROOT)
        if (p.isEmpty()) {
            return DecomposedUserIntent(rawPrompt = p, primaryCategory = IntentCategory.MULTI_STEP_GUI_OR_CHAT)
        }

        // 1. Single-word external-app navigation ONLY when activePackage is non-blank and not Hanson
        tryBuildSingleWordExternalNavIntent(p, pLower, snapshot)?.let { return it }

        // 2. Strictly anchored explicit numeric alarm / timer creation fast-lane
        tryBuildStrictAlarmOrTimerIntent(p, pLower)?.let { return it }

        // 3. Default: route everything else directly to the real LLM / VLM
        return DecomposedUserIntent(
            rawPrompt = p,
            primaryCategory = IntentCategory.MULTI_STEP_GUI_OR_CHAT
        )
    }

    private fun tryBuildSingleWordExternalNavIntent(
        p: String,
        pLower: String,
        snapshot: AtomicSnapshot?
    ): DecomposedUserIntent? {
        if (!isExternalAppSnapshot(snapshot)) return null
        val normalized = pLower.trimEnd('.', '!', '~', ' ')
        return when (normalized) {
            "뒤로" -> {
                val reply = "이전 화면으로 이동했어요."
                DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "외부 앱 뒤로가기 실행",
                        subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                        toolName = "press_key",
                        toolArgs = JSONObject().put("key", "BACK"),
                        spokenReply = reply
                    )
                )
            }
            "홈" -> {
                val reply = "홈 화면으로 이동했어요."
                DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "외부 앱 홈 이동 실행",
                        subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                        toolName = "press_key",
                        toolArgs = JSONObject().put("key", "HOME"),
                        spokenReply = reply
                    )
                )
            }
            else -> null
        }
    }

    private fun tryBuildStrictAlarmOrTimerIntent(
        p: String,
        pLower: String
    ): DecomposedUserIntent? {
        if (p.length > 52) return null
        if (NEGATIVE_OR_NON_CREATE_CUE_REGEX.containsMatchIn(pLower)) return null

        // Explicit numeric alarm creation (e.g., "오전 7시 30분 알람 맞춰줘", "19:00 알람 설정해줘")
        if (pLower.contains("알람") && !pLower.contains("타이머") && EXPLICIT_ALARM_CREATE_SUFFIX_REGEX.containsMatchIn(pLower)) {
            val parsed = parseExplicitNumericAlarmOrNull(p, pLower) ?: return null
            val (hour24, minute, alarmLabel) = parsed
            val amPmKo = if (hour24 < 12) "오전" else "오후"
            val displayHour = when {
                hour24 == 0 -> 12
                hour24 > 12 -> hour24 - 12
                else -> hour24
            }
            val timeStr = "%s %d시%s".format(
                amPmKo,
                displayHour,
                if (minute > 0) " ${minute}분" else ""
            )
            val padTime = "%02d:%02d".format(hour24, minute)
            val reply = "$timeStr($padTime) 알람을 설정했어요."
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏰ $timeStr 알람 설정 완료",
                headerSubtitle = "$padTime · $alarmLabel",
                items = listOf(
                    StructuredUiItem(
                        badge = "ON",
                        title = "$timeStr ($padTime)",
                        subtitle = "레이블: $alarmLabel",
                        meta = "시계",
                        actionUrlOrCmd = ""
                    )
                ),
                footerHint = "시스템 시계에 등록되었습니다."
            )
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = padTime,
                autoCompleteSingleTurn = true,
                structuredCard = card,
                fastDecision = ModelTurnDecision(
                    observation = "명시적 숫자 알람 등록 ($padTime · $alarmLabel)",
                    subGoals = listOf(SubGoalItem("$timeStr 알람 설정", SubGoalStatus.COMPLETED)),
                    toolName = "set_alarm",
                    toolArgs = JSONObject().apply {
                        put("hour", hour24)
                        put("minute", minute)
                        put("label", alarmLabel)
                    },
                    spokenReply = reply
                )
            )
        }

        // Explicit numeric timer creation (e.g., "3분 타이머 맞춰줘", "라면 타이머 4분 30초 시작해줘")
        if (pLower.contains("타이머") && !pLower.contains("알람") && EXPLICIT_TIMER_CREATE_SUFFIX_REGEX.containsMatchIn(pLower)) {
            val parsed = parseExplicitNumericTimerOrNull(p, pLower) ?: return null
            val (totalSeconds, timerLabel, durationKo) = parsed
            val reply = "$durationKo 타이머($timerLabel)를 시작했어요."
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏱️ $durationKo 타이머 시작",
                headerSubtitle = "$timerLabel · 총 ${totalSeconds}초 카운트다운",
                items = listOf(
                    StructuredUiItem(
                        badge = "RUNNING",
                        title = "$durationKo 카운트다운 진행 중",
                        subtitle = "레이블: $timerLabel",
                        meta = "시계",
                        actionUrlOrCmd = ""
                    )
                ),
                footerHint = "시스템 타이머가 작동 중입니다."
            )
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "${totalSeconds}s",
                autoCompleteSingleTurn = true,
                structuredCard = card,
                fastDecision = ModelTurnDecision(
                    observation = "명시적 숫자 타이머 시작 (${totalSeconds}초 · $timerLabel)",
                    subGoals = listOf(SubGoalItem("$durationKo 타이머 시작", SubGoalStatus.COMPLETED)),
                    toolName = "set_timer",
                    toolArgs = JSONObject().apply {
                        put("seconds", totalSeconds)
                        put("label", timerLabel)
                    },
                    spokenReply = reply
                )
            )
        }

        return null
    }

    /**
     * Strictly parses an explicit numeric alarm expression (`HH:MM` or `N시 [M분|반]`).
     * Returns `null` if no valid explicit numeric hour is present — NEVER silently defaults to 7 AM.
     */
    fun parseExplicitNumericAlarmOrNull(
        rawPrompt: String,
        pLower: String = rawPrompt.lowercase(Locale.ROOT)
    ): Triple<Int, Int, String>? {
        val isPm = pLower.contains("오후") || pLower.contains("저녁") || pLower.contains("밤")
        val isAm = pLower.contains("오전") || pLower.contains("아침") || pLower.contains("새벽")

        var rawHour: Int? = null
        var rawMinute = 0

        val colonMatch = Regex("(?<!\\d)(\\d{1,2})\\s*:\\s*(\\d{2})(?!\\d)").find(pLower)
        if (colonMatch != null) {
            rawHour = colonMatch.groupValues[1].toIntOrNull()
            rawMinute = colonMatch.groupValues[2].toIntOrNull() ?: return null
        } else {
            val hourMatch = Regex("(?<!\\d)(\\d{1,2})\\s*시").find(pLower) ?: return null
            rawHour = hourMatch.groupValues[1].toIntOrNull()
            val minMatch = Regex("(?<!\\d)(\\d{1,2})\\s*분").find(pLower)
            if (minMatch != null) {
                rawMinute = minMatch.groupValues[1].toIntOrNull() ?: return null
            } else if (Regex("\\d{1,2}\\s*시\\s*반").containsMatchIn(pLower)) {
                rawMinute = 30
            }
        }

        val parsedHour = rawHour ?: return null
        if (parsedHour !in 0..23 || rawMinute !in 0..59) return null

        var hour24 = parsedHour
        if (isPm && hour24 in 1..11) {
            hour24 += 12
        } else if (isAm && hour24 == 12) {
            hour24 = 0
        }

        val customLabel = rawPrompt
            .replace(
                Regex("(?i)(내일|오늘|아침|새벽|오전|오후|저녁|밤|\\d{1,2}\\s*:\\s*\\d{2}|\\d{1,2}\\s*시|\\d{1,2}\\s*분|반|에|으로|로|알람을|알람|맞춰\\s*줘|맞춰줘|맞춰\\s*주세요|맞춰|설정해\\s*줘|설정해줘|설정해\\s*주세요|설정|걸어\\s*줘|걸어줘|등록해\\s*줘|등록해줘|등록)"),
                ""
            )
            .trim()
            .takeIf { it.length >= 2 }
            ?: "Hanson AI 알람"

        return Triple(hour24, rawMinute, customLabel)
    }

    /**
     * Strictly parses an explicit positive numeric timer duration (`N시간`, `N분`, `N초`).
     * Returns `null` if no explicit positive duration is present — NEVER silently defaults to 3 minutes.
     */
    fun parseExplicitNumericTimerOrNull(
        rawPrompt: String,
        pLower: String = rawPrompt.lowercase(Locale.ROOT)
    ): Triple<Int, String, String>? {
        val hours = Regex("(?<!\\d)(\\d{1,2})\\s*시간").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(?<!\\d)(\\d{1,3})\\s*분").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(?<!\\d)(\\d{1,3})\\s*초").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val totalSec = hours * 3600 + minutes * 60 + seconds
        if (totalSec <= 0) return null

        val durationKo = buildList {
            if (hours > 0) add("${hours}시간")
            if (minutes > 0) add("${minutes}분")
            if (seconds > 0) add("${seconds}초")
        }.joinToString(" ")

        val customLabel = rawPrompt
            .replace(
                Regex("(\\d+\\s*시간|\\d+\\s*분|\\d+\\s*초|타이머를|타이머|맞춰\\s*줘|맞춰줘|맞춰\\s*주세요|맞춰|설정해\\s*줘|설정해줘|설정해\\s*주세요|설정|시작해\\s*줘|시작해줘|시작해\\s*주세요|시작|걸어\\s*줘|걸어줘|돌려\\s*줘|돌려줘)"),
                ""
            )
            .trim()
            .takeIf { it.length >= 2 }
            ?.let { "$it 타이머" }
            ?: "Hanson 타이머"

        return Triple(totalSec, customLabel, durationKo)
    }

    // =========================================================================
    // Minimal Caller-Compatibility Helpers (Safe Stubs / Exact Lookups Only)
    // =========================================================================

    /**
     * Exact app-alias lookup used by `AgentToolExecutionHarness.executeManageApp` and
     * `MobileAiInfrastructureRouter`. Only matches when [rawPrompt] itself is a clean app name
     * (never substring-hijacking arbitrary sentences).
     */
    fun resolveTargetAppFromPrompt(rawPrompt: String): Pair<String, String>? {
        val normalized = rawPrompt.trim()
            .lowercase(Locale.ROOT)
            .replace(" ", "")
            .removeSuffix("앱")
            .removeSuffix("어플")
        if (normalized.isEmpty()) return null
        return EXACT_APP_ALIAS_CATALOG
            .firstOrNull { (alias, _, _) -> alias == normalized }
            ?.let { (_, canonical, pkg) -> canonical to pkg }
    }

    /**
     * Minimal YouTube query cleaner retained for `AgentMediaFileEngine.searchYouTubeTopVideoId`.
     */
    fun cleanYouTubeSearchQuery(rawPrompt: String): String {
        val withoutUrls = rawPrompt.replace(Regex("https?://\\S+"), "").trim()
        val quoted = Regex("\"([^\"]{1,60})\"|'([^']{1,60})'").find(withoutUrls)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        }
        if (!quoted.isNullOrBlank()) return quoted

        return withoutUrls
            .replace(Regex("(?i)^\\s*(?:유튜브에서|유튜브로|유튜브|youtube에서|youtube)\\s*"), "")
            .replace(
                Regex("\\s*(?:동영상|영상|비디오)?(?:을|를)?\\s*(?:검색해서|찾아서)?\\s*(?:틀어|재생|보여|열어|찾아|검색)(?:해)?\\s*(?:주세요|줘요|줘|봐요|봐)?[.!?~]*$"),
                ""
            )
            .trim('\'', '"', '“', '”', '‘', '’', ' ')
    }

    /** Safe stub: spatial resolution is handled by the real VLM. */
    fun extractSpatialHint(pLower: String): SpatialHint = SpatialHint.NONE

    /** Safe stub: fuzzy screen-element clicking is removed; real VLM handles all clicks. */
    fun findBestScreenElement(
        snapshot: AtomicSnapshot?,
        rawQuery: String,
        spatialHint: SpatialHint = SpatialHint.NONE
    ): IndexedUiElement? = null

    /** Minimal quote/whitespace cleaner retained for `AgentToolExecutionHarness`. */
    fun cleanScreenTargetQuery(rawPrompt: String): String {
        val quoted = Regex("\"([^\"]{2,50})\"|'([^']{2,50})'").find(rawPrompt)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        }
        return quoted ?: rawPrompt.trim()
    }

    /** Safe stub: compound app+search routing is delegated to the real LLM/VLM. */
    fun extractCleanAppAndSearchQuery(rawPrompt: String): Pair<Pair<String, String>?, String?> =
        null to null

    /** Safe stub: generative/code tool invocation is decided by the real LLM, not substring rules. */
    fun isExplicitGenerativeOrCodeRequest(
        prompt: String,
        pLower: String = prompt.lowercase(Locale.ROOT),
        snapshot: AtomicSnapshot? = null,
        isMainActivityInForeground: Boolean = true
    ): Boolean = false

    /** Safe stub: search query extraction is handled by the real LLM. */
    fun extractSafeSearchQueryIfExplicit(rawPrompt: String): String? = null
}
