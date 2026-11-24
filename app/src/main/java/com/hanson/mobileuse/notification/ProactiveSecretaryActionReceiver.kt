package com.hanson.mobileuse.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import java.util.concurrent.Executors

/**
 * Strictly non-exported (`android:exported="false"`) broadcast receiver handling 1-tap
 * lockscreen / notification shade actions from [ProactiveSecretaryNotifier].
 *
 * Uses `goAsync()` + a dedicated background executor so calendar writes, alarms, notes,
 * and RemoteInput quick replies never block the Android main thread.
 */
class ProactiveSecretaryActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretaryActionRcvr"
        private val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "HansonSecretaryNotifAction").apply { isDaemon = true }
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action?.trim().orEmpty()
        val proposalId = intent.getStringExtra(ProactiveSecretaryNotifier.EXTRA_PROPOSAL_ID)
            ?.trim()
            .orEmpty()
        if (action.isEmpty() || proposalId.isEmpty()) return

        val appCtx = context.applicationContext ?: context
        val notifId = intent.getIntExtra(
            ProactiveSecretaryNotifier.EXTRA_NOTIFICATION_ID,
            ProactiveSecretaryNotifier.notificationIdFor(proposalId)
        )
        val snoozeMinutes = intent.getLongExtra(
            ProactiveSecretaryNotifier.EXTRA_SNOOZE_MINUTES,
            60L
        ).coerceIn(1L, 24L * 60L)

        val pendingResult = goAsync()
        backgroundExecutor.execute {
            try {
                val engine = HansonRuntimeHolder.getAgentEngine(appCtx)
                when (action) {
                    ProactiveSecretaryNotifier.ACTION_APPROVE -> {
                        val (succeeded, message) = engine.executeProactiveProposal(
                            proposalId = proposalId,
                            actionKey = "approve"
                        )
                        ProactiveSecretaryNotifier.showActionResultNotification(
                            context = appCtx,
                            proposalId = proposalId,
                            titleKo = if (succeeded) "Hanson 선제안 실행 완료" else "Hanson 선제안 확인 필요",
                            messageKo = message.ifBlank {
                                if (succeeded) "선택한 제안을 처리했어요." else "작업을 완료하지 못했어요."
                            }
                        )
                    }

                    ProactiveSecretaryNotifier.ACTION_SNOOZE -> {
                        engine.executeProactiveProposal(
                            proposalId = proposalId,
                            actionKey = "snooze",
                            snoozeMinutes = snoozeMinutes
                        )
                        ProactiveSecretaryNotifier.cancelByNotificationId(appCtx, notifId)
                        ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId)
                    }

                    ProactiveSecretaryNotifier.ACTION_DISMISS -> {
                        engine.executeProactiveProposal(
                            proposalId = proposalId,
                            actionKey = "dismiss"
                        )
                        ProactiveSecretaryNotifier.cancelByNotificationId(appCtx, notifId)
                        ProactiveSecretaryNotifier.cancelNotification(appCtx, proposalId)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error handling secretary notification action '$action': ${t.message}")
                ProactiveSecretaryNotifier.cancelByNotificationId(appCtx, notifId)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }
}
