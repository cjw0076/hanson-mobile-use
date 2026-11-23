package com.hanson.mobileuse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.BoardEventCommand
import com.hanson.mobileuse.agent.CoDrawAuthor
import com.hanson.mobileuse.agent.CoDrawElement
import com.hanson.mobileuse.agent.CoDrawToolKind
import com.hanson.mobileuse.agent.CoDrawingCanvasEngine
import com.hanson.mobileuse.agent.CoDrawingCanvasState
import com.hanson.mobileuse.agent.CrossAppOverlayMode
import com.hanson.mobileuse.agent.DynamicUiEventBus
import com.hanson.mobileuse.agent.DynamicUiSurfaceState
import com.hanson.mobileuse.agent.DynamicUiWidget
import com.hanson.mobileuse.agent.SseStreamPhase
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =============================================================================
// 1. Live SSE Dynamic UI Surface (Shared by In-App Board & Out-of-App Overlay)
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SseLiveDynamicUiSurface(
    surfaceState: DynamicUiSurfaceState,
    modifier: Modifier = Modifier,
    compactOverlayMode: Boolean = false,
    onCitationClick: ((String) -> Unit)? = null,
    onSpeakText: ((String) -> Unit)? = null,
    onAskAboutItem: ((String) -> Unit)? = null,
    onOpenCoDraw: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val events = surfaceState.events
    val widgets = surfaceState.widgets
    val isStreaming = surfaceState.phase == SseStreamPhase.STREAMING || surfaceState.phase == SseStreamPhase.CONNECTING
    val hasContent = events.isNotEmpty() || widgets.isNotEmpty() || isStreaming || !surfaceState.errorReason.isNullOrBlank()
    if (!hasContent) return

    var replayNonce by remember { mutableIntStateOf(0) }
    var visibleLimit by remember(surfaceState.streamId, replayNonce) {
        mutableIntStateOf(if (isStreaming) Int.MAX_VALUE else 0)
    }
    var replayCaption by remember(surfaceState.streamId, replayNonce) { mutableStateOf("") }
    var replayFocusId by remember(surfaceState.streamId, replayNonce) { mutableStateOf<String?>(null) }

    // When replaying a completed stream, step through each event sequentially like Ahaboard
    LaunchedEffect(surfaceState.streamId, replayNonce, surfaceState.phase) {
        if (surfaceState.phase == SseStreamPhase.COMPLETED && replayNonce > 0) {
            visibleLimit = 0
            events.forEachIndexed { idx, ev ->
                visibleLimit = idx + 1
                when (val cmd = ev.command) {
                    is BoardEventCommand.Speak -> {
                        replayCaption = cmd.text
                        replayFocusId = cmd.linkedElementId
                        delay(260L)
                    }
                    else -> {
                        replayFocusId = ev.elementId
                        delay(110L)
                    }
                }
            }
            delay(220L)
            replayFocusId = null
        } else {
            visibleLimit = Int.MAX_VALUE
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "sse_live_dot")
    val livePulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(720, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sse_dot_alpha"
    )

    val displayedEvents = if (visibleLimit == Int.MAX_VALUE) events else events.take(visibleLimit)
    val headlineEv = displayedEvents.firstOrNull { it.command is BoardEventCommand.WriteHeadline }
    val statEvents = displayedEvents.filter { it.command is BoardEventCommand.AddStatPill }
    val takeawayEvents = displayedEvents.filter { it.command is BoardEventCommand.AddTakeaway }
    val noteEvents = displayedEvents.filter { it.command is BoardEventCommand.WriteNote }
    val citationEvents = displayedEvents.filter { it.command is BoardEventCommand.AddCitation }

    val activeFocusId = if (replayNonce > 0 && visibleLimit < events.size) replayFocusId else surfaceState.activeElementId
    val activeCaption = if (replayNonce > 0 && replayCaption.isNotBlank()) replayCaption else surfaceState.activeCaptionText
    val spokenAll = remember(events, activeCaption) {
        events.mapNotNull { (it.command as? BoardEventCommand.Speak)?.text }
            .joinToString(". ")
            .ifBlank { activeCaption }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (compactOverlayMode) 16.dp else 18.dp))
            .background(ObsidianColors.Surface)
            .border(1.2.dp, ObsidianColors.Accent.copy(alpha = if (isStreaming) 0.72f else 0.28f), RoundedCornerShape(if (compactOverlayMode) 16.dp else 18.dp))
            .padding(if (compactOverlayMode) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (compactOverlayMode) 8.dp else 10.dp)
    ) {
        // Header Bar: Live SSE Status + Event Counter + Replay / Listen / Co-Draw Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            ObsidianColors.Accent.copy(
                                alpha = if (isStreaming) livePulseAlpha else 0.75f
                            )
                        )
                )
                val statusLabel = when (surfaceState.phase) {
                    SseStreamPhase.STREAMING -> "SSE LIVE · ${events.size}건"
                    SseStreamPhase.CONNECTING -> "SSE 연결 중..."
                    SseStreamPhase.RECONNECTING -> "SSE 재연결 중..."
                    SseStreamPhase.ERROR -> "SSE 오류"
                    SseStreamPhase.COMPLETED -> surfaceState.sourceLabel.ifBlank { "SSE 보드 완료" }
                    SseStreamPhase.IDLE -> "SSE 대기"
                }
                Text(
                    text = statusLabel,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (surfaceState.phase == SseStreamPhase.COMPLETED && events.isNotEmpty()) {
                    SseMiniChip(label = "↻ 다시", onClick = { replayNonce++ })
                }
                if (onSpeakText != null && spokenAll.isNotBlank()) {
                    SseMiniChip(label = "🔊 듣기", onClick = { onSpeakText(spokenAll) })
                }
                if (onOpenCoDraw != null) {
                    SseMiniChip(label = "✎ 그리기", emphasized = true, onClick = onOpenCoDraw)
                }
                if (onDismiss != null) {
                    SseMiniChip(label = "✕", onClick = onDismiss)
                }
            }
        }

        // Transparent Error Banner
        if (!surfaceState.errorReason.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = surfaceState.errorReason,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // Scrollable Body (bounded in compact overlay so it never obscures the whole screen)
        Column(
            modifier = if (compactOverlayMode) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Animated Highlight Headline
            val headlineCmd = headlineEv?.command as? BoardEventCommand.WriteHeadline
            if (headlineCmd != null) {
                val swipeProgress = remember(headlineCmd.headline, replayNonce) { Animatable(0f) }
                LaunchedEffect(headlineCmd.headline, replayNonce) {
                    swipeProgress.snapTo(0f)
                    swipeProgress.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
                }
                val washColor = ObsidianColors.AccentDim
                Text(
                    text = headlineCmd.headline,
                    color = ObsidianColors.TextPrimary,
                    fontSize = if (compactOverlayMode) 14.sp else 15.5.sp,
                    lineHeight = if (compactOverlayMode) 20.sp else 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val sweepW = size.width * swipeProgress.value
                            drawRoundRect(
                                color = washColor,
                                topLeft = Offset(0f, size.height * 0.54f),
                                size = Size(sweepW, size.height * 0.42f),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }
                )
            }

            // 2. Metric Stat Pills
            if (statEvents.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    statEvents.forEach { ev ->
                        val cmd = ev.command as BoardEventCommand.AddStatPill
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cmd.label,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            Text(
                                text = cmd.value,
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // 3. Numbered Key Takeaways (Tap-to-Ask + Active Spoken Focus)
            if (takeawayEvents.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    takeawayEvents.forEach { ev ->
                        val cmd = ev.command as BoardEventCommand.AddTakeaway
                        val isFocused = activeFocusId == ev.elementId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isFocused) ObsidianColors.SurfaceTop else ObsidianColors.SurfaceHigh)
                                .border(
                                    width = if (isFocused) 1.5.dp else 1.dp,
                                    color = if (isFocused) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .then(
                                    if (onAskAboutItem != null) {
                                        Modifier.clickable {
                                            val topic = cmd.keyPrefix.ifBlank { cmd.body.take(28) }
                                            onAskAboutItem("'$topic' 부분을 더 자세히 알려줘")
                                        }
                                    } else Modifier
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 1.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (isFocused) ObsidianColors.Accent else ObsidianColors.SurfaceTop),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = cmd.index.toString(),
                                    color = if (isFocused) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                if (cmd.keyPrefix.isNotBlank()) {
                                    Text(
                                        text = cmd.keyPrefix,
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = rememberFormattedMarkdownText(cmd.body),
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }
                }
            }

            // 4. Clean Body Notes
            noteEvents.forEach { ev ->
                val cmd = ev.command as BoardEventCommand.WriteNote
                Text(
                    text = rememberFormattedMarkdownText(cmd.text),
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp
                )
            }

            // 5. Dynamic Interactive Widgets (Quiz Cards & Checklists streamed over SSE)
            widgets.forEach { widget ->
                when (widget) {
                    is DynamicUiWidget.QuizCard -> {
                        SseInteractiveQuizCard(
                            quiz = widget,
                            onSelectOption = { idx -> DynamicUiEventBus.selectQuizOption(widget.id, idx) }
                        )
                    }
                    is DynamicUiWidget.ChecklistItem -> {
                        SseInteractiveChecklistRow(
                            item = widget,
                            onToggle = { DynamicUiEventBus.toggleChecklistItem(widget.id) }
                        )
                    }
                }
            }

            // 6. Verified Domain Citations
            if (citationEvents.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    citationEvents.forEach { ev ->
                        val cmd = ev.command as BoardEventCommand.AddCitation
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .then(
                                    if (onCitationClick != null) {
                                        Modifier.clickable { onCitationClick(cmd.verifiedUrl) }
                                    } else Modifier
                                )
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${cmd.title.take(22)} ↗",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        // Live Synchronized Karaoke Voice Caption Strip
        AnimatedVisibility(
            visible = activeCaption.isNotBlank(),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isStreaming) "LIVE 자막" else "음성 자막",
                    color = ObsidianColors.OnAccent,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianColors.Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    text = activeCaption,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SseMiniChip(
    label: String,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (emphasized) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
            .border(
                1.dp,
                if (emphasized) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                RoundedCornerShape(999.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (emphasized) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun SseInteractiveQuizCard(
    quiz: DynamicUiWidget.QuizCard,
    onSelectOption: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "QUIZ · ${quiz.question}",
            color = ObsidianColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 17.sp
        )
        quiz.options.forEachIndexed { idx, opt ->
            val isSelected = quiz.selectedIndex == idx
            val isCorrect = quiz.correctIndex == idx
            val showAnswer = quiz.selectedIndex != null && quiz.correctIndex >= 0
            val suffix = when {
                showAnswer && isCorrect -> " · ✓ 정답"
                showAnswer && isSelected && !isCorrect -> " · × 다시 생각해보세요"
                isSelected -> " · 선택됨"
                else -> ""
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            isSelected -> ObsidianColors.Accent
                            showAnswer && isCorrect -> ObsidianColors.SurfaceTop
                            else -> ObsidianColors.Surface
                        }
                    )
                    .border(
                        1.dp,
                        if (isSelected || (showAnswer && isCorrect)) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                        RoundedCornerShape(9.dp)
                    )
                    .clickable { onSelectOption(idx) }
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = "${idx + 1}. $opt$suffix",
                    color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontWeight = if (isSelected || (showAnswer && isCorrect)) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SseInteractiveChecklistRow(
    item: DynamicUiWidget.ChecklistItem,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (item.checked) ObsidianColors.Accent else ObsidianColors.Surface)
                .border(1.2.dp, ObsidianColors.Accent, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (item.checked) {
                Text(
                    text = "✓",
                    color = ObsidianColors.OnAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        Text(
            text = item.label,
            color = if (item.checked) ObsidianColors.TextMuted else ObsidianColors.TextPrimary,
            fontSize = 12.sp,
            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f)
        )
    }
}

// =============================================================================
// 2. Human + AI Co-Drawing Interactive Canvas (In-App & Cross-App Overlay)
// =============================================================================

/**
 * Resolution-independent Jetpack Compose vector canvas rendering BOTH Human strokes and
 * animated AI co-drawing primitives (`SPOTLIGHT_RING`, `ARROW`, `HIGHLIGHT_BOX`, `CALLOUT_BADGE`).
 *
 * - Set [showDotGrid] = `true` for the in-app whiteboard (`Paper` / `Carbon` dot-grid canvas).
 * - Set [showDotGrid] = `false` for the transparent out-of-app `SYSTEM_ALERT_WINDOW` overlay over other apps.
 * - Set [isInteractive] = `false` when the out-of-app overlay is in `PASSTHROUGH_WATCH` mode.
 */
@Composable
fun CoDrawingInteractiveCanvas(
    canvasState: CoDrawingCanvasState,
    isInteractive: Boolean,
    showDotGrid: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val primaryInk = ObsidianColors.Accent
    val secondaryInk = ObsidianColors.TextSecondary
    val surfaceColor = ObsidianColors.Surface
    val onAccentColor = ObsidianColors.OnAccent
    val dotColor = ObsidianColors.DotGridColor

    // Continuous pulsing factor for AI spotlight rings (strictly achromatic luminance pulse)
    val aiTransition = rememberInfiniteTransition(label = "ai_codraw_pulse")
    val aiPulse by aiTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1350, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ai_ring_pulse"
    )

    // Progressive reveal factor whenever a new AI element arrives
    val revealProgress = remember(canvasState.revision) { Animatable(0f) }
    LaunchedEffect(canvasState.revision) {
        revealProgress.snapTo(0.25f)
        revealProgress.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
    }

    BoxWithConstraints(
        modifier = modifier
            .then(
                if (showDotGrid) {
                    Modifier.background(ObsidianColors.Background)
                } else {
                    Modifier.background(Color.Transparent)
                }
            )
            .then(
                if (isInteractive && !showDotGrid) {
                    // Subtle monochrome active-drawing frame around the live screen
                    Modifier.border(2.dp, primaryInk.copy(alpha = 0.72f))
                } else {
                    Modifier
                }
            )
    ) {
        val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val heightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }

        val drawModifier = if (isInteractive) {
            Modifier
                .fillMaxSize()
                .pointerInput(canvasState.selectedTool, widthPx, heightPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            CoDrawingCanvasEngine.beginHumanStroke(
                                nx = (offset.x / widthPx).coerceIn(0f, 1f),
                                ny = (offset.y / heightPx).coerceIn(0f, 1f)
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val pos = change.position
                            CoDrawingCanvasEngine.appendHumanStrokePoint(
                                nx = (pos.x / widthPx).coerceIn(0f, 1f),
                                ny = (pos.y / heightPx).coerceIn(0f, 1f)
                            )
                        },
                        onDragEnd = {
                            CoDrawingCanvasEngine.finishHumanStroke()
                        },
                        onDragCancel = {
                            CoDrawingCanvasEngine.finishHumanStroke()
                        }
                    )
                }
        } else {
            Modifier.fillMaxSize()
        }

        val allToDraw = remember(canvasState.visibleElements, canvasState.activeDraft) {
            val draft = canvasState.activeDraft
            if (draft != null) canvasState.visibleElements + draft else canvasState.visibleElements
        }

        Canvas(modifier = drawModifier) {
            if (showDotGrid) {
                val spacing = 22.dp.toPx()
                val radius = 1.15.dp.toPx()
                var y = spacing
                while (y < size.height) {
                    var x = spacing
                    while (x < size.width) {
                        drawCircle(color = dotColor, radius = radius, center = Offset(x, y))
                        x += spacing
                    }
                    y += spacing
                }
            }

            allToDraw.forEachIndexed { idx, elem ->
                val isNewest = idx == allToDraw.lastIndex && elem.author == CoDrawAuthor.AI
                val animFactor = if (isNewest) revealProgress.value else 1f
                drawCoDrawElement(
                    elem = elem,
                    primaryInk = primaryInk,
                    secondaryInk = secondaryInk,
                    surfaceBackdrop = surfaceColor,
                    aiPulse = aiPulse,
                    reveal = animFactor
                )
            }
        }

        // Crisp Compose Callout Labels & Step Badges anchored in normalized viewport space
        allToDraw.forEach { elem ->
            if (elem.label.isNotBlank() || elem.stepBadge.isNotBlank()) {
                val anchor = elem.points.firstOrNull() ?: return@forEach
                val anchorX = (anchor.nx * widthPx).roundToInt()
                val anchorY = (anchor.ny * heightPx).roundToInt()
                val offsetYShift = if (elem.kind == CoDrawToolKind.SPOTLIGHT_RING) {
                    (elem.radiusNorm * min(widthPx, heightPx)).roundToInt() + with(density) { 8.dp.roundToPx() }
                } else {
                    with(density) { (-30).dp.roundToPx() }
                }
                val clampedX = anchorX.coerceIn(
                    with(density) { 12.dp.roundToPx() },
                    (widthPx - with(density) { 168.dp.toPx() }).roundToInt().coerceAtLeast(12)
                )
                val clampedY = (anchorY + offsetYShift).coerceIn(
                    with(density) { 12.dp.roundToPx() },
                    (heightPx - with(density) { 44.dp.toPx() }).roundToInt().coerceAtLeast(12)
                )

                Row(
                    modifier = Modifier
                        .offset { IntOffset(clampedX, clampedY) }
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (elem.author == CoDrawAuthor.AI) primaryInk else surfaceColor)
                        .border(1.2.dp, primaryInk, RoundedCornerShape(999.dp))
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    if (elem.stepBadge.isNotBlank()) {
                        Text(
                            text = elem.stepBadge,
                            color = if (elem.author == CoDrawAuthor.AI) primaryInk else onAccentColor,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (elem.author == CoDrawAuthor.AI) onAccentColor else primaryInk)
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    if (elem.label.isNotBlank()) {
                        Text(
                            text = elem.label,
                            color = if (elem.author == CoDrawAuthor.AI) onAccentColor else primaryInk,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawCoDrawElement(
    elem: CoDrawElement,
    primaryInk: Color,
    secondaryInk: Color,
    surfaceBackdrop: Color,
    aiPulse: Float,
    reveal: Float
) {
    val pts = elem.points.map { Offset(it.nx * size.width, it.ny * size.height) }
    if (pts.isEmpty()) return

    val strokePx = elem.strokeWidthDp.dp.toPx()
    val dashEffect = if (elem.dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f) else null
    val baseColor = if (elem.author == CoDrawAuthor.AI) primaryInk else primaryInk

    when (elem.kind) {
        CoDrawToolKind.FREEHAND_PEN, CoDrawToolKind.CIRCLE_LASSO -> {
            val path = buildSmoothQuadraticPath(pts)
            // Subtle light halo under stroke so strokes stay readable over both dark and light third-party apps
            drawPath(
                path = path,
                color = surfaceBackdrop.copy(alpha = 0.55f),
                style = Stroke(width = strokePx + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = baseColor,
                style = Stroke(
                    width = strokePx,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = dashEffect
                )
            )
        }
        CoDrawToolKind.HIGHLIGHTER_BAND -> {
            val path = buildSmoothQuadraticPath(pts)
            drawPath(
                path = path,
                color = secondaryInk.copy(alpha = 0.28f),
                style = Stroke(width = strokePx.coerceAtLeast(14.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        CoDrawToolKind.ARROW -> {
            if (pts.size >= 2) {
                val start = pts.first()
                val rawEnd = pts.last()
                val end = Offset(
                    start.x + (rawEnd.x - start.x) * reveal,
                    start.y + (rawEnd.y - start.y) * reveal
                )
                drawLine(
                    color = surfaceBackdrop.copy(alpha = 0.65f),
                    start = start,
                    end = end,
                    strokeWidth = strokePx + 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = baseColor,
                    start = start,
                    end = end,
                    strokeWidth = strokePx,
                    cap = StrokeCap.Round,
                    pathEffect = dashEffect
                )
                // Arrowhead wings
                val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val headLen = 15.dp.toPx()
                val wing1 = Offset(
                    (end.x - headLen * cos(angle - Math.PI / 6.0)).toFloat(),
                    (end.y - headLen * sin(angle - Math.PI / 6.0)).toFloat()
                )
                val wing2 = Offset(
                    (end.x - headLen * cos(angle + Math.PI / 6.0)).toFloat(),
                    (end.y - headLen * sin(angle + Math.PI / 6.0)).toFloat()
                )
                drawLine(color = baseColor, start = end, end = wing1, strokeWidth = strokePx, cap = StrokeCap.Round)
                drawLine(color = baseColor, start = end, end = wing2, strokeWidth = strokePx, cap = StrokeCap.Round)
            }
        }
        CoDrawToolKind.SPOTLIGHT_RING -> {
            val center = pts.first()
            val baseRadius = elem.radiusNorm * min(size.width, size.height) * reveal
            // Expanding pulse halo
            val haloRadius = baseRadius * (1f + 0.38f * aiPulse)
            val haloAlpha = (1f - aiPulse) * 0.45f
            drawCircle(
                color = primaryInk.copy(alpha = haloAlpha),
                radius = haloRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            // Inner translucent wash + crisp backdrop contrast ring
            drawCircle(
                color = primaryInk.copy(alpha = 0.10f),
                radius = baseRadius,
                center = center
            )
            drawCircle(
                color = surfaceBackdrop.copy(alpha = 0.72f),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 5.dp.toPx())
            )
            drawCircle(
                color = primaryInk,
                radius = baseRadius,
                center = center,
                style = Stroke(width = 2.6.dp.toPx(), pathEffect = dashEffect)
            )
        }
        CoDrawToolKind.HIGHLIGHT_BOX -> {
            if (pts.size >= 2) {
                val left = min(pts[0].x, pts[1].x)
                val top = min(pts[0].y, pts[1].y)
                val width = kotlin.math.abs(pts[1].x - pts[0].x) * reveal
                val height = kotlin.math.abs(pts[1].y - pts[0].y) * reveal
                val cr = CornerRadius(12.dp.toPx(), 12.dp.toPx())
                drawRoundRect(
                    color = primaryInk.copy(alpha = 0.10f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = cr
                )
                drawRoundRect(
                    color = surfaceBackdrop.copy(alpha = 0.65f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = cr,
                    style = Stroke(width = strokePx + 2.5.dp.toPx())
                )
                drawRoundRect(
                    color = primaryInk,
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = cr,
                    style = Stroke(width = strokePx, pathEffect = dashEffect)
                )
            }
        }
        CoDrawToolKind.CALLOUT_BADGE -> {
            val pt = pts.first()
            drawCircle(color = surfaceBackdrop.copy(alpha = 0.85f), radius = 7.dp.toPx(), center = pt)
            drawCircle(color = primaryInk, radius = 4.5.dp.toPx(), center = pt)
        }
    }
}

private fun buildSmoothQuadraticPath(pts: List<Offset>): Path {
    val path = Path()
    if (pts.isEmpty()) return path
    path.moveTo(pts.first().x, pts.first().y)
    if (pts.size == 1) {
        path.lineTo(pts.first().x + 0.5f, pts.first().y + 0.5f)
        return path
    }
    for (i in 1 until pts.size - 1) {
        val midX = (pts[i].x + pts[i + 1].x) / 2f
        val midY = (pts[i].y + pts[i + 1].y) / 2f
        path.quadraticTo(pts[i].x, pts[i].y, midX, midY)
    }
    path.lineTo(pts.last().x, pts.last().y)
    return path
}

// =============================================================================
// 3. Co-Drawing Toolbar Pill & In-App Studio Modal Sheet
// =============================================================================

@Composable
fun CoDrawingToolbarPill(
    canvasState: CoDrawingCanvasState,
    onSubmitToAi: (() -> Unit)? = null,
    onLaunchCrossAppOverlay: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tools = remember {
        listOf(
            CoDrawToolKind.FREEHAND_PEN,
            CoDrawToolKind.HIGHLIGHTER_BAND,
            CoDrawToolKind.ARROW,
            CoDrawToolKind.CIRCLE_LASSO
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tools.forEach { tool ->
            val selected = canvasState.selectedTool == tool
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                    .border(
                        1.dp,
                        if (selected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { CoDrawingCanvasEngine.selectTool(tool) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = tool.labelKo,
                    color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (canvasState.canUndo) {
            SseMiniChip(label = "↩ 실행취소", onClick = { CoDrawingCanvasEngine.undo() })
        }
        if (canvasState.canRedo) {
            SseMiniChip(label = "↪ 다시실행", onClick = { CoDrawingCanvasEngine.redo() })
        }
        SseMiniChip(
            label = if (canvasState.showAiLayer) "AI 표시 ON" else "AI 표시 OFF",
            onClick = { CoDrawingCanvasEngine.toggleLayerVisibility(CoDrawAuthor.AI) }
        )
        if (canvasState.elements.isNotEmpty()) {
            SseMiniChip(label = "지우기", onClick = { CoDrawingCanvasEngine.clear() })
        }
        if (onLaunchCrossAppOverlay != null) {
            SseMiniChip(label = "타 앱 위 띄우기 ↗", onClick = onLaunchCrossAppOverlay)
        }
        if (onSubmitToAi != null) {
            SseMiniChip(label = "AI와 함께 풀기 ↗", emphasized = true, onClick = onSubmitToAi)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoDrawingStudioModalSheet(
    onDismiss: () -> Unit,
    onSubmitCoDrawingPrompt: (String) -> Unit,
    onLaunchCrossAppOverlay: () -> Unit,
    onCitationClick: ((String) -> Unit)? = null,
    onSpeakText: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val canvasState by CoDrawingCanvasEngine.state.collectAsState()
    val sseState by DynamicUiEventBus.surfaceState.collectAsState()
    var promptText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianColors.Surface,
        contentColor = ObsidianColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "함께 그리기 · Live SSE Co-Draw Studio",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "내 스케치 ${canvasState.humanElementCount}개 · AI 표시 ${canvasState.aiElementCount}개",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                SseMiniChip(
                    label = "타 앱 위 오버레이 ↗",
                    emphasized = true,
                    onClick = onLaunchCrossAppOverlay
                )
            }

            // Interactive Dot-Grid Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.2.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(16.dp))
            ) {
                CoDrawingInteractiveCanvas(
                    canvasState = canvasState,
                    isInteractive = true,
                    showDotGrid = true,
                    modifier = Modifier.fillMaxSize()
                )
                if (canvasState.elements.isEmpty() && canvasState.activeDraft == null) {
                    Text(
                        text = "손가락으로 그림·화살표·원형 표시를 그리면\nAI가 좌표를 이해하고 같은 캔버스에 함께 표시해요",
                        color = ObsidianColors.TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 24.dp)
                    )
                }
            }

            CoDrawingToolbarPill(
                canvasState = canvasState,
                onSubmitToAi = {
                    val q = promptText.trim().ifBlank { "방금 표시한 영역을 분석하고 핵심 위치를 함께 표시해줘" }
                    onSubmitCoDrawingPrompt(q)
                    promptText = ""
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    placeholder = {
                        Text(
                            text = "표시한 부분에 대해 물어보거나 AI에게 그리라고 해보세요...",
                            fontSize = 12.sp,
                            color = ObsidianColors.TextMuted
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ObsidianColors.Accent,
                        unfocusedBorderColor = ObsidianColors.GlassBorder,
                        focusedTextColor = ObsidianColors.TextPrimary,
                        unfocusedTextColor = ObsidianColors.TextPrimary
                    )
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.Accent)
                        .clickable {
                            val q = promptText.trim().ifBlank { "내가 표시한 부분을 설명하고 다음 단계를 화살표로 그려줘" }
                            onSubmitCoDrawingPrompt(q)
                            promptText = ""
                        }
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "전송",
                        color = ObsidianColors.OnAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            if (sseState.events.isNotEmpty() || sseState.widgets.isNotEmpty() || sseState.phase == SseStreamPhase.STREAMING) {
                HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
                SseLiveDynamicUiSurface(
                    surfaceState = sseState,
                    compactOverlayMode = true,
                    onCitationClick = onCitationClick,
                    onSpeakText = onSpeakText,
                    onAskAboutItem = onSubmitCoDrawingPrompt
                )
            }
        }
    }
}

// =============================================================================
// 4. Out-of-App Cross-App Floating Control Pill (Layer 2 Compose Overlay)
// =============================================================================

@Composable
fun CrossAppCoDrawControlPill(
    canvasState: CoDrawingCanvasState,
    sseState: DynamicUiSurfaceState,
    onToggleMode: () -> Unit,
    onToggleCard: () -> Unit,
    onAskWithSketch: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDrawing = canvasState.overlayMode == CrossAppOverlayMode.CO_DRAW_ACTIVE
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.Surface)
            .border(1.4.dp, ObsidianColors.Accent, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mode Toggle: PASSTHROUGH_WATCH <-> CO_DRAW_ACTIVE
        SseMiniChip(
            label = if (isDrawing) "✎ 그리기 중" else "터치 통과",
            emphasized = isDrawing,
            onClick = onToggleMode
        )

        // Toggle SSE Floating Card visibility
        val hasLiveCard = sseState.events.isNotEmpty() || sseState.widgets.isNotEmpty() || sseState.phase == SseStreamPhase.STREAMING
        if (hasLiveCard) {
            SseMiniChip(
                label = if (canvasState.showFloatingDynamicCard) "보드 숨김" else "보드 보기",
                onClick = onToggleCard
            )
        }

        // Send current screen + sketch to AI
        SseMiniChip(
            label = "AI 질문",
            emphasized = !isDrawing && canvasState.humanElementCount > 0,
            onClick = onAskWithSketch
        )

        if (canvasState.elements.isNotEmpty()) {
            SseMiniChip(label = "지우기", onClick = onClear)
        }

        SseMiniChip(label = "✕", onClick = onClose)
    }
}
