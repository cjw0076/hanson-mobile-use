package com.hanson.mobileuse.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hanson.mobileuse.agent.GeminiMultimodalClient
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class OnboardingAiTrack(
    val id: String,
    val titleKo: String,
    val badgeKo: String,
    val subtitleKo: String
) {
    FREE_CLOUD_KEY(
        id = "free_cloud",
        titleKo = "무료 AI 키로 10초 시작",
        badgeKo = "추천",
        subtitleKo = "Google AI Studio 무료 키를 복사해 붙이면 바로 대화·화면을 이해해요."
    ),
    LOCAL_OR_BRIDGE(
        id = "local_bridge",
        titleKo = "내 컴퓨터·로컬 AI 연결 (비용 0원)",
        badgeKo = "로컬",
        subtitleKo = "노트북 CLI 브리지나 Ollama 호환 주소로 API 비용 없이 연결해요."
    ),
    OFFLINE_TRY_FIRST(
        id = "offline_first",
        titleKo = "지금은 키 없이 바로 써보기",
        badgeKo = "빠른 체험",
        subtitleKo = "더치페이·메뉴 보드·모션 코스·기본 기기 실행을 먼저 체험해요."
    )
}

data class StarterPreferenceOption(
    val id: String,
    val emoji: String,
    val category: String,
    val labelKo: String,
    val factKo: String,
    val defaultSelected: Boolean = false
)

object ConsumerOnboardingLogic {

    const val AI_STUDIO_KEY_URL = "https://aistudio.google.com/apikey"
    const val DEFAULT_LOCAL_BRIDGE_URL = "http://127.0.0.1:18889/v1"
    const val TOTAL_STEPS = 3

    val starterPreferences: List<StarterPreferenceOption> = listOf(
        StarterPreferenceOption(
            id = "pref_coffee",
            emoji = "☕",
            category = "FOOD",
            labelKo = "아이스 아메리카노",
            factKo = "커피는 아이스 아메리카노를 주로 선호해요",
            defaultSelected = true
        ),
        StarterPreferenceOption(
            id = "pref_mild",
            emoji = "🍽️",
            category = "FOOD",
            labelKo = "음식은 덜 맵게",
            factKo = "음식은 너무 맵지 않고 깔끔한 맛을 선호해요",
            defaultSelected = true
        ),
        StarterPreferenceOption(
            id = "pref_window",
            emoji = "🪟",
            category = "PLACE",
            labelKo = "조용한 창가 자리",
            factKo = "카페나 식당은 조용하고 창가 자리가 있는 곳을 좋아해요",
            defaultSelected = false
        ),
        StarterPreferenceOption(
            id = "pref_route",
            emoji = "🚇",
            category = "PLACE",
            labelKo = "환승 적은 편한 길",
            factKo = "이동 경로는 환승이 적은 편한 경로를 선호해요",
            defaultSelected = true
        ),
        StarterPreferenceOption(
            id = "pref_tone",
            emoji = "💬",
            category = "STYLE",
            labelKo = "짧고 다정한 한국어",
            factKo = "답장이나 요약은 짧고 다정한 한국어로 정리해 주세요",
            defaultSelected = true
        )
    )

    /**
     * Extracts and classifies a pasted API key or custom OpenAI-compatible URL in plain Korean.
     */
    fun classifyCredentialInput(rawInput: String): Pair<Boolean, String> {
        val clean = rawInput.trim()
        if (clean.isEmpty()) return false to "키나 주소를 입력하거나 '지금은 건너뛰기'를 눌러 시작할 수 있어요."
        val lower = clean.lowercase()
        if (clean.contains("...") ||
            lower.contains("your-api-key") ||
            lower.contains("your_api_key") ||
            lower.contains("placeholder")
        ) {
            return false to "예시 문구 대신 실제로 발급받은 키나 주소를 입력해 주세요."
        }
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            val hostPart = clean.substringAfter("://").substringBefore("/").trim()
            return if (hostPart.isNotBlank() && hostPart.length >= 3) {
                true to "로컬·호환 AI 서버 주소로 인식했어요."
            } else {
                false to "올바른 서버 주소를 입력해 주세요 (예: http://127.0.0.1:18889/v1)."
            }
        }
        val keySet = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(clean)
        return when {
            !keySet.geminiKey.isNullOrBlank() && GeminiMultimodalClient.isValidGeminiKey(keySet.geminiKey) ->
                true to "Google Gemini 키를 확인했어요. 기기 금고에 암호화 저장됩니다."
            !keySet.anthropicKey.isNullOrBlank() ->
                true to "Claude API 키를 확인했어요. 기기 금고에 암호화 저장됩니다."
            !keySet.openRouterKey.isNullOrBlank() ->
                true to "OpenRouter 키를 확인했어요. 기기 금고에 암호화 저장됩니다."
            !keySet.openAiKey.isNullOrBlank() ->
                true to "OpenAI 키를 확인했어요. 기기 금고에 암호화 저장됩니다."
            clean.length >= 16 ->
                true to "사용자 토큰 형식을 확인했어요. 기기 금고에 암호화 저장됩니다."
            else ->
                false to "키 형식이 조금 짧아요. 발급받은 키 전체를 복사해 붙여 넣어주세요."
        }
    }

    fun readinessScore(hasAiReady: Boolean, a11yReady: Boolean, hudReady: Boolean, notifReady: Boolean): Int {
        var score = 1 // Instant interactive morph cards always work out of the box
        if (hasAiReady) score++
        if (a11yReady) score++
        if (hudReady || notifReady) score++
        return score.coerceIn(1, 4)
    }

    fun readinessLabelKo(hasAiReady: Boolean, a11yReady: Boolean): String = when {
        hasAiReady && a11yReady -> "AI 대화 + 화면 제어 모두 준비 완료"
        hasAiReady -> "AI 대화·인터랙티브 카드 준비 완료 (화면 제어는 원할 때 켜기)"
        a11yReady -> "화면 제어 + 인터랙티브 카드 준비 완료 (AI 키는 나중에 연결 가능)"
        else -> "권한·키 없이 바로 체험 준비 완료"
    }
}

/**
 * Effortless 3-step consumer onboarding sheet (`ConsumerOnboardingWizardSheet`).
 * Pure 0%-saturation Paper & Carbon design, zero developer jargon, non-blocking optional setup.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConsumerOnboardingWizardSheet(
    initialApiKey: String,
    initialCustomBaseUrl: String,
    a11yReady: Boolean,
    hudReady: Boolean,
    notifReady: Boolean,
    onApplyStarterPreferences: (List<StarterPreferenceOption>) -> Unit,
    onSaveAiConnection: (apiKey: String, customBaseUrl: String, selectedModel: String?) -> Unit,
    onOneTouchSetup: () -> Unit,
    onFinish: (launchDemoPrompt: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var step by remember { mutableIntStateOf(0) }
    var selectedPrefIds by remember {
        mutableStateOf(
            ConsumerOnboardingLogic.starterPreferences
                .filter { it.defaultSelected }
                .map { it.id }
                .toSet()
        )
    }
    var selectedTrack by remember {
        mutableStateOf(
            when {
                initialApiKey.isNotBlank() -> OnboardingAiTrack.FREE_CLOUD_KEY
                initialCustomBaseUrl.isNotBlank() -> OnboardingAiTrack.LOCAL_OR_BRIDGE
                else -> OnboardingAiTrack.FREE_CLOUD_KEY
            }
        )
    }
    var apiKeyInput by remember { mutableStateOf(initialApiKey) }
    var baseUrlInput by remember {
        mutableStateOf(initialCustomBaseUrl.ifBlank { ConsumerOnboardingLogic.DEFAULT_LOCAL_BRIDGE_URL })
    }
    var showKeyPlain by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testStatusKo by remember { mutableStateOf<String?>(null) }
    var testSucceeded by remember { mutableStateOf(false) }
    var showRestrictedTip by remember { mutableStateOf(false) }

    val (inputValid, inputHintKo) = remember(apiKeyInput, baseUrlInput, selectedTrack) {
        when (selectedTrack) {
            OnboardingAiTrack.FREE_CLOUD_KEY -> {
                val trimmed = apiKeyInput.trim()
                if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                    false to "서버 주소는 '내 컴퓨터·로컬 AI 연결' 항목에서 입력해 주세요."
                } else {
                    ConsumerOnboardingLogic.classifyCredentialInput(trimmed)
                }
            }
            OnboardingAiTrack.LOCAL_OR_BRIDGE -> {
                val trimmed = baseUrlInput.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                    false to "http:// 또는 https://로 시작하는 서버 주소를 입력해 주세요."
                } else {
                    ConsumerOnboardingLogic.classifyCredentialInput(trimmed)
                }
            }
            OnboardingAiTrack.OFFLINE_TRY_FIRST -> true to "키 없이도 더치페이·메뉴 보드·모션 코스를 바로 쓸 수 있어요."
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianColors.Surface,
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Top step indicator & close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(ConsumerOnboardingLogic.TOTAL_STEPS) { idx ->
                        Box(
                            modifier = Modifier
                                .height(6.dp)
                                .width(if (idx == step) 28.dp else 10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (idx <= step) ObsidianColors.Accent else ObsidianColors.GlassBorder
                                )
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${step + 1} / ${ConsumerOnboardingLogic.TOTAL_STEPS}",
                        color = ObsidianColors.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
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

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "onboarding_step_transition"
            ) { activeStep ->
                when (activeStep) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "권한 없이 바로 써보는\n인터랙티브 AI 비서",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 22.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "긴 채팅 답장 대신, 말 한마디를 바로 조작하는 카드로 바꿔드려요. 평소 취향만 골라두면 매번 설명하지 않아도 됩니다.",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 13.5.sp,
                                lineHeight = 20.sp
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ConsumerOnboardingLogic.starterPreferences.forEach { pref ->
                                    val picked = pref.id in selectedPrefIds
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(if (picked) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                            .border(
                                                1.dp,
                                                if (picked) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                                RoundedCornerShape(999.dp)
                                            )
                                            .clickable {
                                                selectedPrefIds = if (picked) {
                                                    selectedPrefIds - pref.id
                                                } else {
                                                    selectedPrefIds + pref.id
                                                }
                                            }
                                            .padding(horizontal = 13.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "${pref.emoji} ${pref.labelKo}",
                                            color = if (picked) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (picked) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "선택됨",
                                                tint = ObsidianColors.OnAccent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
                                    .padding(14.dp)
                            ) {
                                Text(
                                    text = "🫧 선택한 취향 ${selectedPrefIds.size}개는 홈 화면 '취향' 버튼에서 언제든 한 번에 켜고 끌 수 있어요.",
                                    color = ObsidianColors.TextSecondary,
                                    fontSize = 12.5.sp,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }

                    1 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "AI 두뇌 10초 연결",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "원하는 방식을 고르세요. 키는 기기 내부 암호화 금고에만 안전하게 보관되며 외부로 공유되지 않습니다.",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OnboardingAiTrack.entries.forEach { track ->
                                    val active = selectedTrack == track
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (active) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                            .border(
                                                1.dp,
                                                if (active) ObsidianColors.Accent else ObsidianColors.GlassBorder,
                                                RoundedCornerShape(20.dp)
                                            )
                                            .clickable {
                                                selectedTrack = track
                                                testStatusKo = null
                                            }
                                            .padding(horizontal = 15.dp, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = track.titleKo,
                                                color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(999.dp))
                                                    .background(
                                                        if (active) ObsidianColors.OnAccent.copy(alpha = 0.18f)
                                                        else ObsidianColors.Surface
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = track.badgeKo,
                                                    color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Text(
                                            text = track.subtitleKo,
                                            color = if (active) ObsidianColors.OnAccent.copy(alpha = 0.85f) else ObsidianColors.TextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                            }

                            if (selectedTrack == OnboardingAiTrack.FREE_CLOUD_KEY) {
                                OutlinedTextField(
                                    value = apiKeyInput,
                                    onValueChange = {
                                        apiKeyInput = it
                                        testStatusKo = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            text = "발급받은 AI 키 붙여넣기 (Gemini · Claude · OpenAI)",
                                            color = ObsidianColors.TextMuted,
                                            fontSize = 13.sp
                                        )
                                    },
                                    singleLine = true,
                                    visualTransformation = if (showKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    shape = RoundedCornerShape(18.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ObsidianColors.TextPrimary,
                                        unfocusedBorderColor = ObsidianColors.GlassBorder,
                                        focusedTextColor = ObsidianColors.TextPrimary,
                                        unfocusedTextColor = ObsidianColors.TextPrimary,
                                        cursorColor = ObsidianColors.TextPrimary
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = { showKeyPlain = !showKeyPlain }) {
                                            Icon(
                                                imageVector = if (showKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showKeyPlain) "숨기기" else "보기",
                                                tint = ObsidianColors.TextSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(ConsumerOnboardingLogic.AI_STUDIO_KEY_URL)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Text(
                                            text = "무료 키 발급 ↗",
                                            color = ObsidianColors.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            val clip = clipboard.getText()?.text?.trim().orEmpty()
                                            if (clip.isNotEmpty()) {
                                                apiKeyInput = clip
                                                testStatusKo = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(999.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.ContentPaste,
                                            contentDescription = "붙여넣기",
                                            tint = ObsidianColors.TextPrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "클립보드 붙여넣기",
                                            color = ObsidianColors.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }
                            } else if (selectedTrack == OnboardingAiTrack.LOCAL_OR_BRIDGE) {
                                OutlinedTextField(
                                    value = baseUrlInput,
                                    onValueChange = {
                                        baseUrlInput = it
                                        testStatusKo = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text(
                                            text = "예: http://127.0.0.1:18889/v1 또는 Ollama 주소",
                                            color = ObsidianColors.TextMuted,
                                            fontSize = 13.sp
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
                            }

                            if (selectedTrack != OnboardingAiTrack.OFFLINE_TRY_FIRST) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = testStatusKo ?: inputHintKo,
                                        color = if (testSucceeded) ObsidianColors.TextPrimary else ObsidianColors.TextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = if (testSucceeded) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(999.dp))
                                            .background(ObsidianColors.SurfaceHigh)
                                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                                            .clickable(enabled = !isTesting && inputValid) {
                                                isTesting = true
                                                testStatusKo = "연결 확인 중..."
                                                scope.launch {
                                                    val res = withContext(Dispatchers.IO) {
                                                        val keyToTest = if (selectedTrack == OnboardingAiTrack.FREE_CLOUD_KEY) {
                                                            apiKeyInput.trim()
                                                        } else {
                                                            ""
                                                        }
                                                        val urlToTest = if (selectedTrack == OnboardingAiTrack.LOCAL_OR_BRIDGE) {
                                                            baseUrlInput.trim()
                                                        } else {
                                                            ""
                                                        }
                                                        val modelToTest = if (selectedTrack == OnboardingAiTrack.LOCAL_OR_BRIDGE && urlToTest.contains("18889")) {
                                                            "cli-subscription-bridge"
                                                        } else {
                                                            GeminiMultimodalClient.DEFAULT_MODEL
                                                        }
                                                        GeminiMultimodalClient().verifyConnectionHealth(
                                                            apiKey = keyToTest,
                                                            modelName = modelToTest,
                                                            customBaseUrl = urlToTest
                                                        )
                                                    }
                                                    isTesting = false
                                                    testSucceeded = res.ok
                                                    testStatusKo = if (res.ok) "✓ ${res.messageKo}" else res.messageKo
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = if (isTesting) "확인 중..." else "연결 테스트",
                                            color = ObsidianColors.TextPrimary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }

                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "필요할 때만 켜는\n폰 제어 도우미 (선택)",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 22.sp,
                                lineHeight = 30.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "지금 권한을 켜지 않아도 바로 시작할 수 있어요. 다른 앱을 대신 눌러주길 원할 때만 아래 3가지를 켜세요.",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )

                            OnboardingPermissionRow(
                                titleKo = "화면 함께 보기 (접근성)",
                                subtitleKo = "다른 앱 화면을 보고 대신 눌러줄 때 필요해요",
                                ready = a11yReady,
                                onAction = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            )
                            OnboardingPermissionRow(
                                titleKo = "다른 앱 위 미니 바",
                                subtitleKo = "작업 중 언제든 1탭으로 멈추고 확인해요",
                                ready = hudReady,
                                onAction = {
                                    val opened = runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            ).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                        true
                                    }.getOrDefault(false)
                                    if (!opened) {
                                        onOneTouchSetup()
                                    }
                                }
                            )
                            OnboardingPermissionRow(
                                titleKo = "꺼진 화면 빠른 답장 (알림)",
                                subtitleKo = "메신저 알림을 앱 열지 않고 바로 답장해요",
                                ready = notifReady,
                                onAction = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            )

                            // Android 13+ Restricted Settings calm help accordion
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(18.dp))
                                    .clickable { showRestrictedTip = !showRestrictedTip }
                                    .padding(13.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (showRestrictedTip) "▾ 접근성 스위치가 회색으로 안 눌리나요?" else "▸ 접근성 스위치가 회색으로 안 눌리나요? (안드로이드 13+ 안내)",
                                    color = ObsidianColors.TextPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (showRestrictedTip) {
                                    Text(
                                        text = "1) 아래 '앱 정보 열기'를 누르세요.\n2) 화면 오른쪽 위 점 세 개(⋮) → '제한된 설정 허용'을 누르세요.\n3) 다시 돌아와 접근성을 켜면 바로 연결됩니다.",
                                        color = ObsidianColors.TextSecondary,
                                        fontSize = 12.sp,
                                        lineHeight = 18.sp
                                    )
                                    Text(
                                        text = "앱 정보 바로 열기 ↗",
                                        color = ObsidianColors.TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                        data = Uri.fromParts("package", context.packageName, null)
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Bottom action bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step = (step - 1).coerceAtLeast(0) },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text(
                            text = "이전",
                            color = ObsidianColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Button(
                    onClick = {
                        when (step) {
                            0 -> {
                                val chosen = ConsumerOnboardingLogic.starterPreferences.filter { it.id in selectedPrefIds }
                                onApplyStarterPreferences(chosen)
                                step = 1
                            }
                            1 -> {
                                when (selectedTrack) {
                                    OnboardingAiTrack.FREE_CLOUD_KEY -> {
                                        val cleanKey = apiKeyInput.trim()
                                        if (inputValid && cleanKey.isNotEmpty()) {
                                            onSaveAiConnection(cleanKey, "", null)
                                        }
                                    }
                                    OnboardingAiTrack.LOCAL_OR_BRIDGE -> {
                                        val cleanUrl = baseUrlInput.trim()
                                        if (inputValid && cleanUrl.isNotEmpty()) {
                                            val targetModel = if (cleanUrl.contains("18889")) {
                                                "cli-subscription-bridge"
                                            } else {
                                                null
                                            }
                                            onSaveAiConnection("", cleanUrl, targetModel)
                                        }
                                    }
                                    OnboardingAiTrack.OFFLINE_TRY_FIRST -> Unit
                                }
                                step = 2
                            }
                            else -> {
                                onFinish("강남역 삼겹살 회식 18만 6천원 5명 더치페이 계산해줘")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ObsidianColors.Accent,
                        contentColor = ObsidianColors.OnAccent
                    )
                ) {
                    Text(
                        text = when (step) {
                            0 -> "다음 · AI 두뇌 연결하기"
                            1 -> if (selectedTrack == OnboardingAiTrack.OFFLINE_TRY_FIRST || !inputValid) {
                                "건너뛰고 다음으로"
                            } else {
                                "저장하고 다음으로"
                            }
                            else -> if (a11yReady) "완료 · 인터랙티브 체험 시작" else "지금은 건너뛰고 바로 시작"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun OnboardingPermissionRow(
    titleKo: String,
    subtitleKo: String,
    ready: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = titleKo,
                color = ObsidianColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitleKo,
                color = ObsidianColors.TextSecondary,
                fontSize = 11.5.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (ready) ObsidianColors.Accent else ObsidianColors.Surface)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .clickable(enabled = !ready) { onAction() }
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (ready) "✓ 켜짐" else "켜기",
                color = if (ready) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
