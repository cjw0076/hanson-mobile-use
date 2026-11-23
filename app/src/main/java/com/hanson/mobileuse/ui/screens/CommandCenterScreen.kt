package com.hanson.mobileuse.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import java.util.Calendar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.ActionReceipt
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.ClipboardQuickSuggestion
import com.hanson.mobileuse.agent.FirestoreEditionMode
import com.hanson.mobileuse.agent.FirestoreSyncUiState
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.agent.HumanMemoryBubble
import com.hanson.mobileuse.agent.InteractiveMorphEngine
import com.hanson.mobileuse.agent.InteractiveMorphSurface
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.LiveVoicePhase
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.MemoryTelemetryState
import com.hanson.mobileuse.agent.MobileAiInfrastructureState
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.PersonalRoutine
import com.hanson.mobileuse.agent.ProactiveDailyBriefEngine
import com.hanson.mobileuse.agent.ScratchpadEntry
import com.hanson.mobileuse.agent.StructuredUiCard
import com.hanson.mobileuse.agent.DynamicUiEventBus
import com.hanson.mobileuse.agent.SseStreamPhase
import com.hanson.mobileuse.agent.StructuredUiTemplateType
import com.hanson.mobileuse.agent.UserExperienceMode
import com.hanson.mobileuse.agent.VaultHandleSummary
import com.hanson.mobileuse.agent.VaultSecretCategory
import com.hanson.mobileuse.agent.WatcherActionMode
import com.hanson.mobileuse.agent.WatcherRule
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.service.DynamicCanvasOverlayService
import com.hanson.mobileuse.ui.components.AhaboardDotGridBox
import com.hanson.mobileuse.ui.components.AhaboardMultimodalMediaView
import com.hanson.mobileuse.ui.components.AhaboardResponseCard
import com.hanson.mobileuse.ui.components.CoDrawingStudioModalSheet
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.CompanionDrawerBottomSheet
import com.hanson.mobileuse.ui.components.CompanionDrawerTab
import com.hanson.mobileuse.ui.components.ContextualFocusDeckCard
import com.hanson.mobileuse.ui.components.DynamicExecutionIslandBanner
import com.hanson.mobileuse.ui.components.EditorialApiKeySheet
import com.hanson.mobileuse.ui.components.FlagshipPresenceTopBar
import com.hanson.mobileuse.ui.components.FlagshipQuickPillDock
import com.hanson.mobileuse.ui.components.FloatingOneTapUndoPill
import com.hanson.mobileuse.ui.components.GenerativeUiSpec
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.components.GenerativeUiSurfaceCard
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.components.HumanFriendlyComposerBar
import com.hanson.mobileuse.ui.components.HumanTakeoverBannerCard
import com.hanson.mobileuse.ui.components.InlineApprovalPromptCard
import com.hanson.mobileuse.ui.components.InteractiveMorphSurfaceCard
import com.hanson.mobileuse.ui.components.LiveExecutionChecklistCard
import com.hanson.mobileuse.ui.components.LivingPresenceHeroStage
import com.hanson.mobileuse.ui.components.MemoryBubblesStripCard
import com.hanson.mobileuse.ui.components.PickleAgentHubSheet
import com.hanson.mobileuse.ui.components.PickleHubTab
import com.hanson.mobileuse.ui.components.PinnedRoutinesAndShowMeCard
import com.hanson.mobileuse.ui.components.PlanBlueprintActionBanner
import com.hanson.mobileuse.ui.components.SessionInboxDrawerSheet
import com.hanson.mobileuse.ui.components.SlashAndMentionPaletteCard
import com.hanson.mobileuse.ui.components.SseLiveDynamicUiSurface
import com.hanson.mobileuse.ui.components.StudioComposerCard
import com.hanson.mobileuse.ui.components.StudioTopHeaderBar
import com.hanson.mobileuse.ui.components.WorkWithAppsCompanionStrip
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.ComposerCommandParser
import com.hanson.mobileuse.workspace.SessionStoreState
import com.hanson.mobileuse.workspace.WorkspaceUiState

private enum class BoardMode(val titleKo: String, val subKo: String) {
    INSTANT("즉시 실행", "한 줄 지시 · 궤적 제어"),
    BOARD("화면 보드", "원터치 마이크로 UI"),
    VOICE("음성 동행", "실시간 보이스 대화")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommandCenterScreen(
    session: AgentRunSession,
    chatMessages: List<LiveChatMessage>,
    modelName: String,
    apiKey: String = "",
    ttsEnabled: Boolean,
    a11yState: AccessibilityEngineState,
    permissionState: PermissionHubState = PermissionHubState(),
    memoryState: MemoryTelemetryState = MemoryTelemetryState(),
    routerState: MobileAiInfrastructureState = MobileAiInfrastructureState(),
    notifications: List<InterceptedNotification>,
    onSendChatMessage: (String) -> Unit,
    onStopAgent: () -> Unit,
    onClearChat: () -> Unit,
    onOptimizeMemory: () -> Unit = {},
    onToggleTts: () -> Unit,
    onToggleOverlayHud: (Boolean) -> Unit,
    onOneTouchAutoSetup: () -> Unit,
    onOpenDeepSettings: () -> Unit,
    workspaceState: WorkspaceUiState = WorkspaceUiState(),
    sessionStoreState: SessionStoreState = SessionStoreState(),
    workMode: AgentWorkMode = sessionStoreState.activeSession?.mode ?: AgentWorkMode.AUTO,
    onSelectWorkMode: (AgentWorkMode) -> Unit = {},
    onSelectSession: (String) -> Unit = {},
    onCreateSession: () -> Unit = {},
    onToggleSessionForeground: (String) -> Unit = {},
    onSelectWorkspaceFile: (String) -> Unit = {},
    onSaveWorkspaceFile: (String, String) -> Unit = { _, _ -> },
    onInitAgentsMd: () -> Unit = {},
    onCreateCheckpoint: (String) -> Unit = {},
    onRestoreCheckpoint: (String) -> Unit = {},
    onRunWorkspaceCheck: () -> Unit = {},
    onApprovePlanAndBuild: () -> Unit = {},
    onApprovalDecision: (Boolean, Boolean) -> Unit = { _, _ -> },
    vaultHandles: List<VaultHandleSummary> = emptyList(),
    watcherRules: List<WatcherRule> = emptyList(),
    scratchpadEntries: List<ScratchpadEntry> = emptyList(),
    onPutVaultSecret: (String, String, String, VaultSecretCategory) -> Unit = { _, _, _, _ -> },
    onDeleteVaultSecret: (String) -> Unit = {},
    onAddWatcherRule: (String, String, List<String>, WatcherActionMode) -> Unit = { _, _, _, _ -> },
    onToggleWatcherRule: (String, Boolean) -> Unit = { _, _ -> },
    onRemoveWatcherRule: (String) -> Unit = {},
    onPutScratchpad: (String, String) -> Unit = { _, _ -> },
    onRemoveScratchpad: (String) -> Unit = {},
    onClearScratchpad: () -> Unit = {},
    onResolveHumanTakeover: (Boolean) -> Unit = {},
    uxMode: UserExperienceMode = UserExperienceMode.SIMPLE_HUMAN,
    onSelectUxMode: (UserExperienceMode) -> Unit = {},
    memoryBubbles: List<HumanMemoryBubble> = emptyList(),
    onAddBubble: (String) -> Unit = {},
    onToggleBubble: (String) -> Unit = {},
    onRemoveBubble: (String) -> Unit = {},
    personalRoutines: List<PersonalRoutine> = emptyList(),
    isRecordingDemo: Boolean = false,
    recordedStepCount: Int = 0,
    onStartDemoRecord: () -> Unit = {},
    onFinishDemoRecord: (String) -> Unit = {},
    onCancelDemoRecord: () -> Unit = {},
    onRemoveRoutine: (String) -> Unit = {},
    latestReversibleReceipt: ActionReceipt? = null,
    onUndoReceipt: (String) -> Unit = {},
    clipboardSuggestions: List<ClipboardQuickSuggestion> = emptyList(),
    onDismissClipboardSuggestions: () -> Unit = {},
    onApproveAndDial: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    cloudSyncState: FirestoreSyncUiState = FirestoreSyncUiState(),
    onSaveCloudSyncConfig: (
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String,
        editionMode: FirestoreEditionMode,
        userUid: String
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onTriggerCloudSyncNow: () -> Unit = {},
    showOnboardingInitially: Boolean = false,
    onCompleteOnboarding: () -> Unit = {},
    lastCloudApiError: String? = null,
    onSaveAiConnection: ((apiKey: String?, customBaseUrl: String?, selectedModel: String?) -> Unit)? = null,
    onToggleVoice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardOpen = imeBottomPx > 0

    var promptInput by rememberSaveable { mutableStateOf("") }
    var attachScreenContext by rememberSaveable { mutableStateOf(false) }
    var selectedModeIdx by rememberSaveable { mutableIntStateOf(0) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showApiKeySheet by rememberSaveable { mutableStateOf(false) }
    var showCoDrawStudioSheet by rememberSaveable { mutableStateOf(false) }
    var showOnboardingWizard by rememberSaveable { mutableStateOf(showOnboardingInitially) }
    var selectedDetailMessage by remember { mutableStateOf<LiveChatMessage?>(null) }

    var showSessionDrawer by rememberSaveable { mutableStateOf(false) }
    var showWorkbenchSheet by rememberSaveable { mutableStateOf(false) }
    var workbenchInitialTab by rememberSaveable { mutableStateOf(WorkbenchTab.FILES) }
    var showPickleHubSheet by rememberSaveable { mutableStateOf(false) }
    var pickleHubInitialTab by rememberSaveable { mutableStateOf(PickleHubTab.VAULT) }
    var showSlashPaletteOverride by remember { mutableStateOf(false) }
    var attachedContexts by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCompanionDrawer by rememberSaveable { mutableStateOf(false) }
    var companionDrawerTab by rememberSaveable { mutableStateOf(CompanionDrawerTab.BUBBLES) }
    var activeMorphSurface by remember { mutableStateOf<InteractiveMorphSurface?>(null) }
    var morphPresetIndex by rememberSaveable { mutableIntStateOf(0) }

    val morphShowcasePresets = remember {
        listOf(
            "성수동 저녁 4명 148,000원 더치페이하고 코스 영상 보드 만들어줘",
            "배민에서 오늘 저녁 메뉴 추천해줘",
            "성수동 산책 동선 스토리보드 만들어줘",
            "식당에 창가석 전화 대본 준비해줘"
        )
    }

    val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val presenceGreeting = remember(currentHour) {
        when (currentHour) {
            in 6..11 -> "좋은 아침이에요"
            in 12..17 -> "여유로운 오후예요"
            in 18..23 -> "편안한 저녁이에요"
            else -> "고요한 밤이에요"
        }
    }

    val voiceState by LiveVoiceSessionManager.state.collectAsState()
    val isVoiceListening = voiceState.isActive
    val activeGenSpec by GenerativeUiStateHolder.activeSpec.collectAsState()

    val isAgentBusy = session.status == AgentRunStatus.OBSERVING ||
        session.status == AgentRunStatus.THINKING ||
        session.status == AgentRunStatus.ACTING ||
        session.status == AgentRunStatus.AWAITING_APPROVAL ||
        session.status == AgentRunStatus.AWAITING_TAKEOVER

    val isEnvConnected = a11yState.isConnected && HansonAccessibilityService.instance != null

    val resolvedSaveAiConnection: (String?, String?, String?) -> Unit = { newKey, newUrl, newModel ->
        if (onSaveAiConnection != null) {
            onSaveAiConnection(newKey, newUrl, newModel)
        } else {
            val engine = com.hanson.mobileuse.agent.HansonRuntimeHolder.getAgentEngine(context)
            val trimmedKey = newKey?.trim()
            val trimmedUrl = newUrl?.trim()
            val isBridgeUrl = !trimmedUrl.isNullOrBlank() && trimmedUrl.contains("18889")
            val resolvedModel = when {
                !newModel.isNullOrBlank() -> newModel.trim()
                isBridgeUrl -> "cli-subscription-bridge"
                trimmedUrl == "" && engine.config.value.modelName == "cli-subscription-bridge" ->
                    GeminiMultimodalClient.DEFAULT_MODEL
                else -> null
            }
            engine.updateConfig(
                apiKey = trimmedKey?.takeIf { it.isNotEmpty() },
                modelName = resolvedModel,
                customBaseUrl = trimmedUrl
            )
            val effectiveKey = trimmedKey?.takeIf { it.isNotEmpty() } ?: engine.config.value.apiKey
            LiveVoiceSessionManager.configureGeminiLiveCredentials(effectiveKey)
        }
    }

    val resolvedToggleVoice: () -> Unit = {
        if (onToggleVoice != null) {
            onToggleVoice()
        } else if (LiveVoiceSessionManager.state.value.isActive) {
            LiveVoiceSessionManager.stopLiveSession()
        } else {
            LiveVoiceSessionManager.startLiveSession(
                context = context.applicationContext,
                apiKey = apiKey.takeIf { it.isNotBlank() }
            )
        }
    }

    val hudShowing by com.hanson.mobileuse.service.FloatingHudService.isShowing.collectAsState()
    val coDrawOverlayActive by DynamicCanvasOverlayService.isOverlayActive.collectAsState()
    val sseSurfaceState by DynamicUiEventBus.surfaceState.collectAsState()
    val hudReady = hudShowing || runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    val toggleCrossAppCoDrawOverlay: () -> Unit = {
        if (coDrawOverlayActive) {
            DynamicCanvasOverlayService.stop(context)
        } else if (Settings.canDrawOverlays(context)) {
            DynamicCanvasOverlayService.startIfPermitted(context, initialDrawMode = true)
        } else {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }
    val workspaceFileCount = remember(workspaceState.files) {
        workspaceState.files.count { !it.isDirectory }
    }
    val foregroundAppLabel = remember(a11yState.activePackage) {
        a11yState.activePackage.substringAfterLast('.').ifBlank { "Hanson Board" }
    }
    val mentionSuggestions = remember(workspaceState.files, a11yState.activePackage) {
        ComposerCommandParser.buildMentionSuggestions(workspaceState.files, a11yState.activePackage)
    }
    val lastTokenForPalette = remember(promptInput) {
        promptInput.substringAfterLast(' ').substringAfterLast('\n')
    }
    val isSlashOrMentionPaletteOpen = showSlashPaletteOverride ||
        promptInput.trimStart().startsWith("/") ||
        lastTokenForPalette.startsWith("@")

    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size, chatMessages.lastOrNull()?.id, isKeyboardOpen) {
        if (chatMessages.isNotEmpty()) {
            val total = listState.layoutInfo.totalItemsCount
            if (total > 0) {
                listState.animateScrollToItem(total - 1)
            }
        }
    }

    val hasCloudToken = apiKey.trim().isNotEmpty()
    val shortModelBadge = remember(modelName, routerState.lastUsedBackendBadge, hasCloudToken) {
        val rawBackend = routerState.lastUsedBackendBadge.removePrefix("GEMINI-")
        val resolvedBackend = if (hasCloudToken && (rawBackend == "S25-NPU-NANO" || rawBackend == "S25-ONDEVICE")) {
            "CLOUD·TOKEN"
        } else {
            rawBackend.take(11)
        }
        when {
            modelName == GeminiMultimodalClient.AUTO_MULTI_MODEL -> "AUTO·$resolvedBackend"
            hasCloudToken -> "${modelName.removePrefix("gemini-").removeSuffix("-preview").take(9)}·API"
            else -> modelName
                .removePrefix("gemini-")
                .removeSuffix("-preview")
                .take(14)
        }
    }

    val starterPrompts = remember {
        listOf(
            "배달의민족에서 족발 검색해줘",
            "오전 9시에 알람 맞춰 줘",
            "타이머 3분 맞춰 줘",
            "잘 준비 해줘",
            "집중 모드 켜줘",
            "유튜브 아이유 밤편지 틀어줘",
            "손전등 켜줘",
            "코드로 더치페이 정산 미니앱 만들어줘"
        )
    }

    val dailyBrief = remember(
        currentHour,
        notifications,
        watcherRules,
        scratchpadEntries,
        memoryBubbles,
        personalRoutines
    ) {
        ProactiveDailyBriefEngine.buildBrief(
            hourOfDay = currentHour,
            notifications = notifications,
            watcherRules = watcherRules,
            scratchpadEntries = scratchpadEntries,
            bubbles = memoryBubbles,
            pinnedRoutines = personalRoutines
        )
    }

    AhaboardDotGridBox(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. Breathable Flagship Mode Switcher Bar (일상 | 스튜디오) + Circle Icons
            FlagshipPresenceTopBar(
                activeMode = uxMode,
                onSelectMode = onSelectUxMode,
                isVoiceListening = isVoiceListening,
                onToggleVoice = resolvedToggleVoice,
                onToggleContrast = { ObsidianColors.toggleTheme() },
                onOpenPickleHub = {
                    pickleHubInitialTab = PickleHubTab.VAULT
                    showPickleHubSheet = true
                },
                onOpenMoreMenu = { showOverflowMenu = true },
                isMoreMenuExpanded = showOverflowMenu,
                onDismissMoreMenu = { showOverflowMenu = false },
                overflowMenuContent = {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "안전·기억 허브 열기 (Vault)",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            pickleHubInitialTab = PickleHubTab.VAULT
                            showPickleHubSheet = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "3-Tier 메모리 최적화 및 상태 브리핑",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onOptimizeMemory()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "적재적소 멀티모델 자동 라우팅 켜기",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onSendChatMessage("적재적소 멀티모델 모드로 바꿔줘")
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "✨ 처음 시작 가이드 (3단계 설정)",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            showOnboardingWizard = true
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "원터치 환경 자동 치유",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onOneTouchAutoSetup()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "플로팅 보드 토글",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onToggleOverlayHud(true)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "보드 대화 비우기",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onClearChat()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "AI API 키 설정 (클라우드 모델)",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            showApiKeySheet = true
                        }
                    )
                    HorizontalDivider(color = ObsidianColors.Divider)
                    DropdownMenuItem(
                        text = {
                            Text(
                                "심층 엔진 설정 (고급) ↗",
                                color = ObsidianColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        },
                        onClick = {
                            showOverflowMenu = false
                            onOpenDeepSettings()
                        }
                    )
                }
            )

            // 2. Mode-Specific Secondary Chrome + Dynamic Execution Island
            DynamicExecutionIslandBanner(
                visible = isAgentBusy,
                statusLineKo = buildString {
                    append(session.status.labelKo)
                    val latestObs = session.steps.lastOrNull()?.observation?.take(38)
                    if (!latestObs.isNullOrBlank()) {
                        append(" · ")
                        append(latestObs)
                    } else if (session.goal.isNotBlank()) {
                        append(" · ")
                        append(session.goal.take(32))
                    }
                },
                onStop = onStopAgent
            )

            // 2.5 P0-3 Transparent Cloud/Bridge Error Alert Banner (Monochrome Paper/Carbon)
            val activeApiError = lastCloudApiError?.trim()?.takeIf { it.isNotEmpty() }
            AnimatedVisibility(visible = activeApiError != null) {
                if (activeApiError != null) {
                    val errorHeadline = classifyCloudApiErrorTitle(activeApiError)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.5.dp, ObsidianColors.Error, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Accent)
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = "⚠ $errorHeadline",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.Surface)
                                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                        .clickable { showOnboardingWizard = true }
                                        .padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "가이드",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.Accent)
                                        .clickable { showApiKeySheet = true }
                                        .padding(horizontal = 11.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "AI 연결 설정",
                                        color = ObsidianColors.OnAccent,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                        Text(
                            text = activeApiError,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (uxMode == UserExperienceMode.SIMPLE_HUMAN) {
                // Setup readiness is cleanly presented inside ContextualFocusDeckCard (slide 1/N) without stacking boxes.
            } else {
                // Conditional 1-Touch Environment Setup Pill in Studio Pro
                AnimatedVisibility(visible = !isEnvConnected && !isKeyboardOpen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                            .clickable { onOneTouchAutoSetup() }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "접근성 및 환경 권한이 꺼져 있습니다",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "원터치로 접근성·알림·오버레이 권한을 즉시 복구합니다",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .padding(horizontal = 11.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "원터치 복구",
                                color = ObsidianColors.OnAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Studio v4.0 Header (Session Drawer + Auto/Plan/Build Mode Switcher + Code Workbench)
                StudioTopHeaderBar(
                    activeSessionTitle = sessionStoreState.activeSession?.title ?: "Primary Session",
                    workMode = workMode,
                    isRunning = isAgentBusy,
                    hasPendingApproval = session.status == AgentRunStatus.AWAITING_APPROVAL,
                    hasCloudToken = hasCloudToken,
                    workspaceFileCount = workspaceFileCount,
                    onOpenSessionDrawer = { showSessionDrawer = true },
                    onSelectMode = onSelectWorkMode,
                    onOpenWorkbench = {
                        workbenchInitialTab = WorkbenchTab.FILES
                        showWorkbenchSheet = true
                    },
                    onOpenSettings = onOpenDeepSettings
                )

                AnimatedVisibility(visible = !isKeyboardOpen) {
                    WorkWithAppsCompanionStrip(
                        foregroundAppLabel = foregroundAppLabel,
                        foregroundPackage = a11yState.activePackage,
                        a11yReady = isEnvConnected,
                        hudEnabled = hudShowing,
                        onAttachScreenContext = {
                            if ("@screen" !in attachedContexts) {
                                attachedContexts = attachedContexts + "@screen"
                            }
                        },
                        onToggleHud = onToggleOverlayHud,
                        onOpenWorkbench = {
                            workbenchInitialTab = WorkbenchTab.FILES
                            showWorkbenchSheet = true
                        },
                        vaultCount = vaultHandles.size,
                        activeWatcherCount = watcherRules.count { it.enabled },
                        onOpenPickleHub = {
                            pickleHubInitialTab = PickleHubTab.VAULT
                            showPickleHubSheet = true
                        }
                    )
                }

                // Ahaboard 3-Column Tactile Mode Strip (collapses when keyboard or slash/mention palette is open)
                AnimatedVisibility(visible = !isKeyboardOpen && !isSlashOrMentionPaletteOpen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BoardMode.entries.forEachIndexed { idx, mode ->
                            GlassCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedModeIdx = idx
                                        when (mode) {
                                            BoardMode.INSTANT -> Unit
                                            BoardMode.BOARD -> GenerativeUiStateHolder.restoreDefaultCockpit()
                                            BoardMode.VOICE -> {
                                                if (!ttsEnabled) {
                                                    onToggleTts()
                                                }
                                                resolvedToggleVoice()
                                            }
                                        }
                                    },
                                cornerRadius = 20.dp,
                                containerColor = ObsidianColors.Surface,
                                borderColor = ObsidianColors.GlassBorder,
                                tactileBottomLip = false,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = mode.titleKo,
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (mode == BoardMode.VOICE && (ttsEnabled || isVoiceListening)) {
                                        "보이스 켜짐 · 탭하여 전환"
                                    } else {
                                        mode.subKo
                                    },
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // 4. Clean Whiteboard Stage (Human Summaries + Ahaboard Multimodal Media Blocks + Pre-Structured UI Cards)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .imeNestedScroll(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (uxMode == UserExperienceMode.STUDIO_PRO && BoardMode.entries[selectedModeIdx] == BoardMode.BOARD && !isKeyboardOpen) {
                    item(key = "gen_ui_board") {
                        GenerativeUiSurfaceCard(
                            onRunCommand = { cmd -> onSendChatMessage(cmd) }
                        )
                    }
                }

                val pendingTakeover = session.pendingTakeover
                if (session.status == AgentRunStatus.AWAITING_TAKEOVER && pendingTakeover != null) {
                    item(key = "studio_human_takeover") {
                        HumanTakeoverBannerCard(
                            takeover = pendingTakeover,
                            onResumeAfterTakeover = { onResolveHumanTakeover(true) },
                            onCancelTakeover = { onResolveHumanTakeover(false) }
                        )
                    }
                }

                val pendingApproval = session.pendingApproval
                if (session.status == AgentRunStatus.AWAITING_APPROVAL && pendingApproval != null) {
                    item(key = "studio_inline_approval") {
                        InlineApprovalPromptCard(
                            toolName = pendingApproval.toolSpec.name,
                            riskTierLabel = pendingApproval.toolSpec.riskTier.name,
                            targetSummary = pendingApproval.args.toString().take(140),
                            reasonKo = pendingApproval.reasonKo,
                            onDeny = { onApprovalDecision(false, false) },
                            onAllowOnce = { onApprovalDecision(true, false) },
                            onAlwaysAllow = { onApprovalDecision(true, true) },
                            isHighStakesFinancial = pendingApproval.isHighStakesFinancial,
                            detectedPriceText = pendingApproval.detectedPriceText,
                            priceDriftNoticeKo = pendingApproval.priceDriftNoticeKo,
                            targetAppOrMerchant = pendingApproval.targetAppOrMerchant
                        )
                    }
                }

                if (uxMode == UserExperienceMode.SIMPLE_HUMAN) {
                    val hasNoUserMessages = chatMessages.none { it.sender == ChatSender.USER }
                    val isIdleHome = hasNoUserMessages && activeMorphSurface == null && !isKeyboardOpen
                    item(key = "human_living_orb_hero") {
                        LivingPresenceHeroStage(
                            greeting = presenceGreeting,
                            isActive = isAgentBusy,
                            isVoiceListening = isVoiceListening,
                            compact = !isIdleHome
                        )
                    }

                    val morphSurface = activeMorphSurface
                    if (morphSurface != null) {
                        item(key = "human_interactive_morph_surface") {
                            AnimatedContent(
                                targetState = morphSurface,
                                transitionSpec = {
                                    (fadeIn(tween(240)) + slideInVertically(
                                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 360f),
                                        initialOffsetY = { it / 5 }
                                    )) togetherWith fadeOut(tween(150))
                                },
                                label = "morph_card_transition"
                            ) { currentSurface ->
                                InteractiveMorphSurfaceCard(
                                    surface = currentSurface,
                                    onUpdateSurface = { updated -> activeMorphSurface = updated },
                                    onExecuteSurface = { surfaceToRun ->
                                        onSendChatMessage(InteractiveMorphEngine.buildExecutionPrompt(surfaceToRun))
                                    },
                                    onOpenInMiniBoard = { surfaceToPin ->
                                        GenerativeUiStateHolder.publishSpec(
                                            GenerativeUiSpec(
                                                id = surfaceToPin.id,
                                                title = surfaceToPin.badgeKo,
                                                subtitle = surfaceToPin.titleKo,
                                                htmlContent = InteractiveMorphEngine.buildAchromaticMicroAppHtml(surfaceToPin),
                                                heightDp = 240
                                            )
                                        )
                                        selectedModeIdx = 1
                                        onSelectUxMode(UserExperienceMode.STUDIO_PRO)
                                    },
                                    onDismiss = { activeMorphSurface = null }
                                )
                            }
                        }
                    } else if (hasNoUserMessages || clipboardSuggestions.isNotEmpty()) {
                        item(key = "human_contextual_focus_deck") {
                            ContextualFocusDeckCard(
                                brief = dailyBrief,
                                clipboardSuggestions = clipboardSuggestions,
                                a11yReady = isEnvConnected,
                                hudReady = hudReady,
                                aiReady = hasCloudToken,
                                onRunGoal = { goalPrompt ->
                                    activeMorphSurface = null
                                    onSendChatMessage(goalPrompt)
                                },
                                onRunClipboard = { suggestion ->
                                    onSendChatMessage(suggestion.actionPrompt)
                                    onDismissClipboardSuggestions()
                                },
                                onDismissClipboard = onDismissClipboardSuggestions,
                                onOneTouchSetup = onOneTouchAutoSetup,
                                onOpenApiKey = { showApiKeySheet = true },
                                onOpenMorphPreset = { presetPrompt ->
                                    activeMorphSurface = InteractiveMorphEngine.synthesizeFromPrompt(presetPrompt, memoryBubbles)
                                }
                            )
                        }
                    }
                } else {
                    if (workMode == AgentWorkMode.PLAN && workspaceState.planArtifactMarkdown.isNotBlank()) {
                        item(key = "studio_plan_blueprint") {
                            PlanBlueprintActionBanner(
                                planMarkdown = workspaceState.planArtifactMarkdown,
                                onApproveAndBuild = onApprovePlanAndBuild,
                                onOpenPlanArtifact = {
                                    workbenchInitialTab = WorkbenchTab.ARTIFACTS
                                    showWorkbenchSheet = true
                                }
                            )
                        }
                    }

                    if (session.subGoals.isNotEmpty() || isAgentBusy) {
                        item(key = "studio_live_checklist") {
                            LiveExecutionChecklistCard(
                                steps = session.subGoals,
                                currentToolLabel = session.steps.lastOrNull()?.toolName ?: session.status.labelKo,
                                onOpenWorkbenchPlan = {
                                    workbenchInitialTab = WorkbenchTab.ARTIFACTS
                                    showWorkbenchSheet = true
                                }
                            )
                        }
                    }
                }

                if (isAgentBusy) {
                    item(key = "active_trajectory_card") {
                        GlassCard(
                            cornerRadius = 24.dp,
                            containerColor = ObsidianColors.Surface,
                            tactileBottomLip = false,
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "답변을 준비하고 있어요",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = session.steps.lastOrNull()?.observation
                                            ?: session.goal.ifBlank { "화면을 분석하고 있습니다..." },
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.Accent)
                                        .clickable { onStopAgent() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Stop,
                                            contentDescription = "중지",
                                            tint = ObsidianColors.OnAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "중지",
                                            color = ObsidianColors.OnAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Human-Readable Conversation Cards + Ahaboard Multimodal Media Blocks (GLB/MP4/GIF/IMAGE/CODE) + Structured UI Templates
                items(chatMessages, key = { it.id }) { msg ->
                    val isUser = msg.sender == ChatSender.USER
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        if (isUser) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(ObsidianColors.Accent)
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            val isErrorMessage = !msg.succeeded
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth(0.96f)
                                    .clickable { selectedDetailMessage = msg },
                                cornerRadius = 26.dp,
                                containerColor = ObsidianColors.Surface,
                                borderColor = if (isErrorMessage) ObsidianColors.Error else ObsidianColors.GlassBorder,
                                tactileBottomLip = false,
                                contentPadding = PaddingValues(20.dp)
                            ) {
                                if (isErrorMessage) {
                                    val msgErrorTitle = classifyCloudApiErrorTitle(
                                        listOf(msg.subtitle, msg.toolBadge.orEmpty(), msg.text).joinToString(" ")
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(ObsidianColors.Accent)
                                                .padding(horizontal = 9.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "⚠ $msgErrorTitle",
                                                color = ObsidianColors.OnAccent,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        if (!msg.toolBadge.isNullOrBlank()) {
                                            CodeBadge(msg.toolBadge)
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                val isWebCitationCard = msg.structuredCard?.templateType == StructuredUiTemplateType.CURATED_LIST &&
                                    msg.structuredCard.items.isNotEmpty() &&
                                    msg.structuredCard.items.all { it.actionUrlOrCmd.startsWith("https://", ignoreCase = true) }

                                val openCitationUrl: (com.hanson.mobileuse.agent.StructuredUiItem) -> Unit = { item ->
                                    val actionUrl = item.actionUrlOrCmd.trim()
                                    if (actionUrl.startsWith("http://", ignoreCase = true) ||
                                        actionUrl.startsWith("https://", ignoreCase = true)
                                    ) {
                                        runCatching {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(actionUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        selectedDetailMessage = msg
                                    }
                                }

                                if (msg.structuredCard?.headerTitle != null && !isWebCitationCard) {
                                    Text(
                                        text = msg.structuredCard.headerTitle,
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                }

                                // Ahaboard-Style Event-Sourced Dual-Channel Board & Rich Markdown Renderer
                                AhaboardResponseCard(
                                    rawText = msg.text,
                                    citations = if (isWebCitationCard) msg.structuredCard?.items.orEmpty() else emptyList(),
                                    onCitationClick = openCitationUrl,
                                    onSpeakText = { spoken -> LiveVoiceSessionManager.speakAgentResponse(spoken) },
                                    onAskAboutItem = { followUpPrompt -> onSendChatMessage(followUpPrompt) }
                                )

                                // Ahaboard Inline Multimodal Media Block (GLB 3D / MP4 Video / GIF Loop / Image / Code Widget)
                                if (msg.mediaBlock != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    AhaboardMultimodalMediaView(
                                        block = msg.mediaBlock,
                                        onExpandClick = { selectedDetailMessage = msg },
                                        onRunCommand = { cmd -> onSendChatMessage(cmd) }
                                    )
                                }

                                // Non-citation Structured UI Card (e.g. METRIC_GRID, COMPARISON_TABLE)
                                if (msg.structuredCard != null && !isWebCitationCard) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    StructuredUiCardRenderer(
                                        card = msg.structuredCard,
                                        onItemClick = openCitationUrl
                                    )
                                }

                                // Tap-to-Open Detail Footer
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = if (isErrorMessage) Arrangement.SpaceBetween else Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isErrorMessage) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(ObsidianColors.Accent)
                                                .clickable { showApiKeySheet = true }
                                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "AI 연결 설정",
                                                color = ObsidianColors.OnAccent,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                    Text(
                                        text = "상세 보기 ↗",
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Live SSE Streaming Dynamic UI Surface (progressively renders tokens, quizzes & checklists)
                val showLiveSseItem = sseSurfaceState.phase == SseStreamPhase.STREAMING ||
                    sseSurfaceState.phase == SseStreamPhase.CONNECTING ||
                    sseSurfaceState.widgets.isNotEmpty()
                if (showLiveSseItem) {
                    item(key = "live_sse_dynamic_surface") {
                        SseLiveDynamicUiSurface(
                            surfaceState = sseSurfaceState,
                            compactOverlayMode = false,
                            onCitationClick = { rawUrl ->
                                if (rawUrl.startsWith("https://", ignoreCase = true)) {
                                    runCatching {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                            },
                            onSpeakText = { spoken -> LiveVoiceSessionManager.speakAgentResponse(spoken) },
                            onAskAboutItem = { followUp -> onSendChatMessage(followUp) },
                            onOpenCoDraw = { showCoDrawStudioSheet = true }
                        )
                    }
                }
            }

            // 4.5 Live Voice Conversation Bar (also surfaces voice errors such as missing mic permission)
            val voiceErrorText = voiceState.errorMessage?.takeIf { it.isNotBlank() }
            if (isVoiceListening || (voiceState.phase == LiveVoicePhase.ERROR && voiceErrorText != null)) {
                val isVoiceErr = voiceState.phase == LiveVoicePhase.ERROR && !isVoiceListening
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianColors.Surface)
                        .border(
                            1.dp,
                            if (isVoiceErr) ObsidianColors.Error else ObsidianColors.Accent,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable {
                            if (isVoiceErr) resolvedToggleVoice()
                            else LiveVoiceSessionManager.bargeInAndListen()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "실시간 보이스 동행 · ${voiceState.phase.labelKo}",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        val liveTranscript = voiceErrorText ?: voiceState.partialTranscript.ifBlank {
                            voiceState.lastSpokenResponse.ifBlank {
                                "말씀하시면 화면을 함께 보고 대화하며 제어합니다"
                            }
                        }
                        Text(
                            text = liveTranscript,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .clickable { LiveVoiceSessionManager.stopLiveSession() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (isVoiceErr) "닫기" else "종료",
                            color = ObsidianColors.OnAccent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (uxMode == UserExperienceMode.SIMPLE_HUMAN) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianColors.Background)
                        .windowInsetsPadding(WindowInsets.ime)
                ) {
                    FloatingOneTapUndoPill(
                        receipt = latestReversibleReceipt,
                        onUndoReceipt = onUndoReceipt
                    )

                    if (!isKeyboardOpen) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                                    .clickable { showCoDrawStudioSheet = true }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "✎ 함께 그리기 스튜디오",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (coDrawOverlayActive) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                    .border(
                                        1.dp,
                                        if (coDrawOverlayActive) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .clickable { toggleCrossAppCoDrawOverlay() }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (coDrawOverlayActive) "● 타 앱 오버레이 켜짐" else "타 앱 위 SSE·그리기 ↗",
                                    color = if (coDrawOverlayActive) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        FlagshipQuickPillDock(
                            activeBubblesCount = memoryBubbles.count { it.enabled },
                            routinesCount = personalRoutines.size,
                            isRecordingDemo = isRecordingDemo,
                            onOpenDrawerTab = { tab ->
                                companionDrawerTab = tab
                                showCompanionDrawer = true
                            },
                            onCycleMorphDemo = {
                                val nextIdx = morphPresetIndex % morphShowcasePresets.size
                                val preset = morphShowcasePresets[nextIdx]
                                morphPresetIndex = (nextIdx + 1) % morphShowcasePresets.size
                                activeMorphSurface = InteractiveMorphEngine.synthesizeFromPrompt(preset, memoryBubbles)
                            },
                            onOpenPickleVault = {
                                pickleHubInitialTab = PickleHubTab.VAULT
                                showPickleHubSheet = true
                            }
                        )
                    }

                    HumanFriendlyComposerBar(
                        inputText = promptInput,
                        onInputChange = { next -> promptInput = next },
                        attachScreenContext = attachScreenContext,
                        foregroundAppLabel = foregroundAppLabel,
                        onToggleAttachScreen = { attachScreenContext = !attachScreenContext },
                        isVoiceListening = isVoiceListening,
                        onToggleVoice = resolvedToggleVoice,
                        isRunning = isAgentBusy,
                        onSend = {
                            val rawPrompt = promptInput.trim()
                            val composed = buildString {
                                if (attachScreenContext) append("@screen ")
                                append(rawPrompt)
                            }.trim()
                            if (composed.isNotEmpty()) {
                                promptInput = ""
                                attachScreenContext = false
                                focusManager.clearFocus()
                                // B2: Always forward user prompts to the real AI engine (never hijack into mock morph cards).
                                // Interactive morph cards remain explicitly opt-in via the "✦ UI 변형" dock button.
                                activeMorphSurface = null
                                onSendChatMessage(composed)
                            }
                        },
                        onStop = onStopAgent
                    )
                }
            } else {
                // 5. 1-Touch Starter Chips (hidden when keyboard or command palette is open so composer stays cleanly visible)
                AnimatedVisibility(visible = !isKeyboardOpen && !isSlashOrMentionPaletteOpen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        starterPrompts.forEach { preset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Surface)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                    .clickable { onSendChatMessage(preset) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = preset,
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                // 6. Mobile Agent Studio v4.0 Slash/Mention Palette + Bottom Composer (anchored above soft keyboard or nav bar)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ObsidianColors.Background)
                        .windowInsetsPadding(WindowInsets.ime)
                ) {
                    AnimatedVisibility(visible = isSlashOrMentionPaletteOpen) {
                        SlashAndMentionPaletteCard(
                            queryText = promptInput,
                            slashOptions = ComposerCommandParser.defaultSlashCommands,
                            mentionOptions = mentionSuggestions,
                            onPickSlash = { option ->
                                showSlashPaletteOverride = false
                                when (option.command) {
                                    "/files" -> {
                                        workbenchInitialTab = WorkbenchTab.FILES
                                        showWorkbenchSheet = true
                                        promptInput = ""
                                    }
                                    "/diff" -> {
                                        workbenchInitialTab = WorkbenchTab.DIFFS
                                        showWorkbenchSheet = true
                                        promptInput = ""
                                    }
                                    "/undo" -> {
                                        workbenchInitialTab = WorkbenchTab.CHECKPOINTS
                                        showWorkbenchSheet = true
                                        promptInput = ""
                                    }
                                    "/sessions" -> {
                                        showSessionDrawer = true
                                        promptInput = ""
                                    }
                                    "/vault" -> {
                                        pickleHubInitialTab = PickleHubTab.VAULT
                                        showPickleHubSheet = true
                                        promptInput = ""
                                    }
                                    "/watch" -> {
                                        pickleHubInitialTab = PickleHubTab.WATCHERS
                                        showPickleHubSheet = true
                                        promptInput = ""
                                    }
                                    "/a2a" -> {
                                        pickleHubInitialTab = PickleHubTab.A2A
                                        showPickleHubSheet = true
                                        promptInput = ""
                                    }
                                    "/notes" -> {
                                        pickleHubInitialTab = PickleHubTab.LIVE_AND_NOTES
                                        showPickleHubSheet = true
                                        promptInput = ""
                                    }
                                    "/auto", "/plan", "/build" -> {
                                        option.targetMode?.let(onSelectWorkMode)
                                        promptInput = "${option.command} "
                                    }
                                    else -> {
                                        promptInput = ""
                                        onSendChatMessage(option.command)
                                    }
                                }
                            },
                            onPickMention = { mention ->
                                showSlashPaletteOverride = false
                                if (mention.token !in attachedContexts) {
                                    attachedContexts = attachedContexts + mention.token
                                }
                                val tok = promptInput.substringAfterLast(' ').substringAfterLast('\n')
                                promptInput = if (tok.startsWith("@")) {
                                    promptInput.dropLast(tok.length).trimEnd()
                                } else {
                                    promptInput
                                }
                            }
                        )
                    }

                    StudioComposerCard(
                        inputText = promptInput,
                        onInputChange = { next ->
                            promptInput = next
                            if (next.isNotBlank()) {
                                showSlashPaletteOverride = false
                            }
                        },
                        workMode = workMode,
                        isRunning = isAgentBusy,
                        attachedContexts = attachedContexts,
                        onRemoveContext = { ctx ->
                            attachedContexts = attachedContexts.filterNot { it == ctx }
                        },
                        onToggleSlashPalette = {
                            showSlashPaletteOverride = !showSlashPaletteOverride
                        },
                        onSend = {
                            val composed = buildString {
                                if (attachedContexts.isNotEmpty()) {
                                    append(attachedContexts.joinToString(" "))
                                    if (promptInput.isNotBlank()) append(' ')
                                }
                                append(promptInput.trim())
                            }.trim()
                            if (composed.isNotEmpty()) {
                                promptInput = ""
                                attachedContexts = emptyList()
                                showSlashPaletteOverride = false
                                focusManager.clearFocus()
                                onSendChatMessage(composed)
                            }
                        },
                        onStop = onStopAgent
                    )
                }
            }
        }

        // 7. Tap-to-Open Chat Detail Modal (Human Summary vs. AI Model/Tool Telemetry & Raw Data)
        val detailMsg = selectedDetailMessage
        if (detailMsg != null) {
            ChatDetailModalDialog(
                msg = detailMsg,
                routerState = routerState,
                memoryState = memoryState,
                onDismiss = { selectedDetailMessage = null },
                onOpenUrl = { url ->
                    runCatching {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                },
                onRunFollowUp = { followUpPrompt ->
                    selectedDetailMessage = null
                    onSendChatMessage(followUpPrompt)
                }
            )
        }

        if (showCoDrawStudioSheet) {
            CoDrawingStudioModalSheet(
                onDismiss = { showCoDrawStudioSheet = false },
                onSubmitCoDrawingPrompt = { prompt ->
                    val engine = com.hanson.mobileuse.agent.HansonRuntimeHolder.getAgentEngine(context)
                    engine.submitCoDrawingQuery(prompt)
                },
                onLaunchCrossAppOverlay = {
                    showCoDrawStudioSheet = false
                    toggleCrossAppCoDrawOverlay()
                },
                onCitationClick = { rawUrl ->
                    if (rawUrl.startsWith("https://", ignoreCase = true)) {
                        runCatching {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }
                },
                onSpeakText = { spoken -> LiveVoiceSessionManager.speakAgentResponse(spoken) }
            )
        }

        if (showApiKeySheet) {
            val runtimeEngine = remember(context) {
                com.hanson.mobileuse.agent.HansonRuntimeHolder.getAgentEngine(context)
            }
            EditorialApiKeySheet(
                initialKey = apiKey,
                onDismiss = { showApiKeySheet = false },
                onSaveKey = { newKey ->
                    val trimmedKey = newKey.trim()
                    if (trimmedKey.isNotEmpty()) {
                        resolvedSaveAiConnection(trimmedKey, null, null)
                    }
                    showApiKeySheet = false
                },
                initialCustomBaseUrl = runtimeEngine.config.value.customBaseUrl,
                currentModelName = modelName,
                onSaveCustomBaseUrl = { newUrl ->
                    val trimmedUrl = newUrl.trim()
                    val bridgeModel = if (trimmedUrl.contains("18889")) "cli-subscription-bridge" else null
                    resolvedSaveAiConnection(null, trimmedUrl, bridgeModel)
                }
            )
        }

        if (showSessionDrawer) {
            SessionInboxDrawerSheet(
                storeState = sessionStoreState,
                onDismiss = { showSessionDrawer = false },
                onSelectSession = { sessionId ->
                    onSelectSession(sessionId)
                    showSessionDrawer = false
                },
                onCreateSession = {
                    onCreateSession()
                    showSessionDrawer = false
                },
                onToggleForeground = onToggleSessionForeground
            )
        }

        if (showWorkbenchSheet) {
            MobileCodeWorkbenchSheet(
                workspaceState = workspaceState,
                initialTab = workbenchInitialTab,
                onDismiss = { showWorkbenchSheet = false },
                onSelectFile = onSelectWorkspaceFile,
                onSaveFile = onSaveWorkspaceFile,
                onInitAgentsMd = onInitAgentsMd,
                onCreateCheckpoint = onCreateCheckpoint,
                onRestoreCheckpoint = onRestoreCheckpoint,
                onRunWorkspaceCheck = onRunWorkspaceCheck
            )
        }

        if (showPickleHubSheet) {
            PickleAgentHubSheet(
                initialTab = pickleHubInitialTab,
                session = session,
                activePackage = a11yState.activePackage,
                vaultHandles = vaultHandles,
                watcherRules = watcherRules,
                scratchpadEntries = scratchpadEntries,
                onDismiss = { showPickleHubSheet = false },
                onStopAgent = onStopAgent,
                onPutVaultSecret = onPutVaultSecret,
                onDeleteVaultSecret = onDeleteVaultSecret,
                onAddWatcherRule = onAddWatcherRule,
                onToggleWatcherRule = onToggleWatcherRule,
                onRemoveWatcherRule = onRemoveWatcherRule,
                onPutScratchpad = onPutScratchpad,
                onRemoveScratchpad = onRemoveScratchpad,
                onClearScratchpad = onClearScratchpad,
                onApproveAndDial = onApproveAndDial,
                cloudSyncState = cloudSyncState,
                onSaveCloudSyncConfig = onSaveCloudSyncConfig,
                onTriggerCloudSyncNow = onTriggerCloudSyncNow
            )
        }

        if (showCompanionDrawer) {
            CompanionDrawerBottomSheet(
                initialTab = companionDrawerTab,
                onDismiss = { showCompanionDrawer = false },
                memoryBubbles = memoryBubbles,
                onAddBubble = onAddBubble,
                onToggleBubble = onToggleBubble,
                onRemoveBubble = onRemoveBubble,
                personalRoutines = personalRoutines,
                isRecordingDemo = isRecordingDemo,
                recordedStepCount = recordedStepCount,
                onRunRoutine = { routine -> onSendChatMessage(routine.goalPrompt) },
                onRemoveRoutine = onRemoveRoutine,
                onStartDemoRecord = onStartDemoRecord,
                onFinishDemoRecord = onFinishDemoRecord,
                onCancelDemoRecord = onCancelDemoRecord,
                onApproveAndDial = onApproveAndDial,
                cloudSyncState = cloudSyncState,
                onSaveCloudSyncConfig = onSaveCloudSyncConfig,
                onTriggerCloudSyncNow = onTriggerCloudSyncNow
            )
        }
        if (showOnboardingWizard) {
            val runtimeEngine = remember(context) {
                com.hanson.mobileuse.agent.HansonRuntimeHolder.getAgentEngine(context)
            }
            val notifReady = permissionState.items.any {
                it.id.contains("notif", ignoreCase = true) && it.isGranted
            }
            com.hanson.mobileuse.ui.components.ConsumerOnboardingWizardSheet(
                initialApiKey = apiKey,
                initialCustomBaseUrl = runtimeEngine.config.value.customBaseUrl,
                a11yReady = isEnvConnected,
                hudReady = hudReady,
                notifReady = notifReady,
                onApplyStarterPreferences = { pickedList ->
                    val existingFacts = memoryBubbles.map { it.factKo }.toSet()
                    pickedList.forEach { opt ->
                        if (opt.factKo !in existingFacts) {
                            onAddBubble(opt.factKo)
                        }
                    }
                },
                onSaveAiConnection = { newKey, newUrl, selectedModel ->
                    resolvedSaveAiConnection(
                        newKey.takeIf { it.isNotBlank() },
                        newUrl,
                        selectedModel
                    )
                },
                onOneTouchSetup = onOneTouchAutoSetup,
                onFinish = { demoPrompt ->
                    showOnboardingWizard = false
                    onCompleteOnboarding()
                    if (!demoPrompt.isNullOrBlank()) {
                        activeMorphSurface = InteractiveMorphEngine.synthesizeFromPrompt(demoPrompt, memoryBubbles)
                    }
                },
                onDismiss = {
                    showOnboardingWizard = false
                    onCompleteOnboarding()
                }
            )
        }
    }
}
@Composable
private fun StructuredUiCardRenderer(
    card: StructuredUiCard,
    onItemClick: (com.hanson.mobileuse.agent.StructuredUiItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Card Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = card.headerTitle,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (card.headerSubtitle.isNotBlank()) {
                    Text(
                        text = card.headerSubtitle,
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)

        // Render items according to template type
        card.items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(ObsidianColors.Surface)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(9.dp))
                    .clickable { onItemClick(item) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (item.badge.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ObsidianColors.Accent)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.badge,
                            color = ObsidianColors.OnAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.subtitle,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.meta.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = item.meta,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-Detail Modal Dialog opened when the user taps any assistant chat bubble.
 * Shows:
 * 1. Human-friendly summary & full structured items
 * 2. Multi-model specialist routing telemetry (which model was used on each turn and why)
 * 3. Collapsible raw AI/tool output for technical inspection
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatDetailModalDialog(
    msg: LiveChatMessage,
    routerState: MobileAiInfrastructureState,
    memoryState: MemoryTelemetryState,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onRunFollowUp: (String) -> Unit
) {
    val detail = msg.detailPayload
    val modalContext = LocalContext.current
    var isDisclosureExpanded by remember { mutableStateOf(false) }
    var copiedItemNoticeKo by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = ObsidianColors.Surface,
        scrimColor = ObsidianColors.Background.copy(alpha = 0.65f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ObsidianColors.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Sheet Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = msg.structuredCard?.headerTitle ?: "상세 이야기",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "한눈에 보기",
                        color = ObsidianColors.TextMuted,
                        fontSize = 11.5.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ObsidianColors.Divider)
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Lifestyle Summary Section ("이야기 요약")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Text(
                        text = "이야기 요약",
                        color = ObsidianColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    AhaboardResponseCard(rawText = msg.text)
                    val sourceUrl = detail?.sourceUrl.orEmpty()
                    if (sourceUrl.startsWith("http")) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .clickable { onOpenUrl(sourceUrl) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "원문 링크 열기 ↗",
                                color = ObsidianColors.OnAccent,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // 1.5. Full-Height Interactive Multimodal Media Stage (GLB 3D / MP4 / GIF / Image / Code)
                if (msg.mediaBlock != null) {
                    AhaboardMultimodalMediaView(
                        block = msg.mediaBlock,
                        heightDpOverride = 300,
                        onExpandClick = null,
                        onRunCommand = { cmd -> onRunFollowUp(cmd) }
                    )
                }

                // 2. Full Structured Items (if present)
                if (msg.structuredCard != null && msg.structuredCard.items.isNotEmpty()) {
                    StructuredUiCardRenderer(
                        card = msg.structuredCard,
                        onItemClick = { item ->
                            val action = item.actionUrlOrCmd.trim()
                            when {
                                action.startsWith("http://", ignoreCase = true) ||
                                    action.startsWith("https://", ignoreCase = true) -> {
                                    onOpenUrl(action)
                                }
                                action.isEmpty() -> {
                                    // P0-5: Never auto-send synthetic follow-up prompts when actionUrlOrCmd is empty.
                                    val copyPayload = listOf(item.title, item.subtitle)
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n")
                                    if (copyPayload.isNotBlank()) {
                                        runCatching {
                                            val cm = modalContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                            cm?.setPrimaryClip(ClipData.newPlainText("HansonItem", copyPayload))
                                        }
                                        copiedItemNoticeKo = "항목 내용을 복사했어요"
                                    } else {
                                        onDismiss()
                                    }
                                }
                                else -> {
                                    onRunFollowUp(action)
                                }
                            }
                        }
                    )
                    if (!copiedItemNoticeKo.isNullOrBlank()) {
                        Text(
                            text = copiedItemNoticeKo!!,
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 3. Collapsible Technical Disclosure Accordion
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
                        .clickable { isDisclosureExpanded = !isDisclosureExpanded }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isDisclosureExpanded) "기술 세부정보 (모델 및 실행 로그) ▲" else "기술 세부정보 (모델 및 실행 로그) ▼",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (isDisclosureExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = ObsidianColors.Divider)
                        Spacer(modifier = Modifier.height(10.dp))

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("호출 엔진 / 배지", color = ObsidianColors.TextMuted, fontSize = 11.5.sp)
                                CodeBadge(detail?.modelBadge?.ifBlank { msg.toolBadge ?: "AUTO" } ?: "AUTO")
                            }
                            val roleReason = detail?.specialistRoleKo?.ifBlank { routerState.lastSpecialistReason }
                                ?: routerState.lastSpecialistReason
                            Text(
                                text = "선택 역할: $roleReason",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 11.5.sp
                            )
                            if (!detail?.executedToolName.isNullOrBlank()) {
                                Text(
                                    text = "실행 도구: ${detail?.executedToolName} ${detail?.executedToolArgs.orEmpty()}",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            val ledger = detail?.turnLedger?.takeIf { it.isNotEmpty() } ?: routerState.turnModelLedger
                            if (ledger.isNotEmpty()) {
                                Text(
                                    text = "턴별 멀티모델 스위칭 이력:",
                                    color = ObsidianColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                ledger.forEach { entry ->
                                    Text(
                                        text = "• $entry",
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            val rawOut = detail?.rawTechnicalOutput.orEmpty()
                            if (rawOut.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "원문 출력 데이터:",
                                    color = ObsidianColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = rawOut,
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 10.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            // Bottom Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                        .clickable { onRunFollowUp("방금 내용 바탕으로 코드로 시각화 보드 그려줘") }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "시각화 보드로 그리기",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "닫기",
                        color = ObsidianColors.OnAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun classifyCloudApiErrorTitle(rawError: String): String {
    val lower = rawError.lowercase()
    return when {
        lower.contains("402") || lower.contains("크레딧") || lower.contains("credit") || lower.contains("잔액") ->
            "API 크레딧 소진(402)"
        lower.contains("401") || lower.contains("403") || lower.contains("인증") || lower.contains("invalid api key") ->
            "API 키 인증 오류"
        lower.contains("429") || lower.contains("rate") || lower.contains("한도") || lower.contains("quota") ->
            "API 요청 한도(429)"
        lower.contains("18889") || lower.contains("브리지") || lower.contains("bridge") ->
            "노트북 브리지 미연결"
        lower.contains("미설정") || lower.contains("미입력") || lower.contains("키가 없") || lower.contains("no api key") ->
            "API 키 미설정"
        else ->
            "AI 연결 오류"
    }
}
