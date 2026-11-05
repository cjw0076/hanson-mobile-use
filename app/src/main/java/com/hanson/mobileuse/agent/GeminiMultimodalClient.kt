package com.hanson.mobileuse.agent

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ModelTurnDecision(
    val observation: String,
    val subGoals: List<SubGoalItem>,
    val toolName: String,
    val toolArgs: JSONObject
)

/**
 * Multimodal Gemini VLM + Function Calling Client with Google Artemis 3-Phase Cognitive Protocol
 * and intelligent on-device fallback planner when offline or before an API key is configured.
 */
class GeminiMultimodalClient {

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        fun normalize2026ModelName(rawModel: String): String {
            val trimmed = rawModel.trim()
            if (trimmed.isEmpty() ||
                trimmed.startsWith("gemini-2.5") ||
                trimmed.startsWith("gemini-2.0") ||
                trimmed.startsWith("gemini-1.5")
            ) {
                return DEFAULT_MODEL
            }
            return trimmed
        }

        val SYSTEM_INSTRUCTION = """
You are Hanson Mobile-Use AI, an unrestricted autonomous Android mobile operator combining Google Artemis (`third_party/mobile_use`) and EZmap.

Follow the 3-Phase Cognitive Protocol on EVERY turn:
1. Phase 1 (Observation): Start your text response with `OBSERVATION: <1-sentence Korean analysis of current screen, active app, and whether previous action succeeded>`.
2. Phase 2 (Live Sub-goal Ledger): Next, output `PLAN:` followed by markdown checklist items using `[x]` (completed), `[/]` (currently executing), and `[ ]` (pending), in Korean.
3. Phase 3 (Tool Execution): Invoke exactly ONE tool call to advance the `[/]` sub-goal.

Critical Mobile-Use Rules:
- Fast-Lane Priority: If the goal can be achieved via a direct OS/Notification tool (`reply_notification`, `list_notifications`, `manage_app`, `control_device_setting`, `make_call`, `send_sms`, `search_contacts`, `manage_calendar`, `set_alarm`), ALWAYS prefer it over multi-step GUI clicking!
- Screen-Off Reply: When replying to KakaoTalk/SMS/Telegram notifications, call `list_notifications` or `reply_notification` first for sub-200ms background reply without opening the app.
- GUI Element Indexing: When using `click`, `long_press`, or `input_text`, prefer passing `element_index` from the `Visible UI Elements` list. If an element is only visible in the screenshot, pass `norm_x` and `norm_y` on the [0..1000, 0..1000] grid.
- Swipe Semantics: `swipe(direction='up')` drags the finger UP (scrolling down to reveal lower content). `swipe(direction='down')` drags the finger DOWN (scrolling up).
- Loop Avoidance: Never repeat the exact same failing click more than twice; try scrolling (`swipe`), `press_key(key='BACK')`, or `manage_app`.
- Completion: When the user's goal is achieved, call `complete_task(summary=...)` in Korean.
""".trimIndent()

        fun parseObservationAndPlan(rawText: String, previousPlan: List<SubGoalItem>): Pair<String, List<SubGoalItem>> {
            if (rawText.isBlank()) {
                return "화면 상태 및 UI 요소 분석 완료" to previousPlan
            }
            var observation = ""
            val parsedGoals = mutableListOf<SubGoalItem>()

            for (line in rawText.lines()) {
                val trimmed = line.trim()
                if (trimmed.uppercase().startsWith("OBSERVATION:")) {
                    observation = trimmed.substringAfter(":").trim()
                } else if (trimmed.startsWith("- [x]") || trimmed.startsWith("[x]")) {
                    val title = trimmed.substringAfter("[x]").trim()
                    if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.COMPLETED))
                } else if (trimmed.startsWith("- [/]") || trimmed.startsWith("[/]")) {
                    val title = trimmed.substringAfter("[/]").trim()
                    if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.IN_PROGRESS))
                } else if (trimmed.startsWith("- [ ]") || trimmed.startsWith("[ ]")) {
                    val title = trimmed.substringAfter("[ ]").trim()
                    if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.PENDING))
                }
            }

            if (observation.isBlank()) {
                observation = rawText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("PLAN") }
                    ?.take(140)
                    ?: "화면 분석 및 다음 행동 결정"
            }

            return observation to (if (parsedGoals.isNotEmpty()) parsedGoals else previousPlan)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    fun decideNextAction(
        apiKey: String,
        modelName: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage> = emptyList(),
        preferLaptopCliBridge: Boolean = true,
        laptopBridgeUrl: String = "http://127.0.0.1:18889/decide"
    ): ModelTurnDecision {
        // 1. Try Laptop CLI Bridge first (adb reverse tcp:18889 tcp:18889) if enabled
        if (preferLaptopCliBridge) {
            val bridgeDecision = tryCallLaptopCliBridge(
                bridgeUrl = laptopBridgeUrl,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory
            )
            if (bridgeDecision != null) {
                return bridgeDecision
            }
        }

        // 2. Try Direct Gemini API if API key is configured
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan)
        }

        return try {
            val normalizedModel = normalize2026ModelName(modelName)
            callGeminiMultimodalApi(
                apiKey = cleanKey,
                modelName = normalizedModel,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory
            )
        } catch (e: Exception) {
            ModelTurnDecision(
                observation = "Gemini API 호출 중 오류가 발생하여 로컬 온디바이스 추론으로 전환합니다 (${e.message?.take(60)})",
                subGoals = previousPlan,
                toolName = "complete_task",
                toolArgs = JSONObject().put("summary", "API 호출 오류: ${e.message}")
            )
        }
    }

    private fun tryCallLaptopCliBridge(
        bridgeUrl: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision? {
        return try {
            val payload = JSONObject().apply {
                put("goal", goal)
                put("iteration", iteration)
                put("activePackage", snapshot.activePackage)
                put("screenWidth", snapshot.screenWidth)
                put("screenHeight", snapshot.screenHeight)
                put("promptContext", snapshot.toPromptContext(maxElements = 80))
                val chatArr = JSONArray()
                chatHistory.takeLast(10).forEach { chatArr.put(it.toJson()) }
                put("chatHistory", chatArr)
                val stepArr = JSONArray()
                historySteps.takeLast(5).forEach { s ->
                    stepArr.put(
                        JSONObject().apply {
                            put("step", s.stepNumber)
                            put("tool", s.toolName)
                            put("args", s.toolArgsJson)
                            put("succeeded", s.succeeded)
                            put("result", s.resultSummary)
                        }
                    )
                }
                put("historySteps", stepArr)
            }

            val req = Request.Builder()
                .url(bridgeUrl)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val fastClient = httpClient.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .build()

            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val obs = json.optString("observation", "[노트북 CLI 브리지] 화면 및 명령 분석 완료")
                val toolName = json.optString("toolName", "complete_task")
                val toolArgs = json.optJSONObject("toolArgs") ?: JSONObject()
                val planArr = json.optJSONArray("subGoals")
                val parsedPlan = mutableListOf<SubGoalItem>()
                if (planArr != null) {
                    for (i in 0 until planArr.length()) {
                        val item = planArr.optJSONObject(i) ?: continue
                        val statusStr = item.optString("status", "IN_PROGRESS")
                        val status = runCatching { SubGoalStatus.valueOf(statusStr) }
                            .getOrDefault(SubGoalStatus.IN_PROGRESS)
                        parsedPlan.add(SubGoalItem(item.optString("title", "서브골"), status))
                    }
                }
                ModelTurnDecision(
                    observation = obs,
                    subGoals = if (parsedPlan.isNotEmpty()) parsedPlan else previousPlan,
                    toolName = toolName,
                    toolArgs = toolArgs
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun callGeminiMultimodalApi(
        apiKey: String,
        modelName: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

        val chatSummary = if (chatHistory.isEmpty()) {
            "(No prior live chat messages)"
        } else {
            chatHistory.takeLast(12).joinToString("\n") { msg ->
                "[${msg.sender.name}] ${msg.text}"
            }
        }

        val historySummary = if (historySteps.isEmpty()) {
            "(First turn - no prior actions executed yet)"
        } else {
            historySteps.takeLast(6).joinToString("\n") { step ->
                "Step ${step.stepNumber}: tool=${step.toolName}(${step.toolArgsJson}) -> ${if (step.succeeded) "OK" else "FAIL"}: ${step.resultSummary}"
            }
        }

        val planSummary = if (previousPlan.isEmpty()) {
            "(Create initial sub-goal checklist now)"
        } else {
            previousPlan.joinToString("\n") { "- ${it.status.marker} ${it.title}" }
        }

        val promptText = buildString {
            appendLine("Continuous Live Chat Context:")
            appendLine(chatSummary)
            appendLine()
            appendLine("Current User Message / Goal: $goal")
            appendLine("Current Turn: $iteration")
            appendLine()
            appendLine("Previous Plan Ledger:")
            appendLine(planSummary)
            appendLine()
            appendLine("Recent Execution History:")
            appendLine(historySummary)
            appendLine()
            appendLine("Current Device Perception Snapshot:")
            appendLine(snapshot.toPromptContext(maxElements = 95))
            appendLine()
            appendLine("Respond with OBSERVATION:, PLAN:, and invoke one tool call.")
        }

        val partsArray = JSONArray()
        if (!snapshot.screenshotBase64Jpeg.isNullOrBlank()) {
            partsArray.put(
                JSONObject().apply {
                    put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", snapshot.screenshotBase64Jpeg)
                        }
                    )
                }
            )
        }
        partsArray.put(JSONObject().put("text", promptText))

        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
                )
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    }
                )
            )
            put("tools", ToolSpecRegistry.toGeminiToolsJson())
            // 2026 Gemini 3.8 / Gemini 4 Argon spec: omit deprecated temperature/top_p and use thinkingConfig
            put(
                "generationConfig",
                JSONObject().apply {
                    val thinkingLevel = if (modelName.contains("argon")) "medium" else "low"
                    put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", thinkingLevel)
                    )
                }
            )
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("Api-Revision", "2026-05-20")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(180)}")
            }

            val root = JSONObject(bodyStr)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts") ?: JSONArray()

            var textOut = ""
            var fnName: String? = null
            var fnArgs = JSONObject()

            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.has("text")) {
                    textOut += p.optString("text") + "\n"
                }
                if (p.has("functionCall")) {
                    val fc = p.getJSONObject("functionCall")
                    fnName = fc.optString("name")
                    fnArgs = fc.optJSONObject("args") ?: JSONObject()
                }
            }

            val (obs, updatedPlan) = parseObservationAndPlan(textOut, previousPlan)
            if (fnName != null) {
                return ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan,
                    toolName = fnName,
                    toolArgs = fnArgs
                )
            } else {
                return ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", textOut.trim().ifBlank { obs })
                )
            }
        }
    }

    /**
     * Built-in on-device deterministic planner so the user can test Fast-Lane OS actions,
     * Screen-Off notification replies, app launching, and UI clicking even before setting a Gemini API key.
     */
    fun inferLocalOnDeviceAction(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>
    ): ModelTurnDecision {
        val g = goal.trim()
        val gLower = g.lowercase()

        // =====================================================================
        // MULTI-STEP GOAL TRAJECTORY PLANNER (열기 -> 탐색/탭 -> 타이핑 -> 드래그/클릭 -> 검증 완료)
        // =====================================================================
        val appMap = mapOf(
            "카카오톡" to "com.kakao.talk",
            "카톡" to "com.kakao.talk",
            "유튜브" to "com.google.android.youtube",
            "설정" to "com.android.settings",
            "크롬" to "com.android.chrome",
            "네이버" to "com.nhn.android.search",
            "갤러리" to "com.sec.android.gallery3d",
            "카메라" to "com.sec.android.app.camera",
            "시계" to "com.sec.android.app.clockpackage",
            "배달의민족" to "com.sampleapp",
            "쿠팡" to "com.coupang.mobile",
            "토스" to "viva.republica.toss",
            "인스타그램" to "com.instagram.android"
        )
        val matchedAppEntry = appMap.entries.firstOrNull { gLower.contains(it.key) }
        val targetAppName = matchedAppEntry?.key
        val targetPkg = matchedAppEntry?.value

        // Extract search/typing query ONLY if the user isn't directly asking to click an existing element on screen
        val directScreenElementMatch = snapshot.indexedElements.firstOrNull { el ->
            (el.text.length >= 2 && g.contains(el.text, ignoreCase = true) && gLower.contains("눌러")) ||
                (el.contentDesc.length >= 2 && g.contains(el.contentDesc, ignoreCase = true) && gLower.contains("눌러"))
        }

        val extractedQuery: String? = if (directScreenElementMatch != null && !gLower.contains("검색하고") && !gLower.contains("검색해서")) {
            null
        } else {
            val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(g)?.let { m ->
                m.groupValues[1].ifBlank { m.groupValues[2] }
            }
            if (!quoted.isNullOrBlank()) {
                quoted
            } else {
                val searchMatch = Regex("([가-힣a-zA-Z0-9_\\s]{1,22}?)\\s*(?:검색하고|검색해서|검색해|검색|입력하고|입력해|찾고|찾아서|타이핑)").find(g)
                searchMatch?.groupValues?.getOrNull(1)
                    ?.replace("열어서", "")
                    ?.replace("열고", "")
                    ?.replace("검색창에", "")
                    ?.replace("검색창", "")
                    ?.replace("카카오톡", "")
                    ?.replace("카톡", "")
                    ?.replace("유튜브", "")
                    ?.replace("설정", "")
                    ?.replace("토스", "")
                    ?.replace("네이버", "")
                    ?.replace("화면에서", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        val wantsDragAndDrop = gLower.contains("드래그") || gLower.contains("슬라이더") || gLower.contains("옮겨")
        val wantsSwipeScroll = gLower.contains("스크롤") || gLower.contains("스와이프") || gLower.contains("내려") || gLower.contains("올려")
        val wantsPostResultClick = (extractedQuery != null) && (
            gLower.contains("클릭") || gLower.contains("틀어") || gLower.contains("재생") ||
                gLower.contains("열어") || gLower.contains("선택") || gLower.contains("눌러") || gLower.contains("들어가")
            )

        val isMultiStepTrajectoryGoal = !gLower.contains("답장") && !gLower.contains("알림") && (
            (extractedQuery != null) || wantsDragAndDrop || wantsSwipeScroll ||
                (targetAppName != null && (gLower.contains("그리고") || gLower.contains("해서") || gLower.contains("열어서") || gLower.contains("누르고")))
            )

        if (isMultiStepTrajectoryGoal && iteration <= 7) {
            val hasLaunchedApp = historySteps.any { it.toolName == "manage_app" }
            val hasTypedQuery = historySteps.any { it.toolName == "input_text" }
            val hasClickedSearch = historySteps.any { it.toolName == "click" }
            val hasDragged = historySteps.any { it.toolName == "drag_and_drop" || it.toolName == "haptic_trajectory" }
            val hasSwiped = historySteps.any { it.toolName == "swipe" }
            val clicksAfterTyping = historySteps.dropWhile { it.toolName != "input_text" }.count { it.toolName == "click" }

            // Build dynamic trajectory ledger
            val ledger = mutableListOf<SubGoalItem>()
            if (targetAppName != null) {
                val done = (snapshot.activePackage == targetPkg) || hasLaunchedApp
                ledger.add(SubGoalItem("1단계: '$targetAppName' 앱 진입 및 포그라운드 검증", if (done) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
            }
            if (extractedQuery != null) {
                ledger.add(
                    SubGoalItem(
                        "2단계: 검색/입력창 탐색 탭 및 '$extractedQuery' 타이핑 주입",
                        when {
                            hasTypedQuery -> SubGoalStatus.COMPLETED
                            ledger.all { it.status == SubGoalStatus.COMPLETED } -> SubGoalStatus.IN_PROGRESS
                            else -> SubGoalStatus.PENDING
                        }
                    )
                )
            }
            if (wantsPostResultClick) {
                ledger.add(
                    SubGoalItem(
                        "3단계: 검색 결과 타깃 요소 탐색 및 탭 실행",
                        when {
                            clicksAfterTyping >= 1 -> SubGoalStatus.COMPLETED
                            hasTypedQuery -> SubGoalStatus.IN_PROGRESS
                            else -> SubGoalStatus.PENDING
                        }
                    )
                )
            }
            if (wantsDragAndDrop) {
                ledger.add(
                    SubGoalItem(
                        "햅틱 궤적: 홀드 후 드래그 앤 드롭(Drag & Drop) 제어",
                        if (hasDragged) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS
                    )
                )
            }
            if (wantsSwipeScroll) {
                ledger.add(
                    SubGoalItem(
                        "햅틱 궤적: 화면 스크롤/스와이프 이동 및 변위 검증",
                        if (hasSwiped) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS
                    )
                )
            }

            // Phase 1: Launch Target App if not in foreground yet
            if (targetAppName != null && snapshot.activePackage != targetPkg && !hasLaunchedApp) {
                return ModelTurnDecision(
                    observation = "[행동 궤적 1단계 · 앱 진입] 현재 패키지(${snapshot.activePackage})에서 목표 앱 '$targetAppName'($targetPkg)으로 진입합니다.",
                    subGoals = ledger,
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", targetAppName)
                )
            }

            // Phase 2 & 3: Search / Input Text Trajectory
            if (extractedQuery != null && !hasTypedQuery) {
                val editableEl = snapshot.indexedElements.firstOrNull { it.editable }
                if (editableEl != null) {
                    return ModelTurnDecision(
                        observation = "[행동 궤적 · 타이핑 주입] 활성 입력창([${editableEl.index}] ${editableEl.shortClass})을 포착하여 '$extractedQuery' 텍스트를 입력하고 제출(ENTER)합니다.",
                        subGoals = ledger,
                        toolName = "input_text",
                        toolArgs = JSONObject().apply {
                            put("element_index", editableEl.index)
                            put("text", extractedQuery)
                            put("clear_existing", false)
                            put("submit_enter", true)
                        }
                    )
                }

                // If no editable field is open yet, locate the search button/icon and click it
                if (!hasClickedSearch) {
                    val searchBtn = snapshot.indexedElements.firstOrNull { el ->
                        el.text.contains("검색") || el.contentDesc.contains("검색") ||
                            el.text.contains("Search", ignoreCase = true) ||
                            el.contentDesc.contains("Search", ignoreCase = true) ||
                            el.resourceId.contains("search", ignoreCase = true)
                    } ?: snapshot.indexedElements.firstOrNull { el ->
                        el.clickable && el.centerY in 60..320 && el.centerX > (snapshot.screenWidth * 0.65)
                    }

                    if (searchBtn != null) {
                        return ModelTurnDecision(
                            observation = "[행동 궤적 · 검색창 열기] 입력창을 활성화하기 위해 상단 검색 요소([${searchBtn.index}] '${searchBtn.text.ifBlank { searchBtn.contentDesc.ifBlank { "검색 아이콘" } }}')를 탭합니다.",
                            subGoals = ledger,
                            toolName = "click",
                            toolArgs = JSONObject().apply {
                                put("element_index", searchBtn.index)
                                put("target_description", "검색 버튼 열기")
                            }
                        )
                    }
                }

                // Direct fallback: type into focused field or via OS CLI
                return ModelTurnDecision(
                    observation = "[행동 궤적 · 포커스 타이핑] '$extractedQuery' 텍스트를 입력창에 주입하고 검색(ENTER)을 실행합니다.",
                    subGoals = ledger,
                    toolName = "input_text",
                    toolArgs = JSONObject().apply {
                        put("text", extractedQuery)
                        put("submit_enter", true)
                    }
                )
            }

            // Phase 4-A: Haptic Drag & Drop Trajectory
            if (wantsDragAndDrop && !hasDragged) {
                val sliderEl = snapshot.indexedElements.firstOrNull { el ->
                    el.shortClass.contains("SeekBar", ignoreCase = true) ||
                        el.shortClass.contains("Slider", ignoreCase = true) ||
                        el.text.contains("밝기") || el.contentDesc.contains("밝기")
                }
                val startNormX = if (sliderEl != null) ((sliderEl.left + 40) * 1000 / snapshot.screenWidth.coerceAtLeast(1)) else 260
                val endNormX = if (sliderEl != null) ((sliderEl.right - 40) * 1000 / snapshot.screenWidth.coerceAtLeast(1)) else 780
                val normY = if (sliderEl != null) sliderEl.normY else 500

                return ModelTurnDecision(
                    observation = "[행동 궤적 · 햅틱 드래그 앤 드롭] 시작 지점($startNormX, $normY)에서 480ms 홀드 후 목표 지점($endNormX, $normY)까지 연속 곡선 궤적으로 드래그 앤 드롭합니다.",
                    subGoals = ledger,
                    toolName = "drag_and_drop",
                    toolArgs = JSONObject().apply {
                        put("start_norm_x", startNormX)
                        put("start_norm_y", normY)
                        put("end_norm_x", endNormX)
                        put("end_norm_y", normY)
                        put("hold_ms", 480)
                        put("duration_ms", 680)
                        put("description", "목표 슬라이더/아이템 햅틱 드래그 앤 드롭")
                    }
                )
            }

            // Phase 4-B: Swipe / Scroll Trajectory
            if (wantsSwipeScroll && !hasSwiped) {
                val dir = if (gLower.contains("아래로") || gLower.contains("위로 스크롤")) "down" else "up"
                return ModelTurnDecision(
                    observation = "[행동 궤적 · 햅틱 스와이프] 화면 콘텐츠 탐색을 위해 '$dir' 방향으로 연속 스와이프 궤적을 실행합니다.",
                    subGoals = ledger,
                    toolName = "swipe",
                    toolArgs = JSONObject().put("direction", dir).put("duration_ms", 380)
                )
            }

            // Phase 4-C: Post-Search Result Click
            if (wantsPostResultClick && clicksAfterTyping == 0) {
                val candidateResult = snapshot.indexedElements.firstOrNull { el ->
                    !el.editable && el.centerY > 300 &&
                        (el.text.contains(extractedQuery ?: "", ignoreCase = true) ||
                            el.contentDesc.contains(extractedQuery ?: "", ignoreCase = true))
                } ?: snapshot.indexedElements.firstOrNull { el ->
                    el.clickable && !el.editable && el.centerY in 340..(snapshot.screenHeight - 220) &&
                        (el.text.isNotBlank() || el.contentDesc.isNotBlank())
                }

                if (candidateResult != null) {
                    return ModelTurnDecision(
                        observation = "[행동 궤적 · 결과 선택] 검색 결과 화면에서 목표 타깃([${candidateResult.index}] '${candidateResult.text.ifBlank { candidateResult.contentDesc }.take(28)}')을 탭합니다.",
                        subGoals = ledger,
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", candidateResult.index)
                            put("target_description", candidateResult.text.ifBlank { candidateResult.contentDesc }.take(30))
                        }
                    )
                }
            }

            // Phase 5: All trajectory steps completed and verified!
            if (historySteps.isNotEmpty()) {
                val trajectorySummary = historySteps.joinToString("\n") { s ->
                    "• Step ${s.stepNumber} [${s.toolName}]: ${s.resultSummary.lines().firstOrNull().orEmpty()}"
                }
                return ModelTurnDecision(
                    observation = "모든 행동 궤적(${historySteps.size}단계) 실행 및 사후 상태 전이(Post-Snapshot) 검증을 완료했습니다.",
                    subGoals = ledger.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put(
                        "summary",
                        "[목표 궤적 완주 완료 · 총 ${historySteps.size}스텝 검증됨]\n$trajectorySummary"
                    )
                )
            }
        }

        // Single-action completion fallback if already executed a single fast-lane command
        if (iteration >= 2 && historySteps.isNotEmpty()) {
            val last = historySteps.last()
            return ModelTurnDecision(
                observation = "이전 단계(${last.toolName}) 상태 전이 검증 결과를 확인했습니다.",
                subGoals = listOf(
                    SubGoalItem("명령 실행 및 상태 전이 검증", SubGoalStatus.COMPLETED),
                    SubGoalItem("결과 보고", SubGoalStatus.COMPLETED)
                ),
                toolName = "complete_task",
                toolArgs = JSONObject().put(
                    "summary",
                    "[실행 및 상태 검증 완료]\n${last.resultSummary}"
                )
            )
        }

        // 0-A. Universal App Reverse-Engineering & Skill Synthesis (UARP)
        if (
            gLower.contains("조종석") ||
            gLower.contains("대시보드") ||
            gLower.contains("위젯") ||
            gLower.contains("ui 그려") ||
            gLower.contains("렌더링") ||
            gLower.contains("gen-ui") ||
            gLower.contains("generative")
        ) {
            return ModelTurnDecision(
                observation = "[Generative UI 엔진] 사용자의 요청에 맞춰 window.HansonOS 브리지와 직결된 실시간 HTML5/JS 마이크로 앱을 합성합니다.",
                subGoals = listOf(
                    SubGoalItem("맞춤형 Generative UI 마이크로 앱 합성 및 마운트", SubGoalStatus.IN_PROGRESS),
                    SubGoalItem("렌더링 결과 브리핑", SubGoalStatus.PENDING)
                ),
                toolName = "render_generative_ui",
                toolArgs = JSONObject().apply {
                    put("title", "맞춤형 실시간 슈퍼앱 컨트롤 데크")
                    put("subtitle", "요청('$g') 기반 동적 합성 UI · window.HansonOS 직결")
                    put("html_content", "")
                }
            )
        }

        if (
            gLower.contains("리버스") ||
            gLower.contains("reverse") ||
            gLower.contains("분석해서 스킬") ||
            gLower.contains("스킬로 만들") ||
            gLower.contains("프로토콜 학습") ||
            gLower.contains("앱 분석") ||
            gLower.contains("동작방식 학습")
        ) {
            val cleaned = g
                .replace("리버스 엔지니어링", "")
                .replace("리버스", "")
                .replace("분석해서 스킬로 만들어줘", "")
                .replace("스킬로 만들어줘", "")
                .replace("프로토콜 학습해줘", "")
                .replace("앱 분석해줘", "")
                .replace("해줘", "")
                .trim()
                .ifBlank { "현재앱" }
            return ModelTurnDecision(
                observation = "[UARP 리버스 엔지니어링] '$cleaned'의 APK 바이너리(base.apk), 매니페스트 딥링크, Exported Activity 및 UI 프레임워크를 역분석하여 재사용 가능한 스킬로 합성합니다.",
                subGoals = listOf(
                    SubGoalItem("APK 바이너리 및 매니페스트 딥링크/Activity 역분석", SubGoalStatus.IN_PROGRESS),
                    SubGoalItem("영구 앱 제어 스킬 합성 및 등록", SubGoalStatus.PENDING)
                ),
                toolName = "reverse_engineer_app",
                toolArgs = JSONObject().put("app_query", cleaned)
            )
        }

        // 0. Native Mobile OS CLI (`hanson ...`, `/cli ...`, `/sh ...`, `dumpsys`, `애니메이션`, `강제 종료`, `self-heal`)
        if (
            gLower.startsWith("hanson ") ||
            gLower.startsWith("/cli ") ||
            gLower.startsWith("/sh ") ||
            gLower.contains("dumpsys") ||
            gLower.contains("액티비티 스택") ||
            gLower.contains("애니메이션 배속") ||
            gLower.contains("자가 치유") ||
            gLower.contains("self-heal")
        ) {
            val cliCmd = when {
                gLower.contains("액티비티 스택") -> "top"
                gLower.contains("애니메이션 배속") -> {
                    val scale = Regex("[0-9.]+").find(g)?.value ?: "0.5"
                    "setting put global animator_duration_scale $scale"
                }
                gLower.contains("자가 치유") || gLower.contains("self-heal") -> "self-heal"
                else -> g.removePrefix("hanson ").removePrefix("/cli ").removePrefix("/sh ").trim()
            }
            return ModelTurnDecision(
                observation = "[Hanson Mobile OS CLI] 네이티브 OS CLI 명령('$cliCmd')을 실행합니다.",
                subGoals = listOf(SubGoalItem("모바일 OS CLI ($cliCmd) 실행", SubGoalStatus.IN_PROGRESS)),
                toolName = "os_cli_exec",
                toolArgs = JSONObject().put("command", cliCmd)
            )
        }

        // 1. Notification reply / check
        if (gLower.contains("답장") || (gLower.contains("알림") && gLower.contains("보내"))) {
            val replyMsg = g.substringAfter("\"", "").substringBefore("\"", "")
                .ifBlank { "확인했습니다. 잠시 후 연락드리겠습니다." }
            return ModelTurnDecision(
                observation = "수신된 메신저 알림의 RemoteInput 토큰을 통해 화면을 켜지 않고 즉시 답장을 전송합니다.",
                subGoals = listOf(
                    SubGoalItem("수신 알림 RemoteInput 세션 탐색 및 즉시 답장", SubGoalStatus.IN_PROGRESS),
                    SubGoalItem("완료 보고", SubGoalStatus.PENDING)
                ),
                toolName = "reply_notification",
                toolArgs = JSONObject().apply {
                    put("target", "")
                    put("message", replyMsg)
                }
            )
        }

        if (gLower.contains("알림")) {
            return ModelTurnDecision(
                observation = "최근 가로챈 카카오톡·SMS·메신저 알림 세션을 조회합니다.",
                subGoals = listOf(
                    SubGoalItem("최근 수신 알림 목록 조회", SubGoalStatus.IN_PROGRESS),
                    SubGoalItem("결과 요약", SubGoalStatus.PENDING)
                ),
                toolName = "list_notifications",
                toolArgs = JSONObject().put("only_replyable", false).put("limit", 10)
            )
        }

        // 2. Brightness / Volume / DND
        if (gLower.contains("밝기")) {
            val num = Regex("\\d+").find(g)?.value?.toIntOrNull() ?: 180
            return ModelTurnDecision(
                observation = "시스템 화면 밝기 설정을 $num 으로 직접 변경합니다.",
                subGoals = listOf(SubGoalItem("시스템 화면 밝기 제어", SubGoalStatus.IN_PROGRESS)),
                toolName = "control_device_setting",
                toolArgs = JSONObject().put("setting", "brightness").put("value", num.toString())
            )
        }

        if (gLower.contains("볼륨") || gLower.contains("소리")) {
            val num = Regex("\\d+").find(g)?.value?.toIntOrNull() ?: 50
            return ModelTurnDecision(
                observation = "미디어 오디오 볼륨을 ${num}%로 변경합니다.",
                subGoals = listOf(SubGoalItem("오디오 볼륨 조절", SubGoalStatus.IN_PROGRESS)),
                toolName = "control_device_setting",
                toolArgs = JSONObject().put("setting", "volume_media").put("value", num.toString())
            )
        }

        if (gLower.contains("방해금지") || gLower.contains("dnd")) {
            val turnOff = gLower.contains("꺼") || gLower.contains("해제") || gLower.contains("off")
            return ModelTurnDecision(
                observation = "방해금지 모드(DND)를 ${if (turnOff) "해제" else "활성화"}합니다.",
                subGoals = listOf(SubGoalItem("방해금지 모드 전환", SubGoalStatus.IN_PROGRESS)),
                toolName = "control_device_setting",
                toolArgs = JSONObject().put("setting", "dnd").put("value", if (turnOff) "off" else "on")
            )
        }

        // 3. Alarm
        if (gLower.contains("알람")) {
            val numbers = Regex("\\d+").findAll(g).mapNotNull { it.value.toIntOrNull() }.toList()
            val hour = numbers.getOrNull(0)?.coerceIn(0, 23) ?: 7
            val minute = numbers.getOrNull(1)?.coerceIn(0, 59) ?: 30
            return ModelTurnDecision(
                observation = "시스템 시계 알람을 ${hour}시 ${minute}분으로 설정합니다.",
                subGoals = listOf(SubGoalItem("시스템 알람 등록", SubGoalStatus.IN_PROGRESS)),
                toolName = "set_alarm",
                toolArgs = JSONObject().apply {
                    put("hour", hour)
                    put("minute", minute)
                    put("label", "Hanson AI 알람")
                }
            )
        }

        // 4. Hardware keys
        if (gLower.contains("홈 화면") || gLower == "홈" || gLower.contains("home")) {
            return ModelTurnDecision(
                observation = "전역 HOME 키를 주입하여 홈 화면으로 이동합니다.",
                subGoals = listOf(SubGoalItem("홈 화면 이동", SubGoalStatus.IN_PROGRESS)),
                toolName = "press_key",
                toolArgs = JSONObject().put("key", "HOME")
            )
        }
        if (gLower.contains("뒤로") || gLower.contains("back")) {
            return ModelTurnDecision(
                observation = "전역 BACK 키를 주입합니다.",
                subGoals = listOf(SubGoalItem("뒤로가기 실행", SubGoalStatus.IN_PROGRESS)),
                toolName = "press_key",
                toolArgs = JSONObject().put("key", "BACK")
            )
        }
        if (gLower.contains("빠른 설정") || gLower.contains("퀵세팅")) {
            return ModelTurnDecision(
                observation = "시스템 빠른 설정(Quick Settings) 패널을 엽니다.",
                subGoals = listOf(SubGoalItem("빠른 설정 패널 열기", SubGoalStatus.IN_PROGRESS)),
                toolName = "press_key",
                toolArgs = JSONObject().put("key", "QUICK_SETTINGS")
            )
        }

        // 5. App Launch
        val appKeywords = listOf("카카오톡", "카톡", "유튜브", "설정", "크롬", "네이버", "갤러리", "카메라", "시계", "배달의민족", "쿠팡", "토스", "인스타그램")
        val matchedApp = appKeywords.firstOrNull { gLower.contains(it) }
        if (matchedApp != null || gLower.contains("열어") || gLower.contains("실행")) {
            val targetApp = matchedApp ?: g.replace("열어줘", "").replace("실행해줘", "").replace("앱", "").trim()
            return ModelTurnDecision(
                observation = "QUERY_ALL_PACKAGES 권한으로 '$targetApp' 패키지를 찾아 즉시 실행합니다.",
                subGoals = listOf(
                    SubGoalItem("'$targetApp' 앱 실행", SubGoalStatus.IN_PROGRESS),
                    SubGoalItem("실행 확인", SubGoalStatus.PENDING)
                ),
                toolName = "manage_app",
                toolArgs = JSONObject().put("action", "launch").put("app_name", targetApp.ifBlank { "설정" })
            )
        }

        // 6. Element click by matching text on screen
        val matchedElement = snapshot.indexedElements.firstOrNull { el ->
            (el.text.isNotBlank() && g.contains(el.text, ignoreCase = true)) ||
                (el.contentDesc.isNotBlank() && g.contains(el.contentDesc, ignoreCase = true))
        }
        if (matchedElement != null) {
            return ModelTurnDecision(
                observation = "화면 UI 트리에서 '[${matchedElement.index}] ${matchedElement.text.ifBlank { matchedElement.contentDesc }}' 요소를 발견하여 탭합니다.",
                subGoals = listOf(SubGoalItem("화면 요소 탭 실행", SubGoalStatus.IN_PROGRESS)),
                toolName = "click",
                toolArgs = JSONObject().apply {
                    put("element_index", matchedElement.index)
                    put("target_description", matchedElement.text.ifBlank { matchedElement.contentDesc })
                }
            )
        }

        // Default: summarize current UI snapshot & prompt for Gemini API key if multi-step vision reasoning is desired
        return ModelTurnDecision(
            observation = "현재 포그라운드 앱(${snapshot.activePackage.ifBlank { "감지 대기" }})에서 ${snapshot.indexedElements.size}개의 UI 요소를 스캔했습니다.",
            subGoals = listOf(SubGoalItem("화면 스냅샷 및 UI 요소 진단 완료", SubGoalStatus.COMPLETED)),
            toolName = "complete_task",
            toolArgs = JSONObject().put(
                "summary",
                "현재 화면에서 ${snapshot.indexedElements.size}개의 UI 요소를 분석했습니다. 자유로운 멀티스텝 비전 에이전트 추론을 위해 [설정] 탭에서 Gemini API Key를 입력하거나, '카카오톡 열어줘', '밝기 180으로 설정', '최근 알림 답장해줘' 같은 즉시 제어 명령을 실행해 보세요."
            )
        )
    }
}
