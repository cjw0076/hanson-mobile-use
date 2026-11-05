package com.hanson.mobileuse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.AgentCapability
import com.hanson.mobileuse.agent.PermissionHubState
import com.hanson.mobileuse.agent.PermissionLevel
import com.hanson.mobileuse.agent.PermissionStatusItem
import com.hanson.mobileuse.agent.PermissionTier
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors

@Composable
fun PermissionsHubScreen(
    permState: PermissionHubState,
    onRequestAllRuntimePermissions: () -> Unit,
    onRequestSinglePermissionItem: (PermissionStatusItem) -> Unit,
    onToggleUnrestrictedMode: (Boolean) -> Unit,
    onCycleCapabilityGovernance: (AgentCapability) -> Unit,
    onRefresh: () -> Unit
) {
    val grouped = permState.items.groupBy { it.tier }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Master Readiness & Unrestricted Autonomous Mode Card
        item {
            GlassCard(
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = ObsidianColors.Accent
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            tint = ObsidianColors.Accent
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "5-Tier 전권한 및 접근성 컨트롤 센터",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "플레이스토어 정책 제약 없는 풀스택 OS 제어 권한 매트릭스",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "새로고침",
                            tint = ObsidianColors.Accent
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "OS 권한 활성화율: ${permState.readinessPercent}%",
                        color = ObsidianColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${permState.grantedCount} / ${permState.totalCount} 항목 활성",
                        color = ObsidianColors.Success,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { permState.readinessPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = ObsidianColors.Success,
                    trackColor = ObsidianColors.SurfaceTop
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Unrestricted Autonomous Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianColors.Surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "무제한 자율 실행 모드 (Unrestricted Autonomous Mode)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "활성화 시 매 단계 사용자 승인 팝업 없이 모든 GUI·전화·SMS·설정을 에이전트가 즉시 실행합니다.",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = permState.unrestrictedAutonomousMode,
                        onCheckedChange = onToggleUnrestrictedMode,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianColors.Background,
                            checkedTrackColor = ObsidianColors.Success
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onRequestAllRuntimePermissions,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                ) {
                    Text(
                        text = "런타임 위험 권한 12종 원탭 일괄 허용 요청",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 2. Render each Tier section and its permission cards
        for (tier in PermissionTier.entries) {
            val tierItems = grouped[tier].orEmpty()
            if (tierItems.isEmpty()) continue

            item {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = tier.titleKo,
                        color = ObsidianColors.Accent,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = tier.subtitleKo,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            items(tierItems, key = { it.id }) { item ->
                PermissionItemCard(
                    item = item,
                    onActionClick = { onRequestSinglePermissionItem(item) },
                    onCycleGovernance = {
                        item.capability?.let { cap -> onCycleCapabilityGovernance(cap) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PermissionItemCard(
    item: PermissionStatusItem,
    onActionClick: () -> Unit,
    onCycleGovernance: () -> Unit
) {
    GlassCard(
        borderColor = if (item.isGranted) ObsidianColors.GlassBorder else ObsidianColors.Warning
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                CodeBadge(
                    text = item.permissionCode,
                    color = if (item.isGranted) ObsidianColors.Accent else ObsidianColors.Warning
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (item.isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (item.isGranted) ObsidianColors.Success else ObsidianColors.Warning
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (item.isGranted) "허용됨" else "설정 필요",
                    color = if (item.isGranted) ObsidianColors.Success else ObsidianColors.Warning,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = item.description,
            color = ObsidianColors.TextSecondary,
            fontSize = 12.5.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "API: ${item.apiNote}",
            color = ObsidianColors.TextMuted,
            fontSize = 11.sp
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.capability != null) {
                val govColor = when (item.governanceLevel) {
                    PermissionLevel.ALWAYS -> ObsidianColors.Success
                    PermissionLevel.ASK_EVERY_TIME -> ObsidianColors.Warning
                    PermissionLevel.NEVER -> ObsidianColors.Error
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(govColor.copy(alpha = 0.14f))
                        .border(1.dp, govColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { onCycleGovernance() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "거버넌스: ${item.governanceLevel.labelKo} (탭하여 변경)",
                        color = govColor,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            if (item.settingsAction != null || item.isRuntimePermission) {
                Button(
                    onClick = onActionClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (item.isGranted) ObsidianColors.SurfaceTop else ObsidianColors.Accent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = null,
                        tint = ObsidianColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (item.isGranted) "설정 화면 열기" else "즉시 권한 허용",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
