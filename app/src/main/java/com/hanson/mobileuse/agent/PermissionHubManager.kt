package com.hanson.mobileuse.agent

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PermissionTier(val titleKo: String, val subtitleKo: String) {
    TIER_1_ACCESSIBILITY(
        "Tier 1 · 접근성 및 GUI 엔진 (Google Artemis 코어)",
        "멀티윈도우 UI 트리 덤프, 아토믹 스크린샷, 제스처 주입 및 전역 하드웨어 키 제어"
    ),
    TIER_2_BACKGROUND_OVERLAY(
        "Tier 2 · 백그라운드 상시 실행 · 플로팅 HUD · 알림 가로채기",
        "화면 꺼짐 상태 메신저 0.2초 즉시 답장, 타 앱 위 라이브 HUD 오버레이, 배터리 무제한"
    ),
    TIER_3_SYSTEM_OS(
        "Tier 3 · OS 시스템 설정 제어 및 전체 패키지 탐색",
        "설치된 모든 앱 조회/실행, 시스템 밝기/볼륨/방해금지(DND) 제어 및 포그라운드 앱 감지"
    ),
    TIER_4_SENSORS_TELEPHONY(
        "Tier 4 · 멀티모달 센서 · 전화 · 문자 · 연락처 · 캘린더",
        "음성 마이크, 카메라, 위치, 즉시 전화 발신, SMS 전송, 연락처 및 일정 자동화"
    ),
    TIER_5_BRIDGE_PRIVILEGED(
        "Tier 5 · Artemis 루프백 브리지 (:18888) 및 자율 거버넌스",
        "외부 AI(Google Artemis, ADB, MCP) 연동 브리지 및 무제한 자율 실행 거버넌스"
    )
}

enum class AgentCapability(val displayName: String, val defaultLevel: PermissionLevel) {
    GUI_AUTOMATION("화면 터치·스와이프·텍스트 입력 (GUI 제어)", PermissionLevel.ALWAYS),
    APP_LAUNCH("전체 앱 탐색 및 실행/전환", PermissionLevel.ALWAYS),
    NOTIFICATION_REPLY("화면 꺼짐 알림 읽기 및 즉시 답장 (RemoteInput)", PermissionLevel.ALWAYS),
    SYSTEM_SETTINGS("시스템 밝기·볼륨·방해금지(DND) 변경", PermissionLevel.ALWAYS),
    PHONE_CALL("전화 즉시 발신 (CALL_PHONE)", PermissionLevel.ASK_EVERY_TIME),
    SMS_SEND("SMS 문자 발송 (SEND_SMS)", PermissionLevel.ASK_EVERY_TIME),
    CONTACTS_ACCESS("연락처 조회 및 검색", PermissionLevel.ALWAYS),
    CALENDAR_ALARM("캘린더 일정 및 알람 등록", PermissionLevel.ALWAYS),
    EXTERNAL_WEB("외부 웹 링크 및 딥링크 열기", PermissionLevel.ALWAYS)
}

enum class PermissionLevel(val labelKo: String) {
    ALWAYS("항상 허용"),
    ASK_EVERY_TIME("실행 전 확인"),
    NEVER("차단")
}

data class PermissionStatusItem(
    val id: String,
    val title: String,
    val permissionCode: String,
    val tier: PermissionTier,
    val description: String,
    val apiNote: String,
    val isGranted: Boolean,
    val isRuntimePermission: Boolean = false,
    val runtimePermissions: List<String> = emptyList(),
    val settingsAction: String? = null,
    val capability: AgentCapability? = null,
    val governanceLevel: PermissionLevel = PermissionLevel.ALWAYS
)

data class PermissionHubState(
    val unrestrictedAutonomousMode: Boolean = true,
    val items: List<PermissionStatusItem> = emptyList(),
    val grantedCount: Int = 0,
    val totalCount: Int = 0
) {
    val readinessPercent: Int
        get() = if (totalCount == 0) 100 else ((grantedCount * 100f) / totalCount).toInt().coerceIn(0, 100)
}

/**
 * Comprehensive Android Permission & Accessibility Control Hub.
 * Tracks OS grant states across all 5 tiers, provides 1-tap deep-link intents to exact
 * system settings pages, and manages EZmap/Hanson tool governance + Unrestricted Mode.
 */
class PermissionHubManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "hanson_permission_governance"
        private const val KEY_UNRESTRICTED_MODE = "unrestricted_autonomous_mode"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(PermissionHubState())
    val state: StateFlow<PermissionHubState> = _state.asStateFlow()

    init {
        refreshStatus()
    }

    fun isUnrestrictedMode(): Boolean = prefs.getBoolean(KEY_UNRESTRICTED_MODE, true)

    fun setUnrestrictedMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UNRESTRICTED_MODE, enabled).apply()
        if (enabled) {
            for (cap in AgentCapability.entries) {
                prefs.edit().putString("cap_${cap.name}", PermissionLevel.ALWAYS.name).apply()
            }
        }
        refreshStatus()
    }

    fun getCapabilityLevel(capability: AgentCapability): PermissionLevel {
        if (isUnrestrictedMode()) return PermissionLevel.ALWAYS
        val raw = prefs.getString("cap_${capability.name}", capability.defaultLevel.name)
        return try {
            PermissionLevel.valueOf(raw ?: capability.defaultLevel.name)
        } catch (_: Exception) {
            capability.defaultLevel
        }
    }

    fun setCapabilityLevel(capability: AgentCapability, level: PermissionLevel) {
        prefs.edit().putString("cap_${capability.name}", level.name).apply()
        if (level != PermissionLevel.ALWAYS && isUnrestrictedMode()) {
            prefs.edit().putBoolean(KEY_UNRESTRICTED_MODE, false).apply()
        }
        refreshStatus()
    }

    fun cycleCapabilityLevel(capability: AgentCapability) {
        val current = getCapabilityLevel(capability)
        val next = when (current) {
            PermissionLevel.ALWAYS -> PermissionLevel.ASK_EVERY_TIME
            PermissionLevel.ASK_EVERY_TIME -> PermissionLevel.NEVER
            PermissionLevel.NEVER -> PermissionLevel.ALWAYS
        }
        setCapabilityLevel(capability, next)
    }

    fun getAllRuntimePermissionsToRequest(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return perms.toTypedArray()
    }

    fun buildSettingsIntent(item: PermissionStatusItem): Intent {
        val pkgUri = Uri.parse("package:${context.packageName}")
        return when (item.id) {
            "a11y_service" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notif_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "overlay_hud" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri)
            "battery_ignore" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)
            "write_settings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri)
            "dnd_policy" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            "usage_stats" -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun refreshStatus() {
        val unrestricted = isUnrestrictedMode()
        val a11yGranted = isAccessibilityEnabled()
        val notifListenerGranted = isNotificationListenerEnabled()
        val overlayGranted = Settings.canDrawOverlays(context)
        val batteryGranted = isIgnoringBatteryOptimizations()
        val writeSettingsGranted = Settings.System.canWrite(context)
        val dndGranted = isNotificationPolicyGranted()
        val usageStatsGranted = isUsageStatsGranted()
        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true

        val items = listOf(
            // TIER 1
            PermissionStatusItem(
                id = "a11y_service",
                title = "접근성 서비스 (Hanson × Artemis Engine)",
                permissionCode = "BIND_ACCESSIBILITY_SERVICE",
                tier = PermissionTier.TIER_1_ACCESSIBILITY,
                description = "멀티윈도우 UI 계층 트리 스캔, 아토믹 스크린샷 캡처, 정밀 제스처 주입(Tap/Swipe/Burst) 및 하드웨어 키 제어",
                apiNote = "AccessibilityService · getWindows() · takeScreenshot() · dispatchGesture()",
                isGranted = a11yGranted,
                settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                capability = AgentCapability.GUI_AUTOMATION,
                governanceLevel = getCapabilityLevel(AgentCapability.GUI_AUTOMATION)
            ),
            // TIER 2
            PermissionStatusItem(
                id = "notif_listener",
                title = "알림 가로채기 및 화면 꺼짐 0.2초 답장",
                permissionCode = "BIND_NOTIFICATION_LISTENER_SERVICE",
                tier = PermissionTier.TIER_2_BACKGROUND_OVERLAY,
                description = "화면이 꺼진 상태에서도 카카오톡·SMS·텔레그램·슬랙 알림을 즉시 수신하고 RemoteInput 토큰으로 백그라운드 답장 전송",
                apiNote = "NotificationListenerService · RemoteInput.addResultsToIntent()",
                isGranted = notifListenerGranted,
                settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
                capability = AgentCapability.NOTIFICATION_REPLY,
                governanceLevel = getCapabilityLevel(AgentCapability.NOTIFICATION_REPLY)
            ),
            PermissionStatusItem(
                id = "overlay_hud",
                title = "타 앱 위 플로팅 라이브 HUD 오버레이",
                permissionCode = "SYSTEM_ALERT_WINDOW",
                tier = PermissionTier.TIER_2_BACKGROUND_OVERLAY,
                description = "에이전트가 다른 앱을 제어할 때 화면 최상단에 실시간 진행 상태와 1-Tap 긴급 정지 버튼을 표시",
                apiNote = "WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY",
                isGranted = overlayGranted,
                settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            PermissionStatusItem(
                id = "battery_ignore",
                title = "배터리 최적화 제외 (Doze 면역)",
                permissionCode = "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                tier = PermissionTier.TIER_2_BACKGROUND_OVERLAY,
                description = "안드로이드 Doze 모드 및 OEM 백그라운드 프로세스 킬러로부터 에이전트 데몬을 영구 보호",
                apiNote = "PowerManager.isIgnoringBatteryOptimizations() · PARTIAL_WAKE_LOCK",
                isGranted = batteryGranted,
                settingsAction = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ),
            PermissionStatusItem(
                id = "post_notifications",
                title = "포그라운드 데몬 및 작업 영수증 알림",
                permissionCode = "POST_NOTIFICATIONS + FOREGROUND_SERVICE_SPECIAL_USE",
                tier = PermissionTier.TIER_2_BACKGROUND_OVERLAY,
                description = "상시 실행 데몬 상태 표시줄 알림 및 에이전트 긴급 정지 액션 버튼 표시",
                apiNote = "ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE",
                isGranted = postNotifGranted,
                isRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                runtimePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    listOf(Manifest.permission.POST_NOTIFICATIONS)
                } else emptyList()
            ),
            // TIER 3
            PermissionStatusItem(
                id = "query_all_packages",
                title = "설치된 모든 앱 패키지 무제한 탐색",
                permissionCode = "QUERY_ALL_PACKAGES",
                tier = PermissionTier.TIER_3_SYSTEM_OS,
                description = "플레이스토어 제한 없이 기기에 설치된 모든 앱의 이름·패키지명을 검색하여 즉시 실행",
                apiNote = "PackageManager.getInstalledApplications(0)",
                isGranted = true,
                capability = AgentCapability.APP_LAUNCH,
                governanceLevel = getCapabilityLevel(AgentCapability.APP_LAUNCH)
            ),
            PermissionStatusItem(
                id = "write_settings",
                title = "시스템 설정 직접 변경 (밝기·화면 타임아웃)",
                permissionCode = "WRITE_SETTINGS",
                tier = PermissionTier.TIER_3_SYSTEM_OS,
                description = "설정 앱을 열지 않고 화면 밝기(0~255), 자동 밝기 모드, 화면 꺼짐 시간을 즉시 제어",
                apiNote = "Settings.System.canWrite() · SCREEN_BRIGHTNESS",
                isGranted = writeSettingsGranted,
                settingsAction = Settings.ACTION_MANAGE_WRITE_SETTINGS,
                capability = AgentCapability.SYSTEM_SETTINGS,
                governanceLevel = getCapabilityLevel(AgentCapability.SYSTEM_SETTINGS)
            ),
            PermissionStatusItem(
                id = "dnd_policy",
                title = "방해금지 모드(DND) 및 오디오 볼륨 제어",
                permissionCode = "ACCESS_NOTIFICATION_POLICY + MODIFY_AUDIO_SETTINGS",
                tier = PermissionTier.TIER_3_SYSTEM_OS,
                description = "회의·수면·집중 모드 시 방해금지(DND) 필터 및 미디어/벨소리/알람 볼륨을 즉시 제어",
                apiNote = "NotificationManager.setInterruptionFilter() · AudioManager",
                isGranted = dndGranted,
                settingsAction = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
                capability = AgentCapability.SYSTEM_SETTINGS,
                governanceLevel = getCapabilityLevel(AgentCapability.SYSTEM_SETTINGS)
            ),
            PermissionStatusItem(
                id = "usage_stats",
                title = "앱 사용 통계 및 포그라운드 앱 정밀 추적",
                permissionCode = "PACKAGE_USAGE_STATS",
                tier = PermissionTier.TIER_3_SYSTEM_OS,
                description = "현재 실행 중인 앱과 최근 사용 앱 히스토리를 조회하여 에이전트 컨텍스트 정확도 향상",
                apiNote = "UsageStatsManager.queryEvents()",
                isGranted = usageStatsGranted,
                settingsAction = Settings.ACTION_USAGE_ACCESS_SETTINGS
            ),
            // TIER 4
            PermissionStatusItem(
                id = "audio_mic",
                title = "음성 명령 마이크 입력 (STT)",
                permissionCode = "RECORD_AUDIO",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "실시간 한국어 음성 인식(SpeechRecognizer)으로 손을 쓰지 않고 에이전트에 명령 전달",
                apiNote = "SpeechRecognizer · RecognizerIntent",
                isGranted = hasPerm(Manifest.permission.RECORD_AUDIO),
                isRuntimePermission = true,
                runtimePermissions = listOf(Manifest.permission.RECORD_AUDIO)
            ),
            PermissionStatusItem(
                id = "phone_call",
                title = "전화 즉시 발신",
                permissionCode = "CALL_PHONE",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "다이얼 화면에서 멈추지 않고 지정한 번호나 연락처로 즉시 전화를 겁니다",
                apiNote = "Intent.ACTION_CALL",
                isGranted = hasPerm(Manifest.permission.CALL_PHONE),
                isRuntimePermission = true,
                runtimePermissions = listOf(Manifest.permission.CALL_PHONE),
                capability = AgentCapability.PHONE_CALL,
                governanceLevel = getCapabilityLevel(AgentCapability.PHONE_CALL)
            ),
            PermissionStatusItem(
                id = "sms_messaging",
                title = "SMS 문자 메시지 송수신",
                permissionCode = "SEND_SMS + READ_SMS",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "백그라운드에서 SMS 문자를 즉시 전송하거나 최근 수신된 인증번호/문자를 확인합니다",
                apiNote = "SmsManager.sendTextMessage() · Telephony.Sms",
                isGranted = hasPerm(Manifest.permission.SEND_SMS) && hasPerm(Manifest.permission.READ_SMS),
                isRuntimePermission = true,
                runtimePermissions = listOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_SMS),
                capability = AgentCapability.SMS_SEND,
                governanceLevel = getCapabilityLevel(AgentCapability.SMS_SEND)
            ),
            PermissionStatusItem(
                id = "contacts_access",
                title = "연락처 이름·전화번호 검색 및 추가",
                permissionCode = "READ_CONTACTS + WRITE_CONTACTS",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "\"엄마에게 전화해줘\", \"김대리에게 문자 보내줘\" 실행 시 연락처 DB에서 번호를 즉시 조회",
                apiNote = "ContactsContract.CommonDataKinds.Phone",
                isGranted = hasPerm(Manifest.permission.READ_CONTACTS),
                isRuntimePermission = true,
                runtimePermissions = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
                capability = AgentCapability.CONTACTS_ACCESS,
                governanceLevel = getCapabilityLevel(AgentCapability.CONTACTS_ACCESS)
            ),
            PermissionStatusItem(
                id = "calendar_alarm",
                title = "캘린더 일정 관리 및 시스템 알람 설정",
                permissionCode = "READ_CALENDAR + WRITE_CALENDAR + SET_ALARM",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "기기 캘린더 일정 조회·등록 및 시스템 시계 알람/타이머 즉시 생성",
                apiNote = "CalendarContract.Events · AlarmClock.ACTION_SET_ALARM",
                isGranted = hasPerm(Manifest.permission.READ_CALENDAR) && hasPerm(Manifest.permission.WRITE_CALENDAR),
                isRuntimePermission = true,
                runtimePermissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                capability = AgentCapability.CALENDAR_ALARM,
                governanceLevel = getCapabilityLevel(AgentCapability.CALENDAR_ALARM)
            ),
            PermissionStatusItem(
                id = "camera_location",
                title = "카메라 및 정밀 위치(GPS) 센서",
                permissionCode = "CAMERA + ACCESS_FINE_LOCATION",
                tier = PermissionTier.TIER_4_SENSORS_TELEPHONY,
                description = "현장 시각 컨텍스트 및 현재 위치 기반 모바일 자동화 지원",
                apiNote = "LocationManager · CameraManager",
                isGranted = hasPerm(Manifest.permission.CAMERA) && hasPerm(Manifest.permission.ACCESS_FINE_LOCATION),
                isRuntimePermission = true,
                runtimePermissions = listOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            ),
            // TIER 5
            PermissionStatusItem(
                id = "artemis_loopback_bridge",
                title = "Google Artemis 로컬 루프백 브리지 (127.0.0.1:18888)",
                permissionCode = "INTERNET + LOCAL_LOOPBACK_RPC",
                tier = PermissionTier.TIER_5_BRIDGE_PRIVILEGED,
                description = "Google Artemis Python 에이전트, ADB, Termux, 로컬 MCP 서버가 HTTP/JSON-RPC로 즉시 제어 가능",
                apiNote = "GET /snapshot, /dump_xml · POST /action, /rpc",
                isGranted = true
            )
        )

        val granted = items.count { it.isGranted }
        _state.value = PermissionHubState(
            unrestrictedAutonomousMode = unrestricted,
            items = items,
            grantedCount = granted,
            totalCount = items.size
        )
    }

    private fun hasPerm(perm: String): Boolean {
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (HansonAccessibilityService.isServiceActive()) return true
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabledServices.contains(context.packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        if (RemoteInputSessionCache.listenerConnected.value) return true
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ).orEmpty()
        return flat.contains(context.packageName)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun isNotificationPolicyGranted(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.isNotificationPolicyAccessGranted
    }

    private fun isUsageStatsGranted(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }
}
