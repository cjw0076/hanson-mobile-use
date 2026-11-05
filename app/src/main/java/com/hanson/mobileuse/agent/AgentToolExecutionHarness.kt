package com.hanson.mobileuse.agent

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import org.json.JSONObject
import kotlin.math.roundToInt

data class ToolExecutionOutcome(
    val succeeded: Boolean,
    val outputText: String,
    val receipt: ActionReceipt? = null
)

/**
 * Executes all 20 Mobile-Use tools across the Accessibility Engine, Screen-Off Notification Cache,
 * and Android OS System/Telephony/PIM APIs, producing verifiable ActionReceipts.
 */
class AgentToolExecutionHarness(private val context: Context) {

    companion object {
        private const val NOTES_PREFS = "hanson_agent_memory_notes"

        private val KNOWN_APP_ALIASES = mapOf(
            "카카오톡" to "com.kakao.talk",
            "카톡" to "com.kakao.talk",
            "kakaotalk" to "com.kakao.talk",
            "유튜브" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "설정" to "com.android.settings",
            "settings" to "com.android.settings",
            "크롬" to "com.android.chrome",
            "chrome" to "com.android.chrome",
            "네이버" to "com.nhn.android.search",
            "naver" to "com.nhn.android.search",
            "네이버지도" to "com.nhn.android.nmap",
            "카카오맵" to "net.daum.android.map",
            "배달의민족" to "com.sampleapp",
            "배민" to "com.sampleapp",
            "쿠팡" to "com.coupang.mobile",
            "토스" to "viva.republica.toss",
            "인스타그램" to "com.instagram.android",
            "인스타" to "com.instagram.android",
            "텔레그램" to "org.telegram.messenger",
            "슬랙" to "com.Slack",
            "디스코드" to "com.discord",
            "갤러리" to "com.sec.android.gallery3d",
            "카메라" to "com.sec.android.app.camera",
            "시계" to "com.sec.android.app.clockpackage"
        )
    }

    private val notesPrefs = context.getSharedPreferences(NOTES_PREFS, Context.MODE_PRIVATE)

    fun execute(
        toolName: String,
        args: JSONObject,
        latestSnapshot: AtomicSnapshot?
    ): ToolExecutionOutcome {
        return try {
            when (toolName) {
                "click" -> executeClick(args, latestSnapshot)
                "long_press" -> executeLongPress(args, latestSnapshot)
                "input_text" -> executeInputText(args, latestSnapshot)
                "swipe" -> executeSwipe(args, latestSnapshot)
                "click_sequence" -> executeClickSequence(args, latestSnapshot)
                "press_key" -> executePressKey(args)
                "manage_app" -> executeManageApp(args)
                "wait_for_delay" -> executeWait(args)
                "open_link" -> executeOpenLink(args)
                "list_notifications" -> executeListNotifications(args)
                "reply_notification" -> executeReplyNotification(args)
                "control_device_setting" -> executeControlDeviceSetting(args)
                "make_call" -> executeMakeCall(args)
                "send_sms" -> executeSendSms(args)
                "search_contacts" -> executeSearchContacts(args)
                "manage_calendar" -> executeManageCalendar(args)
                "set_alarm" -> executeSetAlarm(args)
                "save_note" -> executeSaveNote(args)
                "read_note" -> executeReadNote(args)
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
        } catch (e: Exception) {
            ToolExecutionOutcome(
                succeeded = false,
                outputText = "도구 실행 오류 ($toolName): ${e.message}"
            )
        }
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

        if (args.has("element_index")) {
            val idx = args.optInt("element_index", -1)
            val el = snapshot?.indexedElements?.firstOrNull { it.index == idx }
            if (el != null) {
                return el.centerX to el.centerY
            }
        }

        if (args.has("norm_x") && args.has("norm_y")) {
            val nx = args.optInt("norm_x", -1)
            val ny = args.optInt("norm_y", -1)
            if (nx >= 0 && ny >= 0) {
                return CoordinateNormalizer.normalizedToPixel(nx, ny, screenW, screenH)
            }
        }
        return null
    }

    private fun executeClick(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("click")

        val pt = resolveTargetPixel(args, snapshot)
            ?: return ToolExecutionOutcome(false, "click 대상(element_index 또는 norm_x/norm_y)을 찾을 수 없습니다.")

        val desc = args.optString("target_description", "UI 요소")
        val times = args.optInt("times", 1).coerceIn(1, 5)
        val delayMs = args.optLong("delay_ms", 120L)

        val ok = GestureController.multiTap(service, pt.first.toFloat(), pt.second.toFloat(), times, delayMs)
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
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("long_press")

        val pt = resolveTargetPixel(args, snapshot)
            ?: return ToolExecutionOutcome(false, "long_press 대상 좌표를 확인할 수 없습니다.")

        val desc = args.optString("target_description", "UI 요소")
        val duration = args.optLong("duration_ms", 700L)
        val ok = GestureController.longPress(service, pt.first.toFloat(), pt.second.toFloat(), duration)

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
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("input_text")

        val text = args.optString("text", "")
        val clear = args.optBoolean("clear_existing", false)
        val pressEnter = args.optBoolean("press_enter", true)
        val pt = resolveTargetPixel(args, snapshot)

        val ok = GestureController.inputText(
            service = service,
            text = text,
            targetX = pt?.first?.toFloat(),
            targetY = pt?.second?.toFloat(),
            clearExisting = clear
        )
        if (ok && pressEnter) {
            try {
                Thread.sleep(150L)
            } catch (_: InterruptedException) {
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
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("swipe")

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

        val ok = GestureController.swipe(
            service = service,
            startX = vector.startX.toFloat(),
            startY = vector.startY.toFloat(),
            endX = vector.endX.toFloat(),
            endY = vector.endY.toFloat(),
            durationMs = duration
        )

        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) {
                "스와이프($direction) 완료: (${vector.startX},${vector.startY}) -> (${vector.endX},${vector.endY})"
            } else {
                "스와이프 실행 실패"
            }
        )
    }

    private fun executeClickSequence(args: JSONObject, snapshot: AtomicSnapshot?): ToolExecutionOutcome {
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("click_sequence")

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
        val ok = GestureController.clickSequence(service, points, delayMs)

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
        val service = HansonAccessibilityService.instance
            ?: return a11yNotConnectedOutcome("press_key")
        val key = args.optString("key", "BACK").uppercase()
        val ok = GestureController.performGlobalKey(service, key)
        return ToolExecutionOutcome(
            succeeded = ok,
            outputText = if (ok) "전역 시스템 키 실행 완료: $key" else "전역 시스템 키 실행 실패: $key"
        )
    }

    private fun executeManageApp(args: JSONObject): ToolExecutionOutcome {
        val action = args.optString("action", "launch").lowercase()
        val query = args.optString("app_name", "").trim()
        val pm = context.packageManager

        if (action == "search" || action == "list") {
            val matches = searchInstalledApps(pm, query).take(15)
            val formatted = if (matches.isEmpty()) {
                "'$query'와 일치하는 설치된 앱이 없습니다."
            } else {
                matches.joinToString("\n") { "- ${it.first} (${it.second})" }
            }
            return ToolExecutionOutcome(true, "설치된 앱 검색 결과:\n$formatted")
        }

        // Launch app
        val directPkg = KNOWN_APP_ALIASES[query.lowercase()] ?: query
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

    private fun searchInstalledApps(pm: PackageManager, query: String): List<Pair<String, String>> {
        val q = query.lowercase()
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
            if (q.isEmpty() || label.lowercase().contains(q) || pkg.lowercase().contains(q)) {
                label to pkg
            } else {
                null
            }
        }.sortedBy { it.first }
    }

    private fun executeWait(args: JSONObject): ToolExecutionOutcome {
        val ms = args.optLong("time_in_ms", 1500L).coerceIn(200L, 10000L)
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return ToolExecutionOutcome(true, "${ms}ms 대기 완료")
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
            "wifi_panel" -> {
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                ToolExecutionOutcome(true, "Wi-Fi 설정 패널을 열었습니다.")
            }
            "bluetooth_panel" -> {
                context.startActivity(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                ToolExecutionOutcome(true, "블루투스 설정 화면을 열었습니다.")
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
        val action = args.optString("action", "list").lowercase()
        val title = args.optString("title", "새 일정")
        val offsetMin = args.optLong("offset_minutes_from_now", 60L)
        val durationMin = args.optLong("duration_minutes", 60L)
        val desc = args.optString("description", "Hanson Mobile-Use 에이전트가 생성한 일정")

        val startMs = System.currentTimeMillis() + offsetMin * 60_000L
        val endMs = startMs + durationMin * 60_000L

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
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
                    }
                    context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    return ToolExecutionOutcome(
                        succeeded = true,
                        outputText = "캘린더 일정 등록 완료: '$title' (${offsetMin}분 후 시작, ${durationMin}분간)",
                        receipt = ActionReceipt(
                            toolName = "manage_calendar",
                            summaryKo = "캘린더 일정 추가: '$title'",
                            detailKo = "$desc (${durationMin}분)",
                            undoHintKo = "캘린더 앱에서 일정을 삭제할 수 있습니다."
                        )
                    )
                }
            }
            val insertIntent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.Events.DESCRIPTION, desc)
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

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val timeFormatted = "%02d:%02d".format(hour, minute)
        return ToolExecutionOutcome(
            succeeded = true,
            outputText = "시스템 알람 설정 완료: $timeFormatted ($label)",
            receipt = ActionReceipt(
                toolName = "set_alarm",
                summaryKo = "알람 설정: $timeFormatted ($label)",
                detailKo = "AlarmClock.ACTION_SET_ALARM ($timeFormatted)",
                undoHintKo = "시계 앱에서 해당 알람을 해제할 수 있습니다."
            )
        )
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

    private fun a11yNotConnectedOutcome(toolName: String) = ToolExecutionOutcome(
        succeeded = false,
        outputText = "접근성 서비스(HansonAccessibilityService)가 아직 활성화되지 않아 '$toolName' 도구를 실행할 수 없습니다. [권한 허브] 탭에서 접근성 서비스를 켜주세요."
    )
}
