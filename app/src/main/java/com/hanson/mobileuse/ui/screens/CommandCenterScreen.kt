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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.SubGoalStatus
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.components.StatusPill
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CommandCenterScreen(
    a11yState: AccessibilityEngineState,
    permState: PermissionHubState,
    session: AgentRunSession,
    chatMessages: List<LiveChatMessage>,
    recentNotifications: List<InterceptedNotification>,
    isHudShowing: Boolean,
    ttsEnabled: Boolean,
    onSendChatMessage: (String) -> Unit,
    onStopGoal: () -> Unit,
    onClearChat: () -> Unit,
    onApprovePending: (Boolean, Boolean) -> Unit,
    onQuickReplyNotification: (String, String) -> Unit,
    onToggleFloatingHud: () -> Unit,
    onToggleTts: () -> Unit,
    onLaunchVoiceInput: ((String) -> Unit) -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    var chatInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }

    val isRunning = session.status in listOf(
        AgentRunStatus.OBSERVING,
        AgentRunStatus.THINKING,
        AgentRunStatus.ACTING,
        AgentRunStatus.AWAITING_APPROVAL
    )

    val quickChips = listOf(
        "지금 화면 요약" to "현재 화면의 접근성 UI 트리와 클릭 가능한 요소를 요약해줘",
        "최근 알림 답장" to "최근 수신된 알림에 \"확인했습니다. 곧 연락드리겠습니다!\"라고 즉시 답장해줘",
        "집중 회의 모드 ON" to "방해금지 모드를 켜고 볼륨을 20으로 낮추고 밝기를 120으로 설정해줘",
        "카카오톡 열기" to "카카오톡을 실행해줘",
        "유튜브 Lo-Fi" to "유튜브 앱을 실행하고 'Lo-Fi 코딩 음악'을 검색해서 재생해줘"
    )

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background)
    ) {
        // 1. Top Compact Runtime Status Header
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            containerColor = ObsidianColors.SurfaceHigh,
            borderColor = if (a11yState.isConnected) ObsidianColors.Accent else ObsidianColors.Warning,
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hanson × Artemis 상시 라이브 채팅 런타임",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "노트북 CLI (:18888/chat) 직결 + 온디바이스 Gemini 듀얼 브레인",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleTts, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "TTS 음성 응답",
                            tint = if (ttsEnabled) ObsidianColors.Success else ObsidianColors.TextMuted
                        )
                    }
                    IconButton(onClick = onClearChat, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "채팅 비우기",
                            tint = ObsidianColors.TextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill(
                    label = if (a11yState.isConnected) "A11y ON" else "A11y OFF",
                    active = a11yState.isConnected,
                    onClick = onNavigateToPermissions
                )
                StatusPill(
                    label = "CLI Bridge :${a11yState.bridgePort} (${a11yState.bridgeRequestCount} req)",
                    active = a11yState.bridgeRunning
                )
                StatusPill(
                    label = if (isHudShowing) "플로팅 채팅창 ON" else "플로팅 채팅창 띄우기",
                    active = isHudShowing,
                    activeColor = ObsidianColors.Accent,
                    inactiveColor = ObsidianColors.TextSecondary,
                    onClick = onToggleFloatingHud
                )
                StatusPill(
                    label = "권한 ${permState.readinessPercent}%",
                    active = permState.readinessPercent >= 75,
                    onClick = onNavigateToPermissions
                )
            }
        }

        // 2. Live Sub-Goal Plan Ledger Strip (shown while running or when sub-goals exist)
        if (session.subGoals.isNotEmpty() && isRunning) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                contentPadding = PaddingValues(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "실시간 서브골 플랜 원장",
                        color = ObsidianColors.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    CodeBadge("${session.status.labelKo} · Step ${session.currentIteration}/${session.maxIterations}")
                }
                session.subGoals.forEach { sg ->
                    val c = when (sg.status) {
                        SubGoalStatus.COMPLETED -> ObsidianColors.Success
                        SubGoalStatus.IN_PROGRESS -> ObsidianColors.Accent
                        SubGoalStatus.PENDING -> ObsidianColors.TextMuted
                    }
                    Text(
                        text = "${sg.status.marker} ${sg.title}",
                        color = c,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 3. Pending Safety Gate Approval Card
        val pending = session.pendingApproval
        if (pending != null) {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                containerColor = ObsidianColors.WarningDim,
                borderColor = ObsidianColors.Warning,
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = ObsidianColors.Warning)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "승인 요청 (${pending.toolSpec.name})",
                        color = ObsidianColors.Warning,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Text(text = pending.reasonKo, color = ObsidianColors.TextPrimary, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onApprovePending(true, false) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Success)
                    ) { Text("1회 승인", fontWeight = FontWeight.Bold) }
                    Button(
                        onClick = { onApprovePending(true, true) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                    ) { Text("항상 허용", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { onApprovePending(false, false) },
                        modifier = Modifier.weight(1f)
                    ) { Text("거부", color = ObsidianColors.Error) }
                }
            }
        }

        // 4. Continuous Scrollable Live Chat Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(chatMessages, key = { it.id }) { msg ->
                LiveChatBubbleItem(
                    msg = msg,
                    timeFormatted = timeFmt.format(Date(msg.timestampMs)),
                    onQuickReply = { sessionId ->
                        onQuickReplyNotification(sessionId, "네, 확인했습니다! (Hanson AI 즉시 답장)")
                    }
                )
            }
        }

        // 5. Pinned Bottom Live Chat Composer Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianColors.Surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for ((chipLabel, chipPrompt) in quickChips) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.AccentDim)
                            .border(1.dp, ObsidianColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .clickable {
                                onSendChatMessage(chipPrompt)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = chipLabel,
                            color = ObsidianColors.Accent,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = {
                        onLaunchVoiceInput { spoken ->
                            onSendChatMessage(spoken)
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.AccentPurpleDim)
                        .border(1.dp, ObsidianColors.AccentPurple, CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성 입력",
                        tint = ObsidianColors.AccentPurple
                    )
                }

                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "실시간 라이브 채팅 메시지 또는 명령 입력...",
                            color = ObsidianColors.TextMuted,
                            fontSize = 13.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ObsidianColors.Accent,
                        unfocusedBorderColor = ObsidianColors.GlassBorder,
                        focusedTextColor = ObsidianColors.TextPrimary,
                        unfocusedTextColor = ObsidianColors.TextPrimary,
                        focusedContainerColor = ObsidianColors.SurfaceHigh,
                        unfocusedContainerColor = ObsidianColors.SurfaceHigh
                    ),
                    shape = RoundedCornerShape(16.dp),
                    maxLines = 3
                )

                if (isRunning) {
                    IconButton(
                        onClick = onStopGoal,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "정지",
                            tint = ObsidianColors.TextPrimary
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (chatInput.isNotBlank()) {
                                val toSend = chatInput.trim()
                                chatInput = ""
                                onSendChatMessage(toSend)
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Accent)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "전송",
                            tint = ObsidianColors.Background
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChatBubbleItem(
    msg: LiveChatMessage,
    timeFormatted: String,
    onQuickReply: (String) -> Unit
) {
    val isUser = msg.sender == ChatSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        val containerColor = when (msg.sender) {
            ChatSender.USER -> ObsidianColors.AccentDim
            ChatSender.AGENT -> ObsidianColors.SurfaceHigh
            ChatSender.ACTION -> ObsidianColors.Surface
            ChatSender.NOTIFICATION -> ObsidianColors.AccentPurpleDim
        }
        val borderColor = when (msg.sender) {
            ChatSender.USER -> ObsidianColors.Accent
            ChatSender.AGENT -> ObsidianColors.GlassBorder
            ChatSender.ACTION -> if (msg.succeeded) ObsidianColors.Success else ObsidianColors.Error
            ChatSender.NOTIFICATION -> ObsidianColors.AccentPurple
        }

        GlassCard(
            modifier = Modifier.widthIn(max = 325.dp),
            cornerRadius = 16.dp,
            containerColor = containerColor,
            borderColor = borderColor,
            contentPadding = PaddingValues(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeText = when (msg.sender) {
                    ChatSender.USER -> "나 (USER)"
                    ChatSender.AGENT -> msg.toolBadge ?: "Hanson AI"
                    ChatSender.ACTION -> msg.toolBadge ?: "ACTION"
                    ChatSender.NOTIFICATION -> "실시간 알림 · ${msg.toolBadge ?: "RemoteInput"}"
                }
                val badgeColor = when (msg.sender) {
                    ChatSender.USER -> ObsidianColors.Accent
                    ChatSender.AGENT -> ObsidianColors.Success
                    ChatSender.ACTION -> if (msg.succeeded) ObsidianColors.Accent else ObsidianColors.Error
                    ChatSender.NOTIFICATION -> ObsidianColors.AccentPurple
                }
                CodeBadge(text = badgeText, color = badgeColor)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeFormatted,
                    color = ObsidianColors.TextMuted,
                    fontSize = 10.5.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = msg.text,
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal
            )

            if (msg.subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = msg.subtitle,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }

            if (msg.replySessionId != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { onQuickReply(msg.replySessionId) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Success)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = ObsidianColors.Background,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "0.2초 즉시 답장 전송",
                        color = ObsidianColors.Background,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
