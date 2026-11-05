package com.hanson.mobileuse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.accessibility.AccessibilityEngineState
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.ui.components.CodeBadge
import com.hanson.mobileuse.ui.components.GlassCard
import com.hanson.mobileuse.ui.components.StatusPill
import com.hanson.mobileuse.ui.theme.ObsidianColors

@Composable
fun InspectorBridgeScreen(
    a11yState: AccessibilityEngineState,
    onCaptureSnapshot: () -> Unit,
    onTestTapElement: (IndexedUiElement) -> Unit,
    onTriggerGlobalKey: (String) -> Unit,
    onToggleBridgeTokenAuth: (Boolean) -> Unit,
    onCopyText: (String) -> Unit
) {
    var filterQuery by remember { mutableStateOf("") }
    val snapshot = a11yState.latestSnapshot
    val elements = snapshot?.indexedElements.orEmpty().filter { el ->
        val q = filterQuery.trim().lowercase()
        q.isEmpty() ||
            el.text.lowercase().contains(q) ||
            el.contentDesc.lowercase().contains(q) ||
            el.resourceId.lowercase().contains(q) ||
            el.shortClass.lowercase().contains(q)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Google Artemis Loopback Bridge (:18888) Card
        item {
            GlassCard(
                containerColor = ObsidianColors.SurfaceHigh,
                borderColor = if (a11yState.bridgeRunning) ObsidianColors.Success else ObsidianColors.Warning
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Google Artemis 로컬 루프백 브리지",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "127.0.0.1:${a11yState.bridgePort} · HTTP & Raw JSON-RPC",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    StatusPill(
                        label = if (a11yState.bridgeRunning) "LISTENING :${a11yState.bridgePort}" else "OFFLINE",
                        active = a11yState.bridgeRunning
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "브리지 토큰 인증 강제 (X-Artemis-Token)",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "꺼두면 ADB/Termux/로컬 에이전트가 토큰 없이 127.0.0.1:${a11yState.bridgePort}에 즉시 연결됩니다.",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                    Switch(
                        checked = a11yState.bridgeRequireToken,
                        onCheckedChange = onToggleBridgeTokenAuth,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianColors.Background,
                            checkedTrackColor = ObsidianColors.Accent
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                val adbCmd = "adb forward tcp:18888 tcp:18888 && curl -s http://127.0.0.1:18888/snapshot | jq ."
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ObsidianColors.Surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = adbCmd,
                        color = ObsidianColors.Accent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onCopyText(adbCmd) }) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "복사",
                            tint = ObsidianColors.TextSecondary
                        )
                    }
                }

                if (a11yState.recentBridgeTraffic.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "최근 브리지 요청 (총 ${a11yState.bridgeRequestCount}건):",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.5.sp
                    )
                    a11yState.recentBridgeTraffic.take(4).forEach { entry ->
                        Text(
                            text = "• ${entry.endpoint} -> ${entry.status}",
                            color = ObsidianColors.Success,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 2. Atomic Snapshot & Global Hardware Key Tester
        item {
            GlassCard {
                Text(
                    text = "실시간 멀티윈도우 UI 트리 인스펙터 & 하드웨어 키 주입",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onCaptureSnapshot,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianColors.Accent)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "아토믹 스냅샷 캡처 (UI 트리 + 스크린샷 + 0..1000 정규화 좌표)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("HOME", "BACK", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS", "TAKE_SCREENSHOT").forEach { key ->
                        OutlinedButton(
                            onClick = { onTriggerGlobalKey(key) },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = key,
                                color = ObsidianColors.TextPrimary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                if (snapshot != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        CodeBadge("해상도: ${snapshot.screenWidth}x${snapshot.screenHeight}")
                        CodeBadge("윈도우: ${snapshot.windowCount}개", ObsidianColors.AccentPurple)
                        CodeBadge("요소: ${snapshot.indexedElements.size}개", ObsidianColors.Success)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "활성 패키지: ${snapshot.activePackage.ifBlank { "없음" }} | 스크린샷: ${if (snapshot.screenshotBase64Jpeg != null) "캡처 성공 (${snapshot.screenshotBase64Jpeg.length / 1024} KB)" else snapshot.screenshotError ?: "미포함"}",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // 3. Searchable Indexed UI Element List
        item {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "UI 요소 검색 (텍스트, contentDescription, resourceId, 클래스명)...",
                        color = ObsidianColors.TextMuted,
                        fontSize = 13.sp
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ObsidianColors.Accent,
                    unfocusedBorderColor = ObsidianColors.GlassBorder,
                    focusedTextColor = ObsidianColors.TextPrimary,
                    unfocusedTextColor = ObsidianColors.TextPrimary,
                    focusedContainerColor = ObsidianColors.Surface,
                    unfocusedContainerColor = ObsidianColors.Surface
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )
        }

        items(elements, key = { it.index }) { el ->
            GlassCard(
                contentPadding = PaddingValues(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        CodeBadge(text = "[${el.index}] ${el.shortClass}")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = el.text.ifBlank { el.contentDesc.ifBlank { el.resourceId.substringAfterLast('/') } }
                                .ifBlank { "(라벨 없음)" },
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    IconButton(onClick = { onTestTapElement(el) }) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "탭 테스트",
                            tint = ObsidianColors.Success
                        )
                    }
                }
                Text(
                    text = "정규화=[${el.normX}, ${el.normY}] · 픽셀=(${el.centerX}, ${el.centerY}) · bounds=[${el.left},${el.top}][${el.right},${el.bottom}]",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                val flags = buildList {
                    if (el.clickable) add("clickable")
                    if (el.longClickable) add("longClickable")
                    if (el.scrollable) add("scrollable")
                    if (el.editable) add("editable")
                    if (el.checked) add("checked")
                }
                if (flags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "속성: ${flags.joinToString(" · ")} | 윈도우: ${el.windowType} (L${el.windowLayer})",
                        color = ObsidianColors.Accent,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
