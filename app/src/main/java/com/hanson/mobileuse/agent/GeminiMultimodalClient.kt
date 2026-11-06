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
    val toolArgs: JSONObject,
    val spokenReply: String = ""
)

/**
 * Conversational Multimodal VLM + Full-Control Mobile Operator (`GeminiMultimodalClient`).
 *
 * Combines real-time natural Korean dialogue (`spokenReply`) with full device control
 * (`click`, `long_press`, `input_text`, `swipe`, `drag_and_drop`, `haptic_trajectory`,
 * `press_key`, `manage_app`, `reply_notification`, `control_device_setting`, `ask_user`, `complete_task`).
 */
class GeminiMultimodalClient {

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"

        /**
         * Official model catalog synchronized with https://aistudio.google.com/docs/models
         */
        val AISTUDIO_OFFICIAL_MODELS: List<Pair<String, String>> = listOf(
            // Gemini 4 Flagship
            "gemini-4-argon" to "[Gemini 4 · Flagship] 공유 1M 컨텍스트 · 최대 1M Long Decode · 고차원 추론 (thinking_level=medium)",
            // Gemini 3.8 & 3.x Multimodal / Agentic
            "gemini-3.8-flash" to "[Gemini 3.8 · New 권장] 자율 에이전트 · 장기 엔지니어링 · 1M 컨텍스트 (thinking_level=low)",
            "gemini-3.6-flash" to "[Gemini 3.6 · Stable] 프로덕션 안정화 범용 멀티모달 에이전트 Flash",
            "gemini-3.5-flash-lite" to "[Gemini 3.5 · Stable] 초저지연·초고효율 서브에이전트 & 고속 실행 (2.5-flash 공식 대체)",
            "gemini-3.1-flash-lite" to "[Gemini 3.1 · Stable] 프론티어급 초경량·최저비용 고속 멀티모달",
            "gemini-3.1-pro-preview" to "[Gemini 3.1 · Preview] 고난도 멀티모달 추론 프리뷰 모델",
            "gemini-3-flash-preview" to "[Gemini 3 · Preview] 차세대 고속 프론티어 프리뷰 모델",
            // Specialized Embodied & Managed Agents (aistudio.google.com/docs/models)
            "gemini-robotics-er-2-preview" to "[Embodied Reasoning 2 · Preview] 공간 추론(Spatial) · 비디오 이해 · 멀티스텝 물리 제어 특화 VLM",
            "antigravity-preview-09-2026" to "[Managed Agent] Antigravity Agent — 격리 리눅스 샌드박스 코드 실행·웹 브라우징 에이전트",
            "deep-research-preview-04-2026" to "[Managed Agent] Gemini Deep Research — 자율 다단계 심층 리서치 에이전트",
            "deep-research-max-preview-04-2026" to "[Managed Agent] Gemini Deep Research Max — 최대 깊이 자율 리서치 에이전트",
            // Live Audio / Speech / Translation (aistudio.google.com/docs/models#audio-models)
            "gemini-3.8-live" to "[Live API · New 기본] 추론 지연 없는 초저지연 실시간 양방향 음성·비디오 에이전트",
            "gemini-3.8-live-extended-thinking" to "[Live API · New 고추론] 실시간 음성 대화 + 백그라운드 심층 추론 결합",
            "gemini-3.8-flash-tts" to "[Audio TTS · New] 스튜디오급 Voice Design · Voice Replication · 다중 화자 합성",
            "gemini-3.8-flash-lite-tts" to "[Audio TTS · New] 실시간 음성 에이전트 캐스케이드용 초고속 TTS",
            "gemini-3.5-transcribe" to "[Speech-to-Text · Stable] 화자 분리(Diarization) · 단어 타임스탬프 · 다국어 자동 감지 STT",
            "gemini-3.5-transcribe-live" to "[Speech-to-Text · Live] 실시간 스트리밍 음성 인식 STT",
            "gemini-3.5-live-translate-preview" to "[Live Translate · Preview] 70개국어 실시간 음성-음성 동시 통역 모델",
            // Generative Media (Image / Video)
            "gemini-nano-banana-2.1" to "[Image · New] Nano Banana 2.1 — 다중 레퍼런스(14장) · 타이포그래피 · 대화형 이미지 생성/편집",
            "gemini-3-pro-image" to "[Image · Stable] Nano Banana Pro — 고해상도 컨텍스트 이미지 생성/편집",
            "gemini-3.1-flash-image" to "[Image · Stable] Nano Banana 2 — 고속 이미지 생성 및 편집",
            "gemini-3.1-flash-lite-image" to "[Image · Stable] Nano Banana 2 Lite — 초저지연 대량 이미지 생성",
            "gemini-omni-1.1-flash" to "[Video · Stable] Gemini Omni Flash — 네이티브 오디오 비디오 생성·편집·키프레임 보간",
            // Active 2.5 Lite & Local / CLI Bridges
            "gemini-2.5-flash-lite" to "[Gemini 2.5 · Active] AI Studio 공식 유지 경량 모델 (API 지속 서빙)",
            "cli-subscription-bridge" to "[로컬 CLI 직결] 노트북 CLI 구독 라우터 (Gemini CLI / Codex / Claude / Agy · API 키 불필요)",
            "gemma-4-26b-a4b-it" to "[온디바이스 로컬] Gemma 4 MoE / LiteRT-LM / Samsung AICore 오프라인 추론"
        )

        fun normalize2026ModelName(rawModel: String): String {
            val trimmed = rawModel.trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL

            return when {
                trimmed == "gemini-2.5-flash-lite" -> "gemini-2.5-flash-lite"
                trimmed == "gemini-2.5-flash" ||
                    trimmed == "gemini-2.5-flash-preview-09-2025" ||
                    trimmed == "gemini-2.0-flash-lite" -> "gemini-3.5-flash-lite"
                trimmed == "gemini-3.1-flash-lite-preview" -> "gemini-3.1-flash-lite"
                trimmed == "gemini-robotics-er-1.6-preview" -> "gemini-robotics-er-2-preview"
                trimmed == "gemini-3.1-flash-live-preview" ||
                    trimmed == "gemini-2.5-flash-native-audio-preview-12-2025" -> "gemini-3.8-live"
                trimmed == "gemini-3.1-flash-tts-preview" ||
                    trimmed == "gemini-2.5-flash-preview-tts" ||
                    trimmed == "gemini-2.5-pro-preview-tts" -> "gemini-3.8-flash-tts"
                trimmed == "gemini-2.5-flash-image" -> "gemini-nano-banana-2.1"
                trimmed == "gemini-2.5-pro" ||
                    trimmed == "gemini-3-pro-preview" ||
                    trimmed == "gemini-2.5-computer-use-preview-10-2025" ||
                    trimmed.startsWith("gemini-2.5") ||
                    trimmed.startsWith("gemini-2.0") ||
                    trimmed.startsWith("gemini-1.5") -> DEFAULT_MODEL
                else -> trimmed
            }
        }

        val SYSTEM_INSTRUCTION = """
You are Hanson, a warm, perceptive, conversational Korean voice & mobile full-control companion.
You talk naturally with the user like a smart human partner watching their phone screen together, while having complete control over every app, UI element, gesture, and OS setting.

On EVERY turn, follow this structure:
1. `SPOKEN: <1~2 natural conversational Korean sentences spoken aloud to the user. Never sound like a system log! Speak naturally, e.g., "유튜브를 열고 로파이 음악을 찾아볼게요.", "지금 카톡 화면에 엄마랑 김철수 님 대화방이 보이는데 어느 방을 열까요?", "네, 두 번째 영상을 바로 재생했어요.">`
2. `OBSERVATION: <concise Korean summary of current screen and progress>`
3. `PLAN:` followed by markdown checklist items (`[x]`, `[/]`, `[ ]`).
4. Invoke ONE tool call to advance the user's goal or respond.

Conversational Full-Control Rules:
- Communicate AND Control: When the user asks a question about the screen or just chats, answer naturally via `complete_task(summary=...)` or `ask_user(question=...)`. When the user wants an action or changes their mind mid-task ("아니 그거 말고 밑에 거 눌러줘", "조금 더 내려봐"), adapt immediately and explain naturally in `SPOKEN:`.
- Ambiguity -> Ask Naturally: If a request needs a choice (e.g. multiple matching items on screen or missing recipient/message), call `ask_user(question=...)` with a natural spoken question so the user can answer by voice.
- Fast-Lane & GUI Mastery: Use `reply_notification`, `list_notifications`, `manage_app`, `control_device_setting`, `click`, `long_press`, `input_text`, `swipe`, `drag_and_drop`, `haptic_trajectory`, and `press_key` seamlessly.
- Never output bracketed diagnostic jargon like `[행동 궤적 1단계]` or `[GROUND-TRUTH]` in `SPOKEN:` or `summary`.
""".trimIndent()

        fun parseObservationAndPlan(rawText: String, previousPlan: List<SubGoalItem>): Pair<String, List<SubGoalItem>> {
            val (_, obs, plan) = parseSpokenObservationAndPlan(rawText, previousPlan)
            return obs to plan
        }

        fun parseSpokenObservationAndPlan(
            rawText: String,
            previousPlan: List<SubGoalItem>
        ): Triple<String, String, List<SubGoalItem>> {
            if (rawText.isBlank()) {
                return Triple("", "화면 상태 분석 완료", previousPlan)
            }
            var spoken = ""
            var observation = ""
            val parsedGoals = mutableListOf<SubGoalItem>()

            for (line in rawText.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed.uppercase().startsWith("SPOKEN:") -> {
                        spoken = trimmed.substringAfter(":").trim()
                    }
                    trimmed.uppercase().startsWith("OBSERVATION:") -> {
                        observation = trimmed.substringAfter(":").trim()
                    }
                    trimmed.startsWith("- [x]") || trimmed.startsWith("[x]") -> {
                        val title = trimmed.substringAfter("[x]").trim()
                        if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.COMPLETED))
                    }
                    trimmed.startsWith("- [/]") || trimmed.startsWith("[/]") -> {
                        val title = trimmed.substringAfter("[/]").trim()
                        if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.IN_PROGRESS))
                    }
                    trimmed.startsWith("- [ ]") || trimmed.startsWith("[ ]") -> {
                        val title = trimmed.substringAfter("[ ]").trim()
                        if (title.isNotEmpty()) parsedGoals.add(SubGoalItem(title, SubGoalStatus.PENDING))
                    }
                }
            }

            if (observation.isBlank()) {
                observation = rawText.lines()
                    .firstOrNull {
                        it.isNotBlank() &&
                            !it.uppercase().startsWith("SPOKEN:") &&
                            !it.uppercase().startsWith("PLAN")
                    }
                    ?.take(140)
                    ?: spoken.ifBlank { "화면 분석 및 행동 결정" }
            }

            if (spoken.isBlank()) {
                spoken = observation
            }

            return Triple(spoken, observation, if (parsedGoals.isNotEmpty()) parsedGoals else previousPlan)
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
        preferLaptopCliBridge: Boolean = false,
        laptopBridgeUrl: String = "http://127.0.0.1:18889/decide"
    ): ModelTurnDecision {
        val useCliBridge = preferLaptopCliBridge ||
            modelName == "cli-subscription-bridge" ||
            modelName == "laptop-cli-subscription"

        if (useCliBridge) {
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

        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
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
        } catch (_: Exception) {
            inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
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
                .connectTimeout(1200, TimeUnit.MILLISECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            fastClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val obs = json.optString("observation", "화면 및 명령 분석 완료")
                val spoken = json.optString("spokenReply", obs)
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
                    toolArgs = toolArgs,
                    spokenReply = spoken
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
        val chatSummary = if (chatHistory.isEmpty()) {
            "(No prior live chat messages)"
        } else {
            chatHistory.takeLast(14).joinToString("\n") { msg ->
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
            appendLine("Live Conversation History with User:")
            appendLine(chatSummary)
            appendLine()
            appendLine("Current User Utterance / Goal: $goal")
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
            appendLine("Respond with SPOKEN:, OBSERVATION:, PLAN:, and invoke one tool call.")
        }

        val interactionsDecision = runCatching {
            callGeminiInteractionsApi(
                apiKey = apiKey,
                modelName = modelName,
                promptText = promptText,
                screenshotBase64Jpeg = snapshot.screenshotBase64Jpeg,
                previousPlan = previousPlan
            )
        }.getOrNull()
        if (interactionsDecision != null) {
            return interactionsDecision
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
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

            val (spoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            return if (fnName != null) {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan,
                    toolName = fnName,
                    toolArgs = fnArgs,
                    spokenReply = spoken
                )
            } else {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", spoken.ifBlank { obs }),
                    spokenReply = spoken
                )
            }
        }
    }

    private fun callGeminiInteractionsApi(
        apiKey: String,
        modelName: String,
        promptText: String,
        screenshotBase64Jpeg: String?,
        previousPlan: List<SubGoalItem>
    ): ModelTurnDecision? {
        val url = "https://generativelanguage.googleapis.com/v1beta/interactions"
        val isManagedAgent = modelName.startsWith("antigravity-") || modelName.startsWith("deep-research-")

        val inputArray = JSONArray()
        if (!screenshotBase64Jpeg.isNullOrBlank()) {
            inputArray.put(
                JSONObject().apply {
                    put("type", "image")
                    put("mime_type", "image/jpeg")
                    put("data", screenshotBase64Jpeg)
                }
            )
        }
        inputArray.put(
            JSONObject().apply {
                put("type", "text")
                put("text", promptText)
            }
        )

        val payload = JSONObject().apply {
            if (isManagedAgent) {
                put("agent", modelName)
                put("environment", "remote")
            } else {
                put("model", modelName)
            }
            put("system_instruction", SYSTEM_INSTRUCTION)
            put("input", inputArray)
            put("tools", ToolSpecRegistry.toGeminiInteractionsToolsJson())
            if (!isManagedAgent) {
                val thinkingLevel = if (modelName.contains("argon")) "medium" else "low"
                put(
                    "generation_config",
                    JSONObject().apply {
                        put("thinking_level", thinkingLevel)
                        put("max_output_tokens", 65536)
                    }
                )
            }
        }

        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Api-Revision", "2026-05-20")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = resp.body?.string().orEmpty()
            val root = JSONObject(bodyStr)
            val steps = root.optJSONArray("steps") ?: return null

            var textOut = ""
            var thoughtSummary = ""
            var fnName: String? = null
            var fnArgs = JSONObject()

            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                when (step.optString("type")) {
                    "thought" -> {
                        val summary = step.optString("summary")
                        if (summary.isNotBlank()) thoughtSummary = summary
                    }
                    "function_call" -> {
                        fnName = step.optString("name")
                        fnArgs = step.optJSONObject("arguments") ?: JSONObject()
                    }
                    "model_output" -> {
                        val contentArr = step.optJSONArray("content")
                        if (contentArr != null) {
                            for (j in 0 until contentArr.length()) {
                                val item = contentArr.optJSONObject(j) ?: continue
                                if (item.optString("type") == "text") {
                                    textOut += item.optString("text") + "\n"
                                }
                            }
                        }
                    }
                }
            }

            val combinedText = if (textOut.isNotBlank()) textOut else thoughtSummary
            val (spoken, obs, updatedPlan) = parseSpokenObservationAndPlan(combinedText, previousPlan)
            return if (fnName != null) {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan,
                    toolName = fnName,
                    toolArgs = fnArgs,
                    spokenReply = spoken
                )
            } else {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", spoken.ifBlank { obs }),
                    spokenReply = spoken
                )
            }
        }
    }

    /**
     * Context-Aware Conversational Screen Partner & Full-Control Engine.
     * Understands natural spoken Korean questions ("지금 화면에 뭐 있어?", "이거 읽어줘"),
     * deictic/ordinal/spatial references ("첫 번째 거 눌러줘", "두 번째 거", "오른쪽 위에", "더 내려봐", "뒤로 가"),
     * natural text input, and multi-step app control while replying in natural human Korean.
     */
    fun inferLocalOnDeviceAction(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage> = emptyList()
    ): ModelTurnDecision {
        val g = goal.trim()
        val gLower = g.lowercase()

        val friendlyAppNames = mapOf(
            "com.kakao.talk" to "카카오톡",
            "com.google.android.youtube" to "유튜브",
            "com.android.settings" to "설정",
            "com.android.chrome" to "크롬 브라우저",
            "com.nhn.android.search" to "네이버",
            "viva.republica.toss" to "토스",
            "com.coupang.mobile" to "쿠팡",
            "com.sampleapp" to "배달의민족",
            "com.instagram.android" to "인스타그램",
            "com.sec.android.gallery3d" to "갤러리",
            "com.sec.android.app.camera" to "카메라",
            "com.sec.android.app.clockpackage" to "시계",
            "com.hanson.mobileuse" to "Hanson 보드"
        )
        val currentAppName = friendlyAppNames[snapshot.activePackage]
            ?: snapshot.activePackage.substringAfterLast('.').ifBlank { "현재 화면" }

        val visibleLabels = snapshot.indexedElements
            .mapNotNull { el ->
                el.text.trim().ifBlank { el.contentDesc.trim() }.takeIf { it.length >= 2 }
            }
            .distinct()

        // 1. Conversational Screen Inquiry ("지금 화면에 뭐 있어?", "뭐 보여?", "화면 읽어줘", "무슨 앱이야?")
        if (
            gLower.contains("화면") && (gLower.contains("뭐") || gLower.contains("읽어") || gLower.contains("보여") || gLower.contains("설명") || gLower.contains("알려")) ||
            gLower.contains("뭐가 보") || gLower.contains("무슨 앱") || gLower == "지금 뭐해" || gLower == "뭐하고 있어"
        ) {
            val previewItems = visibleLabels.take(5)
            val reply = if (previewItems.isNotEmpty()) {
                "지금 $currentAppName 화면을 보고 있어요. 화면에는 ${previewItems.joinToString(", ") { "'$it'" }} 같은 항목들이 보이네요. 어떤 걸 눌러볼까요?"
            } else {
                "지금 $currentAppName 화면이에요. 실행하거나 누르고 싶은 항목을 편하게 말씀해 주세요."
            }
            return ModelTurnDecision(
                observation = "$currentAppName 화면 요소 ${snapshot.indexedElements.size}개 요약",
                subGoals = listOf(SubGoalItem("현재 화면 브리핑", SubGoalStatus.COMPLETED)),
                toolName = "complete_task",
                toolArgs = JSONObject().put("summary", reply),
                spokenReply = reply
            )
        }

        // 2. Conversational Greeting / Chitchat ("안녕", "반가워", "고마워", "수고했어", "잘했어", "들려?")
        if (
            gLower in listOf("안녕", "안녕하세요", "반가워", "야", "헤이", "한슨", "들려", "내 말 들려", "잘 들려") ||
            gLower.startsWith("고마워") || gLower.startsWith("감사") || gLower.startsWith("잘했어") || gLower.startsWith("수고")
        ) {
            val reply = if (gLower.startsWith("고마") || gLower.startsWith("감사") || gLower.startsWith("잘했") || gLower.startsWith("수고")) {
                "별말씀을요! 지금 $currentAppName 화면인데 이어서 도와드릴 작업이 있을까요?"
            } else {
                "네, 잘 들려요! 지금 $currentAppName 화면을 함께 보고 있습니다. 앱을 열거나 화면을 조작할 일이 있으면 편하게 말씀해 주세요."
            }
            return ModelTurnDecision(
                observation = "사용자와 실시간 대화 응답",
                subGoals = listOf(SubGoalItem("음성 대화 소통", SubGoalStatus.COMPLETED)),
                toolName = "complete_task",
                toolArgs = JSONObject().put("summary", reply),
                spokenReply = reply
            )
        }

        // 3. Direct Navigation / Hardware Keys ("뒤로", "이전 화면", "홈으로", "나가", "알림창 열어")
        if (iteration == 1) {
            if (gLower.contains("뒤로") || gLower.contains("이전 화면") || gLower.contains("이전으로") || gLower == "취소해" || gLower == "나가") {
                val spoken = "네, 이전 화면으로 돌아갈게요."
                return ModelTurnDecision(
                    observation = "뒤로가기 실행",
                    subGoals = listOf(SubGoalItem("이전 화면 이동", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "BACK"),
                    spokenReply = spoken
                )
            }
            if (gLower.contains("홈 화면") || gLower.contains("홈으로") || gLower == "홈") {
                val spoken = "네, 홈 화면으로 이동할게요."
                return ModelTurnDecision(
                    observation = "홈 화면 이동",
                    subGoals = listOf(SubGoalItem("홈 화면 이동", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "HOME"),
                    spokenReply = spoken
                )
            }
            if (gLower.contains("최근 앱") || gLower.contains("멀티태스킹")) {
                val spoken = "최근 사용한 앱 목록을 열게요."
                return ModelTurnDecision(
                    observation = "최근 앱 목록 열기",
                    subGoals = listOf(SubGoalItem("최근 앱 열기", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "RECENTS"),
                    spokenReply = spoken
                )
            }
        }

        // 4. Conversational Ordinal / Spatial Element Selection ("첫 번째 거 눌러", "두 번째 거", "세 번째", "맨 위에 거", "맨 아래 거", "오른쪽 위")
        if (iteration == 1 && !gLower.contains("열어서") && !gLower.contains("검색")) {
            val clickableTargets = snapshot.indexedElements.filter {
                (it.clickable || it.text.isNotBlank() || it.contentDesc.isNotBlank()) &&
                    it.centerY in 90..(snapshot.screenHeight - 120)
            }.sortedWith(compareBy<com.hanson.mobileuse.accessibility.IndexedUiElement> { it.centerY / 90 }.thenBy { it.centerX })

            val ordinalTarget = when {
                gLower.contains("첫 번째") || gLower.contains("첫번째") || gLower.contains("1번") || gLower.contains("맨 위") || gLower.contains("제일 위") ->
                    clickableTargets.getOrNull(0)
                gLower.contains("두 번째") || gLower.contains("두번째") || gLower.contains("2번") ->
                    clickableTargets.getOrNull(1)
                gLower.contains("세 번째") || gLower.contains("세번째") || gLower.contains("3번") ->
                    clickableTargets.getOrNull(2)
                gLower.contains("네 번째") || gLower.contains("네번째") || gLower.contains("4번") ->
                    clickableTargets.getOrNull(3)
                gLower.contains("마지막") || gLower.contains("맨 아래") || gLower.contains("제일 아래") || gLower.contains("맨 밑") ->
                    clickableTargets.lastOrNull()
                gLower.contains("오른쪽 위") || gLower.contains("우측 상단") ->
                    snapshot.indexedElements.filter { it.clickable && it.centerY < 320 }.maxByOrNull { it.centerX }
                gLower.contains("왼쪽 위") || gLower.contains("좌측 상단") ->
                    snapshot.indexedElements.filter { it.clickable && it.centerY < 320 }.minByOrNull { it.centerX }
                else -> null
            }

            if (ordinalTarget != null) {
                val label = ordinalTarget.text.ifBlank { ordinalTarget.contentDesc.ifBlank { "선택하신 위치의 항목" } }.take(24)
                val spoken = "네, '$label' 항목을 바로 누를게요."
                return ModelTurnDecision(
                    observation = "'$label' 요소 탭 실행",
                    subGoals = listOf(SubGoalItem("'$label' 선택", SubGoalStatus.IN_PROGRESS)),
                    toolName = "click",
                    toolArgs = JSONObject().apply {
                        put("element_index", ordinalTarget.index)
                        put("target_description", label)
                    },
                    spokenReply = spoken
                )
            }
        }

        // 5. Conversational Scroll / Swipe ("좀 더 내려봐", "아래로 스크롤", "위로 올려봐", "옆으로 넘겨")
        if (
            iteration == 1 &&
            (gLower.contains("스크롤") || gLower.contains("스와이프") || gLower.contains("내려") || gLower.contains("올려") || gLower.contains("넘겨")) &&
            !gLower.contains("볼륨") && !gLower.contains("소리") && !gLower.contains("밝기")
        ) {
            val dir = when {
                gLower.contains("위로 올") || gLower.contains("맨 위로") || gLower.contains("다시 위로") -> "down"
                gLower.contains("오른쪽") || gLower.contains("다음 페이지") || gLower.contains("옆으로") -> "left"
                gLower.contains("왼쪽") || gLower.contains("이전 페이지") -> "right"
                else -> "up"
            }
            val spoken = when (dir) {
                "down" -> "화면을 위로 다시 올려볼게요."
                "left" -> "옆 화면으로 넘겨볼게요."
                "right" -> "이전 페이지 쪽으로 넘길게요."
                else -> "아래 내용을 볼 수 있게 화면을 내려볼게요."
            }
            return ModelTurnDecision(
                observation = "화면 스와이프 이동 ($dir)",
                subGoals = listOf(SubGoalItem("화면 스크롤 이동", SubGoalStatus.IN_PROGRESS)),
                toolName = "swipe",
                toolArgs = JSONObject().put("direction", dir).put("duration_ms", 360),
                spokenReply = spoken
            )
        }

        // 6. Multi-Step App + Search/Type + Click Trajectory
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
            "배민" to "com.sampleapp",
            "쿠팡" to "com.coupang.mobile",
            "토스" to "viva.republica.toss",
            "인스타그램" to "com.instagram.android",
            "인스타" to "com.instagram.android"
        )
        val matchedAppEntry = appMap.entries.firstOrNull { gLower.contains(it.key) }
        val targetAppName = matchedAppEntry?.key
        val targetPkg = matchedAppEntry?.value

        val directScreenElementMatch = snapshot.indexedElements.firstOrNull { el ->
            (el.text.length >= 2 && g.contains(el.text, ignoreCase = true) && (gLower.contains("눌러") || gLower.contains("선택") || gLower.contains("열어") || gLower.contains("클릭"))) ||
                (el.contentDesc.length >= 2 && g.contains(el.contentDesc, ignoreCase = true) && (gLower.contains("눌러") || gLower.contains("선택") || gLower.contains("열어") || gLower.contains("클릭")))
        }

        val extractedQuery: String? = if (directScreenElementMatch != null && !gLower.contains("검색") && !gLower.contains("틀어") && !gLower.contains("입력")) {
            null
        } else {
            val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(g)?.let { m ->
                m.groupValues[1].ifBlank { m.groupValues[2] }
            }
            if (!quoted.isNullOrBlank()) {
                quoted
            } else {
                val pattern = Regex("([가-힣a-zA-Z0-9_\\s]{1,22}?)\\s*(?:검색하고|검색해서|검색해|검색|입력하고|입력해|쳐줘|적어줘|찾고|찾아서|찾아줘|틀어줘|틀어|재생해줘|재생)")
                pattern.find(g)?.groupValues?.getOrNull(1)
                    ?.replace("열어서", "")
                    ?.replace("열고", "")
                    ?.replace("에서", "")
                    ?.replace("검색창에", "")
                    ?.replace("검색창", "")
                    ?.replace("입력창에", "")
                    ?.replace("카카오톡", "")
                    ?.replace("카톡", "")
                    ?.replace("유튜브", "")
                    ?.replace("설정", "")
                    ?.replace("토스", "")
                    ?.replace("네이버", "")
                    ?.replace("쿠팡", "")
                    ?.replace("배민", "")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            }
        }

        val wantsDragAndDrop = gLower.contains("드래그") || gLower.contains("슬라이더") || gLower.contains("옮겨")
        val wantsPostResultClick = (extractedQuery != null) && (
            gLower.contains("클릭") || gLower.contains("틀어") || gLower.contains("재생") ||
                gLower.contains("열어") || gLower.contains("선택") || gLower.contains("눌러") || gLower.contains("들어가")
            )

        val isMultiStepGoal = !gLower.contains("답장") && !gLower.contains("알림") && (
            (extractedQuery != null) || wantsDragAndDrop ||
                (targetAppName != null && (gLower.contains("그리고") || gLower.contains("해서") || gLower.contains("열어서") || gLower.contains("틀어") || gLower.contains("찾아")))
            )

        if (isMultiStepGoal && iteration <= 7) {
            val hasLaunchedApp = historySteps.any { it.toolName == "manage_app" }
            val hasTypedQuery = historySteps.any { it.toolName == "input_text" }
            val hasClickedSearch = historySteps.any { it.toolName == "click" }
            val hasDragged = historySteps.any { it.toolName == "drag_and_drop" || it.toolName == "haptic_trajectory" }
            val clicksAfterTyping = historySteps.dropWhile { it.toolName != "input_text" }.count { it.toolName == "click" }

            val ledger = mutableListOf<SubGoalItem>()
            if (targetAppName != null) {
                val done = (snapshot.activePackage == targetPkg) || hasLaunchedApp
                ledger.add(SubGoalItem("$targetAppName 열기", if (done) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
            }
            if (extractedQuery != null) {
                ledger.add(SubGoalItem("'$extractedQuery' 입력 및 검색", if (hasTypedQuery) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
            }
            if (wantsPostResultClick) {
                ledger.add(SubGoalItem("결과 항목 선택", if (clicksAfterTyping >= 1) SubGoalStatus.COMPLETED else SubGoalStatus.PENDING))
            }

            if (targetAppName != null && snapshot.activePackage != targetPkg && !hasLaunchedApp) {
                val spoken = if (extractedQuery != null) {
                    "네, 먼저 ${targetAppName}을 열고 '$extractedQuery'를 찾아볼게요."
                } else {
                    "네, ${targetAppName}을 바로 열게요."
                }
                return ModelTurnDecision(
                    observation = "$targetAppName 실행 중",
                    subGoals = ledger,
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", targetAppName),
                    spokenReply = spoken
                )
            }

            if (extractedQuery != null && !hasTypedQuery) {
                val editableEl = snapshot.indexedElements.firstOrNull { it.editable }
                if (editableEl != null) {
                    val spoken = "검색창에 '$extractedQuery'라고 입력하고 있어요."
                    return ModelTurnDecision(
                        observation = "'$extractedQuery' 텍스트 입력 및 검색",
                        subGoals = ledger,
                        toolName = "input_text",
                        toolArgs = JSONObject().apply {
                            put("element_index", editableEl.index)
                            put("text", extractedQuery)
                            put("clear_existing", false)
                            put("submit_enter", true)
                        },
                        spokenReply = spoken
                    )
                }

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
                        val spoken = "검색창을 열어서 '$extractedQuery'를 입력할게요."
                        return ModelTurnDecision(
                            observation = "상단 검색 버튼 탭",
                            subGoals = ledger,
                            toolName = "click",
                            toolArgs = JSONObject().apply {
                                put("element_index", searchBtn.index)
                                put("target_description", "검색 버튼 열기")
                            },
                            spokenReply = spoken
                        )
                    }
                }

                val spoken = "'$extractedQuery'를 바로 입력할게요."
                return ModelTurnDecision(
                    observation = "'$extractedQuery' 입력",
                    subGoals = ledger,
                    toolName = "input_text",
                    toolArgs = JSONObject().apply {
                        put("text", extractedQuery)
                        put("submit_enter", true)
                    },
                    spokenReply = spoken
                )
            }

            if (wantsDragAndDrop && !hasDragged) {
                val sliderEl = snapshot.indexedElements.firstOrNull { el ->
                    el.shortClass.contains("SeekBar", ignoreCase = true) ||
                        el.shortClass.contains("Slider", ignoreCase = true) ||
                        el.text.contains("밝기") || el.contentDesc.contains("밝기")
                }
                val startNormX = if (sliderEl != null) ((sliderEl.left + 40) * 1000 / snapshot.screenWidth.coerceAtLeast(1)) else 260
                val endNormX = if (sliderEl != null) ((sliderEl.right - 40) * 1000 / snapshot.screenWidth.coerceAtLeast(1)) else 780
                val normY = if (sliderEl != null) sliderEl.normY else 500
                val spoken = "슬라이더를 부드럽게 드래그해서 조절할게요."
                return ModelTurnDecision(
                    observation = "슬라이더 드래그 앤 드롭 실행",
                    subGoals = ledger,
                    toolName = "drag_and_drop",
                    toolArgs = JSONObject().apply {
                        put("start_norm_x", startNormX)
                        put("start_norm_y", normY)
                        put("end_norm_x", endNormX)
                        put("end_norm_y", normY)
                        put("hold_ms", 480)
                        put("duration_ms", 680)
                        put("description", "슬라이더 드래그 조절")
                    },
                    spokenReply = spoken
                )
            }

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
                    val label = candidateResult.text.ifBlank { candidateResult.contentDesc }.take(24)
                    val spoken = "검색 결과에서 '$label' 항목을 선택할게요."
                    return ModelTurnDecision(
                        observation = "검색 결과 '$label' 탭",
                        subGoals = ledger,
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", candidateResult.index)
                            put("target_description", label)
                        },
                        spokenReply = spoken
                    )
                }
            }

            if (historySteps.isNotEmpty()) {
                val doneMsg = when {
                    wantsPostResultClick && extractedQuery != null ->
                        "'$extractedQuery' 검색 결과를 열었어요. 다른 항목을 누르거나 더 내려볼까요?"
                    extractedQuery != null ->
                        "'$extractedQuery' 검색을 완료했어요. 화면에서 어떤 항목을 열어볼까요?"
                    else ->
                        "요청하신 작업을 완료했어요. 이어서 무엇을 할까요?"
                }
                return ModelTurnDecision(
                    observation = "요청 작업 완료",
                    subGoals = ledger.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", doneMsg),
                    spokenReply = doneMsg
                )
            }
        }

        // Single-step completion with natural conversational response
        if (iteration >= 2 && historySteps.isNotEmpty()) {
            val last = historySteps.last()
            val cleanLine = last.resultSummary.lines().firstOrNull()
                ?.replace(Regex("\\[[^\\]]*\\]"), "")
                ?.trim()
                .orEmpty()
            val naturalDone = when (last.toolName) {
                "manage_app" -> "$currentAppName 화면을 열었어요. 여기서 무엇을 도와드릴까요?"
                "click" -> "선택하신 항목을 눌렀어요. 지금 화면에서 이어서 할 작업을 말씀해 주세요."
                "swipe" -> "화면을 이동했어요. 찾으시는 항목이 보이나요?"
                "press_key" -> "화면을 전환했어요. 다음엔 어떻게 할까요?"
                else -> cleanLine.ifBlank { "요청하신 작업을 마쳤어요. 더 필요한 게 있으신가요?" }
            }
            return ModelTurnDecision(
                observation = "작업 완료 및 대화 대기",
                subGoals = listOf(SubGoalItem("작업 완료", SubGoalStatus.COMPLETED)),
                toolName = "complete_task",
                toolArgs = JSONObject().put("summary", naturalDone),
                spokenReply = naturalDone
            )
        }

        // 7. Notifications & Quick Reply
        if (gLower.contains("답장") || (gLower.contains("알림") && gLower.contains("보내"))) {
            val replyMsg = g.substringAfter("\"", "").substringBefore("\"", "")
                .ifBlank { g.substringAfter("'", "").substringBefore("'", "") }
            if (replyMsg.isBlank()) {
                val q = "어떤 내용으로 답장을 보낼까요? 보낼 말씀을 편하게 이야기해 주세요."
                return ModelTurnDecision(
                    observation = "답장 메시지 내용 확인 요청",
                    subGoals = listOf(SubGoalItem("답장 내용 확인", SubGoalStatus.IN_PROGRESS)),
                    toolName = "ask_user",
                    toolArgs = JSONObject().put("question", q),
                    spokenReply = q
                )
            }
            val spoken = "네, 화면을 전환하지 않고 바로 '$replyMsg'라고 답장을 보낼게요."
            return ModelTurnDecision(
                observation = "알림 즉시 답장 전송",
                subGoals = listOf(SubGoalItem("알림 답장 전송", SubGoalStatus.IN_PROGRESS)),
                toolName = "reply_notification",
                toolArgs = JSONObject().apply {
                    put("target", "")
                    put("message", replyMsg)
                },
                spokenReply = spoken
            )
        }

        if (gLower.contains("알림") || (gLower.contains("카톡") && (gLower.contains("왔어") || gLower.contains("읽어") || gLower.contains("요약") || gLower.contains("확인")))) {
            val spoken = "최근 도착한 알림과 메시지를 바로 확인해 볼게요."
            return ModelTurnDecision(
                observation = "수신 알림 확인",
                subGoals = listOf(SubGoalItem("최근 알림 조회", SubGoalStatus.IN_PROGRESS)),
                toolName = "list_notifications",
                toolArgs = JSONObject().put("only_replyable", false).put("limit", 10),
                spokenReply = spoken
            )
        }

        // 8. Brightness / Volume / DND
        if (gLower.contains("밝기")) {
            val num = Regex("\\d+").find(g)?.value?.toIntOrNull()
                ?: if (gLower.contains("어둡") || gLower.contains("낮춰") || gLower.contains("줄여")) 80 else 200
            val spoken = "화면 밝기를 ${num}으로 조절할게요."
            return ModelTurnDecision(
                observation = "화면 밝기 $num 설정",
                subGoals = listOf(SubGoalItem("화면 밝기 조절", SubGoalStatus.IN_PROGRESS)),
                toolName = "control_device_setting",
                toolArgs = JSONObject().put("setting", "brightness").put("value", num.toString()),
                spokenReply = spoken
            )
        }

        if (gLower.contains("볼륨") || gLower.contains("소리")) {
            val num = Regex("\\d+").find(g)?.value?.toIntOrNull()
                ?: if (gLower.contains("줄여") || gLower.contains("낮춰") || gLower.contains("작게")) 25 else 65
            val spoken = "미디어 소리를 ${num}%로 맞출게요."
            return ModelTurnDecision(
                observation = "볼륨 ${num}% 조절",
                subGoals = listOf(SubGoalItem("볼륨 조절", SubGoalStatus.IN_PROGRESS)),
                toolName = "control_device_setting",
                toolArgs = JSONObject().put("setting", "volume_media").put("value", num.toString()),
                spokenReply = spoken
            )
        }

        // 9. Match any visible UI element on the current screen by text/description
        val matchedElement = snapshot.indexedElements.firstOrNull { el ->
            (el.text.length >= 2 && g.contains(el.text, ignoreCase = true)) ||
                (el.contentDesc.length >= 2 && g.contains(el.contentDesc, ignoreCase = true))
        }
        if (matchedElement != null) {
            val label = matchedElement.text.ifBlank { matchedElement.contentDesc }.take(24)
            val spoken = "네, 화면에 보이는 '$label' 항목을 누를게요."
            return ModelTurnDecision(
                observation = "'$label' 요소 탭",
                subGoals = listOf(SubGoalItem("'$label' 탭 실행", SubGoalStatus.IN_PROGRESS)),
                toolName = "click",
                toolArgs = JSONObject().apply {
                    put("element_index", matchedElement.index)
                    put("target_description", label)
                },
                spokenReply = spoken
            )
        }

        // 10. Direct App Launch
        if (targetAppName != null || gLower.contains("열어") || gLower.contains("실행")) {
            val targetApp = targetAppName ?: g.replace("열어줘", "").replace("열어", "").replace("실행해줘", "").replace("실행", "").replace("앱", "").trim()
            if (targetApp.isNotBlank()) {
                val spoken = "네, ${targetApp}을 바로 열어드릴게요."
                return ModelTurnDecision(
                    observation = "$targetApp 실행",
                    subGoals = listOf(SubGoalItem("$targetApp 실행", SubGoalStatus.IN_PROGRESS)),
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", targetApp),
                    spokenReply = spoken
                )
            }
        }

        // 11. Conversational Fallback: talk naturally about the current screen and ask how to help!
        val topVisible = visibleLabels.take(4)
        val conversationalQuestion = if (topVisible.isNotEmpty()) {
            "지금 $currentAppName 화면에서 ${topVisible.joinToString(", ") { "'$it'" }} 항목들이 보이고 있어요. 이 중에서 어떤 걸 누를지, 아니면 다른 앱을 열지 편하게 말씀해 주세요."
        } else {
            "말씀하신 내용을 조금만 더 구체적으로 알려주시겠어요? 예를 들어 '유튜브에서 로파이 틀어줘'나 '첫 번째 거 눌러줘'처럼 말씀하시면 바로 조작할게요."
        }
        return ModelTurnDecision(
            observation = "사용자에게 다음 행동 확인",
            subGoals = listOf(SubGoalItem("대화형 의도 확인", SubGoalStatus.COMPLETED)),
            toolName = "ask_user",
            toolArgs = JSONObject().put("question", conversationalQuestion),
            spokenReply = conversationalQuestion
        )
    }
}
