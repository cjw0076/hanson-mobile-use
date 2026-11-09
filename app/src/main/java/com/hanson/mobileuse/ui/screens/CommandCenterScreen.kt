package com.hanson.mobileuse.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.MemoryTelemetryState
import com.hanson.mobileuse.agent.MobileAiInfrastructureState
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.StructuredUiCard
import com.hanson.mobileuse.agent.StructuredUiTemplateType
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.ui.components.AhaboardDotGridBox
import com.hanson.mobileuse.ui.components.AhaboardMultimodalMediaView
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.components.GenerativeUiSurfaceCard
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors

private enum class BoardMode(val titleKo: String, val subKo: String) {
    INSTANT("즉시 실행", "한 줄 지시 · 궤적 제어"),
    BOARD("화면 보드", "원터치 마이크로 UI"),
    VOICE("음성 동행", "실시간 보이스 대화")
}

@Composable
fun CommandCenterScreen(
    session: AgentRunSession,
    chatMessages: List<LiveChatMessage>,
    modelName: String,
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
    onOpenDeepSettings: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardOpen = imeBottomPx > 0

    var promptInput by rememberSaveable { mutableStateOf("") }
    var selectedModeIdx by rememberSaveable { mutableIntStateOf(0) }
    var selectedLaneIdx by rememberSaveable { mutableIntStateOf(0) } // 0=AUTO, 1=FG(포그라운드), 2=BG(백그라운드)
    var showOverflowMenu by remember { mutableStateOf(false) }
    var selectedDetailMessage by remember { mutableStateOf<LiveChatMessage?>(null) }

    val voiceState by LiveVoiceSessionManager.state.collectAsState()
    val isVoiceListening = voiceState.isActive
    val activeGenSpec by GenerativeUiStateHolder.activeSpec.collectAsState()

    val isAgentBusy = session.status == AgentRunStatus.OBSERVING ||
        session.status == AgentRunStatus.THINKING ||
        session.status == AgentRunStatus.ACTING ||
        session.status == AgentRunStatus.AWAITING_APPROVAL

    val isEnvConnected = a11yState.isConnected ||
        permissionState.readinessPercent >= 80 ||
        permissionState.items.any { it.id.contains("a11y", ignoreCase = true) && it.isGranted }

    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size, isKeyboardOpen) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    val shortModelBadge = remember(modelName, routerState.lastUsedBackendBadge) {
        when {
            modelName == "jev-multi-model-auto" -> "JEV·${routerState.lastUsedBackendBadge.removePrefix("GEMINI-").take(10)}"
            else -> modelName
                .removePrefix("gemini-")
                .removeSuffix("-preview")
                .take(14)
        }
    }

    val starterPrompts = remember {
        listOf(
            "3D GLB 로봇 모델 띄워줘",
            "MP4 모션그래픽 영상 만들어줘",
            "GIF 루프 애니메이션 띄워줘",
            "기하학 아트워크 이미지 생성해서 보여줘",
            "코드로 시스템 구조도 그려서 띄워줘",
            "브라우저로 해커뉴스 최신 글 요약해줘",
            "메모리 상태 확인하고 최적화해줘"
        )
    }

    AhaboardDotGridBox(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. Minimal Ahaboard Header (56dp) with 1-Touch Multi-Model & 3-Tier Memory Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .background(ObsidianColors.Background.copy(alpha = 0.94f))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Accent)
                    )
                    Text(
                        text = "hanson",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 17.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    // 1-Touch Specialist Multi-Model Cycle Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable {
                                val nextPrompt = when {
                                    modelName == "jev-multi-model-auto" -> "모델 Argon으로 바꿔줘"
                                    modelName.contains("argon") -> "3.8 플래시 모델로 바꿔줘"
                                    modelName.contains("3.8-flash") -> "3.5 라이트 모델로 바꿔줘"
                                    else -> "적재적소 멀티모델 모드로 바꿔줘"
                                }
                                onSendChatMessage(nextPrompt)
                            }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = shortModelBadge,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 1-Touch 3-Tier Memory Status & Optimization Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable { onOptimizeMemory() }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "MEM ${memoryState.jvmUsedMb.coerceAtLeast(12)}M·${memoryState.semanticEntries.size}기억",
                            color = ObsidianColors.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Right actions: Theme toggle (Paper <-> Carbon) + Deep Menu (⋯)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                            .clickable { ObsidianColors.toggleTheme() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Contrast,
                            contentDescription = "무채색 명암 전환",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                                .clickable { showOverflowMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "더보기 메뉴",
                                tint = ObsidianColors.TextPrimary,
                                modifier = Modifier.size(17.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                            modifier = Modifier
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                        ) {
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
                    }
                }
            }

            HorizontalDivider(color = ObsidianColors.Divider, thickness = 1.dp)

            // 2. Conditional 1-Touch Environment Setup Pill (hidden when connected or when keyboard is open)
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

            // 3. Ahaboard 3-Column Tactile Mode Strip (collapses automatically when keyboard opens so chat has full height!)
            AnimatedVisibility(visible = !isKeyboardOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BoardMode.entries.forEachIndexed { idx, mode ->
                        val active = selectedModeIdx == idx
                        GlassCard(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedModeIdx = idx
                                    when (mode) {
                                        BoardMode.INSTANT -> Unit
                                        BoardMode.BOARD -> GenerativeUiStateHolder.restoreDefaultCockpit()
                                        BoardMode.VOICE -> {
                                            onToggleTts()
                                            LiveVoiceSessionManager.toggleLiveVoiceSession(context) { spoken ->
                                                onSendChatMessage(spoken)
                                            }
                                        }
                                    }
                                },
                            cornerRadius = 14.dp,
                            containerColor = ObsidianColors.Surface,
                            borderColor = ObsidianColors.GlassBorder,
                            tactileBottomLip = active,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 9.dp)
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

            // 4. Clean Whiteboard Stage (Human Summaries + Ahaboard Multimodal Media Blocks + Pre-Structured UI Cards)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (BoardMode.entries[selectedModeIdx] == BoardMode.BOARD && !isKeyboardOpen) {
                    item(key = "gen_ui_board") {
                        GenerativeUiSurfaceCard(
                            onRunCommand = { cmd -> onSendChatMessage(cmd) }
                        )
                    }
                }

                if (isAgentBusy) {
                    item(key = "active_trajectory_card") {
                        GlassCard(
                            cornerRadius = 14.dp,
                            containerColor = ObsidianColors.Surface,
                            tactileBottomLip = true,
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "행동 궤적 실행 중 · Step ${session.steps.size + 1} (${routerState.lastUsedBackendBadge})",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.sp,
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
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(ObsidianColors.Accent)
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    text = msg.text,
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .clickable { selectedDetailMessage = msg },
                                cornerRadius = 15.dp,
                                containerColor = ObsidianColors.Surface,
                                borderColor = ObsidianColors.GlassBorder,
                                tactileBottomLip = msg.structuredCard != null || msg.mediaBlock != null,
                                contentPadding = PaddingValues(13.dp)
                            ) {
                                // Top Badge Row: Specialist Model / Tool Badge + Media Format / Template Type Pill
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg.toolBadge ?: "AI-SUMMARY",
                                        color = ObsidianColors.TextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    when {
                                        msg.mediaBlock != null -> CodeBadge(msg.mediaBlock.format.labelKo)
                                        msg.structuredCard != null -> CodeBadge(msg.structuredCard.templateType.labelKo)
                                    }
                                }

                                Spacer(modifier = Modifier.height(5.dp))

                                // Human-Friendly Core Summary
                                Text(
                                    text = msg.text,
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (msg.structuredCard != null || msg.mediaBlock != null) FontWeight.SemiBold else FontWeight.Normal,
                                    lineHeight = 19.sp
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

                                // Pre-Structured UI Card (if present)
                                if (msg.structuredCard != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    StructuredUiCardRenderer(
                                        card = msg.structuredCard,
                                        onItemClick = { item ->
                                            if (item.actionUrlOrCmd.startsWith("http")) {
                                                selectedDetailMessage = msg
                                            } else {
                                                selectedDetailMessage = msg
                                            }
                                        }
                                    )
                                }

                                // Tap-to-Open Detail Footer
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = msg.structuredCard?.footerHint
                                            ?: "탭하여 사용한 모델·실행 도구·상세 내역 보기 ↗",
                                        color = ObsidianColors.TextMuted,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "상세 ↗",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4.5 Live Voice Conversation Bar
            if (isVoiceListening) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.Accent, RoundedCornerShape(14.dp))
                        .clickable { LiveVoiceSessionManager.bargeInAndListen() }
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
                        val liveTranscript = voiceState.partialTranscript.ifBlank {
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
                            .clickable { LiveVoiceSessionManager.stopLiveVoiceSession() }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "종료",
                            color = ObsidianColors.OnAccent,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 5. 1-Touch Starter Chips (hidden when keyboard is open so input bar sits cleanly above keyboard)
            AnimatedVisibility(visible = !isKeyboardOpen) {
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

            // 6. Ahaboard Bare-Field Bottom Composer (lifts smoothly above soft keyboard via MainActivity imePadding)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ObsidianColors.Background)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                GlassCard(
                    cornerRadius = 16.dp,
                    containerColor = ObsidianColors.Surface,
                    borderColor = ObsidianColors.Accent,
                    tactileBottomLip = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val laneBadgeText = when (selectedLaneIdx) {
                            1 -> "FG"
                            2 -> "BG"
                            else -> "AUTO"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (selectedLaneIdx == 0) ObsidianColors.SurfaceHigh else ObsidianColors.Accent)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .clickable { selectedLaneIdx = (selectedLaneIdx + 1) % 3 }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = laneBadgeText,
                                color = if (selectedLaneIdx == 0) ObsidianColors.TextPrimary else ObsidianColors.OnAccent,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            if (promptInput.isEmpty()) {
                                val hintText = when (selectedLaneIdx) {
                                    1 -> "[FG 포그라운드] 화면 직접 제어 지시..."
                                    2 -> "[BG 백그라운드] 화면 전환 없이 병렬 실행..."
                                    else -> "할 일을 말하거나 설정을 지시하세요..."
                                }
                                Text(
                                    text = hintText,
                                    color = ObsidianColors.TextMuted,
                                    fontSize = 13.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = promptInput,
                                onValueChange = { promptInput = it },
                                textStyle = TextStyle(
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                cursorBrush = SolidColor(ObsidianColors.Accent),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(
                                    onSend = {
                                        val text = promptInput.trim()
                                        if (text.isNotEmpty()) {
                                            val prefix = when (selectedLaneIdx) {
                                                1 -> "[FG] "
                                                2 -> "[BG] "
                                                else -> ""
                                            }
                                            promptInput = ""
                                            focusManager.clearFocus()
                                            onSendChatMessage("$prefix$text")
                                        }
                                    }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // 1-Touch Voice Mic Button
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(if (isVoiceListening) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                                .clickable {
                                    LiveVoiceSessionManager.toggleLiveVoiceSession(context) { spoken ->
                                        onSendChatMessage(spoken)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "음성 입력",
                                tint = if (isVoiceListening) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }

                        // Solid Ink Execute Pill Button
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .clickable {
                                    val text = promptInput.trim()
                                    if (text.isNotEmpty()) {
                                        val prefix = when (selectedLaneIdx) {
                                            1 -> "[FG] "
                                            2 -> "[BG] "
                                            else -> ""
                                        }
                                        promptInput = ""
                                        focusManager.clearFocus()
                                        onSendChatMessage("$prefix$text")
                                    }
                                }
                                .padding(horizontal = 13.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "실행",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "전송",
                                    tint = ObsidianColors.OnAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
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
    }
}

/**
 * Renders pre-structured Human UI Card templates inside an assistant chat bubble.
 */
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
                            maxLines = 2,
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
    var showRawOutput by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianColors.Background.copy(alpha = 0.88f))
                .padding(horizontal = 16.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f),
                cornerRadius = 20.dp,
                containerColor = ObsidianColors.Surface,
                borderColor = ObsidianColors.Accent,
                tactileBottomLip = true,
                contentPadding = PaddingValues(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Modal Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = msg.structuredCard?.headerTitle ?: "채팅 실행 상세 및 원문 리포트",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "인간 유저용 핵심 브리핑 · 적재적소 멀티모델 & 도구 명세",
                                color = ObsidianColors.TextMuted,
                                fontSize = 11.sp
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

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = ObsidianColors.Divider)
                    Spacer(modifier = Modifier.height(10.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Human Summary Section
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "인간 유저용 핵심 요약",
                                color = ObsidianColors.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = msg.text,
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 19.sp
                            )
                            val sourceUrl = detail?.sourceUrl.orEmpty()
                            if (sourceUrl.startsWith("http")) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.Accent)
                                        .clickable { onOpenUrl(sourceUrl) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "브라우저에서 원문 사이트 열기 ($sourceUrl) ↗",
                                        color = ObsidianColors.OnAccent,
                                        fontSize = 11.sp,
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
                                    if (item.actionUrlOrCmd.startsWith("http")) {
                                        onOpenUrl(item.actionUrlOrCmd)
                                    } else {
                                        onRunFollowUp("${item.title} 자세히 알려줘")
                                    }
                                }
                            )
                        }

                        // 3. JEV Multi-Model & Tool Telemetry Card
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "AI 적재적소 멀티모델 & 도구 실행 명세",
                                color = ObsidianColors.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("호출 엔진 / 배지", color = ObsidianColors.TextSecondary, fontSize = 12.sp)
                                CodeBadge(detail?.modelBadge?.ifBlank { msg.toolBadge ?: "AUTO" } ?: "AUTO")
                            }
                            val roleReason = detail?.specialistRoleKo?.ifBlank { routerState.lastSpecialistReason }
                                ?: routerState.lastSpecialistReason
                            Text(
                                text = "선택 역할: $roleReason",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
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
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "턴별 멀티모델 스위칭 이력:",
                                    color = ObsidianColors.TextMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                ledger.forEach { entry ->
                                    Text(
                                        text = "• $entry",
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // 4. Collapsible Raw Technical Output (kept out of the main chat!)
                        val rawOut = detail?.rawTechnicalOutput.orEmpty()
                        if (rawOut.isNotBlank()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                                    .clickable { showRawOutput = !showRawOutput }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "AI / 브라우저 원본 추출 데이터 (Raw Output)",
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (showRawOutput) "접기 ▲" else "펼치기 ▼",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (showRawOutput) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = ObsidianColors.Divider)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = rawOut,
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    // Bottom Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                                .clickable { onRunFollowUp("방금 내용 바탕으로 코드로 시각화 보드 그려줘") }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "시각화 보드로 그리기",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ObsidianColors.Accent)
                                .clickable { onDismiss() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "닫기",
                                color = ObsidianColors.OnAccent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
