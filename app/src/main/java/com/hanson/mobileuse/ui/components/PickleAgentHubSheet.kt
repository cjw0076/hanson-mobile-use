package com.hanson.mobileuse.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.A2ANegotiationOutcome
import com.hanson.mobileuse.agent.AgentRunSession
import com.hanson.mobileuse.agent.AgentRunStatus
import com.hanson.mobileuse.agent.FirestoreEditionMode
import com.hanson.mobileuse.agent.FirestoreSyncUiState
import com.hanson.mobileuse.agent.PickleA2AScheduleNegotiator
import com.hanson.mobileuse.agent.ScratchpadEntry
import com.hanson.mobileuse.agent.VaultHandleSummary
import com.hanson.mobileuse.agent.VaultSecretCategory
import com.hanson.mobileuse.agent.WatcherActionMode
import com.hanson.mobileuse.agent.WatcherRule
import com.hanson.mobileuse.ui.theme.ObsidianColors

enum class PickleHubTab(val labelKo: String) {
    VAULT("볼트"),
    LIVE_AND_NOTES("라이브·메모"),
    WATCHERS("능동 감시"),
    A2A("A2A 일정")
}

/**
 * Paper & Carbon 4-tab Hub Sheet absorbing Pickle & SOTA mobile-agent workflows:
 * 1) Blind Credential Vault (Zero LLM plaintext exposure)
 * 2) Remote Live Phone Frame + Notetaker cross-app scratchpad
 * 3) Proactive Ticket / Status Watchers (Poke/Pickle pattern)
 * 4) Pickle A2A Schedule Negotiation simulator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickleAgentHubSheet(
    initialTab: PickleHubTab,
    session: AgentRunSession,
    activePackage: String,
    vaultHandles: List<VaultHandleSummary>,
    watcherRules: List<WatcherRule>,
    scratchpadEntries: List<ScratchpadEntry>,
    onDismiss: () -> Unit,
    onStopAgent: () -> Unit,
    onPutVaultSecret: (key: String, label: String, plaintext: String, category: VaultSecretCategory) -> Unit,
    onDeleteVaultSecret: (key: String) -> Unit,
    onAddWatcherRule: (title: String, targetAppOrUrl: String, keywords: List<String>, mode: WatcherActionMode) -> Unit,
    onToggleWatcherRule: (ruleId: String, enabled: Boolean) -> Unit,
    onRemoveWatcherRule: (ruleId: String) -> Unit,
    onPutScratchpad: (key: String, value: String) -> Unit,
    onRemoveScratchpad: (key: String) -> Unit,
    onClearScratchpad: () -> Unit,
    onApproveAndDial: (calleeLabel: String, phoneNumber: String, purposeKo: String, scriptText: String) -> Unit = { _, _, _, _ -> },
    cloudSyncState: FirestoreSyncUiState = FirestoreSyncUiState(),
    onSaveCloudSyncConfig: (
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String,
        editionMode: FirestoreEditionMode,
        userUid: String
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onTriggerCloudSyncNow: () -> Unit = {}
) {
    var activeTab by rememberSaveable(initialTab) { mutableStateOf(initialTab) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
                .padding(horizontal = 18.dp)
                .padding(bottom = 22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Accent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Agent Hub",
                            tint = ObsidianColors.OnAccent,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Agent Safety & Memory Hub",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Blind Vault · Live Frame · Watchers · A2A",
                            color = ObsidianColors.TextMuted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
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

            // 4-Tab Monochrome Pill Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PickleHubTab.entries.forEach { tab ->
                    val selected = tab == activeTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                            .clickable { activeTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.labelKo,
                            color = if (selected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = ObsidianColors.Divider)
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 260.dp, max = 500.dp)
            ) {
                when (activeTab) {
                    PickleHubTab.VAULT -> BlindVaultTabContent(
                        handles = vaultHandles,
                        onPutSecret = onPutVaultSecret,
                        onDeleteSecret = onDeleteVaultSecret
                    )
                    PickleHubTab.LIVE_AND_NOTES -> LiveAndNotetakerTabContent(
                        session = session,
                        activePackage = activePackage,
                        entries = scratchpadEntries,
                        onStopAgent = onStopAgent,
                        onPutScratchpad = onPutScratchpad,
                        onRemoveScratchpad = onRemoveScratchpad,
                        onClearScratchpad = onClearScratchpad,
                        cloudSyncState = cloudSyncState,
                        onSaveCloudSyncConfig = onSaveCloudSyncConfig,
                        onTriggerCloudSyncNow = onTriggerCloudSyncNow
                    )
                    PickleHubTab.WATCHERS -> ProactiveWatchersTabContent(
                        rules = watcherRules,
                        onAddRule = onAddWatcherRule,
                        onToggleRule = onToggleWatcherRule,
                        onRemoveRule = onRemoveWatcherRule
                    )
                    PickleHubTab.A2A -> A2aScheduleTabContent(
                        onApproveAndDial = onApproveAndDial
                    )
                }
            }
        }
    }
}

@Composable
private fun BlindVaultTabContent(
    handles: List<VaultHandleSummary>,
    onPutSecret: (key: String, label: String, plaintext: String, category: VaultSecretCategory) -> Unit,
    onDeleteSecret: (key: String) -> Unit
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    var labelInput by rememberSaveable { mutableStateOf("") }
    var secretInput by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(VaultSecretCategory.LOGIN) }
    var revealSecret by remember { mutableStateOf(false) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "vault_banner") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = ObsidianColors.TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "AES-256-GCM Blind Vault · LLM 평문 비노출",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "AI 모델은 vault://핸들 이름만 보며, 실제 복호화 입력은 기기 내부 Accessibility/KeyStore에서 직접 수행됩니다.",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        item(key = "vault_add_form") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "+ 새 자격증명 핸들 추가",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    VaultSecretCategory.entries.forEach { cat ->
                        val active = cat == selectedCategory
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = cat.labelKo,
                                color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioMonoTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        placeholder = "핸들 키 (예: naver_id)",
                        modifier = Modifier.weight(1f)
                    )
                    StudioMonoTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        placeholder = "표시 이름 (예: 네이버 계정)",
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StudioMonoTextField(
                        value = secretInput,
                        onValueChange = { secretInput = it },
                        placeholder = "비밀번호 / 카드 / 인증값 (암호화 저장)",
                        visualTransformation = if (revealSecret) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                            .clickable { revealSecret = !revealSecret }
                            .padding(horizontal = 10.dp, vertical = 9.dp)
                    ) {
                        Text(
                            text = if (revealSecret) "숨김" else "보기",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                val canSave = keyInput.isNotBlank() && secretInput.isNotBlank()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canSave) ObsidianColors.Accent else ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = canSave) {
                            onPutSecret(
                                keyInput.trim(),
                                labelInput.trim().ifBlank { keyInput.trim() },
                                secretInput,
                                selectedCategory
                            )
                            keyInput = ""
                            labelInput = ""
                            secretInput = ""
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "AES-256-GCM 암호화 저장",
                        color = if (canSave) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        if (handles.isEmpty()) {
            item(key = "vault_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "등록된 Vault 핸들이 없습니다. 위 양식으로 첫 핸들을 등록해 보세요.",
                        color = ObsidianColors.TextMuted,
                        fontSize = 11.5.sp
                    )
                }
            }
        } else {
            items(handles, key = { it.key }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = item.handle,
                                color = ObsidianColors.TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            CodeBadge(text = item.category.labelKo, color = ObsidianColors.TextSecondary)
                            if (item.category.alwaysRequireApproval) {
                                CodeBadge(text = "승인필수", color = ObsidianColors.Warning)
                            }
                        }
                        Text(
                            text = "${item.label} · ${item.maskedPreview}",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                            .clickable { onDeleteSecret(item.key) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "삭제",
                            tint = ObsidianColors.TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAndNotetakerTabContent(
    session: AgentRunSession,
    activePackage: String,
    entries: List<ScratchpadEntry>,
    onStopAgent: () -> Unit,
    onPutScratchpad: (key: String, value: String) -> Unit,
    onRemoveScratchpad: (key: String) -> Unit,
    onClearScratchpad: () -> Unit,
    cloudSyncState: FirestoreSyncUiState = FirestoreSyncUiState(),
    onSaveCloudSyncConfig: (
        enabled: Boolean,
        projectId: String,
        applicationId: String,
        apiKeyPlaintext: String,
        databaseId: String,
        editionMode: FirestoreEditionMode,
        userUid: String
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onTriggerCloudSyncNow: () -> Unit = {}
) {
    var noteKey by rememberSaveable { mutableStateOf("") }
    var noteVal by rememberSaveable { mutableStateOf("") }

    val latestB64 = session.steps.lastOrNull { !it.screenshotBase64.isNullOrBlank() }?.screenshotBase64
    val previewBitmap: Bitmap? = remember(latestB64) {
        if (latestB64.isNullOrBlank()) null else runCatching {
            val bytes = Base64.decode(latestB64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "live_remote_frame") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "실시간 에이전트 라이브 뷰",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${session.status.labelKo} · ${(activePackage.ifBlank { "Hanson Board" }).substringAfterLast('.')}",
                            color = ObsidianColors.TextMuted,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (session.status == AgentRunStatus.OBSERVING ||
                        session.status == AgentRunStatus.THINKING ||
                        session.status == AgentRunStatus.ACTING
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Accent)
                                .clickable { onStopAgent() }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "작업 중지",
                                tint = ObsidianColors.OnAccent,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "중지",
                                color = ObsidianColors.OnAccent,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Live agent step frame",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(82.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "단계 ${session.currentIteration}/${session.maxIterations} · 실행 시 마지막 화면 프레임이 여기에 실시간 표시됩니다.",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        item(key = "notetaker_add") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Notetaker 크로스앱 작업 메모 (${entries.size})",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (entries.isNotEmpty()) {
                        Text(
                            text = "전체 비우기",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onClearScratchpad() }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StudioMonoTextField(
                        value = noteKey,
                        onValueChange = { noteKey = it },
                        placeholder = "키 (예: 주문번호)",
                        modifier = Modifier.weight(0.9f)
                    )
                    StudioMonoTextField(
                        value = noteVal,
                        onValueChange = { noteVal = it },
                        placeholder = "메모 내용",
                        modifier = Modifier.weight(1.3f)
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianColors.Accent)
                            .clickable(enabled = noteKey.isNotBlank() && noteVal.isNotBlank()) {
                                onPutScratchpad(noteKey.trim(), noteVal.trim())
                                noteKey = ""
                                noteVal = ""
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "추가",
                            tint = ObsidianColors.OnAccent,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        items(entries, key = { it.key }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entry.key,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        CodeBadge(
                            text = if (entry.autoCaptured) "AUTO" else "MANUAL",
                            color = ObsidianColors.TextSecondary
                        )
                    }
                    Text(
                        text = entry.value,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = ObsidianColors.TextMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onRemoveScratchpad(entry.key) }
                )
            }
        }

        item(key = "byo_firestore_sync_card") {
            FirestoreCloudSyncCard(
                syncState = cloudSyncState,
                onSaveConfig = onSaveCloudSyncConfig,
                onSyncNow = onTriggerCloudSyncNow
            )
        }
    }
}

@Composable
private fun ProactiveWatchersTabContent(
    rules: List<WatcherRule>,
    onAddRule: (title: String, targetAppOrUrl: String, keywords: List<String>, mode: WatcherActionMode) -> Unit,
    onToggleRule: (ruleId: String, enabled: Boolean) -> Unit,
    onRemoveRule: (ruleId: String) -> Unit
) {
    var titleInput by rememberSaveable { mutableStateOf("") }
    var keywordsInput by rememberSaveable { mutableStateOf("") }
    var targetInput by rememberSaveable { mutableStateOf("") }
    var actionMode by rememberSaveable { mutableStateOf(WatcherActionMode.HOLD_FOR_APPROVAL) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        item(key = "watcher_form") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Proactive Trigger · 취소표·상태 감시",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "취소표·잔여석" to "잔여좌석, 예매가능, 1석 남음",
                        "품절 재입고" to "구매하기, 재입고, 장바구니"
                    ).forEach { (presetTitle, presetKws) ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                .clickable {
                                    titleInput = presetTitle
                                    keywordsInput = presetKws
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "+ $presetTitle",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                StudioMonoTextField(
                    value = titleInput,
                    onValueChange = { titleInput = it },
                    placeholder = "규칙 제목 (예: KTX 취소표 감시)",
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioMonoTextField(
                        value = keywordsInput,
                        onValueChange = { keywordsInput = it },
                        placeholder = "감시 키워드(쉼표 구분)",
                        modifier = Modifier.weight(1.2f)
                    )
                    StudioMonoTextField(
                        value = targetInput,
                        onValueChange = { targetInput = it },
                        placeholder = "앱/패키지(선택)",
                        modifier = Modifier.weight(0.8f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WatcherActionMode.entries.forEach { m ->
                        val active = m == actionMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface)
                                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                                .clickable { actionMode = m }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = m.labelKo,
                                color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                val parsedKeywords = keywordsInput.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val canAdd = parsedKeywords.isNotEmpty()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (canAdd) ObsidianColors.Accent else ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .clickable(enabled = canAdd) {
                            onAddRule(
                                titleInput.trim().ifBlank { parsedKeywords.joinToString(" · ") },
                                targetInput.trim(),
                                parsedKeywords,
                                actionMode
                            )
                            titleInput = ""
                            keywordsInput = ""
                            targetInput = ""
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "능동 감시 규칙 등록",
                        color = if (canAdd) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        items(rules, key = { it.id }) { rule ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = rule.title,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "키워드: ${rule.matchKeywords.joinToString(", ")} · 감지 ${rule.hitCount}회",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (rule.enabled) ObsidianColors.Accent else ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable { onToggleRule(rule.id, !rule.enabled) }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (rule.enabled) "ON" else "OFF",
                        color = if (rule.enabled) ObsidianColors.OnAccent else ObsidianColors.TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "삭제",
                    tint = ObsidianColors.TextMuted,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onRemoveRule(rule.id) }
                )
            }
        }
    }
}

@Composable
private fun A2aScheduleTabContent(
    onApproveAndDial: (calleeLabel: String, phoneNumber: String, purposeKo: String, scriptText: String) -> Unit = { _, _, _, _ -> }
) {
    var peerName by rememberSaveable { mutableStateOf("김대리 에이전트") }
    var durationMin by rememberSaveable { mutableIntStateOf(60) }
    var preferredArea by rememberSaveable { mutableStateOf("성수역 · Google Meet") }
    var myBusyCsv by rememberSaveable { mutableStateOf("10:00-11:30, 14:00-15:00") }
    var peerFreeCsv by rememberSaveable { mutableStateOf("10:30-13:30, 16:00-19:00") }
    var outcome by remember { mutableStateOf<A2ANegotiationOutcome?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianColors.SurfaceHigh)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(16.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Pickle A2A 비공개 일정 협상",
                color = ObsidianColors.TextPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioMonoTextField(
                    value = peerName,
                    onValueChange = { peerName = it },
                    placeholder = "상대 이름/에이전트",
                    modifier = Modifier.weight(1f)
                )
                StudioMonoTextField(
                    value = preferredArea,
                    onValueChange = { preferredArea = it },
                    placeholder = "선호 지역/링크",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 90).forEach { mins ->
                    val active = durationMin == mins
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
                            .clickable { durationMin = mins }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${mins}분",
                            color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            StudioMonoTextField(
                value = myBusyCsv,
                onValueChange = { myBusyCsv = it },
                placeholder = "내 바쁜 시간 (HH:mm-HH:mm, ...)",
                modifier = Modifier.fillMaxWidth()
            )
            StudioMonoTextField(
                value = peerFreeCsv,
                onValueChange = { peerFreeCsv = it },
                placeholder = "상대 가능 시간 (HH:mm-HH:mm, ...)",
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Accent)
                    .clickable {
                        outcome = PickleA2AScheduleNegotiator.negotiate(
                            peerName = peerName,
                            durationMinutes = durationMin,
                            preferredArea = preferredArea,
                            myBusyCsv = myBusyCsv,
                            peerFreeCsv = peerFreeCsv
                        )
                    }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = ObsidianColors.OnAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "A2A 일정 협상 실행",
                    color = ObsidianColors.OnAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        val currentOutcome = outcome
        if (currentOutcome != null) {
            Text(
                text = currentOutcome.summaryKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
            currentOutcome.candidates.forEach { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${c.dayLabel} ${c.startHHmm}–${c.endHHmm} (${c.durationMin}분)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${c.recommendedArea} · ${c.rationaleKo}",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                    CodeBadge(text = "${c.score}점", color = ObsidianColors.TextPrimary)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianColors.Background)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp)
            ) {
                Text(
                    text = currentOutcome.a2aJsonRpcPayload.toString(2),
                    color = ObsidianColors.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                )
            }
        }
        CallConciergeCard(
            onApproveAndDial = onApproveAndDial,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
    }
}

@Composable
private fun StudioMonoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianColors.Surface)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                color = ObsidianColors.TextMuted,
                fontSize = 11.5.sp,
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
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace
            ),
            cursorBrush = SolidColor(ObsidianColors.Accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
