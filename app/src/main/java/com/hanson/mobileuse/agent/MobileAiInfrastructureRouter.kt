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
    CLOUD_GEMINI_VLM("GEMINI-CLOUD", "Google Gemini 2.5 / 3.8 클라우드 VLM")
}

enum class TaskComplexity {
    SIMPLE_NAVIGATION,
    INTERACTIVE_TASK,
    COMPLEX_REASONING
}

/**
 * Lifestyle task modalities for dynamic multi-model routing (Milestone 4, F12).
 * Maps directly to flagship Google models:
 * - LIFESTYLE_MULTIMODAL_REASONING -> "gemini-4-argon"
 * - LIFESTYLE_FAST_CONVERSATION -> "gemini-3.5-flash-lite"
 * - LIFESTYLE_MEDIA_IMAGE -> "gemini-nano-banana-2.1"
 * - LIFESTYLE_MOTION_CINEMA -> "gemini-omni-1.1-flash"
 */
enum class LifestyleTaskModality {
    LIFESTYLE_MULTIMODAL_REASONING,
    LIFESTYLE_FAST_CONVERSATION,
    LIFESTYLE_MEDIA_IMAGE,
    LIFESTYLE_MOTION_CINEMA
}

data class ModelRoutingTelemetry(
    val modelName: String,
    val specialistRole: String,
    val routeTier: String,
    val latencyMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

fun resolveLifestyleModel(modality: LifestyleTaskModality): String = when (modality) {
    LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING -> "gemini-4-argon"
    LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION -> "gemini-3.5-flash-lite"
    LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE -> "gemini-nano-banana-2.1"
    LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA -> "gemini-omni-1.1-flash"
}

fun resolveFallbackModel(primaryModel: String): String = when (primaryModel) {
    "gemini-4-argon" -> "gemini-3.8-flash"
    "gemini-omni-1.1-flash" -> "gemini-nano-banana-2.1"
    else -> "gemini-3.5-flash-lite"
}

fun resolveOfflineRoute(isOnline: Boolean, targetModel: String): String =
    if (!isOnline) "on-device-nano" else targetModel

fun inferModalityFromPrompt(prompt: String): LifestyleTaskModality = when {
    prompt.contains("그려줘") || (prompt.contains("이미지") && (prompt.contains("생성") || prompt.contains("만들어") || prompt.contains("그려"))) ||
        (prompt.contains("이미지") && !prompt.contains("눌러") && !prompt.contains("클릭") && !prompt.contains("갤러리")) ->
        LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE
    prompt.contains("모션") || (prompt.contains("영상") && (prompt.contains("생성") || prompt.contains("만들어") || prompt.contains("합성") || prompt.contains("제작"))) ->
        LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA
    prompt.contains("계획") || prompt.contains("분석") ->
        LifestyleTaskModality.LIFESTYLE_MULTIMODAL_REASONING
    else -> LifestyleTaskModality.LIFESTYLE_FAST_CONVERSATION
}

fun checkCircuitBreaker(latencyMs: Long, maxAcceptableLatencyMs: Long = 5000L): Boolean =
    latencyMs > maxAcceptableLatencyMs

/**
 * Per-Turn & Role-Specialized Multi-Model Matrix (`SpecialistModelRole`).
 *
 * Instead of binding an entire multi-step task to a single static model,
 * the Multi-Model Router dynamically switches the underlying API model on EVERY turn (`iteration`)
 * and active sub-goal (`SubGoalItem`), defaulting free/standard accounts to live
 * `gemini-2.5-flash` / `gemini-3.8-flash` / `gemini-3.5-flash-lite` (never paid-only `gemini-4-argon` by default):
 * - Turn 1 Complex Planning / Self-Healing after Failure -> `MASTER_PLANNER_HEALER` (`gemini-3.8-flash` / `gemini-2.5-flash`)
 * - Turn 2..N Routine UI Step Execution -> `FAST_REFLEX` (`gemini-3.5-flash-lite` / `gemini-2.5-flash-lite`)
 * - Standard App Search / Form Input / Messaging -> `INTERACTIVE_OPERATOR` (`gemini-2.5-flash` / `gemini-3.8-flash`)
 * - Drag-and-Drop / Slider / Map / Spatial Coordinate Grounding -> `SPATIAL_GROUNDING` (`gemini-3.8-flash` / `gemini-2.5-flash`)
 * - Live HTML5 / SVG / Chart / Widget Code Synthesis & Repair -> `CODE_CANVAS_SYNTHESIS` (`gemini-3.8-flash` / `claude-sonnet-4-6`)
 * - Visual Image / Poster / Icon Generation -> `VISUAL_MEDIA_GEN` (`gemini-nano-banana-2.1` / `gemini-2.5-flash`)
 * - Deep Multi-App Research / UARP Reverse Engineering -> `DEEP_RESEARCH_UARP` (`gemini-3.8-flash` / `gemini-2.5-flash`)
 */
enum class SpecialistModelRole(
    val badge: String,
    val primaryModel: String,
    val fallbackModel: String,
    val roleKo: String
) {
    FAST_REFLEX(
        badge = "GEMINI-3.5-FLASH-LITE",
        primaryModel = "gemini-3.5-flash-lite",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "초저지연 반사신경 실행 (단순 탭·스크롤·후속 스텝 고속 처리)"
    ),
    INTERACTIVE_OPERATOR(
        badge = "GEMINI-2.5-FLASH",
        primaryModel = "gemini-2.5-flash",
        fallbackModel = "gemini-3.8-flash",
        roleKo = "범용 멀티모달 UI 오퍼레이터 (앱 탐색·폼 입력·메시징)"
    ),
    MASTER_PLANNER_HEALER(
        badge = "GEMINI-3.8-FLASH",
        primaryModel = "gemini-3.8-flash",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "마스터 플래너 & 자가 치유(Self-Healing) 추론"
    ),
    SPATIAL_GROUNDING(
        badge = "GEMINI-3.8-SPATIAL",
        primaryModel = "gemini-3.8-flash",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "공간 좌표·드래그·물리 궤적 추론 VLM"
    ),
    CODE_CANVAS_SYNTHESIS(
        badge = "GEMINI-3.8-CODE",
        primaryModel = "gemini-3.8-flash",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "코드 캔버스·SVG·인터랙티브 위젯 합성"
    ),
    VISUAL_MEDIA_GEN(
        badge = "NANO-BANANA-2.1",
        primaryModel = "gemini-nano-banana-2.1",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "비주얼 이미지 생성·합성 오케스트레이터 (Nano Banana 2.1)"
    ),
    DEEP_RESEARCH_UARP(
        badge = "GEMINI-3.8-RESEARCH",
        primaryModel = "gemini-3.8-flash",
        fallbackModel = "gemini-2.5-flash",
        roleKo = "심층 리서치·다중 앱 비교·UARP 분석"
    );

    fun candidateModels(): List<String> = when (this) {
        FAST_REFLEX -> listOf("gemini-3.5-flash-lite", "gemini-3.8-flash", "gemini-2.5-flash-lite", "gemini-2.5-flash")
        INTERACTIVE_OPERATOR -> listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "claude-sonnet-4-6", "gemini-2.5-flash")
        MASTER_PLANNER_HEALER -> listOf("gemini-3.8-flash", "gemini-3.1-pro-preview", "claude-sonnet-4-6", "gemini-2.5-flash")
        SPATIAL_GROUNDING -> listOf("gemini-3.8-flash", "gemini-robotics-er-2-preview", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        CODE_CANVAS_SYNTHESIS -> listOf("gemini-3.8-flash", "claude-sonnet-4-6", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        VISUAL_MEDIA_GEN -> listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
        DEEP_RESEARCH_UARP -> listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
    }
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
    val lastUsedBackendBadge: String = "GEMINI-2.5-FLASH",
    val lastSpecialistRole: String = SpecialistModelRole.FAST_REFLEX.name,
    val lastSpecialistReason: String = SpecialistModelRole.FAST_REFLEX.roleKo,
    val turnModelLedger: List<String> = emptyList(),
    val lastRoutingTelemetry: ModelRoutingTelemetry? = null,
    val isCircuitBreakerTripped: Boolean = false,
    val lastCloudApiError: String? = null,
    val lastDecisionSucceeded: Boolean = true
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

    fun clearLastCloudApiError() {
        _state.update {
            it.copy(
                lastCloudApiError = null,
                lastDecisionSucceeded = true
            )
        }
    }

    fun recordCloudApiError(errorReason: String?, badge: String = "AI 연결 오류") {
        val clean = errorReason?.trim()?.takeIf { it.isNotEmpty() }
        _state.update {
            it.copy(
                lastCloudApiError = clean,
                lastDecisionSucceeded = false,
                lastUsedBackendBadge = badge
            )
        }
    }

    private fun isExplicitCraftAnythingRequest(gLower: String): Boolean {
        if (gLower.contains("마인크래프트") || gLower.contains("스타크래프트") || gLower.contains("워크래프트") ||
            gLower.contains("minecraft") || gLower.contains("starcraft") || gLower.contains("warcraft")
        ) {
            return false
        }
        return gLower.contains("craft-anything") ||
            gLower.contains("craft anything") ||
            gLower.contains("크래프트 애니씽") ||
            gLower.contains("클린룸") ||
            gLower.contains("리버스 엔지니어링") ||
            gLower.contains("apk 구조 분석") ||
            gLower.contains("앱 구조 분석") ||
            gLower.contains("보드로 만들어")
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
     * Per-Turn & Role-Specialized Model Selector (`selectSpecialistRoleForTurn`).
     *
     * Selects the optimal model role for the *current turn (`iteration`)* and *current active sub-goal*,
     * rather than locking an entire multi-step session to a single model:
     * 1. **Self-Healing / Error Recovery Escalation**:
     *    - If `[CANVAS_JS_ERROR]` is present -> `CODE_CANVAS_SYNTHESIS` (`claude-opus-4-6` / `gemini-4-argon`).
     *    - If the previous step failed (`historySteps.lastOrNull()?.succeeded == false`) or repeated the same
     *      action twice in a row -> `MASTER_PLANNER_HEALER` (`gemini-4-argon` / `claude-opus-4-6`).
     * 2. **Modality-Specific Specialist Routing**:
     *    - Code Canvas / SVG / HTML5 / Chart synthesis -> `CODE_CANVAS_SYNTHESIS`
     *    - Visual Image / Illustration / Poster generation -> `VISUAL_MEDIA_GEN`
     *    - Spatial Coordinate / Drag-and-Drop / Slider / Trajectory -> `SPATIAL_GROUNDING`
     *    - Deep Multi-App Research / UARP Reverse Engineering -> `DEEP_RESEARCH_UARP`
     * 3. **Multi-Turn Step-Down Optimization**:
     *    - Turn 1 of a complex goal uses `MASTER_PLANNER_HEALER` (`gemini-4-argon`) to decompose the plan.
     *    - Turns 2..N step down to `FAST_REFLEX` (`gemini-3.5-flash-lite`) or `INTERACTIVE_OPERATOR` (`gemini-3.8-flash`)
     *      for routine UI actions (app launch, click, scroll, text input), and only step back up to
     *      `MASTER_PLANNER_HEALER` when the active sub-goal requires final synthesis/comparison.
     */
    fun selectSpecialistRoleForTurn(
        goal: String,
        iteration: Int,
        snapshot: AtomicSnapshot? = null,
        historySteps: List<AgentStepLog> = emptyList(),
        previousPlan: List<SubGoalItem> = emptyList()
    ): SpecialistModelRole {
        val g = goal.trim()
        val gLower = g.lowercase()

        // 1-A. Live Code Canvas JS Error Self-Healing
        if (g.contains("[CANVAS_JS_ERROR]") || gLower.contains("canvas_js_error")) {
            return SpecialistModelRole.CODE_CANVAS_SYNTHESIS
        }

        // 1-B. Self-Healing Escalation on step failure or repeated loop
        val lastStep = historySteps.lastOrNull()
        val prevStep = if (historySteps.size >= 2) historySteps[historySteps.size - 2] else null
        val isRepeatedLoop = lastStep != null && prevStep != null &&
            lastStep.toolName == prevStep.toolName &&
            lastStep.toolArgsJson == prevStep.toolArgsJson
        if ((lastStep != null && !lastStep.succeeded) || isRepeatedLoop) {
            return SpecialistModelRole.MASTER_PLANNER_HEALER
        }

        // Active sub-goal for this specific turn
        val activeSubGoal = previousPlan.firstOrNull { it.status == SubGoalStatus.IN_PROGRESS }?.title
            ?: previousPlan.firstOrNull { it.status == SubGoalStatus.PENDING }?.title
            ?: ""
        val activeSubLower = activeSubGoal.lowercase()
        val focusText = if (iteration >= 2 && activeSubLower.isNotBlank()) activeSubLower else gLower

        // 2-A. Code Canvas / SVG / Interactive Widget Synthesis
        val codeCanvasKeywords = listOf(
            "캔버스", "canvas", "차트", "그래프", "svg", "html", "javascript", "위젯", "대시보드",
            "코드로 그려", "시각화", "다이어그램", "플로우차트"
        )
        if (codeCanvasKeywords.any { focusText.contains(it) }) {
            return SpecialistModelRole.CODE_CANVAS_SYNTHESIS
        }

        // 2-B. Visual Image / Media Generation
        val imageGenKeywords = listOf(
            "이미지 생성", "그림 그려", "일러스트", "포스터 만들어", "배경화면 생성",
            "배경화면 만들어", "아이콘 생성", "썸네일 만들어", "nano banana", "image gen"
        )
        val promptModality = inferModalityFromPrompt(focusText)
        if (promptModality == LifestyleTaskModality.LIFESTYLE_MEDIA_IMAGE ||
            promptModality == LifestyleTaskModality.LIFESTYLE_MOTION_CINEMA ||
            imageGenKeywords.any { focusText.contains(it) || gLower.contains(it) }
        ) {
            return SpecialistModelRole.VISUAL_MEDIA_GEN
        }

        // 2-C. Spatial Grounding / Drag-and-Drop / Trajectory / Slider / Map Pin
        val spatialKeywords = listOf(
            "드래그", "끌어서", "끌어다", "슬라이더", "조절바", "궤적", "좌표", "핀 옮겨",
            "지도에서 위치", "drag", "slider", "seekbar", "trajectory", "스와이프 궤적"
        )
        if (spatialKeywords.any { focusText.contains(it) }) {
            return SpecialistModelRole.SPATIAL_GROUNDING
        }

        // 2-D. Deep Research / Multi-App Comparison / UARP Architecture Analysis
        val deepResearchKeywords = listOf(
            "심층", "리서치", "리버스", "클린룸", "구조 파악", "아키텍처", "최저가 비교", "비교 분석", "논문"
        )
        if (deepResearchKeywords.any { focusText.contains(it) }) {
            return SpecialistModelRole.DEEP_RESEARCH_UARP
        }

        // 3. Multi-Turn Step-Down Optimization (Turn 2+ when plan is already established and prior step succeeded)
        if (iteration >= 2 && previousPlan.isNotEmpty() && activeSubLower.isNotBlank()) {
            val reasoningSubGoalKeywords = listOf("분석", "비교", "요약", "정리", "판단", "평가", "계산", "종합")
            if (reasoningSubGoalKeywords.any { activeSubLower.contains(it) }) {
                return SpecialistModelRole.MASTER_PLANNER_HEALER
            }
            val fastSubGoalKeywords = listOf("열기", "실행", "탭", "클릭", "선택", "이동", "스크롤", "뒤로", "홈", "확인")
            val inputSubGoalKeywords = listOf("입력", "검색", "전송", "보내기", "작성", "메시지")
            if (inputSubGoalKeywords.any { activeSubLower.contains(it) }) {
                return SpecialistModelRole.INTERACTIVE_OPERATOR
            }
            if (fastSubGoalKeywords.any { activeSubLower.contains(it) }) {
                return SpecialistModelRole.FAST_REFLEX
            }
            return SpecialistModelRole.INTERACTIVE_OPERATOR
        }

        // 4. Turn 1 / Unplanned Fallback based on overall task complexity
        return when (estimateTaskComplexity(goal, snapshot)) {
            TaskComplexity.SIMPLE_NAVIGATION -> SpecialistModelRole.FAST_REFLEX
            TaskComplexity.INTERACTIVE_TASK -> SpecialistModelRole.INTERACTIVE_OPERATOR
            TaskComplexity.COMPLEX_REASONING -> SpecialistModelRole.MASTER_PLANNER_HEALER
        }
    }

    /**
     * Routes a pure [userGoal] (strictly separated from [systemContext]) across the best available AI infrastructure tier:
     * 1. Explicit Clean-Room / Installed Assistant Launch intents (never hijacking general Q&A mentioning app/game names)
     * 2. Per-Turn & Role-Specialized Multi-Model Orchestrator (`SpecialistModelRole` cascade using live 2.5/3.8/3.5-lite models)
     * 3. On-Device MediaPipe `LlmInference` (if a `.task` / `.bin` / `.litertlm` model is on device)
     * 4. Local Edge OpenAI-compatible Server (`127.0.0.1:11434` Ollama / `:8080` llama.cpp)
     * 5. Galaxy S25 Built-In `com.google.ai.edge.aicore.GenerativeModel` (Gemini Nano NPU)
     * 6. Honest Offline Guidance fallback (propagates exact cloud error & marks `lastDecisionSucceeded = false`)
     */
    suspend fun routeAndDecide(
        apiKey: String,
        modelName: String,
        userGoal: String,
        iteration: Int,
        snapshot: AtomicSnapshot,
        historySteps: List<AgentStepLog>,
        previousPlan: List<SubGoalItem>,
        chatHistory: List<LiveChatMessage>,
        customBaseUrl: String = "",
        systemContext: String = "",
        bridgeToken: String = ""
    ): ModelTurnDecision = withContext(Dispatchers.IO) {
        val g = userGoal.trim()
        val gLower = g.lowercase()

        // 0-A. Explicit Craft-Anything Clean-Room Engine ONLY (never hijack "마인크래프트 공략", "craft beer", etc.)
        if (iteration == 1 && isExplicitCraftAnythingRequest(gLower)) {
            _state.update {
                it.copy(
                    lastUsedBackendBadge = "CRAFT-ANYTHING",
                    lastDecisionSucceeded = true
                )
            }
            return@withContext craftAnythingEngine.runCraftAnythingWorkflow(g, snapshot)
        }

        // 0-B. Delegate to an installed AI app ONLY on explicit launch/open verbs (never on "챗지피티 요금제", etc.)
        if (iteration == 1) {
            val delegated = tryDelegateToInstalledMobileAiApp(g, gLower)
            if (delegated != null) {
                _state.update { it.copy(lastDecisionSucceeded = true) }
                return@withContext delegated
            }
        }

        val tier = _state.value.activeTier
        val hasApiKey = apiKey.trim().isNotEmpty()
        val hasCustomUrl = customBaseUrl.trim().isNotEmpty()
        val normalizedModelName = GeminiMultimodalClient.normalize2026ModelName(modelName)
        val isCliBridgeModel = normalizedModelName == "cli-subscription-bridge"
        val canCallRemoteOrBridge = hasApiKey || hasCustomUrl || isCliBridgeModel

        // Run complexity & specialist role selection STRICTLY on pure userGoal (never polluted by systemContext/bubbles!)
        val specialistRole = selectSpecialistRoleForTurn(
            goal = g,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan
        )

        // 0-C. Explicit Custom OpenAI-Compatible Endpoint or CLI Subscription Bridge takes priority when configured
        if (hasCustomUrl || isCliBridgeModel) {
            val directCandidates = listOf(normalizedModelName)
            val cascaded = cloudClient.decideNextActionWithCascade(
                apiKey = apiKey,
                candidateModels = directCandidates,
                goal = g,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                customBaseUrl = customBaseUrl,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            if (cascaded != null) {
                val (resolvedModelBadge, cloudDecision) = cascaded
                recordSpecialistTurn(
                    badge = resolvedModelBadge,
                    role = specialistRole,
                    iteration = iteration,
                    cloudError = null,
                    succeeded = true
                )
                return@withContext cloudDecision
            } else {
                propagateCloudErrorToState()
            }
        }

        // 1. Per-Turn Specialist Multi-Model Routing (`AUTO_SMART` or `auto-multi-model`)
        val useDynamicSpecialistMatrix = tier == MobileAiRouteTier.AUTO_SMART ||
            (tier == MobileAiRouteTier.CLOUD_GEMINI_VLM && normalizedModelName == GeminiMultimodalClient.AUTO_MULTI_MODEL)

        if (useDynamicSpecialistMatrix) {
            // For FAST_REFLEX on simple single-step navigation, try on-device S25 AICore Nano NPU first if available
            if (specialistRole == SpecialistModelRole.FAST_REFLEX &&
                iteration == 1 &&
                tier == MobileAiRouteTier.AUTO_SMART &&
                _state.value.googleAiCoreInstalled
            ) {
                val nanoDecision = inferWithAiCoreGeminiNano(
                    goal = g,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory,
                    systemContext = systemContext
                )
                if (nanoDecision != null) {
                    recordSpecialistTurn(
                        badge = "S25-NPU-NANO",
                        role = specialistRole,
                        iteration = iteration,
                        cloudError = _state.value.lastCloudApiError,
                        succeeded = true
                    )
                    return@withContext nanoDecision
                }
            }

            if (hasApiKey) {
                // Only include a user-selected model if explicitly chosen; NEVER route free/standard
                // users to paid-only gemini-4-argon by default (use live 2.5-flash / 3.8-flash / 3.5-flash-lite).
                val explicitUserModel = if (normalizedModelName != GeminiMultimodalClient.AUTO_MULTI_MODEL && normalizedModelName.isNotBlank()) {
                    listOf(normalizedModelName)
                } else {
                    emptyList()
                }
                val candidateModels = (
                    explicitUserModel +
                        specialistRole.candidateModels() +
                        listOf(GeminiMultimodalClient.DEFAULT_MODEL, "gemini-3.8-flash", "gemini-3.5-flash-lite")
                    ).distinct()
                val cascaded = cloudClient.decideNextActionWithCascade(
                    apiKey = apiKey,
                    candidateModels = candidateModels,
                    goal = g,
                    iteration = iteration,
                    snapshot = snapshot,
                    historySteps = historySteps,
                    previousPlan = previousPlan,
                    chatHistory = chatHistory,
                    customBaseUrl = customBaseUrl,
                    systemContext = systemContext,
                    bridgeToken = bridgeToken
                )
                if (cascaded != null) {
                    val (resolvedModelBadge, cloudDecision) = cascaded
                    val displayBadge = if (resolvedModelBadge == "FAST-PATH-10MS") {
                        specialistRole.badge
                    } else {
                        resolvedModelBadge
                    }
                    recordSpecialistTurn(
                        badge = displayBadge,
                        role = specialistRole,
                        iteration = iteration,
                        cloudError = null,
                        succeeded = true
                    )
                    return@withContext cloudDecision
                } else {
                    propagateCloudErrorToState()
                }
            }
        } else if (hasApiKey && tier == MobileAiRouteTier.CLOUD_GEMINI_VLM) {
            val cascaded = cloudClient.decideNextActionWithCascade(
                apiKey = apiKey,
                candidateModels = listOf(
                    normalizedModelName,
                    GeminiMultimodalClient.DEFAULT_MODEL,
                    "gemini-3.8-flash",
                    "gemini-3.5-flash-lite"
                ).distinct(),
                goal = g,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                customBaseUrl = customBaseUrl,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            if (cascaded != null) {
                val (resolvedBadge, cloudDecision) = cascaded
                recordSpecialistTurn(
                    badge = if (resolvedBadge == "FAST-PATH-10MS") "GEMINI-CLOUD" else resolvedBadge,
                    role = specialistRole,
                    iteration = iteration,
                    cloudError = null,
                    succeeded = true
                )
                return@withContext cloudDecision
            } else {
                propagateCloudErrorToState()
            }
        }

        // 2. Downloaded On-Device MediaPipe LLM (`LlmInference` for `.task` / `.bin` / `.litertlm`)
        val modelPath = _state.value.activeDownloadedModelPath
        if (modelPath != null && (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.ON_DEVICE_MEDIAPIPE)) {
            val mpDecision = inferWithMediaPipeLlm(
                modelPath = modelPath,
                goal = g,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext
            )
            if (mpDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "MEDIAPIPE-NPU", lastDecisionSucceeded = true) }
                return@withContext mpDecision
            }
        }

        // 3. Local Edge LLM Server (Ollama `:11434` or llama.cpp `:8080` on phone/Termux)
        if (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.LOCAL_EDGE_SERVER) {
            val localServerDecision = inferWithLocalEdgeServer(
                goal = g,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext
            )
            if (localServerDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "LOCAL-LLM", lastDecisionSucceeded = true) }
                return@withContext localServerDecision
            }
        }

        // 4. Galaxy S25 Built-In NPU: Google AICore (`com.google.ai.edge.aicore.GenerativeModel` — Gemini Nano)
        if (_state.value.googleAiCoreInstalled && (tier == MobileAiRouteTier.AUTO_SMART || tier == MobileAiRouteTier.S25_AICORE_NANO)) {
            val nanoDecision = inferWithAiCoreGeminiNano(
                goal = g,
                iteration = iteration,
                snapshot = snapshot,
                historySteps = historySteps,
                previousPlan = previousPlan,
                chatHistory = chatHistory,
                systemContext = systemContext
            )
            if (nanoDecision != null) {
                _state.update { it.copy(lastUsedBackendBadge = "S25-NPU-NANO", lastDecisionSucceeded = true) }
                return@withContext nanoDecision
            }
        }

        // 5. Honest Offline Failure Guidance (surface exact cloud error & errorBadge; NEVER mask behind "대화 확인")
        val cloudErr = GeminiMultimodalClient.lastCloudApiErrorReason?.trim()?.takeIf { it.isNotEmpty() }
        val effectiveError = cloudErr ?: "AI API 키 또는 노트북 CLI 브리지(:18889)가 연결되지 않았습니다."
        val errorBadge = when {
            !cloudErr.isNullOrBlank() && cloudErr.contains("402") -> "AI 연결 오류 (크레딧 소진)"
            !cloudErr.isNullOrBlank() && (cloudErr.contains("401") || cloudErr.contains("403")) -> "AI 연결 오류 (API 키/권한)"
            !cloudErr.isNullOrBlank() && cloudErr.contains("429") -> "AI 연결 오류 (요청 한도)"
            !cloudErr.isNullOrBlank() && cloudErr.contains("404") -> "AI 연결 오류 (모델 미지원)"
            !canCallRemoteOrBridge && cloudErr.isNullOrBlank() -> "AI 미연결 (키/브리지 필요)"
            else -> "AI 연결 오류"
        }
        recordSpecialistTurn(
            badge = errorBadge,
            role = specialistRole,
            iteration = iteration,
            cloudError = effectiveError,
            succeeded = false
        )
        val offlineDecision = cloudClient.inferLocalOnDeviceAction(
            goal = g,
            iteration = iteration,
            snapshot = snapshot,
            historySteps = historySteps,
            previousPlan = previousPlan,
            chatHistory = chatHistory
        )
        offlineDecision.toolArgs.put("is_cloud_error", true)
        offlineDecision.toolArgs.put("error_reason", effectiveError)
        offlineDecision.toolArgs.put("error_badge", errorBadge)
        offlineDecision
    }

    private fun propagateCloudErrorToState() {
        val cloudErr = GeminiMultimodalClient.lastCloudApiErrorReason?.trim()?.takeIf { it.isNotEmpty() }
        if (cloudErr != null) {
            _state.update { it.copy(lastCloudApiError = cloudErr) }
        }
    }

    private fun recordSpecialistTurn(
        badge: String,
        role: SpecialistModelRole,
        iteration: Int,
        latencyMs: Long = 0L,
        cloudError: String? = null,
        succeeded: Boolean = true
    ) {
        val shortRole = role.roleKo.substringBefore(" (")
        val entry = "T$iteration:$badge ($shortRole)"
        val telemetry = ModelRoutingTelemetry(
            modelName = role.primaryModel,
            specialistRole = role.name,
            routeTier = _state.value.activeTier.name,
            latencyMs = latencyMs
        )
        val tripped = latencyMs > 5000L
        _state.update { current ->
            val updatedLedger = if (iteration == 1) {
                listOf(entry)
            } else {
                (current.turnModelLedger + entry).takeLast(12)
            }
            current.copy(
                lastUsedBackendBadge = badge,
                lastSpecialistRole = role.name,
                lastSpecialistReason = role.roleKo,
                turnModelLedger = updatedLedger,
                lastRoutingTelemetry = telemetry,
                isCircuitBreakerTripped = tripped,
                lastCloudApiError = cloudError,
                lastDecisionSucceeded = succeeded
            )
        }
    }

    /**
     * Delegates to already-installed AI apps (`Perplexity`, `ChatGPT`, `Voice Access`, `Bixby`)
     * STRICTLY when the user issues an explicit app-launch / app-switch imperative (e.g. "챗지피티 앱 열어줘").
     * NEVER hijacks general questions mentioning app names (e.g. "챗지피티 요금제", "퍼플렉시티 비교", "빅스비란").
     */
    private fun tryDelegateToInstalledMobileAiApp(goal: String, gLower: String): ModelTurnDecision? {
        val resolvedTarget = IntentDecomposerAndRouter.resolveTargetAppFromPrompt(goal)
        val isContextualPastReference = gLower.contains("넣은 거고") || gLower.contains("말고") || gLower.contains("아니라")
        if (isContextualPastReference) return null

        val informationalQuestionCues = listOf(
            "요금", "가격", "비용", "구독", "차이", "비교", "뭐야", "무엇", "어때", "추천",
            "공략", "방법", "알려", "설명", "뉴스", "리뷰", "장단점", "기능", "업데이트", "?"
        )
        if (informationalQuestionCues.any { gLower.contains(it) }) {
            return null
        }

        val explicitLaunchVerbs = listOf(
            "열어", "실행", "켜줘", "켜 줘", "켜서", "띄워", "앱으로", "앱 열", "로 가",
            "open ", "launch ", "start "
        )
        val hasExplicitLaunchIntent = resolvedTarget != null || explicitLaunchVerbs.any { gLower.contains(it) }
        if (!hasExplicitLaunchIntent) {
            return null
        }

        if ((gLower.contains("퍼플렉시티") || gLower.contains("perplexity")) &&
            (resolvedTarget == null || resolvedTarget.second == "ai.perplexity.app.android") &&
            _state.value.perplexityInstalled
        ) {
            val query = goal.replace(Regex("(?i)퍼플렉시티(에서|로|앱)?|perplexity|열어줘|열어|실행해줘|실행"), "").trim()
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

        if ((gLower.contains("챗지피티") || gLower.contains("chatgpt") || gLower.contains("지피티")) &&
            (resolvedTarget == null || resolvedTarget.second == "com.openai.chatgpt") &&
            _state.value.chatGptInstalled
        ) {
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
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
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

                val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory, systemContext)
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
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
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

            val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory, systemContext)
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
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
    ): ModelTurnDecision? {
        val endpoints = listOf(
            "http://127.0.0.1:11434/v1/chat/completions" to "qwen2.5:3b",
            "http://127.0.0.1:8080/v1/chat/completions" to "local-model"
        )
        val fastClient = httpClient.newBuilder()
            .connectTimeout(350, TimeUnit.MILLISECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()

        val prompt = buildCompactOnDevicePrompt(goal, iteration, snapshot, historySteps, chatHistory, systemContext)
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
        chatHistory: List<LiveChatMessage>,
        systemContext: String = ""
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
        val systemSection = if (systemContext.isNotBlank()) {
            "\n\n[보조 시스템 컨텍스트]\n${systemContext.trim().take(400)}"
        } else {
            ""
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
- DONE>$systemSection

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
                            put("press_enter", true)
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
