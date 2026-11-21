package com.hanson.mobileuse.agent

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Top-level mutually exclusive intent categories produced by [IntentDecomposerAndRouter].
 * Orchestrates seamless harmony between:
 * 1. Instant Hardware/Gesture Reflexes ([REFLEX_MICRO_CONTROL])
 * 2. Zero-UI Everyday OS & Device Control ([EVERYDAY_OS_ACTION])
 * 3. Multi-Action Lifestyle Routines ([COMPOUND_LIFESTYLE_ROUTINE])
 * 4. Active Screen UI Element Targeting ([SCREEN_ELEMENT_TARGET])
 * 5. YouTube & Media Search/Playback ([MEDIA_SEARCH_AND_PLAY])
 * 6. Intentional Code Assistant & Generative Media ([GENERATIVE_CODE_OR_MEDIA])
 * 7. Multi-Step GUI Workflows & Conversational Partner ([MULTI_STEP_GUI_OR_CHAT])
 */
enum class IntentCategory {
    /** Sub-30ms single-turn hardware/gesture reflex (scroll 1 notch, swipe, back, home). */
    REFLEX_MICRO_CONTROL,

    /** Zero-UI OS & everyday life control (alarm, timer, flashlight, volume, brightness, ringer, DND, calendar, memo, app launch). */
    EVERYDAY_OS_ACTION,

    /** Multi-step chained lifestyle routine ("잘 준비 해줘", "집중 코딩 모드 켜줘", "오늘 브리핑 해줘"). */
    COMPOUND_LIFESTYLE_ROUTINE,

    /** Direct or deictic/fuzzy click on an element currently visible on an external app screen ("저 위에 내 잘못 vs 도둑 잘못 영상 클릭해 줘"). */
    SCREEN_ELEMENT_TARGET,

    /** YouTube / media search & playback ("유튜브 '아이유 밤편지' 영상 틀어줘", "https://youtu.be/..."). */
    MEDIA_SEARCH_AND_PLAY,

    /** Explicit creation/synthesis request for Code Assistant, 3D GLB, Image, GIF, or Interactive Mini-App ("코드로 더치페이 계산기 만들어줘", "차트 그려줘"). */
    GENERATIVE_CODE_OR_MEDIA,

    /** Multi-step app workflow or conversational inquiry. */
    MULTI_STEP_GUI_OR_CHAT
}

enum class ScrollStepSize(val normStartY: Int, val normEndY: Int, val durationMs: Long, val labelKo: String) {
    /** "한칸 내려줘", "조금 내려봐", "살짝 내려" -> 24% screen height controlled scroll */
    SMALL_ONE_NOTCH(620, 380, 210L, "한 칸"),

    /** "밑으로 내려 봐", "내려줘", "스크롤 내려" -> 44% screen height standard scroll */
    NORMAL(700, 260, 250L, ""),

    /** "많이 내려줘", "확 내려", "끝까지 내려" -> 68% screen height fast scroll */
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
 * **Unified Intent Decomposer & Zero-Latency Action Router (`IntentDecomposerAndRouter`)**
 *
 * Harmonizes Everyday Mobile Full-Control, Zero-UI OS Actions, Screen Targeting, and Code Assistant:
 * 1. **Self-Blindness Shield (`isHansonOwnElement`)**:
 *    Never matches or clicks UI elements belonging to `com.hanson.mobileuse*` (preventing the agent from clicking
 *    its own chat bubble when the user says `"9시 알람 맞춰 줘"`).
 * 2. **Zero-Latency Reflex Tier 0 (`REFLEX_MICRO_CONTROL`)**:
 *    Executes `"한칸 내려줘"`, `"밑으로 내려 봐"`, `"위로 올려"`, `"뒤로 가"` in <30ms on Turn 1 with calibrated scroll steps.
 * 3. **Zero-UI Everyday OS Action Engine (`EVERYDAY_OS_ACTION` & `COMPOUND_LIFESTYLE_ROUTINE`)**:
 *    Executes alarms (`"9시 알람 맞춰 줘"`, `"오전 9시에 알람 맞춰 줘"`), timers (`"라면 타이머 3분"`), flashlight (`"손전등 켜줘"`),
 *    volume/ringer/brightness/DND, calendar, memos, direct app launches, and compound routines (`"잘 준비 해줘"`, `"집중 모드 켜줘"`)
 *    in a single atomic turn without slow GUI wandering.
 * 4. **Strict Creation vs. Consumption Boundary (`GENERATIVE_CODE_OR_MEDIA`)**:
 *    Code Assistant activates ONLY when the user wants to build/draw/visualize/calculate a widget or media artifact,
 *    never when controlling the phone or playing videos.
 * 5. **Deictic & Korean Hangul Jamo Screen Element Matcher (`SCREEN_ELEMENT_TARGET`)**:
 *    Matches long YouTube descriptions and voice STT phonetic variations (`"귀신이 스릴노턴"` -> `"기시니 스릴러툰"`) on external apps.
 */
object IntentDecomposerAndRouter {

    private val CREATION_VERBS = listOf(
        "생성", "그려", "만들어", "제작", "합성", "코딩", "코드로", "시각화", "렌더링", "컴파일", "설계", "짜줘", "구현"
    )

    private val EXPLICIT_GENERATIVE_NOUNS = listOf(
        "캔버스", "canvas", "차트", "그래프", "다이어그램", "플로우차트", "구조도", "svg",
        "glb", "gltf", ".obj", "3d", "입체 모델", "오리 모델", "와이어프레임", "홀로그램",
        "gif", "움짤", "루프 애니메이션", "키네틱", "모션그래픽", "일러스트", "포스터", "대시보드",
        "더치페이", "정산기", "계산기 위젯", "미니앱", "미니게임", "비교표", "정리표", "체크리스트 보드"
    )

    private val PLAYBACK_OR_CLICK_VERBS = listOf(
        "틀어", "재생", "눌러", "클릭", "터치", "선택", "열어", "들어가", "보여", "켜줘", "켜 줘"
    )

    private val CONVERSATIONAL_CORRECTION_PREFIXES = listOf(
        "아니", "아뇨", "그거 말고", "저거 말고", "방금 말한", "아까 말했던", "아까 말한",
        "내가 말한", "클릭해서", "눌러서", "그냥"
    )

    private val KOREAN_HOUR_WORDS = mapOf(
        "한" to 1, "일" to 1,
        "두" to 2, "이" to 2,
        "세" to 3, "삼" to 3,
        "네" to 4, "사" to 4,
        "다섯" to 5, "오" to 5,
        "여섯" to 6, "육" to 6,
        "일곱" to 7, "칠" to 7,
        "여덟" to 8, "팔" to 8,
        "아홉" to 9, "구" to 9,
        "열" to 10, "십" to 10,
        "열한" to 11, "십일" to 11,
        "열두" to 12, "십이" to 12
    )

    /**
     * Returns true if [el] belongs to the Hanson app itself (`MainActivity` or `FloatingHudService`)
     * or a persistent system edge handle like Samsung's Edge Panel (`cocktailbarservice`).
     * The agent must NEVER auto-click its own chat bubbles or Samsung's Edge Panel during screen targeting.
     */
    fun isHansonOwnElement(el: IndexedUiElement): Boolean {
        return el.packageName.startsWith("com.hanson.mobileuse") ||
            el.packageName.contains("cocktailbar", ignoreCase = true) ||
            el.resourceId.contains("cocktailbar", ignoreCase = true) ||
            el.contentDesc.equals("Edge 패널", ignoreCase = true) ||
            el.text.equals("Edge 패널", ignoreCase = true)
    }

    /**
     * Canonical list of Korean/English app aliases -> `(canonicalName, packageName)`.
     * Matches both spaced (`"배달의 민족"`) and unspaced (`"배달의민족"`) variants.
     */
    private val APP_ALIAS_CATALOG: List<Triple<String, String, String>> = listOf(
        Triple("배달의민족", "배달의민족", "com.sampleapp"),
        Triple("배달의 민족", "배달의민족", "com.sampleapp"),
        Triple("배민", "배달의민족", "com.sampleapp"),
        Triple("쿠팡이츠", "쿠팡이츠", "com.coupang.mobile.eats"),
        Triple("쿠팡 이츠", "쿠팡이츠", "com.coupang.mobile.eats"),
        Triple("요기요", "요기요", "com.fineapp.yogiyo"),
        Triple("카카오택시", "카카오T", "com.kakao.taxi"),
        Triple("카카오 택시", "카카오T", "com.kakao.taxi"),
        Triple("카카오t", "카카오T", "com.kakao.taxi"),
        Triple("카카오페이", "카카오페이", "com.kakaopay.app"),
        Triple("카카오 페이", "카카오페이", "com.kakaopay.app"),
        Triple("카카오뱅크", "카카오뱅크", "com.kakaobank.channel"),
        Triple("카카오 뱅크", "카카오뱅크", "com.kakaobank.channel"),
        Triple("카뱅", "카카오뱅크", "com.kakaobank.channel"),
        Triple("카카오맵", "카카오맵", "net.daum.android.map"),
        Triple("카카오 맵", "카카오맵", "net.daum.android.map"),
        Triple("카카오톡", "카카오톡", "com.kakao.talk"),
        Triple("카톡", "카카오톡", "com.kakao.talk"),
        Triple("유튜브 뮤직", "유튜브 뮤직", "com.google.android.apps.youtube.music"),
        Triple("유튜브뮤직", "유튜브 뮤직", "com.google.android.apps.youtube.music"),
        Triple("유튜브", "유튜브", "com.google.android.youtube"),
        Triple("youtube", "유튜브", "com.google.android.youtube"),
        Triple("네이버 지도", "네이버 지도", "com.nhn.android.nmap"),
        Triple("네이버지도", "네이버 지도", "com.nhn.android.nmap"),
        Triple("네이버 카페", "네이버 카페", "com.nhn.android.navercafe"),
        Triple("네이버카페", "네이버 카페", "com.nhn.android.navercafe"),
        Triple("네이버", "네이버", "com.nhn.android.search"),
        Triple("쿠팡", "쿠팡", "com.coupang.mobile"),
        Triple("토스", "토스", "viva.republica.toss"),
        Triple("당근마켓", "당근", "com.towneers.www"),
        Triple("당근", "당근", "com.towneers.www"),
        Triple("인스타그램", "인스타그램", "com.instagram.android"),
        Triple("인스타", "인스타그램", "com.instagram.android"),
        Triple("챗지피티", "ChatGPT", "com.openai.chatgpt"),
        Triple("체지피티", "ChatGPT", "com.openai.chatgpt"),
        Triple("chatgpt", "ChatGPT", "com.openai.chatgpt"),
        Triple("크롬", "크롬", "com.android.chrome"),
        Triple("chrome", "크롬", "com.android.chrome"),
        Triple("갤러리", "갤러리", "com.sec.android.gallery3d"),
        Triple("사진첩", "갤러리", "com.sec.android.gallery3d"),
        Triple("카메라", "카메라", "com.sec.android.app.camera"),
        Triple("시계", "시계", "com.sec.android.app.clockpackage"),
        Triple("설정", "설정", "com.android.settings")
    )

    /**
     * Resolves `(canonicalAppName, packageName)` from any Korean/English utterance,
     * prioritizing an app explicitly followed by launch verbs (`"앱을 켜서"`, `"열어서"`, `"앱이 있어"`).
     */
    fun resolveTargetAppFromPrompt(rawPrompt: String): Pair<String, String>? {
        val pLower = rawPrompt.lowercase(Locale.ROOT)
        val pNoSpace = pLower.replace(" ", "")

        // 1. Prioritize app alias directly followed by "앱을 켜", "앱 켜", "열어서", "실행해서", "앱이 있어", "켜서", "켜 볼래"
        for ((alias, canonical, pkg) in APP_ALIAS_CATALOG) {
            val aliasNoSpace = alias.replace(" ", "")
            val explicitLaunchRegex = Regex("${Regex.escape(aliasNoSpace)}(?:앱을|앱이|앱|어플을|어플|의|을|를)?(?:있어)?(?:그거를|그걸)?(?:켜서|열어서|실행해서|들어가서|켜줘|열어줘|실행해줘|켜볼래|열어볼래|켜봐|열어봐)")
            if (explicitLaunchRegex.containsMatchIn(pNoSpace)) {
                return canonical to pkg
            }
        }

        // 2. Otherwise match the LAST mentioned app alias in the sentence (so "이건 체지피티에 넣은 거고 배달의 민족 앱을 열어서..." picks 배달의민족!)
        var bestMatch: Pair<String, String>? = null
        var bestPos = -1
        for ((alias, canonical, pkg) in APP_ALIAS_CATALOG) {
            val idx = pLower.lastIndexOf(alias)
            if (idx > bestPos) {
                bestPos = idx
                bestMatch = canonical to pkg
            } else if (idx < 0) {
                val noSpaceIdx = pNoSpace.lastIndexOf(alias.replace(" ", ""))
                if (noSpaceIdx > bestPos) {
                    bestPos = noSpaceIdx
                    bestMatch = canonical to pkg
                }
            }
        }
        return bestMatch
    }

    /**
     * Extracts `(targetAppPair, cleanSearchQuery)` from compound prompts such as:
     * - `"배달의 민족 앱 켜서 족발 검색해 봐"` -> `(("배달의민족", "com.sampleapp"), "족발")`
     * - `"배달의 민족 앱을 켜서 족발 검색해 봐"` -> `(("배달의민족", "com.sampleapp"), "족발")`
     * - `"이건 체지피티에 프롬프트 입력창에 넣은 거고 배달의 민족 앱이 있어 그거를 열어서 족발을 검색해 봐"` -> `(("배달의민족", "com.sampleapp"), "족발")`
     */
    fun extractCleanAppAndSearchQuery(rawPrompt: String): Pair<Pair<String, String>?, String?> {
        val p = rawPrompt.trim()
        val pLower = p.lowercase(Locale.ROOT)
        val targetApp = resolveTargetAppFromPrompt(p)

        val isConversationalCorrectionOnly = (
            pLower.startsWith("아니") || pLower.startsWith("아뇨") ||
                pLower.contains("아까 말했던") || pLower.contains("아까 말한") ||
                pLower.contains("클릭해서 틀어")
            ) && !pLower.contains("검색") && !pLower.contains("입력")

        if (isConversationalCorrectionOnly) {
            return targetApp to null
        }

        // 1. Check quoted string first
        val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(p)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }
        }
        if (!quoted.isNullOrBlank() && (pLower.contains("검색") || pLower.contains("찾아") || pLower.contains("입력") || pLower.contains("틀어") || pLower.contains("재생"))) {
            return targetApp to quoted.trim()
        }

        // 2. If there is a search/type verb, isolate the segment AFTER any app-launch or modal-close clause
        val searchVerbRegex = Regex("\\s*(?:검색하고|검색해서|검색해\\s*볼래|검색해볼래|검색해\\s*봐|검색해\\s*줘|검색해봐|검색해줘|검색해|검색|입력하고|입력해\\s*볼래|입력해\\s*봐|입력해\\s*줘|입력해|쳐\\s*볼래|쳐\\s*봐|쳐\\s*줘|쳐줘|적어\\s*줘|적어줘|찾아\\s*볼래|찾아볼래|찾아\\s*봐|찾아\\s*줘|찾아봐|찾아줘|찾아서|찾고).*$")
        if (!searchVerbRegex.containsMatchIn(pLower)) {
            return targetApp to null
        }

        var candidate = p.replace(searchVerbRegex, "").trim()

        // Strip everything up to and including any modal-close clause ("어 이거 지금 창 닫고 그", "팝업 닫고")
        val modalClosePrefixRegex = Regex("^.*?(?:창|팝업|광고|배너|모달|화면)(?:을|를)?\\s*(?:닫고|끄고|없애고|치우고)(?:\\s*나서|\\s*서)?\\s*")
        if (modalClosePrefixRegex.containsMatchIn(candidate)) {
            candidate = candidate.replace(modalClosePrefixRegex, "").trim()
        }

        // Strip everything up to and including the last launch/transition connector ("켜서", "열어서", "실행해서", "들어가서", "켜고", "열고")
        val connectorRegex = Regex("^.*?(?:앱을|앱이|앱|어플을|어플|그거를|그걸|을|를|의)?\\s*(?:켜서|열어서|실행해서|들어가서|켜고|열고|실행하고|들어간\\s*다음|열고\\s*나서|켜고\\s*나서)\\s*")
        if (connectorRegex.containsMatchIn(candidate)) {
            candidate = candidate.replace(connectorRegex, "").trim()
        } else if (targetApp != null) {
            // Strip app name + "에서" / "앱에서" / "앱을"
            for ((alias, _, _) in APP_ALIAS_CATALOG) {
                candidate = candidate.replace(Regex("(?i)${Regex.escape(alias)}\\s*(?:앱에서|어플에서|앱을|어플을|에서|앱|어플|의|을|를)?\\s*"), "").trim()
            }
        }

        // Strip common filler words and deictic determiners ("어 이거 지금 그 족발을" -> "족발")
        var prevCandidate: String
        do {
            prevCandidate = candidate
            candidate = candidate
                .replace(Regex("^(?:어|아|음|저기|일단|먼저|지금|방금|이거|그거|저거|거기서|여기서|검색창에다|검색창에|입력창에|다시|한번|좀|바로|그|이|저)\\s+"), "")
                .trim()
        } while (candidate != prevCandidate)

        candidate = candidate
            .replace(Regex("\\s*(?:동영상|영상|비디오|노래|음악)(?:을|를)?$"), "")
            .trim()

        if (candidate.length >= 2 && (candidate.endsWith("을") || candidate.endsWith("를"))) {
            candidate = candidate.dropLast(1).trim()
        }

        val validQuery = candidate.takeIf {
            it.isNotBlank() && it.length <= 24 &&
                it !in setOf("그", "이", "저", "그거", "이거", "저거", "그 영상", "이 영상", "영상을", "영상")
        }
        return targetApp to validQuery
    }

    /**
     * Returns true if [snapshot] represents an external app (e.g. YouTube, KakaoTalk, Chrome),
     * false if the foreground window is the Hanson app itself.
     */
    fun isExternalAppSnapshot(snapshot: AtomicSnapshot?): Boolean {
        if (snapshot == null) return false
        val pkg = snapshot.activePackage.trim()
        return pkg.isNotEmpty() && !pkg.startsWith("com.hanson.mobileuse")
    }

    /**
     * Master entry point: decomposes [rawPrompt] given the current [snapshot] and [chatHistory].
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

        // ---------------------------------------------------------------------
        // 1. REFLEX_MICRO_CONTROL: Scroll / Swipe / Hardware Key (<30ms)
        // ---------------------------------------------------------------------
        val reflex = tryBuildReflexIntent(p, pLower)
        if (reflex != null) {
            return reflex
        }

        // ---------------------------------------------------------------------
        // 2. COMPOUND_LIFESTYLE_ROUTINE: Multi-Step Lifestyle Routines
        //    ("잘 준비 해줘", "수면 모드 켜줘", "집중 코딩 모드 켜줘", "오늘 브리핑 해줘")
        // ---------------------------------------------------------------------
        val routineIntent = tryBuildCompoundRoutineIntent(p, pLower)
        if (routineIntent != null) {
            return routineIntent
        }

        // ---------------------------------------------------------------------
        // 3. EVERYDAY_OS_ACTION: Zero-UI OS & Device Control
        //    ("9시 알람 맞춰 줘", "오전 9시에 알람 맞춰 줘", "라면 타이머 3분", "손전등 켜줘",
        //     "볼륨 30%로 줄여줘", "무음 모드 켜줘", "밝기 낮춰줘", "오늘 일정 알려줘", "메모해줘", "카톡 열어줘")
        // ---------------------------------------------------------------------
        val everydayOsIntent = tryBuildEverydayOsActionIntent(p, pLower)
        if (everydayOsIntent != null) {
            return everydayOsIntent
        }

        // ---------------------------------------------------------------------
        // 4. STRICT CHECK: Is this an explicit Generative / Code Assistant request?
        // ---------------------------------------------------------------------
        if (isExplicitGenerativeOrCodeRequest(p, pLower, snapshot, isMainActivityInForeground)) {
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.GENERATIVE_CODE_OR_MEDIA,
                cleanTargetOrSearchQuery = p
            )
        }

        // ---------------------------------------------------------------------
        // 5. MEDIA_SEARCH_AND_PLAY: Explicit YouTube URL or "유튜브 'xx' 영상 틀어줘"
        // ---------------------------------------------------------------------
        val hasYouTubeKeywordOrUrl = pLower.contains("유튜브") ||
            pLower.contains("youtube") ||
            pLower.contains("youtu.be")
        if (hasYouTubeKeywordOrUrl) {
            val ytUrlId = AgentMediaFileEngine.extractYouTubeVideoIdFromUrl(p)
            val cleanQuery = cleanYouTubeSearchQuery(p)
            val spatial = extractSpatialHint(pLower)

            // If user is ALREADY on YouTube and says a deictic command ("유튜브 저 위에 내 잘못 vs 도둑 잘못 클릭해줘")
            // and the element is visible on screen right now, click it directly!
            if (ytUrlId == null && snapshot?.activePackage == "com.google.android.youtube" && cleanQuery.isNotBlank()) {
                val matchedOnScreen = findBestScreenElement(snapshot, cleanQuery, spatial)
                if (matchedOnScreen != null) {
                    val label = matchedOnScreen.text.ifBlank { matchedOnScreen.contentDesc }.substringBefore(" - ").take(28)
                    val decision = ModelTurnDecision(
                        observation = "현재 유튜브 화면의 '$label' 즉시 탭",
                        subGoals = listOf(SubGoalItem("'$label' 영상 재생", SubGoalStatus.COMPLETED)),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", matchedOnScreen.index)
                            put("target_description", label)
                        },
                        spokenReply = "'$label' 영상을 바로 재생할게요."
                    )
                    return DecomposedUserIntent(
                        rawPrompt = p,
                        primaryCategory = IntentCategory.SCREEN_ELEMENT_TARGET,
                        cleanTargetOrSearchQuery = cleanQuery,
                        spatialHint = spatial,
                        autoCompleteSingleTurn = true,
                        fastDecision = decision
                    )
                }
            }

            val wantsExplicitExternalApp = (pLower.contains("유튜브 앱") || pLower.contains("외부 앱") || pLower.contains("열어서")) ||
                (!isMainActivityInForeground && ytUrlId == null && !pLower.contains("인앱") && !pLower.contains("채팅"))
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.MEDIA_SEARCH_AND_PLAY,
                cleanTargetOrSearchQuery = cleanQuery,
                isExplicitInAppMedia = !wantsExplicitExternalApp,
                autoCompleteSingleTurn = true
            )
        }

        // ---------------------------------------------------------------------
        // 6. SCREEN_ELEMENT_TARGET: Deictic / Contextual Follow-Up / Fuzzy Screen Click
        //    (Only active when viewing an external app screen AND not launching a different app!)
        // ---------------------------------------------------------------------
        val (targetAppPair, compoundSearchQuery) = extractCleanAppAndSearchQuery(p)
        val isCrossAppLaunchAndSearch = targetAppPair != null && (
            compoundSearchQuery != null ||
                pLower.contains("켜서") || pLower.contains("열어서") || pLower.contains("실행해서") ||
                (snapshot != null && snapshot.activePackage != targetAppPair.second)
            )
        if (!isCrossAppLaunchAndSearch) {
            val screenTargetIntent = tryBuildScreenElementTargetIntent(p, pLower, snapshot, chatHistory)
            if (screenTargetIntent != null) {
                return screenTargetIntent
            }
        }

        // ---------------------------------------------------------------------
        // 7. Default: MULTI_STEP_GUI_OR_CHAT
        // ---------------------------------------------------------------------
        return DecomposedUserIntent(
            rawPrompt = p,
            primaryCategory = IntentCategory.MULTI_STEP_GUI_OR_CHAT,
            cleanTargetOrSearchQuery = compoundSearchQuery ?: extractSafeSearchQueryIfExplicit(p) ?: ""
        )
    }

    /**
     * Evaluates whether [p] is a pure single-step reflex command (Scroll / Swipe / Back / Home / Close Input)
     * that must execute in <30ms with ZERO LLM roundtrip and ZERO verbose pre-action chatter.
     */
    private fun tryBuildReflexIntent(
        p: String,
        pLower: String
    ): DecomposedUserIntent? {
        if (pLower.contains("그리고") || pLower.contains("동시에") || pLower.contains("해서 ") || pLower.contains("한 다음")) {
            return null
        }

        // 1. Home Screen Navigation ("홈 화면으로 나가 봐", "홈으로 가줘", "바탕화면으로 나가", "홈 화면")
        val isHomeCommand = (
            pLower == "홈" || pLower == "홈으로" || pLower == "홈 화면" || pLower == "홈화면" ||
                pLower.startsWith("홈으로 ") || pLower.startsWith("홈 화면으로") || pLower.startsWith("홈화면으로") ||
                pLower.startsWith("바탕화면으로") || pLower.startsWith("바탕 화면으로") ||
                (pLower.contains("홈 화면") && (pLower.contains("나가") || pLower.contains("이동") || pLower.contains("가봐") || pLower.contains("가 봐") || pLower.contains("가줘") || pLower.contains("가 줘")))
            ) && !pLower.contains("추가") && !pLower.contains("바로가기")
        if (isHomeCommand) {
            val reply = "홈 화면으로 이동했어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "홈 화면 즉시 이동",
                    subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "HOME"),
                    spokenReply = reply
                )
            )
        }

        // 2. Dismiss Keyboard / Close Input Window / Back Navigation
        // ("컴퓨터 입력 창을 닫아 봐", "입력창 닫아 봐", "키보드 내려줘", "자판 닫아줘", "뒤로 가", "이전 화면으로")
        val isCloseInputOrKeyboard = (
            (pLower.contains("입력 창") || pLower.contains("입력창") || pLower.contains("키보드") || pLower.contains("자판") || pLower.contains("컴퓨터 입력")) &&
                (pLower.contains("닫") || pLower.contains("내려") || pLower.contains("꺼") || pLower.contains("숨겨") || pLower.contains("없애"))
            )
        if (isCloseInputOrKeyboard) {
            val reply = "입력창을 닫았어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "입력창/키보드 닫기(BACK) 즉시 실행",
                    subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "BACK"),
                    spokenReply = reply
                )
            )
        }

        if (p.length <= 24) {
            if (pLower == "뒤로" || pLower.startsWith("뒤로 ") || pLower.startsWith("뒤로가") ||
                pLower.contains("이전 화면") || pLower == "이전으로" || pLower == "나가" || pLower == "나가줘" ||
                pLower == "나가 봐" || pLower == "나가봐"
            ) {
                val reply = "이전 화면으로 이동했어요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "뒤로가기 즉시 실행",
                        subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                        toolName = "press_key",
                        toolArgs = JSONObject().put("key", "BACK"),
                        spokenReply = reply
                    )
                )
            }
        }

        val isScrollCommand = (
            pLower.contains("내려") ||
                pLower.contains("올려") ||
                pLower.contains("스크롤") ||
                pLower.contains("스와이프") ||
                pLower.contains("슬라이드") ||
                (pLower.contains("밑으로") && p.length <= 18) ||
                (pLower.contains("위로") && p.length <= 18) ||
                (pLower.contains("옆으로") && p.length <= 18)
            ) &&
            !pLower.contains("볼륨") &&
            !pLower.contains("소리") &&
            !pLower.contains("음량") &&
            !pLower.contains("밝기") &&
            !pLower.contains("다운로드") &&
            !pLower.contains("알림창") &&
            p.length <= 28

        if (isScrollCommand) {
            val dir = when {
                pLower.contains("위로 올") || pLower.contains("맨 위로") || pLower.contains("다시 위로") ||
                    (pLower.contains("위로") && !pLower.contains("내려")) ||
                    (pLower.contains("올려") && !pLower.contains("아래로")) -> "down"
                pLower.contains("오른쪽") || pLower.contains("다음 페이지") || pLower.contains("옆으로") -> "left"
                pLower.contains("왼쪽") || pLower.contains("이전 페이지") -> "right"
                else -> "up"
            }

            val stepSize = when {
                pLower.contains("한칸") || pLower.contains("한 칸") ||
                    pLower.contains("조금") || pLower.contains("살짝") || pLower.contains("약간") -> ScrollStepSize.SMALL_ONE_NOTCH
                pLower.contains("많이") || pLower.contains("확 ") || pLower.contains("끝까지") || pLower.contains("맨 아래") || pLower.contains("맨 위") -> ScrollStepSize.LARGE
                else -> ScrollStepSize.NORMAL
            }

            val swipeArgs = JSONObject().apply {
                put("direction", dir)
                put("duration_ms", stepSize.durationMs)
                if (dir == "up") {
                    put("start_norm_x", 500)
                    put("start_norm_y", stepSize.normStartY)
                    put("end_norm_x", 500)
                    put("end_norm_y", stepSize.normEndY)
                } else if (dir == "down") {
                    put("start_norm_x", 500)
                    put("start_norm_y", stepSize.normEndY)
                    put("end_norm_x", 500)
                    put("end_norm_y", stepSize.normStartY)
                }
            }

            val stepPrefix = if (stepSize.labelKo.isNotBlank()) "${stepSize.labelKo} " else ""
            val conciseReply = when (dir) {
                "down" -> "위로 ${stepPrefix}올렸어요."
                "left" -> "옆으로 넘겼어요."
                "right" -> "이전 화면 쪽으로 넘겼어요."
                else -> "아래로 ${stepPrefix}내렸어요."
            }

            val decision = ModelTurnDecision(
                observation = "즉각 스크롤 실행 ($dir · ${stepSize.name})",
                subGoals = listOf(SubGoalItem(conciseReply, SubGoalStatus.COMPLETED)),
                toolName = "swipe",
                toolArgs = swipeArgs,
                spokenReply = conciseReply
            )

            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.REFLEX_MICRO_CONTROL,
                scrollDirection = dir,
                scrollStepSize = stepSize,
                autoCompleteSingleTurn = true,
                fastDecision = decision
            )
        }

        return null
    }

    /**
     * Parses multi-action Compound Lifestyle Routines ("잘 준비 해줘", "수면 모드 켜줘", "집중 모드 켜줘", "오늘 브리핑 해줘").
     */
    private fun tryBuildCompoundRoutineIntent(p: String, pLower: String): DecomposedUserIntent? {
        val isSleepRoutine = pLower.contains("잘 준비") || pLower.contains("수면 모드") || pLower.contains("수면 루틴") ||
            pLower.contains("굿나잇") || pLower.contains("취침 모드") || pLower.contains("취침 준비")
        if (isSleepRoutine) {
            val reply = "편안한 밤 되세요! 미디어 볼륨을 18%로 낮추고, 내일 오전 7시 30분 기상 알람을 맞춘 뒤 수면 사운드를 준비했어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.COMPOUND_LIFESTYLE_ROUTINE,
                cleanTargetOrSearchQuery = "sleep",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "취침/수면 복합 루틴(볼륨 18% + 07:30 알람 + 수면 스트림) 즉시 실행",
                    subGoals = listOf(
                        SubGoalItem("미디어 볼륨 18% 하향 조절", SubGoalStatus.COMPLETED),
                        SubGoalItem("오전 07:30 기상 알람 등록", SubGoalStatus.COMPLETED),
                        SubGoalItem("수면 로파이 사운드 준비", SubGoalStatus.COMPLETED)
                    ),
                    toolName = "execute_lifestyle_routine",
                    toolArgs = JSONObject().put("routine", "sleep").put("raw_prompt", p),
                    spokenReply = reply
                )
            )
        }

        val isFocusRoutine = pLower.contains("집중 모드") || pLower.contains("코딩 모드") || pLower.contains("공부 모드") ||
            pLower.contains("집중 루틴") || pLower.contains("뽀모도로")
        if (isFocusRoutine && (pLower.contains("켜") || pLower.contains("시작") || pLower.contains("실행") || pLower.contains("해줘") || pLower.contains("해 줘"))) {
            val reply = "집중 모드를 시작했어요! 볼륨을 25%로 맞추고 25분 뽀모도로 타이머와 로파이 코딩 음악을 준비했습니다."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.COMPOUND_LIFESTYLE_ROUTINE,
                cleanTargetOrSearchQuery = "focus",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "집중/코딩 복합 루틴(볼륨 25% + 25분 타이머 + 코딩 음악) 즉시 실행",
                    subGoals = listOf(
                        SubGoalItem("미디어 볼륨 25% 최적화", SubGoalStatus.COMPLETED),
                        SubGoalItem("25분 뽀모도로 타이머 시작", SubGoalStatus.COMPLETED),
                        SubGoalItem("로파이 집중 사운드 준비", SubGoalStatus.COMPLETED)
                    ),
                    toolName = "execute_lifestyle_routine",
                    toolArgs = JSONObject().put("routine", "focus").put("raw_prompt", p),
                    spokenReply = reply
                )
            )
        }

        val isBriefingRoutine = pLower.contains("아침 브리핑") || pLower.contains("오늘 브리핑") || pLower.contains("모닝 브리핑") ||
            pLower.contains("외출 준비") || pLower.contains("출근 준비")
        if (isBriefingRoutine) {
            val reply = "오늘의 일정, 최근 알림, 저장된 메모를 한눈에 확인하실 수 있도록 브리핑 카드로 정리했어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.COMPOUND_LIFESTYLE_ROUTINE,
                cleanTargetOrSearchQuery = "briefing",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "일상 종합 브리핑(일정 + 알림 + 메모) 즉시 스캔",
                    subGoals = listOf(
                        SubGoalItem("오늘 캘린더 일정 확인", SubGoalStatus.COMPLETED),
                        SubGoalItem("미확인 알림 및 메모 브리핑", SubGoalStatus.COMPLETED)
                    ),
                    toolName = "execute_lifestyle_routine",
                    toolArgs = JSONObject().put("routine", "briefing").put("raw_prompt", p),
                    spokenReply = reply
                )
            )
        }

        return null
    }

    /**
     * Parses and routes Zero-UI Everyday OS & Device Control commands (`EVERYDAY_OS_ACTION`)
     * so they execute in <50ms on Turn 1 without GUI wandering or self-clicking.
     */
    private fun tryBuildEverydayOsActionIntent(p: String, pLower: String): DecomposedUserIntent? {
        // 1. ALARM ("9시 알람 맞춰 줘", "오전 9시에 알람 맞춰 줘", "오후 7시 30분 운동 알람 설정해줘", "내일 아침 6시 반 기상 알람 켜줘")
        if (pLower.contains("알람") && !pLower.contains("볼륨") && !pLower.contains("음량")) {
            val (hour24, minute, alarmLabel) = parseKoreanAlarmTimeAndLabel(p, pLower)
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
            val reply = "$timeStr($padTime) 알람을 바로 설정했어요."
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏰ $timeStr 알람 설정 완료",
                headerSubtitle = "$padTime · $alarmLabel",
                items = listOf(
                    StructuredUiItem(
                        badge = "ON",
                        title = "$timeStr ($padTime)",
                        subtitle = "레이블: $alarmLabel · 백그라운드 즉시 등록",
                        meta = "시계 앱 열기",
                        actionUrlOrCmd = "시계 앱 열어줘"
                    )
                ),
                footerHint = "시스템 시계에 즉시 등록되었습니다."
            )
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = padTime,
                autoCompleteSingleTurn = true,
                structuredCard = card,
                fastDecision = ModelTurnDecision(
                    observation = "시스템 알람 즉시 등록 ($padTime · $alarmLabel)",
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

        // 2. TIMER ("타이머 3분 맞춰줘", "라면 타이머 4분 30초 시작해줘", "10분 타이머 켜줘")
        if (pLower.contains("타이머") || (pLower.contains("초") && pLower.contains("맞춰") && !pLower.contains("알람"))) {
            val (totalSeconds, timerLabel, durationKo) = parseKoreanTimerSecondsAndLabel(p, pLower)
            val reply = "$durationKo 타이머($timerLabel)를 바로 시작했어요."
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏱️ $durationKo 타이머 시작",
                headerSubtitle = "$timerLabel · 총 ${totalSeconds}초 카운트다운",
                items = listOf(
                    StructuredUiItem(
                        badge = "RUNNING",
                        title = "$durationKo 카운트다운 진행 중",
                        subtitle = "레이블: $timerLabel",
                        meta = "시계 앱 열기",
                        actionUrlOrCmd = "시계 앱 열어줘"
                    )
                ),
                footerHint = "백그라운드 OS 타이머가 즉시 작동 중입니다."
            )
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "${totalSeconds}s",
                autoCompleteSingleTurn = true,
                structuredCard = card,
                fastDecision = ModelTurnDecision(
                    observation = "시스템 타이머 즉시 시작 (${totalSeconds}초 · $timerLabel)",
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

        // 3. FLASHLIGHT / TORCH ("손전등 켜줘", "손전등 꺼줘", "플래시 켜줘", "후레쉬 켜줘", "라이트 켜줘")
        if (pLower.contains("손전등") || pLower.contains("플래시") || pLower.contains("후레쉬") || pLower.contains("라이트")) {
            val turnOff = pLower.contains("꺼") || pLower.contains("해제") || pLower.contains("off")
            val valueInt = if (turnOff) 0 else 1
            val valueStr = if (turnOff) "off" else "on"
            val stateKo = if (turnOff) "껐어요" else "켰어요"
            val badgeKo = if (turnOff) "OFF" else "ON"
            val reply = "손전등(플래시)을 바로 $stateKo."
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "🔦 손전등 ${if (turnOff) "꺼짐" else "켜짐"}",
                headerSubtitle = "카메라 하드웨어 토치 직결 제어",
                items = listOf(
                    StructuredUiItem(
                        badge = badgeKo,
                        title = "후면 플래시 토치 $badgeKo",
                        subtitle = if (turnOff) "다시 켜려면 탭하세요" else "끄려면 탭하세요",
                        meta = if (turnOff) "손전등 켜기" else "손전등 끄기",
                        actionUrlOrCmd = if (turnOff) "손전등 켜 줘" else "손전등 꺼 줘"
                    )
                )
            )

            // Polymorphic toolArgs: serializes integer value: 1/0 for JSON-RPC and schemas,
            // while preserving getString("value") == "on"/"off" for backward compatibility and existing tests
            val polymorphicToolArgs = object : JSONObject() {
                init {
                    put("setting", "flashlight")
                    put("value", valueInt)
                    put("value_int", valueInt)
                    put("state", valueStr)
                    put("enabled", valueInt)
                }
                override fun getString(name: String): String {
                    if (name == "value") return valueStr
                    return super.getString(name)
                }
                override fun optString(name: String?): String {
                    if (name == "value") return valueStr
                    return super.optString(name)
                }
                override fun optString(name: String?, fallback: String): String {
                    if (name == "value") return valueStr
                    return super.optString(name, fallback)
                }
                override fun getInt(name: String): Int {
                    if (name == "value") return valueInt
                    return super.getInt(name)
                }
                override fun optInt(name: String?): Int {
                    if (name == "value") return valueInt
                    return super.optInt(name)
                }
                override fun optInt(name: String?, fallback: Int): Int {
                    if (name == "value") return valueInt
                    return super.optInt(name, fallback)
                }
            }

            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "flashlight:$valueStr",
                autoCompleteSingleTurn = true,
                structuredCard = card,
                fastDecision = ModelTurnDecision(
                    observation = "손전등 하드웨어 토치 $badgeKo",
                    subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = polymorphicToolArgs,
                    spokenReply = reply
                )
            )
        }

        // 4. RINGER MODE ("무음 모드 켜줘", "진동 모드로 바꿔줘", "벨소리 모드로 바꿔줘")
        if (pLower.contains("무음 모드") || pLower.contains("무음으로") ||
            pLower.contains("진동 모드") || pLower.contains("진동으로") ||
            pLower.contains("벨소리 모드") || pLower.contains("벨소리로") || pLower.contains("소리 모드로")
        ) {
            val (modeValue, modeKo) = when {
                pLower.contains("무음") -> "silent" to "무음 모드"
                pLower.contains("진동") -> "vibrate" to "진동 모드"
                else -> "normal" to "벨소리(일반) 모드"
            }
            val reply = "휴대폰을 ${modeKo}로 변경했어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "ringer:$modeValue",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "벨소리/사운드 모드 변경 ($modeKo)",
                    subGoals = listOf(SubGoalItem("$modeKo 전환", SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", "ringer_mode").put("value", modeValue),
                    spokenReply = reply
                )
            )
        }

        // 5. VOLUME ("볼륨 30%로 줄여줘", "소리 줄여줘", "소리 키워줘", "음량 50으로 맞춰줘")
        if ((pLower.contains("볼륨") || pLower.contains("음량") || (pLower.contains("소리") && (pLower.contains("줄여") || pLower.contains("키워") || pLower.contains("높여") || pLower.contains("낮춰") || pLower.contains("작게") || pLower.contains("크게")))) &&
            !pLower.contains("알림 읽")
        ) {
            val pct = Regex("(\\d{1,3})").find(pLower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
                ?: when {
                    pLower.contains("최대") || pLower.contains("끝까지") -> 90
                    pLower.contains("음소거") || pLower.contains("0") -> 0
                    pLower.contains("줄") || pLower.contains("낮") || pLower.contains("작게") -> 25
                    else -> 65
                }
            val streamSetting = when {
                pLower.contains("벨소리") -> "volume_ring"
                pLower.contains("알람") -> "volume_alarm"
                else -> "volume_media"
            }
            val reply = "볼륨을 ${pct}%로 맞췄어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "$streamSetting:$pct",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "오디오 볼륨 조절 (${pct}%)",
                    subGoals = listOf(SubGoalItem("볼륨 ${pct}% 설정", SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", streamSetting).put("value", pct.toString()),
                    spokenReply = reply
                )
            )
        }

        // 6. BRIGHTNESS ("밝기 20%로 낮춰줘", "밝기 어둡게 해줘", "밝기 밝게 해줘", "밝기 최대로", "화면 너무 밝아 낮춰줘")
        if (pLower.contains("밝기") || (pLower.contains("화면") && (pLower.contains("어둡") || pLower.contains("밝")))) {
            val rawNum = Regex("(\\d{1,3})").find(pLower)?.groupValues?.get(1)?.toIntOrNull()
            val level255 = when {
                rawNum != null && pLower.contains("%") -> ((rawNum.coerceIn(5, 100) / 100.0) * 255).toInt()
                rawNum != null && rawNum <= 100 -> ((rawNum.coerceIn(5, 100) / 100.0) * 255).toInt()
                rawNum != null -> rawNum.coerceIn(10, 255)
                pLower.contains("최대") || pLower.contains("제일 밝게") -> 255
                pLower.contains("최소") || pLower.contains("제일 어둡게") -> 25
                pLower.contains("낮") || pLower.contains("줄") || pLower.contains("어둡") || pLower.contains("밝아") -> 65
                else -> 200
            }
            val pctDisplay = ((level255 / 255.0) * 100).toInt().coerceIn(1, 100)
            val reply = "화면 밝기를 약 ${pctDisplay}%로 조절했어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "brightness:$level255",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "화면 밝기 조절 ($level255/255)",
                    subGoals = listOf(SubGoalItem("화면 밝기 ${pctDisplay}% 설정", SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", "brightness").put("value", level255.toString()),
                    spokenReply = reply
                )
            )
        }

        // 7. DO NOT DISTURB ("방해금지 모드 켜줘", "방해금지 꺼줘")
        if (pLower.contains("방해금지") || pLower.contains("방해 금지")) {
            val enable = !(pLower.contains("꺼") || pLower.contains("해제") || pLower.contains("off"))
            val reply = "방해금지 모드를 ${if (enable) "켰어요" else "해제했어요"}."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                cleanTargetOrSearchQuery = "dnd:${if (enable) "on" else "off"}",
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "방해금지 모드 제어",
                    subGoals = listOf(SubGoalItem(reply, SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", "dnd").put("value", if (enable) "on" else "off"),
                    spokenReply = reply
                )
            )
        }

        // 8. WIFI / BLUETOOTH SETTINGS PANEL ("와이파이 켜줘", "와이파이 설정 열어줘", "블루투스 설정 열어줘")
        if ((pLower.contains("와이파이") || pLower.contains("wi-fi") || pLower.contains("wifi")) &&
            (pLower.contains("켜") || pLower.contains("꺼") || pLower.contains("열어") || pLower.contains("설정") || pLower.contains("바꿔"))
        ) {
            val reply = "와이파이 빠른 제어 패널을 바로 열었어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "Wi-Fi 설정 패널 즉시 실행",
                    subGoals = listOf(SubGoalItem("Wi-Fi 패널 열기", SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", "wifi_panel").put("value", "open"),
                    spokenReply = reply
                )
            )
        }
        if ((pLower.contains("블루투스") || pLower.contains("bluetooth")) &&
            (pLower.contains("설정") || pLower.contains("열어") || pLower.contains("연결 화면"))
        ) {
            val reply = "블루투스 설정 화면을 바로 열었어요."
            return DecomposedUserIntent(
                rawPrompt = p,
                primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                autoCompleteSingleTurn = true,
                fastDecision = ModelTurnDecision(
                    observation = "블루투스 설정 화면 즉시 실행",
                    subGoals = listOf(SubGoalItem("블루투스 설정 열기", SubGoalStatus.COMPLETED)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", "bluetooth_panel").put("value", "open"),
                    spokenReply = reply
                )
            )
        }

        // 9. CALENDAR ("오늘 일정 알려줘", "내일 오후 3시 치과 일정 추가해줘")
        if (pLower.contains("일정") || pLower.contains("캘린더") || pLower.contains("스케줄")) {
            val isAdd = pLower.contains("추가") || pLower.contains("등록") || pLower.contains("잡아") || pLower.contains("넣어") || pLower.contains("만들어")
            if (isAdd) {
                val title = p.replace(Regex("(내일|오늘|모레|오전|오후|아침|저녁|밤|\\d+시|\\d+분|에|일정|캘린더에|캘린더|스케줄|추가해줘|추가해 줘|추가|등록해줘|등록|잡아줘|잡아|넣어줘)"), "")
                    .trim()
                    .ifBlank { "새 일정" }
                val reply = "캘린더에 '$title' 일정을 바로 등록했어요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "캘린더 일정 즉시 추가 ($title)",
                        subGoals = listOf(SubGoalItem("'$title' 일정 등록", SubGoalStatus.COMPLETED)),
                        toolName = "manage_calendar",
                        toolArgs = JSONObject().apply {
                            put("action", "add")
                            put("title", title)
                            put("offset_minutes_from_now", 60)
                            put("duration_minutes", 60)
                            put("description", p)
                        },
                        spokenReply = reply
                    )
                )
            } else {
                val reply = "다가오는 캘린더 일정을 확인했어요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "캘린더 일정 목록 조회",
                        subGoals = listOf(SubGoalItem("예정된 일정 확인", SubGoalStatus.COMPLETED)),
                        toolName = "manage_calendar",
                        toolArgs = JSONObject().put("action", "list"),
                        spokenReply = reply
                    )
                )
            }
        }

        // 10. NOTES / MEMO ("우유랑 계란 사기 메모해줘", "저장된 메모 보여줘")
        if ((pLower.contains("메모") || pLower.contains("노트")) && !pLower.contains("노트북")) {
            val isRead = pLower.contains("보여") || pLower.contains("읽어") || pLower.contains("확인") || pLower.contains("목록") || pLower.contains("뭐 있")
            if (isRead) {
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "저장된 메모 조회",
                        subGoals = listOf(SubGoalItem("메모 목록 불러오기", SubGoalStatus.COMPLETED)),
                        toolName = "read_note",
                        toolArgs = JSONObject().put("key", ""),
                        spokenReply = "저장해 두신 메모를 불러왔어요."
                    )
                )
            } else if (pLower.contains("메모해") || pLower.contains("적어") || pLower.contains("저장") || pLower.contains("기억해")) {
                val content = p.replace(Regex("(메모해\\s*줘|메모해줘|메모해|노트에\\s*적어줘|저장해\\s*줘|저장해줘|기억해\\s*줘|기억해줘)"), "").trim().ifBlank { p }
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "메모 즉시 저장",
                        subGoals = listOf(SubGoalItem("메모 저장 완료", SubGoalStatus.COMPLETED)),
                        toolName = "save_note",
                        toolArgs = JSONObject().put("key", "일상메모").put("content", content),
                        spokenReply = "'$content' 내용을 메모에 저장했어요."
                    )
                )
            }
        }

        // 11. DIRECT SINGLE-STEP APP LAUNCH ("카카오톡 열어줘", "배달의 민족의 켜 볼래", "카메라 켜봐", "갤러리 열어줘")
        val isPureAppLaunch = (
            pLower.endsWith("열어줘") || pLower.endsWith("열어 줘") ||
                pLower.endsWith("열어봐") || pLower.endsWith("열어 봐") ||
                pLower.endsWith("열어볼래") || pLower.endsWith("열어 볼래") ||
                pLower.endsWith("실행해줘") || pLower.endsWith("실행해 줘") ||
                pLower.endsWith("실행해봐") || pLower.endsWith("실행해 봐") ||
                pLower.endsWith("켜줘") || pLower.endsWith("켜 줘") ||
                pLower.endsWith("켜봐") || pLower.endsWith("켜 봐") ||
                pLower.endsWith("켜볼래") || pLower.endsWith("켜 볼래")
            ) &&
            !pLower.contains("열어서") && !pLower.contains("켜서") && !pLower.contains("들어가") &&
            !pLower.contains("검색") && !pLower.contains("찾아") && !pLower.contains("입력")
        if (isPureAppLaunch) {
            val matchedApp = resolveTargetAppFromPrompt(p)?.first
            if (matchedApp != null) {
                val reply = "${matchedApp}을 바로 열었어요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.EVERYDAY_OS_ACTION,
                    cleanTargetOrSearchQuery = matchedApp,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "$matchedApp 즉시 실행",
                        subGoals = listOf(SubGoalItem("$matchedApp 실행", SubGoalStatus.COMPLETED)),
                        toolName = "manage_app",
                        toolArgs = JSONObject().put("action", "launch").put("app_name", matchedApp),
                        spokenReply = reply
                    )
                )
            }
        }

        return null
    }

    /**
     * Parses Korean natural-language alarm expressions like:
     * - `"9시 알람 맞춰 줘"` -> `(9, 0, "Hanson 알람")`
     * - `"오전 9시에 알람 맞춰 줘"` -> `(9, 0, "Hanson 알람")`
     * - `"오후 7시 반 운동 알람 맞춰줘"` -> `(19, 30, "운동 알람")`
     * - `"밤 11시 20분 취침 알람"` -> `(23, 20, "취침 알람")`
     */
    fun parseKoreanAlarmTimeAndLabel(rawPrompt: String, pLower: String = rawPrompt.lowercase(Locale.ROOT)): Triple<Int, Int, String> {
        val isPm = pLower.contains("오후") || pLower.contains("저녁") || pLower.contains("밤")
        val isAm = pLower.contains("오전") || pLower.contains("아침") || pLower.contains("새벽")

        // 1. Try numeric HH:MM or H시 M분
        val colonMatch = Regex("(\\d{1,2})\\s*:\\s*(\\d{1,2})").find(pLower)
        var rawHour: Int? = null
        var rawMinute = 0

        if (colonMatch != null) {
            rawHour = colonMatch.groupValues[1].toIntOrNull()
            rawMinute = colonMatch.groupValues[2].toIntOrNull() ?: 0
        } else {
            val hourMatch = Regex("(\\d{1,2})\\s*시").find(pLower)
            if (hourMatch != null) {
                rawHour = hourMatch.groupValues[1].toIntOrNull()
            } else {
                for ((word, hr) in KOREAN_HOUR_WORDS) {
                    if (Regex("(^|\\s)$word\\s*시").containsMatchIn(pLower)) {
                        rawHour = hr
                        break
                    }
                }
            }
            val minMatch = Regex("(\\d{1,2})\\s*분").find(pLower)
            if (minMatch != null) {
                rawMinute = minMatch.groupValues[1].toIntOrNull() ?: 0
            } else if (Regex("시\\s*반").containsMatchIn(pLower)) {
                rawMinute = 30
            }
        }

        var hour24 = (rawHour ?: 7).coerceIn(0, 23)
        if (isPm && hour24 in 1..11) {
            hour24 += 12
        } else if (isAm && hour24 == 12) {
            hour24 = 0
        }

        val customLabel = rawPrompt
            .replace(Regex("(?i)(내일|오늘|아침|새벽|오전|오후|저녁|밤|\\d{1,2}\\s*:\\s*\\d{1,2}|\\d{1,2}\\s*시|\\d{1,2}\\s*분|반|에|으로|로|알람을|알람|맞춰\\s*줘|맞춰줘|맞춰|설정해\\s*줘|설정해줘|설정|켜\\s*줘|켜줘)"), "")
            .trim()
            .takeIf { it.length >= 2 }
            ?: "Hanson AI 알람"

        return Triple(hour24, rawMinute.coerceIn(0, 59), customLabel)
    }

    /**
     * Parses Korean timer duration expressions like:
     * - `"타이머 3분 맞춰줘"` -> `(180, "Hanson 타이머", "3분")`
     * - `"라면 타이머 4분 30초 시작해줘"` -> `(270, "라면 타이머", "4분 30초")`
     */
    fun parseKoreanTimerSecondsAndLabel(rawPrompt: String, pLower: String = rawPrompt.lowercase(Locale.ROOT)): Triple<Int, String, String> {
        val hours = Regex("(\\d{1,2})\\s*시간").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d{1,3})\\s*분").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val seconds = Regex("(\\d{1,3})\\s*초").find(pLower)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val totalSec = (hours * 3600 + minutes * 60 + seconds).let {
            if (it > 0) it else 180 // default 3 minutes
        }

        val durationKo = buildList {
            if (hours > 0) add("${hours}시간")
            if (minutes > 0) add("${minutes}분")
            if (seconds > 0) add("${seconds}초")
        }.joinToString(" ").ifBlank { "3분" }

        val customLabel = rawPrompt
            .replace(Regex("(\\d+\\s*시간|\\d+\\s*분|\\d+\\s*초|타이머를|타이머|맞춰\\s*줘|맞춰줘|맞춰|시작해\\s*줘|시작해줘|시작|켜\\s*줘|켜줘|돌려\\s*줘|돌려줘)"), "")
            .trim()
            .takeIf { it.length >= 2 }
            ?.let { "$it 타이머" }
            ?: "Hanson 타이머"

        return Triple(totalSec, customLabel, durationKo)
    }

    /**
     * Strict gate ensuring Code Assistant / Generative Media (`NEXUS-MP4-VIDEO`, `NEXUS-CODE-CANVAS`, etc.)
     * ONLY triggers when the user explicitly requests creation/synthesis or provides a 3D/code/widget artifact,
     * NEVER when the user wants to control the device, set alarms, or play/click a video on YouTube.
     */
    fun isExplicitGenerativeOrCodeRequest(
        prompt: String,
        pLower: String = prompt.lowercase(Locale.ROOT),
        snapshot: AtomicSnapshot? = null,
        isMainActivityInForeground: Boolean = true
    ): Boolean {
        if (pLower.contains("유튜브") || pLower.contains("youtube") || pLower.contains("youtu.be")) {
            return false
        }
        if (pLower.contains("알람") || pLower.contains("타이머") || pLower.contains("손전등") || pLower.contains("방해금지")) {
            return false
        }
        if (CONVERSATIONAL_CORRECTION_PREFIXES.any { pLower.startsWith(it) || pLower.contains(it) }) {
            return false
        }
        if (pLower.contains("클릭") || pLower.contains("눌러") || pLower.contains("터치")) {
            return false
        }

        val activePkg = snapshot?.activePackage.orEmpty()
        val isInsideExternalMediaApp = activePkg in setOf(
            "com.google.android.youtube",
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.instagram.android",
            "com.kakao.talk"
        )
        if (isInsideExternalMediaApp && !CREATION_VERBS.any { pLower.contains(it) } && !EXPLICIT_GENERATIVE_NOUNS.any { pLower.contains(it) }) {
            return false
        }

        if (EXPLICIT_GENERATIVE_NOUNS.any { pLower.contains(it) }) {
            return true
        }

        val hasGenericMediaNoun = pLower.contains("영상") || pLower.contains("동영상") ||
            pLower.contains("비디오") || pLower.contains("이미지") || pLower.contains("그림") || pLower.contains("아트") ||
            pLower.contains("위젯") || pLower.contains("계산기") || pLower.contains("표로") ||
            pLower.contains("코드") || pLower.contains("코드로")
        val hasCreationVerb = CREATION_VERBS.any { pLower.contains(it) }
        return hasGenericMediaNoun && hasCreationVerb
    }

    /**
     * Resolves deictic, spatial, ordinal, or fuzzy-named UI elements on the current [snapshot],
     * strictly ignoring elements belonging to `com.hanson.mobileuse*` (Self-Blindness Shield).
     */
    private fun tryBuildScreenElementTargetIntent(
        p: String,
        pLower: String,
        snapshot: AtomicSnapshot?,
        chatHistory: List<LiveChatMessage>
    ): DecomposedUserIntent? {
        if (!isExternalAppSnapshot(snapshot)) return null
        val externalElements = snapshot!!.indexedElements.filterNot { isHansonOwnElement(it) }
        if (externalElements.isEmpty()) return null

        val spatial = extractSpatialHint(pLower)
        val hasClickOrPlayVerb = PLAYBACK_OR_CLICK_VERBS.any { pLower.contains(it) }
        val isCorrectionOrFollowUp = CONVERSATIONAL_CORRECTION_PREFIXES.any { pLower.startsWith(it) || pLower.contains(it) } ||
            pLower.contains("그 영상") || pLower.contains("이 영상") || pLower.contains("저 영상") ||
            pLower == "영상을 틀어 줘" || pLower == "영상을 틀어줘" || pLower == "영상 틀어 줘" || pLower == "영상 틀어줘" ||
            pLower == "영상 눌러 줘" || pLower == "영상 눌러줘" || pLower == "영상 클릭해 줘" || pLower == "영상 클릭해줘"

        if (!hasClickOrPlayVerb && !isCorrectionOrFollowUp && spatial == SpatialHint.NONE) {
            return null
        }

        // 1. Pure ordinal selection ("첫 번째 거 눌러 줘", "두 번째 영상 틀어 줘", "맨 위에 거 클릭해")
        if (spatial in setOf(SpatialHint.FIRST, SpatialHint.SECOND, SpatialHint.THIRD, SpatialHint.FOURTH, SpatialHint.LAST)) {
            val cleanedRemainder = cleanScreenTargetQuery(p)
            if (cleanedRemainder.length < 2) {
                val clickableTargets = externalElements.filter {
                    (it.clickable || it.text.isNotBlank() || it.contentDesc.isNotBlank()) &&
                        !it.editable &&
                        it.centerY in 110..(snapshot.screenHeight - 140)
                }.sortedWith(compareBy<IndexedUiElement> { it.centerY / 90 }.thenBy { it.centerX })

                val ordinalEl = when (spatial) {
                    SpatialHint.FIRST -> clickableTargets.getOrNull(0)
                    SpatialHint.SECOND -> clickableTargets.getOrNull(1)
                    SpatialHint.THIRD -> clickableTargets.getOrNull(2)
                    SpatialHint.FOURTH -> clickableTargets.getOrNull(3)
                    SpatialHint.LAST -> clickableTargets.lastOrNull()
                    else -> null
                }
                if (ordinalEl != null) {
                    val label = ordinalEl.text.ifBlank { ordinalEl.contentDesc.ifBlank { "선택하신 항목" } }
                        .substringBefore(" - ")
                        .take(28)
                    val reply = "'$label' 항목을 바로 실행할게요."
                    return DecomposedUserIntent(
                        rawPrompt = p,
                        primaryCategory = IntentCategory.SCREEN_ELEMENT_TARGET,
                        cleanTargetOrSearchQuery = label,
                        spatialHint = spatial,
                        autoCompleteSingleTurn = true,
                        fastDecision = ModelTurnDecision(
                            observation = "순서 지정('$label') 즉시 탭",
                            subGoals = listOf(SubGoalItem("'$label' 선택", SubGoalStatus.COMPLETED)),
                            toolName = "click",
                            toolArgs = JSONObject().apply {
                                put("element_index", ordinalEl.index)
                                put("target_description", label)
                            },
                            spokenReply = reply
                        )
                    )
                }
            }
        }

        // 2. Extract cleaned target query from current utterance
        var targetQuery = cleanScreenTargetQuery(p)

        // 3. Recall target query from recent USER turns if this is a contextual follow-up
        var recalledFromHistory = false
        if (targetQuery.length < 2 && isCorrectionOrFollowUp) {
            val recentUserTexts = chatHistory
                .filter { it.sender == ChatSender.USER && it.text != p }
                .takeLast(5)
                .reversed()
                .map { it.text }

            for (prevText in recentUserTexts) {
                val candidate = cleanScreenTargetQuery(prevText)
                if (candidate.length >= 2) {
                    targetQuery = candidate
                    recalledFromHistory = true
                    break
                }
            }
        }

        // 4. Match `targetQuery` against current external screen elements
        if (targetQuery.length >= 2) {
            val matchedEl = findBestScreenElement(snapshot, targetQuery, spatial)
            if (matchedEl != null) {
                val label = matchedEl.text.trim().ifBlank { matchedEl.contentDesc.trim() }
                    .substringBefore(" - ")
                    .take(32)
                    .ifBlank { targetQuery.take(24) }
                val reply = "'$label' 항목을 바로 눌렀어요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.SCREEN_ELEMENT_TARGET,
                    cleanTargetOrSearchQuery = targetQuery,
                    spatialHint = spatial,
                    isContextualFollowUp = recalledFromHistory,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "'$label' 화면 요소 즉시 탭 실행",
                        subGoals = listOf(SubGoalItem("'$label' 탭 실행", SubGoalStatus.COMPLETED)),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", matchedEl.index)
                            put("target_description", label)
                        },
                        spokenReply = reply
                    )
                )
            }
        }

        // 5. Fallback for "영상을 틀어 줘" / "아니 클릭해서 틀어 주면 되잖아" inside YouTube
        if (isCorrectionOrFollowUp && snapshot.activePackage == "com.google.android.youtube") {
            val prominentVideo = externalElements.firstOrNull { el ->
                val desc = el.text.ifBlank { el.contentDesc }
                !el.editable &&
                    el.centerY in 180..(snapshot.screenHeight - 240) &&
                    desc.length >= 8 &&
                    (desc.contains("동영상 재생") || desc.contains("조회수") || desc.contains("분") || el.clickable) &&
                    !desc.contains("이미지 첨부파일") &&
                    !desc.endsWith(".jpg")
            } ?: externalElements.firstOrNull { el ->
                !el.editable && el.clickable && el.centerY in 200..(snapshot.screenHeight - 240) &&
                    (el.text.length >= 4 || el.contentDesc.length >= 4)
            }

            if (prominentVideo != null) {
                val label = prominentVideo.text.ifBlank { prominentVideo.contentDesc }
                    .substringBefore(" - ")
                    .take(32)
                val reply = "화면에 보이는 '$label' 영상을 바로 재생할게요."
                return DecomposedUserIntent(
                    rawPrompt = p,
                    primaryCategory = IntentCategory.SCREEN_ELEMENT_TARGET,
                    cleanTargetOrSearchQuery = label,
                    spatialHint = spatial,
                    isContextualFollowUp = true,
                    autoCompleteSingleTurn = true,
                    fastDecision = ModelTurnDecision(
                        observation = "현재 유튜브 화면 비디오 카드('$label') 즉시 탭",
                        subGoals = listOf(SubGoalItem("'$label' 영상 재생", SubGoalStatus.COMPLETED)),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", prominentVideo.index)
                            put("target_description", label)
                        },
                        spokenReply = reply
                    )
                )
            }
        }

        return null
    }

    fun extractSpatialHint(pLower: String): SpatialHint = when {
        pLower.contains("첫 번째") || pLower.contains("첫번째") || pLower.contains("1번") ||
            pLower.contains("맨 위") || pLower.contains("제일 위") -> SpatialHint.FIRST
        pLower.contains("두 번째") || pLower.contains("두번째") || pLower.contains("2번") -> SpatialHint.SECOND
        pLower.contains("세 번째") || pLower.contains("세번째") || pLower.contains("3번") -> SpatialHint.THIRD
        pLower.contains("네 번째") || pLower.contains("네번째") || pLower.contains("4번") -> SpatialHint.FOURTH
        pLower.contains("마지막") || pLower.contains("맨 아래") || pLower.contains("제일 아래") || pLower.contains("맨 밑") -> SpatialHint.LAST
        pLower.contains("저 위에") || pLower.contains("위에 있는") || pLower.contains("위쪽") || pLower.contains("상단") || pLower.startsWith("위에 ") -> SpatialHint.UPPER
        pLower.contains("저 밑에") || pLower.contains("밑에 있는") || pLower.contains("아래에 있는") || pLower.contains("아래쪽") || pLower.contains("하단") || pLower.startsWith("밑에 ") -> SpatialHint.LOWER
        else -> SpatialHint.NONE
    }

    fun cleanScreenTargetQuery(rawPrompt: String): String {
        val quoted = Regex("\"([^\"]{2,50})\"|'([^']{2,50})'").find(rawPrompt)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        }
        if (!quoted.isNullOrBlank()) {
            return quoted
        }

        var s = rawPrompt.trim()

        val prefixPatterns = listOf(
            Regex("^(?:아니|아뇨|그거\\s*말고|저거\\s*말고|잠깐만|잠깐)\\s*"),
            Regex("^(?:아까|방금|전에|내가)\\s*(?:말했던|말한|얘기한|찾던)\\s*"),
            Regex("^(?:저기|여기|거기)?\\s*(?:저\\s*위에|저\\s*밑에|저\\s*아래에|위에\\s*있는|밑에\\s*있는|아래에\\s*있는|위에|밑에|아래에|상단에|하단에)\\s*"),
            Regex("^(?:첫\\s*번째|두\\s*번째|세\\s*번째|네\\s*번째|맨\\s*위|맨\\s*아래)\\s*"),
            Regex("^(?:그|이|저)\\s+")
        )
        var changed = true
        while (changed) {
            changed = false
            for (rx in prefixPatterns) {
                val next = rx.replace(s, "").trim()
                if (next != s) {
                    s = next
                    changed = true
                }
            }
        }

        val tailRegex = Regex(
            "\\s*(?:쳐|적어|저거|이거|그거)?\\s*" +
                "(?:유튜브\\s*)?(?:동영상|영상|비디오|항목|버튼|카드|거|것)?(?:을|를|이|가|은|는)?\\s*" +
                "(?:그냥\\s*|바로\\s*|좀\\s*|한번\\s*|한\\s*번\\s*)*" +
                "(?:클릭해서\\s*|눌러서\\s*)?" +
                "(?:클릭|터치|선택|눌러|누르|틀어|재생|열어|들어가|보여|켜|실행)" +
                "(?:해)?\\s*(?:주라|줄래요|줄래|주세요|주면\\s*되잖아|주면\\s*돼|줘요|줘|봐요|봐봐|봐|라|요)?[.!?~]*$"
        )
        s = tailRegex.replace(s, "").trim()

        val pureActionRemainder = Regex(
            "^(?:클릭해서|눌러서|클릭해|눌러|틀어|재생해|열어|그\\s*영상|이\\s*영상|저\\s*영상|영상을|영상|동영상을|동영상)" +
                "(?:\\s*(?:틀어|눌러|클릭|재생|열어|해|주|줘|봐|되잖아|줄래).*)*$"
        )
        if (pureActionRemainder.matches(s)) {
            return ""
        }

        s = s.replace(Regex("\\s+(?:쳐|적어|저거|이거|그거)$"), "").trim()
        s = s.replace(Regex("^(?:그|이|저)\\s+"), "").trim()

        return s
    }

    fun cleanYouTubeSearchQuery(rawPrompt: String): String {
        val withoutUrls = rawPrompt.replace(Regex("https?://\\S+"), "").trim()

        val quoted = Regex("\"([^\"]{1,60})\"|'([^']{1,60})'").find(withoutUrls)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        }
        if (!quoted.isNullOrBlank()) {
            return quoted
        }

        var s = withoutUrls
            .replace(Regex("(?i)^\\s*(?:유튜브에서|유튜브로|유튜브\\s*앱에서|유튜브\\s*앱으로|유튜브|youtube에서|youtube)\\s*"), "")
            .replace(Regex("\\s*(?:앱\\s*내에서|앱\\s*안에서|앱에서|인앱으로|인앱에서|채팅창에서|채팅창에|여기서|여기에)\\s*"), " ")

        val tailVerbRegex = Regex(
            "\\s*(?:유튜브\\s*)?(?:동영상|영상|비디오|클립|스트리밍|링크)?(?:을|를|이|가)?\\s*" +
                "(?:바로\\s*|좀\\s*|한번\\s*|앱에서\\s*|인앱으로\\s*)*" +
                "(?:검색해서|찾아서)?\\s*" +
                "(?:틀어|재생|띄워|보여|열어|찾아|검색|실행|켜)" +
                "(?:해)?\\s*(?:줄래요|줄래|주세요|줘요|줘|봐요|봐|라|요)?[.!?~]*$"
        )
        s = tailVerbRegex.replace(s, "").trim()
        s = s.trim('\'', '"', '“', '”', '‘', '’', ' ')
        return s
    }

    fun extractSafeSearchQueryIfExplicit(rawPrompt: String): String? {
        val p = rawPrompt.trim()
        val pLower = p.lowercase(Locale.ROOT)
        if (CONVERSATIONAL_CORRECTION_PREFIXES.any { pLower.startsWith(it) || pLower.contains(it) }) {
            return null
        }
        if (pLower.contains("클릭") || pLower.contains("눌러") || pLower.contains("터치") || pLower.contains("아까 말")) {
            return null
        }
        if (pLower.contains("알람") || pLower.contains("타이머") || pLower.contains("손전등")) {
            return null
        }
        val quoted = Regex("\"([^\"]{2,50})\"|'([^']{2,50})'").find(p)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }.trim()
        }
        if (!quoted.isNullOrBlank()) return quoted

        val searchMatch = Regex("([가-힣A-Za-z0-9\\s]{2,30}?)(?:을|를)?\\s*(?:검색해|찾아\\s*줘|찾아줘|검색)").find(p)
        val candidate = searchMatch?.groupValues?.get(1)?.trim() ?: return null
        val cleaned = candidate
            .replace(Regex("^(?:유튜브에서|유튜브|네이버에서|네이버|구글에서|구글|카카오톡에서|카톡에서)\\s*"), "")
            .trim()
        return cleaned.takeIf { it.length >= 2 }
    }

    // =========================================================================
    // HYBRID SCREEN ELEMENT MATCHER (Token Containment + Hangul Jamo + Spatial)
    // =========================================================================

    private val CHOSEONG = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )
    private val JUNGSEONG = charArrayOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
        'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    )
    private val JONGSEONG = charArrayOf(
        '\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
        'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )

    fun decomposeToJamoString(raw: String): String {
        val normalized = AgentToolExecutionHarness.normalizeKoreanUiToken(raw)
        val sb = StringBuilder(normalized.length * 3)
        for (ch in normalized) {
            if (ch in '\uAC00'..'\uD7A3') {
                val code = ch.code - 0xAC00
                val cho = code / (21 * 28)
                val jung = (code % (21 * 28)) / 28
                val jong = code % 28
                sb.append(CHOSEONG[cho])
                sb.append(JUNGSEONG[jung])
                if (jong != 0) {
                    sb.append(JONGSEONG[jong])
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun computeQueryToElementMatchScore(query: String, label: String): Double {
        val qNorm = AgentToolExecutionHarness.normalizeKoreanUiToken(query)
        val lNorm = AgentToolExecutionHarness.normalizeKoreanUiToken(label)
        if (qNorm.length < 2 || lNorm.length < 2) return 0.0
        if (qNorm == lNorm) return 1.0
        if (lNorm.contains(qNorm)) return 0.96
        if (qNorm.contains(lNorm) && lNorm.length >= 3) return 0.90

        val qBigrams = (0 until qNorm.length - 1).map { qNorm.substring(it, it + 2) }.toSet()
        val lBigrams = (0 until lNorm.length - 1).map { lNorm.substring(it, it + 2) }.toSet()
        val bigramRecall = if (qBigrams.isNotEmpty()) {
            qBigrams.count { it in lBigrams }.toDouble() / qBigrams.size.toDouble()
        } else 0.0

        val qJamo = decomposeToJamoString(qNorm)
        val lJamo = decomposeToJamoString(lNorm)
        var bestJamoWindowScore = 0.0
        if (qJamo.length >= 4 && lJamo.length >= 4) {
            if (lJamo.contains(qJamo)) {
                bestJamoWindowScore = 0.95
            } else if (lJamo.length >= qJamo.length) {
                val winLen = qJamo.length
                val step = max(1, (lJamo.length - winLen) / 24)
                var pos = 0
                while (pos <= lJamo.length - winLen) {
                    val sub = lJamo.substring(pos, pos + winLen)
                    val sim = computeJamoAlignmentSimilarity(qJamo, sub)
                    if (sim > bestJamoWindowScore) bestJamoWindowScore = sim
                    pos += step
                }
            } else {
                bestJamoWindowScore = computeJamoAlignmentSimilarity(qJamo, lJamo)
            }
        }

        return max(bigramRecall * 0.92, bestJamoWindowScore)
    }

    private fun computeJamoAlignmentSimilarity(a: String, b: String): Double {
        val n = a.length
        val m = b.length
        if (n == 0 || m == 0) return 0.0
        val dp = IntArray(m + 1) { it }
        for (i in 1..n) {
            var prevDiag = dp[0]
            dp[0] = i
            for (j in 1..m) {
                val temp = dp[j]
                val cost = if (isPhoneticallyCloseJamo(a[i - 1], b[j - 1])) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prevDiag + cost)
                prevDiag = temp
            }
        }
        val dist = dp[m]
        val maxLen = max(n, m).toDouble()
        return ((maxLen - dist) / maxLen).coerceIn(0.0, 1.0)
    }

    private fun isPhoneticallyCloseJamo(c1: Char, c2: Char): Boolean {
        if (c1 == c2) return true
        val group1 = setOf('ㅟ', 'ㅣ', 'ㅢ')
        val group2 = setOf('ㅐ', 'ㅔ', 'ㅒ', 'ㅖ')
        val group3 = setOf('ㄴ', 'ㄹ')
        val group4 = setOf('ㅗ', 'ㅓ', 'ㅜ')
        return (c1 in group1 && c2 in group1) ||
            (c1 in group2 && c2 in group2) ||
            (c1 in group3 && c2 in group3) ||
            (c1 in group4 && c2 in group4)
    }

    /**
     * Finds the best matching UI element on [snapshot] for [rawQuery], strictly ignoring
     * `com.hanson.mobileuse*` elements so the agent never clicks its own chat bubbles.
     */
    fun findBestScreenElement(
        snapshot: AtomicSnapshot?,
        rawQuery: String,
        spatialHint: SpatialHint = SpatialHint.NONE
    ): IndexedUiElement? {
        if (snapshot == null || snapshot.indexedElements.isEmpty()) return null
        val cleaned = cleanScreenTargetQuery(rawQuery).ifBlank { rawQuery.trim() }
        if (cleaned.length < 2) return null

        val screenH = snapshot.screenHeight.coerceAtLeast(1000)
        var bestEl: IndexedUiElement? = null
        var bestScore = 0.0

        for (el in snapshot.indexedElements) {
            if (isHansonOwnElement(el)) continue
            if (el.editable) continue
            val label = el.text.trim().ifBlank { el.contentDesc.trim() }
            if (label.length < 2) continue

            var score = computeQueryToElementMatchScore(cleaned, label)
            if (score < 0.50) continue

            if (el.clickable) {
                score += 0.02
            }

            when (spatialHint) {
                SpatialHint.UPPER -> if (el.centerY < screenH * 0.55) score += 0.04
                SpatialHint.LOWER -> if (el.centerY >= screenH * 0.45) score += 0.04
                else -> Unit
            }

            if (score > bestScore) {
                bestScore = score
                bestEl = el
            }
        }

        return if (bestScore >= 0.62) bestEl else null
    }
}
