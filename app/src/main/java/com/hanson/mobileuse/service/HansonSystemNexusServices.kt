package com.hanson.mobileuse.service

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.hanson.mobileuse.agent.ExecutionLaneMode
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.ui.MainActivity

/**
 * **OS-Wide Quick Settings Tile (`HansonQuickTileService`)**
 *
 * Lets the user summon Hanson Floating HUD + Live Voice / Screen Nexus from the Android
 * Quick Settings notification shade while inside ANY app without leaving their current screen.
 */
@RequiresApi(Build.VERSION_CODES.N)
class HansonQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        val appCtx = applicationContext
        val engine = HansonRuntimeHolder.getAgentEngine(appCtx)
        HansonForegroundService.start(appCtx)

        val hudShowing = FloatingHudService.isShowing.value
        if (!hudShowing) {
            FloatingHudService.startIfPermitted(appCtx)
            LiveVoiceSessionManager.ensureInitialized(appCtx) { spoken ->
                engine.sendChatMessage(spoken)
            }
            Toast.makeText(appCtx, "Hanson 플로팅 보드 및 화면 연결이 활성화되었습니다.", Toast.LENGTH_SHORT).show()
        } else {
            // Trigger an instant background screen/notification briefing when tapped while active
            engine.startRun(
                goalPrompt = "지금 화면에 뭐 있는지 한 줄로 요약해줘",
                appendUserBubble = true,
                laneMode = ExecutionLaneMode.BACKGROUND
            )
            Toast.makeText(appCtx, "Hanson이 현재 화면을 분석 중입니다...", Toast.LENGTH_SHORT).show()
        }
        syncTileState()
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        val active = FloatingHudService.isShowing.value || HansonForegroundService.isRunning.value
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Hanson AI"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "능동 연결됨" else "탭하여 호출"
        }
        tile.updateTile()
    }
}

/**
 * **OS-Wide Text Selection (`ACTION_PROCESS_TEXT`) & Share Sheet (`ACTION_SEND`) Nexus Receiver**
 *
 * Connects every app on the phone to Hanson:
 * - Highlight any text in Chrome / KakaoTalk / Docs -> Tap **"Hanson AI 실행"** in the popup menu!
 * - Share any link or text from YouTube / Instagram / Browser -> Tap **"Hanson AI"** in the Share Sheet!
 */
class HansonSystemNexusReceiverActivity : Activity() {

    companion object {
        const val EXTRA_PENDING_EXTERNAL_PROMPT = "com.hanson.mobileuse.EXTRA_PENDING_EXTERNAL_PROMPT"
        const val EXTRA_PENDING_EXTERNAL_PREVIEW = "com.hanson.mobileuse.EXTRA_PENDING_EXTERNAL_PREVIEW"
        private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val CARD_REGEX = Regex("\\b(?:\\d{4}[-\\s]?){3}\\d{3,4}\\b")
        private val RRN_REGEX = Regex("\\b\\d{6}[-\\s]?[1-4]\\d{6}\\b")
        private val GOOGLE_KEY_REGEX = Regex("AIza[0-9A-Za-z_-]{10,}")
        private val OPENAI_KEY_REGEX = Regex("\\bsk-[0-9A-Za-z_-]{12,}\\b")
        private val BEARER_TOKEN_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._-]{16,}")
        private val SECRET_FIELD_REGEX = Regex(
            "(?i)(?:password|passwd|비밀번호|패스워드|계좌비밀번호|카드비밀번호|보안코드|cvv|cvc)\\s*[:=]\\s*\\S+"
        )
        private val PHONE_REGEX = Regex(
            "(?:\\+82[-\\s]?|0\\d{1,2}[-\\s]?|\\b1\\d{3}[-\\s]?)\\d{3,4}[-\\s]?\\d{4}\\b"
        )
        private val DIRECT_COMMAND_REGEX = Regex(
            "(?:해줘|해\\s*줘|열어|틀어|검색|찾아|보내|알려|켜줘|꺼줘|맞춰|저장해|요약해|답장해|예약해|번역해|정리해)"
        )
        private val SCHEDULE_REGEX = Regex(
            "(?:오늘|내일|모레|글피|이번\\s*주|다음\\s*주|(?:월|화|수|목|금|토|일)요일|오전|오후|\\d{1,2}시(?:\\s*\\d{1,2}분)?|\\d{1,2}:\\d{2}|약속|미팅|회의|일정|만남|예약)"
        )
        private val MAP_ADDRESS_REGEX = Regex(
            "(?:[가-힣0-9]+(?:대로|로|길|번길)\\s*\\d+(?:-\\d+)?|[가-힣]{1,5}(?:시|구|군)\\s+[가-힣0-9]{1,6}(?:동|읍|면|가|리)|[가-힣]{2,8}역\\b|사거리|네거리|교차로|주차장|주소|길찾기|네이버지도|카카오맵)"
        )
        private val HANGUL_REGEX = Regex("[\\uAC00-\\uD7A3]")
        private val FOREIGN_ALPHA_REGEX = Regex("[A-Za-z\\u3040-\\u30FF\\u4E00-\\u9FFF]")

        internal fun containsSensitiveSecret(rawText: String): Boolean {
            if (rawText.isBlank()) return false
            return CARD_REGEX.containsMatchIn(rawText) ||
                RRN_REGEX.containsMatchIn(rawText) ||
                GOOGLE_KEY_REGEX.containsMatchIn(rawText) ||
                OPENAI_KEY_REGEX.containsMatchIn(rawText) ||
                BEARER_TOKEN_REGEX.containsMatchIn(rawText) ||
                SECRET_FIELD_REGEX.containsMatchIn(rawText)
        }

        internal fun buildSmartNexusGoal(rawText: String): Pair<String, String> {
            val text = rawText.trim().take(1800)
            val urlMatch = URL_REGEX.find(text)?.value

            if (urlMatch != null) {
                val goal = if (text.equals(urlMatch, ignoreCase = true)) {
                    "브라우저로 $urlMatch 핵심 내용 요약해서 보여줘"
                } else {
                    "공유된 링크와 내용의 핵심만 요약해줘: \"$text\""
                }
                return goal to "한슨이 링크 핵심 내용을 정리할게요"
            }

            if (text.length <= 80 && PHONE_REGEX.containsMatchIn(text)) {
                val goal = "이 전화번호로 통화하기 전에 용건과 안내 멘트를 정리하고 전화 연결을 준비해줘: \"$text\""
                return goal to "전화 연결 도우미를 준비할게요"
            }

            if (text.length <= 60 && DIRECT_COMMAND_REGEX.containsMatchIn(text)) {
                return text to "한슨이 바로 실행할게요"
            }

            if (SCHEDULE_REGEX.containsMatchIn(text)) {
                val goal = "다음 일정 내용을 정리해서 캘린더 등록이나 시간 조율을 도와줘: \"$text\""
                return goal to "일정 도우미로 연결했어요"
            }

            if (MAP_ADDRESS_REGEX.containsMatchIn(text)) {
                val goal = "다음 장소·주소를 지도에서 찾아 위치와 가는 길을 안내해줘: \"$text\""
                return goal to "지도·장소 안내로 연결했어요"
            }

            if (!HANGUL_REGEX.containsMatchIn(text) && FOREIGN_ALPHA_REGEX.containsMatchIn(text)) {
                val goal = "다음 외국어 문장을 자연스러운 한국어로 번역하고 핵심만 요약해줘:\n\"$text\""
                return goal to "한국어 번역·요약으로 연결했어요"
            }

            val fallbackGoal = "선택/공유된 내용을 노트에 저장하고 핵심만 요약해줘: \"$text\""
            return fallbackGoal to "한슨에게 내용을 전달했어요"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingText = extractIncomingText(intent).trim()
        if (incomingText.isNotBlank()) {
            val appCtx = applicationContext
            val blindVault = HansonRuntimeHolder.getBlindVault(appCtx)
            val hasVaultSecret = blindVault.redactSensitiveText(incomingText) != incomingText
            if (containsSensitiveSecret(incomingText) || hasVaultSecret) {
                Toast.makeText(
                    appCtx,
                    "보안상 카드번호·주민번호·API 키 등 민감 정보는 전달하지 않습니다.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }

            val (prompt, toastText) = buildSmartNexusGoal(incomingText)
            val safePreview = incomingText.replace(Regex("[\\r\\n]+"), " ").take(120)

            // Security boundary: NEVER auto-execute agent runs directly from untrusted external Intents.
            // Hand off to MainActivity so the user explicitly reviews and confirms before execution.
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_PENDING_EXTERNAL_PROMPT, prompt)
                putExtra(EXTRA_PENDING_EXTERNAL_PREVIEW, safePreview)
            })
            Toast.makeText(appCtx, "$toastText — 화면에서 확인 후 실행해 주세요.", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        finish()
    }

    private fun extractIncomingText(intent: Intent?): String {
        if (intent == null) return ""
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
                } else ""
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?: ""
            }
            else -> ""
        }
    }
}
