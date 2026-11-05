package com.hanson.mobileuse.notification

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class InterceptedNotification(
    val sessionId: String,
    val notificationKey: String,
    val packageName: String,
    val appLabel: String,
    val sender: String,
    val roomOrTitle: String,
    val messageText: String,
    val subText: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val canQuickReply: Boolean = false,
    val pendingIntent: PendingIntent? = null,
    val remoteInputs: List<RemoteInput> = emptyList(),
    val repliedText: String? = null,
    val repliedAtMs: Long? = null
)

/**
 * Stores intercepted notifications and their `RemoteInput` reply tokens so the agent
 * can list incoming messages and send sub-200ms background replies (`reply_notification`)
 * without turning on the screen or launching the target messenger app.
 */
object RemoteInputSessionCache {
    private const val DEFAULT_TTL_MS = 30 * 60 * 1000L // 30 minutes
    private val sessions = ConcurrentHashMap<String, InterceptedNotification>()

    private val _recentNotifications = MutableStateFlow<List<InterceptedNotification>>(emptyList())
    val recentNotifications: StateFlow<List<InterceptedNotification>> = _recentNotifications.asStateFlow()

    private val _listenerConnected = MutableStateFlow(false)
    val listenerConnected: StateFlow<Boolean> = _listenerConnected.asStateFlow()

    fun setListenerConnected(connected: Boolean) {
        _listenerConnected.value = connected
    }

    fun put(notification: InterceptedNotification) {
        sessions[notification.sessionId] = notification
        refreshFlow()
    }

    fun get(sessionId: String): InterceptedNotification? = sessions[sessionId]

    /**
     * Finds the most recent notification matching a sessionId, sender name, room title, or package/app name.
     */
    fun findBestMatch(query: String): InterceptedNotification? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return sessions.values
                .filter { it.canQuickReply }
                .maxByOrNull { it.timestampMs }
        }
        sessions[query]?.let { return it }

        return sessions.values
            .sortedByDescending { it.timestampMs }
            .firstOrNull { item ->
                item.sessionId.lowercase() == q ||
                    item.sender.lowercase().contains(q) ||
                    item.roomOrTitle.lowercase().contains(q) ||
                    item.appLabel.lowercase().contains(q) ||
                    item.packageName.lowercase().contains(q)
            }
    }

    fun listRecent(limit: Int = 20, onlyReplyable: Boolean = false): List<InterceptedNotification> {
        return sessions.values
            .filter { !onlyReplyable || it.canQuickReply }
            .sortedByDescending { it.timestampMs }
            .take(limit)
    }

    fun markReplied(sessionId: String, replyMessage: String) {
        val existing = sessions[sessionId] ?: return
        sessions[sessionId] = existing.copy(
            repliedText = replyMessage,
            repliedAtMs = System.currentTimeMillis()
        )
        refreshFlow()
    }

    fun evictExpired(nowMs: Long = System.currentTimeMillis(), ttlMs: Long = DEFAULT_TTL_MS): Int {
        var evicted = 0
        val iter = sessions.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (nowMs - entry.value.timestampMs > ttlMs) {
                iter.remove()
                evicted++
            }
        }
        if (evicted > 0) refreshFlow()
        return evicted
    }

    fun clearAll() {
        sessions.clear()
        refreshFlow()
    }

    /**
     * Executes a screen-off quick reply via `RemoteInput.addResultsToIntent` + `PendingIntent.send`.
     */
    fun sendQuickReply(
        context: Context,
        targetQueryOrSessionId: String,
        replyText: String
    ): Pair<Boolean, String> {
        val session = findBestMatch(targetQueryOrSessionId)
            ?: return false to "일치하는 알림 세션을 찾을 수 없습니다: '$targetQueryOrSessionId'"

        if (!session.canQuickReply || session.pendingIntent == null || session.remoteInputs.isEmpty()) {
            return false to "'${session.sender}' (${session.appLabel}) 알림은 빠른 답장(RemoteInput)을 지원하지 않습니다."
        }

        return try {
            val fillInIntent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val resultsBundle = Bundle()
            for (remoteInput in session.remoteInputs) {
                resultsBundle.putCharSequence(remoteInput.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(
                session.remoteInputs.toTypedArray(),
                fillInIntent,
                resultsBundle
            )
            session.pendingIntent.send(context, 0, fillInIntent)
            markReplied(session.sessionId, replyText)
            true to "[Screen-Off Quick Reply 완료] ${session.appLabel} '${session.sender}'님에게 답장 전송: \"$replyText\""
        } catch (e: PendingIntent.CanceledException) {
            sessions.remove(session.sessionId)
            refreshFlow()
            false to "알림 답장 토큰(PendingIntent)이 만료되었습니다. 앱을 직접 열어 답장하세요."
        } catch (e: Exception) {
            false to "빠른 답장 전송 실패: ${e.message}"
        }
    }

    private fun refreshFlow() {
        _recentNotifications.value = sessions.values
            .sortedByDescending { it.timestampMs }
            .take(50)
    }
}
