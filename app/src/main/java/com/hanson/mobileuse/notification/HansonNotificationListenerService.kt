package com.hanson.mobileuse.notification

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Tier 2 Screen-Off Notification Interceptor.
 * Captures notifications across KakaoTalk (`com.kakao.talk`), SMS, Telegram, Slack, Discord,
 * WhatsApp, Instagram, and all apps exposing `Notification.Action` with `RemoteInput`.
 *
 * Modernized behavior:
 * - Hydrates `activeNotifications` on `onListenerConnected`.
 * - Evicts dismissed notifications in `onNotificationRemoved`.
 * - Skips `FLAG_GROUP_SUMMARY` and `FLAG_ONGOING_EVENT` / ongoing status-bar items.
 * - Parses `NotificationCompat.MessagingStyle` messages for accurate sender/text/room extraction.
 * - Avoids initializing `MobileUseAgentEngine` on unrelated notifications unless reactive/auto-reply rules exist.
 */
class HansonNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "HansonNotifListener"
        private const val NEXUS_PREFS_NAME = "hanson_universal_nexus_engine"
        private const val KEY_REACTIVE_RULES = "reactive_automation_rules_json"

        @Volatile
        private var isBound = false

        fun ensureRebound(
            serviceClass: Class<*> = HansonNotificationListenerService::class.java,
            packageName: String
        ) {
            if (!isBound && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(ComponentName(packageName, serviceClass.name))
                } catch (e: Exception) {
                    Log.w(TAG, "requestRebind failed: ${e.message}")
                }
            }
        }

        internal fun computeSessionId(pkg: String, key: String?): String {
            val safeKey = key.orEmpty()
            return "${pkg.substringAfterLast('.')}_${(safeKey.hashCode() and 0x7FFFFFFF).toString(16)}"
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isBound = true
        RemoteInputSessionCache.setListenerConnected(true)
        Log.i(TAG, "HansonNotificationListenerService connected")
        hydrateActiveNotifications()
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
        ingestNotification(sbn, notifyReactiveRules = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName.orEmpty()
        val key = sbn.key.orEmpty()
        val sessionId = if (pkg.isNotBlank()) computeSessionId(pkg, key) else ""

        val candidates = mutableListOf<InterceptedNotification>()
        if (sessionId.isNotBlank()) {
            RemoteInputSessionCache.get(sessionId)?.let { candidates.add(it) }
        }
        if (key.isNotBlank()) {
            RemoteInputSessionCache.listRecent(limit = 50, onlyReplyable = false)
                .filter { it.notificationKey == key && it.sessionId != sessionId }
                .forEach { candidates.add(it) }
        }

        if (candidates.isNotEmpty()) {
            for (item in candidates) {
                // Tombstone timestamp to 0L and immediately evict via RemoteInputSessionCache.evictExpired()
                RemoteInputSessionCache.put(
                    item.copy(
                        timestampMs = 0L,
                        canQuickReply = false,
                        pendingIntent = null,
                        remoteInputs = emptyList()
                    )
                )
            }
            RemoteInputSessionCache.evictExpired()
        }
    }

    private fun hydrateActiveNotifications() {
        try {
            val existing = activeNotifications ?: return
            var count = 0
            for (sbn in existing) {
                if (ingestNotification(sbn, notifyReactiveRules = false) != null) {
                    count++
                }
            }
            Log.i(TAG, "Hydrated $count active notifications into RemoteInputSessionCache")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hydrate activeNotifications: ${e.message}")
        }
    }

    private fun ingestNotification(
        sbn: StatusBarNotification,
        notifyReactiveRules: Boolean
    ): InterceptedNotification? {
        val pkg = sbn.packageName ?: return null
        if (pkg == packageName ||
            pkg == "com.hanson.mobileuse" ||
            pkg == "com.hanson.mobileuse.debug" ||
            pkg == "android" ||
            pkg == "com.android.systemui"
        ) {
            return null
        }

        val notif = sbn.notification ?: return null
        val flags = notif.flags
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return null
        if (sbn.isOngoing) return null

        val extras = notif.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val rawConversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim().orEmpty()
        val rawText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()

        // Parse MessagingStyle when available (KakaoTalk, SMS, Telegram, WhatsApp, Slack, Discord)
        val messagingStyle = runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notif)
        }.getOrNull()

        val styleConversationTitle = messagingStyle?.conversationTitle?.toString()?.trim().orEmpty()
        val latestStyleMessage = messagingStyle?.messages?.lastOrNull()
        val styleSender = latestStyleMessage?.person?.name?.toString()?.trim().orEmpty()
        val styleText = latestStyleMessage?.text?.toString()?.trim().orEmpty()

        val resolvedSender = styleSender.ifBlank { rawTitle }
        val resolvedRoom = styleConversationTitle
            .ifBlank { rawConversationTitle }
            .ifBlank { subText }
            .ifBlank { rawTitle }
        val resolvedText = styleText.ifBlank { rawText }

        if (resolvedSender.isBlank() && resolvedText.isBlank()) return null

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
        val sessionId = computeSessionId(pkg, sbn.key)
        val postTime = latestStyleMessage?.timestamp?.takeIf { it > 0 }
            ?: sbn.postTime.takeIf { it > 0 }
            ?: System.currentTimeMillis()

        val intercepted = InterceptedNotification(
            sessionId = sessionId,
            notificationKey = sbn.key ?: sessionId,
            packageName = pkg,
            appLabel = appLabel,
            sender = resolvedSender.ifBlank { appLabel },
            roomOrTitle = resolvedRoom.ifBlank { resolvedSender.ifBlank { appLabel } },
            messageText = resolvedText,
            subText = subText,
            timestampMs = postTime,
            canQuickReply = replyAction != null,
            pendingIntent = replyAction?.actionIntent,
            remoteInputs = replyAction?.remoteInputs?.toList() ?: emptyList()
        )

        RemoteInputSessionCache.put(intercepted)

        // Only initialize & invoke MobileUseAgentEngine when reactive/auto-reply rules actually exist.
        if (notifyReactiveRules && hasReactiveAutomationRules()) {
            runCatching {
                com.hanson.mobileuse.agent.HansonRuntimeHolder
                    .getAgentEngine(applicationContext)
                    .onNotificationIntercepted(intercepted)
            }
        }
        return intercepted
    }

    private fun hasReactiveAutomationRules(): Boolean {
        return try {
            val prefs = applicationContext.getSharedPreferences(NEXUS_PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_REACTIVE_RULES, null)?.trim()
            !raw.isNullOrEmpty() && raw != "[]"
        } catch (_: Throwable) {
            false
        }
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
