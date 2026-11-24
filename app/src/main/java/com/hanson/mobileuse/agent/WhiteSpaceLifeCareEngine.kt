package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.NotificationThreadBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
// #1. Self-Promise & Commitment Debt Tracker ("내가 한 약속 추적기")
// =============================================================================

data class SelfCommitmentRecord(
    val id: String,
    val fingerprint: String,
    val sourceApp: String,
    val packageName: String,
    val counterparty: String,
    val roomOrTitle: String,
    val promiseText: String,
    val dueTimeMs: Long? = null,
    val isFinancialPromise: Boolean = false,
    val proposalId: String = "",
    val completed: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fingerprint", fingerprint)
        put("sourceApp", sourceApp)
        put("packageName", packageName)
        put("counterparty", counterparty)
        put("roomOrTitle", roomOrTitle)
        put("promiseText", promiseText)
        put("dueTimeMs", dueTimeMs ?: JSONObject.NULL)
        put("isFinancialPromise", isFinancialPromise)
        put("proposalId", proposalId)
        put("completed", completed)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): SelfCommitmentRecord? {
            if (obj == null) return null
            val id = obj.optString("id", "").trim()
            val fp = obj.optString("fingerprint", "").trim()
            val text = obj.optString("promiseText", "").trim()
            if (id.isEmpty() || fp.isEmpty() || text.isEmpty()) return null
            return SelfCommitmentRecord(
                id = id,
                fingerprint = fp,
                sourceApp = obj.optString("sourceApp", ""),
                packageName = obj.optString("packageName", ""),
                counterparty = obj.optString("counterparty", ""),
                roomOrTitle = obj.optString("roomOrTitle", ""),
                promiseText = text,
                dueTimeMs = if (obj.has("dueTimeMs") && !obj.isNull("dueTimeMs")) obj.optLong("dueTimeMs") else null,
                isFinancialPromise = obj.optBoolean("isFinancialPromise", false),
                proposalId = obj.optString("proposalId", ""),
                completed = obj.optBoolean("completed", false),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }
    }
}

object CommitmentDebtTrackerEngine {

    const val ACTION_MARK_DONE = "commitment.mark_done"
    const val ACTION_DRAFT_UPDATE = "commitment.draft_update"
    const val ACTION_RESCHEDULE = "commitment.reschedule"

    private const val FILE_NAME = "hanson_self_commitments.json"
    private const val MAX_RECORDS = 60

    private val lock = Any()
    private val idCounter = AtomicLong(0L)

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    private val records = mutableListOf<SelfCommitmentRecord>()
    private val _commitments = MutableStateFlow<List<SelfCommitmentRecord>>(emptyList())
    val commitments: StateFlow<List<SelfCommitmentRecord>> = _commitments.asStateFlow()

    private val COMMITMENT_REGEX = Regex(
        "(?i)(?:보내\\s*드릴게요?|보낼게요?|확인해\\s*볼게요?|확인하고\\s*(?:연락|회신|알려)|입금할게요?|송금할게요?|정리해서\\s*(?:드릴|보낼|공유)|공유\\s*드릴게요?|공유할게요?|전화\\s*드릴게요?|회신\\s*드릴게요?|답장\\s*드릴게요?|내일까지\\s*(?:보내|드릴|확인|정리|완료|공유)|오늘\\s*밤에\\s*(?:보내|확인|연락|정리)|\\bi['’]ll\\s+(?:send|check|share|email|call|get\\s+back)|get\\s+back\\s+to\\s+you\\s+by|will\\s+send\\s+(?:it|that|this))"
    )
    private val SOFT_HEDGE_REGEX = Regex(
        "(?i)(?:언젠가|나중에\\s*밥\\s*한\\s*번|기회\\s*되면|시간\\s*될\\s*때\\s*한\\s*번|다음에\\s*언제|언제\\s*한\\s*번\\s*봐|maybe\\s+someday|sometime\\s+next\\s+month|no\\s+rush\\s+someday)"
    )
    private val FINANCIAL_PROMISE_REGEX = Regex(
        "(?i)(?:입금|송금|이체|정산|계좌|pay\\s+you\\s+back|wire\\s+you|transfer\\s+money)"
    )

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageFile = File(dir, FILE_NAME)
            loadLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            storageFile = file
            if (clearExisting) {
                records.clear()
                _commitments.value = emptyList()
                file.delete()
            } else {
                loadLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            records.clear()
            _commitments.value = emptyList()
            storageFile = null
            initialized = false
        }
    }

    fun listOpenCommitments(): List<SelfCommitmentRecord> =
        synchronized(lock) { records.filter { !it.completed } }

    /**
     * Captures a 1st-person self-promise from user Quick-Replies, MessagingStyle self-authored
     * messages (`"나"`/`"Me"`/`isSelfMessage`), or explicit Hanson chat notes (ZERO keylogging).
     */
    fun onSelfMessageObserved(
        context: Context?,
        packageName: String,
        roomOrTitle: String,
        counterparty: String,
        selfText: String,
        timestampMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): ProactiveProposal? {
        ensureInitialized(context)
        val trimmed = selfText.trim()
        if (trimmed.length < 3) return null

        if (UntrustedScreenDataGuard.detectPromptInjectionSignals(trimmed).isNotEmpty()) {
            PrivacyAndQuotaProofLedger.recordBlockedPromptInjection(nowMs = timestampMs)
            return null
        }
        if (NotificationThreadBuffer.containsHardSecret(trimmed) ||
            ConvenienceSecretGuard.containsSensitiveSecret(trimmed)
        ) {
            PrivacyAndQuotaProofLedger.recordBlockedSecret(nowMs = timestampMs)
            return null
        }
        if (SOFT_HEDGE_REGEX.containsMatchIn(trimmed)) return null
        if (!COMMITMENT_REGEX.containsMatchIn(trimmed)) return null

        PrivacyAndQuotaProofLedger.recordLocalZeroCostScan(nowMs = timestampMs)

        val safePkg = packageName.trim().ifEmpty { "com.hanson.mobileuse" }
        val sourceApp = AppDetailPlaybookRegistry.resolveAppLabelKo(safePkg)
            ?: safePkg.substringAfterLast('.').ifBlank { "메시지" }
        val safeRoom = roomOrTitle.trim().ifEmpty { counterparty.trim().ifEmpty { sourceApp } }
        val safeCounterparty = counterparty.trim().ifEmpty { safeRoom }

        val parsedDue = KoreanScheduleParser.parse(
            text = trimmed,
            referenceTimeMs = timestampMs,
            zoneId = zoneId,
            fallbackTitle = "${safeCounterparty}님께 한 약속"
        )
        val dueMs = parsedDue?.startTimeMs ?: (timestampMs + 4L * 3_600_000L)
        val isFinancial = FINANCIAL_PROMISE_REGEX.containsMatchIn(trimmed)
        if (isFinancial) {
            PrivacyAndQuotaProofLedger.recordFinanceReadOnlyLock(nowMs = timestampMs)
        }

        val fp = sha256Short("self_commitment", safePkg, safeRoom, safeCounterparty)
        val dueLabel = formatDueKo(dueMs, zoneId)
        val snippet = trimmed.replace(Regex("\\s+"), " ").take(120)

        val primaryAction = if (isFinancial) {
            ProposalAction(
                key = ACTION_MARK_DONE,
                labelKo = "직접 이체 후 완료 표시",
                kind = ProposalActionKind.SAVE_NOTE,
                payload = mapOf(
                    "key" to "내가한약속_${safeCounterparty.take(16)}",
                    "content" to "[읽기전용 금융 약속] ${safeCounterparty}님께: $snippet ($dueLabel)"
                )
            )
        } else {
            ProposalAction(
                key = ACTION_MARK_DONE,
                labelKo = "약속 완료 · 캘린더 리마인더",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to "[내가 한 약속] ${safeCounterparty}님께: ${snippet.take(24)}",
                    "start_time_ms" to dueMs.toString(),
                    "end_time_ms" to (dueMs + 30 * 60_000L).toString(),
                    "description" to "내가 보낸 메시지: \"$snippet\"",
                    "reminder_minutes" to "30"
                )
            )
        }

        val updateDraft = "${safeCounterparty}님, 말씀드린 건 확인 중이라 곧 공유드릴게요!"
        val secondaryActions = listOf(
            ProposalAction(
                key = ACTION_DRAFT_UPDATE,
                labelKo = "진행상황 답장 초안",
                kind = ProposalActionKind.SEND_QUICK_REPLY,
                payload = mapOf(
                    "packageName" to safePkg,
                    "roomOrTitle" to safeRoom,
                    "replyText" to updateDraft
                )
            ),
            ProposalAction(
                key = ACTION_RESCHEDULE,
                labelKo = "내일로 미루기",
                kind = ProposalActionKind.SNOOZE,
                payload = mapOf("minutes" to "1440")
            ),
            ProposalAction("dismiss", "완료·닫기", ProposalActionKind.DISMISS)
        )

        val proposalId = "commit_${timestampMs}_${idCounter.incrementAndGet()}"
        val rawProposal = ProactiveProposal(
            id = proposalId,
            fingerprint = fp,
            type = ProactiveProposalType.SELF_COMMITMENT_DEBT,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = safePkg,
            sender = "나 → $safeCounterparty",
            roomOrTitle = safeRoom,
            titleKo = "내가 한 약속: ${safeCounterparty}님께",
            summaryKo = "$sourceApp · 목표 $dueLabel" +
                if (isFinancial) " · 금융 약속(읽기전용 리마인더)" else " · 잊지 않도록 챙겨드릴게요",
            detailKo = "직접 남긴 약속 메시지를 로컬에서 기록했습니다.\n내용: \"$snippet\"\n예정 기한: $dueLabel",
            quotedExcerpt = snippet,
            confidence = if (parsedDue != null) 0.92 else 0.84,
            startTimeMs = dueMs,
            endTimeMs = dueMs + 30 * 60_000L,
            primaryAction = primaryAction,
            secondaryActions = secondaryActions,
            quickReplyDrafts = listOf(updateDraft, "조금 늦어져서 내일 오전까지 꼭 보내드릴게요!"),
            readOnlyAdvisory = isFinancial,
            createdAtMs = timestampMs,
            updatedAtMs = timestampMs
        )
        val savedProposal = ProactiveSecretaryEngine.registerExternalProposal(rawProposal, timestampMs)

        synchronized(lock) {
            val existingIdx = records.indexOfFirst { !it.completed && it.fingerprint == fp }
            val rec = if (existingIdx >= 0) {
                val ex = records.removeAt(existingIdx)
                ex.copy(
                    promiseText = snippet,
                    dueTimeMs = dueMs,
                    isFinancialPromise = ex.isFinancialPromise || isFinancial,
                    proposalId = savedProposal.id,
                    updatedAtMs = timestampMs
                )
            } else {
                SelfCommitmentRecord(
                    id = "c_${timestampMs}_${idCounter.incrementAndGet()}",
                    fingerprint = fp,
                    sourceApp = sourceApp,
                    packageName = safePkg,
                    counterparty = safeCounterparty,
                    roomOrTitle = safeRoom,
                    promiseText = snippet,
                    dueTimeMs = dueMs,
                    isFinancialPromise = isFinancial,
                    proposalId = savedProposal.id,
                    completed = false,
                    createdAtMs = timestampMs,
                    updatedAtMs = timestampMs
                )
            }
            records.add(0, rec)
            while (records.size > MAX_RECORDS) records.removeAt(records.lastIndex)
            persistLocked()
        }
        return savedProposal
    }

    fun markDone(
        commitmentOrProposalId: String,
        nowMs: Long = System.currentTimeMillis()
    ): SelfCommitmentRecord? {
        val target = commitmentOrProposalId.trim()
        synchronized(lock) {
            val idx = records.indexOfFirst { it.id == target || it.proposalId == target }
            if (idx < 0) return null
            val updated = records[idx].copy(completed = true, updatedAtMs = nowMs)
            records[idx] = updated
            persistLocked()
            if (updated.proposalId.isNotBlank()) {
                ProactiveSecretaryEngine.markExecuted(updated.proposalId, nowMs)
            }
            return updated
        }
    }

    fun rescheduleTomorrow(
        commitmentOrProposalId: String,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): SelfCommitmentRecord? {
        val target = commitmentOrProposalId.trim()
        val tomorrow10Am = Instant.ofEpochMilli(nowMs).atZone(zoneId)
            .toLocalDate().plusDays(1)
            .atTime(LocalTime.of(10, 0))
            .atZone(zoneId).toInstant().toEpochMilli()
        synchronized(lock) {
            val idx = records.indexOfFirst { it.id == target || it.proposalId == target }
            if (idx < 0) return null
            val updated = records[idx].copy(dueTimeMs = tomorrow10Am, updatedAtMs = nowMs)
            records[idx] = updated
            persistLocked()
            if (updated.proposalId.isNotBlank()) {
                ProactiveSecretaryEngine.snoozeProposal(updated.proposalId, 24L * 60L, nowMs)
            }
            return updated
        }
    }

    fun toA2uiJsonl(
        proposal: ProactiveProposal,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val surfaceId = "commitment_debt_${proposal.id}"
        val rootChildren = JSONArray()
            .put("cd_eyebrow").put("cd_title").put("cd_summary").put("cd_quote_card").put("cd_actions_row")
        val components = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            put(JSONObject().put("id", "cd_eyebrow").put("component", "Text").put("text", ptr("/commitment/eyebrow")).put("variant", "caption"))
            put(JSONObject().put("id", "cd_title").put("component", "Text").put("text", ptr("/commitment/titleKo")).put("variant", "h3"))
            put(JSONObject().put("id", "cd_summary").put("component", "Text").put("text", ptr("/commitment/summaryKo")).put("variant", "body"))
            put(JSONObject().put("id", "cd_quote_card").put("component", "Card").put("child", "cd_quote_txt"))
            put(JSONObject().put("id", "cd_quote_txt").put("component", "Text").put("text", ptr("/commitment/quotedExcerpt")).put("variant", "caption"))

            put(JSONObject().put("id", "cd_done_lbl").put("component", "Text").put("text", "완료").put("variant", "caption"))
            put(
                JSONObject().put("id", "cd_done_btn").put("component", "Button").put("variant", "primary").put("child", "cd_done_lbl")
                    .put("action", eventObj(ACTION_MARK_DONE, JSONObject().put("proposalId", ptr("/commitment/id"))))
            )
            put(JSONObject().put("id", "cd_draft_lbl").put("component", "Text").put("text", "진행상황 답장 초안").put("variant", "caption"))
            put(
                JSONObject().put("id", "cd_draft_btn").put("component", "Button").put("variant", "default").put("child", "cd_draft_lbl")
                    .put("action", eventObj(ACTION_DRAFT_UPDATE, JSONObject().put("proposalId", ptr("/commitment/id")).put("draft", ptr("/commitment/draft"))))
            )
            put(JSONObject().put("id", "cd_resched_lbl").put("component", "Text").put("text", "내일로 미루기").put("variant", "caption"))
            put(
                JSONObject().put("id", "cd_resched_btn").put("component", "Button").put("variant", "default").put("child", "cd_resched_lbl")
                    .put("action", eventObj(ACTION_RESCHEDULE, JSONObject().put("proposalId", ptr("/commitment/id"))))
            )
            put(
                JSONObject().put("id", "cd_actions_row").put("component", "Row")
                    .put("children", JSONArray().put("cd_done_btn").put("cd_draft_btn").put("cd_resched_btn"))
            )
        }
        val data = JSONObject()
            .put("id", proposal.id)
            .put("eyebrow", "내가 한 약속 · ${proposal.sourceApp}")
            .put("titleKo", proposal.titleKo)
            .put("summaryKo", proposal.summaryKo)
            .put("quotedExcerpt", proposal.quotedExcerpt)
            .put("draft", proposal.quickReplyDrafts.firstOrNull().orEmpty())

        return buildA2uiTripletJsonl(surfaceId, components, "/commitment", data, protocolVersion)
    }

    fun publishToA2uiRegistry(proposal: ProactiveProposal): String {
        val jsonl = toA2uiJsonl(proposal)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return "commitment_debt_${proposal.id}"
    }

    private fun formatDueKo(dueMs: Long, zoneId: ZoneId): String {
        val zdt = Instant.ofEpochMilli(dueMs).atZone(zoneId)
        return zdt.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
    }

    private fun loadLocked() {
        val file = storageFile ?: return
        records.clear()
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("commitments") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    SelfCommitmentRecord.fromJson(arr.optJSONObject(i))?.let { records.add(it) }
                }
            }
        }
        _commitments.value = records.toList()
    }

    private fun persistLocked() {
        _commitments.value = records.toList()
        val file = storageFile ?: return
        runCatching {
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            ConvenienceStorageHelper.writeAtomically(
                file,
                JSONObject().put("schemaVersion", 1).put("commitments", arr).toString(2)
            )
        }
    }
}

// =============================================================================
// #2. Free-Trial & Subscription Vampire Auto-Defense + 7-Day Return-Window Guard
// =============================================================================

data class DefenseChecklistBundle(
    val proposal: ProactiveProposal,
    val cancelChecklistKo: List<String>,
    val refundDraftKo: String,
    val d2AlarmTimeMs: Long,
    val subscriptionsUrl: String = SubscriptionAndExpiryDefenseEngine.PLAY_STORE_SUBSCRIPTIONS_URL,
    val isReturnWindowGuard: Boolean = false
)

object SubscriptionAndExpiryDefenseEngine {

    const val ACTION_SCHEDULE_D2_ALARM = "defense.schedule_d2_alarm"
    const val ACTION_OPEN_SUBS = "defense.open_subs"
    const val ACTION_DRAFT_REFUND = "defense.draft_refund"
    const val PLAY_STORE_SUBSCRIPTIONS_URL = "https://play.google.com/store/account/subscriptions"

    private val idCounter = AtomicLong(0L)

    private val FREE_TRIAL_OR_ADDON_REGEX = Regex(
        "(?i)(?:무료\\s*체험|첫\\s*달\\s*무료|(\\d{1,2})\\s*일\\s*무료|유료\\s*전환|free\\s*trial|add-?on|애드온\\s*구독)"
    )
    private val AUTO_RENEW_REGEX = Regex(
        "(?i)(?:자동\\s*갱신|정기\\s*결제\\s*예정|정기\\s*결제\\s*갱신|auto-?renew)"
    )
    private val TRIAL_DAYS_REGEX = Regex("(?i)(\\d{1,2})\\s*(?:일\\s*무료|[-\\s]?day\\s+free\\s+trial)")
    private val DELIVERY_DONE_REGEX = Regex("(?:배송\\s*완료|배송완료|로켓배송\\s*완료)")

    fun isExplicitTrialOrAddOnTrap(text: String): Boolean =
        FREE_TRIAL_OR_ADDON_REGEX.containsMatchIn(text)

    /**
     * Detects free-trial, hidden add-on, or auto-renewal traps. Strictly `readOnlyAdvisory = true`
     * (NEVER clicks banking/payment UI).
     */
    fun detectTrialOrRenewal(
        notification: InterceptedNotification,
        sanitizedText: String,
        sourceApp: String,
        referenceTimeMs: Long = notification.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): DefenseChecklistBundle? {
        val isExplicitTrial = FREE_TRIAL_OR_ADDON_REGEX.containsMatchIn(sanitizedText)
        val isAutoRenew = AUTO_RENEW_REGEX.containsMatchIn(sanitizedText)
        if (!isExplicitTrial && !isAutoRenew) return null

        val refZdt = Instant.ofEpochMilli(referenceTimeMs).atZone(zoneId)
        val parsedDate = KoreanScheduleParser.parse(sanitizedText, referenceTimeMs, zoneId)
        val trialEndZdt = when {
            parsedDate != null && parsedDate.hasExplicitDate ->
                Instant.ofEpochMilli(parsedDate.startTimeMs).atZone(zoneId)
            TRIAL_DAYS_REGEX.containsMatchIn(sanitizedText) -> {
                val days = TRIAL_DAYS_REGEX.find(sanitizedText)?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(3L, 90L) ?: 7L
                refZdt.plusDays(days)
            }
            sanitizedText.contains("첫 달") || sanitizedText.contains("첫달") || sanitizedText.contains("1개월") ->
                refZdt.plusDays(30)
            else -> refZdt.plusDays(7)
        }

        val d2Zdt = trialEndZdt.minusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0)
        val d2AlarmMs = maxOf(d2Zdt.toInstant().toEpochMilli(), referenceTimeMs + 3_600_000L)
        val endDateStr = trialEndZdt.format(DateTimeFormatter.ofPattern("MM/dd"))
        val d2DateStr = Instant.ofEpochMilli(d2AlarmMs).atZone(zoneId).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

        val checklist = listOf(
            "구독 관리 화면에서 기본 플랜 해지 여부 확인 (해지해도 남은 무료 기간 유지 여부 체크)",
            "숨은 애드온 구독 확인 (AI 보조 기능·추가 옵션 등 별도 자동결제 항목 확인)",
            "해지 완료 영수증·이메일 보관 및 결제 예정일($endDateStr) 재확인"
        )
        val refundDraft = "안녕하세요. ${notification.sender} 서비스의 무료체험·자동결제(${endDateStr} 예정)와 관련해 전자상거래법상 청약철회 및 정기결제 해지·환불 처리를 요청드립니다. 추가 애드온 구독이 포함된 경우 함께 해지 확인 부탁드립니다. (필요 시 소비자상담 공식 안내 참조)"

        val fp = sha256Short("trial_defense", notification.packageName, notification.sender, endDateStr)
        val id = "def_${referenceTimeMs}_${idCounter.incrementAndGet()}"
        val proposal = ProactiveProposal(
            id = id,
            fingerprint = fp,
            type = ProactiveProposalType.SUBSCRIPTION_TRIAL_DEFENSE,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = notification.roomOrTitle.ifBlank { notification.sender },
            titleKo = "무료체험·자동결제 D-2 해지 방어 (${notification.sender})",
            summaryKo = "결제 예정 $endDateStr · D-2 알림($d2DateStr) + 숨은 애드온 확인 체크리스트 (읽기전용)",
            detailKo = "자동결제 전환 전 D-2 해지 알람과 구독 관리 바로가기를 준비했습니다.\n결제·금융 화면은 자동 클릭하지 않으며 직접 확인하실 수 있도록 안내만 제공합니다.\n원문: ${sanitizedText.take(120)}",
            quotedExcerpt = sanitizedText.take(140),
            confidence = 0.93,
            startTimeMs = d2AlarmMs,
            endTimeMs = d2AlarmMs + 30 * 60_000L,
            primaryAction = ProposalAction(
                key = ACTION_SCHEDULE_D2_ALARM,
                labelKo = "D-2 해지 알람·캘린더 등록",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to "[해지 방어 D-2] ${notification.sender} 자동결제·애드온 확인",
                    "start_time_ms" to d2AlarmMs.toString(),
                    "end_time_ms" to (d2AlarmMs + 30 * 60_000L).toString(),
                    "description" to checklist.joinToString(" / "),
                    "reminder_minutes" to "60"
                )
            ),
            secondaryActions = listOf(
                ProposalAction(
                    key = ACTION_OPEN_SUBS,
                    labelKo = "Play 구독 관리 열기",
                    kind = ProposalActionKind.OPEN_LINK,
                    payload = mapOf("url" to PLAY_STORE_SUBSCRIPTIONS_URL)
                ),
                ProposalAction(
                    key = ACTION_DRAFT_REFUND,
                    labelKo = "청약철회·환불 요청 초안 저장",
                    kind = ProposalActionKind.SAVE_NOTE,
                    payload = mapOf("key" to "환불초안_${notification.sender.take(16)}", "content" to refundDraft)
                ),
                ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS)
            ),
            readOnlyAdvisory = true,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
        return DefenseChecklistBundle(
            proposal = proposal,
            cancelChecklistKo = checklist,
            refundDraftKo = refundDraft,
            d2AlarmTimeMs = d2AlarmMs,
            subscriptionsUrl = PLAY_STORE_SUBSCRIPTIONS_URL,
            isReturnWindowGuard = false
        )
    }

    /**
     * E-commerce 7-day return-window guard: when `"배송 완료"` arrives, creates a D-5 check reminder
     * 2 days before Korea's 7-day statutory return window closes (`readOnlyAdvisory = true`).
     */
    fun detectReturnWindowGuard(
        notification: InterceptedNotification,
        sanitizedText: String,
        sourceApp: String,
        referenceTimeMs: Long = notification.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): DefenseChecklistBundle? {
        if (!DELIVERY_DONE_REGEX.containsMatchIn(sanitizedText)) return null
        val safeText = NotificationThreadBuffer.redactDoorAndLockerPins(sanitizedText)

        val refZdt = Instant.ofEpochMilli(referenceTimeMs).atZone(zoneId)
        val d5Zdt = refZdt.plusDays(5).withHour(19).withMinute(0).withSecond(0).withNano(0)
        val d5Ms = d5Zdt.toInstant().toEpochMilli()
        val deadlineStr = refZdt.plusDays(7).format(DateTimeFormatter.ofPattern("MM/dd"))
        val d5Str = d5Zdt.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))

        val checklist = listOf(
            "수령 상품 파손·오배송·사이즈 확인 (7일 단순변심 반품 마감: $deadlineStr)",
            "택·포장 훼손 전 반품·교환 필요 여부 결정",
            "반품 신청 시 쇼핑몰 공식 주문내역에서 직접 접수"
        )
        val refundDraft = "안녕하세요. ${sourceApp} 배송 완료 상품에 대해 전자상거래법상 7일 이내 청약철회(반품·환불) 신청을 요청드립니다."
        val fp = sha256Short("return_guard", notification.packageName, sourceApp, deadlineStr)
        val id = "ret_${referenceTimeMs}_${idCounter.incrementAndGet()}"

        val proposal = ProactiveProposal(
            id = id,
            fingerprint = fp,
            type = ProactiveProposalType.SUBSCRIPTION_TRIAL_DEFENSE,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = notification.roomOrTitle.ifBlank { sourceApp },
            titleKo = "D-5 반품 기한 확인 가드 ($sourceApp)",
            summaryKo = "7일 반품 마감 $deadlineStr · D-5($d5Str) 확인 리마인더 (읽기전용)",
            detailKo = "$sourceApp 배송 완료를 기준으로 7일 반품 기한 만료 2일 전 리마인더를 제안합니다.\n내용: ${safeText.take(120)}",
            quotedExcerpt = safeText.take(140),
            confidence = 0.88,
            startTimeMs = d5Ms,
            endTimeMs = d5Ms + 30 * 60_000L,
            primaryAction = ProposalAction(
                key = ACTION_SCHEDULE_D2_ALARM,
                labelKo = "D-5 반품 확인 리마인더 등록",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to "[반품 기한 D-5] $sourceApp 수령 상품 확인 (마감 $deadlineStr)",
                    "start_time_ms" to d5Ms.toString(),
                    "end_time_ms" to (d5Ms + 30 * 60_000L).toString(),
                    "description" to checklist.joinToString(" / "),
                    "reminder_minutes" to "60"
                )
            ),
            secondaryActions = listOf(
                ProposalAction(
                    key = ACTION_DRAFT_REFUND,
                    labelKo = "청약철회 요청 초안 저장",
                    kind = ProposalActionKind.SAVE_NOTE,
                    payload = mapOf("key" to "반품초안_$sourceApp", "content" to refundDraft)
                ),
                ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS)
            ),
            readOnlyAdvisory = true,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
        return DefenseChecklistBundle(
            proposal = proposal,
            cancelChecklistKo = checklist,
            refundDraftKo = refundDraft,
            d2AlarmTimeMs = d5Ms,
            subscriptionsUrl = PLAY_STORE_SUBSCRIPTIONS_URL,
            isReturnWindowGuard = true
        )
    }

    fun toA2uiJsonl(
        bundle: DefenseChecklistBundle,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val p = bundle.proposal
        val surfaceId = "trial_defense_${p.id}"
        val rootChildren = JSONArray().put("td_title").put("td_summary")
        val components = JSONArray().apply {
            put(JSONObject().put("id", "td_title").put("component", "Text").put("text", ptr("/defense/titleKo")).put("variant", "h3"))
            put(JSONObject().put("id", "td_summary").put("component", "Text").put("text", ptr("/defense/summaryKo")).put("variant", "body"))
        }
        val itemsArr = JSONArray()
        bundle.cancelChecklistKo.forEachIndexed { idx, line ->
            itemsArr.put(JSONObject().put("label", line).put("checked", false))
            val cbId = "td_cb_$idx"
            rootChildren.put(cbId)
            components.put(
                JSONObject().put("id", cbId).put("component", "CheckBox")
                    .put("label", ptr("/defense/checklist/$idx/label"))
                    .put("value", ptr("/defense/checklist/$idx/checked"))
            )
        }

        val btnRow = JSONArray().put("td_alarm_btn").put("td_subs_btn").put("td_refund_btn")
        components.put(JSONObject().put("id", "td_alarm_lbl").put("component", "Text").put("text", "D-2 해지 알람").put("variant", "caption"))
        components.put(
            JSONObject().put("id", "td_alarm_btn").put("component", "Button").put("variant", "primary").put("child", "td_alarm_lbl")
                .put("action", eventObj(ACTION_SCHEDULE_D2_ALARM, JSONObject().put("proposalId", ptr("/defense/id")).put("alarmTimeMs", ptr("/defense/d2AlarmTimeMs"))))
        )
        components.put(JSONObject().put("id", "td_subs_lbl").put("component", "Text").put("text", "구독 관리 열기").put("variant", "caption"))
        components.put(
            JSONObject().put("id", "td_subs_btn").put("component", "Button").put("variant", "default").put("child", "td_subs_lbl")
                .put("action", eventObj(ACTION_OPEN_SUBS, JSONObject().put("url", ptr("/defense/subscriptionsUrl"))))
        )
        components.put(JSONObject().put("id", "td_refund_lbl").put("component", "Text").put("text", "환불 요청 초안").put("variant", "caption"))
        components.put(
            JSONObject().put("id", "td_refund_btn").put("component", "Button").put("variant", "default").put("child", "td_refund_lbl")
                .put("action", eventObj(ACTION_DRAFT_REFUND, JSONObject().put("proposalId", ptr("/defense/id")).put("draft", ptr("/defense/refundDraftKo"))))
        )
        rootChildren.put("td_actions_row")
        components.put(JSONObject().put("id", "td_actions_row").put("component", "Row").put("children", btnRow))

        val allComponents = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            for (i in 0 until components.length()) put(components.get(i))
        }
        val data = JSONObject()
            .put("id", p.id)
            .put("titleKo", p.titleKo)
            .put("summaryKo", p.summaryKo)
            .put("d2AlarmTimeMs", bundle.d2AlarmTimeMs)
            .put("subscriptionsUrl", bundle.subscriptionsUrl)
            .put("refundDraftKo", bundle.refundDraftKo)
            .put("checklist", itemsArr)

        return buildA2uiTripletJsonl(surfaceId, allComponents, "/defense", data, protocolVersion)
    }

    fun publishToA2uiRegistry(bundle: DefenseChecklistBundle): String {
        val jsonl = toA2uiJsonl(bundle)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return "trial_defense_${bundle.proposal.id}"
    }
}

// =============================================================================
// #4. Smishing / Impersonation Pre-Shield + Plain-Korean Notice Translator
// =============================================================================

data class SmishingRiskReport(
    val score: Int,
    val isHighRisk: Boolean,
    val redFlagsKo: List<String>,
    val detectedUrls: List<String>,
    val officialGoKrAllowed: Boolean = false
)

data class PlainNoticeTranslation(
    val domainKo: String,
    val oneLinePlainKo: String,
    val actionAndDeadlineKo: String,
    val matchedTermsKo: List<String>
)

object SmishingAndPlainKoreanEngine {

    const val ACTION_DIAL_REAL_PERSON = "shield.dial_real_person"
    const val ACTION_BOHONARA_GUIDE = "shield.bohonara_guide"
    const val ACTION_DISMISS_SAFE = "shield.dismiss_safe"

    private val idCounter = AtomicLong(0L)

    private val URL_REGEX = Regex("(?i)https?://[^\\s<>\"'\\])]+")
    private val INTERNATIONAL_PREFIX_REGEX = Regex("(?:\\[\\s*국제발신\\s*\\]|\\[\\s*국외발신\\s*\\])")
    private val SMISHING_LURE_REGEX = Regex(
        "(?i)(?:부고|청첩장|택배|반송|주소\\s*불명|국세청|환급금|과태료|범칙금|클로드\\s*듀엣|챗지피티\\s*닷츠|claude\\s*duet|chatgpt\\s*dots)"
    )
    private val SUSPICIOUS_URL_REGEX = Regex(
        "(?i)(?:bit\\.ly|tinyurl\\.com|t\\.co|me2\\.do|han\\.gl|vo\\.la|cutt\\.ly|rb\\.gy|is\\.gd|url\\.kr|c11\\.kr|xn--|\\.apk(?:\\?|$)|https?://\\d{1,3}(?:\\.\\d{1,3}){3}|go-kr|gov-kr|kaka0|nav3r|claude-duet|chatgpt-dots)"
    )
    private val FAMILY_IMPERSONATION_OR_REMOTE_REGEX = Regex(
        "(?i)(?:액정.{0,12}깨|폰.{0,8}고장|원격.{0,10}설치|원격\\s*제어|anydesk|팀뷰어|teamviewer|애니데스크|대신.{0,10}송금|급하게.{0,10}이체|상품권.{0,10}핀)"
    )

    // Plain-Korean dense notice glossary
    private val NOTICE_TRIGGER_REGEX = Regex(
        "(?:국민건강보험|건강보험공단|국세청|본인부담|면책|진료\\s*예약|금식|위약금|약정\\s*만료|할인반환금|기한\\s*후\\s*신고)"
    )
    private val GLOSSARY: List<Pair<String, String>> = listOf(
        "국민건강보험" to "건강보험 공단 공식 안내",
        "국세청" to "세금 신고·환급·납부 안내",
        "본인부담" to "보험 적용 후 본인이 직접 결제할 금액",
        "면책" to "보험 보장이 제외되는 기간 또는 항목",
        "진료예약" to "병원 방문 예약 일정",
        "진료 예약" to "병원 방문 예약 일정",
        "금식" to "검사 전 음식·음료 섭취 금지 주의",
        "위약금" to "약정 중도 해지 시 발생하는 반환 비용",
        "할인반환금" to "약정 중도 해지 시 돌려내야 하는 할인액",
        "약정만료" to "할인 약정 종료 시점(재약정·요금제 점검 권장)",
        "약정 만료" to "할인 약정 종료 시점(재약정·요금제 점검 권장)",
        "기한 후 신고" to "마감 경과 신고(가산세 발생 여부 확인 필요)"
    )

    /**
     * Zero-network pre-tap smishing risk scorer (`0..100`).
     * NEVER fetches or resolves external URLs (zero SSRF).
     */
    fun scoreSmishingRisk(
        sender: String,
        rawText: String,
        packageName: String = ""
    ): SmishingRiskReport {
        val text = rawText.trim()
        if (text.isEmpty()) {
            return SmishingRiskReport(0, false, emptyList(), emptyList(), false)
        }
        val urls = URL_REGEX.findAll(text).map { it.value.trimEnd('.', ',', ')', ']') }.toList()
        val hasUrl = urls.isNotEmpty()
        val hasFamilyOrRemoteLure = FAMILY_IMPERSONATION_OR_REMOTE_REGEX.containsMatchIn(text)

        // Strict .go.kr official government domain allowlist (only when all URLs are clean .go.kr & no remote/transfer lure)
        val allOfficialGoKr = hasUrl && !hasFamilyOrRemoteLure && urls.all { url ->
            val host = extractHost(url)
            host.endsWith(".go.kr") && !host.contains("@") && !url.contains(".apk", ignoreCase = true)
        }
        if (allOfficialGoKr) {
            return SmishingRiskReport(0, false, emptyList(), urls, officialGoKrAllowed = true)
        }

        var score = 0
        val flags = mutableListOf<String>()

        if (INTERNATIONAL_PREFIX_REGEX.containsMatchIn(text) || INTERNATIONAL_PREFIX_REGEX.containsMatchIn(sender)) {
            score += 35
            flags.add("국제·국외 발신 표시 감지 (+35)")
        }
        if (hasUrl && SMISHING_LURE_REGEX.containsMatchIn(text)) {
            score += 40
            flags.add("부고·청첩장·택배·국세청·가짜 AI 체험 미끼 키워드와 링크 동시 포함 (+40)")
        }
        if (hasUrl && urls.any { SUSPICIOUS_URL_REGEX.containsMatchIn(it) }) {
            score += 45
            flags.add("단축 URL·IP 주소·Punycode·APK·유사 도메인 의심 링크 (+45)")
        }
        if (hasFamilyOrRemoteLure) {
            score += 55
            flags.add("지인 사칭 긴급 송금 또는 원격 제어 앱 설치 유도 (+55)")
        }

        val capped = score.coerceIn(0, 100)
        return SmishingRiskReport(
            score = capped,
            isHighRisk = capped >= 55,
            redFlagsKo = flags,
            detectedUrls = urls,
            officialGoKrAllowed = false
        )
    }

    fun buildScamShieldProposal(
        notification: InterceptedNotification,
        report: SmishingRiskReport,
        sourceApp: String,
        referenceTimeMs: Long = notification.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    ): ProactiveProposal {
        val maskedExcerpt = URL_REGEX.replace(notification.messageText.trim(), "[의심 링크 차단됨]").take(140)
        val fp = sha256Short("scam_shield", notification.packageName, notification.sender, maskedExcerpt.take(36))
        val id = "scam_${referenceTimeMs}_${idCounter.incrementAndGet()}"
        return ProactiveProposal(
            id = id,
            fingerprint = fp,
            type = ProactiveProposalType.SCAM_PRE_SHIELD,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = notification.roomOrTitle.ifBlank { notification.sender },
            titleKo = "스미싱·사칭 주의 (위험도 ${report.score}/100)",
            summaryKo = "링크 클릭·원격앱 설치 금지 · ${report.redFlagsKo.firstOrNull().orEmpty()}",
            detailKo = buildString {
                append("위험도 ${report.score}/100 — 절대 링크를 누르거나 원격 제어 앱을 설치하지 마세요.\n")
                append("감지된 위험 신호:\n")
                report.redFlagsKo.forEach { append("- $it\n") }
                append("확인 방법: 지인에게 기존 저장 번호로 직접 전화하거나 보호나라(KISA) 및 공식 신고 안내를 이용하세요.")
            }.trim(),
            quotedExcerpt = maskedExcerpt,
            confidence = (report.score / 100.0).coerceIn(0.75, 0.99),
            primaryAction = ProposalAction(
                key = ACTION_BOHONARA_GUIDE,
                labelKo = "보호나라(KISA) 및 공식 신고 안내",
                kind = ProposalActionKind.SAVE_NOTE,
                payload = mapOf(
                    "key" to "스미싱차단_${notification.sender.take(14)}",
                    "content" to "위험도 ${report.score}/100 차단 기록 · 링크 미클릭 · 보호나라(KISA) 및 공식 신고 안내 참조"
                )
            ),
            secondaryActions = listOf(
                ProposalAction(
                    key = ACTION_DISMISS_SAFE,
                    labelKo = "클릭 안 하고 안전하게 닫기",
                    kind = ProposalActionKind.DISMISS
                )
            ),
            readOnlyAdvisory = true,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    fun toScamShieldA2uiJsonl(
        proposal: ProactiveProposal,
        report: SmishingRiskReport,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val surfaceId = "scam_shield_${proposal.id}"
        val rootChildren = JSONArray()
            .put("ss_banner").put("ss_summary").put("ss_flags_card").put("ss_actions_row")
        val components = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            put(JSONObject().put("id", "ss_banner").put("component", "Text").put("text", ptr("/scam/banner")).put("variant", "h2"))
            put(JSONObject().put("id", "ss_summary").put("component", "Text").put("text", ptr("/scam/summaryKo")).put("variant", "body"))
            put(JSONObject().put("id", "ss_flags_card").put("component", "Card").put("child", "ss_flags_txt"))
            put(JSONObject().put("id", "ss_flags_txt").put("component", "Text").put("text", ptr("/scam/flagsText")).put("variant", "caption"))

            put(JSONObject().put("id", "ss_dial_lbl").put("component", "Text").put("text", "지인에게 직접 전화 확인").put("variant", "caption"))
            put(
                JSONObject().put("id", "ss_dial_btn").put("component", "Button").put("variant", "primary").put("child", "ss_dial_lbl")
                    .put("action", eventObj(ACTION_DIAL_REAL_PERSON, JSONObject().put("proposalId", ptr("/scam/id")).put("sender", ptr("/scam/sender"))))
            )
            put(JSONObject().put("id", "ss_guide_lbl").put("component", "Text").put("text", "보호나라(KISA) 및 공식 신고 안내").put("variant", "caption"))
            put(
                JSONObject().put("id", "ss_guide_btn").put("component", "Button").put("variant", "default").put("child", "ss_guide_lbl")
                    .put("action", eventObj(ACTION_BOHONARA_GUIDE, JSONObject().put("proposalId", ptr("/scam/id"))))
            )
            put(JSONObject().put("id", "ss_safe_lbl").put("component", "Text").put("text", "클릭 안 하고 닫기").put("variant", "caption"))
            put(
                JSONObject().put("id", "ss_safe_btn").put("component", "Button").put("variant", "default").put("child", "ss_safe_lbl")
                    .put("action", eventObj(ACTION_DISMISS_SAFE, JSONObject().put("proposalId", ptr("/scam/id"))))
            )
            put(
                JSONObject().put("id", "ss_actions_row").put("component", "Row")
                    .put("children", JSONArray().put("ss_dial_btn").put("ss_guide_btn").put("ss_safe_btn"))
            )
        }
        val data = JSONObject()
            .put("id", proposal.id)
            .put("sender", proposal.sender)
            .put("banner", "위험도 ${report.score}/100 · 링크 절대 클릭 금지")
            .put("summaryKo", proposal.summaryKo)
            .put("flagsText", report.redFlagsKo.joinToString("\n") { "• $it" })

        return buildA2uiTripletJsonl(surfaceId, components, "/scam", data, protocolVersion)
    }

    /**
     * Deterministic plain-Korean translator for dense government, medical, insurance, and telecom notices.
     */
    fun translateNotice(rawText: String): PlainNoticeTranslation? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || !NOTICE_TRIGGER_REGEX.containsMatchIn(trimmed)) return null

        val matched = mutableListOf<String>()
        val explanations = mutableListOf<String>()
        for ((term, plain) in GLOSSARY) {
            if (trimmed.contains(term)) {
                matched.add(term)
                explanations.add("${term}(${plain})")
            }
        }
        if (matched.isEmpty()) return null

        val domainKo = when {
            trimmed.contains("진료") || trimmed.contains("금식") || trimmed.contains("병원") -> "병원·진료 안내"
            trimmed.contains("국민건강보험") || trimmed.contains("본인부담") || trimmed.contains("면책") -> "건강·보험 안내"
            trimmed.contains("국세청") || trimmed.contains("신고") -> "세무·공공 안내"
            trimmed.contains("위약금") || trimmed.contains("약정") || trimmed.contains("할인반환금") -> "통신·약정 안내"
            else -> "생활 행정 안내"
        }
        val oneLine = "${domainKo} 쉬운 정리: ${explanations.take(3).joinToString(", ")} 관련 통지입니다."
        val actionHint = when {
            trimmed.contains("금식") -> "검사 전 금식 시작 시간을 캘린더·알람으로 챙겨두세요."
            trimmed.contains("약정") || trimmed.contains("위약금") -> "만료일과 위약금 조건을 확인한 뒤 요금제 변경 여부를 결정하세요."
            trimmed.contains("국세청") || trimmed.contains("신고") -> "공식 .go.kr 앱/홈페이지에서 마감일 전 신고·납부 상태를 확인하세요."
            else -> "핵심 금액과 기한을 원문과 대조해 메모나 캘린더에 저장해 두세요."
        }
        return PlainNoticeTranslation(
            domainKo = domainKo,
            oneLinePlainKo = oneLine,
            actionAndDeadlineKo = actionHint,
            matchedTermsKo = matched.distinct()
        )
    }

    fun buildPlainNoticeProposal(
        notification: InterceptedNotification,
        translation: PlainNoticeTranslation,
        sourceApp: String,
        referenceTimeMs: Long = notification.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): ProactiveProposal {
        val parsed = KoreanScheduleParser.parse(notification.messageText, referenceTimeMs, zoneId)
        val fp = sha256Short("plain_notice", notification.packageName, translation.domainKo, notification.messageText.take(36))
        val id = "plain_${referenceTimeMs}_${idCounter.incrementAndGet()}"
        val primary = if (parsed != null && parsed.hasExplicitDate) {
            ProposalAction(
                key = "save_plain_notice_calendar",
                labelKo = "일정·주의사항 캘린더 등록",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to "[${translation.domainKo}] ${translation.matchedTermsKo.joinToString("·")}",
                    "start_time_ms" to parsed.startTimeMs.toString(),
                    "end_time_ms" to parsed.endTimeMs.toString(),
                    "description" to "${translation.oneLinePlainKo} / ${translation.actionAndDeadlineKo}",
                    "reminder_minutes" to "60"
                )
            )
        } else {
            ProposalAction(
                key = "save_plain_notice_note",
                labelKo = "쉬운 정리 메모 저장",
                kind = ProposalActionKind.SAVE_NOTE,
                payload = mapOf(
                    "key" to "쉬운통역_${translation.domainKo}",
                    "content" to "${translation.oneLinePlainKo} (${translation.actionAndDeadlineKo})"
                )
            )
        }
        return ProactiveProposal(
            id = id,
            fingerprint = fp,
            type = ProactiveProposalType.PLAIN_NOTICE_TRANSLATION,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = notification.roomOrTitle.ifBlank { translation.domainKo },
            titleKo = "쉬운 말 통역 · ${translation.domainKo}",
            summaryKo = translation.oneLinePlainKo,
            detailKo = "${translation.oneLinePlainKo}\n해야 할 일: ${translation.actionAndDeadlineKo}\n원문: ${notification.messageText.take(140)}",
            quotedExcerpt = notification.messageText.take(140),
            confidence = 0.89,
            startTimeMs = parsed?.startTimeMs,
            endTimeMs = parsed?.endTimeMs,
            primaryAction = primary,
            secondaryActions = listOf(
                ProposalAction("snooze_60m", "1시간 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "60")),
                ProposalAction("dismiss", "확인 완료", ProposalActionKind.DISMISS)
            ),
            readOnlyAdvisory = true,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun extractHost(url: String): String {
        return url.substringAfter("://", "")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore(":")
            .lowercase(Locale.ROOT)
    }
}

// =============================================================================
// #5. Zero-Guilt Relationship & Social Care Cadence ("인간관계·안부 텀 케어")
// =============================================================================

data class VipContactCadence(
    val id: String,
    val name: String,
    val relationLabelKo: String = "소중한 분",
    val cadenceDays: Int = 14,
    val preferredPackage: String = "com.kakao.talk",
    val phoneNumber: String = "",
    val lastContactMs: Long = 0L,
    val lifeEventCues: List<String> = emptyList(),
    val snoozedUntilMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("relationLabelKo", relationLabelKo)
        put("cadenceDays", cadenceDays.coerceIn(1, 365))
        put("preferredPackage", preferredPackage)
        put("phoneNumber", phoneNumber)
        put("lastContactMs", lastContactMs)
        put("lifeEventCues", JSONArray(lifeEventCues))
        put("snoozedUntilMs", snoozedUntilMs)
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): VipContactCadence? {
            if (obj == null) return null
            val id = obj.optString("id", "").trim()
            val name = obj.optString("name", "").trim()
            if (id.isEmpty() || name.isEmpty()) return null
            val cues = mutableListOf<String>()
            obj.optJSONArray("lifeEventCues")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optString(i, "").trim()
                    if (c.isNotEmpty()) cues.add(c)
                }
            }
            return VipContactCadence(
                id = id,
                name = name,
                relationLabelKo = obj.optString("relationLabelKo", "소중한 분").ifBlank { "소중한 분" },
                cadenceDays = obj.optInt("cadenceDays", 14).coerceIn(1, 365),
                preferredPackage = obj.optString("preferredPackage", "com.kakao.talk").ifBlank { "com.kakao.talk" },
                phoneNumber = obj.optString("phoneNumber", ""),
                lastContactMs = obj.optLong("lastContactMs", 0L),
                lifeEventCues = cues,
                snoozedUntilMs = obj.optLong("snoozedUntilMs", 0L),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
            )
        }
    }
}

object RelationshipCareCadenceEngine {

    const val ACTION_OPEN_CHAT = "care.open_chat"
    const val ACTION_OPEN_DIALER = "care.open_dialer"
    const val ACTION_SNOOZE_WEEKEND = "care.snooze_weekend"
    const val MAX_VIPS = 25

    private const val FILE_NAME = "hanson_vip_cadence.json"
    private const val DAY_MS = 86_400_000L

    private val lock = Any()
    private val idCounter = AtomicLong(0L)

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    private val vipList = mutableListOf<VipContactCadence>()
    private val _vipContacts = MutableStateFlow<List<VipContactCadence>>(emptyList())
    val vipContacts: StateFlow<List<VipContactCadence>> = _vipContacts.asStateFlow()

    private val LIFE_EVENT_CUE_REGEX = Regex(
        "(?:생일|병원|수술|입원|면접|시험|합격|출국|여행|이사|결혼|출산|졸업)"
    )

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageFile = File(dir, FILE_NAME)
            loadLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, FILE_NAME)
            storageFile = file
            if (clearExisting) {
                vipList.clear()
                _vipContacts.value = emptyList()
                file.delete()
            } else {
                loadLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            vipList.clear()
            _vipContacts.value = emptyList()
            storageFile = null
            initialized = false
        }
    }

    fun listVips(): List<VipContactCadence> = synchronized(lock) { vipList.toList() }

    /**
     * Opt-in local VIP circle management (max 25 VIPs, 100% local, zero cloud sync).
     */
    fun upsertVip(vip: VipContactCadence): VipContactCadence? {
        val cleanName = vip.name.trim()
        if (cleanName.isEmpty()) return null
        if (ConvenienceSecretGuard.containsSensitiveSecret(cleanName)) return null
        synchronized(lock) {
            val existingIdx = vipList.indexOfFirst {
                it.id == vip.id || it.name.equals(cleanName, ignoreCase = true)
            }
            val normalized = vip.copy(
                id = vip.id.trim().ifEmpty { "vip_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}" },
                name = cleanName.take(32),
                cadenceDays = vip.cadenceDays.coerceIn(1, 365)
            )
            if (existingIdx >= 0) {
                vipList[existingIdx] = normalized
            } else {
                if (vipList.size >= MAX_VIPS) return null
                vipList.add(normalized)
            }
            persistLocked()
            return normalized
        }
    }

    fun removeVip(idOrName: String): Boolean {
        val target = idOrName.trim()
        if (target.isEmpty()) return false
        synchronized(lock) {
            val removed = vipList.removeAll { it.id == target || it.name.equals(target, ignoreCase = true) }
            if (removed) persistLocked()
            return removed
        }
    }

    fun snoozeUntilWeekend(idOrName: String, nowMs: Long = System.currentTimeMillis()): VipContactCadence? {
        val target = idOrName.trim()
        synchronized(lock) {
            val idx = vipList.indexOfFirst { it.id == target || it.name.equals(target, ignoreCase = true) }
            if (idx < 0) return null
            val updated = vipList[idx].copy(snoozedUntilMs = nowMs + 3L * DAY_MS)
            vipList[idx] = updated
            persistLocked()
            return updated
        }
    }

    /**
     * Updates `lastContactMs` and extracts life-event memory cues ONLY for explicitly pinned VIPs.
     */
    fun recordContactTouch(
        contactName: String,
        messageText: String = "",
        timestampMs: Long = System.currentTimeMillis()
    ): VipContactCadence? {
        val cleanName = contactName.trim()
        if (cleanName.isEmpty()) return null
        synchronized(lock) {
            val idx = vipList.indexOfFirst { vip ->
                cleanName.equals(vip.name, ignoreCase = true) ||
                    cleanName.contains(vip.name, ignoreCase = true) ||
                    vip.name.contains(cleanName, ignoreCase = true)
            }
            if (idx < 0) return null
            val existing = vipList[idx]
            val nextCues = existing.lifeEventCues.toMutableList()
            val cleanMsg = messageText.trim()
            if (cleanMsg.isNotEmpty() &&
                LIFE_EVENT_CUE_REGEX.containsMatchIn(cleanMsg) &&
                !ConvenienceSecretGuard.containsSensitiveSecret(cleanMsg) &&
                UntrustedScreenDataGuard.detectPromptInjectionSignals(cleanMsg).isEmpty()
            ) {
                val cueSnippet = cleanMsg.replace(Regex("\\s+"), " ").take(48)
                if (nextCues.none { it == cueSnippet }) {
                    nextCues.add(0, cueSnippet)
                    while (nextCues.size > 5) nextCues.removeAt(nextCues.lastIndex)
                }
            }
            val updated = existing.copy(
                lastContactMs = maxOf(existing.lastContactMs, timestampMs),
                lifeEventCues = nextCues
            )
            vipList[idx] = updated
            persistLocked()
            return updated
        }
    }

    /**
     * Scans opt-in VIPs for overdue cadence nudges.
     * HARDLINE: NEVER auto-sends messages (`readOnlyAdvisory = true`, zero `SEND_QUICK_REPLY` primary action).
     */
    fun scanOverdueVips(nowMs: Long = System.currentTimeMillis()): List<ProactiveProposal> {
        val snapshot = listVips()
        val emitted = mutableListOf<ProactiveProposal>()
        for (vip in snapshot) {
            if (vip.snoozedUntilMs > nowMs) continue
            val elapsedDays = if (vip.lastContactMs > 0L) {
                ((nowMs - vip.lastContactMs) / DAY_MS).coerceAtLeast(0L)
            } else {
                vip.cadenceDays.toLong()
            }
            if (elapsedDays < vip.cadenceDays) continue

            val latestCue = vip.lifeEventCues.firstOrNull().orEmpty()
            val starters = if (latestCue.isNotBlank()) {
                listOf(
                    "${vip.name}, 지난번 말씀하신 '${latestCue.take(24)}' 잘 지내고 계신지 안부 여쭤봐요!",
                    "${vip.name}, 요즘 어떻게 지내세요? 시간 되실 때 통화나 식사 한번 해요."
                )
            } else {
                listOf(
                    "${vip.name}, 요즘 잘 지내고 계신가요? 문득 생각나서 안부 인사드려요!",
                    "${vip.name}, 이번 주말에 잠깐 통화 가능하실까요?"
                )
            }
            val fp = sha256Short("care_nudge", vip.id, (nowMs / (3L * DAY_MS)).toString())
            val proposal = ProactiveProposal(
                id = "care_${nowMs}_${idCounter.incrementAndGet()}",
                fingerprint = fp,
                type = ProactiveProposalType.RELATIONSHIP_CARE_NUDGE,
                status = ProactiveProposalStatus.PENDING,
                sourceApp = "안부 케어(로컬)",
                packageName = vip.preferredPackage,
                sender = vip.name,
                roomOrTitle = "${vip.name} (${vip.relationLabelKo})",
                titleKo = "안부 케어 · ${vip.name} (${elapsedDays}일 전 연락)",
                summaryKo = buildString {
                    append("${vip.cadenceDays}일 주기 도달 · 자동 발송 없이 직접 연락만 도와드려요")
                    if (latestCue.isNotBlank()) append(" · 최근 메모: ${latestCue.take(24)}")
                },
                detailKo = buildString {
                    append("마지막 연락으로부터 ${elapsedDays}일이 지났습니다.\n")
                    if (latestCue.isNotBlank()) append("기억해 둔 소식: $latestCue\n")
                    append("원칙: AI가 대신 안부 문자를 자동 발송하지 않으며, 직접 열어 보내실 수 있는 따뜻한 인사 초안만 제안합니다.")
                },
                quotedExcerpt = latestCue,
                confidence = 0.85,
                primaryAction = ProposalAction(
                    key = ACTION_OPEN_CHAT,
                    labelKo = "대화방 열기 (직접 인사)",
                    kind = ProposalActionKind.SAVE_NOTE,
                    payload = mapOf(
                        "vipId" to vip.id,
                        "packageName" to vip.preferredPackage,
                        "name" to vip.name,
                        "starterDraft" to starters.first()
                    )
                ),
                secondaryActions = listOf(
                    ProposalAction(
                        key = ACTION_OPEN_DIALER,
                        labelKo = "전화 걸기",
                        kind = ProposalActionKind.OPEN_LINK,
                        payload = mapOf("url" to if (vip.phoneNumber.isNotBlank()) "tel:${vip.phoneNumber}" else "")
                    ),
                    ProposalAction(
                        key = ACTION_SNOOZE_WEEKEND,
                        labelKo = "주말로 미루기",
                        kind = ProposalActionKind.SNOOZE,
                        payload = mapOf("vipId" to vip.id, "minutes" to "4320")
                    ),
                    ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS)
                ),
                quickReplyDrafts = starters,
                readOnlyAdvisory = true,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
            emitted.add(proposal)
        }
        return emitted
    }

    fun toCareA2uiJsonl(
        proposal: ProactiveProposal,
        vip: VipContactCadence,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val surfaceId = "care_nudge_${vip.id}"
        val rootChildren = JSONArray().put("rc_title").put("rc_summary").put("rc_starter_card").put("rc_actions_row")
        val components = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            put(JSONObject().put("id", "rc_title").put("component", "Text").put("text", ptr("/care/titleKo")).put("variant", "h3"))
            put(JSONObject().put("id", "rc_summary").put("component", "Text").put("text", ptr("/care/summaryKo")).put("variant", "body"))
            put(JSONObject().put("id", "rc_starter_card").put("component", "Card").put("child", "rc_starter_txt"))
            put(JSONObject().put("id", "rc_starter_txt").put("component", "Text").put("text", ptr("/care/starterDraft")).put("variant", "caption"))

            put(JSONObject().put("id", "rc_chat_lbl").put("component", "Text").put("text", "대화방 열기").put("variant", "caption"))
            put(
                JSONObject().put("id", "rc_chat_btn").put("component", "Button").put("variant", "primary").put("child", "rc_chat_lbl")
                    .put("action", eventObj(ACTION_OPEN_CHAT, JSONObject().put("vipId", vip.id).put("packageName", vip.preferredPackage)))
            )
            put(JSONObject().put("id", "rc_dial_lbl").put("component", "Text").put("text", "전화 걸기").put("variant", "caption"))
            put(
                JSONObject().put("id", "rc_dial_btn").put("component", "Button").put("variant", "default").put("child", "rc_dial_lbl")
                    .put("action", eventObj(ACTION_OPEN_DIALER, JSONObject().put("vipId", vip.id).put("phoneNumber", vip.phoneNumber)))
            )
            put(JSONObject().put("id", "rc_snooze_lbl").put("component", "Text").put("text", "주말로 미루기").put("variant", "caption"))
            put(
                JSONObject().put("id", "rc_snooze_btn").put("component", "Button").put("variant", "default").put("child", "rc_snooze_lbl")
                    .put("action", eventObj(ACTION_SNOOZE_WEEKEND, JSONObject().put("vipId", vip.id)))
            )
            put(
                JSONObject().put("id", "rc_actions_row").put("component", "Row")
                    .put("children", JSONArray().put("rc_chat_btn").put("rc_dial_btn").put("rc_snooze_btn"))
            )
        }
        val data = JSONObject()
            .put("vipId", vip.id)
            .put("titleKo", proposal.titleKo)
            .put("summaryKo", proposal.summaryKo)
            .put("starterDraft", proposal.quickReplyDrafts.firstOrNull().orEmpty())

        return buildA2uiTripletJsonl(surfaceId, components, "/care", data, protocolVersion)
    }

    private fun loadLocked() {
        val file = storageFile ?: return
        vipList.clear()
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("vips") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    VipContactCadence.fromJson(arr.optJSONObject(i))?.let { vipList.add(it) }
                }
            }
        }
        _vipContacts.value = vipList.toList()
    }

    private fun persistLocked() {
        _vipContacts.value = vipList.toList()
        val file = storageFile ?: return
        runCatching {
            val arr = JSONArray()
            vipList.forEach { arr.put(it.toJson()) }
            ConvenienceStorageHelper.writeAtomically(
                file,
                JSONObject().put("schemaVersion", 1).put("vips", arr).toString(2)
            )
        }
    }
}

// =============================================================================
// #6. Atomic Multi-Action Rollback Ledger + Explainable Evidence Card
// =============================================================================

enum class ReversibilityClass(val labelKo: String) {
    REVERSIBLE("1탭 원상복구 가능"),
    COMPENSATABLE("보상 조치로 수정 가능"),
    IRREVERSIBLE_EXTERNAL("외부 전송·자동 회수 불가");

    companion object {
        fun fromNameOrDefault(raw: String?, default: ReversibilityClass = REVERSIBLE): ReversibilityClass {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: default
        }
    }
}

data class ProposalEvidenceCard(
    val sourceSummaryKo: String,
    val ruleSummaryKo: String,
    val reversibility: ReversibilityClass,
    val reversibilityLabelKo: String,
    val confidenceBasisKo: String,
    val cloudUploadCount: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sourceSummaryKo", sourceSummaryKo)
        put("ruleSummaryKo", ruleSummaryKo)
        put("reversibility", reversibility.name)
        put("reversibilityLabelKo", reversibilityLabelKo)
        put("confidenceBasisKo", confidenceBasisKo)
        put("cloudUploadCount", 0)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ProposalEvidenceCard? {
            if (obj == null) return null
            val rev = ReversibilityClass.fromNameOrDefault(obj.optString("reversibility", ""))
            return ProposalEvidenceCard(
                sourceSummaryKo = obj.optString("sourceSummaryKo", ""),
                ruleSummaryKo = obj.optString("ruleSummaryKo", ""),
                reversibility = rev,
                reversibilityLabelKo = obj.optString("reversibilityLabelKo", rev.labelKo),
                confidenceBasisKo = obj.optString("confidenceBasisKo", ""),
                cloudUploadCount = 0
            )
        }
    }
}

data class TransactionStepRecord(
    val stepId: String,
    val toolName: String,
    val targetResourceId: String,
    val reversibility: ReversibilityClass,
    val preStateJson: String = "{}",
    val postStateJson: String = "{}",
    val undoCommand: String = "",
    val readbackVerified: Boolean = true,
    val readbackDetailKo: String = "",
    val executedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stepId", stepId)
        put("toolName", toolName)
        put("targetResourceId", targetResourceId)
        put("reversibility", reversibility.name)
        put("preStateJson", preStateJson)
        put("postStateJson", postStateJson)
        put("undoCommand", undoCommand)
        put("readbackVerified", readbackVerified)
        put("readbackDetailKo", readbackDetailKo)
        put("executedAtMs", executedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): TransactionStepRecord? {
            if (obj == null) return null
            val sid = obj.optString("stepId", "").trim()
            if (sid.isEmpty()) return null
            return TransactionStepRecord(
                stepId = sid,
                toolName = obj.optString("toolName", ""),
                targetResourceId = obj.optString("targetResourceId", ""),
                reversibility = ReversibilityClass.fromNameOrDefault(obj.optString("reversibility", "")),
                preStateJson = obj.optString("preStateJson", "{}"),
                postStateJson = obj.optString("postStateJson", "{}"),
                undoCommand = obj.optString("undoCommand", ""),
                readbackVerified = obj.optBoolean("readbackVerified", true),
                readbackDetailKo = obj.optString("readbackDetailKo", ""),
                executedAtMs = obj.optLong("executedAtMs", System.currentTimeMillis())
            )
        }
    }
}

data class ActionTransactionBundle(
    val txId: String,
    val proposalId: String,
    val summaryKo: String,
    val status: String,
    val steps: List<TransactionStepRecord>,
    val prevHash: String,
    val entryHash: String,
    val createdAtMs: Long,
    val updatedAtMs: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("txId", txId)
        put("proposalId", proposalId)
        put("summaryKo", summaryKo)
        put("status", status)
        val arr = JSONArray()
        steps.forEach { arr.put(it.toJson()) }
        put("steps", arr)
        put("prevHash", prevHash)
        put("entryHash", entryHash)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ActionTransactionBundle? {
            if (obj == null) return null
            val txId = obj.optString("txId", "").trim()
            if (txId.isEmpty()) return null
            val steps = mutableListOf<TransactionStepRecord>()
            obj.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    TransactionStepRecord.fromJson(arr.optJSONObject(i))?.let { steps.add(it) }
                }
            }
            return ActionTransactionBundle(
                txId = txId,
                proposalId = obj.optString("proposalId", ""),
                summaryKo = obj.optString("summaryKo", ""),
                status = obj.optString("status", "VERIFIED_DONE"),
                steps = steps,
                prevHash = obj.optString("prevHash", ""),
                entryHash = obj.optString("entryHash", ""),
                createdAtMs = obj.optLong("createdAtMs", 0L),
                updatedAtMs = obj.optLong("updatedAtMs", 0L)
            )
        }
    }
}

data class RollbackExecutionReport(
    val txId: String,
    val rolledBackStepIds: List<String>,
    val irreversibleStepIds: List<String>,
    val failedStepIds: List<String>,
    val summaryKo: String
)

/**
 * SHA-256 hash-chained append-only JSONL ledger (`hanson_action_tx_ledger.jsonl`) grouping
 * multi-step executions (`CalendarContract` eventId + `ScratchpadNote` + `DeviceSetting` pre-states)
 * under one `txId`, recording deterministic readback verification, and executing reverse-order
 * rollback while honestly reporting `IRREVERSIBLE_EXTERNAL` steps.
 */
object ActionTransactionLedger {

    private const val LEDGER_FILE_NAME = "hanson_action_tx_ledger.jsonl"
    private const val GENESIS_HASH = "GENESIS_HANSON_TX_LEDGER_V1"

    private val lock = Any()

    @Volatile
    private var ledgerFile: File? = null

    @Volatile
    private var initialized = false

    private val entries = mutableListOf<ActionTransactionBundle>()
    private val _transactions = MutableStateFlow<List<ActionTransactionBundle>>(emptyList())
    val transactions: StateFlow<List<ActionTransactionBundle>> = _transactions.asStateFlow()

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            ledgerFile = File(dir, LEDGER_FILE_NAME)
            loadLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, LEDGER_FILE_NAME)
            ledgerFile = file
            if (clearExisting) {
                entries.clear()
                _transactions.value = emptyList()
                file.delete()
            } else {
                loadLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            entries.clear()
            _transactions.value = emptyList()
            ledgerFile = null
            initialized = false
        }
    }

    fun listTransactions(): List<ActionTransactionBundle> = synchronized(lock) { entries.toList() }

    fun getTransaction(txId: String): ActionTransactionBundle? =
        synchronized(lock) { entries.lastOrNull { it.txId == txId.trim() } }

    fun recordVerifiedTransaction(
        txId: String,
        proposalId: String,
        summaryKo: String,
        steps: List<TransactionStepRecord>,
        nowMs: Long = System.currentTimeMillis()
    ): ActionTransactionBundle {
        synchronized(lock) {
            val prevHash = entries.lastOrNull()?.entryHash ?: GENESIS_HASH
            val allVerified = steps.isNotEmpty() && steps.all { it.readbackVerified }
            val status = if (allVerified) "VERIFIED_DONE" else "UNVERIFIED_PARTIAL"
            val entryHash = computeEntryHash(prevHash, txId, proposalId, status, steps)
            val bundle = ActionTransactionBundle(
                txId = txId,
                proposalId = proposalId,
                summaryKo = summaryKo,
                status = status,
                steps = steps,
                prevHash = prevHash,
                entryHash = entryHash,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
            entries.add(bundle)
            appendToDiskLocked(bundle)
            _transactions.value = entries.toList()
            return bundle
        }
    }

    /**
     * Executes reverse-order rollback across all reversible/compensatable steps in [txId],
     * honestly skipping & reporting `IRREVERSIBLE_EXTERNAL` steps (e.g., sent external replies).
     */
    fun rollbackTransaction(
        txId: String,
        executor: (TransactionStepRecord) -> Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): RollbackExecutionReport? {
        val bundle = getTransaction(txId) ?: return null
        val rolledBack = mutableListOf<String>()
        val irreversible = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (step in bundle.steps.asReversed()) {
            if (step.reversibility == ReversibilityClass.IRREVERSIBLE_EXTERNAL) {
                irreversible.add(step.stepId)
                continue
            }
            val ok = runCatching { executor.invoke(step) }.getOrDefault(false)
            if (ok) {
                rolledBack.add(step.stepId)
            } else {
                failed.add(step.stepId)
            }
        }

        val nextStatus = when {
            failed.isEmpty() && irreversible.isEmpty() -> "ROLLED_BACK"
            else -> "PARTIAL_ROLLBACK"
        }
        val summaryKo = buildString {
            append("되돌리기 완료 ${rolledBack.size}건")
            if (irreversible.isNotEmpty()) {
                append(" · 외부 전송 완료·자동 회수 불가 ${irreversible.size}건")
            }
            if (failed.isNotEmpty()) {
                append(" · 복구 실패 ${failed.size}건")
            }
        }

        synchronized(lock) {
            val prevHash = entries.lastOrNull()?.entryHash ?: GENESIS_HASH
            val entryHash = computeEntryHash(prevHash, bundle.txId, bundle.proposalId, nextStatus, bundle.steps)
            val rollbackBundle = bundle.copy(
                status = nextStatus,
                prevHash = prevHash,
                entryHash = entryHash,
                updatedAtMs = nowMs
            )
            entries.add(rollbackBundle)
            appendToDiskLocked(rollbackBundle)
            _transactions.value = entries.toList()
        }

        return RollbackExecutionReport(
            txId = bundle.txId,
            rolledBackStepIds = rolledBack,
            irreversibleStepIds = irreversible,
            failedStepIds = failed,
            summaryKo = summaryKo
        )
    }

    /**
     * Verifies the complete SHA-256 hash chain from `GENESIS` to the latest entry (tamper detection).
     */
    fun verifyHashChain(): Boolean {
        synchronized(lock) {
            var expectedPrev = GENESIS_HASH
            for (entry in entries) {
                if (entry.prevHash != expectedPrev) return false
                val recomputed = computeEntryHash(
                    entry.prevHash,
                    entry.txId,
                    entry.proposalId,
                    entry.status,
                    entry.steps
                )
                if (entry.entryHash != recomputed) return false
                expectedPrev = entry.entryHash
            }
            return true
        }
    }

    private fun computeEntryHash(
        prevHash: String,
        txId: String,
        proposalId: String,
        status: String,
        steps: List<TransactionStepRecord>
    ): String {
        val stepsArr = JSONArray()
        steps.forEach { stepsArr.put(it.toJson()) }
        val payload = "$prevHash|$txId|$proposalId|$status|${stepsArr}"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun loadLocked() {
        val file = ledgerFile ?: return
        entries.clear()
        if (file.isFile) {
            runCatching {
                file.readLines(StandardCharsets.UTF_8)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { line ->
                        runCatching { JSONObject(line) }.getOrNull()?.let { obj ->
                            ActionTransactionBundle.fromJson(obj)?.let { entries.add(it) }
                        }
                    }
            }
        }
        _transactions.value = entries.toList()
    }

    private fun appendToDiskLocked(bundle: ActionTransactionBundle) {
        val file = ledgerFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val content = entries.joinToString("\n") { it.toJson().toString() } + "\n"
            ConvenienceStorageHelper.writeAtomically(file, content)
        }
    }
}

object AtomicRollbackAndExplainEngine {

    const val ACTION_ROLLBACK_BUNDLE = "tx.rollback_bundle"
    const val ACTION_MUTE_TYPE = "evidence.mute_type"

    fun classifyReversibility(proposal: ProactiveProposal): ReversibilityClass {
        return when (proposal.primaryAction.kind) {
            ProposalActionKind.SEND_QUICK_REPLY,
            ProposalActionKind.RUN_AGENT_GOAL -> ReversibilityClass.IRREVERSIBLE_EXTERNAL
            ProposalActionKind.ADD_CALENDAR,
            ProposalActionKind.SET_ALARM,
            ProposalActionKind.SAVE_NOTE,
            ProposalActionKind.START_DND -> ReversibilityClass.REVERSIBLE
            ProposalActionKind.OPEN_MAP,
            ProposalActionKind.OPEN_LINK,
            ProposalActionKind.SNOOZE,
            ProposalActionKind.DISMISS -> ReversibilityClass.COMPENSATABLE
        }
    }

    fun buildEvidenceCard(proposal: ProactiveProposal): ProposalEvidenceCard {
        val rev = classifyReversibility(proposal)
        val pct = (proposal.confidence * 100.0).toInt().coerceIn(0, 100)
        val conflictNote = if (proposal.hasCalendarConflict) {
            "기존 캘린더 충돌 감지"
        } else {
            "캘린더 충돌 없음"
        }
        val readOnlyNote = if (proposal.readOnlyAdvisory) " · 읽기전용 잠금" else ""
        return ProposalEvidenceCard(
            sourceSummaryKo = "${proposal.sourceApp} '${proposal.roomOrTitle}' (${proposal.sender}) · 시크릿 마스킹 적용",
            ruleSummaryKo = "로컬 \$0 규칙 (${proposal.type.badgeKo}${readOnlyNote}) · 클라우드 전송 0건",
            reversibility = rev,
            reversibilityLabelKo = rev.labelKo,
            confidenceBasisKo = "신뢰도 ${pct}% · $conflictNote",
            cloudUploadCount = 0
        )
    }
}

// =============================================================================
// Bonus: Cross-Lab Context Portability Pack (Open AGENTS.md-Compatible Markdown)
// =============================================================================

data class ImportedPortabilityPack(
    val bubbles: List<String>,
    val routines: List<Pair<String, String>>,
    val vipNamesAndDays: List<Pair<String, Int>>,
    val beatTitles: List<String>,
    val skippedSecretLines: Int = 0
)

object CrossLabPortabilityPack {

    fun exportMarkdown(
        bubbles: List<HumanMemoryBubble>,
        routines: List<PersonalRoutine>,
        vipContacts: List<VipContactCadence> = emptyList(),
        beats: List<RoutineScheduleSpec> = emptyList()
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# Hanson Portable Context Pack (AGENTS.md Compatible)")
        sb.appendLine()
        sb.appendLine("> Zero-secret portable personal context. Safe for cross-agent migration.")
        sb.appendLine()

        sb.appendLine("## Memory Bubbles")
        bubbles.filter { it.enabled }.forEach { b ->
            if (!isUnsafeLine(b.factKo)) {
                sb.appendLine("- [${b.category}] ${b.factKo.replace("\n", " ").trim()}")
            }
        }
        sb.appendLine()

        sb.appendLine("## Personal Routines")
        routines.forEach { r ->
            if (!isUnsafeLine(r.titleKo) && !isUnsafeLine(r.goalPrompt)) {
                sb.appendLine("- ${r.titleKo.replace("\n", " ").trim()} :: ${r.goalPrompt.replace("\n", " ").trim()}")
            }
        }
        sb.appendLine()

        sb.appendLine("## VIP Care Cadence")
        vipContacts.forEach { v ->
            if (!isUnsafeLine(v.name)) {
                sb.appendLine("- ${v.name.trim()} | ${v.cadenceDays}d | ${v.relationLabelKo.trim()}")
            }
        }
        sb.appendLine()

        sb.appendLine("## Scheduled Beats")
        beats.filter { it.enabled }.forEach { beat ->
            if (!isUnsafeLine(beat.titleKo) && !isUnsafeLine(beat.goalPrompt)) {
                sb.appendLine("- ${beat.titleKo.trim()} (${beat.frequency.name})")
            }
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun parseImportedMarkdown(md: String): ImportedPortabilityPack {
        var section = ""
        val bubbles = mutableListOf<String>()
        val routines = mutableListOf<Pair<String, String>>()
        val vips = mutableListOf<Pair<String, Int>>()
        val beats = mutableListOf<String>()
        var skippedSecrets = 0

        for (rawLine in md.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("# ") || line.startsWith(">")) continue
            if (line.startsWith("## ")) {
                section = line.removePrefix("## ").trim().lowercase(Locale.ROOT)
                continue
            }
            if (!line.startsWith("- ")) continue
            val body = line.removePrefix("- ").trim()
            if (body.isEmpty()) continue
            if (isUnsafeLine(body)) {
                skippedSecrets++
                continue
            }
            when {
                section.contains("memory") || section.contains("bubble") -> {
                    val cleaned = body.replace(Regex("^\\[[A-Za-z0-9_]+\\]\\s*"), "").trim()
                    if (cleaned.isNotEmpty()) bubbles.add(cleaned)
                }
                section.contains("routine") -> {
                    val parts = body.split("::", limit = 2)
                    if (parts.size == 2) {
                        val title = parts[0].trim()
                        val goal = parts[1].trim()
                        if (title.isNotEmpty() && goal.isNotEmpty()) routines.add(title to goal)
                    }
                }
                section.contains("vip") || section.contains("cadence") -> {
                    val parts = body.split("|").map { it.trim() }
                    val name = parts.getOrNull(0).orEmpty()
                    val days = parts.getOrNull(1)?.removeSuffix("d")?.trim()?.toIntOrNull()?.coerceIn(1, 365) ?: 14
                    if (name.isNotEmpty()) vips.add(name to days)
                }
                section.contains("beat") -> {
                    val title = body.substringBefore("(").trim()
                    if (title.isNotEmpty()) beats.add(title)
                }
            }
        }
        return ImportedPortabilityPack(
            bubbles = bubbles,
            routines = routines,
            vipNamesAndDays = vips,
            beatTitles = beats,
            skippedSecretLines = skippedSecrets
        )
    }

    private fun isUnsafeLine(text: String): Boolean {
        return ConvenienceSecretGuard.containsSensitiveSecret(text) ||
            NotificationThreadBuffer.containsHardSecret(text) ||
            UntrustedScreenDataGuard.detectPromptInjectionSignals(text).isNotEmpty()
    }
}

// =============================================================================
// Internal Shared Helpers for WhiteSpaceLifeCareEngine
// =============================================================================

private fun ptr(path: String): JSONObject = JSONObject().put("@path", path)

private fun eventObj(name: String, context: JSONObject): JSONObject =
    JSONObject().put("event", JSONObject().put("name", name).put("context", context))

private fun buildA2uiTripletJsonl(
    surfaceId: String,
    components: JSONArray,
    dataPath: String,
    dataValue: JSONObject,
    protocolVersion: String
): String {
    val ver = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
        A2uiProtocolConstants.VERSION_0_9
    } else {
        A2uiProtocolConstants.VERSION_1_0
    }
    val cat = if (ver == A2uiProtocolConstants.VERSION_0_9) {
        A2uiProtocolConstants.BASIC_CATALOG_V0_9
    } else {
        A2uiProtocolConstants.BASIC_CATALOG_V1_0
    }
    return listOf(
        JSONObject().put("version", ver).put(
            "createSurface",
            JSONObject().put("surfaceId", surfaceId).put("catalogId", cat).put("sendDataModel", true)
        ),
        JSONObject().put("version", ver).put(
            "updateComponents",
            JSONObject().put("surfaceId", surfaceId).put("components", components)
        ),
        JSONObject().put("version", ver).put(
            "updateDataModel",
            JSONObject().put("surfaceId", surfaceId).put("path", dataPath).put("value", dataValue)
        )
    ).joinToString("\n") { it.toString() }
}

private fun sha256Short(vararg parts: String): String {
    val raw = parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
    return digest.take(12).joinToString("") { "%02x".format(it) }
}
