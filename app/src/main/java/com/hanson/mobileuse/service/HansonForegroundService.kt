package com.hanson.mobileuse.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.notification.HansonNotificationListenerService
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import com.hanson.mobileuse.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tier 2 Immortal Foreground Watchdog Service (`specialUse`).
 * Keeps the Mobile-Use AI daemon alive in the background, maintains NotificationListener binding,
 * evicts expired `RemoteInput` tokens every 60s, and provides an optional partial WakeLock during
 * active multi-step agent runs.
 */
class HansonForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "hanson_mobile_use_daemon"
        const val NOTIFICATION_ID = 10042
        const val ACTION_STOP_AGENT = "com.hanson.mobileuse.ACTION_STOP_AGENT"
        private const val WATCHDOG_INTERVAL_MS = 60_000L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        var onEmergencyStopRequested: (() -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, HansonForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_AGENT) {
            onEmergencyStopRequested?.invoke()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdogRunnable)
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
