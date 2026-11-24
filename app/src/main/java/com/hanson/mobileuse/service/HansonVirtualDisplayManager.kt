package com.hanson.mobileuse.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.util.Log
import com.hanson.mobileuse.agent.SensitiveScreenRiskLevel
import com.hanson.mobileuse.agent.UntrustedScreenDataGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

data class VirtualDisplayStatus(
    val active: Boolean = false,
    val displayId: Int = -1,
    val width: Int = 1080,
    val height: Int = 1920,
    val dpi: Int = 320,
    val lastCaptureTimestampMs: Long = 0L
)

/**
 * 3-state execution mode absorbed from `AcidGr/dsh-preset-mobile-use`.
 */
enum class VirtualDisplayMode(val labelKo: String) {
    FOREGROUND("포그라운드 제어"),
    BACKGROUND("백그라운드 가상 디스플레이"),
    IDLE("대기")
}

/**
 * Live Visual "Watch & Take Over" state machine (`ChatGPT Dots` pattern).
 */
enum class VirtualDisplayTakeoverState(val labelKo: String) {
    IDLE("대기"),
    RUNNING("에이전트 실행 중"),
    AWAITING_USER_TAKEOVER("사용자 직접 조작 대기"),
    RETURNED_CONTROL("제어권 반환 완료")
}

/**
 * Mutual-exclusion lease preventing concurrent crew/agent VM collisions (`Grok Bot` collision fix).
 */
data class VirtualDisplayLease(
    val ownerId: String,
    val acquiredAtMs: Long,
    val expiresAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/**
 * Unified UI snapshot stream consumed by Compose Live Visual "Watch & Take Over" PiP cards
 * and bridge telemetry endpoints.
 */
data class VirtualDisplayUiSnapshot(
    val status: VirtualDisplayStatus = VirtualDisplayStatus(),
    val mode: VirtualDisplayMode = VirtualDisplayMode.IDLE,
    val takeoverState: VirtualDisplayTakeoverState = VirtualDisplayTakeoverState.IDLE,
    val leaseOwner: String? = null,
    val latestFrameBase64: String? = null,
    val reasonKo: String = "",
    val lastLaunchedPackage: String? = null,
    val updatedAtMs: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("active", status.active)
        put("displayId", status.displayId)
        put("width", status.width)
        put("height", status.height)
        put("dpi", status.dpi)
        put("lastCaptureTimestampMs", status.lastCaptureTimestampMs)
        put("mode", mode.name)
        put("takeoverState", takeoverState.name)
        put("leaseOwner", leaseOwner ?: JSONObject.NULL)
        put("hasFrame", !latestFrameBase64.isNullOrBlank())
        put("reasonKo", reasonKo)
        put("lastLaunchedPackage", lastLaunchedPackage ?: JSONObject.NULL)
        put("updatedAtMs", updatedAtMs)
    }
}

/**
 * Background VirtualDisplay Engine absorbed and security-hardened from `PhoneHarness/PhoneHarness`
 * (`VDService.java`), `AcidGr/dsh-preset-mobile-use` (3-state mode switching + display launch/input),
 * `ChatGPT Dots` (Live Visual Watch & Take Over), and `Grok Bot` (lease lock anti-collision).
 *
 * Security Hardening:
 * - NEVER writes display IDs or screenshots to world-readable `/sdcard/`.
 * - Captures exclusively into in-memory PNG bytes or app-private `context.cacheDir/vdisplay/`.
 * - Strictly refuses launching denylisted banking, fintech, wallet, or password-manager packages.
 * - Rejects shell metacharacters, invalid coordinates, and out-of-bounds inputs; never uses `sh -c`.
 * - Suspends automated input injection whenever [VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER] is active.
 */
object HansonVirtualDisplayManager {

    private const val TAG = "HansonVirtualDisplay"
    private const val MIN_LEASE_TTL_MS = 1_000L
    private const val MAX_LEASE_TTL_MS = 600_000L
    private const val DEFAULT_LEASE_TTL_MS = 60_000L

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val KEYEVENT_REGEX = Regex("^[A-Z0-9_]{1,48}$")
    private val SHELL_METACHAR_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\]")
    private val ALLOWED_INPUT_ACTIONS = setOf("tap", "swipe", "long_press", "text", "keyevent")

    /** Explicit package prefixes blocked from automated VirtualDisplay launch (fail-closed banking hardline). */
    private val BLOCKED_FINANCIAL_PACKAGE_PREFIXES: Set<String> = setOf(
        "com.kakaobank",
        "viva.republica.toss",
        "com.kbstar",
        "com.shinhan",
        "com.wooribank",
        "com.hanabank",
        "com.hanaskcard",
        "nh.smart",
        "com.kbankwith",
        "com.ibk.android",
        "com.samsung.android.spay",
        "com.samsung.android.samsungpay",
        "com.google.android.apps.walletnfcrel",
        "com.agilebits.onepassword",
        "com.x8bit.bitwarden",
        "com.lastpass",
        "com.dashlane",
        "com.coinbase",
        "com.dunamu.exchange",
        "com.btckorea.bithumb",
        "com.paypal",
        "com.venmo",
        "com.chase",
        "com.infonow.bofa",
        "com.wf.wellsfargomobile",
        "com.citibank",
        "com.sktelecom.tauth",
        "com.kt.ktauth",
        "com.lguplus.smartotp"
    )

    private val lock = Any()

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var activeLease: VirtualDisplayLease? = null

    private val _status = MutableStateFlow(VirtualDisplayStatus())
    val status: StateFlow<VirtualDisplayStatus> = _status.asStateFlow()

    private val _uiSnapshot = MutableStateFlow(VirtualDisplayUiSnapshot())
    val uiSnapshot: StateFlow<VirtualDisplayUiSnapshot> = _uiSnapshot.asStateFlow()

    fun start(
        context: Context,
        width: Int = 1080,
        height: Int = 1920,
        dpi: Int = 320
    ): VirtualDisplayStatus {
        synchronized(lock) {
            if (virtualDisplay != null && _status.value.active) {
                return _status.value
            }
            val safeW = width.coerceIn(360, 1440)
            val safeH = height.coerceIn(640, 3200)
            val safeDpi = dpi.coerceIn(160, 560)

            return try {
                val reader = ImageReader.newInstance(safeW, safeH, PixelFormat.RGBA_8888, 2)
                reader.setOnImageAvailableListener({ /* frames polled on demand */ }, null)
                val dm = context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val vd = dm.createVirtualDisplay(
                    "hanson-bg-display",
                    safeW,
                    safeH,
                    safeDpi,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                )
                if (vd == null) {
                    reader.close()
                    Log.e(TAG, "DisplayManager.createVirtualDisplay returned null")
                    return VirtualDisplayStatus(active = false)
                }
                imageReader = reader
                virtualDisplay = vd
                val id = vd.display.displayId
                val next = VirtualDisplayStatus(
                    active = true,
                    displayId = id,
                    width = safeW,
                    height = safeH,
                    dpi = safeDpi
                )
                _status.value = next
                updateUiSnapshotLocked(
                    status = next,
                    mode = VirtualDisplayMode.BACKGROUND,
                    takeoverState = if (_uiSnapshot.value.takeoverState == VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER) {
                        VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER
                    } else {
                        VirtualDisplayTakeoverState.RUNNING
                    }
                )
                Log.i(TAG, "Created background VirtualDisplay id=$id (${safeW}x${safeH}@${safeDpi}dpi)")
                next
            } catch (e: Exception) {
                Log.e(TAG, "Failed creating VirtualDisplay: ${e.message}", e)
                stopLocked()
                VirtualDisplayStatus(active = false)
            }
        }
    }

    /**
     * Acquires or renews a mutual-exclusion lease for [ownerId], preventing concurrent crew/agent
     * VM collisions. Fails closed if another owner holds an unexpired lease.
     */
    fun acquireLease(
        ownerId: String,
        ttlMs: Long = DEFAULT_LEASE_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> {
        val cleanOwner = ownerId.trim()
        if (cleanOwner.isEmpty() || cleanOwner.length > 96 || SHELL_METACHAR_REGEX.containsMatchIn(cleanOwner)) {
            return false to "유효하지 않은 VirtualDisplay 리스 소유자 ID입니다."
        }
        val clampedTtl = ttlMs.coerceIn(MIN_LEASE_TTL_MS, MAX_LEASE_TTL_MS)
        synchronized(lock) {
            val current = activeLease
            if (current != null && !current.isExpired(nowMs) && current.ownerId != cleanOwner) {
                val remainingMs = (current.expiresAtMs - nowMs).coerceAtLeast(0L)
                return false to "가상 디스플레이 리스가 이미 '${current.ownerId}'에 점유되어 있습니다 (남은 시간: ${remainingMs}ms)."
            }
            val nextLease = VirtualDisplayLease(
                ownerId = cleanOwner,
                acquiredAtMs = nowMs,
                expiresAtMs = nowMs + clampedTtl
            )
            activeLease = nextLease
            updateUiSnapshotLocked(leaseOwner = cleanOwner, nowMs = nowMs)
            return true to "가상 디스플레이 리스 확보 완료: $cleanOwner"
        }
    }

    /**
     * Releases the lease if owned by [ownerId] (or already expired). Refuses releasing another
     * owner's active lease.
     */
    fun releaseLease(
        ownerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val cleanOwner = ownerId.trim()
        synchronized(lock) {
            val current = activeLease ?: return false
            if (current.isExpired(nowMs)) {
                activeLease = null
                updateUiSnapshotLocked(leaseOwner = null, nowMs = nowMs)
                return true
            }
            if (current.ownerId != cleanOwner) {
                return false
            }
            activeLease = null
            updateUiSnapshotLocked(leaseOwner = null, nowMs = nowMs)
            return true
        }
    }

    fun currentLeaseOwner(nowMs: Long = System.currentTimeMillis()): String? {
        synchronized(lock) {
            val current = activeLease ?: return null
            if (current.isExpired(nowMs)) {
                activeLease = null
                updateUiSnapshotLocked(leaseOwner = null, nowMs = nowMs)
                return null
            }
            return current.ownerId
        }
    }

    fun currentLease(nowMs: Long = System.currentTimeMillis()): VirtualDisplayLease? {
        synchronized(lock) {
            val current = activeLease ?: return null
            if (current.isExpired(nowMs)) {
                activeLease = null
                updateUiSnapshotLocked(leaseOwner = null, nowMs = nowMs)
                return null
            }
            return current
        }
    }

    /**
     * Switches between [VirtualDisplayMode.FOREGROUND], [VirtualDisplayMode.BACKGROUND],
     * and [VirtualDisplayMode.IDLE] while preserving any active user takeover hold.
     */
    fun switchMode(mode: VirtualDisplayMode): VirtualDisplayUiSnapshot {
        synchronized(lock) {
            val cur = _uiSnapshot.value
            val nextTakeover = when (mode) {
                VirtualDisplayMode.IDLE -> VirtualDisplayTakeoverState.IDLE
                VirtualDisplayMode.FOREGROUND, VirtualDisplayMode.BACKGROUND -> {
                    when (cur.takeoverState) {
                        VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER ->
                            VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER
                        VirtualDisplayTakeoverState.RETURNED_CONTROL ->
                            VirtualDisplayTakeoverState.RUNNING
                        else -> VirtualDisplayTakeoverState.RUNNING
                    }
                }
            }
            val nextReason = if (mode == VirtualDisplayMode.IDLE) "" else cur.reasonKo
            return updateUiSnapshotLocked(
                mode = mode,
                takeoverState = nextTakeover,
                reasonKo = nextReason
            )
        }
    }

    /**
     * Pauses autonomous input injection and surfaces a Live Visual "Take Over" prompt to the user.
     */
    fun requestUserTakeover(reasonKo: String): VirtualDisplayUiSnapshot {
        val cleanReason = reasonKo.trim().ifBlank { "사용자 직접 확인 또는 조작이 필요합니다." }
        synchronized(lock) {
            return updateUiSnapshotLocked(
                takeoverState = VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER,
                reasonKo = cleanReason
            )
        }
    }

    /**
     * Signals that the user finished manual intervention and returned control to the agent.
     */
    fun returnControlFromUser(): VirtualDisplayUiSnapshot {
        synchronized(lock) {
            return updateUiSnapshotLocked(
                takeoverState = VirtualDisplayTakeoverState.RETURNED_CONTROL,
                reasonKo = "사용자가 제어권을 에이전트로 반환했습니다."
            )
        }
    }

    /**
     * Transitions [VirtualDisplayTakeoverState.RETURNED_CONTROL] back into active [VirtualDisplayTakeoverState.RUNNING].
     */
    fun resumeAgentAfterTakeover(): VirtualDisplayUiSnapshot {
        synchronized(lock) {
            val cur = _uiSnapshot.value
            val nextMode = if (cur.mode == VirtualDisplayMode.IDLE) VirtualDisplayMode.BACKGROUND else cur.mode
            return updateUiSnapshotLocked(
                mode = nextMode,
                takeoverState = VirtualDisplayTakeoverState.RUNNING,
                reasonKo = ""
            )
        }
    }

    /**
     * Pure JVM-testable policy check refusing banking, fintech, wallet, and password-manager apps
     * from unattended VirtualDisplay execution.
     */
    fun isBlockedBankingOrSensitivePackage(packageName: String): Pair<Boolean, String> {
        val pkg = packageName.trim().lowercase(Locale.ROOT)
        if (pkg.isEmpty()) {
            return true to "패키지 이름이 비어 있습니다."
        }
        val prefixHit = BLOCKED_FINANCIAL_PACKAGE_PREFIXES.firstOrNull { prefix ->
            pkg == prefix || pkg.startsWith("$prefix.") || pkg.contains(prefix)
        }
        if (prefixHit != null) {
            return true to "보안 정책: 금융·결제·인증·비밀번호 앱('$packageName')은 가상 디스플레이 자동 실행이 차단되어 있습니다."
        }
        val preflight = UntrustedScreenDataGuard.evaluatePreflightSafety(
            packageName = packageName,
            visibleScreenText = ""
        )
        if (preflight.level == SensitiveScreenRiskLevel.DENYLISTED_APP) {
            return true to (preflight.reasonKo.ifBlank {
                "보안 정책: 민감한 금융·인증 앱('$packageName')은 가상 디스플레이 실행이 차단되어 있습니다."
            })
        }
        return false to ""
    }

    /**
     * Launches [packageName] onto the active VirtualDisplay via `ActivityOptions.setLaunchDisplayId`
     * (API 26+). Strictly refuses denylisted banking/sensitive packages, invalid package names,
     * user-takeover pauses, and inactive displays with honest error messages.
     */
    fun launchAppOnVirtualDisplay(
        context: Context?,
        packageName: String
    ): Pair<Boolean, String> {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || SHELL_METACHAR_REGEX.containsMatchIn(pkg) || !PACKAGE_NAME_REGEX.matches(pkg)) {
            return false to "유효하지 않은 안드로이드 패키지명입니다: $packageName"
        }

        val (blocked, blockReason) = isBlockedBankingOrSensitivePackage(pkg)
        if (blocked) {
            return false to blockReason
        }

        val st: VirtualDisplayStatus
        val takeover: VirtualDisplayTakeoverState
        synchronized(lock) {
            st = _status.value
            takeover = _uiSnapshot.value.takeoverState
        }

        if (takeover == VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER) {
            return false to "사용자 직접 조작(Take Over) 대기 중에는 가상 디스플레이 앱 실행이 일시 정지됩니다."
        }

        if (!st.active || st.displayId <= 0) {
            return false to "VirtualDisplay가 활성화되어 있지 않습니다 (displayId=${st.displayId}). 먼저 가상 디스플레이를 시작해 주세요."
        }

        if (context == null) {
            return false to "Android Context가 없어 가상 디스플레이(displayId=${st.displayId})에서 앱을 실행할 수 없습니다."
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false to "ActivityOptions.setLaunchDisplayId는 Android 8.0 (API 26) 이상에서 지원됩니다."
        }

        return try {
            val appCtx = context.applicationContext ?: context
            val launchIntent = appCtx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return false to "실행 가능한 런처 액티비티를 찾을 수 없습니다: $pkg"
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(st.displayId)
            appCtx.startActivity(launchIntent, options.toBundle())
            synchronized(lock) {
                updateUiSnapshotLocked(
                    mode = VirtualDisplayMode.BACKGROUND,
                    takeoverState = VirtualDisplayTakeoverState.RUNNING,
                    lastLaunchedPackage = pkg
                )
            }
            true to "가상 디스플레이(displayId=${st.displayId})에서 '$pkg' 실행을 요청했습니다."
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException launching $pkg on display ${st.displayId}: ${se.message}")
            false to "권한 제한으로 가상 디스플레이 앱 실행에 실패했습니다: ${se.message}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed launching $pkg on display ${st.displayId}: ${e.message}")
            false to "가상 디스플레이 앱 실행 실패: ${e.message}"
        }
    }

    /**
     * Pure JVM-testable input validator enforcing action allowlist, coordinate bounds, takeover lock,
     * and shell-metacharacter rejection before any display input dispatch.
     */
    fun validateDisplayInput(
        action: String,
        x: Int,
        y: Int,
        x2: Int = x,
        y2: Int = y,
        text: String = "",
        statusOverride: VirtualDisplayStatus? = null,
        takeoverOverride: VirtualDisplayTakeoverState? = null
    ): Pair<Boolean, String> {
        val cleanAction = action.trim().lowercase(Locale.ROOT)
        if (cleanAction.isEmpty() || SHELL_METACHAR_REGEX.containsMatchIn(cleanAction)) {
            return false to "유효하지 않거나 위험한 입력 액션입니다."
        }
        if (cleanAction !in ALLOWED_INPUT_ACTIONS) {
            return false to "허용되지 않은 입력 액션 '$cleanAction' (허용: ${ALLOWED_INPUT_ACTIONS.sorted().joinToString(", ")})"
        }
        if (text.isNotEmpty() && SHELL_METACHAR_REGEX.containsMatchIn(text)) {
            return false to "보안 정책: 입력 텍스트에 셸 메타문자 또는 제어 문자가 포함되어 차단되었습니다."
        }
        if (text.length > 512) {
            return false to "입력 텍스트가 최대 허용 길이(512자)를 초과했습니다."
        }

        val st: VirtualDisplayStatus
        val takeover: VirtualDisplayTakeoverState
        synchronized(lock) {
            st = statusOverride ?: _status.value
            takeover = takeoverOverride ?: _uiSnapshot.value.takeoverState
        }

        if (takeover == VirtualDisplayTakeoverState.AWAITING_USER_TAKEOVER) {
            return false to "사용자 Take Over 대기 상태에서는 자동 입력 주입이 차단됩니다."
        }

        val maxW = st.width.coerceAtLeast(1)
        val maxH = st.height.coerceAtLeast(1)

        when (cleanAction) {
            "tap", "long_press" -> {
                if (x < 0 || y < 0 || x > maxW || y > maxH) {
                    return false to "좌표 ($x, $y)가 가상 디스플레이 범위 [0..$maxW, 0..$maxH]를 벗어났습니다."
                }
            }
            "swipe" -> {
                if (x < 0 || y < 0 || x > maxW || y > maxH || x2 < 0 || y2 < 0 || x2 > maxW || y2 > maxH) {
                    return false to "스와이프 좌표 ($x,$y)->($x2,$y2)가 가상 디스플레이 범위 [0..$maxW, 0..$maxH]를 벗어났습니다."
                }
            }
            "text" -> {
                if (text.isBlank()) {
                    return false to "text 액션에는 비어 있지 않은 텍스트가 필요합니다."
                }
                if (text.any { it.code < 0x20 || it.code > 0x7E }) {
                    return false to "가상 디스플레이 직접 입력은 ASCII 안전 문자만 허용합니다 (다국어 입력은 클립보드/접근성 경로를 사용하세요)."
                }
            }
            "keyevent" -> {
                val keyArg = text.trim().uppercase(Locale.ROOT)
                if (!KEYEVENT_REGEX.matches(keyArg)) {
                    return false to "유효하지 않은 키 이벤트 코드입니다: '$text'"
                }
            }
        }
        return true to cleanAction
    }

    /**
     * Dispatches display-scoped input (`input -d <displayId> ...`) using strict `ProcessBuilder`
     * argument arrays (zero `sh -c`). Validates bounds, refuses shell metacharacters, and reports
     * honest errors if display is inactive or OS `INJECT_EVENTS` privilege is unavailable.
     */
    fun injectDisplayInput(
        action: String,
        x: Int,
        y: Int,
        x2: Int = x,
        y2: Int = y,
        text: String = ""
    ): Pair<Boolean, String> {
        val (valid, detailOrAction) = validateDisplayInput(action, x, y, x2, y2, text)
        if (!valid) {
            return false to detailOrAction
        }
        val cleanAction = detailOrAction
        val st = synchronized(lock) { _status.value }
        if (!st.active || st.displayId <= 0) {
            return false to "VirtualDisplay가 비활성 상태입니다 (displayId=${st.displayId})."
        }

        val cmd: List<String> = when (cleanAction) {
            "tap" -> listOf("input", "-d", st.displayId.toString(), "tap", x.toString(), y.toString())
            "long_press" -> listOf(
                "input", "-d", st.displayId.toString(), "swipe",
                x.toString(), y.toString(), x.toString(), y.toString(), "650"
            )
            "swipe" -> listOf(
                "input", "-d", st.displayId.toString(), "swipe",
                x.toString(), y.toString(), x2.toString(), y2.toString(), "300"
            )
            "text" -> listOf("input", "-d", st.displayId.toString(), "text", text.replace(" ", "%s"))
            "keyevent" -> listOf("input", "-d", st.displayId.toString(), "keyevent", text.trim().uppercase(Locale.ROOT))
            else -> return false to "지원하지 않는 입력 액션입니다: $cleanAction"
        }

        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val finished = proc.waitFor(2_500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return false to "가상 디스플레이 입력 명령이 제한 시간(2.5초)을 초과했습니다."
            }
            val output = runCatching { proc.inputStream.bufferedReader().readText().trim() }.getOrDefault("")
            val code = proc.exitValue()
            if (code == 0) {
                true to "displayId=${st.displayId} 입력($cleanAction) 완료"
            } else {
                false to "가상 디스플레이 입력 실패(exit=$code, INJECT_EVENTS/UID-2000 권한 필요): ${output.take(160)}"
            }
        } catch (e: Exception) {
            false to "가상 디스플레이 입력 실행 불가: ${e.message}"
        }
    }

    /** Captures the latest frame from the VirtualDisplay as PNG bytes (never writes to `/sdcard/`). */
    fun capturePngBytes(context: Context? = null): ByteArray? {
        synchronized(lock) {
            val reader = imageReader ?: return null
            val st = _status.value
            if (!st.active) return null
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return null
            try {
                val plane = image.planes.firstOrNull() ?: return null
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride.coerceAtLeast(1)
                val rowStride = plane.rowStride
                val rowPadding = (rowStride - pixelStride * st.width).coerceAtLeast(0)
                val paddedWidth = st.width + rowPadding / pixelStride

                val rawBitmap = Bitmap.createBitmap(paddedWidth, st.height, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)
                val finalBitmap = if (rawBitmap.width != st.width) {
                    Bitmap.createBitmap(rawBitmap, 0, 0, st.width, st.height).also { rawBitmap.recycle() }
                } else {
                    rawBitmap
                }
                val baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.PNG, 88, baos)
                finalBitmap.recycle()
                val bytes = baos.toByteArray()

                if (context != null) {
                    runCatching {
                        val privateDir = File(context.applicationContext.cacheDir, "vdisplay")
                        if (!privateDir.exists()) privateDir.mkdirs()
                        FileOutputStream(File(privateDir, "latest_frame.png")).use { it.write(bytes) }
                    }
                }
                val now = System.currentTimeMillis()
                val updatedStatus = st.copy(lastCaptureTimestampMs = now)
                _status.value = updatedStatus
                val encoded = Base64.getEncoder().encodeToString(bytes)
                updateUiSnapshotLocked(
                    status = updatedStatus,
                    latestFrameBase64 = encoded,
                    nowMs = now
                )
                return bytes
            } catch (e: Exception) {
                Log.w(TAG, "VirtualDisplay frame capture failed: ${e.message}")
                return null
            } finally {
                runCatching { image.close() }
            }
        }
    }

    fun captureBase64Png(context: Context? = null): String? {
        val bytes = capturePngBytes(context) ?: return null
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun stop() {
        synchronized(lock) {
            stopLocked()
        }
    }

    /** Resets in-memory VirtualDisplay state cleanly for unit test isolation. */
    fun resetForTesting() {
        synchronized(lock) {
            activeLease = null
            stopLocked()
            _uiSnapshot.value = VirtualDisplayUiSnapshot()
        }
    }

    private fun stopLocked() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        val stopped = VirtualDisplayStatus(active = false)
        _status.value = stopped
        updateUiSnapshotLocked(
            status = stopped,
            mode = VirtualDisplayMode.IDLE,
            takeoverState = VirtualDisplayTakeoverState.IDLE,
            latestFrameBase64 = null,
            reasonKo = "",
            lastLaunchedPackage = null
        )
    }

    private fun updateUiSnapshotLocked(
        status: VirtualDisplayStatus = _status.value,
        mode: VirtualDisplayMode = _uiSnapshot.value.mode,
        takeoverState: VirtualDisplayTakeoverState = _uiSnapshot.value.takeoverState,
        leaseOwner: String? = activeLease?.ownerId,
        latestFrameBase64: String? = _uiSnapshot.value.latestFrameBase64,
        reasonKo: String = _uiSnapshot.value.reasonKo,
        lastLaunchedPackage: String? = _uiSnapshot.value.lastLaunchedPackage,
        nowMs: Long = System.currentTimeMillis()
    ): VirtualDisplayUiSnapshot {
        val next = VirtualDisplayUiSnapshot(
            status = status,
            mode = mode,
            takeoverState = takeoverState,
            leaseOwner = leaseOwner,
            latestFrameBase64 = latestFrameBase64,
            reasonKo = reasonKo,
            lastLaunchedPackage = lastLaunchedPackage,
            updatedAtMs = nowMs
        )
        _uiSnapshot.value = next
        return next
    }
}
