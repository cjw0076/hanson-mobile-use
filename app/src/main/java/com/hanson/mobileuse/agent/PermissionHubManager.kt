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
import java.util.Locale

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
    val totalCount: Int = 0,
    val isAccessibilityReady: Boolean = false,
    val isOverlayReady: Boolean = false
) {
    val readinessPercent: Int
        get() = if (totalCount == 0) 0 else ((grantedCount * 100f) / totalCount).toInt().coerceIn(0, 100)

    /**
     * Strict environment readiness for live GUI automation.
     * Requires an active connected AccessibilityService instance — never inferred from readinessPercent.
     */
    val isGuiAutomationReady: Boolean
        get() = isAccessibilityReady
}

data class OneTouchSetupResult(
    val accessibilityReady: Boolean,
    val overlayReady: Boolean,
    val openedSettingsAction: String? = null,
    val openedSettingsSuccess: Boolean = false,
    val messageKo: String = ""
)

/**
 * 3-tier risk classification absorbed from `Sanuu7/AndroidHarness` (`DeviceAccessManager.kt`):
 * - [READ_ONLY_QUIET]: Passive inspection commands executed quietly without interrupting chat.
 * - [PER_CHAT_CONSENT]: Mutating UI/app/workspace controls requiring one-time per-chat approval.
 * - [DESTRUCTIVE_ASK_EVERY_TIME]: Irreversible financial, telephony, uninstall, reset, or shell-risk
 *   commands that ALWAYS require explicit user confirmation regardless of session consent.
 */
enum class PhoneControlTier(
    val labelKo: String,
    val descriptionKo: String
) {
    READ_ONLY_QUIET(
        "읽기 전용 (자동 허용)",
        "화면 조회·앱 목록·파일 읽기 등 상태 변경이 없는 조회 명령"
    ),
    PER_CHAT_CONSENT(
        "채팅 세션 승인",
        "터치·스와이프·앱 실행·파일 편집 등 세션당 1회 승인이 필요한 제어 명령"
    ),
    DESTRUCTIVE_ASK_EVERY_TIME(
        "매번 확인 필요 (고위험)",
        "삭제·결제·송금·전화·문자·초기화·권한 변경 등 비가역 고위험 명령"
    )
}

/**
 * Risk-tiered command classifier and prompt gate absorbed from `Sanuu7/AndroidHarness`.
 */
object PhoneControlGate {

    private val SHELL_METACHAR_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\]")

    private val DESTRUCTIVE_EXACT_OR_PREFIX: Set<String> = setOf(
        "rm", "rm -rf", "rmdir", "delete", "delete_file", "uninstall",
        "pm uninstall", "pm clear", "pm grant", "pm revoke", "pm disable",
        "wipe", "factory_reset", "reset", "format", "reboot", "shutdown",
        "settings put", "settings delete", "su", "sudo", "sh", "bash",
        "call_phone", "make_call", "dial_call", "send_sms", "send_email",
        "transfer", "wire", "pay", "checkout", "purchase", "buy", "buy_now",
        "cancel_subscription", "deactivate", "clear_data", "force_stop_all"
    )

    private val DESTRUCTIVE_SUBSTRINGS: Set<String> = setOf(
        "rm -", "uninstall", "factory_reset", "factory reset", "wipe_data",
        "pm clear", "pm uninstall", "pm grant", "pm revoke",
        "call_phone", "send_sms", "wire_transfer", "bank_transfer",
        "결제", "송금", "이체", "회원탈퇴", "계정 삭제", "공장 초기화",
        "초기화", "앱 삭제", "영구 삭제", "전화 발신", "문자 발송"
    )

    private val READ_ONLY_COMMANDS: Set<String> = setOf(
        "snapshot", "dump", "dump_xml", "elements", "status", "ping", "health",
        "get_state", "portal_state", "portal/state", "portal/ping",
        "list_apps", "list_files", "read_file", "search_files", "grep", "cat",
        "ls", "tree", "wc", "pwd", "diff", "logcat", "top", "preflight",
        "screenshot", "get_notifications", "read_notifications", "list_notifications",
        "discover_deeplinks", "resolve_deeplink", "resolve_deeplink_dry_run",
        "check_conflicts", "wm size", "wm density", "settings get", "getprop",
        "whoami", "id", "wait_stable", "wait_for_text", "repo_map"
    )

    /**
     * Classifies [command] into one of the three [PhoneControlTier] risk levels.
     * Fails closed to [PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME] on shell metacharacters or
     * destructive/financial/telephony keywords, and defaults unknown non-empty commands to
     * [PhoneControlTier.PER_CHAT_CONSENT].
     */
    fun classifyCommandTier(command: String): PhoneControlTier {
        val raw = command.trim()
        if (raw.isEmpty()) return PhoneControlTier.READ_ONLY_QUIET
        if (SHELL_METACHAR_REGEX.containsMatchIn(raw)) {
            return PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME
        }

        val lower = raw.lowercase(Locale.ROOT)
        val normalized = lower.replace(Regex("\\s+"), " ")
        val firstToken = normalized.substringBefore(' ').trimStart('/')
        val firstTwoTokens = normalized.split(' ').take(2).joinToString(" ").trimStart('/')

        if (firstToken in DESTRUCTIVE_EXACT_OR_PREFIX ||
            firstTwoTokens in DESTRUCTIVE_EXACT_OR_PREFIX ||
            DESTRUCTIVE_SUBSTRINGS.any { token -> normalized.contains(token) }
        ) {
            return PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME
        }

        if (firstToken in READ_ONLY_COMMANDS ||
            firstTwoTokens in READ_ONLY_COMMANDS ||
            normalized.trimStart('/') in READ_ONLY_COMMANDS
        ) {
            return PhoneControlTier.READ_ONLY_QUIET
        }

        return PhoneControlTier.PER_CHAT_CONSENT
    }

    /**
     * Determines whether executing an action of [tier] requires prompting the user given
     * [sessionApproved] (whether the user already approved phone control in the current chat).
     */
    fun requiresPrompt(tier: PhoneControlTier, sessionApproved: Boolean): Boolean = when (tier) {
        PhoneControlTier.READ_ONLY_QUIET -> false
        PhoneControlTier.PER_CHAT_CONSENT -> !sessionApproved
        PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME -> true
    }

    fun requiresPrompt(command: String, sessionApproved: Boolean): Boolean =
        requiresPrompt(classifyCommandTier(command), sessionApproved)

    fun explainDecisionKo(command: String, sessionApproved: Boolean): String {
        val tier = classifyCommandTier(command)
        val needPrompt = requiresPrompt(tier, sessionApproved)
        return when {
            tier == PhoneControlTier.READ_ONLY_QUIET ->
                "읽기 전용 작업이므로 사용자 확인 없이 조용히 실행할 수 있습니다."
            tier == PhoneControlTier.DESTRUCTIVE_ASK_EVERY_TIME ->
                "고위험·비가역 작업(${tier.labelKo})으로 분류되어 매번 사용자 승인이 필요합니다."
            needPrompt ->
                "기기 제어 작업(${tier.labelKo})이므로 현재 채팅 세션의 1회 승인이 필요합니다."
            else ->
                "현재 채팅 세션에서 기기 제어가 이미 승인되어 즉시 실행할 수 있습니다."
        }
    }
}

enum class WirelessAdbPairPhase(val labelKo: String) {
    IDLE("대기"),
    DISCOVERING_MDNS("무선 디버깅 mDNS 탐색 중"),
    AWAITING_PAIR_CODE("6자리 페어링 코드 입력 대기"),
    PAIRING("로컬 무선 ADB 페어링 준비 완료"),
    PAIRED_READY("무선 ADB 연결 준비 완료"),
    FAILED("입력 검증 실패")
}

data class WirelessAdbPairValidation(
    val valid: Boolean,
    val normalizedHost: String = "",
    val port: Int = -1,
    val sanitizedPairingCode: String = "",
    val errorKo: String = ""
)

/**
 * In-app Android 11+ Wireless Debugging self-pairing bootstrap state absorbed from
 * `Sanuu7/AndroidHarness` (`WirelessAdbManager.kt` + `AdbPairingClient.kt` + `AdbMdns.kt`),
 * eliminating the external PC cable or third-party Shizuku app requirement.
 */
data class WirelessAdbSelfPairState(
    val phase: WirelessAdbPairPhase = WirelessAdbPairPhase.IDLE,
    val endpoint: String = "",
    val serviceType: String = WirelessAdbSelfPairHelper.MDNS_PAIRING_SERVICE_TYPE,
    val pairingCodeValid: Boolean = false,
    val endpointValid: Boolean = false,
    val pairedHostPort: String? = null,
    val guideStepsKo: List<String> = WirelessAdbSelfPairHelper.DEFAULT_BOOTSTRAP_STEPS_KO,
    val messageKo: String = ""
)

/**
 * Pure JVM-testable validator & bootstrap guide for Android 11+ Wireless ADB self-pairing.
 * Strictly restricts endpoints to loopback (`127.0.0.0/8`, `localhost`, `[::1]`) or RFC1918
 * private LAN subnets (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), refusing all
 * public/routable IPs, arbitrary hostnames, privileged ports (<1024), and shell metacharacters.
 */
object WirelessAdbSelfPairHelper {

    const val MDNS_PAIRING_SERVICE_TYPE: String = "_adb-tls-pairing._tcp"
    const val MDNS_CONNECT_SERVICE_TYPE: String = "_adb-tls-connect._tcp"
    const val MIN_DYNAMIC_PORT: Int = 1024
    const val MAX_PORT: Int = 65535

    private val SHELL_METACHAR_OR_SPACE_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\/\\s]")
    private val SIX_DIGIT_CODE_REGEX = Regex("^\\d{6}$")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    val DEFAULT_BOOTSTRAP_STEPS_KO: List<String> = listOf(
        "1. 설정 > 개발자 옵션 > 무선 디버깅을 켠 뒤 '페어링 코드로 기기 페어링'을 눌러 주세요.",
        "2. 화면을 분할하거나 알림창에서 IP 주소 및 포트(예: 192.168.0.10:37123)와 6자리 코드를 확인하세요.",
        "3. Hanson 앱에 로컬/LAN 엔드포인트와 6자리 페어링 코드를 입력하면 PC·Shizuku 없이 자체 부트스트랩합니다.",
        "4. 보안상 공인 IP나 외부 도메인은 자동 차단되며, 기기 내부(127.0.0.1) 및 사설 LAN 대역만 허용됩니다."
    )

    /**
     * Validates a 6-digit Android Wireless Debugging pairing code, rejecting shell metacharacters
     * and non-numeric input.
     */
    fun validatePairingCode(pairingCode: String): Pair<Boolean, String> {
        if (pairingCode.isEmpty() || SHELL_METACHAR_OR_SPACE_REGEX.containsMatchIn(pairingCode.trim())) {
            return false to "페어링 코드는 공백이나 특수문자 없이 6자리 숫자여야 합니다."
        }
        val trimmed = pairingCode.trim()
        if (!SIX_DIGIT_CODE_REGEX.matches(trimmed)) {
            return false to "무선 디버깅 페어링 코드는 정확히 6자리 숫자(000000~999999)여야 합니다."
        }
        return true to trimmed
    }

    /**
     * Checks whether [host] is strictly a loopback or RFC1918 private LAN address without
     * performing any DNS resolution.
     */
    fun isPrivateOrLoopbackHost(host: String): Boolean {
        val h = host.trim().lowercase(Locale.ROOT)
        if (h == "localhost" || h == "::1") return true

        val match = IPV4_REGEX.matchEntire(h) ?: return false
        val o1 = match.groupValues[1].toIntOrNull() ?: return false
        val o2 = match.groupValues[2].toIntOrNull() ?: return false
        val o3 = match.groupValues[3].toIntOrNull() ?: return false
        val o4 = match.groupValues[4].toIntOrNull() ?: return false
        if (o1 !in 0..255 || o2 !in 0..255 || o3 !in 0..255 || o4 !in 0..255) return false

        // Reject unspecified 0.x.x.x, link-local 169.254.x.x, broadcast 255.255.255.255, etc.
        if (o1 == 127) return true                              // 127.0.0.0/8 loopback
        if (o1 == 10) return true                               // 10.0.0.0/8 RFC1918
        if (o1 == 172 && o2 in 16..31) return true              // 172.16.0.0/12 RFC1918
        if (o1 == 192 && o2 == 168) return true                 // 192.168.0.0/16 RFC1918
        return false
    }

    /**
     * Validates an mDNS/Wireless Debugging `host:port` endpoint strictly on loopback or RFC1918
     * private LAN subnets with a non-privileged port (`1024..65535`).
     */
    fun validateMdnsEndpoint(hostPort: String): Pair<Boolean, String> {
        val trimmed = hostPort.trim()
        if (trimmed.isEmpty()) {
            return false to "무선 디버깅 엔드포인트(host:port)를 입력해 주세요."
        }
        if (SHELL_METACHAR_OR_SPACE_REGEX.containsMatchIn(trimmed)) {
            return false to "보안 정책: 엔드포인트에 셸 메타문자 또는 공백이 포함되어 차단되었습니다."
        }

        val host: String
        val portStr: String
        if (trimmed.startsWith("[")) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket <= 1 || closeBracket + 2 > trimmed.length || trimmed[closeBracket + 1] != ':') {
                return false to "IPv6 엔드포인트 형식이 올바르지 않습니다 (예: [::1]:37123)."
            }
            host = trimmed.substring(1, closeBracket)
            portStr = trimmed.substring(closeBracket + 2)
        } else {
            val colonIdx = trimmed.lastIndexOf(':')
            if (colonIdx <= 0 || colonIdx != trimmed.indexOf(':')) {
                return false to "엔드포인트는 host:port 형식이어야 합니다 (예: 192.168.0.10:37123)."
            }
            host = trimmed.substring(0, colonIdx)
            portStr = trimmed.substring(colonIdx + 1)
        }

        if (!isPrivateOrLoopbackHost(host)) {
            return false to "보안 정책: 공인/외부 IP 및 임의 도메인('$host')은 차단됩니다. 루프백(127.0.0.1) 또는 사설 LAN(RFC1918)만 허용됩니다."
        }

        val port = portStr.toIntOrNull()
        if (port == null || port !in MIN_DYNAMIC_PORT..MAX_PORT) {
            return false to "포트 번호는 $MIN_DYNAMIC_PORT~$MAX_PORT 범위여야 합니다 (입력: '$portStr')."
        }

        val normalized = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return true to normalized
    }

    fun validatePairingRequest(hostPort: String, pairingCode: String): WirelessAdbPairValidation {
        val (endpointOk, endpointOut) = validateMdnsEndpoint(hostPort)
        if (!endpointOk) {
            return WirelessAdbPairValidation(valid = false, errorKo = endpointOut)
        }
        val (codeOk, codeOut) = validatePairingCode(pairingCode)
        if (!codeOk) {
            return WirelessAdbPairValidation(valid = false, errorKo = codeOut)
        }
        val lastColon = endpointOut.lastIndexOf(':')
        val hostPart = endpointOut.substring(0, lastColon).removePrefix("[").removeSuffix("]")
        val portPart = endpointOut.substring(lastColon + 1).toInt()
        return WirelessAdbPairValidation(
            valid = true,
            normalizedHost = hostPart,
            port = portPart,
            sanitizedPairingCode = codeOut
        )
    }

    fun buildBootstrapState(hostPort: String, pairingCode: String): WirelessAdbSelfPairState {
        val (endpointOk, endpointMsg) = validateMdnsEndpoint(hostPort)
        val (codeOk, codeMsg) = validatePairingCode(pairingCode)
        return when {
            endpointOk && codeOk -> WirelessAdbSelfPairState(
                phase = WirelessAdbPairPhase.PAIRING,
                endpoint = endpointMsg,
                pairingCodeValid = true,
                endpointValid = true,
                pairedHostPort = endpointMsg,
                messageKo = "로컬 무선 디버깅($endpointMsg) 페어링 파라미터 검증 완료. Android 11+ 무선 디버깅 TLS 페어링을 진행할 수 있습니다."
            )
            endpointOk && !codeOk -> WirelessAdbSelfPairState(
                phase = WirelessAdbPairPhase.AWAITING_PAIR_CODE,
                endpoint = endpointMsg,
                pairingCodeValid = false,
                endpointValid = true,
                messageKo = codeMsg
            )
            else -> WirelessAdbSelfPairState(
                phase = WirelessAdbPairPhase.FAILED,
                endpoint = hostPort.trim(),
                pairingCodeValid = codeOk,
                endpointValid = false,
                messageKo = endpointMsg
            )
        }
    }

    fun buildWirelessDebuggingSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
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

    private val _wirelessAdbState = MutableStateFlow(WirelessAdbSelfPairState())
    val wirelessAdbState: StateFlow<WirelessAdbSelfPairState> = _wirelessAdbState.asStateFlow()

    init {
        refreshStatus()
    }

    /**
     * Evaluates a device/CLI command through the 3-tier [PhoneControlGate].
     * Returns `(tier, requiresUserPrompt)`.
     */
    fun evaluatePhoneControlGate(
        command: String,
        sessionApproved: Boolean
    ): Pair<PhoneControlTier, Boolean> {
        val tier = PhoneControlGate.classifyCommandTier(command)
        return tier to PhoneControlGate.requiresPrompt(tier, sessionApproved)
    }

    /**
     * Validates and updates in-app Android 11+ Wireless Debugging self-pairing bootstrap state.
     */
    fun prepareWirelessAdbSelfPair(
        hostPort: String,
        pairingCode: String
    ): WirelessAdbSelfPairState {
        val next = WirelessAdbSelfPairHelper.buildBootstrapState(hostPort, pairingCode)
        _wirelessAdbState.value = next
        return next
    }

    @JvmOverloads
    fun openWirelessDebuggingSettings(launcherContext: Context = context): Boolean {
        return runCatching {
            launcherContext.startActivity(WirelessAdbSelfPairHelper.buildWirelessDebuggingSettingsIntent())
            true
        }.getOrDefault(false)
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
        val a11yGranted = isAccessibilityReady()
        val notifListenerGranted = isNotificationListenerEnabled()
        val overlayGranted = isOverlayReady()
        val batteryGranted = isIgnoringBatteryOptimizations()
        val writeSettingsGranted = runCatching { Settings.System.canWrite(context) }.getOrDefault(false)
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
                isGranted = a11yGranted && HansonAccessibilityService.engineState.value.bridgeRunning
            )
        )

        val granted = items.count { it.isGranted }
        _state.value = PermissionHubState(
            unrestrictedAutonomousMode = unrestricted,
            items = items,
            grantedCount = granted,
            totalCount = items.size,
            isAccessibilityReady = a11yGranted,
            isOverlayReady = overlayGranted
        )
    }

    /**
     * Strict accessibility readiness check (P1-5):
     * Requires BOTH `HansonAccessibilityService.engineState.value.isConnected` AND a live non-null
     * `HansonAccessibilityService.instance`. NEVER fakes "연결됨" via `readinessPercent >= 80`
     * or stale `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` string fallback.
     */
    fun isAccessibilityReady(): Boolean {
        val engineState = HansonAccessibilityService.engineState.value
        return engineState.isConnected && HansonAccessibilityService.instance != null
    }

    /**
     * Diagnostic-only check for whether the app appears in Settings.Secure accessibility list.
     * Never used to report active connection readiness.
     */
    fun isAccessibilityRegisteredInSettings(): Boolean {
        return runCatching {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            enabledServices.contains(context.packageName)
        }.getOrDefault(false)
    }

    fun isOverlayReady(): Boolean {
        return runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }

    /**
     * Returns the next required Android system settings Intent when critical GUI permissions
     * (Accessibility or Draw-Over-Apps Overlay) are missing, or `null` when both are granted.
     */
    fun buildNextMissingSystemSetupIntent(): Intent? {
        if (!isAccessibilityReady()) {
            return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        if (!isOverlayReady()) {
            return Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return null
    }

    @JvmOverloads
    fun openAccessibilitySettings(launcherContext: Context = context): Boolean {
        return runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launcherContext.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    @JvmOverloads
    fun openOverlayPermissionSettings(launcherContext: Context = context): Boolean {
        val withPkgUri = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { launcherContext.startActivity(withPkgUri); true }.getOrDefault(false)) {
            return true
        }
        val genericOverlay = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { launcherContext.startActivity(genericOverlay); true }.getOrDefault(false)
    }

    /**
     * One-Touch Setup (P1-5):
     * Enables unrestricted mode, refreshes live permission status, and whenever Accessibility or
     * Overlay permission is missing, cleanly opens `Settings.ACTION_ACCESSIBILITY_SETTINGS` or
     * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` instead of silently doing nothing.
     */
    @JvmOverloads
    fun triggerOneTouchSetup(
        launcherContext: Context = context,
        openMissingSettings: Boolean = true
    ): OneTouchSetupResult {
        setUnrestrictedMode(true)
        val a11yReady = isAccessibilityReady()
        val overlayReady = isOverlayReady()

        if (!a11yReady) {
            val opened = if (openMissingSettings) openAccessibilitySettings(launcherContext) else false
            val extraOverlayNote = if (!overlayReady) " 이후 다른 앱 위에 표시(오버레이) 권한도 허용해 주세요." else ""
            val msg = if (opened) {
                "접근성 서비스가 아직 연결되지 않아 시스템 접근성 설정 화면을 열었어요. 'Hanson'을 활성화해 주세요.$extraOverlayNote"
            } else {
                "접근성 서비스가 꺼져 있어요. 설정 > 접근성 > 설치된 앱에서 'Hanson'을 활성화해 주세요.$extraOverlayNote"
            }
            return OneTouchSetupResult(
                accessibilityReady = false,
                overlayReady = overlayReady,
                openedSettingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
                openedSettingsSuccess = opened,
                messageKo = msg
            )
        }

        if (!overlayReady) {
            val opened = if (openMissingSettings) openOverlayPermissionSettings(launcherContext) else false
            val msg = if (opened) {
                "타 앱 위 플로팅 HUD(오버레이) 권한이 꺼져 있어 설정 화면을 열었어요. 'Hanson'을 허용해 주세요."
            } else {
                "타 앱 위 플로팅 HUD(오버레이) 권한이 필요해요. 시스템 설정에서 '다른 앱 위에 표시'를 허용해 주세요."
            }
            return OneTouchSetupResult(
                accessibilityReady = true,
                overlayReady = false,
                openedSettingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                openedSettingsSuccess = opened,
                messageKo = msg
            )
        }

        return OneTouchSetupResult(
            accessibilityReady = true,
            overlayReady = true,
            openedSettingsAction = null,
            openedSettingsSuccess = false,
            messageKo = "접근성 서비스와 오버레이 권한이 모두 정상 연결되어 있어요."
        )
    }

    private fun hasPerm(perm: String): Boolean {
        return runCatching {
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    private fun isAccessibilityEnabled(): Boolean = isAccessibilityReady()

    private fun isNotificationListenerEnabled(): Boolean {
        if (RemoteInputSessionCache.listenerConnected.value) return true
        return runCatching {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ).orEmpty()
            flat.contains(context.packageName)
        }.getOrDefault(false)
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
    }

    private fun isNotificationPolicyGranted(): Boolean {
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            nm.isNotificationPolicyAccessGranted
        }.getOrDefault(false)
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
