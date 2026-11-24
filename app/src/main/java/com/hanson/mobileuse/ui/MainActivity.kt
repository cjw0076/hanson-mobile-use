package com.hanson.mobileuse.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.hanson.mobileuse.accessibility.GestureController
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.BatchDigestA2uiBuilder
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.ClipboardQuickSuggestion
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.MobileUseAgentEngine
import com.hanson.mobileuse.agent.PermissionHubManager
import com.hanson.mobileuse.agent.PrivacyAndQuotaProofLedger
import com.hanson.mobileuse.agent.ProactiveProposalToA2uiBridge
import com.hanson.mobileuse.agent.ProactiveSecretaryEngine
import com.hanson.mobileuse.agent.QuickIntentKind
import com.hanson.mobileuse.agent.ShareToChecklistEngine
import com.hanson.mobileuse.agent.SmartClipboardAndShareIntentDetector
import com.hanson.mobileuse.agent.UserDemonstrationRecorder
import com.hanson.mobileuse.agent.VaultSecretCategory
import com.hanson.mobileuse.agent.WatcherActionMode
import com.hanson.mobileuse.notification.NotificationStormShieldEngine
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.service.HansonForegroundService
import com.hanson.mobileuse.service.HansonSecretaryWidgetProvider
import com.hanson.mobileuse.service.HansonSystemNexusReceiverActivity
import com.hanson.mobileuse.service.HansonVirtualDisplayManager
import org.json.JSONArray
import org.json.JSONObject
import com.hanson.mobileuse.ui.screens.CommandCenterScreen
import com.hanson.mobileuse.ui.screens.InspectorBridgeScreen
import com.hanson.mobileuse.ui.screens.PermissionsHubScreen
import com.hanson.mobileuse.ui.screens.SettingsScreen
import com.hanson.mobileuse.ui.screens.SkillsAndReceiptsScreen
import com.hanson.mobileuse.ui.theme.HansonObsidianTheme
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 5 Navigation tabs matching Figma UI prototype (figma-ui-prototype/index.html lines 598-620).
 *
 * 1. COMMAND_CENTER (커맨드, ⚡) -> CommandCenterScreen
 * 2. PERMISSIONS_HUB (권한 허브, 🛡️) -> PermissionsHubScreen
 * 3. INSPECTOR_BRIDGE (인스펙터, 🌲) -> InspectorBridgeScreen
 * 4. SKILLS_RECEIPTS (스킬·영수증, ✨) -> SkillsAndReceiptsScreen
 * 5. SETTINGS (설정, ⚙️) -> SettingsScreen
 */
enum class MainTab(
    val titleKo: String,
    val iconEmoji: String
) {
    COMMAND_CENTER("커맨드", "⚡"),
    PERMISSIONS_HUB("권한 허브", "🛡️"),
    INSPECTOR_BRIDGE("인스펙터", "🌲"),
    SKILLS_RECEIPTS("스킬·영수증", "✨"),
    SETTINGS("설정", "⚙️");

    val labelKo: String get() = titleKo
    val route: String get() = name.lowercase()
}

typealias HansonNavTab = MainTab
typealias MainNavigationTab = MainTab

/**
 * Single-Surface Ahaboard Achromatic Activity.
 * - Restores 5-tab NavigationBar per Figma specification.
 * - Mounts all 5 Compose screens: CommandCenter, PermissionsHub, InspectorBridge, SkillsAndReceipts, Settings.
 * - Preserves state across tab switches using [rememberSaveableStateHolder].
 * - Automatically hides the external Floating HUD overlay while `MainActivity` is in the foreground.
 * - Dynamically hides the bottom navigation bar when the IME soft keyboard is open.
 */
class MainActivity : ComponentActivity() {

    private lateinit var permissionHub: PermissionHubManager
    private lateinit var workspaceEngine: MobileWorkspaceEngine
    private lateinit var sessionStore: AgentSessionStore
    private lateinit var agentEngine: MobileUseAgentEngine

    private val clipboardSuggestionsFlow = MutableStateFlow<List<ClipboardQuickSuggestion>>(emptyList())
    private var lastSeenClipboardText: String = ""
    private var dismissedClipboardText: String = ""

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionHub.refreshStatus()
    }

    private var pendingStartVoiceAfterPermission = false

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionHub.refreshStatus()
        val shouldStart = pendingStartVoiceAfterPermission
        pendingStartVoiceAfterPermission = false
        if (granted && shouldStart) {
            LiveVoiceSessionManager.configureGeminiLiveCredentials(agentEngine.config.value.apiKey)
            LiveVoiceSessionManager.startLiveSession(
                context = applicationContext,
                apiKey = agentEngine.config.value.apiKey
            )
        } else if (!granted && shouldStart) {
            Toast.makeText(this, "음성 대화를 사용하려면 마이크 권한이 필요해요.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        permissionHub = HansonRuntimeHolder.getPermissionHub(applicationContext)
        workspaceEngine = HansonRuntimeHolder.getWorkspaceEngine(applicationContext)
        sessionStore = HansonRuntimeHolder.getSessionStore(applicationContext)
        agentEngine = HansonRuntimeHolder.getAgentEngine(applicationContext)

        HansonForegroundService.start(applicationContext)
        FloatingHudService.setMainActivityForeground(applicationContext, true)
        FloatingHudService.onApprovalDecision = { approved ->
            agentEngine.resolvePendingApproval(approved, alwaysAllowCapability = false)
        }
        handleExternalNexusIntent(intent)

        setContent {
            HansonObsidianTheme {
                var currentTab by rememberSaveable { mutableStateOf(MainTab.COMMAND_CENTER) }
                val saveableStateHolder = rememberSaveableStateHolder()

                BackHandler(enabled = currentTab != MainTab.COMMAND_CENTER) {
                    currentTab = MainTab.COMMAND_CENTER
                }

                val a11yState by HansonAccessibilityService.engineState.collectAsState()
                val permissionState by permissionHub.state.collectAsState()
                val session by agentEngine.currentSession.collectAsState()
                val chatMessages by agentEngine.chatMessages.collectAsState()
                val allReceipts by agentEngine.allReceipts.collectAsState()
                val config by agentEngine.config.collectAsState()
                val memoryState by agentEngine.memoryManager.state.collectAsState()
                val routerState by agentEngine.aiInfrastructureRouter.state.collectAsState()
                val recentNotifications by RemoteInputSessionCache.recentNotifications.collectAsState()
                val isHudShowing by FloatingHudService.isShowing.collectAsState()
                val isDaemonRunning by HansonForegroundService.isRunning.collectAsState()
                val workspaceState by workspaceEngine.state.collectAsState()
                val sessionStoreState by sessionStore.state.collectAsState()
                val workMode by agentEngine.workMode.collectAsState()
                val vaultHandlesFlow = remember(agentEngine) {
                    runCatching { agentEngine.resolvedVault.handles }
                        .getOrElse { MutableStateFlow(emptyList()) }
                }
                val vaultHandles by vaultHandlesFlow.collectAsState()
                val watcherRules by agentEngine.resolvedWatcherEngine.rules.collectAsState()
                val scratchpadEntries by agentEngine.resolvedScratchpad.entries.collectAsState()
                val uxMode by agentEngine.uxMode.collectAsState()
                val memoryBubbles by agentEngine.resolvedMemoryBubbles.bubbles.collectAsState()
                val personalRoutines by agentEngine.resolvedPersonalRoutines.routines.collectAsState()
                val isRecordingDemo by UserDemonstrationRecorder.isRecording.collectAsState()
                val recordedSteps by UserDemonstrationRecorder.recordedSteps.collectAsState()
                val clipboardSuggestions by clipboardSuggestionsFlow.collectAsState()
                val cloudSyncState by agentEngine.resolvedCloudSync.state.collectAsState()
                val lastCloudApiError by agentEngine.lastCloudApiError.collectAsState()
                val proactiveProposals by ProactiveSecretaryEngine.proposals.collectAsState()
                val secretarySettings by ProactiveSecretaryEngine.settings.collectAsState()
                val privacyProof by PrivacyAndQuotaProofLedger.snapshot.collectAsState()
                val vdisplaySnapshot by HansonVirtualDisplayManager.uiSnapshot.collectAsState()
                val stormShieldConfig by NotificationStormShieldEngine.config.collectAsState()
                val latestReversibleReceipt = remember(allReceipts) {
                    allReceipts.firstOrNull { it.canOneTapUndo }
                }

                LaunchedEffect(proactiveProposals, privacyProof.localZeroCostScans) {
                    HansonSecretaryWidgetProvider.requestRefresh(applicationContext)
                }

                LaunchedEffect(Unit) {
                    refreshClipboardSuggestions()
                }
                LaunchedEffect(agentEngine) {
                    var lastDispatchedText = ""
                    var lastDispatchedAtMs = 0L
                    LiveVoiceSessionManager.userTranscriptFlow.collect { spoken ->
                        val clean = spoken.trim()
                        if (clean.isEmpty()) return@collect
                        val now = System.currentTimeMillis()
                        val alreadySentByEngine = agentEngine.chatMessages.value.lastOrNull {
                            it.sender == ChatSender.USER
                        }?.let { lastUser ->
                            lastUser.text.trim() == clean && (now - lastUser.timestampMs) < 1_200L
                        } == true
                        if (!alreadySentByEngine && (clean != lastDispatchedText || now - lastDispatchedAtMs > 1_200L)) {
                            lastDispatchedText = clean
                            lastDispatchedAtMs = now
                            agentEngine.sendChatMessage(clean)
                        }
                    }
                }
                LaunchedEffect(isRecordingDemo, recordedSteps.size) {
                    FloatingHudService.updateRecordingBadge(isRecordingDemo, recordedSteps.size)
                }
                LaunchedEffect(latestReversibleReceipt?.id, latestReversibleReceipt?.undone) {
                    val reversible = latestReversibleReceipt
                    if (reversible != null && reversible.canOneTapUndo && !reversible.undone) {
                        FloatingHudService.updateUndoAvailability(true, reversible.undoHintKo.ifBlank { "되돌리기" })
                    } else {
                        FloatingHudService.updateUndoAvailability(false, "")
                    }
                }

                val startDemoRecording: () -> Unit = {
                    UserDemonstrationRecorder.startRecording()
                    FloatingHudService.updateRecordingBadge(true, 0)
                    Toast.makeText(
                        this@MainActivity,
                        "시연 녹화를 시작했어요. 원하는 앱에서 동작을 보여준 뒤 저장하세요.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val finishDemoRecording: (String) -> Unit = { title ->
                    val saved = agentEngine.finishDemonstrationAndSaveRoutine(title)
                    FloatingHudService.updateRecordingBadge(false, 0)
                    Toast.makeText(
                        this@MainActivity,
                        if (saved != null) "'${saved.titleKo}' 루틴으로 저장했어요." else "기록된 단계가 없어 녹화를 마쳤어요.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val cancelDemoRecording: () -> Unit = {
                    UserDemonstrationRecorder.stopAndCompile()
                    FloatingHudService.updateRecordingBadge(false, 0)
                    Toast.makeText(this@MainActivity, "시연 녹화를 취소했어요.", Toast.LENGTH_SHORT).show()
                }
                val undoReceiptAction: (String) -> Unit = { receiptId ->
                    val ok = agentEngine.executeOneTapUndo(receiptId)
                    Toast.makeText(
                        this@MainActivity,
                        if (ok) "방금 실행한 작업을 되돌렸어요." else "되돌릴 수 있는 작업이 없어요.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val approveAndDialAction: (String, String, String, String) -> Unit =
                    { calleeLabel, phoneNumber, purposeKo, scriptText ->
                        val cleanCallee = calleeLabel.ifBlank { "통화 대상" }
                        val cleanPhone = phoneNumber.trim()
                        val noteKey = "call_script_${cleanCallee.take(16)}"
                        agentEngine.resolvedScratchpad.put(
                            key = noteKey,
                            value = buildString {
                                appendLine("대상: $cleanCallee ($cleanPhone)")
                                if (purposeKo.isNotBlank()) appendLine("목적: $purposeKo")
                                if (scriptText.isNotBlank()) appendLine(scriptText)
                            }.trim()
                        )
                        val dialTarget = cleanPhone.filter { it.isDigit() || it == '+' || it == '-' }
                        val dialUri = if (dialTarget.isNotBlank()) {
                            Uri.parse("tel:${Uri.encode(dialTarget)}")
                        } else {
                            Uri.parse("tel:")
                        }
                        runCatching {
                            startActivity(Intent(Intent.ACTION_DIAL, dialUri))
                        }
                        Toast.makeText(
                            this@MainActivity,
                            "통화 안내 문구를 메모에 저장하고 전화 앱을 열었어요.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                val density = LocalDensity.current
                val imeBottomPx = WindowInsets.ime.getBottom(density)
                val isKeyboardOpen = imeBottomPx > 0

                Scaffold(
                    containerColor = ObsidianColors.Background,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (!isKeyboardOpen) {
                            HansonBottomNavigationBar(
                                currentTab = currentTab,
                                onTabSelected = { selected -> currentTab = selected }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                            .statusBarsPadding()
                    ) {
                        Crossfade(
                            targetState = currentTab,
                            animationSpec = tween(durationMillis = 180),
                            label = "MainTabTransition",
                            modifier = Modifier.fillMaxSize()
                        ) { tab ->
                            saveableStateHolder.SaveableStateProvider(key = tab.name) {
                                when (tab) {
                                    MainTab.COMMAND_CENTER -> {
                                        CommandCenterScreen(
                                            session = session,
                                            chatMessages = chatMessages,
                                            modelName = config.modelName,
                                            apiKey = config.apiKey,
                                            ttsEnabled = config.ttsVoiceReply,
                                            a11yState = a11yState,
                                            permissionState = permissionState,
                                            memoryState = memoryState,
                                            routerState = routerState,
                                            notifications = recentNotifications,
                                            onSendChatMessage = { msg -> agentEngine.sendChatMessage(msg) },
                                            onStopAgent = { agentEngine.stopRun() },
                                            onClearChat = { agentEngine.clearLiveChat() },
                                            onOptimizeMemory = { agentEngine.runMemoryOptimization() },
                                            onToggleTts = {
                                                agentEngine.updateConfig(ttsVoiceReply = !config.ttsVoiceReply)
                                            },
                                            onToggleOverlayHud = { enable ->
                                                if (!enable || isHudShowing) {
                                                    FloatingHudService.stop(this@MainActivity)
                                                } else {
                                                    if (!FloatingHudService.startIfPermitted(this@MainActivity, forceEvenInApp = true)) {
                                                        if (!openOverlayPermissionSettings()) {
                                                            triggerOneTouchSetup()
                                                        }
                                                    }
                                                }
                                            },
                                            onOneTouchAutoSetup = {
                                                triggerOneTouchSetup()
                                            },
                                            onOpenDeepSettings = {
                                                currentTab = MainTab.SETTINGS
                                            },
                                            workspaceState = workspaceState,
                                            sessionStoreState = sessionStoreState,
                                            workMode = workMode,
                                            onSelectWorkMode = { mode -> agentEngine.setWorkMode(mode) },
                                            onSelectSession = { sessionId ->
                                                sessionStore.selectSession(sessionId)
                                                sessionStore.markAllRead(sessionId)
                                                sessionStore.state.value.activeSession?.mode?.let { mode ->
                                                    agentEngine.setWorkMode(mode)
                                                }
                                            },
                                            onCreateSession = {
                                                val created = sessionStore.createSession(
                                                    title = "Studio ${sessionStore.state.value.sessions.size + 1}",
                                                    mode = workMode,
                                                    isForeground = true
                                                )
                                                agentEngine.setWorkMode(created.mode)
                                            },
                                            onToggleSessionForeground = { sessionId ->
                                                sessionStore.toggleForeground(sessionId)
                                            },
                                            onSelectWorkspaceFile = { relPath ->
                                                workspaceEngine.selectFile(relPath)
                                            },
                                            onSaveWorkspaceFile = { relPath, newContent ->
                                                workspaceEngine.writeFile(relPath, newContent)
                                            },
                                            onInitAgentsMd = {
                                                workspaceEngine.initAgentsMd()
                                            },
                                            onCreateCheckpoint = { label ->
                                                workspaceEngine.captureCheckpoint(label)
                                            },
                                            onRestoreCheckpoint = { checkpointId ->
                                                workspaceEngine.restoreCheckpoint(checkpointId)
                                            },
                                            onRunWorkspaceCheck = {
                                                workspaceEngine.execWorkspaceCommand("ls")
                                            },
                                            onApprovePlanAndBuild = {
                                                agentEngine.approveAndExecutePlan()
                                            },
                                            onApprovalDecision = { approved, alwaysAllow ->
                                                agentEngine.resolvePendingApproval(approved, alwaysAllowCapability = alwaysAllow)
                                            },
                                            vaultHandles = vaultHandles,
                                            watcherRules = watcherRules,
                                            scratchpadEntries = scratchpadEntries,
                                            onPutVaultSecret = { key, label, plaintext, category ->
                                                runCatching {
                                                    agentEngine.resolvedVault.putSecret(key, label, plaintext, category)
                                                }.onFailure { err ->
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "보안 금고 저장에 실패했어요: ${err.message ?: "키스토어를 사용할 수 없습니다"}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            onDeleteVaultSecret = { key ->
                                                runCatching {
                                                    agentEngine.resolvedVault.deleteSecret(key)
                                                }.onFailure { err ->
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "보안 금고 삭제에 실패했어요: ${err.message ?: "키스토어를 사용할 수 없습니다"}",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            onAddWatcherRule = { title, target, keywords, mode ->
                                                agentEngine.resolvedWatcherEngine.addRule(
                                                    title = title,
                                                    targetAppOrUrl = target,
                                                    matchKeywords = keywords,
                                                    mode = mode
                                                )
                                            },
                                            onToggleWatcherRule = { ruleId, enabled ->
                                                agentEngine.resolvedWatcherEngine.toggleRule(ruleId, enabled)
                                            },
                                            onRemoveWatcherRule = { ruleId ->
                                                agentEngine.resolvedWatcherEngine.removeRule(ruleId)
                                            },
                                            onPutScratchpad = { key, value ->
                                                agentEngine.resolvedScratchpad.put(key, value)
                                            },
                                            onRemoveScratchpad = { key ->
                                                agentEngine.resolvedScratchpad.remove(key)
                                            },
                                            onClearScratchpad = {
                                                agentEngine.resolvedScratchpad.clear()
                                            },
                                            onResolveHumanTakeover = { completedByHuman ->
                                                agentEngine.resolveHumanTakeover(completedByHuman)
                                            },
                                            uxMode = uxMode,
                                            onSelectUxMode = { mode -> agentEngine.setUxMode(mode) },
                                            memoryBubbles = memoryBubbles,
                                            onAddBubble = { text ->
                                                agentEngine.resolvedMemoryBubbles.addBubble(factKo = text)
                                            },
                                            onToggleBubble = { bubbleId ->
                                                agentEngine.resolvedMemoryBubbles.toggleBubble(bubbleId)
                                            },
                                            onRemoveBubble = { bubbleId ->
                                                agentEngine.resolvedMemoryBubbles.removeBubble(bubbleId)
                                            },
                                            personalRoutines = personalRoutines,
                                            isRecordingDemo = isRecordingDemo,
                                            recordedStepCount = recordedSteps.size,
                                            onStartDemoRecord = startDemoRecording,
                                            onFinishDemoRecord = finishDemoRecording,
                                            onCancelDemoRecord = cancelDemoRecording,
                                            onRemoveRoutine = { routineId ->
                                                agentEngine.resolvedPersonalRoutines.removeRoutine(routineId)
                                            },
                                            latestReversibleReceipt = latestReversibleReceipt,
                                            onUndoReceipt = undoReceiptAction,
                                            clipboardSuggestions = clipboardSuggestions,
                                            onDismissClipboardSuggestions = {
                                                dismissedClipboardText = lastSeenClipboardText
                                                clipboardSuggestionsFlow.value = emptyList()
                                            },
                                            onApproveAndDial = approveAndDialAction,
                                            cloudSyncState = cloudSyncState,
                                            onSaveCloudSyncConfig = { enabled, projectId, appId, apiKeyPlaintext, databaseId, editionMode, userUid ->
                                                agentEngine.resolvedCloudSync.saveByoConfig(
                                                    enabled = enabled,
                                                    projectId = projectId,
                                                    applicationId = appId,
                                                    apiKeyPlaintext = apiKeyPlaintext,
                                                    databaseId = databaseId,
                                                    editionMode = editionMode,
                                                    userUid = userUid
                                                )
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "클라우드 동기화 설정을 저장했어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onTriggerCloudSyncNow = {
                                                agentEngine.resolvedCloudSync.syncNow(
                                                    bubblesStore = agentEngine.resolvedMemoryBubbles,
                                                    routineStore = agentEngine.resolvedPersonalRoutines,
                                                    watcherEngine = agentEngine.resolvedWatcherEngine,
                                                    receipts = allReceipts
                                                )
                                            },
                                            showOnboardingInitially = !isOnboardingCompleted() && chatMessages.none { it.sender == ChatSender.USER },
                                            onCompleteOnboarding = {
                                                markOnboardingCompleted()
                                            },
                                            lastCloudApiError = lastCloudApiError,
                                            onSaveAiConnection = { newKey, newUrl, selectedModel ->
                                                handleSaveAiConnection(newKey, newUrl, selectedModel)
                                            },
                                            onToggleVoice = {
                                                toggleVoiceSession()
                                            },
                                            proactiveProposals = proactiveProposals,
                                            secretarySettings = secretarySettings,
                                            onExecuteProactiveProposal = { proposalId, actionKey, replyText, snoozeMinutes ->
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val (_, message) = agentEngine.executeProactiveProposal(
                                                        proposalId = proposalId,
                                                        actionKey = actionKey,
                                                        replyText = replyText,
                                                        snoozeMinutes = snoozeMinutes
                                                    )
                                                    if (message.isNotBlank()) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                message,
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            },
                                            onPublishProposalToA2ui = { proposal ->
                                                ProactiveProposalToA2uiBridge.publishToA2uiRegistry(proposal)
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "'${proposal.titleKo}' 제안을 A2UI 카드로 펼쳤어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onScanProactiveSecretary = {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val found = agentEngine.runProactiveSecretaryScan()
                                                    val msg = if (found.isNotEmpty()) {
                                                        "선제안 비서가 ${found.size}건을 새로 찾았어요."
                                                    } else {
                                                        "지금은 새로 챙길 선제안이 없어요."
                                                    }
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            msg,
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            onUpdateSecretarySettings = { transform ->
                                                ProactiveSecretaryEngine.updateSettings(transform)
                                            },
                                            onToggleSecretaryPackageMute = { pkg, muted ->
                                                ProactiveSecretaryEngine.togglePackageMute(pkg, muted)
                                            },
                                            onA2uiSecretaryAction = { actionName, contextJson ->
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val handled = agentEngine.handleA2uiSecretaryAction(
                                                        actionName = actionName,
                                                        contextJson = contextJson
                                                    )
                                                    if (handled) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(
                                                                this@MainActivity,
                                                                "선제안 비서 액션을 실행했어요.",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                }
                                            },
                                            privacyProof = privacyProof,
                                            stormShieldConfig = stormShieldConfig,
                                            vdisplaySnapshot = vdisplaySnapshot,
                                            onBatchApproveSafeProposals = { safeIds ->
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    val payload = JSONObject().put("proposalIds", JSONArray(safeIds))
                                                    val ok = agentEngine.handleA2uiSecretaryAction(
                                                        actionName = BatchDigestA2uiBuilder.ACTION_BATCH_APPROVE_SAFE,
                                                        contextJson = payload
                                                    )
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            if (ok) "안전한 선제안 항목을 일괄 승인했어요." else "일괄 승인할 안전 항목이 없어요.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            onReduceRoomFrequency = { roomKey ->
                                                ProactiveSecretaryEngine.reduceRoomFrequency(roomKey)
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "'${roomKey}' 방 선제안 빈도를 낮췄어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onAlwaysAllowSafeCalendar = { roomKey ->
                                                ProactiveSecretaryEngine.alwaysAllowSafeCalendarForRoom(roomKey)
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "'${roomKey}' 방 안전 일정은 빠른 승인으로 설정했어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onToggleStormShield = { enabled ->
                                                if (enabled) {
                                                    NotificationStormShieldEngine.startFocusBlock()
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "알림 폭풍 집중 방패를 켰어요. VIP·긴급 알림만 통과합니다.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    NotificationStormShieldEngine.stopFocusBlock()
                                                    val digestProposal = ProactiveSecretaryEngine.flushFocusShieldDigest()
                                                    HansonSecretaryWidgetProvider.requestRefresh(applicationContext)
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        if (digestProposal != null) "집중 방패를 끄고 복귀 3줄 브리프를 정리했어요." else "알림 폭풍 집중 방패를 껐어요.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                            onPlaySpokenDailyBrief = {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                    agentEngine.playSpokenDailyBrief(forceSpeak = true)
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                            this@MainActivity,
                                                            "60초 음성 브리핑을 재생해요.",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                }
                                            },
                                            onExportPortabilityMarkdown = {
                                                val markdown = agentEngine.exportCrossLabPortabilityMarkdown()
                                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                clipboard?.setPrimaryClip(ClipData.newPlainText("HansonPortableContext", markdown))
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "탈-에코시스템 Markdown(${markdown.length}자)을 클립보드에 복사했어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onRequestVirtualDisplayTakeover = {
                                                HansonVirtualDisplayManager.requestUserTakeover("사용자가 직접 조작을 시작했습니다.")
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "직접 조작 모드로 전환했어요. 완료 후 제어 반환을 눌러주세요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onReturnVirtualDisplayControl = {
                                                HansonVirtualDisplayManager.returnControlFromUser()
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "에이전트에 제어권을 반환했어요.",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            },
                                            onResumeVirtualDisplayAgent = {
                                                HansonVirtualDisplayManager.resumeAgentAfterTakeover()
                                            },
                                            onStopVirtualDisplay = {
                                                HansonVirtualDisplayManager.stop()
                                            }
                                        )
                                    }

                                    MainTab.PERMISSIONS_HUB -> {
                                        PermissionsHubScreen(
                                            permState = permissionState,
                                            onRequestAllRuntimePermissions = {
                                                val perms = permissionHub.getAllRuntimePermissionsToRequest()
                                                if (perms.isNotEmpty()) {
                                                    requestPermissionsLauncher.launch(perms)
                                                }
                                            },
                                            onRequestSinglePermissionItem = { item ->
                                                if (item.isRuntimePermission && item.runtimePermissions.isNotEmpty()) {
                                                    requestPermissionsLauncher.launch(item.runtimePermissions.toTypedArray())
                                                } else {
                                                    try {
                                                        startActivity(permissionHub.buildSettingsIntent(item))
                                                    } catch (e: Exception) {
                                                        Toast.makeText(this@MainActivity, "설정 화면을 열 수 없습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            onToggleUnrestrictedMode = { enabled ->
                                                permissionHub.setUnrestrictedMode(enabled)
                                            },
                                            onCycleCapabilityGovernance = { cap ->
                                                permissionHub.cycleCapabilityLevel(cap)
                                            },
                                            onRefresh = {
                                                permissionHub.refreshStatus()
                                            }
                                        )
                                    }

                                    MainTab.INSPECTOR_BRIDGE -> {
                                        InspectorBridgeScreen(
                                            a11yState = a11yState,
                                            onCaptureSnapshot = {
                                                val service = HansonAccessibilityService.instance
                                                if (service != null) {
                                                    service.captureSnapshotNow(includeScreenshot = true)
                                                    Toast.makeText(this@MainActivity, "아토믹 스냅샷을 캡처했습니다.", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "접근성 서비스가 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onTestTapElement = { el ->
                                                val service = HansonAccessibilityService.instance
                                                if (service != null) {
                                                    val ok = GestureController.tap(service, el.centerX.toFloat(), el.centerY.toFloat())
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        if (ok) "[${el.index}] 요소를 탭했습니다." else "탭 주입 실패",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else {
                                                    Toast.makeText(this@MainActivity, "접근성 서비스가 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onTriggerGlobalKey = { key ->
                                                val service = HansonAccessibilityService.instance
                                                if (service != null) {
                                                    val ok = GestureController.performGlobalKey(service, key)
                                                    if (!ok) {
                                                        Toast.makeText(this@MainActivity, "$key 실행 실패", Toast.LENGTH_SHORT).show()
                                                    }
                                                } else {
                                                    Toast.makeText(this@MainActivity, "접근성 서비스가 연결되지 않았습니다.", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            onToggleBridgeTokenAuth = { enabled ->
                                                HansonAccessibilityService.instance?.setBridgeRequireToken(enabled)
                                            },
                                            onCopyText = { text ->
                                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                                clipboard?.setPrimaryClip(ClipData.newPlainText("InspectorBridge", text))
                                                Toast.makeText(this@MainActivity, "클립보드에 복사되었습니다.", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }

                                    MainTab.SKILLS_RECEIPTS -> {
                                        SkillsAndReceiptsScreen(
                                            receipts = allReceipts,
                                            onRunSkill = { skill ->
                                                agentEngine.sendChatMessage(skill.goalPrompt)
                                                currentTab = MainTab.COMMAND_CENTER
                                                Toast.makeText(this@MainActivity, "'${skill.title}' 실행을 시작했습니다.", Toast.LENGTH_SHORT).show()
                                            },
                                            onClearReceipts = {
                                                agentEngine.clearReceipts()
                                                Toast.makeText(this@MainActivity, "작업 영수증이 초기화되었습니다.", Toast.LENGTH_SHORT).show()
                                            },
                                            personalRoutines = personalRoutines,
                                            isRecordingDemo = isRecordingDemo,
                                            recordedStepCount = recordedSteps.size,
                                            onRunRoutine = { routine ->
                                                agentEngine.sendChatMessage(routine.goalPrompt)
                                                currentTab = MainTab.COMMAND_CENTER
                                                Toast.makeText(this@MainActivity, "'${routine.titleKo}' 루틴을 실행할게요.", Toast.LENGTH_SHORT).show()
                                            },
                                            onRemoveRoutine = { routineId ->
                                                agentEngine.resolvedPersonalRoutines.removeRoutine(routineId)
                                            },
                                            onStartDemoRecord = startDemoRecording,
                                            onFinishDemoRecord = finishDemoRecording,
                                            onCancelDemoRecord = cancelDemoRecording,
                                            onUndoReceipt = undoReceiptAction
                                        )
                                    }

                                    MainTab.SETTINGS -> {
                                        SettingsScreen(
                                            config = config,
                                            isDaemonRunning = isDaemonRunning,
                                            onSaveConfig = { key, model, maxIter, autoHud ->
                                                agentEngine.updateConfig(
                                                    apiKey = key,
                                                    modelName = model,
                                                    maxIterations = maxIter,
                                                    autoShowFloatingHud = autoHud
                                                )
                                                LiveVoiceSessionManager.configureGeminiLiveCredentials(key)
                                                Toast.makeText(this@MainActivity, "엔진 설정이 저장되었습니다.", Toast.LENGTH_SHORT).show()
                                            },
                                            onToggleDaemon = { run ->
                                                if (run) {
                                                    HansonForegroundService.start(this@MainActivity)
                                                } else {
                                                    HansonForegroundService.stop(this@MainActivity)
                                                }
                                            },
                                            onOneTouchAutoSetup = {
                                                triggerOneTouchSetup()
                                            },
                                            onSaveCustomBaseUrl = { customUrl ->
                                                agentEngine.updateConfig(customBaseUrl = customUrl)
                                            },
                                            onClose = {
                                                currentTab = MainTab.COMMAND_CENTER
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalNexusIntent(intent)
    }

    private fun handleExternalNexusIntent(incomingIntent: Intent?) {
        if (incomingIntent == null) return
        val safeVault = runCatching { agentEngine.resolvedVault }.getOrNull()

        // 1. Direct Android ACTION_SEND share intent -> Share-to-Checklist A2UI Surface
        if (incomingIntent.action == Intent.ACTION_SEND) {
            val sharedText = (incomingIntent.getStringExtra(Intent.EXTRA_TEXT)
                ?: incomingIntent.getStringExtra(Intent.EXTRA_SUBJECT))
                ?.trim()
                .orEmpty()
            incomingIntent.removeExtra(Intent.EXTRA_TEXT)
            incomingIntent.removeExtra(Intent.EXTRA_SUBJECT)
            if (sharedText.isNotBlank()) {
                val checklist = ShareToChecklistEngine.analyzeSharedContent(
                    rawTextOrUrl = sharedText,
                    vault = safeVault,
                    publishToRegistry = true
                )
                if (checklist != null) {
                    Toast.makeText(
                        this,
                        "공유된 내용으로 체크리스트 카드를 만들었어요: ${checklist.titleKo}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 2. Forwarded HansonSystemNexusReceiverActivity prompt -> Confirmation card + Share-to-Checklist A2UI
        val pendingPrompt = incomingIntent
            .getStringExtra(HansonSystemNexusReceiverActivity.EXTRA_PENDING_EXTERNAL_PROMPT)
            ?.trim()
            .orEmpty()
        if (pendingPrompt.isBlank()) return
        val preview = incomingIntent
            .getStringExtra(HansonSystemNexusReceiverActivity.EXTRA_PENDING_EXTERNAL_PREVIEW)
            ?.trim()
            .orEmpty()
            .ifBlank { pendingPrompt.take(100) }
        incomingIntent.removeExtra(HansonSystemNexusReceiverActivity.EXTRA_PENDING_EXTERNAL_PROMPT)
        incomingIntent.removeExtra(HansonSystemNexusReceiverActivity.EXTRA_PENDING_EXTERNAL_PREVIEW)

        runCatching {
            ShareToChecklistEngine.analyzeSharedContent(
                rawTextOrUrl = preview,
                vault = safeVault,
                publishToRegistry = true
            )
        }

        val confirmCard = ClipboardQuickSuggestion(
            kind = QuickIntentKind.DIRECT_COMMAND,
            badgeKo = "외부 공유 확인",
            titleKo = "공유된 요청을 실행할까요?",
            actionPrompt = pendingPrompt,
            previewText = preview
        )
        clipboardSuggestionsFlow.value = listOf(confirmCard) + clipboardSuggestionsFlow.value.filterNot {
            it.actionPrompt == pendingPrompt
        }
    }

    override fun onResume() {
        super.onResume()
        permissionHub.refreshStatus()
        agentEngine.memoryManager.refreshRuntimeMemoryMetrics(agentEngine.chatMessages.value.size)
        // Always hide external floating HUD while inside MainActivity so it never covers the top bar
        FloatingHudService.setMainActivityForeground(applicationContext, true)
        refreshClipboardSuggestions()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshClipboardSuggestions()
        }
    }

    override fun onStop() {
        super.onStop()
        FloatingHudService.setMainActivityForeground(applicationContext, false)
        val status = agentEngine.currentSession.value.status
        val isActivelyControllingExternalApp = status == com.hanson.mobileuse.agent.AgentRunStatus.OBSERVING ||
            status == com.hanson.mobileuse.agent.AgentRunStatus.THINKING ||
            status == com.hanson.mobileuse.agent.AgentRunStatus.ACTING ||
            UserDemonstrationRecorder.isRecording.value
        if (agentEngine.config.value.autoShowFloatingHud && isActivelyControllingExternalApp) {
            FloatingHudService.startIfPermitted(applicationContext)
        }
    }

    private fun refreshClipboardSuggestions() {
        if (!::agentEngine.isInitialized) return
        val clipText = runCatching {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString().orEmpty().trim()
            } else {
                ""
            }
        }.getOrDefault("")

        if (clipText.isBlank()) {
            clipboardSuggestionsFlow.value = emptyList()
            return
        }
        lastSeenClipboardText = clipText
        if (clipText == dismissedClipboardText) {
            return
        }
        val safeVault = runCatching { agentEngine.resolvedVault }.getOrNull()
        clipboardSuggestionsFlow.value = SmartClipboardAndShareIntentDetector.analyzeText(
            rawText = clipText,
            vault = safeVault
        )
    }

    private fun isOnboardingCompleted(): Boolean {
        val prefs = getSharedPreferences("hanson_onboarding_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("completed_v6_2", false)
    }

    private fun markOnboardingCompleted() {
        val prefs = getSharedPreferences("hanson_onboarding_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("completed_v6_2", true).apply()
    }

    private fun handleSaveAiConnection(
        apiKey: String?,
        customBaseUrl: String?,
        selectedModel: String?
    ) {
        val trimmedKey = apiKey?.trim()
        val trimmedUrl = customBaseUrl?.trim()
        val isBridgeUrl = !trimmedUrl.isNullOrBlank() && trimmedUrl.contains("18889")
        val resolvedModel = when {
            !selectedModel.isNullOrBlank() -> selectedModel.trim()
            isBridgeUrl -> "cli-subscription-bridge"
            trimmedUrl == "" && agentEngine.config.value.modelName == "cli-subscription-bridge" ->
                GeminiMultimodalClient.DEFAULT_MODEL
            else -> null
        }
        runCatching {
            agentEngine.updateConfig(
                apiKey = trimmedKey?.takeIf { it.isNotEmpty() },
                modelName = resolvedModel,
                customBaseUrl = trimmedUrl
            )
        }.onFailure { err ->
            Toast.makeText(
                this,
                "AI 연결 설정 저장에 실패했어요: ${err.message ?: "키스토어 오류"}",
                Toast.LENGTH_SHORT
            ).show()
        }
        val effectiveKey = trimmedKey?.takeIf { it.isNotEmpty() } ?: agentEngine.config.value.apiKey
        LiveVoiceSessionManager.configureGeminiLiveCredentials(effectiveKey)
    }

    private fun toggleVoiceSession() {
        if (LiveVoiceSessionManager.state.value.isActive) {
            LiveVoiceSessionManager.stopLiveSession()
            return
        }
        if (!LiveVoiceSessionManager.hasRecordAudioPermission(this)) {
            pendingStartVoiceAfterPermission = true
            requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        LiveVoiceSessionManager.configureGeminiLiveCredentials(agentEngine.config.value.apiKey)
        LiveVoiceSessionManager.startLiveSession(
            context = applicationContext,
            apiKey = agentEngine.config.value.apiKey
        )
    }

    private fun openOverlayPermissionSettings(): Boolean {
        return runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            true
        }.getOrDefault(false)
    }

    private fun openAccessibilitySettings(): Boolean {
        return runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            true
        }.getOrDefault(false)
    }

    private fun triggerOneTouchSetup() {
        val missingPerms = permissionHub.getAllRuntimePermissionsToRequest().filter { perm ->
            ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missingPerms.isNotEmpty()) {
            requestPermissionsLauncher.launch(missingPerms)
        }
        agentEngine.runOneTouchAutoSetup()
        val overlayGranted = runCatching { Settings.canDrawOverlays(this) }.getOrDefault(false)
        val a11yConnected = HansonAccessibilityService.instance != null &&
            HansonAccessibilityService.engineState.value.isConnected
        when {
            !overlayGranted -> {
                if (openOverlayPermissionSettings()) {
                    Toast.makeText(this, "다른 앱 위에 표시 권한을 허용해 주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "원터치 환경 자동 구성을 실행했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            !a11yConnected -> {
                if (openAccessibilitySettings()) {
                    Toast.makeText(this, "접근성 설정에서 Hanson 서비스를 켜주세요.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "원터치 환경 자동 구성을 실행했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {
                Toast.makeText(this, "원터치 환경 자동 구성을 실행했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 5-Tab Pure Achromatic Bottom Navigation Bar per Figma UI prototype (lines 486-527, 598-620).
 * - Fixed 64.dp height with 1.dp hairline top border using [ObsidianColors.GlassBorder].
 * - Pill-shaped active tab indicator using [ObsidianColors.AccentDim] (999.dp corner radius).
 * - Strictly 0% hue saturation tokens (ObsidianColors.Surface, GlassBorder, Accent, AccentDim, TextSecondary).
 * - Adapts to Android navigation bars inset.
 */
@Composable
fun HansonBottomNavigationBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        color = ObsidianColors.Surface,
        border = BorderStroke(1.dp, ObsidianColors.GlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val isSelected = tab == currentTab
                val interactionSource = remember { MutableInteractionSource() }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onTabSelected(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .then(
                                if (isSelected) Modifier.background(ObsidianColors.AccentDim)
                                else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.iconEmoji,
                            fontSize = 16.sp
                        )
                    }

                    Text(
                        text = tab.titleKo,
                        color = if (isSelected) ObsidianColors.Accent else ObsidianColors.TextSecondary,
                        fontSize = 10.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Convenience alias for [HansonBottomNavigationBar].
 */
@Composable
fun HansonBottomNavBar(
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) = HansonBottomNavigationBar(currentTab = selectedTab, onTabSelected = onTabSelected, modifier = modifier)

/**
 * Material 3 standard NavigationBar implementation option.
 */
@Composable
fun HansonM3NavigationBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.height(64.dp),
        containerColor = ObsidianColors.Surface,
        contentColor = ObsidianColors.Accent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars
    ) {
        MainTab.entries.forEach { tab ->
            val isSelected = tab == currentTab
            NavigationBarItem(
                selected = isSelected,
                onClick = { onTabSelected(tab) },
                icon = { Text(text = tab.iconEmoji, fontSize = 16.sp) },
                label = {
                    Text(
                        text = tab.titleKo,
                        fontSize = 10.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = ObsidianColors.Accent,
                    selectedTextColor = ObsidianColors.Accent,
                    unselectedIconColor = ObsidianColors.TextSecondary,
                    unselectedTextColor = ObsidianColors.TextSecondary,
                    indicatorColor = ObsidianColors.AccentDim
                )
            )
        }
    }
}
