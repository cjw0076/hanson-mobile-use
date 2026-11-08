package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.paradigm.HabitOrganonRuntime
import com.hanson.mobileuse.paradigm.LiquidUiSynthesizer
import com.hanson.mobileuse.paradigm.ModeTurnResult
import com.hanson.mobileuse.paradigm.NeuralSkillCompilerAndDistiller
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

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
    val neuralSkillCompiler = NeuralSkillCompilerAndDistiller(context)
    val semanticTeleportRouter = SemanticTeleportRouter(context)

    private var activeJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
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
                val sender = runCatching { ChatSender.valueOf(obj.optString("sender", "AGENT")) }
                    .getOrDefault(ChatSender.AGENT)
                if (sender == ChatSender.NOTIFICATION) continue
                list.add(
                    LiveChatMessage(
                        id = obj.optString("id", "msg_$i"),
                        sender = sender,
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
        val isGameOrAppInventory = (gLower.contains("게임") || gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린 앱") || gLower.contains("깔린 게임")) &&
            (gLower.contains("찾아") || gLower.contains("뭐 있") || gLower.contains("무슨") || gLower.contains("알려") || gLower.contains("확인") || gLower.contains("목록") || gLower.contains("스캔"))
        val isCraftBlueprint = gLower.contains("크래프트") || gLower.contains("craft") || gLower.contains("클린룸") || gLower.contains("구조 분석") || gLower.contains("리버스 엔지니어링")
        val isNotifCheckOrReply = gLower.contains("알림") || gLower.contains("답장") || (gLower.contains("카톡") && (gLower.contains("왔어") || gLower.contains("요약")))
        val isDeviceSetting = gLower.contains("밝기") || gLower.contains("볼륨") || gLower.contains("소리") || gLower.contains("방해금지")
        val isScreenOrStatusInquiry = (gLower.contains("화면") && (gLower.contains("뭐") || gLower.contains("설명") || gLower.contains("브리핑"))) ||
            gLower.contains("무슨 작업 중") || gLower.contains("병렬 상태")
        return isGameOrAppInventory || isCraftBlueprint || isNotifCheckOrReply || isDeviceSetting || isScreenOrStatusInquiry
    }

    private fun launchParallelBackgroundTask(bgGoal: String) {
        val taskId = "bg_${System.currentTimeMillis()}_${(100..999).random()}"
        val slot = ParallelTaskSlot(taskId = taskId, lane = "BG", goal = bgGoal)
        registerParallelSlot(slot)

        val job = scope.launch(Dispatchers.IO) {
            try {
                val gLower = bgGoal.lowercase(Locale.ROOT).trim()
                val snapshot = HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)

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
                    (gLower.contains("밝기") || gLower.contains("볼륨") || gLower.contains("소리") || gLower.contains("방해금지")) -> {
                        val settingArgs = when {
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
                            chatHistory = _chatMessages.value
                        )
                        val headlessSafeTools = setOf(
                            "list_notifications", "reply_notification", "control_device_setting",
                            "search_contacts", "manage_calendar", "set_alarm", "save_note", "read_note",
                            "os_cli_exec", "reverse_engineer_app", "learn_app_skill", "render_generative_ui"
                        )
                        val resultText = if (decision.toolName in headlessSafeTools ||
                            (decision.toolName == "manage_app" && decision.toolArgs.optString("action") != "launch")
                        ) {
                            val outcome = toolHarness.execute(decision.toolName, decision.toolArgs, snap)
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

        // Explicit BACKGROUND dispatch: force all sub-goals into the non-blocking Background Lane!
        if (effectiveLane == ExecutionLaneMode.BACKGROUND) {
            val subGoals = splitCompoundParallelGoals(trimmedGoal)
            subGoals.forEach { launchParallelBackgroundTask(it) }
            return
        }

        // Explicit FOREGROUND dispatch: force into the Foreground GUI Lane!
        if (effectiveLane == ExecutionLaneMode.FOREGROUND) {
            dispatchToForegroundGuiLane(listOf(trimmedGoal))
            return
        }

        // AUTO smart lane splitting: fan out compound goals into BG and FG lanes simultaneously!
        val subGoals = splitCompoundParallelGoals(trimmedGoal)
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
        pendingGuiGoals.clear()
        concurrentBackgroundJobs.values.forEach { it.cancel() }
        concurrentBackgroundJobs.clear()
        _activeParallelTasks.value = emptyList()
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

            val fastSnapshot: AtomicSnapshot? = HansonAccessibilityService.instance?.captureSnapshotNow(
                includeScreenshot = false
            )
            val needsVisualScreenshot = fastSnapshot == null ||
                fastSnapshot.indexedElements.size < 4 ||
                currentGoal.contains("사진") ||
                currentGoal.contains("이미지") ||
                currentGoal.contains("그림")

            val snapshot: AtomicSnapshot = if (needsVisualScreenshot) {
                HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = true) ?: AtomicSnapshot(
                    screenWidth = fallbackW,
                    screenHeight = fallbackH,
                    activePackage = context.packageName,
                    activeWindowTitle = "Hanson Board"
                )
            } else {
                fastSnapshot!!
            }

            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("S1/S2 계층 분기 중", currentGoal.take(28), true)

            val stepStartMs = System.currentTimeMillis()
            val prevSteps = _currentSession.value.steps
            val recentFailed = prevSteps.lastOrNull()?.let { !it.succeeded } ?: false

            // =========================================================================
            // LAYER 1: SYSTEM-1 BASE (Sub-10ms Reflex + Habit Prototype + Distilled 16KB VLM Probe)
            // =========================================================================
            val instantReflex = multimodalClient.tryInstantConversationalOrScreenFastPath(
                goal = currentGoal,
                iteration = iter,
                snapshot = snapshot,
                historySteps = prevSteps
            )

            val (decision, backendBadge, actorTier) = if (instantReflex != null && instantReflex.toolName == "complete_task") {
                Triple(instantReflex, "S1-REFLEX", "S1_REFLEX")
            } else {
                // Check Beyond-Habit JIT Skill Macro & Persistent Contrastive Subword/Spatial VLM Distiller first
                val advancedS1Decision = neuralSkillCompiler.evaluateAdvancedSystem1(
                    goal = currentGoal,
                    snapshot = snapshot,
                    recentStepFailed = recentFailed
                )
                if (advancedS1Decision != null) {
                    val badge = if (advancedS1Decision.observation.contains("Contrastive")) "S1-CONTRASTIVE-VLM" else "S1-JIT-MACRO"
                    Triple(advancedS1Decision, badge, "S1_JIT_COMPILER")
                } else {
                    val s1TurnResult = executionModeController.evaluateTurn(
                        snapshot = snapshot,
                        goal = currentGoal,
                        recentStepFailed = recentFailed
                    )
                    when (s1TurnResult) {
                        is ModeTurnResult.DirectAction -> {
                            val badge = if (s1TurnResult.rationaleKo.contains("Distilled")) "S1-DISTILLED-VLM" else "S1-HABIT"
                            Triple(s1TurnResult.decision, badge, "S1_HABIT_PROTOTYPE")
                        }
                        is ModeTurnResult.SpeculativeDispatched -> {
                            Triple(s1TurnResult.speculativeDecision, "S1-SPECULATIVE", "S1_HABIT_PROTOTYPE")
                        }
                        is ModeTurnResult.EscalateToSystem2 -> {
                            if (instantReflex != null) {
                                Triple(instantReflex, "S1-FASTPATH", "S1_REFLEX")
                            } else {
                                // =========================================================================
                                // LAYER 2: SYSTEM-2 DELIBERATIVE TEACHER (S25 NPU / Cloud Gemini VLM)
                                // =========================================================================
                                val s2Decision = aiInfrastructureRouter.routeAndDecide(
                                    apiKey = cfg.apiKey,
                                    modelName = cfg.modelName,
                                    goal = currentGoal,
                                    iteration = iter,
                                    snapshot = snapshot,
                                    historySteps = prevSteps,
                                    previousPlan = _currentSession.value.subGoals,
                                    chatHistory = _chatMessages.value
                                )
                                Triple(s2Decision, "S2-${aiInfrastructureRouter.state.value.lastUsedBackendBadge}", "S2_DELIBERATIVE")
                            }
                        }
                    }
                }
            }

            _currentSession.update {
                it.copy(subGoals = decision.subGoals)
            }

            // 1. Task Completion -> speak and post natural conversational response
            if (decision.toolName == "complete_task") {
                FloatingHudService.setTouchPassthrough(false)
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

            // 3. Speak natural conversational acknowledgement ONLY on Turn 1 (no intermediate click narration spam!)
            val stepNarration = decision.spokenReply.trim()
            if (iter == 1 && stepNarration.isNotBlank() && stepNarration != lastSpokenStepText) {
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

            val manageAppAction = decision.toolArgs.optString("action", "").lowercase()
            if (decision.toolName == "manage_app" && manageAppAction in listOf("search", "list", "list_games", "list_all")) {
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

            delay(180L)
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

            val updatedSteps = _currentSession.value.steps
            val stepLatencyMs = (System.currentTimeMillis() - stepStartMs).coerceAtLeast(1L)
            scope.launch(Dispatchers.IO) {
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
                    actorTier = actorTier,
                    toolName = decision.toolName,
                    toolArgsJson = decision.toolArgs.toString(),
                    verifiedSuccess = verification.verifiedSuccess,
                    uiDeltaRatio = verification.treeDeltaRatio,
                    recoveryAttempted = verification.recoveryAttempted,
                    latencyMs = stepLatencyMs,
                    goal = currentGoal
                )
                if (verification.verifiedSuccess) {
                    executionModeController.distillVerifiedStep(
                        goal = currentGoal,
                        snapshot = snapshot,
                        decision = decision
                    )
                }
                neuralSkillCompiler.compileAndDistillVerifiedStep(
                    goal = currentGoal,
                    preSnapshot = snapshot,
                    toolName = decision.toolName,
                    toolArgs = decision.toolArgs,
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
