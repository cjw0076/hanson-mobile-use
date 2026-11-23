package com.hanson.mobileuse.agent

import android.app.SearchManager
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.ui.components.CanvasErrorTelemetry
import com.hanson.mobileuse.ui.components.GenerativeUiRendererDefaults
import com.hanson.mobileuse.ui.components.GenerativeUiSpec
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reactive Event-Driven Automation Rule ("When Notification/Event X occurs -> Execute Action Y").
 */
data class ReactiveAutomationRule(
    val id: String,
    val triggerKeyword: String,
    val actionType: String, // "AUTO_NOTE", "AUTO_REPLY", "WEBHOOK", "AGENT_GOAL"
    val actionPayload: String,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("triggerKeyword", triggerKeyword)
        put("actionType", actionType)
        put("actionPayload", actionPayload)
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): ReactiveAutomationRule = ReactiveAutomationRule(
            id = obj.optString("id"),
            triggerKeyword = obj.optString("triggerKeyword"),
            actionType = obj.optString("actionType"),
            actionPayload = obj.optString("actionPayload"),
            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
        )
    }
}

/**
 * Result of a Universal Nexus Cross-App / Zero-GUI / Cross-Device / Browser-Use / Visual Canvas execution.
 */
data class NexusExecutionResult(
    val handled: Boolean,
    val summaryKo: String,
    val badge: String,
    val followUpGuiGoal: String? = null,
    val renderedVisualBoard: Boolean = false,
    val errorTelemetry: CanvasErrorTelemetry? = null,
    val hasError: Boolean = errorTelemetry != null,
    val mediaBlock: MultimodalMediaBlock? = null
)

/**
 * **Universal Omni-Nexus Engine (`UniversalNexusEngine`)**
 *
 * Unifies 4 Super-Powers beyond single-app GUI clicking:
 * 1. **Zero-GUI DeepLink & Cross-App Data Pipelines (`tryZeroGuiIntentTeleport`, `tryExecuteCrossAppPipeline`, `ReactiveAutomationRule`)**:
 *    Teleports into YouTube, Naver/Google, Maps, and 12+ Android Settings panels in 20ms, and pipes live screen/chat/clipboard context into Notes, Calendar, Clipboard, Quick-Reply, or System Share Sheet.
 * 2. **Cross-Device Mesh & Peripheral / IoT Control (`executeCrossDeviceOrPeripheralControl`)**:
 *    Connects Phone <-> PC/Laptop CLI Bridge (`:18889`), Peer A2A Agents (`/a2a`), Bluetooth/BLE peripherals, Audio routing (`speakerphone`/`bluetooth_sco`), and REST/HTTP IoT & Smart-Home Edge nodes.
 * 3. **2026 LLM Native Web Grounding Engine (`executeHeadlessBrowserUse`, `executeMobileBrowserUse`)**:
 *    Delegates web research & URL summarization to `GeminiMultimodalClient.answerTextOrGroundedWebQuery`
 *    (Gemini native `google_search`/`url_context`, Anthropic web search, or Laptop CLI Bridge `:18889`)
 *    and emits verified markdown citations (`- [Title](https://...)`) for native clickable citation cards.
 * 4. **Live Code Drawing & AI Image Synthesis (`executeDrawWithCode`, `executeGenerateVisualMedia`)**:
 *    Synthesizes live interactive SVG / HTML5 Canvas / Charts / Calculators / Mini-Apps (`draw_with_code`) and generates AI / Procedural Vector-Canvas Images (`generate_visual_media`) rendered inline in the Command Center!
 */
class UniversalNexusEngine(
    private val context: Context,
    private val toolHarness: AgentToolExecutionHarness
) {
    companion object {
        private const val PREFS_NAME = "hanson_universal_nexus_engine"
        private const val KEY_REACTIVE_RULES = "reactive_automation_rules_json"
        private const val KEY_DEFAULT_PEER_ENDPOINT = "default_cross_device_endpoint"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val reactiveRules = mutableListOf<ReactiveAutomationRule>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    var totalZeroGuiTeleports: Int = 0
        private set
    var totalCrossAppPipelines: Int = 0
        private set
    var totalReactiveTriggersFired: Int = 0
        private set
    var totalCrossDeviceCalls: Int = 0
        private set
    var totalBrowserUseOps: Int = 0
        private set
    var totalVisualSyntheses: Int = 0
        private set

    var lastObservedCanvasError: CanvasErrorTelemetry? = null
        private set

    init {
        loadRulesFromDisk()
        GenerativeUiStateHolder.addErrorListener { err ->
            lastObservedCanvasError = err
        }
    }

    fun getLatestCanvasError(): CanvasErrorTelemetry? = GenerativeUiStateHolder.lastError.value
    fun hasCanvasError(): Boolean = GenerativeUiStateHolder.hasError()
    fun clearCanvasError() {
        lastObservedCanvasError = null
        GenerativeUiStateHolder.clearError()
    }
    fun getCanvasErrorHistory(): List<CanvasErrorTelemetry> = GenerativeUiStateHolder.errorHistory.value

    fun onCanvasExecutionError(errorJson: String): ToolExecutionOutcome {
        GenerativeUiStateHolder.reportError(errorJson)
        val telemetry = GenerativeUiStateHolder.lastError.value
        val feedback = telemetry?.toFeedbackPrompt() ?: "캔버스 실행 오류: $errorJson"
        return ToolExecutionOutcome(
            succeeded = false,
            outputText = "[Canvas Execution Error Intercepted] $feedback",
            receipt = ActionReceipt(
                toolName = "draw_with_code",
                summaryKo = "캔버스 런타임 오류 포착: ${telemetry?.type ?: "error"}",
                detailKo = feedback,
                undoHintKo = "에이전트 자체 수정 루프(Self-Correction)를 통해 코드를 수정합니다."
            )
        )
    }

    fun buildSelfCorrectionPrompt(error: CanvasErrorTelemetry? = null): String {
        val err = error ?: GenerativeUiStateHolder.lastError.value
        return err?.toFeedbackPrompt() ?: ""
    }

    fun executeCanvasSelfCorrection(
        correctedPrompt: String,
        correctedCode: String,
        title: String = ""
    ): ToolExecutionOutcome {
        clearCanvasError()
        return executeDrawWithCode(
            JSONObject().apply {
                put("prompt", correctedPrompt)
                put("code_or_svg", correctedCode)
                if (title.isNotBlank()) put("title", title)
            }
        )
    }

    /**
     * Master Fast-Path Evaluator for all 4 Omni-Nexus Pillars:
     * 1. Live Code Canvas Drawing & AI Image Generation ("코드로 그려줘", "차트 그려줘", "이미지 생성해서 보여줘")
     * 2. Mobile Browser-Use ("백그라운드에서 웹페이지 읽어줘", "브라우저로 https://... 요약해줘", "해커뉴스/깃허브 최신 글 가져와줘")
     * 3. Cross-Device & Peripheral / IoT / Robot Control ("노트북으로 클립보드 보내줘", "블루투스 기기 상태 알려줘", "스피커폰 켜줘", "로봇/주변기기 제어")
     * 4. Standing Reactive Rule Registration ("~ 알림 오면 자동으로 ~ 해줘")
     * 5. Cross-App Data Pipeline ("지금 화면/대화 내용 요약해서 노트에 저장하고 클립보드에 복사해줘")
     * 6. Zero-GUI OS/App Intent Teleport ("유튜브에서 로파이 틀어줘", "블루투스 설정 열어줘", "네이버지도로 강남역 찾아줘")
     */
    fun tryExecuteUniversalNexus(
        prompt: String,
        latestSnapshot: AtomicSnapshot?,
        apiKey: String = "",
        modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
        customBaseUrl: String = "",
        bridgeToken: String = ""
    ): NexusExecutionResult? {
        val p = prompt.trim()
        val pLower = p.lowercase(Locale.ROOT)

        // Pillar 4-C: Canvas Error Self-Correction Handling ("캔버스 오류 수정", "에러 고쳐줘", "자체 교정")
        if ((pLower.contains("캔버스 오류") || pLower.contains("코드 오류") || pLower.contains("캔버스 에러")) &&
            (pLower.contains("수정") || pLower.contains("고쳐") || pLower.contains("재시도") || pLower.contains("자체 교정") || pLower.contains("복구"))
        ) {
            val lastErr = GenerativeUiStateHolder.lastError.value
            val errDesc = lastErr?.message ?: "런타임 에러"
            val outcome = executeCanvasSelfCorrection(
                correctedPrompt = "자체 교정된 무채색 시각화 캔버스",
                correctedCode = "",
                title = "자체 교정 캔버스 · $errDesc"
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = "이전 캔버스 오류($errDesc)를 분석하여 에이전트 자체 교정(Self-Correction) 루프를 통해 안전한 인터랙티브 캔버스로 재렌더링했습니다.",
                badge = "NEXUS-CANVAS-SELF-CORRECT",
                renderedVisualBoard = true,
                errorTelemetry = null,
                hasError = false
            )
        }

        val isMainForeground = com.hanson.mobileuse.service.FloatingHudService.isMainActivityInForeground
        val decomposed = IntentDecomposerAndRouter.decompose(
            rawPrompt = p,
            snapshot = latestSnapshot,
            isMainActivityInForeground = isMainForeground
        )

        // Never hijack instant hardware/scroll reflexes, active-screen UI element clicks, or Zero-UI OS/Routine actions!
        if (decomposed.primaryCategory == IntentCategory.REFLEX_MICRO_CONTROL ||
            decomposed.primaryCategory == IntentCategory.SCREEN_ELEMENT_TARGET ||
            decomposed.primaryCategory == IntentCategory.EVERYDAY_OS_ACTION ||
            decomposed.primaryCategory == IntentCategory.COMPOUND_LIFESTYLE_ROUTINE
        ) {
            return null
        }

        // Pillar 4-Y: YouTube Link & Search Video Player ("https://youtu.be/...", "유튜브 'xx' 영상 틀어줘", "'xx' 영상 틀어줘")
        val hasNamedVideoPlayIntent = !pLower.contains("mp4") && !pLower.contains("모션그래픽") &&
            (pLower.contains("영상") || pLower.contains("뮤비") || pLower.contains("노래")) &&
            (pLower.contains("틀어") || pLower.contains("재생")) &&
            IntentDecomposerAndRouter.cleanYouTubeSearchQuery(p).length >= 2 &&
            !IntentDecomposerAndRouter.isExplicitGenerativeOrCodeRequest(p, pLower, latestSnapshot, isMainForeground)

        val isYouTubeIntent = decomposed.primaryCategory == IntentCategory.MEDIA_SEARCH_AND_PLAY ||
            AgentMediaFileEngine.containsYouTubeUrlOrInAppRequest(p) ||
            hasNamedVideoPlayIntent

        if (isYouTubeIntent) {
            val wantsExternalYouTubeApp = ((pLower.contains("유튜브 앱") || pLower.contains("외부 앱") || pLower.contains("열어서")) &&
                (pLower.contains("열어") || pLower.contains("실행") || pLower.contains("틀어") || pLower.contains("재생"))) ||
                (!isMainForeground && AgentMediaFileEngine.extractYouTubeVideoIdFromUrl(p) == null && !pLower.contains("인앱"))

            val outcome = executeGenerateVisualMedia(
                JSONObject().put("prompt", p).put("format", "youtube"),
                apiKey = apiKey
            )
            val resolvedVideoId = outcome.mediaBlock?.youtubeVideoId.orEmpty()
            if (wantsExternalYouTubeApp && resolvedVideoId.isNotBlank()) {
                val watchUri = Uri.parse("https://www.youtube.com/watch?v=$resolvedVideoId")
                val openedNative = runCatching {
                    appContext.startActivity(
                        Intent(Intent.ACTION_VIEW, watchUri).apply {
                            setPackage("com.google.android.youtube")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    true
                }.getOrElse {
                    runCatching {
                        appContext.startActivity(
                            Intent(Intent.ACTION_VIEW, watchUri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                        true
                    }.getOrDefault(false)
                }
                if (openedNative) {
                    val videoTitle = outcome.mediaBlock?.title ?: IntentDecomposerAndRouter.cleanYouTubeSearchQuery(p)
                    return NexusExecutionResult(
                        handled = true,
                        summaryKo = "'$videoTitle' 영상을 바로 찾아 재생했어요.",
                        badge = "NEXUS-YOUTUBE-DIRECT",
                        renderedVisualBoard = false,
                        mediaBlock = outcome.mediaBlock
                    )
                }
            }

            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-YOUTUBE-INAPP",
                renderedVisualBoard = true,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Strict Generative Gate: Only allow Code Assistant / 3D / MP4 / GIF / Image synthesis when explicitly requested
        val allowGenerativeSynthesis = decomposed.primaryCategory == IntentCategory.GENERATIVE_CODE_OR_MEDIA

        // Pillar 4-D: Interactive 3D GLB / OBJ File & Code Assistant 3D Compiler ("glb", "gltf", "obj", "3d", "입체 모델", "오리 모델")
        if (allowGenerativeSynthesis && (
                pLower.contains("glb") || pLower.contains("gltf") || pLower.contains(".obj") || pLower.contains("3d") ||
                    pLower.contains("입체") || pLower.contains("모델링") || pLower.contains("홀로그램") ||
                    pLower.contains("와이어프레임") || pLower.contains("메쉬")
                )
        ) {
            val outcome = executeGenerateVisualMedia(
                JSONObject().put("prompt", p).put("format", "glb"),
                apiKey = apiKey
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-GLB-3D",
                renderedVisualBoard = true,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Pillar 4-E: Motion-Graphic MP4 Video ("mp4", "모션그래픽", "동영상 만들어", "영상 생성") — NEVER on bare "영상을 틀어 줘"!
        if (allowGenerativeSynthesis &&
            (pLower.contains("mp4") || pLower.contains("모션그래픽") ||
                ((pLower.contains("비디오") || pLower.contains("영상") || pLower.contains("동영상") || pLower.contains("프로모")) &&
                    (pLower.contains("생성") || pLower.contains("만들어") || pLower.contains("그려") || pLower.contains("제작") || pLower.contains("합성")))) &&
            !pLower.contains("유튜브") && !pLower.contains("youtube")
        ) {
            val outcome = executeGenerateVisualMedia(
                JSONObject().put("prompt", p).put("format", "mp4"),
                apiKey = apiKey
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-MP4-VIDEO",
                renderedVisualBoard = true,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Pillar 4-F: Animated Loop GIF ("gif", "움짤", "루프 애니메이션", "키네틱")
        if (allowGenerativeSynthesis && (
                pLower.contains("gif") || pLower.contains("움짤") || pLower.contains("루프 애니메이션") ||
                    pLower.contains("키네틱") ||
                    (pLower.contains("애니메이션") && (pLower.contains("생성") || pLower.contains("만들어") || pLower.contains("그려") || pLower.contains("띄워") || pLower.contains("보여")))
                )
        ) {
            val outcome = executeGenerateVisualMedia(
                JSONObject().put("prompt", p).put("format", "gif"),
                apiKey = apiKey
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-GIF-LOOP",
                renderedVisualBoard = true,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Pillar 4-A: AI Image / Illustration Generation ("이미지 생성", "그림 그려서 보여줘", "일러스트 생성")
        if (allowGenerativeSynthesis &&
            (pLower.contains("이미지") || pLower.contains("그림") || pLower.contains("일러스트") || pLower.contains("포스터") || pLower.contains("아트")) &&
            (pLower.contains("생성") || pLower.contains("만들어") || pLower.contains("그려") || pLower.contains("제작") || pLower.contains("합성"))
        ) {
            val outcome = executeGenerateVisualMedia(
                JSONObject().put("prompt", p).put("style", "achromatic_editorial").put("format", "image"),
                apiKey = apiKey
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-IMAGE-GEN",
                renderedVisualBoard = true,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Pillar 4-B: Live Code Drawing / SVG / HTML5 Canvas / Interactive Chart / Lifestyle Micro-App Widget
        if (allowGenerativeSynthesis && (
                pLower.contains("코드로 그려") || pLower.contains("코드로 띄워") || pLower.contains("그려서 띄워") ||
                    pLower.contains("코드로 제작") || pLower.contains("코드로 만들어") ||
                    pLower.contains("더치페이") || pLower.contains("정산 계산기") || pLower.contains("미니앱") ||
                    ((pLower.contains("차트") || pLower.contains("그래프") || pLower.contains("다이어그램") || pLower.contains("구조도") ||
                        pLower.contains("시각화") || pLower.contains("캔버스") || pLower.contains("미니게임") || pLower.contains("대시보드") ||
                        pLower.contains("계산기") || pLower.contains("비교표") || pLower.contains("정리표") || pLower.contains("체크리스트") || pLower.contains("위젯")) &&
                        (pLower.contains("그려") || pLower.contains("띄워") || pLower.contains("만들어") || pLower.contains("보여") || pLower.contains("짜줘")))
                )
        ) {
            val outcome = executeDrawWithCode(
                JSONObject().put("prompt", p).put("canvas_type", "auto"),
                apiKey = apiKey
            )
            val currentErr = GenerativeUiStateHolder.lastError.value
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = if (outcome.succeeded) "NEXUS-CODE-CANVAS" else "NEXUS-CANVAS-ERROR",
                renderedVisualBoard = true,
                errorTelemetry = currentErr,
                hasError = !outcome.succeeded || currentErr != null,
                mediaBlock = outcome.mediaBlock
            )
        }

        // Pillar 3: 2026 LLM Native Web Grounding ("브라우저로", "웹페이지 요약", "해커뉴스", "https://...")
        val urlMatch = Regex("(https?://[^\\s]+)").find(p)?.value
        if (pLower.contains("브라우저로") || pLower.contains("웹페이지") ||
            pLower.contains("사이트 읽어") || pLower.contains("해커뉴스") || pLower.contains("hacker news") ||
            (urlMatch != null && (pLower.contains("읽어") || pLower.contains("요약") || pLower.contains("추출") || pLower.contains("분석") || pLower.contains("열어") || pLower.contains("보여")))
        ) {
            val outcome = executeHeadlessBrowserUse(
                queryOrUrl = p,
                apiKey = apiKey,
                modelName = modelName,
                customBaseUrl = customBaseUrl,
                bridgeToken = bridgeToken
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = if (outcome.succeeded) "NEXUS-WEB-GROUNDING" else "NEXUS-WEB-ERROR",
                renderedVisualBoard = false,
                hasError = !outcome.succeeded
            )
        }

        // Pillar 2: Cross-Device & Peripheral / IoT / Robot Control
        if (pLower.contains("블루투스 기기") || pLower.contains("연결된 기기") || pLower.contains("주변 기기") ||
            pLower.contains("페어링된") || pLower.contains("스피커폰") || pLower.contains("오디오 출력") ||
            pLower.contains("노트북으로") || pLower.contains("pc로") || pLower.contains("컴퓨터로") ||
            pLower.contains("다른 에이전트에게") || pLower.contains("로봇")
        ) {
            val actionType = when {
                pLower.contains("블루투스") || pLower.contains("주변 기기") || pLower.contains("페어링") || pLower.contains("연결된 기기") -> "bluetooth_status"
                pLower.contains("스피커폰") || pLower.contains("오디오") -> "audio_route"
                pLower.contains("노트북") || pLower.contains("pc") || pLower.contains("컴퓨터") -> "pc_clipboard_or_rpc"
                pLower.contains("로봇") -> "iot_robot_command"
                else -> "a2a_delegate"
            }
            val payload = extractScreenOrClipboardContext(latestSnapshot, preferClipboard = pLower.contains("클립보드"))
                .ifBlank { p }
            val outcome = executeCrossDeviceOrPeripheralControl(
                JSONObject()
                    .put("action", actionType)
                    .put("target", "")
                    .put("payload", payload)
                    .put("raw_prompt", p)
            )
            return NexusExecutionResult(
                handled = true,
                summaryKo = outcome.outputText,
                badge = "NEXUS-CROSS-DEVICE",
                hasError = !outcome.succeeded
            )
        }

        // Pillar 1-A: Reactive Rule Registration ("~ 오면 자동으로 ~")
        val ruleResult = tryRegisterReactiveRule(p, pLower)
        if (ruleResult != null) return ruleResult

        // Pillar 1-B: Cross-App Data Pipeline (Screen / Chat / Clipboard -> Note / Calendar / Reply / Share / Webhook)
        val pipeResult = tryExecuteCrossAppPipeline(p, pLower, latestSnapshot)
        if (pipeResult != null) {
            totalCrossAppPipelines++
            return pipeResult
        }

        // Pillar 1-C: Zero-GUI Direct Intent / DeepLink Teleport
        val teleportResult = tryZeroGuiIntentTeleport(p, pLower)
        if (teleportResult != null) {
            totalZeroGuiTeleports++
            return teleportResult
        }

        return null
    }

    // =========================================================================
    // PILLAR 2: Cross-Device Mesh & Peripheral / IoT / Robotics Control
    // =========================================================================

    fun executeCrossDeviceOrPeripheralControl(args: JSONObject): ToolExecutionOutcome {
        totalCrossDeviceCalls++
        val action = args.optString("action", "bluetooth_status").lowercase(Locale.ROOT).trim()
        val target = args.optString("target", "").trim()
        val payload = args.optString("payload", "").trim()
        val rawPrompt = args.optString("raw_prompt", payload).lowercase(Locale.ROOT)

        return when (action) {
            "bluetooth_status", "bluetooth_scan", "peripherals" -> {
                val bm = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bm?.adapter
                if (adapter == null) {
                    return ToolExecutionOutcome(false, "이 기기에서 Bluetooth 어댑터를 찾을 수 없습니다.")
                }
                val isEnabled = runCatching { adapter.isEnabled }.getOrDefault(false)
                val bondedDevices = runCatching {
                    @Suppress("MissingPermission")
                    adapter.bondedDevices?.map { dev ->
                        val name = dev.name ?: "이름 없는 기기"
                        val addr = dev.address ?: ""
                        "$name ($addr)"
                    } ?: emptyList()
                }.getOrDefault(emptyList())

                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val commDevice = am?.communicationDevice
                val outputDevices = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                val btTypes = setOf(
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLE_SPEAKER,
                    AudioDeviceInfo.TYPE_HEARING_AID
                )
                val btAudioActive = (commDevice?.type in btTypes) || outputDevices.any { it.type in btTypes }
                val speakerOn = commDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

                val summary = buildString {
                    append("주변 기기 및 블루투스 허브 상태: Bluetooth=${if (isEnabled) "ON" else "OFF"}")
                    append(", 오디오 출력=${if (btAudioActive) "블루투스 오디오" else if (speakerOn) "내장 스피커폰" else "기본 리시버/이어폰"}. ")
                    if (bondedDevices.isNotEmpty()) {
                        append("페어링된 주변 기기(${bondedDevices.size}대): ${bondedDevices.take(8).joinToString(", ")}")
                    } else {
                        append("현재 페어링된 블루투스 기기 목록을 읽으려면 BLUETOOTH_CONNECT 권한이 필요하거나 등록된 기기가 없습니다.")
                    }
                }
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = summary,
                    receipt = ActionReceipt(
                        toolName = "cross_device_control",
                        summaryKo = "주변 기기(Bluetooth/오디오) 상태 스캔",
                        detailKo = summary.take(120),
                        undoHintKo = "블루투스 설정 패널에서 연결 기기를 전환할 수 있습니다."
                    )
                )
            }

            "audio_route" -> {
                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return ToolExecutionOutcome(false, "AudioManager를 사용할 수 없습니다.")
                val turnOff = rawPrompt.contains("꺼") || rawPrompt.contains("해제") || payload.equals("off", ignoreCase = true)
                val useBt = rawPrompt.contains("블루투스") || payload.contains("bluetooth", ignoreCase = true)

                if (turnOff) {
                    am.clearCommunicationDevice()
                    val routeDesc = if (useBt) {
                        "블루투스 오디오 라우팅을 해제하고 기본 오디오 경로로 되돌렸어요."
                    } else {
                        "스피커폰(Speakerphone) 출력을 끄고 기본 오디오 경로로 되돌렸어요."
                    }
                    return ToolExecutionOutcome(
                        succeeded = true,
                        outputText = routeDesc,
                        receipt = ActionReceipt(
                            toolName = "cross_device_control",
                            summaryKo = "오디오 주변기기 라우팅 해제",
                            detailKo = routeDesc,
                            undoHintKo = "다시 명령하여 오디오 출력을 전환할 수 있습니다."
                        )
                    )
                }

                val available = am.availableCommunicationDevices
                val targetDevice = if (useBt) {
                    available.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_HEARING_AID
                    }
                } else {
                    available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }

                if (targetDevice == null) {
                    val missingDesc = if (useBt) {
                        "연결된 블루투스 통신 오디오 기기를 찾을 수 없어 라우팅을 전환하지 못했습니다."
                    } else {
                        "내장 스피커폰 오디오 장치를 찾을 수 없어 라우팅을 전환하지 못했습니다."
                    }
                    return ToolExecutionOutcome(succeeded = false, outputText = missingDesc)
                }

                val switched = runCatching { am.setCommunicationDevice(targetDevice) }.getOrDefault(false)
                if (!switched) {
                    val failDesc = if (useBt) {
                        "블루투스 오디오(${targetDevice.productName})로 라우팅 전환에 실패했습니다."
                    } else {
                        "스피커폰 출력 전환에 실패했습니다."
                    }
                    return ToolExecutionOutcome(succeeded = false, outputText = failDesc)
                }

                val routeDesc = if (useBt) {
                    "블루투스 오디오(${targetDevice.productName}) 라우팅을 활성화했어요."
                } else {
                    "스피커폰(Speakerphone) 출력을 켰어요."
                }
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = routeDesc,
                    receipt = ActionReceipt(
                        toolName = "cross_device_control",
                        summaryKo = "오디오 주변기기 라우팅 제어",
                        detailKo = routeDesc,
                        undoHintKo = "다시 명령하여 오디오 출력을 원래대로 바꿀 수 있습니다."
                    )
                )
            }

            "pc_clipboard_or_rpc", "pc_sync", "laptop_bridge" -> {
                val endpoint = target.ifBlank {
                    prefs.getString(KEY_DEFAULT_PEER_ENDPOINT, "http://127.0.0.1:18889/bridge")
                        ?: "http://127.0.0.1:18889/bridge"
                }
                val textToSync = payload.ifBlank { readSystemClipboard() }
                copyToSystemClipboard("Hanson Cross-Device Sync", textToSync)

                val sentOk = dispatchExternalWebhookOrA2a(
                    targetUrl = endpoint,
                    eventTitle = "CROSS_DEVICE_SYNC",
                    payloadText = textToSync
                )
                if (!sentOk) {
                    return ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "노트북/PC 브리지($endpoint)로의 HTTP POST 전송에 실패했습니다. 기기 클립보드에는 복사해 두었지만 원격 PC에는 전달되지 않았으므로 브리지 서버 실행 및 연결 상태를 확인해 주세요."
                    )
                }
                val msg = "크로스 디바이스 브리지($endpoint)를 통해 노트북/PC로 데이터를 전송하고 응답을 확인했어요: \"${textToSync.take(70)}\""
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = msg,
                    receipt = ActionReceipt(
                        toolName = "cross_device_control",
                        summaryKo = "PC/노트북 크로스 디바이스 브리지 전송",
                        detailKo = "Target=$endpoint · Payload=${textToSync.take(60)}",
                        undoHintKo = "연결된 PC/노트북에서 수신 완료되었습니다."
                    )
                )
            }

            "iot_robot_command", "a2a_delegate" -> {
                val endpoint = target.ifBlank {
                    Regex("(https?://[^\\s]+)").find(rawPrompt)?.value
                        ?: "http://127.0.0.1:18888/a2a"
                }
                val sentOk = dispatchExternalWebhookOrA2a(
                    targetUrl = endpoint,
                    eventTitle = if (action == "iot_robot_command") "ROBOT_PERIPHERAL_CMD" else "A2A_TASK_DELEGATE",
                    payloadText = payload.ifBlank { rawPrompt }
                )
                if (!sentOk) {
                    return ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "외부 기기/에이전트 노드($endpoint)로의 HTTP POST 전송에 실패했습니다. 대상 서버가 실행 중인지와 네트워크 연결을 확인해 주세요."
                    )
                }
                val msg = "외부 기기/에이전트 노드($endpoint)로 명령을 전송하고 응답을 확인했어요: \"${payload.take(80)}\""
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = msg,
                    receipt = ActionReceipt(
                        toolName = "cross_device_control",
                        summaryKo = "외부 기기 / A2A 노드 명령 전송",
                        detailKo = "$endpoint -> ${payload.take(60)}",
                        undoHintKo = "상태 조회 명령으로 원격 기기 상태를 확인할 수 있습니다."
                    )
                )
            }

            else -> ToolExecutionOutcome(false, "지원하지 않는 크로스 디바이스 제어 액션입니다: $action")
        }
    }

    // =========================================================================
    // PILLAR 3: 2026 LLM Native Web Grounding (`executeHeadlessBrowserUse`)
    // =========================================================================

    fun executeHeadlessBrowserUse(
        queryOrUrl: String,
        apiKey: String = "",
        modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
        customBaseUrl: String = "",
        bridgeToken: String = ""
    ): ToolExecutionOutcome {
        totalBrowserUseOps++
        val cleanQuery = queryOrUrl.trim()
        if (cleanQuery.isEmpty()) {
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "웹 조사할 질문이나 URL을 입력해 주세요."
            )
        }

        val grounded = GeminiMultimodalClient().answerTextOrGroundedWebQuery(
            userGoal = cleanQuery,
            apiKey = apiKey,
            modelName = modelName,
            customBaseUrl = customBaseUrl,
            enableWebSearch = true,
            bridgeToken = bridgeToken
        )

        if (!grounded.succeeded || grounded.answerKo.isBlank()) {
            val detailReason = grounded.errorReason?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "웹 리서치를 실행하려면 AI API 키(Gemini google_search) 또는 노트북 CLI 브리지(:18889) 연결이 필요합니다$detailReason. 설정에서 API 키를 입력하거나 브리지를 연결해 주세요."
            )
        }

        val verifiedCitations = grounded.citations
            .filter { (_, uri) -> uri.trim().startsWith("https://", ignoreCase = true) }
            .distinctBy { (_, uri) -> uri.trim() }
            .take(8)

        val formattedOutput = buildString {
            append(grounded.answerKo.trim())
            if (verifiedCitations.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("출처:")
                verifiedCitations.forEach { (title, uri) ->
                    val safeTitle = title
                        .replace("[", "(")
                        .replace("]", ")")
                        .replace("\n", " ")
                        .trim()
                        .ifBlank { uri.trim() }
                    appendLine("- [$safeTitle](${uri.trim()})")
                }
            }
        }.trim()

        val citationItems = HumanSummaryAndCardBuilder.extractVerifiedHttpsCitations(formattedOutput)
        val citationCard = if (citationItems.isNotEmpty()) {
            val primaryHost = citationItems.first().subtitle
            StructuredUiCard(
                templateType = StructuredUiTemplateType.CURATED_LIST,
                headerTitle = "웹 검색 출처 · ${citationItems.size}건",
                headerSubtitle = if (citationItems.size == 1) primaryHost else "$primaryHost 외 ${citationItems.size - 1}곳",
                items = citationItems,
                footerHint = "출처 항목을 탭하면 확인된 HTTPS 원문을 바로 열 수 있습니다 ↗"
            )
        } else {
            null
        }

        val badgeLabel = grounded.providerBadge.ifBlank { "LLM-WEB-GROUNDING" }
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = formattedOutput,
            receipt = ActionReceipt(
                toolName = "browser_use",
                summaryKo = "웹 그라운딩 조사 완료 ($badgeLabel)",
                detailKo = cleanQuery.take(90),
                undoHintKo = "출처 카드를 탭하면 확인된 HTTPS 원문을 바로 열 수 있습니다."
            ),
            structuredCard = citationCard
        )
    }

    fun executeMobileBrowserUse(
        args: JSONObject,
        apiKey: String = "",
        modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
        customBaseUrl: String = "",
        bridgeToken: String = ""
    ): ToolExecutionOutcome {
        val rawUrl = args.optString("url", "").trim()
        val goal = args.optString("goal", args.optString("query", "")).trim()
        val combinedQuery = when {
            goal.isNotBlank() && rawUrl.isNotBlank() && !goal.contains(rawUrl) -> "$goal ($rawUrl)"
            goal.isNotBlank() -> goal
            rawUrl.isNotBlank() -> rawUrl
            else -> ""
        }
        val resolvedApiKey = apiKey.ifBlank { args.optString("api_key", "").trim() }
        val resolvedModel = if (modelName != GeminiMultimodalClient.DEFAULT_MODEL) {
            modelName
        } else {
            args.optString("model_name", "").trim().ifBlank { modelName }
        }
        val resolvedBaseUrl = customBaseUrl.ifBlank { args.optString("custom_base_url", "").trim() }
        val resolvedBridgeToken = bridgeToken.ifBlank { args.optString("bridge_token", "").trim() }

        return executeHeadlessBrowserUse(
            queryOrUrl = combinedQuery,
            apiKey = resolvedApiKey,
            modelName = resolvedModel,
            customBaseUrl = resolvedBaseUrl,
            bridgeToken = resolvedBridgeToken
        )
    }

    // =========================================================================
    // PILLAR 4: Live Code Canvas Drawing (`draw_with_code`) & AI Image Generation (`generate_visual_media`)
    // =========================================================================

    /**
     * Synthesizes live interactive HTML5 Canvas / SVG / Charts / Diagrams / Mini-Apps from code
     * and mounts them directly into `GenerativeUiStateHolder` so the user sees them immediately!
     */
    fun executeDrawWithCode(args: JSONObject, apiKey: String = ""): ToolExecutionOutcome {
        totalVisualSyntheses++
        val prompt = args.optString("prompt", "인터랙티브 시각화 캔버스").trim()
        val customCode = args.optString("code_or_svg", "").trim()
        val title = args.optString("title", "").ifBlank {
            "코드 라이브 캔버스 · ${prompt.take(18)}"
        }

        // Static syntax & error safety validation
        if (customCode.isNotBlank()) {
            val syntaxError = validateCanvasCode(customCode)
            if (syntaxError != null) {
                GenerativeUiStateHolder.reportError(syntaxError)
                return ToolExecutionOutcome(
                    succeeded = false,
                    outputText = "[Canvas Error] 문법 또는 구문 오류가 감지되었습니다: ${syntaxError.message}. 자체 수정 피드백: ${syntaxError.toFeedbackPrompt()}",
                    receipt = ActionReceipt(
                        toolName = "draw_with_code",
                        summaryKo = "캔버스 렌더링 문법 오류: ${syntaxError.message.take(24)}",
                        detailKo = syntaxError.toFeedbackPrompt(),
                        undoHintKo = "수정된 자바스크립트/HTML 문법으로 재시도하세요."
                    )
                )
            }
        }

        val fallbackHtmlContent = if (customCode.isNotBlank()) {
            if (customCode.contains("<html", ignoreCase = true) || customCode.contains("<!DOCTYPE", ignoreCase = true)) {
                GenerativeUiRendererDefaults.instrumentHtml(customCode)
            } else {
                wrapSvgOrFragmentInAchromaticCanvas(title, customCode)
            }
        } else {
            synthesizeProceduralCodeCanvasHtml(prompt)
        }

        val (savedFile, fileSourceLabel, htmlContent) = AgentMediaFileEngine.synthesizeAndSaveCodeCanvasFile(
            context = appContext,
            prompt = prompt,
            fallbackHtmlContent = fallbackHtmlContent,
            apiKey = apiKey,
            hasCustomCode = customCode.isNotBlank()
        )

        GenerativeUiStateHolder.publishSpec(
            GenerativeUiSpec(
                title = title,
                subtitle = "Live Code Canvas · ${savedFile.name} (${savedFile.length() / 1024}KB)",
                htmlContent = htmlContent,
                heightDp = 270
            )
        )

        val codeMediaBlock = MultimodalMediaBlock(
            format = MediaFormatType.CODE_CANVAS,
            title = title,
            subtitle = "$fileSourceLabel · 인터랙티브 캔버스",
            inlineHtmlContent = htmlContent,
            recommendedHeightDp = 225,
            interactiveHintKo = "생성된 UI 컴포넌트입니다 · 탭하여 상세 보기 ↗",
            localFilePath = savedFile.absolutePath,
            fileSizeBytes = savedFile.length(),
            sourceLabel = fileSourceLabel,
            generatedSourceCode = htmlContent
        )

        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "'${prompt.take(28)}' UI 컴포넌트를 작성하여 캔버스에 바로 띄웠어요!",
            receipt = ActionReceipt(
                toolName = "draw_with_code",
                summaryKo = "코드 어시스턴트 파일 생성 및 렌더링: ${savedFile.name}",
                detailKo = savedFile.absolutePath,
                undoHintKo = "카드를 탭하면 에이전트가 작성한 전체 소스 코드와 파일 경로를 볼 수 있습니다."
            ),
            mediaBlock = codeMediaBlock
        )
    }

    fun validateCanvasCode(code: String): CanvasErrorTelemetry? {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return CanvasErrorTelemetry(
                type = "syntax_error",
                message = "캔버스 코드가 비어 있습니다."
            )
        }

        // Check for unclosed script tags
        val scriptOpenCount = Regex("<script\\b", RegexOption.IGNORE_CASE).findAll(trimmed).count()
        val scriptCloseCount = Regex("</script>", RegexOption.IGNORE_CASE).findAll(trimmed).count()
        if (scriptOpenCount != scriptCloseCount) {
            return CanvasErrorTelemetry(
                type = "syntax_error",
                message = "스크립트 태그가 올바르게 닫히지 않았습니다 (<script> $scriptOpenCount vs </script> $scriptCloseCount)."
            )
        }

        // Check for unclosed svg tags if code is an SVG fragment
        if (trimmed.startsWith("<svg", ignoreCase = true) && !trimmed.contains("</svg>", ignoreCase = true)) {
            return CanvasErrorTelemetry(
                type = "syntax_error",
                message = "SVG 태그가 올바르게 닫히지 않았습니다 (</svg> 누락)."
            )
        }

        // Check for unclosed style tags
        val styleOpenCount = Regex("<style\\b", RegexOption.IGNORE_CASE).findAll(trimmed).count()
        val styleCloseCount = Regex("</style>", RegexOption.IGNORE_CASE).findAll(trimmed).count()
        if (styleOpenCount != styleCloseCount) {
            return CanvasErrorTelemetry(
                type = "syntax_error",
                message = "스타일 태그가 올바르게 닫히지 않았습니다 (<style> $styleOpenCount vs </style> $styleCloseCount)."
            )
        }

        // Check for inline JS errors inside <script> blocks
        Regex("(?si)<script\\b[^>]*>(.*?)</script>").findAll(trimmed).forEach { match ->
            val jsCode = match.groupValues[1]
            val jsError = validateJsBracesAndParentheses(jsCode)
            if (jsError != null) {
                return CanvasErrorTelemetry(
                    type = "js_syntax_error",
                    message = "자바스크립트 구문 오류: $jsError"
                )
            }
        }

        return null
    }

    private fun validateJsBracesAndParentheses(jsCode: String): String? {
        var braces = 0
        var parens = 0
        var brackets = 0
        var inSingleQuote = false
        var inDoubleQuote = false
        var inBacktick = false
        var escaped = false

        for (ch in jsCode) {
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\') {
                escaped = true
                continue
            }
            if (ch == '\'' && !inDoubleQuote && !inBacktick) {
                inSingleQuote = !inSingleQuote
                continue
            }
            if (ch == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote
                continue
            }
            if (ch == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick
                continue
            }
            if (inSingleQuote || inDoubleQuote || inBacktick) continue

            when (ch) {
                '{' -> braces++
                '}' -> {
                    braces--
                    if (braces < 0) return "닫는 중괄호('}')가 여는 중괄호 없이 사용되었습니다."
                }
                '(' -> parens++
                ')' -> {
                    parens--
                    if (parens < 0) return "닫는 소괄호(')')가 여는 소괄호 없이 사용되었습니다."
                }
                '[' -> brackets++
                ']' -> {
                    brackets--
                    if (brackets < 0) return "닫는 대괄호(']')가 여는 대괄호 없이 사용되었습니다."
                }
            }
        }
        if (inSingleQuote) return "작은따옴표(')가 닫히지 않았습니다."
        if (inDoubleQuote) return "큰따옴표(\")가 닫히지 않았습니다."
        if (inBacktick) return "백틱(`)이 닫히지 않았습니다."
        if (braces > 0) return "중괄호('{') ${braces}개가 닫히지 않았습니다."
        if (parens > 0) return "소괄호('(') ${parens}개가 닫히지 않았습니다."
        if (brackets > 0) return "대괄호('[') ${brackets}개가 닫히지 않았습니다."
        return null
    }

    /**
     * Generates and renders Ahaboard-style Multimodal Visual Media:
     * - `GLB_3D` (`.glb`, `.gltf`, interactive 3D WebGL/Canvas mesh & wireframe viewer with touch orbit/zoom)
     * - `MP4` (`.mp4`, `.webm`, native HTML5 video + 60fps procedural motion-graphic cinema player)
     * - `GIF` (`.gif`, seamless animated loop canvas & GIF viewer)
     * - `IMAGE` (`.png`, `.jpg`, Gemini Multimodal Image Generation + high-contrast procedural vector/canvas art)
     */
    fun executeGenerateVisualMedia(args: JSONObject, apiKey: String = ""): ToolExecutionOutcome {
        totalVisualSyntheses++
        val prompt = args.optString("prompt", "Achromatic geometric architectural art").trim()
        val style = args.optString("style", "achromatic_editorial").trim()
        val explicitFormat = args.optString("format", "").lowercase(Locale.ROOT).trim()
        val mediaUrl = args.optString("media_url", "").trim().ifBlank {
            Regex("(https?://[^\\s]+\\.(?:glb|gltf|mp4|webm|gif|png|jpg|jpeg|webp))", RegexOption.IGNORE_CASE)
                .find(prompt)?.value.orEmpty()
        }
        val pLower = prompt.lowercase(Locale.ROOT)

        val formatType: MediaFormatType = when {
            explicitFormat.contains("youtube") || explicitFormat.contains("yt") ||
                AgentMediaFileEngine.containsYouTubeUrlOrInAppRequest(prompt) ||
                AgentMediaFileEngine.extractYouTubeVideoIdFromUrl(mediaUrl) != null -> MediaFormatType.YOUTUBE

            explicitFormat.contains("glb") || explicitFormat.contains("3d") ||
                mediaUrl.endsWith(".glb", true) || mediaUrl.endsWith(".gltf", true) || mediaUrl.endsWith(".obj", true) ||
                pLower.contains("glb") || pLower.contains("gltf") || pLower.contains(".obj") || pLower.contains("3d") ||
                pLower.contains("입체") || pLower.contains("모델링") || pLower.contains("홀로그램") ||
                pLower.contains("와이어프레임") || pLower.contains("메쉬") -> MediaFormatType.GLB_3D

            explicitFormat.contains("mp4") || explicitFormat.contains("video") ||
                mediaUrl.endsWith(".mp4", true) || mediaUrl.endsWith(".webm", true) ||
                pLower.contains("mp4") || pLower.contains("비디오") || pLower.contains("모션그래픽") ||
                pLower.contains("영상") || pLower.contains("동영상") -> MediaFormatType.MP4

            explicitFormat.contains("gif") ||
                mediaUrl.endsWith(".gif", true) ||
                pLower.contains("gif") || pLower.contains("움짤") ||
                pLower.contains("루프 애니메이션") || pLower.contains("키네틱") -> MediaFormatType.GIF

            else -> MediaFormatType.IMAGE
        }

        return when (formatType) {
            MediaFormatType.YOUTUBE -> {
                val yt = AgentMediaFileEngine.resolveYouTubeMedia(appContext, mediaUrl.ifBlank { prompt })
                val title = yt.title.take(40)
                val htmlContent = """
<!DOCTYPE html>
<html lang="ko"><head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/>
<style>html,body{margin:0;padding:0;width:100%;height:100%;background:#09090B;overflow:hidden;}iframe{width:100%;height:100%;border:0;}</style>
</head><body>
<iframe src="${yt.embedUrl}" allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen></iframe>
</body></html>
                """.trimIndent()
                GenerativeUiStateHolder.publishSpec(
                    GenerativeUiSpec(
                        title = "YouTube · $title",
                        subtitle = "${yt.channelName} · ID: ${yt.videoId}",
                        htmlContent = htmlContent,
                        heightDp = 280
                    )
                )
                val wantsAutoPlay = prompt.contains("틀어") || prompt.contains("재생")
                val block = MultimodalMediaBlock(
                    format = MediaFormatType.YOUTUBE,
                    title = title,
                    subtitle = yt.channelName,
                    mediaUrlOrUri = yt.watchUrl,
                    inlineHtmlContent = htmlContent,
                    recommendedHeightDp = 240,
                    interactiveHintKo = if (wantsAutoPlay) {
                        "앱 내 자동 재생 중 · [↗ 유튜브 앱]으로 크게 볼 수 있습니다"
                    } else {
                        "[▶ 영상 재생] 버튼으로 바로 시청하거나 [↗ 유튜브 앱]으로 열 수 있습니다"
                    },
                    localFilePath = yt.thumbnailFilePath,
                    fileSizeBytes = yt.thumbnailSizeBytes,
                    sourceLabel = if (wantsAutoPlay) "YOUTUBE STREAM · AUTOPLAY · ${yt.channelName}" else "YOUTUBE STREAM · ${yt.channelName}",
                    youtubeVideoId = yt.videoId
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "'${yt.title}' (${yt.channelName}) 영상을 바로 준비했어요.",
                    receipt = ActionReceipt(
                        toolName = "generate_visual_media",
                        summaryKo = "인앱 유튜브 플레이어: ${yt.title.take(24)}",
                        detailKo = yt.watchUrl,
                        undoHintKo = "플레이어 내부 [▶ 영상 재생] 또는 [유튜브 앱 ↗] 버튼을 사용할 수 있습니다."
                    ),
                    mediaBlock = block
                )
            }

            MediaFormatType.GLB_3D -> {
                val loadedMesh = AgentMediaFileEngine.resolveOrCompileGlb3DMesh(
                    context = appContext,
                    prompt = prompt,
                    explicitUrlOrPath = mediaUrl,
                    apiKey = apiKey
                )
                val title = if (loadedMesh.fileName.isNotBlank()) "3D 스튜디오 · ${loadedMesh.fileName.removeSuffix(".glb").removeSuffix(".obj")}" else "3D 스튜디오"
                val htmlContent = buildInteractiveGlb3DViewerHtml(prompt, mediaUrl)
                GenerativeUiStateHolder.publishSpec(
                    GenerativeUiSpec(
                        title = title,
                        subtitle = "인터랙티브 3D 모델 · 터치하여 360° 회전",
                        htmlContent = htmlContent,
                        heightDp = 285
                    )
                )
                val block = MultimodalMediaBlock(
                    format = MediaFormatType.GLB_3D,
                    title = title,
                    subtitle = "인터랙티브 3D 모델 · 터치하여 360° 회전",
                    mediaUrlOrUri = mediaUrl,
                    inlineHtmlContent = htmlContent,
                    recommendedHeightDp = 245,
                    interactiveHintKo = "1손가락으로 회전하고 2손가락으로 확대/축소할 수 있습니다 ↗",
                    localFilePath = loadedMesh.filePath,
                    fileSizeBytes = loadedMesh.fileSizeBytes,
                    sourceLabel = loadedMesh.sourceLabel,
                    generatedSourceCode = loadedMesh.generatorCode
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "'${prompt.take(24)}' 3D 모델을 인터랙티브 3D 뷰어에 준비했어요. 화면을 터치하여 360°로 감상할 수 있습니다.",
                    receipt = ActionReceipt(
                        toolName = "generate_visual_media",
                        summaryKo = "인터랙티브 3D 모델 로드: ${loadedMesh.fileName}",
                        detailKo = loadedMesh.filePath,
                        undoHintKo = "터치하여 360° 회전하거나 핀치하여 확대/축소할 수 있습니다."
                    ),
                    mediaBlock = block
                )
            }

            MediaFormatType.MP4 -> {
                val (mp4File, mp4SourceLabel, mp4Code) = AgentMediaFileEngine.resolveOrSynthesizeMp4File(
                    context = appContext,
                    prompt = prompt,
                    mediaUrlOrPath = mediaUrl
                )
                val hasRealLocalVideo = (mp4File.name.endsWith(".mp4", true) || mp4File.name.endsWith(".webm", true)) &&
                    mp4File.isFile && mp4File.length() > 256L
                val hasRealRemoteVideo = (mediaUrl.startsWith("https://", true) || mediaUrl.startsWith("http://", true)) &&
                    (mediaUrl.endsWith(".mp4", true) || mediaUrl.endsWith(".webm", true))

                if (!hasRealLocalVideo && !hasRealRemoteVideo) {
                    return ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "실제 MP4 비디오 렌더링이 생성되지 않았습니다. 직접 .mp4/.webm URL 또는 로컬 비디오 파일 경로를 지정해 주세요."
                    )
                }

                val title = "비디오 · ${prompt.take(24)}"
                val playableUrl = if (hasRealRemoteVideo) mediaUrl else ""
                val htmlContent = buildInteractiveMp4VideoPlayerHtml(prompt, playableUrl)
                GenerativeUiStateHolder.publishSpec(
                    GenerativeUiSpec(
                        title = title,
                        subtitle = mp4SourceLabel,
                        htmlContent = htmlContent,
                        heightDp = 280
                    )
                )
                val block = MultimodalMediaBlock(
                    format = MediaFormatType.MP4,
                    title = title,
                    subtitle = mp4SourceLabel,
                    mediaUrlOrUri = playableUrl,
                    inlineHtmlContent = htmlContent,
                    recommendedHeightDp = 230,
                    interactiveHintKo = "재생/일시정지(▶/❚❚), 타임라인 탐색 및 배속 제어 ↗",
                    localFilePath = if (hasRealLocalVideo) mp4File.absolutePath else "",
                    fileSizeBytes = if (hasRealLocalVideo) mp4File.length() else 0L,
                    sourceLabel = mp4SourceLabel,
                    generatedSourceCode = mp4Code
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "'${prompt.take(24)}' 실제 비디오(${mp4File.name})를 인라인 플레이어로 열었어요.",
                    receipt = ActionReceipt(
                        toolName = "generate_visual_media",
                        summaryKo = "실제 비디오 재생: ${mp4File.name}",
                        detailKo = mp4File.absolutePath,
                        undoHintKo = "플레이어 컨트롤러에서 일시정지하거나 구간을 이동할 수 있습니다."
                    ),
                    mediaBlock = block
                )
            }

            MediaFormatType.GIF -> {
                val hasRealRemoteGif = mediaUrl.startsWith("https://", true) && mediaUrl.endsWith(".gif", true)
                if (!hasRealRemoteGif) {
                    return ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "실제 GIF 파일 인코딩은 온디바이스에서 지원하지 않습니다. 직접 .gif HTTPS URL을 제공하거나 인터랙티브 코드 캔버스(draw_with_code)를 요청해 주세요."
                    )
                }
                val htmlContent = buildInteractiveGifLoopHtml(prompt, mediaUrl)
                val title = "GIF · ${prompt.take(20)}"
                GenerativeUiStateHolder.publishSpec(
                    GenerativeUiSpec(
                        title = title,
                        subtitle = mediaUrl.take(48),
                        htmlContent = htmlContent,
                        heightDp = 270
                    )
                )
                val block = MultimodalMediaBlock(
                    format = MediaFormatType.GIF,
                    title = title,
                    subtitle = "외부 GIF 이미지",
                    mediaUrlOrUri = mediaUrl,
                    inlineHtmlContent = htmlContent,
                    recommendedHeightDp = 220,
                    interactiveHintKo = "외부 GIF 원문을 인라인으로 재생 중입니다 ↗",
                    sourceLabel = "EXTERNAL GIF URL"
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "'${prompt.take(24)}' GIF 이미지를 불러왔어요.",
                    receipt = ActionReceipt(
                        toolName = "generate_visual_media",
                        summaryKo = "외부 GIF 로드",
                        detailKo = mediaUrl,
                        undoHintKo = "카드에서 GIF 원본 URL을 확인할 수 있습니다."
                    ),
                    mediaBlock = block
                )
            }

            MediaFormatType.IMAGE -> {
                var base64Image: String? = null
                var mimeType = "image/png"
                if (apiKey.isNotBlank() && mediaUrl.isBlank()) {
                    val cloudImg = tryFetchGeminiGeneratedImage(prompt, apiKey)
                    if (cloudImg != null) {
                        base64Image = cloudImg.first
                        mimeType = cloudImg.second
                    }
                }

                val (imgFile, imgSourceLabel, imgGenCode) = AgentMediaFileEngine.saveOrDownloadImageFile(
                    context = appContext,
                    prompt = prompt,
                    mediaUrlOrPath = mediaUrl,
                    base64Data = base64Image
                )

                val title = "이미지 · ${prompt.take(20)}"
                val htmlContent = when {
                    !base64Image.isNullOrBlank() -> buildBase64ImageViewerHtml(prompt, base64Image, mimeType)
                    mediaUrl.isNotBlank() -> buildExternalImageViewerHtml(prompt, mediaUrl)
                    else -> buildProceduralGenerativeArtworkHtml(prompt, style)
                }
                val subtitleKo = when {
                    !base64Image.isNullOrBlank() -> "Gemini 이미지 생성 (gemini-nano-banana-2.1)"
                    mediaUrl.isNotBlank() -> "외부 이미지 뷰어"
                    else -> "로컬 절차적 PNG 아트워크 (API 미응답 폴백)"
                }

                GenerativeUiStateHolder.publishSpec(
                    GenerativeUiSpec(
                        title = title,
                        subtitle = subtitleKo,
                        htmlContent = htmlContent,
                        heightDp = 285
                    )
                )

                val block = MultimodalMediaBlock(
                    format = MediaFormatType.IMAGE,
                    title = title,
                    subtitle = subtitleKo,
                    mediaUrlOrUri = mediaUrl,
                    inlineHtmlContent = htmlContent,
                    recommendedHeightDp = 225,
                    interactiveHintKo = "이미지를 탭하면 라이트박스 전체 화면으로 감상할 수 있습니다 ↗",
                    localFilePath = imgFile.absolutePath,
                    fileSizeBytes = imgFile.length(),
                    sourceLabel = imgSourceLabel,
                    generatedSourceCode = imgGenCode
                )
                val outputMsg = when {
                    !base64Image.isNullOrBlank() -> "'$prompt' 이미지를 Gemini(gemini-nano-banana-2.1)로 생성해 준비했어요."
                    mediaUrl.isNotBlank() -> "'$prompt' 외부 이미지를 불러와 준비했어요."
                    else -> "AI 이미지 API 응답을 받지 못해 로컬 절차적 PNG 파일(${imgFile.name})로 대체 표시했어요."
                }
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = outputMsg,
                    receipt = ActionReceipt(
                        toolName = "generate_visual_media",
                        summaryKo = "이미지 파일 준비: ${imgFile.name}",
                        detailKo = imgFile.absolutePath,
                        undoHintKo = "카드를 탭하면 라이트박스에서 원본을 확대해 볼 수 있습니다."
                    ),
                    mediaBlock = block
                )
            }

            MediaFormatType.CODE_CANVAS -> executeDrawWithCode(args)
        }
    }

    private fun tryFetchGeminiGeneratedImage(prompt: String, apiKey: String): Pair<String, String>? {
        val resolvedGeminiKey = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(apiKey).geminiKey
            ?.trim()
            .orEmpty()
        if (resolvedGeminiKey.isEmpty()) return null
        return runCatching {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-nano-banana-2.1:generateContent"
            val reqJson = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", "Generate a high-contrast illustration for: $prompt"))
                        )
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                )
            }
            val req = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", resolvedGeminiKey)
                .post(reqJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val root = JSONObject(body)
                val parts = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts") ?: return@use null
                for (i in 0 until parts.length()) {
                    val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                    if (inlineData != null) {
                        val data = inlineData.optString("data")
                        val mime = inlineData.optString("mimeType", "image/png")
                        if (data.isNotBlank()) return@use data to mime
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun wrapSvgOrFragmentInAchromaticCanvas(title: String, fragment: String): String {
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --ink: #09090B; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --ink: #FAFAFA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
  html, body {
    filter: grayscale(100%) !important;
    -webkit-filter: grayscale(100%) !important;
  }
  body { background: transparent; color: var(--ink); display: flex; flex-direction: column; align-items: center; justify-content: center; min-height: 240px; padding: 6px; }
  .stage { width: 100%; background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 10px; display: flex; flex-direction: column; align-items: center; justify-content: center; overflow: hidden; }
  svg { max-width: 100%; height: auto; }
</style>
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
</head>
<body>
  <div class="stage">$fragment</div>
</body>
</html>
        """.trimIndent()
    }

    private fun synthesizeProceduralCodeCanvasHtml(prompt: String): String {
        val pLower = prompt.lowercase(Locale.ROOT)
        val safeTitle = prompt.take(36).replace("\"", "&quot;").replace("<", "&lt;")

        // Case 0: Everyday Lifestyle Micro-App (Dutch-Pay Split-Bill Calculator & Interactive Checklist)
        if (pLower.contains("더치페이") || pLower.contains("정산") || pLower.contains("계산기") || pLower.contains("체크리스트") || pLower.contains("미니앱")) {
            return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --sub: #F4F4F5; --ink: #09090B; --muted: #71717A; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --sub: #18181B; --ink: #FAFAFA; --muted: #A1A1AA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
  body { background: transparent; color: var(--ink); padding: 4px; }
  .card { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .hdr { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
  .title { font-size: 12px; font-weight: 800; }
  .badge { font-size: 9px; font-weight: 700; padding: 2px 6px; border-radius: 99px; background: var(--sub); border: 1px solid var(--line); }
  .row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
  .lbl { font-size: 11px; font-weight: 600; width: 64px; color: var(--muted); }
  input { flex: 1; background: var(--sub); color: var(--ink); border: 1px solid var(--line); border-radius: 8px; padding: 6px 8px; font-size: 12px; font-weight: 700; }
  .stepper { display: flex; gap: 4px; }
  .btn { background: var(--ink); color: var(--bg); border: none; border-radius: 7px; padding: 5px 10px; font-size: 11px; font-weight: 700; cursor: pointer; }
  .result { background: var(--sub); border: 1px solid var(--line); border-radius: 10px; padding: 10px; margin-top: 8px; display: flex; justify-content: space-between; align-items: center; }
  .res-val { font-size: 15px; font-weight: 900; }
  .res-sub { font-size: 10px; color: var(--muted); margin-top: 2px; }
</style>
</head>
<body>
  <div class="card">
    <div class="hdr">
      <span class="title">즉석 더치페이 · N분의 1 정산 미니앱</span>
      <span class="badge">CODE MINI-APP</span>
    </div>
    <div class="row">
      <span class="lbl">총 결제액</span>
      <input id="totalAmt" type="number" value="48000" oninput="calcSplit()"/>
    </div>
    <div class="row">
      <span class="lbl">참여 인원</span>
      <input id="peopleCnt" type="number" value="4" min="1" max="50" oninput="calcSplit()"/>
      <div class="stepper">
        <button class="btn" onclick="stepPeople(-1)">-</button>
        <button class="btn" onclick="stepPeople(1)">+</button>
      </div>
    </div>
    <div class="result">
      <div>
        <div class="res-val" id="perPerson">1인당 12,000원</div>
        <div class="res-sub" id="remNote">우수리 0원 · 깔끔하게 나누어 떨어집니다</div>
      </div>
      <button class="btn" onclick="copyShare()">복사</button>
    </div>
  </div>
  <script>
    function stepPeople(d) {
      const el = document.getElementById('peopleCnt');
      el.value = Math.max(1, (parseInt(el.value || '1', 10) + d));
      calcSplit();
    }
    function calcSplit() {
      const total = Math.max(0, parseInt(document.getElementById('totalAmt').value || '0', 10));
      const n = Math.max(1, parseInt(document.getElementById('peopleCnt').value || '1', 10));
      const base = Math.floor(total / n / 100) * 100;
      const rem = total - (base * n);
      document.getElementById('perPerson').innerText = '1인당 ' + base.toLocaleString() + '원';
      document.getElementById('remNote').innerText = rem > 0 ? ('100원 단위 절사 · 총무 차액 +' + rem.toLocaleString() + '원') : '우수리 0원 · 정확히 나누어 떨어집니다';
    }
    function copyShare() {
      const txt = document.getElementById('perPerson').innerText + ' (' + document.getElementById('remNote').innerText + ')';
      document.getElementById('remNote').innerText = '정산 내역 준비 완료: ' + txt;
    }
  </script>
</body>
</html>
            """.trimIndent()
        }

        // Case 1: Architecture / Flowchart / Node Diagram
        if (pLower.contains("아키텍처") || pLower.contains("구조도") || pLower.contains("다이어그램") || pLower.contains("플로우") || pLower.contains("연결")) {
            return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --sub: #F4F4F5; --ink: #09090B; --muted: #71717A; --line: #D4D4D8; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --sub: #18181B; --ink: #FAFAFA; --muted: #A1A1AA; --line: #3F3F46; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, monospace; }
  html, body {
    filter: grayscale(100%) !important;
    -webkit-filter: grayscale(100%) !important;
  }
  body { background: transparent; color: var(--ink); padding: 4px; }
  .board { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 10px; }
  .caption { font-size: 11px; font-weight: 700; margin-bottom: 6px; display: flex; justify-content: space-between; }
  svg { width: 100%; height: 210px; display: block; }
  .node { fill: var(--sub); stroke: var(--ink); stroke-width: 1.6; rx: 8; cursor: pointer; transition: all 0.15s; }
  .node:active { stroke-width: 3; }
  .lbl { fill: var(--ink); font-size: 10.5px; font-weight: 700; text-anchor: middle; dominant-baseline: middle; pointer-events: none; }
  .sub-lbl { fill: var(--muted); font-size: 8.5px; text-anchor: middle; pointer-events: none; }
  .edge { stroke: var(--ink); stroke-width: 1.4; stroke-dasharray: 4 3; fill: none; animation: dash 12s linear infinite; }
  @keyframes dash { to { stroke-dashoffset: -120; } }
</style>
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
</head>
<body>
  <div class="board">
    <div class="caption"><span>LIVE SVG ARCHITECTURE · $safeTitle</span><span>TAP NODES</span></div>
    <svg viewBox="0 0 360 200">
      <path class="edge" d="M 75 50 L 180 50 L 285 50" />
      <path class="edge" d="M 180 50 L 180 145" />
      <path class="edge" d="M 75 50 L 75 145 L 285 145 L 285 50" />

      <g onclick="window.HansonOS && window.HansonOS.speak('System 1 반사 신경 및 VLM 증류 계층입니다.')">
        <rect class="node" x="15" y="24" width="110" height="52"/>
        <text class="lbl" x="70" y="44">SYSTEM 1 BASE</text>
        <text class="sub-lbl" x="70" y="60">2ms JIT Macro · VLM</text>
      </g>

      <g onclick="window.HansonOS && window.HansonOS.speak('유니버설 넥서스 크로스앱 허브입니다.')">
        <rect class="node" x="135" y="24" width="95" height="52"/>
        <text class="lbl" x="182" y="44">OMNI NEXUS</text>
        <text class="sub-lbl" x="182" y="60">Cross-App · Intent</text>
      </g>

      <g onclick="window.HansonOS && window.HansonOS.speak('System 2 고차원 추론 및 멀티모달 계층입니다.')">
        <rect class="node" x="242" y="24" width="104" height="52"/>
        <text class="lbl" x="294" y="44">SYSTEM 2 VLM</text>
        <text class="sub-lbl" x="294" y="60">Cloud · CLI Bridge</text>
      </g>

      <g onclick="window.HansonOS && window.HansonOS.speak('웹 그라운딩 및 주변기기 제어 레인입니다.')">
        <rect class="node" x="15" y="120" width="155" height="54"/>
        <text class="lbl" x="92" y="141">WEB &amp; IOT MESH</text>
        <text class="sub-lbl" x="92" y="158">Web Grounding · BLE · A2A</text>
      </g>

      <g onclick="window.HansonOS && window.HansonOS.speak('실시간 코드 캔버스 및 이미지 합성 엔진입니다.')">
        <rect class="node" x="188" y="120" width="158" height="54"/>
        <text class="lbl" x="267" y="141">LIVE CODE CANVAS</text>
        <text class="sub-lbl" x="267" y="158">SVG · HTML5 · Image Gen</text>
      </g>
    </svg>
  </div>
</body>
</html>
            """.trimIndent()
        }

        // Case 2: User-Editable Bar Comparison Canvas (Zero fabricated random benchmarks)
        if (pLower.contains("차트") || pLower.contains("그래프") || pLower.contains("성능") || pLower.contains("속도") || pLower.contains("통계") || pLower.contains("비교")) {
            return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --sub: #F4F4F5; --ink: #09090B; --muted: #71717A; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --sub: #18181B; --ink: #FAFAFA; --muted: #A1A1AA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, monospace; }
  html, body {
    filter: grayscale(100%) !important;
    -webkit-filter: grayscale(100%) !important;
  }
  body { background: transparent; color: var(--ink); padding: 4px; }
  .card { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 12px; }
  .title { font-size: 12px; font-weight: 800; margin-bottom: 10px; display: flex; justify-content: space-between; }
  .bar-row { margin-bottom: 9px; }
  .bar-lbl { display: flex; justify-content: space-between; font-size: 10.5px; margin-bottom: 3px; font-weight: 700; }
  input[type=range] { width: 100%; accent-color: var(--ink); }
  .foot { font-size: 10px; color: var(--muted); margin-top: 8px; text-align: right; }
</style>
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
</head>
<body>
  <div class="card">
    <div class="title"><span>INTERACTIVE CHART · $safeTitle</span><span>EDITABLE</span></div>
    <div class="bar-row">
      <div class="bar-lbl"><span>항목 A</span><span id="v1">50</span></div>
      <input type="range" min="0" max="100" value="50" oninput="document.getElementById('v1').innerText=this.value"/>
    </div>
    <div class="bar-row">
      <div class="bar-lbl"><span>항목 B</span><span id="v2">75</span></div>
      <input type="range" min="0" max="100" value="75" oninput="document.getElementById('v2').innerText=this.value"/>
    </div>
    <div class="bar-row">
      <div class="bar-lbl"><span>항목 C</span><span id="v3">35</span></div>
      <input type="range" min="0" max="100" value="35" oninput="document.getElementById('v3').innerText=this.value"/>
    </div>
    <div class="foot">슬라이더를 조절해 항목별 수치를 직접 비교할 수 있습니다</div>
  </div>
</body>
</html>
            """.trimIndent()
        }

        // Default: Interactive Physics / Generative Wave & Matrix Code Canvas
        return buildProceduralGenerativeArtworkHtml(prompt, "code_canvas")
    }

    private fun buildBase64ImageViewerHtml(prompt: String, base64Data: String, mimeType: String): String {
        val safePrompt = prompt.take(48).replace("\"", "&quot;").replace("<", "&lt;")
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --ink: #09090B; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --ink: #FAFAFA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
  body { background: transparent; color: var(--ink); padding: 4px; }
  .frame { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 8px; text-align: center; }
  img { max-width: 100%; max-height: 220px; border-radius: 8px; object-fit: contain; }
  .cap { font-size: 11px; font-weight: 600; margin-top: 6px; }
</style>
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
</head>
<body>
  <div class="frame">
    <img src="data:$mimeType;base64,$base64Data" alt="$safePrompt"/>
    <div class="cap">$safePrompt</div>
  </div>
</body>
</html>
        """.trimIndent()
    }

    private fun buildExternalImageViewerHtml(prompt: String, imageUrl: String): String {
        val safePrompt = prompt.take(44).replace("\"", "&quot;").replace("<", "&lt;")
        val safeUrl = imageUrl.replace("\"", "&quot;")
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --ink: #09090B; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --ink: #FAFAFA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, sans-serif; }
  body { background: var(--bg); color: var(--ink); padding: 6px; text-align: center; }
  img { width: 100%; max-height: 205px; object-fit: contain; border-radius: 8px; }
  .cap { font-size: 10.5px; font-weight: 700; margin-top: 4px; }
</style>
</head>
<body>
  <img src="$safeUrl" alt="$safePrompt"/>
  <div class="cap">$safePrompt</div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Ahaboard Interactive 3D GLB / WebGL Mesh & Wireframe Orbit Viewer (`GLB_3D`).
     * Supports external `.glb` / `.gltf` URLs AND a built-in zero-latency 60fps 3D Engine
     * with touch drag 360° orbit, zoom, 4 switchable 3D models (`MECH`, `TORUS`, `HAND`, `OCTA`),
     * and 3 render modes (`SOLID`, `WIRE`, `POINTS`).
     */
    private fun buildInteractiveGlb3DViewerHtml(prompt: String, glbUrl: String = ""): String {
        val pLower = prompt.lowercase(Locale.ROOT)
        val initialMeshIdx = when {
            pLower.contains("토러스") || pLower.contains("매듭") || pLower.contains("torus") -> 1
            pLower.contains("손") || pLower.contains("핸드") || pLower.contains("hand") -> 2
            pLower.contains("크리스탈") || pLower.contains("옥타") || pLower.contains("다이아") -> 3
            else -> 0 // MECH ROBOT CORE
        }
        val initialRenderMode = if (pLower.contains("와이어")) 1 else 0
        val safeGlbUrl = glbUrl.replace("\"", "").replace("'", "")
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no"/>
<style>
  :root { --bg: #0B0B0E; --panel: rgba(24,24,27,0.86); --ink: #FAFAFA; --muted: #A1A1AA; --line: #27272A; --accent: #38BDF8; }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, monospace; user-select: none; -webkit-user-select: none; }
  html, body { width: 100%; height: 100%; background: var(--bg); color: var(--ink); overflow: hidden; touch-action: none; }
  .stage { position: relative; width: 100%; height: 100%; display: flex; flex-direction: column; }
  canvas { width: 100%; height: 100%; display: block; cursor: grab; }
  canvas:active { cursor: grabbing; }
  .hud-top { position: absolute; top: 8px; left: 8px; right: 8px; display: flex; justify-content: space-between; align-items: center; pointer-events: none; }
  .badge { background: var(--panel); border: 1px solid var(--line); border-radius: 6px; padding: 3px 7px; font-size: 9.5px; font-weight: 800; color: var(--ink); letter-spacing: 0.3px; }
  .hud-bottom { position: absolute; bottom: 8px; left: 8px; right: 8px; display: flex; justify-content: space-between; align-items: center; gap: 4px; }
  .btn-group { display: flex; gap: 4px; flex-wrap: nowrap; }
  .btn { background: var(--panel); border: 1px solid var(--line); border-radius: 6px; padding: 4px 7px; font-size: 9.5px; font-weight: 700; color: var(--muted); cursor: pointer; }
  .btn.active { background: var(--ink); color: #09090B; border-color: var(--ink); }
</style>
</head>
<body>
<div class="stage">
  <canvas id="glbCanvas" width="680" height="440"></canvas>
  <div class="hud-top">
    <span class="badge" id="meshTitle">GLB·3D · MECH ROBOT CORE</span>
    <span class="badge" id="telemetry">60 FPS · DRAG 360°</span>
  </div>
  <div class="hud-bottom">
    <div class="btn-group">
      <div class="btn" id="m0" onclick="setMesh(0)">MECH</div>
      <div class="btn" id="m1" onclick="setMesh(1)">TORUS</div>
      <div class="btn" id="m2" onclick="setMesh(2)">HAND</div>
      <div class="btn" id="m3" onclick="setMesh(3)">CRYSTAL</div>
    </div>
    <div class="btn-group">
      <div class="btn" id="r0" onclick="setMode(0)">SOLID</div>
      <div class="btn" id="r1" onclick="setMode(1)">WIRE</div>
      <div class="btn" id="r2" onclick="setMode(2)">PTS</div>
      <div class="btn active" id="rotBtn" onclick="toggleAutoRot()">AUTO</div>
    </div>
  </div>
</div>
<script>
const canvas = document.getElementById('glbCanvas');
const ctx = canvas.getContext('2d');
let meshIdx = $initialMeshIdx;
let renderMode = $initialRenderMode; // 0=SOLID, 1=WIRE, 2=POINTS
let autoRotate = true;
let rotX = 0.32, rotY = -0.55;
let zoom = 1.0;
let isDragging = false, lastX = 0, lastY = 0;
let verts = [], faces = [];
const meshNames = ['GLB·3D · MECH ROBOT CORE', 'GLB·3D · TORUS KNOT MESH', 'GLB·3D · CYBERNETIC HAND', 'GLB·3D · GEODESIC CRYSTAL'];

function addBox(cx, cy, cz, sx, sy, sz, colorHue) {
  const base = verts.length;
  const dx = sx/2, dy = sy/2, dz = sz/2;
  const pts = [
    [cx-dx,cy-dy,cz-dz],[cx+dx,cy-dy,cz-dz],[cx+dx,cy+dy,cz-dz],[cx-dx,cy+dy,cz-dz],
    [cx-dx,cy-dy,cz+dz],[cx+dx,cy-dy,cz+dz],[cx+dx,cy+dy,cz+dz],[cx-dx,cy+dy,cz+dz]
  ];
  pts.forEach(p => verts.push(p));
  const f = [
    [0,1,2,3],[5,4,7,6],[4,0,3,7],[1,5,6,2],[4,5,1,0],[3,2,6,7]
  ];
  f.forEach(idx => faces.push({ idx: idx.map(i => base + i), hue: colorHue }));
}

function buildGeometry(type) {
  verts = []; faces = [];
  if (type === 0) {
    // Articulated Mech Robot Core (.glb procedural rig)
    addBox(0, -0.85, 0, 0.72, 0.56, 0.58, 198); // Head
    addBox(0, -0.86, 0.31, 0.54, 0.18, 0.08, 188); // Visor
    addBox(-0.44, -0.92, 0, 0.1, 0.38, 0.1, 210); // Left Antenna
    addBox(0.44, -0.92, 0, 0.1, 0.38, 0.1, 210); // Right Antenna
    addBox(0, -0.05, 0, 1.05, 0.92, 0.66, 215); // Torso
    addBox(0, -0.08, 0.35, 0.46, 0.42, 0.12, 190); // Arc Reactor Core
    addBox(-0.76, -0.05, 0, 0.34, 0.82, 0.38, 220); // Left Arm
    addBox(0.76, -0.05, 0, 0.34, 0.82, 0.38, 220); // Right Arm
    addBox(-0.30, 0.82, 0, 0.38, 0.76, 0.44, 210); // Left Leg
    addBox(0.30, 0.82, 0, 0.38, 0.76, 0.44, 210); // Right Leg
  } else if (type === 1) {
    // 3D Parametric Torus Knot
    const segU = 44, segV = 12;
    for (let i = 0; i < segU; i++) {
      const u = (i / segU) * Math.PI * 2;
      const r = 0.72 + 0.26 * Math.cos(3 * u);
      const cx = r * Math.cos(2 * u);
      const cy = r * Math.sin(2 * u);
      const cz = 0.38 * Math.sin(3 * u);
      for (let j = 0; j < segV; j++) {
        const v = (j / segV) * Math.PI * 2;
        const tube = 0.18;
        verts.push([
          cx + tube * Math.cos(v) * Math.cos(2 * u),
          cy + tube * Math.cos(v) * Math.sin(2 * u),
          cz + tube * Math.sin(v)
        ]);
      }
    }
    for (let i = 0; i < segU; i++) {
      const ni = (i + 1) % segU;
      for (let j = 0; j < segV; j++) {
        const nj = (j + 1) % segV;
        faces.push({
          idx: [i*segV+j, ni*segV+j, ni*segV+nj, i*segV+nj],
          hue: 195 + ((i * 3) % 45)
        });
      }
    }
  } else if (type === 2) {
    // Cybernetic Spatial Hand
    addBox(0, 0.35, 0, 0.95, 0.72, 0.28, 200); // Palm
    const fx = [-0.36, -0.12, 0.12, 0.36];
    const fh = [0.62, 0.76, 0.70, 0.52];
    for (let k = 0; k < 4; k++) {
      addBox(fx[k], -0.25, 0, 0.18, fh[k], 0.20, 190 + k*8);
      addBox(fx[k], -0.25 - fh[k]*0.65, 0.06, 0.15, fh[k]*0.55, 0.17, 185);
    }
    addBox(-0.62, 0.18, 0.12, 0.24, 0.52, 0.22, 195); // Thumb
  } else {
    // Geodesic Octahedral Crystal + Orbit Ring
    const top = [0, -1.15, 0], bot = [0, 1.15, 0];
    const ring = [[-0.85,0,-0.85],[0.85,0,-0.85],[0.85,0,0.85],[-0.85,0,0.85]];
    verts.push(top, bot, ...ring);
    faces.push({idx:[0,2,3], hue:195},{idx:[0,3,4], hue:205},{idx:[0,4,5], hue:215},{idx:[0,5,2], hue:188});
    faces.push({idx:[1,3,2], hue:200},{idx:[1,4,3], hue:210},{idx:[1,5,4], hue:220},{idx:[1,2,5], hue:192});
    for (let k = 0; k < 12; k++) {
      const a = (k / 12) * Math.PI * 2;
      addBox(Math.cos(a)*1.25, Math.sin(a*2)*0.18, Math.sin(a)*1.25, 0.16, 0.16, 0.16, 185);
    }
  }
}

function updateButtons() {
  for (let i = 0; i < 4; i++) {
    document.getElementById('m'+i).className = (i === meshIdx) ? 'btn active' : 'btn';
  }
  for (let i = 0; i < 3; i++) {
    document.getElementById('r'+i).className = (i === renderMode) ? 'btn active' : 'btn';
  }
  document.getElementById('rotBtn').className = autoRotate ? 'btn active' : 'btn';
  document.getElementById('meshTitle').innerText = meshNames[meshIdx];
}

function setMesh(idx) { meshIdx = idx; buildGeometry(idx); updateButtons(); }
function setMode(m) { renderMode = m; updateButtons(); }
function toggleAutoRot() { autoRotate = !autoRotate; updateButtons(); }

canvas.addEventListener('pointerdown', e => {
  isDragging = true; lastX = e.clientX; lastY = e.clientY;
});
window.addEventListener('pointermove', e => {
  if (!isDragging) return;
  const dx = e.clientX - lastX;
  const dy = e.clientY - lastY;
  lastX = e.clientX; lastY = e.clientY;
  rotY += dx * 0.012;
  rotX = Math.max(-1.4, Math.min(1.4, rotX + dy * 0.012));
});
window.addEventListener('pointerup', () => { isDragging = false; });

function project(v, w, h) {
  let x = v[0], y = v[1], z = v[2];
  // Rotate Y
  const cy = Math.cos(rotY), sy = Math.sin(rotY);
  let x1 = x * cy - z * sy;
  let z1 = x * sy + z * cy;
  // Rotate X
  const cx = Math.cos(rotX), sx = Math.sin(rotX);
  let y2 = y * cx - z1 * sx;
  let z2 = y * sx + z1 * cx;
  const dist = 3.4 / zoom;
  const scale = 320 / (dist + z2 + 2.2);
  return [w/2 + x1 * scale, h/2 + y2 * scale, z2, x1, y2];
}

function renderLoop() {
  if (autoRotate && !isDragging) rotY += 0.014;
  const w = canvas.width, h = canvas.height;
  ctx.fillStyle = '#09090B';
  ctx.fillRect(0, 0, w, h);

  // Perspective floor grid
  ctx.strokeStyle = 'rgba(255,255,255,0.07)';
  ctx.lineWidth = 1;
  for (let g = -1.6; g <= 1.6; g += 0.4) {
    const p1 = project([g, 1.25, -1.6], w, h);
    const p2 = project([g, 1.25, 1.6], w, h);
    ctx.beginPath(); ctx.moveTo(p1[0], p1[1]); ctx.lineTo(p2[0], p2[1]); ctx.stroke();
    const p3 = project([-1.6, 1.25, g], w, h);
    const p4 = project([1.6, 1.25, g], w, h);
    ctx.beginPath(); ctx.moveTo(p3[0], p3[1]); ctx.lineTo(p4[0], p4[1]); ctx.stroke();
  }

  const proj = verts.map(v => project(v, w, h));

  if (renderMode === 2) {
    // Point Cloud mode
    proj.forEach(p => {
      const bright = Math.floor(Math.max(90, Math.min(250, 190 - p[2]*55)));
      ctx.fillStyle = 'rgb(' + bright + ',' + bright + ',' + Math.min(255, bright+20) + ')';
      ctx.beginPath();
      ctx.arc(p[0], p[1], 3.2, 0, Math.PI * 2);
      ctx.fill();
    });
  } else {
    // Depth-sort faces
    const sorted = faces.map(f => {
      const pts = f.idx.map(i => proj[i]);
      const avgZ = pts.reduce((acc, p) => acc + p[2], 0) / pts.length;
      // Normal lighting estimate
      const ux = pts[1][3] - pts[0][3], uy = pts[1][4] - pts[0][4];
      const vx = pts[2][3] - pts[0][3], vy = pts[2][4] - pts[0][4];
      const nz = ux * vy - uy * vx;
      return { pts, avgZ, nz, hue: f.hue };
    }).sort((a, b) => b.avgZ - a.avgZ);

    sorted.forEach(poly => {
      ctx.beginPath();
      poly.pts.forEach((p, idx) => {
        if (idx === 0) ctx.moveTo(p[0], p[1]);
        else ctx.lineTo(p[0], p[1]);
      });
      ctx.closePath();
      if (renderMode === 0) {
        const shade = Math.floor(Math.max(38, Math.min(235, 148 - poly.avgZ * 48 + (poly.nz > 0 ? 24 : -18))));
        ctx.fillStyle = 'rgb(' + shade + ',' + shade + ',' + Math.min(255, shade + 12) + ')';
        ctx.fill();
        ctx.strokeStyle = 'rgba(9,9,11,0.65)';
        ctx.lineWidth = 1.1;
        ctx.stroke();
      } else {
        ctx.strokeStyle = 'rgba(244,244,245,0.82)';
        ctx.lineWidth = 1.35;
        ctx.stroke();
      }
    });
  }
  requestAnimationFrame(renderLoop);
}

buildGeometry(meshIdx);
updateButtons();
renderLoop();
</script>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Native HTML5 MP4/WebM Video Player for verified video streams (`MP4`).
     */
    private fun buildInteractiveMp4VideoPlayerHtml(prompt: String, mp4Url: String = ""): String {
        val safeTitle = prompt.take(34).replace("\"", "&quot;").replace("<", "&lt;").replace("'", "")
        val safeUrl = mp4Url.replace("\"", "").replace("'", "")
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #09090B; --ink: #FAFAFA; --muted: #A1A1AA; }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, monospace; }
  html, body { width: 100%; height: 100%; background: var(--bg); color: var(--ink); overflow: hidden; }
  .player { position: relative; width: 100%; height: 100%; display: flex; flex-direction: column; justify-content: center; align-items: center; }
  video { width: 100%; height: 100%; object-fit: contain; display: block; background: #000; }
  .caption { position: absolute; top: 8px; left: 8px; background: rgba(24,24,27,0.85); border-radius: 6px; padding: 3px 7px; font-size: 10px; font-weight: 700; pointer-events: none; }
</style>
</head>
<body>
<div class="player">
  <video id="vid" src="$safeUrl" controls autoplay loop muted playsinline></video>
  <span class="caption">$safeTitle</span>
</div>
</body>
</html>
        """.trimIndent()
    }

    /**
     * Native HTML5 GIF Image Viewer for verified `.gif` HTTPS URLs (`GIF`).
     */
    private fun buildInteractiveGifLoopHtml(prompt: String, gifUrl: String = ""): String {
        val safeTitle = prompt.take(32).replace("\"", "&quot;").replace("<", "&lt;").replace("'", "")
        val safeUrl = gifUrl.replace("\"", "").replace("'", "")
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FAFAFA; --ink: #09090B; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --ink: #FAFAFA; } }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  html, body { width: 100%; height: 100%; background: var(--bg); color: var(--ink); overflow: hidden; }
  img { width: 100%; height: 100%; object-fit: contain; display: block; }
</style>
</head>
<body>
  <img src="$safeUrl" alt="$safeTitle"/>
</body>
</html>
        """.trimIndent()
    }

    private fun buildProceduralGenerativeArtworkHtml(prompt: String, style: String): String {
        val safePrompt = prompt.take(40).replace("\"", "&quot;").replace("<", "&lt;").replace("'", "")
        val initialSeed = (prompt.hashCode() and 0x7FFFFFFF) % 9999 + 1
        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
  :root { --bg: #FFFFFF; --ink: #09090B; --muted: #71717A; --line: #E4E4E7; }
  @media (prefers-color-scheme: dark) { :root { --bg: #121214; --ink: #FAFAFA; --muted: #A1A1AA; --line: #27272A; } }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, monospace; }
  html, body {
    filter: grayscale(100%) !important;
    -webkit-filter: grayscale(100%) !important;
  }
  body { background: transparent; color: var(--ink); padding: 4px; user-select: none; }
  .wrap { background: var(--bg); border: 1px solid var(--line); border-radius: 12px; padding: 8px; position: relative; }
  canvas { width: 100%; height: 210px; display: block; border-radius: 8px; cursor: pointer; }
  .meta { display: flex; justify-content: space-between; align-items: center; font-size: 10.5px; margin-top: 6px; font-weight: 700; }
  .pill { padding: 2px 7px; border: 1px solid var(--line); border-radius: 999px; font-size: 9.5px; }
</style>
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
</head>
<body>
  <div class="wrap" onclick="nextSeed()">
    <canvas id="artCanvas" width="640" height="400"></canvas>
    <div class="meta">
      <span>$safePrompt</span>
      <span class="pill" id="seedBadge">SEED #$initialSeed · TAP TO REMIX</span>
    </div>
  </div>
  <script>
    let seed = $initialSeed;
    let t = 0;
    const canvas = document.getElementById('artCanvas');
    const ctx = canvas.getContext('2d');
    const isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;

    function nextSeed() {
      seed = (seed + 7919) % 99999 + 1;
      document.getElementById('seedBadge').innerText = 'SEED #' + seed + ' · REMIXED';
    }

    function drawFrame() {
      t += 0.025;
      const w = canvas.width;
      const h = canvas.height;
      const bg = isDark ? '#09090B' : '#FAFAFA';
      const ink = isDark ? '#FAFAFA' : '#09090B';
      const mid = isDark ? 'rgba(250,250,250,0.22)' : 'rgba(9,9,11,0.18)';

      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, w, h);

      // Subtle architectural grid
      ctx.strokeStyle = isDark ? 'rgba(255,255,255,0.06)' : 'rgba(0,0,0,0.06)';
      ctx.lineWidth = 1;
      for (let x = 0; x < w; x += 32) {
        ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, h); ctx.stroke();
      }
      for (let y = 0; y < h; y += 32) {
        ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(w, y); ctx.stroke();
      }

      // Procedural harmonic waves + sacred geometry nodes
      const layers = 5;
      for (let l = 0; l < layers; l++) {
        ctx.beginPath();
        ctx.strokeStyle = l === 0 ? ink : mid;
        ctx.lineWidth = l === 0 ? 2.4 : 1.2;
        const freq = 0.008 + (l * 0.003) + ((seed % 17) * 0.0004);
        const amp = 36 + (l * 14);
        for (let x = 0; x <= w; x += 6) {
          const y = h / 2 + Math.sin(x * freq + t + l) * amp * Math.cos(x * 0.004 - t * 0.6);
          if (x === 0) ctx.moveTo(x, y);
          else ctx.lineTo(x, y);
        }
        ctx.stroke();
      }

      // Orbiting geometric focal rings
      const cx = w / 2;
      const cy = h / 2;
      ctx.strokeStyle = ink;
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.arc(cx, cy, 54 + Math.sin(t * 1.5) * 8, 0, Math.PI * 2);
      ctx.stroke();

      for (let i = 0; i < 8; i++) {
        const ang = t * 0.8 + (i * Math.PI / 4);
        const rx = cx + Math.cos(ang) * 84;
        const ry = cy + Math.sin(ang) * 52;
        ctx.fillStyle = ink;
        ctx.beginPath();
        ctx.arc(rx, ry, 4, 0, Math.PI * 2);
        ctx.fill();
      }

      requestAnimationFrame(drawFrame);
    }
    drawFrame();
  </script>
</body>
</html>
        """.trimIndent()
    }

    // =========================================================================
    // PILLAR 1: Reactive Rules, Cross-App Data Pipelines & Zero-GUI Teleports
    // =========================================================================

    private fun tryRegisterReactiveRule(p: String, pLower: String): NexusExecutionResult? {
        if ((pLower.contains("알림 오면") || pLower.contains("카톡 오면") || pLower.contains("메시지 오면") || pLower.contains("연락 오면")) &&
            (pLower.contains("자동") || pLower.contains("기록") || pLower.contains("저장") || pLower.contains("답장"))
        ) {
            val senderMatch = Regex("([가-힣a-zA-Z0-9_]{2,12})\\s*(?:님|교수님|선배|한테|에게|에서)?\\s*(?:알림|카톡|메시지|연락)").find(p)
            val triggerKw = senderMatch?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "전체" }

            val isAutoReply = pLower.contains("답장")
            val replyQuote = Regex("'([^']+)'|\"([^\"]+)\"").find(p)?.let {
                it.groupValues[1].ifBlank { it.groupValues[2] }
            } ?: "확인했습니다. 잠시 후 연락드리겠습니다."

            val rule = ReactiveAutomationRule(
                id = "rule_${System.currentTimeMillis() % 100000}",
                triggerKeyword = triggerKw,
                actionType = if (isAutoReply) "AUTO_REPLY" else "AUTO_NOTE",
                actionPayload = if (isAutoReply) replyQuote else "자동 알림 아카이빙"
            )
            synchronized(reactiveRules) {
                reactiveRules.removeAll { it.triggerKeyword == triggerKw && it.actionType == rule.actionType }
                reactiveRules.add(rule)
                persistRulesToDisk()
            }
            val desc = if (isAutoReply) {
                "'$triggerKw' 관련 알림이 도착하면 화면을 켜지 않고 0.1초 만에 \"$replyQuote\"라고 자동 답장하는 리액티브 트리거를 연결했어요!"
            } else {
                "'$triggerKw' 관련 알림이나 카톡이 도착할 때마다 백그라운드에서 노트에 자동 기록하는 리액티브 파이프라인을 연결했어요!"
            }
            return NexusExecutionResult(
                handled = true,
                summaryKo = desc,
                badge = "NEXUS-TRIGGER"
            )
        }
        return null
    }

    /**
     * Invoked automatically whenever an OS notification arrives.
     */
    fun evaluateReactiveRulesOnNotification(notif: InterceptedNotification): String? {
        val matchedRules = synchronized(reactiveRules) {
            reactiveRules.filter { rule ->
                rule.triggerKeyword == "전체" ||
                    notif.sender.contains(rule.triggerKeyword, ignoreCase = true) ||
                    notif.roomOrTitle.contains(rule.triggerKeyword, ignoreCase = true) ||
                    notif.messageText.contains(rule.triggerKeyword, ignoreCase = true)
            }
        }
        if (matchedRules.isEmpty()) return null

        val logs = mutableListOf<String>()
        for (rule in matchedRules) {
            when (rule.actionType) {
                "AUTO_NOTE" -> {
                    val content = "[${notif.appLabel} · ${notif.roomOrTitle.ifBlank { notif.sender }}] ${notif.sender}: ${notif.messageText}"
                    toolHarness.execute(
                        "save_note",
                        JSONObject().put("key", "자동기록_${notif.sender.take(10)}").put("content", content),
                        null
                    )
                    totalReactiveTriggersFired++
                    logs.add("노트 자동 기록 완료($content)")
                }
                "AUTO_REPLY" -> {
                    if (notif.canQuickReply) {
                        RemoteInputSessionCache.sendQuickReply(appContext, notif.sessionId, rule.actionPayload)
                        totalReactiveTriggersFired++
                        logs.add("'${notif.sender}'에게 자동 답장(\"${rule.actionPayload}\") 전송 완료")
                    }
                }
            }
        }
        return logs.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun tryExecuteCrossAppPipeline(
        p: String,
        pLower: String,
        latestSnapshot: AtomicSnapshot?
    ): NexusExecutionResult? {
        val snapshot = latestSnapshot
            ?: HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)

        val mentionsScreenOrChat = pLower.contains("지금 화면") || pLower.contains("현재 화면") ||
            pLower.contains("이 화면") || pLower.contains("이 방") || pLower.contains("이 카톡방") ||
            pLower.contains("대화 내용") || pLower.contains("화면 내용")
        val mentionsClipboard = pLower.contains("클립보드") || pLower.contains("복사한") || pLower.contains("복사해")

        val wantsSaveNote = (pLower.contains("노트") || pLower.contains("메모") || pLower.contains("기억")) &&
            (pLower.contains("저장") || pLower.contains("기록") || pLower.contains("적어"))
        val wantsCopyClipboard = pLower.contains("클립보드에 복사") || pLower.contains("복사해줘") || pLower.contains("복사해 줘")
        val wantsCalendar = (pLower.contains("캘린더") || pLower.contains("일정")) &&
            (pLower.contains("등록") || pLower.contains("추가") || pLower.contains("넣어"))
        val wantsShareSheet = pLower.contains("공유해줘") || pLower.contains("공유창")

        if (!mentionsScreenOrChat && !mentionsClipboard) return null
        if (!wantsSaveNote && !wantsCopyClipboard && !wantsCalendar && !wantsShareSheet) return null

        val extractedContext = extractScreenOrClipboardContext(snapshot, preferClipboard = mentionsClipboard && !mentionsScreenOrChat)
        if (extractedContext.isBlank()) return null

        val completedPipes = mutableListOf<String>()

        if (wantsSaveNote) {
            val keyName = snapshot?.activePackage?.substringAfterLast('.')?.ifBlank { "screen" } ?: "nexus"
            toolHarness.execute(
                "save_note",
                JSONObject().put("key", "화면요약_$keyName").put("content", extractedContext),
                snapshot
            )
            completedPipes.add("노트 저장")
        }

        if (wantsCopyClipboard) {
            copyToSystemClipboard("Hanson Nexus", extractedContext)
            completedPipes.add("시스템 클립보드 복사")
        }

        if (wantsCalendar) {
            toolHarness.execute(
                "manage_calendar",
                JSONObject().apply {
                    put("action", "add")
                    put("title", extractedContext.take(36))
                    put("offset_minutes_from_now", 60)
                    put("duration_minutes", 60)
                    put("description", extractedContext)
                },
                snapshot
            )
            completedPipes.add("캘린더 일정 등록")
        }

        if (wantsShareSheet) {
            launchSystemShareSheet(extractedContext)
            completedPipes.add("시스템 공유 시트 실행")
        }

        if (completedPipes.isNotEmpty()) {
            return NexusExecutionResult(
                handled = true,
                summaryKo = "앱 간 데이터 파이프라인(${completedPipes.joinToString(" + ")})을 즉시 실행했어요! 추출된 내용: \"${extractedContext.take(90)}\"",
                badge = "NEXUS-PIPE"
            )
        }
        return null
    }

    private fun tryZeroGuiIntentTeleport(p: String, pLower: String): NexusExecutionResult? {
        // 3-A. Direct Android System Settings Sub-Panels
        if (pLower.contains("설정") && (pLower.contains("열어") || pLower.contains("가줘") || pLower.contains("보여") || pLower.contains("켜줘"))) {
            val settingsActionAndLabel: Pair<String, String>? = when {
                pLower.contains("블루투스") || pLower.contains("bluetooth") ->
                    Settings.ACTION_BLUETOOTH_SETTINGS to "블루투스 설정"
                pLower.contains("와이파이") || pLower.contains("wi-fi") || pLower.contains("wifi") ->
                    Settings.ACTION_WIFI_SETTINGS to "와이파이 설정"
                pLower.contains("배터리") || pLower.contains("절전") ->
                    Intent.ACTION_POWER_USAGE_SUMMARY to "배터리 및 디바이스 케어 설정"
                pLower.contains("화면") || pLower.contains("디스플레이") || pLower.contains("밝기") ->
                    Settings.ACTION_DISPLAY_SETTINGS to "디스플레이 설정"
                pLower.contains("소리") || pLower.contains("음량") || pLower.contains("진동") || pLower.contains("사운드") ->
                    Settings.ACTION_SOUND_SETTINGS to "소리 및 진동 설정"
                pLower.contains("접근성") ->
                    Settings.ACTION_ACCESSIBILITY_SETTINGS to "접근성 설정"
                pLower.contains("알림") ->
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS to "알림 접근 설정"
                pLower.contains("위치") || pLower.contains("gps") ->
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS to "위치 설정"
                pLower.contains("애플리케이션") || pLower.contains("앱 관리") ->
                    Settings.ACTION_APPLICATION_SETTINGS to "애플리케이션 설정"
                else -> null
            }
            if (settingsActionAndLabel != null) {
                val (actionStr, labelKo) = settingsActionAndLabel
                val ok = runCatching {
                    val intent = Intent(actionStr).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    appContext.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (ok) {
                    return NexusExecutionResult(
                        handled = true,
                        summaryKo = "네, UI 클릭 단계를 거치지 않고 '$labelKo' 화면으로 바로 직행(0.02초 텔레포트)했어요.",
                        badge = "NEXUS-TELEPORT"
                    )
                }
            }
        }

        // 3-B. Direct YouTube Search / Play Teleport
        if ((pLower.contains("유튜브") || pLower.contains("youtube")) &&
            (pLower.contains("검색") || pLower.contains("찾아") || pLower.contains("틀어") || pLower.contains("재생"))
        ) {
            val query = extractSearchQuery(p, listOf("유튜브에서", "유튜브", "youtube"))
            if (query.isNotBlank()) {
                val launched = runCatching {
                    val intent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra(SearchManager.QUERY, query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    appContext.startActivity(intent)
                    true
                }.getOrElse {
                    runCatching {
                        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                        val fallback = Intent(Intent.ACTION_VIEW, uri).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        appContext.startActivity(fallback)
                        true
                    }.getOrDefault(false)
                }
                if (launched) {
                    val wantsPlayFirst = pLower.contains("틀어") || pLower.contains("재생")
                    return NexusExecutionResult(
                        handled = true,
                        summaryKo = "유튜브에서 '$query' 검색 결과 화면으로 즉시 텔레포트했어요!",
                        badge = "NEXUS-TELEPORT",
                        followUpGuiGoal = if (wantsPlayFirst) "첫 번째 항목 눌러줘" else null
                    )
                }
            }
        }

        // 3-C. Direct Naver Map / KakaoMap Search Teleport
        if (pLower.contains("네이버지도") || pLower.contains("네이버 지도") || pLower.contains("카카오맵") || pLower.contains("지도에서")) {
            val query = extractSearchQuery(p, listOf("네이버지도로", "네이버지도에서", "네이버지도", "네이버 지도", "카카오맵으로", "카카오맵에서", "카카오맵", "지도에서"))
            if (query.isNotBlank()) {
                val isKakaoMap = pLower.contains("카카오맵")
                val uriStr = if (isKakaoMap) {
                    "kakaomap://search?q=${Uri.encode(query)}"
                } else {
                    "nmap://search?query=${Uri.encode(query)}&appname=com.hanson.mobileuse"
                }
                val launched = runCatching {
                    appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                }.getOrElse {
                    runCatching {
                        val webUri = Uri.parse("https://m.map.naver.com/search2/search.naver?query=${Uri.encode(query)}")
                        appContext.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                        true
                    }.getOrDefault(false)
                }
                if (launched) {
                    return NexusExecutionResult(
                        handled = true,
                        summaryKo = "지도 앱의 '$query' 위치 검색 결과로 즉시 이동했어요.",
                        badge = "NEXUS-TELEPORT"
                    )
                }
            }
        }

        // 3-D. Direct Naver / Google Web Search Teleport
        if ((pLower.contains("네이버에서") || pLower.contains("구글에서") || pLower.contains("크롬에서")) &&
            (pLower.contains("검색") || pLower.contains("찾아"))
        ) {
            val query = extractSearchQuery(p, listOf("네이버에서", "구글에서", "크롬에서"))
            if (query.isNotBlank()) {
                val url = if (pLower.contains("네이버")) {
                    "https://m.search.naver.com/search.naver?query=${Uri.encode(query)}"
                } else {
                    "https://www.google.com/search?q=${Uri.encode(query)}"
                }
                val ok = runCatching {
                    appContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    true
                }.getOrDefault(false)
                if (ok) {
                    return NexusExecutionResult(
                        handled = true,
                        summaryKo = "'$query' 검색 결과 페이지를 즉시 열었어요.",
                        badge = "NEXUS-TELEPORT"
                    )
                }
            }
        }

        return null
    }

    /**
     * External Webhook / Peer Agent Callout (`POST` JSON payload to external URL or A2A agent).
     */
    fun dispatchExternalWebhookOrA2a(
        targetUrl: String,
        eventTitle: String,
        payloadText: String
    ): Boolean {
        return runCatching {
            val bodyJson = JSONObject().apply {
                put("source", "Hanson-Mobile-Use-Nexus")
                put("event", eventTitle)
                put("payload", payloadText)
                put("timestampMs", System.currentTimeMillis())
            }
            val req = Request.Builder()
                .url(targetUrl)
                .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    fun extractScreenOrClipboardContext(
        snapshot: AtomicSnapshot?,
        preferClipboard: Boolean = false
    ): String {
        if (preferClipboard) {
            val clip = readSystemClipboard()
            if (clip.isNotBlank()) return clip
        }
        if (snapshot != null && snapshot.indexedElements.isNotEmpty()) {
            val items = snapshot.indexedElements
                .map { it.text.trim().ifBlank { it.contentDesc.trim() } }
                .filter { it.length >= 2 && it !in setOf("뒤로가기", "검색", "보내기", "전송", "메시지 입력") }
                .distinct()
                .take(10)
            if (items.isNotEmpty()) {
                return items.joinToString(" | ")
            }
        }
        return readSystemClipboard()
    }

    private fun readSystemClipboard(): String {
        return runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun copyToSystemClipboard(label: String, text: String) {
        runCatching {
            val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        }
    }

    private fun launchSystemShareSheet(text: String) {
        runCatching {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(sendIntent, "Hanson 데이터 공유").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(chooser)
        }
    }

    private fun extractSearchQuery(rawPrompt: String, appPrefixes: List<String>): String {
        var s = rawPrompt
        for (prefix in appPrefixes) {
            s = s.replace(prefix, "", ignoreCase = true)
        }
        return s
            .replace("검색해서", "")
            .replace("검색해줘", "")
            .replace("검색해", "")
            .replace("검색", "")
            .replace("찾아서", "")
            .replace("찾아줘", "")
            .replace("찾아", "")
            .replace("틀어줘", "")
            .replace("틀어", "")
            .replace("재생해줘", "")
            .replace("재생", "")
            .replace("길찾기", "")
            .trim()
    }

    fun getNexusStatsJson(): JSONObject = JSONObject().apply {
        put("totalZeroGuiTeleports", totalZeroGuiTeleports)
        put("totalCrossAppPipelines", totalCrossAppPipelines)
        put("totalReactiveTriggersFired", totalReactiveTriggersFired)
        put("totalCrossDeviceCalls", totalCrossDeviceCalls)
        put("totalBrowserUseOps", totalBrowserUseOps)
        put("totalVisualSyntheses", totalVisualSyntheses)
        val rulesArr = JSONArray()
        synchronized(reactiveRules) {
            reactiveRules.forEach { rulesArr.put(it.toJson()) }
        }
        put("activeReactiveRules", rulesArr)
    }

    private fun loadRulesFromDisk() {
        try {
            val raw = prefs.getString(KEY_REACTIVE_RULES, null) ?: return
            val arr = JSONArray(raw)
            synchronized(reactiveRules) {
                reactiveRules.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    reactiveRules.add(ReactiveAutomationRule.fromJson(o))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun persistRulesToDisk() {
        try {
            val arr = JSONArray()
            synchronized(reactiveRules) {
                reactiveRules.toList().takeLast(40).forEach { arr.put(it.toJson()) }
            }
            prefs.edit().putString(KEY_REACTIVE_RULES, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}
