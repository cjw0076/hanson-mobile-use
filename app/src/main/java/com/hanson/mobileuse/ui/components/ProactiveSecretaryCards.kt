package com.hanson.mobileuse.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.AppDetailPlaybook
import com.hanson.mobileuse.agent.AppDetailPlaybookRegistry
import com.hanson.mobileuse.agent.ApprovalPolicyTier
import com.hanson.mobileuse.agent.AtomicRollbackAndExplainEngine
import com.hanson.mobileuse.agent.BatchDigestA2uiBuilder
import com.hanson.mobileuse.agent.KoreanScheduleParser
import com.hanson.mobileuse.agent.PrivacyAndQuotaProofLedger
import com.hanson.mobileuse.agent.PrivacyProofSnapshot
import com.hanson.mobileuse.agent.ProactiveProposal
import com.hanson.mobileuse.agent.ProactiveProposalStatus
import com.hanson.mobileuse.agent.ProactiveProposalToA2uiBridge
import com.hanson.mobileuse.agent.ProactiveProposalType
import com.hanson.mobileuse.agent.ProactiveSecretaryEngine
import com.hanson.mobileuse.agent.ProposalActionKind
import com.hanson.mobileuse.agent.SecretarySettings
import com.hanson.mobileuse.agent.TopicChannelClassifier
import com.hanson.mobileuse.agent.TopicChannelType
import com.hanson.mobileuse.notification.NotificationStormShieldEngine
import com.hanson.mobileuse.notification.StormShieldConfig
import com.hanson.mobileuse.service.VirtualDisplayTakeoverState
import com.hanson.mobileuse.service.VirtualDisplayUiSnapshot
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure 0%-Saturation Paper & Carbon Proactive Chief-of-Staff AI Secretary Strip Card.
 * Renders only when pending proposals exist, offering 1-tap approval, quick-reply drafts,
 * calendar conflict alerts, A2UI expansion, map handoff, snooze, and dismiss.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProactiveSecretaryStripCard(
    proposals: List<ProactiveProposal>,
    settings: SecretarySettings,
    onExecuteAction: (proposalId: String, actionKey: String, replyText: String, snoozeMinutes: Long) -> Unit,
    onPublishToA2ui: (ProactiveProposal) -> Unit = { ProactiveProposalToA2uiBridge.publishToA2uiRegistry(it) },
    onScanNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onBatchApproveSafe: (List<String>) -> Unit = {},
    onReduceRoomFrequency: (roomKey: String) -> Unit = { ProactiveSecretaryEngine.reduceRoomFrequency(it) },
    onAlwaysAllowSafeCalendar: (roomKey: String) -> Unit = { ProactiveSecretaryEngine.alwaysAllowSafeCalendarForRoom(it) },
    modifier: Modifier = Modifier
) {
    val pendingProposals = remember(proposals) {
        proposals
            .filter { it.status == ProactiveProposalStatus.PENDING }
            .sortedWith(
                compareByDescending<ProactiveProposal> { it.type == ProactiveProposalType.SCAM_PRE_SHIELD }
                    .thenByDescending { it.hasCalendarConflict }
                    .thenByDescending { it.type.priorityWeight }
                    .thenByDescending { it.updatedAtMs }
            )
    }
    if (pendingProposals.isEmpty()) return

    var isDeckExpanded by rememberSaveable { mutableStateOf(true) }
    var showAllProposals by rememberSaveable { mutableStateOf(false) }
    var selectedChannelId by rememberSaveable { mutableStateOf(TopicChannelType.ALL.id) }

    val channelTabs = remember {
        listOf(
            TopicChannelType.ALL,
            TopicChannelType.WORK,
            TopicChannelType.FAMILY,
            TopicChannelType.SHOPPING
        )
    }
    val selectedChannel = remember(selectedChannelId) {
        channelTabs.firstOrNull { it.id == selectedChannelId } ?: TopicChannelType.ALL
    }
    val filteredProposals = remember(pendingProposals, selectedChannel) {
        TopicChannelClassifier.filterProposals(pendingProposals, selectedChannel)
    }
    val safeBatchIds = remember(filteredProposals) {
        filteredProposals
            .filter { ProactiveSecretaryEngine.evaluatePolicyTier(it) != ApprovalPolicyTier.HARD_MANUAL_GATE }
            .map { it.id }
    }

    val visibleProposals = if (showAllProposals || filteredProposals.size <= 2) {
        filteredProposals
    } else {
        filteredProposals.take(2)
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = ObsidianColors.Accent,
        tactileBottomLip = true,
        contentPadding = PaddingValues(18.dp)
    ) {
        // Header Bar: Title + Count Badge + Collapse Toggle + "지금 스캔" + "비서 설정"
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { isDeckExpanded = !isDeckExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Accent)
                )
                Text(
                    text = "선제안 AI 비서",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Accent)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${pendingProposals.size}건",
                        color = ObsidianColors.OnAccent,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Icon(
                    imageVector = if (isDeckExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isDeckExpanded) "접기" else "펼치기",
                    tint = ObsidianColors.TextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable { onScanNow() }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "지금 스캔",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "지금 스캔",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "비서 설정",
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        if (!settings.enabled) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "자동 감지가 일시 정지되어 있어요 · 설정에서 다시 켤 수 있어요.",
                color = ObsidianColors.TextMuted,
                fontSize = 11.sp
            )
        }

        AnimatedVisibility(visible = isDeckExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Topic Channel Filter Strip (전체 · 업무 · 가족 · 쇼핑)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    channelTabs.forEach { tab ->
                        val isSelected = tab == selectedChannel
                        val count = if (tab == TopicChannelType.ALL) {
                            pendingProposals.size
                        } else {
                            TopicChannelClassifier.filterProposals(pendingProposals, tab).size
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isSelected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                .border(
                                    width = 1.dp,
                                    color = if (isSelected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                    shape = RoundedCornerShape(999.dp)
                                )
                                .clickable { selectedChannelId = tab.id }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (count > 0) "${tab.labelKo} ${count}" else tab.labelKo,
                                color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                            )
                        }
                    }
                }

                // 1-Tap Batch Digest Approval Strip when >= 2 pending proposals exist
                if (pendingProposals.size >= 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (safeBatchIds.isNotEmpty()) ObsidianColors.Accent else ObsidianColors.Surface)
                                .border(
                                    1.dp,
                                    if (safeBatchIds.isNotEmpty()) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                    RoundedCornerShape(999.dp)
                                )
                                .clickable(enabled = safeBatchIds.isNotEmpty()) {
                                    onBatchApproveSafe(safeBatchIds)
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "안전 항목 일괄 승인",
                                tint = if (safeBatchIds.isNotEmpty()) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = if (safeBatchIds.isNotEmpty()) {
                                    "안전 항목 일괄 승인 (${safeBatchIds.size}건)"
                                } else {
                                    "직접 확인 필요 항목만 남음"
                                },
                                color = if (safeBatchIds.isNotEmpty()) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .clickable {
                                    BatchDigestA2uiBuilder.publishBatchDigest(filteredProposals.ifEmpty { pendingProposals })
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "일괄 A2UI",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (visibleProposals.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "'${selectedChannel.labelKo}' 채널에는 대기 중인 제안이 없어요.",
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                } else {
                    visibleProposals.forEach { proposal ->
                        ProactiveProposalItemCard(
                            proposal = proposal,
                            onExecuteAction = onExecuteAction,
                            onPublishToA2ui = onPublishToA2ui,
                            onReduceRoomFrequency = onReduceRoomFrequency,
                            onAlwaysAllowSafeCalendar = onAlwaysAllowSafeCalendar
                        )
                    }
                }

                if (filteredProposals.size > 2) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                            .clickable { showAllProposals = !showAllProposals }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showAllProposals) {
                                "최근 2건만 간단히 보기 ▲"
                            } else {
                                "대기 중인 제안 ${filteredProposals.size - 2}건 더 보기 ▼"
                            },
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProactiveProposalItemCard(
    proposal: ProactiveProposal,
    onExecuteAction: (proposalId: String, actionKey: String, replyText: String, snoozeMinutes: Long) -> Unit,
    onPublishToA2ui: (ProactiveProposal) -> Unit,
    onReduceRoomFrequency: (roomKey: String) -> Unit = {},
    onAlwaysAllowSafeCalendar: (roomKey: String) -> Unit = {}
) {
    val isScamPreShield = proposal.type == ProactiveProposalType.SCAM_PRE_SHIELD
    val cardShape = RoundedCornerShape(18.dp)
    val borderColor = if (isScamPreShield || proposal.hasCalendarConflict) ObsidianColors.Accent else ObsidianColors.GlassBorder
    val borderWidth = if (isScamPreShield) 1.8.dp else if (proposal.hasCalendarConflict) 1.4.dp else 1.dp

    var showEvidenceDrawer by remember(proposal.id) { mutableStateOf(false) }
    var calibrationFeedbackKo by remember(proposal.id) { mutableStateOf("") }
    val evidenceCard = remember(proposal) {
        AtomicRollbackAndExplainEngine.buildEvidenceCard(proposal)
    }
    val roomKey = remember(proposal.roomOrTitle, proposal.sender) {
        proposal.roomOrTitle.ifBlank { proposal.sender }.trim()
    }

    val sourceLine = remember(proposal.sourceApp, proposal.roomOrTitle, proposal.sender) {
        val roomLabel = proposal.roomOrTitle.ifBlank { proposal.sender }
        val parts = buildList {
            if (proposal.sourceApp.isNotBlank()) add(proposal.sourceApp)
            if (roomLabel.isNotBlank() && !roomLabel.equals(proposal.sourceApp, ignoreCase = true)) {
                add(roomLabel)
            }
            if (proposal.sender.isNotBlank() &&
                !proposal.sender.equals(roomLabel, ignoreCase = true) &&
                !proposal.sender.equals(proposal.sourceApp, ignoreCase = true)
            ) {
                add(proposal.sender)
            }
        }
        parts.joinToString(" · ").ifBlank { "알림 맥락" }
    }

    val formattedTimeChip = remember(proposal.startTimeMs, proposal.endTimeMs) {
        formatProposalTimeRange(proposal.startTimeMs, proposal.endTimeMs)
    }

    val hasNavigableLocation = proposal.location.isNotBlank() &&
        proposal.location != proposal.meetingUrl &&
        !proposal.location.startsWith("http", ignoreCase = true)

    val extraSecondaryActions = remember(proposal.secondaryActions) {
        proposal.secondaryActions.filter { sec ->
            sec.kind != ProposalActionKind.DISMISS &&
                sec.kind != ProposalActionKind.SNOOZE &&
                sec.kind != ProposalActionKind.OPEN_MAP &&
                sec.kind != ProposalActionKind.SEND_QUICK_REPLY
        }.take(2)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(ObsidianColors.SurfaceHigh)
            .border(borderWidth, borderColor, cardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Source App + Room/Sender + Type Badge (+ Read-Only Advisory Badge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = sourceLine,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (proposal.readOnlyAdvisory) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "읽기 전용",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = proposal.type.badgeKo,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 1.5 High-Contrast Monochrome Scam Pre-Shield Warning Header
        if (isScamPreShield) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.Accent)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "스미싱 사전 방패 경고",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "스미싱 주의 · 의심 링크 클릭 금지 · 연락처로 직접 확인하세요",
                    color = ObsidianColors.OnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Calendar Conflict Warning Pill (Strictly Monochrome High-Contrast)
        if (proposal.hasCalendarConflict) {
            val conflictMsg = proposal.conflictSummaryKo.ifBlank { "기존 일정과 시간이 겹칩니다." }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.Accent)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "일정 충돌 경고",
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "⚠ $conflictMsg",
                    color = ObsidianColors.OnAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 3. Title & Summary
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = proposal.titleKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 18.sp
            )
            if (proposal.summaryKo.isNotBlank()) {
                Text(
                    text = proposal.summaryKo,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // 4. Quoted Source Excerpt
        if (proposal.quotedExcerpt.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(ObsidianColors.Surface)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(2.5.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ObsidianColors.GlassHighlight)
                )
                Text(
                    text = "“${proposal.quotedExcerpt.trim()}”",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Optional Time / Location Chips
        if (formattedTimeChip.isNotBlank() || hasNavigableLocation) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (formattedTimeChip.isNotBlank()) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "일시",
                            tint = ObsidianColors.TextSecondary,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = formattedTimeChip,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (hasNavigableLocation) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable { onExecuteAction(proposal.id, "open_map", "", 60L) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "장소",
                            tint = ObsidianColors.TextSecondary,
                            modifier = Modifier.size(11.dp)
                        )
                        Text(
                            text = proposal.location,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // 6. Quick-Reply Draft Pills (1-Tap RemoteInput Reply)
        if (proposal.quickReplyDrafts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "빠른 답장 초안 · 탭하여 바로 전송",
                    color = ObsidianColors.TextMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    proposal.quickReplyDrafts.take(3).forEach { draft ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                                .clickable { onExecuteAction(proposal.id, "reply", draft, 60L) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "“${draft.take(34)}”",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.8.dp)

        // 7. Primary 1-Tap Action + Secondary Action Pills
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Primary 1-Tap Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { onExecuteAction(proposal.id, "approve", "", 60L) }
                    .padding(horizontal = 13.dp, vertical = 7.dp)
            ) {
                Text(
                    text = proposal.primaryAction.labelKo,
                    color = ObsidianColors.OnAccent,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Expand as A2UI Interactive Surface
            SecretarySecondaryActionPill(
                label = "A2UI 펼치기",
                onClick = { onPublishToA2ui(proposal) }
            )

            // Map button when location exists and primary isn't already OPEN_MAP
            if (hasNavigableLocation && proposal.primaryAction.kind != ProposalActionKind.OPEN_MAP) {
                SecretarySecondaryActionPill(
                    label = "지도",
                    onClick = { onExecuteAction(proposal.id, "open_map", "", 60L) }
                )
            }

            // Extra contextual secondary actions (e.g., 5분 전 알람, 방해금지 준비, 납부일 리마인더)
            extraSecondaryActions.forEach { sec ->
                SecretarySecondaryActionPill(
                    label = sec.labelKo,
                    onClick = { onExecuteAction(proposal.id, sec.key, "", 60L) }
                )
            }

            // Snooze 1 hour
            SecretarySecondaryActionPill(
                label = "나중에",
                muted = true,
                onClick = { onExecuteAction(proposal.id, "snooze", "", 60L) }
            )

            // Dismiss
            SecretarySecondaryActionPill(
                label = "무시",
                muted = true,
                onClick = { onExecuteAction(proposal.id, "dismiss", "", 60L) }
            )

            // Explainable "왜 제안했나요?" Evidence Drawer toggle
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (showEvidenceDrawer) ObsidianColors.Accent else ObsidianColors.Surface)
                    .border(
                        1.dp,
                        if (showEvidenceDrawer) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                        RoundedCornerShape(999.dp)
                    )
                    .clickable { showEvidenceDrawer = !showEvidenceDrawer }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "왜 제안했나요?",
                    tint = if (showEvidenceDrawer) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = "왜 제안했나요?",
                    color = if (showEvidenceDrawer) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 8. Collapsible "왜 제안했나요?" Explainable Evidence Drawer + Room Urgency Calibration
        AnimatedVisibility(visible = showEvidenceDrawer) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Surface)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = "제안 근거 · 로컬 프라이버시 투명성",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "출처: ${evidenceCard.sourceSummaryKo}",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
                Text(
                    text = "규칙: ${evidenceCard.ruleSummaryKo}",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 14.sp
                )
                Text(
                    text = "되돌리기 등급: ${evidenceCard.reversibilityLabelKo} · ${evidenceCard.confidenceBasisKo}",
                    color = ObsidianColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )

                if (roomKey.isNotBlank()) {
                    HorizontalDivider(color = ObsidianColors.Divider, thickness = 0.6.dp)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SecretarySecondaryActionPill(
                            label = "빈도 줄이기",
                            muted = true,
                            onClick = {
                                onReduceRoomFrequency(roomKey)
                                calibrationFeedbackKo = "'${roomKey}' 방 알림 빈도를 낮췄어요."
                            }
                        )
                        SecretarySecondaryActionPill(
                            label = "항상 허용",
                            muted = false,
                            onClick = {
                                onAlwaysAllowSafeCalendar(roomKey)
                                calibrationFeedbackKo = "'${roomKey}' 방 안전 일정은 빠른 승인으로 설정했어요."
                            }
                        )
                    }
                    if (calibrationFeedbackKo.isNotBlank()) {
                        Text(
                            text = calibrationFeedbackKo,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SecretarySecondaryActionPill(
    label: String,
    muted: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.Surface)
            .border(
                width = 1.dp,
                color = if (muted) ObsidianColors.GlassBorder else ObsidianColors.GlassHighlight,
                shape = RoundedCornerShape(999.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = if (muted) ObsidianColors.TextSecondary else ObsidianColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = if (muted) FontWeight.SemiBold else FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Pure 0%-Saturation ModalBottomSheet for Proactive AI Secretary Settings:
 * - Master toggle
 * - Quiet Hours summary & toggle
 * - Opt-in Open-Meteo Weather Commute Prep toggle
 * - Opt-in Floating A2UI Overlay Proposals toggle
 * - "지금 전체 스캔 실행" button
 * - Scrollable per-app mute toggle list across all 21 playbooks in [AppDetailPlaybookRegistry]
 *   with explicit read-only badges on finance/banking apps.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProactiveSecretarySettingsSheet(
    settings: SecretarySettings,
    pendingCount: Int,
    onDismiss: () -> Unit,
    onUpdateSettings: ((SecretarySettings) -> SecretarySettings) -> Unit,
    onTogglePackageMute: (packageName: String, muted: Boolean) -> Unit,
    onScanNow: () -> Unit,
    privacyProof: PrivacyProofSnapshot = PrivacyAndQuotaProofLedger.snapshot.value,
    stormShieldConfig: StormShieldConfig = NotificationStormShieldEngine.config.value,
    onToggleStormShield: (Boolean) -> Unit = { enabled ->
        if (enabled) NotificationStormShieldEngine.startFocusBlock()
        else NotificationStormShieldEngine.stopFocusBlock()
    },
    onPlaySpokenDailyBrief: () -> Unit = {},
    onExportPortabilityMarkdown: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playbooks = remember { AppDetailPlaybookRegistry.allPlaybooks }
    val quietHoursEnabled = settings.quietHoursStart != settings.quietHoursEnd
    val quietSummaryKo = if (quietHoursEnabled) {
        String.format(Locale.US, "%02d:00 ~ %02d:00", settings.quietHoursStart, settings.quietHoursEnd)
    } else {
        "꺼짐 (24시간 알림)"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = ObsidianColors.Surface,
        scrimColor = ObsidianColors.Background.copy(alpha = 0.65f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ObsidianColors.Divider)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "선제안 AI 비서 설정",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "대기 중인 제안 ${pendingCount}건 · 원탭 승인 후에만 실행돼요",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp
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

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ObsidianColors.Divider)
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 0. Anti-Muse Privacy & Quota Proof Card (Strictly Monochrome)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "로컬 프라이버시 증명",
                                tint = ObsidianColors.TextPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "로컬 프라이버시 · \$0 비용 증명",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "외부 유출 ${privacyProof.externalCloudLeaks}건",
                                color = ObsidianColors.OnAccent,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PrivacyProofMetricPill("로컬 \$0 스캔", "${privacyProof.localZeroCostScans}건")
                        PrivacyProofMetricPill("시크릿·OTP 차단", "${privacyProof.blockedSecretsCount}건")
                        PrivacyProofMetricPill("인젝션 차단", "${privacyProof.blockedPromptInjectionsCount}건")
                        PrivacyProofMetricPill("금융 읽기전용 잠금", "${privacyProof.financeReadOnlyLocksCount}건")
                    }

                    Text(
                        text = buildString {
                            append("일일 VLM 토큰: ${privacyProof.todayVlmTokensUsed} / ${privacyProof.dailyVlmBudgetCap}")
                            if (privacyProof.deterministicFallbackActive) {
                                append(" · 결정론 로컬 폴백 활성")
                            }
                        },
                        color = ObsidianColors.TextSecondary,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // 1. Master Toggle
                SecretaryToggleSettingRow(
                    title = "선제안 AI 비서 활성화",
                    subtitle = "카카오톡·업무 채팅·예약·배송·캘린더 맥락을 분석해 놓치기 쉬운 항목을 1-탭으로 제안합니다.",
                    checked = settings.enabled,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(enabled = checked) }
                    }
                )

                // 2. Quiet Hours Summary & Toggle
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "방해금지(조용한 시간) · $quietSummaryKo",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "지정된 시간대에는 잠금화면 알림을 울리지 않고 앱 인박스에만 조용히 보관합니다.",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        MonochromeSwitch(
                            checked = quietHoursEnabled,
                            onCheckedChange = { enabled ->
                                onUpdateSettings { cur ->
                                    if (enabled) {
                                        cur.copy(quietHoursStart = 23, quietHoursEnd = 7)
                                    } else {
                                        cur.copy(quietHoursStart = 0, quietHoursEnd = 0)
                                    }
                                }
                            }
                        )
                    }

                    if (quietHoursEnabled) {
                        val presets = listOf(
                            Triple("23:00~07:00", 23, 7),
                            Triple("22:00~08:00", 22, 8),
                            Triple("00:00~07:00", 0, 7)
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            presets.forEach { (label, sHour, eHour) ->
                                val selected = settings.quietHoursStart == sHour && settings.quietHoursEnd == eHour
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(if (selected) ObsidianColors.Accent else ObsidianColors.Surface)
                                        .border(
                                            1.dp,
                                            if (selected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                            RoundedCornerShape(999.dp)
                                        )
                                        .clickable {
                                            onUpdateSettings { cur ->
                                                cur.copy(quietHoursStart = sHour, quietHoursEnd = eHour)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Opt-In Weather Commute Prep (Open-Meteo)
                SecretaryToggleSettingRow(
                    title = "외출·날씨 준비 브리핑 (선택 · Open-Meteo)",
                    subtitle = "Opt-in: API 키 없이 Open-Meteo 무료 예보를 조회해 강수·우산 필요 여부를 다가오는 일정 브리핑에 추가합니다.",
                    checked = settings.weatherPrepEnabled,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(weatherPrepEnabled = checked) }
                    }
                )

                // 4. Opt-In Floating A2UI Overlay Proposals
                SecretaryToggleSettingRow(
                    title = "타 앱 위 플로팅 A2UI 제안 (선택)",
                    subtitle = "Opt-in: 중요 제안 도착 시 다른 앱 위 A2UI 오버레이 카드로 표시합니다. (다른 앱 위 표시 권한 필요)",
                    checked = settings.floatingA2uiOverlayEnabled,
                    onCheckedChange = { checked ->
                        onUpdateSettings { it.copy(floatingA2uiOverlayEnabled = checked) }
                    }
                )

                // 4.5 Notification-Storm Cognitive Focus Shield Toggle
                SecretaryToggleSettingRow(
                    title = "알림 폭풍 집중 방패 (Focus Storm Shield)",
                    subtitle = "단톡방 폭주 시 비-VIP 알림을 로컬에 보관하고 복귀 시 3줄 브리프로 정리합니다. (VIP ${stormShieldConfig.vipNames.size}명 예외)",
                    checked = stormShieldConfig.enabled && stormShieldConfig.focusBlockActive,
                    onCheckedChange = onToggleStormShield
                )

                // 5. Immediate Full Context Scan CTA
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianColors.Accent)
                        .clickable { onScanNow() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "지금 전체 스캔 실행",
                            tint = ObsidianColors.OnAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "지금 전체 스캔 실행",
                            color = ObsidianColors.OnAccent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // 5.5 Spoken Daily Brief & Cross-Lab Portability Export Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(14.dp))
                            .clickable { onPlaySpokenDailyBrief() }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = "60초 음성 브리핑 듣기",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "60초 음성 브리핑 듣기",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(14.dp))
                            .clickable { onExportPortabilityMarkdown() }
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "탈-에코시스템 Markdown 내보내기",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "탈-에코시스템 Markdown 내보내기",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = ObsidianColors.Divider)

                // 6. Scrollable Per-App Mute Toggle List (All 21 Playbooks)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "앱별 모니터링 및 음소거 (${playbooks.size}개 앱)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "음소거 ${settings.mutedPackages.size}개",
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "원치 않는 앱의 선제안 감지를 끌 수 있습니다. 금융·결제 앱은 항상 읽기 전용으로 보호됩니다.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playbooks.forEach { playbook ->
                        PlaybookMuteRow(
                            playbook = playbook,
                            isMuted = playbook.packagePatterns.any { settings.isPackageMuted(it) },
                            onToggleMute = { shouldMute ->
                                playbook.packagePatterns.forEach { pkg ->
                                    onTogglePackageMute(pkg, shouldMute)
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun SecretaryToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                color = ObsidianColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        MonochromeSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PlaybookMuteRow(
    playbook: AppDetailPlaybook,
    isMuted: Boolean,
    onToggleMute: (shouldMute: Boolean) -> Unit
) {
    val isActive = !isMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
            .clickable { onToggleMute(!isMuted) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = playbook.appLabelKo,
                    color = if (isActive) ObsidianColors.TextPrimary else ObsidianColors.TextMuted,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = playbook.domainCategory.labelKo,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (playbook.readOnlyAdvisoryOnly) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.Accent)
                            .padding(horizontal = 6.dp, vertical = 1.5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "읽기 전용 금융 보호",
                            tint = ObsidianColors.OnAccent,
                            modifier = Modifier.size(9.dp)
                        )
                        Text(
                            text = "읽기 전용",
                            color = ObsidianColors.OnAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            Text(
                text = playbook.packagePatterns.joinToString(", "),
                color = ObsidianColors.TextMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))
        MonochromeSwitch(
            checked = isActive,
            onCheckedChange = { enabled -> onToggleMute(!enabled) }
        )
    }
}

@Composable
private fun MonochromeSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = ObsidianColors.OnAccent,
            checkedTrackColor = ObsidianColors.Accent,
            checkedBorderColor = ObsidianColors.Accent,
            uncheckedThumbColor = ObsidianColors.TextSecondary,
            uncheckedTrackColor = ObsidianColors.Surface,
            uncheckedBorderColor = ObsidianColors.GlassHighlight
        )
    )
}

@Composable
private fun PrivacyProofMetricPill(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.Surface)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = label,
            color = ObsidianColors.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            color = ObsidianColors.TextPrimary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/**
 * Pure 0%-Saturation Monochrome Live Visual "Watch & Take Over" VirtualDisplay PiP Banner.
 * Displayed whenever the background virtual display is active or awaiting user takeover.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VirtualDisplayTakeoverCard(
    vdisplaySnapshot: VirtualDisplayUiSnapshot,
    onRequestTakeover: () -> Unit,
    onReturnControl: () -> Unit,
    onResumeAgent: () -> Unit = {},
    onStopVirtualDisplay: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val shouldShow = vdisplaySnapshot.status.active ||
        vdisplaySnapshot.takeoverState == VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER
    if (!shouldShow) return

    val isAwaitingTakeover = vdisplaySnapshot.takeoverState == VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER
    val isReturned = vdisplaySnapshot.takeoverState == VirtualDisplayTakeoverState.RETURNED_CONTROL

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        containerColor = ObsidianColors.Surface,
        borderColor = if (isAwaitingTakeover) ObsidianColors.Accent else ObsidianColors.GlassHighlight,
        tactileBottomLip = true,
        contentPadding = PaddingValues(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Accent)
                    )
                    Text(
                        text = "가상 디스플레이 라이브 뷰 · ${vdisplaySnapshot.mode.labelKo}",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isAwaitingTakeover) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = vdisplaySnapshot.takeoverState.labelKo,
                        color = if (isAwaitingTakeover) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            val metaLine = buildString {
                if (vdisplaySnapshot.status.active) {
                    append("Display #${vdisplaySnapshot.status.displayId} (${vdisplaySnapshot.status.width}x${vdisplaySnapshot.status.height})")
                }
                val pkg = vdisplaySnapshot.lastLaunchedPackage?.trim().orEmpty()
                if (pkg.isNotEmpty()) {
                    if (isNotEmpty()) append(" · ")
                    append(pkg.substringAfterLast('.'))
                }
                val lease = vdisplaySnapshot.leaseOwner?.trim().orEmpty()
                if (lease.isNotEmpty()) {
                    if (isNotEmpty()) append(" · 리스: ")
                    append(lease)
                }
            }
            if (metaLine.isNotBlank()) {
                Text(
                    text = metaLine,
                    color = ObsidianColors.TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (vdisplaySnapshot.reasonKo.isNotBlank()) {
                Text(
                    text = vdisplaySnapshot.reasonKo,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (!isAwaitingTakeover) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                        .clickable { onRequestTakeover() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "직접 조작 (Take Over)",
                        color = if (!isAwaitingTakeover) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isAwaitingTakeover) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassHighlight, RoundedCornerShape(999.dp))
                        .clickable { onReturnControl() }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = "제어 반환 (Return Control)",
                        color = if (isAwaitingTakeover) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (isReturned) {
                    SecretarySecondaryActionPill(
                        label = "에이전트 재개",
                        muted = false,
                        onClick = onResumeAgent
                    )
                }

                if (vdisplaySnapshot.status.active) {
                    SecretarySecondaryActionPill(
                        label = "디스플레이 종료",
                        muted = true,
                        onClick = onStopVirtualDisplay
                    )
                }
            }
        }
    }
}

private fun formatProposalTimeRange(startMs: Long?, endMs: Long?): String {
    if (startMs == null) return ""
    return runCatching {
        val zone = KoreanScheduleParser.DEFAULT_ZONE
        val startZdt = Instant.ofEpochMilli(startMs).atZone(zone)
        val dateFmt = DateTimeFormatter.ofPattern("M/d HH:mm")
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        if (endMs != null && endMs > startMs) {
            val endZdt = Instant.ofEpochMilli(endMs).atZone(zone)
            "${startZdt.format(dateFmt)}~${endZdt.format(timeFmt)}"
        } else {
            startZdt.format(dateFmt)
        }
    }.getOrDefault("")
}
