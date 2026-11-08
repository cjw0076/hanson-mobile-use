package com.hanson.mobileuse.notification

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Tier 2 Screen-Off Notification Interceptor.
 * Captures notifications across KakaoTalk (`com.kakao.talk`), SMS, Telegram, Slack, Discord,
 * WhatsApp, Instagram, and all apps exposing `Notification.Action` with `RemoteInput`.
 */
class HansonNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "HansonNotifListener"

        @Volatile
        private var isBound = false

        fun ensureRebound( serviceClass: Class<*> = HansonNotificationListenerService::class.java, packageName: String) {
            if (!isBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(packageName, serviceClass.name))
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind failed: ${e.message}")
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isBound = true
        RemoteInputSessionCache.setListenerConnected(true)
        Log.i(TAG, "HansonNotificationListenerService connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isBound = false
        RemoteInputSessionCache.setListenerConnected(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, HansonNotificationListenerService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        // Extract RemoteInput action if present
        var replyAction: Notification.Action? = null
        val actions = notif.actions
        if (!actions.isNullOrEmpty()) {
            for (action in actions) {
                val rInputs = action.remoteInputs
                if (!rInputs.isNullOrEmpty() && action.actionIntent != null) {
                    replyAction = action
                    break
                }
            }
        }

        val appLabel = resolveAppLabel(pkg)
        val sessionId = "${pkg.substringAfterLast('.')}_${(sbn.key.hashCode() and 0x7FFFFFFF).toString(16)}"

        val intercepted = InterceptedNotification(
            sessionId = sessionId,
            notificationKey = sbn.key ?: sessionId,
            packageName = pkg,
            appLabel = appLabel,
            sender = title.ifBlank { appLabel },
            roomOrTitle = conversationTitle.ifBlank { subText.ifBlank { title } },
            messageText = text,
            subText = subText,
            timestampMs = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
            canQuickReply = replyAction != null,
            pendingIntent = replyAction?.actionIntent,
            remoteInputs = replyAction?.remoteInputs?.toList() ?: emptyList()
        )

        RemoteInputSessionCache.put(intercepted)
    }

    private fun resolveAppLabel(pkg: String): String {
        return when (pkg) {
            "com.kakao.talk" -> "카카오톡"
            "com.samsung.android.messaging", "com.google.android.apps.messaging" -> "메시지(SMS)"
            "org.telegram.messenger" -> "텔레그램"
            "com.Slack" -> "Slack"
            "com.discord" -> "Discord"
            "com.instagram.android" -> "Instagram"
            "com.whatsapp" -> "WhatsApp"
            "jp.naver.line.android" -> "LINE"
            else -> try {
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                pkg.substringAfterLast('.')
            }
        }
    }
}
