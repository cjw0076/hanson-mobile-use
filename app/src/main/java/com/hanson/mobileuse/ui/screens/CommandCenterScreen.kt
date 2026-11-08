package com.hanson.mobileuse.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.ui.components.AhaboardDotGridBox
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
    notifications: List<InterceptedNotification>,
    onSendChatMessage: (String) -> Unit,
    onStopAgent: () -> Unit,
    onClearChat: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleOverlayHud: (Boolean) -> Unit,
    onOneTouchAutoSetup: () -> Unit,
    onOpenDeepSettings: () -> Unit
) {
    val context = LocalContext.current
    var promptInput by rememberSaveable { mutableStateOf("") }
    var selectedModeIdx by rememberSaveable { mutableIntStateOf(0) }
    var selectedLaneIdx by rememberSaveable { mutableIntStateOf(0) } // 0=AUTO, 1=FG(포그라운드), 2=BG(백그라운드)
    var showOverflowMenu by remember { mutableStateOf(false) }

    val voiceState by LiveVoiceSessionManager.state.collectAsState()
    val isVoiceListening = voiceState.isActive

    val isAgentBusy = session.status == AgentRunStatus.OBSERVING ||
        session.status == AgentRunStatus.THINKING ||
        session.status == AgentRunStatus.ACTING ||
        session.status == AgentRunStatus.AWAITING_APPROVAL

    val listState = rememberLazyListState()
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    val shortModelBadge = remember(modelName) {
        modelName
            .removePrefix("gemini-")
            .removeSuffix("-preview")
            .take(18)
    }

    val starterPrompts = remember {
        listOf(
            "지금 화면에 뭐 있어?",
            "유튜브 앱 크래프트 클린룸 보드로 만들어줘",
            "유튜브에서 로파이 틀어줘",
            "첫 번째 항목 눌러줘",
            "온디바이스 AI 모델 상태 확인"
        )
    }

    AhaboardDotGridBox(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 1. Minimal Ahaboard Header (56dp, .lb-header)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(ObsidianColors.Background.copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    // 1-Touch Model Cycle Badge (tapping switches model via agent without opening Settings)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable {
                                val nextPrompt = when {
                                    modelName.contains("argon") -> "3.8 플래시 모델로 바꿔줘"
                                    modelName.contains("3.8-flash") -> "3.5 라이트 모델로 바꿔줘"
                                    modelName.contains("3.5") -> "노트북 CLI 구독 모델로 바꿔줘"
                                    else -> "모델 Argon으로 바꿔줘"
                                }
                                onSendChatMessage(nextPrompt)
                            }
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = shortModelBadge,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
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
                            .size(36.dp)
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
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Box {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
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
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Level-1 Popover (.lb-pop) -> Level-2 Deep Settings entry
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
                                        "원터치 환경 자동 치유",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
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

            // 2. Conditional 1-Touch Environment Setup Pill (hidden automatically when connected)
            if (!a11yState.isConnected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                        .clickable { onOneTouchAutoSetup() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "접근성 및 환경 권한이 꺼져 있습니다",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "설정 화면을 찾을 필요 없이 원터치로 자동 복구합니다",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "원터치 자동 구성",
                            color = ObsidianColors.OnAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Ahaboard 3-Column Tactile Mode Strip (grid-cols-3, rounded-14dp, 3dp solid lip on active)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = mode.titleKo,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.sp,
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
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 4. Clean Whiteboard Stage (Messages + Optional Generative Board + Active Trajectory Pill)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Show Generative UI Micro-Board only when BOARD mode is selected
                if (BoardMode.entries[selectedModeIdx] == BoardMode.BOARD) {
                    item(key = "gen_ui_board") {
                        GenerativeUiSurfaceCard(
                            onRunCommand = { cmd -> onSendChatMessage(cmd) }
                        )
                    }
                }

                // Compact Active Trajectory Banner (shown only while agent is actively running)
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
                                        text = "행동 궤적 실행 중 · Step ${session.steps.size + 1}",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = session.steps.lastOrNull()?.resultSummary
                                            ?: session.goal.ifBlank { "화면을 분석하고 있습니다..." },
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.5.sp,
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

                // Minimalist Monochrome Conversation Cards
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
                                modifier = Modifier.fillMaxWidth(0.92f),
                                cornerRadius = 14.dp,
                                containerColor = ObsidianColors.Surface,
                                borderColor = ObsidianColors.GlassBorder,
                                tactileBottomLip = false,
                                contentPadding = PaddingValues(13.dp)
                            ) {
                                if (!msg.toolBadge.isNullOrBlank()) {
                                    Text(
                                        text = msg.toolBadge,
                                        color = ObsidianColors.TextMuted,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Text(
                                    text = msg.text,
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.5.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4.5 Live Voice Conversation Bar (Barge-In & Real-Time Transcript)
            if (isVoiceListening) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.Accent, RoundedCornerShape(14.dp))
                        .clickable {
                            LiveVoiceSessionManager.bargeInAndListen()
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
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
                                "말씀하시면 화면을 함께 보고 대화하며 제어합니다 (탭하여 끼어들기)"
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
                            .clickable {
                                LiveVoiceSessionManager.stopLiveVoiceSession()
                            }
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

            // 5. 1-Touch Starter Chips (.lb-ask-exb)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
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

            // 6. Ahaboard Bare-Field Bottom Composer (.brand-card with 3dp solid ink bottom lip)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                GlassCard(
                    cornerRadius = 16.dp,
                    containerColor = ObsidianColors.Surface,
                    borderColor = ObsidianColors.Accent,
                    tactileBottomLip = true,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1-Touch Lane Selector Pill: AUTO <-> FG (포그라운드) <-> BG (백그라운드)
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
                                .padding(horizontal = 9.dp, vertical = 6.dp),
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
                                    fontSize = 14.sp
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
                                .size(34.dp)
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
                                modifier = Modifier.size(16.dp)
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
                                        onSendChatMessage("$prefix$text")
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
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
    }
}
