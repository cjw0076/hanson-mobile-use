package com.hanson.mobileuse.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.AppReverseEngineeringEngine
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.LearnedAppProtocol
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.LiveVoicePhase
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.UserDemonstrationRecorder
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.ui.components.GenerativeUiStateHolder
import com.hanson.mobileuse.ui.components.GenerativeUiSurfaceCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SpatialBgDark = Color(0xFF070A10)
private val SpatialCardGlass = Color(0xD90E1420)
private val SpatialStreamGlass = Color(0xEE111827)
private val SpatialCyan = Color(0xFF38BDF8)
private val SpatialCyanGlow = Color(0x4438BDF8)
private val SpatialViolet = Color(0xFFA855F7)
private val SpatialVioletGlow = Color(0x44A855F7)
private val SpatialBorder = Color(0x3894A3B8)
private val SpatialTextPrimary = Color(0xFFF1F5F9)
private val SpatialTextSecondary = Color(0xFF94A3B8)
private val SpatialTextMuted = Color(0xFF64748B)

@Composable
fun CommandCenterScreen(
    session: AgentRunSession,
    chatMessages: List<LiveChatMessage>,
    ttsEnabled: Boolean,
    a11yState: AccessibilityEngineState,
    notifications: List<InterceptedNotification>,
    onSendChatMessage: (String) -> Unit,
    onStopAgent: () -> Unit,
    onClearChat: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleOverlayHud: (Boolean) -> Unit,
    onOpenPermissionsTab: () -> Unit
) {
    val context = LocalContext.current
    val uarpEngine = remember { AppReverseEngineeringEngine(context) }

    // Seed or load reverse-engineered protocols so the Node Graph is immediately rich
    var protocols by remember {
        mutableStateOf(
            uarpEngine.getAllLearnedProtocols().ifEmpty {
                listOfNotNull(
                    uarpEngine.reverseEngineerAndLearn("카카오톡", a11yState.latestSnapshot),
                    uarpEngine.reverseEngineerAndLearn("토스", a11yState.latestSnapshot),
                    uarpEngine.reverseEngineerAndLearn("유튜브", a11yState.latestSnapshot)
                )
            }
        )
    }
    var selectedProtocol by remember(protocols) {
        mutableStateOf(protocols.firstOrNull())
    }

    var inputText by remember { mutableStateOf("") }
    var cliMode by remember { mutableStateOf(false) }
    var overlayActive by remember { mutableStateOf(false) }
    var expandedStream by remember { mutableStateOf(false) }
    var ghostModeActive by remember { mutableStateOf(false) }
    var showGenerativeUiCanvas by remember { mutableStateOf(true) }

    val isRecordingDemo by UserDemonstrationRecorder.isRecording.collectAsState()
    val recordedDemoSteps by UserDemonstrationRecorder.recordedSteps.collectAsState()
    val liveVoiceState by LiveVoiceSessionManager.state.collectAsState()

    LaunchedEffect(Unit) {
        LiveVoiceSessionManager.init(context) { spokenCommand ->
            onSendChatMessage(spokenCommand)
        }
    }

    val isRunning = session.status in setOf(
        AgentRunStatus.OBSERVING,
        AgentRunStatus.THINKING,
        AgentRunStatus.AWAITING_APPROVAL,
        AgentRunStatus.ACTING
    )
    val listState = rememberLazyListState()
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.KOREA) }

    // Refresh learned protocols whenever a new UARP action completes in chat
    LaunchedEffect(chatMessages.size) {
        val updated = uarpEngine.getAllLearnedProtocols()
        if (updated.isNotEmpty()) {
            protocols = updated
            if (selectedProtocol == null || updated.first().timestampMs > (selectedProtocol?.timestampMs ?: 0L)) {
                selectedProtocol = updated.first()
            }
        }
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "spatialPulse")
    val orbAlpha by pulseTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "orbAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B101B),
                        SpatialBgDark,
                        Color(0xFF05070B)
                    )
                )
            )
    ) {
        // Subtle ambient spatial light bloom in top-left & center-right (Concept B aesthetic)
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x2238BDF8), Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.18f),
                    radius = size.width * 0.65f
                ),
                center = Offset(size.width * 0.2f, size.height * 0.18f),
                radius = size.width * 0.65f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x1CA855F7), Color.Transparent),
                    center = Offset(size.width * 0.78f, size.height * 0.48f),
                    radius = size.width * 0.6f
                ),
                center = Offset(size.width * 0.78f, size.height * 0.48f),
                radius = size.width * 0.6f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // =================================================================
            // 1. TOP SPATIAL TELEMETRY PILL (`>_ CLI :18889 · OS UID-2000 · A11Y`)
            // =================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xE6141C2B),
                                Color(0xE60F1726)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0x5594A3B8),
                                SpatialCyan.copy(alpha = 0.65f)
                            )
                        ),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .clickable {
                        onSendChatMessage("현재 화면 브리핑해줘")
                    }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = buildString {
                            append(">_ CLI :18889 · OS UID-2000 · ")
                            append(if (a11yState.isConnected) "A11Y" else "A11Y OFF")
                        },
                        color = SpatialTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.8.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Glowing Cyan Orb (Concept B signature top-right indicator)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(SpatialCyan.copy(alpha = 0.18f * orbAlpha))
                            .border(1.5.dp, SpatialCyan.copy(alpha = orbAlpha), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(SpatialCyan)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // =================================================================
            // 1-B. HUMAN KILLER SCENARIOS STRIP (Live Voice / Gen-UI / Show-Me-Once / Ghost / Briefing)
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 0-A) Live Voice Full-Duplex Toggle (S25 NPU + Gemini 3.8 Live)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (liveVoiceState.isActive) Color(0x4410B981) else Color(0x281E293B)
                        )
                        .border(
                            1.dp,
                            if (liveVoiceState.isActive) Color(0xFF10B981) else SpatialCyan.copy(alpha = 0.65f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            LiveVoiceSessionManager.toggleLiveVoiceSession(context) { spokenCmd ->
                                onSendChatMessage(spokenCmd)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (liveVoiceState.isActive) {
                            "🎙️ 라이브 보이스 ON (${liveVoiceState.phase.labelKo.take(6)})"
                        } else {
                            "🎙️ 라이브 보이스 채팅"
                        },
                        color = if (liveVoiceState.isActive) Color(0xFF34D399) else SpatialCyan,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 0-B) Generative UI Dynamic Micro-App Toggle
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (showGenerativeUiCanvas) SpatialCyanGlow else Color(0x221E293B)
                        )
                        .border(
                            1.dp,
                            if (showGenerativeUiCanvas) SpatialCyan else SpatialBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            showGenerativeUiCanvas = !showGenerativeUiCanvas
                            if (showGenerativeUiCanvas) {
                                GenerativeUiStateHolder.restoreDefaultCockpit()
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (showGenerativeUiCanvas) "✨ Gen-UI 조종석 ON" else "✨ Gen-UI 렌더링",
                        color = if (showGenerativeUiCanvas) SpatialCyan else SpatialTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 1) Show-Me-Once (시연으로 가르치기)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isRecordingDemo) Color(0x44EF4444) else Color(0x221E293B)
                        )
                        .border(
                            1.dp,
                            if (isRecordingDemo) Color(0xFFEF4444) else SpatialBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            if (!isRecordingDemo) {
                                UserDemonstrationRecorder.startRecording()
                            } else {
                                val (pkg, compiledRecipe) = UserDemonstrationRecorder.stopAndCompile()
                                if (compiledRecipe.isNotBlank()) {
                                    val learned = uarpEngine.reverseEngineerAndLearn(
                                        pkg,
                                        a11yState.latestSnapshot,
                                        compiledRecipe
                                    )
                                    if (learned != null) {
                                        protocols = uarpEngine.getAllLearnedProtocols()
                                        selectedProtocol = learned
                                    }
                                    onSendChatMessage("방금 시연한 루틴 저장: $compiledRecipe")
                                }
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isRecordingDemo) {
                            "■ 시연 완료 (${recordedDemoSteps.size}스텝 저장)"
                        } else {
                            "● 시연 학습 (Watch Me)"
                        },
                        color = if (isRecordingDemo) Color(0xFFF87171) else SpatialTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 2) Unread KakaoTalk / Notification Briefing
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x221E293B))
                        .border(1.dp, SpatialCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable {
                            onSendChatMessage("최근 수신된 카톡 및 알림 목록 요약해줘")
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⚡ 카톡 브리핑 (${notifications.size})",
                        color = SpatialCyan,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 3) Ghost Auto-Responder Mode
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (ghostModeActive) SpatialVioletGlow else Color(0x221E293B)
                        )
                        .border(
                            1.dp,
                            if (ghostModeActive) SpatialViolet else SpatialBorder,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable {
                            ghostModeActive = !ghostModeActive
                            if (ghostModeActive) {
                                onSendChatMessage("고스트 비서 활성화: 최근 알림에 \"지금 집중 작업 중이라 확인 후 바로 연락드리겠습니다\"라고 답장 준비해줘")
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (ghostModeActive) "👻 고스트 비서 ON" else "👻 고스트 자동응대",
                        color = if (ghostModeActive) SpatialViolet else SpatialTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 4) Cross-App Teleport
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0x221E293B))
                        .border(1.dp, SpatialBorder, RoundedCornerShape(16.dp))
                        .clickable {
                            onSendChatMessage("현재 화면의 핵심 텍스트와 일정을 추출해서 노트에 저장하고 브리핑해줘")
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "🚀 화면 텔레포트",
                        color = SpatialTextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (liveVoiceState.isActive) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xD906281E))
                        .border(1.dp, Color(0xFF10B981), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "🎙️ ${liveVoiceState.phase.labelKo} · S25 NPU/TTS + Gemini 3.8 Live",
                            color = Color(0xFF34D399),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = liveVoiceState.partialTranscript.ifBlank {
                                liveVoiceState.lastSpokenResponse.ifBlank { "핸즈프리로 무엇이든 말씀하세요 (끼어들기 지원)" }
                            },
                            color = SpatialTextPrimary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = "종료",
                        color = Color(0xFFF87171),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x33EF4444))
                            .clickable { LiveVoiceSessionManager.stopLiveVoiceSession() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // =================================================================
            // 2. SPATIAL WORKSPACE: GENERATIVE UI MICRO-APP OR UARP VECTOR NODE GRAPH + FLOATING STREAM CARD
            // =================================================================
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (showGenerativeUiCanvas) {
                    GenerativeUiSurfaceCard(
                        onRunCommand = onSendChatMessage,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                } else {
                // 2-A. Reverse-Engineered App Protocol Hero Card (Concept B)
                val proto = selectedProtocol
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 340.dp, max = 450.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(SpatialCardGlass)
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0x6694A3B8),
                                    Color(0x2838BDF8),
                                    Color(0x44A855F7)
                                )
                            ),
                            shape = RoundedCornerShape(26.dp)
                        )
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reverse-Engineered App Protocol",
                                color = SpatialTextPrimary,
                                fontSize = 17.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (proto != null) {
                                    "${proto.appLabel} (${proto.packageName}) · ${proto.uiFramework}"
                                } else {
                                    "Glowing vector micro node graph visualizing app deep-links & UI hierarchy"
                                },
                                color = SpatialTextSecondary,
                                fontSize = 11.8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0x22FFFFFF))
                                .border(1.dp, SpatialBorder, CircleShape)
                                .clickable {
                                    // Cycle through learned protocols or trigger reverse engineering
                                    if (protocols.size > 1 && proto != null) {
                                        val idx = protocols.indexOfFirst { it.packageName == proto.packageName }
                                        selectedProtocol = protocols[(idx + 1) % protocols.size]
                                    } else {
                                        onSendChatMessage("토스 리버스 엔지니어링 해줘")
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "다음 앱 프로토콜",
                                tint = SpatialTextPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // App Selector & 1-Tap UARP Reverse-Engineering Pills
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickApps = listOf("카카오톡", "토스", "유튜브", "크롬", "갤러리", "설정")
                        quickApps.forEach { appName ->
                            val isSelected = proto?.appLabel?.contains(appName) == true
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isSelected) SpatialCyanGlow else Color(0x1AFFFFFF)
                                    )
                                    .border(
                                        1.dp,
                                        if (isSelected) SpatialCyan else SpatialBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable {
                                        val learned = uarpEngine.reverseEngineerAndLearn(
                                            appName,
                                            a11yState.latestSnapshot
                                        )
                                        if (learned != null) {
                                            protocols = uarpEngine.getAllLearnedProtocols()
                                            selectedProtocol = learned
                                        }
                                    }
                                    .padding(horizontal = 11.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = appName,
                                    color = if (isSelected) SpatialCyan else SpatialTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Interactive Glowing Vector Node Graph (Concept B Canvas + Node Pills)
                    SpatialProtocolNodeGraph(
                        protocol = proto,
                        a11yElementCount = a11yState.latestSnapshot?.indexedElements?.size ?: 0,
                        onLaunchDeepLink = { schemeUri ->
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (_: Throwable) {
                                onSendChatMessage("${proto?.appLabel ?: ""} 열어줘")
                            }
                        },
                        onLaunchActivity = { actShort ->
                            if (proto != null) {
                                val fullAct = if (actShort.startsWith(".")) {
                                    "${proto.packageName}/${proto.packageName}$actShort"
                                } else {
                                    "${proto.packageName}/$actShort"
                                }
                                onSendChatMessage("hanson sh am start -n $fullAct")
                            }
                        }
                    )
                }
                }

                // 2-B. Floating Spatial Conversation Card ("Hanson OS" stream card in Concept B)
                val streamWidthFraction = if (expandedStream) 1.0f else 0.74f
                val streamHeight = if (expandedStream) 430.dp else 245.dp

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(streamWidthFraction)
                        .height(streamHeight)
                        .clip(RoundedCornerShape(22.dp))
                        .background(SpatialStreamGlass)
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x7794A3B8),
                                    Color(0x3338BDF8)
                                )
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(12.dp)
                ) {
                    // Header of the floating conversation stream
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expandedStream = !expandedStream },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isRunning) SpatialCyan else Color(0xFF34D399))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isRunning) "Hanson OS · 실행 중 (Step ${session.currentIteration})" else "Hanson OS · Live Stream",
                                color = SpatialTextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (ttsEnabled) "TTS ON" else "TTS",
                                color = if (ttsEnabled) SpatialCyan else SpatialTextMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable { onToggleTts() }
                            )
                            Text(
                                text = if (expandedStream) "축소 ▾" else "확대 ▴",
                                color = SpatialCyan,
                                fontSize = 10.8.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(chatMessages.takeLast(25), key = { it.id }) { msg ->
                            SpatialStreamMessageItem(
                                msg = msg,
                                timeString = timeFmt.format(Date(msg.timestampMs)),
                                onQuickReply = { sessionId, replyText ->
                                    RemoteInputSessionCache.sendQuickReply(context, sessionId, replyText)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // =================================================================
            // 3. BOTTOM SPATIAL GLASS COMMAND CAPSULE (`[ ✈️ ] | ... [⌘] [AL] [Q]`)
            // =================================================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xEE111827),
                                Color(0xEE172033)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                SpatialCyan.copy(alpha = 0.55f),
                                Color(0x5594A3B8),
                                SpatialViolet.copy(alpha = 0.45f)
                            )
                        ),
                        shape = RoundedCornerShape(30.dp)
                    )
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Glowing Cyan Send / Stop Orb (exact match to Concept B)
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        if (isRunning) Color(0x55EF4444) else Color(0x4438BDF8),
                                        Color(0x181E293B)
                                    )
                                )
                            )
                            .border(
                                width = 1.2.dp,
                                color = if (isRunning) Color(0xFFEF4444) else SpatialCyan,
                                shape = CircleShape
                            )
                            .clickable {
                                if (isRunning) {
                                    onStopAgent()
                                } else {
                                    val trimmed = inputText.trim()
                                    if (trimmed.isNotBlank()) {
                                        val finalCmd = if (cliMode && !trimmed.startsWith("hanson ")) {
                                            "hanson $trimmed"
                                        } else {
                                            trimmed
                                        }
                                        onSendChatMessage(finalCmd)
                                        inputText = ""
                                    } else {
                                        onSendChatMessage("현재 화면 브리핑해줘")
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                            contentDescription = "전송",
                            tint = if (isRunning) Color(0xFFEF4444) else SpatialCyan,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Center Spatial Command Input
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = if (cliMode) "hanson> top, status, uarp..." else "| 명령 또는 역분석할 앱 입력...",
                                color = SpatialTextMuted,
                                fontSize = 13.sp,
                                fontFamily = if (cliMode) FontFamily.Monospace else FontFamily.Default
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = SpatialTextPrimary,
                                fontSize = 13.5.sp,
                                fontFamily = if (cliMode) FontFamily.Monospace else FontFamily.Default
                            ),
                            cursorBrush = SolidColor(SpatialCyan),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Right 3 Tactile Glass Keys from Concept B: [⌘] [AL] [Q]
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        // [⌘] CLI Mode Toggle (`UID 2000 hanson>`)
                        SpatialKeyCap(
                            label = "⌘",
                            active = cliMode,
                            activeColor = SpatialCyan,
                            onClick = { cliMode = !cliMode }
                        )
                        // [AL] Auto-Learn UARP Reverse-Engineer
                        SpatialKeyCap(
                            label = "AL",
                            active = false,
                            activeColor = SpatialViolet,
                            onClick = {
                                val target = inputText.trim().ifBlank {
                                    selectedProtocol?.appLabel ?: "카카오톡"
                                }
                                val learned = uarpEngine.reverseEngineerAndLearn(
                                    target,
                                    a11yState.latestSnapshot
                                )
                                if (learned != null) {
                                    protocols = uarpEngine.getAllLearnedProtocols()
                                    selectedProtocol = learned
                                }
                                onSendChatMessage("$target 리버스 엔지니어링 해줘")
                                inputText = ""
                            }
                        )
                        // [Q] Quick Floating HUD Overlay Toggle
                        SpatialKeyCap(
                            label = "Q",
                            active = overlayActive,
                            activeColor = SpatialCyan,
                            onClick = {
                                overlayActive = !overlayActive
                                onToggleOverlayHud(overlayActive)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpatialKeyCap(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (active) activeColor.copy(alpha = 0.25f) else Color(0x22FFFFFF)
            )
            .border(
                width = 1.dp,
                color = if (active) activeColor else Color(0x3894A3B8),
                shape = RoundedCornerShape(9.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) activeColor else SpatialTextPrimary,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Renders the Glowing Vector Micro Node Graph from Concept B (`ui_concept_b_spatial_glass`):
 *  - Left Root Cyan Node -> Upper Cyan Branch (Deep-Links) & Lower Violet Branch (Activities & UI Hierarchy)
 *  - Interactive node pills that launch deep-links or Activities on tap!
 */
@Composable
private fun SpatialProtocolNodeGraph(
    protocol: LearnedAppProtocol?,
    a11yElementCount: Int,
    onLaunchDeepLink: (String) -> Unit,
    onLaunchActivity: (String) -> Unit
) {
    val schemes = protocol?.deepLinkSchemes
        ?.filter { !it.contains("schemas.android.com") && !it.contains("play.google.com") }
        .orEmpty()
        .ifEmpty { listOf("kakaotalk://", "banktoss://", "securitiestoss://") }

    val activities = protocol?.exportedActivities
        .orEmpty()
        .ifEmpty { listOf(".MainActivity", ".SearchActivity", ".SplashActivity") }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(215.dp)
    ) {
        // Canvas drawing glowing Cyan & Violet cubic Bezier curves and glowing nodes
        Canvas(modifier = Modifier.fillMaxSize()) {
            val rootPos = Offset(size.width * 0.12f, size.height * 0.50f)
            val upperMidPos = Offset(size.width * 0.34f, size.height * 0.24f)
            val midBranchPos = Offset(size.width * 0.48f, size.height * 0.44f)
            val lowerMidPos = Offset(size.width * 0.34f, size.height * 0.78f)

            val leaf1Pos = Offset(size.width * 0.56f, size.height * 0.14f)
            val leaf2Pos = Offset(size.width * 0.56f, size.height * 0.28f)
            val leaf3Pos = Offset(size.width * 0.68f, size.height * 0.44f)
            val leaf4Pos = Offset(size.width * 0.56f, size.height * 0.70f)
            val leaf5Pos = Offset(size.width * 0.56f, size.height * 0.88f)

            fun drawCubicBranch(start: Offset, end: Offset, color: Color) {
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    val ctrlX1 = start.x + (end.x - start.x) * 0.5f
                    val ctrlX2 = start.x + (end.x - start.x) * 0.5f
                    cubicTo(ctrlX1, start.y, ctrlX2, end.y, end.x, end.y)
                }
                // Outer soft glow
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.24f),
                    style = Stroke(width = 7f, cap = StrokeCap.Round)
                )
                // Core crisp vector line
                drawPath(
                    path = path,
                    color = color.copy(alpha = 0.88f),
                    style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                )
            }

            // Root -> Upper Cyan Branch & Lower Violet Branch
            drawCubicBranch(rootPos, upperMidPos, SpatialCyan)
            drawCubicBranch(rootPos, lowerMidPos, SpatialViolet)

            // Upper Cyan Branch -> Leaf 1, Leaf 2, Mid Violet Sub-node
            drawCubicBranch(upperMidPos, leaf1Pos, SpatialCyan)
            drawCubicBranch(upperMidPos, leaf2Pos, SpatialCyan)
            drawCubicBranch(upperMidPos, midBranchPos, SpatialViolet)
            drawCubicBranch(midBranchPos, leaf3Pos, SpatialViolet)

            // Lower Violet Branch -> Leaf 4, Leaf 5
            drawCubicBranch(lowerMidPos, leaf4Pos, SpatialViolet)
            drawCubicBranch(lowerMidPos, leaf5Pos, SpatialViolet)

            fun drawGlowingNode(center: Offset, color: Color, radius: Float = 11f) {
                drawCircle(
                    color = color.copy(alpha = 0.25f),
                    radius = radius * 2.1f,
                    center = center
                )
                drawCircle(
                    color = Color(0xFF0B101B),
                    radius = radius,
                    center = center
                )
                drawCircle(
                    color = color,
                    radius = radius,
                    center = center,
                    style = Stroke(width = 2.4f)
                )
                drawCircle(
                    color = color,
                    radius = radius * 0.42f,
                    center = center
                )
            }

            drawGlowingNode(rootPos, SpatialCyan, 13f)
            drawGlowingNode(upperMidPos, SpatialCyan, 11f)
            drawGlowingNode(midBranchPos, SpatialViolet, 10f)
            drawGlowingNode(lowerMidPos, SpatialViolet, 11f)
        }

        // Interactive Node Pill Labels (positioned to match the Bezier leaf endpoints)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 155.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            // Leaf 1: Primary Deep-Link Scheme (1-tap launch)
            val scheme1 = schemes.getOrElse(0) { "kakaotalk://" }
            SpatialGraphNodePill(
                text = scheme1,
                borderColor = SpatialCyan,
                onClick = { onLaunchDeepLink(scheme1) }
            )

            // Leaf 2: Secondary Deep-Link Scheme or UI Hierarchy Count
            val scheme2 = schemes.getOrNull(1) ?: "UI Nodes: $a11yElementCount"
            SpatialGraphNodePill(
                text = scheme2,
                borderColor = SpatialCyan,
                onClick = {
                    if (scheme2.contains("://")) onLaunchDeepLink(scheme2)
                }
            )

            // Leaf 3: Primary Exported Activity (1-tap launch)
            val act1 = activities.getOrElse(0) { ".MainActivity" }
            SpatialGraphNodePill(
                text = act1.takeLast(24),
                borderColor = SpatialViolet,
                onClick = { onLaunchActivity(act1) }
            )

            // Leaf 4: Secondary Exported Activity
            val act2 = activities.getOrElse(1) { ".SplashActivity" }
            SpatialGraphNodePill(
                text = act2.takeLast(24),
                borderColor = SpatialViolet,
                onClick = { onLaunchActivity(act2) }
            )
        }
    }
}

@Composable
private fun SpatialGraphNodePill(
    text: String,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC161F30))
            .border(1.dp, borderColor.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = SpatialTextPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SpatialStreamMessageItem(
    msg: LiveChatMessage,
    timeString: String,
    onQuickReply: (sessionId: String, replyText: String) -> Unit
) {
    val accentColor = when (msg.sender) {
        ChatSender.USER -> SpatialCyan
        ChatSender.AGENT -> SpatialViolet
        ChatSender.ACTION -> Color(0xFF34D399)
        ChatSender.NOTIFICATION -> Color(0xFFFBBF24)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xCC182234))
            .border(0.8.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (msg.sender) {
                    ChatSender.USER -> "You · $timeString"
                    ChatSender.AGENT -> "Hanson OS · $timeString"
                    ChatSender.ACTION -> (msg.toolBadge ?: "OS Action")
                    ChatSender.NOTIFICATION -> "Incoming Alert · $timeString"
                },
                color = accentColor,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "•••",
                color = SpatialTextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = msg.text,
            color = SpatialTextPrimary,
            fontSize = 12.2.sp,
            lineHeight = 16.5.sp,
            maxLines = 8,
            overflow = TextOverflow.Ellipsis
        )

        // Inline 0.2s Quick-Reply Pill for KakaoTalk / SMS RemoteInput Notifications!
        val replyId = msg.replySessionId
        if (msg.sender == ChatSender.NOTIFICATION && !replyId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("확인했습니다", "잠시 후 연락드릴게요").forEach { preset ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(SpatialCyanGlow)
                            .border(0.8.dp, SpatialCyan, RoundedCornerShape(10.dp))
                            .clickable { onQuickReply(replyId, preset) }
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "⚡ $preset",
                            color = SpatialCyan,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
