package com.hanson.mobileuse.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.agent.KoreanScheduleParser
import com.hanson.mobileuse.agent.ProactiveProposal
import com.hanson.mobileuse.agent.ProactiveProposalStatus
import com.hanson.mobileuse.agent.ProactiveProposalToA2uiBridge
import com.hanson.mobileuse.agent.ProactiveSecretaryEngine
import com.hanson.mobileuse.service.DynamicCanvasOverlayService
import com.hanson.mobileuse.ui.MainActivity
import java.time.ZonedDateTime

/**
 * Actionable Lockscreen & Notification Shade surface for Hanson's Proactive Chief-of-Staff AI Secretary.
 *
 * Respects `SecretarySettings` (`enabled`, `isWithinQuietHours`, `isPackageMuted`), enforces
 * API 33+ `POST_NOTIFICATIONS` permission checks, and routes all 1-tap action buttons exclusively
 * to the non-exported [ProactiveSecretaryActionReceiver] via `FLAG_IMMUTABLE` PendingIntents.
 */
object ProactiveSecretaryNotifier {

    const val CHANNEL_ID = "hanson_proactive_secretary"
    const val CHANNEL_NAME_KO = "Hanson 선제안 비서"
    private const val NOTIFICATION_ID_BASE = 24_000
    private const val NOTIFICATION_ID_SPAN = 4_000

    const val ACTION_APPROVE = "com.hanson.mobileuse.SECRETARY_ACTION_APPROVE"
    const val ACTION_SNOOZE = "com.hanson.mobileuse.SECRETARY_ACTION_SNOOZE"
    const val ACTION_DISMISS = "com.hanson.mobileuse.SECRETARY_ACTION_DISMISS"

    const val EXTRA_PROPOSAL_ID = "extra_secretary_proposal_id"
    const val EXTRA_NOTIFICATION_ID = "extra_secretary_notification_id"
    const val EXTRA_SNOOZE_MINUTES = "extra_secretary_snooze_minutes"

    fun notificationIdFor(proposalId: String): Int {
        val hash = proposalId.hashCode() and 0x7FFFFFFF
        return NOTIFICATION_ID_BASE + (hash % NOTIFICATION_ID_SPAN)
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)
        runCatching {
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME_KO,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "약속·마감·예약·미답장 등 놓치기 쉬운 항목을 1-탭으로 제안하는 AI 비서 알림 채널"
                setShowBadge(true)
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Binds [ProactiveSecretaryEngine.onHighPriorityProposalEmitted] so high-confidence pending
     * proposals surface cleanly on the lockscreen / notification shade (and optionally as a
     * floating A2UI overlay card when explicitly opted in).
     */
    fun registerHook(context: Context) {
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)
        ensureChannel(appCtx)
        ProactiveSecretaryEngine.onHighPriorityProposalEmitted = { ctx, proposal ->
            maybeNotifyProposal(ctx.applicationContext ?: appCtx, proposal)
        }
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    /**
     * Evaluates user settings (master toggle, quiet hours, per-app mute) and runtime permission,
     * then posts a lockscreen-actionable notification for [proposal].
     */
    fun maybeNotifyProposal(
        context: Context,
        proposal: ProactiveProposal,
        currentHourOverride: Int? = null
    ): Boolean {
        if (proposal.status != ProactiveProposalStatus.PENDING) return false
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)

        val settings = ProactiveSecretaryEngine.settings.value
        if (!settings.enabled) return false
        if (settings.isPackageMuted(proposal.packageName)) return false

        val currentHour = currentHourOverride ?: runCatching {
            ZonedDateTime.now(KoreanScheduleParser.DEFAULT_ZONE).hour
        }.getOrElse {
            java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        }
        if (settings.isWithinQuietHours(currentHour)) return false

        // Opt-in cross-app floating A2UI overlay projection (only when explicitly enabled & permitted)
        if (settings.floatingA2uiOverlayEnabled &&
            runCatching { Settings.canDrawOverlays(appCtx) }.getOrDefault(false)
        ) {
            runCatching {
                ProactiveProposalToA2uiBridge.publishToA2uiRegistry(proposal)
                DynamicCanvasOverlayService.startIfPermitted(appCtx, initialDrawMode = false)
            }
        }

        if (!hasPostNotificationsPermission(appCtx)) return false
        ensureChannel(appCtx)

        val notifId = notificationIdFor(proposal.id)
        val openIntent = Intent(appCtx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_PROPOSAL_ID, proposal.id)
        }
        val openPending = PendingIntent.getActivity(
            appCtx,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approvePending = buildActionPendingIntent(
            context = appCtx,
            action = ACTION_APPROVE,
            proposalId = proposal.id,
            notifId = notifId,
            requestCode = notifId * 10 + 1
        )
        val snoozePending = buildActionPendingIntent(
            context = appCtx,
            action = ACTION_SNOOZE,
            proposalId = proposal.id,
            notifId = notifId,
            requestCode = notifId * 10 + 2,
            snoozeMinutes = 60L
        )
        val dismissPending = buildActionPendingIntent(
            context = appCtx,
            action = ACTION_DISMISS,
            proposalId = proposal.id,
            notifId = notifId,
            requestCode = notifId * 10 + 3
        )

        val titlePrefix = if (proposal.hasCalendarConflict) "⚠ " else ""
        val contentTitle = "$titlePrefix[${proposal.type.badgeKo}] ${proposal.titleKo}"
        val contentText = buildString {
            append(proposal.summaryKo)
            if (proposal.hasCalendarConflict && proposal.conflictSummaryKo.isNotBlank()) {
                append(" · ")
                append(proposal.conflictSummaryKo)
            }
        }
        val bigText = buildString {
            append(contentText)
            if (proposal.detailKo.isNotBlank() && proposal.detailKo != proposal.summaryKo) {
                append("\n")
                append(proposal.detailKo)
            }
            if (proposal.quotedExcerpt.isNotBlank()) {
                append("\n“")
                append(proposal.quotedExcerpt.take(120))
                append("”")
            }
        }
        val subText = listOf(
            proposal.sourceApp,
            proposal.roomOrTitle.ifBlank { proposal.sender }
        ).filter { it.isNotBlank() }.distinct().joinToString(" · ")

        val primaryLabel = proposal.primaryAction.labelKo.trim().ifBlank { "실행" }.take(20)

        return runCatching {
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return false
            val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSubText(subText.ifBlank { CHANNEL_NAME_KO })
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(0, primaryLabel, approvePending)
                .addAction(0, "1시간 뒤", snoozePending)
                .addAction(0, "무시", dismissPending)
                .build()
            nm.notify(notifId, notification)
            true
        }.getOrDefault(false)
    }

    /**
     * Replaces the proposal notification with a brief, silent auto-expiring confirmation toast
     * after a lockscreen 1-tap action completes.
     */
    fun showActionResultNotification(
        context: Context,
        proposalId: String,
        titleKo: String,
        messageKo: String
    ) {
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)
        val notifId = notificationIdFor(proposalId)
        if (!hasPostNotificationsPermission(appCtx)) {
            cancelByNotificationId(appCtx, notifId)
            return
        }
        ensureChannel(appCtx)
        runCatching {
            val nm = appCtx.getSystemService(NotificationManager::class.java) ?: return
            val openIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                appCtx,
                notifId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val updated = NotificationCompat.Builder(appCtx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titleKo)
                .setContentText(messageKo.take(160))
                .setStyle(NotificationCompat.BigTextStyle().bigText(messageKo.take(260)))
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(4_500L)
                .setContentIntent(openPending)
                .build()
            nm.notify(notifId, updated)
        }
    }

    fun cancelNotification(context: Context, proposalId: String) {
        cancelByNotificationId(context, notificationIdFor(proposalId))
    }

    fun cancelByNotificationId(context: Context, notificationId: Int) {
        val appCtx = runCatching { context.applicationContext ?: context }.getOrDefault(context)
        runCatching {
            val nm = appCtx.getSystemService(NotificationManager::class.java)
            nm?.cancel(notificationId)
        }
    }

    private fun buildActionPendingIntent(
        context: Context,
        action: String,
        proposalId: String,
        notifId: Int,
        requestCode: Int,
        snoozeMinutes: Long = 60L
    ): PendingIntent {
        val intent = Intent(context, ProactiveSecretaryActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_PROPOSAL_ID, proposalId)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
            if (action == ACTION_SNOOZE) {
                putExtra(EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
