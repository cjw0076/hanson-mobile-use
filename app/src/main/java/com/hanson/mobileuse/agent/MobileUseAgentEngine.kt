package com.hanson.mobileuse.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.NotificationStormShieldEngine
import com.hanson.mobileuse.notification.ProactiveSecretaryNotifier
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.service.HansonSecretaryWidgetProvider
import com.hanson.mobileuse.ui.components.CanvasErrorTelemetry
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.ComposerCommandParser
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import com.hanson.mobileuse.workspace.ParsedComposerInput
import com.hanson.mobileuse.workspace.WorkspaceDiffRecord
import kotlinx.coroutines.CancellationException
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
import java.io.File
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
    val lane: String, // "FG", "BG", "GUI", or "QA"
    val goal: String,
    val startedAtMs: Long = System.currentTimeMillis()
)

data class AgentEngineConfig(
    val apiKey: String = "",
    val modelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
    val maxIterations: Int = 15,
    val autoShowFloatingHud: Boolean = true,
    val ttsVoiceReply: Boolean = true,
    val customBaseUrl: String = "",
    val bridgeToken: String = ""
)

/**
 * Conversational Full-Control Mobile-Use Agent Engine (`MobileUseAgentEngine`).
 *
 * Bridges:
 * - Text-First Native Grounded Web Q&A (`GeminiMultimodalClient.answerTextOrGroundedWebQuery`)
 * - Multi-Tier Mobile AI Infrastructure Router (`MobileAiInfrastructureRouter`:
 *   Cloud Multi-Provider VLM + Laptop CLI Bridge + MediaPipe + Local Edge + Galaxy S25 AICore Nano NPU)
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
        private const val TAG = "HansonAgentEngine"
        private const val CONFIG_PREFS = "hanson_agent_engine_config"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "gemini_model_name"
        private const val KEY_MAX_ITER = "max_iterations"
        private const val KEY_AUTO_HUD = "auto_floating_hud"
        private const val KEY_TTS_VOICE = "tts_voice_reply"
        private const val KEY_CUSTOM_BASE_URL = "custom_openai_base_url"
        private const val KEY_CHAT_HISTORY = "live_chat_history_json"
        private const val KEY_UX_MODE = "hanson_ux_mode"
        private const val VAULT_KEY_CLOUD_API_KEYS = "cloud_api_keys"
        private const val VAULT_KEY_CLI_BRIDGE_TOKEN = "cli_bridge_token"

        // Fail-closed strict affirmative regexes for AWAITING_APPROVAL and AWAITING_TAKEOVER
        private val STRICT_APPROVAL_REGEX = Regex(
            "^(?:응|어|네|예|확인|승인|진행|진행해|좋아|오케이|ok|yes|approve)$",
            RegexOption.IGNORE_CASE
        )
        private val STRICT_TAKEOVER_RESUME_REGEX = Regex(
            "^(?:응|어|네|예|확인|완료|했어|끝|재개|진행|진행해|ok|yes|done|resume)$",
            RegexOption.IGNORE_CASE
        )

        private val SUPPORTED_A2UI_ACTION_PREFIXES = listOf(
            "secretary.",
            "commitment.",
            "defense.",
            "shield.",
            "care.",
            "tx.",
            "evidence.",
            "share.",
            "demo.",
            "call."
        )

        fun isSupportedA2uiSecretaryAction(actionName: String): Boolean {
            val normalized = actionName.trim().lowercase(Locale.ROOT)
            return SUPPORTED_A2UI_ACTION_PREFIXES.any { normalized.startsWith(it) }
        }
    }

    private val prefs = context.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val multimodalClient = GeminiMultimodalClient()
    val aiInfrastructureRouter = MobileAiInfrastructureRouter(context, multimodalClient)
    private val toolHarness = AgentToolExecutionHarness(context, workspaceEngine)
    private val trajectoryVerifier = ActionTrajectoryVerifierAndHealer(context)
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
    private val pendingGuiAttachedContexts = ConcurrentHashMap<String, String>()
    private val craftAnythingEngine = CraftAnythingMobileEngine(context)

    @Volatile
    private var liveSteeringGoal: String? = null

    private val _lastCloudApiError = MutableStateFlow<String?>(null)
    val lastCloudApiError: StateFlow<String?> = _lastCloudApiError.asStateFlow()

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

    private val _uxMode = MutableStateFlow(
        UserExperienceMode.fromId(prefs.getString(KEY_UX_MODE, UserExperienceMode.SIMPLE_HUMAN.id))
    )
    val uxMode: StateFlow<UserExperienceMode> = _uxMode.asStateFlow()

    private val _workMode = MutableStateFlow(
        if (_uxMode.value == UserExperienceMode.SIMPLE_HUMAN) {
            AgentWorkMode.AUTO
        } else {
            sessionStore?.state?.value?.activeSession?.mode ?: AgentWorkMode.AUTO
        }
    )
    val workMode: StateFlow<AgentWorkMode> = _workMode.asStateFlow()

    @Volatile
    private var pendingPlanGoal: String? = null

    @Volatile
    private var activeCrewAssignment: HansonCrewAssignment? = null
    private val _activeCrewRole = MutableStateFlow<HansonCrewRole?>(null)
    val activeCrewRole: StateFlow<HansonCrewRole?> = _activeCrewRole.asStateFlow()

    init {
        HansonForegroundService.onEmergencyStopRequested = { stopRun("네, 작업을 바로 멈췄어요.") }
        FloatingHudService.onStopClicked = { stopRun("네, 작업을 멈췄어요. 다음엔 어떻게 할까요?") }
        FloatingHudService.onSendLiveChatMessage = { userMsg -> sendChatMessage(userMsg) }
        FloatingHudService.onApprovalDecision = { approved ->
            resolvePendingApproval(approved, alwaysAllowCapability = false)
        }
        FloatingHudService.onUndoLastClicked = { executeOneTapUndo() }
        FloatingHudService.onFinishRecordingClicked = { finishDemonstrationAndSaveRoutine() }
        com.hanson.mobileuse.service.DynamicCanvasOverlayService.onAskWithCoDrawSketch = { q ->
            submitCoDrawingQuery(q)
        }
        LiveVoiceSessionManager.ensureInitialized(context) { spoken ->
            sendChatMessage(spoken)
        }
        GenerativeUiStateHolder.addErrorListener { telemetry ->
            onCanvasExecutionError(telemetry.rawJson.ifBlank { telemetry.toJsonString() })
        }
        runCatching {
            val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
            ProactiveSecretaryEngine.ensureInitialized(appCtx)
            ProactiveSecretaryNotifier.registerHook(appCtx)
            LifestyleBeatScheduler.ensureInitialized(appCtx)
            LifestyleBeatScheduler.seedDefaultBeatsIfEmpty()
            RoomUrgencyCalibrationStore.ensureInitialized(appCtx)
            PrivacyAndQuotaProofLedger.ensureInitialized(appCtx)
            CommitmentDebtTrackerEngine.ensureInitialized(appCtx)
            RelationshipCareCadenceEngine.ensureInitialized(appCtx)
            ActionTransactionLedger.ensureInitialized(appCtx)
        }
    }

    /**
     * Executes a human-approved Proactive Chief-of-Staff AI Secretary proposal action.
     * Enforces strict banking read-only advisory boundaries (never auto-clicks financial apps),
     * routes real OS tool actions via [toolHarness], updates proposal lifecycle state, cleans up
     * A2UI surface + lockscreen notification, and records an [ActionReceipt] + concise Korean chat confirmation.
     */
    fun executeProactiveProposal(
        proposalId: String,
        actionKey: String = "approve",
        replyText: String = "",
        snoozeMinutes: Long = 60L
    ): Pair<Boolean, String> {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }

        val cleanId = proposalId.trim()
        if (cleanId.isEmpty()) {
            return false to "제안 ID가 비어 있어요."
        }
        val proposal = ProactiveSecretaryEngine.getProposal(cleanId)
            ?: return false to "해당 선제안 항목을 찾을 수 없어요: $cleanId"

        fun cleanupProposalSurfaces() {
            runCatching {
                A2uiSurfaceRegistry.deleteSurface(ProactiveProposalToA2uiBridge.surfaceIdFor(proposal))
            }
            runCatching {
                ProactiveSecretaryNotifier.cancelNotification(appCtx, proposal.id)
            }
            runCatching {
                HansonSecretaryWidgetProvider.requestRefresh(appCtx)
            }
        }

        val normalizedKey = actionKey.trim().lowercase(Locale.ROOT).ifBlank { "approve" }
        when (normalizedKey) {
            "dismiss" -> {
                ProactiveSecretaryEngine.dismissProposal(proposal.id)
                cleanupProposalSurfaces()
                return true to "'${proposal.titleKo}' 제안을 무시했어요."
            }

            "snooze" -> {
                val mins = snoozeMinutes.coerceIn(1L, 24L * 60L)
                ProactiveSecretaryEngine.snoozeProposal(proposal.id, snoozeMinutes = mins)
                cleanupProposalSurfaces()
                return true to "'${proposal.titleKo}' 제안을 ${mins}분 뒤로 미뤘어요."
            }

            "a2ui" -> {
                val surfaceId = ProactiveProposalToA2uiBridge.publishToA2uiRegistry(proposal)
                if (ProactiveSecretaryEngine.settings.value.floatingA2uiOverlayEnabled) {
                    runCatching {
                        com.hanson.mobileuse.service.DynamicCanvasOverlayService.startIfPermitted(
                            appCtx,
                            initialDrawMode = false
                        )
                    }
                }
                return true to "'${proposal.titleKo}' 제안을 A2UI 카드($surfaceId)로 펼쳤어요."
            }
        }

        val resolvedAction: ProposalAction = when (normalizedKey) {
            "approve" -> proposal.primaryAction
            "reply" -> {
                proposal.secondaryActions.firstOrNull { it.kind == ProposalActionKind.SEND_QUICK_REPLY }
                    ?: proposal.primaryAction.takeIf { it.kind == ProposalActionKind.SEND_QUICK_REPLY }
                    ?: ProposalAction(
                        key = "reply",
                        labelKo = "빠른 답장",
                        kind = ProposalActionKind.SEND_QUICK_REPLY
                    )
            }
            "open_map" -> {
                proposal.secondaryActions.firstOrNull { it.kind == ProposalActionKind.OPEN_MAP }
                    ?: proposal.primaryAction.takeIf { it.kind == ProposalActionKind.OPEN_MAP }
                    ?: ProposalAction(
                        key = "open_map",
                        labelKo = "지도 열기",
                        kind = ProposalActionKind.OPEN_MAP
                    )
            }
            else -> {
                proposal.primaryAction.takeIf { it.key.equals(actionKey.trim(), ignoreCase = true) }
                    ?: proposal.secondaryActions.firstOrNull { it.key.equals(actionKey.trim(), ignoreCase = true) }
                    ?: return false to "지원하지 않는 비서 액션입니다: $actionKey"
            }
        }

        if (resolvedAction.kind == ProposalActionKind.DISMISS) {
            ProactiveSecretaryEngine.dismissProposal(proposal.id)
            cleanupProposalSurfaces()
            return true to "'${proposal.titleKo}' 제안을 닫았어요."
        }
        if (resolvedAction.kind == ProposalActionKind.SNOOZE) {
            val mins = (resolvedAction.payload["minutes"]?.toLongOrNull() ?: snoozeMinutes).coerceIn(1L, 24L * 60L)
            ProactiveSecretaryEngine.snoozeProposal(proposal.id, snoozeMinutes = mins)
            cleanupProposalSurfaces()
            return true to "'${proposal.titleKo}' 제안을 ${mins}분 뒤로 미뤘어요."
        }

        // Strict Banking Read-Only Hardline: NEVER auto-click or launch autonomous GUI runs for finance apps.
        val isReadOnlyFinance = proposal.readOnlyAdvisory ||
            AppDetailPlaybookRegistry.isFinanceReadOnlyPackage(proposal.packageName)
        val effectiveKind = if (isReadOnlyFinance &&
            (resolvedAction.kind == ProposalActionKind.RUN_AGENT_GOAL ||
                resolvedAction.kind == ProposalActionKind.SEND_QUICK_REPLY)
        ) {
            ProposalActionKind.SAVE_NOTE
        } else {
            resolvedAction.kind
        }

        val outcome: ToolExecutionOutcome = when (effectiveKind) {
            ProposalActionKind.ADD_CALENDAR -> {
                val calTitle = resolvedAction.payload["title"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.titleKo
                val startMs = resolvedAction.payload["start_time_ms"]?.toLongOrNull() ?: proposal.startTimeMs
                val endMs = resolvedAction.payload["end_time_ms"]?.toLongOrNull() ?: proposal.endTimeMs
                val loc = resolvedAction.payload["location"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.location
                val reminderMin = resolvedAction.payload["reminder_minutes"]?.toIntOrNull() ?: 30
                val desc = resolvedAction.payload["description"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.detailKo.ifBlank { proposal.summaryKo }
                val args = JSONObject().apply {
                    put("action", "add")
                    put("title", calTitle)
                    if (startMs != null && startMs > 0L) put("start_time_ms", startMs)
                    if (endMs != null && endMs > 0L) put("end_time_ms", endMs)
                    if (loc.isNotBlank()) put("location", loc)
                    put("reminder_minutes", reminderMin)
                    put("description", desc)
                }
                toolHarness.execute("manage_calendar", args, null)
            }

            ProposalActionKind.SET_ALARM -> {
                val payloadHour = resolvedAction.payload["hour"]?.toIntOrNull()
                val payloadMinute = resolvedAction.payload["minute"]?.toIntOrNull()
                val (hour, minute) = if (payloadHour != null && payloadMinute != null) {
                    payloadHour.coerceIn(0, 23) to payloadMinute.coerceIn(0, 59)
                } else if (proposal.startTimeMs != null) {
                    val targetMs = (proposal.startTimeMs - 15L * 60_000L)
                        .coerceAtLeast(System.currentTimeMillis() + 60_000L)
                    val zdt = java.time.Instant.ofEpochMilli(targetMs)
                        .atZone(KoreanScheduleParser.DEFAULT_ZONE)
                    zdt.hour to zdt.minute
                } else {
                    8 to 0
                }
                val label = resolvedAction.payload["label"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.titleKo
                val args = JSONObject().apply {
                    put("hour", hour)
                    put("minute", minute)
                    put("label", label)
                }
                toolHarness.execute("set_alarm", args, null)
            }

            ProposalActionKind.SEND_QUICK_REPLY -> {
                val finalReply = replyText.trim()
                    .ifBlank { resolvedAction.payload["replyText"]?.trim().orEmpty() }
                    .ifBlank { proposal.quickReplyDrafts.firstOrNull()?.trim().orEmpty() }
                if (finalReply.isEmpty()) {
                    return false to "전송할 빠른 답장 문구가 비어 있어요."
                }
                val sessionIdCandidate = resolvedAction.payload["sessionId"]?.trim().orEmpty()
                val targetRoom = resolvedAction.payload["roomOrTitle"]?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: proposal.roomOrTitle.ifBlank { proposal.sender }
                val targetKey = when {
                    sessionIdCandidate.isNotEmpty() && RemoteInputSessionCache.get(sessionIdCandidate) != null ->
                        sessionIdCandidate
                    else -> RemoteInputSessionCache.findByRoom(
                        pkg = proposal.packageName,
                        room = targetRoom,
                        onlyReplyable = true
                    )?.sessionId ?: targetRoom
                }
                val args = JSONObject().apply {
                    put("target", targetKey)
                    put("message", finalReply)
                }
                toolHarness.execute("reply_notification", args, null)
            }

            ProposalActionKind.SAVE_NOTE -> {
                val noteKey = resolvedAction.payload["key"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "비서_${proposal.type.badgeKo}_${proposal.titleKo.take(18)}"
                val noteContent = resolvedAction.payload["content"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: buildString {
                        append(proposal.titleKo)
                        if (proposal.summaryKo.isNotBlank()) append(" — ").append(proposal.summaryKo)
                        if (proposal.detailKo.isNotBlank()) append("\n").append(proposal.detailKo)
                    }
                runCatching { resolvedScratchpad.put(noteKey, noteContent) }
                val args = JSONObject().apply {
                    put("key", noteKey)
                    put("content", noteContent)
                }
                toolHarness.execute("save_note", args, null)
            }

            ProposalActionKind.OPEN_MAP -> {
                val place = resolvedAction.payload["location"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.location.ifBlank { proposal.roomOrTitle }
                if (place.isBlank()) {
                    return false to "지도에서 열 위치 정보가 없어요."
                }
                val deepLink = resolvedAction.payload["deepLink"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: AppDetailPlaybookRegistry.buildDeepLinkForPlace(place)
                val primaryMapOutcome = toolHarness.execute(
                    "open_link",
                    JSONObject().put("url", deepLink),
                    null
                )
                if (primaryMapOutcome.succeeded) {
                    primaryMapOutcome
                } else {
                    val webFallback = AppDetailPlaybookRegistry.buildWebFallbackMapUrl(place)
                    toolHarness.execute("open_link", JSONObject().put("url", webFallback), null)
                }
            }

            ProposalActionKind.OPEN_LINK -> {
                val url = (resolvedAction.payload["url"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: proposal.meetingUrl).trim()
                if (!url.startsWith("https://", ignoreCase = true)) {
                    return false to "보안상 https:// 링크만 열 수 있어요."
                }
                toolHarness.execute("open_link", JSONObject().put("url", url), null)
            }

            ProposalActionKind.START_DND -> {
                val args = JSONObject().apply {
                    put("setting", "dnd")
                    put("value", "on")
                }
                toolHarness.execute("control_device_setting", args, null)
            }

            ProposalActionKind.RUN_AGENT_GOAL -> {
                val goal = resolvedAction.payload["goal"]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "${proposal.titleKo} (${proposal.summaryKo}) 항목을 진행해줘"
                startRun(goal)
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "AI 에이전트 실행을 시작했어요: $goal",
                    receipt = ActionReceipt(
                        toolName = "proactive_secretary",
                        summaryKo = "선제안 에이전트 실행: ${proposal.titleKo.take(32)}",
                        detailKo = goal.take(120),
                        undoHintKo = "상단 '중지' 버튼으로 언제든 실행을 멈출 수 있어요."
                    )
                )
            }

            ProposalActionKind.DISMISS, ProposalActionKind.SNOOZE -> {
                ToolExecutionOutcome(true, "처리 완료")
            }
        }

        if (outcome.succeeded) {
            ProactiveSecretaryEngine.markExecuted(proposal.id)
            cleanupProposalSurfaces()
            val executedToolName = when (effectiveKind) {
                ProposalActionKind.ADD_CALENDAR -> "manage_calendar"
                ProposalActionKind.SET_ALARM -> "set_alarm"
                ProposalActionKind.SEND_QUICK_REPLY -> "reply_notification"
                ProposalActionKind.SAVE_NOTE -> "save_note"
                ProposalActionKind.OPEN_MAP, ProposalActionKind.OPEN_LINK -> "open_link"
                ProposalActionKind.START_DND -> "control_device_setting"
                ProposalActionKind.RUN_AGENT_GOAL -> "start_run"
                ProposalActionKind.DISMISS, ProposalActionKind.SNOOZE -> "proactive_secretary"
            }
            val savedNoteKey = resolvedAction.payload["key"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "비서_${proposal.type.badgeKo}_${proposal.titleKo.take(18)}"
            val undoCommand = when (effectiveKind) {
                ProposalActionKind.SAVE_NOTE -> "remove_note:$savedNoteKey"
                ProposalActionKind.START_DND -> "dnd_off"
                ProposalActionKind.ADD_CALENDAR -> "delete_calendar:${resolvedAction.payload["title"] ?: proposal.titleKo}"
                else -> ""
            }
            val txNow = System.currentTimeMillis()
            runCatching {
                ActionTransactionLedger.ensureInitialized(appCtx)
                ActionTransactionLedger.recordVerifiedTransaction(
                    txId = "tx_${proposal.id}_${txNow}",
                    proposalId = proposal.id,
                    summaryKo = "[선제안 비서] ${proposal.titleKo}",
                    steps = listOf(
                        TransactionStepRecord(
                            stepId = "step_${proposal.id}_${effectiveKind.name.lowercase(Locale.ROOT)}",
                            toolName = executedToolName,
                            targetResourceId = if (effectiveKind == ProposalActionKind.SAVE_NOTE) savedNoteKey else proposal.id,
                            reversibility = AtomicRollbackAndExplainEngine.classifyReversibility(proposal),
                            undoCommand = undoCommand,
                            readbackVerified = true,
                            readbackDetailKo = outcome.outputText.take(140),
                            executedAtMs = txNow
                        )
                    ),
                    nowMs = txNow
                )
            }
            runCatching { HansonSecretaryWidgetProvider.requestRefresh(appCtx) }
            val receiptToRecord = outcome.receipt ?: ActionReceipt(
                toolName = "proactive_secretary",
                summaryKo = "[선제안 비서] ${proposal.titleKo.take(36)}",
                detailKo = outcome.outputText.take(140),
                undoHintKo = "관련 앱에서 방금 실행한 작업을 확인하거나 변경할 수 있어요."
            )
            runCatching { recordReceipt(receiptToRecord) }
            runCatching {
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "선제안 비서 작업을 완료했어요: ${proposal.titleKo}\n${outcome.outputText}",
                        subtitle = "${proposal.type.badgeKo} · ${proposal.sourceApp}",
                        toolBadge = "SECRETARY",
                        succeeded = true
                    )
                )
            }
        }

        return outcome.succeeded to outcome.outputText
    }

    /**
     * Rolls back the specified or most recent verified Proactive Secretary transaction in
     * reverse order via [ActionTransactionLedger], honestly reporting irreversible external sends.
     */
    fun rollbackLastSecretaryTransaction(txId: String = ""): String {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching { ActionTransactionLedger.ensureInitialized(appCtx) }

        val targetTxId = txId.trim().ifBlank {
            ActionTransactionLedger.listTransactions()
                .lastOrNull { it.status == "VERIFIED_DONE" || it.status == "UNVERIFIED_PARTIAL" }
                ?.txId
                .orEmpty()
        }
        if (targetTxId.isEmpty()) {
            return "되돌릴 최근 선제안 비서 실행 기록이 없어요."
        }

        val report = ActionTransactionLedger.rollbackTransaction(
            txId = targetTxId,
            executor = { step ->
                when {
                    step.undoCommand.startsWith("remove_note:") -> {
                        val key = step.undoCommand.removePrefix("remove_note:").trim()
                        if (key.isNotEmpty()) {
                            resolvedScratchpad.remove(key)
                            true
                        } else {
                            false
                        }
                    }
                    step.undoCommand == "dnd_off" -> {
                        toolHarness.execute(
                            "control_device_setting",
                            JSONObject().put("setting", "dnd").put("value", "off"),
                            null
                        ).succeeded
                    }
                    step.undoCommand.startsWith("delete_calendar:") -> {
                        val title = step.undoCommand.removePrefix("delete_calendar:").trim()
                        toolHarness.execute(
                            "manage_calendar",
                            JSONObject().put("action", "delete").put("title", title),
                            null
                        ).succeeded
                    }
                    else -> executeOneTapUndo()
                }
            }
        ) ?: return "해당 실행 트랜잭션(${targetTxId})을 찾을 수 없어요."

        runCatching {
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = "실행 되돌리기 결과 (${report.txId}): ${report.summaryKo}",
                    subtitle = "Atomic Rollback Ledger",
                    toolBadge = "ROLLBACK",
                    succeeded = report.failedStepIds.isEmpty()
                )
            )
        }
        runCatching { HansonSecretaryWidgetProvider.requestRefresh(appCtx) }
        return report.summaryKo
    }

    /**
     * Builds and speaks a clean 60-second TTS-safe Korean daily briefing via [LifestyleBeatScheduler].
     */
    fun playSpokenDailyBrief(forceSpeak: Boolean = false): String {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val proposals = ProactiveSecretaryEngine.proposals.value
        val brief = ProactiveDailyBriefEngine.buildBrief(
            hourOfDay = hour,
            notifications = RemoteInputSessionCache.listRecent(limit = 15),
            watcherRules = resolvedWatcherEngine.listRules(),
            scratchpadEntries = resolvedScratchpad.listEntries(),
            bubbles = resolvedMemoryBubbles.bubbles.value,
            pinnedRoutines = resolvedPersonalRoutines.routines.value,
            proactiveProposals = proposals
        )
        val script = LifestyleBeatScheduler.buildSpokenDailyBriefScript(
            brief = brief,
            proposals = proposals,
            hourOfDay = hour
        )
        if (forceSpeak) {
            runCatching { LiveVoiceSessionManager.speakAgentResponse(script) }
        } else {
            speakIfEnabled(script)
        }
        return script
    }

    /**
     * Exports a zero-secret, `AGENTS.md`-compatible portable personal context pack via [CrossLabPortabilityPack].
     */
    fun exportCrossLabPortabilityMarkdown(): String {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching {
            LifestyleBeatScheduler.ensureInitialized(appCtx)
            RelationshipCareCadenceEngine.ensureInitialized(appCtx)
        }
        return CrossLabPortabilityPack.exportMarkdown(
            bubbles = resolvedMemoryBubbles.bubbles.value,
            routines = resolvedPersonalRoutines.routines.value,
            vipContacts = RelationshipCareCadenceEngine.listVips(),
            beats = LifestyleBeatScheduler.beats.value
        )
    }

    private fun launchSafeDialer(rawPhoneOrUri: String): Boolean {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        val trimmed = rawPhoneOrUri.trim()
        val rawDigits = trimmed.removePrefix("tel:").filter { it.isDigit() || it == '+' || it == '-' }
        val dialUri = if (rawDigits.isNotBlank()) {
            Uri.parse("tel:${Uri.encode(rawDigits)}")
        } else {
            Uri.parse("tel:")
        }
        return runCatching {
            val intent = Intent(Intent.ACTION_DIAL, dialUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appCtx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun extractStringListFromContext(contextJson: JSONObject?, key: String): List<String> {
        if (contextJson == null) return emptyList()
        val arr = contextJson.optJSONArray(key)
        if (arr != null) {
            return buildList {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "").trim()
                    if (v.isNotEmpty()) add(v)
                }
            }
        }
        val raw = contextJson.optString(key, "").trim()
        if (raw.isEmpty()) return emptyList()
        return raw.split(",", ";", "\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Dispatches Google A2UI `secretary.*`, `commitment.*`, `defense.*`, `shield.*`, `care.*`,
     * `tx.*`, `evidence.*`, `share.*`, `demo.*`, and `call.*` button events emitted from in-app
     * [CommandCenterScreen] or cross-app [com.hanson.mobileuse.service.DynamicCanvasOverlayService].
     */
    fun handleA2uiSecretaryAction(actionName: String, contextJson: JSONObject?): Boolean {
        val normalized = actionName.trim().lowercase(Locale.ROOT)
        if (!isSupportedA2uiSecretaryAction(normalized)) return false
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        val proposalId = contextJson?.optString("proposalId", "")?.trim()
            ?.ifBlank { contextJson.optString("proposal_id", "").trim() }
            .orEmpty()
        val surfaceId = contextJson?.optString("surfaceId", "")?.trim().orEmpty()

        return runCatching {
            when (normalized) {
                ProactiveProposalToA2uiBridge.ACTION_APPROVE -> {
                    if (proposalId.isEmpty()) return false
                    val key = contextJson?.optString("actionKey", "approve")?.trim()
                        ?.ifBlank { "approve" } ?: "approve"
                    executeProactiveProposal(proposalId = proposalId, actionKey = key).first
                }

                ProactiveProposalToA2uiBridge.ACTION_DISMISS -> {
                    if (proposalId.isEmpty()) return false
                    val ok = executeProactiveProposal(proposalId = proposalId, actionKey = "dismiss").first
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    ok
                }

                ProactiveProposalToA2uiBridge.ACTION_SNOOZE -> {
                    if (proposalId.isEmpty()) return false
                    val mins = contextJson?.optLong("snoozeMinutes", 30L)
                        ?.takeIf { it > 0L }
                        ?: contextJson?.optLong("snooze_minutes", 30L)?.takeIf { it > 0L }
                        ?: 30L
                    val ok = executeProactiveProposal(
                        proposalId = proposalId,
                        actionKey = "snooze",
                        snoozeMinutes = mins
                    ).first
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    ok
                }

                ProactiveProposalToA2uiBridge.ACTION_REPLY -> {
                    if (proposalId.isEmpty()) return false
                    val text = contextJson?.optString("replyText", "")?.trim()
                        ?.ifBlank { contextJson.optString("reply_text", "").trim() }
                        .orEmpty()
                    executeProactiveProposal(
                        proposalId = proposalId,
                        actionKey = "reply",
                        replyText = text
                    ).first
                }

                ProactiveProposalToA2uiBridge.ACTION_OPEN_MAP -> {
                    if (proposalId.isNotEmpty()) {
                        executeProactiveProposal(proposalId = proposalId, actionKey = "open_map").first
                    } else {
                        val loc = contextJson?.optString("location", "")?.trim().orEmpty()
                        val url = contextJson?.optString("deepLink", "")?.trim()
                            ?.ifBlank { AppDetailPlaybookRegistry.buildDeepLinkForPlace(loc) }
                            .orEmpty()
                        if (url.isBlank()) false
                        else {
                            val first = toolHarness.execute("open_link", JSONObject().put("url", url), null)
                            if (first.succeeded) true
                            else if (loc.isNotBlank()) {
                                toolHarness.execute(
                                    "open_link",
                                    JSONObject().put("url", AppDetailPlaybookRegistry.buildWebFallbackMapUrl(loc)),
                                    null
                                ).succeeded
                            } else false
                        }
                    }
                }

                BatchDigestA2uiBuilder.ACTION_BATCH_APPROVE_SAFE -> {
                    val requestedIds = extractStringListFromContext(contextJson, "proposalIds")
                        .ifEmpty { ProactiveSecretaryEngine.listPendingProposals().map { it.id } }
                    val safeApproved = ProactiveSecretaryEngine.batchApproveSafe(requestedIds)
                    var executedCount = 0
                    safeApproved.forEach { prop ->
                        val (ok, _) = executeProactiveProposal(prop.id, "approve")
                        if (ok) executedCount++
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    A2uiSurfaceRegistry.deleteSurface("secretary_batch_digest")
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "안전한 선제안 ${executedCount}건을 일괄 승인·실행했어요. (금융·충돌·외부답장은 직접 확인을 위해 유지됩니다.)",
                            subtitle = "Batch Digest Approval",
                            toolBadge = "BATCH",
                            succeeded = true
                        )
                    )
                    executedCount > 0 || safeApproved.isEmpty()
                }

                BatchDigestA2uiBuilder.ACTION_BATCH_DISMISS_ALL -> {
                    val requestedIds = extractStringListFromContext(contextJson, "proposalIds")
                        .ifEmpty { ProactiveSecretaryEngine.listPendingProposals().map { it.id } }
                    val dismissed = ProactiveSecretaryEngine.batchDismiss(requestedIds)
                    dismissed.forEach { prop ->
                        runCatching { A2uiSurfaceRegistry.deleteSurface(ProactiveProposalToA2uiBridge.surfaceIdFor(prop)) }
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, prop.id) }
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    A2uiSurfaceRegistry.deleteSurface("secretary_batch_digest")
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    true
                }

                CommitmentDebtTrackerEngine.ACTION_MARK_DONE -> {
                    val targetId = proposalId.ifBlank { contextJson?.optString("commitmentId", "")?.trim().orEmpty() }
                    CommitmentDebtTrackerEngine.ensureInitialized(appCtx)
                    val updated = if (targetId.isNotEmpty()) CommitmentDebtTrackerEngine.markDone(targetId) else null
                    if (proposalId.isNotEmpty()) {
                        ProactiveSecretaryEngine.markExecuted(proposalId)
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId) }
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    if (targetId.isNotEmpty()) A2uiSurfaceRegistry.deleteSurface("commitment_${targetId}")
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "내가 한 약속 항목을 완료 처리했어요${updated?.let { ": ${it.promiseText}" }.orEmpty()}.",
                            subtitle = "Commitment Tracker",
                            toolBadge = "COMMITMENT",
                            succeeded = true
                        )
                    )
                    true
                }

                CommitmentDebtTrackerEngine.ACTION_DRAFT_UPDATE -> {
                    val draft = contextJson?.optString("draft", "")?.trim()
                        ?.ifBlank { "조금 늦어지고 있어요. 정리되는 대로 바로 보내드릴게요!" }
                        ?: "조금 늦어지고 있어요. 정리되는 대로 바로 보내드릴게요!"
                    runCatching { resolvedScratchpad.put("약속_안내초안", draft) }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "약속 진행 안내 초안을 메모에 준비했어요:\n\"${draft}\"",
                            subtitle = "Commitment Draft",
                            toolBadge = "COMMITMENT",
                            succeeded = true
                        )
                    )
                    true
                }

                CommitmentDebtTrackerEngine.ACTION_RESCHEDULE -> {
                    val targetId = proposalId.ifBlank { contextJson?.optString("commitmentId", "")?.trim().orEmpty() }
                    CommitmentDebtTrackerEngine.ensureInitialized(appCtx)
                    if (targetId.isNotEmpty()) CommitmentDebtTrackerEngine.rescheduleTomorrow(targetId)
                    if (proposalId.isNotEmpty()) {
                        ProactiveSecretaryEngine.snoozeProposal(proposalId, snoozeMinutes = 24L * 60L)
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId) }
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "약속 리마인더를 내일로 미뤘어요.",
                            subtitle = "Commitment Rescheduled",
                            toolBadge = "COMMITMENT",
                            succeeded = true
                        )
                    )
                    true
                }

                SubscriptionAndExpiryDefenseEngine.ACTION_SCHEDULE_D2_ALARM -> {
                    val alarmTimeMs = contextJson?.optLong("alarmTimeMs", 0L)
                        ?.takeIf { it > 0L }
                        ?: ProactiveSecretaryEngine.getProposal(proposalId)?.startTimeMs
                        ?: (System.currentTimeMillis() + 60 * 60_000L)
                    val zdt = java.time.Instant.ofEpochMilli(alarmTimeMs).atZone(KoreanScheduleParser.DEFAULT_ZONE)
                    val label = contextJson?.optString("label", "")?.trim()
                        ?.ifBlank { ProactiveSecretaryEngine.getProposal(proposalId)?.titleKo ?: "구독·반품 D-2 확인" }
                        ?: "구독·반품 D-2 확인"
                    val outcome = toolHarness.execute(
                        "set_alarm",
                        JSONObject().put("hour", zdt.hour).put("minute", zdt.minute).put("label", label),
                        null
                    )
                    if (proposalId.isNotEmpty() && outcome.succeeded) {
                        ProactiveSecretaryEngine.markExecuted(proposalId)
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId) }
                    }
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "구독·반품 D-2 방어 알람을 설정했어요 (${label}).\n${outcome.outputText}",
                            subtitle = "Subscription Defense",
                            toolBadge = "DEFENSE",
                            succeeded = outcome.succeeded
                        )
                    )
                    outcome.succeeded
                }

                SubscriptionAndExpiryDefenseEngine.ACTION_OPEN_SUBS -> {
                    val url = contextJson?.optString("url", "")?.trim()
                        ?.ifBlank { SubscriptionAndExpiryDefenseEngine.PLAY_STORE_SUBSCRIPTIONS_URL }
                        ?: SubscriptionAndExpiryDefenseEngine.PLAY_STORE_SUBSCRIPTIONS_URL
                    if (!url.startsWith("https://", ignoreCase = true)) return false
                    toolHarness.execute("open_link", JSONObject().put("url", url), null).succeeded
                }

                SubscriptionAndExpiryDefenseEngine.ACTION_DRAFT_REFUND -> {
                    val draft = contextJson?.optString("draft", "")?.trim()
                        ?.ifBlank { "구독 해지 및 반품·환불 요청 초안입니다. 결제 내역 확인 후 처리 부탁드립니다." }
                        ?: "구독 해지 및 반품·환불 요청 초안입니다."
                    runCatching { resolvedScratchpad.put("구독_환불요청_초안", draft) }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "해지·환불 요청 초안을 메모에 저장했어요:\n${draft}",
                            subtitle = "Subscription Defense Draft",
                            toolBadge = "DEFENSE",
                            succeeded = true
                        )
                    )
                    true
                }

                NotificationStormShieldEngine.ACTION_ACK_ALL -> {
                    val sid = contextJson?.optString("sessionId", "")?.trim().orEmpty()
                    if (proposalId.isNotEmpty()) ProactiveSecretaryEngine.markExecuted(proposalId)
                    if (sid.isNotEmpty()) A2uiSurfaceRegistry.deleteSurface("focus_reentry_${sid}")
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    true
                }

                NotificationStormShieldEngine.ACTION_EXTEND_30M -> {
                    val mins = contextJson?.optLong("minutes", 30L)?.coerceIn(5L, 240L) ?: 30L
                    NotificationStormShieldEngine.startFocusBlock()
                    NotificationStormShieldEngine.updateConfig { cur ->
                        cur.copy(snoozeDurationMs = mins * 60_000L)
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "알림 폭풍 집중 방패를 ${mins}분 더 연장했어요. VIP·긴급 메시지만 통과합니다.",
                            subtitle = "Focus Storm Shield",
                            toolBadge = "SHIELD",
                            succeeded = true
                        )
                    )
                    true
                }

                NotificationStormShieldEngine.ACTION_ADD_VIP -> {
                    val vipTarget = contextJson?.optString("room", "")?.trim()
                        ?.ifBlank { contextJson.optString("name", "").trim() }
                        .orEmpty()
                    if (vipTarget.isEmpty()) return false
                    NotificationStormShieldEngine.addVip(vipTarget)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "'${vipTarget}' 대상을 집중 방패 VIP 예외 목록에 추가했어요.",
                            subtitle = "Focus Storm Shield VIP",
                            toolBadge = "SHIELD",
                            succeeded = true
                        )
                    )
                    true
                }

                SmishingAndPlainKoreanEngine.ACTION_DIAL_REAL_PERSON -> {
                    val senderLabel = contextJson?.optString("sender", "")?.trim().orEmpty()
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "의심 링크를 누르지 않고 연락처에서 직접 통화로 확인하실 수 있도록 전화 앱을 열어요${if (senderLabel.isNotBlank()) " (${senderLabel})" else ""}.",
                            subtitle = "Scam Pre-Shield",
                            toolBadge = "SHIELD",
                            succeeded = true
                        )
                    )
                    launchSafeDialer("")
                }

                SmishingAndPlainKoreanEngine.ACTION_BOHONARA_GUIDE -> {
                    val guideText = "스미싱 의심 시 링크·첨부파일을 절대 열지 마세요. 보호나라(KISA) 및 공식 신고 안내 절차에 따라 공식 채널에서 문자 진위를 확인하고, 금융 피해가 우려되면 거래 은행 공식 고객센터에 직접 문의하세요."
                    runCatching { resolvedScratchpad.put("스미싱_공식신고_안내", guideText) }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = guideText,
                            subtitle = "보호나라(KISA) 및 공식 신고 안내",
                            toolBadge = "SHIELD",
                            succeeded = true
                        )
                    )
                    true
                }

                SmishingAndPlainKoreanEngine.ACTION_DISMISS_SAFE -> {
                    if (proposalId.isNotEmpty()) {
                        ProactiveSecretaryEngine.dismissProposal(proposalId)
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId) }
                        A2uiSurfaceRegistry.deleteSurface("scam_shield_${proposalId}")
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    true
                }

                RelationshipCareCadenceEngine.ACTION_OPEN_CHAT -> {
                    val pkg = contextJson?.optString("packageName", "")?.trim()
                        ?.ifBlank { "com.kakao.talk" } ?: "com.kakao.talk"
                    toolHarness.execute(
                        "manage_app",
                        JSONObject().put("action", "launch").put("app_name", pkg),
                        null
                    ).succeeded
                }

                RelationshipCareCadenceEngine.ACTION_OPEN_DIALER -> {
                    val phone = contextJson?.optString("phoneNumber", "")?.trim().orEmpty()
                    launchSafeDialer(phone)
                }

                RelationshipCareCadenceEngine.ACTION_SNOOZE_WEEKEND -> {
                    val vipKey = contextJson?.optString("vipId", "")?.trim()
                        ?.ifBlank { contextJson.optString("name", "").trim() }
                        .orEmpty()
                    RelationshipCareCadenceEngine.ensureInitialized(appCtx)
                    if (vipKey.isNotEmpty()) RelationshipCareCadenceEngine.snoozeUntilWeekend(vipKey)
                    if (proposalId.isNotEmpty()) {
                        ProactiveSecretaryEngine.snoozeProposal(proposalId, snoozeMinutes = 24L * 60L)
                        runCatching { ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId) }
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "소중한 분 안부 리마인더를 주말로 미뤘어요.",
                            subtitle = "Relationship Care Cadence",
                            toolBadge = "CARE",
                            succeeded = true
                        )
                    )
                    true
                }

                AtomicRollbackAndExplainEngine.ACTION_ROLLBACK_BUNDLE -> {
                    val txId = contextJson?.optString("txId", "")?.trim().orEmpty()
                    rollbackLastSecretaryTransaction(txId)
                    true
                }

                AtomicRollbackAndExplainEngine.ACTION_MUTE_TYPE -> {
                    val roomKey = contextJson?.optString("roomKey", "")?.trim().orEmpty()
                    val pkg = contextJson?.optString("packageName", "")?.trim().orEmpty()
                    when {
                        roomKey.isNotEmpty() -> {
                            ProactiveSecretaryEngine.reduceRoomFrequency(roomKey)
                            appendChatMessage(
                                LiveChatMessage(
                                    sender = ChatSender.AGENT,
                                    text = "'${roomKey}' 대화방의 선제안 알림 빈도를 낮췄어요.",
                                    subtitle = "Room Urgency Calibration",
                                    toolBadge = "SECRETARY",
                                    succeeded = true
                                )
                            )
                        }
                        pkg.isNotEmpty() -> {
                            ProactiveSecretaryEngine.togglePackageMute(pkg, true)
                            appendChatMessage(
                                LiveChatMessage(
                                    sender = ChatSender.AGENT,
                                    text = "선택한 앱(${pkg})의 선제안 감지를 음소거했어요.",
                                    subtitle = "Secretary Settings",
                                    toolBadge = "SECRETARY",
                                    succeeded = true
                                )
                            )
                        }
                    }
                    true
                }

                ShareToChecklistEngine.ACTION_SAVE_CHECKLIST -> {
                    val title = contextJson?.optString("title", "")?.trim()?.ifBlank { "공유 체크리스트" } ?: "공유 체크리스트"
                    val items = extractStringListFromContext(contextJson, "items")
                    val content = buildString {
                        appendLine(title)
                        if (items.isNotEmpty()) {
                            items.forEachIndexed { i, item -> appendLine("${i + 1}. $item") }
                        } else {
                            appendLine(contextJson?.optString("summary", "")?.trim().orEmpty())
                        }
                    }.trim()
                    val noteKey = "공유체크리스트_${title.take(18)}"
                    runCatching { resolvedScratchpad.put(noteKey, content) }
                    toolHarness.execute("save_note", JSONObject().put("key", noteKey).put("content", content), null)
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "공유된 내용으로 체크리스트를 노트에 저장했어요: ${title}",
                            subtitle = "Share-to-Checklist",
                            toolBadge = "SHARE",
                            succeeded = true
                        )
                    )
                    true
                }

                ShareToChecklistEngine.ACTION_ADD_WATCHER -> {
                    val title = contextJson?.optString("title", "")?.trim()?.ifBlank { "공유 링크 변동 감시" } ?: "공유 링크 변동 감시"
                    val target = contextJson?.optString("target", "")?.trim()
                        ?.ifBlank { contextJson.optString("url", "").trim() }
                        ?.ifBlank { "공유 콘텐츠" } ?: "공유 콘텐츠"
                    val keywords = extractStringListFromContext(contextJson, "keywords")
                        .ifEmpty { listOf("가격", "할인", "재입고", "예약") }
                    resolvedWatcherEngine.addRule(
                        title = title,
                        targetAppOrUrl = target,
                        matchKeywords = keywords,
                        mode = WatcherActionMode.NOTIFY_ONLY
                    )
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "공유 항목을 백그라운드 감시 규칙에 등록했어요: ${title}",
                            subtitle = "Proactive Watcher",
                            toolBadge = "WATCHER",
                            succeeded = true
                        )
                    )
                    true
                }

                ShareToChecklistEngine.ACTION_DISMISS_CHECKLIST -> {
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    true
                }

                UserDemonstrationRecorder.ACTION_RUN_TEMPLATE -> {
                    val templateId = contextJson?.optString("templateId", "")?.trim().orEmpty()
                    val pkg = contextJson?.optString("packageName", "")?.trim().orEmpty()
                    val paramsObj = contextJson?.optJSONObject("params")
                    val paramPairs = mutableListOf<String>()
                    paramsObj?.keys()?.forEach { k ->
                        val v = paramsObj.optString(k, "").trim()
                        if (v.isNotEmpty()) paramPairs.add("${k}=${v}")
                    }
                    val matchingRoutine = resolvedPersonalRoutines.routines.value.firstOrNull {
                        it.id == templateId || (pkg.isNotBlank() && it.goalPrompt.contains(pkg, ignoreCase = true))
                    }
                    val goal = buildString {
                        if (matchingRoutine != null) {
                            append(matchingRoutine.goalPrompt)
                        } else {
                            append("${pkg.ifBlank { "현재 앱" }} 시연 루틴(${templateId.ifBlank { "template" }}) 실행해줘")
                        }
                        if (paramPairs.isNotEmpty()) {
                            append(" · 입력값: ")
                            append(paramPairs.joinToString(", "))
                        }
                    }
                    if (surfaceId.isNotBlank()) A2uiSurfaceRegistry.deleteSurface(surfaceId)
                    startRun(goal)
                    true
                }

                LiveCallCompanionEngine.ACTION_DIAL -> {
                    val dialUri = contextJson?.optString("dialUri", "")?.trim()
                        ?.ifBlank { contextJson.optString("phoneNumber", "").trim() }
                        .orEmpty()
                    launchSafeDialer(dialUri)
                }

                LiveCallCompanionEngine.ACTION_SAVE_POST_CALL -> {
                    val memo = contextJson?.optString("memo", "")?.trim()
                        ?.ifBlank { contextJson.optString("postCallMemo", "").trim() }
                        .orEmpty()
                    val callee = contextJson?.optString("calleeLabel", "")?.trim()?.ifBlank { "통화 상대" } ?: "통화 상대"
                    if (memo.isNotBlank()) {
                        runCatching { resolvedScratchpad.put("통화메모_${callee.take(16)}", memo) }
                        ProactiveSecretaryEngine.ingestExternalSignal(
                            context = appCtx,
                            packageName = "com.hanson.callcompanion",
                            sender = callee,
                            text = memo,
                            appLabel = "전화 비서",
                            roomOrTitle = "${callee} 통화 메모",
                            canQuickReply = false
                        )
                        HansonSecretaryWidgetProvider.requestRefresh(appCtx)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = "통화 후 메모를 저장하고 후속 일정·할일을 추출했어요.",
                                subtitle = "Live Call Companion",
                                toolBadge = "CALL",
                                succeeded = true
                            )
                        )
                    } else {
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = "통화 후 확정된 시간·장소·주의사항을 한 줄로 입력해 주시면 바로 캘린더 제안으로 정리해 드릴게요.",
                                subtitle = "Live Call Companion",
                                toolBadge = "CALL",
                                succeeded = true
                            )
                        )
                    }
                    true
                }

                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Runs a proactive Chief-of-Staff context sweep (unanswered replyable messages + upcoming
     * calendar prep briefs), optionally enriching with zero-key Open-Meteo weather when requested
     * or opted in via `SecretarySettings.weatherPrepEnabled`.
     */
    fun runProactiveSecretaryScan(
        includeWeather: Boolean = false,
        lat: Double? = null,
        lon: Double? = null
    ): List<ProactiveProposal> {
        val appCtx = runCatching { context.applicationContext }.getOrNull() ?: context
        runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }

        val settings = ProactiveSecretaryEngine.settings.value
        val shouldFetchCustomWeather = includeWeather ||
            (settings.weatherPrepEnabled && lat != null && lon != null)
        val weatherBrief = if (shouldFetchCustomWeather) {
            runCatching {
                if (lat != null && lon != null) {
                    ProactiveContextApiConnector.fetchOpenMeteoWeatherBrief(lat = lat, lon = lon)
                } else {
                    ProactiveContextApiConnector.fetchOpenMeteoWeatherBrief()
                }
            }.getOrNull()
        } else {
            null
        }
        return ProactiveSecretaryEngine.scanProactiveContext(
            context = appCtx,
            weatherBriefOverride = weatherBrief
        )
    }

    /**
     * Submits a Human + AI Co-Drawing ("함께 그리기") query from either the in-app whiteboard
     * or the cross-app `DynamicCanvasOverlayService`, injecting normalized human stroke regions
     * and enabling AI `CODRAW:` overlay annotations in the live SSE response.
     */
    fun submitCoDrawingQuery(prompt: String) {
        val cleanPrompt = prompt.trim().ifBlank { "방금 표시한 영역을 분석하고 핵심 위치를 함께 표시해줘" }
        val activePkg = HansonAccessibilityService.engineState.value.activePackage
            .substringAfterLast('.')
            .ifBlank { "현재 화면" }
        val sketchSummary = CoDrawingCanvasEngine.buildSpatialPromptSummary(
            foregroundAppLabel = activePkg
        )
        val combinedPrompt = if (sketchSummary.isNotBlank()) {
            "$cleanPrompt\n\n$sketchSummary"
        } else {
            "$cleanPrompt\n\n[공동 드로잉 모드] 대상 화면: $activePkg. 필요시 `CODRAW: RING <x> <y> <r> <라벨>` 또는 `CODRAW: ARROW <x1> <y1> <x2> <y2> <라벨>`로 핵심 위치를 함께 표시하세요."
        }
        sendChatMessage(combinedPrompt)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable != null) {
                Log.e(TAG, message, throwable)
            } else {
                Log.e(TAG, message)
            }
        }
    }

    fun setUxMode(mode: UserExperienceMode) {
        prefs.edit().putString(KEY_UX_MODE, mode.id).apply()
        _uxMode.value = mode
        if (mode == UserExperienceMode.SIMPLE_HUMAN && _workMode.value != AgentWorkMode.AUTO) {
            pendingPlanGoal = null
            setWorkMode(AgentWorkMode.AUTO)
        }
    }

    /**
     * Alias for [setUxMode] guaranteeing [UserExperienceMode.SIMPLE_HUMAN] resets [workMode]
     * to [AgentWorkMode.AUTO] so users are never trapped in hidden PLAN mode.
     */
    fun setExperienceMode(mode: UserExperienceMode) = setUxMode(mode)

    private fun speakIfEnabled(text: String) {
        val shouldSpeak = LiveVoiceSessionManager.state.value.isActive || _config.value.ttsVoiceReply
        if (!shouldSpeak) return
        LiveVoiceSessionManager.speakConversationalFiller(text)
    }

    private fun persistSecretToVaultVerified(
        vaultKey: String,
        label: String,
        plaintext: String
    ): Result<Unit> = runCatching {
        resolvedVault.putSecret(
            rawKey = vaultKey,
            label = label,
            plaintext = plaintext,
            category = VaultSecretCategory.API_TOKEN
        )
        val readBack = resolvedVault.resolvePlaintextForBlindInjection(vaultKey)?.second?.trim()
        if (readBack != plaintext) {
            throw IllegalStateException("BlindCredentialVault read-back verification failed for '$vaultKey'")
        }
    }

    private fun resolvePrivateKeysFile(): File? {
        val dir = runCatching { context.filesDir }.getOrNull() ?: return null
        return File(dir, "hanson_api_keys.txt")
    }

    private fun loadConfig(): AgentEngineConfig {
        val rawSavedModel = prefs.getString(KEY_MODEL, GeminiMultimodalClient.DEFAULT_MODEL)
            ?: GeminiMultimodalClient.DEFAULT_MODEL
        val normalizedModel = GeminiMultimodalClient.normalize2026ModelName(rawSavedModel)
        if (normalizedModel != rawSavedModel) {
            prefs.edit().putString(KEY_MODEL, normalizedModel).apply()
        }
        var resolvedKey = runCatching {
            resolvedVault.resolvePlaintextForBlindInjection(VAULT_KEY_CLOUD_API_KEYS)?.second?.trim().orEmpty()
        }.onFailure { err ->
            logError("Failed to read cloud_api_keys from BlindCredentialVault during loadConfig", err)
        }.getOrDefault("")

        if (resolvedKey.isEmpty()) {
            // Migrate any legacy plaintext SharedPreferences API key into AES-256-GCM BlindCredentialVault.
            // ONLY delete KEY_API_KEY after putSecret() succeeds AND read-back matches verbatim!
            val legacyPlainKey = prefs.getString(KEY_API_KEY, "").orEmpty().trim()
            if (legacyPlainKey.isNotEmpty()) {
                resolvedKey = legacyPlainKey
                val migration = persistSecretToVaultVerified(
                    vaultKey = VAULT_KEY_CLOUD_API_KEYS,
                    label = "Cloud VLM API Keys",
                    plaintext = resolvedKey
                )
                if (migration.isSuccess) {
                    prefs.edit().remove(KEY_API_KEY).apply()
                } else {
                    logError(
                        "Failed to migrate SharedPreferences API key into BlindCredentialVault; preserving legacy key",
                        migration.exceptionOrNull()
                    )
                }
            }
        }

        if (resolvedKey.isEmpty()) {
            // Only read app-private filesDir (never world-readable /sdcard/Download or /data/local/tmp)
            val privateKeysFile = resolvePrivateKeysFile()
            val keysFileContent = runCatching {
                if (privateKeysFile != null && privateKeysFile.isFile && privateKeysFile.length() > 10L) {
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
                val migration = persistSecretToVaultVerified(
                    vaultKey = VAULT_KEY_CLOUD_API_KEYS,
                    label = "Cloud VLM API Keys",
                    plaintext = resolvedKey
                )
                if (migration.isSuccess) {
                    if (keysFileContent.isNotEmpty() && privateKeysFile != null && privateKeysFile.exists()) {
                        runCatching { privateKeysFile.delete() }.onFailure { err ->
                            logError("Failed to delete migrated legacy hanson_api_keys.txt", err)
                        }
                    }
                } else {
                    logError(
                        "Failed to migrate legacy hanson_api_keys.txt/env API key into BlindCredentialVault; preserving source",
                        migration.exceptionOrNull()
                    )
                }
            }
        }

        val resolvedBridgeToken = runCatching {
            resolvedVault.resolvePlaintextForBlindInjection(VAULT_KEY_CLI_BRIDGE_TOKEN)?.second?.trim().orEmpty()
        }.getOrDefault("")

        return AgentEngineConfig(
            apiKey = resolvedKey,
            modelName = normalizedModel,
            maxIterations = prefs.getInt(KEY_MAX_ITER, 15).coerceIn(3, 40),
            autoShowFloatingHud = prefs.getBoolean(KEY_AUTO_HUD, true),
            ttsVoiceReply = prefs.getBoolean(KEY_TTS_VOICE, true),
            customBaseUrl = prefs.getString(KEY_CUSTOM_BASE_URL, "").orEmpty().trim(),
            bridgeToken = resolvedBridgeToken
        )
    }

    fun updateConfig(
        apiKey: String? = null,
        modelName: String? = null,
        maxIterations: Int? = null,
        autoShowFloatingHud: Boolean? = null,
        ttsVoiceReply: Boolean? = null,
        customBaseUrl: String? = null,
        bridgeToken: String? = null
    ) {
        val current = _config.value
        val resolvedModel = GeminiMultimodalClient.normalize2026ModelName(modelName ?: current.modelName)
        val next = current.copy(
            apiKey = apiKey ?: current.apiKey,
            modelName = resolvedModel,
            maxIterations = (maxIterations ?: current.maxIterations).coerceIn(3, 40),
            autoShowFloatingHud = autoShowFloatingHud ?: current.autoShowFloatingHud,
            ttsVoiceReply = ttsVoiceReply ?: current.ttsVoiceReply,
            customBaseUrl = customBaseUrl?.trim() ?: current.customBaseUrl,
            bridgeToken = bridgeToken?.trim() ?: current.bridgeToken
        )

        var vaultPersistSucceeded = true
        var vaultPersistErrorMsg: String? = null
        val privateKeysFile = resolvePrivateKeysFile()

        if (apiKey != null) {
            val trimmedKey = next.apiKey.trim()
            if (trimmedKey.isNotEmpty()) {
                val persist = persistSecretToVaultVerified(
                    vaultKey = VAULT_KEY_CLOUD_API_KEYS,
                    label = "Cloud VLM API Keys",
                    plaintext = trimmedKey
                )
                if (persist.isSuccess) {
                    if (privateKeysFile != null && privateKeysFile.exists()) {
                        runCatching { privateKeysFile.delete() }.onFailure { err ->
                            logError("Failed to delete legacy hanson_api_keys.txt after vault save", err)
                        }
                    }
                } else {
                    vaultPersistSucceeded = false
                    val err = persist.exceptionOrNull()
                    logError("Failed to persist cloud_api_keys into BlindCredentialVault", err)
                    vaultPersistErrorMsg = "API 키 암호화 저장(Blind Vault)에 실패했습니다: ${err?.message?.take(90) ?: "알 수 없는 오류"}"
                }
            } else {
                runCatching {
                    resolvedVault.deleteSecret(VAULT_KEY_CLOUD_API_KEYS)
                }.onFailure { err ->
                    logError("Failed to delete cloud_api_keys from BlindCredentialVault", err)
                }
                if (privateKeysFile != null && privateKeysFile.exists()) {
                    runCatching { privateKeysFile.delete() }.onFailure { err ->
                        logError("Failed to delete legacy hanson_api_keys.txt on key clear", err)
                    }
                }
            }
        }

        if (bridgeToken != null) {
            val trimmedBridge = next.bridgeToken.trim()
            if (trimmedBridge.isNotEmpty()) {
                persistSecretToVaultVerified(
                    vaultKey = VAULT_KEY_CLI_BRIDGE_TOKEN,
                    label = "Laptop CLI Bridge Token",
                    plaintext = trimmedBridge
                ).onFailure { err ->
                    logError("Failed to persist cli_bridge_token into BlindCredentialVault", err)
                }
            } else {
                runCatching { resolvedVault.deleteSecret(VAULT_KEY_CLI_BRIDGE_TOKEN) }
            }
        }

        // Always reset dead-key / rate-limit caches & stale cloud errors whenever config updates
        GeminiMultimodalClient.Companion.MultiProviderApiKeySet.clearDeadKeys()
        GeminiMultimodalClient.lastCloudApiErrorReason = null
        aiInfrastructureRouter.clearLastCloudApiError()
        _lastCloudApiError.value = vaultPersistErrorMsg

        val editor = prefs.edit()
            .putString(KEY_MODEL, next.modelName)
            .putInt(KEY_MAX_ITER, next.maxIterations)
            .putBoolean(KEY_AUTO_HUD, next.autoShowFloatingHud)
            .putBoolean(KEY_TTS_VOICE, next.ttsVoiceReply)
            .putString(KEY_CUSTOM_BASE_URL, next.customBaseUrl)
        if (vaultPersistSucceeded) {
            editor.remove(KEY_API_KEY)
        }
        editor.apply()
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
     * One-Touch Environment Auto-Setup (P1-5):
     * Enables unrestricted capability governance, tries OS-level `self-heal` when available,
     * checks strict Accessibility + Overlay readiness, and cleanly opens Android System Settings
     * (`ACTION_ACCESSIBILITY_SETTINGS` / `ACTION_MANAGE_OVERLAY_PERMISSION`) whenever either is missing.
     */
    fun runOneTouchAutoSetup() {
        scope.launch {
            permissionHub.setUnrestrictedMode(true)
            aiInfrastructureRouter.refreshInfrastructureState()

            if (ToolSpecRegistry.find("os_cli_exec") != null) {
                val healOutcome = toolHarness.execute(
                    toolName = "os_cli_exec",
                    args = JSONObject().put("command", "self-heal"),
                    latestSnapshot = null
                )
                if (healOutcome.succeeded) {
                    delay(350L)
                }
            }

            permissionHub.refreshStatus()
            val setupResult = permissionHub.triggerOneTouchSetup(
                launcherContext = context,
                openMissingSettings = true
            )

            if (setupResult.accessibilityReady && setupResult.overlayReady) {
                if (_config.value.autoShowFloatingHud) {
                    FloatingHudService.startIfPermitted(context)
                }
                val aiState = aiInfrastructureRouter.state.value
                val readyMsg = if (aiState.googleAiCoreInstalled) {
                    "접근성 서비스, 오버레이 권한 및 온디바이스 AI 라우터가 모두 정상 연결되었어요."
                } else {
                    setupResult.messageKo
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = readyMsg,
                        subtitle = "1-Touch Auto-Setup",
                        toolBadge = "AUTO-SETUP",
                        succeeded = true
                    )
                )
                speakIfEnabled(readyMsg)
            } else {
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = setupResult.messageKo,
                        subtitle = "1-Touch Auto-Setup · 시스템 권한 필요",
                        toolBadge = "권한 설정 필요",
                        succeeded = false
                    )
                )
                speakIfEnabled(setupResult.messageKo)
            }
        }
    }

    /**
     * Handles ONLY explicit `/slash` self-configuration commands (`/setup`, `/memory`, `/theme`, `/model`, `/models`).
     * NEVER hijacks natural-language queries (e.g. `"메모리 반도체"`, `"라이트 모드 장점"`, `"모바일 AI 전망"`).
     */
    private fun tryHandleExplicitHansonSelfConfig(prompt: String): Boolean {
        val trimmed = prompt.trim()
        if (!trimmed.startsWith("/")) return false

        val lower = trimmed.lowercase(Locale.ROOT)
        val cmdToken = lower.substringBefore(' ').trim()
        val argRaw = trimmed.substringAfter(' ', "").trim()
        val argLower = argRaw.lowercase(Locale.ROOT)

        when (cmdToken) {
            "/models" -> {
                val urlMatch = Regex("(https?://[^\\s]+)").find(argRaw)?.value
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
            "/setup" -> {
                runOneTouchAutoSetup()
                return true
            }
            "/memory" -> {
                if (argLower.contains("clear") || argLower.contains("reset") || argLower.contains("초기화")) {
                    memoryManager.clearAllSemanticMemories()
                }
                runMemoryOptimization()
                return true
            }
            "/theme" -> {
                return when {
                    argLower in setOf("dark", "carbon", "다크", "카본") -> {
                        ObsidianColors.isDark = true
                        val reply = "다크 카본 무채색 모드로 바꿨어요."
                        appendChatMessage(
                            LiveChatMessage(sender = ChatSender.AGENT, text = reply, toolBadge = "CARBON")
                        )
                        speakIfEnabled(reply)
                        true
                    }
                    argLower in setOf("light", "paper", "라이트", "페이퍼") -> {
                        ObsidianColors.isDark = false
                        val reply = "밝은 페이퍼 무채색 모드로 바꿨어요."
                        appendChatMessage(
                            LiveChatMessage(sender = ChatSender.AGENT, text = reply, toolBadge = "PAPER")
                        )
                        speakIfEnabled(reply)
                        true
                    }
                    else -> {
                        ObsidianColors.isDark = !ObsidianColors.isDark
                        val reply = if (ObsidianColors.isDark) "다크 카본 무채색 모드로 바꿨어요." else "밝은 페이퍼 무채색 모드로 바꿨어요."
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = reply,
                                toolBadge = if (ObsidianColors.isDark) "CARBON" else "PAPER"
                            )
                        )
                        speakIfEnabled(reply)
                        true
                    }
                }
            }
            "/model" -> {
                if (argRaw.isBlank()) {
                    val cur = _config.value.modelName
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = "현재 선택된 모델은 `$cur` 입니다. `/model gemini-2.5-flash`, `/model auto`, `/model cli` 형식으로 변경할 수 있어요.",
                            toolBadge = "MODEL"
                        )
                    )
                    return true
                }
                val (modelId, label) = when {
                    argLower in setOf("auto", "multi", "자동", "멀티") -> {
                        aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                        GeminiMultimodalClient.AUTO_MULTI_MODEL to "멀티모델 자동 라우팅"
                    }
                    argLower in setOf("nano", "npu", "ondevice", "온디바이스") -> {
                        aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.S25_AICORE_NANO)
                        "gemma-4-26b-a4b-it" to "온디바이스 Gemini Nano NPU"
                    }
                    argLower in setOf("cli", "bridge", "노트북", "구독") -> {
                        aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                        "cli-subscription-bridge" to "노트북 CLI 구독 브리지"
                    }
                    else -> {
                        aiInfrastructureRouter.setPreferredTier(MobileAiRouteTier.AUTO_SMART)
                        val normalized = GeminiMultimodalClient.normalize2026ModelName(argRaw)
                        normalized to normalized
                    }
                }
                updateConfig(modelName = modelId)
                val reply = "추론 모델을 `$label` 모드로 전환했어요."
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
            else -> return false
        }
    }

    private fun resolveCliBridgeToken(cfg: AgentEngineConfig = _config.value): String {
        return cfg.bridgeToken.trim().ifBlank {
            runCatching { System.getenv("HANSON_CLI_BRIDGE_TOKEN") }.getOrNull()?.trim().orEmpty()
        }
    }

    private fun canonicalizeToolName(rawName: String): String = when (rawName.trim().lowercase(Locale.ROOT)) {
        "open_url" -> "open_link"
        "wait" -> "wait_for_delay"
        else -> rawName.trim()
    }

    internal fun isExplicitCraftAnythingRequest(gLower: String): Boolean {
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

    /**
     * Classifies whether a pure [pureUserGoal] is a knowledge / stock-price / news / weather /
     * web-search / conversational Q&A that should be answered directly via
     * [GeminiMultimodalClient.answerTextOrGroundedWebQuery] with ZERO accessibility dumps,
     * ZERO self-screenshots of Hanson's UI, and ZERO DuckDuckGo HTML scraping.
     */
    internal fun isPureTextOrGroundedWebQuery(pureUserGoal: String): Boolean {
        val g = pureUserGoal.trim()
        if (g.isEmpty()) return false
        val gLower = g.lowercase(Locale.ROOT)

        // 1. Slash commands, @mentions, or canvas JS error self-correction payloads
        if (g.startsWith("/") ||
            g.contains("[CANVAS_JS_ERROR]") ||
            Regex("(?:^|\\s)@[a-zA-Z0-9_:/.-]+").containsMatchIn(g)
        ) {
            return false
        }

        // 2. Explicit current-screen inspection cues -> needs live screen perception
        val explicitScreenCues = listOf(
            "지금 화면", "이 화면", "현재 화면", "화면 봐", "화면 좀", "화면에", "화면을",
            "화면 캡처", "스크린샷", "방금 화면", "보고 있는 화면", "화면 설명", "화면 브리핑",
            "on screen", "this screen", "current screen", "screenshot"
        )
        if (explicitScreenCues.any { gLower.contains(it) }) {
            return false
        }

        // 3. Single-word navigation & explicit UI gesture/input imperatives
        val normalizedWord = gLower.trimEnd('.', '!', '~', ' ')
        if (normalizedWord in setOf("뒤로", "뒤로가기", "홈", "홈으로", "back", "home", "recents")) {
            return false
        }
        val explicitGuiActionCues = listOf(
            "눌러", "클릭", "탭해", "탭 해", "터치해", "터치 해", "길게 눌러",
            "스크롤", "스와이프", "드래그", "끌어서", "끌어다", "슬라이더",
            "한 칸 내려", "위로 올려", "아래로 내려", "밑으로 내려", "내려줘", "올려줘",
            "입력해", "쳐줘", "검색창에", "선택해", "체크해", "뒤로 가", "뒤로가", "홈으로 가"
        )
        if (explicitGuiActionCues.any { gLower.contains(it) }) {
            return false
        }

        // 4. Explicit app-launch / app-switch / in-app interactive verbs
        val explicitAppLaunchOrControlCues = listOf(
            "열어줘", "열어 줘", "열어주세요", "열어봐", "열어서", "열고 ",
            "실행해", "실행 해", "실행시켜", "켜줘", "켜 줘", "켜서", "들어가",
            "띄워줘", "띄워 줘", "닫아줘", "닫아 줘", "종료해", "앱으로", "앱 열", "앱 켜",
            "launch ", "open app"
        )
        if (explicitAppLaunchOrControlCues.any { gLower.contains(it) }) {
            return false
        }
        if (Regex("(?:^|\\s)(?:open|launch)\\s+[a-z0-9가-힣._-]+").containsMatchIn(gLower)) {
            return false
        }
        val inAppTargetingRegex = Regex(
            "(?:유튜브|youtube|카카오톡|카톡|카카오맵|카카오택시|카카오t|배달의민족|배민|쿠팡이츠|쿠팡|요기요|당근마켓|당근|네이버지도|네이버카페|네이버|토스|인스타그램|인스타|크롬|chrome|텔레그램|슬랙|디스코드|퍼플렉시티|perplexity|챗지피티|chatgpt|빅스비|bixby|갤러리|카메라|시계|설정)\\s*(?:앱)?\\s*(?:에서|로|을|를)\\s*(?:검색|찾아|틀어|재생|주문|예약|결제|송금|이체|보내|열어|켜|실행|보여|확인|가줘|가자|이동)"
        )
        if (inAppTargetingRegex.containsMatchIn(gLower)) {
            return false
        }

        // 5. External communication / financial / transactional imperatives on device
        val transactionalOrCommCues = listOf(
            "보내줘", "보내 줘", "전송해", "전송 해", "답장해", "답장 해",
            "문자 보내", "카톡 보내", "전화 걸", "전화해", "전화 해", "통화해",
            "송금해", "송금 해", "이체해", "이체 해", "주문해", "주문 해", "예약해", "예약 해", "결제해", "결제 해"
        )
        if (transactionalOrCommCues.any { gLower.contains(it) }) {
            return false
        }

        // 6. Strict numeric alarm/timer & on-device OS/personal data commands
        if (IntentDecomposerAndRouter.decompose(rawPrompt = g, snapshot = null).fastDecision != null) {
            return false
        }
        val osAndDeviceCues = listOf(
            "알람 맞춰", "알람 설정", "알람 걸", "타이머 맞춰", "타이머 설정", "타이머 시작",
            "화면 밝기", "밝기 높", "밝기 낮", "밝기 줄", "밝기 올", "밝기 최", "밝기 설정", "밝기 맞춰",
            "볼륨", "음량", "소리 줄", "소리 키", "소리 크", "소리 작", "소리 안나", "소리 최대", "음소거",
            "방해금지", "손전등", "플래시", "후레쉬", "진동 모드", "무음 모드", "무음으로", "벨소리 모드",
            "와이파이 켜", "와이파이 꺼", "와이파이 설정", "블루투스 켜", "블루투스 꺼", "블루투스 설정",
            "블루투스 기기", "주변 기기", "스피커폰", "노트북으로",
            "내 폰에", "설치된 앱", "깔린 앱", "깔린 게임", "내 앱",
            "알림 확인", "알림 요약", "알림 왔", "알림 보여", "알림 읽어", "알림 오면", "카톡 왔어", "카톡 요약",
            "연락처", "전화번호 찾아", "전화번호 검색",
            "내 일정", "오늘 일정", "내일 일정", "캘린더", "일정 등록", "일정 추가", "일정 확인", "일정 알려",
            "메모 저장", "메모 읽어", "메모 확인", "노트에 저장", "노트 읽어", "클립보드",
            "감시 규칙", "워처 등록", "무슨 작업 중", "병렬 상태", "배터리 얼마나"
        )
        if (osAndDeviceCues.any { gLower.contains(it) }) {
            return false
        }

        // 7. Explicit in-app media playback, code canvas, visual/3D/MP4/GIF generation, or explicit clean-room APK analysis
        if (AgentMediaFileEngine.containsYouTubeUrlOrInAppRequest(g)) {
            return false
        }
        if ((gLower.contains("영상") || gLower.contains("뮤비") || gLower.contains("노래")) &&
            (gLower.contains("틀어") || gLower.contains("재생"))
        ) {
            return false
        }
        val explicitMediaOrCodeGenCues = listOf(
            "코드로 그려", "캔버스", "canvas", "svg 차트", "차트 그려", "그래프 그려",
            "이미지 생성", "그림 그려", "일러스트 그려", "포스터 만들어", "배경화면 생성",
            "3d 모델", "glb", "gltf", "mp4 생성", "동영상 만들어", "영상 생성", "gif 만들어", "움짤 만들어"
        )
        if (explicitMediaOrCodeGenCues.any { gLower.contains(it) }) {
            return false
        }
        if (isExplicitCraftAnythingRequest(gLower)) {
            return false
        }

        return true
    }

    /**
     * Builds the supplementary system context strictly SEPARATE from `userGoal` (P0-4).
     * NEVER concatenate bubbles, tips, scratchpad, or vault handles onto `userGoal`!
     */
    private fun buildSystemContextBlock(
        pureUserGoal: String,
        snapshot: AtomicSnapshot? = null,
        attachedContext: String = ""
    ): String = buildString {
        val bubblesBlock = resolvedMemoryBubbles.formatBubblesForSystemPrompt(resolvedVault)
        if (bubblesBlock.isNotBlank()) {
            appendLine(bubblesBlock)
        }
        val activePkg = snapshot?.activePackage.orEmpty()
        val appTip = snapshot?.let {
            AppSpecificTipBank.getTipForPackageOrGoal(it.activePackage, pureUserGoal)
        }.orEmpty()
        if (appTip.isNotBlank()) {
            appendLine("[App Tip] $appTip")
        }
        val activePlaybook = AppDetailPlaybookRegistry.findByPackage(activePkg)
        if (activePlaybook != null) {
            val guidance = activePlaybook.guiGuidanceKo.trim()
            if (guidance.isNotBlank() && guidance != appTip.trim()) {
                appendLine("[App Playbook (${activePlaybook.appLabelKo})] $guidance")
            }
            if (activePlaybook.readOnlyAdvisoryOnly) {
                appendLine("[App Playbook Safety (${activePlaybook.appLabelKo})] 읽기 전용 금융·결제 앱: 자동 클릭·송금·결제 실행 금지.")
            }
        }
        val notetakerBlock = resolvedScratchpad.toPromptBlock(maxChars = 420)
        if (notetakerBlock.isNotBlank()) {
            appendLine(notetakerBlock)
        }
        val vaultHandlesSummary = resolvedVault.listHandles()
            .joinToString(", ") { "${it.handle}(${it.category.labelKo})" }
        if (vaultHandlesSummary.isNotBlank()) {
            appendLine("[Blind Vault Handles Available] $vaultHandlesSummary")
        }
        if (attachedContext.isNotBlank()) {
            appendLine(resolvedVault.redactSensitiveText(attachedContext))
        }
    }.trim()

    private fun buildOneTapFixHintKo(errReason: String, cfg: AgentEngineConfig): String {
        val lower = errReason.lowercase(Locale.ROOT)
        return when {
            lower.contains("402") || lower.contains("크레딧") ->
                "해결 방법: 선불 크레딧이 소진되었어요. 상단 설정에서 무료 Gemini AI Studio API 키(AIza...)를 입력하거나 노트북 CLI 브리지(:18889)로 전환해 주세요."
            lower.contains("401") || lower.contains("403") || lower.contains("api 키") || lower.contains("형식") ->
                "해결 방법: 상단 설정에서 유효한 Gemini·Claude·OpenAI API 키를 다시 입력한 뒤 '연결 테스트'를 탭해 주세요."
            lower.contains("429") || lower.contains("한도") || lower.contains("쿨다운") ->
                "해결 방법: 요청 한도에 잠시 도달했어요. 약 45초 후 다시 시도하거나 설정에서 `gemini-2.5-flash-lite` 모델로 전환해 주세요."
            lower.contains("404") || lower.contains("모델") ->
                "해결 방법: 선택한 모델(`${cfg.modelName}`)이 지원되지 않아요. 설정에서 기본 모델(`gemini-2.5-flash`)로 변경해 주세요."
            lower.contains("18889") || lower.contains("브리지") || lower.contains("미연결") || lower.contains("설정되지") ->
                "해결 방법: 상단 설정에서 Gemini API 키(AIza...)를 등록하거나, 노트북 CLI 브리지(:18889 + `adb reverse tcp:18889 tcp:18889`)를 연결한 뒤 다시 시도해 주세요."
            else ->
                "해결 방법: 인터넷 연결 및 상단 설정의 API 키/모델 상태를 확인한 뒤 다시 시도해 주세요."
        }
    }

    private enum class SafetyGateEvaluationOutcome {
        PROCEED,
        STOPPED_OR_DENIED,
        TAKEOVER_RESUMED_REOBSERVE
    }

    /**
     * Centralized mandatory fail-closed safety gate (B3):
     * 1. Fails CLOSED if [toolName] (after canonical alias mapping) is missing from [ToolSpecRegistry].
     * 2. Always evaluates [FinancialAndTakeoverGuard.assessBeforeAction] (CAPTCHA/2FA/Secure takeover,
     *    financial commit, price drift, sensitive vault handle — even when unrestrictedAutonomousMode == true).
     * 3. Always evaluates [ToolSafetyGate.evaluate].
     */
    private suspend fun evaluateMandatorySafetyGates(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot?,
        allowInteractivePrompt: Boolean
    ): Pair<SafetyGateEvaluationOutcome, String?> {
        val canonical = canonicalizeToolName(toolName)
        val spec = ToolSpecRegistry.find(canonical)
        if (spec == null) {
            val denyMsg = "등록되지 않은 도구('$toolName')는 보안 정책에 따라 실행이 차단되었어요."
            logError("Fail-closed safety gate blocked unregistered tool '$toolName'")
            if (allowInteractivePrompt) {
                stopRun(denyMsg)
            }
            return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to denyMsg
        }

        val effectiveSnap = snapshot ?: AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2340,
            activePackage = context.packageName,
            activeWindowTitle = "Hanson Board"
        )
        val activeEpoch = currentControlEpoch.get()
        val consumedSnapshot = consumedExternalApprovalReceipts.toSet()
        val hsAssessment = FinancialAndTakeoverGuard.assessBeforeAction(
            toolName = canonical,
            args = args,
            snapshot = effectiveSnap,
            lastApprovedAmount = lastApprovedAmountText,
            expectedControlEpoch = activeEpoch,
            consumedReceiptIds = consumedSnapshot
        )
        if (hsAssessment.externalApprovalValidated && !hsAssessment.externalApprovalReceiptId.isNullOrBlank()) {
            consumedExternalApprovalReceipts.add(hsAssessment.externalApprovalReceiptId)
            resolvedVault.recordAuditEvent(
                action = "EXTERNAL_APPROVAL_BOUND",
                handle = args.optString("handle", canonical).ifBlank { canonical },
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
            if (!allowInteractivePrompt) {
                val msg = "보안/본인확인 화면(${hsAssessment.kind.name})이 감지되어 백그라운드 실행을 차단했어요. 직접 확인해 주세요."
                return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to msg
            }
            val tkDeferred = CompletableDeferred<Boolean>()
            takeoverDeferred = tkDeferred
            val takeoverState = HumanTakeoverState(
                kind = hsAssessment.kind,
                titleKo = hsAssessment.targetLabel.ifBlank { "사용자 직접 인증 필요" },
                reasonKo = hsAssessment.reasonKo,
                activePackage = effectiveSnap.activePackage
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
                val cancelMsg = "사용자 직접 확인 단계에서 작업을 취소했어요."
                stopRun(cancelMsg)
                return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to cancelMsg
            }
            loopBreaker.reset()
            return SafetyGateEvaluationOutcome.TAKEOVER_RESUMED_REOBSERVE to null
        }

        // Mandatory Financial / Price-Drift or Sensitive Vault Handle approval (EVEN when unrestrictedAutonomousMode == true!)
        val sensitiveVaultSummary = if (canonical == "vault_blind_inject") {
            val handleArg = args.optString("handle", "").trim()
            resolvedVault.getHandleSummary(handleArg)?.takeIf { it.category.alwaysRequireApproval }
        } else {
            null
        }
        val sensitiveVaultNeedsApproval = sensitiveVaultSummary != null && !hsAssessment.externalApprovalValidated

        if (hsAssessment.kind == HighStakesKind.FINANCIAL_COMMIT ||
            hsAssessment.kind == HighStakesKind.PRICE_DRIFT_ALERT ||
            sensitiveVaultNeedsApproval
        ) {
            val reason = sensitiveVaultSummary?.let { s ->
                "[민감 볼트(${s.category.labelKo}) 주입 승인] ${s.handle} (${s.label}) 값을 현재 입력란에 안전 주입할까요?"
            } ?: hsAssessment.reasonKo

            if (!allowInteractivePrompt) {
                val msg = "고위험 결제·민감 볼트 작업은 백그라운드에서 자동 실행할 수 없어요: $reason"
                return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to msg
            }

            val deferred = CompletableDeferred<Boolean>()
            approvalDeferred = deferred
            lastBoundApprovalReceiptId = null
            lastBoundControlEpoch = null
            _currentSession.update {
                it.copy(
                    status = AgentRunStatus.AWAITING_APPROVAL,
                    pendingApproval = PendingApprovalRequest(
                        toolSpec = spec,
                        args = args,
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
                    text = "$reason 승인 버튼을 누르거나 '승인'/'네'라고 정확히 답하시면 실행할게요.",
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
            syncSessionStoreStatus(if (approved) "ACTING" else "STOPPED", spec.name, hasPendingApproval = false)
            _currentSession.update { it.copy(pendingApproval = null) }
            if (!approved) {
                val rejectMsg = "고위험 결제·민감 작업 승인을 거절하여 실행하지 않았어요."
                stopRun(rejectMsg)
                return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to rejectMsg
            }
            if (hsAssessment.detectedAmountText.isNotBlank()) {
                lastApprovedAmountText = hsAssessment.detectedAmountText
            }
            lastBoundApprovalReceiptId?.let { boundId ->
                args.put("external_approval_receipt_id", boundId)
            }
            lastBoundControlEpoch?.let { boundEpoch ->
                args.put("control_epoch", boundEpoch)
            }
            return SafetyGateEvaluationOutcome.PROCEED to null
        }

        // Standard ToolSafetyGate governance check
        when (val gate = ToolSafetyGate.evaluate(spec, args, permissionHub)) {
            is SafetyGateDecision.Allow -> return SafetyGateEvaluationOutcome.PROCEED to null
            is SafetyGateDecision.Deny -> {
                val msg = "보안 설정으로 인해 '${spec.name}' 작업이 제한되어 있어요. 권한 설정을 확인해 주세요."
                if (allowInteractivePrompt) {
                    stopRun(msg)
                }
                return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to msg
            }
            is SafetyGateDecision.AskUser -> {
                if (!allowInteractivePrompt) {
                    val msg = "${gate.reasonKo}\n백그라운드 자동 실행을 중단했어요. 포그라운드에서 승인 후 실행해 주세요."
                    return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to msg
                }
                val deferred = CompletableDeferred<Boolean>()
                approvalDeferred = deferred
                _currentSession.update {
                    it.copy(
                        status = AgentRunStatus.AWAITING_APPROVAL,
                        pendingApproval = PendingApprovalRequest(
                            toolSpec = spec,
                            args = args,
                            reasonKo = gate.reasonKo
                        )
                    )
                }
                val askMsg = "${gate.reasonKo} 진행할까요? 승인 버튼을 누르거나 '네' 또는 '승인'이라고 답하시면 실행할게요."
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = askMsg,
                        toolBadge = "승인 확인"
                    )
                )
                speakIfEnabled(askMsg)
                FloatingHudService.updateHudStatus("승인 대기 중", "'승인' 또는 버튼 선택", true)
                FloatingHudService.updateApprovalPrompt(true, gate.reasonKo)
                syncSessionStoreStatus("APPROVAL", gate.reasonKo.take(60), hasPendingApproval = true)
                val approved = deferred.await()
                approvalDeferred = null
                FloatingHudService.updateApprovalPrompt(false)
                syncSessionStoreStatus(if (approved) "ACTING" else "STOPPED", spec.name, hasPendingApproval = false)
                _currentSession.update { it.copy(pendingApproval = null) }
                if (!approved) {
                    val cancelMsg = "네, 실행하지 않고 취소했어요."
                    stopRun(cancelMsg)
                    return SafetyGateEvaluationOutcome.STOPPED_OR_DENIED to cancelMsg
                }
                return SafetyGateEvaluationOutcome.PROCEED to null
            }
        }
    }

    /**
     * Executes a validated tool call while ensuring `browser_use` receives live API credentials/model/bridgeToken
     * for 2026 native web grounding without ever embedding secrets inside `args`.
     */
    private fun executeToolWithConfig(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot?,
        cfg: AgentEngineConfig
    ): ToolExecutionOutcome {
        val canonical = canonicalizeToolName(toolName)
        val crew = activeCrewAssignment
        if (crew != null && crew.explicitMention && !HansonCrewRouter.isToolAllowed(crew.role, canonical)) {
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "[Hanson Crew · ${crew.role.labelKo}(${crew.role.mentionKo})] 허용 도구 범위 밖의 동작('${canonical}')은 실행하지 않습니다."
            )
        }
        if (canonical == "browser_use") {
            val rawOutcome = universalNexusEngine.executeMobileBrowserUse(
                args = args,
                apiKey = cfg.apiKey,
                modelName = cfg.modelName,
                customBaseUrl = cfg.customBaseUrl,
                bridgeToken = resolveCliBridgeToken(cfg)
            )
            val redactedText = resolvedVault.redactSensitiveText(rawOutcome.outputText)
            val redactedReceipt = rawOutcome.receipt?.let { r ->
                r.copy(
                    summaryKo = resolvedVault.redactSensitiveText(r.summaryKo),
                    detailKo = resolvedVault.redactSensitiveText(r.detailKo),
                    undoHintKo = resolvedVault.redactSensitiveText(r.undoHintKo)
                )
            }
            return rawOutcome.copy(outputText = redactedText, receipt = redactedReceipt)
        }
        return toolHarness.execute(canonical, args, snapshot)
    }

    private suspend fun executeBackgroundToolSafely(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot?,
        cfg: AgentEngineConfig
    ): ToolExecutionOutcome {
        val (gateOutcome, denyReason) = evaluateMandatorySafetyGates(
            toolName = toolName,
            args = args,
            snapshot = snapshot,
            allowInteractivePrompt = false
        )
        if (gateOutcome != SafetyGateEvaluationOutcome.PROCEED) {
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = denyReason ?: "보안 정책에 따라 '$toolName' 백그라운드 실행이 차단되었어요."
            )
        }
        return executeToolWithConfig(toolName, args, snapshot, cfg)
    }

    /**
     * A1 / C2 Text-First & Native Grounded Web Q&A fast lane.
     * ZERO accessibility dumps, ZERO self-screenshots of Hanson's UI, ZERO DuckDuckGo HTML scraping.
     */
    private fun launchTextFirstGroundedQa(
        pureUserGoal: String,
        attachedContext: String = ""
    ) {
        val cfg = _config.value
        val safeGoal = resolvedVault.redactSensitiveText(pureUserGoal)
        val systemContext = buildSystemContextBlock(
            pureUserGoal = safeGoal,
            snapshot = null,
            attachedContext = attachedContext
        )
        val recentChatHistory: List<Pair<String, String>> = _chatMessages.value
            .filter { it.sender == ChatSender.USER || it.sender == ChatSender.AGENT }
            .takeLast(12)
            .map { m ->
                val role = if (m.sender == ChatSender.USER) "user" else "model"
                role to m.text
            }
        val bridgeToken = resolveCliBridgeToken(cfg)

        val isGuiBusy = activeJob?.isActive == true &&
            (_currentSession.value.status == AgentRunStatus.OBSERVING ||
                _currentSession.value.status == AgentRunStatus.THINKING ||
                _currentSession.value.status == AgentRunStatus.ACTING)

        val taskId = "qa_${System.currentTimeMillis()}_${(100..999).random()}"
        registerParallelSlot(ParallelTaskSlot(taskId = taskId, lane = "QA", goal = pureUserGoal))

        if (!isGuiBusy) {
            activeJob?.cancel()
            val qaSession = AgentRunSession(
                goal = pureUserGoal,
                status = AgentRunStatus.THINKING,
                maxIterations = 1,
                subGoals = listOf(SubGoalItem("질문 분석 및 실시간 웹 검색 응답 생성", SubGoalStatus.IN_PROGRESS))
            )
            _currentSession.value = qaSession
        }

        val qaJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                DynamicUiEventBus.beginStream(sourceLabel = "실시간 SSE 브리핑")
                val answer = multimodalClient.answerTextOrGroundedWebQuery(
                    userGoal = safeGoal,
                    systemContext = systemContext,
                    apiKey = cfg.apiKey,
                    modelName = cfg.modelName,
                    customBaseUrl = cfg.customBaseUrl,
                    chatHistory = recentChatHistory,
                    enableWebSearch = true,
                    bridgeToken = bridgeToken,
                    onTextDelta = { chunk -> DynamicUiEventBus.appendStreamingDelta(chunk) }
                )

                if (answer.succeeded && answer.answerKo.isNotBlank()) {
                    _lastCloudApiError.value = null
                    aiInfrastructureRouter.clearLastCloudApiError()

                    val verifiedCitations = answer.citations
                        .filter { (_, uri) -> uri.trim().startsWith("https://", ignoreCase = true) }
                        .distinctBy { (_, uri) -> uri.trim() }
                        .take(8)

                    val combinedWithCitations = buildString {
                        append(answer.answerKo.trim())
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

                    val badge = answer.providerBadge.ifBlank { "GEMINI-WEB" }
                    val incrementalOut = IncrementalBoardStreamCompiler.compileIncremental(
                        accumulatedRaw = combinedWithCitations
                    )
                    val explicitSpeak = incrementalOut.explicitSpeak
                    val cleanVisualAnswer = incrementalOut.script.visualBodyText
                    val extractedInlineCitations = HumanSummaryAndCardBuilder.extractVerifiedHttpsCitations(cleanVisualAnswer)
                    val totalCitations = maxOf(verifiedCitations.size, extractedInlineCitations.size)
                    val rawAgentMsg = LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = cleanVisualAnswer,
                        subtitle = if (totalCitations > 0) "실시간 SSE 웹 검색 · 출처 ${totalCitations}건" else "실시간 SSE AI 응답",
                        toolBadge = badge,
                        succeeded = true
                    )
                    val formattedMsg = if (totalCitations > 0) {
                        HumanSummaryAndCardBuilder.transformGroundedWebResponse(
                            msg = rawAgentMsg,
                            cleanedRaw = cleanVisualAnswer,
                            specialistRoleKo = "실시간 네이티브 SSE 웹 그라운딩 Q&A"
                        )
                    } else {
                        rawAgentMsg
                    }
                    val compiledBoardScript = DynamicUiEventBus.completeStreamWithScript(
                        rawText = combinedWithCitations,
                        citations = formattedMsg.structuredCard?.items.orEmpty(),
                        sourceLabel = badge
                    )
                    val spokenToPlay = explicitSpeak
                        ?: compiledBoardScript.combinedSpokenText.ifBlank { formattedMsg.text }

                    if (!isGuiBusy) {
                        _currentSession.update { prev ->
                            val completed = prev.copy(
                                status = AgentRunStatus.COMPLETED,
                                subGoals = prev.subGoals.map { it.copy(status = SubGoalStatus.COMPLETED) },
                                finalSummary = formattedMsg.text,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(completed)
                            completed
                        }
                        syncSessionStoreStatus("DONE", formattedMsg.text.take(80), hasPendingApproval = false)
                    }
                    appendChatMessage(formattedMsg)
                    speakIfEnabled(spokenToPlay)
                } else {
                    val errReason = answer.errorReason?.takeIf { it.isNotBlank() }
                        ?: GeminiMultimodalClient.lastCloudApiErrorReason?.takeIf { it.isNotBlank() }
                        ?: "AI API 키 또는 노트북 CLI 브리지(:18889)가 연결되지 않았습니다."
                    DynamicUiEventBus.failStream(errReason)
                    _lastCloudApiError.value = errReason
                    aiInfrastructureRouter.recordCloudApiError(errReason, badge = "AI 연결 오류")

                    val fixHint = buildOneTapFixHintKo(errReason, cfg)
                    val honestErrorText = buildString {
                        appendLine("AI 응답을 가져오지 못했어요.")
                        appendLine("원인: $errReason")
                        append(fixHint)
                    }.trim()

                    if (!isGuiBusy) {
                        _currentSession.update { prev ->
                            val failed = prev.copy(
                                status = AgentRunStatus.ERROR,
                                finalSummary = honestErrorText,
                                finishedAtMs = System.currentTimeMillis()
                            )
                            archiveSession(failed)
                            failed
                        }
                        syncSessionStoreStatus("ERROR", errReason.take(80), hasPendingApproval = false)
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = honestErrorText,
                            subtitle = "AI 연결 오류 · 설정 또는 브리지 확인 필요",
                            toolBadge = "AI 연결 오류",
                            succeeded = false
                        )
                    )
                    speakIfEnabled("AI 연결 오류가 발생했어요. 설정에서 API 키 또는 브리지 연결을 확인해 주세요.")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val errReason = "AI 요청 처리 중 예외가 발생했습니다: ${t.message?.take(120) ?: t.javaClass.simpleName}"
                logError(errReason, t)
                _lastCloudApiError.value = errReason
                aiInfrastructureRouter.recordCloudApiError(errReason, badge = "AI 연결 오류")
                if (!isGuiBusy) {
                    _currentSession.update { prev ->
                        val failed = prev.copy(
                            status = AgentRunStatus.ERROR,
                            finalSummary = errReason,
                            finishedAtMs = System.currentTimeMillis()
                        )
                        archiveSession(failed)
                        failed
                    }
                    syncSessionStoreStatus("ERROR", errReason.take(80), hasPendingApproval = false)
                }
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "AI 응답 처리 중 오류가 발생했어요.\n원인: $errReason",
                        subtitle = "AI 실행 예외",
                        toolBadge = "AI 연결 오류",
                        succeeded = false
                    )
                )
            } finally {
                concurrentBackgroundJobs.remove(taskId)
                unregisterParallelSlot(taskId)
                if (!isGuiBusy) {
                    val curStatus = _currentSession.value.status
                    if (curStatus == AgentRunStatus.THINKING || curStatus == AgentRunStatus.OBSERVING || curStatus == AgentRunStatus.ACTING) {
                        _currentSession.update {
                            it.copy(status = AgentRunStatus.STOPPED, finishedAtMs = System.currentTimeMillis())
                        }
                    }
                    val nextQueuedGui = pendingGuiGoals.poll()
                    if (!nextQueuedGui.isNullOrBlank()) {
                        startGuiLaneLoop(nextQueuedGui, pendingGuiAttachedContexts.remove(nextQueuedGui).orEmpty())
                    }
                }
            }
        }

        if (isGuiBusy) {
            concurrentBackgroundJobs[taskId] = qaJob
        } else {
            activeJob = qaJob
        }
        qaJob.start()
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
        val hasForegroundAppLaunchOrInteract = gLower.contains("열어서") || gLower.contains("열고 ") ||
            gLower.contains("켜서") || gLower.contains("앱 열") || gLower.contains("앱 켜") ||
            gLower.contains("클릭") || gLower.contains("눌러") || gLower.contains("들어가") || gLower.contains("틀어")
        if (hasForegroundAppLaunchOrInteract) {
            return false
        }

        val isGameOrAppInventory = (
            gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린 앱") ||
                gLower.contains("깔린 게임") ||
                (gLower.contains("게임") && (gLower.contains("깔려") || gLower.contains("깔린") || gLower.contains("설치") || gLower.contains("내 폰") || gLower.contains("폰에")))
            ) && (gLower.contains("찾아") || gLower.contains("뭐 있") || gLower.contains("무슨") || gLower.contains("알려") || gLower.contains("확인") || gLower.contains("목록") || gLower.contains("스캔"))
        val isCraftBlueprint = isExplicitCraftAnythingRequest(gLower)
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

    private fun launchParallelBackgroundTask(bgGoal: String, attachedContext: String = "") {
        val taskId = "bg_${System.currentTimeMillis()}_${(100..999).random()}"
        val slot = ParallelTaskSlot(taskId = taskId, lane = "BG", goal = bgGoal)
        registerParallelSlot(slot)

        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val cfg = _config.value
                val bridgeToken = resolveCliBridgeToken(cfg)
                val gLower = bgGoal.lowercase(Locale.ROOT).trim()
                val snapshot = HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = false)

                // Check Universal Omni-Nexus Fast-Path first (Native Web Grounding, Code Canvas, Image/Media Gen, Cross-Device, Pipelines)
                val nexusRes = universalNexusEngine.tryExecuteUniversalNexus(
                    prompt = bgGoal,
                    latestSnapshot = snapshot,
                    apiKey = cfg.apiKey,
                    modelName = cfg.modelName,
                    customBaseUrl = cfg.customBaseUrl,
                    bridgeToken = bridgeToken
                )
                if (nexusRes != null && nexusRes.handled) {
                    if (nexusRes.hasError) {
                        GeminiMultimodalClient.lastCloudApiErrorReason?.takeIf { it.isNotBlank() }?.let {
                            _lastCloudApiError.value = it
                        }
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = nexusRes.summaryKo,
                            subtitle = "Universal Omni-Nexus · ${nexusRes.badge}",
                            toolBadge = nexusRes.badge,
                            succeeded = !nexusRes.hasError,
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
                    (gLower.contains("내 앱") || gLower.contains("설치된 앱") || gLower.contains("깔린") ||
                        (gLower.contains("게임") && (gLower.contains("깔려") || gLower.contains("깔린") || gLower.contains("설치") || gLower.contains("내 폰") || gLower.contains("폰에")))) -> {
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
                    isExplicitCraftAnythingRequest(gLower) -> {
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
                        val outcome = executeBackgroundToolSafely(
                            toolName = "reply_notification",
                            args = JSONObject().put("target", "").put("message", replyMsg),
                            snapshot = snapshot,
                            cfg = cfg
                        )
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · RemoteInput",
                                toolBadge = if (outcome.succeeded) "병렬완료 · 즉시답장" else "승인/권한 필요",
                                succeeded = outcome.succeeded
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    (gLower.contains("알림") || (gLower.contains("카톡") && (gLower.contains("왔어") || gLower.contains("요약")))) -> {
                        val outcome = executeBackgroundToolSafely(
                            toolName = "list_notifications",
                            args = JSONObject().put("only_replyable", false).put("limit", 8),
                            snapshot = snapshot,
                            cfg = cfg
                        )
                        val notifSummary = if (!outcome.succeeded) {
                            outcome.outputText
                        } else if (outcome.outputText.contains("없습니다") || outcome.outputText.isBlank()) {
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
                                toolBadge = if (outcome.succeeded) "병렬완료 · 알림브리핑" else "알림 조회 제한",
                                succeeded = outcome.succeeded
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
                        val outcome = executeBackgroundToolSafely("control_device_setting", settingArgs, snapshot, cfg)
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · OS Fast-Lane",
                                toolBadge = if (outcome.succeeded) "병렬완료 · OS제어" else "OS 제어 제한",
                                succeeded = outcome.succeeded
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    // 5. Headless Contacts / Calendar / Notes / OS CLI / General Background Task Execution
                    gLower.contains("연락처") || gLower.contains("전화번호") -> {
                        val q = bgGoal.replace("연락처", "").replace("전화번호", "").replace("찾아줘", "").replace("찾아", "").replace("알려줘", "").trim()
                        val outcome = executeBackgroundToolSafely("search_contacts", JSONObject().put("query", q), snapshot, cfg)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 연락처 조회",
                                toolBadge = if (outcome.succeeded) "병렬완료 · BG연락처" else "연락처 조회 제한",
                                succeeded = outcome.succeeded
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    gLower.contains("일정") || gLower.contains("캘린더") -> {
                        val outcome = executeBackgroundToolSafely("manage_calendar", JSONObject().put("action", "list"), snapshot, cfg)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 캘린더 조회",
                                toolBadge = if (outcome.succeeded) "병렬완료 · BG캘린더" else "캘린더 조회 제한",
                                succeeded = outcome.succeeded
                            )
                        )
                        speakIfEnabled(outcome.outputText)
                    }
                    gLower.contains("메모") || gLower.contains("노트") -> {
                        val outcome = if (gLower.contains("저장") || gLower.contains("기억")) {
                            executeBackgroundToolSafely("save_note", JSONObject().put("key", "bg_note").put("content", bgGoal), snapshot, cfg)
                        } else {
                            executeBackgroundToolSafely("read_note", JSONObject().put("key", ""), snapshot, cfg)
                        }
                        recordReceipt(outcome.receipt)
                        appendChatMessage(
                            LiveChatMessage(
                                sender = ChatSender.AGENT,
                                text = outcome.outputText,
                                subtitle = "병렬 백그라운드 · 메모리 노트",
                                toolBadge = if (outcome.succeeded) "병렬완료 · BG노트" else "노트 접근 제한",
                                succeeded = outcome.succeeded
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
                        val safeGoal = resolvedVault.redactSensitiveText(bgGoal)
                        val systemContext = buildSystemContextBlock(
                            pureUserGoal = safeGoal,
                            snapshot = snap,
                            attachedContext = attachedContext
                        )
                        val decision = aiInfrastructureRouter.routeAndDecide(
                            apiKey = cfg.apiKey,
                            modelName = cfg.modelName,
                            userGoal = safeGoal,
                            iteration = 1,
                            snapshot = snap,
                            historySteps = emptyList(),
                            previousPlan = emptyList(),
                            chatHistory = _chatMessages.value,
                            customBaseUrl = cfg.customBaseUrl,
                            systemContext = systemContext,
                            bridgeToken = bridgeToken
                        )
                        val routerState = aiInfrastructureRouter.state.value
                        _lastCloudApiError.value = routerState.lastCloudApiError
                        val isCloudError = decision.toolArgs.optBoolean("is_cloud_error", false) || !routerState.lastDecisionSucceeded

                        if (isCloudError) {
                            val errBadge = decision.toolArgs.optString("error_badge").ifBlank { "AI 연결 오류" }
                            val errText = decision.toolArgs.optString("question").ifBlank {
                                decision.spokenReply.ifBlank { "AI 연결 오류로 백그라운드 작업을 완료하지 못했어요." }
                            }
                            appendChatMessage(
                                LiveChatMessage(
                                    sender = ChatSender.AGENT,
                                    text = errText,
                                    subtitle = "병렬 백그라운드 · $errBadge",
                                    toolBadge = errBadge,
                                    succeeded = false
                                )
                            )
                            return@launch
                        }

                        val headlessSafeTools = setOf(
                            "list_notifications", "reply_notification", "control_device_setting",
                            "search_contacts", "manage_calendar", "set_alarm", "set_timer", "save_note", "read_note",
                            "os_cli_exec", "reverse_engineer_app", "learn_app_skill", "render_generative_ui",
                            "browser_use", "cross_device_control", "draw_with_code", "generate_visual_media",
                            "workspace_list", "workspace_read", "workspace_write",
                            "workspace_edit", "workspace_search", "workspace_exec",
                            "vault_list_handles", "watcher_manage", "a2a_negotiate_schedule", "scratchpad_note"
                        )
                        val canonicalTool = canonicalizeToolName(decision.toolName)
                        var bgSucceeded = true
                        var bgStructuredCard: StructuredUiCard? = null
                        val resultText = if (canonicalTool in headlessSafeTools ||
                            (canonicalTool == "manage_app" && decision.toolArgs.optString("action") != "launch")
                        ) {
                            val outcome = executeBackgroundToolSafely(canonicalTool, decision.toolArgs, snap, cfg)
                            recordReceipt(outcome.receipt)
                            bgSucceeded = outcome.succeeded
                            bgStructuredCard = outcome.structuredCard
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
                                toolBadge = if (bgSucceeded) "병렬완료 · BG실행" else "BG 실행 제한",
                                succeeded = bgSucceeded,
                                structuredCard = bgStructuredCard
                            )
                        )
                        speakIfEnabled(resultText)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logError("Background parallel task failed", t)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = "백그라운드 작업 처리 중 오류가 발생했어요: ${t.message?.take(100) ?: t.javaClass.simpleName}",
                        subtitle = "병렬 백그라운드 예외",
                        toolBadge = "BG 오류",
                        succeeded = false
                    )
                )
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

        // B3: Fail-closed approval check BEFORE anything else when awaiting user approval.
        // Default MUST be REJECT; ONLY approve on exact affirmative match; NEVER auto-set alwaysAllowCapability = true!
        if (_currentSession.value.status == AgentRunStatus.AWAITING_APPROVAL && approvalDeferred != null) {
            val normalizedReply = lower.trim().trimEnd('.', '!', '~', ' ')
            val approve = STRICT_APPROVAL_REGEX.matches(normalizedReply)
            resolvePendingApproval(approved = approve, alwaysAllowCapability = false)
            return
        }

        // Fail-closed human takeover resume check
        if (_currentSession.value.status == AgentRunStatus.AWAITING_TAKEOVER && takeoverDeferred != null) {
            val normalizedReply = lower.trim().trimEnd('.', '!', '~', ' ')
            val resume = STRICT_TAKEOVER_RESUME_REGEX.matches(normalizedReply)
            resolveHumanTakeover(completedByHuman = resume)
            return
        }

        // Explicit /slash self-config commands only (never hijacks natural language)
        if (tryHandleExplicitHansonSelfConfig(trimmedGoal)) {
            return
        }

        // Hanson Crew explicit role routing (@비서 / @리서처 / @예약 / @쇼핑 / @개발)
        val crewAssignment = HansonCrewRouter.route(trimmedGoal)
        val effectiveGoalAfterCrew = if (crewAssignment.explicitMention) {
            activeCrewAssignment = crewAssignment
            _activeCrewRole.value = crewAssignment.role
            crewAssignment.cleanedGoal.ifBlank { trimmedGoal }
        } else {
            activeCrewAssignment = null
            _activeCrewRole.value = null
            trimmedGoal
        }

        val parsedInput = ComposerCommandParser.parse(effectiveGoalAfterCrew, _workMode.value)
        if (tryHandleStudioSlashCommand(parsedInput)) {
            return
        }

        if (parsedInput.slashCommand == null) {
            val learnedBubble = resolvedMemoryBubbles.extractAndLearnFromUserUtterance(effectiveGoalAfterCrew, resolvedVault)
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

        val effectiveWorkMode = when {
            parsedInput.resolvedMode != null -> parsedInput.resolvedMode
            _uxMode.value == UserExperienceMode.SIMPLE_HUMAN && _workMode.value == AgentWorkMode.PLAN -> AgentWorkMode.AUTO
            else -> _workMode.value
        }
        if (parsedInput.slashCommand in listOf("/auto", "/plan", "/build")) {
            setWorkMode(effectiveWorkMode)
        } else if (_uxMode.value == UserExperienceMode.SIMPLE_HUMAN && _workMode.value == AgentWorkMode.PLAN) {
            setWorkMode(AgentWorkMode.AUTO)
        }

        val baseGoal = if (parsedInput.slashCommand != null || parsedInput.mentions.isNotEmpty()) {
            parsedInput.cleanPrompt.ifBlank { effectiveGoalAfterCrew }
        } else {
            effectiveGoalAfterCrew
        }

        if (effectiveWorkMode == AgentWorkMode.PLAN) {
            enterPlanModeAndEmitBlueprint(goal = baseGoal, parsed = parsedInput)
            return
        }

        // P0-4: Keep pureUserGoal strictly clean; place @mention attachments & Crew directive into separate systemContext!
        val mentionCtx = if (parsedInput.mentions.isNotEmpty()) {
            buildAttachedContextForMentions(parsedInput)
        } else {
            ""
        }
        val attachedMentionContext = listOfNotNull(
            crewAssignment.takeIf { it.explicitMention }?.systemDirectiveKo,
            mentionCtx.takeIf { it.isNotBlank() }
        ).joinToString("\n")
        val statusPreview = if (crewAssignment.explicitMention) {
            "[${crewAssignment.role.mentionKo}] ${baseGoal.take(70)}"
        } else {
            baseGoal.take(80)
        }
        syncSessionStoreStatus(statusBadge = "RUNNING", previewLine = statusPreview, hasPendingApproval = false)

        // Explicit BACKGROUND dispatch: force all sub-goals into the non-blocking Background Lane!
        if (effectiveLane == ExecutionLaneMode.BACKGROUND) {
            val subGoals = splitCompoundParallelGoals(baseGoal)
            subGoals.forEach { launchParallelBackgroundTask(it, attachedMentionContext) }
            return
        }

        // Explicit FOREGROUND dispatch: force into the Foreground GUI Lane!
        if (effectiveLane == ExecutionLaneMode.FOREGROUND) {
            dispatchToForegroundGuiLane(listOf(baseGoal), attachedMentionContext)
            return
        }

        // AUTO smart lane splitting:
        val subGoals = splitCompoundParallelGoals(baseGoal)

        // A1 / C2 Text-First & Native Grounded Web Q&A Path (ZERO accessibility dumps, ZERO screenshots, ZERO DuckDuckGo!)
        if (subGoals.size == 1 && parsedInput.mentions.isEmpty()) {
            val singleGoal = subGoals.first()
            val (sgLane, sgClean) = extractExplicitLanePrefix(singleGoal)
            if (sgLane == ExecutionLaneMode.AUTO && isPureTextOrGroundedWebQuery(sgClean)) {
                launchTextFirstGroundedQa(sgClean)
                return
            }
        }

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
            launchParallelBackgroundTask(bgGoal, attachedMentionContext)
        }

        if (guiSubGoals.isEmpty()) {
            return
        }

        dispatchToForegroundGuiLane(guiSubGoals, attachedMentionContext)
    }

    private fun dispatchToForegroundGuiLane(
        guiSubGoals: List<String>,
        attachedContext: String = ""
    ) {
        if (guiSubGoals.isEmpty()) return
        val primaryGuiGoal = guiSubGoals.first()
        val extraGuiGoals = guiSubGoals.drop(1)
        extraGuiGoals.forEach { queued ->
            if (attachedContext.isNotBlank()) {
                pendingGuiAttachedContexts[queued] = attachedContext
            }
            pendingGuiGoals.offer(queued)
        }

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
                if (attachedContext.isNotBlank()) {
                    pendingGuiAttachedContexts[primaryGuiGoal] = attachedContext
                }
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

        startGuiLaneLoop(primaryGuiGoal, attachedContext)
    }

    private fun startGuiLaneLoop(
        guiGoal: String,
        attachedContext: String = ""
    ) {
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
                // Do NOT hold HUD FLAG_NOT_TOUCHABLE across the loop — AgentToolExecutionHarness
                // toggles FloatingHudService.setPassThroughTemporarily strictly during gesture dispatch
                // so the emergency Stop button stays touchable throughout the run!
                runLoop(guiGoal, cfg, attachedContext)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val errSummary = "화면 제어 루프 중 예기치 않은 오류가 발생했어요: ${t.message?.take(120) ?: t.javaClass.simpleName}"
                logError("GUI loop crashed unexpectedly", t)
                _currentSession.update { prev ->
                    val failed = prev.copy(
                        status = AgentRunStatus.ERROR,
                        pendingApproval = null,
                        pendingTakeover = null,
                        finalSummary = errSummary,
                        finishedAtMs = System.currentTimeMillis()
                    )
                    archiveSession(failed)
                    failed
                }
                syncSessionStoreStatus("ERROR", errSummary.take(80), hasPendingApproval = false)
                appendChatMessage(
                    LiveChatMessage(
                        sender = ChatSender.AGENT,
                        text = errSummary,
                        subtitle = "GUI 실행 오류",
                        toolBadge = "실행 오류",
                        succeeded = false
                    )
                )
                FloatingHudService.updateHudStatus("실행 오류", errSummary.take(28), false)
            } finally {
                FloatingHudService.setTouchPassthrough(false)
                FloatingHudService.updateApprovalPrompt(false)
                unregisterParallelSlot(guiTaskId)
                // Guarantee busy state NEVER freezes forever if coroutine exited abnormally or via cancellation
                val curStatus = _currentSession.value.status
                if (curStatus == AgentRunStatus.OBSERVING ||
                    curStatus == AgentRunStatus.THINKING ||
                    curStatus == AgentRunStatus.ACTING ||
                    curStatus == AgentRunStatus.AWAITING_APPROVAL ||
                    curStatus == AgentRunStatus.AWAITING_TAKEOVER
                ) {
                    _currentSession.update { prev ->
                        prev.copy(
                            status = AgentRunStatus.STOPPED,
                            pendingApproval = null,
                            pendingTakeover = null,
                            finishedAtMs = System.currentTimeMillis()
                        )
                    }
                    FloatingHudService.updateHudStatus("소통 및 제어 대기", "대기 중", false)
                }
                val nextQueuedGui = pendingGuiGoals.poll()
                if (!nextQueuedGui.isNullOrBlank()) {
                    val nextAttached = pendingGuiAttachedContexts.remove(nextQueuedGui).orEmpty()
                    startGuiLaneLoop(nextQueuedGui, nextAttached)
                }
            }
        }
    }

    fun stopRun(reason: String = "네, 현재 작업을 멈췄어요. 이어서 무엇을 도와드릴까요?") {
        FloatingHudService.setTouchPassthrough(false)
        FloatingHudService.updateApprovalPrompt(false)
        pendingGuiGoals.clear()
        pendingGuiAttachedContexts.clear()
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
            val outcome = executeBackgroundToolSafely(
                toolName = target.undoToolName,
                args = undoArgs,
                snapshot = snap,
                cfg = _config.value
            )
            val confirmKo = if (outcome.succeeded) {
                "↩ '${target.summaryKo}' 작업을 되돌렸어요."
            } else {
                "↩ '${target.summaryKo}' 되돌리기가 제한되었어요: ${outcome.outputText}"
            }
            appendChatMessage(
                LiveChatMessage(
                    sender = ChatSender.AGENT,
                    text = confirmKo,
                    subtitle = "1-Tap Undo Receipt",
                    toolBadge = if (outcome.succeeded) "되돌리기 완료" else "되돌리기 제한",
                    succeeded = outcome.succeeded
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

    private suspend fun runLoop(
        initialGoal: String,
        cfg: AgentEngineConfig,
        attachedContext: String = ""
    ) {
        val (fallbackW, fallbackH) = HierarchyDumper.getScreenDimensions(context)
        val bridgeToken = resolveCliBridgeToken(cfg)
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
            // LAYER 0-REFLEX: STRICT ANCHORED FAST-LANE (single-word external nav / explicit numeric alarm & timer)
            // Runs mandatory fail-closed FinancialAndTakeoverGuard + ToolSafetyGate BEFORE execution!
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
                    val (fastGate, _) = evaluateMandatorySafetyGates(
                        toolName = fastDec.toolName,
                        args = fastDec.toolArgs,
                        snapshot = reflexSnap,
                        allowInteractivePrompt = true
                    )
                    if (fastGate == SafetyGateEvaluationOutcome.STOPPED_OR_DENIED) {
                        return
                    }
                    if (fastGate == SafetyGateEvaluationOutcome.TAKEOVER_RESUMED_REOBSERVE) {
                        continue
                    }

                    _currentSession.update { it.copy(status = AgentRunStatus.ACTING, subGoals = fastDec.subGoals) }
                    val outcome = executeToolWithConfig(
                        toolName = fastDec.toolName,
                        args = fastDec.toolArgs,
                        snapshot = reflexSnap,
                        cfg = cfg
                    )
                    recordReceipt(outcome.receipt)
                    val conciseSummary = if (outcome.succeeded) {
                        fastDec.spokenReply.ifBlank { outcome.outputText }
                    } else {
                        outcome.outputText.ifBlank { fastDec.spokenReply }
                    }
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
                    _currentSession.update { prev ->
                        val completed = prev.copy(
                            status = if (outcome.succeeded) AgentRunStatus.COMPLETED else AgentRunStatus.ERROR,
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
                            succeeded = outcome.succeeded,
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

            // P0-4: Estimate complexity strictly on pure currentGoal (never polluted by systemContext/bubbles)
            val taskComplexity = aiInfrastructureRouter.estimateTaskComplexity(currentGoal, baseSnapshot)
            _currentSession.update { it.copy(status = AgentRunStatus.THINKING) }
            FloatingHudService.updateHudStatus("관측 · 추론 [${taskComplexity.name}]", currentGoal.take(28), true)

            val prevSteps = _currentSession.value.steps

            // =========================================================================
            // LAYER 0: UNIVERSAL OMNI-NEXUS (Zero-GUI Teleport, Cross-App Pipe, Native Web Grounding, Code Canvas, Image Gen, Cross-Device)
            // =========================================================================
            if (iter == 1) {
                val nexusHit = universalNexusEngine.tryExecuteUniversalNexus(
                    prompt = currentGoal,
                    latestSnapshot = baseSnapshot,
                    apiKey = cfg.apiKey,
                    modelName = cfg.modelName,
                    customBaseUrl = cfg.customBaseUrl,
                    bridgeToken = bridgeToken
                )
                if (nexusHit != null && nexusHit.handled) {
                    if (nexusHit.hasError) {
                        GeminiMultimodalClient.lastCloudApiErrorReason?.takeIf { it.isNotBlank() }?.let {
                            _lastCloudApiError.value = it
                        }
                    }
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = nexusHit.summaryKo,
                            subtitle = "Universal Omni-Nexus · ${nexusHit.badge}",
                            toolBadge = nexusHit.badge,
                            succeeded = !nexusHit.hasError,
                            mediaBlock = nexusHit.mediaBlock
                        )
                    )
                    speakIfEnabled(nexusHit.summaryKo)
                    if (!nexusHit.followUpGuiGoal.isNullOrBlank()) {
                        currentGoal = nexusHit.followUpGuiGoal
                        delay(450L)
                        continue
                    } else {
                        _currentSession.update { prev ->
                            val completed = prev.copy(
                                status = if (nexusHit.hasError) AgentRunStatus.ERROR else AgentRunStatus.COMPLETED,
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
            // LAYER 1 & 2: SET-OF-MARKS DELIBERATIVE VLM ROUTER
            // Guarantees live screenshot + Set-of-Marks `[index]` visual grounding and strict
            // `userGoal` vs `systemContext` separation on every VLM turn!
            // =========================================================================
            val snapshot: AtomicSnapshot =
                HansonAccessibilityService.instance?.captureSnapshotNow(includeScreenshot = true) ?: baseSnapshot

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

            // P0-4: Strict userGoal vs systemContext separation (NEVER concatenate bubbles/tips/notes onto userGoal!)
            val pureSafeGoal = resolvedVault.redactSensitiveText(currentGoal)
            val systemContext = buildSystemContextBlock(
                pureUserGoal = pureSafeGoal,
                snapshot = snapshot,
                attachedContext = attachedContext
            )

            val rawDecision = aiInfrastructureRouter.routeAndDecide(
                apiKey = cfg.apiKey,
                modelName = cfg.modelName,
                userGoal = pureSafeGoal,
                iteration = iter,
                snapshot = snapshot,
                historySteps = prevSteps,
                previousPlan = _currentSession.value.subGoals,
                chatHistory = _chatMessages.value,
                customBaseUrl = cfg.customBaseUrl,
                systemContext = systemContext,
                bridgeToken = bridgeToken
            )
            val routerState = aiInfrastructureRouter.state.value
            _lastCloudApiError.value = routerState.lastCloudApiError
            val backendBadge = if (routerState.lastDecisionSucceeded) {
                "S2-${routerState.lastUsedBackendBadge}"
            } else {
                routerState.lastUsedBackendBadge
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
                        toolBadge = backendBadge,
                        succeeded = true
                    )
                )
                speakIfEnabled(summary)
                FloatingHudService.updateHudStatus("소통 및 제어 대기", summary.take(30), false)
                return
            }

            // 2. Conversational Clarification (`ask_user`) OR Honest Cloud Connection Failure
            if (decision.toolName == "ask_user") {
                val isCloudError = decision.toolArgs.optBoolean("is_cloud_error", false) || !routerState.lastDecisionSucceeded
                val question = decision.toolArgs.optString("question").ifBlank {
                    decision.spokenReply.ifBlank { "어떤 항목을 선택할지 말씀해 주세요." }
                }
                if (isCloudError) {
                    val errReason = decision.toolArgs.optString("error_reason").ifBlank {
                        routerState.lastCloudApiError ?: GeminiMultimodalClient.lastCloudApiErrorReason ?: question
                    }
                    val errBadge = decision.toolArgs.optString("error_badge").ifBlank { "AI 연결 오류" }
                    _lastCloudApiError.value = errReason
                    _currentSession.update { prev ->
                        val failed = prev.copy(
                            status = AgentRunStatus.ERROR,
                            finalSummary = question,
                            finishedAtMs = System.currentTimeMillis()
                        )
                        archiveSession(failed)
                        failed
                    }
                    syncSessionStoreStatus("ERROR", errReason.take(80), hasPendingApproval = false)
                    appendChatMessage(
                        LiveChatMessage(
                            sender = ChatSender.AGENT,
                            text = question,
                            subtitle = errBadge,
                            toolBadge = errBadge,
                            succeeded = false
                        )
                    )
                    speakIfEnabled("AI 연결 오류가 발생했어요. 설정에서 API 키 또는 브리지 연결을 확인해 주세요.")
                    FloatingHudService.updateHudStatus("AI 연결 오류", errBadge.take(30), false)
                    return
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
                        toolBadge = "대화 확인",
                        succeeded = true
                    )
                )
                speakIfEnabled(question)
                FloatingHudService.updateHudStatus("대답을 듣고 있어요", question.take(30), false)
                return
            }

            // 3. Advisory Safety Alignment check (never mutates VLM click/input coordinates)
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

            // 3A. Mandatory Fail-Closed Registry + Financial/Takeover + ToolSafetyGate Check
            val (gateOutcome, _) = evaluateMandatorySafetyGates(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                snapshot = snapshot,
                allowInteractivePrompt = true
            )
            if (gateOutcome == SafetyGateEvaluationOutcome.STOPPED_OR_DENIED) {
                return
            }
            if (gateOutcome == SafetyGateEvaluationOutcome.TAKEOVER_RESUMED_REOBSERVE) {
                continue
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

            val outcome = executeToolWithConfig(
                toolName = effDecision.toolName,
                args = effDecision.toolArgs,
                snapshot = snapshot,
                cfg = cfg
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
                        toolBadge = "${effDecision.toolName} · $backendBadge",
                        succeeded = outcome.succeeded,
                        structuredCard = outcome.structuredCard
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

    private fun buildAttachedContextForMentions(
        parsed: ParsedComposerInput,
        snapshot: AtomicSnapshot? = null
    ): String {
        if (parsed.mentions.isEmpty()) return ""
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
        if (blocks.isEmpty()) return ""
        return resolvedVault.redactSensitiveText(
            "<attached_context>\n${blocks.joinToString("\n\n")}\n</attached_context>"
        )
    }

    fun buildContextualPrompt(
        baseGoal: String,
        parsed: ParsedComposerInput,
        snapshot: AtomicSnapshot? = null
    ): String {
        if (parsed.mentions.isEmpty()) return baseGoal
        val safeBase = resolvedVault.redactSensitiveText(baseGoal)
        val attachedBlock = buildAttachedContextForMentions(parsed, snapshot)
        if (attachedBlock.isEmpty()) return safeBase
        val assembled = buildString {
            append(safeBase.ifBlank { "첨부된 컨텍스트를 분석하고 처리해 주세요." })
            append("\n\n")
            append(attachedBlock)
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
