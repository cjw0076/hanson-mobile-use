package com.hanson.mobileuse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.hanson.mobileuse.agent.BoardEventCommand
import com.hanson.mobileuse.agent.BoardEventStreamCompiler
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
 * Ahaboard-inspired Event-Sourced Dual-Channel Response Board (`AhaboardResponseCard`):
 * - Driven by ordered [BoardEventStreamCompiler] events (`turnIndex`, `orderIndex`, `elementId`)
 * - Top floating pill header (`LIVE WEB BOARD` eyebrow + `↻ 다시 보기` replay + `🔊 듣기` natural TTS)
 * - Animated `.hl-swipe` highlight sweep headline (0%-saturation Paper & Carbon monochrome harmony)
 * - Compact metric stat pills (`현재 주가 $83.16`, `변동 범위 $67.05 ~ $84.86`, `웹 출처 2건`)
 * - Sequential numbered `Key takeaways` cards with active spoken-element ink focus border + tap-to-ask
 * - Live karaoke caption bar synced to `Speak` events during playback/replay (ZERO raw `**` or `*`)
 * - Clean Ahaboard-style domain citation pills (`01 investing.com ↗`, hiding raw redirect hostnames)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AhaboardResponseCard(
    rawText: String,
    citations: List<StructuredUiItem> = emptyList(),
    eyebrowOverride: String = "",
    onCitationClick: (StructuredUiItem) -> Unit = {},
    onSpeakText: ((String) -> Unit)? = null,
    onAskAboutItem: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val script = remember(rawText, citations, eyebrowOverride) {
        BoardEventStreamCompiler.compile(
            rawText = rawText,
            citations = citations,
            eyebrowOverride = eyebrowOverride
        )
    }

    if (!script.isRichBoard) {
        Text(
            text = rememberFormattedMarkdownText(script.visualBodyText),
            color = ObsidianColors.TextPrimary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
            modifier = modifier
        )
        return
    }

    var replayNonce by remember(rawText) { mutableIntStateOf(0) }
    var visibleOrderLimit by remember(rawText) { mutableIntStateOf(script.events.size) }
    var activeElementId by remember(rawText) { mutableStateOf<String?>(null) }
    var activeCaptionText by remember(rawText) { mutableStateOf("") }
    var activeCaptionWordIndex by remember(rawText) { mutableIntStateOf(0) }
    var isReplaying by remember(rawText) { mutableStateOf(false) }

    val swipeProgress = remember(rawText, replayNonce) { Animatable(0f) }

    // Ordered progressive event playback (mirrors Ahaboard's orderIndex event stream + karaoke sync)
    LaunchedEffect(rawText, replayNonce) {
        val explicitReplay = replayNonce > 0
        isReplaying = explicitReplay
        visibleOrderLimit = 0
        activeElementId = null
        activeCaptionText = ""
        activeCaptionWordIndex = 0
        swipeProgress.snapTo(0f)

        val stepDelayMs = if (explicitReplay) 190L else 55L
        for (ev in script.events) {
            visibleOrderLimit = ev.orderIndex + 1
            when (val cmd = ev.command) {
                is BoardEventCommand.WriteHeadline -> {
                    activeElementId = ev.elementId
                    swipeProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = if (explicitReplay) 480 else 360, easing = FastOutSlowInEasing)
                    )
                }
                is BoardEventCommand.Speak -> {
                    activeElementId = cmd.linkedElementId ?: ev.elementId
                    if (explicitReplay) {
                        activeCaptionText = cmd.text
                        val words = cmd.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                        for (wIdx in 1..words.size) {
                            activeCaptionWordIndex = wIdx
                            delay(95L)
                        }
                    } else {
                        delay(45L)
                    }
                }
                else -> {
                    if (explicitReplay && cmd is BoardEventCommand.AddTakeaway) {
                        activeElementId = ev.elementId
                    }
                    delay(stepDelayMs)
                }
            }
        }
        visibleOrderLimit = script.events.size
        if (explicitReplay) {
            delay(450L)
        }
        activeElementId = null
        activeCaptionText = ""
        isReplaying = false
    }

    val visibleEvents = remember(script.events, visibleOrderLimit) {
        script.events.filter { it.orderIndex < visibleOrderLimit }
    }
    val headlineEvent = visibleEvents.firstOrNull { it.command is BoardEventCommand.WriteHeadline }
    val headlineCmd = headlineEvent?.command as? BoardEventCommand.WriteHeadline
    val statPills = visibleEvents.mapNotNull { it.command as? BoardEventCommand.AddStatPill }
    val takeawayEvents = visibleEvents.filter { it.command is BoardEventCommand.AddTakeaway }
    val noteCommands = visibleEvents.mapNotNull { it.command as? BoardEventCommand.WriteNote }
    val citationCommands = visibleEvents.mapNotNull { it.command as? BoardEventCommand.AddCitation }

    val highlightBarColor = ObsidianColors.AccentDim

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. Ahaboard-style top bar: tracked eyebrow + ↻ Replay + 🔊 Listen pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = headlineCmd?.eyebrow?.ifBlank { "SUMMARY BOARD" } ?: "SUMMARY BOARD",
                color = ObsidianColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable {
                            replayNonce += 1
                            if (script.combinedSpokenText.isNotBlank()) {
                                onSpeakText?.invoke(script.combinedSpokenText)
                            }
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isReplaying) "● 재생 중" else "↻ 다시 보기",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (onSpeakText != null && script.combinedSpokenText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable { onSpeakText(script.combinedSpokenText) }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🔊 듣기",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 2. Animated `.hl-swipe` highlight headline
        if (headlineCmd != null && headlineCmd.headline.isNotBlank()) {
            Text(
                text = headlineCmd.headline,
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

        // 3. Compact Ahaboard Stat Pills (`RoundedCornerShape(999.dp)`, hairline border)
        if (statPills.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                statPills.forEach { pill ->
                    AhaboardStatPillChip(label = pill.label, value = pill.value)
                }
            }
        }

        // 4. Numbered `Key takeaways` cards with active spoken-element focus & tap-to-ask
        if (takeawayEvents.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                takeawayEvents.forEach { ev ->
                    val item = ev.command as BoardEventCommand.AddTakeaway
                    val isFocused = activeElementId == ev.elementId
                    AhaboardTakeawayRowCard(
                        index = item.index,
                        keyPrefix = item.keyPrefix,
                        body = item.body,
                        isFocused = isFocused,
                        onClick = onAskAboutItem?.let { ask ->
                            {
                                val topic = item.keyPrefix.ifBlank { item.body.take(28) }
                                ask("'$topic'에 대해 더 자세히 알려줘")
                            }
                        }
                    )
                }
            }
        }

        // 5. Clean closing / additional prose notes (real bold, zero raw `**`)
        if (noteCommands.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                noteCommands.forEach { note ->
                    Text(
                        text = rememberFormattedMarkdownText(note.text),
                        color = ObsidianColors.TextSecondary,
                        fontSize = 13.5.sp,
                        lineHeight = 20.5.sp
                    )
                }
            }
        }

        // 6. Live Karaoke Caption Bar (mirrors Ahaboard's spoken ink vs upcoming muted caption strip)
        AnimatedVisibility(
            visible = activeCaptionText.isNotBlank(),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220))
        ) {
            AhaboardKaraokeCaptionStrip(
                text = activeCaptionText,
                spokenWordCount = activeCaptionWordIndex
            )
        }

        // 7. Clean Ahaboard-style domain citation pills (zero raw vertexaisearch redirect URLs)
        if (citationCommands.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                citationCommands.forEach { cit ->
                    val matchingItem = citations.firstOrNull { it.actionUrlOrCmd == cit.verifiedUrl }
                        ?: StructuredUiItem(
                            badge = cit.badge,
                            title = cit.title,
                            subtitle = cit.subtitle,
                            meta = "출처 ↗",
                            actionUrlOrCmd = cit.verifiedUrl
                        )
                    AhaboardSourcePill(
                        item = matchingItem,
                        onClick = { onCitationClick(matchingItem) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AhaboardStatPillChip(label: String, value: String) {
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
            text = label,
            color = ObsidianColors.TextMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
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
    index: Int,
    keyPrefix: String,
    body: String,
    isFocused: Boolean,
    onClick: (() -> Unit)?
) {
    val reveal = remember(index, body) { Animatable(0f) }
    LaunchedEffect(index, body) {
        reveal.snapTo(0f)
        reveal.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
        )
    }

    val borderColor = if (isFocused) ObsidianColors.Accent else ObsidianColors.GlassBorder
    val borderWidth = if (isFocused) 1.6.dp else 1.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = reveal.value
                translationY = (1f - reveal.value) * 12f
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) ObsidianColors.Surface else ObsidianColors.SurfaceHigh)
            .border(borderWidth, borderColor, RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(ObsidianColors.Accent),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index.toString(),
                color = ObsidianColors.OnAccent,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (keyPrefix.isNotBlank()) {
                Text(
                    text = keyPrefix,
                    color = ObsidianColors.TextMuted,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = rememberFormattedMarkdownText(body),
                color = ObsidianColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (keyPrefix.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                lineHeight = 20.5.sp
            )
        }
    }
}

@Composable
private fun AhaboardKaraokeCaptionStrip(
    text: String,
    spokenWordCount: Int
) {
    val words = remember(text) { text.split(Regex("\\s+")).filter { it.isNotBlank() } }
    val primaryColor = ObsidianColors.TextPrimary
    val mutedColor = ObsidianColors.TextMuted
    val annotated = remember(words, spokenWordCount, primaryColor, mutedColor) {
        buildAnnotatedString {
            words.forEachIndexed { idx, w ->
                if (idx > 0) append(" ")
                val active = idx < spokenWordCount
                withStyle(
                    SpanStyle(
                        color = if (active) primaryColor else mutedColor,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                    )
                ) { append(w) }
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "CAPTION",
            color = ObsidianColors.TextMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.8.sp
        )
        Text(
            text = annotated,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
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
