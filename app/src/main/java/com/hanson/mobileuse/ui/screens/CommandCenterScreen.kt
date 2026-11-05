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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.SubGoalStatus
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.components.StatusPill
import com.hanson.mobileuse.ui.theme.ObsidianColors

@Composable
fun CommandCenterScreen(
    a11yState: AccessibilityEngineState,
    permState: PermissionHubState,
    session: AgentRunSession,
    recentNotifications: List<InterceptedNotification>,
    isHudShowing: Boolean,
    onStartGoal: (String) -> Unit,
    onStopGoal: () -> Unit,
    onApprovePending: (Boolean, Boolean) -> Unit,
    onQuickReplyNotification: (String, String) -> Unit,
    onToggleFloatingHud: () -> Unit,
    onLaunchVoiceInput: ((String) -> Unit) -> Unit,
    onNavigateToPermissions: () -> Unit
) {
    var goalInput by remember { mutableStateOf("") }
    val isRunning = session.status in listOf(
        AgentRunStatus.OBSERVING,
        AgentRunStatus.THINKING,
        AgentRunStatus.ACTING,
        AgentRunStatus.AWAITING_APPROVAL
    )

    val quickChips = listOf(
        "최근 알림 즉시 답장" to "최근 수신된 알림에 \"확인했습니다. 곧 연락드리겠습니다!\"라고 즉시 답장해줘",
        "집중 회의 모드 ON" to "방해금지 모드를 켜고 볼륨을 20으로 낮추고 밝기를 120으로 설정해줘",
        "유튜브 Lo-Fi 검색" to "유튜브 앱을 실행하고 'Lo-Fi 코딩 음악'을 검색해서 재생해줘",
        "카카오톡 실행" to "카카오톡을 실행하고 현재 화면 구성을 분석해줘",
        "현재 화면 UI 진단" to "현재 화면의 접근성 UI 트리와 클릭 가능한 요소를 요약해줘"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Top Engine Status Banner
        item {
            GlassCard(
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = if (a11yState.isConnected) ObsidianColors.Accent else ObsidianColors.Warning
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hanson × Google Artemis Engine",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "멀티모달 비전 GUI 오퍼레이터 · 화면 꺼짐 0.2초 답장 · 로컬 RPC (:18888)",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    CodeBadge(
                        text = if (permState.unrestrictedAutonomousMode) "UNRESTRICTED" else "GOVERNED",
                        color = if (permState.unrestrictedAutonomousMode) ObsidianColors.Success else ObsidianColors.Accent
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = if (a11yState.isConnected) "접근성 엔진 ON" else "접근성 꺼짐 (설정 이동)",
                        active = a11yState.isConnected,
                        onClick = onNavigateToPermissions
                    )
                    StatusPill(
                        label = "권한 준비도 ${permState.readinessPercent}% (${permState.grantedCount}/${permState.totalCount})",
                        active = permState.readinessPercent >= 75,
                        onClick = onNavigateToPermissions
                    )
                    StatusPill(
                        label = if (isHudShowing) "플로팅 HUD 켜짐" else "플로팅 HUD 켜기",
                        active = isHudShowing,
                        activeColor = ObsidianColors.Accent,
                        inactiveColor = ObsidianColors.TextSecondary,
                        onClick = onToggleFloatingHud
                    )
                    StatusPill(
                        label = "Bridge :${a11yState.bridgePort}",
                        active = a11yState.bridgeRunning
                    )
                }
            }
        }

        // 2. Multimodal Prompt Bar + Voice Input + Quick Chips
        item {
            GlassCard {
                Text(
                    text = "자율 모바일 에이전트 명령 센터",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((chipLabel, chipPrompt) in quickChips) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.AccentDim)
                                .border(1.dp, ObsidianColors.Accent.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                                .clickable {
                                    goalInput = chipPrompt
                                    onStartGoal(chipPrompt)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = chipLabel,
                                color = ObsidianColors.Accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = goalInput,
                    onValueChange = { goalInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            text = "예: 카카오톡 열어 나와의 채팅에 '회의 10분 뒤 시작' 보내고 7시 알람 맞춰줘",
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
                    shape = RoundedCornerShape(14.dp),
                    minLines = 2,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            onLaunchVoiceInput { spoken ->
                                goalInput = spoken
                                onStartGoal(spoken)
                            }
                        },
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.AccentPurpleDim)
                            .border(1.dp, ObsidianColors.AccentPurple, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "음성 명령",
                            tint = ObsidianColors.AccentPurple
                        )
                    }

                    if (isRunning) {
                        Button(
                            onClick = onStopGoal,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Error)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "에이전트 긴급 정지 (${session.status.labelKo})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (goalInput.isNotBlank()) {
                                    onStartGoal(goalInput)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "자율 모바일 에이전트 실행",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // 3. Pending Safety Gate Approval Card (if awaiting confirmation)
        val pending = session.pendingApproval
        if (pending != null) {
            item {
                GlassCard(
                    containerColor = ObsidianColors.WarningDim,
                    borderColor = ObsidianColors.Warning
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = ObsidianColors.Warning
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "실행 전 사용자 승인 요청 (${pending.toolSpec.name})",
                            color = ObsidianColors.Warning,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = pending.reasonKo,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onApprovePending(true, false) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Success)
                        ) {
                            Text("1회 승인", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onApprovePending(true, true) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                        ) {
                            Text("항상 허용", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onApprovePending(false, false) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("거부", color = ObsidianColors.Error)
                        }
                    }
                }
            }
        }

        // 4. Google Artemis 3-Phase Live Plan Ledger & Final Summary
        if (session.goal.isNotBlank()) {
            item {
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Phase 2 · 실시간 서브골 플랜 원장 (Sub-goal Ledger)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        CodeBadge(
                            text = "${session.status.labelKo} · Step ${session.currentIteration}/${session.maxIterations}",
                            color = when (session.status) {
                                AgentRunStatus.COMPLETED -> ObsidianColors.Success
                                AgentRunStatus.ERROR, AgentRunStatus.STOPPED -> ObsidianColors.Error
                                else -> ObsidianColors.Accent
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "목표: ${session.goal}",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )

                    if (session.subGoals.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        session.subGoals.forEach { sg ->
                            val markerColor = when (sg.status) {
                                SubGoalStatus.COMPLETED -> ObsidianColors.Success
                                SubGoalStatus.IN_PROGRESS -> ObsidianColors.Accent
                                SubGoalStatus.PENDING -> ObsidianColors.TextMuted
                            }
                            Row(
                                modifier = Modifier.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = sg.status.marker,
                                    color = markerColor,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = sg.title,
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    if (!session.finalSummary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianColors.SuccessDim)
                                .padding(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = ObsidianColors.Success,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = session.finalSummary,
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Step-by-Step Multimodal Execution Trace
        if (session.steps.isNotEmpty()) {
            item {
                Text(
                    text = "실시간 에이전트 추론 및 액션 로그 (${session.steps.size} Steps)",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(session.steps.reversed()) { step ->
                GlassCard(
                    containerColor = ObsidianColors.Surface,
                    borderColor = if (step.succeeded) ObsidianColors.GlassBorder else ObsidianColors.Error
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge(
                            text = "STEP ${step.stepNumber} · ${step.toolName}",
                            color = if (step.succeeded) ObsidianColors.Accent else ObsidianColors.Error
                        )
                        Text(
                            text = "${step.activePackage.substringAfterLast('.')} · 요소 ${step.elementCount}개",
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "관찰(Observation): ${step.observation}",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "인자: ${step.toolArgsJson}",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "결과: ${step.resultSummary}",
                        color = if (step.succeeded) ObsidianColors.Success else ObsidianColors.Error,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 6. Screen-Off Notification Quick-Reply Live Monitor
        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = ObsidianColors.Success,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "화면 꺼짐 알림 가로채기 & 0.2초 즉시 답장 캐시",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    CodeBadge(text = "${recentNotifications.size}건 캐시됨", color = ObsidianColors.Success)
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (recentNotifications.isEmpty()) {
                    Text(
                        text = "아직 가로챈 카카오톡/SMS/메신저 알림이 없습니다. 알림이 도착하면 화면이 꺼진 상태에서도 RemoteInput 토큰으로 0.2초 내에 답장할 수 있습니다.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )
                } else {
                    recentNotifications.take(4).forEach { notif ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "[${notif.appLabel}] ${notif.sender} (${notif.roomOrTitle})",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = notif.messageText,
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 2
                                )
                                if (notif.repliedText != null) {
                                    Text(
                                        text = "답장 완료: \"${notif.repliedText}\"",
                                        color = ObsidianColors.Success,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }
                            if (notif.canQuickReply) {
                                IconButton(
                                    onClick = {
                                        onQuickReplyNotification(
                                            notif.sessionId,
                                            "네, 확인했습니다! (Hanson AI 자동 답장)"
                                        )
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Reply,
                                        contentDescription = "즉시 답장",
                                        tint = ObsidianColors.Accent
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
