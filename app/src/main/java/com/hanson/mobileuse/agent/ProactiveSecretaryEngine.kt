package com.hanson.mobileuse.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.hanson.mobileuse.notification.InterceptedNotification
import com.hanson.mobileuse.notification.NotificationThreadBuffer
import com.hanson.mobileuse.notification.NotificationThreadContext
import com.hanson.mobileuse.notification.RemoteInputSessionCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

// =============================================================================
// 1. Core Proactive Secretary Models & Wire Contract (:18888/secretary/*)
// =============================================================================

enum class ProactiveProposalType(
    val badgeKo: String,
    val priorityWeight: Int
) {
    CALENDAR_EVENT("일정 등록", 95),
    DEADLINE_TASK("마감·할일", 85),
    UNANSWERED_FOLLOWUP("미답장 팔로업", 78),
    MEETING_LINK_PREP("화상회의 준비", 92),
    RESERVATION_COMMUTE("예약·이동", 90),
    DELIVERY_TRACKING("배송·택배", 70),
    BILLING_SUBSCRIPTION_WATCH("금융·청구 알림", 75),
    UPCOMING_PREP_BRIEF("다가오는 일정 브리핑", 88),
    SELF_COMMITMENT_DEBT("내가 한 약속", 91),
    SUBSCRIPTION_TRIAL_DEFENSE("구독·반품 방어", 93),
    FOCUS_REENTRY_DIGEST("집중 복귀 브리프", 89),
    SCAM_PRE_SHIELD("스미싱 주의", 100),
    PLAIN_NOTICE_TRANSLATION("쉬운 말 통역", 82),
    RELATIONSHIP_CARE_NUDGE("안부 케어", 76);

    companion object {
        fun fromNameOrNull(raw: String?): ProactiveProposalType? {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        }
    }
}

enum class ProactiveProposalStatus {
    PENDING,
    APPROVED,
    EXECUTED,
    DISMISSED,
    SNOOZED;

    companion object {
        fun fromNameOrDefault(
            raw: String?,
            default: ProactiveProposalStatus = PENDING
        ): ProactiveProposalStatus {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: default
        }
    }
}

enum class ProposalActionKind {
    ADD_CALENDAR,
    SEND_QUICK_REPLY,
    SET_ALARM,
    SAVE_NOTE,
    OPEN_MAP,
    OPEN_LINK,
    START_DND,
    DISMISS,
    SNOOZE,
    RUN_AGENT_GOAL;

    companion object {
        fun fromNameOrDefault(
            raw: String?,
            default: ProposalActionKind = SAVE_NOTE
        ): ProposalActionKind {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: default
        }
    }
}

data class ProposalAction(
    val key: String,
    val labelKo: String,
    val kind: ProposalActionKind,
    val payload: Map<String, String> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("labelKo", labelKo)
        put("kind", kind.name)
        if (payload.isNotEmpty()) {
            val p = JSONObject()
            payload.forEach { (k, v) -> p.put(k, v) }
            put("payload", p)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject?): ProposalAction? {
            if (obj == null) return null
            val key = obj.optString("key", "").trim()
            val labelKo = obj.optString("labelKo", "").trim()
            if (key.isEmpty() || labelKo.isEmpty()) return null
            val kind = ProposalActionKind.fromNameOrDefault(obj.optString("kind", ""))
            val payload = LinkedHashMap<String, String>()
            obj.optJSONObject("payload")?.let { pObj ->
                val keys = pObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    payload[k] = pObj.optString(k, "")
                }
            }
            return ProposalAction(
                key = key,
                labelKo = labelKo,
                kind = kind,
                payload = payload
            )
        }
    }
}

data class ProactiveProposal(
    val id: String,
    val fingerprint: String,
    val type: ProactiveProposalType,
    val status: ProactiveProposalStatus = ProactiveProposalStatus.PENDING,
    val sourceApp: String,
    val packageName: String,
    val sender: String,
    val roomOrTitle: String,
    val titleKo: String,
    val summaryKo: String,
    val detailKo: String,
    val quotedExcerpt: String,
    val confidence: Double,
    val hasCalendarConflict: Boolean = false,
    val conflictSummaryKo: String = "",
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val location: String = "",
    val meetingUrl: String = "",
    val primaryAction: ProposalAction,
    val secondaryActions: List<ProposalAction> = emptyList(),
    val quickReplyDrafts: List<String> = emptyList(),
    val readOnlyAdvisory: Boolean = false,
    val snoozedUntilMs: Long? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fingerprint", fingerprint)
        put("type", type.name)
        put("status", status.name)
        put("sourceApp", sourceApp)
        put("packageName", packageName)
        put("sender", sender)
        put("roomOrTitle", roomOrTitle)
        put("titleKo", titleKo)
        put("summaryKo", summaryKo)
        put("detailKo", detailKo)
        put("quotedExcerpt", quotedExcerpt)
        put("confidence", confidence)
        put("hasCalendarConflict", hasCalendarConflict)
        put("conflictSummaryKo", conflictSummaryKo)
        put("startTimeMs", startTimeMs ?: JSONObject.NULL)
        put("endTimeMs", endTimeMs ?: JSONObject.NULL)
        put("location", location)
        put("meetingUrl", meetingUrl)
        put("primaryAction", primaryAction.toJson())
        val secArr = JSONArray()
        secondaryActions.forEach { secArr.put(it.toJson()) }
        put("secondaryActions", secArr)
        put("quickReplyDrafts", JSONArray(quickReplyDrafts))
        put("readOnlyAdvisory", readOnlyAdvisory)
        put("approvalTier", RoomUrgencyCalibrationStore.classifyTier(this@ProactiveProposal).name)
        put("topicChannel", TopicChannelClassifier.classifyProposalChannel(this@ProactiveProposal).name)
        put("evidence", AtomicRollbackAndExplainEngine.buildEvidenceCard(this@ProactiveProposal).toJson())
        put("snoozedUntilMs", snoozedUntilMs ?: JSONObject.NULL)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ProactiveProposal? {
            if (obj == null) return null
            val id = obj.optString("id", "").trim()
            val fingerprint = obj.optString("fingerprint", "").trim()
            val type = ProactiveProposalType.fromNameOrNull(obj.optString("type", "")) ?: return null
            val primary = ProposalAction.fromJson(obj.optJSONObject("primaryAction")) ?: return null
            if (id.isEmpty() || fingerprint.isEmpty()) return null

            val secList = mutableListOf<ProposalAction>()
            obj.optJSONArray("secondaryActions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    ProposalAction.fromJson(arr.optJSONObject(i))?.let { secList.add(it) }
                }
            }
            val drafts = mutableListOf<String>()
            obj.optJSONArray("quickReplyDrafts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optString(i, "").trim()
                    if (d.isNotEmpty()) drafts.add(d)
                }
            }
            return ProactiveProposal(
                id = id,
                fingerprint = fingerprint,
                type = type,
                status = ProactiveProposalStatus.fromNameOrDefault(obj.optString("status", "")),
                sourceApp = obj.optString("sourceApp", ""),
                packageName = obj.optString("packageName", ""),
                sender = obj.optString("sender", ""),
                roomOrTitle = obj.optString("roomOrTitle", ""),
                titleKo = obj.optString("titleKo", ""),
                summaryKo = obj.optString("summaryKo", ""),
                detailKo = obj.optString("detailKo", ""),
                quotedExcerpt = obj.optString("quotedExcerpt", ""),
                confidence = obj.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
                hasCalendarConflict = obj.optBoolean("hasCalendarConflict", false),
                conflictSummaryKo = obj.optString("conflictSummaryKo", ""),
                startTimeMs = if (obj.has("startTimeMs") && !obj.isNull("startTimeMs")) obj.optLong("startTimeMs") else null,
                endTimeMs = if (obj.has("endTimeMs") && !obj.isNull("endTimeMs")) obj.optLong("endTimeMs") else null,
                location = obj.optString("location", ""),
                meetingUrl = obj.optString("meetingUrl", ""),
                primaryAction = primary,
                secondaryActions = secList,
                quickReplyDrafts = drafts,
                readOnlyAdvisory = obj.optBoolean("readOnlyAdvisory", false),
                snoozedUntilMs = if (obj.has("snoozedUntilMs") && !obj.isNull("snoozedUntilMs")) obj.optLong("snoozedUntilMs") else null,
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }
    }
}

data class SecretarySettings(
    val enabled: Boolean = true,
    val mutedPackages: Set<String> = emptySet(),
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
    val weatherPrepEnabled: Boolean = false,
    val floatingA2uiOverlayEnabled: Boolean = false,
    val unansweredFollowupMinutes: Int = 10
) {
    fun isPackageMuted(packageName: String): Boolean {
        val target = packageName.trim()
        if (target.isEmpty()) return false
        return mutedPackages.any { it.equals(target, ignoreCase = true) }
    }

    fun isWithinQuietHours(hourOfDay: Int): Boolean {
        val h = ((hourOfDay % 24) + 24) % 24
        val start = ((quietHoursStart % 24) + 24) % 24
        val end = ((quietHoursEnd % 24) + 24) % 24
        if (start == end) return false
        return if (start < end) {
            h in start until end
        } else {
            h >= start || h < end
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("enabled", enabled)
        put("mutedPackages", JSONArray(mutedPackages.sorted()))
        put("quietHoursStart", quietHoursStart)
        put("quietHoursEnd", quietHoursEnd)
        put("weatherPrepEnabled", weatherPrepEnabled)
        put("floatingA2uiOverlayEnabled", floatingA2uiOverlayEnabled)
        put("unansweredFollowupMinutes", unansweredFollowupMinutes)
    }

    companion object {
        fun fromJson(obj: JSONObject?): SecretarySettings {
            if (obj == null) return SecretarySettings()
            val muted = LinkedHashSet<String>()
            obj.optJSONArray("mutedPackages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val pkg = arr.optString(i, "").trim()
                    if (pkg.isNotEmpty()) muted.add(pkg)
                }
            }
            return SecretarySettings(
                enabled = obj.optBoolean("enabled", true),
                mutedPackages = muted,
                quietHoursStart = obj.optInt("quietHoursStart", 23).coerceIn(0, 23),
                quietHoursEnd = obj.optInt("quietHoursEnd", 7).coerceIn(0, 23),
                weatherPrepEnabled = obj.optBoolean("weatherPrepEnabled", false),
                floatingA2uiOverlayEnabled = obj.optBoolean("floatingA2uiOverlayEnabled", false),
                unansweredFollowupMinutes = obj.optInt("unansweredFollowupMinutes", 10).coerceIn(1, 240)
            )
        }
    }
}

// =============================================================================
// 2. Korean & English Deterministic Schedule Parser (Injectable Clock & Zone)
// =============================================================================

data class ParsedKoreanSchedule(
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val hasExplicitDate: Boolean,
    val hasExplicitTime: Boolean,
    val location: String = "",
    val meetingUrl: String = "",
    val confidence: Double = 0.0
)

object KoreanScheduleParser {

    val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private val VIDEO_URL_REGEX = Regex(
        "(?i)https?://(?:[a-z0-9-]+\\.)?(?:meet\\.google\\.com|zoom\\.us|teams\\.microsoft\\.com|teams\\.live\\.com|whereby\\.com|webex\\.com)/[^\\s<>\"'\\])]+",
        RegexOption.IGNORE_CASE
    )
    private val ANY_URL_REGEX = Regex("(?i)https?://[^\\s<>\"']+")

    // Date patterns
    private val ISO_DATE_REGEX = Regex("(?<!\\d)(20\\d{2})[-./](\\d{1,2})[-./](\\d{1,2})(?!\\d)")
    private val KO_MONTH_DAY_REGEX = Regex("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
    private val SLASH_MONTH_DAY_REGEX = Regex("(?<![\\d:/])(\\d{1,2})/(\\d{1,2})(?![\\d:/])")
    private val REL_WEEK_DOW_REGEX = Regex("(이번\\s*주|금주|다음\\s*주|담\\s*주|차주)\\s*(월|화|수|목|금|토|일)(?:요일|욜)?")
    private val REL_DAY_REGEX = Regex("(?i)(내일\\s*모레|글피|모레|내일|낼|오늘|\\btomorrow\\b|\\btoday\\b)")
    private val BARE_DOW_REGEX = Regex("(월|화|수|목|금|토|일)(?:요일|욜)")

    // Time patterns
    private val TIME_RANGE_REGEX = Regex(
        "(오전|오후|아침|점심|저녁|밤|새벽)?\\s*(?<!\\d)(\\d{1,2})\\s*(?:시(?!간)|:)\\s*(반|(\\d{1,2})\\s*분?)?\\s*(?:~|-|부터)\\s*(오전|오후|아침|점심|저녁|밤|새벽)?\\s*(\\d{1,2})\\s*(?:시(?!간)|:)\\s*(반|(\\d{1,2})\\s*분?)?(?:까지)?"
    )
    private val EN_AMPM_TIME_REGEX = Regex("(?i)(?<!\\d)(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b")
    private val COLON_TIME_REGEX = Regex("(오전|오후|아침|점심|저녁|밤|새벽)?\\s*(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)")
    private val KO_HOUR_MIN_REGEX = Regex(
        "(오전|오후|아침|점심|저녁|밤|새벽)?\\s*(?<!\\d)(\\d{1,2})\\s*시(?!간)(?:\\s*(반|(\\d{1,2})\\s*분(?!간|\\s*동안)))?"
    )

    // Duration patterns
    private val DURATION_HOURS_REGEX = Regex("(?<!\\d)(\\d{1,2})\\s*시간(?:\\s*(반|(\\d{1,2})\\s*분))?")
    private val DURATION_MINS_REGEX = Regex("(?<!\\d)(\\d{1,3})\\s*분(?:간|\\s*(?:동안|미팅|회의|약속|예정))")

    // Location patterns
    private val EXPLICIT_PLACE_LABEL_REGEX = Regex("(?:장소|위치|매장|식당|출발역)\\s*[:：]\\s*([^\\n,!?]{2,40})")
    private val SUBWAY_STATION_COMBO_REGEX = Regex(
        "([가-힣A-Za-z0-9]{1,12}역(?:\\s*\\d{1,2}번\\s*출구)?(?:\\s+(?:앞\\s+)?[가-힣A-Za-z0-9]{2,16})?)"
    )
    private val EXIT_VENUE_REGEX = Regex("(\\d{1,2}번\\s*출구(?:\\s+(?:앞\\s+)?[가-힣A-Za-z0-9]{2,16})?)")
    private val KO_ADDRESS_REGEX = Regex(
        "((?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)(?:특별시|광역시|특별자치시|특별자치도|도)?\\s+[가-힣0-9-]+(?:시|군|구)(?:\\s+[가-힣0-9-]+(?:동|읍|면|로|길|대로)(?:\\s*\\d+(?:-\\d+)?)?)?)"
    )
    private val MEETING_ROOM_REGEX = Regex(
        "((?:[가-힣A-Za-z0-9]{1,8}\\s+)?(?:\\d{1,2}층\\s*)?(?:대회의실|소회의실|중회의실|회의실|미팅룸|컨퍼런스룸)(?:\\s*[A-Za-z0-9가-힣]{1,6})?)"
    )
    private val KNOWN_HUB_VENUE_REGEX = Regex(
        "((?:강남|판교|성수|홍대|여의도|광화문|잠실|삼성동|역삼|을지로|신촌|용산|서울숲|서초|분당|선릉|종로)(?:\\s+[가-힣A-Za-z0-9]{2,14})?|(?:스타벅스|투썸플레이스|투썸|블루보틀|할리스|이디야|폴바셋|교보문고)(?:\\s+[가-힣A-Za-z0-9]{2,12})?)"
    )
    private val RESERVATION_VENUE_REGEX = Regex(
        "(?:\\[[^\\]]+\\]\\s*)?([가-힣A-Za-z0-9\\s·&-]{2,24})\\s*(?:예약이?\\s*(?:확정|완료)|방문\\s*예정)"
    )

    private val NON_LOCATION_TRAILING_WORDS = setOf(
        "확정", "하자", "어때", "어때요", "괜찮아", "괜찮아요", "가능해", "볼래", "볼까", "만나", "만나자",
        "봐요", "봬요", "에서", "으로", "출발", "도착", "미팅", "회의", "약속", "예약"
    )

    fun extractMeetingUrl(text: String): String {
        return VIDEO_URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', ']', ';') ?: ""
    }

    /**
     * Parses Korean & English date/time/duration/location/meetingUrl deterministically
     * using [referenceTimeMs] and [zoneId]. Supports multi-turn text where earlier lines
     * supply a date/area and later lines refine time/exact venue.
     */
    fun parse(
        text: String,
        referenceTimeMs: Long,
        zoneId: ZoneId = DEFAULT_ZONE,
        fallbackTitle: String = "약속"
    ): ParsedKoreanSchedule? {
        val raw = text.trim()
        if (raw.isEmpty()) return null

        val meetingUrl = extractMeetingUrl(raw)
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val latestLine = lines.lastOrNull().orEmpty()

        val cleanedFull = raw.replace(ANY_URL_REGEX, " ").trim()
        val cleanedLatest = latestLine.replace(ANY_URL_REGEX, " ").trim()

        val refZdt = Instant.ofEpochMilli(referenceTimeMs).atZone(zoneId)
        val refDate = refZdt.toLocalDate()

        // Prefer latest line if it explicitly specifies date/time (handles rescheduling),
        // otherwise merge from full multi-turn thread context.
        val parsedDate = parseDate(cleanedLatest, refDate) ?: parseDate(cleanedFull, refDate)
        val parsedTime = parseTimeAndEnd(cleanedLatest, cleanedFull) ?: parseTimeAndEnd(cleanedFull, cleanedFull)

        if (parsedDate == null && parsedTime == null) {
            return null
        }

        val hasExplicitDate = parsedDate != null
        val hasExplicitTime = parsedTime != null

        val resolvedTime = parsedTime?.first ?: inferDefaultTime(cleanedFull)
        var resolvedDate = parsedDate ?: refDate
        // If only a time was mentioned (no date) and it already passed today by > 5 mins, roll to tomorrow
        if (!hasExplicitDate && hasExplicitTime) {
            val candidate = ZonedDateTime.of(resolvedDate, resolvedTime, zoneId)
            if (candidate.toInstant().toEpochMilli() < referenceTimeMs - 5 * 60_000L) {
                resolvedDate = resolvedDate.plusDays(1)
            }
        }

        val startZdt = ZonedDateTime.of(resolvedDate, resolvedTime, zoneId)
        val startMs = startZdt.toInstant().toEpochMilli()

        val explicitEndTime = parsedTime?.second
        val durationMinutes = parseDurationMinutes(cleanedLatest)
            ?: parseDurationMinutes(cleanedFull)
            ?: 60L

        val endMs = if (explicitEndTime != null) {
            var endZdt = ZonedDateTime.of(resolvedDate, explicitEndTime, zoneId)
            if (!endZdt.isAfter(startZdt)) {
                endZdt = endZdt.plusHours(12)
                if (!endZdt.isAfter(startZdt)) {
                    endZdt = startZdt.plusMinutes(durationMinutes)
                }
            }
            endZdt.toInstant().toEpochMilli()
        } else {
            startZdt.plusMinutes(durationMinutes).toInstant().toEpochMilli()
        }

        val location = extractLocation(cleanedLatest, cleanedFull, meetingUrl)

        var confidence = 0.45
        if (hasExplicitDate) confidence += 0.23
        if (hasExplicitTime) confidence += 0.22
        if (location.isNotBlank()) confidence += 0.06
        if (meetingUrl.isNotBlank()) confidence += 0.06
        if (cleanedFull.contains("확정") || cleanedFull.contains("예약")) confidence += 0.05
        confidence = confidence.coerceIn(0.45, 0.98)

        val inferredTitle = inferTitle(cleanedFull, fallbackTitle, location, meetingUrl)

        return ParsedKoreanSchedule(
            title = inferredTitle,
            startTimeMs = startMs,
            endTimeMs = endMs,
            hasExplicitDate = hasExplicitDate,
            hasExplicitTime = hasExplicitTime,
            location = location,
            meetingUrl = meetingUrl,
            confidence = confidence
        )
    }

    private fun parseDate(text: String, refDate: LocalDate): LocalDate? {
        if (text.isBlank()) return null

        // 1. Explicit ISO date (2026-11-28)
        ISO_DATE_REGEX.findAll(text).lastOrNull()?.let { m ->
            val y = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (y != null && mo != null && d != null) {
                runCatching { LocalDate.of(y, mo, d) }.getOrNull()?.let { return it }
            }
        }

        // 2. Explicit Korean month/day (11월 27일)
        KO_MONTH_DAY_REGEX.findAll(text).lastOrNull()?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                resolveMonthDay(refDate, mo, d)?.let { return it }
            }
        }

        // 3. Explicit slash month/day (12/3)
        SLASH_MONTH_DAY_REGEX.findAll(text).lastOrNull()?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                resolveMonthDay(refDate, mo, d)?.let { return it }
            }
        }

        // 4. Relative week + weekday (이번 주 금요일, 이번주 금욜, 다음 주 월요일, 담주 월욜)
        REL_WEEK_DOW_REGEX.findAll(text).lastOrNull()?.let { m ->
            val weekWord = m.groupValues[1].replace(Regex("\\s+"), "")
            val dow = parseKoreanDayOfWeek(m.groupValues[2]) ?: return@let
            val mondayOfThisWeek = refDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val weekOffset = if (weekWord.startsWith("다음") || weekWord.startsWith("담") || weekWord.startsWith("차")) {
                1L
            } else {
                0L
            }
            return mondayOfThisWeek.plusWeeks(weekOffset).plusDays((dow.value - 1).toLong())
        }

        // 5. Relative day words (오늘, 내일, 모레, 글피, today, tomorrow)
        REL_DAY_REGEX.findAll(text).lastOrNull()?.let { m ->
            val token = m.groupValues[1].replace(Regex("\\s+"), "").lowercase(Locale.ROOT)
            return when (token) {
                "오늘", "today" -> refDate
                "내일", "낼", "tomorrow" -> refDate.plusDays(1)
                "모레", "내일모레" -> refDate.plusDays(2)
                "글피" -> refDate.plusDays(3)
                else -> null
            }
        }

        // 6. Bare Korean weekday (금요일, 금욜) -> next or same occurrence from refDate
        BARE_DOW_REGEX.findAll(text).lastOrNull()?.let { m ->
            val dow = parseKoreanDayOfWeek(m.groupValues[1]) ?: return@let
            return refDate.with(TemporalAdjusters.nextOrSame(dow))
        }

        return null
    }

    private fun resolveMonthDay(refDate: LocalDate, month: Int, day: Int): LocalDate? {
        val candidate = runCatching { LocalDate.of(refDate.year, month, day) }.getOrNull() ?: return null
        return if (candidate.isBefore(refDate.minusDays(60))) {
            runCatching { LocalDate.of(refDate.year + 1, month, day) }.getOrNull() ?: candidate
        } else {
            candidate
        }
    }

    private fun parseKoreanDayOfWeek(ch: String): DayOfWeek? = when (ch) {
        "월" -> DayOfWeek.MONDAY
        "화" -> DayOfWeek.TUESDAY
        "수" -> DayOfWeek.WEDNESDAY
        "목" -> DayOfWeek.THURSDAY
        "금" -> DayOfWeek.FRIDAY
        "토" -> DayOfWeek.SATURDAY
        "일" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun parseTimeAndEnd(text: String, fullContext: String): Pair<LocalTime, LocalTime?>? {
        if (text.isBlank()) return null

        // 1. Explicit time range (e.g., 14:00~15:30, 오후 2시~4시)
        TIME_RANGE_REGEX.findAll(text).lastOrNull()?.let { m ->
            val p1 = m.groupValues[1]
            val h1Raw = m.groupValues[2].toIntOrNull()
            val m1Token = m.groupValues[3]
            val m1Digits = m.groupValues[4].toIntOrNull()
            val p2 = m.groupValues[5].ifBlank { p1 }
            val h2Raw = m.groupValues[6].toIntOrNull()
            val m2Token = m.groupValues[7]
            val m2Digits = m.groupValues[8].toIntOrNull()

            if (h1Raw != null && h2Raw != null && h1Raw in 0..23 && h2Raw in 0..23) {
                val min1 = if (m1Token == "반") 30 else (m1Digits ?: 0)
                val min2 = if (m2Token == "반") 30 else (m2Digits ?: 0)
                if (min1 in 0..59 && min2 in 0..59) {
                    val h1 = adjustKoreanHour(h1Raw, p1, fullContext)
                    var h2 = adjustKoreanHour(h2Raw, p2, fullContext)
                    if (h2 < h1 && p2.isBlank() && h2 + 12 <= 23) h2 += 12
                    return LocalTime.of(h1, min1) to LocalTime.of(h2, min2)
                }
            }
        }

        // 2. English AM/PM (7:30pm, 3pm)
        EN_AMPM_TIME_REGEX.findAll(text).lastOrNull()?.let { m ->
            val rawH = m.groupValues[1].toIntOrNull()
            val rawM = m.groupValues[2].toIntOrNull() ?: 0
            val ampm = m.groupValues[3].lowercase(Locale.ROOT)
            if (rawH != null && rawH in 1..12 && rawM in 0..59) {
                val h24 = when {
                    ampm == "pm" && rawH < 12 -> rawH + 12
                    ampm == "am" && rawH == 12 -> 0
                    else -> rawH
                }
                return LocalTime.of(h24, rawM) to null
            }
        }

        // 3. Colon time (19:30, 오후 7:30)
        COLON_TIME_REGEX.findAll(text).lastOrNull()?.let { m ->
            val period = m.groupValues[1]
            val rawH = m.groupValues[2].toIntOrNull()
            val rawM = m.groupValues[3].toIntOrNull()
            if (rawH != null && rawM != null && rawH in 0..23 && rawM in 0..59) {
                val h24 = adjustKoreanHour(rawH, period, fullContext)
                return LocalTime.of(h24, rawM) to null
            }
        }

        // 4. Korean hour/minute (오후 7시 30분, 저녁 7시 반, 14시)
        KO_HOUR_MIN_REGEX.findAll(text).lastOrNull()?.let { m ->
            val period = m.groupValues[1]
            val rawH = m.groupValues[2].toIntOrNull()
            val halfOrMin = m.groupValues[3]
            val minDigits = m.groupValues[4].toIntOrNull()
            if (rawH != null && rawH in 0..23) {
                val min = if (halfOrMin == "반") 30 else (minDigits ?: 0)
                if (min in 0..59) {
                    val h24 = adjustKoreanHour(rawH, period, fullContext)
                    return LocalTime.of(h24, min) to null
                }
            }
        }

        return null
    }

    private fun adjustKoreanHour(rawHour: Int, period: String, fullContext: String): Int {
        if (rawHour >= 13) return rawHour.coerceIn(0, 23)
        return when (period) {
            "오후", "저녁", "밤" -> when {
                rawHour == 12 && period == "밤" -> 0
                rawHour in 1..11 -> rawHour + 12
                else -> rawHour
            }
            "오전", "아침", "새벽" -> if (rawHour == 12) 0 else rawHour
            "점심" -> if (rawHour in 1..5) rawHour + 12 else rawHour
            else -> {
                when {
                    rawHour in 1..6 && !fullContext.contains("새벽") -> rawHour + 12
                    rawHour in 7..8 &&
                        (fullContext.contains("저녁") || fullContext.contains("회식") || fullContext.contains("퇴근")) ->
                        rawHour + 12
                    else -> rawHour
                }
            }
        }.coerceIn(0, 23)
    }

    private fun inferDefaultTime(text: String): LocalTime = when {
        text.contains("아침") || text.contains("조찬") -> LocalTime.of(9, 0)
        text.contains("점심") || text.contains("런치") -> LocalTime.of(12, 0)
        text.contains("저녁") || text.contains("디너") || text.contains("회식") -> LocalTime.of(18, 30)
        text.contains("오후") -> LocalTime.of(14, 0)
        else -> LocalTime.of(10, 0)
    }

    private fun parseDurationMinutes(text: String): Long? {
        DURATION_HOURS_REGEX.find(text)?.let { m ->
            val hours = m.groupValues[1].toLongOrNull() ?: return@let
            val extraToken = m.groupValues[2]
            val extraMins = if (extraToken == "반") 30L else (m.groupValues[3].toLongOrNull() ?: 0L)
            return (hours * 60L + extraMins).coerceIn(15L, 720L)
        }
        DURATION_MINS_REGEX.find(text)?.let { m ->
            val mins = m.groupValues[1].toLongOrNull() ?: return@let
            return mins.coerceIn(10L, 480L)
        }
        return null
    }

    private fun extractLocation(latestLine: String, fullText: String, meetingUrl: String): String {
        val fromLatest = extractSinglePassLocation(latestLine)
        val fromFull = extractSinglePassLocation(fullText)

        // Multi-turn synthesis: e.g. turn 1 had "강남" / "강남역" and turn 2 had "11번 출구 스타벅스"
        if (fromLatest.isNotBlank() && Regex("^\\d{1,2}번\\s*출구").containsMatchIn(fromLatest)) {
            val areaPrefix = extractAreaOrStationPrefix(fullText)
            if (areaPrefix.isNotBlank() && !fromLatest.contains(areaPrefix)) {
                val stationPrefix = if (areaPrefix.endsWith("역")) areaPrefix else "${areaPrefix}역"
                return "$stationPrefix $fromLatest".trim()
            }
        }

        val chosen = when {
            fromLatest.isNotBlank() && fromLatest.length >= fromFull.length -> fromLatest
            fromFull.isNotBlank() -> fromFull
            fromLatest.isNotBlank() -> fromLatest
            meetingUrl.isNotBlank() -> meetingUrl
            else -> ""
        }
        return chosen.take(64)
    }

    private fun extractSinglePassLocation(text: String): String {
        if (text.isBlank()) return ""

        EXPLICIT_PLACE_LABEL_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        SUBWAY_STATION_COMBO_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        EXIT_VENUE_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        KO_ADDRESS_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 3) return cleaned
        }

        MEETING_ROOM_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        RESERVATION_VENUE_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        KNOWN_HUB_VENUE_REGEX.find(text)?.let { m ->
            val cleaned = cleanLocationPhrase(m.groupValues[1])
            if (cleaned.length >= 2) return cleaned
        }

        return ""
    }

    private fun extractAreaOrStationPrefix(fullText: String): String {
        SUBWAY_STATION_COMBO_REGEX.find(fullText)?.let { m ->
            val stationOnly = Regex("([가-힣A-Za-z0-9]{1,12}역)").find(m.groupValues[1])?.groupValues?.get(1)
            if (!stationOnly.isNullOrBlank()) return stationOnly
        }
        val hubRegex = Regex("(강남|판교|성수|홍대|여의도|광화문|잠실|삼성|역삼|을지로|신촌|용산|서초|선릉|종로)")
        return hubRegex.find(fullText)?.groupValues?.get(1).orEmpty()
    }

    private val DATE_OR_TIME_TOKEN_REGEX = Regex(
        "^(?:\\d{1,4}(?:년|월|일|시|분|명|인)|\\d{1,2}:\\d{2}|\\d{1,2}/\\d{1,2}|오전|오후|아침|점심|저녁|밤|오늘|내일|모레|글피|이번주|다음주|담주|(?:월|화|수|목|금|토|일)(?:요일|욜)?)$"
    )

    private fun cleanLocationPhrase(raw: String): String {
        val tokens = raw.trim()
            .trimEnd('.', ',', '!', '?', '~', ')', ']', '"', '\'')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toMutableList()

        while (tokens.isNotEmpty() &&
            (tokens.last() in NON_LOCATION_TRAILING_WORDS || DATE_OR_TIME_TOKEN_REGEX.matches(tokens.last()))
        ) {
            tokens.removeAt(tokens.lastIndex)
        }
        while (tokens.isNotEmpty() && DATE_OR_TIME_TOKEN_REGEX.matches(tokens.first())) {
            tokens.removeAt(0)
        }
        if (tokens.isEmpty()) return ""

        var last = tokens.last()
        // Strip trailing Korean location postpositions without harming road names like "테헤란로 152"
        val postpositions = listOf("에서요", "으로요", "앞에서", "쪽에서", "에서", "으로", "로요", "에요")
        for (pp in postpositions) {
            if (last.length > pp.length + 1 && last.endsWith(pp)) {
                last = last.removeSuffix(pp)
                break
            }
        }
        if (last.length >= 3 && (last.endsWith("로") || last.endsWith("에")) && !last.endsWith("대로")) {
            val prevToken = if (tokens.size >= 2) tokens[tokens.lastIndex - 1] else ""
            val isDistrictOrDong = (prevToken.endsWith("구") &&
                prevToken != "출구" &&
                prevToken != "입구" &&
                !prevToken.endsWith("출구") &&
                !prevToken.endsWith("입구")) ||
                prevToken.endsWith("동")
            val isRoadName = last.endsWith("로") && isDistrictOrDong
            if (!isRoadName) {
                last = last.dropLast(1)
            }
        }
        tokens[tokens.lastIndex] = last
        while (tokens.isNotEmpty() &&
            (tokens.last().isBlank() ||
                tokens.last() in NON_LOCATION_TRAILING_WORDS ||
                DATE_OR_TIME_TOKEN_REGEX.matches(tokens.last()))
        ) {
            tokens.removeAt(tokens.lastIndex)
        }
        return tokens.joinToString(" ").trim()
    }

    private fun inferTitle(
        fullText: String,
        fallbackTitle: String,
        location: String,
        meetingUrl: String
    ): String {
        val base = fallbackTitle.trim().ifBlank { "약속" }
        return when {
            meetingUrl.isNotBlank() -> "$base 화상회의"
            fullText.contains("예약") && location.isNotBlank() -> "$location 예약"
            fullText.contains("면접") -> "$base 면접"
            fullText.contains("회의") || fullText.contains("미팅") || fullText.contains("싱크") -> "$base 미팅"
            fullText.contains("점심") -> "$base 점심 약속"
            fullText.contains("저녁") || fullText.contains("회식") -> "$base 저녁 약속"
            location.isNotBlank() && location != meetingUrl -> "$base 약속 ($location)"
            else -> "$base 약속"
        }.take(60)
    }
}

// =============================================================================
// 3. Calendar Conflict & Open-Meteo Context Connector
// =============================================================================

data class DeviceCalendarEvent(
    val id: Long,
    val title: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val location: String = ""
)

data class OpenMeteoWeatherBrief(
    val temperatureC: Double,
    val precipitationProbabilityPct: Int,
    val precipitationMm: Double,
    val weatherCode: Int,
    val needsUmbrella: Boolean,
    val summaryKo: String
)

object ProactiveContextApiConnector {

    private val RAIN_OR_SNOW_WMO_CODES: Set<Int> = setOf(
        51, 53, 55, 56, 57,
        61, 63, 65, 66, 67,
        71, 73, 75, 77,
        80, 81, 82, 85, 86,
        95, 96, 99
    )

    /**
     * Test hook for deterministic offline JVM unit testing without Android ContentResolver.
     */
    @Volatile
    var calendarEventsProviderOverride: ((startMs: Long, endMs: Long) -> List<DeviceCalendarEvent>)? = null

    fun queryCalendarEvents(
        context: Context?,
        startMs: Long,
        endMs: Long
    ): List<DeviceCalendarEvent> {
        calendarEventsProviderOverride?.let { provider ->
            return runCatching {
                provider.invoke(startMs, endMs)
                    .filter { it.startTimeMs < endMs && it.endTimeMs > startMs }
                    .sortedBy { it.startTimeMs }
            }.getOrDefault(emptyList())
        }

        if (context == null || endMs <= startMs) return emptyList()

        return runCatching {
            val hasRead = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasRead) return@runCatching emptyList()

            val results = mutableListOf<DeviceCalendarEvent>()
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION
            )
            val selection = "${CalendarContract.Events.DELETED} != 1 AND " +
                "${CalendarContract.Events.DTSTART} < ? AND " +
                "${CalendarContract.Events.DTEND} > ?"
            val selectionArgs = arrayOf(endMs.toString(), startMs.toString())

            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext() && results.size < 30) {
                    val id = cursor.getLong(0)
                    val title = cursor.getString(1)?.trim().orEmpty().ifBlank { "(제목 없음)" }
                    val dtStart = cursor.getLong(2)
                    val rawEnd = cursor.getLong(3)
                    val dtEnd = if (rawEnd > dtStart) rawEnd else dtStart + 60 * 60_000L
                    val loc = cursor.getString(4)?.trim().orEmpty()
                    if (dtStart < endMs && dtEnd > startMs) {
                        results.add(
                            DeviceCalendarEvent(
                                id = id,
                                title = title,
                                startTimeMs = dtStart,
                                endTimeMs = dtEnd,
                                location = loc
                            )
                        )
                    }
                }
            }
            results.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }

    fun findCalendarConflicts(
        context: Context?,
        startMs: Long,
        endMs: Long
    ): List<DeviceCalendarEvent> {
        if (endMs <= startMs) return emptyList()
        return queryCalendarEvents(context, startMs, endMs)
            .filter { it.startTimeMs < endMs && it.endTimeMs > startMs }
    }

    fun formatConflictSummaryKo(
        conflicts: List<DeviceCalendarEvent>,
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): String {
        if (conflicts.isEmpty()) return ""
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val first = conflicts.first()
        val sStr = Instant.ofEpochMilli(first.startTimeMs).atZone(zoneId).format(fmt)
        val eStr = Instant.ofEpochMilli(first.endTimeMs).atZone(zoneId).format(fmt)
        return if (conflicts.size == 1) {
            "기존 일정 '${first.title}' ($sStr~$eStr)과 시간이 겹칩니다."
        } else {
            "기존 일정 '${first.title}' ($sStr~$eStr) 외 ${conflicts.size - 1}건과 시간이 겹칩니다."
        }
    }

    /**
     * Pure offline parser for Open-Meteo `/v1/forecast` JSON response.
     */
    fun parseOpenMeteoJson(rawJson: String): OpenMeteoWeatherBrief? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val root = JSONObject(trimmed)
            val current = root.optJSONObject("current")
            val hourly = root.optJSONObject("hourly")

            val tempC = when {
                current != null && current.has("temperature_2m") -> current.optDouble("temperature_2m", 20.0)
                hourly?.optJSONArray("temperature_2m")?.let { it.length() > 0 } == true ->
                    hourly.optJSONArray("temperature_2m")!!.optDouble(0, 20.0)
                else -> return@runCatching null
            }

            val currentPrecip = current?.optDouble("precipitation", 0.0) ?: 0.0
            val weatherCode = current?.optInt("weather_code", 0)
                ?: hourly?.optJSONArray("weather_code")?.takeIf { it.length() > 0 }?.optInt(0, 0)
                ?: 0

            var maxProb = current?.optInt("precipitation_probability", 0) ?: 0
            hourly?.optJSONArray("precipitation_probability")?.let { arr ->
                val inspectCount = minOf(arr.length(), 6)
                for (i in 0 until inspectCount) {
                    val p = arr.optInt(i, 0)
                    if (p > maxProb) maxProb = p
                }
            }

            var maxHourlyPrecip = currentPrecip
            hourly?.optJSONArray("precipitation")?.let { arr ->
                val inspectCount = minOf(arr.length(), 6)
                for (i in 0 until inspectCount) {
                    val mm = arr.optDouble(i, 0.0)
                    if (mm > maxHourlyPrecip) maxHourlyPrecip = mm
                }
            }

            val needsUmbrella = maxProb >= 40 || maxHourlyPrecip >= 0.5 || weatherCode in RAIN_OR_SNOW_WMO_CODES
            val tempStr = String.format(Locale.US, "%.1f°C", tempC)
            val summaryKo = if (needsUmbrella) {
                "현재 $tempStr, 강수확률 ${maxProb}% — 외출 시 우산을 챙기세요."
            } else {
                "현재 $tempStr, 강수확률 ${maxProb}% — 우산 없이 쾌적한 외출 날씨예요."
            }
            OpenMeteoWeatherBrief(
                temperatureC = tempC,
                precipitationProbabilityPct = maxProb.coerceIn(0, 100),
                precipitationMm = maxHourlyPrecip.coerceAtLeast(0.0),
                weatherCode = weatherCode,
                needsUmbrella = needsUmbrella,
                summaryKo = summaryKo
            )
        }.getOrNull()
    }

    /**
     * Zero-API-key HTTPS GET to Open-Meteo (`https://api.open-meteo.com/v1/forecast`).
     * MUST only be invoked when `SecretarySettings.weatherPrepEnabled == true` (opt-in).
     */
    fun fetchOpenMeteoWeatherBrief(
        lat: Double = 37.5665,
        lon: Double = 126.9780,
        timeoutMs: Int = 4000
    ): OpenMeteoWeatherBrief? {
        return runCatching {
            val endpoint = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,precipitation,weather_code" +
                "&hourly=precipitation_probability,precipitation" +
                "&forecast_days=1&timezone=Asia%2FSeoul"
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
                parseOpenMeteoJson(body)
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}

// =============================================================================
// 4. Google A2UI v1.0 Bridge for Proactive Proposals
// =============================================================================

object ProactiveProposalToA2uiBridge {

    const val ACTION_APPROVE = "secretary.approve"
    const val ACTION_DISMISS = "secretary.dismiss"
    const val ACTION_SNOOZE = "secretary.snooze"
    const val ACTION_REPLY = "secretary.reply"
    const val ACTION_OPEN_MAP = "secretary.open_map"

    fun surfaceIdFor(proposal: ProactiveProposal): String {
        val safeId = proposal.id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        return "secretary_$safeId"
    }

    private fun ptr(path: String): JSONObject = JSONObject().put("@path", path)

    /**
     * Compiles a single [ProactiveProposal] into canonical Google A2UI v1.0 messages
     * (`createSurface`, `updateComponents`, `updateDataModel`).
     */
    fun buildMessages(
        proposal: ProactiveProposal,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): List<JSONObject> {
        val version = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.VERSION_0_9
        } else {
            A2uiProtocolConstants.VERSION_1_0
        }
        val catalogId = if (version == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.BASIC_CATALOG_V0_9
        } else {
            A2uiProtocolConstants.BASIC_CATALOG_V1_0
        }
        val surfaceId = surfaceIdFor(proposal)

        val rootChildren = JSONArray()
        val components = JSONArray()
        val deepLink = if (proposal.location.isNotBlank()) {
            AppDetailPlaybookRegistry.buildDeepLinkForPlace(proposal.location)
        } else {
            ""
        }

        val evidence = AtomicRollbackAndExplainEngine.buildEvidenceCard(proposal)

        val dataObj = JSONObject().apply {
            put("id", proposal.id)
            put("type", proposal.type.name)
            put("status", proposal.status.name)
            put("eyebrow", "${proposal.type.badgeKo} · ${proposal.sourceApp} · ${proposal.sender}")
            put("titleKo", proposal.titleKo)
            put("summaryKo", proposal.summaryKo)
            put("detailKo", proposal.detailKo)
            put("quotedExcerpt", proposal.quotedExcerpt)
            put("hasCalendarConflict", proposal.hasCalendarConflict)
            put("conflictSummaryKo", proposal.conflictSummaryKo)
            put("location", proposal.location)
            put("deepLink", deepLink)
            put("meetingUrl", proposal.meetingUrl)
            put("packageName", proposal.packageName)
            put("roomOrTitle", proposal.roomOrTitle)
            put("sender", proposal.sender)
            put("primaryActionKey", proposal.primaryAction.key)
            put("primaryActionKind", proposal.primaryAction.kind.name)
            put("primaryActionLabel", proposal.primaryAction.labelKo)
            put("readOnlyAdvisory", proposal.readOnlyAdvisory)
            put("quickReplyDrafts", JSONArray(proposal.quickReplyDrafts))
            put("evidence", evidence.toJson())
            put(
                "evidenceSummaryKo",
                "왜 제안했나요? ${evidence.sourceSummaryKo} | ${evidence.ruleSummaryKo} | ${evidence.reversibilityLabelKo} (${evidence.confidenceBasisKo})"
            )
        }

        // Eyebrow + Title + Summary
        rootChildren.put("sec_eyebrow").put("sec_title").put("sec_summary")
        components.put(
            JSONObject()
                .put("id", "sec_eyebrow")
                .put("component", "Text")
                .put("text", ptr("/proposal/eyebrow"))
                .put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "sec_title")
                .put("component", "Text")
                .put("text", ptr("/proposal/titleKo"))
                .put("variant", "h3")
        )
        components.put(
            JSONObject()
                .put("id", "sec_summary")
                .put("component", "Text")
                .put("text", ptr("/proposal/summaryKo"))
                .put("variant", "body")
        )

        // Calendar conflict alert card
        if (proposal.hasCalendarConflict && proposal.conflictSummaryKo.isNotBlank()) {
            rootChildren.put("sec_conflict_card")
            components.put(
                JSONObject()
                    .put("id", "sec_conflict_card")
                    .put("component", "Card")
                    .put("child", "sec_conflict_txt")
            )
            components.put(
                JSONObject()
                    .put("id", "sec_conflict_txt")
                    .put("component", "Text")
                    .put("text", ptr("/proposal/conflictSummaryKo"))
                    .put("variant", "body")
            )
        }

        // Quoted excerpt card
        if (proposal.quotedExcerpt.isNotBlank()) {
            rootChildren.put("sec_quote_card")
            components.put(
                JSONObject()
                    .put("id", "sec_quote_card")
                    .put("component", "Card")
                    .put("child", "sec_quote_txt")
            )
            components.put(
                JSONObject()
                    .put("id", "sec_quote_txt")
                    .put("component", "Text")
                    .put("text", ptr("/proposal/quotedExcerpt"))
                    .put("variant", "caption")
            )
        }

        // Quick-reply drafts row if present
        if (proposal.quickReplyDrafts.isNotEmpty()) {
            val replyRowChildren = JSONArray()
            proposal.quickReplyDrafts.take(3).forEachIndexed { idx, _ ->
                val lblId = "sec_reply_lbl_$idx"
                val btnId = "sec_reply_btn_$idx"
                replyRowChildren.put(btnId)
                components.put(
                    JSONObject()
                        .put("id", lblId)
                        .put("component", "Text")
                        .put("text", ptr("/proposal/quickReplyDrafts/$idx"))
                        .put("variant", "caption")
                )
                components.put(
                    JSONObject()
                        .put("id", btnId)
                        .put("component", "Button")
                        .put("variant", "default")
                        .put("child", lblId)
                        .put(
                            "action",
                            JSONObject().put(
                                "event",
                                JSONObject()
                                    .put("name", ACTION_REPLY)
                                    .put(
                                        "context",
                                        JSONObject()
                                            .put("proposalId", ptr("/proposal/id"))
                                            .put("replyText", ptr("/proposal/quickReplyDrafts/$idx"))
                                            .put("packageName", ptr("/proposal/packageName"))
                                            .put("roomOrTitle", ptr("/proposal/roomOrTitle"))
                                    )
                            )
                        )
                )
            }
            rootChildren.put("sec_reply_row")
            components.put(
                JSONObject()
                    .put("id", "sec_reply_row")
                    .put("component", "Row")
                    .put("children", replyRowChildren)
            )
        }

        // Primary & Secondary action controls row
        val actionRowChildren = JSONArray()

        // Approve button
        actionRowChildren.put("sec_approve_btn")
        components.put(
            JSONObject()
                .put("id", "sec_approve_lbl")
                .put("component", "Text")
                .put("text", ptr("/proposal/primaryActionLabel"))
                .put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "sec_approve_btn")
                .put("component", "Button")
                .put("variant", "primary")
                .put("child", "sec_approve_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_APPROVE)
                            .put(
                                "context",
                                JSONObject()
                                    .put("proposalId", ptr("/proposal/id"))
                                    .put("actionKey", ptr("/proposal/primaryActionKey"))
                                    .put("actionKind", ptr("/proposal/primaryActionKind"))
                            )
                    )
                )
        )

        // Open map button when location exists
        if (proposal.location.isNotBlank() && proposal.location != proposal.meetingUrl) {
            actionRowChildren.put("sec_map_btn")
            components.put(
                JSONObject()
                    .put("id", "sec_map_lbl")
                    .put("component", "Text")
                    .put("text", "지도 열기")
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "sec_map_btn")
                    .put("component", "Button")
                    .put("variant", "default")
                    .put("child", "sec_map_lbl")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_OPEN_MAP)
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("proposalId", ptr("/proposal/id"))
                                        .put("location", ptr("/proposal/location"))
                                        .put("deepLink", ptr("/proposal/deepLink"))
                                )
                        )
                    )
            )
        }

        // Snooze button
        actionRowChildren.put("sec_snooze_btn")
        components.put(
            JSONObject()
                .put("id", "sec_snooze_lbl")
                .put("component", "Text")
                .put("text", "30분 뒤")
                .put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "sec_snooze_btn")
                .put("component", "Button")
                .put("variant", "default")
                .put("child", "sec_snooze_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_SNOOZE)
                            .put(
                                "context",
                                JSONObject()
                                    .put("proposalId", ptr("/proposal/id"))
                                    .put("snoozeMinutes", 30)
                            )
                    )
                )
        )

        // Dismiss button
        actionRowChildren.put("sec_dismiss_btn")
        components.put(
            JSONObject()
                .put("id", "sec_dismiss_lbl")
                .put("component", "Text")
                .put("text", "닫기")
                .put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "sec_dismiss_btn")
                .put("component", "Button")
                .put("variant", "default")
                .put("child", "sec_dismiss_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_DISMISS)
                            .put("context", JSONObject().put("proposalId", ptr("/proposal/id")))
                    )
                )
        )

        // Inline "왜 제안했나요?" Explainable Evidence Card
        rootChildren.put("sec_evidence_card")
        components.put(
            JSONObject()
                .put("id", "sec_evidence_card")
                .put("component", "Card")
                .put("child", "sec_evidence_txt")
        )
        components.put(
            JSONObject()
                .put("id", "sec_evidence_txt")
                .put("component", "Text")
                .put("text", ptr("/proposal/evidenceSummaryKo"))
                .put("variant", "caption")
        )

        rootChildren.put("sec_divider").put("sec_actions_row")
        components.put(
            JSONObject()
                .put("id", "sec_divider")
                .put("component", "Divider")
                .put("axis", "horizontal")
        )
        components.put(
            JSONObject()
                .put("id", "sec_actions_row")
                .put("component", "Row")
                .put("children", actionRowChildren)
        )

        val allComponents = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "root")
                    .put("component", "Column")
                    .put("children", rootChildren)
            )
            for (i in 0 until components.length()) {
                put(components.get(i))
            }
        }

        return listOf(
            JSONObject()
                .put("version", version)
                .put(
                    "createSurface",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("catalogId", catalogId)
                        .put("sendDataModel", true)
                ),
            JSONObject()
                .put("version", version)
                .put(
                    "updateComponents",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("components", allComponents)
                ),
            JSONObject()
                .put("version", version)
                .put(
                    "updateDataModel",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("path", "/proposal")
                        .put("value", dataObj)
                )
        )
    }

    fun toJsonl(
        proposal: ProactiveProposal,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String = buildMessages(proposal, protocolVersion).joinToString("\n") { it.toString() }

    /**
     * Compiles a list of pending proposals into a single A2UI v1.0 Chief-of-Staff inbox surface.
     */
    fun buildInboxMessages(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_inbox",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): List<JSONObject> {
        val version = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.VERSION_0_9
        } else {
            A2uiProtocolConstants.VERSION_1_0
        }
        val catalogId = if (version == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.BASIC_CATALOG_V0_9
        } else {
            A2uiProtocolConstants.BASIC_CATALOG_V1_0
        }

        val rootChildren = JSONArray()
        val components = JSONArray()
        val itemsData = JSONArray()

        rootChildren.put("inbox_header")
        components.put(
            JSONObject()
                .put("id", "inbox_header")
                .put("component", "Text")
                .put("text", ptr("/inbox/headline"))
                .put("variant", "h2")
        )

        val visible = proposals.take(8)
        visible.forEachIndexed { idx, p ->
            itemsData.put(
                JSONObject()
                    .put("id", p.id)
                    .put("titleKo", p.titleKo)
                    .put("summaryKo", p.summaryKo)
                    .put("badgeKo", "${p.type.badgeKo} · ${p.sourceApp}")
                    .put("actionLabel", p.primaryAction.labelKo)
                    .put("actionKey", p.primaryAction.key)
                    .put("actionKind", p.primaryAction.kind.name)
            )
            val cardId = "item_card_$idx"
            val colId = "item_col_$idx"
            val badgeId = "item_badge_$idx"
            val titleId = "item_title_$idx"
            val sumId = "item_sum_$idx"
            val btnRowId = "item_row_$idx"
            val appLblId = "item_approve_lbl_$idx"
            val appBtnId = "item_approve_btn_$idx"
            val disLblId = "item_dismiss_lbl_$idx"
            val disBtnId = "item_dismiss_btn_$idx"

            rootChildren.put(cardId)
            components.put(JSONObject().put("id", cardId).put("component", "Card").put("child", colId))
            components.put(
                JSONObject()
                    .put("id", colId)
                    .put("component", "Column")
                    .put("children", JSONArray().put(badgeId).put(titleId).put(sumId).put(btnRowId))
            )
            components.put(
                JSONObject()
                    .put("id", badgeId)
                    .put("component", "Text")
                    .put("text", ptr("/inbox/items/$idx/badgeKo"))
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", titleId)
                    .put("component", "Text")
                    .put("text", ptr("/inbox/items/$idx/titleKo"))
                    .put("variant", "h4")
            )
            components.put(
                JSONObject()
                    .put("id", sumId)
                    .put("component", "Text")
                    .put("text", ptr("/inbox/items/$idx/summaryKo"))
                    .put("variant", "body")
            )
            components.put(
                JSONObject()
                    .put("id", appLblId)
                    .put("component", "Text")
                    .put("text", ptr("/inbox/items/$idx/actionLabel"))
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", appBtnId)
                    .put("component", "Button")
                    .put("variant", "primary")
                    .put("child", appLblId)
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_APPROVE)
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("proposalId", ptr("/inbox/items/$idx/id"))
                                        .put("actionKey", ptr("/inbox/items/$idx/actionKey"))
                                        .put("actionKind", ptr("/inbox/items/$idx/actionKind"))
                                )
                        )
                    )
            )
            components.put(
                JSONObject()
                    .put("id", disLblId)
                    .put("component", "Text")
                    .put("text", "닫기")
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", disBtnId)
                    .put("component", "Button")
                    .put("variant", "default")
                    .put("child", disLblId)
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_DISMISS)
                                .put("context", JSONObject().put("proposalId", ptr("/inbox/items/$idx/id")))
                        )
                    )
            )
            components.put(
                JSONObject()
                    .put("id", btnRowId)
                    .put("component", "Row")
                    .put("children", JSONArray().put(appBtnId).put(disBtnId))
            )
        }

        if (visible.isEmpty()) {
            rootChildren.put("inbox_empty")
            components.put(
                JSONObject()
                    .put("id", "inbox_empty")
                    .put("component", "Text")
                    .put("text", "현재 대기 중인 AI 비서 제안이 없습니다.")
                    .put("variant", "body")
            )
        }

        val allComponents = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "root")
                    .put("component", "Column")
                    .put("children", rootChildren)
            )
            for (i in 0 until components.length()) {
                put(components.get(i))
            }
        }

        val inboxData = JSONObject()
            .put("headline", "AI 비서 제안 (${visible.size}건)")
            .put("count", visible.size)
            .put("items", itemsData)

        return listOf(
            JSONObject()
                .put("version", version)
                .put(
                    "createSurface",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("catalogId", catalogId)
                        .put("sendDataModel", true)
                ),
            JSONObject()
                .put("version", version)
                .put(
                    "updateComponents",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("components", allComponents)
                ),
            JSONObject()
                .put("version", version)
                .put(
                    "updateDataModel",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("path", "/inbox")
                        .put("value", inboxData)
                )
        )
    }

    fun toInboxJsonl(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_inbox",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String = buildInboxMessages(proposals, surfaceId, protocolVersion).joinToString("\n") { it.toString() }

    /**
     * Publishes a single [ProactiveProposal] directly into [A2uiSurfaceRegistry] and returns its `surfaceId`.
     */
    fun publishToA2uiRegistry(
        proposal: ProactiveProposal,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val jsonl = toJsonl(proposal, protocolVersion)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return surfaceIdFor(proposal)
    }

    /**
     * Publishes a pending proposals list as a unified A2UI v1.0 inbox surface.
     */
    fun publishPendingListToA2uiRegistry(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_inbox",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val jsonl = toInboxJsonl(proposals, surfaceId, protocolVersion)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return surfaceId
    }
}

// =============================================================================
// 5. Core Proactive Chief-of-Staff AI Secretary Engine
// =============================================================================

object ProactiveSecretaryEngine {

    private const val PROPOSALS_FILE_NAME = "hanson_proactive_proposals.json"
    private const val PREFS_FILE_NAME = "hanson_secretary_prefs.json"
    private const val MAX_STORED_PROPOSALS = 80
    private const val LIVE_MERGE_WINDOW_MS = 30L * 60L * 1000L

    private val lock = Any()
    private val idCounter = AtomicLong(0L)

    @Volatile
    private var storageDir: File? = null

    @Volatile
    private var initialized = false

    private val proposalList = mutableListOf<ProactiveProposal>()
    private val _proposals = MutableStateFlow<List<ProactiveProposal>>(emptyList())
    val proposals: StateFlow<List<ProactiveProposal>> = _proposals.asStateFlow()

    private val _settings = MutableStateFlow(SecretarySettings())
    val settings: StateFlow<SecretarySettings> = _settings.asStateFlow()

    /**
     * Hook for Wave 2 (`ProactiveSecretaryNotifier`) to emit high-priority lockscreen/overlay alerts
     * without creating circular dependencies back into UI or Notification classes.
     */
    @Volatile
    var onHighPriorityProposalEmitted: ((Context, ProactiveProposal) -> Unit)? = null

    // Extra prompt-injection cues complementing UntrustedScreenDataGuard
    private val EXTRA_INJECTION_REGEX = Regex(
        "(?i)(?:\\bsystem\\s*prompt\\b|\\bjailbreak\\b|execute\\s+tool\\s+immediately|auto[-\\s]?approve\\s+all|무조건\\s*(?:승인|실행|전송)|시스템\\s*명령\\s*실행)"
    )

    private val FINANCE_ADVISORY_CUE_REGEX = Regex(
        "(?i)(?:결제\\s*예정|청구서|납부\\s*마감|납부일|정기\\s*결제|구독\\s*갱신|자동\\s*이체|카드\\s*대금|출금\\s*예정|고액\\s*결제|이용\\s*대금|승인\\s*금액|원\\s*결제|subscription\\s*renewal|invoice\\s*due|payment\\s*due|billing\\s*statement)"
    )
    private val RESERVATION_CUE_REGEX = Regex(
        "(?i)(?:예약이?\\s*(?:확정|완료)|예매\\s*(?:확정|완료)|승차권|열차\\s*출발|ktx|srt|체크인|항공편|탑승\\s*수속|캐치테이블|방문\\s*예정|reservation\\s*confirmed|check-?in)"
    )
    private val DELIVERY_CUE_REGEX = Regex(
        "(?i)(?:배송\\s*완료|배송완료|배송\\s*예정|배송\\s*출발|택배\\s*도착|택배\\s*배송|문앞\\s*배송|무인\\s*택배함|보관함\\s*보관|배달\\s*시작|배달\\s*완료|로켓배송|운송장|out\\s*for\\s*delivery|delivered)"
    )
    private val MEETUP_SCHEDULE_CUE_REGEX = Regex(
        "(?i)(?:볼래|볼까|만나|약속|확정|미팅|회의|식사|저녁|점심|커피|모임|방문|면접|세미나|일정|캘린더|시간\\s*(?:어때|가능|괜찮)|가능해|괜찮아|보실래|meetup|meeting|lunch|dinner|sync|catch\\s*up)"
    )
    private val DEADLINE_TASK_CUE_REGEX = Regex(
        "(?i)(?:오늘까지|내일\\s*오전까지|내일까지|(?:월|화|수|목|금|토|일)요일까지|마감|데드라인|회신\\s*부탁|회신\\s*바랍니다|답변\\s*부탁|리뷰\\s*부탁|pr\\s*리뷰|코드\\s*리뷰|승인\\s*요청|결재\\s*요청|견적서|회의록|제출\\s*(?:부탁|마감|요청)|확인\\s*부탁|검토\\s*부탁|공유\\s*부탁|action\\s*required|review\\s*requested|due\\s*by|deadline|please\\s*review|please\\s*approve)"
    )
    private val QUESTION_CUE_REGEX = Regex(
        "(?i)(?:\\?|어때요?|가능해|가능할까|괜찮아|괜찮으세|언제|어디야|어디세|확인했|보냈어|되나요|될까|알려줄래|알려주세요|줄\\s*수\\s*있|도착했|how\\s*about|can\\s*you|could\\s*you|did\\s*you|are\\s*you)"
    )
    private val HIGH_URGENCY_QUESTION_REGEX = Regex(
        "(?i)(?:지금|급해|급합니다|빨리|당장|곧\\s*시작|어디야|기다리고|도착했|asap|urgent|right\\s*now)"
    )

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageDir = dir
            loadFromDiskLocked()
            initialized = true
        }
    }

    /**
     * Points disk persistence to an isolated directory (for JUnit4 tests).
     */
    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            storageDir = dir
            if (clearExisting) {
                proposalList.clear()
                _proposals.value = emptyList()
                _settings.value = SecretarySettings()
                File(dir, PROPOSALS_FILE_NAME).delete()
                File(dir, PREFS_FILE_NAME).delete()
            } else {
                loadFromDiskLocked()
            }
            initialized = true
        }
    }

    /**
     * Resets all in-memory state, test overrides, and callbacks (for JUnit4 `@Before`/`@After`).
     */
    fun resetForTesting() {
        synchronized(lock) {
            proposalList.clear()
            _proposals.value = emptyList()
            _settings.value = SecretarySettings()
            storageDir = null
            initialized = false
            onHighPriorityProposalEmitted = null
            ProactiveContextApiConnector.calendarEventsProviderOverride = null
        }
        NotificationThreadBuffer.clearAll()
    }

    fun listProposals(statusFilter: ProactiveProposalStatus? = null): List<ProactiveProposal> {
        return synchronized(lock) {
            if (statusFilter == null) {
                proposalList.toList()
            } else {
                proposalList.filter { it.status == statusFilter }
            }
        }
    }

    fun listPendingProposals(): List<ProactiveProposal> = listProposals(ProactiveProposalStatus.PENDING)

    fun getProposal(id: String): ProactiveProposal? {
        val target = id.trim()
        if (target.isEmpty()) return null
        return synchronized(lock) {
            proposalList.firstOrNull { it.id == target }
        }
    }

    fun updateSettings(transform: (SecretarySettings) -> SecretarySettings): SecretarySettings {
        synchronized(lock) {
            val next = transform(_settings.value)
            _settings.value = next
            saveSettingsLocked()
            return next
        }
    }

    fun setSettings(newSettings: SecretarySettings): SecretarySettings = updateSettings { newSettings }

    fun togglePackageMute(packageName: String, muted: Boolean? = null): SecretarySettings {
        val clean = packageName.trim()
        if (clean.isEmpty()) return _settings.value
        return updateSettings { cur ->
            val nextSet = cur.mutedPackages.toMutableSet()
            val shouldMute = muted ?: !cur.isPackageMuted(clean)
            if (shouldMute) {
                nextSet.add(clean)
            } else {
                nextSet.removeAll { it.equals(clean, ignoreCase = true) }
            }
            cur.copy(mutedPackages = nextSet)
        }
    }

    fun isInQuietHours(
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): Boolean {
        val hour = Instant.ofEpochMilli(nowMs).atZone(zoneId).hour
        return _settings.value.isWithinQuietHours(hour)
    }

    fun shouldEmitLiveAlert(
        proposal: ProactiveProposal,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): Boolean {
        val currentSettings = _settings.value
        if (!currentSettings.enabled) return false
        if (currentSettings.isPackageMuted(proposal.packageName)) return false
        if (isInQuietHours(nowMs, zoneId)) return false
        val roomProfile = RoomUrgencyCalibrationStore.getProfile(proposal.roomOrTitle)
        if (roomProfile?.reducedFrequency == true && !proposal.hasCalendarConflict) {
            return false
        }
        return proposal.hasCalendarConflict ||
            proposal.confidence >= 0.70 ||
            proposal.type in setOf(
                ProactiveProposalType.SCAM_PRE_SHIELD,
                ProactiveProposalType.CALENDAR_EVENT,
                ProactiveProposalType.SUBSCRIPTION_TRIAL_DEFENSE,
                ProactiveProposalType.MEETING_LINK_PREP,
                ProactiveProposalType.SELF_COMMITMENT_DEBT,
                ProactiveProposalType.RESERVATION_COMMUTE,
                ProactiveProposalType.FOCUS_REENTRY_DIGEST,
                ProactiveProposalType.DEADLINE_TASK,
                ProactiveProposalType.UPCOMING_PREP_BRIEF,
                ProactiveProposalType.BILLING_SUBSCRIPTION_WATCH
            )
    }

    /**
     * Registers or enriches an externally constructed [ProactiveProposal] (e.g., from
     * `CommitmentDebtTrackerEngine`, `RelationshipCareCadenceEngine`, or `NotificationStormShieldEngine`).
     */
    fun registerExternalProposal(
        proposal: ProactiveProposal,
        nowMs: Long = proposal.updatedAtMs
    ): ProactiveProposal = upsertOrEnrichProposal(proposal, nowMs)

    /**
     * Primary screen-off notification ingestion pipeline.
     * Enforces smishing pre-shield (FIRST), secret/OTP/card/RRN/API-key blocking,
     * prompt-injection rejection, noise/ad suppression, per-app mute, multi-turn thread enrichment,
     * calendar conflict checks, verifiable PrivacyAndQuotaProofLedger accounting, and atomic JSON persistence.
     */
    fun onNotificationPosted(
        context: Context?,
        notification: InterceptedNotification,
        threadContext: NotificationThreadContext? = null,
        referenceTimeMs: Long = notification.timestampMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE,
        emitHighPriorityAlert: Boolean = true
    ): ProactiveProposal? {
        ensureInitialized(context)
        PrivacyAndQuotaProofLedger.ensureInitialized(context)
        RoomUrgencyCalibrationStore.ensureInitialized(context)

        val currentSettings = _settings.value
        if (!currentSettings.enabled) return null
        if (currentSettings.isPackageMuted(notification.packageName)) return null

        val rawText = notification.messageText.trim()
        if (rawText.isEmpty()) return null

        PrivacyAndQuotaProofLedger.recordLocalZeroCostScan(nowMs = referenceTimeMs)

        // 1. Prompt-injection hardline check on both raw notification and thread context
        val rawCombinedForSecurity = buildString {
            append(notification.sender).append('\n')
            append(notification.roomOrTitle).append('\n')
            append(rawText)
            if (threadContext != null && threadContext.combinedText.isNotBlank()) {
                append('\n').append(threadContext.combinedText)
            }
        }
        if (containsPromptInjection(rawCombinedForSecurity)) {
            PrivacyAndQuotaProofLedger.recordBlockedPromptInjection(nowMs = referenceTimeMs)
            return null
        }

        // 2. Hard secret check before noise suppression so OTP/card/RRN/key blocks are counted in the proof ledger
        if (NotificationThreadBuffer.containsHardSecret(rawText)) {
            PrivacyAndQuotaProofLedger.recordBlockedSecret(nowMs = referenceTimeMs)
            return null
        }

        // 3. Smishing / Impersonation Pre-Shield FIRST (highest-priority zero-network scam protection!)
        val sourceAppPre = AppDetailPlaybookRegistry.resolveAppLabelKo(notification.packageName)
            ?: notification.appLabel.ifBlank { notification.packageName.substringAfterLast('.') }
        val scamReport = SmishingAndPlainKoreanEngine.scoreSmishingRisk(
            sender = notification.sender,
            rawText = rawText,
            packageName = notification.packageName
        )
        if (scamReport.isHighRisk) {
            val scamProposal = SmishingAndPlainKoreanEngine.buildScamShieldProposal(
                notification = notification,
                report = scamReport,
                sourceApp = sourceAppPre,
                referenceTimeMs = referenceTimeMs
            )
            val persistedScam = upsertOrEnrichProposal(scamProposal, referenceTimeMs)
            if (emitHighPriorityAlert &&
                context != null &&
                shouldEmitLiveAlert(persistedScam, nowMs = referenceTimeMs, zoneId = zoneId)
            ) {
                runCatching { onHighPriorityProposalEmitted?.invoke(context, persistedScam) }
            }
            return persistedScam
        }

        // 4. Noise & ad suppression
        if (AppDetailPlaybookRegistry.isNoiseNotification(notification.packageName, notification.sender, rawText)) {
            return null
        }

        // 4. Secret / OTP / card / RRN / API-key blocking (while redacting door/locker PINs for delivery)
        val sanitizedCurrentText = sanitizeOrRejectText(rawText, notification.packageName)
        if (sanitizedCurrentText == null) {
            PrivacyAndQuotaProofLedger.recordBlockedSecret(nowMs = referenceTimeMs)
            return null
        }
        if (sanitizedCurrentText != rawText && sanitizedCurrentText.contains("[PIN 비공개]")) {
            PrivacyAndQuotaProofLedger.recordBlockedSecret(nowMs = referenceTimeMs)
        }
        val sanitizedRoomLines = buildSanitizedThreadLines(
            notification = notification,
            sanitizedCurrentText = sanitizedCurrentText,
            threadContext = threadContext
        )
        val combinedText = sanitizedRoomLines.joinToString("\n")
        if (combinedText.isBlank()) return null

        val playbook = AppDetailPlaybookRegistry.findByPackage(notification.packageName)
        val sourceApp = AppDetailPlaybookRegistry.resolveAppLabelKo(notification.packageName)
            ?: notification.appLabel.ifBlank { notification.packageName.substringAfterLast('.') }
        val roomKey = notification.roomOrTitle.ifBlank { notification.sender.ifBlank { sourceApp } }

        val candidate = detectProposalFromSignal(
            context = context,
            notification = notification,
            playbook = playbook,
            sourceApp = sourceApp,
            roomKey = roomKey,
            sanitizedCurrentText = sanitizedCurrentText,
            combinedText = combinedText,
            referenceTimeMs = referenceTimeMs,
            zoneId = zoneId
        ) ?: return null

        val persisted = upsertOrEnrichProposal(candidate, referenceTimeMs)

        if (emitHighPriorityAlert &&
            context != null &&
            shouldEmitLiveAlert(persisted, nowMs = referenceTimeMs, zoneId = zoneId)
        ) {
            runCatching { onHighPriorityProposalEmitted?.invoke(context, persisted) }
        }
        return persisted
    }

    /**
     * Convenience ingestion API for `:18888/secretary/ingest` and `hanson_secretary_sync.py`.
     */
    fun ingestExternalSignal(
        context: Context?,
        packageName: String,
        sender: String,
        text: String,
        appLabel: String = "",
        roomOrTitle: String = "",
        canQuickReply: Boolean = false,
        timestampMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): ProactiveProposal? {
        val safePkg = packageName.trim().ifEmpty { "com.hanson.external" }
        val resolvedLabel = appLabel.trim()
            .ifBlank { AppDetailPlaybookRegistry.resolveAppLabelKo(safePkg) ?: safePkg.substringAfterLast('.') }
        val safeSender = sender.trim().ifEmpty { resolvedLabel }
        val safeRoom = roomOrTitle.trim().ifEmpty { safeSender }

        NotificationThreadBuffer.appendMessage(
            packageName = safePkg,
            roomKey = safeRoom,
            sender = safeSender,
            text = text,
            timestampMs = timestampMs,
            nowMs = timestampMs
        )
        val thread = NotificationThreadBuffer.getThreadContext(safePkg, safeRoom, nowMs = timestampMs)
        val synthNotif = InterceptedNotification(
            sessionId = "ext_${safePkg.substringAfterLast('.')}_${(safeRoom.hashCode() and 0x7FFFFFFF).toString(16)}",
            notificationKey = "ext::$safePkg::$safeRoom::$timestampMs",
            packageName = safePkg,
            appLabel = resolvedLabel,
            sender = safeSender,
            roomOrTitle = safeRoom,
            messageText = text,
            timestampMs = timestampMs,
            canQuickReply = canQuickReply
        )
        return onNotificationPosted(
            context = context,
            notification = synthNotif,
            threadContext = thread,
            referenceTimeMs = timestampMs,
            zoneId = zoneId,
            emitHighPriorityAlert = true
        )
    }

    /**
     * Runs proactive context sweeps: unanswered replyable questions + upcoming calendar prep brief.
     */
    fun scanProactiveContext(
        context: Context?,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE,
        weatherBriefOverride: OpenMeteoWeatherBrief? = null
    ): List<ProactiveProposal> {
        ensureInitialized(context)
        if (!_settings.value.enabled) return emptyList()

        val emitted = mutableListOf<ProactiveProposal>()
        emitted.addAll(scanUnansweredFollowups(context, nowMs, zoneId))
        emitted.addAll(scanUpcomingPrepBriefs(context, nowMs, zoneId, weatherBriefOverride))
        RelationshipCareCadenceEngine.ensureInitialized(context)
        RelationshipCareCadenceEngine.scanOverdueVips(nowMs).forEach { nudge ->
            emitted.add(upsertOrEnrichProposal(nudge, nowMs))
        }
        return emitted
    }

    /**
     * Flushes any buffered storm alerts from `NotificationStormShieldEngine` into a single
     * 3-line `FOCUS_REENTRY_DIGEST` proposal + A2UI surface.
     */
    fun flushFocusShieldDigest(
        sessionId: String = "focus_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis(),
        publishToA2ui: Boolean = true
    ): ProactiveProposal? {
        val digest = com.hanson.mobileuse.notification.NotificationStormShieldEngine
            .buildReentryDigestAndClear(sessionId = sessionId, nowMs = nowMs)
            ?: return null
        val (proposal, _) = com.hanson.mobileuse.notification.NotificationStormShieldEngine
            .buildProposalAndA2ui(digest, publishToRegistry = publishToA2ui)
        return upsertOrEnrichProposal(proposal, nowMs)
    }

    /**
     * Scans `RemoteInputSessionCache` for unreplied human questions older than `unansweredFollowupMinutes`.
     */
    fun scanUnansweredFollowups(
        context: Context?,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): List<ProactiveProposal> {
        ensureInitialized(context)
        val currentSettings = _settings.value
        if (!currentSettings.enabled) return emptyList()

        val minAgeMs = currentSettings.unansweredFollowupMinutes * 60_000L
        val stale = RemoteInputSessionCache.listUnrepliedOlderThan(
            minAgeMs = minAgeMs,
            nowMs = nowMs,
            onlyReplyable = true
        )

        val created = mutableListOf<ProactiveProposal>()
        for (notif in stale) {
            if (currentSettings.isPackageMuted(notif.packageName)) continue
            if (AppDetailPlaybookRegistry.isFinanceReadOnlyPackage(notif.packageName)) continue
            if (AppDetailPlaybookRegistry.isNoiseNotification(notif.packageName, notif.sender, notif.messageText)) continue
            if (containsPromptInjection(notif.messageText)) continue
            val sanitized = sanitizeOrRejectText(notif.messageText, notif.packageName) ?: continue
            if (!QUESTION_CUE_REGEX.containsMatchIn(sanitized)) continue

            val sourceApp = AppDetailPlaybookRegistry.resolveAppLabelKo(notif.packageName)
                ?: notif.appLabel.ifBlank { notif.packageName }
            val ageMin = ((nowMs - notif.timestampMs) / 60_000L).coerceAtLeast(1L)
            val proposal = buildUnansweredFollowupProposal(
                notification = notif,
                sourceApp = sourceApp,
                roomKey = notif.roomOrTitle.ifBlank { notif.sender },
                sanitizedText = sanitized,
                elapsedMinutes = ageMin,
                nowMs = nowMs
            )
            val saved = upsertOrEnrichProposal(proposal, nowMs)
            created.add(saved)
        }
        return created
    }

    /**
     * Scans upcoming device calendar events (next 4 hours) for conflicts, back-to-back buffer warnings,
     * related unread chat threads, and optional opt-in Open-Meteo rain/umbrella advice.
     */
    fun scanUpcomingPrepBriefs(
        context: Context?,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE,
        weatherBriefOverride: OpenMeteoWeatherBrief? = null
    ): List<ProactiveProposal> {
        ensureInitialized(context)
        val currentSettings = _settings.value
        if (!currentSettings.enabled) return emptyList()

        val windowEndMs = nowMs + 4L * 60L * 60L * 1000L
        val upcoming = ProactiveContextApiConnector.queryCalendarEvents(context, nowMs, windowEndMs)
        if (upcoming.isEmpty()) return emptyList()

        val weather = weatherBriefOverride ?: if (currentSettings.weatherPrepEnabled) {
            ProactiveContextApiConnector.fetchOpenMeteoWeatherBrief()
        } else {
            null
        }

        val activeThreads = NotificationThreadBuffer.listActiveThreads(nowMs = nowMs, limit = 15)
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val results = mutableListOf<ProactiveProposal>()

        for (i in upcoming.indices) {
            val ev = upcoming[i]
            val overlapping = upcoming.filter { other ->
                other.id != ev.id && other.startTimeMs < ev.endTimeMs && other.endTimeMs > ev.startTimeMs
            }
            val nextEvent = upcoming.getOrNull(i + 1)
            val gapMin = if (nextEvent != null && nextEvent.startTimeMs >= ev.endTimeMs) {
                (nextEvent.startTimeMs - ev.endTimeMs) / 60_000L
            } else {
                Long.MAX_VALUE
            }
            val isBackToBack = gapMin in 0L..10L

            val relatedThread = activeThreads.firstOrNull { thread ->
                val titleTokens = ev.title.split(Regex("[\\s,·()]+")).filter { it.length >= 2 }
                val locTokens = ev.location.split(Regex("[\\s,·()]+")).filter { it.length >= 2 }
                val haystack = "${thread.roomKey} ${thread.combinedText}"
                titleTokens.any { haystack.contains(it, ignoreCase = true) } ||
                    locTokens.any { haystack.contains(it, ignoreCase = true) }
            }

            val minsUntil = ((ev.startTimeMs - nowMs) / 60_000L).coerceAtLeast(0L)
            val shouldEmit = overlapping.isNotEmpty() ||
                isBackToBack ||
                relatedThread != null ||
                weather?.needsUmbrella == true ||
                minsUntil <= 90L
            if (!shouldEmit) continue

            val startStr = Instant.ofEpochMilli(ev.startTimeMs).atZone(zoneId).format(fmt)
            val endStr = Instant.ofEpochMilli(ev.endTimeMs).atZone(zoneId).format(fmt)
            val conflictSummary = ProactiveContextApiConnector.formatConflictSummaryKo(overlapping, zoneId)

            val detailLines = mutableListOf<String>()
            detailLines.add("일정 시간: $startStr~$endStr (${minsUntil}분 후 시작)")
            if (ev.location.isNotBlank()) detailLines.add("장소: ${ev.location}")
            if (conflictSummary.isNotBlank()) detailLines.add("충돌 경고: $conflictSummary")
            if (isBackToBack && nextEvent != null) {
                detailLines.add("연속 일정 주의: 직후 '${nextEvent.title}'까지 여유 ${gapMin}분입니다.")
            }
            if (relatedThread != null) {
                val latestMsg = relatedThread.messages.lastOrNull()
                detailLines.add("관련 미확인 대화(${relatedThread.roomKey}): ${latestMsg?.sender.orEmpty()} \"${latestMsg?.text.orEmpty().take(50)}\"")
            }
            if (weather != null) {
                detailLines.add("날씨 브리핑: ${weather.summaryKo}")
            }

            val summaryParts = buildList {
                add("${startStr} 시작")
                if (overlapping.isNotEmpty()) add("일정 충돌 ${overlapping.size}건")
                if (isBackToBack) add("연속 일정 여유 ${gapMin}분")
                if (weather?.needsUmbrella == true) add("우산 필요")
                if (relatedThread != null) add("관련 대화 있음")
            }

            val deepLink = AppDetailPlaybookRegistry.buildDeepLinkForPlace(ev.location)
            val primaryAction = if (ev.location.isNotBlank() && deepLink.isNotBlank()) {
                ProposalAction(
                    key = "open_prep_map",
                    labelKo = "네이버 지도로 경로 보기",
                    kind = ProposalActionKind.OPEN_MAP,
                    payload = mapOf("location" to ev.location, "deepLink" to deepLink)
                )
            } else {
                val alarmZdt = Instant.ofEpochMilli((ev.startTimeMs - 15 * 60_000L).coerceAtLeast(nowMs + 60_000L)).atZone(zoneId)
                ProposalAction(
                    key = "set_prep_alarm",
                    labelKo = "15분 전 준비 알람",
                    kind = ProposalActionKind.SET_ALARM,
                    payload = mapOf(
                        "hour" to alarmZdt.hour.toString(),
                        "minute" to alarmZdt.minute.toString(),
                        "label" to "${ev.title} 준비"
                    )
                )
            }

            val secondary = buildList {
                add(
                    ProposalAction(
                        key = "save_prep_note",
                        labelKo = "브리핑 메모 저장",
                        kind = ProposalActionKind.SAVE_NOTE,
                        payload = mapOf("key" to "브리핑_${ev.title}", "content" to detailLines.joinToString(" / "))
                    )
                )
                add(ProposalAction("start_prep_dnd", "회의 방해금지", ProposalActionKind.START_DND))
                add(ProposalAction("snooze_30m", "30분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "30")))
                add(ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS))
            }

            val fp = computeFingerprint("UPCOMING_PREP_BRIEF", "calendar", ev.id.toString(), ev.startTimeMs.toString())
            val proposal = ProactiveProposal(
                id = nextProposalId(nowMs),
                fingerprint = fp,
                type = ProactiveProposalType.UPCOMING_PREP_BRIEF,
                status = ProactiveProposalStatus.PENDING,
                sourceApp = "캘린더",
                packageName = "com.android.calendar",
                sender = "캘린더",
                roomOrTitle = ev.title,
                titleKo = "다가오는 일정 준비: ${ev.title}",
                summaryKo = summaryParts.joinToString(" · "),
                detailKo = detailLines.joinToString("\n"),
                quotedExcerpt = relatedThread?.messages?.lastOrNull()?.text?.take(120).orEmpty(),
                confidence = if (overlapping.isNotEmpty() || isBackToBack) 0.94 else 0.85,
                hasCalendarConflict = overlapping.isNotEmpty(),
                conflictSummaryKo = conflictSummary,
                startTimeMs = ev.startTimeMs,
                endTimeMs = ev.endTimeMs,
                location = ev.location,
                meetingUrl = KoreanScheduleParser.extractMeetingUrl(ev.location),
                primaryAction = primaryAction,
                secondaryActions = secondary,
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
            results.add(upsertOrEnrichProposal(proposal, nowMs))
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Proposal Lifecycle Mutations
    // -------------------------------------------------------------------------

    fun markApproved(id: String, nowMs: Long = System.currentTimeMillis()): ProactiveProposal? =
        mutateProposalStatus(id, ProactiveProposalStatus.APPROVED, null, nowMs)

    fun markExecuted(id: String, nowMs: Long = System.currentTimeMillis()): ProactiveProposal? =
        mutateProposalStatus(id, ProactiveProposalStatus.EXECUTED, null, nowMs)

    fun dismissProposal(id: String, nowMs: Long = System.currentTimeMillis()): ProactiveProposal? =
        mutateProposalStatus(id, ProactiveProposalStatus.DISMISSED, null, nowMs)

    fun snoozeProposal(
        id: String,
        snoozeMinutes: Long = 30L,
        nowMs: Long = System.currentTimeMillis()
    ): ProactiveProposal? {
        val until = nowMs + snoozeMinutes.coerceIn(1L, 24L * 60L) * 60_000L
        return mutateProposalStatus(id, ProactiveProposalStatus.SNOOZED, until, nowMs)
    }

    fun evaluatePolicyTier(proposal: ProactiveProposal): ApprovalPolicyTier =
        RoomUrgencyCalibrationStore.classifyTier(proposal)

    fun reduceRoomFrequency(
        roomKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): RoomUrgencyProfile = RoomUrgencyCalibrationStore.reduceRoomFrequency(roomKey, nowMs)

    fun alwaysAllowSafeCalendarForRoom(
        roomKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): RoomUrgencyProfile = RoomUrgencyCalibrationStore.alwaysAllowSafeCalendarForRoom(roomKey, nowMs)

    fun resetRoomPolicy(roomKey: String): Boolean =
        RoomUrgencyCalibrationStore.resetRoomPolicy(roomKey)

    /**
     * Batch-approves ONLY proposals whose [ApprovalPolicyTier] is NOT [ApprovalPolicyTier.HARD_MANUAL_GATE].
     * Finance advisory, external replies, and calendar conflicts remain strictly gated for manual review.
     */
    fun batchApproveSafe(
        proposalIds: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ProactiveProposal> {
        val approved = mutableListOf<ProactiveProposal>()
        for (id in proposalIds) {
            val existing = getProposal(id) ?: continue
            if (existing.status != ProactiveProposalStatus.PENDING) continue
            if (evaluatePolicyTier(existing) == ApprovalPolicyTier.HARD_MANUAL_GATE) continue
            markApproved(existing.id, nowMs)?.let { approved.add(it) }
        }
        return approved
    }

    fun batchDismiss(
        proposalIds: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): List<ProactiveProposal> {
        val dismissed = mutableListOf<ProactiveProposal>()
        for (id in proposalIds) {
            dismissProposal(id, nowMs)?.let { dismissed.add(it) }
        }
        return dismissed
    }

    /**
     * Builds the canonical `:18888/secretary/proposals` JSON wire payload.
     */
    fun buildStatusJson(includeAllStatuses: Boolean = false): JSONObject {
        val items = if (includeAllStatuses) listProposals(null) else listPendingProposals()
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("status", "ok")
            put("count", items.size)
            put("pendingCount", listPendingProposals().size)
            put("settings", _settings.value.toJson())
            put("privacyLedger", PrivacyAndQuotaProofLedger.toJson())
            put("proposals", arr)
        }
    }

    // -------------------------------------------------------------------------
    // Internal Detectors & Enrichment Logic
    // -------------------------------------------------------------------------

    private fun detectProposalFromSignal(
        context: Context?,
        notification: InterceptedNotification,
        playbook: AppDetailPlaybook?,
        sourceApp: String,
        roomKey: String,
        sanitizedCurrentText: String,
        combinedText: String,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal? {
        val isFinanceApp = AppDetailPlaybookRegistry.isFinanceReadOnlyPackage(notification.packageName)

        // Subscription & Free-Trial Auto-Defense (strictly readOnlyAdvisory = true; never clicks payment UI)
        val shouldCheckTrialDefense = !isFinanceApp ||
            SubscriptionAndExpiryDefenseEngine.isExplicitTrialOrAddOnTrap(sanitizedCurrentText)
        if (shouldCheckTrialDefense) {
            SubscriptionAndExpiryDefenseEngine.detectTrialOrRenewal(
                notification = notification,
                sanitizedText = sanitizedCurrentText,
                sourceApp = sourceApp,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )?.let { bundle ->
                PrivacyAndQuotaProofLedger.recordFinanceReadOnlyLock(nowMs = referenceTimeMs)
                return bundle.proposal
            }
        }

        // Detector 7 (Hardline FIRST for banking/payment apps): BILLING_SUBSCRIPTION_WATCH
        if (isFinanceApp || FINANCE_ADVISORY_CUE_REGEX.containsMatchIn(sanitizedCurrentText)) {
            PrivacyAndQuotaProofLedger.recordFinanceReadOnlyLock(nowMs = referenceTimeMs)
            return buildFinanceAdvisoryProposal(
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedText = sanitizedCurrentText,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        // Plain-Korean dense gov/medical/insurance/telecom notice translation
        SmishingAndPlainKoreanEngine.translateNotice(sanitizedCurrentText)?.let { translation ->
            return SmishingAndPlainKoreanEngine.buildPlainNoticeProposal(
                notification = notification,
                translation = translation,
                sourceApp = sourceApp,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        // Detector 4: MEETING_LINK_PREP (Zoom/Meet/Teams/Whereby URL)
        val meetingUrl = KoreanScheduleParser.extractMeetingUrl(combinedText)
        if (meetingUrl.isNotBlank()) {
            return buildMeetingLinkPrepProposal(
                context = context,
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedCurrentText = sanitizedCurrentText,
                combinedText = combinedText,
                meetingUrl = meetingUrl,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        // Detector 5: RESERVATION_COMMUTE (CatchTable / Korail / SRT / Hotel / Flight)
        val isReservationApp = playbook?.domainCategory == AppDomainCategory.RESERVATION
        if (isReservationApp || RESERVATION_CUE_REGEX.containsMatchIn(sanitizedCurrentText)) {
            return buildReservationCommuteProposal(
                context = context,
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedCurrentText = sanitizedCurrentText,
                combinedText = combinedText,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        // Detector 6: DELIVERY_TRACKING (Coupang / Baemin / CJ / Hanjin / Lotte / SMS parcel)
        val isDeliveryApp = playbook?.domainCategory == AppDomainCategory.DELIVERY
        if (isDeliveryApp || DELIVERY_CUE_REGEX.containsMatchIn(sanitizedCurrentText)) {
            return buildDeliveryTrackingProposal(
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedText = sanitizedCurrentText,
                referenceTimeMs = referenceTimeMs
            )
        }

        // Detector 2: DEADLINE_TASK (Work chat / Email / Productivity / explicit deadline cues)
        val hasDeadlineCue = DEADLINE_TASK_CUE_REGEX.containsMatchIn(sanitizedCurrentText)
        val isWorkOrEmail = playbook?.domainCategory in setOf(
            AppDomainCategory.WORK_CHAT,
            AppDomainCategory.EMAIL,
            AppDomainCategory.PRODUCTIVITY
        )

        // Parse potential schedule across multi-turn thread
        val parsedSchedule = KoreanScheduleParser.parse(
            text = combinedText,
            referenceTimeMs = referenceTimeMs,
            zoneId = zoneId,
            fallbackTitle = roomKey
        )

        // Detector 1: CALENDAR_EVENT (multi-turn room context aware!)
        val hasMeetupCue = MEETUP_SCHEDULE_CUE_REGEX.containsMatchIn(combinedText)
        if (parsedSchedule != null &&
            !hasDeadlineCue &&
            ((parsedSchedule.hasExplicitDate && parsedSchedule.hasExplicitTime) ||
                ((parsedSchedule.hasExplicitDate || parsedSchedule.hasExplicitTime) && hasMeetupCue))
        ) {
            return buildCalendarEventProposal(
                context = context,
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedCurrentText = sanitizedCurrentText,
                combinedText = combinedText,
                parsed = parsedSchedule,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        if (hasDeadlineCue || (isWorkOrEmail && (sanitizedCurrentText.contains("요청") || sanitizedCurrentText.contains("부탁")))) {
            return buildDeadlineTaskProposal(
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedText = sanitizedCurrentText,
                parsedSchedule = parsedSchedule,
                referenceTimeMs = referenceTimeMs,
                zoneId = zoneId
            )
        }

        // Detector 3: UNANSWERED_FOLLOWUP (human question with RemoteInput quick reply)
        if (notification.canQuickReply &&
            QUESTION_CUE_REGEX.containsMatchIn(sanitizedCurrentText) &&
            (HIGH_URGENCY_QUESTION_REGEX.containsMatchIn(sanitizedCurrentText) ||
                playbook?.domainCategory in setOf(AppDomainCategory.MESSENGER, AppDomainCategory.WORK_CHAT))
        ) {
            return buildUnansweredFollowupProposal(
                notification = notification,
                sourceApp = sourceApp,
                roomKey = roomKey,
                sanitizedText = sanitizedCurrentText,
                elapsedMinutes = 0L,
                nowMs = referenceTimeMs
            )
        }

        return null
    }

    private fun buildCalendarEventProposal(
        context: Context?,
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedCurrentText: String,
        combinedText: String,
        parsed: ParsedKoreanSchedule,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal {
        val conflicts = ProactiveContextApiConnector.findCalendarConflicts(context, parsed.startTimeMs, parsed.endTimeMs)
        val hasConflict = conflicts.isNotEmpty()
        val conflictSummary = ProactiveContextApiConnector.formatConflictSummaryKo(conflicts, zoneId)
        val timeFmt = formatKoreanDateTimeRange(parsed.startTimeMs, parsed.endTimeMs, zoneId)

        val summaryKo = buildString {
            append(timeFmt)
            if (parsed.location.isNotBlank()) append(" · ").append(parsed.location)
            if (hasConflict) append(" · ⚠ 일정 충돌")
        }
        val detailKo = buildString {
            append("$sourceApp '$roomKey' 대화에서 약속 일정을 감지했습니다.\n")
            append("시간: $timeFmt")
            if (parsed.location.isNotBlank()) append("\n장소: ${parsed.location}")
            if (hasConflict) append("\n충돌 알림: $conflictSummary")
        }

        val addCalendarPayload = buildMap {
            put("title", parsed.title)
            put("start_time_ms", parsed.startTimeMs.toString())
            put("end_time_ms", parsed.endTimeMs.toString())
            put("location", parsed.location)
            put("description", "$sourceApp '$roomKey'에서 추출: ${sanitizedCurrentText.take(120)}")
            put("reminder_minutes", "30")
        }

        val primary = ProposalAction(
            key = "add_calendar_event",
            labelKo = if (hasConflict) "충돌 확인 후 일정 등록" else "캘린더에 등록",
            kind = ProposalActionKind.ADD_CALENDAR,
            payload = addCalendarPayload
        )
        val secondary = mutableListOf<ProposalAction>()
        if (parsed.location.isNotBlank()) {
            secondary.add(
                ProposalAction(
                    key = "open_map_location",
                    labelKo = "네이버 지도 보기",
                    kind = ProposalActionKind.OPEN_MAP,
                    payload = mapOf(
                        "location" to parsed.location,
                        "deepLink" to AppDetailPlaybookRegistry.buildDeepLinkForPlace(parsed.location)
                    )
                )
            )
        }
        val replyDrafts = if (notification.canQuickReply) {
            if (hasConflict) {
                listOf(
                    "그 시간에 기존 일정이 있어서 30분 정도 조정 가능할까요?",
                    "확인해 보고 바로 시간 다시 알려드릴게요!"
                )
            } else {
                listOf("좋아요, 그때 봬요!", "캘린더에 등록해 둘게요!")
            }
        } else {
            emptyList()
        }
        if (notification.canQuickReply && replyDrafts.isNotEmpty()) {
            secondary.add(
                ProposalAction(
                    key = "quick_reply_confirm",
                    labelKo = "빠른 답장: ${replyDrafts.first().take(16)}",
                    kind = ProposalActionKind.SEND_QUICK_REPLY,
                    payload = mapOf(
                        "sessionId" to notification.sessionId,
                        "packageName" to notification.packageName,
                        "roomOrTitle" to roomKey,
                        "replyText" to replyDrafts.first()
                    )
                )
            )
        }
        secondary.add(ProposalAction("snooze_30m", "30분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "30")))
        secondary.add(ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS))

        val dateBucket = Instant.ofEpochMilli(parsed.startTimeMs).atZone(zoneId).toLocalDate().toString()
        val fp = computeFingerprint("CALENDAR_EVENT", notification.packageName, roomKey, dateBucket)

        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.CALENDAR_EVENT,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = parsed.title,
            summaryKo = summaryKo,
            detailKo = detailKo,
            quotedExcerpt = combinedText.takeLast(180).trim(),
            confidence = parsed.confidence,
            hasCalendarConflict = hasConflict,
            conflictSummaryKo = conflictSummary,
            startTimeMs = parsed.startTimeMs,
            endTimeMs = parsed.endTimeMs,
            location = parsed.location,
            meetingUrl = parsed.meetingUrl,
            primaryAction = primary,
            secondaryActions = secondary,
            quickReplyDrafts = replyDrafts,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun buildDeadlineTaskProposal(
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedText: String,
        parsedSchedule: ParsedKoreanSchedule?,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal {
        val cleanSnippet = sanitizedText.replace(Regex("\\s+"), " ").take(90)
        val dueLabel = parsedSchedule?.let {
            formatKoreanDateTimeRange(it.startTimeMs, it.endTimeMs, zoneId)
        }.orEmpty()

        val titleKo = "${notification.sender} 요청: ${cleanSnippet.take(28)}"
        val summaryKo = if (dueLabel.isNotBlank()) {
            "$sourceApp 마감·요청 감지 · 기한 $dueLabel"
        } else {
            "$sourceApp 업무·회신 요청 감지 · $roomKey"
        }
        val detailKo = buildString {
            append("$sourceApp '$roomKey' (${notification.sender})에서 처리 필요한 요청을 감지했습니다.\n")
            append("내용: $cleanSnippet")
            if (dueLabel.isNotBlank()) append("\n예상 기한: $dueLabel")
        }

        val primary = if (parsedSchedule != null) {
            ProposalAction(
                key = "add_deadline_calendar",
                labelKo = "마감 일정 등록",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to "[마감] $titleKo",
                    "start_time_ms" to parsedSchedule.startTimeMs.toString(),
                    "end_time_ms" to parsedSchedule.endTimeMs.toString(),
                    "description" to cleanSnippet,
                    "reminder_minutes" to "30"
                )
            )
        } else {
            ProposalAction(
                key = "save_deadline_task",
                labelKo = "할일 메모 저장",
                kind = ProposalActionKind.SAVE_NOTE,
                payload = mapOf(
                    "key" to "할일_${roomKey.take(18)}",
                    "content" to "[$sourceApp/${notification.sender}] $cleanSnippet"
                )
            )
        }

        val replyDrafts = if (notification.canQuickReply) {
            listOf(
                "확인했습니다! 정리해서 기한 내에 공유드릴게요.",
                "네, 바로 검토 후 회신드리겠습니다."
            )
        } else {
            emptyList()
        }

        val secondary = mutableListOf<ProposalAction>()
        if (primary.kind != ProposalActionKind.SAVE_NOTE) {
            secondary.add(
                ProposalAction(
                    key = "save_deadline_note",
                    labelKo = "할일 메모 저장",
                    kind = ProposalActionKind.SAVE_NOTE,
                    payload = mapOf(
                        "key" to "할일_${roomKey.take(18)}",
                        "content" to "[$sourceApp/${notification.sender}] $cleanSnippet"
                    )
                )
            )
        }
        if (notification.canQuickReply && replyDrafts.isNotEmpty()) {
            secondary.add(
                ProposalAction(
                    key = "reply_ack_task",
                    labelKo = "확인 답장 보내기",
                    kind = ProposalActionKind.SEND_QUICK_REPLY,
                    payload = mapOf(
                        "sessionId" to notification.sessionId,
                        "packageName" to notification.packageName,
                        "roomOrTitle" to roomKey,
                        "replyText" to replyDrafts.first()
                    )
                )
            )
        }
        secondary.add(ProposalAction("snooze_30m", "30분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "30")))
        secondary.add(ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS))

        val fp = computeFingerprint("DEADLINE_TASK", notification.packageName, roomKey, cleanSnippet.take(40))
        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.DEADLINE_TASK,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = titleKo,
            summaryKo = summaryKo,
            detailKo = detailKo,
            quotedExcerpt = cleanSnippet,
            confidence = if (parsedSchedule != null) 0.88 else 0.80,
            startTimeMs = parsedSchedule?.startTimeMs,
            endTimeMs = parsedSchedule?.endTimeMs,
            primaryAction = primary,
            secondaryActions = secondary,
            quickReplyDrafts = replyDrafts,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun buildUnansweredFollowupProposal(
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedText: String,
        elapsedMinutes: Long,
        nowMs: Long
    ): ProactiveProposal {
        val cleanSnippet = sanitizedText.replace(Regex("\\s+"), " ").take(100)
        val drafts = generateQuickReplyDrafts(cleanSnippet)

        val primary = ProposalAction(
            key = "quick_reply_0",
            labelKo = "바로 답장: ${drafts.first().take(18)}",
            kind = ProposalActionKind.SEND_QUICK_REPLY,
            payload = mapOf(
                "sessionId" to notification.sessionId,
                "packageName" to notification.packageName,
                "roomOrTitle" to roomKey,
                "sender" to notification.sender,
                "replyText" to drafts.first()
            )
        )
        val secondary = mutableListOf<ProposalAction>()
        drafts.drop(1).forEachIndexed { idx, draft ->
            secondary.add(
                ProposalAction(
                    key = "quick_reply_${idx + 1}",
                    labelKo = draft.take(22),
                    kind = ProposalActionKind.SEND_QUICK_REPLY,
                    payload = mapOf(
                        "sessionId" to notification.sessionId,
                        "packageName" to notification.packageName,
                        "roomOrTitle" to roomKey,
                        "sender" to notification.sender,
                        "replyText" to draft
                    )
                )
            )
        }
        secondary.add(ProposalAction("snooze_15m", "15분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "15")))
        secondary.add(ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS))

        val waitNote = if (elapsedMinutes > 0L) "${elapsedMinutes}분째 미답장" else "빠른 답장 제안"
        val fp = computeFingerprint("UNANSWERED_FOLLOWUP", notification.packageName, roomKey, cleanSnippet.take(36))
        return ProactiveProposal(
            id = nextProposalId(nowMs),
            fingerprint = fp,
            type = ProactiveProposalType.UNANSWERED_FOLLOWUP,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = "${notification.sender}님에게 답장 제안",
            summaryKo = "$sourceApp · $waitNote · 빠른 답장 초안 ${drafts.size}개 준비됨",
            detailKo = "'${notification.sender}'님의 질문에 화면을 켜지 않고 바로 답장할 수 있어요.\n원문: \"$cleanSnippet\"",
            quotedExcerpt = cleanSnippet,
            confidence = if (elapsedMinutes >= _settings.value.unansweredFollowupMinutes ||
                HIGH_URGENCY_QUESTION_REGEX.containsMatchIn(cleanSnippet)
            ) 0.84 else 0.72,
            primaryAction = primary,
            secondaryActions = secondary,
            quickReplyDrafts = drafts,
            createdAtMs = nowMs,
            updatedAtMs = nowMs
        )
    }

    private fun buildMeetingLinkPrepProposal(
        context: Context?,
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedCurrentText: String,
        combinedText: String,
        meetingUrl: String,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal {
        val parsed = KoreanScheduleParser.parse(
            text = combinedText,
            referenceTimeMs = referenceTimeMs,
            zoneId = zoneId,
            fallbackTitle = roomKey
        )
        val startMs = parsed?.startTimeMs ?: (referenceTimeMs + 15 * 60_000L)
        val endMs = parsed?.endTimeMs ?: (startMs + 60 * 60_000L)
        val conflicts = ProactiveContextApiConnector.findCalendarConflicts(context, startMs, endMs)
        val hasConflict = conflicts.isNotEmpty()
        val conflictSummary = ProactiveContextApiConnector.formatConflictSummaryKo(conflicts, zoneId)

        val alarmMs = (startMs - 5 * 60_000L).coerceAtLeast(referenceTimeMs + 60_000L)
        val alarmZdt = Instant.ofEpochMilli(alarmMs).atZone(zoneId)
        val timeLabel = formatKoreanDateTimeRange(startMs, endMs, zoneId)
        val platformLabel = when {
            meetingUrl.contains("meet.google.com", ignoreCase = true) -> "Google Meet"
            meetingUrl.contains("zoom.us", ignoreCase = true) -> "Zoom"
            meetingUrl.contains("teams.", ignoreCase = true) -> "Microsoft Teams"
            meetingUrl.contains("whereby.com", ignoreCase = true) -> "Whereby"
            else -> "화상회의"
        }

        val primary = ProposalAction(
            key = "add_meeting_calendar",
            labelKo = "일정 등록 + 5분 전 알람",
            kind = ProposalActionKind.ADD_CALENDAR,
            payload = mapOf(
                "title" to "$roomKey ($platformLabel)",
                "start_time_ms" to startMs.toString(),
                "end_time_ms" to endMs.toString(),
                "location" to meetingUrl,
                "description" to "화상회의 링크: $meetingUrl",
                "reminder_minutes" to "5"
            )
        )
        val secondary = listOf(
            ProposalAction(
                key = "set_5min_alarm",
                labelKo = "5분 전 알람",
                kind = ProposalActionKind.SET_ALARM,
                payload = mapOf(
                    "hour" to alarmZdt.hour.toString(),
                    "minute" to alarmZdt.minute.toString(),
                    "label" to "$platformLabel 회의 5분 전"
                )
            ),
            ProposalAction(
                key = "start_meeting_dnd",
                labelKo = "방해금지 준비",
                kind = ProposalActionKind.START_DND
            ),
            ProposalAction(
                key = "open_meeting_url",
                labelKo = "$platformLabel 열기",
                kind = ProposalActionKind.OPEN_LINK,
                payload = mapOf("url" to meetingUrl)
            ),
            ProposalAction("snooze_15m", "15분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "15")),
            ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS)
        )

        val fp = computeFingerprint("MEETING_LINK_PREP", notification.packageName, roomKey, meetingUrl)
        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.MEETING_LINK_PREP,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = "$platformLabel 화상회의 준비 · $roomKey",
            summaryKo = "$timeLabel · 5분 전 알람·방해금지 준비 가능" + if (hasConflict) " · ⚠ 일정 충돌" else "",
            detailKo = "${sourceApp}에서 $platformLabel 링크를 감지했습니다.\n시간: $timeLabel\n링크: $meetingUrl" +
                if (hasConflict) "\n충돌 알림: $conflictSummary" else "",
            quotedExcerpt = sanitizedCurrentText.take(160),
            confidence = 0.93,
            hasCalendarConflict = hasConflict,
            conflictSummaryKo = conflictSummary,
            startTimeMs = startMs,
            endTimeMs = endMs,
            location = parsed?.location.orEmpty().ifBlank { platformLabel },
            meetingUrl = meetingUrl,
            primaryAction = primary,
            secondaryActions = secondary,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun buildReservationCommuteProposal(
        context: Context?,
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedCurrentText: String,
        combinedText: String,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal {
        val parsed = KoreanScheduleParser.parse(
            text = combinedText,
            referenceTimeMs = referenceTimeMs,
            zoneId = zoneId,
            fallbackTitle = sourceApp
        )
        val startMs = parsed?.startTimeMs
        val endMs = parsed?.endTimeMs
        val conflicts = if (startMs != null && endMs != null) {
            ProactiveContextApiConnector.findCalendarConflicts(context, startMs, endMs)
        } else {
            emptyList()
        }
        val hasConflict = conflicts.isNotEmpty()
        val conflictSummary = ProactiveContextApiConnector.formatConflictSummaryKo(conflicts, zoneId)

        val place = parsed?.location.orEmpty().ifBlank {
            if (roomKey != sourceApp && roomKey.length >= 2) roomKey else ""
        }
        val deepLink = AppDetailPlaybookRegistry.buildDeepLinkForPlace(place)
        val timeLabel = if (startMs != null && endMs != null) {
            formatKoreanDateTimeRange(startMs, endMs, zoneId)
        } else {
            "시간 확인 필요"
        }

        val titleKo = if (place.isNotBlank()) "$place 예약·이동 준비" else "$sourceApp 예약 확정"
        val primary = if (startMs != null && endMs != null) {
            ProposalAction(
                key = "add_reservation_calendar",
                labelKo = "예약 캘린더 등록",
                kind = ProposalActionKind.ADD_CALENDAR,
                payload = mapOf(
                    "title" to titleKo,
                    "start_time_ms" to startMs.toString(),
                    "end_time_ms" to endMs.toString(),
                    "location" to place,
                    "description" to sanitizedCurrentText.take(150),
                    "reminder_minutes" to "60"
                )
            )
        } else {
            ProposalAction(
                key = "save_reservation_note",
                labelKo = "예약 메모 저장",
                kind = ProposalActionKind.SAVE_NOTE,
                payload = mapOf("key" to "예약_$sourceApp", "content" to sanitizedCurrentText.take(150))
            )
        }

        val secondary = mutableListOf<ProposalAction>()
        if (place.isNotBlank() && deepLink.isNotBlank()) {
            secondary.add(
                ProposalAction(
                    key = "open_naver_map",
                    labelKo = "네이버 지도 길찾기",
                    kind = ProposalActionKind.OPEN_MAP,
                    payload = mapOf("location" to place, "deepLink" to deepLink)
                )
            )
        }
        if (startMs != null) {
            val departMs = (startMs - 60 * 60_000L).coerceAtLeast(referenceTimeMs + 60_000L)
            val departZdt = Instant.ofEpochMilli(departMs).atZone(zoneId)
            secondary.add(
                ProposalAction(
                    key = "set_departure_alarm",
                    labelKo = "출발 1시간 전 알람",
                    kind = ProposalActionKind.SET_ALARM,
                    payload = mapOf(
                        "hour" to departZdt.hour.toString(),
                        "minute" to departZdt.minute.toString(),
                        "label" to "${place.ifBlank { sourceApp }} 출발 준비"
                    )
                )
            )
        }
        secondary.add(ProposalAction("snooze_30m", "30분 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "30")))
        secondary.add(ProposalAction("dismiss", "닫기", ProposalActionKind.DISMISS))

        val fp = computeFingerprint("RESERVATION_COMMUTE", notification.packageName, place.ifBlank { roomKey }, startMs?.toString() ?: "nodate")
        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.RESERVATION_COMMUTE,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = titleKo,
            summaryKo = "$sourceApp · $timeLabel" +
                (if (place.isNotBlank()) " · $place" else "") +
                (if (hasConflict) " · ⚠ 일정 충돌" else ""),
            detailKo = "$sourceApp 예약·교통 알림을 감지했습니다.\n일시: $timeLabel" +
                (if (place.isNotBlank()) "\n장소: $place\n지도 딥링크: $deepLink" else "") +
                (if (hasConflict) "\n충돌 알림: $conflictSummary" else ""),
            quotedExcerpt = sanitizedCurrentText.take(160),
            confidence = 0.91,
            hasCalendarConflict = hasConflict,
            conflictSummaryKo = conflictSummary,
            startTimeMs = startMs,
            endTimeMs = endMs,
            location = place,
            primaryAction = primary,
            secondaryActions = secondary,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun buildDeliveryTrackingProposal(
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedText: String,
        referenceTimeMs: Long
    ): ProactiveProposal {
        // Double-enforce PIN redaction so zero locker/door PIN digits can ever persist
        val safeText = NotificationThreadBuffer.redactDoorAndLockerPins(sanitizedText)
            .replace(Regex("\\s+"), " ")
            .take(140)
        val statusLabel = when {
            safeText.contains("완료") || safeText.contains("도착") || safeText.contains("보관") -> "배송 완료 · 수령 확인"
            safeText.contains("출발") || safeText.contains("시작") -> "배송 출발"
            else -> "배송 현황 안내"
        }
        val titleKo = "$sourceApp $statusLabel"
        val primary = ProposalAction(
            key = "save_delivery_note",
            labelKo = "수령 메모 저장",
            kind = ProposalActionKind.SAVE_NOTE,
            payload = mapOf(
                "key" to "배송_$sourceApp",
                "content" to "$statusLabel: $safeText"
            )
        )
        val secondary = listOf(
            ProposalAction("snooze_30m", "30분 뒤 수령 리마인더", ProposalActionKind.SNOOZE, mapOf("minutes" to "30")),
            ProposalAction("dismiss", "확인 완료", ProposalActionKind.DISMISS)
        )
        val fp = computeFingerprint("DELIVERY_TRACKING", notification.packageName, statusLabel, safeText.take(36))
        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.DELIVERY_TRACKING,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = titleKo,
            summaryKo = "$sourceApp · $statusLabel (현관/보관함 PIN 자동 비공개 처리)",
            detailKo = "배송 알림을 수령 리마인더로 정리했습니다. 보안상 공동현관·무인택배함 비밀번호는 저장하지 않습니다.\n내용: $safeText",
            quotedExcerpt = safeText,
            confidence = 0.86,
            primaryAction = primary,
            secondaryActions = secondary,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun buildFinanceAdvisoryProposal(
        notification: InterceptedNotification,
        sourceApp: String,
        roomKey: String,
        sanitizedText: String,
        referenceTimeMs: Long,
        zoneId: ZoneId
    ): ProactiveProposal {
        // STRICT READ-ONLY ADVISORY: NEVER emit RUN_AGENT_GOAL or banking UI automation.
        val cleanSnippet = sanitizedText.replace(Regex("\\s+"), " ").take(120)
        val parsedDate = KoreanScheduleParser.parse(
            text = cleanSnippet,
            referenceTimeMs = referenceTimeMs,
            zoneId = zoneId,
            fallbackTitle = "$sourceApp 청구·결제"
        )
        val titleKo = "[읽기전용] $sourceApp 금융·청구 알림"
        val primary = ProposalAction(
            key = "save_finance_advisory_note",
            labelKo = "청구·결제 메모 저장",
            kind = ProposalActionKind.SAVE_NOTE,
            payload = mapOf(
                "key" to "금융메모_$sourceApp",
                "content" to "[$sourceApp 읽기전용 알림] $cleanSnippet"
            )
        )
        val secondary = mutableListOf<ProposalAction>()
        if (parsedDate != null) {
            secondary.add(
                ProposalAction(
                    key = "add_finance_reminder_calendar",
                    labelKo = "납부일 리마인더 등록",
                    kind = ProposalActionKind.ADD_CALENDAR,
                    payload = mapOf(
                        "title" to "[리마인더] $sourceApp 납부·갱신 확인",
                        "start_time_ms" to parsedDate.startTimeMs.toString(),
                        "end_time_ms" to parsedDate.endTimeMs.toString(),
                        "description" to "금융 앱 자동 실행 없이 사용자 직접 확인용 리마인더입니다.",
                        "reminder_minutes" to "60"
                    )
                )
            )
        }
        secondary.add(ProposalAction("snooze_60m", "1시간 뒤", ProposalActionKind.SNOOZE, mapOf("minutes" to "60")))
        secondary.add(ProposalAction("dismiss", "확인 완료", ProposalActionKind.DISMISS))

        val fp = computeFingerprint("BILLING_SUBSCRIPTION_WATCH", notification.packageName, roomKey, cleanSnippet.take(40))
        return ProactiveProposal(
            id = nextProposalId(referenceTimeMs),
            fingerprint = fp,
            type = ProactiveProposalType.BILLING_SUBSCRIPTION_WATCH,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = sourceApp,
            packageName = notification.packageName,
            sender = notification.sender,
            roomOrTitle = roomKey,
            titleKo = titleKo,
            summaryKo = "$sourceApp 청구·구독·결제 안내 (읽기 전용 · 금융 앱 자동 조작 안 함)",
            detailKo = "금융 보안 원칙에 따라 은행·카드·결제 앱을 자동으로 조작하지 않고 메모·리마인더만 제안합니다.\n요약: $cleanSnippet",
            quotedExcerpt = cleanSnippet,
            confidence = 0.87,
            startTimeMs = parsedDate?.startTimeMs,
            endTimeMs = parsedDate?.endTimeMs,
            primaryAction = primary,
            secondaryActions = secondary,
            readOnlyAdvisory = true,
            createdAtMs = referenceTimeMs,
            updatedAtMs = referenceTimeMs
        )
    }

    private fun generateQuickReplyDrafts(text: String): List<String> {
        return when {
            text.contains("어디") || text.contains("도착") || text.contains("출발") -> listOf(
                "지금 이동 중이에요! 도착하면 바로 연락드릴게요.",
                "거의 다 왔어요, 5~10분 정도 걸릴 것 같아요.",
                "확인했습니다! 곧 출발할게요."
            )
            text.contains("시간") || text.contains("가능") || text.contains("괜찮") || text.contains("어때") -> listOf(
                "네, 그 시간 괜찮습니다!",
                "일정 확인해 보고 바로 답변드릴게요.",
                "조금만 조정할 수 있을까요? 확인 후 연락드릴게요."
            )
            else -> listOf(
                "확인했습니다! 잠시 후 자세히 답장드릴게요.",
                "네, 알겠습니다!",
                "지금 확인 중이에요. 정리해서 곧 회신드릴게요."
            )
        }
    }

    private fun upsertOrEnrichProposal(
        incoming: ProactiveProposal,
        nowMs: Long
    ): ProactiveProposal {
        synchronized(lock) {
            // 1. Exact deterministic fingerprint match -> update in place
            val byFingerprintIdx = proposalList.indexOfFirst { it.fingerprint == incoming.fingerprint }
            if (byFingerprintIdx >= 0) {
                val existing = proposalList[byFingerprintIdx]
                val merged = mergeExistingProposal(existing, incoming, nowMs)
                proposalList.removeAt(byFingerprintIdx)
                proposalList.add(0, merged)
                trimAndPersistLocked()
                return merged
            }

            // 2. Multi-turn live room enrichment for active PENDING CALENDAR_EVENT / MEETING_LINK_PREP
            //    in the exact same (packageName, roomOrTitle) within 30 minutes
            if (incoming.type == ProactiveProposalType.CALENDAR_EVENT ||
                incoming.type == ProactiveProposalType.MEETING_LINK_PREP
            ) {
                val liveRoomIdx = proposalList.indexOfFirst { existing ->
                    existing.status == ProactiveProposalStatus.PENDING &&
                        existing.type == incoming.type &&
                        existing.packageName.equals(incoming.packageName, ignoreCase = true) &&
                        existing.roomOrTitle.equals(incoming.roomOrTitle, ignoreCase = true) &&
                        (nowMs - existing.updatedAtMs) in 0L..LIVE_MERGE_WINDOW_MS
                }
                if (liveRoomIdx >= 0) {
                    val existing = proposalList[liveRoomIdx]
                    val merged = mergeExistingProposal(existing, incoming, nowMs)
                    proposalList.removeAt(liveRoomIdx)
                    proposalList.add(0, merged)
                    trimAndPersistLocked()
                    return merged
                }
            }

            proposalList.add(0, incoming)
            trimAndPersistLocked()
            return incoming
        }
    }

    private fun mergeExistingProposal(
        existing: ProactiveProposal,
        incoming: ProactiveProposal,
        nowMs: Long
    ): ProactiveProposal {
        return incoming.copy(
            id = existing.id,
            status = if (existing.status == ProactiveProposalStatus.PENDING) {
                ProactiveProposalStatus.PENDING
            } else {
                existing.status
            },
            location = incoming.location.ifBlank { existing.location },
            meetingUrl = incoming.meetingUrl.ifBlank { existing.meetingUrl },
            startTimeMs = incoming.startTimeMs ?: existing.startTimeMs,
            endTimeMs = incoming.endTimeMs ?: existing.endTimeMs,
            confidence = maxOf(existing.confidence, incoming.confidence),
            createdAtMs = existing.createdAtMs,
            updatedAtMs = nowMs
        )
    }

    private fun mutateProposalStatus(
        id: String,
        newStatus: ProactiveProposalStatus,
        snoozedUntilMs: Long?,
        nowMs: Long
    ): ProactiveProposal? {
        val target = id.trim()
        if (target.isEmpty()) return null
        synchronized(lock) {
            val idx = proposalList.indexOfFirst { it.id == target }
            if (idx < 0) return null
            val updated = proposalList[idx].copy(
                status = newStatus,
                snoozedUntilMs = snoozedUntilMs,
                updatedAtMs = nowMs
            )
            proposalList[idx] = updated
            trimAndPersistLocked()
            return updated
        }
    }

    private fun containsPromptInjection(text: String): Boolean {
        if (text.isBlank()) return false
        if (UntrustedScreenDataGuard.detectPromptInjectionSignals(text).isNotEmpty()) return true
        return EXTRA_INJECTION_REGEX.containsMatchIn(text)
    }

    private fun sanitizeOrRejectText(rawText: String, packageName: String): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        // Hard secrets (OTP, 16-digit card, RRN, API keys, Bearer tokens, login passwords) MUST be rejected.
        if (NotificationThreadBuffer.containsHardSecret(trimmed)) return null

        // For delivery context, redact door/locker PINs first, then re-verify against ConvenienceSecretGuard.
        val isDelivery = AppDetailPlaybookRegistry.findByPackage(packageName)?.domainCategory == AppDomainCategory.DELIVERY ||
            DELIVERY_CUE_REGEX.containsMatchIn(trimmed)
        if (isDelivery) {
            val redacted = NotificationThreadBuffer.redactDoorAndLockerPins(trimmed)
            if (NotificationThreadBuffer.containsHardSecret(redacted)) return null
            return redacted
        }

        // For all non-delivery messages, enforce the full ConvenienceSecretGuard check.
        if (ConvenienceSecretGuard.containsSensitiveSecret(trimmed)) return null
        return trimmed
    }

    private fun buildSanitizedThreadLines(
        notification: InterceptedNotification,
        sanitizedCurrentText: String,
        threadContext: NotificationThreadContext?
    ): List<String> {
        val lines = mutableListOf<String>()
        if (threadContext != null && threadContext.messages.isNotEmpty()) {
            for (m in threadContext.messages) {
                val safe = sanitizeOrRejectText(m.text, notification.packageName) ?: continue
                if (!containsPromptInjection(safe)) {
                    lines.add(safe)
                }
            }
        }
        if (lines.lastOrNull() != sanitizedCurrentText) {
            lines.add(sanitizedCurrentText)
        }
        return lines
    }

    private fun formatKoreanDateTimeRange(
        startMs: Long,
        endMs: Long,
        zoneId: ZoneId
    ): String {
        val sZdt = Instant.ofEpochMilli(startMs).atZone(zoneId)
        val eZdt = Instant.ofEpochMilli(endMs).atZone(zoneId)
        val dowKo = when (sZdt.dayOfWeek) {
            DayOfWeek.MONDAY -> "월"
            DayOfWeek.TUESDAY -> "화"
            DayOfWeek.WEDNESDAY -> "수"
            DayOfWeek.THURSDAY -> "목"
            DayOfWeek.FRIDAY -> "금"
            DayOfWeek.SATURDAY -> "토"
            DayOfWeek.SUNDAY -> "일"
        }
        val dateStr = String.format(Locale.US, "%d/%d(%s)", sZdt.monthValue, sZdt.dayOfMonth, dowKo)
        val sTime = String.format(Locale.US, "%02d:%02d", sZdt.hour, sZdt.minute)
        val eTime = String.format(Locale.US, "%02d:%02d", eZdt.hour, eZdt.minute)
        return "$dateStr $sTime~$eTime"
    }

    private fun computeFingerprint(vararg parts: String): String {
        val raw = parts.joinToString("|") { it.trim().lowercase(Locale.ROOT) }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun nextProposalId(nowMs: Long): String = "sec_${nowMs}_${idCounter.incrementAndGet()}"

    // -------------------------------------------------------------------------
    // Atomic JSON Persistence
    // -------------------------------------------------------------------------

    private fun trimAndPersistLocked() {
        while (proposalList.size > MAX_STORED_PROPOSALS) {
            proposalList.removeAt(proposalList.lastIndex)
        }
        _proposals.value = proposalList.toList()
        saveProposalsLocked()
    }

    private fun loadFromDiskLocked() {
        val dir = storageDir ?: return

        // Load settings
        val prefsFile = File(dir, PREFS_FILE_NAME)
        if (prefsFile.isFile) {
            runCatching {
                val obj = JSONObject(prefsFile.readText(StandardCharsets.UTF_8))
                _settings.value = SecretarySettings.fromJson(obj)
            }
        }

        // Load proposals
        proposalList.clear()
        val propFile = File(dir, PROPOSALS_FILE_NAME)
        if (propFile.isFile) {
            runCatching {
                val root = JSONObject(propFile.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("proposals") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = ProactiveProposal.fromJson(arr.optJSONObject(i)) ?: continue
                    if (!containsPromptInjection(item.detailKo) &&
                        !NotificationThreadBuffer.containsHardSecret(item.quotedExcerpt)
                    ) {
                        proposalList.add(item)
                    }
                }
            }
        }
        _proposals.value = proposalList.toList()
    }

    private fun saveProposalsLocked() {
        val dir = storageDir ?: return
        runCatching {
            val arr = JSONArray()
            proposalList.forEach { arr.put(it.toJson()) }
            val root = JSONObject()
                .put("schemaVersion", 1)
                .put("proposals", arr)
            ConvenienceStorageHelper.writeAtomically(File(dir, PROPOSALS_FILE_NAME), root.toString(2))
        }
    }

    private fun saveSettingsLocked() {
        val dir = storageDir ?: return
        runCatching {
            ConvenienceStorageHelper.writeAtomically(
                File(dir, PREFS_FILE_NAME),
                _settings.value.toJson().toString(2)
            )
        }
    }
}
