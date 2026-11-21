package com.hanson.mobileuse.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.ui.theme.ObsidianColors

/**
 * Warm editorial bottom sheet for viewing and configuring AI API credentials (F17).
 * Squircle geometry (26dp corner radius), password masking with visibility toggle,
 * instant validation, clipboard paste, and safe persistence callback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorialApiKeySheet(
    initialKey: String = "",
    onDismiss: () -> Unit,
    onSaveKey: (String) -> Unit,
    initialCustomBaseUrl: String = "",
    currentModelName: String = GeminiMultimodalClient.DEFAULT_MODEL,
    onSaveCustomBaseUrl: (String) -> Unit = {}
) {
    var keyInput by remember { mutableStateOf(initialKey) }
    var customBaseUrlInput by remember { mutableStateOf(initialCustomBaseUrl) }
    var isPasswordVisible by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }
    var connectionTestMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current

    val trimmed = keyInput.trim()
    val trimmedUrl = customBaseUrlInput.trim()
    val isValid = remember(trimmed, trimmedUrl) {
        (trimmed.isNotEmpty() && (GeminiMultimodalClient.isValidGeminiKey(trimmed) || trimmed.startsWith("sk-") || trimmed.length >= 16)) ||
            trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ObsidianColors.Surface,
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(ObsidianColors.TextMuted.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API 키",
                            tint = ObsidianColors.TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "AI 토큰 설정",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Gemini · Claude · OpenAI · OpenRouter · Custom URL",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.SurfaceHigh)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Key Input Field
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "AIzaSy...",
                        color = ObsidianColors.TextMuted,
                        fontSize = 14.sp
                    )
                },
                singleLine = true,
                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    if (trimmed.isNotEmpty()) {
                        onSaveKey(trimmed)
                        onDismiss()
                    }
                }),
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ObsidianColors.TextPrimary,
                    unfocusedBorderColor = ObsidianColors.GlassBorder,
                    focusedTextColor = ObsidianColors.TextPrimary,
                    unfocusedTextColor = ObsidianColors.TextPrimary,
                    cursorColor = ObsidianColors.TextPrimary
                ),
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        if (keyInput.isNotEmpty()) {
                            IconButton(onClick = { keyInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "지우기",
                                    tint = ObsidianColors.TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                            Icon(
                                imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (isPasswordVisible) "숨기기" else "보기",
                                tint = ObsidianColors.TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = customBaseUrlInput,
                onValueChange = { customBaseUrlInput = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "(선택) OpenAI 호환 URL · 예: http://192.168.0.10:11434/v1",
                        color = ObsidianColors.TextMuted,
                        fontSize = 12.5.sp
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ObsidianColors.TextPrimary,
                    unfocusedBorderColor = ObsidianColors.GlassBorder,
                    focusedTextColor = ObsidianColors.TextPrimary,
                    unfocusedTextColor = ObsidianColors.TextPrimary,
                    cursorColor = ObsidianColors.TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action row: Paste from clipboard, 1-Tap Health Check & Token status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable {
                                val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                if (clip.isNotEmpty()) {
                                    keyInput = clip
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentPaste,
                            contentDescription = "붙여넣기",
                            tint = ObsidianColors.TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "붙여넣기",
                            color = ObsidianColors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .clickable(enabled = !isTestingConnection) {
                                isTestingConnection = true
                                connectionTestMsg = "확인 중..."
                                scope.launch {
                                    val res = withContext(Dispatchers.IO) {
                                        GeminiMultimodalClient().verifyConnectionHealth(
                                            apiKey = trimmed,
                                            modelName = currentModelName,
                                            customBaseUrl = trimmedUrl
                                        )
                                    }
                                    connectionTestMsg = res.messageKo
                                    isTestingConnection = false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isTestingConnection) "확인 중..." else "연결 테스트",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (trimmed.isNotEmpty() || trimmedUrl.isNotEmpty()) {
                    val statusText = if (isValid) "형식 유효 ✓" else "토큰 확인 필요"
                    Text(
                        text = statusText,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (!connectionTestMsg.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = connectionTestMsg!!,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.5.sp
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Save Button
            Button(
                onClick = {
                    if (trimmed.isNotEmpty() || trimmedUrl.isNotEmpty()) {
                        onSaveCustomBaseUrl(trimmedUrl)
                        onSaveKey(trimmed)
                        onDismiss()
                    }
                },
                enabled = trimmed.isNotEmpty() || trimmedUrl.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ObsidianColors.TextPrimary,
                    contentColor = ObsidianColors.Surface,
                    disabledContainerColor = ObsidianColors.SurfaceHigh,
                    disabledContentColor = ObsidianColors.TextMuted
                )
            ) {
                Text(
                    text = "API 키 저장 및 연결",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
