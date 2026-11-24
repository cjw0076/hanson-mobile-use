package com.hanson.mobileuse.agent

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.CoordinateNormalizer
import com.hanson.mobileuse.accessibility.GestureController
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.HierarchyDumper
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import org.json.JSONObject
import kotlin.math.roundToInt

data class ToolExecutionOutcome(
    val succeeded: Boolean,
    val outputText: String,
    val receipt: ActionReceipt? = null,
    val mediaBlock: MultimodalMediaBlock? = null,
    val structuredCard: StructuredUiCard? = null
)

/**
 * Executes all Mobile-Use & Workspace tools across the Accessibility Engine,
 * Screen-Off Notification Cache, Local Sandbox Workspace, and Android OS APIs,
 * producing verifiable ActionReceipts.
 */
class AgentToolExecutionHarness @JvmOverloads constructor(
    private val context: Context,
    private val workspaceEngine: MobileWorkspaceEngine? = null
) {

    private val resolvedWorkspaceEngine: MobileWorkspaceEngine by lazy {
        workspaceEngine ?: MobileWorkspaceEngine(context.applicationContext ?: context).also {
            it.initWorkspace()
        }
    }

    companion object {
        private const val NOTES_PREFS = "hanson_agent_memory_notes"

        private val KNOWN_APP_ALIASES = mapOf(
            "카카오톡" to "com.kakao.talk",
            "카톡" to "com.kakao.talk",
            "kakaotalk" to "com.kakao.talk",
            "카카오택시" to "com.kakao.taxi",
            "카카오t" to "com.kakao.taxi",
            "카카오페이" to "com.kakaopay.app",
            "카카오뱅크" to "com.kakaobank.channel",
            "카뱅" to "com.kakaobank.channel",
            "유튜브" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "유튜브뮤직" to "com.google.android.apps.youtube.music",
            "설정" to "com.android.settings",
            "settings" to "com.android.settings",
            "크롬" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "네이버" to "com.nhn.android.search",
            "naver" to "com.nhn.android.search",
            "네이버지도" to "com.nhn.android.nmap",
            "네이버카페" to "com.nhn.android.navercafe",
            "카카오맵" to "net.daum.android.map",
            "배달의민족" to "com.sampleapp",
            "배달의 민족" to "com.sampleapp",
            "배민" to "com.sampleapp",
            "쿠팡이츠" to "com.coupang.mobile.eats",
            "쿠팡" to "com.coupang.mobile",
            "요기요" to "com.fineapp.yogiyo",
            "당근" to "com.towneers.www",
            "당근마켓" to "com.towneers.www",
            "토스" to "viva.republica.toss",
            "인스타그램" to "com.instagram.android",
            "인스타" to "com.instagram.android",
            "챗지피티" to "com.openai.chatgpt",
            "체지피티" to "com.openai.chatgpt",
            "chatgpt" to "com.openai.chatgpt",
            "텔레그램" to "org.telegram.messenger",
            "슬랙" to "com.Slack",
            "디스코드" to "com.discord",
            "갤러리" to "com.sec.android.gallery3d",
            "사진첩" to "com.sec.android.gallery3d",
            "카메라" to "com.sec.android.app.camera",
            "시계" to "com.sec.android.app.clockpackage"
        )

        /**
         * Normalizes Sino-Korean counter prefixes (e.g. "삼번 출구" -> "3번 출구", "사층 회의실" -> "4층 회의실")
         * for voice STT queries.
         */
        fun normalizeSinoKoreanNumberPrefix(raw: String): String {
            var out = raw
            val counters = listOf("번", "층", "팀", "호", "조", "기", "차")
            val sinoDigits = listOf(
                "일" to "1", "이" to "2", "삼" to "3", "사" to "4", "오" to "5",
                "육" to "6", "칠" to "7", "팔" to "8", "구" to "9"
            )
            for ((ko, num) in sinoDigits) {
                for (c in counters) {
                    if (out.startsWith("$ko$c")) {
                        out = "$num$c" + out.removePrefix("$ko$c")
                    }
                }
            }
            return out
        }

        /**
         * Normalizes Korean/English UI text and STT utterances so Sino-Korean counters
         * (e.g. "사층 회의실" and "4층회의실") normalize & match reliably.
         */
        fun normalizeKoreanUiToken(raw: String): String {
            var s = raw.lowercase()
                .replace(Regex("[\\s'\"`·•,\\-_.:;/\\\\()\\[\\]{}!?~]+"), "")
            val sinoDigits = listOf(
                "일" to "1", "이" to "2", "삼" to "3", "사" to "4", "오" to "5",
                "육" to "6", "칠" to "7", "팔" to "8", "구" to "9"
            )
            val counters = listOf("번", "층", "팀", "호", "조", "기", "차")
            for ((ko, num) in sinoDigits) {
                for (c in counters) {
                    s = s.replace("$ko$c", "$num$c")
                }
            }
            return s
        }

        fun computeTokenSimilarity(a: String, b: String): Double {
            val na = normalizeKoreanUiToken(a)
            val nb = normalizeKoreanUiToken(b)
            if (na.isEmpty() || nb.isEmpty()) return 0.0
            if (na == nb) return 1.0
            if (na.contains(nb) || nb.contains(na)) {
                val minLen = minOf(na.length, nb.length)
                return if (minLen >= 2) 0.92 else 0.70
            }
            val charsA = na.toSet()
            val charsB = nb.toSet()
            val inter = charsA.intersect(charsB).size.toDouble()
            val union = charsA.union(charsB).size.coerceAtLeast(1).toDouble()
            return inter / union
        }

        fun findBestMatchingElement(
            snapshot: AtomicSnapshot?,
            rawQuery: String
        ): com.hanson.mobileuse.accessibility.IndexedUiElement? {
            if (snapshot == null) return null
            val spatial = IntentDecomposerAndRouter.extractSpatialHint(rawQuery.lowercase())
            IntentDecomposerAndRouter.findBestScreenElement(snapshot, rawQuery, spatial)?.let { return it }

            val cleaned = IntentDecomposerAndRouter.cleanScreenTargetQuery(rawQuery)
                .ifBlank { rawQuery }
                .replace("카톡방", "")
                .replace("대화방", "")
                .replace("채팅방", "")
                .replace("단톡방", "")
                .trim()
            val qNorm = normalizeKoreanUiToken(cleaned)
            if (qNorm.length < 2) return null

            var bestEl: com.hanson.mobileuse.accessibility.IndexedUiElement? = null
            var bestScore = 0.0

            for (el in snapshot.indexedElements) {
                if (IntentDecomposerAndRouter.isHansonOwnElement(el)) continue
                val label = el.text.trim().ifBlank { el.contentDesc.trim() }
                if (label.length < 2) continue
                val lNorm = normalizeKoreanUiToken(label)
                val score = when {
                    lNorm == qNorm -> 1.0
                    lNorm.contains(qNorm) || qNorm.contains(lNorm) -> 0.94
                    else -> computeTokenSimilarity(cleaned, label)
                }
                if (score > bestScore) {
                    bestScore = score
                    bestEl = el
                }
            }
            return if (bestScore >= 0.58) bestEl else null
        }
    }

    private val notesPrefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)
    val universalNexusEngine: UniversalNexusEngine by lazy { UniversalNexusEngine(context, this) }

    @Volatile
    private var blindVaultRef: BlindCredentialVault? = null

    @Volatile
    private var watcherEngineRef: ProactiveWatcherEngine? = null

    @Volatile
    private var scratchpadRef: NotetakerScratchpad? = null

    internal fun attachSotaModules(
        blindVault: BlindCredentialVault,
        watcherEngine: ProactiveWatcherEngine,
        scratchpad: NotetakerScratchpad
    ) {
        this.blindVaultRef = blindVault
        this.watcherEngineRef = watcherEngine
        this.scratchpadRef = scratchpad
    }

    private fun resolveBlindVault(): BlindCredentialVault =
        blindVaultRef ?: HansonRuntimeHolder.getBlindVault(context).also { blindVaultRef = it }

    private fun resolveWatcherEngine(): ProactiveWatcherEngine =
        watcherEngineRef ?: HansonRuntimeHolder.getWatcherEngine(context).also { watcherEngineRef = it }

    private fun resolveScratchpad(): NotetakerScratchpad =
        scratchpadRef ?: HansonRuntimeHolder.getScratchpad(context).also { scratchpadRef = it }

    private inline fun <T> withTemporaryHudPassThrough(block: () -> T): T {
        FloatingHudService.setPassThroughTemporarily(true)
        return try {
            block()
        } finally {
            FloatingHudService.setPassThroughTemporarily(false)
        }
    }

    fun execute(
        toolName: String,
        args: JSONObject,
        latestSnapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val canonicalTool = when (toolName.trim().lowercase()) {
            "open_url" -> "open_link"
            "wait" -> "wait_for_delay"
            else -> toolName.trim()
        }
        return try {
            val preActionValue = capturePreActionValueForUndo(canonicalTool, args)
            val rawOutcome = when (canonicalTool) {
                "click" -> executeClick(args, latestSnapshot)
                "long_press" -> executeLongPress(args, latestSnapshot)
                "input_text" -> executeInputText(args, latestSnapshot)
                "swipe" -> executeSwipe(args, latestSnapshot)
                "drag_and_drop" -> executeDragAndDrop(args, latestSnapshot)
                "haptic_trajectory" -> executeHapticTrajectory(args, latestSnapshot)
                "click_sequence" -> executeClickSequence(args, latestSnapshot)
                "press_key" -> executePressKey(args)
                "manage_app" -> executeManageApp(args)
                "wait_for_delay" -> executeWait(args)
                "open_link" -> executeOpenLink(args)
                "observe_screen" -> executeObserveScreen(args, latestSnapshot)
                "list_notifications" -> executeListNotifications(args)
                "reply_notification" -> executeReplyNotification(args)
                "control_device_setting" -> executeControlDeviceSetting(args)
                "make_call" -> executeMakeCall(args)
                "send_sms" -> executeSendSms(args)
                "search_contacts" -> executeSearchContacts(args)
                "manage_calendar" -> executeManageCalendar(args)
                "set_alarm" -> executeSetAlarm(args)
                "set_timer" -> executeSetTimer(args)
                "execute_lifestyle_routine" -> executeLifestyleRoutine(args, latestSnapshot)
                "save_note" -> executeSaveNote(args)
                "read_note" -> executeReadNote(args)
                "os_cli_exec" -> executeOsCli(args)
                "reverse_engineer_app" -> executeReverseEngineerApp(args, latestSnapshot)
                "learn_app_skill" -> executeLearnAppSkill(args, latestSnapshot)
                "render_generative_ui" -> executeRenderGenerativeUi(args)
                "browser_use" -> universalNexusEngine.executeMobileBrowserUse(args)
                "cross_device_control" -> universalNexusEngine.executeCrossDeviceOrPeripheralControl(args)
                "draw_with_code" -> universalNexusEngine.executeDrawWithCode(args)
                "generate_visual_media" -> universalNexusEngine.executeGenerateVisualMedia(args)
                "workspace_list" -> executeWorkspaceList(args)
                "workspace_read" -> executeWorkspaceRead(args)
                "workspace_write" -> executeWorkspaceWrite(args)
                "workspace_edit" -> executeWorkspaceEdit(args)
                "workspace_search" -> executeWorkspaceSearch(args)
                "workspace_exec" -> executeWorkspaceExec(args)
                "vault_blind_inject" -> executeVaultBlindInject(args, latestSnapshot)
                "vault_list_handles" -> executeVaultListHandles(args)
                "watcher_manage" -> executeWatcherManage(args, latestSnapshot)
                "a2a_negotiate_schedule" -> executeA2aNegotiateSchedule(args)
                "scratchpad_note" -> executeScratchpadNote(args, latestSnapshot)
                "ask_user" -> ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "사용자에게 질문 전달: ${args.optString("question")}"
                )
                "complete_task" -> ToolExecutionOutcome(
                    succeeded = true,
                    outputText = args.optString("summary", "작업을 완료했습니다.")
                )
                else -> ToolExecutionOutcome(
                    succeeded = false,
                    outputText = "알 수 없는 도구입니다: $toolName"
                )
            }
            val vault = resolveBlindVault()
            val redactedText = vault.redactSensitiveText(rawOutcome.outputText)
            val enrichedReceipt = rawOutcome.receipt?.let { rcpt ->
                val withUndo = if (rcpt.undoToolName.isBlank()) {
                    val spec = ReversibleReceiptPlanner.planUndoForAction(canonicalTool, args, preActionValue)
                    if (spec != null) {
                        val combinedHint = when {
                            rcpt.undoHintKo.isBlank() -> spec.undoLabelKo
                            rcpt.undoHintKo.startsWith(spec.undoLabelKo) -> rcpt.undoHintKo
                            else -> "${spec.undoLabelKo} · ${rcpt.undoHintKo}"
                        }
                        rcpt.copy(
                            undoToolName = spec.undoToolName,
                            undoArgsJson = spec.undoArgsJson,
                            undoHintKo = combinedHint
                        )
                    } else {
                        rcpt
                    }
                } else {
                    rcpt
                }
                withUndo.copy(
                    summaryKo = vault.redactSensitiveText(withUndo.summaryKo),
                    detailKo = vault.redactSensitiveText(withUndo.detailKo),
                    undoHintKo = vault.redactSensitiveText(withUndo.undoHintKo)
                )
            }
            if (redactedText == rawOutcome.outputText && enrichedReceipt == rawOutcome.receipt) {
                rawOutcome
            } else {
                rawOutcome.copy(
                    outputText = redactedText,
                    receipt = enrichedReceipt
                )
            }
        } catch (e: Exception) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "도구 실행 오류 ($toolName): ${e.message}"
            )
        }
    }

    private fun capturePreActionValueForUndo(toolName: String, args: JSONObject): String? {
        return runCatching {
            when (toolName) {
                "save_note" -> {
                    val key = args.optString("key", "").trim()
                    if (key.isNotEmpty()) notesPrefs.getString(key, null) else null
                }
                "scratchpad_note" -> {
                    if (args.optString("action", "").trim().equals("put", ignoreCase = true)) {
                        val key = args.optString("key", "").trim()
                        if (key.isNotEmpty()) resolveScratchpad().get(key)?.value else null
                    } else {
                        null
                    }
                }
                "control_device_setting" -> {
                    when (args.optString("setting", "").trim().lowercase()) {
                        "brightness" -> Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS
                        ).toString()
                        "volume_media", "volume_ring", "volume_alarm" -> {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                ?: return@runCatching null
                            val stream = when (args.optString("setting", "").trim().lowercase()) {
                                "volume_ring" -> AudioManager.STREAM_RING
                                "volume_alarm" -> AudioManager.STREAM_ALARM
                                else -> AudioManager.STREAM_MUSIC
                            }
                            val maxVol = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                            val curVol = am.getStreamVolume(stream).coerceIn(0, maxVol)
                            ((curVol * 100.0) / maxVol).roundToInt().toString()
                        }
                        "ringer_mode" -> {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                                ?: return@runCatching null
                            when (am.ringerMode) {
                                AudioManager.RINGER_MODE_SILENT -> "silent"
                                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                                AudioManager.RINGER_MODE_NORMAL -> "normal"
                                else -> null
                            }
                        }
                        else -> null
                    }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun resolveTargetPixel(
        args: JSONObject,
        snapshot: AtomicSnapshot?
    ): Pair<Int, Int>? {
        val (screenW, screenH) = if (snapshot != null) {
            snapshot.screenWidth to snapshot.screenHeight
        } else {
            HierarchyDumper.getScreenDimensions(context)
        }

        // Priority 1: explicit element_index (>= 0) resolved from snapshot
        if (args.has("element_index")) {
            val idx = args.optInt("element_index", -1)
            if (idx >= 0) {
                val el = snapshot?.indexedElements?.firstOrNull { it.index == idx }
                if (el != null) {
                    return el.centerX to el.centerY
                }
            }
        }

        // Priority 2: explicit normalized coordinates (0..1000)
        if (args.has("norm_x") && args.has("norm_y")) {
            val nx = args.optInt("norm_x", -1)
            val ny = args.optInt("norm_y", -1)
            if (nx >= 0 && ny >= 0) {
                return CoordinateNormalizer.normalizedToPixel(nx, ny, screenW, screenH)
            }
        }

        // Priority 3: x / y — treat as 0..1000 normalized coordinates when both are in 0..1000,
        // and treat as raw pixel coordinates only when either exceeds 1000.
        if (args.has("x") && args.has("y")) {
            val rx = args.optInt("x", -1)
            val ry = args.optInt("y", -1)
            if (rx >= 0 && ry >= 0) {
                return if (rx <= 1000 && ry <= 1000) {
                    CoordinateNormalizer.normalizedToPixel(rx, ry, screenW, screenH)
                } else {
                    rx.coerceIn(0, (screenW - 1).coerceAtLeast(0)) to
                        ry.coerceIn(0, (screenH - 1).coerceAtLeast(0))
                }
            }
        }

        // Priority 4: fuzzy match on target_description
        val desc = args.optString("target_description", "").trim()
        if (desc.isNotEmpty()) {
            val fuzzyEl = findBestMatchingElement(snapshot, desc)
            if (fuzzyEl != null) {
                return fuzzyEl.centerX to fuzzyEl.centerY
            }
        }
        return null
    }

    private fun executeClick(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val pt = resolveTargetPixel(args, snapshot)
            ?: return ToolExecutionOutcome(false, "click 대상(element_index 또는 norm_x/norm_y)을 찾을 수 없습니다.")

        val desc = args.optString("target_description", "UI 요소")
        val times = args.optInt("times", 1).coerceIn(1, 5)
        val delayMs = args.optLong("delay_ms", 120L)

        val service = HansonAccessibilityService.instance
        if (service == null) {
            val px = pt.first
            val py = pt.second
            var allOk = true
            var lastCliOutcome = withTemporaryHudPassThrough {
                executeOsCli(JSONObject().put("command", "tap $px $py"))
            }
            if (!lastCliOutcome.succeeded) allOk = false
            for (i in 2..times) {
                try {
                    Thread.sleep(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    allOk = false
                    break
                }
                lastCliOutcome = withTemporaryHudPassThrough {
                    executeOsCli(JSONObject().put("command", "tap $px $py"))
                }
                if (!lastCliOutcome.succeeded) allOk = false
            }
            return ToolExecutionOutcome(
                succeeded = allOk,
                outputText = if (allOk) {
                    "'$desc' 위치($px, $py) ${times}회 탭 완료 [OS CLI 데몬 자동 폴백: tap $px $py]\n${lastCliOutcome.outputText}"
                } else {
                    "'$desc' 위치($px, $py) 탭 실패 [OS CLI 데몬 폴백 실패]\n${lastCliOutcome.outputText}"
                },
                receipt = if (allOk) ActionReceipt(
                    toolName = "click",
                    summaryKo = "'$desc' 화면 탭 (${times}회 · OS CLI 데몬 폴백)",
                    detailKo = "접근성 서비스 미연결로 OS CLI 커널 tap $px $py 실행",
                    undoHintKo = "뒤로가기(BACK) 키로 이전 화면으로 돌아갈 수 있습니다."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.multiTap(service, pt.first.toFloat(), pt.second.toFloat(), times, delayMs)
        }
        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "'$desc' 위치(${pt.first}, ${pt.second}) ${times}회 탭 완료" else "'$desc' 탭 실패",
            receipt = if (ok) ActionReceipt(
                toolName = "click",
                summaryKo = "'$desc' 화면 탭 (${times}회)",
                detailKo = "픽셀 좌표 (${pt.first}, ${pt.second}) 탭 실행",
                undoHintKo = "뒤로가기(BACK) 키로 이전 화면으로 돌아갈 수 있습니다."
            ) else null
        )
    }

    private fun executeLongPress(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val pt = resolveTargetPixel(args, snapshot)
            ?: return ToolExecutionOutcome(false, "long_press 대상 좌표를 확인할 수 없습니다.")

        val desc = args.optString("target_description", "UI 요소")
        val duration = args.optLong("duration_ms", 700L)

        val service = HansonAccessibilityService.instance
        if (service == null) {
            val px = pt.first
            val py = pt.second
            val dur = duration
            val cliOutcome = withTemporaryHudPassThrough {
                executeOsCli(JSONObject().put("command", "swipe $px $py $px $py $dur"))
            }
            val ok = cliOutcome.succeeded
            return ToolExecutionOutcome(
                succeeded = ok,
                outputText = if (ok) {
                    "'$desc' ($px, $py) ${dur}ms 길게 누르기 완료 [OS CLI 데몬 자동 폴백: swipe $px $py $px $py $dur]\n${cliOutcome.outputText}"
                } else {
                    "'$desc' ($px, $py) 길게 누르기 실패 [OS CLI 데몬 폴백 실패]\n${cliOutcome.outputText}"
                },
                receipt = if (ok) ActionReceipt(
                    toolName = "long_press",
                    summaryKo = "'$desc' 길게 누르기 (OS CLI 데몬 폴백)",
                    detailKo = "좌표 ($px, $py) 커널 swipe $px $py $px $py $dur 홀드 (A11y 부재 폴백)",
                    undoHintKo = "빈 영역을 탭하거나 BACK 키로 컨텍스트 메뉴를 닫으세요."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.longPress(service, pt.first.toFloat(), pt.second.toFloat(), duration)
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "'$desc' (${pt.first}, ${pt.second}) ${duration}ms 길게 누르기 완료" else "길게 누르기 실패",
            receipt = if (ok) ActionReceipt(
                toolName = "long_press",
                summaryKo = "'$desc' 길게 누르기",
                detailKo = "좌표 (${pt.first}, ${pt.second}) ${duration}ms 홀드",
                undoHintKo = "빈 영역을 탭하거나 BACK 키로 컨텍스트 메뉴를 닫으세요."
            ) else null
        )
    }

    private fun executeInputText(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val rawText = args.optString("text", "")
        val text = normalizeSinoKoreanNumberPrefix(rawText)
        val clear = args.optBoolean("clear_existing", false)
        val pressEnter = if (args.has("press_enter")) {
            args.optBoolean("press_enter", true)
        } else {
            args.optBoolean("submit_enter", true)
        }
        val pt = resolveTargetPixel(args, snapshot)

        val service = HansonAccessibilityService.instance
        if (service == null) {
            if (pt != null) {
                withTemporaryHudPassThrough {
                    executeOsCli(JSONObject().put("command", "tap ${pt.first} ${pt.second}"))
                }
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            val cliOutcome = executeOsCli(JSONObject().put("command", "text $text"))
            var ok = cliOutcome.succeeded
            if (ok && pressEnter) {
                try {
                    Thread.sleep(150L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                val enterOutcome = executeOsCli(JSONObject().put("command", "key 66"))
                ok = ok && enterOutcome.succeeded
            }
            return ToolExecutionOutcome(
                succeeded = ok,
                outputText = if (ok) {
                    "텍스트 입력 완료: \"$text\" [OS CLI 데몬 자동 폴백: text $text]\n${cliOutcome.outputText}"
                } else {
                    "텍스트 입력 실패: \"$text\" [OS CLI 데몬 폴백 실패]\n${cliOutcome.outputText}"
                },
                receipt = if (ok) ActionReceipt(
                    toolName = "input_text",
                    summaryKo = "텍스트 입력: \"${text.take(24)}\" (OS CLI 데몬 폴백)",
                    detailKo = "입력창 커널 text $text 주입 및 엔터 연동 (A11y 부재 폴백)",
                    undoHintKo = "입력창 내용을 지워 되돌릴 수 있습니다."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.inputText(
                service = service,
                text = text,
                targetX = pt?.first?.toFloat(),
                targetY = pt?.second?.toFloat(),
                clearExisting = clear
            )
        }
        if (ok && pressEnter) {
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            GestureController.performGlobalKey(service, "ENTER")
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "텍스트 입력 완료: \"$text\"" else "포커스된 입력창을 찾지 못해 텍스트 입력에 실패했습니다.",
            receipt = if (ok) ActionReceipt(
                toolName = "input_text",
                summaryKo = "텍스트 입력: \"${text.take(24)}\"",
                detailKo = "입력창에 텍스트 주입 및 클립보드 연동 수행",
                undoHintKo = "입력창 내용을 지워 되돌릴 수 있습니다."
            ) else null
        )
    }

    private fun executeSwipe(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val (screenW, screenH) = if (snapshot != null) {
            snapshot.screenWidth to snapshot.screenHeight
        } else {
            HierarchyDumper.getScreenDimensions(context)
        }

        val direction = args.optString("direction", "up")
        val duration = args.optLong("duration_ms", 350L)

        val vector = if (args.has("start_norm_x") && args.has("end_norm_x")) {
            val (sx, sy) = CoordinateNormalizer.normalizedToPixel(
                args.optInt("start_norm_x", 500),
                args.optInt("start_norm_y", 700),
                screenW,
                screenH
            )
            val (ex, ey) = CoordinateNormalizer.normalizedToPixel(
                args.optInt("end_norm_x", 500),
                args.optInt("end_norm_y", 300),
                screenW,
                screenH
            )
            CoordinateNormalizer.SwipeVector(sx, sy, ex, ey)
        } else {
            val anchorEl = if (args.has("element_index")) {
                val idx = args.optInt("element_index", -1)
                snapshot?.indexedElements?.firstOrNull { it.index == idx }
            } else null
            CoordinateNormalizer.computeDirectionalSwipe(
                direction = direction,
                screenWidth = screenW,
                screenHeight = screenH,
                anchorCenterX = anchorEl?.centerX,
                anchorCenterY = anchorEl?.centerY
            )
        }

        val service = HansonAccessibilityService.instance
        if (service == null) {
            val px1 = vector.startX
            val py1 = vector.startY
            val px2 = vector.endX
            val py2 = vector.endY
            val dur = duration
            val cliOutcome = withTemporaryHudPassThrough {
                executeOsCli(JSONObject().put("command", "swipe $px1 $py1 $px2 $py2 $dur"))
            }
            val ok = cliOutcome.succeeded
            return ToolExecutionOutcome(
                succeeded = ok,
                outputText = if (ok) {
                    "스와이프($direction) 완료: ($px1,$py1) -> ($px2,$py2) [OS CLI 데몬 자동 폴백: swipe $px1 $py1 $px2 $py2 $dur]\n${cliOutcome.outputText}"
                } else {
                    "스와이프($direction) 실패 [OS CLI 데몬 폴백 실패]\n${cliOutcome.outputText}"
                },
                receipt = if (ok) ActionReceipt(
                    toolName = "swipe",
                    summaryKo = "스와이프: $direction (OS CLI 데몬 폴백)",
                    detailKo = "($px1,$py1) -> ($px2,$py2) ${dur}ms (A11y 부재 폴백)",
                    undoHintKo = "반대 방향 스와이프로 되돌릴 수 있습니다."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.swipe(
                service = service,
                startX = vector.startX.toFloat(),
                startY = vector.startY.toFloat(),
                endX = vector.endX.toFloat(),
                endY = vector.endY.toFloat(),
                durationMs = duration
            )
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) {
                "스와이프($direction) 완료: (${vector.startX},${vector.startY}) -> (${vector.endX},${vector.endY})"
            } else {
                "스와이프 실행 실패"
            },
            receipt = if (ok) ActionReceipt(
                toolName = "swipe",
                summaryKo = "화면 스크롤 ($direction)",
                detailKo = "(${vector.startX},${vector.startY}) -> (${vector.endX},${vector.endY}) ${duration}ms",
                undoHintKo = "반대 방향 스와이프로 되돌릴 수 있습니다."
            ) else null
        )
    }

    private fun executeDragAndDrop(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val (screenW, screenH) = if (snapshot != null) {
            snapshot.screenWidth to snapshot.screenHeight
        } else {
            HierarchyDumper.getScreenDimensions(context)
        }

        val fromEl = if (args.has("from_element_index")) {
            snapshot?.indexedElements?.firstOrNull { it.index == args.optInt("from_element_index", -1) }
        } else null
        val toEl = if (args.has("to_element_index")) {
            snapshot?.indexedElements?.firstOrNull { it.index == args.optInt("to_element_index", -1) }
        } else null

        val (startX, startY) = if (fromEl != null) {
            fromEl.centerX to fromEl.centerY
        } else {
            CoordinateNormalizer.normalizedToPixel(
                args.optInt("start_norm_x", 300),
                args.optInt("start_norm_y", 600),
                screenW,
                screenH
            )
        }

        val (endX, endY) = if (toEl != null) {
            toEl.centerX to toEl.centerY
        } else {
            CoordinateNormalizer.normalizedToPixel(
                args.optInt("end_norm_x", 700),
                args.optInt("end_norm_y", 400),
                screenW,
                screenH
            )
        }

        val holdMs = args.optLong("hold_ms", 480L)
        val durationMs = args.optLong("duration_ms", 680L)
        val desc = args.optString("description", "드래그 앤 드롭")

        val service = HansonAccessibilityService.instance
        if (service == null) {
            val cliOutcome = withTemporaryHudPassThrough {
                executeOsCli(JSONObject().put("command", "swipe $startX $startY $endX $endY $durationMs"))
            }
            val ok = cliOutcome.succeeded
            return ToolExecutionOutcome(
                succeeded = ok,
                outputText = if (ok) {
                    "드래그 앤 드롭('$desc') 완료: ($startX,$startY) -> ($endX,$endY) [OS CLI 데몬 자동 폴백: swipe $startX $startY $endX $endY $durationMs]\n${cliOutcome.outputText}"
                } else {
                    "드래그 앤 드롭('$desc') 실패 [OS CLI 데몬 폴백 실패]\n${cliOutcome.outputText}"
                },
                receipt = if (ok) ActionReceipt(
                    toolName = "drag_and_drop",
                    summaryKo = "드래그 앤 드롭: $desc (OS CLI 데몬 폴백)",
                    detailKo = "($startX,$startY) -> ($endX,$endY) · 홀드 ${holdMs}ms · OS CLI 폴백",
                    undoHintKo = "반대 방향 드래그로 원복할 수 있습니다."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.dragAndDrop(
                service = service,
                startX = startX.toFloat(),
                startY = startY.toFloat(),
                endX = endX.toFloat(),
                endY = endY.toFloat(),
                holdMs = holdMs,
                dragDurationMs = durationMs
            )
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) {
                "햅틱 드래그 앤 드롭('$desc') 완료: ($startX,$startY) [홀드 ${holdMs}ms] -> ($endX,$endY) [이동 ${durationMs}ms]"
            } else {
                "햅틱 드래그 앤 드롭 실패: $desc"
            },
            receipt = if (ok) ActionReceipt(
                toolName = "drag_and_drop",
                summaryKo = "드래그 앤 드롭: $desc",
                detailKo = "($startX,$startY) -> ($endX,$endY) · 홀드 ${holdMs}ms",
                undoHintKo = "반대 방향 드래그로 원복할 수 있습니다."
            ) else null
        )
    }

    private fun executeHapticTrajectory(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("haptic_trajectory")
        val (screenW, screenH) = if (snapshot != null) {
            snapshot.screenWidth to snapshot.screenHeight
        } else {
            HierarchyDumper.getScreenDimensions(context)
        }

        val rawCsv = args.optString("waypoints_csv", "")
        val waypoints = rawCsv.split(";").mapNotNull { token ->
            val parts = token.split(",")
            if (parts.size >= 2) {
                val nx = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val ny = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                val (px, py) = CoordinateNormalizer.normalizedToPixel(nx, ny, screenW, screenH)
                px.toFloat() to py.toFloat()
            } else null
        }

        if (waypoints.size < 2) {
            return ToolExecutionOutcome(false, "haptic_trajectory에는 최소 2개 이상의 웨이포인트가 필요합니다.")
        }

        val durationMs = args.optLong("duration_ms", 650L)
        val desc = args.optString("description", "연속 햅틱 궤적")
        val ok = withTemporaryHudPassThrough {
            GestureController.executeWaypointTrajectory(service, waypoints, durationMs)
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) {
                "연속 햅틱 궤적('$desc', ${waypoints.size}개 경유점, ${durationMs}ms) 실행 완료"
            } else {
                "연속 햅틱 궤적 실행 실패"
            },
            receipt = if (ok) ActionReceipt(
                toolName = "haptic_trajectory",
                summaryKo = "햅틱 궤적: $desc (${waypoints.size}점)",
                detailKo = "경유점 ${waypoints.size}개 · ${durationMs}ms 연속 이동",
                undoHintKo = "BACK 또는 역방향 제스처로 복귀 가능합니다."
            ) else null
        )
    }

    private fun executeClickSequence(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val (screenW, screenH) = if (snapshot != null) {
            snapshot.screenWidth to snapshot.screenHeight
        } else {
            HierarchyDumper.getScreenDimensions(context)
        }

        val points = mutableListOf<Pair<Float, Float>>()
        val indicesCsv = args.optString("indices_csv", "").trim()
        if (indicesCsv.isNotEmpty() && snapshot != null) {
            for (token in indicesCsv.split(",")) {
                val idx = token.trim().toIntOrNull() ?: continue
                val el = snapshot.indexedElements.firstOrNull { it.index == idx }
                if (el != null) {
                    points.add(el.centerX.toFloat() to el.centerY.toFloat())
                }
            }
        }

        val coordsCsv = args.optString("coords_csv", "").trim()
        if (coordsCsv.isNotEmpty()) {
            for (pairStr in coordsCsv.split(";")) {
                val parts = pairStr.split(",")
                if (parts.size >= 2) {
                    val nx = parts[0].trim().toIntOrNull() ?: continue
                    val ny = parts[1].trim().toIntOrNull() ?: continue
                    val (px, py) = CoordinateNormalizer.normalizedToPixel(nx, ny, screenW, screenH)
                    points.add(px.toFloat() to py.toFloat())
                }
            }
        }

        if (points.isEmpty()) {
            return ToolExecutionOutcome(false, "click_sequence 좌표 목록이 비어 있습니다.")
        }

        val delayMs = args.optLong("delay_ms", 180L)
        val desc = args.optString("description", "${points.size}개 연속 탭")

        val service = HansonAccessibilityService.instance
        if (service == null) {
            var allOk = true
            var lastOutput = ""
            for ((idx, pt) in points.withIndex()) {
                val cliOutcome = withTemporaryHudPassThrough {
                    executeOsCli(JSONObject().put("command", "tap ${pt.first.toInt()} ${pt.second.toInt()}"))
                }
                lastOutput = cliOutcome.outputText
                if (!cliOutcome.succeeded) allOk = false
                if (idx < points.size - 1) {
                    try {
                        Thread.sleep(delayMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        allOk = false
                        break
                    }
                }
            }
            return ToolExecutionOutcome(
                succeeded = allOk,
                outputText = if (allOk) {
                    "연속 탭 버스트($desc, ${points.size}회) 완료 [OS CLI 데몬 자동 폴백]"
                } else {
                    "연속 탭 버스트($desc) 실패 [OS CLI 데몬 폴백 실패]\n$lastOutput"
                },
                receipt = if (allOk) ActionReceipt(
                    toolName = "click_sequence",
                    summaryKo = "연속 탭 버스트 (${points.size}회 · OS CLI 데몬 폴백)",
                    detailKo = "${points.size}개 지점 커널 tap 주입 (A11y 부재 폴백)",
                    undoHintKo = "이전 상태로 돌아가려면 BACK 키를 실행하세요."
                ) else null
            )
        }

        val ok = withTemporaryHudPassThrough {
            GestureController.clickSequence(service, points, delayMs)
        }

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "연속 탭 버스트($desc, ${points.size}회) 완료" else "연속 탭 일부 실패",
            receipt = if (ok) ActionReceipt(
                toolName = "click_sequence",
                summaryKo = "연속 탭 버스트 (${points.size}회): $desc",
                detailKo = "${points.size}개 지점 연속 제스처 주입 (간격 ${delayMs}ms)",
                undoHintKo = "이전 상태로 돌아가려면 BACK 키를 실행하세요."
            ) else null
        )
    }

    private fun executePressKey(args: JSONObject): ToolExecutionOutcome {
        val key = args.optString("key", "BACK").uppercase()

        val service = HansonAccessibilityService.instance
        if (service == null) {
            val keycode = when (key.removePrefix("KEYCODE_")) {
                "BACK" -> "4"
                "HOME" -> "3"
                "RECENTS", "APP_SWITCH" -> "187"
                "ENTER", "IME_ENTER", "SEARCH" -> "66"
                "VOLUME_UP" -> "24"
                "VOLUME_DOWN" -> "25"
                "POWER" -> "26"
                else -> if (key.all { it.isDigit() }) key else if (key.startsWith("KEYCODE_")) key else "KEYCODE_$key"
            }
            val cliOutcome = executeOsCli(JSONObject().put("command", "key $keycode"))
            val ok = cliOutcome.succeeded
            return ToolExecutionOutcome(
                succeeded = ok,
                outputText = if (ok) {
                    "전역 시스템 키 실행 완료: $key (키코드: $keycode) [OS CLI 데몬 자동 폴백: key $keycode]\n${cliOutcome.outputText}"
                } else {
                    "전역 시스템 키 실행 실패: $key (키코드: $keycode) [OS CLI 데몬 폴백 실패]\n${cliOutcome.outputText}"
                },
                receipt = if (ok) ActionReceipt(
                    toolName = "press_key",
                    summaryKo = "전역 키: $key (OS CLI 데몬 폴백)",
                    detailKo = "커널 input keyevent $keycode 주입 (A11y 부재 폴백)",
                    undoHintKo = "반대 키 입력 또는 화면 복귀로 되돌릴 수 있습니다."
                ) else null
            )
        }

        val ok = GestureController.performGlobalKey(service, key)
        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "전역 시스템 키 실행 완료: $key" else "전역 시스템 키 실행 실패: $key",
            receipt = if (ok) ActionReceipt(
                toolName = "press_key",
                summaryKo = "시스템 키: $key",
                detailKo = "Accessibility 전역 키($key) 실행",
                undoHintKo = "이전 화면 또는 앱 전환으로 돌아갈 수 있습니다."
            ) else null
        )
    }

    private fun executeManageApp(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "launch").lowercase()
        val query = args.optString("app_name", "").trim()
        val pm = context.packageManager

        // Only run inventory/game scan for list/search actions; NEVER hijack action == "launch"
        // even if the target app's name contains "game" / "게임" (e.g. "Play Games", "게임런처").
        val isListOrSearchAction = action == "list_games" ||
            action == "search" ||
            action == "list" ||
            action == "list_all"

        if (isListOrSearchAction) {
            val wantsGameScan = action == "list_games" ||
                query.contains("게임") ||
                query.lowercase().contains("game")
            val report = scanInstalledGamesAndApps(
                query = if (wantsGameScan || action == "list_all" || query == "전체" || query == "모두") "" else query,
                prioritizeGames = wantsGameScan
            )
            return ToolExecutionOutcome(true, report)
        }

        // Launch app (normalize spaces so "배달의 민족" and "배달의민족" both match)
        val normQuery = query.lowercase().replace(" ", "")
        val directPkg = KNOWN_APP_ALIASES[query.lowercase()]
            ?: KNOWN_APP_ALIASES[normQuery]
            ?: IntentDecomposerAndRouter.resolveTargetAppFromPrompt(query)?.second
            ?: query
        val directIntent = pm.getLaunchIntentForPackage(directPkg)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "앱 실행 완료: $query ($directPkg)",
                receipt = ActionReceipt(
                    toolName = "manage_app",
                    summaryKo = "'$query' 앱 실행",
                    detailKo = "패키지: $directPkg",
                    undoHintKo = "홈(HOME) 또는 최근 앱(RECENTS)으로 전환할 수 있습니다."
                )
            )
        }

        val matches = searchInstalledApps(pm, query)
        val best = matches.firstOrNull()
            ?: return ToolExecutionOutcome(false, "'$query' 앱이 기기에 설치되어 있지 않습니다.")

        val launchIntent = pm.getLaunchIntentForPackage(best.second)
            ?: return ToolExecutionOutcome(false, "'${best.first}' (${best.second}) 실행 인텐트를 가져올 수 없습니다.")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "앱 실행 완료: ${best.first} (${best.second})",
            receipt = ActionReceipt(
                toolName = "manage_app",
                summaryKo = "'${best.first}' 앱 실행",
                detailKo = "패키지: ${best.second}",
                undoHintKo = "홈(HOME) 키로 복귀할 수 있습니다."
            )
        )
    }

    /**
     * Scans the device's PackageManager in parallel-friendly read mode to identify
     * all installed Games (`CATEGORY_GAME` / `FLAG_IS_GAME` / game engines & publishers)
     * as well as user-installed applications.
     */
    fun scanInstalledGamesAndApps(query: String = "", prioritizeGames: Boolean = false): String {
        val pm = context.packageManager
        val q = query.lowercase().trim()
        val apps = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            try {
                pm.getInstalledApplications(0)
            } catch (_: Exception) {
                emptyList()
            }
        }

        val gameKeywords = listOf(
            "game", "gaming", "playgames", "gamelauncher", "gamehome", "unity", "unreal",
            "supercell", "nexon", "netmarble", "krafton", "ncsoft", "kakaogames", "com2us",
            "devsisters", "hoyoverse", "mihoyo", "riotgames", "roblox", "brawl", "genshin",
            "pubg", "minecraft", "chess", "puzzle", "arcade", "casino", "poker", "sudoku",
            "solitaire", "emulator", "steam", "xbox", "playstation", "nintendo", "nikke",
            "maple", "fifa", "fconline", "kart", "cookie", "stardew", "terraria", "pokemon"
        )

        val games = mutableListOf<Pair<String, String>>()
        val userApps = mutableListOf<Pair<String, String>>()

        for (appInfo in apps) {
            val pkg = appInfo.packageName ?: continue
            if (pkg == context.packageName) continue
            val isLaunchable = pm.getLaunchIntentForPackage(pkg) != null
            if (!isLaunchable) continue

            val label = try {
                pm.getApplicationLabel(appInfo).toString().trim()
            } catch (_: Exception) {
                pkg
            }
            val lowerPkg = pkg.lowercase()
            val lowerLabel = label.lowercase()

            if (q.isNotEmpty() && !lowerLabel.contains(q) && !lowerPkg.contains(q)) {
                continue
            }

            val isCategoryGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                false
            }
            @Suppress("DEPRECATION")
            val isFlagGame = (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val matchesGameKeyword = gameKeywords.any { kw -> lowerPkg.contains(kw) || lowerLabel.contains(kw) }
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

            if (isCategoryGame || isFlagGame || matchesGameKeyword) {
                games.add(label to pkg)
            } else if (!isSystemApp || q.isNotEmpty()) {
                userApps.add(label to pkg)
            }
        }

        val sortedGames = games.distinctBy { it.second }.sortedBy { it.first }
        val sortedUserApps = userApps.distinctBy { it.second }.sortedBy { it.first }

        return if (prioritizeGames) {
            if (sortedGames.isNotEmpty()) {
                val gameNames = sortedGames.joinToString(", ") { "${it.first}(${it.second.substringAfterLast('.')})" }
                val extraUserApps = sortedUserApps.take(10).joinToString(", ") { it.first }
                "기기 전체 패키지 스캔 결과, 게임/게임 관련 앱 ${sortedGames.size}개가 확인되었습니다: $gameNames. " +
                    if (extraUserApps.isNotEmpty()) "(설치된 주요 사용자 앱 총 ${sortedUserApps.size}개: $extraUserApps 등)" else ""
            } else {
                val topUserApps = sortedUserApps.take(15).joinToString(", ") { it.first }
                "기기에 설치된 전체 앱(${apps.size}개 패키지, 실행 가능한 사용자 앱 ${sortedUserApps.size}개)을 모두 스캔한 결과, 현재 설치된 모바일 게임(CATEGORY_GAME)은 없습니다. 설치되어 있는 주요 앱은 [$topUserApps] 입니다."
            }
        } else {
            val combined = (sortedGames + sortedUserApps).distinctBy { it.second }
            if (combined.isEmpty()) {
                "'$query'와 일치하는 설치된 앱이 없습니다."
            } else {
                val preview = combined.take(18).joinToString(", ") { "${it.first}(${it.second})" }
                "설치된 앱 확인 결과 (총 ${combined.size}개): $preview"
            }
        }
    }

    private fun searchInstalledApps(pm: PackageManager, query: String): List<Pair<String, String>> {
        val q = query.lowercase().trim()
        val qNoSpace = q.replace(" ", "")
        val apps = try {
            pm.getInstalledApplications(0)
        } catch (_: Exception) {
            emptyList()
        }
        return apps.mapNotNull { appInfo ->
            val pkg = appInfo.packageName ?: return@mapNotNull null
            if (pm.getLaunchIntentForPackage(pkg) == null) return@mapNotNull null
            val label = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg
            }
            val labelLower = label.lowercase()
            val labelNoSpace = labelLower.replace(" ", "")
            if (q.isEmpty() || labelLower.contains(q) || labelNoSpace.contains(qNoSpace) || qNoSpace.contains(labelNoSpace) || pkg.lowercase().contains(qNoSpace)) {
                label to pkg
            } else {
                null
            }
        }.sortedBy { it.first }
    }

    private fun executeWait(args: JSONObject): ToolExecutionOutcome {
        val rawMs = when {
            args.has("time_in_ms") -> args.optLong("time_in_ms", 1500L)
            args.has("duration_ms") -> args.optLong("duration_ms", 1500L)
            args.has("ms") -> args.optLong("ms", 1500L)
            args.has("seconds") -> (args.optDouble("seconds", 1.5) * 1000.0).roundToInt().toLong()
            else -> 1500L
        }
        val ms = rawMs.coerceIn(200L, 10000L)
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ToolExecutionOutcome(true, "${ms}ms 대기 완료")
    }

    private fun executeObserveScreen(
        args: JSONObject,
        latestSnapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val includeScreenshot = args.optBoolean("include_screenshot", false)
        val maxElements = args.optInt("max_elements", 40).coerceIn(5, 120)
        val service = HansonAccessibilityService.instance
        val snap = service?.captureSnapshotNow(includeScreenshot = includeScreenshot)
            ?: latestSnapshot
            ?: return a11yNotConnectedOutcome("observe_screen")

        val compact = ContextorCompactor.buildCompactOutline(
            snapshot = snap,
            vault = resolveBlindVault(),
            maxElements = maxElements
        )
        val header = buildString {
            append("화면 관측 완료 (${snap.screenWidth}x${snap.screenHeight}, 패키지=${snap.activePackage.ifBlank { "unknown" }}")
            if (snap.activeWindowTitle.isNotBlank()) append(", 창='${snap.activeWindowTitle}'")
            if (snap.isSecureWindow) append(", FLAG_SECURE")
            append(", 요소 ${snap.indexedElements.size}개)")
        }
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "$header\n$compact"
        )
    }

    private fun executeOpenLink(args: JSONObject): ToolExecutionOutcome {
        val rawUrl = args.optString("url", "").trim()
        if (rawUrl.isEmpty()) return ToolExecutionOutcome(false, "URL이 비어 있습니다.")
        val normalizedUrl = if (!rawUrl.contains("://")) "https://$rawUrl" else rawUrl
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "링크/딥링크 열기 완료: $normalizedUrl",
            receipt = ActionReceipt(
                toolName = "open_link",
                summaryKo = "링크 열기: ${normalizedUrl.take(36)}",
                detailKo = normalizedUrl,
                undoHintKo = "BACK 키로 이전 앱으로 돌아갈 수 있습니다."
            )
        )
    }

    private fun executeListNotifications(args: JSONObject): ToolExecutionOutcome {
        val onlyReplyable = args.optBoolean("only_replyable", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val items = RemoteInputSessionCache.listRecent(limit = limit, onlyReplyable = onlyReplyable)
        if (items.isEmpty()) {
            return ToolExecutionOutcome(true, "최근 수신된 알림 세션이 없습니다.")
        }
        val formatted = items.joinToString("\n") { n ->
            val replyBadge = if (n.canQuickReply) "[즉시답장가능]" else "[읽기전용]"
            "- id=${n.sessionId} $replyBadge [${n.appLabel}] 발신자='${n.sender}' 방='${n.roomOrTitle}' 내용=\"${n.messageText}\""
        }
        return ToolExecutionOutcome(true, "수신된 알림 목록 (${items.size}건):\n$formatted")
    }

    private fun executeReplyNotification(args: JSONObject): ToolExecutionOutcome {
        val target = args.optString("target", "")
        val message = args.optString("message", "").trim()
        if (message.isEmpty()) {
            return ToolExecutionOutcome(false, "답장할 메시지 내용(message)이 비어 있습니다.")
        }
        val (ok, msg) = RemoteInputSessionCache.sendQuickReply(context, target, message)
        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = msg,
            receipt = if (ok) ActionReceipt(
                toolName = "reply_notification",
                summaryKo = "화면 꺼짐 즉시 답장 전송",
                detailKo = msg,
                undoHintKo = "메신저 앱을 열어 전송된 메시지를 확인할 수 있습니다."
            ) else null
        )
    }

    private fun executeControlDeviceSetting(args: JSONObject): ToolExecutionOutcome {
        val setting = args.optString("setting", "").trim().lowercase()
        val value = args.optString("value", "").trim()

        return when (setting) {
            "brightness" -> {
                if (!Settings.System.canWrite(context)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return ToolExecutionOutcome(false, "WRITE_SETTINGS 권한이 필요합니다. 권한 설정 화면을 열었습니다.")
                }
                val level = value.toIntOrNull()?.coerceIn(0, 255) ?: 150
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "화면 밝기를 $level (0..255)로 변경했습니다.",
                    receipt = ActionReceipt(
                        toolName = "control_device_setting",
                        summaryKo = "시스템 화면 밝기 변경 ($level/255)",
                        detailKo = "Settings.System.SCREEN_BRIGHTNESS = $level",
                        undoHintKo = "밝기를 이전 값으로 다시 설정할 수 있습니다."
                    )
                )
            }
            "volume_media", "volume_ring", "volume_alarm" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return ToolExecutionOutcome(false, "AudioManager를 사용할 수 없습니다.")
                val stream = when (setting) {
                    "volume_ring" -> AudioManager.STREAM_RING
                    "volume_alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val maxVol = am.getStreamMaxVolume(stream).coerceAtLeast(1)
                val pct = value.toIntOrNull()?.coerceIn(0, 100) ?: 50
                val targetVol = ((pct / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
                am.setStreamVolume(stream, targetVol, AudioManager.FLAG_SHOW_UI)
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "$setting 볼륨을 ${pct}% ($targetVol/$maxVol)로 설정했습니다.",
                    receipt = ActionReceipt(
                        toolName = "control_device_setting",
                        summaryKo = "오디오 볼륨 변경 ($setting: ${pct}%)",
                        detailKo = "Stream Volume = $targetVol / $maxVol",
                        undoHintKo = "볼륨 버튼으로 즉시 조절할 수 있습니다."
                    )
                )
            }
            "dnd" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return ToolExecutionOutcome(false, "NotificationManager를 사용할 수 없습니다.")
                if (!nm.isNotificationPolicyAccessGranted) {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    return ToolExecutionOutcome(false, "방해금지 모드 권한이 필요합니다. 설정 화면을 열었습니다.")
                }
                val enable = value.lowercase() in listOf("on", "true", "1", "켜기")
                nm.setInterruptionFilter(
                    if (enable) NotificationManager.INTERRUPTION_FILTER_NONE
                    else NotificationManager.INTERRUPTION_FILTER_ALL
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "방해금지 모드(DND)를 ${if (enable) "활성화" else "해제"}했습니다.",
                    receipt = ActionReceipt(
                        toolName = "control_device_setting",
                        summaryKo = "방해금지 모드 ${if (enable) "ON" else "OFF"}",
                        detailKo = "NotificationManager.setInterruptionFilter",
                        undoHintKo = "방해금지 모드를 다시 토글할 수 있습니다."
                    )
                )
            }
            "clipboard" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("hanson_agent", value))
                ToolExecutionOutcome(true, "클립보드에 텍스트를 복사했습니다: \"${value.take(40)}\"")
            }
            "wifi_panel", "wifi" -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolExecutionOutcome(true, "Wi-Fi 설정 패널을 열었습니다.")
            }
            "bluetooth_panel", "bluetooth" -> {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ToolExecutionOutcome(true, "블루투스 설정 화면을 열었습니다.")
            }
            "flashlight", "torch" -> {
                val enable = value.lowercase() !in listOf("off", "false", "0", "끄기", "꺼")
                val camManager = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
                val toggledOk = runCatching {
                    val camId = camManager?.cameraIdList?.firstOrNull()
                    if (camId != null) {
                        camManager.setTorchMode(camId, enable)
                        true
                    } else false
                }.getOrDefault(false)
                val statusKo = if (enable) "켰어요" else "껐어요"
                ToolExecutionOutcome(
                    succeeded = toggledOk,
                    outputText = if (toggledOk) "손전등(플래시)을 바로 $statusKo." else "손전등 제어에 실패했습니다.",
                    receipt = ActionReceipt(
                        toolName = "control_device_setting",
                        summaryKo = "손전등 ${if (enable) "ON" else "OFF"}",
                        detailKo = "CameraManager.setTorchMode($enable)",
                        undoHintKo = "다시 손전등 토글 명령으로 끄거나 켤 수 있습니다."
                    )
                )
            }
            "ringer_mode" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    ?: return ToolExecutionOutcome(false, "AudioManager를 사용할 수 없습니다.")
                val vLower = value.lowercase()
                val (targetMode, modeLabel) = when {
                    vLower.contains("silent") || vLower.contains("무음") -> AudioManager.RINGER_MODE_SILENT to "무음 모드"
                    vLower.contains("vibrate") || vLower.contains("진동") -> AudioManager.RINGER_MODE_VIBRATE to "진동 모드"
                    else -> AudioManager.RINGER_MODE_NORMAL to "벨소리(일반) 모드"
                }
                val ok = runCatching {
                    am.ringerMode = targetMode
                    true
                }.getOrDefault(false)
                ToolExecutionOutcome(
                    succeeded = ok,
                    outputText = if (ok) "사운드 상태를 ${modeLabel}로 변경했어요." else "$modeLabel 변경을 위해 방해금지 권한이 필요할 수 있습니다.",
                    receipt = ActionReceipt(
                        toolName = "control_device_setting",
                        summaryKo = "벨소리 모드 변경 ($modeLabel)",
                        detailKo = "AudioManager.ringerMode = $targetMode",
                        undoHintKo = "볼륨 버튼 또는 벨소리 모드 명령으로 복원할 수 있습니다."
                    )
                )
            }
            else -> ToolExecutionOutcome(false, "지원하지 않는 설정 항목입니다: $setting")
        }
    }

    private fun executeMakeCall(args: JSONObject): ToolExecutionOutcome {
        val recipient = args.optString("recipient", "").trim()
        val direct = args.optBoolean("direct_call", true)
        val number = resolvePhoneNumber(recipient) ?: recipient
        if (number.isBlank()) {
            return ToolExecutionOutcome(false, "전화번호나 연락처를 찾을 수 없습니다: $recipient")
        }

        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val action = if (direct && hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        val modeLabel = if (action == Intent.ACTION_CALL) "즉시 전화 발신" else "전화 다이얼 화면 열기"
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "$modeLabel 완료: $recipient ($number)",
            receipt = ActionReceipt(
                toolName = "make_call",
                summaryKo = "$modeLabel: $recipient",
                detailKo = "전화번호: $number",
                undoHintKo = "통화 종료 버튼으로 즉시 끊을 수 있습니다."
            )
        )
    }

    private fun executeSendSms(args: JSONObject): ToolExecutionOutcome {
        val recipient = args.optString("recipient", "").trim()
        val message = args.optString("message", "").trim()
        val number = resolvePhoneNumber(recipient) ?: recipient
        if (number.isBlank() || message.isBlank()) {
            return ToolExecutionOutcome(false, "수신자 번호와 메시지 내용을 모두 지정해야 합니다.")
        }

        val hasSmsPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSmsPerm) {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "SMS 즉시 발송 완료: $recipient ($number) -> \"$message\"",
                receipt = ActionReceipt(
                    toolName = "send_sms",
                    summaryKo = "SMS 전송: $recipient ($number)",
                    detailKo = "내용: $message",
                    undoHintKo = "메시지 앱에서 발송 내역을 확인할 수 있습니다."
                )
            )
        } else {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "SEND_SMS 권한이 없어 메시지 작성 화면을 열었습니다: $recipient ($number)"
            )
        }
    }

    private fun executeSearchContacts(args: JSONObject): ToolExecutionOutcome {
        val query = args.optString("query", "").trim()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionOutcome(false, "READ_CONTACTS 권한이 허용되지 않았습니다.")
        }
        val results = queryContacts(query, limit = 15)
        if (results.isEmpty()) {
            return ToolExecutionOutcome(true, "'$query' 검색 결과 일치하는 연락처가 없습니다.")
        }
        val formatted = results.joinToString("\n") { "- ${it.first}: ${it.second}" }
        return ToolExecutionOutcome(true, "연락처 검색 결과 (${results.size}건):\n$formatted")
    }

    private fun executeManageCalendar(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "list").trim().lowercase()
        val title = args.optString("title", "새 일정")
        val offsetMin = args.optLong("offset_minutes_from_now", 60L)
        val durationMin = args.optLong("duration_minutes", 60L).coerceAtLeast(1L)
        val desc = args.optString("description", "Hanson Mobile-Use 에이전트가 생성한 일정")
        val location = args.optString("location", "").trim()
        val reminderMinutes = args.optInt("reminder_minutes", -1)

        val explicitStartMs = if (args.has("start_time_ms") && !args.isNull("start_time_ms")) {
            args.optLong("start_time_ms", -1L).takeIf { it > 0L }
        } else {
            null
        }
        val startMs = explicitStartMs ?: (System.currentTimeMillis() + offsetMin * 60_000L)
        val explicitEndMs = if (args.has("end_time_ms") && !args.isNull("end_time_ms")) {
            args.optLong("end_time_ms", -1L).takeIf { it > startMs }
        } else {
            null
        }
        val endMs = explicitEndMs ?: (startMs + durationMin * 60_000L)
        val resolvedDurationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1L)

        if (action == "check_conflicts") {
            val hasOverride = ProactiveContextApiConnector.calendarEventsProviderOverride != null
            if (!hasOverride &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED
            ) {
                return ToolExecutionOutcome(false, "READ_CALENDAR 권한이 필요합니다.")
            }
            val conflicts = ProactiveContextApiConnector.findCalendarConflicts(context, startMs, endMs)
            if (conflicts.isEmpty()) {
                return ToolExecutionOutcome(true, "해당 시간대에 겹치는 일정이 없습니다.")
            }
            val fmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
            val lines = conflicts.joinToString("\n") { ev ->
                val s = fmt.format(java.util.Date(ev.startTimeMs))
                val e = fmt.format(java.util.Date(ev.endTimeMs))
                val locSuffix = if (ev.location.isNotBlank()) " @ ${ev.location}" else ""
                "- [$s~$e] ${ev.title}$locSuffix"
            }
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "겹치는 일정 ${conflicts.size}건:\n$lines"
            )
        }

        if (action == "add" || action == "create") {
            val hasWriteCal = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

            if (hasWriteCal) {
                val calId = findPrimaryCalendarId()
                if (calId != null) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, startMs)
                        put(CalendarContract.Events.DTEND, endMs)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DESCRIPTION, desc)
                        if (location.isNotBlank()) {
                            put(CalendarContract.Events.EVENT_LOCATION, location)
                        }
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    }
                    val insertedUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    var reminderAttached = false
                    if (reminderMinutes > 0) {
                        val eventId = insertedUri?.lastPathSegment?.toLongOrNull()
                        if (eventId != null) {
                            reminderAttached = runCatching {
                                val remValues = ContentValues().apply {
                                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                                    put(CalendarContract.Reminders.MINUTES, reminderMinutes)
                                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                                }
                                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, remValues) != null
                            }.getOrDefault(false)
                        }
                    }
                    val timingSummary = if (explicitStartMs != null) {
                        val dtFmt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
                            .format(java.util.Date(startMs))
                        "$dtFmt 시작, ${resolvedDurationMin}분간"
                    } else {
                        "${offsetMin}분 후 시작, ${resolvedDurationMin}분간"
                    }
                    val locationPart = if (location.isNotBlank()) ", 장소: $location" else ""
                    val reminderPart = if (reminderAttached) ", ${reminderMinutes}분 전 알림 설정" else ""
                    return ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "캘린더 일정 등록 완료: '$title' ($timingSummary$locationPart$reminderPart)",
                        receipt = ActionReceipt(
                            toolName = "manage_calendar",
                            summaryKo = "캘린더 일정 추가: '$title'",
                            detailKo = buildString {
                                append("$desc (${resolvedDurationMin}분)")
                                if (location.isNotBlank()) append(" · $location")
                                if (reminderAttached) append(" · ${reminderMinutes}분 전 알림")
                            },
                            undoHintKo = "캘린더 앱에서 일정을 삭제할 수 있습니다."
                        )
                    )
                }
            }
            val insertIntent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
                if (location.isNotBlank()) {
                    putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                }
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(insertIntent)
            return ToolExecutionOutcome(true, "캘린더 일정 추가 화면을 열었습니다: '$title'")
        }

        // List upcoming events
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return ToolExecutionOutcome(false, "READ_CALENDAR 권한이 필요합니다.")
        }
        val upcoming = mutableListOf<String>()
        val projection = arrayOf(
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART
        )
        val now = System.currentTimeMillis()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            projection,
            "${CalendarContract.Events.DTSTART} >= ?",
            arrayOf(now.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && upcoming.size < 10) {
                val evTitle = cursor.getString(0) ?: "(제목 없음)"
                val dt = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.KOREA)
                    .format(java.util.Date(cursor.getLong(1)))
                upcoming.add("- [$dt] $evTitle")
            }
        }
        return ToolExecutionOutcome(
            true,
            if (upcoming.isEmpty()) "예정된 캘린더 일정이 없습니다." else "다가오는 일정:\n${upcoming.joinToString("\n")}"
        )
    }

    private fun executeSetAlarm(args: JSONObject): ToolExecutionOutcome {
        val hour = args.optInt("hour", 7).coerceIn(0, 23)
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val label = args.optString("label", "Hanson AI 알람")

        val launched = runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val timeFormatted = "%02d:%02d".format(hour, minute)
        val amPmKo = if (hour < 12) "오전" else "오후"
        val dispHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val friendlyTime = "$amPmKo ${dispHour}시${if (minute > 0) " ${minute}분" else ""}"
        return ToolExecutionOutcome(
            succeeded = launched,
            outputText = "$friendlyTime($timeFormatted) 알람을 바로 설정했어요.",
            receipt = ActionReceipt(
                toolName = "set_alarm",
                summaryKo = "알람 설정: $friendlyTime ($label)",
                detailKo = "AlarmClock.ACTION_SET_ALARM ($timeFormatted, SKIP_UI=true)",
                undoHintKo = "시계 앱에서 해당 알람을 해제할 수 있습니다."
            ),
            structuredCard = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏰ $friendlyTime 알람 설정 완료",
                headerSubtitle = "$timeFormatted · $label",
                items = listOf(
                    StructuredUiItem(
                        badge = "ON",
                        title = "$friendlyTime ($timeFormatted)",
                        subtitle = "레이블: $label · 백그라운드 즉시 등록",
                        meta = "시계 앱 열기",
                        actionUrlOrCmd = "시계 앱 열어줘"
                    )
                )
            )
        )
    }

    private fun executeSetTimer(args: JSONObject): ToolExecutionOutcome {
        val seconds = args.optInt("seconds", 180).coerceIn(5, 86400)
        val label = args.optString("label", "Hanson 타이머")

        val launched = runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        val mins = seconds / 60
        val secs = seconds % 60
        val durationKo = buildList {
            if (mins > 0) add("${mins}분")
            if (secs > 0) add("${secs}초")
        }.joinToString(" ").ifBlank { "${seconds}초" }

        return ToolExecutionOutcome(
            succeeded = launched,
            outputText = "$durationKo 타이머($label)를 바로 시작했어요.",
            receipt = ActionReceipt(
                toolName = "set_timer",
                summaryKo = "타이머 시작: $durationKo ($label)",
                detailKo = "AlarmClock.ACTION_SET_TIMER (${seconds}s, SKIP_UI=true)",
                undoHintKo = "시계 앱에서 타이머를 일시정지하거나 취소할 수 있습니다."
            ),
            structuredCard = StructuredUiCard(
                templateType = StructuredUiTemplateType.CONTROL_RECEIPT,
                headerTitle = "⏱️ $durationKo 타이머 진행 중",
                headerSubtitle = "$label · 총 ${seconds}초 카운트다운",
                items = listOf(
                    StructuredUiItem(
                        badge = "RUNNING",
                        title = "$durationKo 카운트다운",
                        subtitle = "레이블: $label",
                        meta = "시계 앱 열기",
                        actionUrlOrCmd = "시계 앱 열어줘"
                    )
                )
            )
        )
    }

    private fun executeLifestyleRoutine(args: JSONObject, latestSnapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val routine = args.optString("routine", "sleep").lowercase()
        return when (routine) {
            "sleep" -> {
                executeControlDeviceSetting(JSONObject().put("setting", "volume_media").put("value", "18"))
                executeSetAlarm(JSONObject().put("hour", 7).put("minute", 30).put("label", "상쾌한 아침 기상"))
                val ytOutcome = universalNexusEngine.executeGenerateVisualMedia(
                    JSONObject().put("prompt", "편안한 수면 빗소리 로파이 음악 틀어줘").put("format", "youtube")
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "편안한 밤 되세요! 미디어 볼륨을 18%로 낮추고, 내일 오전 7시 30분 기상 알람을 등록한 뒤 수면 사운드를 재생했어요.",
                    receipt = ActionReceipt(
                        toolName = "execute_lifestyle_routine",
                        summaryKo = "수면/취침 복합 루틴 3단계 실행 완료",
                        detailKo = "볼륨 18% + 오전 07:30 기상 알람 + 수면 로파이 재생",
                        undoHintKo = "개별 설정은 언제든 음성이나 탭으로 되돌릴 수 있습니다."
                    ),
                    mediaBlock = ytOutcome.mediaBlock,
                    structuredCard = StructuredUiCard(
                        templateType = StructuredUiTemplateType.STEP_TIMELINE,
                        headerTitle = "🌙 굿나잇 수면 루틴 완료",
                        headerSubtitle = "3단계 기기 제어 및 사운드 큐레이션",
                        items = listOf(
                            StructuredUiItem("STEP 1", "미디어 볼륨 18% 하향", "밤 시간대 편안한 음량으로 조절 완료", "완료"),
                            StructuredUiItem("STEP 2", "오전 07:30 기상 알람 세팅", "시계 앱 백그라운드 무클릭 등록 완료", "시계 열기", "시계 앱 열어줘"),
                            StructuredUiItem("STEP 3", "수면 로파이 사운드스케이프", "인앱 백그라운드 스트림 재생 시작", "재생 중")
                        )
                    )
                )
            }
            "focus" -> {
                executeControlDeviceSetting(JSONObject().put("setting", "volume_media").put("value", "25"))
                executeSetTimer(JSONObject().put("seconds", 1500).put("label", "25분 집중 뽀모도로"))
                val ytOutcome = universalNexusEngine.executeGenerateVisualMedia(
                    JSONObject().put("prompt", "로파이 코딩 집중 음악 틀어줘").put("format", "youtube")
                )
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "집중 코딩 모드를 시작했어요! 볼륨을 25%로 맞추고, 25분 뽀모도로 타이머와 로파이 코딩 음악을 함께 켰습니다.",
                    receipt = ActionReceipt(
                        toolName = "execute_lifestyle_routine",
                        summaryKo = "집중/코딩 복합 루틴 3단계 실행 완료",
                        detailKo = "볼륨 25% + 25분 뽀모도로 타이머 + 코딩 로파이 재생",
                        undoHintKo = "타이머는 시계 앱에서 언제든 중지할 수 있습니다."
                    ),
                    mediaBlock = ytOutcome.mediaBlock,
                    structuredCard = StructuredUiCard(
                        templateType = StructuredUiTemplateType.STEP_TIMELINE,
                        headerTitle = "🎧 집중 코딩 & 뽀모도로 루틴",
                        headerSubtitle = "25분 딥워크 세션 활성화",
                        items = listOf(
                            StructuredUiItem("STEP 1", "미디어 볼륨 25% 최적화", "집중에 방해되지 않는 배경 음량 설정", "완료"),
                            StructuredUiItem("STEP 2", "25분 뽀모도로 타이머 시작", "1,500초 백그라운드 카운트다운 작동", "진행 중"),
                            StructuredUiItem("STEP 3", "로파이 코딩 음악 스트리밍", "인앱 플레이어 자동 재생", "재생 중")
                        )
                    )
                )
            }
            else -> {
                val calOutcome = executeManageCalendar(JSONObject().put("action", "list"))
                val notifOutcome = executeListNotifications(JSONObject().put("limit", 5))
                val noteOutcome = executeReadNote(JSONObject().put("key", ""))
                val calPreview = calOutcome.outputText.lines().drop(1).firstOrNull() ?: "오늘 등록된 일정이 없습니다."
                val notifCount = RemoteInputSessionCache.listRecent(limit = 10, onlyReplyable = false).size
                val notePreview = noteOutcome.outputText.lines().drop(1).firstOrNull() ?: "저장된 메모가 없습니다."
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "오늘의 일정, 미확인 알림(${notifCount}건), 저장된 메모를 종합 브리핑 카드로 정리했어요.",
                    receipt = ActionReceipt(
                        toolName = "execute_lifestyle_routine",
                        summaryKo = "일상 종합 브리핑 스캔 완료",
                        detailKo = "캘린더 + 알림(${notifCount}건) + 메모 통합",
                        undoHintKo = "각 항목을 탭하여 상세 내용을 확인할 수 있습니다."
                    ),
                    structuredCard = StructuredUiCard(
                        templateType = StructuredUiTemplateType.CURATED_LIST,
                        headerTitle = "☀️ 오늘의 라이프스타일 브리핑",
                        headerSubtitle = "일정 · 알림 · 메모 실시간 요약",
                        items = listOf(
                            StructuredUiItem("일정", "다가오는 스케줄", calPreview, "일정 추가", "내일 오후 3시 일정 추가해줘"),
                            StructuredUiItem("알림", "최근 수신 알림 ${notifCount}건", notifOutcome.outputText.lines().drop(1).firstOrNull() ?: "새로 쌓인 알림이 없습니다.", "확인"),
                            StructuredUiItem("메모", "최근 저장 노트", notePreview, "메모 보기", "저장된 메모 보여줘")
                        )
                    )
                )
            }
        }
    }

    private fun executeSaveNote(args: JSONObject): ToolExecutionOutcome {
        val key = args.optString("key", "memo_${System.currentTimeMillis()}").trim()
        val content = args.optString("content", "").trim()
        notesPrefs.edit().putString(key, content).apply()
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "에이전트 메모리에 저장 완료 [$key]: $content",
            receipt = ActionReceipt(
                toolName = "save_note",
                summaryKo = "메모 저장: $key",
                detailKo = content.take(80),
                undoHintKo = "메모 목록에서 언제든 삭제할 수 있습니다."
            )
        )
    }

    private fun executeReadNote(args: JSONObject): ToolExecutionOutcome {
        val key = args.optString("key", "").trim()
        if (key.isNotEmpty()) {
            val valStr = notesPrefs.getString(key, null)
                ?: return ToolExecutionOutcome(true, "'$key' 키로 저장된 메모가 없습니다.")
            return ToolExecutionOutcome(true, "[$key]: $valStr")
        }
        val all = notesPrefs.all
        if (all.isEmpty()) return ToolExecutionOutcome(true, "저장된 에이전트 메모가 없습니다.")
        val listStr = all.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
        return ToolExecutionOutcome(true, "저장된 에이전트 메모 (${all.size}건):\n$listStr")
    }

    private fun resolvePhoneNumber(recipient: String): String? {
        val cleaned = recipient.filter { it.isDigit() || it == '+' }
        if (cleaned.length >= 8) return recipient
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return queryContacts(recipient, limit = 1).firstOrNull()?.second
    }

    private fun queryContacts(query: String, limit: Int): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val q = query.lowercase()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && list.size < limit) {
                val name = cursor.getString(0).orEmpty()
                val number = cursor.getString(1).orEmpty()
                if (q.isEmpty() || name.lowercase().contains(q) || number.contains(q)) {
                    list.add(name to number)
                }
            }
        }
        return list
    }

    private fun findPrimaryCalendarId(): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun executeOsCli(args: JSONObject): ToolExecutionOutcome {
        val rawCmd = args.optString("command", "status").trim()
            .removePrefix("hanson ")
            .removePrefix("/cli ")
            .removePrefix("/sh ")
            .trim()
        if (com.hanson.mobileuse.oscli.HansonOsCliMain.containsShellMetacharacters(rawCmd)) {
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "[보안 차단] OS CLI 명령에 허용되지 않는 셸 메타문자(; & | $ ` > < 등)가 포함되어 거부되었습니다."
            )
        }
        val tokens = rawCmd.split(Regex("\\s+")).filter { it.isNotBlank() }
        val action = tokens.firstOrNull()?.lowercase() ?: "status"
        val rest = tokens.drop(1)
        if (action != "settings" && action !in com.hanson.mobileuse.oscli.HansonOsCliMain.ALLOWED_ACTIONS) {
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "[보안 차단] 허용되지 않은 OS CLI 명령('$action')입니다. 허용 목록: ${com.hanson.mobileuse.oscli.HansonOsCliMain.ALLOWED_ACTIONS.joinToString(", ")}"
            )
        }

        // 1. Direct Settings.Secure / Settings.Global / Settings.System via permanent WRITE_SECURE_SETTINGS
        if (action == "setting" || action == "settings") {
            val op = rest.getOrNull(0)?.lowercase() ?: "get"
            val ns = rest.getOrNull(1)?.lowercase() ?: "system"
            val key = rest.getOrNull(2).orEmpty()
            val value = rest.drop(3).joinToString(" ")
            if (key.isNotBlank()) {
                try {
                    val cr = context.contentResolver
                    if (op == "put") {
                        val ok = when (ns) {
                            "secure" -> Settings.Secure.putString(cr, key, value)
                            "global" -> Settings.Global.putString(cr, key, value)
                            else -> Settings.System.putString(cr, key, value)
                        }
                        if (ok) {
                            return ToolExecutionOutcome(
                                succeeded = true,
                                outputText = "[Hanson OS CLI · WRITE_SECURE_SETTINGS] $ns.$key = '$value' 설정 완료",
                                receipt = ActionReceipt(
                                    toolName = "os_cli_exec",
                                    summaryKo = "OS CLI 설정 변경 ($ns.$key)",
                                    detailKo = "값 '$value' 직접 기록 (WRITE_SECURE_SETTINGS)",
                                    undoHintKo = "hanson setting put $ns $key <이전값> 으로 복구 가능"
                                )
                            )
                        }
                    } else if (op == "get") {
                        val current = when (ns) {
                            "secure" -> Settings.Secure.getString(cr, key)
                            "global" -> Settings.Global.getString(cr, key)
                            else -> Settings.System.getString(cr, key)
                        }
                        return ToolExecutionOutcome(
                            succeeded = true,
                            outputText = "[Hanson OS CLI] $ns.$key = ${current ?: "(null)"}"
                        )
                    }
                } catch (_: Throwable) {
                    // Fall through to UID-2000 daemon
                }
            }
        }

        // 2. Try UID-2000 On-Device OS CLI Daemon (127.0.0.1:18890) with 256-bit shared token
        try {
            val sharedToken = com.hanson.mobileuse.oscli.HansonOsCliMain.getOrCreateSharedDaemonToken(context)
            val url = java.net.URL("http://127.0.0.1:${com.hanson.mobileuse.oscli.HansonOsCliMain.DAEMON_PORT}/exec")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1500
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty(com.hanson.mobileuse.oscli.HansonOsCliMain.TOKEN_HEADER, sharedToken)
            }
            val reqJson = JSONObject().apply {
                put("action", action)
                put("args", org.json.JSONArray(rest))
            }
            conn.outputStream.use { it.write(reqJson.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode in 200..299) {
                val respStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(respStr)
                val daemonOk = json.optString("status") == "ok" &&
                    (!json.has("exitCode") || json.optInt("exitCode", -1) == 0)
                val out = json.optString("stdout").ifBlank { json.toString(2) }
                return ToolExecutionOutcome(
                    succeeded = daemonOk,
                    outputText = "[Hanson OS CLI · UID 2000 Daemon]\n$out",
                    receipt = if (daemonOk) ActionReceipt(
                        toolName = "os_cli_exec",
                        summaryKo = "OS CLI (UID 2000 :18890): $rawCmd",
                        detailKo = out.take(160),
                        undoHintKo = "모바일 OS CLI(hanson)에서 상태 복원 가능"
                    ) else null
                )
            }
        } catch (_: Throwable) {
            // Daemon not active on :18890; run via in-process HansonOsCliMain
        }

        // 3. Fallback: Execute via in-process HansonOsCliMain + Hidden Binder / Shell
        val resJson = com.hanson.mobileuse.oscli.HansonOsCliMain.executeCommandJson(action, rest)
        val stdout = resJson.optString("stdout").ifBlank { resJson.toString(2) }
        val ok = resJson.optString("status") == "ok" &&
            (!resJson.has("exitCode") || resJson.optInt("exitCode", -1) == 0)
        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = "[Hanson OS CLI]\n$stdout",
            receipt = if (ok) ActionReceipt(
                toolName = "os_cli_exec",
                summaryKo = "OS CLI 실행: $rawCmd",
                detailKo = stdout.take(160),
                undoHintKo = "시스템 설정 또는 hanson CLI로 되돌릴 수 있습니다."
            ) else null
        )
    }

    private fun executeReverseEngineerApp(
        args: JSONObject,
        latestSnapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val appQuery = args.optString("app_query", "현재앱").ifBlank { "현재앱" }
        val customRecipe = args.optString("custom_recipe", "").takeIf { it.isNotBlank() }
        val uarp = AppReverseEngineeringEngine(context)
        val protocol = uarp.reverseEngineerAndLearn(appQuery, latestSnapshot, customRecipe)
            ?: return ToolExecutionOutcome(
                succeeded = false,
                outputText = "'$appQuery'에 해당하는 설치된 앱을 찾지 못했습니다. 앱 이름이나 패키지명을 확인해 주세요."
            )

        return ToolExecutionOutcome(
            succeeded = true,
            outputText = buildString {
                appendLine("[UARP 앱 리버스 엔지니어링 & 스킬 합성 완료]")
                append(protocol.toPromptContext())
            },
            receipt = ActionReceipt(
                toolName = "reverse_engineer_app",
                summaryKo = "UARP 리버스 엔지니어링: ${protocol.appLabel} (${protocol.uiFramework})",
                detailKo = "딥링크 ${protocol.deepLinkSchemes.size}개, Activity ${protocol.exportedActivities.size}개, 앵커 ${protocol.uiAnchors.size}개 학습 완료",
                undoHintKo = "[스킬 & 감사] 탭에서 학습된 앱 프로토콜을 확인/재실행할 수 있습니다."
            )
        )
    }

    private fun executeLearnAppSkill(
        args: JSONObject,
        latestSnapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val appQuery = args.optString("app_query", "현재앱").ifBlank { "현재앱" }
        val recipe = args.optString("recipe", "").trim()
        if (recipe.isBlank()) {
            return ToolExecutionOutcome(succeeded = false, outputText = "저장할 프로토콜/레시피 내용이 비어 있습니다.")
        }
        val uarp = AppReverseEngineeringEngine(context)
        val protocol = uarp.reverseEngineerAndLearn(appQuery, latestSnapshot, recipe)
            ?: return ToolExecutionOutcome(
                succeeded = false,
                outputText = "'$appQuery' 앱을 찾지 못해 스킬을 저장하지 못했습니다."
            )
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "[UARP 스킬 학습 완료] ${protocol.appLabel} (${protocol.packageName})에 새로운 실행 프로토콜을 영구 등록했습니다:\n- $recipe",
            receipt = ActionReceipt(
                toolName = "learn_app_skill",
                summaryKo = "앱 스킬 학습: ${protocol.appLabel}",
                detailKo = recipe,
                undoHintKo = "[스킬 & 감사] 탭에서 영구 재사용됩니다."
            )
        )
    }

    private fun executeRenderGenerativeUi(args: JSONObject): ToolExecutionOutcome {
        val title = args.optString("title", "Generative UI 커스텀 위젯").ifBlank { "Generative UI 커스텀 위젯" }
        val subtitle = args.optString("subtitle", "window.HansonOS 실시간 브리지 연동")
        val htmlContent = args.optString("html_content", "").trim()
        if (htmlContent.isBlank()) {
            com.hanson.mobileuse.ui.components.GenerativeUiStateHolder.restoreDefaultCockpit()
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "[Generative UI] 기본 슈퍼앱 조종석 캔버스를 렌더링했습니다."
            )
        }
        com.hanson.mobileuse.ui.components.GenerativeUiStateHolder.publishSpec(
            com.hanson.mobileuse.ui.components.GenerativeUiSpec(
                title = title,
                subtitle = subtitle,
                htmlContent = htmlContent
            )
        )
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "[Generative UI 렌더링 완료] '$title' 인터랙티브 마이크로 앱이 커맨드 센터 상단에 동적 합성되었습니다.",
            receipt = ActionReceipt(
                toolName = "render_generative_ui",
                summaryKo = "Generative UI 합성: $title",
                detailKo = subtitle,
                undoHintKo = "우측 상단 새로고침/닫기 아이콘으로 기본 조종석으로 복원할 수 있습니다."
            )
        )
    }

    private fun executeVaultBlindInject(
        args: JSONObject,
        snapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val rawHandle = args.optString("handle", "").trim()
        if (rawHandle.isEmpty()) {
            return ToolExecutionOutcome(false, "주입할 볼트 핸들(handle)을 지정하세요.")
        }
        val externalApprovalReceiptId = args.optString("external_approval_receipt_id", "")
            .ifBlank { args.optString("approval_receipt_id", "") }
            .trim()
            .takeIf { it.isNotEmpty() }
        val controlEpoch = if (args.has("control_epoch") && !args.isNull("control_epoch")) {
            args.optLong("control_epoch", -1L).takeIf { it >= 0L }
        } else {
            null
        }

        val vault = resolveBlindVault()
        val summary = vault.getHandleSummary(rawHandle)
            ?: run {
                vault.recordAuditEvent(
                    action = "BLIND_INJECT_MISSING_HANDLE",
                    handle = rawHandle,
                    approvedReceiptId = externalApprovalReceiptId,
                    controlEpoch = controlEpoch,
                    outcome = "NOT_FOUND"
                )
                return ToolExecutionOutcome(
                    succeeded = false,
                    outputText = "등록된 Blind Vault 핸들을 찾을 수 없습니다: $rawHandle"
                )
            }

        val service = HansonAccessibilityService.instance
        if (service == null) {
            vault.recordAuditEvent(
                action = "BLIND_INJECT_BLOCKED_A11Y",
                handle = summary.handle,
                approvedReceiptId = externalApprovalReceiptId,
                controlEpoch = controlEpoch,
                outcome = "ACCESSIBILITY_UNAVAILABLE"
            )
            return ToolExecutionOutcome(
                succeeded = false,
                outputText = "[ACCESSIBILITY_UNAVAILABLE] 접근성 서비스(HansonAccessibilityService)가 비활성 상태여서 '${summary.handle}' (${summary.category.labelKo}) 블라인드 주입을 실행할 수 없습니다. 평문은 복호화되지 않았습니다. [권한 허브]에서 접근성 서비스를 활성화하거나 사용자가 직접 입력해 주세요(Human Takeover).",
                receipt = null
            )
        }

        val pt = resolveTargetPixel(args, snapshot)
        val clearExisting = args.optBoolean("clear_existing", true)
        val pressEnter = if (args.has("press_enter")) {
            args.optBoolean("press_enter", false)
        } else {
            args.optBoolean("submit_enter", false)
        }

        val ok = vault.withDecryptedSecretChars(
            keyOrHandle = rawHandle,
            approvedReceiptId = externalApprovalReceiptId,
            controlEpoch = controlEpoch
        ) { _, secretChars ->
            withTemporaryHudPassThrough {
                GestureController.inputText(
                    service = service,
                    text = String(secretChars),
                    targetX = pt?.first?.toFloat(),
                    targetY = pt?.second?.toFloat(),
                    clearExisting = clearExisting
                )
            }
        } ?: false

        if (ok && pressEnter) {
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
            }
            GestureController.performGlobalKey(service, "ENTER")
        }

        vault.recordAuditEvent(
            action = if (ok) "BLIND_INJECT_SUCCESS" else "BLIND_INJECT_FAILED",
            handle = summary.handle,
            approvedReceiptId = externalApprovalReceiptId,
            controlEpoch = controlEpoch,
            outcome = if (ok) "OK" else "TARGET_FIELD_NOT_FOUND"
        )

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) {
                "[블라인드 주입 완료] '${summary.handle}' (${summary.category.labelKo}) 필드에 안전하게 입력했습니다 (LLM 평문 비노출)."
            } else {
                "포커스된 입력창을 찾지 못해 '${summary.handle}' 블라인드 주입에 실패했습니다."
            },
            receipt = if (ok) ActionReceipt(
                toolName = "vault_blind_inject",
                summaryKo = "블라인드 볼트 주입: ${summary.handle}",
                detailKo = "${summary.label} · ${summary.category.labelKo} (평문 메모리 비저장)",
                undoHintKo = "입력 필드를 지우면 값을 초기화할 수 있습니다.",
                approvalReceiptId = externalApprovalReceiptId,
                controlEpoch = controlEpoch
            ) else null
        )
    }

    private fun executeVaultListHandles(args: JSONObject): ToolExecutionOutcome {
        val rawCat = args.optString("category", "").trim().uppercase(java.util.Locale.ROOT)
        val filter = if (rawCat.isNotEmpty()) {
            runCatching { VaultSecretCategory.valueOf(rawCat) }.getOrNull()
        } else {
            null
        }
        val handles = resolveBlindVault().listHandles(filter)
        if (handles.isEmpty()) {
            return ToolExecutionOutcome(
                succeeded = true,
                outputText = "Blind Credential Vault에 등록된 핸들이 없습니다. Studio의 Shield 버튼 또는 `/vault`에서 자격증명 핸들을 추가하세요."
            )
        }
        val lines = handles.joinToString("\n") { h ->
            "- ${h.handle} [${h.category.labelKo}] · ${h.label} (${h.maskedPreview})"
        }
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "[Blind Vault 핸들 ${handles.size}건 · 평문 비노출]\n$lines",
            receipt = ActionReceipt(
                toolName = "vault_list_handles",
                summaryKo = "볼트 핸들 조회 (${handles.size}건)",
                detailKo = "AES-256-GCM 암호화 메타데이터만 조회",
                undoHintKo = "읽기 전용 조회"
            )
        )
    }

    private fun executeWatcherManage(
        args: JSONObject,
        snapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val action = args.optString("action", "list").trim().lowercase(java.util.Locale.ROOT)
        val engine = resolveWatcherEngine()
        return when (action) {
            "list" -> {
                val rules = engine.listRules()
                if (rules.isEmpty()) {
                    ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "등록된 능동 감시 규칙(Proactive Watcher)이 없습니다."
                    )
                } else {
                    val lines = rules.joinToString("\n") { r ->
                        val state = if (r.enabled) "ON" else "OFF"
                        "- [${r.id}] ($state · ${r.mode.labelKo}) ${r.title} · 키워드=${r.matchKeywords.joinToString(",")} · 감지=${r.hitCount}회"
                    }
                    ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "[Proactive Watchers ${rules.size}개]\n$lines",
                        receipt = ActionReceipt(
                            toolName = "watcher_manage",
                            summaryKo = "감시 규칙 목록 조회 (${rules.size}개)",
                            detailKo = "Poke/Pickle 능동 모니터",
                            undoHintKo = "읽기 전용 조회"
                        )
                    )
                }
            }
            "add" -> {
                val rawKeywords = args.optString("match_keywords", args.optString("trigger_regex", "")).trim()
                val keywords = rawKeywords.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                if (keywords.isEmpty()) {
                    return ToolExecutionOutcome(false, "감시할 키워드(match_keywords)를 1개 이상 지정하세요.")
                }
                val title = args.optString("title", keywords.joinToString(" · ")).trim()
                val target = args.optString("target_app_or_url", args.optString("target_package", "")).trim()
                val intervalSec = args.optInt("interval_sec", args.optInt("cooldown_sec", 60))
                val modeStr = args.optString("action_mode", "HOLD_FOR_APPROVAL").trim().uppercase(java.util.Locale.ROOT)
                val mode = if (modeStr == "NOTIFY_ONLY") WatcherActionMode.NOTIFY_ONLY else WatcherActionMode.HOLD_FOR_APPROVAL
                val created = engine.addRule(
                    title = title,
                    targetAppOrUrl = target,
                    matchKeywords = keywords,
                    intervalSec = intervalSec,
                    mode = mode
                )
                val undoSpec = ReversibleReceiptPlanner.planUndoForAction("watcher_manage", args, created.id)
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "[감시 규칙 추가] ${created.title} (${created.id}) · 키워드=${created.matchKeywords.joinToString(",")} · 모드=${created.mode.labelKo}",
                    receipt = ActionReceipt(
                        toolName = "watcher_manage",
                        summaryKo = "능동 감시 등록: ${created.title}",
                        detailKo = "키워드=${created.matchKeywords.joinToString(",")}",
                        undoHintKo = if (undoSpec != null) {
                            "${undoSpec.undoLabelKo} · Studio Hub 또는 `/watch`에서 비활성화/삭제 가능"
                        } else {
                            "Studio Hub 또는 `/watch`에서 비활성화/삭제 가능"
                        },
                        undoToolName = undoSpec?.undoToolName.orEmpty(),
                        undoArgsJson = undoSpec?.undoArgsJson.orEmpty()
                    )
                )
            }
            "toggle" -> {
                val ruleId = args.optString("rule_id", "").trim()
                val enabled = args.optBoolean("enabled", true)
                val ok = engine.toggleRule(ruleId, enabled)
                ToolExecutionOutcome(
                    succeeded = ok,
                    outputText = if (ok) "감시 규칙($ruleId) 상태를 ${if (enabled) "ON" else "OFF"}로 변경했습니다." else "감시 규칙을 찾을 수 없습니다: $ruleId"
                )
            }
            "remove" -> {
                val ruleId = args.optString("rule_id", "").trim()
                val ok = engine.removeRule(ruleId)
                ToolExecutionOutcome(
                    succeeded = ok,
                    outputText = if (ok) "감시 규칙($ruleId)을 삭제했습니다." else "삭제할 감시 규칙을 찾을 수 없습니다: $ruleId"
                )
            }
            "evaluate" -> {
                val hits = engine.evaluateSnapshot(snapshot)
                if (hits.isEmpty()) {
                    ToolExecutionOutcome(true, "현재 화면에서 조건에 일치한 능동 감시 이벤트가 없습니다.")
                } else {
                    val summary = hits.joinToString("\n") { "- ${it.summaryKo}" }
                    ToolExecutionOutcome(true, "[감시 이벤트 감지 ${hits.size}건]\n$summary")
                }
            }
            else -> ToolExecutionOutcome(false, "지원하지 않는 watcher_manage action입니다: $action")
        }
    }

    private fun executeA2aNegotiateSchedule(args: JSONObject): ToolExecutionOutcome {
        val peerName = args.optString("peer_name", "상대 에이전트").trim()
        val durationMin = args.optInt("duration_minutes", 60)
        val area = args.optString("preferred_area", "성수·강남 중간지점").trim()
        val myBusy = args.optString("my_busy_slots", "").trim()
        val peerFree = args.optString("peer_free_slots", "12:00-14:00, 18:30-21:00").trim()

        val outcome = PickleA2AScheduleNegotiator.negotiate(
            peerName = peerName,
            durationMinutes = durationMin,
            preferredArea = area,
            myBusyCsv = myBusy,
            peerFreeCsv = peerFree
        )
        return ToolExecutionOutcome(
            succeeded = outcome.candidates.isNotEmpty(),
            outputText = "${outcome.summaryKo}\n\n[A2A JSON-RPC]\n${outcome.a2aJsonRpcPayload.toString(2)}",
            receipt = ActionReceipt(
                toolName = "a2a_negotiate_schedule",
                summaryKo = "A2A 일정 조율: ${outcome.peerName} (${outcome.candidates.size}개 후보)",
                detailKo = "${durationMin}분 · $area",
                undoHintKo = "확정 전까지 실제 캘린더는 수정되지 않습니다."
            )
        )
    }

    private fun executeScratchpadNote(
        args: JSONObject,
        snapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        val action = args.optString("action", "list").trim().lowercase(java.util.Locale.ROOT)
        val pad = resolveScratchpad()
        return when (action) {
            "list" -> {
                val entries = pad.listEntries()
                if (entries.isEmpty()) {
                    ToolExecutionOutcome(true, "Notetaker 크로스앱 작업 메모장이 비어 있습니다.")
                } else {
                    val lines = entries.joinToString("\n") { e ->
                        val src = e.sourcePackage.substringAfterLast('.').ifBlank { "manual" }
                        "- [${e.key}] (${src}) ${e.value}"
                    }
                    ToolExecutionOutcome(true, "[Notetaker Scratchpad ${entries.size}건]\n$lines")
                }
            }
            "put" -> {
                val key = args.optString("key", "").trim()
                val rawValue = args.optString("value", "").trim()
                if (key.isEmpty() || rawValue.isEmpty()) {
                    return ToolExecutionOutcome(false, "저장할 key와 value를 모두 입력하세요.")
                }
                val safeVal = resolveBlindVault().redactSensitiveText(rawValue)
                val sourceApp = args.optString("source_app", snapshot?.activePackage.orEmpty()).trim()
                val entry = pad.put(key = key, value = safeVal, sourcePackage = sourceApp, autoCaptured = false)
                ToolExecutionOutcome(
                    succeeded = true,
                    outputText = "[Notetaker 저장] ${entry.key} = ${entry.value}",
                    receipt = ActionReceipt(
                        toolName = "scratchpad_note",
                        summaryKo = "Notetaker 기록: ${entry.key}",
                        detailKo = entry.value.take(60),
                        undoHintKo = "scratchpad_note(action='remove')로 삭제할 수 있습니다."
                    )
                )
            }
            "get" -> {
                val key = args.optString("key", "").trim()
                val found = pad.get(key)
                if (found == null) {
                    ToolExecutionOutcome(false, "Notetaker 항목을 찾을 수 없습니다: $key")
                } else {
                    ToolExecutionOutcome(true, "[${found.key}] ${found.value} (출처: ${found.sourcePackage.ifBlank { "manual" }})")
                }
            }
            "remove" -> {
                val key = args.optString("key", "").trim()
                val ok = pad.remove(key)
                ToolExecutionOutcome(ok, if (ok) "Notetaker 항목($key)을 삭제했습니다." else "항목을 찾을 수 없습니다: $key")
            }
            "clear" -> {
                pad.clear()
                ToolExecutionOutcome(true, "Notetaker 크로스앱 작업 메모장을 비웠습니다.")
            }
            else -> ToolExecutionOutcome(false, "지원하지 않는 scratchpad_note action입니다: $action")
        }
    }

    private fun executeWorkspaceList(args: JSONObject): ToolExecutionOutcome {
        val subDir = args.optString("sub_dir", "").trim()
            .ifBlank { args.optString("path", "").trim() }
        val maxDepth = if (args.has("max_depth")) args.optInt("max_depth", 4).coerceIn(1, 10) else 4
        return try {
            val entries = resolvedWorkspaceEngine.listFiles(subDir, maxDepth)
            val text = resolvedWorkspaceEngine.formatFilesAsText(subDir, maxDepth)
            val displayRoot = subDir.ifBlank { "." }
            ToolExecutionOutcome(
                succeeded = true,
                outputText = if (entries.isEmpty()) {
                    "작업공간($displayRoot)에 파일이 없습니다."
                } else {
                    "[작업공간 파일 ${entries.size}개]\n$text"
                },
                receipt = ActionReceipt(
                    toolName = "workspace_list",
                    summaryKo = "작업공간 목록 조회 (${entries.size}개)",
                    detailKo = displayRoot,
                    undoHintKo = "읽기 전용 조회"
                )
            )
        } catch (sec: SecurityException) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "보안 경로 차단 (Path traversal blocked): ${sec.message}"
            )
        }
    }

    private fun executeWorkspaceRead(args: JSONObject): ToolExecutionOutcome {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) {
            return ToolExecutionOutcome(false, "읽을 파일의 상대 경로(path)를 지정하세요.")
        }
        val startLine = if (args.has("start_line")) args.optInt("start_line", 1).coerceAtLeast(1) else 1
        val endLine = if (args.has("end_line")) args.optInt("end_line", startLine + 199).coerceAtLeast(startLine) else (startLine + 199)
        return try {
            val result = resolvedWorkspaceEngine.readFile(
                relativePath = path,
                startLine = startLine,
                endLine = endLine,
                includeLineNumbers = true
            )
            result.fold(
                onSuccess = { content ->
                    ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "[$path L$startLine-$endLine]\n$content",
                        receipt = ActionReceipt(
                            toolName = "workspace_read",
                            summaryKo = "파일 읽기: $path",
                            detailKo = "Lines $startLine..$endLine",
                            undoHintKo = "읽기 전용 조회"
                        )
                    )
                },
                onFailure = { err ->
                    val prefix = if (err is SecurityException) {
                        "보안 경로 차단 (Path traversal blocked)"
                    } else {
                        "파일 읽기 실패 ($path)"
                    }
                    ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "$prefix: ${err.message}"
                    )
                }
            )
        } catch (sec: SecurityException) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "보안 경로 차단 (Path traversal blocked): ${sec.message}"
            )
        }
    }

    private fun executeWorkspaceWrite(args: JSONObject): ToolExecutionOutcome {
        val path = args.optString("path", "").trim()
        if (path.isBlank()) {
            return ToolExecutionOutcome(false, "저장할 파일 경로(path)가 비어 있습니다.")
        }
        val content = args.optString("content", "")
        return try {
            val diff = resolvedWorkspaceEngine.writeFile(path, content)
            ToolExecutionOutcome(
                succeeded = true,
                outputText = "[파일 저장 완료] $path (+${diff.addedLines} / -${diff.removedLines})\n${diff.unifiedDiff.take(1500)}",
                receipt = ActionReceipt(
                    toolName = "workspace_write",
                    summaryKo = "파일 쓰기: $path (+${diff.addedLines}/-${diff.removedLines})",
                    detailKo = diff.unifiedDiff.take(200),
                    undoHintKo = "Code Workbench Diff/Undo 탭에서 체크포인트 복원 가능"
                )
            )
        } catch (sec: SecurityException) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "보안 경로 차단 (Path traversal blocked): ${sec.message}"
            )
        }
    }

    private fun executeWorkspaceEdit(args: JSONObject): ToolExecutionOutcome {
        val path = args.optString("path", "").trim()
        val oldText = args.optString("old_text", "")
        val newText = args.optString("new_text", "")
        if (path.isBlank() || oldText.isEmpty()) {
            return ToolExecutionOutcome(false, "파일 경로(path)와 교체할 기존 텍스트(old_text)가 필요합니다.")
        }
        return try {
            val result = resolvedWorkspaceEngine.editFile(path, oldText, newText)
            result.fold(
                onSuccess = { diff ->
                    ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "[파일 패치 완료] $path (+${diff.addedLines} / -${diff.removedLines})\n${diff.unifiedDiff.take(1500)}",
                        receipt = ActionReceipt(
                            toolName = "workspace_edit",
                            summaryKo = "파일 패치: $path (+${diff.addedLines}/-${diff.removedLines})",
                            detailKo = diff.unifiedDiff.take(200),
                            undoHintKo = "Code Workbench Undo 탭에서 복구 가능"
                        )
                    )
                },
                onFailure = { err ->
                    val prefix = if (err is SecurityException) {
                        "보안 경로 차단 (Path traversal blocked)"
                    } else {
                        "파일 패치 실패 ($path)"
                    }
                    ToolExecutionOutcome(
                        succeeded = false,
                        outputText = "$prefix: ${err.message}"
                    )
                }
            )
        } catch (sec: SecurityException) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "보안 경로 차단 (Path traversal blocked): ${sec.message}"
            )
        }
    }

    private fun executeWorkspaceSearch(args: JSONObject): ToolExecutionOutcome {
        val query = args.optString("query", "")
        val pathPrefix = args.optString("path_prefix", "").trim()
        if (query.isBlank()) {
            return ToolExecutionOutcome(false, "검색어(query)를 입력하세요.")
        }
        return try {
            val hits = resolvedWorkspaceEngine.searchFiles(query, pathPrefix)
            val scopeLabel = pathPrefix.ifBlank { "전체 작업공간" }
            ToolExecutionOutcome(
                succeeded = true,
                outputText = if (hits.isEmpty()) {
                    "검색 결과 없음: '$query' ($scopeLabel)"
                } else {
                    "[검색 결과 ${hits.size}건] '$query'\n${hits.joinToString("\n")}"
                },
                receipt = ActionReceipt(
                    toolName = "workspace_search",
                    summaryKo = "작업공간 검색 (${hits.size}건): $query",
                    detailKo = scopeLabel,
                    undoHintKo = "읽기 전용 조회"
                )
            )
        } catch (sec: SecurityException) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "보안 경로 차단 (Path traversal blocked): ${sec.message}"
            )
        }
    }

    private fun executeWorkspaceExec(args: JSONObject): ToolExecutionOutcome {
        val command = args.optString("command", "").trim()
        if (command.isBlank()) {
            return ToolExecutionOutcome(false, "실행할 안전 샌드박스 명령(command)을 입력하세요.")
        }
        val res = resolvedWorkspaceEngine.execWorkspaceCommand(command)
        return ToolExecutionOutcome(
            succeeded = res.succeeded,
            outputText = res.toFormattedOutput(),
            receipt = ActionReceipt(
                toolName = "workspace_exec",
                summaryKo = "워크스페이스 명령: ${command.take(40)}",
                detailKo = "exit=${res.exitCode}",
                undoHintKo = "샌드박스 허용 목록 실행"
            )
        )
    }

    private fun a11yNotConnectedOutcome(toolName: String) = ToolExecutionOutcome(
        succeeded = false,
        outputText = "[ACCESSIBILITY_UNAVAILABLE] 접근성 서비스(HansonAccessibilityService)가 아직 활성화되지 않아 '$toolName' 도구를 실행할 수 없습니다. [권한 허브] 탭에서 접근성 서비스를 켜거나 직접 조작(Human Takeover)해 주세요."
    )
}
