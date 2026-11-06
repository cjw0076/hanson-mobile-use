package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.paradigm.HabitOrganonRuntime
import com.hanson.mobileuse.paradigm.LiquidUiSynthesizer
import com.hanson.mobileuse.paradigm.ParadigmExecutionModeController
import com.hanson.mobileuse.paradigm.SemanticTeleportRouter
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
    val ttsVoiceReply: Boolean = true
)

/**
 * Conversational Full-Control Mobile-Use Agent Engine (`MobileUseAgentEngine`).
 *
 * Bridges:
 * - Multi-Tier Mobile AI Infrastructure Router (`MobileAiInfrastructureRouter`:
 *   Galaxy S25 Built-In AICore Gemini Nano NPU + MediaPipe Tasks GenAI + Local Edge LLM + Cloud Gemini 3.8/Argon)
 * - Full-Duplex Conversational Voice Loop (`LiveVoiceSessionManager`) with live mid-execution steering
 * - Ground-Truth Action Trajectory Verifier & Self-Healer (`ActionTrajectoryVerifierAndHealer`)
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
    val aiInfrastructureRouter = MobileAiInfrastructureRouter(context, multimodalClient)
    private val toolHarness = AgentToolExecutionHarness(context)
    private val trajectoryVerifier = ActionTrajectoryVerifierAndHealer(context)
    val worldModelGraph = WorldModelGraph(context)
    val habitOrganonRuntime = HabitOrganonRuntime(context, worldModelGraph)
    val executionModeController = ParadigmExecutionModeController(worldModelGraph, habitOrganonRuntime)
    val semanticTeleportRouter = SemanticTeleportRouter(context)

    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var liveSteeringGoal: String? = null

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
        HansonForegroundService.onEmergencyStopRequested = { stopRun("네, 작업을 바로 멈췄어요.") }
        FloatingHudService.onStopClicked = { stopRun("네, 작업을 멈췄어요. 다음엔 어떻게 할까요?") }
        FloatingHudService.onSendLiveChatMessage = { userMsg -> sendChatMessage(userMsg) }
        LiveVoiceSessionManager.ensureInitialized(context) { spoken ->
            sendChatMessage(spoken)
        }
    }

    private fun speakIfEnabled(text: String) {
        val shouldSpeak = LiveVoiceSessionManager.state.value.isActive || _config.value.ttsVoiceReply
        if (!shouldSpeak) return
        LiveVoiceSessionManager.speakConversationalFiller(text)
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
            ttsVoiceReply = prefs.getBoolean(KEY_TTS_VOICE, true)
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
        if (lower.contains("모델") && (lower.contains("바꿔") || lower.contains("변경") || lower.contains("전환"))) {
            val matchedModel: Pair<String, String>? = when {
                lower.contains("나노") || lower.contains("nano") || lower.contains("온디바이스") || lower.contains("npu") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.S25_AICORE_NANO)
                    "gemma-4-26b-a4b-it" to "Galaxy S25 내장 Gemini Nano NPU"
                }
                lower.contains("argon") || lower.contains("아곤") -> {
                    aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                    "gemini-4-argon" to "Gemini 4 Argon"
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
                val reply = "네, 추론 엔진을 $label 모델로 전환했어요."
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = reply,
                        toolBadge = "MODEL"
                    )
                )
                speakIfEnabled(reply)
                return true
            }
        }

        return false
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

    /**
     * Starts a new conversational control run OR steers an active run in real time if the user
     * speaks mid-execution ("잠깐 멈춰", "아니 두 번째 거 눌러줘", "좀 더 내려봐").
     */
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

        val lower = trimmedGoal.lowercase(Locale.ROOT)
        // Immediate stop/cancel intent by voice or text
        if (lower in listOf("멈춰", "스톱", "그만", "정지", "취소", "잠깐만 멈춰", "멈춰줘", "그만해")) {
            stopRun("네, 바로 멈췄어요. 다음엔 어떻게 할까요?")
            return
        }

        if (tryHandleExplicitHansonSelfConfig(trimmedGoal)) {
            return
        }

        // If the agent is currently waiting for approval and the user says "응/그래/승인/해줘"
        if (_currentSession.value.status == AgentRunStatus.AWAITING_APPROVAL && approvalDeferred != null) {
            val approve = !(lower.contains("아니") || lower.contains("취소") || lower.contains("거절") || lower.contains("마"))
            resolvePendingApproval(approve, alwaysAllowCapability = approve)
            return
        }

        // Live Mid-Execution Steering: if a multi-step trajectory is currently running, steer it in-place!
        if (activeJob?.isActive == true &&
            (_currentSession.value.status == AgentRunStatus.OBSERVING ||
                _currentSession.value.status == AgentRunStatus.THINKING ||
                _currentSession.value.status == AgentRunStatus.ACTING)
        ) {
            liveSteeringGoal = trimmedGoal
            _currentSession.update { it.copy(goal = trimmedGoal) }
            FloatingHudService.updateHudStatus("실시간 방향 수정", trimmedGoal.take(30), true)
            return
        }

        activeJob?.cancel()
        approvalDeferred?.complete(false)
        liveSteeringGoal = null

        val cfg = _config.value
        val initialSession = AgentRunSession(
            goal = trimmedGoal,
            status = AgentRunStatus.OBSERVING,
            maxIterations = cfg.maxIterations,
            subGoals = listOf(SubGoalItem("화면 확인 및 실행", SubGoalStatus.IN_PROGRESS))
        )
        _currentSession.value = initialSession

        if (cfg.autoShowFloatingHud) {
            FloatingHudService.startIfPermitted(context)
        }
        FloatingHudService.updateHudStatus("한슨 생각 중", trimmedGoal.take(30), true)

        activeJob = scope.launch {
            runLoop(trimmedGoal, cfg)
        }
    }

    fun stopRun(reason: String = "네, 현재 작업을 멈췄어요. 이어서 무엇을 도와드릴까요?") {
        activeJob?.cancel()
        activeJob = null
        liveSteeringGoal = null
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
                succeeded = true
            )
        )
        speakIfEnabled(reason)
        FloatingHudService.updateHudStatus("소통 및 제어 대기", reason.take(28), false)
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

    private suspend fun runLoop(initialGoal: String, cfg: AgentEngineConfig) {
        val (fallbackW, fallbackH) = HierarchyDumper.getScreenDimensions(context)
        var currentGoal = initialGoal
        var lastSpokenStepText = ""

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

            val snapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = true
            ) ?: AtomicSnapshot(
                screenWidth = fallbackW,
                screenHeight = fallbackH,
                activePackage = context.packageName,
                activeWindowTitle = "Hanson Board"
            )

            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("화면 분석 중", currentGoal.take(28), true)

            val decision = aiInfrastructureRouter.routeAndDecide(
                apiKey = cfg.apiKey,
                modelName = cfg.modelName,
                goal = currentGoal,
                iteration = iter,
                snapshot = snapshot,
                historySteps = _currentSession.value.steps,
                previousPlan = _currentSession.value.subGoals,
                chatHistory = _chatMessages.value
            )

            val backendBadge = aiInfrastructureRouter.state.value.lastUsedBackendBadge

            _currentSession.update {
                it.copy(subGoals = decision.subGoals)
            }

            // 1. Task Completion -> speak and post natural conversational response
            if (decision.toolName == "complete_task") {
                habitOrganonRuntime.finalizeEpisodeAndAssignDelayedCredit(
                    goal = currentGoal,
                    activePackage = snapshot.activePackage,
                    episodeGoalReached = true
                )
                val summary = decision.spokenReply.ifBlank {
                    decision.toolArgs.optString("summary", "요청하신 작업을 완료했어요.")
                }
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

            // 3. Speak natural conversational progress while executing the action
            val stepNarration = decision.spokenReply.trim()
            if (stepNarration.isNotBlank() && stepNarration != lastSpokenStepText) {
                lastSpokenStepText = stepNarration
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = stepNarration,
                        subtitle = backendBadge,
                        toolBadge = "${decision.toolName} · $backendBadge"
                    )
                )
                speakIfEnabled(stepNarration)
            }

            // 4. Safety Gate evaluation
            val spec = ToolSpecRegistry.find(decision.toolName)
            if (spec != null) {
                when (val gate = ToolSafetyGate.evaluate(spec, decision.toolArgs, permissionHub)) {
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
                                    args = decision.toolArgs,
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
                        val approved = deferred.await()
                        approvalDeferred = null
                        _currentSession.update { it.copy(pendingApproval = null) }
                        if (!approved) {
                            stopRun("네, 실행하지 않고 취소했어요.")
                            return
                        }
                    }
                }
            }

            // 5. Execute Tool Action on the Phone
            _currentSession.update { it.copy(status = AgentRunStatus.ACTING) }
            FloatingHudService.updateHudStatus(
                "제어 중 · ${decision.toolName}",
                stepNarration.ifBlank { decision.observation }.take(30),
                true
            )

            val outcome = toolHarness.execute(
                toolName = decision.toolName,
                args = decision.toolArgs,
                latestSnapshot = snapshot
            )

            // If `list_notifications` was called, immediately summarize the notifications in natural Korean!
            if (decision.toolName == "list_notifications") {
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

            delay(450L)
            val postSnapshot: AtomicSnapshot = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = false
            ) ?: snapshot

            val verification = trajectoryVerifier.verifyAndSelfHeal(
                toolName = decision.toolName,
                args = decision.toolArgs,
                initialOutcomeSucceeded = outcome.succeeded,
                preSnapshot = snapshot,
                postSnapshot = postSnapshot
            )

            worldModelGraph.recordTransition(
                preSnapshot = snapshot,
                postSnapshot = verification.finalSnapshot,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                verifiedSuccess = verification.verifiedSuccess,
                uiDeltaRatio = verification.treeDeltaRatio,
                selfHealRecipe = verification.recoveryActionKo
            )

            val stepLog = AgentStepLog(
                stepNumber = iter,
                observation = decision.observation,
                planLedger = decision.subGoals,
                toolName = decision.toolName,
                toolArgsJson = decision.toolArgs.toString(),
                resultSummary = outcome.outputText,
                succeeded = verification.verifiedSuccess,
                activePackage = verification.finalSnapshot.activePackage,
                elementCount = verification.finalSnapshot.indexedElements.size,
                screenshotBase64 = snapshot.screenshotBase64Jpeg
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

            LiquidUiSynthesizer.updateLiveTrajectoryHud(
                goal = currentGoal,
                steps = _currentSession.value.steps,
                worldGraph = worldModelGraph
            )

            delay(650L)
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

    private fun archiveSession(session: AgentRunSession) {
        _runHistory.update { prev ->
            (listOf(session) + prev.filterNot { it.runId == session.runId }).take(25)
        }
    }
}
