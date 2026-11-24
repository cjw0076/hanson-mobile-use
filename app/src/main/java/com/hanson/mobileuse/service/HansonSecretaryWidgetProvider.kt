package com.hanson.mobileuse.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.hanson.mobileuse.R
import com.hanson.mobileuse.agent.PrivacyAndQuotaProofLedger
import com.hanson.mobileuse.agent.PrivacyProofSnapshot
import com.hanson.mobileuse.agent.ProactiveProposal
import com.hanson.mobileuse.agent.ProactiveProposalStatus
import com.hanson.mobileuse.agent.ProactiveProposalType
import com.hanson.mobileuse.agent.ProactiveSecretaryEngine
import com.hanson.mobileuse.ui.MainActivity

/**
 * Zero-Dependency Native Android Home-Screen Widget (`AppWidgetProvider`) for Hanson AI Secretary.
 * Renders a pure 0%-saturation Paper & Carbon monochrome card displaying pending proposal count,
 * highest-priority proposal (with conflict / scam pre-shield alert cues), and on-device `$0`
 * privacy proof metrics. Tapping opens [MainActivity].
 */
class HansonSecretaryWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.hanson.mobileuse.ACTION_REFRESH_SECRETARY_WIDGET"
        private const val REQUEST_CODE_OPEN_APP = 4201

        fun requestRefresh(context: Context?) {
            if (context == null) return
            runCatching {
                val appCtx = context.applicationContext ?: context
                val manager = AppWidgetManager.getInstance(appCtx) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(appCtx, HansonSecretaryWidgetProvider::class.java)
                )
                if (ids != null && ids.isNotEmpty()) {
                    updateWidgets(appCtx, manager, ids)
                }
            }
        }

        fun buildWidgetCopy(
            proposals: List<ProactiveProposal>,
            privacySnapshot: PrivacyProofSnapshot
        ): WidgetRenderCopy {
            val pending = proposals
                .filter { it.status == ProactiveProposalStatus.PENDING }
                .sortedWith(
                    compareByDescending<ProactiveProposal> { it.type == ProactiveProposalType.SCAM_PRE_SHIELD }
                        .thenByDescending { it.hasCalendarConflict }
                        .thenByDescending { it.type.priorityWeight }
                        .thenByDescending { it.updatedAtMs }
                )

            val badgeText = "${pending.size}건"
            val top = pending.firstOrNull()
            val topTitle = when {
                top == null -> "지금은 새로 챙길 선제안이 없어요"
                top.type == ProactiveProposalType.SCAM_PRE_SHIELD -> "[주의] ${top.titleKo}"
                top.hasCalendarConflict -> "[충돌] ${top.titleKo}"
                else -> top.titleKo
            }
            val topSummary = when {
                top == null -> "카카오톡·일정·구독·스미싱 맥락을 로컬에서 조용히 지켜보고 있어요."
                top.hasCalendarConflict && top.conflictSummaryKo.isNotBlank() -> top.conflictSummaryKo
                top.summaryKo.isNotBlank() -> "${top.sourceApp} · ${top.summaryKo}"
                else -> "${top.sourceApp} · ${top.type.badgeKo}"
            }
            val privacyLine = "로컬 \$0 스캔 ${privacySnapshot.localZeroCostScans}건 · 외부 유출 ${privacySnapshot.externalCloudLeaks}건"

            return WidgetRenderCopy(
                badgeText = badgeText,
                topTitle = topTitle,
                topSummary = topSummary,
                privacyLine = privacyLine
            )
        }

        private fun updateWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            runCatching { ProactiveSecretaryEngine.ensureInitialized(context) }
            runCatching { PrivacyAndQuotaProofLedger.ensureInitialized(context) }

            val copy = buildWidgetCopy(
                proposals = ProactiveSecretaryEngine.proposals.value,
                privacySnapshot = PrivacyAndQuotaProofLedger.snapshot.value
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_APP,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            for (appWidgetId in appWidgetIds) {
                runCatching {
                    val views = RemoteViews(context.packageName, R.layout.widget_hanson_secretary)
                    views.setTextViewText(R.id.widget_pending_badge, copy.badgeText)
                    views.setTextViewText(R.id.widget_top_title, copy.topTitle)
                    views.setTextViewText(R.id.widget_top_summary, copy.topSummary)
                    views.setTextViewText(R.id.widget_privacy_proof, copy.privacyLine)
                    views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
                    appWidgetManager.updateAppWidget(appWidgetId, views)
                }
            }
        }
    }

    data class WidgetRenderCopy(
        val badgeText: String,
        val topTitle: String,
        val topSummary: String,
        val privacyLine: String
    )

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            requestRefresh(context)
        }
    }
}
