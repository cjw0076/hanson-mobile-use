package com.hanson.mobileuse.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class MobileAiRouteTier(val badge: String, val labelKo: String) {
    AUTO_SMART("AUTO-ROUTER", "스마트 멀티티어 자동 라우팅 (온디바이스 NPU + 클라우드)"),
    S25_AICORE_NANO("S25-NPU-NANO", "Galaxy S25 내장 Gemini Nano NPU (Google AICore)"),
    ON_DEVICE_MEDIAPIPE("ON-DEVICE-LLM", "모바일 다운로드 모델 (MediaPipe / LiteRT-LM)"),
    LOCAL_EDGE_SERVER("LOCAL-LLM", "로컬 온디바이스 서버 (Ollama / Llama.cpp :11434)"),
    CLOUD_GEMINI_VLM("GEMINI-CLOUD", "Google Gemini 3.8 / Argon 클라우드 VLM")
}

enum class TaskComplexity {
    SIMPLE_NAVIGATION,
    INTERACTIVE_TASK,
    COMPLEX_REASONING
}

data class MobileAiInfrastructureState(
    val activeTier: MobileAiRouteTier = MobileAiRouteTier.AUTO_SMART,
    val googleAiCoreInstalled: Boolean = false,
    val samsungAiCoreInstalled: Boolean = false,
    val voiceAccessInstalled: Boolean = false,
    val bixbyOnDeviceInstalled: Boolean = false,
    val chatGptInstalled: Boolean = false,
    val perplexityInstalled: Boolean = false,
    val downloadedModelFiles: List<String> = emptyList(),
    val activeDownloadedModelPath: String? = null,
    val isDownloadingModel: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val downloadStatusText: String = "",
    val lastUsedBackendBadge: String = "S25-NPU-NANO"
)

/**
 * Unified Mobile AI Infrastructure Router (`MobileAiInfrastructureRouter`).
 *
 * Maximally leverages existing mobile AI infrastructure instead of reinventing heuristics:
 * 1. **Galaxy S25 Built-In NPU (`com.google.ai.edge.aicore.GenerativeModel`)**:
 *    Connects directly to `com.google.android.aicore` (Gemini Nano on Snapdragon 8 Elite NPU)
 *    with zero API key and zero model download required.
 * 2. **MediaPipe Tasks GenAI (`com.google.mediapipe.tasks.genai.llminference.LlmInference`)**:
 *    Loads downloaded `.task` / `.bin` / `.litertlm` open-weight models directly on the mobile GPU/NPU,
 *    and supports 1-touch model downloading onto device storage.
 * 3. **Local Edge Server (`127.0.0.1:11434` / `:8080`)**:
 *    Connects to Termux / Ollama / llama.cpp if running on the phone or LAN.
 * 4. **Installed Mobile Assistant Delegation**:
 *    Can hand off specialized queries to installed apps (`Perplexity`, `ChatGPT`, `Bixby`, `Voice Access`).
 * 5. **Cloud Frontier VLM (`GeminiMultimodalClient`)**:
 *    Uses Gemini 3.8 / Argon when an API key is configured.
 */
class MobileAiInfrastructureRouter(
    private val context: Context,
    private val cloudClient: GeminiMultimodalClient
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(MobileAiInfrastructureState())
    val state: StateFlow<MobileAiInfrastructureState> = _state.asStateFlow()

    private val craftAnythingEngine = CraftAnythingMobileEngine(appContext)
    private var aiCoreNanoModel: GenerativeModel? = null
    private var mediaPipeLlm: LlmInference? = null
    private var loadedMediaPipePath: String? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        refreshInfrastructureState()
    }

    fun refreshInfrastructureState() {
        val pm = appContext.packageManager
        fun isInstalled(pkg: String): Boolean =
            runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)

        val googleAiCore = isInstalled("com.google.android.aicore")
        val samsungAiCore = isInstalled("com.samsung.android.aicore")
        val voiceAccess = isInstalled("com.google.android.apps.accessibility.voiceaccess")
        val bixbyKo = isInstalled("com.samsung.android.bixby.ondevice.kokr") || isInstalled("com.samsung.android.bixby.agent")
        val chatGpt = isInstalled("com.openai.chatgpt")
        val perplexity = isInstalled("ai.perplexity.app.android")

        val modelFiles = scanOnDeviceModelFiles()

        _state.update {
            it.copy(
                googleAiCoreInstalled = googleAiCore,
                samsungAiCoreInstalled = samsungAiCore,
                voiceAccessInstalled = voiceAccess,
                bixbyOnDeviceInstalled = bixbyKo,
                chatGptInstalled = chatGpt,
                perplexityInstalled = perplexity,
                downloadedModelFiles = modelFiles.map { f -> f.name },
                activeDownloadedModelPath = modelFiles.firstOrNull()?.absolutePath
            )
        }
    }

    fun setPreferredTier(tier: MobileAiRouteTier) {
        _state.update { it.copy(activeTier = tier) }
    }

    private fun scanOnDeviceModelFiles(): List<File> {
        val dirs = listOfNotNull(
            File(appContext.filesDir, "models").apply { mkdirs() },
            appContext.getExternalFilesDir("models"),
            File("/sdcard/Download"),
            File("/data/local/tmp")
        )
        val exts = setOf("task", "bin", "litertlm", "gguf", "tflite")
        return dirs.flatMap { dir ->
            runCatching {
                dir.listFiles()?.filter { f ->
                    f.isFile && f.length() > 5_000_000L && f.extension.lowercase() in exts
                }.orEmpty()
            }.getOrDefault(emptyList())
        }.distinctBy { it.name }
    }

    /**
     * Downloads a compact on-device model file into `filesDir/models/` or registers an existing file.
     */
    suspend fun downloadOnDeviceModel(
        customUrl: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        refreshInfrastructureState()
        val existing = scanOnDeviceModelFiles()
        if (existing.isNotEmpty() && customUrl.isNullOrBlank()) {
            val first = existing.first()
            _state.update {
                it.copy(
                    activeDownloadedModelPath = first.absolutePath,
                    downloadStatusText = "온디바이스 모델 감지됨: ${first.name}"
                )
            }
            return@withContext "이미 기기에 온디바이스 모델(${first.name})이 준비되어 있어 즉시 연결했습니다."
        }

        val modelsDir = File(appContext.filesDir, "models").apply { mkdirs() }
        // Default open-access compact model URL if user specifies or triggers download
        val targetUrl = customUrl?.trim()?.takeIf { it.startsWith("http") }
        if (targetUrl == null) {
            return@withContext if (_state.value.googleAiCoreInstalled) {
                "현재 Galaxy S25 내장 NPU 모델(Google AICore Gemini Nano)과 Samsung AICore가 이미 기기에 설치되어 바로 작동 중입니다. 별도 `.task`/`.litertlm` 모델 파일을 적용하려면 다운로드 폴더(`/sdcard/Download`)에 넣거나 모델 URL을 말씀해 주세요."
            } else {
                "다운로드할 `.task` 또는 `.litertlm` 모델 URL을 함께 말씀해 주시거나 `/sdcard/Download` 폴더에 넣어주시면 MediaPipe 온디바이스 엔진이 자동으로 로드합니다."
            }
        }

        val fileName = targetUrl.substringAfterLast('/').substringBefore('?').ifBlank { "ondevice_model.task" }
        val outFile = File(modelsDir, fileName)

        try {
            _state.update {
                it.copy(
                    isDownloadingModel = true,
                    downloadProgressPercent = 1,
                    downloadStatusText = "$fileName 다운로드 시작..."
                )
            }
            onProgress?.invoke("모바일 온디바이스 모델($fileName) 다운로드를 시작합니다.")

            val req = Request.Builder().url(targetUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}")
                }
                val body = resp.body ?: throw IllegalStateException("Empty body")
                val totalBytes = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = ((downloaded * 100) / totalBytes).toInt().coerceIn(1, 99)
                                _state.update { it.copy(downloadProgressPercent = pct) }
                            }
                        }
                    }
                }
            }

            refreshInfrastructureState()
            _state.update {
                it.copy(
                    isDownloadingModel = false,
                    downloadProgressPercent = 100,
                    activeDownloadedModelPath = outFile.absolutePath,
                    downloadStatusText = "다운로드 완료: ${outFile.name}"
                )
            }
            "온디바이스 모델(${outFile.name}) 다운로드 및 MediaPipe 엔진 마운트를 완료했습니다."
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isDownloadingModel = false,
                    downloadStatusText = "다운로드 실패: ${e.message}"
                )
            }
            "온디바이스 모델 다운로드 중 오류가 발생했습니다(${e.message}). 내장 S25 Gemini Nano NPU로 계속 동작합니다."
        }
    }

    /**
     * Dynamically estimates task complexity for intelligent multi-model routing.
     *
     * - SIMPLE_NAVIGATION: Single-step, trivial navigation, volume/brightness/settings toggles, or direct app opening.
     * - INTERACTIVE_TASK: Standard multi-step interactive app tasks, in-app search, form-filling, or messaging.
     * - COMPLEX_REASONING: Deep analysis, comparison, code canvas visualization, app reverse engineering, or compound multi-goal tasks.
     */
    fun estimateTaskComplexity(prompt: String, snapshot: AtomicSnapshot? = null): TaskComplexity {
        val p = prompt.trim()
        val pLower = p.lowercase()

        // 1. COMPLEX_REASONING: analytical reasoning, code canvas, reverse engineering, compound subgoals
        val complexKeywords = listOf(
            "분석", "비교", "요약", "정리", "추론", "이유", "계획", "전략", "코드", "시각화",
            "캔버스", "canvas", "차트", "그래프", "svg", "draw", "html", "javascript", "script",
            "크래프트", "클린룸", "리버스", "구조 파악", "설계", "해석", "판단", "평가",
            "analyze", "compare", "summarize", "reason", "plan", "strategy", "code", "chart"
        )
        if (complexKeywords.any { pLower.contains(it) }) {
            return TaskComplexity.COMPLEX_REASONING
        }

        val compoundDelimiters = listOf("동시에", "하면서", ";", "||", "하고 그 다음", "하고 나서", "후에")
        if (compoundDelimiters.any { pLower.contains(it) }) {
            return TaskComplexity.COMPLEX_REASONING
        }

        if (p.length > 70 && (p.count { it == ',' || it == '.' || it == '?' } >= 2)) {
            return TaskComplexity.COMPLEX_REASONING
        }

        if (snapshot != null && snapshot.indexedElements.size > 55 && p.length > 40) {
            return TaskComplexity.COMPLEX_REASONING
        }

        // 2. SIMPLE_NAVIGATION: single-step hardware controls, navigation, quick toggles, simple app launches
        val simpleNavKeywords = listOf(
            "뒤로", "뒤로가기", "back", "홈", "홈으로", "home",
            "소리", "볼륨", "음량", "volume", "음소거",
            "밝기", "brightness", "와이파이", "wifi", "블루투스", "bluetooth",
            "스크롤", "내려", "올려", "scroll down", "scroll up", "위로", "아래로",
            "시간", "배터리", "날씨", "몇 시", "몇 %", "상태 확인"
        )
        if (simpleNavKeywords.any { pLower.contains(it) } && p.length <= 35) {
            return TaskComplexity.SIMPLE_NAVIGATION
        }

        val simpleOpenClose = listOf("열어", "실행", "켜줘", "open", "launch", "닫아", "종료", "close")
        val isSimpleOpen = simpleOpenClose.any { pLower.contains(it) } && p.length <= 22 && !pLower.contains("검색") && !pLower.contains("보내")
        if (isSimpleOpen) {
            return TaskComplexity.SIMPLE_NAVIGATION
        }

        if (p.length <= 15 && (pLower.contains("눌러") || pLower.contains("터치") || pLower.contains("클릭") || pLower.contains("탭"))) {
            return TaskComplexity.SIMPLE_NAVIGATION
        }

        // 3. Default: INTERACTIVE_TASK
        return TaskComplexity.INTERACTIVE_TASK
    }

    /**
     * Routes a user goal/utterance across the best available AI infrastructure tier:
     * 1. Infrastructure / Assistant Delegation intents (e.g. Perplexity, ChatGPT, Bixby, AICore status)
     * 2. Cloud Gemini VLM (if API key is configured and cloud tier is active)
     * 3. On-Device MediaPipe `LlmInference` (if a `.task` / `.bin` / `.litertlm` model is on device)
     * 4. Local Edge OpenAI-compatible Server (`127.0.0.1:11434` Ollama / `:8080` llama.cpp)
     * 5. Galaxy S25 Built-In `com.google.ai.edge.aicore.GenerativeModel` (Gemini Nano NPU)
     * 6. Context-Aware Conversational Screen Partner (guaranteed sub-50ms fallback)
     */
    suspend fun routeAndDecide(
        apiKey: String,
        modelName: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision = withContext(Dispatchers.IO) {
        val g = goal.trim()
        val gLower = g.lowercase()

        // 0-A. Craft-Anything Clean-Room Engine (`storytold/craft-anything`: Study -> Build -> Check -> Ready)
        if (iteration == 1 && (gLower.contains("크래프트") || gLower.contains("craft") || gLower.contains("클린룸") || gLower.contains("구조 분석") || gLower.contains("보드로 만들어") || gLower.contains("리버스 엔지니어링"))) {
            _state.update { it.copy(lastUsedBackendBadge = "CRAFT-ANYTHING") }
            return@withContext craftAnythingEngine.runCraftAnythingWorkflow(g, snapshot)
        }

        // 0-B. Check if user explicitly wants to delegate to an installed AI app (Perplexity / ChatGPT / Bixby / Voice Access)
        if (iteration == 1) {
            val delegated = tryDelegateToInstalledMobileAiApp(g, gLower)
            if (delegated != null) return@withContext delegated
        }

        val tier = _state.value.activeTier
        val hasApiKey = apiKey.trim().isNotEmpty()

        // 1. AUTO_SMART Dynamic Complexity Multi-Model Routing
        if (tier == MobileAiRouteTier.AUTO_SMART) {
            val complexity = estimateTaskComplexity(goal, snapshot)
            when (complexity) {
                TaskComplexity.SIMPLE_NAVIGATION -> {
                    // Route SIMPLE_NAVIGATION to on-device AICore Nano or gemini-3.5-flash-lite
                    if (_state.value.googleAiCoreInstalled) {
                        val nanoDecision = inferWithAiCoreGeminiNano(
                            goal = goal,
                            iteration = iteration,
                            snapshot = snapshot,
                            historySteps = historySteps,
                            previousPlan = previousPlan,
                            chatHistory = chatHistory
                        )
                        if (nanoDecision != null) {
                            _state.update { it.copy(lastUsedBackendBadge = "S25-NPU-NANO") }
                            return@withContext nanoDecision
                        }
                    }
                    if (hasApiKey) {
                        val cloudDecision = runCatching {
                            cloudClient.decideNextAction(
                                apiKey = apiKey,
                                modelName = "gemini-3.5-flash-lite",
                                goal = goal,
                                iteration = iteration,
                                snapshot = snapshot,
                                historySteps = historySteps,
                                previousPlan = previousPlan,
                                chatHistory = chatHistory
                            )
                        }.getOrNull()
                        if (cloudDecision != null) {
                            _state.update { it.copy(lastUsedBackendBadge = "GEMINI-3.5-FLASH-LITE") }
                            return@withContext cloudDecision
                        }
                    }
                }
                TaskComplexity.INTERACTIVE_TASK -> {
                    // Route INTERACTIVE_TASK to gemini-3.8-flash
                    if (hasApiKey) {
                        val cloudDecision = runCatching {
                            cloudClient.decideNextAction(
                                apiKey = apiKey,
                                modelName = "gemini-3.8-flash",
                                goal = goal,
                                iteration = iteration,
                                snapshot = snapshot,
                                historySteps = historySteps,
                                previousPlan = previousPlan,
                                chatHistory = chatHistory
                            )
                        }.getOrNull()
                        if (cloudDecision != null) {
                            _state.update { it.copy(lastUsedBackendBadge = "GEMINI-3.8-FLASH") }
                            return@withContext cloudDecision
                        }
                    }
                }
                TaskComplexity.COMPLEX_REASONING -> {
                    // Route COMPLEX_REASONING to gemini-4-argon
                    if (hasApiKey) {
                        val cloudDecision = runCatching {
                            cloudClient.decideNextAction(
                                apiKey = apiKey,
                                modelName = "gemini-4-argon",
                                goal = goal,
                                iteration = iteration,
                                snapshot = snapshot,
                                historySteps = historySteps,
                                previousPlan = previousPlan,
                                chatHistory = chatHistory
                            )
                        }.getOrNull()
                        if (cloudDecision != null) {
                            _state.update { it.copy(lastUsedBackendBadge = "GEMINI-4-ARGON") }
                            return@withContext cloudDecision
                        }
                    }
                }
            }
        } else if (hasApiKey && tier == MobileAiRouteTier.CLOUD_GEMINI_VLM) {
            val cloudDecision = runCatching {
                cloudClient.decideNextAction(
                    apiKey = apiKey,
                    modelName = modelName,
                    goal = goal,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory
                )
            }.getOrNull()
            if (cloudDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "GEMINI-CLOUD") }
                return@withContext cloudDecision
            }
        }

        // 2. Downloaded On-Device MediaPipe LLM (`LlmInference` for `.task` / `.bin` / `.litertlm`)
        val modelPath = _state.value.activeDownloadedModelPath
        if (modelPath != null && (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.ON_DEVICE_MEDIAPIPE)) {
            val mpDecision = inferWithMediaPipeLlm(
                modelPath = modelPath,
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory
            )
            if (mpDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "MEDIAPIPE-NPU") }
                return@withContext mpDecision
            }
        }

        // 3. Local Edge LLM Server (Ollama `:11434` or llama.cpp `:8080` on phone/Termux)
        if (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.LOCAL_EDGE_SERVER) {
            val localServerDecision = inferWithLocalEdgeServer(
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory
            )
            if (localServerDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "LOCAL-LLM") }
                return@withContext localServerDecision
            }
        }

        // 4. Galaxy S25 Built-In NPU: Google AICore (`com.google.ai.edge.aicore.GenerativeModel` — Gemini Nano)
        if (_state.value.googleAiCoreInstalled && (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.S25_AICORE_NANO)) {
            val nanoDecision = inferWithAiCoreGeminiNano(
                goal = goal,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory
            )
            if (nanoDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "S25-NPU-NANO") }
                return@withContext nanoDecision
            }
        }

        // 5. Fast Screen-Aware Conversational Partner (guaranteed zero-latency control + natural Korean speech)
        _state.update {
            it.copy(
                lastUsedBackendBadge = if (_state.value.googleAiCoreInstalled) "S25-ONDEVICE" else "ONDEVICE-CORE"
            )
        }
        cloudClient.inferLocalOnDeviceAction(
            goal = goal,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan,
            chatHistory = chatHistory
        )
    }

    /**
     * Delegates to already-installed AI apps on the Galaxy S25 (`Perplexity`, `ChatGPT`, `Voice Access`, `Bixby`)
     * when the user asks for them or requests deep web search without an API key.
     */
    private fun tryDelegateToInstalledMobileAiApp(goal: String, gLower: String): ModelTurnDecision? {
        if ((gLower.contains("퍼플렉시티") || gLower.contains("perplexity")) && _state.value.perplexityInstalled) {
            val query = goal.replace(Regex("(?i)퍼플렉시티(에서|로)?|perplexity"), "").trim()
            return ModelTurnDecision(
                observation = "기기에 설치된 Perplexity AI 앱으로 연결",
                subGoals = listOf(
                    SubGoalItem("Perplexity 앱 실행", SubGoalStatus.IN_PROGRESS),
                    if (query.isNotBlank()) SubGoalItem("'$query' 질문 입력", SubGoalStatus.PENDING) else SubGoalItem("대화 대기", SubGoalStatus.PENDING)
                ),
                toolName = "manage_app",
                toolArgs = JSONObject().put("action", "launch").put("app_name", "ai.perplexity.app.android"),
                spokenReply = "네, 폰에 설치된 퍼플렉시티 앱을 바로 열게요."
            )
        }

        if ((gLower.contains("챗지피티") || gLower.contains("chatgpt") || gLower.contains("지피티")) && _state.value.chatGptInstalled) {
            return ModelTurnDecision(
                observation = "기기에 설치된 ChatGPT 앱으로 연결",
                subGoals = listOf(SubGoalItem("ChatGPT 앱 실행", SubGoalStatus.IN_PROGRESS)),
                toolName = "manage_app",
                toolArgs = JSONObject().put("action", "launch").put("app_name", "com.openai.chatgpt"),
                spokenReply = "네, 폰에 설치된 챗지피티 앱을 열어드릴게요."
            )
        }

        if (gLower.contains("보이스 액세스") || gLower.contains("voice access")) {
            if (_state.value.voiceAccessInstalled) {
                return ModelTurnDecision(
                    observation = "Google Voice Access 실행",
                    subGoals = listOf(SubGoalItem("Google Voice Access 활성화", SubGoalStatus.IN_PROGRESS)),
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", "com.google.android.apps.accessibility.voiceaccess"),
                    spokenReply = "네, 구글 보이스 액세스를 바로 실행할게요."
                )
            }
        }

        if (gLower.contains("빅스비") || gLower.contains("bixby")) {
            if (_state.value.bixbyOnDeviceInstalled) {
                return ModelTurnDecision(
                    observation = "Samsung 온디바이스 빅스비 호출",
                    subGoals = listOf(SubGoalItem("빅스비 에이전트 호출", SubGoalStatus.IN_PROGRESS)),
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", "com.samsung.android.bixby.agent"),
                    spokenReply = "네, 삼성 온디바이스 빅스비를 호출할게요."
                )
            }
        }

        return null
    }

    /**
     * Calls the Galaxy S25's built-in Gemini Nano NPU via `com.google.ai.edge.aicore.GenerativeModel`.
     */
    private suspend fun inferWithAiCoreGeminiNano(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision? {
        return withTimeoutOrNull(4500L) {
            try {
                val model = aiCoreNanoModel ?: GenerativeModel(
                    generationConfig {
                        context = appContext
                        temperature = 0.2f
                        topK = 16
                        maxOutputTokens = 256
                    }
                ).also { aiCoreNanoModel = it }

                val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory)
                val response = model.generateContent(prompt)
                val rawText = response.text?.trim().orEmpty()
                if (rawText.isBlank()) return@withTimeoutOrNull null

                parseCompactOnDeviceDecision(rawText, goal, iteration, snapshot, historySteps, previousPlan)
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Calls a downloaded `.task` / `.bin` / `.litertlm` model on-device via Google AI Edge MediaPipe `LlmInference`.
     */
    private fun inferWithMediaPipeLlm(
        modelPath: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision? {
        return try {
            val engine = if (mediaPipeLlm != null && loadedMediaPipePath == modelPath) {
                mediaPipeLlm!!
            } else {
                mediaPipeLlm?.close()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(320)
                    .build()
                LlmInference.createFromOptions(appContext, options).also {
                    mediaPipeLlm = it
                    loadedMediaPipePath = modelPath
                }
            }

            val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory)
            val rawOut = engine.generateResponse(prompt)?.trim().orEmpty()
            if (rawOut.isBlank()) return null

            parseCompactOnDeviceDecision(rawOut, goal, iteration, snapshot, historySteps, previousPlan)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Connects to a local on-device / Termux / LAN Ollama (`:11434`) or llama.cpp (`:8080`) server if running.
     */
    private fun inferWithLocalEdgeServer(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>
    ): ModelTurnDecision? {
        val endpoints = listOf(
            "http://127.0.0.1:11434/v1/chat/completions" to "qwen2.5:3b",
            "http://127.0.0.1:8080/v1/chat/completions" to "local-model"
        )
        val fastClient = httpClient.newBuilder()
            .connectTimeout(350, TimeUnit.MILLISECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory)
        for ((url, defaultModel) in endpoints) {
            try {
                val body = JSONObject().apply {
                    put("model", defaultModel)
                    put(
                        "messages",
                        JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", prompt)
                            }
                        )
                    )
                    put("max_tokens", 256)
                    put("temperature", 0.2)
                }
                val req = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val content = json.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        ?.trim()
                        .orEmpty()
                    if (content.isNotBlank()) {
                        return parseCompactOnDeviceDecision(content, goal, iteration, snapshot, historySteps, previousPlan)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun buildCompactOnDevicePrompt(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        chatHistory: List<LiveChatMessage>
    ): String {
        val recentChat = chatHistory.takeLast(5).joinToString("\n") { m ->
            "${if (m.sender == ChatSender.USER) "사용자" else "한슨"}: ${m.text.take(90)}"
        }
        val recentActions = historySteps.takeLast(3).joinToString("\n") { s ->
            "Step ${s.stepNumber}: ${s.toolName}(${s.toolArgsJson}) -> ${if (s.succeeded) "성공" else "실패"}"
        }
        val topElements = snapshot.indexedElements.take(32).joinToString("\n") { el ->
            val label = el.text.ifBlank { el.contentDesc }.take(28)
            val flags = buildList {
                if (el.clickable) add("CLICK")
                if (el.editable) add("EDIT")
            }.joinToString(",")
            "[${el.index}] '$label' ($flags)"
        }

        return """
당신은 스마트폰 화면을 함께 보며 자연스럽게 대화하고 완벽하게 제어하는 한국어 AI 파트너 '한슨(Hanson)'입니다.
반드시 아래 두 줄 형식으로만 답하세요:
SPOKEN: <사용자에게 사람처럼 자연스럽고 친절하게 말할 한국어 1~2문장>
ACTION: <다음 중 하나만 출력:
- CLICK <요소번호>
- INPUT <요소번호> <입력할텍스트>
- SWIPE <up|down|left|right>
- LAUNCH <앱이름>
- KEY <BACK|HOME|RECENTS>
- SETTING <brightness|volume_media> <숫자>
- ASK <사용자에게 되물을 질문>
- DONE>

[최근 대화]
$recentChat

[현재 실행된 스텝 (Turn $iteration)]
${recentActions.ifBlank { "없음" }}

[현재 화면 패키지: ${snapshot.activePackage}]
$topElements

[사용자 발화]: $goal
""".trimIndent()
    }

    private fun parseCompactOnDeviceDecision(
        rawOutput: String,
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>
    ): ModelTurnDecision? {
        var spoken = ""
        var actionLine = ""
        for (line in rawOutput.lines()) {
            val t = line.trim()
            if (t.uppercase().startsWith("SPOKEN:")) {
                spoken = t.substringAfter(":").trim()
            } else if (t.uppercase().startsWith("ACTION:")) {
                actionLine = t.substringAfter(":").trim()
            }
        }

        if (spoken.isBlank()) {
            spoken = rawOutput.lines().firstOrNull { !it.uppercase().startsWith("ACTION:") }?.trim().orEmpty()
        }
        if (actionLine.isBlank()) {
            return if (spoken.isNotBlank()) {
                ModelTurnDecision(
                    observation = spoken,
                    subGoals = listOf(SubGoalItem("온디바이스 AI 응답", SubGoalStatus.COMPLETED)),
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", spoken),
                    spokenReply = spoken
                )
            } else null
        }

        val parts = actionLine.split(" ").filter { it.isNotBlank() }
        val verb = parts.firstOrNull()?.uppercase().orEmpty()

        return when (verb) {
            "CLICK" -> {
                val idx = parts.getOrNull(1)?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                if (idx != null) {
                    ModelTurnDecision(
                        observation = spoken.ifBlank { "요소 [$idx] 탭 실행" },
                        subGoals = listOf(SubGoalItem("화면 요소 [$idx] 선택", SubGoalStatus.IN_PROGRESS)),
                        toolName = "click",
                        toolArgs = JSONObject().put("element_index", idx).put("target_description", "온디바이스 AI 선택 요소"),
                        spokenReply = spoken.ifBlank { "네, 해당 항목을 누를게요." }
                    )
                } else null
            }
            "INPUT" -> {
                val idx = parts.getOrNull(1)?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                val text = if (idx != null) parts.drop(2).joinToString(" ") else parts.drop(1).joinToString(" ")
                if (text.isNotBlank()) {
                    ModelTurnDecision(
                        observation = spoken.ifBlank { "'$text' 입력" },
                        subGoals = listOf(SubGoalItem("'$text' 입력", SubGoalStatus.IN_PROGRESS)),
                        toolName = "input_text",
                        toolArgs = JSONObject().apply {
                            if (idx != null) put("element_index", idx)
                            put("text", text)
                            put("submit_enter", true)
                        },
                        spokenReply = spoken.ifBlank { "네, '$text'라고 입력할게요." }
                    )
                } else null
            }
            "SWIPE" -> {
                val dir = parts.getOrNull(1)?.lowercase() ?: "up"
                ModelTurnDecision(
                    observation = spoken.ifBlank { "화면 스크롤 ($dir)" },
                    subGoals = listOf(SubGoalItem("화면 스크롤", SubGoalStatus.IN_PROGRESS)),
                    toolName = "swipe",
                    toolArgs = JSONObject().put("direction", dir).put("duration_ms", 360),
                    spokenReply = spoken.ifBlank { "화면을 이동해 볼게요." }
                )
            }
            "LAUNCH" -> {
                val app = parts.drop(1).joinToString(" ").ifBlank { goal }
                ModelTurnDecision(
                    observation = spoken.ifBlank { "$app 실행" },
                    subGoals = listOf(SubGoalItem("$app 실행", SubGoalStatus.IN_PROGRESS)),
                    toolName = "manage_app",
                    toolArgs = JSONObject().put("action", "launch").put("app_name", app),
                    spokenReply = spoken.ifBlank { "네, ${app}을 열게요." }
                )
            }
            "KEY" -> {
                val key = parts.getOrNull(1)?.uppercase() ?: "BACK"
                ModelTurnDecision(
                    observation = spoken.ifBlank { "$key 키 실행" },
                    subGoals = listOf(SubGoalItem("$key 이동", SubGoalStatus.IN_PROGRESS)),
                    toolName = "press_key",
                    toolArgs = JSONObject().put("key", key),
                    spokenReply = spoken.ifBlank { "네, 이동할게요." }
                )
            }
            "SETTING" -> {
                val setting = parts.getOrNull(1)?.lowercase() ?: "brightness"
                val value = parts.getOrNull(2) ?: "180"
                ModelTurnDecision(
                    observation = spoken.ifBlank { "$setting $value 설정" },
                    subGoals = listOf(SubGoalItem("시스템 설정 변경", SubGoalStatus.IN_PROGRESS)),
                    toolName = "control_device_setting",
                    toolArgs = JSONObject().put("setting", setting).put("value", value),
                    spokenReply = spoken.ifBlank { "네, 설정을 변경했어요." }
                )
            }
            "ASK" -> {
                val q = parts.drop(1).joinToString(" ").ifBlank { spoken }
                ModelTurnDecision(
                    observation = "사용자 확인 요청",
                    subGoals = listOf(SubGoalItem("대화 확인", SubGoalStatus.IN_PROGRESS)),
                    toolName = "ask_user",
                    toolArgs = JSONObject().put("question", q),
                    spokenReply = spoken.ifBlank { q }
                )
            }
            "DONE" -> {
                val summary = spoken.ifBlank { "요청하신 내용을 확인했어요." }
                ModelTurnDecision(
                    observation = summary,
                    subGoals = previousPlan.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgs = JSONObject().put("summary", summary),
                    spokenReply = summary
                )
            }
            else -> null
        }
    }
}
