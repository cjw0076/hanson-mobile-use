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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.ActionReceipt
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
    val skills = SkillCatalog.defaultSkills
    val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
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
