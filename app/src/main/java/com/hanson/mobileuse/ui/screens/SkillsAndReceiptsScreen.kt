package com.hanson.mobileuse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.agent.ActionReceipt
import com.hanson.mobileuse.agent.AppReverseEngineeringEngine
import com.hanson.mobileuse.agent.LearnedAppProtocol
import com.hanson.mobileuse.agent.MobileUseSkill
import com.hanson.mobileuse.agent.SkillCatalog
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SkillsAndReceiptsScreen(
    receipts: List<ActionReceipt>,
    onRunSkill: (MobileUseSkill) -> Unit,
    onClearReceipts: () -> Unit
) {
    val context = LocalContext.current
    val uarpEngine = remember { AppReverseEngineeringEngine(context) }
    var learnedProtocols by remember { mutableStateOf(uarpEngine.getAllLearnedProtocols()) }
    var targetAppInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }

    val skills = SkillCatalog.defaultSkills
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // =====================================================================
        // 1. UARP Universal App Reverse-Engineering & Skill Synthesizer
        // =====================================================================
        item {
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = ObsidianColors.AccentPurple
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "UARP 앱 리버스 엔지니어링 & 자율 스킬 합성기",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "처음 보는 앱도 APK 바이너리(base.apk)·매니페스트 딥링크·Activity·UI 프레임워크를 즉시 역분석해 전용 스킬로 만듭니다.",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.8.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = targetAppInput,
                        onValueChange = { targetAppInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text(
                                "앱 이름 또는 패키지명 (예: 카카오톡, 유튜브, 토스)",
                                color = ObsidianColors.TextMuted,
                                fontSize = 12.sp
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ObsidianColors.AccentPurple,
                            unfocusedBorderColor = ObsidianColors.GlassBorder,
                            focusedTextColor = ObsidianColors.TextPrimary,
                            unfocusedTextColor = ObsidianColors.TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val q = targetAppInput.ifBlank { "설정" }
                            val snap = HansonAccessibilityService.engineState.value.latestSnapshot
                            val proto = uarpEngine.reverseEngineerAndLearn(q, snap)
                            if (proto != null) {
                                learnedProtocols = uarpEngine.getAllLearnedProtocols()
                                statusMessage = "✅ '${proto.appLabel}' 역분석 완료: ${proto.uiFramework}, 딥링크 ${proto.deepLinkSchemes.size}개, Activity ${proto.exportedActivities.size}개 추출"
                                targetAppInput = ""
                            } else {
                                statusMessage = "⚠️ '$q' 앱을 찾지 못했습니다."
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.AccentPurple)
                    ) {
                        Text("역분석 학습", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                }
                if (statusMessage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = statusMessage,
                        color = ObsidianColors.Success,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (learnedProtocols.isNotEmpty()) {
            item {
                Text(
                    text = "학습된 앱 프로토콜 (${learnedProtocols.size}개 앱 마스터)",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            items(learnedProtocols, key = { it.packageName }) { proto: LearnedAppProtocol ->
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge(text = proto.uiFramework, color = ObsidianColors.AccentPurple)
                        CodeBadge(text = "v${proto.versionName}", color = ObsidianColors.Success)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${proto.appLabel} (${proto.packageName})",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "제어 전략: ${proto.controlStrategyKo}",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp
                    )
                    if (proto.deepLinkSchemes.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "추출된 딥링크: ${proto.deepLinkSchemes.take(6).joinToString(", ")}",
                            color = ObsidianColors.Accent,
                            fontSize = 11.5.sp
                        )
                    }
                    if (proto.exportedActivities.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "진입 Activity: ${proto.exportedActivities.take(5).joinToString(", ")}",
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.2.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onRunSkill(
                                MobileUseSkill(
                                    id = "uarp_${proto.packageName}",
                                    title = "${proto.appLabel} 자율 프로토콜 실행",
                                    category = "UARP Learned",
                                    badge = proto.uiFramework.substringBefore(" "),
                                    description = proto.controlStrategyKo,
                                    goalPrompt = "${proto.appLabel} 앱을 실행하고 학습된 프로토콜(${proto.uiFramework})로 현재 화면 구성을 브리핑해줘"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${proto.appLabel} 프로토콜로 에이전트 실행", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // =====================================================================
        // 2. Built-in Preset Skills
        // =====================================================================
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ObsidianColors.Accent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "원탭 자율 모바일 유즈 스킬 카탈로그",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Google Artemis GUI 오퍼레이터 + 화면 꺼짐 답장 + OS Fast-Lane 프리셋",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        items(skills, key = { it.id }) { skill ->
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CodeBadge(text = skill.category, color = ObsidianColors.AccentPurple)
                    CodeBadge(text = skill.badge, color = ObsidianColors.Success)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = skill.title,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = skill.description,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 12.5.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onRunSkill(skill) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("즉시 실행", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        tint = ObsidianColors.Success
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "에이전트가 수행한 작업 영수증 (${receipts.size}건)",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (receipts.isNotEmpty()) {
                    IconButton(onClick = onClearReceipts) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "영수증 비우기",
                            tint = ObsidianColors.TextSecondary
                        )
                    }
                }
            }
        }

        if (receipts.isEmpty()) {
            item {
                GlassCard {
                    Text(
                        text = "아직 기록된 작업 영수증이 없습니다. 에이전트가 앱 실행, 화면 탭, 화면 꺼짐 답장, 시스템 설정 변경 등을 수행하면 되돌리기 가이드와 함께 이곳에 자동 기록됩니다.",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )
                }
            }
        } else {
            items(receipts, key = { it.id }) { rcpt ->
                GlassCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge(text = rcpt.toolName, color = ObsidianColors.Success)
                        Text(
                            text = timeFmt.format(Date(rcpt.timestampMs)),
                            color = ObsidianColors.TextMuted,
                            fontSize = 11.5.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = rcpt.summaryKo,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = rcpt.detailKo,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "되돌리기 힌트: ${rcpt.undoHintKo}",
                        color = ObsidianColors.Accent,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
    }
}
