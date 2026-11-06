package com.hanson.mobileuse.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.paradigm.CompositeExecutionMode
import com.hanson.mobileuse.paradigm.HabitOrganonRuntime
import com.hanson.mobileuse.paradigm.LiquidUiSynthesizer
import com.hanson.mobileuse.paradigm.ModeTurnResult
import com.hanson.mobileuse.paradigm.ParadigmExecutionModeController
import com.hanson.mobileuse.paradigm.SemanticTeleportCandidate
import com.hanson.mobileuse.paradigm.SemanticTeleportRouter
import com.hanson.mobileuse.paradigm.TeleportResult
import com.hanson.mobileuse.paradigm.WorldModelGraph
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

data class AgentEngineConfig(
    val apiKey: String = "",
    val modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
    val maxIterations: Int = 15,
    val autoShowFloatingHud: Boolean = true,
    val ttsVoiceReply: Boolean = false
)

/**
 * Core Multimodal Mobile-Use Agent Engine with Continuous Runtime Live Chat.
 * Maintains an always-on conversational thread (`chatMessages`) shared across the main app,
 * the Floating Live Chat Overlay (`SYSTEM_ALERT_WINDOW`), and the `:18888/chat` RPC bridge.
 */
class MobileUseAgentEngine(
    private val context: Context,
    val permissionHub: PermissionHubManager
) {
    companion object {
        private const val CONFIG_PREFS = "hanson_agent_engine_config"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model_name"
        private const val KEY_MAX_ITER = "max_iterations"
        private const val KEY_AUTO_HUD = "auto_floating_hud"
        private const val KEY_TTS_VOICE = "tts_voice_reply"
        private const val KEY_CHAT_HISTORY = "live_chat_history_json"
    }

    private val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val multimodalClient = GeminiMultimodalClient()
    private val toolHarness = AgentToolExecutionHarness(context)
    private val trajectoryVerifier = ActionTrajectoryVerifierAndHealer(context)
    val worldModelGraph = WorldModelGraph(context)
    val habitOrganonRuntime = HabitOrganonRuntime(context, worldModelGraph)
    val executionModeController = ParadigmExecutionModeController(worldModelGraph, habitOrganonRuntime)
    val semanticTeleportRouter = SemanticTeleportRouter(context)

    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

    init {
        HansonForegroundService.onEmergencyStopRequested = { stopRun("알림바 긴급 정지 버튼으로 중단되었습니다.") }
        FloatingHudService.onStopClicked = { stopRun("플로팅 HUD 정지 버튼으로 중단되었습니다.") }
        FloatingHudService.onSendLiveChatMessage = { userMsg -> sendChatMessage(userMsg) }
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.KOREAN
                    ttsReady = true
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun speakIfEnabled(text: String) {
        if (!_config.value.ttsVoiceReply || !ttsReady) return
        try {
            tts?.speak(text.take(220), TextToSpeech.QUEUE_FLUSH, null, "hanson_live_${System.currentTimeMillis()}")
        } catch (_: Exception) {
        }
    }

    private fun loadConfig(): AgentEngineConfig {
        val rawSavedModel = prefs.getString(KEY_MODEL, GeminiMultimodalClient.DEFAULT_MODEL)
            ?: GeminiMultimodalClient.DEFAULT_MODEL
        val normalizedModel = GeminiMultimodalClient.normalize2026ModelName(rawSavedModel)
        if (normalizedModel != rawSavedModel) {
            prefs.edit().putString(KEY_MODEL, normalizedModel).apply()
        }
        return AgentEngineConfig(
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            modelName = normalizedModel,
            maxIterations = prefs.getInt(KEY_MAX_ITER, 15).coerceIn(3, 40),
            autoShowFloatingHud = prefs.getBoolean(KEY_AUTO_HUD, true),
            ttsVoiceReply = prefs.getBoolean(KEY_TTS_VOICE, false)
        )
    }

    fun updateConfig(
        apiKey: String? = null,
        modelName: String? = null,
        maxIterations: Int? = null,
        autoShowFloatingHud: Boolean? = null,
        ttsVoiceReply: Boolean? = null
    ) {
        val current = _config.value
        val resolvedModel = GeminiMultimodalClient.normalize2026ModelName(modelName ?: current.modelName)
        val next = current.copy(
            apiKey = apiKey ?: current.apiKey,
            modelName = resolvedModel,
            maxIterations = (maxIterations ?: current.maxIterations).coerceIn(3, 40),
            autoShowFloatingHud = autoShowFloatingHud ?: current.autoShowFloatingHud,
            ttsVoiceReply = ttsVoiceReply ?: current.ttsVoiceReply
        )
        prefs.edit()
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_MODEL, next.modelName)
            .putInt(KEY_MAX_ITER, next.maxIterations)
            .putBoolean(KEY_AUTO_HUD, next.autoShowFloatingHud)
            .putBoolean(KEY_TTS_VOICE, next.ttsVoiceReply)
            .apply()
        _config.value = next
    }

    private fun loadPersistedChatMessages(): List<LiveChatMessage> {
        val raw = prefs.getString(KEY_CHAT_HISTORY, null)
        if (raw.isNullOrBlank()) {
            return listOf(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "할 일을 한 줄로 입력하거나 음성으로 말씀하세요. 모델 변경·권한 구성·음성 설정도 에이전트가 직접 처리합니다.",
                    subtitle = "One-Touch Board · Active",
                    toolBadge = "READY"
                )
            )
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LiveChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    LiveChatMessage(
                        id = obj.optString("id", "msg_$i"),
                        sender = runCatching { ChatSender.valueOf(obj.optString("sender", "AGENT")) }
                            .getOrDefault(ChatSender.AGENT),
                        text = obj.optString("text", ""),
                        subtitle = obj.optString("subtitle", ""),
                        toolBadge = obj.optString("toolBadge").takeIf { it.isNotBlank() },
                        succeeded = obj.optBoolean("succeeded", true),
                        replySessionId = obj.optString("replySessionId").takeIf { it.isNotBlank() },
                        timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                    )
                )
            }
            if (list.isEmpty()) {
                listOf(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "원터치 보드가 준비되었습니다.",
                        toolBadge = "READY"
                    )
                )
            } else list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun appendChatMessage(msg: LiveChatMessage) {
        _chatMessages.update { prev ->
            val updated = (prev + msg).takeLast(80)
            persistChatMessages(updated)
            updated
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
            text = "보드를 비웠습니다. 수행할 작업이나 설정을 지시해 주세요.",
            subtitle = "One-Touch Board",
            toolBadge = "CLEAR"
        )
        _chatMessages.value = listOf(welcome)
        persistChatMessages(_chatMessages.value)
        FloatingHudService.notifyChatUpdated(_chatMessages.value)
    }

    /**
     * One-Touch Environment Auto-Setup:
     * Automatically enables unrestricted capability governance, triggers OS-level `self-heal`
     * (Accessibility, Notification Listener, Overlay), and refreshes permission state.
     */
    fun runOneTouchAutoSetup() {
        scope.launch {
            permissionHub.setUnrestrictedMode(true)
            val healOutcome = toolHarness.execute(
                toolName = "os_cli_exec",
                args = JSONObject().put("subcommand", "self-heal"),
                latestSnapshot = null
            )
            permissionHub.refreshStatus()
            if (_config.value.autoShowFloatingHud) {
                FloatingHudService.startIfPermitted(context)
            }
            val msgText = if (healOutcome.succeeded) {
                "원터치 환경 자동 구성을 완료했습니다. (접근성·알림 리스너·오버레이·무제한 거버넌스 활성화)"
            } else {
                "권한 거버넌스를 무제한으로 전환하고 런타임 구성을 갱신했습니다."
            }
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = msgText,
                    subtitle = "1-Touch Self-Heal Completed",
                    toolBadge = "AUTO-SETUP"
                )
            )
            speakIfEnabled(msgText)
        }
    }

    /**
     * Handles user requests to change settings (model, voice/TTS, HUD, theme, permissions)
     * directly through the agent without opening any settings screen.
     */
    private fun tryHandleAgentConfigCommand(prompt: String): Boolean {
        val lower = prompt.lowercase(Locale.ROOT)
        val isConfigIntent = listOf(
            "모델", "바꿔", "변경", "전환", "켜줘", "꺼줘", "켜", "꺼",
            "설정", "다크 모드", "라이트 모드", "화이트 모드", "음성", "목소리",
            "플로팅", "오버레이", "권한", "자동 구성", "자가 치유", "원터치"
        ).any { lower.contains(it) }
        if (!isConfigIntent) return false

        // 1. Auto-setup / permissions
        if (lower.contains("권한") || lower.contains("자동 구성") || lower.contains("자가 치유") || lower.contains("환경 구성")) {
            runOneTouchAutoSetup()
            return true
        }

        // 2. Theme switching (Ahaboard Paper Light vs Carbon Dark)
        if (lower.contains("다크 모드") || lower.contains("어둡게") || lower.contains("카본 모드")) {
            ObsidianColors.isDark = true
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "Ahaboard Carbon (무채색 다크) 모드로 전환했습니다.",
                    subtitle = "Agent Self-Configuration",
                    toolBadge = "CARBON"
                )
            )
            return true
        }
        if (lower.contains("라이트 모드") || lower.contains("화이트 모드") || lower.contains("밝게") || lower.contains("페이퍼 모드")) {
            ObsidianColors.isDark = false
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "Ahaboard Paper (무채색 라이트) 모드로 전환했습니다.",
                    subtitle = "Agent Self-Configuration",
                    toolBadge = "PAPER"
                )
            )
            return true
        }

        // 3. Voice / TTS toggle
        if (lower.contains("음성") || lower.contains("목소리") || lower.contains("보이스")) {
            val enable = !(lower.contains("꺼") || lower.contains("끄") || lower.contains("중지") || lower.contains("off"))
            updateConfig(ttsVoiceReply = enable)
            val reply = if (enable) "음성 동행(TTS) 응답을 켰습니다." else "음성 동행(TTS) 응답을 껐습니다."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = reply,
                    subtitle = "Agent Self-Configuration",
                    toolBadge = if (enable) "VOICE ON" else "VOICE OFF"
                )
            )
            if (enable) speakIfEnabled(reply)
            return true
        }

        // 4. Floating HUD overlay toggle
        if (lower.contains("플로팅") || lower.contains("오버레이") || lower.contains("hud")) {
            val enable = !(lower.contains("꺼") || lower.contains("끄") || lower.contains("숨겨") || lower.contains("off"))
            updateConfig(autoShowFloatingHud = enable)
            if (enable) {
                FloatingHudService.startIfPermitted(context)
            } else {
                FloatingHudService.stop(context)
            }
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = if (enable) "플로팅 오버레이 보드를 활성화했습니다." else "플로팅 오버레이 보드를 숨겼습니다.",
                    subtitle = "Agent Self-Configuration",
                    toolBadge = if (enable) "HUD ON" else "HUD OFF"
                )
            )
            return true
        }

        // 5. Model switching via natural language
        val matchedModel: Pair<String, String>? = when {
            lower.contains("argon") || lower.contains("아곤") || lower.contains("자비스") ->
                "gemini-jarvis-argon" to "Gemini Jarvis Argon (최상위 에이전트 플래그십)"
            lower.contains("3.8") && (lower.contains("pro") || lower.contains("프로")) ->
                "gemini-3.8-pro-preview" to "Gemini 3.8 Pro Preview"
            lower.contains("3.8") || (lower.contains("플래시") && !lower.contains("3.5")) ->
                "gemini-3.8-flash-preview" to "Gemini 3.8 Flash Preview"
            lower.contains("3.6") ->
                "gemini-3.6-pro-preview" to "Gemini 3.6 Pro Preview"
            lower.contains("3.5") || lower.contains("라이트") || lower.contains("lite") ->
                "gemini-3.5-flash-lite-preview" to "Gemini 3.5 Flash-Lite Preview"
            lower.contains("로보틱스") || lower.contains("robotics") ->
                "gemini-robotics-er-1.6-preview" to "Gemini Robotics ER 1.6 Preview"
            lower.contains("컴퓨터") || lower.contains("computer") ->
                "gemini-2.5-computer-use-preview-10-2025" to "Gemini 2.5 Computer Use"
            lower.contains("cli") || lower.contains("구독") || lower.contains("노트북") ->
                Pair("laptop-cli-subscription", "노트북 CLI 구독 브리지 (API 키 불필요)")
            else -> null
        }

        if (matchedModel != null) {
            val (modelId, label) = matchedModel
            updateConfig(modelName = modelId)
            val reply = "엔진 모델을 [$label] ($modelId)로 즉시 전환했습니다."
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = reply,
                    subtitle = "Agent Self-Configuration · No Settings Needed",
                    toolBadge = "MODEL"
                )
            )
            speakIfEnabled(reply)
            return true
        }

        return false
    }

    /**
     * Injects a live message or response directly from the Laptop CLI (:18888/chat) into the phone's live chat stream.
     */
    fun injectExternalReply(text: String, badge: String = "LAPTOP CLI") {
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = text,
                subtitle = "노트북 CLI 실시간 브리지 (:18888/chat)",
                toolBadge = badge
            )
        )
        speakIfEnabled(text)
        FloatingHudService.updateHudStatus("노트북 CLI 메시지 도착", text.take(32), false)
    }

    /**
     * Streams an incoming intercepted notification (KakaoTalk, SMS, Telegram, Slack, etc.)
     * directly into the continuous runtime Live Chat so the user can see and reply to it live.
     */
    fun postIncomingNotificationToLiveChat(notif: InterceptedNotification) {
        val msg = LiveChatMessage(
            sender = ChatSender.NOTIFICATION,
            text = "[${notif.appLabel}] ${notif.sender}: ${notif.messageText}",
            subtitle = if (notif.canQuickReply) "방: ${notif.roomOrTitle} · 0.2초 즉시 답장 가능" else "방: ${notif.roomOrTitle}",
            toolBadge = if (notif.canQuickReply) "RemoteInput" else "Notification",
            replySessionId = if (notif.canQuickReply) notif.sessionId else null
        )
        appendChatMessage(msg)
    }

    /**
     * Sends a user message into the continuous runtime Live Chat and triggers the agent loop.
     */
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

    fun startRun(goalPrompt: String, appendUserBubble: Boolean = true) {
        val trimmedGoal = goalPrompt.trim()
        if (trimmedGoal.isEmpty()) return

        if (appendUserBubble) {
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.USER,
                    text = trimmedGoal
                )
            )
        }

        // Agent Self-Configuration: handle model switch, voice toggle, HUD toggle, theme, or 1-touch setup directly
        if (tryHandleAgentConfigCommand(trimmedGoal)) {
            return
        }

        activeJob?.cancel()
        approvalDeferred?.complete(false)

        val cfg = _config.value
        val initialSession = AgentRunSession(
            goal = trimmedGoal,
            status = AgentRunStatus.OBSERVING,
            maxIterations = cfg.maxIterations,
            subGoals = listOf(SubGoalItem("화면 분석 및 실행 계획 수립", SubGoalStatus.IN_PROGRESS))
        )
        _currentSession.value = initialSession

        if (cfg.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(context)
        }
        FloatingHudService.updateHudStatus("Hanson AI 추론 중", trimmedGoal.take(32), true)

        activeJob = scope.launch {
            runLoop(trimmedGoal, cfg)
        }
    }

    fun stopRun(reason: String = "사용자 요청으로 현재 작업을 중지했습니다. 라이브 채팅은 계속 유지됩니다.") {
        activeJob?.cancel()
        activeJob = null
        approvalDeferred?.complete(false)
        approvalDeferred = null

        _currentSession.update { prev ->
            val stopped = prev.copy(
                status = AgentRunStatus.STOPPED,
                pendingApproval = null,
                finalSummary = reason,
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(stopped)
            stopped
        }
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = reason,
                toolBadge = "STOPPED",
                succeeded = false
            )
        )
        FloatingHudService.updateHudStatus("라이브 채팅 대기 중", reason.take(30), false)
    }

    fun resolvePendingApproval(approved: Boolean, alwaysAllowCapability: Boolean = false) {
        val pending = _currentSession.value.pendingApproval
        if (approved && alwaysAllowCapability && pending?.toolSpec?.capability != null) {
            permissionHub.setCapabilityLevel(pending.toolSpec.capability, PermissionLevel.ALWAYS)
        }
        approvalDeferred?.complete(approved)
    }

    fun clearReceipts() {
        _allReceipts.value = emptyList()
    }

    private suspend fun runLoop(goal: String, cfg: AgentEngineConfig) {
        val (fallbackW, fallbackH) = HierarchyDumper.getScreenDimensions(context)

        // Layer 1: Check for Fast-Lane Semantic Teleport before executing kinetic steps
        val teleportCandidate = semanticTeleportRouter.findFastLaneCandidate(goal)
        var hadInitialTeleportStep = false
        if (teleportCandidate != null) {
            when (val teleportResult = semanticTeleportRouter.executeTeleport(teleportCandidate)) {
                is TeleportResult.Success -> {
                    val summary = teleportResult.messageKo
                    val isCompound = semanticTeleportRouter.isCompoundGoal(goal)

                    // For an app launch Intent with compound instructions (e.g. search, click, navigate):
                    // do NOT terminate with COMPLETED. Instead, launch the app and hand off to Layer 2 in-app kinetic control!
                    if (teleportCandidate is SemanticTeleportCandidate.IntentCandidate && isCompound) {
                        val stepLog = AgentStepLog(
                            stepNumber = 1,
                            observation = "[Layer 1 Semantic Teleport] 앱 빠른 진입 성공: $summary. 인앱 키네틱 제어로 전환합니다.",
                            planLedger = listOf(
                                SubGoalItem("Semantic Teleport 앱 실행", SubGoalStatus.COMPLETED),
                                SubGoalItem("인앱 연속 물리 제어 (Layer 2)", SubGoalStatus.IN_PROGRESS)
                            ),
                            toolName = "semantic_teleport",
                            toolArgsJson = "{\"targetPackage\": \"${teleportCandidate.packageName}\"}",
                            resultSummary = summary,
                            succeeded = true,
                            activePackage = teleportCandidate.packageName,
                            elementCount = 0
                        )
                        _currentSession.update { prev ->
                            prev.copy(
                                status = AgentRunStatus.ACTING,
                                subGoals = listOf(
                                    SubGoalItem("Semantic Teleport 앱 실행", SubGoalStatus.COMPLETED),
                                    SubGoalItem("인앱 연속 물리 제어 (Layer 2)", SubGoalStatus.IN_PROGRESS)
                                ),
                                steps = prev.steps + stepLog
                            )
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = "$summary (인앱 키네틱 조작 계속)",
                                subtitle = "[Layer 1 Teleport] 앱 실행 후 Layer 2 키네틱 루프로 인계",
                                toolBadge = "TELEPORT -> KINETIC"
                            )
                        )
                        FloatingHudService.updateHudStatus("앱 실행 완료 · 화면 분석 중", goal.take(30), true)
                        hadInitialTeleportStep = true
                        // Fall through to kinetic loop
                    } else {
                        // Pure terminal teleport (e.g. RemoteInput reply, pure OS setting, or solo app launch)
                        val stepLog = AgentStepLog(
                            stepNumber = 1,
                            observation = "[Layer 1 Semantic Teleport] Fast-Lane 단축 경로 실행 완료",
                            planLedger = listOf(SubGoalItem("Semantic Teleport 실행", SubGoalStatus.COMPLETED)),
                            toolName = "semantic_teleport",
                            toolArgsJson = "{}",
                            resultSummary = summary,
                            succeeded = true,
                            activePackage = context.packageName,
                            elementCount = 0
                        )
                        _currentSession.update { prev ->
                            val completed = prev.copy(
                                status = AgentRunStatus.COMPLETED,
                                subGoals = listOf(SubGoalItem("Semantic Teleport 실행", SubGoalStatus.COMPLETED)),
                                steps = prev.steps + stepLog,
                                finalSummary = summary,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(completed)
                            completed
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = summary,
                                subtitle = "[Layer 1 Teleport] 화면 조작 없이 서브-100ms 완료",
                                toolBadge = "TELEPORT OK"
                            )
                        )
                        speakIfEnabled(summary)
                        FloatingHudService.updateHudStatus("라이브 채팅 대기 중", summary.take(32), false)
                        return
                    }
                }
                is TeleportResult.FallbackToKinetic -> {
                    // Fall back to Layer 2 / Layer 3 kinetic navigation
                }
            }
        }

        val startIter = if (hadInitialTeleportStep) 2 else 1
        for (iter in startIter..cfg.maxIterations) {
            _currentSession.update {
                it.copy(
                    currentIteration = iter,
                    status = AgentRunStatus.OBSERVING
                )
            }
            FloatingHudService.updateHudStatus("Step $iter/${cfg.maxIterations} · 화면 분석", goal.take(30), true)

            val snapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = true
            ) ?: AtomicSnapshot(
                screenWidth = fallbackW,
                screenHeight = fallbackH,
                activePackage = context.packageName,
                activeWindowTitle = "AccessibilityService Off (Fast-Lane Mode)"
            )

            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("Step $iter · AI 추론 중", "화면 요소 ${snapshot.indexedElements.size}개 분석", true)

            // Evaluate turn using ParadigmExecutionModeController (BOCPD Change-Point Gate + 3 Composite Modes)
            val recentFailed = _currentSession.value.steps.lastOrNull()?.succeeded == false
            val modeTurnResult = executionModeController.evaluateTurn(
                snapshot = snapshot,
                goal = goal,
                mode = CompositeExecutionMode.RECURSIVE_EVOLUTION_3PHASE,
                recentStepFailed = recentFailed
            )

            val (decision, currentActorTier) = when (modeTurnResult) {
                is ModeTurnResult.DirectAction -> {
                    val s1 = modeTurnResult.decision
                    val tier = if (modeTurnResult.rationaleKo.contains("Rollout") || modeTurnResult.mode == CompositeExecutionMode.PRE_SIMULATION_ROLLOUT) {
                        "S1_ROLLOUT"
                    } else {
                        "S1_HABIT_PROTOTYPE"
                    }
                    FloatingHudService.updateHudStatus("Step $iter · 0-LLM S1 반사 실행", modeTurnResult.rationaleKo.take(30), true)
                    s1 to tier
                }
                is ModeTurnResult.SpeculativeDispatched -> {
                    val s1 = modeTurnResult.speculativeDecision
                    FloatingHudService.updateHudStatus("Step $iter · 병렬 투기적 S1 디스패치", "투기적 실행 중", true)
                    s1 to "S1_SPECULATIVE"
                }
                is ModeTurnResult.EscalateToSystem2 -> {
                    FloatingHudService.updateHudStatus("Step $iter · S2 심판 추론", modeTurnResult.reasonKo.take(30), true)
                    val s2 = multimodalClient.decideNextAction(
                        apiKey = cfg.apiKey,
                        modelName = cfg.modelName,
                        goal = goal,
                        iteration = iter,
                        snapshot = snapshot,
                        historySteps = _currentSession.value.steps,
                        previousPlan = _currentSession.value.subGoals,
                        chatHistory = _chatMessages.value
                    )
                    s2 to "S2_DELIBERATIVE"
                }
            }

            _currentSession.update {
                it.copy(subGoals = decision.subGoals)
            }

            if (decision.toolName == "complete_task") {
                val consolidatedTrace = habitOrganonRuntime.finalizeEpisodeAndAssignDelayedCredit(
                    goal = goal,
                    activePackage = snapshot.activePackage,
                    episodeGoalReached = true
                )
                val rawSummary = decision.toolArgs.optString("summary", "작업을 성공적으로 완료했습니다.")
                val creditFooter = if (consolidatedTrace.isNotEmpty()) {
                    "\n[Habit-Organon 지연 신용 할당 완료: ${consolidatedTrace.size}개 순수 궤적 간선 승격 · S1=${habitOrganonRuntime.totalS1Calls}/S2=${habitOrganonRuntime.totalS2Deferrals}]"
                } else ""
                val summary = rawSummary + creditFooter
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
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = summary,
                        subtitle = decision.observation,
                        toolBadge = "COMPLETE"
                    )
                )
                speakIfEnabled(summary)
                FloatingHudService.updateHudStatus("라이브 채팅 대기 중", summary.take(32), false)
                return
            }

            val spec = ToolSpecRegistry.find(decision.toolName)
            if (spec != null) {
                when (val gate = ToolSafetyGate.evaluate(spec, decision.toolArgs, permissionHub)) {
                    is SafetyGateDecision.Allow -> Unit
                    is SafetyGateDecision.Deny -> {
                        val deniedStep = AgentStepLog(
                            stepNumber = iter,
                            observation = decision.observation,
                            planLedger = decision.subGoals,
                            toolName = decision.toolName,
                            toolArgsJson = decision.toolArgs.toString(),
                            resultSummary = "차단됨: ${gate.reasonKo}",
                            succeeded = false,
                            activePackage = snapshot.activePackage,
                            elementCount = snapshot.indexedElements.size
                        )
                        _currentSession.update { prev ->
                            val stopped = prev.copy(
                                status = AgentRunStatus.STOPPED,
                                steps = prev.steps + deniedStep,
                                finalSummary = gate.reasonKo,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(stopped)
                            stopped
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = gate.reasonKo,
                                toolBadge = "DENIED · ${decision.toolName}",
                                succeeded = false
                            )
                        )
                        FloatingHudService.updateHudStatus("거버넌스 차단", gate.reasonKo.take(28), false)
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
                                    args = decision.toolArgs,
                                    reasonKo = gate.reasonKo
                                )
                            )
                        }
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = "승인 요청: ${gate.reasonKo}",
                                toolBadge = "APPROVAL · ${spec.name}"
                            )
                        )
                        FloatingHudService.updateHudStatus("승인 대기 중", "${spec.name} 실행 확인 필요", true)
                        val approved = deferred.await()
                        approvalDeferred = null
                        _currentSession.update { it.copy(pendingApproval = null) }
                        if (!approved) {
                            stopRun("사용자가 '${spec.name}' 실행을 거부하여 중단했습니다.")
                            return
                        }
                    }
                }
            }

            _currentSession.update { it.copy(status = AgentRunStatus.ACTING) }
            FloatingHudService.updateHudStatus("Step $iter · ${decision.toolName}", decision.observation.take(30), true)

            val outcome = toolHarness.execute(
                toolName = decision.toolName,
                args = decision.toolArgs,
                latestSnapshot = snapshot
            )

            // Wait for UI animation/state transition settle, then capture Post-Snapshot S_{t+1}
            delay(480L)
            val postSnapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = false
            ) ?: snapshot

            // Closed-Loop Ground-Truth Verification & 3-Stage Self-Healing (인지 -> 복구 -> 재실행)
            val verification = trajectoryVerifier.verifyAndSelfHeal(
                toolName = decision.toolName,
                args = decision.toolArgs,
                initialOutcomeSucceeded = outcome.succeeded,
                preSnapshot = snapshot,
                postSnapshot = postSnapshot
            )

            // Record transition S_t -> S_{t+1} into the Paradigm World-Model State Graph
            worldModelGraph.recordTransition(
                preSnapshot = snapshot,
                postSnapshot = verification.finalSnapshot,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                verifiedSuccess = verification.verifiedSuccess,
                uiDeltaRatio = verification.treeDeltaRatio,
                selfHealRecipe = verification.recoveryActionKo
            )

            habitOrganonRuntime.recordStepProvenance(
                stepNumber = iter,
                preSnapshot = snapshot,
                postSnapshot = verification.finalSnapshot,
                actorTier = if (verification.recoveryAttempted) "SELF_HEALER" else currentActorTier,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                verifiedSuccess = verification.verifiedSuccess,
                uiDeltaRatio = verification.treeDeltaRatio,
                recoveryAttempted = verification.recoveryAttempted,
                latencyMs = 480L
            )

            val verifiedResultText = buildString {
                append(outcome.outputText)
                append("\n")
                append(verification.toFormattedBadgeAndLog())
            }

            val stepLog = AgentStepLog(
                stepNumber = iter,
                observation = decision.observation,
                planLedger = decision.subGoals,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                resultSummary = verifiedResultText,
                succeeded = verification.verifiedSuccess,
                activePackage = verification.finalSnapshot.activePackage,
                elementCount = verification.finalSnapshot.indexedElements.size,
                screenshotBase64 = snapshot.screenshotBase64Jpeg
            )

            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.ACTION,
                    text = verifiedResultText,
                    subtitle = "궤적 관찰: ${decision.observation}",
                    toolBadge = if (verification.recoveryAttempted) {
                        "STEP $iter · ${decision.toolName} (SELF-HEALED)"
                    } else {
                        "STEP $iter · ${decision.toolName}"
                    },
                    succeeded = verification.verifiedSuccess
                )
            )

            if (outcome.receipt != null) {
                _allReceipts.update { (listOf(outcome.receipt) + it).take(50) }
            }

            _currentSession.update { prev ->
                prev.copy(
                    steps = prev.steps + stepLog,
                    receipts = if (outcome.receipt != null) prev.receipts + outcome.receipt else prev.receipts
                )
            }

            // Live-update the Liquid UI Trajectory & World-Model HUD
            LiquidUiSynthesizer.updateLiveTrajectoryHud(
                goal = goal,
                steps = _currentSession.value.steps,
                worldGraph = worldModelGraph
            )

            if (decision.toolName == "ask_user") {
                val question = decision.toolArgs.optString("question", "추후 진행 방향을 알려주세요.")
                _currentSession.update { prev ->
                    val finished = prev.copy(
                        status = AgentRunStatus.COMPLETED,
                        finalSummary = question,
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(finished)
                    completedSessionWithQuestion(finished)
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = question,
                        toolBadge = "QUESTION"
                    )
                )
                speakIfEnabled(question)
                FloatingHudService.updateHudStatus("질문 응답 대기", question.take(30), false)
                return
            }

            delay(900L)
        }

        val maxMsg = "최대 반복 횟수(${cfg.maxIterations}회)까지 실행을 마쳤습니다. 추가로 진행할 작업을 말씀해 주세요."
        _currentSession.update { prev ->
            val finished = prev.copy(
                status = AgentRunStatus.COMPLETED,
                finalSummary = maxMsg,
                finishedAtMs = System.currentTimeMillis()
            )
            archiveSession(finished)
            finished
        }
        appendChatMessage(
            LiveChatMessage(
                sender = ChatSender.AGENT,
                text = maxMsg,
                toolBadge = "DONE"
            )
        )
        FloatingHudService.updateHudStatus("라이브 채팅 대기 중", "다음 명령 대기", false)
    }

    private fun completedSessionWithQuestion(session: AgentRunSession): AgentRunSession = session

    private fun archiveSession(session: AgentRunSession) {
        _runHistory.update { prev ->
            (listOf(session) + prev.filterNot { it.runId == session.runId }).take(25)
        }
    }
}
