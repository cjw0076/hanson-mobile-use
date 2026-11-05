package com.hanson.mobileuse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.hanson.mobileuse.agent.ToolSpecRegistry
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    config: AgentEngineConfig,
    isDaemonRunning: Boolean,
    onSaveConfig: (apiKey: String, modelName: String, maxIter: Int, autoHud: Boolean) -> Unit,
    onToggleDaemon: (Boolean) -> Unit
) {
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    var modelName by remember(config.modelName) {
        mutableStateOf(GeminiMultimodalClient.normalize2026ModelName(config.modelName))
    }
    var maxIter by remember(config.maxIterations) { mutableFloatStateOf(config.maxIterations.toFloat()) }
    var autoHud by remember(config.autoShowFloatingHud) { mutableStateOf(config.autoShowFloatingHud) }
    var showKey by remember { mutableStateOf(false) }

    val modelPresets = GeminiMultimodalClient.AISTUDIO_OFFICIAL_MODELS

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            GlassCard(
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = ObsidianColors.Accent
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = ObsidianColors.Accent)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Gemini 멀티모달 VLM 엔진 설정",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Google AI Studio API Key 및 모델·반복 한도 구성",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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

                Text(
                    text = "멀티모달 에이전트 모델 선택",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                modelPresets.forEach { (id, desc) ->
                    val selected = modelName == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) ObsidianColors.AccentDim else ObsidianColors.Surface)
                            .clickable { modelName = id }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = id,
                                color = if (selected) ObsidianColors.Accent else ObsidianColors.TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = desc,
                                color = ObsidianColors.TextSecondary,
                                fontSize = 11.5.sp
                            )
                        }
                        if (selected) {
                            CodeBadge("선택됨", ObsidianColors.Success)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "최대 자율 추론 스텝 (Max Iterations): ${maxIter.roundToInt()}회",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.5.sp,
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
                            text = "에이전트 실행 시 플로팅 HUD 자동 표시",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "다른 앱 위에서 동작할 때 상단 드래그형 오버레이 컨트롤러 표시",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    Switch(
                        checked = autoHud,
                        onCheckedChange = { autoHud = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianColors.Background,
                            checkedTrackColor = ObsidianColors.Success
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        onSaveConfig(apiKey.trim(), modelName.trim(), maxIter.roundToInt(), autoHud)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("에이전트 엔진 설정 저장", fontWeight = FontWeight.Bold)
                }
            }
        }

        item {
            GlassCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "백그라운드 상시 실행 워치독 데몬 (Foreground Service)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "화면 꺼짐 알림 리스너 재바인딩 및 Artemis 브리지 상시 유지",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                    Switch(
                        checked = isDaemonRunning,
                        onCheckedChange = onToggleDaemon,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianColors.Background,
                            checkedTrackColor = ObsidianColors.Success
                        )
                    )
                }
            }
        }

        item {
            GlassCard {
                Text(
                    text = "등록된 통합 도구 스펙 레지스트리 (${ToolSpecRegistry.allSpecs.size} Tools)",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToolSpecRegistry.allSpecs.forEach { spec ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CodeBadge(spec.name, ObsidianColors.Accent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = spec.description,
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
