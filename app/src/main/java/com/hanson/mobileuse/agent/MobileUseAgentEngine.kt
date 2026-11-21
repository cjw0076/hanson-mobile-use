package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.paradigm.LiquidUiSynthesizer
import com.hanson.mobileuse.paradigm.ModeTurnResult
import com.hanson.mobileuse.paradigm.NeuralSkillCompilerAndDistiller
import com.hanson.mobileuse.paradigm.ParadigmExecutionModeController
import com.hanson.mobileuse.paradigm.SemanticTeleportRouter
import com.hanson.mobileuse.paradigm.WorldModelGraph
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.ui.components.CanvasErrorTelemetry
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.ComposerCommandParser
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import com.hanson.mobileuse.workspace.ParsedComposerInput
import com.hanson.mobileuse.workspace.WorkspaceDiffRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

enum class ExecutionLaneMode(val labelKo: String, val badge: String) {
    AUTO("자동 분기", "AUTO"),
    FOREGROUND("포그라운드", "FG"),
    BACKGROUND("백그라운드", "BG")
}

data class ParallelTaskSlot(
    val taskId: String,
    val lane: String, // "FG" or "BG"
    val goal: String,
    val startedAtMs: Long = System.currentTimeMillis()
)

data class AgentEngineConfig(
    val apiKey: String = "",
    val modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
    val maxIterations: Int = 15,
    val autoShowFloatingHud: Boolean = true,
    val ttsVoiceReply: Boolean = true,
    val customBaseUrl: String = ""
)

/**
 * Conversational Full-Control Mobile-Use Agent Engine (`MobileUseAgentEngine`).
 *
 * Bridges:
 * - Multi-Tier Mobile AI Infrastructure Router (`MobileAiInfrastructureRouter`:
 *   Galaxy S25 Built-In AICore Gemini Nano NPU + MediaPipe Tasks GenAI + Local Edge LLM + Cloud Multi-Provider VLM)
 * - Set-of-Marks (`[index]` visual grounding) + Accessibility UI Tree atomic perception
 * - Full-Duplex Conversational Voice Loop (`LiveVoiceSessionManager`) with live mid-execution steering
 * - Ground-Truth Action Trajectory Verifier & Self-Healer (`ActionTrajectoryVerifierAndHealer`)
 */
class MobileUseAgentEngine @JvmOverloads constructor(
    private val context: Context,
    val permissionHub: PermissionHubManager,
    val workspaceEngine: MobileWorkspaceEngine? = null,
    val sessionStore: AgentSessionStore? = null,
    val blindVault: BlindCredentialVault? = null,
    val watcherEngine: ProactiveWatcherEngine? = null,
    val scratchpad: NotetakerScratchpad? = null,
    val memoryBubblesStore: HumanMemoryBubblesStore? = null,
    val personalRoutineStore: PersonalRoutineStore? = null
) {
    companion object {
        private const val CONFIG_PREFS = "hanson_agent_engine_config"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model_name"
        private const val KEY_MAX_ITER = "max_iterations"
        private const val KEY_AUTO_HUD = "auto_floating_hud"
        private const val KEY_TTS_VOICE = "tts_voice_reply"
        private const val KEY_CUSTOM_BASE_URL = "custom_openai_base_url"
        private const val KEY_CHAT_HISTORY = "live_chat_history_json"
        private const val KEY_UX_MODE = "hanson_ux_mode"
    }

    private val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val multimodalClient = GeminiMultimodalClient()
    val aiInfrastructureRouter = MobileAiInfrastructureRouter(context, multimodalClient)
    private val toolHarness = AgentToolExecutionHarness(context, workspaceEngine)
    private val trajectoryVerifier = ActionTrajectoryVerifierAndHealer(context)
    val worldModelGraph = WorldModelGraph(context)
    val executionModeController = ParadigmExecutionModeController(worldModelGraph, context)
    val neuralSkillCompiler = NeuralSkillCompilerAndDistiller(context)
    val semanticTeleportRouter = SemanticTeleportRouter(context)
    val memoryManager = AgentMemoryManager(context)
    val resolvedVault: BlindCredentialVault by lazy {
        blindVault ?: HansonRuntimeHolder.getBlindVault(context)
    }
    val resolvedWatcherEngine: ProactiveWatcherEngine by lazy {
        watcherEngine ?: HansonRuntimeHolder.getWatcherEngine(context)
    }
    val resolvedScratchpad: NotetakerScratchpad by lazy {
        scratchpad ?: HansonRuntimeHolder.getScratchpad(context)
    }
    val resolvedMemoryBubbles: HumanMemoryBubblesStore by lazy {
        (memoryBubblesStore ?: HansonRuntimeHolder.getMemoryBubblesStore(context)).apply {
            seedDefaultsIfEmpty()
        }
    }
    val resolvedPersonalRoutines: PersonalRoutineStore by lazy {
        (personalRoutineStore ?: HansonRuntimeHolder.getPersonalRoutineStore(context)).apply {
            seedDefaultsIfEmpty()
        }
    }
    val resolvedCloudSync: HansonFirestoreCloudSyncEngine by lazy {
        HansonRuntimeHolder.getFirestoreCloudSyncEngine(context)
    }
    private val loopBreaker = MetaCognitiveLoopBreaker()
    private val currentControlEpoch = AtomicLong(1L)
    private val consumedExternalApprovalReceipts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile
    private var lastApprovedAmountText: String? = null
    @Volatile
    private var lastStepDeltaRatio: Float = 1.0f
    @Volatile
    private var lastBoundApprovalReceiptId: String? = null
    @Volatile
    private var lastBoundControlEpoch: Long? = null

    fun currentControlEpoch(): Long = currentControlEpoch.get()

    fun advanceControlEpoch(): Long = currentControlEpoch.incrementAndGet()
    val universalNexusEngine: UniversalNexusEngine
        get() = toolHarness.universalNexusEngine

    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var takeoverDeferred: CompletableDeferred<Boolean>? = null

    init {
        toolHarness.attachSotaModules(resolvedVault, resolvedWatcherEngine, resolvedScratchpad)
    }
    private val concurrentBackgroundJobs = ConcurrentHashMap<String, Job>()
    private val pendingGuiGoals = ConcurrentLinkedQueue<String>()
    private val craftAnythingEngine = CraftAnythingMobileEngine(context)

    @Volatile
    private var liveSteeringGoal: String? = null

    private val _activeParallelTasks = MutableStateFlow<List<ParallelTaskSlot>>(emptyList())
    val activeParallelTasks: StateFlow<List<ParallelTaskSlot>> = _activeParallelTasks.asStateFlow()

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<AgentEngineConfig> = _config.asStateFlow()

    private val _currentSession = MutableStateFlow(AgentRunSession())
    val currentSession: StateFlow<AgentRunSession> = _currentSession.asStateFlow()

    private val _chatMessages = MutableStateFlow(loadPersistedChatMessages())
    val chatMessages: StateFlow<List<LiveChatMessage>> = _chatMessages.asStateFlow()

    private val _runHistory = MutableStateFlow<List<AgentRunSession>>(emptyList())
    val runHistory: StateFlow<List<AgentRunSession>> = _runHistory.asStateFlow()

    private val _allReceipts = MutableStateFlow<List<ActionReceipt>>(emptyList())
    val allReceipts: StateFlow<List<ActionReceipt>> = _allReceipts.asStateFlow()

    private val _workMode = MutableStateFlow(
        sessionStore?.state?.value?.activeSession?.mode ?: AgentWorkMode.AUTO
    )
    val workMode: StateFlow<AgentWorkMode> = _workMode.asStateFlow()

    private val _uxMode = MutableStateFlow(
        UserExperienceMode.fromId(prefs.getString(KEY_UX_MODE, UserExperienceMode.SIMPLE_HUMAN.id))
    )
    val uxMode: StateFlow<UserExperienceMode> = _uxMode.asStateFlow()

    @Volatile
    private var pendingPlanGoal: String? = null

    init {
        HansonForegroundService.onEmergencyStopRequested = { stopRun("네, 작업을 바로 멈췄어요.") }
        FloatingHudService.onStopClicked = { stopRun("네, 작업을 멈췄어요. 다음엔 어떻게 할까요?") }
        FloatingHudService.onSendLiveChatMessage = { userMsg -> sendChatMessage(userMsg) }
        FloatingHudService.onApprovalDecision = { approved ->
            resolvePendingApproval(approved, alwaysAllowCapability = false)
        }
        FloatingHudService.onUndoLastClicked = { executeOneTapUndo() }
        FloatingHudService.onFinishRecordingClicked = { finishDemonstrationAndSaveRoutine() }
        LiveVoiceSessionManager.ensureInitialized(context) { spoken ->
            sendChatMessage(spoken)
        }
        GenerativeUiStateHolder.addErrorListener { telemetry ->
            onCanvasExecutionError(telemetry.rawJson.ifBlank { telemetry.toJsonString() })
        }
    }

    fun setUxMode(mode: UserExperienceMode) {
        prefs.edit().putString(KEY_UX_MODE, mode.id).apply()
        _uxMode.value = mode
    }

    private fun speakIfEnabled(text: String) {
        val shouldSpeak = LiveVoiceSessionManager.state.value.isActive || _config.value.ttsVoiceReply
        if (!shouldSpeak) return
        LiveVoiceSessionManager.speakConversationalFiller(text)
    }

    private fun loadConfig(): AgentEngineConfig {
        val rawSavedModel = prefs.getString(KEY_MODEL, GeminiMultimodalClient.AUTO_MULTI_MODEL)
            ?: GeminiMultimodalClient.AUTO_MULTI_MODEL
        val normalizedModel = GeminiMultimodalClient.normalize2026ModelName(rawSavedModel)
        if (normalizedModel != rawSavedModel) {
            prefs.edit().putString(KEY_MODEL, normalizedModel).apply()
        }
        var resolvedKey = runCatching {
            resolvedVault.resolvePlaintextForBlindInjection("cloud_api_keys")?.second?.trim().orEmpty()
        }.getOrDefault("")
        if (resolvedKey.isEmpty()) {
            // Migrate any legacy plaintext SharedPreferences API key into AES-256-GCM BlindCredentialVault
            val legacyPlainKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
            if (legacyPlainKey.isNotEmpty()) {
                resolvedKey = legacyPlainKey
                runCatching {
                    resolvedVault.putSecret(
                        rawKey = "cloud_api_keys",
                        label = "Cloud VLM API Keys",
                        plaintext = resolvedKey,
                        category = VaultSecretCategory.API_TOKEN
                    )
                }
                prefs.edit().remove(KEY_API_KEY).apply()
            }
        }
        if (resolvedKey.isEmpty()) {
            // Only read app-private filesDir (never world-readable /sdcard/Download or /data/local/tmp)
            val privateKeysFile = java.io.File(context.filesDir, "hanson_api_keys.txt")
            val keysFileContent = runCatching {
                if (privateKeysFile.isFile && privateKeysFile.length() > 10L) {
                    privateKeysFile.readText(Charsets.UTF_8).trim()
                } else {
                    ""
                }
            }.getOrDefault("")
            resolvedKey = GeminiMultimodalClient.resolveApiKey(
                fileContent = keysFileContent,
                buildConfigKey = com.hanson.mobileuse.BuildConfig.GEMINI_API_KEY,
                envVar = System.getenv("GEMINI_API_KEY")
            )
            if (resolvedKey.isNotEmpty()) {
                runCatching {
                    resolvedVault.putSecret(
                        rawKey = "cloud_api_keys",
                        label = "Cloud VLM API Keys",
                        plaintext = resolvedKey,
                        category = VaultSecretCategory.API_TOKEN
                    )
                }
            }
        }
        return AgentEngineConfig(
            apiKey = resolvedKey,
            modelName = normalizedModel,
            maxIterations = prefs.getInt(KEY_MAX_ITER, 15).coerceIn(3, 40),
            autoShowFloatingHud = prefs.getBoolean(KEY_AUTO_HUD, true),
            ttsVoiceReply = prefs.getBoolean(KEY_TTS_VOICE, true),
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty().trim()
        )
    }

    fun updateConfig(
        apiKey: String? = null,
        modelName: String? = null,
        maxIterations: Int? = null,
        autoShowFloatingHud: Boolean? = null,
        ttsVoiceReply: Boolean? = null,
        customBaseUrl: String? = null
    ) {
        val current = _config.value
        val resolvedModel = GeminiMultimodalClient.normalize2026ModelName(modelName ?: current.modelName)
        val next = current.copy(
            apiKey = apiKey ?: current.apiKey,
            modelName = resolvedModel,
            maxIterations = (maxIterations ?: current.maxIterations).coerceIn(3, 40),
            autoShowFloatingHud = autoShowFloatingHud ?: current.autoShowFloatingHud,
            ttsVoiceReply = ttsVoiceReply ?: current.ttsVoiceReply,
            customBaseUrl = customBaseUrl?.trim() ?: current.customBaseUrl
        )
        if (apiKey != null) {
            val trimmedKey = next.apiKey.trim()
            runCatching {
                if (trimmedKey.isNotEmpty()) {
                    resolvedVault.putSecret(
                        rawKey = "cloud_api_keys",
                        label = "Cloud VLM API Keys",
                        plaintext = trimmedKey,
                        category = VaultSecretCategory.API_TOKEN
                    )
                } else {
                    resolvedVault.deleteSecret("cloud_api_keys")
                }
            }
        }
        prefs.edit()
            .remove(KEY_API_KEY)
            .putString(KEY_MODEL, next.modelName)
            .putInt(KEY_MAX_ITER, next.maxIterations)
            .putBoolean(KEY_AUTO_HUD, next.autoShowFloatingHud)
            .putBoolean(KEY_TTS_VOICE, next.ttsVoiceReply)
            .putString(KEY_CUSTOM_BASE_URL, next.customBaseUrl)
            .apply()
        _config.value = next
    }

    private fun loadPersistedChatMessages(): List<LiveChatMessage> {
        val raw = prefs.getString(KEY_CHAT_HISTORY, null)
        if (raw.isNullOrBlank()) {
            return listOf(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "안녕하세요! 화면을 함께 보며 대화하고 제어할 준비가 되었어요. 마이크를 켜고 편하게 말씀하시거나 아래에 입력해 주세요.",
                    subtitle = "S25 NPU · Conversational Full-Control",
                    toolBadge = "READY"
                )
            )
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LiveChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sender = runCatching { ChatSender.valueOf(obj.optString("sender", "AGENT")) }
                    .getOrDefault(ChatSender.AGENT)
                if (sender == ChatSender.NOTIFICATION) continue
                val rawMsg = LiveChatMessage(
                    id = obj.optString("id", "msg_$i"),
                    sender = sender,
                    text = obj.optString("text", ""),
                    subtitle = obj.optString("subtitle", ""),
                    toolBadge = obj.optString("toolBadge").takeIf { it.isNotBlank() },
                    succeeded = obj.optBoolean("succeeded", true),
                    replySessionId = obj.optString("replySessionId").takeIf { it.isNotBlank() },
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis()),
                    structuredCard = StructuredUiCard.fromJson(obj.optJSONObject("structuredCard")),
                    mediaBlock = MultimodalMediaBlock.fromJson(obj.optJSONObject("mediaBlock")),
                    detailPayload = ChatDetailPayload.fromJson(obj.optJSONObject("detailPayload"))
                )
                list.add(HumanSummaryAndCardBuilder.enrichMessageForHumanDisplay(rawMsg))
            }
            if (list.isEmpty()) {
                listOf(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "화면을 보며 대화하고 제어할 준비가 되었어요.",
                        toolBadge = "READY"
                    )
                )
            } else list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendChatMessage(msg: LiveChatMessage) {
        val routerState = aiInfrastructureRouter.state.value
        val enriched = HumanSummaryAndCardBuilder.enrichMessageForHumanDisplay(
            msg = msg,
            specialistRoleKo = routerState.lastSpecialistReason,
            turnLedger = routerState.turnModelLedger
        )
        _chatMessages.update { prev ->
            if (enriched.sender == ChatSender.AGENT) {
                val lastUserMsg = prev.lastOrNull { it.sender == ChatSender.USER }?.text.orEmpty()
                if (lastUserMsg.isNotBlank()) {
                    memoryManager.observeAndLearnFromTurn(lastUserMsg, enriched.text)
                }
            }
            val combined = (prev + enriched).takeLast(80)
            val compacted = memoryManager.compactChatHistoryIfNeeded(combined, keepRecentCount = 16)
            persistChatMessages(compacted)
            compacted
        }
        FloatingHudService.notifyChatUpdated(_chatMessages.value)
    }

    private fun persistChatMessages(messages: List<LiveChatMessage>) {
        try {
            val arr = JSONArray()
            messages.takeLast(50).forEach { arr.put(it.toJson()) }
            prefs.edit().putString(KEY_CHAT_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {
        }
    }

    fun clearLiveChat() {
        val welcome = LiveChatMessage(
            sender = ChatSender.AGENT,
            text = "대화 보드를 비웠어요. 지금 보고 계신 화면에서 무엇을 도와드릴까요?",
            subtitle = "Conversational Full-Control",
            toolBadge = "READY"
        )
        _chatMessages.value = listOf(welcome)
        persistChatMessages(_chatMessages.value)
        FloatingHudService.notifyChatUpdated(_chatMessages.value)
    }

    /**
     * One-Touch Environment Auto-Setup:
     * Enables unrestricted capability governance, triggers OS-level `self-heal`
     * (Accessibility, Notification Listener, Overlay), and refreshes mobile AI infrastructure.
     */
    fun runOneTouchAutoSetup() {
        scope.launch {
            permissionHub.setUnrestrictedMode(true)
            aiInfrastructureRouter.refreshInfrastructureState()
            val healOutcome = toolHarness.execute(
                toolName = "os_cli_exec",
                args = JSONObject().put("command", "self-heal"),
                latestSnapshot = null
            )
            permissionHub.refreshStatus()
            if (_config.value.autoShowFloatingHud) {
                FloatingHudService.startIfPermitted(context)
            }
            val aiState = aiInfrastructureRouter.state.value
            val npuNote = if (aiState.googleAiCoreInstalled) {
                "S25 내장 Gemini Nano NPU 및 접근성·오버레이 권한이 모두 연결되었습니다."
            } else {
                "접근성·알림·오버레이 권한 자동 구성을 완료했습니다."
            }
            val msgText = if (healOutcome.succeeded) npuNote else "런타임 권한과 온디바이스 AI 라우터 구성을 완료했습니다."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = msgText,
                    subtitle = "1-Touch Auto-Setup",
                    toolBadge = "AUTO-SETUP"
                )
            )
            speakIfEnabled(msgText)
        }
    }

    /**
     * Handles explicit Hanson self-configuration or mobile AI model management commands
     * without intercepting normal app-control requests like "유튜브 켜줘".
     */
    private fun tryHandleExplicitHansonSelfConfig(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT).trim()

        // 1. Mobile On-Device Model Download / Infrastructure status
        if (
            lower.contains("온디바이스 모델") ||
            lower.contains("모바일 모델") ||
            lower.contains("모바일 ai") ||
            lower.contains("모델 다운로드") ||
            lower.contains("모델 받아") ||
            lower.contains("인프라 상태")
        ) {
            val urlMatch = Regex("(https?://[^\\s]+)").find(prompt)?.value
            scope.launch {
                val statusMsg = aiInfrastructureRouter.downloadOnDeviceModel(urlMatch)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = statusMsg,
                        subtitle = "Mobile AI Infrastructure Router",
                        toolBadge = aiInfrastructureRouter.state.value.lastUsedBackendBadge
                    )
                )
                speakIfEnabled(statusMsg)
            }
            return true
        }

        // 2. Auto-setup / permissions
        if (lower.contains("권한 원터치") || lower.contains("환경 원터치") || lower.contains("권한 자동 구성") || lower.contains("자가 치유")) {
            runOneTouchAutoSetup()
            return true
        }

        // 2-B. 3-Tier Memory Management & Optimization ("메모리 관리", "메모리 정리", "메모리 상태", "기억 초기화")
        if (lower.contains("메모리") || lower.contains("기억 정리") || lower.contains("기억 초기화") || lower.contains("컨텍스트 압축")) {
            if (lower.contains("초기화") || lower.contains("다 지워")) {
                memoryManager.clearAllSemanticMemories()
            }
            runMemoryOptimization()
            return true
        }

        // 3. Theme switching (Ahaboard Paper Light vs Carbon Dark)
        if (lower == "다크 모드" || lower == "다크모드" || lower.contains("다크 모드로") || lower.contains("카본 모드로")) {
            ObsidianColors.isDark = true
            val reply = "다크 카본 무채색 모드로 바꿨어요."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = reply,
                    toolBadge = "CARBON"
                )
            )
            speakIfEnabled(reply)
            return true
        }
        if (lower == "라이트 모드" || lower == "라이트모드" || lower.contains("라이트 모드로") || lower.contains("페이퍼 모드로")) {
            ObsidianColors.isDark = false
            val reply = "밝은 페이퍼 무채색 모드로 바꿨어요."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = reply,
                    toolBadge = "PAPER"
                )
            )
            speakIfEnabled(reply)
            return true
        }

        // 4. Explicit Model / AI Router tier switching
        if (lower.contains("모델") && (lower.contains("바꿔") || lower.contains("변경") || lower.contains("전환") || lower.contains("적재적소") || lower.contains("멀티"))) {
            val matchedModel: Pair<String, String>? = when {
                lower.contains("멀티") || lower.contains("적재적소") || lower.contains("자동") || lower.contains("auto") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    GeminiMultimodalClient.AUTO_MULTI_MODEL to "적재적소 멀티모델 자동 오케스트레이터"
                }
                lower.contains("나노") || lower.contains("nano") || lower.contains("온디바이스") || lower.contains("npu") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.S25_AICORE_NANO)
                    "gemma-4-26b-a4b-it" to "Galaxy S25 내장 Gemini Nano NPU"
                }
                lower.contains("argon") || lower.contains("아곤") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "gemini-4-argon" to "Gemini 4 Argon"
                }
                lower.contains("opus") || lower.contains("오퍼스") || lower.contains("클로드") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "claude-opus-4-6" to "Claude Opus 4.6"
                }
                lower.contains("3.8") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "gemini-3.8-flash" to "Gemini 3.8 Flash"
                }
                lower.contains("3.5") || lower.contains("라이트") || lower.contains("lite") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "gemini-3.5-flash-lite" to "Gemini 3.5 Flash-Lite"
                }
                lower.contains("cli") || lower.contains("구독") || lower.contains("노트북") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "cli-subscription-bridge" to "노트북 CLI 구독 브리지"
                }
                else -> null
            }

            if (matchedModel != null) {
                val (modelId, label) = matchedModel
                updateConfig(modelName = modelId)
                val reply = "네, 추론 엔진을 $label 모드로 전환했어요."
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = reply,
                        toolBadge = "AUTO-MULTI-MODEL"
                    )
                )
                speakIfEnabled(reply)
                return true
            }
        }

        return false
    }

    fun runMemoryOptimization() {
        val dashboardCard = memoryManager.optimizeMemoryNow(
            currentMessages = _chatMessages.value,
            onUpdateMessages = { compacted ->
                _chatMessages.value = compacted
                persistChatMessages(compacted)
            }
        )
        val memState = memoryManager.state.value
        val rawMemReport = buildString {
            appendLine("=== 3-Tier Cognitive & Runtime Memory Report ===")
            appendLine("1. Runtime JVM Heap: ${memState.jvmUsedMb}MB / ${memState.jvmMaxMb}MB")
            appendLine("2. Device RAM Available: ${memState.deviceAvailRamMb}MB / ${memState.deviceTotalRamMb}MB")
            appendLine("3. Trimmed Historical Base64 Snapshots: ${memState.trimmedSnapshotCount}")
            appendLine("4. Compacted Working Digest: ${memState.compactedDigestSummary.ifBlank { "(None yet)" }}")
            appendLine("5. Semantic Memory Vault (${memState.semanticEntries.size} items):")
            memState.semanticEntries.forEachIndexed { idx, e ->
                appendLine("   [${idx + 1}] (${e.category}) ${e.titleKo}: ${e.contentKo}")
            }
        }.trim()

        val summaryKo = "단기 대화 컨텍스트 압축과 런타임 캐시 정리를 마쳤어요. 현재 앱 힙 메모리는 ${memState.jvmUsedMb}MB 사용 중이며, 장기 시맨틱 기억 ${memState.semanticEntries.size}건이 보존되어 있습니다."
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = summaryKo,
                subtitle = "3-Tier Memory Manager",
                toolBadge = "MEMORY-GUARD",
                structuredCard = dashboardCard,
                detailPayload = ChatDetailPayload(
                    humanSummary = summaryKo,
                    modelBadge = "MEMORY-GUARD",
                    specialistRoleKo = "3-Tier 단기 컨텍스트 압축 · 장기 시맨틱 볼트 · 런타임 RAM 가드",
                    executedToolName = "optimize_memory",
                    rawTechnicalOutput = rawMemReport,
                    turnLedger = aiInfrastructureRouter.state.value.turnModelLedger
                )
            )
        )
        speakIfEnabled(summaryKo)
    }

    fun injectExternalReply(text: String, badge: String = "LAPTOP CLI") {
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = text,
                subtitle = "실시간 브리지",
                toolBadge = badge
            )
        )
        speakIfEnabled(text)
        FloatingHudService.updateHudStatus("메시지 도착", text.take(32), false)
    }

    fun postIncomingNotificationToLiveChat(notif: InterceptedNotification) {
        val msg = LiveChatMessage(
            sender = ChatSender.NOTIFICATION,
            text = "[${notif.appLabel}] ${notif.sender}: ${notif.messageText}",
            subtitle = if (notif.canQuickReply) "방: ${notif.roomOrTitle} · 즉시 답장 가능" else "방: ${notif.roomOrTitle}",
            toolBadge = if (notif.canQuickReply) "RemoteInput" else "Notification",
            replySessionId = if (notif.canQuickReply) notif.sessionId else null
        )
        appendChatMessage(msg)
    }

    /**
     * Evaluates standing Reactive Automation Rules (`UniversalNexusEngine`) whenever
     * `HansonNotificationListenerService` intercepts an incoming notification.
     */
    fun onNotificationIntercepted(notif: InterceptedNotification) {
        scope.launch(Dispatchers.IO) {
            val triggerSummary = universalNexusEngine.evaluateReactiveRulesOnNotification(notif)
            if (!triggerSummary.isNullOrBlank()) {
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "[리액티브 자동 실행] $triggerSummary",
                        subtitle = "Universal Nexus Trigger · ${notif.appLabel}",
                        toolBadge = "NEXUS-AUTO"
                    )
                )
            }
        }
    }

    fun sendChatMessage(userMessage: String) {
        val trimmed = userMessage.trim()
        if (trimmed.isEmpty()) return

        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.USER,
                text = trimmed
            )
        )
        startRun(trimmed, appendUserBubble = false)
    }

    private fun registerParallelSlot(slot: ParallelTaskSlot) {
        _activeParallelTasks.update { prev -> prev.filterNot { it.taskId == slot.taskId } + slot }
    }

    private fun unregisterParallelSlot(taskId: String) {
        _activeParallelTasks.update { prev -> prev.filterNot { it.taskId == taskId } }
    }

    private fun splitCompoundParallelGoals(rawGoal: String): List<String> {
        val g = rawGoal.trim()
        if (g.isEmpty()) return emptyList()
        val splitRegex = Regex("\\s*(?:\\|\\||;|동시에|하면서\\s*동시에|하면서|그리고\\s*동시에|그리고\\s*(?=내\\s*앱|무슨\\s*게임|설치된|알림|볼륨|소리|밝기|크래프트))\\s*")
        val parts = g.split(splitRegex)
            .map { it.trim() }
            .filter { it.length >= 2 }
        return if (parts.size >= 2) parts else listOf(g)
    }

    private fun isBackgroundParallelizableGoal(goal: String): Boolean {
        val gLower = goal.lowercase(Locale.ROOT).trim()
        val decomposed = IntentDecomposerAndRouter.decompose(
            rawPrompt = goal,
            isMainActivityInForeground = FloatingHudService.isMainActivityInForeground
        )

        // Classify non-GUI system setting goals (volume, brightness, ringer, DND, flashlight/torch)
        val isDeviceSetting = gLower.contains("밝기") || gLower.contains("볼륨") || gLower.contains("소리") || gLower.contains("방해금지") ||
            gLower.contains("손전등") || gLower.contains("플래시") || gLower.contains("후레쉬") ||
            gLower.contains("진동") || gLower.contains("무음") || gLower.contains("벨소리") ||
            (gLower.contains("화면") && (gLower.contains("밝") || gLower.contains("어둡"))) ||
            (decomposed.primaryCategory == IntentCategory.EVERYDAY_OS_ACTION &&
             decomposed.fastDecision?.toolName == "control_device_setting" &&
             decomposed.fastDecision?.toolArgs?.optString("setting") !in listOf("wifi_panel", "bluetooth_panel"))

        // Instant reflexes, screen-element clicks, and non-setting OS/Routine actions run on Layer 0-Reflex with full StructuredUiCard support
        if (decomposed.primaryCategory == IntentCategory.REFLEX_MICRO_CONTROL ||
            decomposed.primaryCategory == IntentCategory.SCREEN_ELEMENT_TARGET ||
            (decomposed.primaryCategory == IntentCategory.EVERYDAY_OS_ACTION && !isDeviceSetting) ||
            decomposed.primaryCategory == IntentCategory.COMPOUND_LIFESTYLE_ROUTINE
        ) {
            return false
        }

        // Never hijack foreground multi-step app launch / search / click intents into the headless background lane!
        val hasTargetExternalApp = IntentDecomposerAndRouter.resolveTargetAppFromPrompt(goal) != null
        val hasForegroundActionVerb = gLower.contains("열어서") || gLower.contains("켜서") ||
            gLower.contains("검색") || gLower.contains("클릭") || gLower.contains("눌러") ||
            gLower.contains("들어가") || gLower.contains("틀어")
        if (hasTargetExternalApp && hasForegroundActionVerb) {
            return false
        }

        val isGameOrAppInventory = (gLower.contains("게임") || gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린 앱") || gLower.contains("깔린 게임")) &&
            (gLower.contains("찾아") || gLower.contains("뭐 있") || gLower.contains("무슨") || gLower.contains("알려") || gLower.contains("확인") || gLower.contains("목록") || gLower.contains("스캔"))
        val isCraftBlueprint = gLower.contains("크래프트") || gLower.contains("craft") || gLower.contains("클린룸") || gLower.contains("구조 분석") || gLower.contains("리버스 엔지니어링")
        val isNotifCheckOrReply = gLower.contains("알림") || gLower.contains("답장") || (gLower.contains("카톡") && (gLower.contains("왔어") || gLower.contains("요약")))
        val isScreenOrStatusInquiry = (gLower.contains("화면") && (gLower.contains("뭐") || gLower.contains("설명") || gLower.contains("브리핑"))) ||
            gLower.contains("무슨 작업 중") || gLower.contains("병렬 상태")
        val isOmniNexusHeadless = gLower.contains("브라우저로") || gLower.contains("웹페이지") || gLower.contains("해커뉴스") ||
            gLower.contains("코드로") ||
            decomposed.primaryCategory == IntentCategory.GENERATIVE_CODE_OR_MEDIA ||
            (decomposed.primaryCategory == IntentCategory.MEDIA_SEARCH_AND_PLAY && decomposed.isExplicitInAppMedia) ||
            gLower.contains("주변 기기") || gLower.contains("블루투스 기기") || gLower.contains("노트북으로")
        return isGameOrAppInventory || isCraftBlueprint || isNotifCheckOrReply || isDeviceSetting || isScreenOrStatusInquiry || isOmniNexusHeadless
    }

    private fun launchParallelBackgroundTask(bgGoal: String) {
        val taskId = "bg_${System.currentTimeMillis()}_${(100..999).random()}"
        val slot = ParallelTaskSlot(taskId = taskId, lane = "BG", goal = bgGoal)
        registerParallelSlot(slot)

        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val gLower = bgGoal.lowercase(Locale.ROOT).trim()
                val snapshot = HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)

                // Check Universal Omni-Nexus Fast-Path first (Browser-Use, Code Canvas, Image/GIF/MP4/GLB Gen, Cross-Device, Pipelines)
                val nexusRes = universalNexusEngine.tryExecuteUniversalNexus(
                    prompt = bgGoal,
                    latestSnapshot = snapshot,
                    apiKey = _config.value.apiKey
                )
                if (nexusRes != null && nexusRes.handled) {
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = nexusRes.summaryKo,
                            subtitle = "Universal Omni-Nexus · ${nexusRes.badge}",
                            toolBadge = nexusRes.badge,
                            mediaBlock = nexusRes.mediaBlock
                        )
                    )
                    speakIfEnabled(nexusRes.summaryKo)
                    if (!nexusRes.followUpGuiGoal.isNullOrBlank()) {
                        dispatchToForegroundGuiLane(listOf(nexusRes.followUpGuiGoal))
                    }
                    return@launch
                }

                when {
                    (gLower.contains("게임") || gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린")) -> {
                        val wantsGames = gLower.contains("게임")
                        val report = toolHarness.scanInstalledGamesAndApps(
                            query = "",
                            prioritizeGames = wantsGames
                        )
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = report,
                                subtitle = "병렬 백그라운드 스캔 완료 (${_activeParallelTasks.value.size}개 레인)",
                                toolBadge = "병렬완료 · 앱/게임 스캔"
                            )
                        )
                        speakIfEnabled(report)
                    }
                    (gLower.contains("크래프트") || gLower.contains("craft") || gLower.contains("클린룸") || gLower.contains("구조 분석") || gLower.contains("리버스 엔지니어링")) -> {
                        val snap = snapshot ?: AtomicSnapshot(
                            screenWidth = 1080,
                            screenHeight = 2340,
                            activePackage = context.packageName,
                            activeWindowTitle = "Hanson Board"
                        )
                        val decision = craftAnythingEngine.runCraftAnythingWorkflow(bgGoal, snap)
                        val summary = decision.spokenReply.ifBlank {
                            decision.toolArgs.optString("summary", "크래프트 클린룸 분석을 완료했어요.")
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = summary,
                                subtitle = "병렬 백그라운드 · CRAFT-ANYTHING",
                                toolBadge = "병렬완료 · CRAFT-ANYTHING"
                            )
                        )
                        speakIfEnabled(summary)
                    }
                    (gLower.contains("답장") || (gLower.contains("알림") && gLower.contains("보내"))) -> {
                        val replyMsg = bgGoal.substringAfter("\"", "").substringBefore("\"", "")
                            .ifBlank { bgGoal.substringAfter("'", "").substringBefore("'", "") }
                            .ifBlank { bgGoal.replace("답장해줘", "").replace("답장", "").trim() }
                        val outcome = toolHarness.execute(
                            toolName = "reply_notification",
                            args = JSONObject().put("target", "").put("message", replyMsg),
                            latestSnapshot = snapshot
                        )
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · RemoteInput",
                                toolBadge = "병렬완료 · 즉시답장"
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    (gLower.contains("알림") || (gLower.contains("카톡") && (gLower.contains("왔어") || gLower.contains("요약")))) -> {
                        val outcome = toolHarness.execute(
                            toolName = "list_notifications",
                            args = JSONObject().put("only_replyable", false).put("limit", 8),
                            latestSnapshot = snapshot
                        )
                        val notifSummary = if (outcome.outputText.contains("없습니다") || outcome.outputText.isBlank()) {
                            "백그라운드에서 확인해 보니 현재 새로 쌓인 알림 세션이 없어요."
                        } else {
                            val lines = outcome.outputText.lines().filter { it.isNotBlank() }.take(4)
                            "백그라운드 병렬 알림 확인 결과: ${lines.joinToString(" / ")}"
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = notifSummary,
                                subtitle = "병렬 백그라운드 · 알림 브리핑",
                                toolBadge = "병렬완료 · 알림브리핑"
                            )
                        )
                        speakIfEnabled(notifSummary)
                    }
                    (gLower.contains("밝기") || gLower.contains("볼륨") || gLower.contains("소리") || gLower.contains("방해금지") ||
                     gLower.contains("손전등") || gLower.contains("플래시") || gLower.contains("후레쉬") ||
                     gLower.contains("진동") || gLower.contains("무음") || gLower.contains("벨소리")) -> {
                        val settingArgs = when {
                            gLower.contains("손전등") || gLower.contains("플래시") || gLower.contains("후레쉬") -> {
                                val off = gLower.contains("꺼") || gLower.contains("해제") || gLower.contains("off")
                                val vInt = if (off) 0 else 1
                                JSONObject().put("setting", "flashlight").put("value", vInt).put("state", if (off) "off" else "on").put("enabled", vInt)
                            }
                            gLower.contains("진동") || gLower.contains("무음") || gLower.contains("벨소리") -> {
                                val mode = when {
                                    gLower.contains("무음") -> "silent"
                                    gLower.contains("진동") -> "vibrate"
                                    else -> "normal"
                                }
                                JSONObject().put("setting", "ringer_mode").put("value", mode)
                            }
                            gLower.contains("밝기") -> {
                                val num = Regex("\\d+").find(bgGoal)?.value?.toIntOrNull()
                                    ?: if (gLower.contains("낮") || gLower.contains("줄") || gLower.contains("어둡")) 80 else 200
                                JSONObject().put("setting", "brightness").put("value", num.toString())
                            }
                            gLower.contains("방해금지") -> {
                                val on = !(gLower.contains("꺼") || gLower.contains("해제"))
                                JSONObject().put("setting", "dnd").put("value", if (on) "on" else "off")
                            }
                            else -> {
                                val num = Regex("\\d+").find(bgGoal)?.value?.toIntOrNull()
                                    ?: if (gLower.contains("낮") || gLower.contains("줄") || gLower.contains("작게")) 25 else 60
                                JSONObject().put("setting", "volume_media").put("value", num.toString())
                            }
                        }
                        val outcome = toolHarness.execute("control_device_setting", settingArgs, snapshot)
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · OS Fast-Lane",
                                toolBadge = "병렬완료 · OS제어"
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    // 5. Headless Contacts / Calendar / Notes / OS CLI / General Background Task Execution
                    gLower.contains("연락처") || gLower.contains("전화번호") -> {
                        val q = bgGoal.replace("연락처", "").replace("전화번호", "").replace("찾아줘", "").replace("찾아", "").replace("알려줘", "").trim()
                        val outcome = toolHarness.execute("search_contacts", JSONObject().put("query", q), snapshot)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 연락처 조회",
                                toolBadge = "병렬완료 · BG연락처"
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    gLower.contains("일정") || gLower.contains("캘린더") -> {
                        val outcome = toolHarness.execute("manage_calendar", JSONObject().put("action", "list"), snapshot)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 캘린더 조회",
                                toolBadge = "병렬완료 · BG캘린더"
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    gLower.contains("메모") || gLower.contains("노트") -> {
                        val outcome = if (gLower.contains("저장") || gLower.contains("기억")) {
                            toolHarness.execute("save_note", JSONObject().put("key", "bg_note").put("content", bgGoal), snapshot)
                        } else {
                            toolHarness.execute("read_note", JSONObject().put("key", ""), snapshot)
                        }
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 메모리 노트",
                                toolBadge = "병렬완료 · BG노트"
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    else -> {
                        val snap = snapshot ?: AtomicSnapshot(
                            screenWidth = 1080,
                            screenHeight = 2340,
                            activePackage = context.packageName,
                            activeWindowTitle = "Hanson Board"
                        )
                        val cfg = _config.value
                        val decision = aiInfrastructureRouter.routeAndDecide(
                            apiKey = cfg.apiKey,
                            modelName = cfg.modelName,
                            goal = bgGoal,
                            iteration = 1,
                            snapshot = snap,
                            historySteps = emptyList(),
                            previousPlan = emptyList(),
                            chatHistory = _chatMessages.value,
                            customBaseUrl = cfg.customBaseUrl
                        )
                        val headlessSafeTools = setOf(
                            "list_notifications", "reply_notification", "control_device_setting",
                            "search_contacts", "manage_calendar", "set_alarm", "save_note", "read_note",
                            "os_cli_exec", "reverse_engineer_app", "learn_app_skill", "render_generative_ui",
                            "browser_use", "cross_device_control", "draw_with_code", "generate_visual_media",
                            "workspace_list", "workspace_read", "workspace_write",
                            "workspace_edit", "workspace_search", "workspace_exec",
                            "vault_list_handles", "watcher_manage", "a2a_negotiate_schedule", "scratchpad_note"
                        )
                        val resultText = if (decision.toolName in headlessSafeTools ||
                            (decision.toolName == "manage_app" && decision.toolArgs.optString("action") != "launch")
                        ) {
                            val outcome = toolHarness.execute(decision.toolName, decision.toolArgs, snap)
                            recordReceipt(outcome.receipt)
                            outcome.outputText.ifBlank { decision.spokenReply }
                        } else {
                            decision.spokenReply.ifBlank {
                                decision.toolArgs.optString("summary", decision.observation)
                            }
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = resultText,
                                subtitle = "병렬 백그라운드 (BG Lane)",
                                toolBadge = "병렬완료 · BG실행"
                            )
                        )
                        speakIfEnabled(resultText)
                    }
                }
            } finally {
                concurrentBackgroundJobs.remove(taskId)
                unregisterParallelSlot(taskId)
            }
        }
        concurrentBackgroundJobs[taskId] = job
        job.start()
    }

    private fun extractExplicitLanePrefix(rawGoal: String): Pair<ExecutionLaneMode, String> {
        val trimmed = rawGoal.trim()
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("[bg]") -> ExecutionLaneMode.BACKGROUND to trimmed.substring(4).trim()
            lower.startsWith("/bg ") -> ExecutionLaneMode.BACKGROUND to trimmed.substring(4).trim()
            lower.startsWith("백그라운드로 ") -> ExecutionLaneMode.BACKGROUND to trimmed.removePrefix("백그라운드로 ").trim()
            lower.startsWith("백그라운드에서 ") -> ExecutionLaneMode.BACKGROUND to trimmed.removePrefix("백그라운드에서 ").trim()
            lower.startsWith("뒤에서 ") -> ExecutionLaneMode.BACKGROUND to trimmed.removePrefix("뒤에서 ").trim()
            lower.startsWith("[fg]") -> ExecutionLaneMode.FOREGROUND to trimmed.substring(4).trim()
            lower.startsWith("/fg ") -> ExecutionLaneMode.FOREGROUND to trimmed.substring(4).trim()
            lower.startsWith("포그라운드로 ") -> ExecutionLaneMode.FOREGROUND to trimmed.removePrefix("포그라운드로 ").trim()
            lower.startsWith("포그라운드에서 ") -> ExecutionLaneMode.FOREGROUND to trimmed.removePrefix("포그라운드에서 ").trim()
            lower.startsWith("화면에서 ") -> ExecutionLaneMode.FOREGROUND to trimmed.removePrefix("화면에서 ").trim()
            else -> ExecutionLaneMode.AUTO to trimmed
        }
    }

    fun startRun(
        goalPrompt: String,
        appendUserBubble: Boolean = true,
        laneMode: ExecutionLaneMode = ExecutionLaneMode.AUTO
    ) {
        val rawTrimmed = goalPrompt.trim()
        if (rawTrimmed.isEmpty()) return

        val (prefixLane, cleanedGoal) = extractExplicitLanePrefix(rawTrimmed)
        val effectiveLane = if (laneMode != ExecutionLaneMode.AUTO) laneMode else prefixLane
        val trimmedGoal = cleanedGoal.ifBlank { rawTrimmed }

        if (appendUserBubble) {
            val prefixTag = when (effectiveLane) {
                ExecutionLaneMode.BACKGROUND -> "[BG] "
                ExecutionLaneMode.FOREGROUND -> "[FG] "
                ExecutionLaneMode.AUTO -> ""
            }
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.USER,
                    text = "$prefixTag$trimmedGoal"
                )
            )
        }

        val lower = trimmedGoal.lowercase(Locale.ROOT)
        if (lower in listOf("멈춰", "스톱", "그만", "정지", "취소", "잠깐만 멈춰", "멈춰줘", "그만해")) {
            stopRun("네, 모든 병렬 및 화면 작업을 바로 멈췄어요. 다음엔 어떻게 할까요?")
            return
        }

        if (tryHandleExplicitHansonSelfConfig(trimmedGoal)) {
            return
        }

        if (_currentSession.value.status == AgentRunStatus.AWAITING_APPROVAL && approvalDeferred != null) {
            val approve = !(lower.contains("아니") || lower.contains("취소") || lower.contains("거절") || lower.contains("마"))
            resolvePendingApproval(approve, alwaysAllowCapability = approve)
            return
        }

        if (_currentSession.value.status == AgentRunStatus.AWAITING_TAKEOVER && takeoverDeferred != null) {
            val resume = !(lower.contains("취소") || lower.contains("중단") || lower.contains("그만"))
            resolveHumanTakeover(completedByHuman = resume)
            return
        }

        val parsedInput = ComposerCommandParser.parse(trimmedGoal, _workMode.value)
        if (tryHandleStudioSlashCommand(parsedInput)) {
            return
        }

        if (parsedInput.slashCommand == null) {
            val learnedBubble = resolvedMemoryBubbles.extractAndLearnFromUserUtterance(trimmedGoal, resolvedVault)
            if (learnedBubble != null) {
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "${learnedBubble.emoji} 내 취향 기억 버블에 저장했어요: \"${learnedBubble.factKo}\"",
                        subtitle = "Memory Bubbles · 언제든 탭하여 끄거나 삭제 가능",
                        toolBadge = "기억 버블"
                    )
                )
            }
        }

        val effectiveWorkMode = parsedInput.resolvedMode ?: _workMode.value
        if (parsedInput.slashCommand in listOf("/auto", "/plan", "/build")) {
            setWorkMode(effectiveWorkMode)
        }

        val baseGoal = if (parsedInput.slashCommand != null || parsedInput.mentions.isNotEmpty()) {
            parsedInput.cleanPrompt.ifBlank { trimmedGoal }
        } else {
            trimmedGoal
        }

        if (effectiveWorkMode == AgentWorkMode.PLAN) {
            enterPlanModeAndEmitBlueprint(goal = baseGoal, parsed = parsedInput)
            return
        }

        val dispatchGoal = if (parsedInput.mentions.isNotEmpty()) {
            buildContextualPrompt(baseGoal, parsedInput)
        } else {
            baseGoal
        }
        syncSessionStoreStatus(statusBadge = "RUNNING", previewLine = baseGoal.take(80), hasPendingApproval = false)

        // Explicit BACKGROUND dispatch: force all sub-goals into the non-blocking Background Lane!
        if (effectiveLane == ExecutionLaneMode.BACKGROUND) {
            val subGoals = splitCompoundParallelGoals(dispatchGoal)
            subGoals.forEach { launchParallelBackgroundTask(it) }
            return
        }

        // Explicit FOREGROUND dispatch: force into the Foreground GUI Lane!
        if (effectiveLane == ExecutionLaneMode.FOREGROUND) {
            dispatchToForegroundGuiLane(listOf(dispatchGoal))
            return
        }

        // AUTO smart lane splitting: fan out compound goals into BG and FG lanes simultaneously!
        val subGoals = splitCompoundParallelGoals(dispatchGoal)
        val bgSubGoals = mutableListOf<String>()
        val guiSubGoals = mutableListOf<String>()

        for (sg in subGoals) {
            val (sgLane, sgClean) = extractExplicitLanePrefix(sg)
            when {
                sgLane == ExecutionLaneMode.BACKGROUND -> bgSubGoals.add(sgClean)
                sgLane == ExecutionLaneMode.FOREGROUND -> guiSubGoals.add(sgClean)
                isBackgroundParallelizableGoal(sgClean) -> bgSubGoals.add(sgClean)
                else -> guiSubGoals.add(sgClean)
            }
        }

        for (bgGoal in bgSubGoals) {
            launchParallelBackgroundTask(bgGoal)
        }

        if (guiSubGoals.isEmpty()) {
            return
        }

        dispatchToForegroundGuiLane(guiSubGoals)
    }

    private fun dispatchToForegroundGuiLane(guiSubGoals: List<String>) {
        if (guiSubGoals.isEmpty()) return
        val primaryGuiGoal = guiSubGoals.first()
        val extraGuiGoals = guiSubGoals.drop(1)
        extraGuiGoals.forEach { pendingGuiGoals.offer(it) }

        val isGuiLaneBusy = activeJob?.isActive == true &&
            (_currentSession.value.status == AgentRunStatus.OBSERVING ||
                _currentSession.value.status == AgentRunStatus.THINKING ||
                _currentSession.value.status == AgentRunStatus.ACTING)

        if (isGuiLaneBusy) {
            val pLower = primaryGuiGoal.lowercase(Locale.ROOT)
            val isMidFlightSteering = pLower.startsWith("아니") ||
                pLower.startsWith("잠깐") ||
                pLower.contains("그거 말고") ||
                pLower.contains("대신") ||
                pLower.contains("더 내려") ||
                pLower.contains("위로 올려") ||
                pLower.contains("첫 번째") ||
                pLower.contains("두 번째") ||
                pLower.contains("뒤로")

            if (isMidFlightSteering) {
                liveSteeringGoal = primaryGuiGoal
                _currentSession.update { it.copy(goal = primaryGuiGoal) }
                FloatingHudService.updateHudStatus("실시간 방향 수정", primaryGuiGoal.take(30), true)
                return
            } else {
                pendingGuiGoals.offer(primaryGuiGoal)
                val queueSize = pendingGuiGoals.size
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "현재 화면 제어 작업과 병렬로 대기열(${queueSize}건)에 등록했어요. 지금 단계가 끝나자마자 이어서 '$primaryGuiGoal' 작업을 바로 실행할게요!",
                        subtitle = "Parallel GUI Queue · $queueSize pending",
                        toolBadge = "병렬큐 등록"
                    )
                )
                return
            }
        }

        startGuiLaneLoop(primaryGuiGoal)
    }

    private fun startGuiLaneLoop(guiGoal: String) {
        activeJob?.cancel()
        approvalDeferred?.complete(false)
        liveSteeringGoal = null

        val cfg = _config.value
        val initialSession = AgentRunSession(
            goal = guiGoal,
            status = AgentRunStatus.OBSERVING,
            maxIterations = cfg.maxIterations,
            subGoals = listOf(SubGoalItem("화면 확인 및 병렬 실행", SubGoalStatus.IN_PROGRESS))
        )
        _currentSession.value = initialSession

        if (cfg.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(context)
        }
        FloatingHudService.updateHudStatus("한슨 병렬 제어 중", guiGoal.take(30), true)

        val guiTaskId = "gui_${initialSession.runId}"
        registerParallelSlot(ParallelTaskSlot(taskId = guiTaskId, lane = "GUI", goal = guiGoal))

        activeJob = scope.launch {
            try {
                FloatingHudService.setTouchPassthrough(true)
                runLoop(guiGoal, cfg)
            } finally {
                FloatingHudService.setTouchPassthrough(false)
                unregisterParallelSlot(guiTaskId)
                val nextQueuedGui = pendingGuiGoals.poll()
                if (!nextQueuedGui.isNullOrBlank()) {
                    startGuiLaneLoop(nextQueuedGui)
                }
            }
        }
    }

    fun stopRun(reason: String = "네, 현재 작업을 멈췄어요. 이어서 무엇을 도와드릴까요?") {
        FloatingHudService.setTouchPassthrough(false)
        FloatingHudService.updateApprovalPrompt(false)
        pendingGuiGoals.clear()
        concurrentBackgroundJobs.values.forEach { it.cancel() }
        concurrentBackgroundJobs.clear()
        _activeParallelTasks.value = emptyList()
        activeJob?.cancel()
        activeJob = null
        liveSteeringGoal = null
        approvalDeferred?.complete(false)
        approvalDeferred = null
        takeoverDeferred?.complete(false)
        takeoverDeferred = null
        lastApprovedAmountText = null
        lastBoundApprovalReceiptId = null
        lastBoundControlEpoch = null
        currentControlEpoch.incrementAndGet()

        _currentSession.update { prev ->
            val stopped = prev.copy(
                status = AgentRunStatus.STOPPED,
                pendingApproval = null,
                pendingTakeover = null,
                finalSummary = reason,
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(stopped)
            stopped
        }
        syncSessionStoreStatus("STOPPED", reason.take(80), hasPendingApproval = false)
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = reason,
                toolBadge = "STOPPED",
                succeeded = true
            )
        )
        speakIfEnabled(reason)
        FloatingHudService.updateHudStatus("소통 및 제어 대기", reason.take(28), false)
    }

    @JvmOverloads
    fun resolvePendingApproval(
        approved: Boolean,
        alwaysAllowCapability: Boolean = false,
        externalApprovalReceiptId: String? = null,
        controlEpoch: Long? = null
    ) {
        val pending = _currentSession.value.pendingApproval
        val effectiveEpoch = controlEpoch ?: currentControlEpoch.get()
        if (approved && !externalApprovalReceiptId.isNullOrBlank()) {
            val validation = FinancialAndTakeoverGuard.validateExternalApprovalReceipt(
                receiptId = externalApprovalReceiptId,
                controlEpoch = effectiveEpoch,
                expectedControlEpoch = currentControlEpoch.get(),
                toolName = pending?.toolSpec?.name.orEmpty(),
                detectedAmountText = pending?.detectedPriceText.orEmpty(),
                consumedReceiptIds = consumedExternalApprovalReceipts.toSet()
            )
            if (!validation.valid) {
                resolvedVault.recordAuditEvent(
                    action = "EXTERNAL_APPROVAL_REJECTED",
                    handle = pending?.args?.optString("handle", pending.toolSpec.name).orEmpty().ifBlank { "approval" },
                    approvedReceiptId = externalApprovalReceiptId,
                    controlEpoch = effectiveEpoch,
                    outcome = validation.rejectionCode
                )
                FloatingHudService.updateApprovalPrompt(false)
                approvalDeferred?.complete(false)
                return
            }
            consumedExternalApprovalReceipts.add(validation.receiptId)
            lastBoundApprovalReceiptId = validation.receiptId
            lastBoundControlEpoch = validation.controlEpoch
            resolvedVault.recordAuditEvent(
                action = "EXTERNAL_APPROVAL_BOUND",
                handle = pending?.args?.optString("handle", pending.toolSpec.name).orEmpty().ifBlank { "approval" },
                approvedReceiptId = validation.receiptId,
                controlEpoch = validation.controlEpoch,
                outcome = "APPROVED"
            )
        }
        if (approved && alwaysAllowCapability && pending?.toolSpec?.capability != null && !pending.isHighStakesFinancial) {
            permissionHub.setCapabilityLevel(pending.toolSpec.capability, PermissionLevel.ALWAYS)
        }
        FloatingHudService.updateApprovalPrompt(false)
        syncSessionStoreStatus(
            statusBadge = if (approved) "ACTING" else "STOPPED",
            previewLine = pending?.toolSpec?.name ?: "",
            hasPendingApproval = false
        )
        approvalDeferred?.complete(approved)
    }

    fun resolveHumanTakeover(completedByHuman: Boolean) {
        currentControlEpoch.incrementAndGet()
        syncSessionStoreStatus(
            statusBadge = if (completedByHuman) "OBSERVING" else "STOPPED",
            previewLine = if (completedByHuman) "사용자 직접 인증 완료 · 재개" else "사용자 직접 확인 취소",
            hasPendingApproval = false
        )
        takeoverDeferred?.complete(completedByHuman)
    }

    fun clearReceipts() {
        _allReceipts.value = emptyList()
        syncFloatingHudUndoAvailability()
    }

    private fun recordReceipt(receipt: ActionReceipt?) {
        if (receipt == null) return
        _allReceipts.update { (listOf(receipt) + it).take(50) }
        syncFloatingHudUndoAvailability()
    }

    private fun syncFloatingHudUndoAvailability() {
        val nextUndo = _allReceipts.value.firstOrNull { it.canOneTapUndo }
        FloatingHudService.updateUndoAvailability(
            available = nextUndo != null,
            labelKo = nextUndo?.undoHintKo.orEmpty()
        )
    }

    fun executeOneTapUndo(receiptId: String? = null): Boolean {
        val target = if (receiptId != null) {
            _allReceipts.value.firstOrNull { it.id == receiptId && it.canOneTapUndo }
                ?: _currentSession.value.receipts.asReversed().firstOrNull { it.id == receiptId && it.canOneTapUndo }
        } else {
            _allReceipts.value.firstOrNull { it.canOneTapUndo }
                ?: _currentSession.value.receipts.asReversed().firstOrNull { it.canOneTapUndo }
        } ?: return false

        _allReceipts.update { list ->
            list.map { if (it.id == target.id) it.copy(undone = true) else it }
        }
        _currentSession.update { sess ->
            sess.copy(
                receipts = sess.receipts.map { if (it.id == target.id) it.copy(undone = true) else it }
            )
        }
        syncFloatingHudUndoAvailability()

        scope.launch(Dispatchers.IO) {
            val snap = runCatching {
                HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)
            }.getOrNull()
            val undoArgs = runCatching { JSONObject(target.undoArgsJson) }.getOrDefault(JSONObject())
            toolHarness.execute(
                toolName = target.undoToolName,
                args = undoArgs,
                latestSnapshot = snap
            )
            val confirmKo = "↩ '${target.summaryKo}' 작업을 되돌렸어요."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = confirmKo,
                    subtitle = "1-Tap Undo Receipt",
                    toolBadge = "되돌리기 완료"
                )
            )
            speakIfEnabled(confirmKo)
            FloatingHudService.updateHudStatus("되돌리기 완료", target.summaryKo.take(28), false)
        }
        return true
    }

    fun finishDemonstrationAndSaveRoutine(customTitle: String = ""): PersonalRoutine? {
        val stepCount = UserDemonstrationRecorder.recordedSteps.value.size
        val (recordedPackage, compiledRecipe) = UserDemonstrationRecorder.stopAndCompile()
        FloatingHudService.updateRecordingBadge(false, 0)
        if (compiledRecipe.isBlank()) {
            return null
        }
        val created = resolvedPersonalRoutines.compileFromDemonstration(
            customTitle = customTitle,
            recordedPackage = recordedPackage,
            compiledRecipe = compiledRecipe,
            stepCount = stepCount.coerceAtLeast(1)
        )
        val confirmKo = "🎬 직접 보여주신 ${created.stepCount}단계 동작을 '${created.titleKo}' 루틴으로 저장했어요. 이제 원탭으로 바로 실행할 수 있어요."
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = confirmKo,
                subtitle = "Show-Me-Once Routine",
                toolBadge = "루틴 저장"
            )
        )
        speakIfEnabled(confirmKo)
        return created
    }

    private suspend fun runLoop(initialGoal: String, cfg: AgentEngineConfig) {
        val (fallbackW, fallbackH) = HierarchyDumper.getScreenDimensions(context)
        var currentGoal = initialGoal
        var lastSpokenStepText = ""
        loopBreaker.reset()
        lastApprovedAmountText = null
        lastStepDeltaRatio = 1.0f

        for (iter in 1..cfg.maxIterations) {
            // Check if the user spoke a new mid-execution steering instruction!
            val steered = liveSteeringGoal
            if (steered != null) {
                currentGoal = steered
                liveSteeringGoal = null
            }

            _currentSession.update {
                it.copy(
                    goal = currentGoal,
                    currentIteration = iter,
                    status = AgentRunStatus.OBSERVING
                )
            }

            val fastSnapshot: AtomicSnapshot? = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = false
            )

            // =========================================================================
            // LAYER 0-REFLEX: ZERO-LATENCY (<30ms) ATOMIC REFLEX & SCREEN ELEMENT DISPATCH
            // Executes "한칸 내려줘", "밑으로 내려 봐", "뒤로 가", "저 위에 내 잘못 vs 도둑 잘못 영상 클릭해 줘"
            // IMMEDIATELY without screenshot encoding delay, without pre-action LLM chatter, and without Turn 2 loop!
            // =========================================================================
            if (iter == 1) {
                val reflexSnap = fastSnapshot ?: AtomicSnapshot(
                    screenWidth = fallbackW,
                    screenHeight = fallbackH,
                    activePackage = context.packageName,
                    activeWindowTitle = "Hanson Board"
                )
                val decomposed = IntentDecomposerAndRouter.decompose(
                    rawPrompt = currentGoal,
                    snapshot = reflexSnap,
                    chatHistory = _chatMessages.value,
                    isMainActivityInForeground = FloatingHudService.isMainActivityInForeground
                )
                val fastDec = decomposed.fastDecision
                if (fastDec != null && decomposed.autoCompleteSingleTurn) {
                    _currentSession.update { it.copy(status = AgentRunStatus.ACTING, subGoals = fastDec.subGoals) }
                    val outcome = toolHarness.execute(
                        toolName = fastDec.toolName,
                        args = fastDec.toolArgs,
                        latestSnapshot = reflexSnap
                    )
                    recordReceipt(outcome.receipt)
                    val conciseSummary = fastDec.spokenReply.ifBlank { outcome.outputText }
                    val cleanSummary = HumanStepTranslator.sanitizeForHumanDisplay(conciseSummary)
                        .ifBlank { conciseSummary }
                    val stepLog = AgentStepLog(
                        stepNumber = 1,
                        observation = fastDec.observation,
                        planLedger = fastDec.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                        toolName = fastDec.toolName,
                        toolArgsJson = fastDec.toolArgs.toString(),
                        resultSummary = cleanSummary,
                        succeeded = outcome.succeeded,
                        activePackage = reflexSnap.activePackage,
                        elementCount = reflexSnap.indexedElements.size
                    )
                    FloatingHudService.setTouchPassthrough(false)
                    _currentSession.update { prev ->
                        val completed = prev.copy(
                            status = AgentRunStatus.COMPLETED,
                            subGoals = fastDec.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                            steps = prev.steps + stepLog,
                            receipts = if (outcome.receipt != null) prev.receipts + outcome.receipt else prev.receipts,
                            finalSummary = cleanSummary,
                            finishedAtMs = System.currentTimeMillis()
                        )
                        archiveSession(completed)
                        completed
                    }
                    persistRunWalkthrough(
                        goal = currentGoal,
                        summary = conciseSummary,
                        steps = _currentSession.value.steps,
                        receipts = _currentSession.value.receipts
                    )
                    val reflexBadge = when (decomposed.primaryCategory) {
                        IntentCategory.REFLEX_MICRO_CONTROL -> "S1-REFLEX"
                        IntentCategory.EVERYDAY_OS_ACTION -> "OS-DIRECT"
                        IntentCategory.COMPOUND_LIFESTYLE_ROUTINE -> "VIBE-ROUTINE"
                        else -> "S1-DIRECT-TARGET"
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = conciseSummary,
                            subtitle = reflexBadge,
                            toolBadge = "${fastDec.toolName} · $reflexBadge",
                            structuredCard = decomposed.structuredCard ?: outcome.structuredCard,
                            mediaBlock = outcome.mediaBlock
                        )
                    )
                    if (LiveVoiceSessionManager.state.value.isActive) {
                        speakIfEnabled(conciseSummary)
                    }
                    FloatingHudService.updateHudStatus("즉각 실행 완료", conciseSummary.take(28), false)
                    return
                }
            }

            val baseSnapshot: AtomicSnapshot = fastSnapshot ?: AtomicSnapshot(
                screenWidth = fallbackW,
                screenHeight = fallbackH,
                activePackage = context.packageName,
                activeWindowTitle = "Hanson Board"
            )

            val taskComplexity = aiInfrastructureRouter.estimateTaskComplexity(currentGoal, baseSnapshot)
            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("관측 · 추론 [${taskComplexity.name}]", currentGoal.take(28), true)

            val stepStartMs = System.currentTimeMillis()
            val prevSteps = _currentSession.value.steps

            // =========================================================================
            // LAYER 0: UNIVERSAL OMNI-NEXUS (Zero-GUI Teleport, Cross-App Pipe, Browser-Use, Code Canvas, Image Gen, Cross-Device)
            // =========================================================================
            if (iter == 1) {
                val nexusHit = universalNexusEngine.tryExecuteUniversalNexus(
                    prompt = currentGoal,
                    latestSnapshot = baseSnapshot,
                    apiKey = cfg.apiKey
                )
                if (nexusHit != null && nexusHit.handled) {
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = nexusHit.summaryKo,
                            subtitle = "Universal Omni-Nexus · ${nexusHit.badge}",
                            toolBadge = nexusHit.badge,
                            mediaBlock = nexusHit.mediaBlock
                        )
                    )
                    speakIfEnabled(nexusHit.summaryKo)
                    if (!nexusHit.followUpGuiGoal.isNullOrBlank()) {
                        currentGoal = nexusHit.followUpGuiGoal
                        delay(450L)
                        continue
                    } else {
                        FloatingHudService.setTouchPassthrough(false)
                        _currentSession.update { prev ->
                            val completed = prev.copy(
                                status = AgentRunStatus.COMPLETED,
                                finalSummary = nexusHit.summaryKo,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(completed)
                            completed
                        }
                        FloatingHudService.updateHudStatus("소통 및 제어 대기", nexusHit.summaryKo.take(28), false)
                        return
                    }
                }
            }

            // =========================================================================
            // LAYER 1 & 2: UNIFIED FAST-PATH + SET-OF-MARKS DELIBERATIVE VLM ROUTER
            // Guarantees stateful turn progression and Set-of-Marks `[index]` visual grounding on every VLM turn.
            // =========================================================================
            val instantReflex = multimodalClient.tryInstantConversationalOrScreenFastPath(
                goal = currentGoal,
                iteration = iter,
                snapshot = baseSnapshot,
                historySteps = prevSteps
            )

            // Empirical Fix: Whenever deliberative VLM / CLI Bridge routing runs, ALWAYS capture
            // a live screenshot (`includeScreenshot = true`) so Set-of-Marks `[index]` visual grounding
            // (`snapshot.visualGroundingBase64`) is delivered even on dense commercial apps (>4 UI nodes)!
            val snapshot: AtomicSnapshot = if (instantReflex != null) {
                baseSnapshot
            } else {
                HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = true) ?: baseSnapshot
            }

            // Notetaker cross-app auto-extraction + Proactive Watcher live evaluation
            resolvedScratchpad.observeAndAutoExtract(snapshot, resolvedVault)
            val watcherHits = resolvedWatcherEngine.evaluateSnapshot(snapshot)
            if (watcherHits.isNotEmpty()) {
                watcherHits.forEach { hit ->
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "[Proactive Watcher · ${hit.rule.title}] ${hit.summaryKo}",
                            subtitle = "실시간 화면 감시 트리거",
                            toolBadge = "WATCHER HIT"
                        )
                    )
                }
            }
            val notetakerBlock = resolvedScratchpad.toPromptBlock(maxChars = 420)
            val bubblesBlock = resolvedMemoryBubbles.toPromptContextBlock(resolvedVault)
            val appTip = AppSpecificTipBank.getTipForPackageOrGoal(snapshot.activePackage, currentGoal)
            val vaultHandlesSummary = resolvedVault.listHandles().joinToString(", ") { "${it.handle}(${it.category.labelKo})" }
            val enrichedGoalForRouter = buildString {
                append(resolvedVault.redactSensitiveText(currentGoal))
                if (bubblesBlock.isNotBlank()) {
                    append("\n\n")
                    append(bubblesBlock)
                }
                if (appTip.isNotBlank()) {
                    append("\n\n[App Tip] ")
                    append(appTip)
                }
                if (notetakerBlock.isNotBlank()) {
                    append("\n\n")
                    append(notetakerBlock)
                }
                if (vaultHandlesSummary.isNotBlank()) {
                    append("\n[Blind Vault Handles Available] ")
                    append(vaultHandlesSummary)
                }
            }

            val (rawDecision, backendBadge, actorTier) = if (instantReflex != null) {
                val badge = if (instantReflex.toolName == "complete_task") "S1-REFLEX" else "S1-FASTPATH"
                Triple(instantReflex, badge, "S1_REFLEX")
            } else {
                val s2Decision = aiInfrastructureRouter.routeAndDecide(
                    apiKey = cfg.apiKey,
                    modelName = cfg.modelName,
                    goal = enrichedGoalForRouter,
                    iteration = iter,
                    snapshot = snapshot,
                    historySteps = prevSteps,
                    previousPlan = _currentSession.value.subGoals,
                    chatHistory = _chatMessages.value,
                    customBaseUrl = cfg.customBaseUrl
                )
                Triple(s2Decision, "S2-${aiInfrastructureRouter.state.value.lastUsedBackendBadge}", "S2_DELIBERATIVE")
            }

            // Minitap MetaCognitive LoopBreaker: detect stalled repeats & 2-step oscillations and escape cleanly
            val preFingerprint = loopBreaker.computeScreenFingerprint(snapshot)
            val loopDiagnosis = loopBreaker.recordAndDiagnose(
                snapshot = snapshot,
                toolName = rawDecision.toolName,
                argsJson = rawDecision.toolArgs.toString(),
                treeDeltaRatio = lastStepDeltaRatio
            )

            val decision = if (loopDiagnosis.isLooping && rawDecision.toolName != "complete_task" && rawDecision.toolName != "ask_user") {
                if (loopDiagnosis.repeatCount >= 2) {
                    val stopSummary = "화면 상태가 반복되어 루프를 탈출하고 현재까지의 진행 상황을 정리했어요. 직접 확인 후 이어서 지시해 주세요."
                    ModelTurnDecision(
                        observation = loopDiagnosis.escapeDirectiveKo,
                        subGoals = rawDecision.subGoals,
                        toolName = "complete_task",
                        toolArgs = JSONObject().put("summary", stopSummary),
                        spokenReply = stopSummary
                    )
                } else if (loopDiagnosis.pattern == LoopPattern.TWO_STEP_OSCILLATION) {
                    ModelTurnDecision(
                        observation = loopDiagnosis.escapeDirectiveKo,
                        subGoals = rawDecision.subGoals,
                        toolName = "press_key",
                        toolArgs = JSONObject().put("key", "BACK"),
                        spokenReply = "두 화면 사이 반복이 감지되어 BACK으로 경로를 전환할게요."
                    )
                } else {
                    ModelTurnDecision(
                        observation = loopDiagnosis.escapeDirectiveKo,
                        subGoals = rawDecision.subGoals,
                        toolName = "swipe",
                        toolArgs = JSONObject().put("direction", "up").put("duration_ms", 320),
                        spokenReply = "화면 반응이 없어 스크롤하여 새 요소를 탐색할게요."
                    )
                }
            } else {
                rawDecision
            }

            _currentSession.update {
                it.copy(subGoals = decision.subGoals)
            }

            // 1. Task Completion -> speak and post natural conversational response
            if (decision.toolName == "complete_task") {
                FloatingHudService.setTouchPassthrough(false)
                executionModeController.finalizeEpisode(episodeGoalReached = true)
                val rawSummary = decision.spokenReply.ifBlank {
                    decision.toolArgs.optString("summary", "요청하신 작업을 완료했어요.")
                }
                val summary = HumanStepTranslator.sanitizeForHumanDisplay(rawSummary).ifBlank { rawSummary }
                val finalStep = AgentStepLog(
                    stepNumber = iter,
                    observation = decision.observation,
                    planLedger = decision.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                    toolName = "complete_task",
                    toolArgsJson = decision.toolArgs.toString(),
                    resultSummary = summary,
                    succeeded = true,
                    activePackage = snapshot.activePackage,
                    elementCount = snapshot.indexedElements.size,
                    screenshotBase64 = snapshot.screenshotBase64Jpeg
                )
                _currentSession.update { prev ->
                    val completed = prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        subGoals = prev.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                        steps = prev.steps + finalStep,
                        finalSummary = summary,
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(completed)
                    completed
                }
                persistRunWalkthrough(
                    goal = currentGoal,
                    summary = summary,
                    steps = _currentSession.value.steps,
                    receipts = _currentSession.value.receipts
                )
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = summary,
                        subtitle = backendBadge,
                        toolBadge = backendBadge
                    )
                )
                speakIfEnabled(summary)
                FloatingHudService.updateHudStatus("소통 및 제어 대기", summary.take(30), false)
                return
            }

            // 2. Conversational Clarification (`ask_user`) -> speak question & listen for user's answer
            if (decision.toolName == "ask_user") {
                FloatingHudService.setTouchPassthrough(false)
                val question = decision.toolArgs.optString("question").ifBlank {
                    decision.spokenReply.ifBlank { "어떤 항목을 선택할지 말씀해 주세요." }
                }
                _currentSession.update { prev ->
                    val finished = prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        finalSummary = question,
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(finished)
                    finished
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = question,
                        subtitle = backendBadge,
                        toolBadge = "대화 확인"
                    )
                )
                speakIfEnabled(question)
                FloatingHudService.updateHudStatus("대답을 듣고 있어요", question.take(30), false)
                return
            }

            // 3. D-Artemis Deliberative Aligner (TAC + ACA) for mutating GUI actions
            val effDecision = if (decision.toolName in setOf("click", "long_press", "input_text", "press_key")) {
                val alignment = ArtemisDeliberativeAligner.alignBeforeExecution(
                    toolName = decision.toolName,
                    args = decision.toolArgs,
                    thoughtOrReply = decision.spokenReply.ifBlank { decision.observation },
                    snapshot = snapshot,
                    iteration = iter
                )
                if (alignment.wasCorrected) {
                    decision.copy(
                        toolName = alignment.toolName,
                        toolArgs = alignment.alignedArgs,
                        observation = if (alignment.correctionReasonKo.isNotBlank()) {
                            "${decision.observation} (${alignment.correctionReasonKo})".trim()
                        } else decision.observation
                    )
                } else {
                    decision
                }
            } else {
                decision
            }

            val stepNarration = effDecision.spokenReply
                .replace(Regex("^['\"][^'\"]{1,30}['\"]\\s*(?:을|를|명령을)?\\s*실행하여\\s*"), "")
                .trim()

            // 3A. Strict Non-Bypass Takeover (CAPTCHA/2FA/Biometric) & Irreversible Financial/Price-Drift Gate
            val spec = ToolSpecRegistry.find(effDecision.toolName)
            val activeEpoch = currentControlEpoch.get()
            val consumedSnapshot = consumedExternalApprovalReceipts.toSet()
            val hsAssessment = FinancialAndTakeoverGuard.assessBeforeAction(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                snapshot = snapshot,
                lastApprovedAmount = lastApprovedAmountText,
                expectedControlEpoch = activeEpoch,
                consumedReceiptIds = consumedSnapshot
            )
            if (hsAssessment.externalApprovalValidated && !hsAssessment.externalApprovalReceiptId.isNullOrBlank()) {
                consumedExternalApprovalReceipts.add(hsAssessment.externalApprovalReceiptId)
                resolvedVault.recordAuditEvent(
                    action = "EXTERNAL_APPROVAL_BOUND",
                    handle = effDecision.toolArgs.optString("handle", effDecision.toolName).ifBlank { effDecision.toolName },
                    approvedReceiptId = hsAssessment.externalApprovalReceiptId,
                    controlEpoch = hsAssessment.controlEpoch ?: activeEpoch,
                    outcome = "APPROVED"
                )
            }

            if (hsAssessment.kind in setOf(
                    HighStakesKind.CAPTCHA_BOT_GATE,
                    HighStakesKind.TWO_FACTOR_OR_BIOMETRIC,
                    HighStakesKind.SECURE_SURFACE_BLOCKED
                )
            ) {
                val tkDeferred = CompletableDeferred<Boolean>()
                takeoverDeferred = tkDeferred
                val takeoverState = HumanTakeoverState(
                    kind = hsAssessment.kind,
                    titleKo = hsAssessment.targetLabel.ifBlank { "사용자 직접 인증 필요" },
                    reasonKo = hsAssessment.reasonKo,
                    activePackage = snapshot.activePackage
                )
                _currentSession.update {
                    it.copy(
                        status = AgentRunStatus.AWAITING_TAKEOVER,
                        pendingTakeover = takeoverState
                    )
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = hsAssessment.reasonKo,
                        subtitle = "Non-Bypass Human Takeover",
                        toolBadge = "HUMAN TAKEOVER"
                    )
                )
                speakIfEnabled(hsAssessment.reasonKo)
                FloatingHudService.updateHudStatus("사용자 직접 확인 대기", takeoverState.titleKo.take(28), true)
                syncSessionStoreStatus("TAKEOVER", takeoverState.titleKo.take(60), hasPendingApproval = true)
                val completedByHuman = tkDeferred.await()
                takeoverDeferred = null
                _currentSession.update {
                    it.copy(
                        status = if (completedByHuman) AgentRunStatus.OBSERVING else AgentRunStatus.STOPPED,
                        pendingTakeover = null
                    )
                }
                if (!completedByHuman) {
                    stopRun("사용자 직접 확인 단계에서 작업을 취소했어요.")
                    return
                }
                loopBreaker.reset()
                continue
            }

            // Mandatory Financial / Price-Drift or Sensitive Vault Handle approval (EVEN when unrestrictedAutonomousMode == true!)
            val sensitiveVaultSummary = if (effDecision.toolName == "vault_blind_inject") {
                val handleArg = effDecision.toolArgs.optString("handle", "").trim()
                resolvedVault.getHandleSummary(handleArg)?.takeIf { it.category.alwaysRequireApproval }
            } else {
                null
            }

            val sensitiveVaultNeedsApproval = sensitiveVaultSummary != null && !hsAssessment.externalApprovalValidated

            if (spec != null && (hsAssessment.kind == HighStakesKind.FINANCIAL_COMMIT ||
                    hsAssessment.kind == HighStakesKind.PRICE_DRIFT_ALERT ||
                    sensitiveVaultNeedsApproval)
            ) {
                val reason = sensitiveVaultSummary?.let { s ->
                    "[민감 볼트(${s.category.labelKo}) 주입 승인] ${s.handle} (${s.label}) 값을 현재 입력란에 안전 주입할까요?"
                } ?: hsAssessment.reasonKo
                val deferred = CompletableDeferred<Boolean>()
                approvalDeferred = deferred
                lastBoundApprovalReceiptId = null
                lastBoundControlEpoch = null
                _currentSession.update {
                    it.copy(
                        status = AgentRunStatus.AWAITING_APPROVAL,
                        pendingApproval = PendingApprovalRequest(
                            toolSpec = spec,
                            args = effDecision.toolArgs,
                            reasonKo = reason,
                            isHighStakesFinancial = hsAssessment.kind != HighStakesKind.NONE,
                            detectedPriceText = hsAssessment.detectedAmountText.ifBlank { null },
                            priceDriftNoticeKo = if (hsAssessment.kind == HighStakesKind.PRICE_DRIFT_ALERT) {
                                "${hsAssessment.previousApprovedAmount} → ${hsAssessment.detectedAmountText}"
                            } else null,
                            targetAppOrMerchant = hsAssessment.merchantOrApp.ifBlank { null },
                            controlEpoch = activeEpoch,
                            externalApprovalReceiptId = hsAssessment.externalApprovalReceiptId
                        )
                    )
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "$reason 승인하시면 바로 실행할게요.",
                        toolBadge = if (hsAssessment.kind == HighStakesKind.PRICE_DRIFT_ALERT) "PRICE DRIFT" else "고위험 승인"
                    )
                )
                speakIfEnabled(reason)
                FloatingHudService.updateHudStatus("고위험 승인 대기", reason.take(28), true)
                FloatingHudService.updateApprovalPrompt(true, reason)
                syncSessionStoreStatus("APPROVAL", reason.take(60), hasPendingApproval = true)
                val approved = deferred.await()
                approvalDeferred = null
                FloatingHudService.updateApprovalPrompt(false)
                syncSessionStoreStatus("ACTING", spec.name, hasPendingApproval = false)
                _currentSession.update { it.copy(pendingApproval = null) }
                if (!approved) {
                    stopRun("고위험 결제·민감 작업 승인을 거절하여 실행하지 않았어요.")
                    return
                }
                if (hsAssessment.detectedAmountText.isNotBlank()) {
                    lastApprovedAmountText = hsAssessment.detectedAmountText
                }
                lastBoundApprovalReceiptId?.let { boundId ->
                    effDecision.toolArgs.put("external_approval_receipt_id", boundId)
                }
                lastBoundControlEpoch?.let { boundEpoch ->
                    effDecision.toolArgs.put("control_epoch", boundEpoch)
                }
            } else if (spec != null) {
                when (val gate = ToolSafetyGate.evaluate(spec, effDecision.toolArgs, permissionHub)) {
                    is SafetyGateDecision.Allow -> Unit
                    is SafetyGateDecision.Deny -> {
                        val msg = "보안 설정으로 인해 '${spec.name}' 작업이 제한되어 있어요. 원터치 자동 구성을 실행해 주세요."
                        stopRun(msg)
                        return
                    }
                    is SafetyGateDecision.AskUser -> {
                        val deferred = CompletableDeferred<Boolean>()
                        approvalDeferred = deferred
                        _currentSession.update {
                            it.copy(
                                status = AgentRunStatus.AWAITING_APPROVAL,
                                pendingApproval = PendingApprovalRequest(
                                    toolSpec = spec,
                                    args = effDecision.toolArgs,
                                    reasonKo = gate.reasonKo
                                )
                            )
                        }
                        val askMsg = "${gate.reasonKo} 진행할까요? '응'이라고 말씀하시면 바로 실행할게요."
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = askMsg,
                                toolBadge = "승인 확인"
                            )
                        )
                        speakIfEnabled(askMsg)
                        FloatingHudService.updateHudStatus("승인 대기 중", "'응'이라고 말씀하세요", true)
                        FloatingHudService.updateApprovalPrompt(true, gate.reasonKo)
                        syncSessionStoreStatus("APPROVAL", gate.reasonKo.take(60), hasPendingApproval = true)
                        val approved = deferred.await()
                        approvalDeferred = null
                        FloatingHudService.updateApprovalPrompt(false)
                        syncSessionStoreStatus("ACTING", spec.name, hasPendingApproval = false)
                        _currentSession.update { it.copy(pendingApproval = null) }
                        if (!approved) {
                            stopRun("네, 실행하지 않고 취소했어요.")
                            return
                        }
                    }
                }
            }

            // 4. Execute Tool Action on the Phone FIRST (Zero pre-action narration delay!)
            _currentSession.update { it.copy(status = AgentRunStatus.ACTING) }
            val plainStepKo = HumanStepTranslator.toPlainKoreanStep(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                snapshot = snapshot
            )
            FloatingHudService.updateHudStatus(
                plainStepKo.take(24),
                stepNarration.ifBlank { plainStepKo }.take(30),
                true
            )

            val outcome = toolHarness.execute(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                latestSnapshot = snapshot
            )
            HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)?.let { postQuickSnap ->
                val postFp = loopBreaker.computeScreenFingerprint(postQuickSnap)
                lastStepDeltaRatio = if (postFp != preFingerprint) 1.0f else 0.0f
            }

            // 5. Post concise acknowledgement AFTER the physical gesture has already fired
            if (iter == 1 && stepNarration.isNotBlank() && stepNarration != lastSpokenStepText) {
                lastSpokenStepText = stepNarration
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = stepNarration,
                        subtitle = backendBadge,
                        toolBadge = "${effDecision.toolName} · $backendBadge"
                    )
                )
                speakIfEnabled(stepNarration)
            }

            val manageAppAction = effDecision.toolArgs.optString("action", "").lowercase()
            if (effDecision.toolName == "manage_app" && manageAppAction in listOf("search", "list", "list_games", "list_all")) {
                val appSummary = outcome.outputText
                _currentSession.update { prev ->
                    prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        finalSummary = appSummary,
                        finishedAtMs = System.currentTimeMillis()
                    )
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = appSummary,
                        subtitle = backendBadge,
                        toolBadge = "앱/게임 스캔"
                    )
                )
                speakIfEnabled(appSummary)
                FloatingHudService.updateHudStatus("소통 및 제어 대기", appSummary.take(28), false)
                return
            }

            if (effDecision.toolName == "list_notifications") {
                val notifSummary = if (outcome.outputText.contains("수신된 알림이 없습니다") || outcome.outputText.isBlank()) {
                    "지금 새로 도착한 알림이 없어요."
                } else {
                    val lines = outcome.outputText.lines().filter { it.isNotBlank() }.take(3)
                    "최근 도착한 알림을 확인했어요: ${lines.joinToString(" / ")}. 답장을 보낼 메시지가 있나요?"
                }
                _currentSession.update { prev ->
                    prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        finalSummary = notifSummary,
                        finishedAtMs = System.currentTimeMillis()
                    )
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = notifSummary,
                        toolBadge = "알림 브리핑"
                    )
                )
                speakIfEnabled(notifSummary)
                FloatingHudService.updateHudStatus("소통 및 제어 대기", notifSummary.take(28), false)
                return
            }

            if (effDecision.toolName == "manage_app" && manageAppAction == "launch") {
                // Wait for the launched app window to actually come to the foreground
                delay(650L)
                for (poll in 1..3) {
                    val curPkg = HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)?.activePackage
                    if (!curPkg.isNullOrBlank() && curPkg != snapshot.activePackage && !curPkg.startsWith("com.hanson.mobileuse")) {
                        break
                    }
                    delay(320L)
                }
            } else {
                delay(220L)
            }
            val postSnapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = false
            ) ?: snapshot

            val verification = trajectoryVerifier.verifyAndSelfHeal(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                initialOutcomeSucceeded = outcome.succeeded,
                preSnapshot = snapshot,
                postSnapshot = postSnapshot
            )

            val humanStepSummary = HumanStepTranslator.sanitizeForHumanDisplay(
                HumanStepTranslator.toPlainKoreanStep(
                    toolName = effDecision.toolName,
                    args = effDecision.toolArgs,
                    snapshot = snapshot,
                    rawSummary = outcome.outputText
                )
            ).ifBlank {
                HumanStepTranslator.sanitizeForHumanDisplay(outcome.outputText)
                    .ifBlank { plainStepKo }
            }

            val stepLog = AgentStepLog(
                stepNumber = iter,
                observation = HumanStepTranslator.sanitizeForHumanDisplay(effDecision.observation)
                    .ifBlank { effDecision.observation },
                planLedger = effDecision.subGoals,
                toolName = effDecision.toolName,
                toolArgsJson = effDecision.toolArgs.toString(),
                resultSummary = humanStepSummary,
                succeeded = verification.verifiedSuccess,
                activePackage = verification.finalSnapshot.activePackage,
                elementCount = verification.finalSnapshot.indexedElements.size,
                screenshotBase64 = snapshot.screenshotBase64Jpeg
            )

            recordReceipt(outcome.receipt)

            _currentSession.update { prev ->
                prev.copy(
                    steps = memoryManager.trimStepSnapshotsInPlace(prev.steps + stepLog, keepRecentScreenshots = 2),
                    receipts = if (outcome.receipt != null) prev.receipts + outcome.receipt else prev.receipts
                )
            }

            val updatedSteps = _currentSession.value.steps
            val stepLatencyMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
            scope.launch(Dispatchers.IO) {
                worldModelGraph.recordTransition(
                    preSnapshot = snapshot,
                    postSnapshot = verification.finalSnapshot,
                    toolName = effDecision.toolName,
                    toolArgsJson = effDecision.toolArgs.toString(),
                    verifiedSuccess = verification.verifiedSuccess,
                    uiDeltaRatio = verification.treeDeltaRatio,
                    selfHealRecipe = verification.recoveryActionKo
                )
                executionModeController.recordStepTransition(
                    stepNumber = iter,
                    preSnapshot = snapshot,
                    postSnapshot = verification.finalSnapshot,
                    actorTier = actorTier,
                    toolName = effDecision.toolName,
                    toolArgsJson = effDecision.toolArgs.toString(),
                    verifiedSuccess = verification.verifiedSuccess,
                    uiDeltaRatio = verification.treeDeltaRatio,
                    recoveryAttempted = verification.recoveryAttempted,
                    latencyMs = stepLatencyMs,
                    goal = currentGoal
                )
                neuralSkillCompiler.compileAndDistillVerifiedStep(
                    goal = currentGoal,
                    preSnapshot = snapshot,
                    toolName = effDecision.toolName,
                    toolArgs = effDecision.toolArgs,
                    verifiedSuccess = verification.verifiedSuccess
                )
                LiquidUiSynthesizer.updateLiveTrajectoryHud(
                    goal = currentGoal,
                    steps = updatedSteps,
                    worldGraph = worldModelGraph
                )
            }

            delay(60L)
        }

        val maxMsg = "여기까지 실행을 마쳤어요. 지금 화면에서 이어서 할 작업을 말씀해 주세요."
        _currentSession.update { prev ->
            val finished = prev.copy(
                status = AgentRunStatus.COMPLETED,
                finalSummary = maxMsg,
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(finished)
            finished
        }
        persistRunWalkthrough(
            goal = currentGoal,
            summary = maxMsg,
            steps = _currentSession.value.steps,
            receipts = _currentSession.value.receipts
        )
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = maxMsg,
                toolBadge = "DONE"
            )
        )
        speakIfEnabled(maxMsg)
        FloatingHudService.updateHudStatus("소통 및 제어 대기", "다음 명령 대기", false)
    }

    /**
     * Intercepts Generative UI / Live Code Canvas execution errors and formulates
     * a self-correction feedback prompt turn for the agent loop.
     */
    fun onCanvasExecutionError(errorPayload: String) {
        if (errorPayload.isBlank()) return
        val telemetry = CanvasErrorTelemetry.fromJson(errorPayload)
        val feedbackPrompt = telemetry.toFeedbackPrompt()

        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = "캔버스 코드 실행 중 오류가 감지되어 자체 수정(Self-Correction)을 진행합니다:\n${telemetry.message}${if ((telemetry.lineNumber ?: 0) > 0) " (Line ${telemetry.lineNumber})" else ""}",
                subtitle = "Live Code Canvas · Self-Correction Feedback",
                toolBadge = "SELF-CORRECT"
            )
        )

        val currentStatus = _currentSession.value.status
        if (currentStatus == AgentRunStatus.ACTING || currentStatus == AgentRunStatus.THINKING || currentStatus == AgentRunStatus.OBSERVING) {
            liveSteeringGoal = feedbackPrompt
        } else {
            pendingGuiGoals.offer(feedbackPrompt)
            dispatchToForegroundGuiLane(listOf(feedbackPrompt))
        }
    }

    /**
     * Delegates task complexity estimation to the infrastructure router.
     */
    fun estimateTaskComplexity(prompt: String, snapshot: AtomicSnapshot? = null): TaskComplexity =
        aiInfrastructureRouter.estimateTaskComplexity(prompt, snapshot)

    fun setWorkMode(mode: AgentWorkMode) {
        _workMode.value = mode
        sessionStore?.updateActiveMode(mode)
    }

    fun approveAndExecutePlan() {
        val fromArtifact = workspaceEngine?.state?.value?.planArtifactMarkdown
            ?.lines()
            ?.firstOrNull { it.startsWith("- **Goal**:") }
            ?.removePrefix("- **Goal**:")
            ?.trim()
            .orEmpty()
        val targetGoal = pendingPlanGoal?.takeIf { it.isNotBlank() } ?: fromArtifact
        pendingPlanGoal = null
        setWorkMode(AgentWorkMode.BUILD)
        if (targetGoal.isBlank()) {
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "Build 모드로 전환했어요. 실행할 목표가 비어 있어 새 지시를 입력해 주세요.",
                    toolBadge = "BUILD"
                )
            )
            return
        }
        startRun(targetGoal, appendUserBubble = true)
    }

    fun buildContextualPrompt(
        baseGoal: String,
        parsed: ParsedComposerInput,
        snapshot: AtomicSnapshot? = null
    ): String {
        if (parsed.mentions.isEmpty()) return baseGoal
        val liveSnap = snapshot ?: runCatching {
            HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)
        }.getOrNull()

        val blocks = mutableListOf<String>()
        for (mention in parsed.mentions) {
            val lower = mention.lowercase(Locale.ROOT)
            when {
                lower == "@screen" -> {
                    val pkg = liveSnap?.activePackage?.ifBlank { context.packageName } ?: context.packageName
                    val title = liveSnap?.activeWindowTitle ?: "Unknown"
                    val elemLines = liveSnap?.indexedElements.orEmpty().take(8).joinToString("; ") { el ->
                        val label = el.text.ifBlank { el.contentDesc }.ifBlank { el.resourceId }
                        "[${el.index}] $label"
                    }
                    blocks.add("[Context @screen] pkg=$pkg, window=$title, elements=$elemLines")
                }
                lower == "@app" -> {
                    val pkg = liveSnap?.activePackage?.ifBlank { context.packageName } ?: context.packageName
                    val title = liveSnap?.activeWindowTitle ?: "Unknown"
                    blocks.add("[Context @app] foregroundPackage=$pkg, windowTitle=$title")
                }
                lower == "@tree" -> {
                    val tree = liveSnap?.indexedElements.orEmpty().take(20).joinToString("\n") { el ->
                        val label = el.text.ifBlank { el.contentDesc }.ifBlank { el.resourceId }
                        "- [${el.index}] \"$label\" clickable=${el.clickable}"
                    }
                    blocks.add("[Context @tree]\n${tree.ifBlank { "(접근성 노드 스냅샷 없음)" }}")
                }
                lower == "@diff" -> {
                    val diffs = workspaceEngine?.state?.value?.diffs.orEmpty().takeLast(3)
                    val diffBody = if (diffs.isEmpty()) {
                        "(변경된 파일 없음)"
                    } else {
                        diffs.joinToString("\n\n") { d ->
                            "### ${d.relativePath} (+${d.addedLines}/-${d.removedLines})\n${d.unifiedDiff.take(1200)}"
                        }
                    }
                    blocks.add("[Context @diff]\n$diffBody")
                }
                lower == "@agents" -> {
                    val content = workspaceEngine?.readFile("AGENTS.md", 1, 100)?.getOrNull()
                    blocks.add("[Context @agents]\n${content?.take(2000) ?: "(AGENTS.md 없음 — /init 으로 생성 가능)"}")
                }
                lower == "@vault" -> {
                    val handles = resolvedVault.listHandles()
                    val body = if (handles.isEmpty()) {
                        "(등록된 Blind Vault 핸들 없음)"
                    } else {
                        handles.joinToString("\n") { h ->
                            "- ${h.handle} [${h.category.labelKo}] · ${h.label} (${h.maskedPreview})"
                        }
                    }
                    blocks.add("[Context @vault (Zero-Plaintext Metadata)]\n$body")
                }
                lower == "@notes" -> {
                    val padBlock = resolvedScratchpad.toPromptBlock(maxChars = 1200)
                    blocks.add("[Context @notes]\n${padBlock.ifBlank { "(Notetaker 작업 메모가 비어 있음)" }}")
                }
                lower == "@watchers" -> {
                    val rules = resolvedWatcherEngine.listRules()
                    val body = if (rules.isEmpty()) {
                        "(등록된 Proactive Watcher 규칙 없음)"
                    } else {
                        rules.joinToString("\n") { r ->
                            "- ${r.title} (${if (r.enabled) "ON" else "OFF"}) · 키워드=${r.matchKeywords.joinToString(",")}"
                        }
                    }
                    blocks.add("[Context @watchers]\n$body")
                }
                lower.startsWith("@file:") -> {
                    val relPath = mention.substringAfter(':', "").trim()
                    val fileBody = if (relPath.isNotBlank()) {
                        workspaceEngine?.readFile(relPath, 1, 160, includeLineNumbers = true)?.fold(
                            onSuccess = { it.take(3000) },
                            onFailure = { "(파일 읽기 오류: ${it.message})" }
                        ) ?: "(작업공간 미연결)"
                    } else {
                        "(파일 경로 누락)"
                    }
                    blocks.add("[Context @file:$relPath]\n$fileBody")
                }
            }
        }
        val safeBase = resolvedVault.redactSensitiveText(baseGoal)
        if (blocks.isEmpty()) return safeBase
        val assembled = buildString {
            append(safeBase.ifBlank { "첨부된 컨텍스트를 분석하고 처리해 주세요." })
            append("\n\n<attached_context>\n")
            append(blocks.joinToString("\n\n"))
            append("\n</attached_context>")
        }
        return resolvedVault.redactSensitiveText(assembled)
    }

    private fun tryHandleStudioSlashCommand(parsed: ParsedComposerInput): Boolean {
        val cmd = parsed.slashCommand ?: return false
        when (cmd.lowercase(Locale.ROOT)) {
            "/auto", "/plan", "/build" -> {
                val targetMode = parsed.resolvedMode ?: _workMode.value
                setWorkMode(targetMode)
                if (parsed.cleanPrompt.isBlank() && parsed.mentions.isEmpty()) {
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "작업 모드를 '${targetMode.label}' (${targetMode.shortBadge}) 모드로 전환했어요.\n${targetMode.description}",
                            toolBadge = targetMode.shortBadge
                        )
                    )
                    syncSessionStoreStatus("MODE:${targetMode.shortBadge}", targetMode.label, false)
                    return true
                }
                return false
            }
            "/init" -> {
                val ws = workspaceEngine
                if (ws == null) {
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "작업공간 엔진이 초기화되지 않아 AGENTS.md를 생성할 수 없어요.",
                            toolBadge = "INIT",
                            succeeded = false
                        )
                    )
                    return true
                }
                val diff = ws.initAgentsMd(parsed.cleanPrompt)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "`${diff.relativePath}` 가이드를 생성/갱신했어요. (+${diff.addedLines}/-${diff.removedLines} lines)\nCode Workbench의 Files 탭에서 편집할 수 있습니다.",
                        toolBadge = "AGENTS.md"
                    )
                )
                syncSessionStoreStatus("READY", "AGENTS.md initialized", false)
                return true
            }
            "/files" -> {
                val listing = workspaceEngine?.formatFilesAsText()?.ifBlank { "(작업공간이 비어 있음)" }
                    ?: "(작업공간 엔진 미연결)"
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "작업공간 파일 목록입니다:\n```\n$listing\n```",
                        toolBadge = "FILES"
                    )
                )
                return true
            }
            "/diff" -> {
                val diffs = workspaceEngine?.state?.value?.diffs.orEmpty()
                val summary = if (diffs.isEmpty()) {
                    "아직 기록된 작업공간 Diff 변경 내역이 없습니다."
                } else {
                    diffs.takeLast(5).joinToString("\n\n") { rec ->
                        "**${rec.relativePath}** (+${rec.addedLines}/-${rec.removedLines})\n${rec.unifiedDiff.take(900)}"
                    }
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = summary,
                        toolBadge = "DIFF"
                    )
                )
                return true
            }
            "/undo" -> {
                val ws = workspaceEngine
                val checkpoints = ws?.state?.value?.checkpoints.orEmpty()
                if (ws == null || checkpoints.isEmpty()) {
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "복원할 작업공간 체크포인트가 아직 없습니다.",
                            toolBadge = "UNDO"
                        )
                    )
                    return true
                }
                val arg = parsed.cleanPrompt.trim()
                if (arg.startsWith("restore", ignoreCase = true) || arg.contains("복구") || arg.startsWith("cp", ignoreCase = true)) {
                    val requestedId = arg.removePrefix("restore").trim()
                    val target = checkpoints.firstOrNull { it.id.equals(requestedId, ignoreCase = true) }
                        ?: checkpoints.maxByOrNull { it.createdAtMs }
                        ?: checkpoints.first()
                    val ok = ws.restoreCheckpoint(target.id)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = if (ok) {
                                "체크포인트 '${target.label}' (${target.id}) 상태로 작업공간을 복구했어요."
                            } else {
                                "체크포인트(${target.id}) 복구에 실패했어요."
                            },
                            toolBadge = "UNDO",
                            succeeded = ok
                        )
                    )
                } else {
                    val listText = checkpoints.sortedByDescending { it.createdAtMs }.take(6).joinToString("\n") {
                        "- `${it.id}` · ${it.label} (${it.fileCount} files)"
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "저장된 체크포인트 목록입니다 (`\"/undo restore\"` 로 최신 상태 복구 가능):\n$listText",
                            toolBadge = "UNDO"
                        )
                    )
                }
                return true
            }
            "/sessions" -> {
                val storeState = sessionStore?.state?.value
                val lines = storeState?.sessions.orEmpty().joinToString("\n") { thread ->
                    val activeTag = if (thread.id == storeState?.activeSessionId) "*" else "-"
                    "$activeTag [${thread.mode.shortBadge}] ${thread.title} (${thread.statusBadge})"
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "세션 목록:\n${lines.ifBlank { "(세션 정보 없음)" }}",
                        toolBadge = "SESSIONS"
                    )
                )
                return true
            }
            "/vault" -> {
                val handles = resolvedVault.listHandles()
                val text = if (handles.isEmpty()) {
                    "Blind Credential Vault가 비어 있습니다. Studio 상단 Shield 버튼 또는 Tool을 통해 암호화 핸들을 추가해 주세요 (LLM 평문 비노출)."
                } else {
                    "Blind Vault 핸들 목록 (${handles.size}건 · AES-256-GCM 보호):\n" +
                        handles.joinToString("\n") { "- `${it.handle}` [${it.category.labelKo}] · ${it.label} (${it.maskedPreview})" }
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = text,
                        toolBadge = "VAULT"
                    )
                )
                return true
            }
            "/watch" -> {
                val arg = parsed.cleanPrompt.trim()
                if (arg.isNotBlank() && arg.contains(",")) {
                    val parts = arg.split('|')
                    val title = parts.getOrNull(0)?.trim().orEmpty()
                    val kws = (parts.getOrNull(1) ?: arg).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    if (kws.isNotEmpty()) {
                        val rule = resolvedWatcherEngine.addRule(
                            title = title.ifBlank { kws.joinToString(" · ") },
                            targetAppOrUrl = parts.getOrNull(2)?.trim().orEmpty(),
                            matchKeywords = kws
                        )
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = "Proactive Watcher 규칙을 등록했어요: **${rule.title}** (`${rule.id}` · 키워드=${rule.matchKeywords.joinToString(",")})",
                                toolBadge = "WATCH"
                            )
                        )
                        return true
                    }
                }
                val rules = resolvedWatcherEngine.listRules()
                val text = if (rules.isEmpty()) {
                    "등록된 Proactive Watcher가 없습니다. Studio Hub의 Watchers 탭이나 `/watch 제목 | 키워드1, 키워드2` 형식으로 추가할 수 있습니다."
                } else {
                    "Proactive Watchers (${rules.size}건):\n" + rules.joinToString("\n") {
                        "- [${if (it.enabled) "ON" else "OFF"}] **${it.title}** · 키워드=${it.matchKeywords.joinToString(",")} (${it.hitCount}회 감지)"
                    }
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = text,
                        toolBadge = "WATCH"
                    )
                )
                return true
            }
            "/a2a" -> {
                val peerName = parsed.cleanPrompt.trim().ifBlank { "상대 에이전트" }
                val outcome = PickleA2AScheduleNegotiator.negotiate(peerName = peerName)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = outcome.summaryKo,
                        toolBadge = "A2A"
                    )
                )
                return true
            }
            "/notes" -> {
                val block = resolvedScratchpad.toPromptBlock(maxChars = 1200)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = if (block.isBlank()) "Notetaker 크로스앱 작업 메모장이 비어 있습니다." else block,
                        toolBadge = "NOTES"
                    )
                )
                return true
            }
            "/help" -> {
                val slashHelp = ComposerCommandParser.defaultSlashCommands.joinToString("\n") {
                    "- `${it.command}`: ${it.description}"
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "사용 가능한 슬래시 명령어:\n$slashHelp\n\n컨텍스트 멘션: `@screen`, `@app`, `@tree`, `@diff`, `@agents`, `@vault`, `@notes`, `@watchers`, `@file:<경로>`",
                        toolBadge = "HELP"
                    )
                )
                return true
            }
            else -> return false
        }
    }

    private fun enterPlanModeAndEmitBlueprint(goal: String, parsed: ParsedComposerInput) {
        val enrichedGoal = if (parsed.mentions.isNotEmpty()) {
            buildContextualPrompt(goal, parsed)
        } else {
            goal
        }
        val parts = splitCompoundParallelGoals(goal).ifEmpty { listOf(goal) }
        val plannedSubGoals = mutableListOf<SubGoalItem>()
        plannedSubGoals.add(SubGoalItem(title = "작업공간 및 현재 화면 컨텍스트 점검", status = SubGoalStatus.PENDING))
        parts.forEach { part ->
            plannedSubGoals.add(SubGoalItem(title = part.take(60), status = SubGoalStatus.PENDING))
        }
        plannedSubGoals.add(SubGoalItem(title = "결과 검증 및 WALKTHROUGH.md 생성", status = SubGoalStatus.PENDING))

        val planMd = generatePlanBlueprintMarkdown(
            goal = goal,
            enrichedContext = enrichedGoal,
            plannedSteps = plannedSubGoals
        )
        pendingPlanGoal = goal
        workspaceEngine?.updatePlanArtifact(planMd, persistToFile = true)
        sessionStore?.state?.value?.activeSessionId?.let { activeId ->
            sessionStore.updateArtifacts(activeId, planMarkdown = planMd)
        }
        syncSessionStoreStatus("PLAN_READY", goal.take(80), hasPendingApproval = false)

        _currentSession.update { prev ->
            prev.copy(
                goal = goal,
                status = AgentRunStatus.COMPLETED,
                subGoals = plannedSubGoals,
                finalSummary = "PLAN.md 설계안을 작성했어요. 승인 후 빌드 실행으로 진행할 수 있습니다.",
                finishedAtMs = System.currentTimeMillis()
            )
        }
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = "PLAN.md 설계안을 생성했어요. 검토 후 상단 배너의 '승인 후 빌드 실행' 버튼을 누르면 즉시 실행합니다.\n\n${planMd.take(700)}",
                subtitle = "Plan Mode Blueprint · PLAN.md",
                toolBadge = "PLAN.md"
            )
        )
        FloatingHudService.updateHudStatus("PLAN 설계 완료", goal.take(28), false)
    }

    private fun generatePlanBlueprintMarkdown(
        goal: String,
        enrichedContext: String,
        plannedSteps: List<SubGoalItem>
    ): String = buildString {
        val files = workspaceEngine?.state?.value?.files?.filter { !it.isDirectory }.orEmpty()
        appendLine("# PLAN.md — Execution Blueprint")
        appendLine()
        appendLine("- **Goal**: $goal")
        appendLine("- **Mode**: PLAN (Read-first blueprint before Build)")
        appendLine("- **Workspace Files**: ${files.size} file(s)")
        appendLine()
        appendLine("## Proposed Execution Steps")
        plannedSteps.forEachIndexed { index, step ->
            appendLine("${index + 1}. [ ] ${step.title}")
        }
        if (enrichedContext != goal) {
            appendLine()
            appendLine("## Attached Context Summary")
            appendLine("```")
            appendLine(enrichedContext.take(1000))
            appendLine("```")
        }
        appendLine()
        appendLine("## Safety & Rollback")
        appendLine("- File writes capture automatic pre-write checkpoints in Code Workbench.")
        appendLine("- Tap **Approve & Build** to transition to `BUILD` mode and execute this plan.")
    }.trimEnd()

    private fun persistRunWalkthrough(
        goal: String,
        summary: String,
        steps: List<AgentStepLog>,
        receipts: List<ActionReceipt>,
        diffs: List<WorkspaceDiffRecord> = workspaceEngine?.state?.value?.diffs.orEmpty()
    ) {
        val walkthrough = buildWalkthroughMarkdown(goal, summary, steps, receipts, diffs)
        workspaceEngine?.updateWalkthroughArtifact(walkthrough, persistToFile = true)
        sessionStore?.state?.value?.activeSessionId?.let { activeId ->
            sessionStore.updateArtifacts(activeId, walkthroughMarkdown = walkthrough)
        }
        syncSessionStoreStatus("DONE", summary.take(80), hasPendingApproval = false)
    }

    private fun buildWalkthroughMarkdown(
        goal: String,
        summary: String,
        steps: List<AgentStepLog>,
        receipts: List<ActionReceipt>,
        diffs: List<WorkspaceDiffRecord>
    ): String = buildString {
        appendLine("# WALKTHROUGH.md — Execution Summary")
        appendLine()
        appendLine("- **Goal**: ${goal.ifBlank { "(Interactive session)" }}")
        appendLine("- **Result**: $summary")
        appendLine("- **Steps Executed**: ${steps.size}")
        appendLine()
        if (steps.isNotEmpty()) {
            appendLine("## Step Trajectory")
            steps.takeLast(12).forEach { step ->
                val mark = if (step.succeeded) "OK" else "FAIL"
                appendLine("- Step ${step.stepNumber} [`$mark`] `${step.toolName}`: ${step.resultSummary.take(140)}")
            }
            appendLine()
        }
        if (diffs.isNotEmpty()) {
            appendLine("## Workspace File Changes")
            diffs.takeLast(6).forEach { diff ->
                appendLine("- `${diff.relativePath}` (+${diff.addedLines} / -${diff.removedLines})")
            }
            appendLine()
        }
        if (receipts.isNotEmpty()) {
            appendLine("## Action Receipts")
            receipts.takeLast(8).forEach { receipt ->
                appendLine("- **${receipt.toolName}**: ${receipt.summaryKo}")
            }
        }
    }.trimEnd()

    fun syncSessionStoreStatus(
        statusBadge: String,
        previewLine: String = "",
        hasPendingApproval: Boolean = false
    ) {
        val store = sessionStore ?: return
        val activeId = store.state.value.activeSessionId
        val pkg = runCatching {
            HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)?.activePackage.orEmpty()
        }.getOrDefault("")
        store.updateSessionStatus(
            sessionId = activeId,
            statusBadge = statusBadge,
            previewLine = previewLine,
            foregroundPackage = pkg,
            hasPendingApproval = hasPendingApproval
        )
    }

    private fun archiveSession(session: AgentRunSession) {
        _runHistory.update { prev ->
            (listOf(session) + prev.filterNot { it.runId == session.runId }).take(25)
        }
    }
}
