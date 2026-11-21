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
        const val AUTO_MULTI_MODEL = "auto-multi-model"

        @Volatile
        var lastCloudApiErrorReason: String? = null
            internal set

        data class ApiConnectionCheckResult(
            val ok: Boolean,
            val provider: String,
            val statusCode: Int,
            val messageKo: String,
            val latencyMs: Long
        )

        /**
         * Parses a single or multi-provider API key string (comma/semicolon/newline separated).
         * Supports simultaneous Google AI Studio (`AIza...`), Anthropic (`sk-ant-...`),
         * OpenRouter (`sk-or-...`), and OpenAI (`sk-...`) keys for role-specialized routing.
         */
        data class MultiProviderApiKeySet(
            val geminiKey: String? = null,
            val anthropicKey: String? = null,
            val openRouterKey: String? = null,
            val openAiKey: String? = null
        ) {
            val hasAnyCloudKey: Boolean
                get() = !geminiKey.isNullOrBlank() ||
                    !anthropicKey.isNullOrBlank() ||
                    !openRouterKey.isNullOrBlank() ||
                    !openAiKey.isNullOrBlank()

            companion object {
                private val deadKeysCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
                private const val DEAD_KEY_COOLDOWN_MS = 10 * 60 * 1000L

                fun isKeyTemporarilyDead(key: String): Boolean {
                    val ts = deadKeysCache[key] ?: return false
                    return (System.currentTimeMillis() - ts) < DEAD_KEY_COOLDOWN_MS
                }

                fun markKeyDead(key: String) {
                    if (key.isNotBlank()) {
                        deadKeysCache[key] = System.currentTimeMillis()
                    }
                }

                fun clearDeadKeys() {
                    deadKeysCache.clear()
                }

                fun parse(rawKeyString: String): MultiProviderApiKeySet {
                    val cleanedRaw = rawKeyString.replace("\uFEFF", "")
                    val tokens = cleanedRaw.split(',', ';', '\n', ' ')
                        .map { rawTok ->
                            val t = rawTok.trim().trim('"', '\'')
                            if (t.contains("=")) t.substringAfter("=").trim().trim('"', '\'') else t
                        }
                        .filter { it.isNotEmpty() }
                    var gemini: String? = null
                    var anthropic: String? = null
                    var openRouter: String? = null
                    var openAi: String? = null
                    for (token in tokens) {
                        when {
                            token.startsWith("sk-ant-", ignoreCase = true) -> anthropic = token
                            token.startsWith("sk-or-", ignoreCase = true) -> openRouter = token
                            token.startsWith("AIza") -> gemini = token
                            token.startsWith("sk-") -> openAi = token
                            gemini == null -> gemini = token
                        }
                    }
                    return MultiProviderApiKeySet(
                        geminiKey = gemini,
                        anthropicKey = anthropic,
                        openRouterKey = openRouter,
                        openAiKey = openAi
                    )
                }
            }
        }

        /**
         * Universal Multi-Provider VLM Model Catalog (Gemini, Claude, OpenAI, OpenRouter, Custom BaseURL & CLI Bridge).
         */
        val AISTUDIO_OFFICIAL_MODELS: List<Pair<String, String>> = listOf(
            // Per-Turn Specialist Multi-Model Orchestrator
            "auto-multi-model" to "[멀티모델 자동 라우팅] 턴·복잡도별 최적 VLM(Gemini / Claude / GPT / OpenRouter) 동적 선택",
            // Production Gemini Multimodal VLMs
            "gemini-2.5-flash" to "[Gemini 2.5 Flash · 권장] 빠르고 정확한 범용 모바일 시각 그라운딩 & 도구 호출 VLM",
            "gemini-2.5-pro" to "[Gemini 2.5 Pro · 고추론] 복잡한 다단계 앱 제어 및 화면 추론 플래그십",
            "gemini-2.5-flash-lite" to "[Gemini 2.5 Flash Lite] 초저지연·초저비용 고속 모바일 제어 모델",
            // Next-Gen & Preview Aliases (auto-cascades to live endpoints)
            "gemini-3.8-flash" to "[Gemini 3.8 · Preview] 자율 에이전트 · 장기 컨텍스트 멀티모달",
            "gemini-4-argon" to "[Gemini 4 · Preview] 고차원 계획 및 멀티모달 추론",
            "gemini-3.6-flash" to "[Gemini 3.6 · Preview] 범용 멀티모달 에이전트 Flash",
            "gemini-3.5-flash-lite" to "[Gemini 3.5 · Preview] 초저지연 서브에이전트 & 고속 실행",
            "gemini-3.1-flash-lite" to "[Gemini 3.1 · Preview] 경량 멀티모달 VLM",
            "gemini-3.1-pro-preview" to "[Gemini 3.1 Pro · Preview] 고난도 멀티모달 추론 프리뷰",
            "gemini-3-flash-preview" to "[Gemini 3 Flash · Preview] 고속 멀티모달 프리뷰",
            "gemini-robotics-er-2-preview" to "[Embodied Reasoning · Preview] 공간 좌표(Spatial) · 멀티스텝 제어 VLM",
            "antigravity-preview-09-2026" to "[Managed Agent] 격리 샌드박스 코드 실행·웹 브라우징 에이전트",
            // Multi-Provider Specialists (Anthropic Claude / OpenAI / OpenRouter / Custom Endpoint)
            "claude-sonnet-4-6" to "[Anthropic · Sonnet] 정밀 UI 그라운딩 및 멀티모달 오퍼레이터 (sk-ant-... / sk-or-...)",
            "claude-opus-4-6" to "[Anthropic · Opus] 고난도 다단계 계획 및 복합 문제 해결 (sk-ant-... / sk-or-...)",
            "gpt-4.1" to "[OpenAI / Custom URL] 범용 멀티모달 UI 및 도구 호출 에이전트 (sk-... / OpenRouter / Ollama)",
            "gpt-4.1-mini" to "[OpenAI / Custom URL] 초고속 경량 비전·도구 호출 모델 (sk-... / OpenRouter)",
            // Live Audio / Speech / Media Specialists
            "gemini-3.8-live" to "[Live Audio] 초저지연 실시간 양방향 음성·화면 에이전트",
            "gemini-3.8-live-extended-thinking" to "[Live Audio · 고추론] 실시간 음성 대화 + 심층 추론",
            "gemini-3.8-flash-tts" to "[Audio TTS] 자연스러운 음성 합성",
            "gemini-3.8-flash-lite-tts" to "[Audio TTS · Lite] 초고속 실시간 음성 합성",
            "gemini-3.5-transcribe" to "[Speech-to-Text] 다국어 음성 인식 STT",
            "gemini-3.5-transcribe-live" to "[Speech-to-Text · Live] 실시간 스트리밍 STT",
            "gemini-nano-banana-2.1" to "[Image] 대화형 이미지 생성 및 편집",
            "gemini-omni-1.1-flash" to "[Video] 비디오 생성 및 편집",
            // Zero-API-Cost CLI Subscription Bridge & On-Device Local NPU
            "cli-subscription-bridge" to "[로컬 CLI 구독 브리지 · API 비용 $0] 노트북 Claude Code / Codex / Gemini CLI 연동",
            "gemma-4-26b-a4b-it" to "[온디바이스 로컬] 온디바이스 / 로컬 서버(Ollama · vLLM) 오프라인 추론"
        )

        fun normalize2026ModelName(rawModel: String): String {
            val trimmed = rawModel.trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL

            return when {
                trimmed.equals(AUTO_MULTI_MODEL, ignoreCase = true) ||
                    trimmed.endsWith("-multi-model-auto", ignoreCase = true) -> AUTO_MULTI_MODEL
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

        fun normalizeChatCompletionsEndpoint(rawBaseUrl: String): String {
            val trimmed = rawBaseUrl.trim().trimEnd('/')
            return when {
                trimmed.endsWith("/chat/completions", ignoreCase = true) -> trimmed
                trimmed.endsWith("/v1", ignoreCase = true) -> "$trimmed/chat/completions"
                else -> "$trimmed/v1/chat/completions"
            }
        }

        val SYSTEM_INSTRUCTION = """
You are Hanson, a precise, natural Korean mobile full-control AI agent ("browser-use for Android").
You observe both the live UI accessibility element list AND a Set-of-Marks (SoM) screenshot where interactive elements are highlighted with numbered `[index]` badges matching `Visible UI Elements`.

On EVERY turn, output:
1. `SPOKEN: <1 short, natural Korean sentence (under 35 chars) stating what you just did or found>`
2. `OBSERVATION: <concise Korean summary of current screen, popups/obstacles, and goal progress>`
3. `PLAN:` followed by markdown checklist items (`[x]`, `[/]`, `[ ]`).
4. Invoke ONE tool call to advance the user's goal.

Empirical Mobile Full-Control Rules:
- Set-of-Marks Visual Grounding: Match numbered `[index]` badges on the screenshot with `[index]` in `Visible UI Elements` and pass `element_index` to `click`, `long_press`, or `input_text` whenever possible. If a visual button/banner has no accessibility node, pass normalized coordinates `x` and `y` in `0..1000`.
- Clear Obstacles First: If an in-app promo popup, bottom sheet ad, permission dialog, or tooltip (`닫기`, `오늘 그만 보기`, `다음에`, `X`) covers the target UI, dismiss that obstacle FIRST before clicking behind it.
- Strict Postcondition Verification: NEVER call `complete_task` on Step 1 of an action request or right after launching an app/typing a query! Only call `complete_task(summary=...)` AFTER you observe on the current screen that the requested target state (e.g. search results loaded, message sent, setting toggled) is genuinely achieved.
- Ambiguity -> Ask Naturally: If user confirmation is needed (e.g. payment/checkout or multiple ambiguous recipients), call `ask_user(question=...)`.
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

        // --- Feature 15: Real Native AI Generation Pipeline ---

        fun buildMultimodalGenerationPayload(prompt: String, base64Image: String? = null): JSONObject {
            val parts = JSONArray()
            parts.put(JSONObject().put("text", prompt))
            if (!base64Image.isNullOrBlank()) {
                parts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            }
            return JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            }
        }

        fun deserializeCardResponse(apiJsonResponse: String): String {
            return try {
                val json = JSONObject(apiJsonResponse)
                val candidates = json.optJSONArray("candidates") ?: return ""
                if (candidates.length() == 0) return ""
                val firstCandidate = candidates.optJSONObject(0) ?: return ""
                val finishReason = firstCandidate.optString("finishReason", "")
                if (finishReason == "SAFETY") {
                    return resolveSafetyNotice(finishReason)
                }
                val content = firstCandidate.optJSONObject("content") ?: return ""
                val parts = content.optJSONArray("parts") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    sb.append(p.optString("text", ""))
                }
                sb.toString()
            } catch (_: Exception) {
                ""
            }
        }

        fun resolveSafetyNotice(finishReason: String): String {
            return if (finishReason == "SAFETY") {
                "안전한 콘텐츠 가이드라인에 맞춰 답변을 다시 작성하고 있습니다."
            } else {
                "답변 완료"
            }
        }

        fun computeExponentialBackoff(attempt: Int): Long {
            return (1000L * Math.pow(2.0, attempt.toDouble())).toLong()
        }

        fun aggregateStreamingChunks(chunks: List<String>): String {
            return chunks.joinToString("")
        }

        const val MEDIA_GEN_TIMEOUT_SECONDS = 30L

        // --- Feature 16: Zero-Friction Credential Resolution Pipeline ---

        fun resolveApiKey(
            sheetInput: String? = null,
            envVar: String? = null,
            fileContent: String? = null,
            buildConfigKey: String? = null
        ): String {
            val keyFromFile = if (!fileContent.isNullOrBlank()) {
                extractKeyFromFile(fileContent, "gemini_key")
                    ?: extractKeyFromFile(fileContent, "GEMINI_API_KEY")
                    ?: fileContent.trim()
            } else ""
            return when {
                !sheetInput.isNullOrBlank() -> sheetInput.trim()
                !envVar.isNullOrBlank() -> envVar.trim()
                keyFromFile.isNotBlank() -> keyFromFile.trim()
                !buildConfigKey.isNullOrBlank() -> buildConfigKey.trim()
                else -> ""
            }
        }

        fun extractKeyFromFile(content: String, keyName: String): String? {
            return content.lineSequence()
                .filter { it.startsWith("$keyName=") }
                .map { it.substringAfter("=").trim() }
                .firstOrNull()
        }

        fun maskKey(key: String): String {
            val trimmed = key.trim()
            return if (trimmed.length <= 8) "****" else "${trimmed.take(4)}...${trimmed.takeLast(4)}"
        }

        fun isValidGeminiKey(key: String): Boolean {
            val trimmed = key.trim()
            return trimmed.startsWith("AIzaSy") && trimmed.length >= 20
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Performs an immediate live connection health check against the configured VLM endpoint
     * (Custom OpenAI-Compatible Base URL, Laptop CLI Bridge, Anthropic, OpenRouter, OpenAI, or Gemini).
     */
    fun verifyConnectionHealth(
        apiKey: String,
        modelName: String = DEFAULT_MODEL,
        customBaseUrl: String = ""
    ): ApiConnectionCheckResult {
        val startMs = System.currentTimeMillis()
        MultiProviderApiKeySet.clearDeadKeys()
        val cleanCustomUrl = customBaseUrl.trim()

        if (modelName == "cli-subscription-bridge" || (apiKey.isBlank() && cleanCustomUrl.isBlank())) {
            return try {
                val req = Request.Builder().url("http://127.0.0.1:18889/health").get().build()
                httpClient.newCall(req).execute().use { resp ->
                    val dt = System.currentTimeMillis() - startMs
                    if (resp.isSuccessful) {
                        ApiConnectionCheckResult(true, "CLI-BRIDGE", resp.code, "노트북 CLI 브리지(:18889) 연결 정상 (${dt}ms)", dt)
                    } else {
                        ApiConnectionCheckResult(false, "CLI-BRIDGE", resp.code, "CLI 브리지 응답 오류 (HTTP ${resp.code})", dt)
                    }
                }
            } catch (_: Exception) {
                val dt = System.currentTimeMillis() - startMs
                ApiConnectionCheckResult(false, "NONE", 0, "API 키 미입력 및 노트북 CLI 브리지(:18889) 미실행", dt)
            }
        }

        if (cleanCustomUrl.isNotEmpty()) {
            val endpoint = normalizeChatCompletionsEndpoint(cleanCustomUrl)
            val payload = JSONObject().apply {
                put("model", modelName.ifBlank { "gpt-4o-mini" })
                put("max_tokens", 8)
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            }
            return try {
                val reqBuilder = Request.Builder()
                    .url(endpoint)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val dt = System.currentTimeMillis() - startMs
                    if (resp.isSuccessful) {
                        lastCloudApiErrorReason = null
                        ApiConnectionCheckResult(true, "CUSTOM-OPENAI", resp.code, "커스텀 엔드포인트 연결 정상 (${dt}ms)", dt)
                    } else {
                        ApiConnectionCheckResult(false, "CUSTOM-OPENAI", resp.code, formatHttpStatusKo("Custom API", resp.code), dt)
                    }
                }
            } catch (e: Exception) {
                val dt = System.currentTimeMillis() - startMs
                ApiConnectionCheckResult(false, "CUSTOM-OPENAI", 0, "커스텀 URL 연결 실패: ${e.message?.take(60)}", dt)
            }
        }

        val keySet = MultiProviderApiKeySet.parse(apiKey)
        return try {
            when {
                !keySet.anthropicKey.isNullOrBlank() -> {
                    val payload = JSONObject().apply {
                        put("model", if (modelName.startsWith("claude-")) modelName else "claude-sonnet-4-6")
                        put("max_tokens", 8)
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    }
                    val req = Request.Builder()
                        .url("https://api.anthropic.com/v1/messages")
                        .addHeader("x-api-key", keySet.anthropicKey)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val dt = System.currentTimeMillis() - startMs
                        if (resp.isSuccessful) {
                            lastCloudApiErrorReason = null
                            ApiConnectionCheckResult(true, "ANTHROPIC", resp.code, "Anthropic Claude API 연결 정상 (${dt}ms)", dt)
                        } else {
                            ApiConnectionCheckResult(false, "ANTHROPIC", resp.code, formatHttpStatusKo("Anthropic", resp.code), dt)
                        }
                    }
                }
                !keySet.openRouterKey.isNullOrBlank() || !keySet.openAiKey.isNullOrBlank() -> {
                    val isOr = !keySet.openRouterKey.isNullOrBlank()
                    val key = (if (isOr) keySet.openRouterKey else keySet.openAiKey).orEmpty()
                    val endpoint = if (isOr) "https://openrouter.ai/api/v1/chat/completions" else "https://api.openai.com/v1/chat/completions"
                    val testModel = when {
                        isOr && modelName.contains("/") -> modelName
                        isOr -> "google/gemini-2.5-flash"
                        modelName.startsWith("gpt-") -> modelName
                        else -> "gpt-4.1-mini"
                    }
                    val payload = JSONObject().apply {
                        put("model", testModel)
                        put("max_tokens", 8)
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    }
                    val req = Request.Builder()
                        .url(endpoint)
                        .addHeader("Authorization", "Bearer $key")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val dt = System.currentTimeMillis() - startMs
                        val label = if (isOr) "OpenRouter" else "OpenAI"
                        if (resp.isSuccessful) {
                            lastCloudApiErrorReason = null
                            ApiConnectionCheckResult(true, label.uppercase(), resp.code, "$label API 연결 정상 (${dt}ms)", dt)
                        } else {
                            ApiConnectionCheckResult(false, label.uppercase(), resp.code, formatHttpStatusKo(label, resp.code), dt)
                        }
                    }
                }
                !keySet.geminiKey.isNullOrBlank() -> {
                    val payload = buildMultimodalGenerationPayload("ping")
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("x-goog-api-key", keySet.geminiKey)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val dt = System.currentTimeMillis() - startMs
                        if (resp.isSuccessful) {
                            lastCloudApiErrorReason = null
                            ApiConnectionCheckResult(true, "GEMINI", resp.code, "Google Gemini API 연결 정상 (${dt}ms)", dt)
                        } else {
                            val msg = formatHttpStatusKo("Gemini", resp.code)
                            lastCloudApiErrorReason = msg
                            ApiConnectionCheckResult(false, "GEMINI", resp.code, msg, dt)
                        }
                    }
                }
                else -> ApiConnectionCheckResult(false, "NONE", 0, "유효한 API 키 형식이 아닙니다", 0L)
            }
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - startMs
            ApiConnectionCheckResult(false, "NETWORK", 0, "네트워크 오류: ${e.message?.take(60)}", dt)
        }
    }

    private fun formatHttpStatusKo(provider: String, code: Int): String = when (code) {
        401, 403 -> "HTTP $code ($provider API 키 인증/권한 실패)"
        402 -> "HTTP 402 ($provider 선불 크레딧 소진 — 결제/무료 키 확인 필요)"
        429 -> "HTTP 429 ($provider 요청 한도 초과)"
        404 -> "HTTP 404 ($provider 모델명을 찾을 수 없음)"
        else -> "HTTP $code ($provider 응답 오류)"
    }

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
        laptopBridgeUrl: String = "http://127.0.0.1:18889/decide",
        customBaseUrl: String = ""
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

        // Ultra-low-latency (<10ms) Conversational Screen Reader & Deterministic Navigation Fast-Path
        val fastDecision = tryInstantConversationalOrScreenFastPath(
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps
        )
        if (fastDecision != null) {
            return fastDecision
        }

        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() && customBaseUrl.isBlank()) {
            return inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        }

        val normalizedModel = normalize2026ModelName(modelName)
        val cascaded = decideNextActionWithCascade(
            apiKey = cleanKey,
            candidateModels = listOf(normalizedModel, "gemini-3.8-flash", "gemini-3.5-flash-lite").distinct(),
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan,
            chatHistory = chatHistory,
            customBaseUrl = customBaseUrl
        )
        return cascaded?.second ?: inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
    }

    /**
     * Executes a per-turn cloud model cascade across one or more specialist models and providers.
     */
    fun decideNextActionWithCascade(
        apiKey: String,
        candidateModels: List<String>,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage> = emptyList(),
        customBaseUrl: String = ""
    ): Pair<String, ModelTurnDecision>? {
        val cleanCustomUrl = customBaseUrl.trim()
        if (cleanCustomUrl.isNotEmpty()) {
            val customModel = candidateModels.firstOrNull { it != AUTO_MULTI_MODEL }.orEmpty().ifBlank { "gpt-4.1" }
            val customDecision = runCatching {
                callOpenAiCompatibleApi(
                    apiKey = apiKey.trim().ifBlank { "local" },
                    modelName = customModel,
                    isOpenRouter = cleanCustomUrl.contains("openrouter.ai", ignoreCase = true),
                    goal = goal,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory,
                    customBaseUrl = cleanCustomUrl
                )
            }.onFailure { err ->
                lastCloudApiErrorReason = err.message?.take(80)
            }.getOrNull()
            if (customDecision != null) {
                lastCloudApiErrorReason = null
                return "CUSTOM-VLM" to customDecision
            }
        }

        val keySet = MultiProviderApiKeySet.parse(apiKey)

        // When a real Cloud LLM key is configured, prioritize full-context VLM reasoning first
        // (except for 1-word hardware reflexes like "뒤로" / "홈"), and use fast-path if cloud fails.
        if (keySet.hasAnyCloudKey) {
            val expandedCandidates = buildProviderCandidateQueue(candidateModels, keySet)
            for ((providerTag, targetModel, providerKey) in expandedCandidates) {
                if (MultiProviderApiKeySet.isKeyTemporarilyDead(providerKey)) {
                    continue
                }
                val decision = runCatching {
                    when (providerTag) {
                        "ANTHROPIC" -> callAnthropicMessagesApi(
                            apiKey = providerKey,
                            modelName = targetModel,
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory
                        )
                        "OPENROUTER", "OPENAI" -> callOpenAiCompatibleApi(
                            apiKey = providerKey,
                            modelName = targetModel,
                            isOpenRouter = providerTag == "OPENROUTER",
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory
                        )
                        else -> callGeminiMultimodalApi(
                            apiKey = providerKey,
                            modelName = normalize2026ModelName(targetModel),
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory
                        )
                    }
                }.onFailure { err ->
                    val msg = err.message.orEmpty()
                    when {
                        msg.contains("HTTP 402") || msg.contains("RESOURCE_EXHAUSTED") -> {
                            lastCloudApiErrorReason = "HTTP 402 (AI Studio 크레딧 소진)"
                            MultiProviderApiKeySet.markKeyDead(providerKey)
                        }
                        msg.contains("HTTP 401") || msg.contains("HTTP 403") -> {
                            lastCloudApiErrorReason = "HTTP 401/403 (API 키 권한/인증 실패)"
                            MultiProviderApiKeySet.markKeyDead(providerKey)
                        }
                        msg.contains("HTTP 429") -> {
                            lastCloudApiErrorReason = "HTTP 429 (API 호출 한도 초과)"
                        }
                    }
                }.getOrNull()

                if (decision != null) {
                    lastCloudApiErrorReason = null
                    return targetModel.uppercase() to decision
                }
            }
        }

        // If Cloud API is unavailable (e.g. HTTP 402 depleted credits), check if Laptop CLI Bridge (:18889) is connected via USB adb reverse
        val bridgeFallback = tryCallLaptopCliBridge(
            bridgeUrl = "http://127.0.0.1:18889/decide",
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan,
            chatHistory = chatHistory
        )
        if (bridgeFallback != null) {
            return "LAPTOP-CLI-BRIDGE" to bridgeFallback
        }

        val fastDecision = tryInstantConversationalOrScreenFastPath(
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps
        )
        if (fastDecision != null) {
            return "FAST-PATH-10MS" to fastDecision
        }

        return null
    }

    private fun buildProviderCandidateQueue(
        requestedModels: List<String>,
        keySet: MultiProviderApiKeySet
    ): List<Triple<String, String, String>> {
        val queue = mutableListOf<Triple<String, String, String>>()
        for (raw in requestedModels) {
            val m = raw.trim()
            if (m.isEmpty()) continue
            when {
                m.startsWith("claude-", ignoreCase = true) -> {
                    if (!keySet.anthropicKey.isNullOrBlank()) {
                        queue.add(Triple("ANTHROPIC", m, keySet.anthropicKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        queue.add(Triple("OPENROUTER", "anthropic/$m", keySet.openRouterKey))
                    } else if (!keySet.geminiKey.isNullOrBlank()) {
                        val mapped = if (m.contains("opus")) "gemini-4-argon" else "gemini-3.8-flash"
                        queue.add(Triple("GEMINI", mapped, keySet.geminiKey))
                    }
                }
                m.startsWith("gpt-", ignoreCase = true) || m.startsWith("o3", ignoreCase = true) || m.startsWith("o4", ignoreCase = true) -> {
                    if (!keySet.openAiKey.isNullOrBlank()) {
                        queue.add(Triple("OPENAI", m, keySet.openAiKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        queue.add(Triple("OPENROUTER", "openai/$m", keySet.openRouterKey))
                    } else if (!keySet.geminiKey.isNullOrBlank()) {
                        queue.add(Triple("GEMINI", "gemini-3.8-flash", keySet.geminiKey))
                    }
                }
                else -> {
                    // Requested a Gemini specialist model
                    if (!keySet.geminiKey.isNullOrBlank()) {
                        queue.add(Triple("GEMINI", m, keySet.geminiKey))
                    } else if (!keySet.anthropicKey.isNullOrBlank()) {
                        val mappedClaude = when {
                            m.contains("argon") || m.contains("deep-research") -> "claude-opus-4-6"
                            m.contains("lite") -> "claude-haiku-4-5"
                            else -> "claude-sonnet-4-6"
                        }
                        queue.add(Triple("ANTHROPIC", mappedClaude, keySet.anthropicKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        val mappedOr = when {
                            m.contains("argon") -> "google/gemini-2.5-pro"
                            else -> "google/gemini-2.5-flash"
                        }
                        queue.add(Triple("OPENROUTER", mappedOr, keySet.openRouterKey))
                    } else if (!keySet.openAiKey.isNullOrBlank()) {
                        val mappedOai = if (m.contains("lite")) "gpt-4.1-mini" else "gpt-4.1"
                        queue.add(Triple("OPENAI", mappedOai, keySet.openAiKey))
                    }
                }
            }
        }
        return queue.distinctBy { "${it.first}:${it.second}" }
    }

    /**
     * Sub-10ms Conversational Screen Partner & Direct Action Fast-Path.
     * Eliminates 2~4s cloud round-trips for:
     * 1. Reading/summarizing live chat rooms or current screen contents in natural Korean
     * 2. Confirming arrival inside a target chat room and immediately briefing its conversation
     * 3. Navigating into a KakaoTalk room or clicking an already-visible UI element
     * 4. Instant hardware keys ("뒤로 가", "홈으로") and scroll/ordinal commands
     */
    internal fun tryInstantConversationalOrScreenFastPath(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>
    ): ModelTurnDecision? {
        val g = goal.trim()
        val gLower = g.lowercase()

        val isChatRoomEntryGoal = (gLower.contains("카톡방") || gLower.contains("대화방") || gLower.contains("채팅방") || gLower.contains("단톡방")) &&
            (gLower.contains("들어가") || gLower.contains("열어") || gLower.contains("눌러") || gLower.contains("보여") || gLower.contains("확인") || gLower.contains("알려") || gLower.contains("요약") || gLower.contains("읽어"))

        val isConversationalReadGoal =
            gLower.contains("무슨 얘기") || gLower.contains("무슨 말") || gLower.contains("무슨 대화") ||
                gLower.contains("대화 내용") || gLower.contains("내용 알려") || gLower.contains("내용 요약") ||
                gLower.contains("요약해") || gLower.contains("읽어줘") || gLower.contains("읽어봐") ||
                gLower.contains("누가 뭐") || gLower.contains("뭐라고 했") || gLower.contains("뭐라고 해") ||
                (gLower.contains("화면") && (gLower.contains("뭐") || gLower.contains("읽어") || gLower.contains("설명") || gLower.contains("알려")))

        val insideChatRoom = isInsideKakaoChatRoom(snapshot)

        // 1. If user asks to read/summarize current screen OR we just entered the target chat room -> natural conversational briefing!
        if (isConversationalReadGoal && (!isChatRoomEntryGoal || insideChatRoom)) {
            val summary = buildNaturalChatOrScreenSummary(snapshot, g)
            return ModelTurnDecision(
                observation = "현재 화면 및 대화 맥락 즉시 브리핑",
                subGoals = listOf(SubGoalItem("화면/대화 내용 요약 브리핑", SubGoalStatus.COMPLETED)),
                toolName = "complete_task",
                toolArgs = JSONObject().put("summary", summary),
                spokenReply = summary
            )
        }

        // 2. Chat room navigation fast-path ("팀 회의실 카톡방 들어가 봐")
        if (isChatRoomEntryGoal && iteration <= 6) {
            val roomQuery = extractCleanRoomQuery(g)

            // 2-A. Check if we are ALREADY inside the requested chat room!
            if (insideChatRoom) {
                val topHeader = snapshot.indexedElements
                    .filter { it.centerY in 60..320 && it.text.isNotBlank() && it.text !in setOf("뒤로가기", "검색", "메뉴") }
                    .map { it.text.trim() }
                    .firstOrNull()
                    .orEmpty()
                val matchesTargetRoom = roomQuery.isBlank() ||
                    AgentToolExecutionHarness.computeTokenSimilarity(topHeader, roomQuery) >= 0.50 ||
                    historySteps.any { it.toolName == "click" && AgentToolExecutionHarness.computeTokenSimilarity(it.resultSummary, roomQuery) >= 0.40 }
                if (matchesTargetRoom) {
                    val summary = buildNaturalChatOrScreenSummary(snapshot, g)
                    return ModelTurnDecision(
                        observation = "'${topHeader.ifBlank { "대화방" }}' 진입 완료 및 대화 브리핑",
                        subGoals = listOf(
                            SubGoalItem("대화방 진입", SubGoalStatus.COMPLETED),
                            SubGoalItem("대화 내용 확인", SubGoalStatus.COMPLETED)
                        ),
                        toolName = "complete_task",
                        toolArgs = JSONObject().put("summary", summary),
                        spokenReply = summary
                    )
                } else if (iteration == 1) {
                    // Inside a different chat room -> press BACK first to return to chat list
                    return ModelTurnDecision(
                        observation = "현재 대화방에서 목록으로 이동",
                        subGoals = listOf(SubGoalItem("채팅 목록으로 이동", SubGoalStatus.IN_PROGRESS)),
                        toolName = "press_key",
                        toolArgs = JSONObject().put("key", "BACK"),
                        spokenReply = "네, '$roomQuery' 카톡방으로 바로 이동할게요."
                    )
                }
            }

            // 2-B. Not in KakaoTalk yet -> launch KakaoTalk immediately
            if (snapshot.activePackage != "com.kakao.talk" && !historySteps.any { it.toolName == "manage_app" }) {
                return ModelTurnDecision(
                    observation = "카카오톡 즉시 실행",
                    subGoals = listOf(
                        SubGoalItem("카카오톡 열기", SubGoalStatus.IN_PROGRESS),
                        SubGoalItem("'$roomQuery' 대화방 진입", SubGoalStatus.PENDING)
                    ),
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", "카카오톡"),
                    spokenReply = "네, 카카오톡을 열어서 '$roomQuery' 방으로 바로 들어갈게요."
                )
            }

            // 2-C. Check if the target room element is visible on screen right now (excluding the search EditText itself)
            val nonEditableCandidates = snapshot.copy(
                indexedElements = snapshot.indexedElements.filter { !it.editable }
            )
            val matchedRoomEl = AgentToolExecutionHarness.findBestMatchingElement(nonEditableCandidates, roomQuery.ifBlank { g })
            if (matchedRoomEl != null) {
                val label = matchedRoomEl.text.ifBlank { matchedRoomEl.contentDesc }.take(24)
                return ModelTurnDecision(
                    observation = "'$label' 대화방 즉시 탭 진입",
                    subGoals = listOf(
                        SubGoalItem("'$label' 대화방 진입", SubGoalStatus.IN_PROGRESS)
                    ),
                    toolName = "click",
                    toolArgs = JSONObject().apply {
                        put("element_index", matchedRoomEl.index)
                        put("target_description", label)
                    },
                    spokenReply = if (iteration == 1) "네, '$label' 카톡방으로 바로 들어갈게요." else ""
                )
            }

            // 2-D. If search input box (`editable == true`) is open on screen
            val searchEdit = snapshot.indexedElements.firstOrNull { it.editable }
            val hasTypedRoom = historySteps.any { it.toolName == "input_text" }
            if (searchEdit != null && !hasTypedRoom && roomQuery.isNotBlank()) {
                return ModelTurnDecision(
                    observation = "검색창에 '$roomQuery' 즉시 입력",
                    subGoals = listOf(SubGoalItem("'$roomQuery' 검색", SubGoalStatus.IN_PROGRESS)),
                    toolName = "input_text",
                    toolArgs = JSONObject().apply {
                        put("element_index", searchEdit.index)
                        put("text", roomQuery)
                        put("clear_existing", false)
                        put("press_enter", true)
                    },
                    spokenReply = if (iteration == 1) "네, '$roomQuery' 카톡방을 검색해서 들어갈게요." else ""
                )
            }

            // 2-E. If we already typed into search, but are on an empty tab (e.g. '메시지' tab), click '전체' or '채팅방' tab
            if (hasTypedRoom) {
                val tabEl = snapshot.indexedElements.firstOrNull {
                    it.text == "채팅방" || it.contentDesc == "채팅방" || it.text == "전체" || it.contentDesc == "전체"
                }
                if (tabEl != null) {
                    val tabLabel = tabEl.text.ifBlank { tabEl.contentDesc }
                    return ModelTurnDecision(
                        observation = "'$tabLabel' 검색 탭 전환",
                        subGoals = listOf(SubGoalItem("'$tabLabel' 탭에서 대화방 선택", SubGoalStatus.IN_PROGRESS)),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", tabEl.index)
                            put("target_description", tabLabel)
                        },
                        spokenReply = ""
                    )
                }
            }

            // 2-F. Otherwise click the '검색' button in KakaoTalk header
            val searchBtn = snapshot.indexedElements.firstOrNull {
                it.text == "검색" || it.contentDesc == "검색" || it.resourceId.contains("search", ignoreCase = true)
            }
            if (searchBtn != null) {
                return ModelTurnDecision(
                    observation = "상단 검색 아이콘 탭",
                    subGoals = listOf(SubGoalItem("대화방 검색창 열기", SubGoalStatus.IN_PROGRESS)),
                    toolName = "click",
                    toolArgs = JSONObject().apply {
                        put("element_index", searchBtn.index)
                        put("target_description", "검색")
                    },
                    spokenReply = if (iteration == 1) "네, '$roomQuery' 카톡방을 찾아서 바로 들어갈게요." else ""
                )
            }
        }

        // 3. Instant Hardware Key / Back / Home / Scroll on Turn 1
        if (iteration == 1) {
            if (gLower == "뒤로" || gLower == "뒤로 가" || gLower == "뒤로가" || gLower == "뒤로 가줘" || gLower == "이전 화면" || gLower == "나가") {
                return ModelTurnDecision(
                    observation = "이전 화면 이동",
                    subGoals = listOf(SubGoalItem("뒤로가기", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "BACK"),
                    spokenReply = "네, 이전 화면으로 돌아갈게요."
                )
            }
            if (gLower == "홈" || gLower == "홈으로" || gLower == "홈 화면" || gLower == "홈으로 가줘") {
                return ModelTurnDecision(
                    observation = "홈 화면 이동",
                    subGoals = listOf(SubGoalItem("홈 이동", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", "HOME"),
                    spokenReply = "네, 홈 화면으로 이동할게요."
                )
            }
        }

        return null
    }

    private fun isInsideKakaoChatRoom(snapshot: AtomicSnapshot): Boolean {
        if (snapshot.activePackage != "com.kakao.talk") return false
        return snapshot.indexedElements.any { el ->
            el.text.contains("메시지 입력") ||
                el.contentDesc.contains("메시지 입력") ||
                el.contentDesc == "전송" ||
                el.contentDesc == "보내기" ||
                el.contentDesc.contains("이모티콘") ||
                el.contentDesc.contains("멀티미디어") ||
                (el.editable && el.centerY > snapshot.screenHeight * 0.55)
        }
    }

    private fun extractCleanRoomQuery(rawGoal: String): String {
        val cleaned = rawGoal
            .replace("카카오톡에서", "")
            .replace("카카오톡", "")
            .replace("카톡방에서", "")
            .replace("카톡방에", "")
            .replace("카톡방", "")
            .replace("대화방에서", "")
            .replace("대화방", "")
            .replace("채팅방", "")
            .replace("단톡방", "")
            .replace("들어가서", "")
            .replace("들어가봐", "")
            .replace("들어가줘", "")
            .replace("들어가", "")
            .replace("열어서", "")
            .replace("열어봐", "")
            .replace("열어줘", "")
            .replace("열어", "")
            .replace("무슨 얘기하고 있는지", "")
            .replace("무슨 얘기", "")
            .replace("내용 알려줘", "")
            .replace("내용 요약해줘", "")
            .replace("읽어줘", "")
            .replace("알려줘", "")
            .replace("해봐", "")
            .replace("봐봐", "")
            .replace("한번", "")
            .replace("바로", "")
            .replace("봐", "")
            .trim()
        return AgentToolExecutionHarness.normalizeSinoKoreanNumberPrefix(cleaned)
    }

    private fun buildNaturalChatOrScreenSummary(snapshot: AtomicSnapshot, goal: String): String {
        if (snapshot.activePackage == "com.kakao.talk" && isInsideKakaoChatRoom(snapshot)) {
            val headerCandidates = snapshot.indexedElements
                .filter { it.centerY in 60..310 && it.text.isNotBlank() }
                .map { it.text.trim() }
                .filterNot { it in setOf("뒤로가기", "검색", "메뉴", "공지", "하단으로 이동") || it.all { ch -> ch.isDigit() } }
            val roomTitle = headerCandidates.firstOrNull() ?: "현재 카카오톡"

            val ignoreSet = setOf(
                roomTitle, "뒤로가기", "검색", "대화상대 및 메뉴", "공지", "메시지 입력", "이모티콘", "전송", "보내기",
                "멀티미디어 전송", "샵검색", "음성메시지", "하단으로 이동", "읽음", "안읽음", "공감", "답장"
            )
            val timeRegex = Regex("^(오전|오후)\\s*\\d{1,2}:\\d{2}$|^\\d{4}년.*요일$")

            val bodyItems = snapshot.indexedElements
                .filter { it.centerY in 280..(snapshot.screenHeight - 220) }
                .map { it.text.trim().ifBlank { it.contentDesc.trim() } }
                .filter { label ->
                    label.length >= 2 &&
                        label !in ignoreSet &&
                        !label.all { ch -> ch.isDigit() } &&
                        !timeRegex.matches(label) &&
                        !label.endsWith("개의 읽지 않은 메시지")
                }
                .distinct()

            if (bodyItems.isNotEmpty()) {
                // Pair short names (2~4 Korean chars) with subsequent messages when possible
                val dialogSnippets = mutableListOf<String>()
                var i = 0
                while (i < bodyItems.size && dialogSnippets.size < 4) {
                    val cur = bodyItems[i]
                    val next = bodyItems.getOrNull(i + 1)
                    if (cur.length in 2..4 && next != null && next.length >= 2 && !next.endsWith("님")) {
                        dialogSnippets.add("${cur} 님이 \"$next\"")
                        i += 2
                    } else {
                        dialogSnippets.add("\"$cur\"")
                        i += 1
                    }
                }
                return "'$roomTitle' 방에 들어왔어요! 최근 대화를 보면 ${dialogSnippets.joinToString(", ")}라고 이야기하고 있네요. 이 방에 뭐라고 답장을 보낼까요?"
            }
            return "'$roomTitle' 카톡방에 들어왔어요! 어떤 메시지를 입력해서 보낼까요?"
        }

        val visibleTexts = snapshot.indexedElements
            .map { it.text.trim().ifBlank { it.contentDesc.trim() } }
            .filter { it.length >= 2 && !it.all { ch -> ch.isDigit() } }
            .distinct()
            .take(6)

        return if (visibleTexts.isNotEmpty()) {
            "지금 화면에는 ${visibleTexts.joinToString(", ") { "'$it'" }} 항목들이 보이고 있어요. 어떤 항목을 누르거나 실행할까요?"
        } else {
            "지금 화면을 확인했어요. 이어서 누르거나 실행할 작업을 편하게 말씀해 주세요."
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
                if (!snapshot.visualGroundingBase64.isNullOrBlank()) {
                    put("somScreenshot", snapshot.visualGroundingBase64)
                }
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
                .readTimeout(35, TimeUnit.SECONDS)
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

        val visualBase64 = snapshot.visualGroundingBase64
        val isManagedAgentModel = modelName.startsWith("antigravity-") || modelName.startsWith("deep-research-")
        if (isManagedAgentModel) {
            val interactionsDecision = runCatching {
                callGeminiInteractionsApi(
                    apiKey = apiKey,
                    modelName = modelName,
                    promptText = promptText,
                    screenshotBase64Jpeg = visualBase64,
                    previousPlan = previousPlan
                )
            }.getOrNull()
            if (interactionsDecision != null) {
                return rescueActionPromiseDecision(
                    decision = interactionsDecision,
                    goal = goal,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory
                )
            }
        }

        val partsArray = JSONArray()
        if (!visualBase64.isNullOrBlank()) {
            partsArray.put(
                JSONObject().apply {
                    put(
                        "inlineData",
                        JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", visualBase64)
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
                "toolConfig",
                JSONObject().put(
                    "functionCallingConfig",
                    JSONObject().put("mode", "ANY")
                )
            )
        }

        // Resolve 2026 logical model names to include live production Google Gemini v1beta model IDs
        // so that if a 2026 preview alias returns HTTP 404, we immediately execute on live Gemini 2.5/2.0 endpoints.
        val endpointCandidates = buildList {
            add(modelName)
            when {
                modelName.contains("argon") || modelName.contains("pro") -> {
                    add("gemini-2.5-pro")
                    add("gemini-2.5-flash")
                    add("gemini-2.0-flash")
                }
                modelName.contains("lite") -> {
                    add("gemini-2.5-flash-lite")
                    add("gemini-2.5-flash")
                    add("gemini-2.0-flash")
                }
                else -> {
                    add("gemini-2.5-flash")
                    add("gemini-2.5-flash-lite")
                    add("gemini-2.0-flash")
                }
            }
        }.distinct()

        var lastError: IllegalStateException? = null
        for (endpointModel in endpointCandidates) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$endpointModel:generateContent"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(180)}")
                    lastError = err
                    // If auth/quota error (401/402/403/429), fail immediately; if 404/400 (model alias not found), try next endpointModel
                    if (resp.code == 401 || resp.code == 402 || resp.code == 403 || resp.code == 429) {
                        throw err
                    }
                    return@use
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

                val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
                val argSpoken = fnArgs.optString("spoken_reply", "").trim()
                val spoken = argSpoken.ifBlank { parsedSpoken }
                val rawDecision = if (fnName != null) {
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
                return rescueActionPromiseDecision(
                    decision = rawDecision,
                    goal = goal,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory
                )
            }
        }
        throw (lastError ?: IllegalStateException("HTTP 404: No matching Gemini endpoint model"))
    }

    /**
     * Calls Anthropic Messages API (`https://api.anthropic.com/v1/messages`) with multimodal image + tool calling.
     * Converts `ToolSpecRegistry.toGeminiToolsJson()` declarations into Anthropic `input_schema` tools.
     */
    private fun callAnthropicMessagesApi(
        apiKey: String,
        modelName: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision {
        val promptText = buildUnifiedTurnPrompt(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        val visualBase64 = snapshot.visualGroundingBase64
        val contentBlocks = JSONArray()
        if (!visualBase64.isNullOrBlank()) {
            contentBlocks.put(
                JSONObject().apply {
                    put("type", "image")
                    put(
                        "source",
                        JSONObject().apply {
                            put("type", "base64")
                            put("media_type", "image/jpeg")
                            put("data", visualBase64)
                        }
                    )
                }
            )
        }
        contentBlocks.put(
            JSONObject().apply {
                put("type", "text")
                put("text", promptText)
            }
        )

        val anthropicTools = JSONArray()
        val geminiToolGroups = ToolSpecRegistry.toGeminiToolsJson()
        for (g in 0 until geminiToolGroups.length()) {
            val decls = geminiToolGroups.optJSONObject(g)?.optJSONArray("functionDeclarations") ?: continue
            for (i in 0 until decls.length()) {
                val fn = decls.optJSONObject(i) ?: continue
                anthropicTools.put(
                    JSONObject().apply {
                        put("name", fn.optString("name"))
                        put("description", fn.optString("description"))
                        put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                    }
                )
            }
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", 2048)
            put("system", SYSTEM_INSTRUCTION)
            put("tools", anthropicTools)
            put("tool_choice", JSONObject().put("type", "any"))
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", contentBlocks)
                    }
                )
            )
        }

        val req = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("Anthropic HTTP ${resp.code}: ${bodyStr.take(180)}")
            }
            val root = JSONObject(bodyStr)
            val contentArr = root.optJSONArray("content") ?: JSONArray()
            var textOut = ""
            var fnName: String? = null
            var fnArgs = JSONObject()

            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                when (block.optString("type")) {
                    "text" -> textOut += block.optString("text") + "\n"
                    "tool_use" -> {
                        fnName = block.optString("name")
                        fnArgs = block.optJSONObject("input") ?: JSONObject()
                    }
                }
            }

            val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            val argSpoken = fnArgs.optString("spoken_reply", "").trim()
            val spoken = argSpoken.ifBlank { parsedSpoken }
            val rawDecision = if (fnName != null) {
                ModelTurnDecision(obs, updatedPlan, fnName, fnArgs, spoken)
            } else {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", spoken.ifBlank { obs }),
                    spokenReply = spoken
                )
            }
            return rescueActionPromiseDecision(rawDecision, goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        }
    }

    /**
     * Calls OpenAI / OpenRouter / Custom OpenAI-Compatible Chat Completions API (`/v1/chat/completions`)
     * with Set-of-Marks multimodal vision + tool calling.
     */
    private fun callOpenAiCompatibleApi(
        apiKey: String,
        modelName: String,
        isOpenRouter: Boolean,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>,
        customBaseUrl: String = ""
    ): ModelTurnDecision {
        val promptText = buildUnifiedTurnPrompt(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        val visualBase64 = snapshot.visualGroundingBase64
        val userContent = JSONArray()
        if (!visualBase64.isNullOrBlank()) {
            userContent.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$visualBase64"))
                }
            )
        }
        userContent.put(JSONObject().put("type", "text").put("text", promptText))

        val openAiTools = JSONArray()
        val geminiToolGroups = ToolSpecRegistry.toGeminiToolsJson()
        for (g in 0 until geminiToolGroups.length()) {
            val decls = geminiToolGroups.optJSONObject(g)?.optJSONArray("functionDeclarations") ?: continue
            for (i in 0 until decls.length()) {
                val fn = decls.optJSONObject(i) ?: continue
                openAiTools.put(
                    JSONObject().apply {
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", fn.optString("name"))
                                put("description", fn.optString("description"))
                                put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
                            }
                        )
                    }
                )
            }
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("tools", openAiTools)
            put("tool_choice", if (customBaseUrl.isNotBlank()) "auto" else "required")
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", SYSTEM_INSTRUCTION))
                    put(JSONObject().put("role", "user").put("content", userContent))
                }
            )
        }

        val endpoint = when {
            customBaseUrl.isNotBlank() -> normalizeChatCompletionsEndpoint(customBaseUrl)
            isOpenRouter -> "https://openrouter.ai/api/v1/chat/completions"
            else -> "https://api.openai.com/v1/chat/completions"
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("OpenAI/OR HTTP ${resp.code}: ${bodyStr.take(180)}")
            }
            val root = JSONObject(bodyStr)
            val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            val textOut = msg?.optString("content").orEmpty()
            val toolCalls = msg?.optJSONArray("tool_calls")
            var fnName: String? = null
            var fnArgs = JSONObject()
            if (toolCalls != null && toolCalls.length() > 0) {
                val fnObj = toolCalls.optJSONObject(0)?.optJSONObject("function")
                fnName = fnObj?.optString("name")
                val argsStr = fnObj?.optString("arguments").orEmpty()
                fnArgs = runCatching { JSONObject(argsStr) }.getOrDefault(JSONObject())
            }

            val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            val argSpoken = fnArgs.optString("spoken_reply", "").trim()
            val spoken = argSpoken.ifBlank { parsedSpoken }
            val rawDecision = if (!fnName.isNullOrBlank()) {
                ModelTurnDecision(obs, updatedPlan, fnName, fnArgs, spoken)
            } else {
                ModelTurnDecision(
                    observation = obs,
                    subGoals = updatedPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", spoken.ifBlank { obs }),
                    spokenReply = spoken
                )
            }
            return rescueActionPromiseDecision(rawDecision, goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        }
    }

    private fun buildUnifiedTurnPrompt(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): String {
        val chatSummary = if (chatHistory.isEmpty()) {
            "(No prior live chat messages)"
        } else {
            chatHistory.takeLast(14).joinToString("\n") { msg -> "[${msg.sender.name}] ${msg.text}" }
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
        return buildString {
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
    }

    /**
     * Prevents "Talk-Only Stop" failures where a model says "네, '프로젝트팀' 카톡방으로 바로 들어갈게요."
     * or "설치된 앱 중에서 어떤 게임들이 있는지 전부 확인해 드릴게요. 잠시만요!" on Step 1
     * without emitting the actual tool call (or prematurely calling `complete_task`).
     */
    private fun rescueActionPromiseDecision(
        decision: ModelTurnDecision,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision {
        if (decision.toolName != "complete_task") {
            return decision
        }
        val gLower = goal.lowercase()
        val spokenText = decision.spokenReply.ifBlank { decision.toolArgs.optString("summary", "") }
        val promisesAction = spokenText.contains("들어갈게요") ||
            spokenText.contains("열어볼게요") ||
            spokenText.contains("열어드릴게요") ||
            spokenText.contains("확인해 드릴게요") ||
            spokenText.contains("확인해 볼게요") ||
            spokenText.contains("찾아볼게요") ||
            spokenText.contains("찾아드릴게요") ||
            spokenText.contains("눌러볼게요") ||
            spokenText.contains("누를게요") ||
            spokenText.contains("잠시만요") ||
            spokenText.contains("실행할게요")

        val isActionGoalOnStep1 = iteration == 1 && historySteps.isEmpty() && (
            promisesAction ||
                gLower.contains("들어가") ||
                gLower.contains("눌러") ||
                gLower.contains("찾아봐") ||
                gLower.contains("찾아줘") ||
                gLower.contains("열어") ||
                gLower.contains("실행") ||
                gLower.contains("틀어")
            )

        if (!isActionGoalOnStep1) {
            return decision
        }

        // 1. Game / Installed App Discovery rescue
        if ((gLower.contains("게임") || gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린")) &&
            (gLower.contains("찾아") || gLower.contains("뭐 있") || gLower.contains("무슨") || gLower.contains("알려") || gLower.contains("확인"))
        ) {
            return ModelTurnDecision(
                observation = "기기 설치 게임 및 앱 패키지 스캔",
                subGoals = listOf(SubGoalItem("설치된 게임 및 앱 전체 스캔", SubGoalStatus.IN_PROGRESS)),
                toolName = "manage_app",
                toolArgs = JSONObject().put("action", "list_games").put("app_name", "게임"),
                spokenReply = spokenText.ifBlank { "기기에 설치된 게임과 앱 목록을 바로 스캔해서 알려드릴게요." }
            )
        }

        // 2. Quoted target in model's own spoken reply (e.g. "네, '프로젝트팀' 카톡방으로 바로 들어갈게요.")
        val quotedTarget = Regex("'([^']{2,30})'|\"([^\"]{2,30})\"").find(spokenText)?.let { m ->
            m.groupValues[1].ifBlank { m.groupValues[2] }
        }
        if (!quotedTarget.isNullOrBlank()) {
            val matchedFromQuote = AgentToolExecutionHarness.findBestMatchingElement(snapshot, quotedTarget)
            if (matchedFromQuote != null) {
                val label = matchedFromQuote.text.ifBlank { matchedFromQuote.contentDesc }.take(24)
                return ModelTurnDecision(
                    observation = "'$label' 요소 탭 실행",
                    subGoals = listOf(SubGoalItem("'$label' 진입", SubGoalStatus.IN_PROGRESS)),
                    toolName = "click",
                    toolArgs = JSONObject().apply {
                        put("element_index", matchedFromQuote.index)
                        put("target_description", label)
                    },
                    spokenReply = spokenText
                )
            }
        }

        // 3. Fuzzy match user goal against current screen elements (handles Sino-Korean digit ASR like "사층 회의실" -> "4층회의실")
        val fuzzyScreenMatch = AgentToolExecutionHarness.findBestMatchingElement(snapshot, goal)
        if (fuzzyScreenMatch != null) {
            val label = fuzzyScreenMatch.text.ifBlank { fuzzyScreenMatch.contentDesc }.take(24)
            return ModelTurnDecision(
                observation = "'$label' 요소 탭 실행",
                subGoals = listOf(SubGoalItem("'$label' 선택", SubGoalStatus.IN_PROGRESS)),
                toolName = "click",
                toolArgs = JSONObject().apply {
                    put("element_index", fuzzyScreenMatch.index)
                    put("target_description", label)
                },
                spokenReply = spokenText.ifBlank { "네, '$label' 항목으로 바로 들어갈게요." }
            )
        }

        // 4. Fallback to deterministic local action inference
        val localRescue = inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        return if (localRescue.toolName != "complete_task" && localRescue.toolName != "ask_user") {
            localRescue.copy(spokenReply = spokenText.ifBlank { localRescue.spokenReply })
        } else {
            decision
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

        // 6. Multi-Step App + Obstacle Dismissal + Search/Type + Click Trajectory (Verified Postcondition Loop)
        // If user says "완료 못 했잖아", "안 됐잖아", "다시 해봐", recover the unfinished goal from recent chatHistory!
        val isRetryOrComplaint = gLower.contains("완료 못") || gLower.contains("안 됐") || gLower.contains("안됐") ||
            gLower.contains("못 했잖아") || gLower.contains("못했잖아") || gLower == "다시 해봐" || gLower == "다시 해 줘"
        val effectiveGoal = if (isRetryOrComplaint) {
            chatHistory.lastOrNull {
                it.sender == ChatSender.USER &&
                    it.text != g &&
                    !it.text.contains("완료 못") &&
                    (it.text.contains("검색") || it.text.contains("열어") || it.text.contains("켜") || it.text.contains("찾아"))
            }?.text ?: g
        } else {
            g
        }

        val (targetAppPair, cleanCompoundQuery) = IntentDecomposerAndRouter.extractCleanAppAndSearchQuery(effectiveGoal)
        val targetAppName = targetAppPair?.first
        val targetPkg = targetAppPair?.second

        // Explicit modal/popup close command ("이거 창 닫아줘", "팝업 닫아봐") without search
        val wantsExplicitModalClose = gLower.contains("창 닫") || gLower.contains("팝업 닫") || gLower.contains("광고 닫") || gLower == "닫기 눌러"
        if (wantsExplicitModalClose && cleanCompoundQuery == null && iteration == 1) {
            val closeEl = snapshot.indexedElements
                .filterNot { IntentDecomposerAndRouter.isHansonOwnElement(it) }
                .firstOrNull { el ->
                    el.text.contains("닫기") || el.contentDesc.contains("닫기") ||
                        el.text.contains("그만 보기") || el.text.contains("보지 않기") ||
                        el.resourceId.contains("close", ignoreCase = true)
                }
            if (closeEl != null) {
                return ModelTurnDecision(
                    observation = "현재 화면의 팝업 닫기 버튼 탭",
                    subGoals = listOf(SubGoalItem("팝업 창 닫기", SubGoalStatus.COMPLETED)),
                    toolName = "click",
                    toolArgs = JSONObject().apply {
                        put("element_index", closeEl.index)
                        put("target_description", "팝업 닫기")
                    },
                    spokenReply = "네, 화면에 떠 있는 창을 바로 닫을게요."
                )
            }
        }

        // 5-B. Chat room entry flow ("팀 회의실 카톡방 들어가 봐"):
        // Launch KakaoTalk if not in foreground; once in KakaoTalk, fuzzy-match the room title and click it!
        if ((gLower.contains("카톡방") || gLower.contains("대화방") || gLower.contains("채팅방") || gLower.contains("단톡방")) &&
            (gLower.contains("들어가") || gLower.contains("열어") || gLower.contains("눌러") || gLower.contains("보여") || gLower.contains("확인"))
        ) {
            val hasClickedRoom = historySteps.any { it.toolName == "click" }
            if (!hasClickedRoom) {
                if (snapshot.activePackage != "com.kakao.talk" && !historySteps.any { it.toolName == "manage_app" }) {
                    val spoken = "네, 먼저 카카오톡을 열고 말씀하신 대화방을 찾아볼게요."
                    return ModelTurnDecision(
                        observation = "카카오톡 실행",
                        subGoals = listOf(
                            SubGoalItem("카카오톡 열기", SubGoalStatus.IN_PROGRESS),
                            SubGoalItem("대화방 진입", SubGoalStatus.PENDING)
                        ),
                        toolName = "manage_app",
                        toolArgs = JSONObject().put("action", "launch").put("app_name", "카카오톡"),
                        spokenReply = spoken
                    )
                }
                val roomElement = AgentToolExecutionHarness.findBestMatchingElement(snapshot, g)
                if (roomElement != null) {
                    val label = roomElement.text.ifBlank { roomElement.contentDesc }.take(24)
                    val spoken = "네, '$label' 카톡방으로 바로 들어갈게요."
                    return ModelTurnDecision(
                        observation = "'$label' 대화방 탭 진입",
                        subGoals = listOf(
                            SubGoalItem("카카오톡 열기", SubGoalStatus.COMPLETED),
                            SubGoalItem("'$label' 대화방 진입", SubGoalStatus.IN_PROGRESS)
                        ),
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", roomElement.index)
                            put("target_description", label)
                        },
                        spokenReply = spoken
                    )
                }
            }
        }

        // 0. Check Unified Intent Decomposer first for instant reflex or deictic/phonetic screen element clicks!
        if (iteration == 1) {
            val decomposed = IntentDecomposerAndRouter.decompose(
                rawPrompt = g,
                snapshot = snapshot,
                chatHistory = chatHistory,
                isMainActivityInForeground = false
            )
            if (decomposed.fastDecision != null) {
                return decomposed.fastDecision
            }
        }

        val isCrossAppTarget = targetPkg != null && snapshot.activePackage != targetPkg
        val directScreenElementMatch = if (isCrossAppTarget || !IntentDecomposerAndRouter.isExternalAppSnapshot(snapshot)) {
            null
        } else {
            snapshot.indexedElements
                .filterNot { IntentDecomposerAndRouter.isHansonOwnElement(it) }
                .firstOrNull { el ->
                    (el.text.length >= 2 && g.contains(el.text, ignoreCase = true) && (gLower.contains("눌러") || gLower.contains("선택") || gLower.contains("열어") || gLower.contains("클릭"))) ||
                        (el.contentDesc.length >= 2 && g.contains(el.contentDesc, ignoreCase = true) && (gLower.contains("눌러") || gLower.contains("선택") || gLower.contains("열어") || gLower.contains("클릭")))
                } ?: IntentDecomposerAndRouter.findBestScreenElement(snapshot, g, IntentDecomposerAndRouter.extractSpatialHint(gLower))
        }

        val isConversationalCorrection = gLower.startsWith("아니") || gLower.startsWith("아뇨") ||
            gLower.contains("그거 말고") || gLower.contains("아까 말했던") || gLower.contains("아까 말한") ||
            gLower.contains("클릭해서") || gLower.contains("눌러서") || gLower.contains("저 위에") || gLower.contains("저 밑에")

        val extractedQuery: String? = if (isConversationalCorrection ||
            (directScreenElementMatch != null && !gLower.contains("검색") && !gLower.contains("입력"))
        ) {
            null
        } else {
            cleanCompoundQuery
        }

        val wantsDragAndDrop = gLower.contains("드래그") || gLower.contains("슬라이더") || gLower.contains("옮겨")
        val wantsPostResultClick = (extractedQuery != null) && (
            gLower.contains("클릭") || gLower.contains("틀어") || gLower.contains("재생") ||
                gLower.contains("열어") || gLower.contains("선택") || gLower.contains("눌러") || gLower.contains("들어가")
            ) && !(targetAppName != null && (gLower.contains("열어서") || gLower.contains("켜서")) && !gLower.contains("클릭") && !gLower.contains("눌러") && !gLower.contains("틀어"))

        val isMultiStepGoal = !gLower.contains("답장") && !gLower.contains("알림") && (
            (extractedQuery != null) || wantsDragAndDrop ||
                (targetAppName != null && (gLower.contains("그리고") || gLower.contains("해서") || gLower.contains("열어서") || gLower.contains("켜서") || gLower.contains("틀어") || gLower.contains("찾아") || gLower.contains("검색")))
            )

        if (isMultiStepGoal && iteration <= 8) {
            val launchSteps = historySteps.filter { it.toolName == "manage_app" }
            val hasLaunchedApp = launchSteps.isNotEmpty()
            val hasDismissedModal = historySteps.any {
                it.toolName == "click" && (it.toolArgsJson.contains("팝업") || it.toolArgsJson.contains("닫기"))
            }
            val hasClickedSearch = historySteps.any {
                it.toolName == "click" && it.toolArgsJson.contains("검색")
            }
            val hasTypedQuery = historySteps.any { it.toolName == "input_text" && it.succeeded }
            val hasDragged = historySteps.any { it.toolName == "drag_and_drop" || it.toolName == "haptic_trajectory" }
            val clicksAfterTyping = historySteps.dropWhile { it.toolName != "input_text" }.count { it.toolName == "click" }

            val safeElements = snapshot.indexedElements.filterNot { IntentDecomposerAndRouter.isHansonOwnElement(it) }
            val blockingModalCloseBtn = safeElements.firstOrNull { el ->
                val lbl = "${el.text} ${el.contentDesc}".trim()
                lbl == "닫기" || lbl.startsWith("닫기") || lbl.endsWith("닫기") ||
                    lbl.contains("오늘 하루 보지 않기") || lbl.contains("그만 보기") ||
                    lbl.contains("나중에") || lbl.contains("건너뛰기") ||
                    el.resourceId.contains("btn_close", ignoreCase = true) ||
                    el.resourceId.contains("close_btn", ignoreCase = true)
            }

            val ledger = mutableListOf<SubGoalItem>()
            if (targetAppName != null) {
                val done = (snapshot.activePackage == targetPkg)
                ledger.add(SubGoalItem("$targetAppName 열기", if (done) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
            }
            if (blockingModalCloseBtn != null || hasDismissedModal || wantsExplicitModalClose) {
                ledger.add(SubGoalItem("가로막는 팝업 닫기", if (hasDismissedModal && blockingModalCloseBtn == null) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
            }
            if (extractedQuery != null) {
                ledger.add(SubGoalItem("검색창 열기", if (hasClickedSearch || hasTypedQuery) SubGoalStatus.COMPLETED else SubGoalStatus.IN_PROGRESS))
                ledger.add(SubGoalItem("'$extractedQuery' 입력 및 검색", if (hasTypedQuery) SubGoalStatus.COMPLETED else SubGoalStatus.PENDING))
            }
            if (wantsPostResultClick) {
                ledger.add(SubGoalItem("결과 항목 선택", if (clicksAfterTyping >= 1) SubGoalStatus.COMPLETED else SubGoalStatus.PENDING))
            }

            // STAGE 1: Target App Not in Foreground Yet -> Launch App
            if (targetAppName != null && targetPkg != null && snapshot.activePackage != targetPkg) {
                val lastLaunchFailed = launchSteps.lastOrNull()?.succeeded == false
                if (lastLaunchFailed) {
                    val failMsg = "기기에 '$targetAppName' 앱이 설치되어 있지 않거나 실행할 수 없어요."
                    return ModelTurnDecision(
                        observation = "$targetAppName 실행 불가",
                        subGoals = ledger,
                        toolName = "complete_task",
                        toolArgs = JSONObject().put("summary", failMsg),
                        spokenReply = failMsg
                    )
                }
                if (launchSteps.size < 2) {
                    val spoken = if (!hasLaunchedApp) {
                        if (extractedQuery != null) {
                            "네, 먼저 ${targetAppName}을 열고 '$extractedQuery'를 검색해 볼게요."
                        } else {
                            "네, ${targetAppName}을 바로 열게요."
                        }
                    } else {
                        ""
                    }
                    return ModelTurnDecision(
                        observation = "현재 화면($currentAppName)에서 목표 앱($targetAppName)으로 전환하기 위해 앱을 실행합니다.",
                        subGoals = ledger,
                        toolName = "manage_app",
                        toolArgs = JSONObject().put("action", "launch").put("app_name", targetAppName),
                        spokenReply = spoken
                    )
                } else {
                    val waitMsg = "${targetAppName}을 열었어요. 화면이 완전히 준비되면 '$extractedQuery' 검색을 이어서 진행해 주세요."
                    return ModelTurnDecision(
                        observation = "$targetAppName 전환 대기 완료",
                        subGoals = ledger,
                        toolName = "complete_task",
                        toolArgs = JSONObject().put("summary", waitMsg),
                        spokenReply = waitMsg
                    )
                }
            }

            // STAGE 2: Search & Input Trajectory (with Modal Obstacle Dismissal)
            if (extractedQuery != null && !hasTypedQuery) {
                val editableEl = safeElements.firstOrNull { it.editable }

                // 2-A. If an editable search input box is ALREADY open on screen (and no explicit modal close was requested first)
                if (editableEl != null && !(wantsExplicitModalClose && !hasDismissedModal && blockingModalCloseBtn != null)) {
                    val spoken = "검색창에 '$extractedQuery'라고 입력하고 있어요."
                    return ModelTurnDecision(
                        observation = "검색 입력창이 활성화되어 있어 '$extractedQuery' 텍스트를 입력하고 검색을 실행합니다.",
                        subGoals = ledger.map {
                            if (it.title.contains("검색창 열기")) it.copy(status = SubGoalStatus.COMPLETED)
                            else if (it.title.contains("입력 및 검색")) it.copy(status = SubGoalStatus.IN_PROGRESS)
                            else it
                        },
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

                // 2-B. Check if user explicitly asked to close a modal ("창 닫고") OR a modal ('닫기') is blocking the search bar
                val explicitSearchBtn = safeElements.firstOrNull { el ->
                    el.text.contains("검색") || el.contentDesc.contains("검색") ||
                        el.text.contains("찾으시나요") || el.contentDesc.contains("찾으시나요") ||
                        el.text.contains("먹고 싶은") || el.contentDesc.contains("먹고 싶은") ||
                        el.text.contains("Search", ignoreCase = true) ||
                        el.contentDesc.contains("Search", ignoreCase = true) ||
                        el.resourceId.contains("search", ignoreCase = true)
                }

                if (blockingModalCloseBtn != null && !hasDismissedModal && (wantsExplicitModalClose || explicitSearchBtn == null)) {
                    val spoken = if (isRetryOrComplaint) {
                        "팝업 창에 가려져 있었네요! 먼저 창을 닫고 '$extractedQuery' 검색을 마저 진행할게요."
                    } else {
                        "화면을 가리고 있는 팝업 창을 먼저 닫고 '$extractedQuery'를 검색할게요."
                    }
                    return ModelTurnDecision(
                        observation = "$currentAppName 화면을 가리고 있는 팝업('닫기')이 감지되어 먼저 팝업 닫기를 실행합니다.",
                        subGoals = ledger,
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", blockingModalCloseBtn.index)
                            put("target_description", "팝업 닫기")
                        },
                        spokenReply = spoken
                    )
                }

                // 2-C. Click the search bar / search icon to open the editable input field
                if (!hasClickedSearch) {
                    val searchBtn = explicitSearchBtn ?: safeElements.firstOrNull { el ->
                        // Wide search bar banner near top of app home screen (e.g. Baemin, Coupang, Naver)
                        el.clickable && el.centerY in 90..560 &&
                            (el.right - el.left) > (snapshot.screenWidth * 0.42) &&
                            el.normX in 120..880 &&
                            !el.text.contains("닫기") && !el.contentDesc.contains("닫기")
                    } ?: safeElements.firstOrNull { el ->
                        // Top-right search icon, strictly avoiding Samsung Edge Panel handle (normX in 650..935)
                        el.clickable && el.centerY in 60..280 && el.normX in 650..935 &&
                            !el.text.contains("닫기") && !el.contentDesc.contains("닫기")
                    }

                    if (searchBtn != null) {
                        val spoken = "검색창을 열어서 '$extractedQuery'를 입력할게요."
                        return ModelTurnDecision(
                            observation = "$currentAppName 화면에서 검색창 요소를 확인하여 탭합니다.",
                            subGoals = ledger,
                            toolName = "click",
                            toolArgs = JSONObject().apply {
                                put("element_index", searchBtn.index)
                                put("target_description", "검색창 열기")
                            },
                            spokenReply = spoken
                        )
                    }
                }

                // 2-D. Only type blind if we ALREADY clicked the search bar ("검색창 열기") on the target app screen
                if (hasClickedSearch) {
                    val spoken = "'$extractedQuery'를 바로 입력할게요."
                    return ModelTurnDecision(
                        observation = "열린 검색창에 '$extractedQuery'를 입력하고 엔터를 전송합니다.",
                        subGoals = ledger,
                        toolName = "input_text",
                        toolArgs = JSONObject().apply {
                            put("text", extractedQuery)
                            put("submit_enter", true)
                        },
                        spokenReply = spoken
                    )
                }
            }

            // STAGE 3: Post-Typing Verification — If we typed the query, verify if search confirm button still needs a click
            if (extractedQuery != null && hasTypedQuery && !wantsPostResultClick && clicksAfterTyping == 0) {
                val editableStillOpen = safeElements.firstOrNull { it.editable }
                val searchConfirmBtn = safeElements.firstOrNull { el ->
                    !el.editable && el.clickable && el.centerY in 60..360 &&
                        (el.text == "검색" || el.contentDesc == "검색" ||
                            el.contentDesc.contains("검색", ignoreCase = true) ||
                            el.resourceId.contains("search_btn", ignoreCase = true) ||
                            el.resourceId.contains("btn_search", ignoreCase = true))
                }
                val resultItemsBelow = safeElements.count { !it.editable && it.centerY > 380 && it.text.length >= 2 }
                if (editableStillOpen != null && searchConfirmBtn != null && resultItemsBelow <= 3) {
                    return ModelTurnDecision(
                        observation = "'$extractedQuery' 입력 후 검색 결과를 불러오기 위해 우측 검색 버튼을 탭합니다.",
                        subGoals = ledger,
                        toolName = "click",
                        toolArgs = JSONObject().apply {
                            put("element_index", searchConfirmBtn.index)
                            put("target_description", "검색 실행 확인")
                        },
                        spokenReply = ""
                    )
                }
            }

            if (wantsDragAndDrop && !hasDragged) {
                val sliderEl = safeElements.firstOrNull { el ->
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

            if (wantsPostResultClick && hasTypedQuery && clicksAfterTyping == 0) {
                val candidateResult = safeElements.firstOrNull { el ->
                    !el.editable && el.centerY > 300 &&
                        (el.text.contains(extractedQuery ?: "", ignoreCase = true) ||
                            el.contentDesc.contains(extractedQuery ?: "", ignoreCase = true))
                } ?: safeElements.firstOrNull { el ->
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

            // STAGE 4: Verified Goal Completion Postcondition Check (`DONE` Option)
            // NEVER claim search completion if `extractedQuery != null` and `hasTypedQuery == false`!
            val goalPostconditionMet = when {
                extractedQuery != null && wantsPostResultClick -> hasTypedQuery && clicksAfterTyping >= 1
                extractedQuery != null -> hasTypedQuery
                wantsDragAndDrop -> hasDragged
                targetAppName != null -> snapshot.activePackage == targetPkg
                else -> historySteps.isNotEmpty()
            }

            if (goalPostconditionMet) {
                val doneMsg = when {
                    wantsPostResultClick && extractedQuery != null ->
                        "'$extractedQuery' 검색 결과를 열었어요. 다른 항목을 누르거나 더 내려볼까요?"
                    extractedQuery != null ->
                        "$currentAppName 화면에서 '$extractedQuery' 검색 결과까지 확인했어요. 어떤 가게나 메뉴를 눌러볼까요?"
                    else ->
                        "요청하신 작업을 완료했어요. 이어서 무엇을 할까요?"
                }
                return ModelTurnDecision(
                    observation = "목표 사후조건(Postcondition) 달성 확인 완료: $doneMsg",
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

        // 8-B. Installed Game / App Inventory Scan ("내 앱에서 무슨 게임 있는지 찾아봐", "설치된 앱 알려줘")
        if ((gLower.contains("게임") || gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린")) &&
            (gLower.contains("찾아") || gLower.contains("뭐 있") || gLower.contains("무슨") || gLower.contains("알려") || gLower.contains("확인") || gLower.contains("목록"))
        ) {
            val spoken = "기기에 설치된 게임과 앱들을 바로 스캔해서 알려드릴게요."
            return ModelTurnDecision(
                observation = "설치된 게임 및 앱 패키지 스캔",
                subGoals = listOf(SubGoalItem("설치된 게임/앱 조회", SubGoalStatus.IN_PROGRESS)),
                toolName = "manage_app",
                toolArgs = JSONObject().put("action", if (gLower.contains("게임")) "list_games" else "list_all").put("app_name", "게임"),
                spokenReply = spoken
            )
        }

        // 9. Match any visible UI element on an external app screen by text/description (never self-clicking Hanson's own UI)
        val matchedElement = if (IntentDecomposerAndRouter.isExternalAppSnapshot(snapshot)) {
            snapshot.indexedElements
                .filterNot { IntentDecomposerAndRouter.isHansonOwnElement(it) }
                .firstOrNull { el ->
                    (el.text.length >= 2 && g.contains(el.text, ignoreCase = true)) ||
                        (el.contentDesc.length >= 2 && g.contains(el.contentDesc, ignoreCase = true))
                } ?: AgentToolExecutionHarness.findBestMatchingElement(snapshot, g)
        } else null
        if (matchedElement != null) {
            val label = matchedElement.text.ifBlank { matchedElement.contentDesc }.substringBefore(" - ").take(24)
            val spoken = "'$label' 항목을 바로 눌렀어요."
            return ModelTurnDecision(
                observation = "'$label' 요소 탭",
                subGoals = listOf(SubGoalItem("'$label' 탭 실행", SubGoalStatus.COMPLETED)),
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
