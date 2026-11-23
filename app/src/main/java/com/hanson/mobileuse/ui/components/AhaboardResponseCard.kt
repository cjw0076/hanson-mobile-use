package com.hanson.mobileuse.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.BoardStatPill
import com.hanson.mobileuse.agent.BoardTakeawayItem
import com.hanson.mobileuse.agent.RichAnswerBoardFormatter
import com.hanson.mobileuse.agent.StructuredUiItem
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.delay

/**
 * Converts markdown text into a clean Compose [AnnotatedString] with real bold & monospace code spans,
 * completely eliminating literal `**`, `*`, `#`, or `` ` `` characters on screen.
 */
@Composable
fun rememberFormattedMarkdownText(rawText: String): AnnotatedString {
    val codeBg = ObsidianColors.SurfaceHigh
    return remember(rawText, codeBg) {
        buildAnnotatedString {
            val lines = rawText.lines()
            lines.forEachIndexed { lineIdx, rawLine ->
                if (lineIdx > 0) append("\n")
                val cleanedLine = rawLine
                    .replace(Regex("""^\s{0,3}#{1,6}\s+"""), "")
                    .replace(Regex("""^\s*[-*]\s+"""), "• ")
                val isHeading = rawLine.trimStart().startsWith("#")
                val spans = RichAnswerBoardFormatter.parseInlineMarkdown(cleanedLine)
                for (seg in spans) {
                    when {
                        seg.isCode -> withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                background = codeBg
                            )
                        ) { append(seg.text) }
                        seg.isBold || isHeading -> withStyle(
                            SpanStyle(fontWeight = FontWeight.Bold)
                        ) { append(seg.text) }
                        else -> append(seg.text)
                    }
                }
            }
        }
    }
}

/**
 * Ahaboard-inspired visual response renderer (`AhaboardResponseCard`):
 * - Tracked uppercase eyebrow (`LIVE WEB BOARD` / `KEY TAKEAWAYS`)
 * - Animated `.hl-swipe` highlight sweep headline (0%-saturation Paper & Carbon monochrome harmony)
 * - Compact metric stat pills (`현재 주가 $83.16`, `변동 범위 $67.05 ~ $84.86`, `웹 출처 2건`)
 * - Progressive staggered numbered `Key takeaways` cards (`1`, `2`, `3` circular badges + bold key-lead)
 * - Clean rich-formatted closing prose (zero raw `**` or `*`)
 * - Clean Ahaboard-style domain citation pills (`01 investing.com ↗`, never raw redirect URLs)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AhaboardResponseCard(
    rawText: String,
    citations: List<StructuredUiItem> = emptyList(),
    eyebrowOverride: String = "",
    onCitationClick: (StructuredUiItem) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val board = remember(rawText, citations.size, eyebrowOverride) {
        RichAnswerBoardFormatter.buildStructuredBoard(
            rawText = rawText,
            citationCount = citations.size,
            eyebrowOverride = eyebrowOverride
        )
    }

    if (!board.isRichBoard) {
        Text(
            text = rememberFormattedMarkdownText(rawText),
            color = ObsidianColors.TextPrimary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = modifier
        )
        return
    }

    val swipeProgress = remember(board.headline) { Animatable(0f) }
    LaunchedEffect(board.headline) {
        swipeProgress.snapTo(0f)
        swipeProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 440, easing = FastOutSlowInEasing)
        )
    }

    val highlightBarColor = ObsidianColors.AccentDim

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Tracked uppercase eyebrow (Ahaboard 11sp-13sp Bold tracking)
        if (board.eyebrow.isNotBlank()) {
            Text(
                text = board.eyebrow,
                color = ObsidianColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        // 2. Animated `.hl-swipe` highlight headline
        if (board.headline.isNotBlank()) {
            Text(
                text = board.headline,
                color = ObsidianColors.TextPrimary,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 23.sp,
                modifier = Modifier
                    .drawBehind {
                        val barWidth = size.width * swipeProgress.value
                        val barHeight = size.height * 0.36f
                        if (barWidth > 1f) {
                            drawRoundRect(
                                color = highlightBarColor,
                                topLeft = Offset(0f, size.height - barHeight),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                            )
                        }
                    }
                    .padding(bottom = 2.dp)
            )
        }

        // 3. Compact Ahaboard Stat Pills (`RoundedCornerShape(50)`, hairline border)
        if (board.statPills.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                board.statPills.forEach { pill ->
                    AhaboardStatPillChip(pill = pill)
                }
            }
        }

        // 4. Numbered `Key takeaways` cards with staggered progressive reveal
        if (board.takeaways.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                board.takeaways.forEachIndexed { idx, item ->
                    AhaboardTakeawayRowCard(
                        item = item,
                        staggerIndex = idx
                    )
                }
            }
        }

        // 5. Clean closing / additional prose paragraphs (real bold, zero raw `**`)
        if (board.bodyParagraphs.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                board.bodyParagraphs.forEach { para ->
                    Text(
                        text = rememberFormattedMarkdownText(para),
                        color = ObsidianColors.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 20.5.sp
                    )
                }
            }
        }

        // 6. Clean Ahaboard-style domain citation pills (zero raw vertexaisearch redirect URLs)
        if (citations.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                citations.forEach { item ->
                    AhaboardSourcePill(
                        item = item,
                        onClick = { onCitationClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AhaboardStatPillChip(pill: BoardStatPill) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            text = pill.label,
            color = ObsidianColors.TextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = pill.value,
            color = ObsidianColors.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AhaboardTakeawayRowCard(
    item: BoardTakeawayItem,
    staggerIndex: Int
) {
    val reveal = remember(item.index, item.body) { Animatable(0f) }
    LaunchedEffect(item.index, item.body) {
        reveal.snapTo(0f)
        delay((staggerIndex * 65L).coerceAtMost(260L))
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 14f
            }
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Circular numbered badge (Ahaboard 28dp circle badge)
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(ObsidianColors.Accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.index.toString(),
                color = ObsidianColors.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (item.keyPrefix.isNotBlank()) {
                Text(
                    text = item.keyPrefix,
                    color = ObsidianColors.TextMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = rememberFormattedMarkdownText(item.body),
                color = ObsidianColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (item.keyPrefix.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 20.5.sp
            )
        }
    }
}

@Composable
private fun AhaboardSourcePill(
    item: StructuredUiItem,
    onClick: () -> Unit
) {
    val displayLabel = remember(item.title, item.subtitle) {
        val t = item.title.trim()
        if (t.isNotBlank() && !t.contains("vertexaisearch.cloud.google.com")) {
            t
        } else {
            RichAnswerBoardFormatter.friendlySourceHost(item.subtitle, item.title)
        }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.Surface)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (item.badge.isNotBlank()) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ObsidianColors.Accent)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.badge,
                    color = ObsidianColors.OnAccent,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
        Text(
            text = displayLabel,
            color = ObsidianColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "↗",
            color = ObsidianColors.TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
