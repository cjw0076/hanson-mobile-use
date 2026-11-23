package com.hanson.mobileuse.agent

import com.hanson.mobileuse.accessibility.AtomicSnapshot
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ModelTurnDecision(
    val observation: String,
    val subGoals: List<SubGoalItem>,
    val toolName: String,
    val toolArgs: JSONObject,
    val spokenReply: String = ""
)

data class GroundedWebAnswer(
    val succeeded: Boolean,
    val answerKo: String,
    val citations: List<Pair<String, String>> = emptyList(),
    val providerBadge: String = "",
    val errorReason: String? = null
)

/**
 * Conversational Multimodal VLM, Native Web Grounding Q&A & Full-Control Mobile Operator (`GeminiMultimodalClient`).
 *
 * Combines real-time natural Korean dialogue (`spokenReply`), native web-grounded Q&A
 * (`answerTextOrGroundedWebQuery`), and full device control (`click`, `long_press`, `input_text`,
 * `swipe`, `drag_and_drop`, `haptic_trajectory`, `press_key`, `manage_app`, `reply_notification`,
 * `control_device_setting`, `ask_user`, `complete_task`) across Google Gemini, Anthropic Claude,
 * OpenAI, OpenRouter, Custom OpenAI-compatible endpoints, and the Laptop CLI Bridge (`:18889`).
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
         * Safely reads a string field from [obj], returning `""` when the key is missing or explicitly JSON `null`.
         * Prevents Android `org.json.JSONObject.optString` from returning the literal 4-char string `"null"`.
         */
        fun optNonNullString(obj: JSONObject, key: String): String {
            return if (obj.isNull(key)) "" else obj.optString(key, "")
        }

        /**
         * Recursively normalizes uppercase Gemini OpenAPI schema types (`OBJECT`, `STRING`, `INTEGER`,
         * `BOOLEAN`, `NUMBER`, `ARRAY`) to standard lowercase JSON Schema types.
         */
        internal fun lowercaseSchemaTypes(schema: JSONObject): JSONObject {
            val out = JSONObject()
            val keys = schema.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = schema.opt(key)
                when {
                    key == "type" && value is String -> out.put(key, value.lowercase())
                    value is JSONObject -> out.put(key, lowercaseSchemaTypes(value))
                    value is JSONArray -> {
                        val arr = JSONArray()
                        for (i in 0 until value.length()) {
                            val elem = value.opt(i)
                            if (elem is JSONObject) {
                                arr.put(lowercaseSchemaTypes(elem))
                            } else {
                                arr.put(elem)
                            }
                        }
                        out.put(key, arr)
                    }
                    else -> out.put(key, value)
                }
            }
            return out
        }

        /**
         * Parses a single or multi-provider API key string (line/comma/semicolon/space separated).
         * Supports simultaneous Google AI Studio (`AIza...`), Anthropic (`sk-ant-...`),
         * OpenRouter (`sk-or-...`), and OpenAI (`sk-...`) keys for role-specialized routing.
         *
         * Line-oriented parser skips `#` comment lines, strips `KEY=` env/property prefixes,
         * and never assigns header noise (`Bearer`, `OPENAI_API_KEY`, etc.) to `geminiKey`.
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
                private val deadKeysCache = ConcurrentHashMap<String, Long>()
                private const val DEAD_KEY_COOLDOWN_MS = 10 * 60 * 1000L

                private val modelRateLimitCache = ConcurrentHashMap<String, Long>()
                private const val MODEL_RATE_LIMIT_COOLDOWN_MS = 45_000L

                private fun rateLimitCacheKey(key: String, model: String): String =
                    "${key.trim()}::${model.trim().lowercase()}"

                fun isKeyTemporarilyDead(key: String): Boolean {
                    if (key.isBlank()) return false
                    val ts = deadKeysCache[key] ?: return false
                    val stillDead = (System.currentTimeMillis() - ts) < DEAD_KEY_COOLDOWN_MS
                    if (!stillDead) {
                        deadKeysCache.remove(key)
                    }
                    return stillDead
                }

                fun markKeyDead(key: String) {
                    if (key.isNotBlank()) {
                        deadKeysCache[key] = System.currentTimeMillis()
                    }
                }

                fun clearDeadKeys() {
                    deadKeysCache.clear()
                    modelRateLimitCache.clear()
                }

                fun isModelRateLimited(key: String, model: String): Boolean {
                    if (key.isBlank() || model.isBlank()) return false
                    val cacheKey = rateLimitCacheKey(key, model)
                    val ts = modelRateLimitCache[cacheKey] ?: return false
                    val stillLimited = (System.currentTimeMillis() - ts) < MODEL_RATE_LIMIT_COOLDOWN_MS
                    if (!stillLimited) {
                        modelRateLimitCache.remove(cacheKey)
                    }
                    return stillLimited
                }

                fun markModelRateLimited(key: String, model: String) {
                    if (key.isNotBlank() && model.isNotBlank()) {
                        modelRateLimitCache[rateLimitCacheKey(key, model)] = System.currentTimeMillis()
                    }
                }

                fun clearRateLimits() {
                    modelRateLimitCache.clear()
                }

                private val ENV_VAR_PREFIX_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")
                private val NOISE_TOKENS = setOf(
                    "bearer",
                    "basic",
                    "token",
                    "apikey",
                    "api_key",
                    "authorization",
                    "authorization:",
                    "export",
                    "set",
                    "key",
                    "gemini_api_key",
                    "openai_api_key",
                    "anthropic_api_key",
                    "openrouter_api_key",
                    "claude_api_key",
                    "google_api_key",
                    "x-goog-api-key",
                    "x-api-key",
                    "x-goog-api-key:",
                    "x-api-key:"
                )

                fun parse(rawKeyString: String): MultiProviderApiKeySet {
                    val cleanedRaw = rawKeyString.replace("\uFEFF", "")
                    var gemini: String? = null
                    var anthropic: String? = null
                    var openRouter: String? = null
                    var openAi: String? = null

                    for (rawLine in cleanedRaw.lineSequence()) {
                        val trimmedLine = rawLine.trim()
                        if (trimmedLine.isEmpty() || trimmedLine.startsWith("#") || trimmedLine.startsWith("//")) {
                            continue
                        }
                        val withoutInlineComment = trimmedLine.replace(Regex("\\s+#.*$"), "").trim()
                        if (withoutInlineComment.isEmpty()) continue

                        val segments = withoutInlineComment.split(',', ';')
                        for (segment in segments) {
                            val cleanSeg = segment.trim()
                                .removePrefix("export ")
                                .removePrefix("EXPORT ")
                                .trim()
                            if (cleanSeg.isEmpty()) continue

                            val afterEquals = stripEnvPrefixIfPresent(cleanSeg)
                            val tokens = afterEquals.split(Regex("\\s+"))
                                .map { stripEnvPrefixIfPresent(it.trim().trim('"', '\'', ',', ';')) }
                                .map { it.trim().trim('"', '\'', ',', ';') }
                                .filter { it.isNotEmpty() }

                            for (token in tokens) {
                                val lower = token.lowercase()
                                if (lower in NOISE_TOKENS ||
                                    lower.endsWith("_api_key") ||
                                    lower.endsWith("_key") ||
                                    lower.endsWith(":") ||
                                    token == "="
                                ) {
                                    continue
                                }
                                when {
                                    token.startsWith("sk-ant-", ignoreCase = true) -> anthropic = token
                                    token.startsWith("sk-or-", ignoreCase = true) -> openRouter = token
                                    token.startsWith("AIza") || token.startsWith("AQ.") -> gemini = token
                                    token.startsWith("sk-") -> openAi = token
                                    else -> {
                                        // Intentionally ignore unrecognized words (e.g. Bearer, OPENAI_API_KEY)
                                        // so noise is never misassigned to geminiKey.
                                    }
                                }
                            }
                        }
                    }

                    return MultiProviderApiKeySet(
                        geminiKey = gemini,
                        anthropicKey = anthropic,
                        openRouterKey = openRouter,
                        openAiKey = openAi
                    )
                }

                private fun stripEnvPrefixIfPresent(raw: String): String {
                    val trimmed = raw.trim()
                    if (trimmed.startsWith("AIza") ||
                        trimmed.startsWith("AQ.") ||
                        trimmed.startsWith("sk-ant-", ignoreCase = true) ||
                        trimmed.startsWith("sk-or-", ignoreCase = true) ||
                        trimmed.startsWith("sk-")
                    ) {
                        return trimmed
                    }
                    if (!trimmed.contains("=")) return trimmed
                    val lhs = trimmed.substringBefore("=").trim()
                    val rhs = trimmed.substringAfter("=").trim()
                    return if (ENV_VAR_PREFIX_REGEX.matches(lhs)) {
                        rhs.trim('"', '\'')
                    } else {
                        trimmed
                    }
                }
            }
        }

        /**
         * Curated VLM / Chat Model Picker Catalog containing ONLY live chat & multimodal VLM models
         * (excludes TTS, STT, Image, Video, Live Audio, retired 404 models, and unusable managed-agent previews).
         */
        val VLM_PICKER_MODELS: List<Pair<String, String>> = listOf(
            "auto-multi-model" to "[멀티모델 자동 라우팅] 턴·복잡도별 최적 VLM(Gemini / Claude / GPT / OpenRouter) 동적 선택",
            "gemini-3.8-flash" to "[Gemini 3.8 Flash · 권장 기본] 최신 AI Studio 키(AQ./AIza) 기본 VLM · 네이티브 구글 검색",
            "gemini-3.5-flash-lite" to "[Gemini 3.5 Flash Lite] 초저지연·초저비용 고속 모바일 제어 모델",
            "gemini-3.6-flash" to "[Gemini 3.6 Flash] 범용 멀티모달 에이전트 Flash",
            "gemini-3.1-pro-preview" to "[Gemini 3.1 Pro · Preview] 고난도 다단계 추론 플래그십 프리뷰",
            "gemini-2.5-flash" to "[Gemini 2.5 Flash · 기존 키 호환] 기존 AI Studio 프로젝트용 안정 VLM",
            "gemini-2.5-flash-lite" to "[Gemini 2.5 Flash Lite · 기존 키 호환] 경량 안정 VLM",
            "gemini-3.1-flash-lite" to "[Gemini 3.1 Flash Lite · Preview] 경량 멀티모달 VLM",
            "gemini-3-flash-preview" to "[Gemini 3 Flash · Preview] 고속 멀티모달 프리뷰",
            "gemini-4-argon" to "[Gemini 4 · Preview] 고차원 계획 및 멀티모달 추론",
            "gemini-robotics-er-2-preview" to "[Embodied Reasoning · Preview] 공간 좌표(Spatial) · 멀티스텝 제어 VLM",
            "claude-sonnet-4-6" to "[Anthropic · Sonnet] 정밀 UI 그라운딩 및 멀티모달 오퍼레이터 (sk-ant-... / sk-or-...)",
            "claude-opus-4-6" to "[Anthropic · Opus] 고난도 다단계 계획 및 복합 문제 해결 (sk-ant-... / sk-or-...)",
            "gpt-4.1" to "[OpenAI / Custom URL] 범용 멀티모달 UI 및 도구 호출 에이전트 (sk-... / OpenRouter / Ollama)",
            "gpt-4.1-mini" to "[OpenAI / Custom URL] 초고속 경량 비전·도구 호출 모델 (sk-... / OpenRouter)",
            "cli-subscription-bridge" to "[로컬 CLI 구독 브리지 · API 비용 $0] 노트북 Claude Code / Codex / Gemini CLI 연동",
            "gemma-4-26b-a4b-it" to "[온디바이스 로컬] 온디바이스 / 로컬 서버(Ollama · vLLM) 오프라인 추론"
        )

        /**
         * Full AI Studio & Multi-Provider Model Catalog (live VLM models + Live Audio/Speech/Media specialists).
         * Retired 404 models and unusable managed-agent aliases are excluded.
         */
        val AISTUDIO_OFFICIAL_MODELS: List<Pair<String, String>> = VLM_PICKER_MODELS + listOf(
            "gemini-3.8-live" to "[Live Audio] 초저지연 실시간 양방향 음성·화면 에이전트",
            "gemini-3.8-live-extended-thinking" to "[Live Audio · 고추론] 실시간 음성 대화 + 심층 추론",
            "gemini-3.8-flash-tts" to "[Audio TTS] 자연스러운 음성 합성",
            "gemini-3.8-flash-lite-tts" to "[Audio TTS · Lite] 초고속 실시간 음성 합성",
            "gemini-3.5-transcribe" to "[Speech-to-Text] 다국어 음성 인식 STT",
            "gemini-3.5-transcribe-live" to "[Speech-to-Text · Live] 실시간 스트리밍 STT",
            "gemini-nano-banana-2.1" to "[Image] 대화형 이미지 생성 및 편집",
            "gemini-omni-1.1-flash" to "[Video] 비디오 생성 및 편집"
        )

        fun normalize2026ModelName(rawModel: String): String {
            val trimmed = rawModel.trim()
            if (trimmed.isEmpty()) return DEFAULT_MODEL

            return when {
                trimmed.equals(AUTO_MULTI_MODEL, ignoreCase = true) ||
                    trimmed.endsWith("-multi-model-auto", ignoreCase = true) -> AUTO_MULTI_MODEL
                // Live stable 2.5 models MUST NOT be silently rewritten into preview aliases
                trimmed == "gemini-2.5-flash" -> "gemini-2.5-flash"
                trimmed == "gemini-2.5-flash-lite" -> "gemini-2.5-flash-lite"
                // Retired Pro legacy IDs -> live Pro preview
                trimmed == "gemini-2.5-pro" ||
                    trimmed == "gemini-3-pro-preview" -> "gemini-3.1-pro-preview"
                // Retired legacy 1.5 / 2.0 / deprecated snapshot IDs -> live DEFAULT_MODEL (gemini-2.5-flash)
                trimmed == "gemini-2.0-flash" ||
                    trimmed == "gemini-2.0-flash-lite" ||
                    trimmed == "gemini-2.5-flash-preview-09-2025" ||
                    trimmed == "gemini-2.5-computer-use-preview-10-2025" ||
                    trimmed.startsWith("gemini-2.0") ||
                    trimmed.startsWith("gemini-1.5") -> DEFAULT_MODEL
                // Preview alias migrations
                trimmed == "gemini-3.1-flash-lite-preview" -> "gemini-3.1-flash-lite"
                trimmed == "gemini-robotics-er-1.6-preview" -> "gemini-robotics-er-2-preview"
                trimmed == "gemini-3.1-flash-live-preview" ||
                    trimmed == "gemini-2.5-flash-native-audio-preview-12-2025" -> "gemini-3.8-live"
                trimmed == "gemini-3.1-flash-tts-preview" ||
                    trimmed == "gemini-2.5-flash-preview-tts" ||
                    trimmed == "gemini-2.5-pro-preview-tts" -> "gemini-3.8-flash-tts"
                trimmed == "gemini-2.5-flash-image" -> "gemini-nano-banana-2.1"
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
You are Hanson, a precise, natural Korean mobile AI assistant and full-control Android operator.
You observe both the live UI accessibility element list AND a Set-of-Marks (SoM) screenshot where interactive elements are highlighted with numbered `[index]` badges matching `Visible UI Elements`.

On EVERY turn, output:
1. `SPOKEN: <1~2 natural Korean sentences clearly answering the user or stating your current step>`
2. `OBSERVATION: <concise Korean summary of current screen, obstacles, and goal progress>`
3. `PLAN:` followed by markdown checklist items (`[x]`, `[/]`, `[ ]`).
4. Invoke ONE tool call matching the user's intent.

Mode Separation (Q&A vs GUI Control):
- Pure Q&A, Information, Summary, or Screen Explanation: If the user asks a general knowledge question, asks to summarize/explain what is on screen, or only wants a direct answer without operating the device, answer directly in clear Korean and call `complete_task(summary=...)` in 1 turn.
- Mobile GUI & OS Control: When the user asks to perform an action on the phone (open an app, tap, search, type, toggle settings, send a message), execute the needed tool step-by-step and ONLY call `complete_task(summary=...)` AFTER observing on screen that the target state is genuinely achieved.

Visual Grounding & Safety Rules:
- Unified Coordinate Schema: Always prefer passing `element_index` (matching `[index]` in `Visible UI Elements` and SoM badges) to `click`, `long_press`, or `input_text`. If a visual target has no accessibility node, pass normalized `0..1000` coordinates using `norm_x` and `norm_y`.
- Clear Obstacles First: If a promo popup, bottom sheet ad, permission dialog, or tooltip (`닫기`, `오늘 그만 보기`, `다음에`, `X`) covers the target UI, dismiss that obstacle FIRST before tapping behind it.
- Ambiguity & Sensitive Confirmation: Before payments, transfers, irreversible submissions, or ambiguous recipients/choices, pause and call `ask_user(question=...)`.
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
                val finishReason = optNonNullString(firstCandidate, "finishReason")
                if (finishReason == "SAFETY") {
                    return resolveSafetyNotice(finishReason)
                }
                val content = firstCandidate.optJSONObject("content") ?: return ""
                val parts = content.optJSONArray("parts") ?: return ""
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    sb.append(optNonNullString(p, "text"))
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
            return (trimmed.startsWith("AIzaSy") || trimmed.startsWith("AQ.")) && trimmed.length >= 20
        }

        /**
         * Parses an OpenAI `/v1/responses` JSON body (including `web_search_preview` `url_citation` annotations)
         * into a clean [GroundedWebAnswer].
         */
        fun parseOpenAiResponsesWebSearchBody(bodyStr: String, modelName: String): GroundedWebAnswer {
            val root = JSONObject(bodyStr)
            val textBuilder = StringBuilder()
            val citations = mutableListOf<Pair<String, String>>()
            val seenUrls = mutableSetOf<String>()
            val outputArr = root.optJSONArray("output")
            if (outputArr != null) {
                for (i in 0 until outputArr.length()) {
                    val outItem = outputArr.optJSONObject(i) ?: continue
                    if (optNonNullString(outItem, "type") != "message") continue
                    val contentArr = outItem.optJSONArray("content") ?: continue
                    for (j in 0 until contentArr.length()) {
                        val block = contentArr.optJSONObject(j) ?: continue
                        if (optNonNullString(block, "type") != "output_text") continue
                        val text = optNonNullString(block, "text")
                        if (text.isNotEmpty()) {
                            if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                            textBuilder.append(text)
                        }
                        val annotations = block.optJSONArray("annotations")
                        if (annotations != null) {
                            for (k in 0 until annotations.length()) {
                                val ann = annotations.optJSONObject(k) ?: continue
                                if (optNonNullString(ann, "type") == "url_citation") {
                                    val url = optNonNullString(ann, "url").trim()
                                    val title = optNonNullString(ann, "title").trim().ifBlank { url }
                                    if (url.startsWith("http", ignoreCase = true) && seenUrls.add(url)) {
                                        citations.add(title to url)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val answerText = textBuilder.toString().trim()
            if (answerText.isEmpty()) {
                throw IllegalStateException("OpenAI Responses API returned empty output_text")
            }
            return GroundedWebAnswer(
                succeeded = true,
                answerKo = answerText,
                citations = citations,
                providerBadge = "OPENAI-WEB (${modelName.uppercase()})"
            )
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun logCloudError(message: String, err: Throwable? = null) {
        runCatching {
            if (err != null) {
                android.util.Log.e("HansonVLM", message, err)
            } else {
                android.util.Log.e("HansonVLM", message)
            }
        }
    }

    private fun composeSystemInstruction(systemContext: String): String {
        val extra = systemContext.trim()
        return if (extra.isEmpty()) {
            SYSTEM_INSTRUCTION
        } else {
            "$SYSTEM_INSTRUCTION\n\nRuntime Context & Memory:\n$extra"
        }
    }

    private fun isCliBridgeModel(modelName: String): Boolean {
        val clean = modelName.trim()
        return clean.equals("cli-subscription-bridge", ignoreCase = true) ||
            clean.equals("laptop-cli-subscription", ignoreCase = true)
    }

    private fun isTextOrVlmGeminiModel(modelName: String): Boolean {
        val m = modelName.trim().lowercase()
        if (!m.startsWith("gemini-")) return false
        return !m.contains("-tts") &&
            !m.contains("-live") &&
            !m.contains("transcribe") &&
            !m.contains("banana") &&
            !m.contains("omni")
    }

    /**
     * Classifies and records ANY cloud/custom API failure into [lastCloudApiErrorReason] and Logcat.
     * Enforces strict 429 vs 402 separation:
     * - HTTP 429 (even if body contains RESOURCE_EXHAUSTED): per-(key, model) 45s cooldown ONLY.
     * - HTTP 402, HTTP 401, or API_KEY_INVALID: mark key temporarily dead.
     * - HTTP 400, 403, 404, 5xx, timeouts, network I/O, JSON parse errors, etc.: always recorded clearly.
     */
    private fun handleCloudProviderFailure(
        providerTag: String,
        targetModel: String,
        providerKey: String?,
        err: Throwable
    ): String {
        val msg = err.message.orEmpty()
        val upper = msg.uppercase()
        val diagnostic = when {
            msg.contains("HTTP 429") -> {
                if (!providerKey.isNullOrBlank()) {
                    MultiProviderApiKeySet.markModelRateLimited(providerKey, targetModel)
                }
                "HTTP 429 ($providerTag/$targetModel 요청 한도 초과 — 45초 쿨다운 후 자동 복구)"
            }
            msg.contains("HTTP 402") -> {
                if (!providerKey.isNullOrBlank()) {
                    MultiProviderApiKeySet.markKeyDead(providerKey)
                }
                "HTTP 402 ($providerTag 선불 크레딧 소진 — 결제 또는 무료 API 키 확인 필요)"
            }
            msg.contains("HTTP 401") || upper.contains("API_KEY_INVALID") || upper.contains("INVALID_API_KEY") -> {
                if (!providerKey.isNullOrBlank()) {
                    MultiProviderApiKeySet.markKeyDead(providerKey)
                }
                "HTTP 401 ($providerTag API 키가 유효하지 않거나 만료되었습니다)"
            }
            upper.contains("RESOURCE_EXHAUSTED") -> {
                if (!providerKey.isNullOrBlank()) {
                    MultiProviderApiKeySet.markModelRateLimited(providerKey, targetModel)
                }
                "HTTP 429 ($providerTag/$targetModel 할당량 일시 초과 — 45초 쿨다운)"
            }
            msg.contains("HTTP 403") -> {
                "HTTP 403 ($providerTag/$targetModel 접근 권한 또는 지역 제한 오류)"
            }
            msg.contains("HTTP 404") -> {
                "HTTP 404 ($providerTag 모델 '$targetModel'을 찾을 수 없습니다)"
            }
            msg.contains("HTTP 400") -> {
                "HTTP 400 ($providerTag 요청 형식/파라미터 오류: ${msg.substringAfter("HTTP 400:").trim().take(100)})"
            }
            Regex("HTTP 5\\d\\d").containsMatchIn(msg) -> {
                "$providerTag 서버 일시 오류 (${msg.take(100)})"
            }
            err is SocketTimeoutException -> {
                "$providerTag 응답 시간 초과(Timeout) — 네트워크 상태 또는 모델 부하를 확인해 주세요"
            }
            err is UnknownHostException -> {
                "$providerTag 네트워크 연결 실패(DNS 조회 불가) — 인터넷 연결을 확인해 주세요"
            }
            err is IOException -> {
                "$providerTag 네트워크 통신 오류: ${msg.ifBlank { err.javaClass.simpleName }.take(100)}"
            }
            err is JSONException -> {
                "$providerTag 응답 JSON 파싱 오류: ${msg.ifBlank { "잘못된 JSON 형식" }.take(100)}"
            }
            else -> {
                "$providerTag 호출 실패 ($targetModel): ${msg.ifBlank { err.javaClass.simpleName }.take(120)}"
            }
        }

        lastCloudApiErrorReason = diagnostic
        logCloudError("[$providerTag/$targetModel] $diagnostic | raw=${msg.take(240)}", err)
        return diagnostic
    }

    /**
     * Performs an immediate live connection health check against the ACTUAL requested model and endpoint
     * (Custom OpenAI-Compatible Base URL, Laptop CLI Bridge, Anthropic, OpenRouter, OpenAI, or Gemini).
     */
    fun verifyConnectionHealth(
        apiKey: String,
        modelName: String = DEFAULT_MODEL,
        customBaseUrl: String = "",
        bridgeToken: String = ""
    ): ApiConnectionCheckResult {
        val startMs = System.currentTimeMillis()
        MultiProviderApiKeySet.clearDeadKeys()
        val cleanCustomUrl = customBaseUrl.trim()
        val normalizedModel = normalize2026ModelName(modelName)

        if (isCliBridgeModel(normalizedModel) || (apiKey.isBlank() && cleanCustomUrl.isBlank())) {
            return try {
                val reqBuilder = Request.Builder()
                    .url("http://127.0.0.1:18889/health")
                    .get()
                if (bridgeToken.isNotBlank()) {
                    reqBuilder.addHeader("X-Bridge-Token", bridgeToken.trim())
                }
                httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val dt = System.currentTimeMillis() - startMs
                    if (resp.isSuccessful) {
                        lastCloudApiErrorReason = null
                        ApiConnectionCheckResult(true, "CLI-BRIDGE", resp.code, "노트북 CLI 브리지(:18889) 연결 정상 (${dt}ms)", dt)
                    } else {
                        val msg = "CLI 브리지 응답 오류 (HTTP ${resp.code})"
                        lastCloudApiErrorReason = msg
                        logCloudError("Health check CLI-BRIDGE failed: $msg")
                        ApiConnectionCheckResult(false, "CLI-BRIDGE", resp.code, msg, dt)
                    }
                }
            } catch (e: Exception) {
                val dt = System.currentTimeMillis() - startMs
                val msg = "API 키 미입력 및 노트북 CLI 브리지(:18889) 미실행"
                lastCloudApiErrorReason = msg
                logCloudError("Health check CLI-BRIDGE unreachable: ${e.message}", e)
                ApiConnectionCheckResult(false, "NONE", 0, msg, dt)
            }
        }

        if (cleanCustomUrl.isNotEmpty()) {
            val endpoint = normalizeChatCompletionsEndpoint(cleanCustomUrl)
            val testModel = if (normalizedModel == AUTO_MULTI_MODEL || normalizedModel.isBlank()) "gpt-4.1-mini" else normalizedModel
            val payload = JSONObject().apply {
                put("model", testModel)
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
                        ApiConnectionCheckResult(true, "CUSTOM-OPENAI", resp.code, "커스텀 엔드포인트 ($testModel) 연결 정상 (${dt}ms)", dt)
                    } else {
                        val msg = formatHttpStatusKo("Custom API ($testModel)", resp.code)
                        lastCloudApiErrorReason = msg
                        logCloudError("Health check CUSTOM-OPENAI failed: $msg")
                        ApiConnectionCheckResult(false, "CUSTOM-OPENAI", resp.code, msg, dt)
                    }
                }
            } catch (e: Exception) {
                val dt = System.currentTimeMillis() - startMs
                val msg = "커스텀 URL 연결 실패: ${e.message?.take(60)}"
                lastCloudApiErrorReason = msg
                logCloudError("Health check CUSTOM-OPENAI error: $msg", e)
                ApiConnectionCheckResult(false, "CUSTOM-OPENAI", 0, msg, dt)
            }
        }

        val keySet = MultiProviderApiKeySet.parse(apiKey)
        return try {
            val preferredProvider = when {
                normalizedModel.startsWith("claude-", ignoreCase = true) && !keySet.anthropicKey.isNullOrBlank() -> "ANTHROPIC"
                (normalizedModel.startsWith("gpt-", ignoreCase = true) ||
                    normalizedModel.startsWith("o3", ignoreCase = true) ||
                    normalizedModel.startsWith("o4", ignoreCase = true)) && !keySet.openAiKey.isNullOrBlank() -> "OPENAI"
                normalizedModel.contains("/") && !keySet.openRouterKey.isNullOrBlank() -> "OPENROUTER"
                normalizedModel.startsWith("gemini-", ignoreCase = true) && !keySet.geminiKey.isNullOrBlank() -> "GEMINI"
                !keySet.geminiKey.isNullOrBlank() -> "GEMINI"
                !keySet.anthropicKey.isNullOrBlank() -> "ANTHROPIC"
                !keySet.openRouterKey.isNullOrBlank() -> "OPENROUTER"
                !keySet.openAiKey.isNullOrBlank() -> "OPENAI"
                else -> "NONE"
            }

            when (preferredProvider) {
                "ANTHROPIC" -> {
                    val key = keySet.anthropicKey.orEmpty()
                    val testModel = if (normalizedModel.startsWith("claude-", ignoreCase = true)) normalizedModel else "claude-sonnet-4-6"
                    val payload = JSONObject().apply {
                        put("model", testModel)
                        put("max_tokens", 8)
                        put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
                    }
                    val req = Request.Builder()
                        .url("https://api.anthropic.com/v1/messages")
                        .addHeader("x-api-key", key)
                        .addHeader("anthropic-version", "2023-06-01")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        val dt = System.currentTimeMillis() - startMs
                        if (resp.isSuccessful) {
                            lastCloudApiErrorReason = null
                            ApiConnectionCheckResult(true, "ANTHROPIC", resp.code, "Anthropic Claude ($testModel) 연결 정상 (${dt}ms)", dt)
                        } else {
                            val msg = formatHttpStatusKo("Anthropic ($testModel)", resp.code)
                            lastCloudApiErrorReason = msg
                            logCloudError("Health check ANTHROPIC failed: $msg")
                            ApiConnectionCheckResult(false, "ANTHROPIC", resp.code, msg, dt)
                        }
                    }
                }
                "OPENROUTER", "OPENAI" -> {
                    val isOr = preferredProvider == "OPENROUTER"
                    val key = (if (isOr) keySet.openRouterKey else keySet.openAiKey).orEmpty()
                    val endpoint = if (isOr) "https://openrouter.ai/api/v1/chat/completions" else "https://api.openai.com/v1/chat/completions"
                    val testModel = when {
                        isOr && normalizedModel.contains("/") -> normalizedModel
                        isOr -> "google/gemini-2.5-flash"
                        normalizedModel.startsWith("gpt-", ignoreCase = true) ||
                            normalizedModel.startsWith("o3", ignoreCase = true) ||
                            normalizedModel.startsWith("o4", ignoreCase = true) -> normalizedModel
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
                            ApiConnectionCheckResult(true, label.uppercase(), resp.code, "$label ($testModel) 연결 정상 (${dt}ms)", dt)
                        } else {
                            val msg = formatHttpStatusKo("$label ($testModel)", resp.code)
                            lastCloudApiErrorReason = msg
                            logCloudError("Health check $label failed: $msg")
                            ApiConnectionCheckResult(false, label.uppercase(), resp.code, msg, dt)
                        }
                    }
                }
                "GEMINI" -> {
                    val key = keySet.geminiKey.orEmpty()
                    val requestedGemini = if (isTextOrVlmGeminiModel(normalizedModel)) normalizedModel else DEFAULT_MODEL
                    val testModels = listOf(requestedGemini, "gemini-3.8-flash", "gemini-3.5-flash-lite").distinct()
                    val payload = buildMultimodalGenerationPayload("ping")
                    var lastCode = 0
                    var lastMsg = ""
                    for (candidateModel in testModels) {
                        val url = "https://generativelanguage.googleapis.com/v1beta/models/$candidateModel:generateContent"
                        val req = Request.Builder()
                            .url(url)
                            .addHeader("x-goog-api-key", key)
                            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                            .build()
                        httpClient.newCall(req).execute().use { resp ->
                            val dt = System.currentTimeMillis() - startMs
                            lastCode = resp.code
                            if (resp.isSuccessful) {
                                lastCloudApiErrorReason = null
                                return ApiConnectionCheckResult(true, "GEMINI", resp.code, "Google Gemini ($candidateModel) 연결 정상 (${dt}ms)", dt)
                            }
                            lastMsg = formatHttpStatusKo("Gemini ($candidateModel)", resp.code)
                            // Only fall back to gemini-3.8-flash / 3.5-flash-lite when a legacy model returns 404 on new AQ. keys
                            if (resp.code != 404) {
                                lastCloudApiErrorReason = lastMsg
                                logCloudError("Health check GEMINI failed: $lastMsg")
                                return ApiConnectionCheckResult(false, "GEMINI", resp.code, lastMsg, dt)
                            }
                        }
                    }
                    val dt = System.currentTimeMillis() - startMs
                    lastCloudApiErrorReason = lastMsg
                    logCloudError("Health check GEMINI failed: $lastMsg")
                    ApiConnectionCheckResult(false, "GEMINI", lastCode, lastMsg, dt)
                }
                else -> {
                    val msg = "유효한 API 키 형식이 아닙니다 (AQ...., AIza..., sk-ant-..., sk-or-..., sk-...)"
                    lastCloudApiErrorReason = msg
                    logCloudError("Health check failed: $msg")
                    ApiConnectionCheckResult(false, "NONE", 0, msg, 0L)
                }
            }
        } catch (e: Exception) {
            val dt = System.currentTimeMillis() - startMs
            val msg = "네트워크 오류: ${e.message?.take(60)}"
            lastCloudApiErrorReason = msg
            logCloudError("Health check network error: $msg", e)
            ApiConnectionCheckResult(false, "NETWORK", 0, msg, dt)
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
        customBaseUrl: String = "",
        systemContext: String = "",
        bridgeToken: String = ""
    ): ModelTurnDecision {
        val useCliBridge = preferLaptopCliBridge || isCliBridgeModel(modelName)

        if (useCliBridge) {
            val bridgeDecision = tryCallLaptopCliBridge(
                bridgeUrl = laptopBridgeUrl,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            if (bridgeDecision != null) {
                lastCloudApiErrorReason = null
                return bridgeDecision
            }
        }

        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() && customBaseUrl.isBlank()) {
            if (useCliBridge) {
                lastCloudApiErrorReason = "노트북 CLI 브리지(:18889)에 연결할 수 없으며 클라우드 API 키도 설정되지 않았습니다"
                logCloudError(lastCloudApiErrorReason.orEmpty())
            } else {
                lastCloudApiErrorReason = null
            }
            return inferLocalOnDeviceAction(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        }

        val normalizedModel = normalize2026ModelName(modelName)
        val cascaded = decideNextActionWithCascade(
            apiKey = cleanKey,
            candidateModels = listOf(normalizedModel, DEFAULT_MODEL, "gemini-3.8-flash", "gemini-3.5-flash-lite").distinct(),
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan,
            chatHistory = chatHistory,
            customBaseUrl = customBaseUrl,
            systemContext = systemContext,
            bridgeToken = bridgeToken,
            laptopBridgeUrl = laptopBridgeUrl
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
        customBaseUrl: String = "",
        systemContext: String = "",
        bridgeToken: String = "",
        laptopBridgeUrl: String = "http://127.0.0.1:18889/decide"
    ): Pair<String, ModelTurnDecision>? {
        val cleanCustomUrl = customBaseUrl.trim()
        val explicitBridgeRequested = candidateModels.firstOrNull()?.let { isCliBridgeModel(it) } == true ||
            cleanCustomUrl.contains("18889")

        if (explicitBridgeRequested) {
            val bridgeDecision = tryCallLaptopCliBridge(
                bridgeUrl = laptopBridgeUrl,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            if (bridgeDecision != null) {
                lastCloudApiErrorReason = null
                return "LAPTOP-CLI-BRIDGE" to bridgeDecision
            }
        }

        if (cleanCustomUrl.isNotEmpty() && !cleanCustomUrl.contains("18889")) {
            val customModel = candidateModels
                .firstOrNull { it != AUTO_MULTI_MODEL && !isCliBridgeModel(it) }
                .orEmpty()
                .ifBlank { "gpt-4.1" }
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
                    customBaseUrl = cleanCustomUrl,
                    systemContext = systemContext
                )
            }.onFailure { err ->
                handleCloudProviderFailure("CUSTOM-VLM", customModel, null, err)
            }.getOrNull()
            if (customDecision != null) {
                lastCloudApiErrorReason = null
                return "CUSTOM-VLM" to customDecision
            }
        }

        val keySet = MultiProviderApiKeySet.parse(apiKey)

        if (keySet.hasAnyCloudKey) {
            val expandedCandidates = buildProviderCandidateQueue(candidateModels, keySet)
            var skippedDeadKeyCount = 0
            var skippedRateLimitCount = 0
            var attemptedAny = false

            for ((providerTag, targetModel, providerKey) in expandedCandidates) {
                val effectiveModel = if (providerTag == "GEMINI") normalize2026ModelName(targetModel) else targetModel
                if (MultiProviderApiKeySet.isKeyTemporarilyDead(providerKey)) {
                    skippedDeadKeyCount++
                    continue
                }
                if (MultiProviderApiKeySet.isModelRateLimited(providerKey, effectiveModel)) {
                    skippedRateLimitCount++
                    continue
                }
                attemptedAny = true

                val decision = runCatching {
                    when (providerTag) {
                        "ANTHROPIC" -> callAnthropicMessagesApi(
                            apiKey = providerKey,
                            modelName = effectiveModel,
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory,
                            systemContext = systemContext
                        )
                        "OPENROUTER", "OPENAI" -> callOpenAiCompatibleApi(
                            apiKey = providerKey,
                            modelName = effectiveModel,
                            isOpenRouter = providerTag == "OPENROUTER",
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory,
                            systemContext = systemContext
                        )
                        else -> callGeminiMultimodalApi(
                            apiKey = providerKey,
                            modelName = effectiveModel,
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory,
                            systemContext = systemContext
                        )
                    }
                }.onFailure { err ->
                    handleCloudProviderFailure(providerTag, effectiveModel, providerKey, err)
                }.getOrNull()

                if (decision != null) {
                    lastCloudApiErrorReason = null
                    return effectiveModel.uppercase() to decision
                }
            }

            if (!attemptedAny) {
                val skipReason = when {
                    skippedRateLimitCount > 0 ->
                        "HTTP 429 (모든 후보 모델이 요청 한도 45초 쿨다운 중입니다 — 잠시 후 다시 시도해 주세요)"
                    skippedDeadKeyCount > 0 ->
                        "API 키가 인증/크레딧 오류로 일시 차단 상태입니다 — 설정에서 API 키를 확인하거나 연결 테스트를 눌러 주세요"
                    else ->
                        "실행 가능한 클라우드 모델 후보를 찾지 못했습니다"
                }
                lastCloudApiErrorReason = skipReason
                logCloudError("Cascade skipped all candidates: $skipReason")
            }
        } else if (cleanCustomUrl.isEmpty()) {
            val keyErr = if (apiKey.isBlank()) {
                "AI API 키가 설정되지 않았습니다"
            } else {
                "입력된 API 키 형식을 인식할 수 없습니다 (AIza..., sk-ant-..., sk-or-..., sk-...)"
            }
            if (!explicitBridgeRequested) {
                lastCloudApiErrorReason = keyErr
                logCloudError("Cloud cascade aborted before call: $keyErr")
            }
        }

        // If Cloud API is unavailable, check if Laptop CLI Bridge (:18889) is connected via USB adb reverse
        if (!explicitBridgeRequested) {
            val bridgeFallback = tryCallLaptopCliBridge(
                bridgeUrl = laptopBridgeUrl,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            if (bridgeFallback != null) {
                lastCloudApiErrorReason = null
                return "LAPTOP-CLI-BRIDGE" to bridgeFallback
            }
        } else if (lastCloudApiErrorReason.isNullOrBlank()) {
            val msg = "노트북 CLI 브리지(:18889)에 연결할 수 없습니다 (adb reverse tcp:18889 확인 필요)"
            lastCloudApiErrorReason = msg
            logCloudError(msg)
        }

        return null
    }

    /**
     * 2026 Native Web Grounding & Text-First Q&A Pipeline.
     *
     * Routes directly to:
     * 1. Laptop CLI Bridge (`POST http://127.0.0.1:18889/web/research`) at `$0` extra API cost when selected or when no API key is configured.
     * 2. Google Gemini REST API (`x-goog-api-key`) with native `tools: [{"google_search": {}}, {"url_context": {}}]` and verified `groundingMetadata.groundingChunks[].web` citations.
     * 3. Anthropic Messages API with native `web_search_20250305` tool (with clean text fallback if web_search is rejected).
     * 4. OpenAI / OpenRouter / Custom OpenAI-Compatible `/chat/completions` with null-safe parsing.
     *
     * Never scrapes HTML and never fabricates hardcoded fallback answers.
     */
    fun answerTextOrGroundedWebQuery(
        userGoal: String,
        systemContext: String = "",
        apiKey: String,
        modelName: String = DEFAULT_MODEL,
        customBaseUrl: String = "",
        chatHistory: List<Pair<String, String>> = emptyList(),
        enableWebSearch: Boolean = true,
        bridgeToken: String = "",
        onTextDelta: ((String) -> Unit)? = null
    ): GroundedWebAnswer {
        val cleanGoal = userGoal.trim()
        if (cleanGoal.isEmpty()) {
            val err = "질문 내용이 비어 있습니다."
            lastCloudApiErrorReason = err
            return GroundedWebAnswer(succeeded = false, answerKo = "", errorReason = err)
        }

        val cleanKey = apiKey.trim()
        val cleanCustomUrl = customBaseUrl.trim()
        val normalizedModel = normalize2026ModelName(modelName)
        val qaSystemPrompt = buildQaSystemPrompt(systemContext, enableWebSearch)

        val preferBridgeFirst = isCliBridgeModel(normalizedModel) ||
            cleanCustomUrl.contains("18889") ||
            (cleanKey.isBlank() && cleanCustomUrl.isBlank())

        if (preferBridgeFirst) {
            val bridgeAnswer = tryCallLaptopBridgeWebResearch(
                userGoal = cleanGoal,
                systemContext = systemContext,
                chatHistory = chatHistory,
                enableWebSearch = enableWebSearch,
                bridgeToken = bridgeToken
            )
            if (bridgeAnswer.succeeded) {
                lastCloudApiErrorReason = null
                return bridgeAnswer
            }
            if (cleanKey.isBlank() && (cleanCustomUrl.isBlank() || cleanCustomUrl.contains("18889"))) {
                val reason = bridgeAnswer.errorReason
                    ?: "AI API 키 또는 노트북 브리지(:18889)가 연결되지 않았어요"
                lastCloudApiErrorReason = reason
                logCloudError("answerTextOrGroundedWebQuery failed (bridge-only): $reason")
                return GroundedWebAnswer(succeeded = false, answerKo = "", errorReason = reason)
            }
        }

        // Custom OpenAI-compatible base URL (non-bridge)
        if (cleanCustomUrl.isNotEmpty() && !cleanCustomUrl.contains("18889")) {
            val customModel = if (normalizedModel == AUTO_MULTI_MODEL || isCliBridgeModel(normalizedModel)) {
                "gpt-4.1"
            } else {
                normalizedModel
            }
            val customResult = runCatching {
                callOpenAiTextOrWebApi(
                    apiKey = cleanKey,
                    modelName = customModel,
                    endpoint = normalizeChatCompletionsEndpoint(cleanCustomUrl),
                    providerBadge = "CUSTOM-LLM",
                    qaSystemPrompt = qaSystemPrompt,
                    userGoal = cleanGoal,
                    chatHistory = chatHistory
                )
            }.onFailure { err ->
                handleCloudProviderFailure("CUSTOM-LLM", customModel, null, err)
            }.getOrNull()
            if (customResult != null && customResult.succeeded) {
                lastCloudApiErrorReason = null
                return customResult
            }
        }

        // Cloud multi-provider routing
        val keySet = MultiProviderApiKeySet.parse(cleanKey)
        if (keySet.hasAnyCloudKey) {
            val providerOrder = buildQaProviderOrder(normalizedModel, keySet)
            for (provider in providerOrder) {
                val answer: GroundedWebAnswer? = when (provider) {
                    "GEMINI" -> {
                        val key = keySet.geminiKey.orEmpty()
                        if (key.isBlank() || MultiProviderApiKeySet.isKeyTemporarilyDead(key)) {
                            null
                        } else {
                            callGeminiGroundedWebCascade(
                                apiKey = key,
                                requestedModel = normalizedModel,
                                qaSystemPrompt = qaSystemPrompt,
                                userGoal = cleanGoal,
                                chatHistory = chatHistory,
                                enableWebSearch = enableWebSearch,
                                onTextDelta = onTextDelta
                            )
                        }
                    }
                    "ANTHROPIC" -> {
                        val key = keySet.anthropicKey.orEmpty()
                        val targetModel = if (normalizedModel.startsWith("claude-", ignoreCase = true)) {
                            normalizedModel
                        } else {
                            "claude-sonnet-4-6"
                        }
                        if (key.isBlank() ||
                            MultiProviderApiKeySet.isKeyTemporarilyDead(key) ||
                            MultiProviderApiKeySet.isModelRateLimited(key, targetModel)
                        ) {
                            null
                        } else {
                            runCatching {
                                callAnthropicGroundedWebApi(
                                    apiKey = key,
                                    modelName = targetModel,
                                    qaSystemPrompt = qaSystemPrompt,
                                    userGoal = cleanGoal,
                                    chatHistory = chatHistory,
                                    enableWebSearch = enableWebSearch
                                )
                            }.onFailure { err ->
                                handleCloudProviderFailure("ANTHROPIC", targetModel, key, err)
                            }.getOrNull()
                        }
                    }
                    "OPENROUTER" -> {
                        val key = keySet.openRouterKey.orEmpty()
                        val targetModel = if (normalizedModel.contains("/")) {
                            normalizedModel
                        } else {
                            "google/gemini-2.5-flash"
                        }
                        if (key.isBlank() ||
                            MultiProviderApiKeySet.isKeyTemporarilyDead(key) ||
                            MultiProviderApiKeySet.isModelRateLimited(key, targetModel)
                        ) {
                            null
                        } else {
                            runCatching {
                                callOpenAiTextOrWebApi(
                                    apiKey = key,
                                    modelName = targetModel,
                                    endpoint = "https://openrouter.ai/api/v1/chat/completions",
                                    providerBadge = "OPENROUTER",
                                    qaSystemPrompt = qaSystemPrompt,
                                    userGoal = cleanGoal,
                                    chatHistory = chatHistory
                                )
                            }.onFailure { err ->
                                handleCloudProviderFailure("OPENROUTER", targetModel, key, err)
                            }.getOrNull()
                        }
                    }
                    "OPENAI" -> {
                        val key = keySet.openAiKey.orEmpty()
                        val targetModel = if (normalizedModel.startsWith("gpt-", ignoreCase = true) ||
                            normalizedModel.startsWith("o3", ignoreCase = true) ||
                            normalizedModel.startsWith("o4", ignoreCase = true)
                        ) {
                            normalizedModel
                        } else {
                            "gpt-4.1-mini"
                        }
                        if (key.isBlank() ||
                            MultiProviderApiKeySet.isKeyTemporarilyDead(key) ||
                            MultiProviderApiKeySet.isModelRateLimited(key, targetModel)
                        ) {
                            null
                        } else {
                            runCatching {
                                if (enableWebSearch) {
                                    callOpenAiResponsesWebSearchApi(
                                        apiKey = key,
                                        modelName = targetModel,
                                        qaSystemPrompt = qaSystemPrompt,
                                        userGoal = cleanGoal,
                                        chatHistory = chatHistory
                                    )
                                } else {
                                    callOpenAiTextOrWebApi(
                                        apiKey = key,
                                        modelName = targetModel,
                                        endpoint = "https://api.openai.com/v1/chat/completions",
                                        providerBadge = "OPENAI",
                                        qaSystemPrompt = qaSystemPrompt,
                                        userGoal = cleanGoal,
                                        chatHistory = chatHistory
                                    )
                                }
                            }.recoverCatching { webErr ->
                                if (enableWebSearch) {
                                    logCloudError("OpenAI Responses web_search fallback to chat/completions: ${webErr.message}")
                                    callOpenAiTextOrWebApi(
                                        apiKey = key,
                                        modelName = targetModel,
                                        endpoint = "https://api.openai.com/v1/chat/completions",
                                        providerBadge = "OPENAI",
                                        qaSystemPrompt = qaSystemPrompt,
                                        userGoal = cleanGoal,
                                        chatHistory = chatHistory
                                    )
                                } else {
                                    throw webErr
                                }
                            }.onFailure { err ->
                                handleCloudProviderFailure("OPENAI", targetModel, key, err)
                            }.getOrNull()
                        }
                    }
                    else -> null
                }

                if (answer != null && answer.succeeded) {
                    lastCloudApiErrorReason = null
                    return answer
                }
            }
        } else if (cleanCustomUrl.isEmpty() && !preferBridgeFirst) {
            lastCloudApiErrorReason = "입력된 API 키 형식을 인식할 수 없습니다 (AQ...., AIza..., sk-ant-..., sk-or-..., sk-...)"
            logCloudError("answerTextOrGroundedWebQuery invalid key format")
        }

        // Secondary fallback to Laptop CLI Bridge (:18889/web/research) if cloud failed
        if (!preferBridgeFirst) {
            val fallbackBridge = tryCallLaptopBridgeWebResearch(
                userGoal = cleanGoal,
                systemContext = systemContext,
                chatHistory = chatHistory,
                enableWebSearch = enableWebSearch,
                bridgeToken = bridgeToken
            )
            if (fallbackBridge.succeeded) {
                lastCloudApiErrorReason = null
                return fallbackBridge
            }
        }

        val finalReason = lastCloudApiErrorReason?.takeIf { it.isNotBlank() }
            ?: "AI API 키 또는 노트북 브리지(:18889)가 연결되지 않았어요"
        lastCloudApiErrorReason = finalReason
        logCloudError("answerTextOrGroundedWebQuery exhausted all providers: $finalReason")
        return GroundedWebAnswer(
            succeeded = false,
            answerKo = "",
            citations = emptyList(),
            providerBadge = "",
            errorReason = finalReason
        )
    }

    private fun buildQaSystemPrompt(systemContext: String, enableWebSearch: Boolean): String = buildString {
        append("You are Hanson, a precise, helpful, natural Korean AI assistant.")
        if (enableWebSearch) {
            append(" Ground answers in real-time web search results whenever up-to-date facts, news, weather, prices, schedules, or URLs are relevant. Never fabricate URLs or unverified facts.")
        }
        append(" Separate voice and visual board channels cleanly: start line 1 with 'SPEAK: ' followed by 1-2 warm spoken Korean sentences written strictly for ears (zero asterisks/markdown/URLs; pronounce currency naturally like '83.16달러'). Then on the next lines write the visual board: 1 crisp summary headline, 2-4 concise '• 항목: 핵심 수치/내용' takeaways, and at most one short closing line without nested markdown noise.")
        if (systemContext.contains("공동 드로잉") || systemContext.contains("CODRAW")) {
            append(" When guiding the user on a mobile screen or responding to a user sketch, you may also append optional 0..1 normalized overlay annotations such as 'CODRAW: RING 0.50 0.45 0.11 여기 확인' or 'CODRAW: ARROW 0.25 0.25 0.50 0.45 1단계'.")
        }
        if (systemContext.isNotBlank()) {
            append("\n\nAdditional Context:\n")
            append(systemContext.trim())
        }
    }

    private fun buildQaProviderOrder(normalizedModel: String, keySet: MultiProviderApiKeySet): List<String> {
        val preferred = mutableListOf<String>()
        when {
            normalizedModel.startsWith("claude-", ignoreCase = true) -> preferred.add("ANTHROPIC")
            normalizedModel.startsWith("gpt-", ignoreCase = true) ||
                normalizedModel.startsWith("o3", ignoreCase = true) ||
                normalizedModel.startsWith("o4", ignoreCase = true) -> preferred.add("OPENAI")
            normalizedModel.contains("/") -> preferred.add("OPENROUTER")
            else -> preferred.add("GEMINI")
        }
        val allAvailable = listOf("GEMINI", "ANTHROPIC", "OPENROUTER", "OPENAI").filter { provider ->
            when (provider) {
                "GEMINI" -> !keySet.geminiKey.isNullOrBlank()
                "ANTHROPIC" -> !keySet.anthropicKey.isNullOrBlank()
                "OPENROUTER" -> !keySet.openRouterKey.isNullOrBlank()
                "OPENAI" -> !keySet.openAiKey.isNullOrBlank()
                else -> false
            }
        }
        return (preferred.filter { it in allAvailable } + allAvailable).distinct()
    }

    private fun tryCallLaptopBridgeWebResearch(
        userGoal: String,
        systemContext: String,
        chatHistory: List<Pair<String, String>>,
        enableWebSearch: Boolean,
        bridgeToken: String
    ): GroundedWebAnswer {
        return try {
            val payload = JSONObject().apply {
                put("query", userGoal)
                put("goal", userGoal)
                put("enableWebSearch", enableWebSearch)
                if (systemContext.isNotBlank()) {
                    put("systemContext", systemContext.trim())
                }
                val historyArr = JSONArray()
                chatHistory.takeLast(12).forEach { (role, text) ->
                    if (text.isNotBlank()) {
                        historyArr.put(JSONObject().put("role", role).put("text", text))
                    }
                }
                put("chatHistory", historyArr)
            }
            val reqBuilder = Request.Builder()
                .url("http://127.0.0.1:18889/web/research")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (bridgeToken.isNotBlank()) {
                reqBuilder.addHeader("X-Bridge-Token", bridgeToken.trim())
            }
            val bridgeClient = httpClient.newBuilder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .build()

            bridgeClient.newCall(reqBuilder.build()).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = "노트북 CLI 브리지(:18889/web/research) 오류 (HTTP ${resp.code})"
                    return GroundedWebAnswer(succeeded = false, answerKo = "", errorReason = err)
                }
                val root = JSONObject(bodyStr)
                val answer = optNonNullString(root, "answerKo").trim()
                    .ifBlank { optNonNullString(root, "answer").trim() }
                    .ifBlank { optNonNullString(root, "summary").trim() }
                    .ifBlank { optNonNullString(root, "text").trim() }
                if (answer.isBlank()) {
                    return GroundedWebAnswer(
                        succeeded = false,
                        answerKo = "",
                        errorReason = "노트북 CLI 브리지(:18889)가 빈 응답을 반환했습니다"
                    )
                }
                val citations = mutableListOf<Pair<String, String>>()
                val seen = mutableSetOf<String>()
                val citArr = root.optJSONArray("citations")
                if (citArr != null) {
                    for (i in 0 until citArr.length()) {
                        val item = citArr.opt(i)
                        if (item is JSONObject) {
                            val uri = optNonNullString(item, "uri").trim()
                                .ifBlank { optNonNullString(item, "url").trim() }
                            val title = optNonNullString(item, "title").trim().ifBlank { uri }
                            if (uri.isNotEmpty() && seen.add(uri)) {
                                citations.add(title to uri)
                            }
                        } else if (item is JSONArray && item.length() >= 2) {
                            val title = item.optString(0, "").trim()
                            val uri = item.optString(1, "").trim()
                            if (uri.isNotEmpty() && seen.add(uri)) {
                                citations.add(title.ifBlank { uri } to uri)
                            }
                        }
                    }
                }
                val badge = optNonNullString(root, "providerBadge").trim()
                    .ifBlank { optNonNullString(root, "backend").trim().uppercase() }
                    .ifBlank { "CLI-BRIDGE-WEB" }
                GroundedWebAnswer(
                    succeeded = true,
                    answerKo = answer,
                    citations = citations,
                    providerBadge = badge
                )
            }
        } catch (e: Exception) {
            GroundedWebAnswer(
                succeeded = false,
                answerKo = "",
                errorReason = "노트북 CLI 브리지(:18889) 연결 불가: ${e.message?.take(80) ?: e.javaClass.simpleName}"
            )
        }
    }

    private fun callGeminiGroundedWebCascade(
        apiKey: String,
        requestedModel: String,
        qaSystemPrompt: String,
        userGoal: String,
        chatHistory: List<Pair<String, String>>,
        enableWebSearch: Boolean,
        onTextDelta: ((String) -> Unit)? = null
    ): GroundedWebAnswer? {
        val preferredGemini = if (isTextOrVlmGeminiModel(requestedModel)) requestedModel else DEFAULT_MODEL
        val candidates = listOf(
            preferredGemini,
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash",
            "gemini-2.5-flash"
        ).distinct()

        val contentsArr = JSONArray()
        val recentHistory = chatHistory.takeLast(12)
        for ((index, entry) in recentHistory.withIndex()) {
            val (rawRole, rawText) = entry
            val text = rawText.trim()
            if (text.isEmpty()) continue
            val roleLower = rawRole.trim().lowercase()
            if (roleLower == "system") continue
            val geminiRole = if (roleLower == "user") "user" else "model"
            if (index == recentHistory.lastIndex && geminiRole == "user" && text == userGoal) {
                continue
            }
            contentsArr.put(
                JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", JSONArray().put(JSONObject().put("text", text)))
                }
            )
        }
        contentsArr.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userGoal)))
            }
        )

        var skippedAllDueToRateLimit = true
        for (candidateModel in candidates) {
            if (MultiProviderApiKeySet.isKeyTemporarilyDead(apiKey)) {
                return null
            }
            if (MultiProviderApiKeySet.isModelRateLimited(apiKey, candidateModel)) {
                continue
            }
            skippedAllDueToRateLimit = false

            val attempt = runCatching {
                if (onTextDelta != null) {
                    runCatching {
                        executeGeminiGroundedSseStreamRequest(
                            apiKey = apiKey,
                            modelName = candidateModel,
                            qaSystemPrompt = qaSystemPrompt,
                            contentsArr = contentsArr,
                            enableWebSearch = enableWebSearch,
                            includeUrlContext = enableWebSearch,
                            onTextDelta = onTextDelta
                        )
                    }.recoverCatching { sseErr ->
                        val sseMsg = sseErr.message.orEmpty()
                        // Do not mask auth/quota errors; fall back to unary generateContent only for SSE transport issues
                        if (sseMsg.contains("HTTP 401") || sseMsg.contains("HTTP 402") || sseMsg.contains("HTTP 429") || sseMsg.contains("HTTP 404")) {
                            throw sseErr
                        }
                        executeGeminiGroundedRequest(
                            apiKey = apiKey,
                            modelName = candidateModel,
                            qaSystemPrompt = qaSystemPrompt,
                            contentsArr = contentsArr,
                            enableWebSearch = enableWebSearch,
                            includeUrlContext = enableWebSearch
                        )
                    }.getOrThrow()
                } else {
                    executeGeminiGroundedRequest(
                        apiKey = apiKey,
                        modelName = candidateModel,
                        qaSystemPrompt = qaSystemPrompt,
                        contentsArr = contentsArr,
                        enableWebSearch = enableWebSearch,
                        includeUrlContext = enableWebSearch
                    )
                }
            }

            if (attempt.isSuccess) {
                val res = attempt.getOrNull()
                if (res != null && res.succeeded) {
                    return res
                }
            } else {
                val err = attempt.exceptionOrNull() ?: continue
                val msg = err.message.orEmpty()
                // If endpoint rejects url_context with HTTP 400, retry this same model with google_search only
                if (enableWebSearch && msg.contains("HTTP 400") && msg.contains("url_context", ignoreCase = true)) {
                    val retryAttempt = runCatching {
                        executeGeminiGroundedRequest(
                            apiKey = apiKey,
                            modelName = candidateModel,
                            qaSystemPrompt = qaSystemPrompt,
                            contentsArr = contentsArr,
                            enableWebSearch = true,
                            includeUrlContext = false
                        )
                    }.onFailure { retryErr ->
                        handleCloudProviderFailure("GEMINI", candidateModel, apiKey, retryErr)
                    }.getOrNull()
                    if (retryAttempt != null && retryAttempt.succeeded) {
                        return retryAttempt
                    }
                    continue
                }

                handleCloudProviderFailure("GEMINI", candidateModel, apiKey, err)
                val upper = msg.uppercase()
                if (msg.contains("HTTP 401") || msg.contains("HTTP 402") || upper.contains("API_KEY_INVALID")) {
                    return null
                }
                // On 429 / 404 / 5xx / transient errors, cascade to the next live Gemini model!
            }
        }

        if (skippedAllDueToRateLimit) {
            val msg = "HTTP 429 (Gemini 모델이 모두 요청 한도 45초 쿨다운 중입니다 — 잠시 후 다시 시도해 주세요)"
            lastCloudApiErrorReason = msg
            logCloudError(msg)
        }
        return null
    }

    private fun executeGeminiGroundedSseStreamRequest(
        apiKey: String,
        modelName: String,
        qaSystemPrompt: String,
        contentsArr: JSONArray,
        enableWebSearch: Boolean,
        includeUrlContext: Boolean,
        onTextDelta: (String) -> Unit
    ): GroundedWebAnswer {
        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", qaSystemPrompt)))
            )
            put("contents", contentsArr)
            if (enableWebSearch) {
                val toolsArr = JSONArray()
                toolsArr.put(JSONObject().put("google_search", JSONObject()))
                if (includeUrlContext) {
                    toolsArr.put(JSONObject().put("url_context", JSONObject()))
                }
                put("tools", toolsArr)
            }
        }

        // Security invariant: NEVER put API key in URL query param; always send via `x-goog-api-key` header.
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:streamGenerateContent?alt=sse"
        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw IllegalStateException("HTTP ${resp.code}: ${errBody.take(220)}")
            }
            val stream = resp.body?.byteStream()
                ?: throw IllegalStateException("Gemini SSE response body was empty")

            val parser = SseLineParser()
            val textBuilder = StringBuilder()
            val citations = mutableListOf<Pair<String, String>>()
            val seenUris = mutableSetOf<String>()
            var finishReason = ""

            fun processJsonFrame(dataJson: String) {
                val trimmed = dataJson.trim()
                if (trimmed.isEmpty() || trimmed == "[DONE]") return
                val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return
                val firstCandidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: return
                val fr = optNonNullString(firstCandidate, "finishReason")
                if (fr.isNotEmpty()) finishReason = fr

                val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        val chunk = optNonNullString(p, "text")
                        if (chunk.isNotEmpty()) {
                            textBuilder.append(chunk)
                            runCatching { onTextDelta(chunk) }
                        }
                    }
                }

                val groundingMeta = firstCandidate.optJSONObject("groundingMetadata")
                val chunks = groundingMeta?.optJSONArray("groundingChunks")
                if (chunks != null) {
                    for (i in 0 until chunks.length()) {
                        val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                        val uri = optNonNullString(web, "uri").trim()
                        val title = optNonNullString(web, "title").trim().ifBlank { uri }
                        if (uri.isNotEmpty() && seenUris.add(uri)) {
                            citations.add(title to uri)
                        }
                    }
                }
            }

            java.io.BufferedReader(java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val frame = parser.feedLine(line) ?: continue
                    processJsonFrame(frame.data)
                }
                parser.flushPendingFrame()?.let { processJsonFrame(it.data) }
            }

            if (finishReason == "SAFETY" && textBuilder.isEmpty()) {
                return GroundedWebAnswer(
                    succeeded = true,
                    answerKo = resolveSafetyNotice(finishReason),
                    citations = emptyList(),
                    providerBadge = "${modelName.uppercase()}-SSE"
                )
            }

            val answerText = textBuilder.toString().trim()
            if (answerText.isEmpty()) {
                throw IllegalStateException("Gemini SSE stream returned empty text (finishReason=$finishReason)")
            }

            val badge = if (citations.isNotEmpty() || enableWebSearch) {
                "GEMINI-SSE (${modelName.uppercase()})"
            } else {
                "${modelName.uppercase()}-SSE"
            }
            return GroundedWebAnswer(
                succeeded = true,
                answerKo = answerText,
                citations = citations,
                providerBadge = badge
            )
        }
    }

    private fun executeGeminiGroundedRequest(
        apiKey: String,
        modelName: String,
        qaSystemPrompt: String,
        contentsArr: JSONArray,
        enableWebSearch: Boolean,
        includeUrlContext: Boolean
    ): GroundedWebAnswer {
        val payload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", qaSystemPrompt)))
            )
            put("contents", contentsArr)
            if (enableWebSearch) {
                val toolsArr = JSONArray()
                toolsArr.put(JSONObject().put("google_search", JSONObject()))
                if (includeUrlContext) {
                    toolsArr.put(JSONObject().put("url_context", JSONObject()))
                }
                put("tools", toolsArr)
            }
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent"
        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(220)}")
            }

            val root = JSONObject(bodyStr)
            val firstCandidate = root.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw JSONException("Gemini response missing candidates[0]")

            val finishReason = optNonNullString(firstCandidate, "finishReason")
            if (finishReason == "SAFETY") {
                return GroundedWebAnswer(
                    succeeded = true,
                    answerKo = resolveSafetyNotice(finishReason),
                    citations = emptyList(),
                    providerBadge = modelName.uppercase()
                )
            }

            val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            val textBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought", false)) continue
                val chunk = optNonNullString(p, "text")
                if (chunk.isNotEmpty()) {
                    textBuilder.append(chunk)
                }
            }
            val answerText = textBuilder.toString().trim()
            if (answerText.isEmpty()) {
                throw IllegalStateException("Gemini returned empty answer text (finishReason=$finishReason)")
            }

            val citations = mutableListOf<Pair<String, String>>()
            val seenUris = mutableSetOf<String>()
            val groundingMeta = firstCandidate.optJSONObject("groundingMetadata")
            val chunks = groundingMeta?.optJSONArray("groundingChunks")
            if (chunks != null) {
                for (i in 0 until chunks.length()) {
                    val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                    val uri = optNonNullString(web, "uri").trim()
                    val title = optNonNullString(web, "title").trim().ifBlank { uri }
                    if (uri.isNotEmpty() && seenUris.add(uri)) {
                        citations.add(title to uri)
                    }
                }
            }

            val badge = if (citations.isNotEmpty() || enableWebSearch) {
                "GEMINI-WEB (${modelName.uppercase()})"
            } else {
                modelName.uppercase()
            }
            return GroundedWebAnswer(
                succeeded = true,
                answerKo = answerText,
                citations = citations,
                providerBadge = badge
            )
        }
    }

    private fun callAnthropicGroundedWebApi(
        apiKey: String,
        modelName: String,
        qaSystemPrompt: String,
        userGoal: String,
        chatHistory: List<Pair<String, String>>,
        enableWebSearch: Boolean
    ): GroundedWebAnswer {
        val messagesArr = buildAnthropicMessagesArray(chatHistory, userGoal)

        fun executeOnce(useWebSearchTool: Boolean): Pair<Int, String> {
            val payload = JSONObject().apply {
                put("model", modelName)
                put("max_tokens", 2048)
                put("system", qaSystemPrompt)
                put("messages", messagesArr)
                if (useWebSearchTool) {
                    put(
                        "tools",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "web_search_20250305")
                                put("name", "web_search")
                                put("max_uses", 5)
                            }
                        )
                    )
                }
            }
            val req = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                return resp.code to resp.body?.string().orEmpty()
            }
        }

        var (code, bodyStr) = executeOnce(enableWebSearch)
        if (code == 400 && enableWebSearch) {
            // Clean text fallback if workspace/org does not allow web_search_20250305
            val fallback = executeOnce(false)
            code = fallback.first
            bodyStr = fallback.second
        }
        if (code !in 200..299) {
            throw IllegalStateException("Anthropic HTTP $code: ${bodyStr.take(220)}")
        }

        val root = JSONObject(bodyStr)
        val contentArr = root.optJSONArray("content") ?: JSONArray()
        val textBuilder = StringBuilder()
        val citations = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()

        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            when (optNonNullString(block, "type")) {
                "thinking", "redacted_thinking" -> continue
                "text" -> {
                    val t = optNonNullString(block, "text")
                    if (t.isNotEmpty()) {
                        textBuilder.append(t)
                    }
                    val blockCitations = block.optJSONArray("citations")
                    if (blockCitations != null) {
                        for (j in 0 until blockCitations.length()) {
                            val cit = blockCitations.optJSONObject(j) ?: continue
                            val url = optNonNullString(cit, "url").trim()
                            val title = optNonNullString(cit, "title").trim().ifBlank { url }
                            if (url.isNotEmpty() && seenUrls.add(url)) {
                                citations.add(title to url)
                            }
                        }
                    }
                }
                "web_search_tool_result" -> {
                    val results = block.optJSONArray("content") ?: continue
                    for (j in 0 until results.length()) {
                        val item = results.optJSONObject(j) ?: continue
                        if (optNonNullString(item, "type") == "web_search_result") {
                            val url = optNonNullString(item, "url").trim()
                            val title = optNonNullString(item, "title").trim().ifBlank { url }
                            if (url.isNotEmpty() && seenUrls.add(url)) {
                                citations.add(title to url)
                            }
                        }
                    }
                }
            }
        }

        val answerText = textBuilder.toString().trim()
        if (answerText.isEmpty()) {
            throw IllegalStateException("Anthropic returned empty text response")
        }
        val badge = if (citations.isNotEmpty()) "CLAUDE-WEB (${modelName.uppercase()})" else modelName.uppercase()
        return GroundedWebAnswer(
            succeeded = true,
            answerKo = answerText,
            citations = citations,
            providerBadge = badge
        )
    }

    private fun buildAnthropicMessagesArray(
        chatHistory: List<Pair<String, String>>,
        userGoal: String
    ): JSONArray {
        val merged = mutableListOf<Pair<String, String>>()
        val recent = chatHistory.takeLast(12)
        for ((index, entry) in recent.withIndex()) {
            val (rawRole, rawText) = entry
            val text = rawText.trim()
            if (text.isEmpty()) continue
            val roleLower = rawRole.trim().lowercase()
            if (roleLower == "system") continue
            val role = if (roleLower == "user") "user" else "assistant"
            if (index == recent.lastIndex && role == "user" && text == userGoal) continue
            if (merged.isEmpty() && role != "user") continue
            if (merged.isNotEmpty() && merged.last().first == role) {
                val prev = merged.removeAt(merged.lastIndex)
                merged.add(role to "${prev.second}\n$text")
            } else {
                merged.add(role to text)
            }
        }
        if (merged.isNotEmpty() && merged.last().first == "user") {
            val prev = merged.removeAt(merged.lastIndex)
            merged.add("user" to "${prev.second}\n$userGoal")
        } else {
            merged.add("user" to userGoal)
        }
        val arr = JSONArray()
        for ((role, content) in merged) {
            arr.put(JSONObject().put("role", role).put("content", content))
        }
        return arr
    }

    private fun callOpenAiResponsesWebSearchApi(
        apiKey: String,
        modelName: String,
        qaSystemPrompt: String,
        userGoal: String,
        chatHistory: List<Pair<String, String>>
    ): GroundedWebAnswer {
        val inputArr = JSONArray()
        val recent = chatHistory.takeLast(10)
        for ((index, entry) in recent.withIndex()) {
            val (rawRole, rawText) = entry
            val text = rawText.trim()
            if (text.isEmpty()) continue
            val roleLower = rawRole.trim().lowercase()
            if (roleLower == "system") continue
            val role = if (roleLower == "user") "user" else "assistant"
            if (index == recent.lastIndex && role == "user" && text == userGoal) continue
            inputArr.put(JSONObject().put("role", role).put("content", text))
        }
        inputArr.put(JSONObject().put("role", "user").put("content", userGoal))

        val payload = JSONObject().apply {
            put("model", modelName)
            put("instructions", qaSystemPrompt)
            put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
            put("input", inputArr)
        }

        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("OpenAI Responses HTTP ${resp.code}: ${bodyStr.take(220)}")
            }
            return parseOpenAiResponsesWebSearchBody(bodyStr, modelName)
        }
    }

    private fun callOpenAiTextOrWebApi(
        apiKey: String,
        modelName: String,
        endpoint: String,
        providerBadge: String,
        qaSystemPrompt: String,
        userGoal: String,
        chatHistory: List<Pair<String, String>>
    ): GroundedWebAnswer {
        val messagesArr = JSONArray()
        messagesArr.put(JSONObject().put("role", "system").put("content", qaSystemPrompt))
        val recent = chatHistory.takeLast(12)
        for ((index, entry) in recent.withIndex()) {
            val (rawRole, rawText) = entry
            val text = rawText.trim()
            if (text.isEmpty()) continue
            val roleLower = rawRole.trim().lowercase()
            if (roleLower == "system") continue
            val role = if (roleLower == "user") "user" else "assistant"
            if (index == recent.lastIndex && role == "user" && text == userGoal) continue
            messagesArr.put(JSONObject().put("role", role).put("content", text))
        }
        messagesArr.put(JSONObject().put("role", "user").put("content", userGoal))

        val payload = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArr)
        }

        val reqBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("OpenAI/OR HTTP ${resp.code}: ${bodyStr.take(220)}")
            }
            val root = JSONObject(bodyStr)
            val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw JSONException("OpenAI/OR response missing choices[0].message")
            val content = optNonNullString(msg, "content").trim()
            if (content.isEmpty()) {
                throw IllegalStateException("OpenAI/OR returned empty content")
            }

            val citations = mutableListOf<Pair<String, String>>()
            val seenUrls = mutableSetOf<String>()
            val annotations = msg.optJSONArray("annotations")
            if (annotations != null) {
                for (i in 0 until annotations.length()) {
                    val ann = annotations.optJSONObject(i) ?: continue
                    val urlCit = ann.optJSONObject("url_citation") ?: continue
                    val url = optNonNullString(urlCit, "url").trim()
                    val title = optNonNullString(urlCit, "title").trim().ifBlank { url }
                    if (url.isNotEmpty() && seenUrls.add(url)) {
                        citations.add(title to url)
                    }
                }
            }
            val topCitations = root.optJSONArray("citations")
            if (topCitations != null) {
                for (i in 0 until topCitations.length()) {
                    val url = topCitations.optString(i, "").trim()
                    if (url.isNotEmpty() && url != "null" && seenUrls.add(url)) {
                        citations.add(url to url)
                    }
                }
            }

            return GroundedWebAnswer(
                succeeded = true,
                answerKo = content,
                citations = citations,
                providerBadge = "$providerBadge (${modelName.uppercase()})"
            )
        }
    }

    private fun buildProviderCandidateQueue(
        requestedModels: List<String>,
        keySet: MultiProviderApiKeySet
    ): List<Triple<String, String, String>> {
        val queue = mutableListOf<Triple<String, String, String>>()
        val cleanedModels = requestedModels
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(AUTO_MULTI_MODEL, ignoreCase = true) && !isCliBridgeModel(it) }
            .ifEmpty { listOf(DEFAULT_MODEL) }

        for (m in cleanedModels) {
            when {
                m.startsWith("claude-", ignoreCase = true) -> {
                    if (!keySet.anthropicKey.isNullOrBlank()) {
                        queue.add(Triple("ANTHROPIC", m, keySet.anthropicKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        queue.add(Triple("OPENROUTER", "anthropic/$m", keySet.openRouterKey))
                    } else if (!keySet.geminiKey.isNullOrBlank()) {
                        val mapped = if (m.contains("opus", ignoreCase = true)) "gemini-3.1-pro-preview" else DEFAULT_MODEL
                        queue.add(Triple("GEMINI", mapped, keySet.geminiKey))
                    }
                }
                m.startsWith("gpt-", ignoreCase = true) || m.startsWith("o3", ignoreCase = true) || m.startsWith("o4", ignoreCase = true) -> {
                    if (!keySet.openAiKey.isNullOrBlank()) {
                        queue.add(Triple("OPENAI", m, keySet.openAiKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        queue.add(Triple("OPENROUTER", "openai/$m", keySet.openRouterKey))
                    } else if (!keySet.geminiKey.isNullOrBlank()) {
                        queue.add(Triple("GEMINI", DEFAULT_MODEL, keySet.geminiKey))
                    }
                }
                m.contains("/") -> {
                    if (!keySet.openRouterKey.isNullOrBlank()) {
                        queue.add(Triple("OPENROUTER", m, keySet.openRouterKey))
                    } else if (!keySet.geminiKey.isNullOrBlank()) {
                        queue.add(Triple("GEMINI", DEFAULT_MODEL, keySet.geminiKey))
                    }
                }
                else -> {
                    val normalizedGemini = normalize2026ModelName(m)
                    if (!keySet.geminiKey.isNullOrBlank()) {
                        queue.add(Triple("GEMINI", normalizedGemini, keySet.geminiKey))
                    } else if (!keySet.anthropicKey.isNullOrBlank()) {
                        val mappedClaude = when {
                            normalizedGemini.contains("argon") || normalizedGemini.contains("pro") -> "claude-opus-4-6"
                            normalizedGemini.contains("lite") -> "claude-haiku-4-5"
                            else -> "claude-sonnet-4-6"
                        }
                        queue.add(Triple("ANTHROPIC", mappedClaude, keySet.anthropicKey))
                    } else if (!keySet.openRouterKey.isNullOrBlank()) {
                        val mappedOr = when {
                            normalizedGemini.contains("argon") || normalizedGemini.contains("pro") -> "anthropic/claude-sonnet-4-6"
                            else -> "google/gemini-2.5-flash"
                        }
                        queue.add(Triple("OPENROUTER", mappedOr, keySet.openRouterKey))
                    } else if (!keySet.openAiKey.isNullOrBlank()) {
                        val mappedOai = if (normalizedGemini.contains("lite")) "gpt-4.1-mini" else "gpt-4.1"
                        queue.add(Triple("OPENAI", mappedOai, keySet.openAiKey))
                    }
                }
            }
        }
        return queue.distinctBy { "${it.first}:${it.second}" }
    }

    /**
     * Disabled legacy fast-path stub. Returns `null` unconditionally so user queries are NEVER hijacked
     * into 6-label screen dumps or hardcoded app-specific regex state machines.
     */
    internal fun tryInstantConversationalOrScreenFastPath(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>
    ): ModelTurnDecision? = null

    private fun tryCallLaptopCliBridge(
        bridgeUrl: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>,
        systemContext: String = "",
        bridgeToken: String = ""
    ): ModelTurnDecision? {
        return try {
            val payload = JSONObject().apply {
                put("goal", goal)
                put("iteration", iteration)
                put("activePackage", snapshot.activePackage)
                put("screenWidth", snapshot.screenWidth)
                put("screenHeight", snapshot.screenHeight)
                put("promptContext", snapshot.toPromptContext(maxElements = 80))
                if (systemContext.isNotBlank()) {
                    put("systemContext", systemContext.trim())
                }
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

            val reqBuilder = Request.Builder()
                .url(bridgeUrl)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (bridgeToken.isNotBlank()) {
                reqBuilder.addHeader("X-Bridge-Token", bridgeToken.trim())
            }

            val fastClient = httpClient.newBuilder()
                .connectTimeout(1200, TimeUnit.MILLISECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .build()

            fastClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string().orEmpty()
                val json = JSONObject(bodyStr)
                val obs = optNonNullString(json, "observation").ifBlank { "화면 및 명령 분석 완료" }
                val spoken = optNonNullString(json, "spokenReply").ifBlank { obs }
                val toolName = optNonNullString(json, "toolName").ifBlank { "complete_task" }
                val toolArgs = json.optJSONObject("toolArgs") ?: JSONObject()
                val planArr = json.optJSONArray("subGoals")
                val parsedPlan = mutableListOf<SubGoalItem>()
                if (planArr != null) {
                    for (i in 0 until planArr.length()) {
                        val item = planArr.optJSONObject(i) ?: continue
                        val statusStr = optNonNullString(item, "status").ifBlank { "IN_PROGRESS" }
                        val status = runCatching { SubGoalStatus.valueOf(statusStr) }
                            .getOrDefault(SubGoalStatus.IN_PROGRESS)
                        val title = optNonNullString(item, "title").ifBlank { "서브골" }
                        parsedPlan.add(SubGoalItem(title, status))
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
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
    ): ModelTurnDecision {
        val promptText = buildUnifiedTurnPrompt(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        val effectiveSystemInstruction = composeSystemInstruction(systemContext)
        val visualBase64 = snapshot.visualGroundingBase64

        val isManagedAgentModel = modelName.startsWith("antigravity-") || modelName.startsWith("deep-research-")
        if (isManagedAgentModel) {
            val interactionsDecision = runCatching {
                callGeminiInteractionsApi(
                    apiKey = apiKey,
                    modelName = modelName,
                    promptText = promptText,
                    screenshotBase64Jpeg = visualBase64,
                    previousPlan = previousPlan,
                    systemInstruction = effectiveSystemInstruction
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
                    JSONArray().put(JSONObject().put("text", effectiveSystemInstruction))
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

        // Live Gemini v1beta cascade prioritizing models active for both new AQ. and legacy AIza keys
        val endpointCandidates = buildList {
            add(modelName)
            when {
                modelName.contains("argon") || modelName.contains("pro") -> {
                    add("gemini-3.1-pro-preview")
                    add("gemini-3.8-flash")
                    add("gemini-3.5-flash-lite")
                    add("gemini-2.5-flash")
                }
                modelName.contains("lite") -> {
                    add("gemini-3.5-flash-lite")
                    add("gemini-3.8-flash")
                    add("gemini-2.5-flash-lite")
                    add("gemini-2.5-flash")
                }
                else -> {
                    add("gemini-3.8-flash")
                    add("gemini-3.5-flash-lite")
                    add("gemini-3.6-flash")
                    add("gemini-2.5-flash")
                }
            }
        }.distinct()

        var lastError: IllegalStateException? = null
        for (endpointModel in endpointCandidates) {
            if (MultiProviderApiKeySet.isModelRateLimited(apiKey, endpointModel)) {
                lastError = IllegalStateException("HTTP 429: $endpointModel 요청 한도 45초 쿨다운 중")
                continue
            }

            val url = "https://generativelanguage.googleapis.com/v1beta/models/$endpointModel:generateContent"
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val err = IllegalStateException("HTTP ${resp.code}: ${bodyStr.take(220)}")
                    lastError = err
                    val upperBody = bodyStr.uppercase()
                    // Only abort provider immediately on explicit 401/402/API_KEY_INVALID
                    if (resp.code == 401 || resp.code == 402 || upperBody.contains("API_KEY_INVALID")) {
                        throw err
                    }
                    // On HTTP 429, mark ONLY (apiKey, endpointModel) rate-limited for 45s and try remaining candidates
                    if (resp.code == 429) {
                        MultiProviderApiKeySet.markModelRateLimited(apiKey, endpointModel)
                        logCloudError("Gemini HTTP 429 on '$endpointModel'; cascading to next live endpoint")
                        return@use
                    }
                    // On HTTP 404 or other model-specific errors, continue trying remaining endpointCandidates
                    logCloudError("Gemini HTTP ${resp.code} on '$endpointModel'; trying next endpoint")
                    return@use
                }

                val root = JSONObject(bodyStr)
                val candidates = root.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts") ?: JSONArray()

                val textBuilder = StringBuilder()
                var fnName: String? = null
                var fnArgs = JSONObject()

                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (p.optBoolean("thought", false)) continue
                    val partText = optNonNullString(p, "text")
                    if (partText.isNotEmpty()) {
                        textBuilder.append(partText).append("\n")
                    }
                    if (fnName == null) {
                        val fc = p.optJSONObject("functionCall")
                        if (fc != null) {
                            val candidateFnName = optNonNullString(fc, "name").trim()
                            if (candidateFnName.isNotEmpty()) {
                                fnName = candidateFnName
                                fnArgs = fc.optJSONObject("args") ?: JSONObject()
                            }
                        }
                    }
                }

                val textOut = textBuilder.toString()
                val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
                val argSpoken = optNonNullString(fnArgs, "spoken_reply").trim()
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
        throw (lastError ?: IllegalStateException("HTTP 404: No matching live Gemini endpoint model"))
    }

    /**
     * Calls Anthropic Messages API (`https://api.anthropic.com/v1/messages`) with multimodal image + tool calling.
     * Uses standard lowercase JSON Schema tool definitions via `ToolSpecRegistry.toAnthropicToolsJson()`.
     */
    private fun callAnthropicMessagesApi(
        apiKey: String,
        modelName: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
    ): ModelTurnDecision {
        val promptText = buildUnifiedTurnPrompt(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        val effectiveSystemInstruction = composeSystemInstruction(systemContext)
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

        val anthropicTools = ToolSpecRegistry.toAnthropicToolsJson()

        val payload = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", 2048)
            put("system", effectiveSystemInstruction)
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
                throw IllegalStateException("Anthropic HTTP ${resp.code}: ${bodyStr.take(220)}")
            }
            val root = JSONObject(bodyStr)
            val contentArr = root.optJSONArray("content") ?: JSONArray()
            val textBuilder = StringBuilder()
            var fnName: String? = null
            var fnArgs = JSONObject()

            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                when (optNonNullString(block, "type")) {
                    "thinking", "redacted_thinking" -> continue
                    "text" -> {
                        val t = optNonNullString(block, "text")
                        if (t.isNotEmpty()) textBuilder.append(t).append("\n")
                    }
                    "tool_use" -> {
                        if (fnName == null) {
                            val candidateName = optNonNullString(block, "name").trim()
                            if (candidateName.isNotEmpty()) {
                                fnName = candidateName
                                fnArgs = block.optJSONObject("input") ?: JSONObject()
                            }
                        }
                    }
                }
            }

            val textOut = textBuilder.toString()
            val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            val argSpoken = optNonNullString(fnArgs, "spoken_reply").trim()
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
     * with Set-of-Marks multimodal vision + lowercase JSON Schema tool calling via `ToolSpecRegistry.toOpenAiToolsJson()`.
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
        customBaseUrl: String = "",
        systemContext: String = ""
    ): ModelTurnDecision {
        val promptText = buildUnifiedTurnPrompt(goal, iteration, snapshot, historySteps, previousPlan, chatHistory)
        val effectiveSystemInstruction = composeSystemInstruction(systemContext)
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

        val openAiTools = ToolSpecRegistry.toOpenAiToolsJson()

        val payload = JSONObject().apply {
            put("model", modelName)
            put("tools", openAiTools)
            put("tool_choice", if (customBaseUrl.isNotBlank()) "auto" else "required")
            put(
                "messages",
                JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", effectiveSystemInstruction))
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
                throw IllegalStateException("OpenAI/OR HTTP ${resp.code}: ${bodyStr.take(220)}")
            }
            val root = JSONObject(bodyStr)
            val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw JSONException("OpenAI/OR response missing choices[0].message")
            val textOut = optNonNullString(msg, "content")
            val toolCalls = msg.optJSONArray("tool_calls")
            var fnName: String? = null
            var fnArgs = JSONObject()

            if (toolCalls != null && toolCalls.length() > 0) {
                for (i in 0 until toolCalls.length()) {
                    val fnObj = toolCalls.optJSONObject(i)?.optJSONObject("function") ?: continue
                    val candidateName = optNonNullString(fnObj, "name").trim()
                    if (candidateName.isEmpty()) continue
                    fnName = candidateName
                    val rawArgs = fnObj.opt("arguments")
                    fnArgs = when (rawArgs) {
                        is JSONObject -> rawArgs
                        else -> {
                            val argsStr = optNonNullString(fnObj, "arguments").trim()
                            if (argsStr.isEmpty()) {
                                throw JSONException("Empty tool_calls[$i].function.arguments for tool '$candidateName'")
                            }
                            try {
                                JSONObject(argsStr)
                            } catch (_: JSONException) {
                                throw JSONException(
                                    "Malformed tool_calls[$i].function.arguments JSON for '$candidateName': ${argsStr.take(120)}"
                                )
                            }
                        }
                    }
                    break
                }
            }

            val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            val argSpoken = optNonNullString(fnArgs, "spoken_reply").trim()
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
     * Pass-through hook preserved for call-site stability; never alters or discards valid LLM replies.
     */
    private fun rescueActionPromiseDecision(
        decision: ModelTurnDecision,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision = decision

    private fun callGeminiInteractionsApi(
        apiKey: String,
        modelName: String,
        promptText: String,
        screenshotBase64Jpeg: String?,
        previousPlan: List<SubGoalItem>,
        systemInstruction: String = SYSTEM_INSTRUCTION
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
            put("system_instruction", systemInstruction)
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

            val textBuilder = StringBuilder()
            var fnName: String? = null
            var fnArgs = JSONObject()

            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                when (optNonNullString(step, "type")) {
                    "thought" -> continue
                    "function_call" -> {
                        if (fnName == null) {
                            val candidateName = optNonNullString(step, "name").trim()
                            if (candidateName.isNotEmpty()) {
                                fnName = candidateName
                                fnArgs = step.optJSONObject("arguments") ?: JSONObject()
                            }
                        }
                    }
                    "model_output" -> {
                        val contentArr = step.optJSONArray("content")
                        if (contentArr != null) {
                            for (j in 0 until contentArr.length()) {
                                val item = contentArr.optJSONObject(j) ?: continue
                                if (optNonNullString(item, "type") == "text") {
                                    val t = optNonNullString(item, "text")
                                    if (t.isNotEmpty()) textBuilder.append(t).append("\n")
                                }
                            }
                        }
                    }
                }
            }

            val textOut = textBuilder.toString()
            val (parsedSpoken, obs, updatedPlan) = parseSpokenObservationAndPlan(textOut, previousPlan)
            val argSpoken = optNonNullString(fnArgs, "spoken_reply").trim()
            val spoken = argSpoken.ifBlank { parsedSpoken }
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

    private fun resolveCleanAppLabel(activePackage: String): String {
        val pkg = activePackage.trim().removeSuffix(".debug")
        if (pkg.isEmpty() || pkg.equals("unknown", ignoreCase = true)) return ""
        val friendlyAppNames = mapOf(
            "com.kakao.talk" to "카카오톡",
            "com.google.android.youtube" to "유튜브",
            "com.android.settings" to "설정",
            "com.android.chrome" to "크롬",
            "com.nhn.android.search" to "네이버",
            "viva.republica.toss" to "토스",
            "com.coupang.mobile" to "쿠팡",
            "com.sampleapp" to "배달의민족",
            "com.instagram.android" to "인스타그램",
            "com.sec.android.gallery3d" to "갤러리",
            "com.sec.android.app.camera" to "카메라",
            "com.sec.android.app.clockpackage" to "시계",
            "com.hanson.mobileuse" to "Hanson"
        )
        friendlyAppNames[pkg]?.let { return it }
        val segment = pkg.substringAfterLast('.').trim()
        if (segment.isEmpty() ||
            segment.equals("android", ignoreCase = true) ||
            segment.equals("app", ignoreCase = true) ||
            segment.equals("debug", ignoreCase = true)
        ) {
            return ""
        }
        return segment
    }

    /**
     * Honest offline fallback handler invoked only when no cloud LLM, custom endpoint,
     * on-device NPU model, or Laptop CLI Bridge (`:18889`) could fulfill the turn.
     *
     * Never hijacks queries via regexes, never fuzzy-clicks arbitrary UI labels, never triggers
     * `reply_notification`, and surfaces the exact cloud failure reason or setup guidance clearly.
     */
    fun inferLocalOnDeviceAction(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage> = emptyList()
    ): ModelTurnDecision {
        val cleanGoal = goal.trim()
        val errorDetail = lastCloudApiErrorReason?.trim().orEmpty()
        val appLabel = resolveCleanAppLabel(snapshot.activePackage)
        val appSuffix = if (appLabel.isNotEmpty()) " (현재 앱: $appLabel)" else ""

        val guidanceMessage = if (errorDetail.isNotEmpty()) {
            "클라우드 AI 연결에 실패해 요청을 실행하지 못했어요$appSuffix.\n" +
                "원인: $errorDetail\n" +
                "설정에서 API 키와 모델을 확인하거나 노트북 CLI 브리지(:18889)를 연결한 뒤 다시 시도해 주세요."
        } else {
            "AI API 키 또는 노트북 브리지(:18889)가 연결되지 않았어요$appSuffix.\n" +
                "설정에서 Gemini·Claude·OpenAI API 키를 등록하거나 노트북 CLI 브리지(:18889)를 연결해 주세요."
        }

        val observationText = if (errorDetail.isNotEmpty()) {
            "오프라인 안내: 클라우드 AI 호출 실패 ($errorDetail)"
        } else {
            "오프라인 안내: AI API 키 또는 노트북 브리지(:18889) 미연결"
        }

        val subGoalTitle = if (cleanGoal.isNotEmpty()) {
            "'${cleanGoal.take(32)}' 처리 대기 (AI 연결 필요)"
        } else {
            "AI API 키 또는 브리지 연결 확인"
        }

        return ModelTurnDecision(
            observation = observationText,
            subGoals = listOf(SubGoalItem(subGoalTitle, SubGoalStatus.IN_PROGRESS)),
            toolName = "ask_user",
            toolArgs = JSONObject().put("question", guidanceMessage),
            spokenReply = guidanceMessage
        )
    }
}
