package com.hanson.mobileuse.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.HumanTakeoverState
import com.hanson.mobileuse.agent.HighStakesKind
import com.hanson.mobileuse.agent.SubGoalItem
import com.hanson.mobileuse.agent.SubGoalStatus
import com.hanson.mobileuse.ui.theme.ObsidianColors
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.MentionSuggestion
import com.hanson.mobileuse.workspace.SessionStoreState
import com.hanson.mobileuse.workspace.SessionThread
import com.hanson.mobileuse.workspace.SlashCommandOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact, thumb-ergonomic Studio top bar with:
 * - Session Inbox drawer trigger pill
 * - Auto | Plan | Build segmented mode switcher
 * - Workspace file count workbench trigger pill
 * - Cloud API / Settings status pill
 */
@Composable
fun StudioTopHeaderBar(
    activeSessionTitle: String,
    workMode: AgentWorkMode,
    isRunning: Boolean,
    hasPendingApproval: Boolean,
    hasCloudToken: Boolean,
    workspaceFileCount: Int,
    onOpenSessionDrawer: () -> Unit,
    onSelectMode: (AgentWorkMode) -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianColors.Background.copy(alpha = 0.96f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Session Inbox Trigger Pill
            val sessionBorder = if (hasPendingApproval || isRunning) {
                ObsidianColors.Accent
            } else {
                ObsidianColors.GlassBorder
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Surface)
                    .border(1.dp, sessionBorder, RoundedCornerShape(999.dp))
                    .clickable { onOpenSessionDrawer() }
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                hasPendingApproval -> ObsidianColors.Accent
                                isRunning -> ObsidianColors.Success
                                else -> ObsidianColors.TextMuted
                            }
                        )
                )
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = "세션 인박스",
                    tint = ObsidianColors.TextSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = activeSessionTitle.ifBlank { "기본 세션" },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (hasPendingApproval) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "승인",
                            color = ObsidianColors.OnAccent,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "세션 목록 열기",
                    tint = ObsidianColors.TextMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Workbench File Count Pill
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .clickable { onOpenWorkbench() }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "코드 워크벤치",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = workspaceFileCount.toString(),
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            // API / Settings Status Badge
            Row(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (hasCloudToken) ObsidianColors.Surface else ObsidianColors.SurfaceHigh)
                    .border(
                        width = 1.dp,
                        color = if (hasCloudToken) ObsidianColors.GlassBorder else ObsidianColors.GlassHighlight,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (hasCloudToken) ObsidianColors.Success else ObsidianColors.Warning)
                )
                Text(
                    text = if (hasCloudToken) "API" else "설정",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "스튜디오 설정",
                    tint = ObsidianColors.TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        // Auto | Plan | Build Segmented Switcher Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                AgentWorkMode.entries.forEach { mode ->
                    val selected = mode == workMode
                    val modeIcon = when (mode) {
                        AgentWorkMode.AUTO -> Icons.Default.AutoAwesome
                        AgentWorkMode.PLAN -> Icons.Default.Description
                        AgentWorkMode.BUILD -> Icons.Default.Build
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .clickable { onSelectMode(mode) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = modeIcon,
                            contentDescription = mode.label,
                            tint = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = mode.label,
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // Live run status badge when active
            if (isRunning) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.Accent, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Accent)
                    )
                    Text(
                        text = "실행 중",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        HorizontalDivider(color = ObsidianColors.Divider, thickness = 1.dp)
    }
}

/**
 * ChatGPT-Desktop-inspired "Work with Apps" mobile companion strip.
 * Surfaces the current foreground app, accessibility readiness, @screen context attach,
 * HUD toggle, and Code Workbench shortcut.
 */
@Composable
fun WorkWithAppsCompanionStrip(
    foregroundAppLabel: String,
    foregroundPackage: String,
    a11yReady: Boolean,
    hudEnabled: Boolean,
    onAttachScreenContext: () -> Unit,
    onToggleHud: (Boolean) -> Unit,
    onOpenWorkbench: () -> Unit,
    modifier: Modifier = Modifier,
    vaultCount: Int = 0,
    activeWatcherCount: Int = 0,
    onOpenPickleHub: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianColors.Surface)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Smartphone,
            contentDescription = "Work with Apps",
            tint = ObsidianColors.TextPrimary,
            modifier = Modifier.size(16.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (a11yReady) ObsidianColors.Success else ObsidianColors.TextMuted)
                )
                Text(
                    text = foregroundAppLabel.ifBlank { "현재 포그라운드 앱" },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = buildString {
                    append(if (a11yReady) "Work with Apps" else "접근성 대기")
                    if (foregroundPackage.isNotBlank()) {
                        append(" · ")
                        append(foregroundPackage)
                    }
                },
                color = ObsidianColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // @screen Quick-Attach Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                .clickable { onAttachScreenContext() }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(
                text = "+ @screen",
                color = ObsidianColors.TextPrimary,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // HUD Toggle Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (hudEnabled) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                .border(
                    width = 1.dp,
                    color = if (hudEnabled) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                    shape = RoundedCornerShape(999.dp)
                )
                .clickable { onToggleHud(!hudEnabled) }
                .padding(horizontal = 9.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (hudEnabled) "HUD ON" else "HUD",
                color = if (hudEnabled) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Safety & Memory Hub Pill (Vault / Watchers / A2A / Notes)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .clickable { onOpenPickleHub() }
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Vault & Hub 열기",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (vaultCount > 0 || activeWatcherCount > 0) {
                        "Vault·${vaultCount + activeWatcherCount}"
                    } else {
                        "Vault"
                    },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Workbench Shortcut Icon Pill
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                .clickable { onOpenWorkbench() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = "워크벤치 열기",
                tint = ObsidianColors.TextPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * Floating suggestion palette rendered above the composer when typing `/` commands or `@` mentions.
 */
@Composable
fun SlashAndMentionPaletteCard(
    queryText: String,
    slashOptions: List<SlashCommandOption>,
    mentionOptions: List<MentionSuggestion>,
    onPickSlash: (SlashCommandOption) -> Unit,
    onPickMention: (MentionSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    val trimmedStart = queryText.trimStart()
    val lastToken = queryText.substringAfterLast(' ').substringAfterLast('\n')
    val isMentionMode = lastToken.startsWith("@")
    val isSlashMode = !isMentionMode && (trimmedStart.startsWith("/") || (queryText.isBlank() && slashOptions.isNotEmpty()))

    val filteredSlash = remember(queryText, slashOptions) {
        if (!isSlashMode) {
            emptyList()
        } else {
            val q = trimmedStart.substringBefore(' ').lowercase()
            if (q == "/" || q.isEmpty()) {
                slashOptions
            } else {
                slashOptions.filter {
                    it.command.lowercase().contains(q) || it.label.lowercase().contains(q.removePrefix("/"))
                }
            }
        }
    }

    val filteredMentions = remember(queryText, mentionOptions) {
        if (!isMentionMode) {
            emptyList()
        } else {
            val q = lastToken.lowercase()
            if (q == "@") {
                mentionOptions
            } else {
                mentionOptions.filter {
                    it.token.lowercase().contains(q) ||
                        it.label.lowercase().contains(q.removePrefix("@")) ||
                        it.category.lowercase().contains(q.removePrefix("@"))
                }
            }
        }
    }

    if (filteredSlash.isEmpty() && filteredMentions.isEmpty()) return

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        cornerRadius = 20.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassHighlight,
        tactileBottomLip = true,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isMentionMode) "컨텍스트 멘션 (@)" else "슬래시 명령어 (/)",
                color = ObsidianColors.TextPrimary,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold
            )
            CodeBadge(
                text = if (isMentionMode) "${filteredMentions.size}개" else "${filteredSlash.size}개",
                color = ObsidianColors.TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)
        Spacer(modifier = Modifier.height(6.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 148.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            filteredSlash.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .clickable { onPickSlash(option) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CodeBadge(text = option.command)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (option.description.isNotBlank()) {
                            Text(
                                text = option.description,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    option.targetMode?.let { mode ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = mode.shortBadge,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            filteredMentions.forEach { suggestion ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .clickable { onPickMention(suggestion) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CodeBadge(text = suggestion.token)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = suggestion.label,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (suggestion.description.isNotBlank()) {
                            Text(
                                text = suggestion.description,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (suggestion.category.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = suggestion.category,
                                color = ObsidianColors.TextMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Thumb-zone bottom studio composer card with removable @ context chips,
 * / command trigger, active mode indicator, multiline text box, and Send/Stop button.
 */
@Composable
fun StudioComposerCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    workMode: AgentWorkMode,
    isRunning: Boolean,
    attachedContexts: List<String>,
    onRemoveContext: (String) -> Unit,
    onToggleSlashPalette: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = inputText.isNotBlank() || attachedContexts.isNotEmpty()
    val placeholderText = when (workMode) {
        AgentWorkMode.AUTO -> "지시를 입력하거나 /명령 · @컨텍스트를 붙여보세요..."
        AgentWorkMode.PLAN -> "Plan 모드 — 실행 전 PLAN.md 청사진을 작성합니다..."
        AgentWorkMode.BUILD -> "Build 모드 — 코드 수정 및 기기 제어를 바로 실행합니다..."
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        cornerRadius = 24.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = if (canSend) ObsidianColors.GlassHighlight else ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Attached @ Context Chips
        AnimatedVisibility(visible = attachedContexts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                attachedContexts.forEach { ctx ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                            .clickable { onRemoveContext(ctx) }
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            text = ctx,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "컨텍스트 제거",
                            tint = ObsidianColors.TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        // Multiline Text Input
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 34.dp, max = 112.dp)
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (inputText.isEmpty()) {
                Text(
                    text = placeholderText,
                    color = ObsidianColors.TextMuted,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                textStyle = TextStyle(
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.sp,
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

        Spacer(modifier = Modifier.height(8.dp))

        // Bottom Controls Row: / button, @ button, Mode Badge, Send/Stop Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // "/" Quick Slash Command Button
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                        .clickable { onToggleSlashPalette() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "/ 명령",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                // "@" Quick Mention Trigger Button
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                        .clickable {
                            val updated = when {
                                inputText.endsWith("@") -> inputText
                                inputText.isBlank() || inputText.endsWith(" ") || inputText.endsWith("\n") -> "${inputText}@"
                                else -> "$inputText @"
                            }
                            onInputChange(updated)
                        }
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "@",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Mode Indicator Chip
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianColors.Background)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = workMode.shortBadge,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (isRunning) {
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onStop() }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "실행 중단",
                        tint = ObsidianColors.OnAccent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "중단",
                        color = ObsidianColors.OnAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (canSend) ObsidianColors.Accent else ObsidianColors.SurfaceTop)
                        .clickable(enabled = canSend) { onSend() }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (workMode == AgentWorkMode.PLAN) "계획" else "전송",
                        color = if (canSend) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "전송",
                        tint = if (canSend) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/**
 * Claude-Code-inspired inline tool permission gate card with 3 crisp decisions:
 * 거부 | 이번만 허용 | 항상 허용
 */
@Composable
fun InlineApprovalPromptCard(
    toolName: String,
    riskTierLabel: String,
    targetSummary: String,
    reasonKo: String,
    onDeny: () -> Unit,
    onAllowOnce: () -> Unit,
    onAlwaysAllow: () -> Unit,
    modifier: Modifier = Modifier,
    isHighStakesFinancial: Boolean = false,
    detectedPriceText: String? = null,
    priceDriftNoticeKo: String? = null,
    targetAppOrMerchant: String? = null
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        borderColor = ObsidianColors.Accent,
        containerColor = ObsidianColors.Surface,
        tactileBottomLip = true,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "권한 승인 요청",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isHighStakesFinancial || priceDriftNoticeKo != null) {
                        "결제·민감 작업 최종 승인"
                    } else {
                        "실행 승인 필요"
                    },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (priceDriftNoticeKo != null) {
                    CodeBadge(text = "PRICE DRIFT", color = ObsidianColors.Warning)
                }
                CodeBadge(text = toolName)
                if (riskTierLabel.isNotBlank()) {
                    CodeBadge(text = riskTierLabel, color = ObsidianColors.TextSecondary)
                }
            }
        }

        if (!targetAppOrMerchant.isNullOrBlank() || !detectedPriceText.isNullOrBlank() || !priceDriftNoticeKo.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.Accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = buildString {
                        append(targetAppOrMerchant?.ifBlank { "결제/거래 화면" } ?: "결제/거래 화면")
                        if (!detectedPriceText.isNullOrBlank()) {
                            append(" · 감지 금액: ")
                            append(detectedPriceText)
                        }
                    },
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (!priceDriftNoticeKo.isNullOrBlank()) {
                    Text(
                        text = "금액 변동 감지: $priceDriftNoticeKo",
                        color = ObsidianColors.Warning,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (targetSummary.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = targetSummary,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 16.sp
                )
            }
        }

        if (reasonKo.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = reasonKo,
                color = ObsidianColors.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(0.85f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .clickable { onDeny() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "거부",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1.15f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onAllowOnce() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "이번만 허용",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            if (!isHighStakesFinancial) {
                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.5.dp, ObsidianColors.Accent, RoundedCornerShape(12.dp))
                        .clickable { onAlwaysAllow() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "항상 허용",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Monochrome CAPTCHA / Anti-Bot / 2FA / Secure Keypad Human Takeover banner.
 * Agent NEVER attempts automated CAPTCHA solving or security bypass.
 */
@Composable
fun HumanTakeoverBannerCard(
    takeover: HumanTakeoverState,
    onResumeAfterTakeover: () -> Unit,
    onCancelTakeover: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        borderColor = ObsidianColors.Warning,
        containerColor = ObsidianColors.Surface,
        tactileBottomLip = true,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "사용자 직접 확인 필요",
                    tint = ObsidianColors.Warning,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = takeover.titleKo,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            CodeBadge(
                text = when (takeover.kind) {
                    HighStakesKind.CAPTCHA_BOT_GATE -> "NON-BYPASS CAPTCHA"
                    HighStakesKind.TWO_FACTOR_OR_BIOMETRIC -> "2FA · BIOMETRIC"
                    HighStakesKind.SECURE_SURFACE_BLOCKED -> "FLAG_SECURE"
                    else -> "HUMAN TAKEOVER"
                },
                color = ObsidianColors.Warning
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = takeover.reasonKo,
            color = ObsidianColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            Text(
                text = takeover.resumeHintKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(0.75f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .clickable { onCancelTakeover() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "작업 취소",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .weight(1.4f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onResumeAfterTakeover() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "직접 완료 · 에이전트 재개",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/**
 * Collapsible live execution checklist and inline tool trace strip.
 */
@Composable
fun LiveExecutionChecklistCard(
    steps: List<SubGoalItem>,
    currentToolLabel: String,
    onOpenWorkbenchPlan: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty() && currentToolLabel.isBlank()) return

    var expanded by remember { mutableStateOf(true) }
    val completedCount = steps.count { it.status == SubGoalStatus.COMPLETED }
    val progressFraction = if (steps.isNotEmpty()) {
        (completedCount.toFloat() / steps.size.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.GlassBorder,
        tactileBottomLip = false,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "실행 추적",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = if (steps.isNotEmpty()) "실행 체크리스트" else "라이브 도구 실행",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                if (steps.isNotEmpty()) {
                    CodeBadge(text = "$completedCount/${steps.size}", color = ObsidianColors.TextSecondary)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(8.dp))
                        .clickable { onOpenWorkbenchPlan() }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "PLAN",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (steps.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "접기" else "펼치기",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (currentToolLabel.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Accent)
                )
                Text(
                    text = currentToolLabel,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (steps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceTop)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                )
            }
        }

        AnimatedVisibility(visible = expanded && steps.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                steps.forEach { step ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (step.status) {
                            SubGoalStatus.COMPLETED -> {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(ObsidianColors.Accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "완료",
                                        tint = ObsidianColors.OnAccent,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                            SubGoalStatus.IN_PROGRESS -> {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .border(2.dp, ObsidianColors.Accent, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(ObsidianColors.Accent)
                                    )
                                }
                            }
                            SubGoalStatus.PENDING -> {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, ObsidianColors.GlassHighlight, CircleShape)
                                )
                            }
                        }

                        Text(
                            text = step.title,
                            color = when (step.status) {
                                SubGoalStatus.COMPLETED -> ObsidianColors.TextMuted
                                SubGoalStatus.IN_PROGRESS -> ObsidianColors.TextPrimary
                                SubGoalStatus.PENDING -> ObsidianColors.TextSecondary
                            },
                            fontSize = 12.sp,
                            fontWeight = if (step.status == SubGoalStatus.IN_PROGRESS) FontWeight.Bold else FontWeight.Medium,
                            textDecoration = if (step.status == SubGoalStatus.COMPLETED) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Plan-mode blueprint banner showing a readable PLAN.md excerpt and two quick actions:
 * "PLAN.md 미리보기" and "승인 후 Build 실행".
 */
@Composable
fun PlanBlueprintActionBanner(
    planMarkdown: String,
    onApproveAndBuild: () -> Unit,
    onOpenPlanArtifact: () -> Unit,
    modifier: Modifier = Modifier
) {
    val excerpt = remember(planMarkdown) {
        planMarkdown.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("\n")
            .ifBlank { "실행 전 PLAN.md 설계안을 검토하고 승인 후 Build 모드로 전환할 수 있습니다." }
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.Accent,
        tactileBottomLip = true,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "PLAN.md",
                    tint = ObsidianColors.TextPrimary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "PLAN.md 실행 청사진",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            CodeBadge(text = "PLAN MODE")
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                .padding(11.dp)
        ) {
            Text(
                text = excerpt,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.5.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .clickable { onOpenPlanArtifact() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "PLAN.md 미리보기",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier
                    .weight(1.15f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onApproveAndBuild() },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Build 실행",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "승인 후 Build 실행",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

/**
 * Antigravity-inspired ModalBottomSheet displaying parallel Foreground/Background sessions,
 * mode badges, unread counts, pending approval alerts, and a "+ 새 세션" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInboxDrawerSheet(
    storeState: SessionStoreState,
    onDismiss: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateSession: () -> Unit,
    onToggleForeground: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = ObsidianColors.Surface,
        scrimColor = ObsidianColors.Background.copy(alpha = 0.68f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(38.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(ObsidianColors.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "세션 인박스",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${storeState.sessions.size}개 병렬 에이전트 스레드 · FG/BG 전환",
                        color = ObsidianColors.TextMuted,
                        fontSize = 11.5.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .height(34.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .clickable { onCreateSession() }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "새 세션",
                            tint = ObsidianColors.OnAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "새 세션",
                            color = ObsidianColors.OnAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
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
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ObsidianColors.Divider)
            Spacer(modifier = Modifier.height(10.dp))

            if (storeState.sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "진행 중인 세션이 없습니다. '+ 새 세션'을 눌러 시작하세요.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 18.dp)
                ) {
                    items(storeState.sessions, key = { it.id }) { session ->
                        SessionInboxThreadCard(
                            session = session,
                            isActive = session.id == storeState.activeSessionId,
                            onSelect = { onSelectSession(session.id) },
                            onToggleForeground = { onToggleForeground(session.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionInboxThreadCard(
    session: SessionThread,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleForeground: () -> Unit
) {
    val cardBg = if (isActive) ObsidianColors.SurfaceHigh else ObsidianColors.Surface
    val cardBorder = if (isActive) ObsidianColors.Accent else ObsidianColors.GlassBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
            .border(if (isActive) 1.5.dp else 1.dp, cardBorder, RoundedCornerShape(18.dp))
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isActive) ObsidianColors.Accent else ObsidianColors.TextMuted)
            )
            Text(
                text = session.title.ifBlank { "제목 없는 세션" },
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // FG / BG Parallel Pill Toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (session.isForeground) ObsidianColors.Accent else ObsidianColors.Surface)
                    .border(
                        width = 1.dp,
                        color = if (session.isForeground) ObsidianColors.Accent else ObsidianColors.GlassHighlight,
                        shape = RoundedCornerShape(999.dp)
                    )
                    .clickable { onToggleForeground() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (session.isForeground) "FG" else "BG",
                    color = if (session.isForeground) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            CodeBadge(text = session.mode.shortBadge)
        }

        if (session.previewLine.isNotBlank()) {
            Text(
                text = session.previewLine,
                color = ObsidianColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (session.statusBadge.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(ObsidianColors.Background)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = session.statusBadge,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (session.foregroundPackage.isNotBlank()) {
                    Text(
                        text = session.foregroundPackage.substringAfterLast('.'),
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (session.hasPendingApproval) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.Accent, RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "승인 대기",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "승인 대기",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (session.updatedAtMs > 0L) {
                    Text(
                        text = formatSessionTime(session.updatedAtMs),
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (session.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = session.unreadCount.toString(),
                            color = ObsidianColors.OnAccent,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
        }
    }
}

private fun formatSessionTime(timestampMs: Long): String {
    val diffMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    return when {
        diffMs < 60_000L -> "방금"
        diffMs < 3_600_000L -> "${diffMs / 60_000L}분 전"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
}
