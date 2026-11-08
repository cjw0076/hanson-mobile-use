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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incomingText = extractIncomingText(intent).trim()
        if (incomingText.isNotBlank()) {
            val appCtx = applicationContext
            val engine = HansonRuntimeHolder.getAgentEngine(appCtx)
            HansonForegroundService.start(appCtx)
            FloatingHudService.startIfPermitted(appCtx)

            val prompt = if (incomingText.startsWith("http://") || incomingText.startsWith("https://")) {
                "브라우저로 $incomingText 핵심 내용 요약해서 보여줘"
            } else if (incomingText.length <= 45 && (incomingText.contains("해줘") || incomingText.contains("열어") || incomingText.contains("그려") || incomingText.contains("찾아"))) {
                incomingText
            } else {
                "선택/공유된 텍스트를 노트에 저장하고 핵심만 요약해줘: \"$incomingText\""
            }

            engine.startRun(
                goalPrompt = prompt,
                appendUserBubble = true,
                laneMode = ExecutionLaneMode.AUTO
            )
            Toast.makeText(appCtx, "Hanson Nexus로 전달됨: ${incomingText.take(28)}", Toast.LENGTH_SHORT).show()
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
