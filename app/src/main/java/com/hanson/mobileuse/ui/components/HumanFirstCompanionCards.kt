package com.hanson.mobileuse.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.ActionReceipt
import com.hanson.mobileuse.agent.CallConciergePlan
import com.hanson.mobileuse.agent.CallConciergePlanner
import com.hanson.mobileuse.agent.ClipboardQuickSuggestion
import com.hanson.mobileuse.agent.DailyBriefSnapshot
import com.hanson.mobileuse.agent.FirestoreEditionMode
import com.hanson.mobileuse.agent.FirestoreSyncUiState
import com.hanson.mobileuse.agent.HumanMemoryBubble
import com.hanson.mobileuse.agent.InteractiveMorphEngine
import com.hanson.mobileuse.agent.InteractiveMorphSurface
import com.hanson.mobileuse.agent.MorphKind
import com.hanson.mobileuse.agent.PersonalRoutine
import com.hanson.mobileuse.agent.UserExperienceMode
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1. Everyday-human & Studio dual-mode top header bar.
 * Strictly achromatic Paper/Carbon styling with natural Korean labels.
 */
@Composable
fun HumanExperienceModeHeaderBar(
    presenceGreeting: String,
    activeMode: UserExperienceMode,
    onSelectMode: (UserExperienceMode) -> Unit,
    isVoiceListening: Boolean,
    onToggleVoice: () -> Unit,
    onToggleContrast: () -> Unit,
    onOpenPickleHub: () -> Unit,
    onOpenMoreMenu: () -> Unit,
    modifier: Modifier = Modifier,
    isMoreMenuExpanded: Boolean = false,
    onDismissMoreMenu: () -> Unit = {},
    overflowMenuContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianColors.Background.copy(alpha = 0.96f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Accent)
                )
                Text(
                    text = presenceGreeting.ifBlank { "안녕하세요, 오늘도 편하게 말씀하세요" },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isVoiceListening) ObsidianColors.Accent else ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { onToggleVoice() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "음성 대화",
                        tint = if (isVoiceListening) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { onToggleContrast() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Contrast,
                        contentDescription = "화면 명암 전환",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { onOpenPickleHub() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "보안·일정 허브",
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
                            .clickable { onOpenMoreMenu() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "더보기 메뉴",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    if (overflowMenuContent != null) {
                        DropdownMenu(
                            expanded = isMoreMenuExpanded,
                            onDismissRequest = onDismissMoreMenu,
                            modifier = Modifier
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp)),
                            content = overflowMenuContent
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                UserExperienceMode.entries.forEach { mode ->
                    val selected = mode == activeMode
                    val label = when (mode) {
                        UserExperienceMode.SIMPLE_HUMAN -> "일상"
                        UserExperienceMode.STUDIO_PRO -> "스튜디오"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .clickable { onSelectMode(mode) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianColors.Divider, thickness = 1.dp)
    }
}

/**
 * 2. Human-friendly 3-step readiness checklist shown only when setup is incomplete.
 */
@Composable
fun OneTapGuidedReadinessCard(
    a11yReady: Boolean,
    hudReady: Boolean,
    aiReady: Boolean,
    onOneTouchSetup: () -> Unit,
    onOpenApiKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (a11yReady && hudReady && aiReady) return

    val readyCount = listOf(a11yReady, hudReady, aiReady).count { it }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassHighlight,
        tactileBottomLip = true,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "바로 시작하기 준비",
                color = ObsidianColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "$readyCount/3 완료",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "한 번만 켜두면 카톡·배달·일정 앱을 대신 처리해 드려요.",
            color = ObsidianColors.TextSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReadinessStepRow(
                stepLabel = "① 화면 도우미",
                ready = a11yReady,
                statusText = if (a11yReady) "연결됨" else "켜기 필요"
            )
            ReadinessStepRow(
                stepLabel = "② 미니창",
                ready = hudReady,
                statusText = if (hudReady) "준비됨" else "허용 필요"
            )
            ReadinessStepRow(
                stepLabel = "③ AI 연결",
                ready = aiReady,
                statusText = if (aiReady) "연결됨" else "키 입력 필요"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.35f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onOneTouchSetup() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "원터치로 바로 시작하기",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box(
                modifier = Modifier
                    .weight(0.75f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .clickable { onOpenApiKey() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI 키 입력",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ReadinessStepRow(
    stepLabel: String,
    ready: Boolean,
    statusText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (ready) ObsidianColors.Accent else ObsidianColors.Surface)
                    .border(
                        width = 1.dp,
                        color = if (ready) ObsidianColors.Accent else ObsidianColors.GlassHighlight,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (ready) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "완료",
                        tint = ObsidianColors.OnAccent,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Text(
                text = stepLabel,
                color = ObsidianColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = statusText,
            color = if (ready) ObsidianColors.TextPrimary else ObsidianColors.TextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 3. Proactive Daily Brief Card with highlights and 1-tap quick action pills.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProactiveDailyBriefCard(
    brief: DailyBriefSnapshot,
    onRunQuickAction: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "오늘의 한슨 브리핑",
                    color = ObsidianColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = brief.headlineKo,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 20.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            DismissPillButton(onClick = onDismiss)
        }

        if (brief.summaryKo.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = brief.summaryKo,
                color = ObsidianColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        }

        if (brief.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                brief.highlights.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Text(
                            text = "•",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        if (brief.quickActions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                brief.quickActions.forEach { action ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                            .clickable { onRunQuickAction(action.goalPrompt) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (action.badgeKo.isNotBlank()) {
                            Text(
                                text = action.badgeKo,
                                color = ObsidianColors.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = action.labelKo,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4. Smart Clipboard Quick-Action Banner for copied links, schedules, addresses, and phone numbers.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipboardQuickActionBanner(
    suggestions: List<ClipboardQuickSuggestion>,
    onRunSuggestion: (ClipboardQuickSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (suggestions.isEmpty()) return

    val previewText = suggestions.firstOrNull { it.previewText.isNotBlank() }?.previewText.orEmpty()

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassHighlight,
        tactileBottomLip = true,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📋 방금 복사한 내용으로 바로 할까요?",
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            DismissPillButton(onClick = onDismiss)
        }

        if (previewText.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = previewText,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { suggestion ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onRunSuggestion(suggestion) }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (suggestion.badgeKo.isNotBlank()) {
                        Text(
                            text = suggestion.badgeKo,
                            color = ObsidianColors.OnAccent.copy(alpha = 0.78f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = suggestion.titleKo,
                        color = ObsidianColors.OnAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

/**
 * 5. Transparent preference memory bubbles card with inline creation, toggle, and removal.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryBubblesStripCard(
    bubbles: List<HumanMemoryBubble>,
    onAddBubble: (String) -> Unit,
    onToggleBubble: (String) -> Unit,
    onRemoveBubble: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draftBubble by rememberSaveable { mutableStateOf("") }

    val submitDraft = {
        val cleaned = draftBubble.trim()
        if (cleaned.isNotEmpty()) {
            onAddBubble(cleaned)
            draftBubble = ""
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🫧 한슨이 기억하는 내 취향",
                color = ObsidianColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${bubbles.count { it.enabled }}개 사용 중",
                color = ObsidianColors.TextMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "눌러서 켜고 끌 수 있고, 원치 않는 기억은 바로 지울 수 있어요.",
            color = ObsidianColors.TextSecondary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (bubbles.isEmpty()) {
            Text(
                text = "아직 저장된 취향이 없어요. 아래 입력칸에 편하게 적어보세요.",
                color = ObsidianColors.TextMuted,
                fontSize = 12.sp
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bubbles.forEach { bubble ->
                    val enabled = bubble.enabled
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (enabled) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .border(
                                width = 1.dp,
                                color = if (enabled) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                shape = RoundedCornerShape(999.dp)
                            )
                            .clickable { onToggleBubble(bubble.id) }
                            .padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${bubble.emoji} ${bubble.factKo}".trim(),
                            color = if (enabled) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                            fontSize = 12.sp,
                            fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                            textDecoration = if (enabled) null else TextDecoration.LineThrough,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { onRemoveBubble(bubble.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "취향 삭제",
                                tint = if (enabled) ObsidianColors.OnAccent.copy(alpha = 0.8f) else ObsidianColors.TextMuted,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HumanInlineTextField(
                value = draftBubble,
                onValueChange = { draftBubble = it },
                placeholder = "+ 취향 직접 추가 (예: 지하철 이동 선호)",
                imeAction = ImeAction.Done,
                onImeAction = submitDraft,
                modifier = Modifier.weight(1f)
            )
            Row(
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (draftBubble.isNotBlank()) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .clickable(enabled = draftBubble.isNotBlank()) { submitDraft() }
                    .padding(horizontal = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "취향 추가",
                    tint = if (draftBubble.isNotBlank()) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "추가",
                    color = if (draftBubble.isNotBlank()) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 6. 1-Tap Personal Routines & Show-Me-Once Demonstration Recorder Card.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PinnedRoutinesAndShowMeCard(
    routines: List<PersonalRoutine>,
    isRecordingDemo: Boolean,
    recordedStepCount: Int,
    onRunRoutine: (PersonalRoutine) -> Unit,
    onRemoveRoutine: (String) -> Unit,
    onStartDemoRecord: () -> Unit,
    onFinishDemoRecord: (String) -> Unit,
    onCancelDemoRecord: () -> Unit,
    modifier: Modifier = Modifier
) {
    var customTitle by rememberSaveable { mutableStateOf("") }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "⚡ 자주 쓰는 내 루틴",
                color = ObsidianColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${routines.size}개",
                color = ObsidianColors.TextMuted,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (routines.isEmpty()) {
            Text(
                text = "아직 고정된 루틴이 없어요. 아래 시연 기능으로 나만의 루틴을 만들어 보세요.",
                color = ObsidianColors.TextMuted,
                fontSize = 12.sp
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                routines.forEach { routine ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                            .combinedClickable(
                                onClick = { onRunRoutine(routine) },
                                onLongClick = { onRemoveRoutine(routine.id) }
                            )
                            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${routine.emoji} ${routine.titleKo}".trim(),
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (routine.stepCount > 1) {
                            Text(
                                text = "${routine.stepCount}단계",
                                color = ObsidianColors.TextMuted,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .clickable { onRemoveRoutine(routine.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "루틴 삭제",
                                tint = ObsidianColors.TextMuted,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = ObsidianColors.Divider, thickness = 1.dp)
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "🎬 직접 보여주고 가르치기",
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
            if (isRecordingDemo) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "● ${recordedStepCount}단계 기록 중",
                        color = ObsidianColors.OnAccent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (!isRecordingDemo) {
            Text(
                text = "원하는 앱에서 직접 한 번만 눌러 보여주면 나만의 1탭 루틴으로 저장해 드려요.",
                color = ObsidianColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                    .clickable { onStartDemoRecord() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "녹화 시작",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "녹화 시작",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Text(
                text = "다른 앱에서 원하는 동작을 직접 진행한 뒤 이름을 입력하고 저장 완료를 누르세요.",
                color = ObsidianColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            HumanInlineTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                placeholder = "루틴 이름 입력 (예: 단골 카페 주문)",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.75f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable {
                            customTitle = ""
                            onCancelDemoRecord()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "취소",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1.25f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .clickable {
                            val title = customTitle.trim()
                            customTitle = ""
                            onFinishDemoRecord(title)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "저장 완료",
                        color = ObsidianColors.OnAccent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

/**
 * 7. Reversible Action Receipt Banner with 1-tap undo pill.
 */
@Composable
fun OneTapUndoReceiptBanner(
    receipt: ActionReceipt?,
    onUndoReceipt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (receipt == null || !receipt.canOneTapUndo || receipt.undone) return

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.Accent,
        tactileBottomLip = true,
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = receipt.summaryKo.ifBlank { "방금 실행한 작업을 되돌릴 수 있어요" },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (receipt.undoHintKo.isNotBlank()) {
                    Text(
                        text = receipt.undoHintKo,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onUndoReceipt(receipt.id) }
                    .padding(horizontal = 13.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "↩ 1탭 되돌리기",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 8. Calm everyday Korean composer bar without technical prefixes or badges.
 */
@Composable
fun HumanFriendlyComposerBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    attachScreenContext: Boolean,
    foregroundAppLabel: String,
    onToggleAttachScreen: () -> Unit,
    isVoiceListening: Boolean,
    onToggleVoice: () -> Unit,
    isRunning: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = inputText.isNotBlank() || attachScreenContext

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = if (canSend) ObsidianColors.GlassHighlight else ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (attachScreenContext) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (attachScreenContext) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .clickable { onToggleAttachScreen() }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "화면 함께 보기",
                    tint = if (attachScreenContext) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (attachScreenContext) {
                        "지금 화면 함께 보기 · ${foregroundAppLabel.ifBlank { "현재 화면" }}"
                    } else {
                        "지금 화면 함께 보기"
                    },
                    color = if (attachScreenContext) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp, max = 120.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (inputText.isEmpty()) {
                Text(
                    text = "한슨에게 편하게 말해보세요 (예: 카톡 답장해줘, 배민 족발 검색)",
                    color = ObsidianColors.TextMuted,
                    fontSize = 13.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp
                )
            }
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp
                ),
                cursorBrush = SolidColor(ObsidianColors.Accent),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (canSend && !isRunning) onSend()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isVoiceListening) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (isVoiceListening) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .clickable { onToggleVoice() }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "음성으로 말하기",
                    tint = if (isVoiceListening) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isVoiceListening) "듣는 중" else "말로 하기",
                    color = if (isVoiceListening) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isRunning) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onStop() }
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "중지",
                        tint = ObsidianColors.OnAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "멈추기",
                        color = ObsidianColors.OnAccent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (canSend) ObsidianColors.Accent else ObsidianColors.SurfaceTop)
                        .clickable(enabled = canSend) { onSend() }
                        .padding(horizontal = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = "보내기",
                        color = if (canSend) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "보내기",
                        tint = if (canSend) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * 9. Interactive Phone Concierge Card preparing call script lines before human-approved dialing.
 */
@Composable
fun CallConciergeCard(
    onApproveAndDial: (calleeLabel: String, phoneNumber: String, purposeKo: String, scriptText: String) -> Unit,
    modifier: Modifier = Modifier,
    initialCalleeLabel: String = "",
    initialPhoneNumber: String = "",
    initialPurposeKo: String = "",
    initialPreferredSlot: String = "",
    onBuildPlan: (callee: String, phone: String, purpose: String, slot: String) -> CallConciergePlan = { callee, phone, purpose, slot ->
        CallConciergePlanner.buildPlan(
            calleeLabel = callee,
            phoneNumber = phone,
            purposeKo = purpose,
            preferredSlot = slot
        )
    }
) {
    var calleeLabel by rememberSaveable(initialCalleeLabel) { mutableStateOf(initialCalleeLabel) }
    var phoneNumber by rememberSaveable(initialPhoneNumber) { mutableStateOf(initialPhoneNumber) }
    var purposeKo by rememberSaveable(initialPurposeKo) { mutableStateOf(initialPurposeKo) }
    var preferredSlot by rememberSaveable(initialPreferredSlot) { mutableStateOf(initialPreferredSlot) }

    val plan = remember(calleeLabel, phoneNumber, purposeKo, preferredSlot) {
        onBuildPlan(
            calleeLabel.trim(),
            phoneNumber.trim(),
            purposeKo.trim(),
            preferredSlot.trim()
        )
    }
    val scriptText = remember(plan) {
        plan.scriptLinesKo.joinToString("\n")
    }
    val canDial = phoneNumber.isNotBlank() || calleeLabel.isNotBlank()

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "전화 도우미",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "전화 도우미 · 말할 내용 준비",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "승인 후 발신",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HumanInlineTextField(
                    value = calleeLabel,
                    onValueChange = { calleeLabel = it },
                    placeholder = "받는 분 / 가게명",
                    modifier = Modifier.weight(1f)
                )
                HumanInlineTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    placeholder = "전화번호 (02-...)",
                    keyboardType = KeyboardType.Phone,
                    modifier = Modifier.weight(1f)
                )
            }
            HumanInlineTextField(
                value = purposeKo,
                onValueChange = { purposeKo = it },
                placeholder = "통화 목적 (예: 내일 저녁 7시 2명 예약)",
                modifier = Modifier.fillMaxWidth()
            )
            HumanInlineTextField(
                value = preferredSlot,
                onValueChange = { preferredSlot = it },
                placeholder = "희망 시간대 (선택 사항)",
                modifier = Modifier.fillMaxWidth()
            )
        }

        AnimatedVisibility(visible = plan.scriptLinesKo.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "미리 준비된 통화 안내 문구",
                    color = ObsidianColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                plan.scriptLinesKo.forEachIndexed { idx, line ->
                    Text(
                        text = "${idx + 1}. $line",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(if (canDial) ObsidianColors.Accent else ObsidianColors.SurfaceTop)
                .clickable(enabled = canDial) {
                    onApproveAndDial(
                        calleeLabel.trim(),
                        phoneNumber.trim(),
                        purposeKo.trim(),
                        scriptText
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Phone,
                contentDescription = null,
                tint = if (canDial) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "승인 후 전화 앱 열기",
                color = if (canDial) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

/**
 * 10. Optional BYO Cloud Firestore Sync Card (Vault-protected API key, zero Vault secret upload).
 */
@Composable
fun FirestoreCloudSyncCard(
    syncState: FirestoreSyncUiState,
    onSaveConfig: (
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String,
        editionMode: FirestoreEditionMode,
        userUid: String
    ) -> Unit,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cfg = syncState.config
    var enabled by rememberSaveable(cfg.enabled) { mutableStateOf(cfg.enabled) }
    var projectId by rememberSaveable(cfg.projectId) { mutableStateOf(cfg.projectId) }
    var applicationId by rememberSaveable(cfg.applicationId) { mutableStateOf(cfg.applicationId) }
    var databaseId by rememberSaveable(cfg.databaseId) {
        mutableStateOf(cfg.databaseId.ifBlank { "(default)" })
    }
    var userUid by rememberSaveable(cfg.userUid) {
        mutableStateOf(cfg.userUid.ifBlank { "local_owner" })
    }
    var editionMode by rememberSaveable(cfg.editionMode) { mutableStateOf(cfg.editionMode) }
    var apiKeyDraft by rememberSaveable { mutableStateOf("") }
    var revealApiKey by remember { mutableStateOf(false) }

    val formattedSyncTime = remember(syncState.lastSyncAtMs) {
        if (syncState.lastSyncAtMs <= 0L) {
            ""
        } else {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(syncState.lastSyncAtMs))
        }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "선택형 클라우드 동기화",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "선택형 클라우드 동기화",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (enabled) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .clickable { enabled = !enabled }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (enabled) "사용 중" else "꺼짐",
                    color = if (enabled) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Vault 비밀키는 업로드되지 않음",
                color = ObsidianColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = syncState.lastStatusKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = buildString {
                    append("동기화 내역: 취향 ${syncState.syncedBubbleCount}개 · 루틴 ${syncState.syncedRoutineCount}개")
                    if (formattedSyncTime.isNotBlank()) {
                        append(" · $formattedSyncTime")
                    }
                },
                color = ObsidianColors.TextSecondary,
                fontSize = 11.5.sp
            )
            Text(
                text = if (syncState.hasApiKeyInVault) {
                    "API 키 상태: 금고(Vault)에 암호화 보관 중"
                } else {
                    "API 키 상태: 아직 입력되지 않음"
                },
                color = ObsidianColors.TextMuted,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HumanInlineTextField(
                    value = projectId,
                    onValueChange = { projectId = it },
                    placeholder = "Project ID",
                    modifier = Modifier.weight(1f)
                )
                HumanInlineTextField(
                    value = databaseId,
                    onValueChange = { databaseId = it },
                    placeholder = "Database ((default))",
                    modifier = Modifier.weight(1f)
                )
            }
            HumanInlineTextField(
                value = applicationId,
                onValueChange = { applicationId = it },
                placeholder = "Application ID",
                modifier = Modifier.fillMaxWidth()
            )
            HumanInlineTextField(
                value = userUid,
                onValueChange = { userUid = it },
                placeholder = "사용자 ID (기본: local_owner)",
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HumanInlineTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    placeholder = if (syncState.hasApiKeyInVault) {
                        "새 API 키 입력 시 교체 (금고 보호 중)"
                    } else {
                        "Firebase API 키 (금고에 암호화 저장)"
                    },
                    visualTransformation = if (revealApiKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    imeAction = ImeAction.Done,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .clickable { revealApiKey = !revealApiKey }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (revealApiKey) "숨김" else "보기",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Standard | Enterprise Edition Toggle Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FirestoreEditionMode.entries.forEach { mode ->
                val selected = editionMode == mode
                val label = when (mode) {
                    FirestoreEditionMode.STANDARD -> "Standard"
                    FirestoreEditionMode.ENTERPRISE -> "Enterprise"
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .clickable { editionMode = mode },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                    .clickable {
                        onSaveConfig(
                            enabled,
                            projectId.trim(),
                            applicationId.trim(),
                            apiKeyDraft.trim(),
                            databaseId.trim().ifBlank { "(default)" },
                            editionMode,
                            userUid.trim().ifBlank { "local_owner" }
                        )
                        apiKeyDraft = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "설정 저장",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .weight(1.1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (!syncState.isSyncing) ObsidianColors.Accent else ObsidianColors.SurfaceTop)
                    .clickable(enabled = !syncState.isSyncing) { onSyncNow() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "지금 동기화",
                    tint = if (!syncState.isSyncing) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (syncState.isSyncing) "동기화 중..." else "지금 동기화",
                    color = if (!syncState.isSyncing) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun DismissPillButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "닫기",
            color = ObsidianColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun HumanInlineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onImeAction: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = ObsidianColors.TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            visualTransformation = visualTransformation,
            textStyle = TextStyle(
                color = ObsidianColors.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium
            ),
            cursorBrush = SolidColor(ObsidianColors.Accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onDone = { onImeAction() },
                onSend = { onImeAction() }
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}


// =========================================================================
// v6.1 Flagship Figma-First Motion UI & NL-to-Interactive Morph Composables
// Mirrors Figma frames 5:3, 5:66, 5:126, 5:183 (ObsidianColors Paper/Carbon)
// =========================================================================

enum class CompanionDrawerTab(val labelKo: String, val emoji: String) {
    BUBBLES("취향", "🫧"),
    ROUTINES("루틴", "⚡"),
    CALL("전화", "📞"),
    CLOUD_SYNC("동기화", "☁️")
}

/**
 * Minimal, breathable Figma TopBar (`5:4`): capsule mode switch on left + 34dp circle icon pills on right.
 */
@Composable
fun FlagshipPresenceTopBar(
    activeMode: UserExperienceMode,
    onSelectMode: (UserExperienceMode) -> Unit,
    isVoiceListening: Boolean,
    onToggleVoice: () -> Unit,
    onToggleContrast: () -> Unit,
    onOpenPickleHub: () -> Unit,
    onOpenMoreMenu: () -> Unit,
    modifier: Modifier = Modifier,
    isMoreMenuExpanded: Boolean = false,
    onDismissMoreMenu: () -> Unit = {},
    overflowMenuContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianColors.Background.copy(alpha = 0.94f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            UserExperienceMode.entries.forEach { mode ->
                val selected = mode == activeMode
                val label = when (mode) {
                    UserExperienceMode.SIMPLE_HUMAN -> "일상"
                    UserExperienceMode.STUDIO_PRO -> "스튜디오"
                }
                val scale by animateFloatAsState(
                    targetValue = if (selected) 1f else 0.97f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 420f),
                    label = "mode_pill_scale"
                )
                Box(
                    modifier = Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .clickable { onSelectMode(mode) }
                        .padding(horizontal = 15.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlagshipCircleIconButton(
                active = isVoiceListening,
                onClick = onToggleVoice,
                contentDescription = "음성 대화",
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
            FlagshipCircleIconButton(
                active = false,
                onClick = onToggleContrast,
                contentDescription = "화면 명암 전환",
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Contrast,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
            FlagshipCircleIconButton(
                active = false,
                onClick = onOpenPickleHub,
                contentDescription = "안전 허브",
                icon = { tint ->
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(15.dp)
                    )
                }
            )
            Box {
                FlagshipCircleIconButton(
                    active = false,
                    onClick = onOpenMoreMenu,
                    contentDescription = "더보기 메뉴",
                    icon = { tint ->
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                if (overflowMenuContent != null) {
                    DropdownMenu(
                        expanded = isMoreMenuExpanded,
                        onDismissRequest = onDismissMoreMenu,
                        modifier = Modifier
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp)),
                        content = overflowMenuContent
                    )
                }
            }
        }
    }
}

@Composable
private fun FlagshipCircleIconButton(
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    icon: @Composable (tint: androidx.compose.ui.graphics.Color) -> Unit
) {
    val bg = if (active) ObsidianColors.Accent else ObsidianColors.Surface
    val tint = if (active) ObsidianColors.OnAccent else ObsidianColors.TextPrimary
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        icon(tint)
    }
}

/**
 * Figma Screen 3 (`5:143`): Morphing Dynamic Execution Island shown cleanly at the top while working.
 */
@Composable
fun DynamicExecutionIslandBanner(
    visible: Boolean,
    statusLineKo: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "island_pulse")
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "island_dot_scale"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 400f),
            initialOffsetY = { -it / 2 }
        ),
        exit = fadeOut(tween(180)) + slideOutVertically(targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.3.dp, ObsidianColors.Accent, RoundedCornerShape(999.dp))
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                    .clip(CircleShape)
                    .background(ObsidianColors.Accent)
            )
            Text(
                text = statusLineKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onStop() }
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "중지",
                    color = ObsidianColors.OnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/**
 * Figma Screen 1 (`5:20`): Breathing Living Presence Orb + Orbiting Satellite + 5 Animated Waveform Bars + Editorial Greeting.
 */
@Composable
fun LivingPresenceHeroStage(
    greeting: String,
    isActive: Boolean,
    isVoiceListening: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "living_orb_loop")
    val haloScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isVoiceListening || isActive) 1.18f else 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isVoiceListening || isActive) 950 else 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_scale"
    )
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "halo_alpha"
    )
    val orbitAngleDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 1800 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isVoiceListening || isActive) 900 else 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave_phase"
    )

    val canvasSize = if (compact) 84.dp else 148.dp
    val coreSize = if (compact) 46.dp else 76.dp
    val inkColor = ObsidianColors.Accent
    val borderStrong = ObsidianColors.GlassHighlight
    val onInk = ObsidianColors.OnAccent

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = if (compact) 6.dp else 18.dp, bottom = if (compact) 4.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)
    ) {
        Box(
            modifier = Modifier.size(canvasSize),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = (size.minDimension * 0.47f) * haloScale
                drawCircle(
                    color = borderStrong.copy(alpha = haloAlpha),
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 1.2.dp.toPx())
                )

                val midRadius = size.minDimension * 0.36f
                drawCircle(
                    color = inkColor.copy(alpha = 0.72f),
                    radius = midRadius,
                    center = center,
                    style = Stroke(
                        width = 1.2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), phase = 0f)
                    )
                )

                val rad = (orbitAngleDeg - 90f) * PI.toFloat() / 180f
                val satOffset = Offset(
                    x = center.x + midRadius * cos(rad),
                    y = center.y + midRadius * sin(rad)
                )
                drawCircle(
                    color = inkColor,
                    radius = if (compact) 3.dp.toPx() else 4.dp.toPx(),
                    center = satOffset
                )
            }

            Box(
                modifier = Modifier
                    .size(coreSize)
                    .clip(CircleShape)
                    .background(inkColor),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.5.dp else 3.5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val baseHeights = listOf(14f, 24f, 32f, 22f, 13f)
                    baseHeights.forEachIndexed { idx, base ->
                        val ratio = 0.58f + 0.48f * ((sin(wavePhase + idx * 0.85f) + 1f) / 2f)
                        val barH = ((if (compact) base * 0.58f else base) * ratio).dp
                        Box(
                            modifier = Modifier
                                .width(if (compact) 2.5.dp else 3.5.dp)
                                .height(barH)
                                .clip(RoundedCornerShape(999.dp))
                                .background(onInk)
                        )
                    }
                }
            }
        }

        if (!compact) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = greeting.ifBlank { "편안한 저녁이에요" },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 31.sp
                )
                Text(
                    text = if (isVoiceListening) {
                        "듣고 있어요 · 말한 내용이 바로 살아있는 화면으로 바뀌어요"
                    } else {
                        "말 한마디를 살아있는 화면과 행동으로 바꿔드려요"
                    },
                    color = ObsidianColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

private enum class FocusDeckSlide { BRIEF, CLIPBOARD, READINESS }

/**
 * Figma Screen 1 (`5:34`): Single swipe-paginated contextual Focus Card (`1 / N`) eliminating 5-card clutter.
 */
@Composable
fun ContextualFocusDeckCard(
    brief: DailyBriefSnapshot,
    clipboardSuggestions: List<ClipboardQuickSuggestion>,
    a11yReady: Boolean,
    hudReady: Boolean,
    aiReady: Boolean,
    onRunGoal: (String) -> Unit,
    onRunClipboard: (ClipboardQuickSuggestion) -> Unit,
    onDismissClipboard: () -> Unit,
    onOneTouchSetup: () -> Unit,
    onOpenApiKey: () -> Unit,
    onOpenMorphPreset: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val slides = remember(clipboardSuggestions, a11yReady, hudReady, aiReady) {
        buildList {
            if (clipboardSuggestions.isNotEmpty()) add(FocusDeckSlide.CLIPBOARD)
            add(FocusDeckSlide.BRIEF)
            if (!(a11yReady && hudReady && aiReady)) add(FocusDeckSlide.READINESS)
        }
    }
    var pageIdx by rememberSaveable { mutableIntStateOf(0) }
    val safeIdx = pageIdx.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
    val activeSlide = slides.getOrElse(safeIdx) { FocusDeckSlide.BRIEF }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.Accent,
        tactileBottomLip = true,
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val tagLabel = when (activeSlide) {
                FocusDeckSlide.BRIEF -> "오늘 브리핑 · D-Artemis 정렬됨"
                FocusDeckSlide.CLIPBOARD -> "방금 복사한 내용 · 바로 실행"
                FocusDeckSlide.READINESS -> "시작 준비 · ${listOf(a11yReady, hudReady, aiReady).count { it }}/3 완료"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Text(
                    text = tagLabel,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (slides.size > 1) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${safeIdx + 1} / ${slides.size}",
                        color = ObsidianColors.TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "‹",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { pageIdx = if (safeIdx > 0) safeIdx - 1 else slides.lastIndex }
                            .padding(horizontal = 4.dp)
                    )
                    Text(
                        text = "›",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { pageIdx = (safeIdx + 1) % slides.size }
                            .padding(horizontal = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        AnimatedContent(
            targetState = activeSlide,
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                    initialOffsetY = { it / 6 }
                )) togetherWith (fadeOut(tween(140)))
            },
            label = "focus_deck_slide"
        ) { slide ->
            when (slide) {
                FocusDeckSlide.BRIEF -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = brief.headlineKo,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 22.sp
                        )
                        Text(
                            text = brief.summaryKo,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.SurfaceHigh)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.68f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Accent)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Accent)
                                    .clickable {
                                        onOpenMorphPreset("성수동 저녁 4명 148,000원 더치페이하고 코스 영상 보드 만들어줘")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "코스 UI로 변형하기",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1
                                )
                            }
                            val firstQuick = brief.quickActions.firstOrNull()
                            if (firstQuick != null) {
                                Box(
                                    modifier = Modifier
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.SurfaceHigh)
                                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                        .clickable { onRunGoal(firstQuick.goalPrompt) }
                                        .padding(horizontal = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = firstQuick.labelKo,
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                FocusDeckSlide.CLIPBOARD -> {
                    val topClip = clipboardSuggestions.firstOrNull()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = topClip?.previewText?.ifBlank { "복사한 링크·주소를 바로 실행할 수 있어요" }
                                ?: "복사한 내용을 바로 처리할까요?",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (topClip != null) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(ObsidianColors.Accent)
                                        .clickable { onRunClipboard(topClip) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = topClip.titleKo,
                                        color = ObsidianColors.OnAccent,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                    .clickable { onDismissClipboard() }
                                    .padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "닫기",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                FocusDeckSlide.READINESS -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "한 번만 켜두면 카톡·배달·일정을 대신 처리해요",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "화면 도우미" to a11yReady,
                                "미니창" to hudReady,
                                "AI 연결" to aiReady
                            ).forEach { (label, ok) ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (ok) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = if (ok) "✓ $label" else label,
                                        color = if (ok) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Accent)
                                    .clickable { onOneTouchSetup() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "원터치 권한 준비",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                    .clickable { onOpenApiKey() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "AI 키",
                                    color = ObsidianColors.TextPrimary,
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
}

/**
 * Figma Screen 2 (`5:91`): NL -> Live Interactive UI + Motion Storyboard Moat Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InteractiveMorphSurfaceCard(
    surface: InteractiveMorphSurface,
    onUpdateSurface: (InteractiveMorphSurface) -> Unit,
    onExecuteSurface: (InteractiveMorphSurface) -> Unit,
    onOpenInMiniBoard: (InteractiveMorphSurface) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var storyboardPlaying by remember(surface.id) { mutableStateOf(true) }
    val infiniteTransition = rememberInfiniteTransition(label = "sb_playhead")
    val autoProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "playhead_ratio"
    )
    var pausedProgress by remember(surface.id) { mutableFloatStateOf(0.35f) }
    val activeProgress = if (storyboardPlaying) autoProgress else pausedProgress

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassHighlight,
        tactileBottomLip = true,
        contentPadding = PaddingValues(18.dp)
    ) {
        // Synthesis confirmation pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "자연어 → ${surface.badgeKo} (${surface.synthesisMs}ms)",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            DismissPillButton(onClick = onDismiss)
        }

        if (surface.stops.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            val inkColor = ObsidianColors.Accent
            val mutedColor = ObsidianColors.TextMuted
            val surfaceBg = ObsidianColors.Background

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(surfaceBg)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
                    .clickable {
                        if (storyboardPlaying) pausedProgress = autoProgress
                        storyboardPlaying = !storyboardPlaying
                    }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = if (storyboardPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "재생 전환",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = surface.storyboardCaptionKo.ifBlank { "MOTION STORYBOARD" },
                            color = ObsidianColors.TextPrimary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "00:${String.format(Locale.US, "%02d", (activeProgress * 12).toInt())} / 00:12",
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    val p0 = Offset(20.dp.toPx(), size.height * 0.72f)
                    val p1 = Offset(size.width * 0.5f, size.height * 0.44f)
                    val p2 = Offset(size.width - 20.dp.toPx(), size.height * 0.25f)
                    val routePath = Path().apply {
                        moveTo(p0.x, p0.y)
                        cubicTo(size.width * 0.28f, size.height * 0.14f, size.width * 0.72f, size.height * 0.86f, p2.x, p2.y)
                    }
                    drawPath(
                        path = routePath,
                        color = inkColor.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()), 0f)
                        )
                    )
                    listOf(p0, p1, p2).forEach { pt ->
                        drawCircle(color = inkColor, radius = 5.dp.toPx(), center = pt)
                    }
                    // Scrub track at bottom of canvas
                    val trackY = size.height - 4.dp.toPx()
                    drawLine(
                        color = mutedColor.copy(alpha = 0.28f),
                        start = Offset(4.dp.toPx(), trackY),
                        end = Offset(size.width - 4.dp.toPx(), trackY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    val headX = 4.dp.toPx() + (size.width - 8.dp.toPx()) * activeProgress
                    drawLine(
                        color = inkColor,
                        start = Offset(4.dp.toPx(), trackY),
                        end = Offset(headX, trackY),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(color = inkColor, radius = 5.5.dp.toPx(), center = Offset(headX, trackY))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    surface.stops.take(3).forEach { stop ->
                        Column {
                            Text(
                                text = stop.placeLabel,
                                color = ObsidianColors.TextPrimary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stop.timeLabel,
                                color = ObsidianColors.TextMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (surface.kind == MorphKind.SPLIT_BILL) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = surface.titleKo,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = InteractiveMorphEngine.formatWon(surface.perPersonWon),
                        color = ObsidianColors.TextPrimary,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp
                    )
                }

                // Live tactile − N명 + Stepper
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (surface.headcount > 1) {
                                    onUpdateSurface(surface.copy(headcount = surface.headcount - 1))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("−", color = ObsidianColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "${surface.headcount}명",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (surface.headcount < 30) {
                                    onUpdateSurface(surface.copy(headcount = surface.headcount + 1))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("+", color = ObsidianColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (surface.roundTo100Won) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable { onUpdateSurface(surface.copy(roundTo100Won = !surface.roundTo100Won)) }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (surface.roundTo100Won) "✓ 100원 깔끔 정산" else "원 단위 정확 계산",
                        color = if (surface.roundTo100Won) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                surface.appliedBubbleLabels.forEach { label ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = label,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        } else {
            Text(
                text = surface.titleKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = surface.subtitleKo,
                color = ObsidianColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )

            if (surface.appliedBubbleLabels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    surface.appliedBubbleLabels.forEach { b ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 9.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = b,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (surface.selectableItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    surface.selectableItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (item.selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                                .clickable {
                                    val updated = surface.selectableItems.map { candidate ->
                                        if (surface.kind == MorphKind.FOOD_CURATOR || surface.kind == MorphKind.MEDIA_STORYBOARD) {
                                            candidate.copy(selected = candidate.id == item.id)
                                        } else {
                                            if (candidate.id == item.id) candidate.copy(selected = !candidate.selected) else candidate
                                        }
                                    }
                                    onUpdateSurface(surface.copy(selectableItems = updated))
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    color = if (item.selected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (item.subtitle.isNotBlank()) {
                                    Text(
                                        text = item.subtitle,
                                        color = if (item.selected) ObsidianColors.OnAccent.copy(alpha = 0.78f) else ObsidianColors.TextSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            if (item.badge.isNotBlank()) {
                                Text(
                                    text = item.badge,
                                    color = if (item.selected) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1.3f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onExecuteSurface(surface) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = surface.primaryActionLabelKo,
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .weight(0.75f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .clickable { onOpenInMiniBoard(surface) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "보드 저장",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Figma Screen 3 (`5:169`): Compact high-contrast spring Undo Pill anchored above composer.
 */
@Composable
fun FloatingOneTapUndoPill(
    receipt: ActionReceipt?,
    onUndoReceipt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = receipt != null && receipt.canOneTapUndo && !receipt.undone
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            initialOffsetY = { it }
        ),
        exit = fadeOut(tween(150)) + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        if (receipt != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Undo,
                    contentDescription = "되돌리기",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = receipt.summaryKo.ifBlank { "방금 실행한 작업을 되돌릴 수 있어요" },
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Background)
                        .clickable { onUndoReceipt(receipt.id) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "되돌리기",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

/**
 * Figma Screen 1 (`5:49`): Single compact horizontal Quick Pill Dock keeping Home serene & uncluttered.
 */
@Composable
fun FlagshipQuickPillDock(
    activeBubblesCount: Int,
    routinesCount: Int,
    isRecordingDemo: Boolean,
    onOpenDrawerTab: (CompanionDrawerTab) -> Unit,
    onCycleMorphDemo: () -> Unit,
    onOpenPickleVault: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DockActionPill("🫧 취향 $activeBubblesCount") { onOpenDrawerTab(CompanionDrawerTab.BUBBLES) }
        DockActionPill(
            label = if (isRecordingDemo) "● 시연 녹화 중" else "⚡ 루틴 $routinesCount",
            highlighted = isRecordingDemo
        ) { onOpenDrawerTab(CompanionDrawerTab.ROUTINES) }
        DockActionPill("✦ UI 변형") { onCycleMorphDemo() }
        DockActionPill("📞 전화 준비") { onOpenDrawerTab(CompanionDrawerTab.CALL) }
        DockActionPill("🛡️ 볼트") { onOpenPickleVault() }
    }
}

@Composable
private fun DockActionPill(
    label: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (highlighted) ObsidianColors.Accent else ObsidianColors.Surface)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (highlighted) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/**
 * Figma Screen 4 (`5:202`): On-Demand Modal Bottom Sheet housing Bubbles, Routines/Show-Me-Once, Call Concierge & Sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionDrawerBottomSheet(
    initialTab: CompanionDrawerTab,
    onDismiss: () -> Unit,
    memoryBubbles: List<HumanMemoryBubble>,
    onAddBubble: (String) -> Unit,
    onToggleBubble: (String) -> Unit,
    onRemoveBubble: (String) -> Unit,
    personalRoutines: List<PersonalRoutine>,
    isRecordingDemo: Boolean,
    recordedStepCount: Int,
    onRunRoutine: (PersonalRoutine) -> Unit,
    onRemoveRoutine: (String) -> Unit,
    onStartDemoRecord: () -> Unit,
    onFinishDemoRecord: (String) -> Unit,
    onCancelDemoRecord: () -> Unit,
    onApproveAndDial: (String, String, String, String) -> Unit,
    cloudSyncState: FirestoreSyncUiState,
    onSaveCloudSyncConfig: (
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String,
        editionMode: FirestoreEditionMode,
        userUid: String
    ) -> Unit,
    onTriggerCloudSyncNow: () -> Unit
) {
    var activeTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = ObsidianColors.Surface,
        scrimColor = ObsidianColors.Background.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 18.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "나만의 한슨 도우미 서랍",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                DismissPillButton(onClick = onDismiss)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CompanionDrawerTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable { activeTab = tab }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "${tab.emoji} ${tab.labelKo}",
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (activeTab) {
                    CompanionDrawerTab.BUBBLES -> {
                        MemoryBubblesStripCard(
                            bubbles = memoryBubbles,
                            onAddBubble = onAddBubble,
                            onToggleBubble = onToggleBubble,
                            onRemoveBubble = onRemoveBubble
                        )
                    }
                    CompanionDrawerTab.ROUTINES -> {
                        PinnedRoutinesAndShowMeCard(
                            routines = personalRoutines,
                            isRecordingDemo = isRecordingDemo,
                            recordedStepCount = recordedStepCount,
                            onRunRoutine = { r ->
                                onDismiss()
                                onRunRoutine(r)
                            },
                            onRemoveRoutine = onRemoveRoutine,
                            onStartDemoRecord = onStartDemoRecord,
                            onFinishDemoRecord = onFinishDemoRecord,
                            onCancelDemoRecord = onCancelDemoRecord
                        )
                    }
                    CompanionDrawerTab.CALL -> {
                        CallConciergeCard(onApproveAndDial = onApproveAndDial)
                    }
                    CompanionDrawerTab.CLOUD_SYNC -> {
                        FirestoreCloudSyncCard(
                            syncState = cloudSyncState,
                            onSaveConfig = onSaveCloudSyncConfig,
                            onSyncNow = onTriggerCloudSyncNow
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
