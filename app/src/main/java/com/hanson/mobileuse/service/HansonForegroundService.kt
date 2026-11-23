package com.hanson.mobileuse.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.notification.HansonNotificationListenerService
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.oscli.HansonOsCliMain
import com.hanson.mobileuse.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Tier 2 Immortal Foreground Watchdog Service (`specialUse`).
 * Keeps the Mobile-Use AI daemon alive in the background, maintains NotificationListener binding,
 * evicts expired `RemoteInput` tokens every 60s, provides an optional partial WakeLock during
 * active multi-step agent runs, and runs a coroutine watchdog for automatic accessibility self-healing.
 */
class HansonForegroundService : Service() {

    companion object {
        private const val TAG = "HansonFgService"
        const val CHANNEL_ID = "hanson_mobile_use_daemon"
        const val NOTIFICATION_ID = 10042
        const val ACTION_STOP_AGENT = "com.hanson.mobileuse.ACTION_STOP_AGENT"
        private const val WATCHDOG_INTERVAL_MS = 60_000L
        private val A11Y_SELF_HEAL_BACKOFF_MS = longArrayOf(5_000L, 30_000L, 120_000L, 300_000L)

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _lastSelfHealingAttemptMs = MutableStateFlow(0L)
        val lastSelfHealingAttemptMs: StateFlow<Long> = _lastSelfHealingAttemptMs.asStateFlow()

        var onEmergencyStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, HansonForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(TAG, "ForegroundServiceStartNotAllowedException starting HansonForegroundService: ${e.message}")
                } else {
                    Log.w(TAG, "IllegalStateException starting HansonForegroundService: ${e.message}")
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, HansonForegroundService::class.java))
            } catch (_: Exception) {
            }
        }

        /**
         * Checks if HansonAccessibilityService is active and triggers self-healing if disconnected.
         * Preserves existing enabled accessibility services (TalkBack/password managers) by appending + deduplicating.
         * Attempts direct Settings.Secure restoration when WRITE_SECURE_SETTINGS is granted, else escalates once to OS CLI.
         */
        fun initiateAccessibilitySelfHealing(context: Context): Boolean {
            _lastSelfHealingAttemptMs.value = System.currentTimeMillis()
            val expectedService = "${context.packageName}/${HansonAccessibilityService::class.java.name}"
            var healed = false

            // 1. Direct Settings.Secure update only if app actually holds WRITE_SECURE_SETTINGS
            val hasWriteSecure = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED

            if (hasWriteSecure) {
                try {
                    val cr = context.contentResolver
                    val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                    val target = HansonOsCliMain.mergeAccessibilityServices(current, listOf(expectedService))
                    val wroteServices = Settings.Secure.putString(
                        cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        target
                    )
                    val wroteEnabled = Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                    if (wroteServices && wroteEnabled) {
                        healed = true
                        Log.i(TAG, "Self-healing applied via Settings.Secure: $target")
                    }
                } catch (e: Throwable) {
                    Log.d(TAG, "Direct Settings.Secure write failed (${e.message}), escalating to OS CLI")
                }
            }

            // 2. Escalate to OS CLI daemon (:18890) only if direct Settings.Secure restoration did not succeed
            if (!healed) {
                try {
                    val harness = com.hanson.mobileuse.agent.AgentToolExecutionHarness(context)
                    val outcome = harness.execute(
                        toolName = "os_cli_exec",
                        args = JSONObject().put("command", "self-heal ${context.packageName}"),
                        latestSnapshot = null
                    )
                    if (outcome.succeeded) {
                        healed = true
                        Log.i(TAG, "Self-healing executed via OS CLI: ${outcome.outputText}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "OS CLI self-healing execution error: ${e.message}")
                }
            }

            return healed
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var a11yWatchdogJob: Job? = null

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            HansonNotificationListenerService.ensureRebound(packageName = packageName)
            RemoteInputSessionCache.evictExpired()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val notification = buildNotification("Artemis Bridge (:18888) · Screen-Off Reply 대기 중")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        _isRunning.value = true
        handler.post(watchdogRunnable)
        startAccessibilityWatchdog()
    }

    /**
     * Checks accessibility permission/status once after startup with bounded exponential backoff
     * (no infinite 10-second self-heal polling loop).
     */
    private fun startAccessibilityWatchdog() {
        a11yWatchdogJob?.cancel()
        a11yWatchdogJob = serviceScope.launch {
            for ((attemptIdx, delayMs) in A11Y_SELF_HEAL_BACKOFF_MS.withIndex()) {
                if (!isActive) return@launch
                delay(delayMs)
                if (HansonAccessibilityService.isServiceActive()) {
                    updateNotification()
                    return@launch
                }

                val hasWriteSecure = ContextCompat.checkSelfPermission(
                    this@HansonForegroundService,
                    Manifest.permission.WRITE_SECURE_SETTINGS
                ) == PackageManager.PERMISSION_GRANTED

                Log.i(
                    TAG,
                    "Bounded A11y check #${attemptIdx + 1}/${A11Y_SELF_HEAL_BACKOFF_MS.size} (WRITE_SECURE_SETTINGS=$hasWriteSecure)"
                )
                val healed = initiateAccessibilitySelfHealing(this@HansonForegroundService)
                updateNotification(
                    if (healed) "A11y 자동 복구 요청됨 · 연결 확인 중"
                    else "접근성 서비스 꺼짐 · [권한 허브]에서 활성화 필요"
                )
                if (!healed && !hasWriteSecure) {
                    // Stop early when privileged self-heal is unavailable instead of retrying in background
                    return@launch
                }
            }
            if (!HansonAccessibilityService.isServiceActive()) {
                updateNotification("접근성 서비스 꺼짐 · [권한 허브]에서 활성화 필요")
            }
        }
    }

    private fun updateNotification(statusText: String? = null) {
        try {
            val text = statusText ?: if (HansonAccessibilityService.isServiceActive()) {
                "Artemis Bridge (:18888) · A11y 정상 연결"
            } else {
                "A11y 재연결 대기 및 자동 치유 중..."
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AGENT) {
            onEmergencyStopRequested?.invoke()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
        a11yWatchdogJob?.cancel()
        serviceJob.cancel()
        releaseWakeLock()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun acquireWakeLock(timeoutMs: Long = 5 * 60 * 1000L) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HansonMobileUse::AgentRunWakeLock").apply {
                acquire(timeoutMs)
            }
        } catch (_: Exception) {
        }
    }

    fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        } finally {
            wakeLock = null
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hanson Mobile-Use Daemon",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "온디바이스 AI 에이전트 및 접근성 루프백 브리지 상시 실행 채널"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, HansonForegroundService::class.java).apply {
            action = ACTION_STOP_AGENT
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val a11yUp = if (HansonAccessibilityService.isServiceActive()) "A11y ON" else "A11y OFF"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Hanson × Artemis Mobile-Use [$a11yUp]")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_media_pause, "에이전트 긴급 정지", stopPending)
            .build()
    }
}
