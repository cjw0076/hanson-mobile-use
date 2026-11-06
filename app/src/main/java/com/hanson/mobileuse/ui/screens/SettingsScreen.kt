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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.AgentEngineConfig
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.ui.components.AhaboardDotGridBox
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlin.math.roundToInt

/**
 * Deep Level-2 Engine Settings Screen.
 * Hidden from the primary surface and reachable only via `⋯` -> `심층 엔진 설정 (고급) ↗`.
 * Most routine adjustments (model switch, voice toggle, permissions) are handled directly by the agent.
 */
@Composable
fun SettingsScreen(
    config: AgentEngineConfig,
    isDaemonRunning: Boolean,
    onSaveConfig: (apiKey: String, modelName: String, maxIter: Int, autoHud: Boolean) -> Unit,
    onToggleDaemon: (Boolean) -> Unit,
    onOneTouchAutoSetup: () -> Unit = {},
    onClose: () -> Unit = {}
) {
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var modelName by remember(config.modelName) {
        mutableStateOf(GeminiMultimodalClient.normalize2026ModelName(config.modelName))
    }
    var maxIter by remember(config.maxIterations) { mutableFloatStateOf(config.maxIterations.toFloat()) }
    var autoHud by remember(config.autoShowFloatingHud) { mutableStateOf(config.autoShowFloatingHud) }
    var showKey by remember { mutableStateOf(false) }
    var showAllModels by remember { mutableStateOf(false) }

    val allModels = GeminiMultimodalClient.AISTUDIO_OFFICIAL_MODELS
    val displayedModels = if (showAllModels) allModels else allModels.take(8)

    AhaboardDotGridBox(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Level-2 Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(ObsidianColors.Background)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "보드로 돌아가기",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "심층 엔진 설정 (고급)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "Level-2 · 일반 설정은 대화창에서 에이전트에게 바로 지시하세요",
                            color = ObsidianColors.TextMuted,
                            fontSize = 10.5.sp
                        )
                    }
                }
                CodeBadge("LEVEL-2")
            }

            HorizontalDivider(color = ObsidianColors.Divider)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. One-Touch Environment Self-Heal Card
                item {
                    GlassCard(tactileBottomLip = true) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "권한 및 OS 브리지 원터치 자동 구성",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "접근성·알림 리스너·오버레이·무제한 거버넌스를 한 번에 복구합니다",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ObsidianColors.Accent)
                                    .clickable { onOneTouchAutoSetup() }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Text(
                                    text = "원터치 복구",
                                    color = ObsidianColors.OnAccent,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // 2. API Key & Model Config
                item {
                    GlassCard {
                        Text(
                            text = "API 인증 및 추론 모델",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Gemini API Key (AIza...)", color = ObsidianColors.TextSecondary) },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showKey = !showKey }) {
                                    Icon(
                                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        tint = ObsidianColors.TextSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ObsidianColors.Accent,
                                unfocusedBorderColor = ObsidianColors.GlassBorder,
                                focusedTextColor = ObsidianColors.TextPrimary,
                                unfocusedTextColor = ObsidianColors.TextPrimary
                            ),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "모델 카탈로그 (${allModels.size}종)",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (showAllModels) "핵심 모델만 보기" else "전체 26종 펼치기",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { showAllModels = !showAllModels }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        displayedModels.forEach { (id, desc) ->
                            val selected = modelName == id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) ObsidianColors.SurfaceHigh else ObsidianColors.Surface)
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable { modelName = id }
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = id,
                                        color = ObsidianColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = desc,
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                if (selected) {
                                    CodeBadge("ACTIVE")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "최대 자율 궤적 스텝: ${maxIter.roundToInt()}회",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = maxIter,
                            onValueChange = { maxIter = it },
                            valueRange = 5f..35f,
                            steps = 29,
                            colors = SliderDefaults.colors(
                                thumbColor = ObsidianColors.Accent,
                                activeTrackColor = ObsidianColors.Accent
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "플로팅 오버레이 자동 표시",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "타 앱 제어 시 상단 미니 보드 표시",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = autoHud,
                                onCheckedChange = { autoHud = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ObsidianColors.OnAccent,
                                    checkedTrackColor = ObsidianColors.Accent
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "백그라운드 상시 데몬",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "알림 리스너 및 브리지 상시 유지",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isDaemonRunning,
                                onCheckedChange = onToggleDaemon,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = ObsidianColors.OnAccent,
                                    checkedTrackColor = ObsidianColors.Accent
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = {
                                onSaveConfig(apiKey.trim(), modelName.trim(), maxIter.roundToInt(), autoHud)
                                onClose()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ObsidianColors.Accent,
                                contentColor = ObsidianColors.OnAccent
                            )
                        ) {
                            Text("저장 후 보드로 돌아가기", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
