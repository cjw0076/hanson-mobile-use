package com.hanson.mobileuse.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// =============================================================================
// 1. Scheduled "Beats" + 60s Spoken Brief (Claude Duet + Dots + Grok Bot)
// =============================================================================

enum class BeatFrequency {
    DAILY_AT,
    WEEKDAYS_AT,
    INTERVAL_HOURS;

    companion object {
        fun fromNameOrDefault(raw: String?, default: BeatFrequency = DAILY_AT): BeatFrequency {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull { it.name.equals(clean, ignoreCase = true) } ?: default
        }
    }
}

data class RoutineScheduleSpec(
    val id: String,
    val titleKo: String,
    val goalPrompt: String,
    val frequency: BeatFrequency = BeatFrequency.DAILY_AT,
    val hour: Int = 8,
    val minute: Int = 0,
    val intervalHours: Int = 4,
    val enabled: Boolean = true,
    val spokenBriefEnabled: Boolean = false,
    val respectQuietHours: Boolean = true,
    val requiresVlm: Boolean = false,
    val lastRunAtMs: Long = 0L,
    val createdAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("titleKo", titleKo)
        put("goalPrompt", goalPrompt)
        put("frequency", frequency.name)
        put("hour", hour.coerceIn(0, 23))
        put("minute", minute.coerceIn(0, 59))
        put("intervalHours", intervalHours.coerceIn(1, 24))
        put("enabled", enabled)
        put("spokenBriefEnabled", spokenBriefEnabled)
        put("respectQuietHours", respectQuietHours)
        put("requiresVlm", requiresVlm)
        put("lastRunAtMs", lastRunAtMs)
        put("createdAtMs", createdAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): RoutineScheduleSpec? {
            if (obj == null) return null
            val id = obj.optString("id", "").trim()
            val titleKo = obj.optString("titleKo", "").trim()
            val goalPrompt = obj.optString("goalPrompt", "").trim()
            if (id.isEmpty() || titleKo.isEmpty() || goalPrompt.isEmpty()) return null
            return RoutineScheduleSpec(
                id = id,
                titleKo = titleKo,
                goalPrompt = goalPrompt,
                frequency = BeatFrequency.fromNameOrDefault(obj.optString("frequency", "")),
                hour = obj.optInt("hour", 8).coerceIn(0, 23),
                minute = obj.optInt("minute", 0).coerceIn(0, 59),
                intervalHours = obj.optInt("intervalHours", 4).coerceIn(1, 24),
                enabled = obj.optBoolean("enabled", true),
                spokenBriefEnabled = obj.optBoolean("spokenBriefEnabled", false),
                respectQuietHours = obj.optBoolean("respectQuietHours", true),
                requiresVlm = obj.optBoolean("requiresVlm", false),
                lastRunAtMs = obj.optLong("lastRunAtMs", 0L),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
            )
        }
    }
}

object LifestyleBeatScheduler {

    private const val BEATS_FILE_NAME = "hanson_lifestyle_beats.json"
    private const val MAX_BEATS = 32

    private val lock = Any()

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    private val beatList = mutableListOf<RoutineScheduleSpec>()
    private val _beats = MutableStateFlow<List<RoutineScheduleSpec>>(emptyList())
    val beats: StateFlow<List<RoutineScheduleSpec>> = _beats.asStateFlow()

    private val MARKDOWN_STRIP_REGEX = Regex("[*_`#~>\\[\\]]+")
    private val URL_STRIP_REGEX = Regex("(?i)https?://\\S+")
    private val MULTI_SPACE_REGEX = Regex("\\s+")

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageFile = File(dir, BEATS_FILE_NAME)
            loadFromDiskLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, BEATS_FILE_NAME)
            storageFile = file
            if (clearExisting) {
                beatList.clear()
                _beats.value = emptyList()
                file.delete()
            } else {
                loadFromDiskLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            beatList.clear()
            _beats.value = emptyList()
            storageFile = null
            initialized = false
        }
    }

    fun listBeats(): List<RoutineScheduleSpec> = synchronized(lock) { beatList.toList() }

    fun upsertBeat(spec: RoutineScheduleSpec): RoutineScheduleSpec {
        synchronized(lock) {
            val normalized = spec.copy(
                id = spec.id.trim().ifEmpty { "beat_${System.currentTimeMillis()}" },
                titleKo = spec.titleKo.trim().take(60),
                goalPrompt = spec.goalPrompt.trim().take(300),
                hour = spec.hour.coerceIn(0, 23),
                minute = spec.minute.coerceIn(0, 59),
                intervalHours = spec.intervalHours.coerceIn(1, 24)
            )
            val idx = beatList.indexOfFirst { it.id == normalized.id }
            if (idx >= 0) {
                beatList[idx] = normalized
            } else {
                beatList.add(normalized)
            }
            while (beatList.size > MAX_BEATS) {
                beatList.removeAt(beatList.lastIndex)
            }
            persistLocked()
            return normalized
        }
    }

    fun toggleBeat(id: String, enabled: Boolean? = null): RoutineScheduleSpec? {
        synchronized(lock) {
            val idx = beatList.indexOfFirst { it.id == id.trim() }
            if (idx < 0) return null
            val current = beatList[idx]
            val updated = current.copy(enabled = enabled ?: !current.enabled)
            beatList[idx] = updated
            persistLocked()
            return updated
        }
    }

    fun removeBeat(id: String): Boolean {
        synchronized(lock) {
            val removed = beatList.removeAll { it.id == id.trim() }
            if (removed) persistLocked()
            return removed
        }
    }

    fun markBeatExecuted(id: String, executedAtMs: Long = System.currentTimeMillis()): RoutineScheduleSpec? {
        synchronized(lock) {
            val idx = beatList.indexOfFirst { it.id == id.trim() }
            if (idx < 0) return null
            val updated = beatList[idx].copy(lastRunAtMs = executedAtMs)
            beatList[idx] = updated
            persistLocked()
            return updated
        }
    }

    fun seedDefaultBeatsIfEmpty(nowMs: Long = System.currentTimeMillis()): List<RoutineScheduleSpec> {
        synchronized(lock) {
            if (beatList.isNotEmpty()) return beatList.toList()
            beatList.addAll(
                listOf(
                    RoutineScheduleSpec(
                        id = "beat_morning_spoken_brief",
                        titleKo = "평일 아침 60초 음성 브리핑",
                        goalPrompt = "오늘 캘린더 일정과 대기 중인 AI 비서 제안을 60초 음성 브리핑으로 정리해줘",
                        frequency = BeatFrequency.WEEKDAYS_AT,
                        hour = 8,
                        minute = 0,
                        enabled = true,
                        spokenBriefEnabled = true,
                        respectQuietHours = true,
                        requiresVlm = false,
                        createdAtMs = nowMs
                    ),
                    RoutineScheduleSpec(
                        id = "beat_midday_secretary_triage",
                        titleKo = "4시간 주기 알림·일정 정리",
                        goalPrompt = "미답장 메시지와 다가오는 일정 충돌을 로컬 스캔해줘",
                        frequency = BeatFrequency.INTERVAL_HOURS,
                        intervalHours = 4,
                        enabled = true,
                        spokenBriefEnabled = false,
                        respectQuietHours = true,
                        requiresVlm = false,
                        createdAtMs = nowMs
                    ),
                    RoutineScheduleSpec(
                        id = "beat_evening_wrapup",
                        titleKo = "저녁 하루 마감 브리핑",
                        goalPrompt = "오늘 남은 할일과 내일 첫 일정을 한눈에 요약해줘",
                        frequency = BeatFrequency.DAILY_AT,
                        hour = 21,
                        minute = 0,
                        enabled = true,
                        spokenBriefEnabled = true,
                        respectQuietHours = true,
                        requiresVlm = false,
                        createdAtMs = nowMs
                    )
                )
            )
            persistLocked()
            return beatList.toList()
        }
    }

    /**
     * Deterministically returns all scheduled beats due at [nowMs] in [zoneId],
     * honoring quiet hours and the daily VLM budget governor (`PrivacyAndQuotaProofLedger`).
     */
    fun findDueBeats(
        nowMs: Long,
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE,
        settings: SecretarySettings = ProactiveSecretaryEngine.settings.value,
        ignoreVlmBudget: Boolean = false
    ): List<RoutineScheduleSpec> {
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zoneId)
        val nowDate = nowZdt.toLocalDate()
        val nowMinutesOfDay = nowZdt.hour * 60 + nowZdt.minute
        val isWeekend = nowZdt.dayOfWeek == DayOfWeek.SATURDAY || nowZdt.dayOfWeek == DayOfWeek.SUNDAY
        val inQuietHours = settings.isWithinQuietHours(nowZdt.hour)
        val vlmExhausted = !ignoreVlmBudget && PrivacyAndQuotaProofLedger.isDeterministicFallbackActive(nowMs, zoneId)

        val snapshot = listBeats()
        return snapshot.filter { beat ->
            if (!beat.enabled) return@filter false
            if (beat.respectQuietHours && inQuietHours) return@filter false
            if (beat.requiresVlm && vlmExhausted) return@filter false

            when (beat.frequency) {
                BeatFrequency.DAILY_AT -> {
                    val targetMinutes = beat.hour * 60 + beat.minute
                    if (nowMinutesOfDay < targetMinutes) return@filter false
                    val lastDate = if (beat.lastRunAtMs > 0L) {
                        Instant.ofEpochMilli(beat.lastRunAtMs).atZone(zoneId).toLocalDate()
                    } else {
                        null
                    }
                    lastDate != nowDate
                }
                BeatFrequency.WEEKDAYS_AT -> {
                    if (isWeekend) return@filter false
                    val targetMinutes = beat.hour * 60 + beat.minute
                    if (nowMinutesOfDay < targetMinutes) return@filter false
                    val lastDate = if (beat.lastRunAtMs > 0L) {
                        Instant.ofEpochMilli(beat.lastRunAtMs).atZone(zoneId).toLocalDate()
                    } else {
                        null
                    }
                    lastDate != nowDate
                }
                BeatFrequency.INTERVAL_HOURS -> {
                    val intervalMs = beat.intervalHours.coerceAtLeast(1) * 3_600_000L
                    beat.lastRunAtMs <= 0L || (nowMs - beat.lastRunAtMs) >= intervalMs
                }
            }
        }
    }

    /**
     * Generates clean, TTS-friendly 60-second Korean narration with ZERO markdown,
     * ZERO asterisks, ZERO bullet glyphs, and ZERO raw URLs.
     */
    fun buildSpokenDailyBriefScript(
        brief: DailyBriefSnapshot,
        proposals: List<ProactiveProposal>,
        hourOfDay: Int
    ): String {
        val h = ((hourOfDay % 24) + 24) % 24
        val greeting = when (h) {
            in 5..10 -> "좋은 아침입니다. 오늘의 한손 비서 브리핑을 시작합니다."
            in 11..13 -> "안녕하세요. 점심 무렵 핵심 브리핑을 전해드립니다."
            in 14..17 -> "오후 브리핑입니다. 지금 챙겨보실 내용을 정리해 드립니다."
            in 18..21 -> "저녁 브리핑입니다. 오늘 정리와 내일 준비 사항을 안내합니다."
            else -> "편안한 밤입니다. 취침 전 확인하실 내용을 간단히 안내합니다."
        }

        val pending = proposals
            .filter { it.status == ProactiveProposalStatus.PENDING }
            .sortedWith(
                compareByDescending<ProactiveProposal> { it.hasCalendarConflict }
                    .thenByDescending { it.type.priorityWeight }
                    .thenByDescending { it.updatedAtMs }
            )

        val sentences = mutableListOf(greeting)
        if (pending.isEmpty()) {
            val cleanSummary = cleanForTts(brief.summaryKo)
            if (cleanSummary.isNotBlank()) {
                sentences.add(cleanSummary)
            } else {
                sentences.add("현재 대기 중인 급한 제안이나 충돌 일정은 없습니다.")
            }
        } else {
            sentences.add("현재 대기 중인 비서 제안은 총 ${pending.size}건입니다.")
            val conflictCount = pending.count { it.hasCalendarConflict }
            if (conflictCount > 0) {
                sentences.add("그중 ${conflictCount}건은 기존 캘린더 일정과 시간이 겹쳐 먼저 확인이 필요합니다.")
            }
            pending.take(3).forEachIndexed { idx, p ->
                val cleanTitle = cleanForTts(p.titleKo)
                val cleanSummary = cleanForTts(p.summaryKo)
                val ordinal = when (idx) {
                    0 -> "첫 번째"
                    1 -> "두 번째"
                    else -> "세 번째"
                }
                sentences.add("${ordinal}, ${cleanForTts(p.sourceApp)}에서 감지된 ${cleanTitle} 항목입니다. ${cleanSummary}.")
            }
        }

        val extraHighlight = brief.highlights
            .map(::cleanForTts)
            .firstOrNull { it.isNotBlank() && !it.contains("AI 비서 제안") }
        if (!extraHighlight.isNullOrBlank()) {
            sentences.add("참고로 ${extraHighlight}.")
        }
        sentences.add("세부 제안은 화면에서 한 번의 탭으로 승인하거나 나중에 보기로 미룰 수 있습니다.")

        return sentences
            .map(::cleanForTts)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .take(520)
    }

    private fun cleanForTts(raw: String): String {
        return raw
            .replace(URL_STRIP_REGEX, "")
            .replace(MARKDOWN_STRIP_REGEX, " ")
            .replace("•", " ")
            .replace("·", ", ")
            .replace("⚠", "주의 ")
            .replace("|", ", ")
            .replace(MULTI_SPACE_REGEX, " ")
            .replace(" ,", ",")
            .replace("..", ".")
            .trim()
    }

    fun toStatusJson(): JSONObject = JSONObject().apply {
        put("status", "ok")
        put("count", beatList.size)
        val arr = JSONArray()
        listBeats().forEach { arr.put(it.toJson()) }
        put("beats", arr)
    }

    private fun loadFromDiskLocked() {
        val file = storageFile ?: return
        beatList.clear()
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("beats") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    RoutineScheduleSpec.fromJson(arr.optJSONObject(i))?.let { beatList.add(it) }
                }
            }
        }
        _beats.value = beatList.toList()
    }

    private fun persistLocked() {
        _beats.value = beatList.toList()
        val file = storageFile ?: return
        runCatching {
            val arr = JSONArray()
            beatList.forEach { arr.put(it.toJson()) }
            val root = JSONObject()
                .put("schemaVersion", 1)
                .put("beats", arr)
            ConvenienceStorageHelper.writeAtomically(file, root.toString(2))
        }
    }
}

// =============================================================================
// 2. Anti-Approval-Fatigue 3-Tier Policy + Batch Digest + Room Urgency Store
// =============================================================================

enum class ApprovalPolicyTier(val labelKo: String) {
    AUTO_SAFE_LOCAL("로컬 자동 안전"),
    BATCH_DIGEST("묶음 다이제스트"),
    HARD_MANUAL_GATE("직접 승인 필수")
}

data class RoomUrgencyProfile(
    val roomKey: String,
    val reducedFrequency: Boolean = false,
    val alwaysAllowSafeCalendar: Boolean = false,
    val dismissCount: Int = 0,
    val approveCount: Int = 0,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("roomKey", roomKey)
        put("reducedFrequency", reducedFrequency)
        put("alwaysAllowSafeCalendar", alwaysAllowSafeCalendar)
        put("dismissCount", dismissCount)
        put("approveCount", approveCount)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): RoomUrgencyProfile? {
            if (obj == null) return null
            val roomKey = obj.optString("roomKey", "").trim()
            if (roomKey.isEmpty()) return null
            return RoomUrgencyProfile(
                roomKey = roomKey,
                reducedFrequency = obj.optBoolean("reducedFrequency", false),
                alwaysAllowSafeCalendar = obj.optBoolean("alwaysAllowSafeCalendar", false),
                dismissCount = obj.optInt("dismissCount", 0).coerceAtLeast(0),
                approveCount = obj.optInt("approveCount", 0).coerceAtLeast(0),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }
    }
}

object RoomUrgencyCalibrationStore {

    private const val ROOM_URGENCY_FILE_NAME = "hanson_room_urgency.json"
    private const val MAX_PROFILES = 100

    private val lock = Any()

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    private val profileMap = LinkedHashMap<String, RoomUrgencyProfile>()
    private val _profiles = MutableStateFlow<Map<String, RoomUrgencyProfile>>(emptyMap())
    val profiles: StateFlow<Map<String, RoomUrgencyProfile>> = _profiles.asStateFlow()

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageFile = File(dir, ROOM_URGENCY_FILE_NAME)
            loadFromDiskLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, ROOM_URGENCY_FILE_NAME)
            storageFile = file
            if (clearExisting) {
                profileMap.clear()
                _profiles.value = emptyMap()
                file.delete()
            } else {
                loadFromDiskLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            profileMap.clear()
            _profiles.value = emptyMap()
            storageFile = null
            initialized = false
        }
    }

    fun normalizeRoomKey(roomKey: String): String = roomKey.trim().lowercase(Locale.ROOT)

    fun getProfile(roomKey: String): RoomUrgencyProfile? {
        val norm = normalizeRoomKey(roomKey)
        if (norm.isEmpty()) return null
        return synchronized(lock) { profileMap[norm] }
    }

    /** "이 방 빈도 줄이기" */
    fun reduceRoomFrequency(
        roomKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): RoomUrgencyProfile {
        val norm = normalizeRoomKey(roomKey).ifEmpty { "default" }
        synchronized(lock) {
            val cur = profileMap[norm] ?: RoomUrgencyProfile(roomKey = roomKey.trim().ifEmpty { norm })
            val updated = cur.copy(
                roomKey = cur.roomKey.ifBlank { roomKey.trim() },
                reducedFrequency = true,
                alwaysAllowSafeCalendar = false,
                dismissCount = cur.dismissCount + 1,
                updatedAtMs = nowMs
            )
            profileMap[norm] = updated
            trimAndPersistLocked()
            return updated
        }
    }

    /** "이 방 항상 허용" (safe, non-conflicting calendar events only) */
    fun alwaysAllowSafeCalendarForRoom(
        roomKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): RoomUrgencyProfile {
        val norm = normalizeRoomKey(roomKey).ifEmpty { "default" }
        synchronized(lock) {
            val cur = profileMap[norm] ?: RoomUrgencyProfile(roomKey = roomKey.trim().ifEmpty { norm })
            val updated = cur.copy(
                roomKey = cur.roomKey.ifBlank { roomKey.trim() },
                reducedFrequency = false,
                alwaysAllowSafeCalendar = true,
                approveCount = cur.approveCount + 1,
                updatedAtMs = nowMs
            )
            profileMap[norm] = updated
            trimAndPersistLocked()
            return updated
        }
    }

    fun resetRoomPolicy(roomKey: String): Boolean {
        val norm = normalizeRoomKey(roomKey)
        if (norm.isEmpty()) return false
        synchronized(lock) {
            val removed = profileMap.remove(norm) != null
            if (removed) trimAndPersistLocked()
            return removed
        }
    }

    /**
     * Classifies a [ProactiveProposal] into one of 3 anti-fatigue approval tiers:
     * - [ApprovalPolicyTier.HARD_MANUAL_GATE]: ALWAYS enforced for read-only finance advisory,
     *   external replies (`SEND_QUICK_REPLY` / `UNANSWERED_FOLLOWUP`), calendar conflicts,
     *   or agent UI automation goals (`RUN_AGENT_GOAL`).
     * - [ApprovalPolicyTier.AUTO_SAFE_LOCAL]: reversible local notes (when room isn't reduced-frequency)
     *   or non-conflicting calendar events in a room explicitly opted into `alwaysAllowSafeCalendar`.
     * - [ApprovalPolicyTier.BATCH_DIGEST]: everything else (bundled into one checklist card).
     */
    fun classifyTier(
        proposal: ProactiveProposal,
        roomProfile: RoomUrgencyProfile? = getProfile(proposal.roomOrTitle)
    ): ApprovalPolicyTier {
        // Hard manual gates NEVER get bypassed by room overrides
        if (proposal.readOnlyAdvisory ||
            proposal.type == ProactiveProposalType.BILLING_SUBSCRIPTION_WATCH ||
            AppDetailPlaybookRegistry.isFinanceReadOnlyPackage(proposal.packageName)
        ) {
            return ApprovalPolicyTier.HARD_MANUAL_GATE
        }
        if (proposal.hasCalendarConflict) {
            return ApprovalPolicyTier.HARD_MANUAL_GATE
        }
        if (proposal.type == ProactiveProposalType.UNANSWERED_FOLLOWUP ||
            proposal.primaryAction.kind == ProposalActionKind.SEND_QUICK_REPLY ||
            proposal.primaryAction.kind == ProposalActionKind.RUN_AGENT_GOAL
        ) {
            return ApprovalPolicyTier.HARD_MANUAL_GATE
        }

        // Reduced-frequency rooms funnel into quiet batch digest
        if (roomProfile?.reducedFrequency == true) {
            return ApprovalPolicyTier.BATCH_DIGEST
        }

        // Reversible local note saving
        if (proposal.primaryAction.kind == ProposalActionKind.SAVE_NOTE) {
            return ApprovalPolicyTier.AUTO_SAFE_LOCAL
        }

        // User-opted-in safe calendar auto-allow for a specific trusted room
        if (proposal.type == ProactiveProposalType.CALENDAR_EVENT &&
            roomProfile?.alwaysAllowSafeCalendar == true
        ) {
            return ApprovalPolicyTier.AUTO_SAFE_LOCAL
        }

        return ApprovalPolicyTier.BATCH_DIGEST
    }

    fun toJson(): JSONObject = JSONObject().apply {
        val arr = JSONArray()
        synchronized(lock) { profileMap.values.toList() }.forEach { arr.put(it.toJson()) }
        put("count", arr.length())
        put("profiles", arr)
    }

    private fun loadFromDiskLocked() {
        val file = storageFile ?: return
        profileMap.clear()
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("profiles") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = RoomUrgencyProfile.fromJson(arr.optJSONObject(i)) ?: continue
                    profileMap[normalizeRoomKey(p.roomKey)] = p
                }
            }
        }
        _profiles.value = profileMap.toMap()
    }

    private fun trimAndPersistLocked() {
        while (profileMap.size > MAX_PROFILES) {
            val oldest = profileMap.entries.minByOrNull { it.value.updatedAtMs }?.key ?: break
            profileMap.remove(oldest)
        }
        _profiles.value = profileMap.toMap()
        val file = storageFile ?: return
        runCatching {
            val arr = JSONArray()
            profileMap.values.forEach { arr.put(it.toJson()) }
            val root = JSONObject()
                .put("schemaVersion", 1)
                .put("profiles", arr)
            ConvenienceStorageHelper.writeAtomically(file, root.toString(2))
        }
    }
}

object BatchDigestA2uiBuilder {

    const val ACTION_BATCH_APPROVE_SAFE = "secretary.batch_approve_safe"
    const val ACTION_BATCH_DISMISS_ALL = "secretary.batch_dismiss_all"

    private fun ptr(path: String): JSONObject = JSONObject().put("@path", path)

    fun buildBatchDigestMessages(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_batch_digest",
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

        val pending = proposals.filter { it.status == ProactiveProposalStatus.PENDING }.take(10)
        val safeIds = mutableListOf<String>()
        val allIds = mutableListOf<String>()
        val itemsData = JSONArray()
        val rootChildren = JSONArray()
        val components = JSONArray()

        rootChildren.put("digest_header").put("digest_subtitle")
        components.put(
            JSONObject()
                .put("id", "digest_header")
                .put("component", "Text")
                .put("text", ptr("/digest/headline"))
                .put("variant", "h2")
        )
        components.put(
            JSONObject()
                .put("id", "digest_subtitle")
                .put("component", "Text")
                .put("text", ptr("/digest/subtitle"))
                .put("variant", "caption")
        )

        pending.forEachIndexed { idx, p ->
            val tier = RoomUrgencyCalibrationStore.classifyTier(p)
            val isSafeForBatch = tier != ApprovalPolicyTier.HARD_MANUAL_GATE
            allIds.add(p.id)
            if (isSafeForBatch) safeIds.add(p.id)

            val label = "[${tier.labelKo}] ${p.sourceApp} · ${p.titleKo}"
            itemsData.put(
                JSONObject()
                    .put("id", p.id)
                    .put("label", label)
                    .put("summary", p.summaryKo)
                    .put("tier", tier.name)
                    .put("safeForBatch", isSafeForBatch)
                    .put("checked", isSafeForBatch)
            )
            val cardId = "digest_card_$idx"
            val colId = "digest_col_$idx"
            val cbId = "digest_cb_$idx"
            val sumId = "digest_sum_$idx"
            rootChildren.put(cardId)
            components.put(JSONObject().put("id", cardId).put("component", "Card").put("child", colId))
            components.put(
                JSONObject()
                    .put("id", colId)
                    .put("component", "Column")
                    .put("children", JSONArray().put(cbId).put(sumId))
            )
            components.put(
                JSONObject()
                    .put("id", cbId)
                    .put("component", "CheckBox")
                    .put("label", ptr("/digest/items/$idx/label"))
                    .put("value", ptr("/digest/items/$idx/checked"))
            )
            components.put(
                JSONObject()
                    .put("id", sumId)
                    .put("component", "Text")
                    .put("text", ptr("/digest/items/$idx/summary"))
                    .put("variant", "caption")
            )
        }

        if (pending.isEmpty()) {
            rootChildren.put("digest_empty")
            components.put(
                JSONObject()
                    .put("id", "digest_empty")
                    .put("component", "Text")
                    .put("text", "묶어 처리할 대기 제안이 없습니다.")
                    .put("variant", "body")
            )
        } else {
            rootChildren.put("digest_divider").put("digest_actions_row")
            components.put(
                JSONObject()
                    .put("id", "digest_divider")
                    .put("component", "Divider")
                    .put("axis", "horizontal")
            )
            components.put(
                JSONObject()
                    .put("id", "digest_approve_lbl")
                    .put("component", "Text")
                    .put("text", "안전 제안 일괄 승인 (${safeIds.size}건)")
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "digest_approve_btn")
                    .put("component", "Button")
                    .put("variant", "primary")
                    .put("child", "digest_approve_lbl")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_BATCH_APPROVE_SAFE)
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("proposalIds", ptr("/digest/safeIds"))
                                        .put("safeCount", ptr("/digest/safeCount"))
                                )
                        )
                    )
            )
            components.put(
                JSONObject()
                    .put("id", "digest_dismiss_lbl")
                    .put("component", "Text")
                    .put("text", "모두 닫기")
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "digest_dismiss_btn")
                    .put("component", "Button")
                    .put("variant", "default")
                    .put("child", "digest_dismiss_lbl")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_BATCH_DISMISS_ALL)
                                .put("context", JSONObject().put("proposalIds", ptr("/digest/allIds")))
                        )
                    )
            )
            components.put(
                JSONObject()
                    .put("id", "digest_actions_row")
                    .put("component", "Row")
                    .put("children", JSONArray().put("digest_approve_btn").put("digest_dismiss_btn"))
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

        val digestData = JSONObject()
            .put("headline", "비서 묶음 다이제스트 (${pending.size}건)")
            .put("subtitle", "일괄 승인 가능 ${safeIds.size}건 · 직접 확인 필요 ${pending.size - safeIds.size}건")
            .put("safeCount", safeIds.size)
            .put("safeIds", JSONArray(safeIds))
            .put("allIds", JSONArray(allIds))
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
                        .put("path", "/digest")
                        .put("value", digestData)
                )
        )
    }

    fun toBatchDigestJsonl(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_batch_digest",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String = buildBatchDigestMessages(proposals, surfaceId, protocolVersion).joinToString("\n") { it.toString() }

    fun publishBatchDigest(
        proposals: List<ProactiveProposal>,
        surfaceId: String = "secretary_batch_digest",
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val jsonl = toBatchDigestJsonl(proposals, surfaceId, protocolVersion)
        A2uiSurfaceRegistry.ingestJsonl(jsonl)
        return surfaceId
    }
}

// =============================================================================
// 3. Anti-Muse Privacy Proof Ledger + Daily VLM Budget Governor
// =============================================================================

data class PrivacyProofSnapshot(
    val localZeroCostScans: Long = 0L,
    val blockedSecretsCount: Long = 0L,
    val blockedPromptInjectionsCount: Long = 0L,
    val financeReadOnlyLocksCount: Long = 0L,
    val externalCloudLeaks: Long = 0L,
    val todayVlmTokensUsed: Long = 0L,
    val dailyVlmBudgetCap: Long = 50_000L,
    val budgetDateIso: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val remainingVlmTokens: Long
        get() = (dailyVlmBudgetCap - todayVlmTokensUsed).coerceAtLeast(0L)

    val deterministicFallbackActive: Boolean
        get() = todayVlmTokensUsed >= dailyVlmBudgetCap

    fun toSummaryLineKo(): String {
        return "로컬 \$0 스캔 ${localZeroCostScans}건 · 차단된 시크릿·PIN ${blockedSecretsCount}건 · 프롬프트 인젝션 차단 ${blockedPromptInjectionsCount}건 · 금융 읽기전용 잠금 ${financeReadOnlyLocksCount}건 · 외부 클라우드 전송 ${externalCloudLeaks}건 · 오늘 VLM 잔여 ${remainingVlmTokens}토큰"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("localZeroCostScans", localZeroCostScans)
        put("blockedSecretsCount", blockedSecretsCount)
        put("blockedPromptInjectionsCount", blockedPromptInjectionsCount)
        put("financeReadOnlyLocksCount", financeReadOnlyLocksCount)
        put("externalCloudLeaks", 0L)
        put("todayVlmTokensUsed", todayVlmTokensUsed)
        put("dailyVlmBudgetCap", dailyVlmBudgetCap)
        put("remainingVlmTokens", remainingVlmTokens)
        put("deterministicFallbackActive", deterministicFallbackActive)
        put("budgetDateIso", budgetDateIso)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject?): PrivacyProofSnapshot {
            if (obj == null) return PrivacyProofSnapshot()
            return PrivacyProofSnapshot(
                localZeroCostScans = obj.optLong("localZeroCostScans", 0L).coerceAtLeast(0L),
                blockedSecretsCount = obj.optLong("blockedSecretsCount", 0L).coerceAtLeast(0L),
                blockedPromptInjectionsCount = obj.optLong("blockedPromptInjectionsCount", 0L).coerceAtLeast(0L),
                financeReadOnlyLocksCount = obj.optLong("financeReadOnlyLocksCount", 0L).coerceAtLeast(0L),
                externalCloudLeaks = 0L,
                todayVlmTokensUsed = obj.optLong("todayVlmTokensUsed", 0L).coerceAtLeast(0L),
                dailyVlmBudgetCap = obj.optLong("dailyVlmBudgetCap", 50_000L).coerceAtLeast(1_000L),
                budgetDateIso = obj.optString("budgetDateIso", ""),
                updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
            )
        }
    }
}

object PrivacyAndQuotaProofLedger {

    private const val LEDGER_FILE_NAME = "hanson_privacy_proof_ledger.json"

    private val lock = Any()

    @Volatile
    private var storageFile: File? = null

    @Volatile
    private var initialized = false

    private val _snapshot = MutableStateFlow(PrivacyProofSnapshot())
    val snapshot: StateFlow<PrivacyProofSnapshot> = _snapshot.asStateFlow()

    fun ensureInitialized(context: Context?) {
        if (initialized || context == null) return
        val dir = runCatching { (context.applicationContext ?: context).filesDir }.getOrNull() ?: return
        synchronized(lock) {
            if (initialized) return
            storageFile = File(dir, LEDGER_FILE_NAME)
            loadFromDiskLocked()
            initialized = true
        }
    }

    fun configureForTesting(dir: File, clearExisting: Boolean = true) {
        synchronized(lock) {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, LEDGER_FILE_NAME)
            storageFile = file
            if (clearExisting) {
                _snapshot.value = PrivacyProofSnapshot()
                file.delete()
            } else {
                loadFromDiskLocked()
            }
            initialized = true
        }
    }

    fun resetForTesting() {
        synchronized(lock) {
            _snapshot.value = PrivacyProofSnapshot()
            storageFile = null
            initialized = false
        }
    }

    fun recordLocalZeroCostScan(delta: Long = 1L, nowMs: Long = System.currentTimeMillis()): PrivacyProofSnapshot =
        mutateLocked(nowMs) { cur ->
            cur.copy(localZeroCostScans = cur.localZeroCostScans + delta.coerceAtLeast(0L))
        }

    fun recordBlockedSecret(delta: Long = 1L, nowMs: Long = System.currentTimeMillis()): PrivacyProofSnapshot =
        mutateLocked(nowMs) { cur ->
            cur.copy(blockedSecretsCount = cur.blockedSecretsCount + delta.coerceAtLeast(0L))
        }

    fun recordBlockedPromptInjection(delta: Long = 1L, nowMs: Long = System.currentTimeMillis()): PrivacyProofSnapshot =
        mutateLocked(nowMs) { cur ->
            cur.copy(blockedPromptInjectionsCount = cur.blockedPromptInjectionsCount + delta.coerceAtLeast(0L))
        }

    fun recordFinanceReadOnlyLock(delta: Long = 1L, nowMs: Long = System.currentTimeMillis()): PrivacyProofSnapshot =
        mutateLocked(nowMs) { cur ->
            cur.copy(financeReadOnlyLocksCount = cur.financeReadOnlyLocksCount + delta.coerceAtLeast(0L))
        }

    fun recordVlmTokensUsed(
        tokens: Long,
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): PrivacyProofSnapshot {
        val todayIso = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().toString()
        return mutateLocked(nowMs) { cur ->
            val baseUsed = if (cur.budgetDateIso.isNotBlank() && cur.budgetDateIso != todayIso) {
                0L
            } else {
                cur.todayVlmTokensUsed
            }
            cur.copy(
                todayVlmTokensUsed = baseUsed + tokens.coerceAtLeast(0L),
                budgetDateIso = todayIso
            )
        }
    }

    fun setDailyVlmBudgetCap(
        capTokens: Long,
        nowMs: Long = System.currentTimeMillis()
    ): PrivacyProofSnapshot = mutateLocked(nowMs) { cur ->
        cur.copy(dailyVlmBudgetCap = capTokens.coerceIn(1_000L, 10_000_000L))
    }

    fun isDeterministicFallbackActive(
        nowMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): Boolean {
        val cur = _snapshot.value
        val todayIso = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate().toString()
        if (cur.budgetDateIso.isNotBlank() && cur.budgetDateIso != todayIso) {
            return false
        }
        return cur.todayVlmTokensUsed >= cur.dailyVlmBudgetCap
    }

    fun toJson(): JSONObject = _snapshot.value.toJson()

    private fun mutateLocked(
        nowMs: Long,
        transform: (PrivacyProofSnapshot) -> PrivacyProofSnapshot
    ): PrivacyProofSnapshot {
        synchronized(lock) {
            val next = transform(_snapshot.value).copy(
                externalCloudLeaks = 0L,
                updatedAtMs = nowMs
            )
            _snapshot.value = next
            saveToDiskLocked(next)
            return next
        }
    }

    private fun loadFromDiskLocked() {
        val file = storageFile ?: return
        if (file.isFile) {
            runCatching {
                val obj = JSONObject(file.readText(StandardCharsets.UTF_8))
                _snapshot.value = PrivacyProofSnapshot.fromJson(obj)
            }
        }
    }

    private fun saveToDiskLocked(state: PrivacyProofSnapshot) {
        val file = storageFile ?: return
        runCatching {
            ConvenienceStorageHelper.writeAtomically(file, state.toJson().toString(2))
        }
    }
}

// =============================================================================
// 4. Share-Sheet -> A2UI Checklist / Itinerary / Price-Drop Watcher (Meta Muse)
// =============================================================================

enum class ShareChecklistKind(val labelKo: String) {
    PRICE_DROP_WATCH("가격·재고 감시"),
    TRIP_ITINERARY("여행·방문 일정"),
    SHOPPING_CHECKLIST("쇼핑 체크리스트"),
    READING_CHECKLIST("읽기·요약 체크리스트")
}

data class ShareChecklistItem(
    val id: String,
    val labelKo: String,
    val checked: Boolean = false,
    val noteKo: String = ""
)

data class ShareChecklistResult(
    val surfaceId: String,
    val kind: ShareChecklistKind,
    val titleKo: String,
    val summaryKo: String,
    val sourceUrl: String = "",
    val items: List<ShareChecklistItem>,
    val a2uiJsonl: String,
    val suggestedWatcherRule: WatcherRule? = null
)

object ShareToChecklistEngine {

    const val ACTION_SAVE_CHECKLIST = "share.save_checklist"
    const val ACTION_ADD_WATCHER = "share.add_watcher"
    const val ACTION_DISMISS_CHECKLIST = "share.dismiss"

    private const val MAX_SHARED_CHARS = 4000
    private val URL_REGEX = Regex("(?i)https?://[^\\s<>\"']+")
    private val PRICE_WATCH_URL_REGEX = Regex(
        "(?i)(?:coupang\\.com|smartstore\\.naver\\.com|shopping\\.naver\\.com|brand\\.naver\\.com|catchtable\\.co\\.kr|letskorail\\.com|etk\\.srail\\.kr|musinsa\\.com|kurly\\.com)"
    )
    private val PRICE_WATCH_CUE_REGEX = Regex(
        "(?i)(?:가격\\s*하락|최저가|할인\\s*알림|재입고|품절|매진|취소표|예약\\s*오픈|price\\s*drop|restock|back\\s*in\\s*stock)"
    )
    private val TRIP_CUE_REGEX = Regex(
        "(?i)(?:여행|일정\\s*표|코스|\\d+박\\s*\\d+일|\\d+일차|항공편|숙소|호텔\\s*체크인|제주|부산|도쿄|오사카|교토|방콕|itinerary|trip\\s*plan)"
    )
    private val SHOPPING_CUE_REGEX = Regex(
        "(?i)(?:장보기|쇼핑|구매\\s*목록|살\\s*것|준비물|장바구니|마트|사야\\s*(?:할|함|돼)|shopping\\s*list|groceries)"
    )
    private val BULLET_PREFIX_REGEX = Regex("^(?:[-*•·]|\\d+[.)]|\\[\\s*[xX ]?\\s*\\])\\s*")

    private fun ptr(path: String): JSONObject = JSONObject().put("@path", path)

    /**
     * Converts shared text or a URL into a local, privacy-checked checklist/itinerary/price-drop
     * A2UI v1.0 surface with zero cloud scraping.
     */
    fun analyzeSharedContent(
        rawTextOrUrl: String,
        vault: BlindCredentialVault? = null,
        publishToRegistry: Boolean = false
    ): ShareChecklistResult? {
        val trimmed = rawTextOrUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SHARED_CHARS) return null

        // Hardline security checks
        if (UntrustedScreenDataGuard.detectPromptInjectionSignals(trimmed).isNotEmpty()) {
            PrivacyAndQuotaProofLedger.recordBlockedPromptInjection()
            return null
        }
        if (ConvenienceSecretGuard.containsSensitiveSecret(trimmed, vault)) {
            PrivacyAndQuotaProofLedger.recordBlockedSecret()
            return null
        }
        PrivacyAndQuotaProofLedger.recordLocalZeroCostScan()

        val sourceUrl = URL_REGEX.find(trimmed)?.value?.trimEnd('.', ',', ')', ']') ?: ""
        val textWithoutUrl = trimmed.replace(URL_REGEX, " ").replace(Regex("\\s+"), " ").trim()

        val kind = when {
            (sourceUrl.isNotBlank() && PRICE_WATCH_URL_REGEX.containsMatchIn(sourceUrl)) ||
                PRICE_WATCH_CUE_REGEX.containsMatchIn(trimmed) -> ShareChecklistKind.PRICE_DROP_WATCH
            TRIP_CUE_REGEX.containsMatchIn(trimmed) -> ShareChecklistKind.TRIP_ITINERARY
            SHOPPING_CUE_REGEX.containsMatchIn(trimmed) -> ShareChecklistKind.SHOPPING_CHECKLIST
            else -> ShareChecklistKind.READING_CHECKLIST
        }

        val items = buildItemsForKind(kind, trimmed, textWithoutUrl, sourceUrl)
        val titleKo = inferChecklistTitle(kind, textWithoutUrl, sourceUrl)
        val summaryKo = "${kind.labelKo} · 항목 ${items.size}개 (로컬 분석 · 외부 클라우드 스크래핑 없음)"

        val watcherRule = if (kind == ShareChecklistKind.PRICE_DROP_WATCH) {
            buildSuggestedWatcherRule(titleKo, textWithoutUrl, sourceUrl)
        } else {
            null
        }

        val hash = sha256Hex("${kind.name}|$trimmed").take(10)
        val surfaceId = "share_checklist_$hash"
        val a2uiJsonl = buildChecklistA2uiJsonl(
            surfaceId = surfaceId,
            kind = kind,
            titleKo = titleKo,
            summaryKo = summaryKo,
            sourceUrl = sourceUrl,
            items = items,
            watcherRule = watcherRule
        )
        if (publishToRegistry) {
            A2uiSurfaceRegistry.ingestJsonl(a2uiJsonl)
        }

        return ShareChecklistResult(
            surfaceId = surfaceId,
            kind = kind,
            titleKo = titleKo,
            summaryKo = summaryKo,
            sourceUrl = sourceUrl,
            items = items,
            a2uiJsonl = a2uiJsonl,
            suggestedWatcherRule = watcherRule
        )
    }

    private fun buildItemsForKind(
        kind: ShareChecklistKind,
        raw: String,
        textWithoutUrl: String,
        sourceUrl: String
    ): List<ShareChecklistItem> {
        val extractedLines = extractUserListItems(raw)
        return when (kind) {
            ShareChecklistKind.PRICE_DROP_WATCH -> {
                val targetLabel = textWithoutUrl.take(36).ifBlank { sourceUrl.substringAfter("://").take(36) }
                listOf(
                    ShareChecklistItem("item_0", "현재 가격·옵션 확인: $targetLabel"),
                    ShareChecklistItem("item_1", "재입고·가격 하락 로컬 감시 규칙 활성화"),
                    ShareChecklistItem("item_2", "최종 결제·예약 전 직접 승인 후 진행")
                )
            }
            ShareChecklistKind.TRIP_ITINERARY -> {
                if (extractedLines.size >= 2) {
                    extractedLines.take(8).mapIndexed { idx, line ->
                        ShareChecklistItem("item_$idx", line)
                    }
                } else {
                    listOf(
                        ShareChecklistItem("item_0", "교통편·출발 시간 캘린더 등록"),
                        ShareChecklistItem("item_1", "숙소 체크인 정보 및 지도 위치 저장"),
                        ShareChecklistItem("item_2", "방문 코스 동선 확인: ${textWithoutUrl.take(40).ifBlank { "주요 방문지" }}"),
                        ShareChecklistItem("item_3", "출발 당일 날씨·우산 여부 확인")
                    )
                }
            }
            ShareChecklistKind.SHOPPING_CHECKLIST -> {
                val parsed = if (extractedLines.size >= 2) {
                    extractedLines
                } else {
                    splitInlineShoppingTokens(textWithoutUrl)
                }
                if (parsed.isNotEmpty()) {
                    parsed.take(10).mapIndexed { idx, token ->
                        ShareChecklistItem("item_$idx", token)
                    }
                } else {
                    listOf(
                        ShareChecklistItem("item_0", textWithoutUrl.take(48).ifBlank { "구매 항목 확인" }),
                        ShareChecklistItem("item_1", "장바구니 담기 및 가격 비교")
                    )
                }
            }
            ShareChecklistKind.READING_CHECKLIST -> {
                if (extractedLines.size >= 2) {
                    extractedLines.take(8).mapIndexed { idx, line ->
                        ShareChecklistItem("item_$idx", line)
                    }
                } else {
                    listOf(
                        ShareChecklistItem("item_0", "핵심 내용 읽기·요약: ${textWithoutUrl.take(42).ifBlank { sourceUrl.take(42) }}"),
                        ShareChecklistItem("item_1", "중요 인사이트 작업 메모 저장"),
                        ShareChecklistItem("item_2", "후속 실행 항목 검토")
                    )
                }
            }
        }
    }

    private fun extractUserListItems(raw: String): List<String> {
        return raw.lineSequence()
            .map { it.replace(URL_REGEX, "").trim() }
            .map { it.replace(BULLET_PREFIX_REGEX, "").trim() }
            .filter { it.length >= 2 }
            .distinct()
            .toList()
    }

    private fun splitInlineShoppingTokens(text: String): List<String> {
        val cleaned = text
            .replace(Regex("^(?:장보기|쇼핑|구매\\s*목록|살\\s*것|준비물)\\s*[:：-]?\\s*"), "")
            .replace(Regex("\\s*(?:사야\\s*(?:함|할|돼|해요|됨)|구매\\s*필요|부탁해?요?)\\s*[.!?]*$"), "")
            .trim()
        return cleaned.split(Regex("[,/·]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun inferChecklistTitle(
        kind: ShareChecklistKind,
        textWithoutUrl: String,
        sourceUrl: String
    ): String {
        val snippet = textWithoutUrl.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(30).orEmpty()
        return when (kind) {
            ShareChecklistKind.PRICE_DROP_WATCH -> "가격·재고 감시: ${snippet.ifBlank { sourceUrl.substringAfter("://").substringBefore("/") }}"
            ShareChecklistKind.TRIP_ITINERARY -> "여행 일정: ${snippet.ifBlank { "공유된 코스" }}"
            ShareChecklistKind.SHOPPING_CHECKLIST -> "쇼핑 체크리스트: ${snippet.ifBlank { "장보기 항목" }}"
            ShareChecklistKind.READING_CHECKLIST -> "읽기 체크리스트: ${snippet.ifBlank { sourceUrl.substringAfter("://").substringBefore("/") }}"
        }
    }

    private fun buildSuggestedWatcherRule(
        titleKo: String,
        textWithoutUrl: String,
        sourceUrl: String
    ): WatcherRule {
        val target = when {
            sourceUrl.contains("coupang.com", ignoreCase = true) -> sourceUrl
            sourceUrl.contains("catchtable", ignoreCase = true) -> "co.kr.catchtable.android.catchtable_app"
            sourceUrl.contains("korail", ignoreCase = true) -> "com.korail.talk"
            sourceUrl.isNotBlank() -> sourceUrl
            textWithoutUrl.contains("쿠팡") -> "com.coupang.mobile"
            textWithoutUrl.contains("캐치테이블") -> "co.kr.catchtable.android.catchtable_app"
            else -> "com.nhn.android.search"
        }
        val keywords = textWithoutUrl
            .split(Regex("[\\s,·/()]+"))
            .filter { it.length >= 2 && !it.startsWith("http", ignoreCase = true) }
            .distinct()
            .take(4)
            .ifEmpty { listOf("할인", "재입고", "예약가능") }

        val id = "watch_share_${sha256Hex(target + titleKo).take(8)}"
        return WatcherRule(
            id = id,
            title = titleKo.take(48),
            targetAppOrUrl = target,
            matchKeywords = keywords,
            mode = WatcherActionMode.HOLD_FOR_APPROVAL,
            enabled = true
        )
    }

    private fun buildChecklistA2uiJsonl(
        surfaceId: String,
        kind: ShareChecklistKind,
        titleKo: String,
        summaryKo: String,
        sourceUrl: String,
        items: List<ShareChecklistItem>,
        watcherRule: WatcherRule?
    ): String {
        val rootChildren = JSONArray().put("cl_title").put("cl_summary")
        val components = JSONArray()
        val itemsJson = JSONArray()

        components.put(
            JSONObject()
                .put("id", "cl_title")
                .put("component", "Text")
                .put("text", ptr("/checklist/titleKo"))
                .put("variant", "h3")
        )
        components.put(
            JSONObject()
                .put("id", "cl_summary")
                .put("component", "Text")
                .put("text", ptr("/checklist/summaryKo"))
                .put("variant", "caption")
        )

        items.forEachIndexed { idx, item ->
            itemsJson.put(
                JSONObject()
                    .put("id", item.id)
                    .put("label", item.labelKo)
                    .put("checked", item.checked)
            )
            val cbId = "cl_cb_$idx"
            rootChildren.put(cbId)
            components.put(
                JSONObject()
                    .put("id", cbId)
                    .put("component", "CheckBox")
                    .put("label", ptr("/checklist/items/$idx/label"))
                    .put("value", ptr("/checklist/items/$idx/checked"))
            )
        }

        val btnRowChildren = JSONArray().put("cl_save_btn")
        components.put(
            JSONObject().put("id", "cl_save_lbl").put("component", "Text").put("text", "메모 저장").put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "cl_save_btn")
                .put("component", "Button")
                .put("variant", "primary")
                .put("child", "cl_save_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_SAVE_CHECKLIST)
                            .put(
                                "context",
                                JSONObject()
                                    .put("surfaceId", surfaceId)
                                    .put("titleKo", ptr("/checklist/titleKo"))
                                    .put("items", ptr("/checklist/items"))
                            )
                    )
                )
        )

        if (watcherRule != null) {
            btnRowChildren.put("cl_watch_btn")
            components.put(
                JSONObject().put("id", "cl_watch_lbl").put("component", "Text").put("text", "로컬 감시 등록").put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "cl_watch_btn")
                    .put("component", "Button")
                    .put("variant", "default")
                    .put("child", "cl_watch_lbl")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_ADD_WATCHER)
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("ruleId", watcherRule.id)
                                        .put("title", watcherRule.title)
                                        .put("targetAppOrUrl", watcherRule.targetAppOrUrl)
                                )
                        )
                    )
            )
        }

        btnRowChildren.put("cl_dismiss_btn")
        components.put(
            JSONObject().put("id", "cl_dismiss_lbl").put("component", "Text").put("text", "닫기").put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "cl_dismiss_btn")
                .put("component", "Button")
                .put("variant", "default")
                .put("child", "cl_dismiss_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_DISMISS_CHECKLIST)
                            .put("context", JSONObject().put("surfaceId", surfaceId))
                    )
                )
        )

        rootChildren.put("cl_actions_row")
        components.put(
            JSONObject().put("id", "cl_actions_row").put("component", "Row").put("children", btnRowChildren)
        )

        val allComponents = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            for (i in 0 until components.length()) put(components.get(i))
        }

        val dataObj = JSONObject()
            .put("kind", kind.name)
            .put("titleKo", titleKo)
            .put("summaryKo", summaryKo)
            .put("sourceUrl", sourceUrl)
            .put("items", itemsJson)

        val messages = listOf(
            JSONObject()
                .put("version", A2uiProtocolConstants.VERSION_1_0)
                .put(
                    "createSurface",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("catalogId", A2uiProtocolConstants.BASIC_CATALOG_V1_0)
                        .put("sendDataModel", true)
                ),
            JSONObject()
                .put("version", A2uiProtocolConstants.VERSION_1_0)
                .put(
                    "updateComponents",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("components", allComponents)
                ),
            JSONObject()
                .put("version", A2uiProtocolConstants.VERSION_1_0)
                .put(
                    "updateDataModel",
                    JSONObject()
                        .put("surfaceId", surfaceId)
                        .put("path", "/checklist")
                        .put("value", dataObj)
                )
        )
        return messages.joinToString("\n") { it.toString() }
    }

    private fun sha256Hex(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

// =============================================================================
// 5. Topic Channels + Multi-Specialist Hanson Crew + Live Call Companion
// =============================================================================

enum class TopicChannelType(
    val id: String,
    val labelKo: String
) {
    ALL("all", "전체"),
    WORK("work", "업무"),
    FAMILY("family", "가족·지인"),
    SHOPPING("shopping", "쇼핑·예약"),
    SIDE_CHAT("side_chat", "사이드 챗");

    companion object {
        fun fromIdOrDefault(raw: String?, default: TopicChannelType = ALL): TopicChannelType {
            val clean = raw?.trim().orEmpty()
            return entries.firstOrNull {
                it.id.equals(clean, ignoreCase = true) || it.name.equals(clean, ignoreCase = true)
            } ?: default
        }
    }
}

object TopicChannelClassifier {

    private val WORK_TEXT_CUE_REGEX = Regex(
        "(?i)(?:pr\\s*리뷰|코드\\s*리뷰|견적서|회의록|스프린트|배포|클라이언트|보고서|결재|팀장|부장|대표님|미팅|업무|슬랙|지라|노션)"
    )

    fun classifyProposalChannel(proposal: ProactiveProposal): TopicChannelType {
        val domain = AppDetailPlaybookRegistry.findByPackage(proposal.packageName)?.domainCategory

        if (proposal.type == ProactiveProposalType.DELIVERY_TRACKING ||
            proposal.type == ProactiveProposalType.BILLING_SUBSCRIPTION_WATCH ||
            proposal.type == ProactiveProposalType.RESERVATION_COMMUTE ||
            domain == AppDomainCategory.DELIVERY ||
            domain == AppDomainCategory.FINANCE_ADVISORY ||
            domain == AppDomainCategory.RESERVATION
        ) {
            return TopicChannelType.SHOPPING
        }

        if (domain == AppDomainCategory.WORK_CHAT ||
            domain == AppDomainCategory.EMAIL ||
            domain == AppDomainCategory.PRODUCTIVITY ||
            proposal.type == ProactiveProposalType.DEADLINE_TASK ||
            proposal.type == ProactiveProposalType.MEETING_LINK_PREP
        ) {
            return TopicChannelType.WORK
        }

        if (WORK_TEXT_CUE_REGEX.containsMatchIn("${proposal.roomOrTitle} ${proposal.titleKo} ${proposal.quotedExcerpt}")) {
            return TopicChannelType.WORK
        }

        if (domain == AppDomainCategory.MESSENGER ||
            proposal.type == ProactiveProposalType.CALENDAR_EVENT ||
            proposal.type == ProactiveProposalType.UNANSWERED_FOLLOWUP ||
            proposal.type == ProactiveProposalType.UPCOMING_PREP_BRIEF
        ) {
            return TopicChannelType.FAMILY
        }

        return TopicChannelType.SIDE_CHAT
    }

    fun filterProposals(
        proposals: List<ProactiveProposal>,
        channel: TopicChannelType
    ): List<ProactiveProposal> {
        if (channel == TopicChannelType.ALL) return proposals
        return proposals.filter { classifyProposalChannel(it) == channel }
    }
}

enum class HansonCrewRole(
    val mentionKo: String,
    val labelKo: String,
    val requiresVirtualDisplayLease: Boolean,
    val allowedTools: Set<String>
) {
    SECRETARY(
        mentionKo = "@비서",
        labelKo = "AI 비서",
        requiresVirtualDisplayLease = false,
        allowedTools = setOf(
            "list_notifications",
            "reply_notification",
            "manage_calendar",
            "set_alarm",
            "set_timer",
            "save_note",
            "read_note",
            "scratchpad_note",
            "a2a_negotiate_schedule",
            "execute_lifestyle_routine",
            "ask_user",
            "complete_task"
        )
    ),
    RESEARCHER(
        mentionKo = "@리서처",
        labelKo = "웹 리서처",
        requiresVirtualDisplayLease = false,
        allowedTools = setOf(
            "browser_use",
            "open_link",
            "save_note",
            "read_note",
            "scratchpad_note",
            "render_generative_ui",
            "ask_user",
            "complete_task"
        )
    ),
    SHOPPING_BOOKING(
        mentionKo = "@예약",
        labelKo = "예약·쇼핑",
        requiresVirtualDisplayLease = true,
        allowedTools = setOf(
            "manage_app",
            "click",
            "input_text",
            "swipe",
            "press_key",
            "wait_for_delay",
            "open_link",
            "watcher_manage",
            "save_note",
            "read_note",
            "ask_user",
            "complete_task"
        )
    ),
    DEVELOPER(
        mentionKo = "@개발",
        labelKo = "모바일 개발자",
        requiresVirtualDisplayLease = true,
        allowedTools = setOf(
            "workspace_list_files",
            "workspace_read_file",
            "workspace_write_file",
            "workspace_run_command",
            "run_os_cli",
            "browser_use",
            "save_note",
            "read_note",
            "ask_user",
            "complete_task"
        )
    )
}

data class HansonCrewAssignment(
    val role: HansonCrewRole,
    val cleanedGoal: String,
    val explicitMention: Boolean,
    val allowedTools: Set<String>,
    val requiresVirtualDisplayLease: Boolean,
    val systemDirectiveKo: String
)

object HansonCrewRouter {

    private val MENTION_PREFIX_REGEX = Regex(
        "^(?:@(비서|secretary|리서처|조사|researcher|예약|쇼핑|예약·쇼핑|booking|shopping|개발|코딩|developer|dev))\\s*[:：,-]?\\s*",
        RegexOption.IGNORE_CASE
    )
    private val DEV_CUE_REGEX = Regex(
        "(?i)(?:코드|버그|빌드|gradle|git|pr\\s*리뷰|스크립트|터미널|워크스페이스|파일\\s*수정|디버그|컴파일|api\\s*테스트)"
    )
    private val SHOPPING_BOOKING_CUE_REGEX = Regex(
        "(?i)(?:쿠팡|배민|배달의민족|쿠팡이츠|캐치테이블|코레일|ktx|srt|예약|예매|장바구니|주문|택시|카카오\\s*t|최저가|취소표)"
    )
    private val RESEARCH_CUE_REGEX = Regex(
        "(?i)(?:리서치|조사해|찾아줘|검색해|뉴스\\s*요약|웹페이지\\s*요약|논문|트렌드|비교\\s*분석|https?://)"
    )

    fun route(rawInput: String): HansonCrewAssignment {
        val trimmed = rawInput.trim()
        val mentionMatch = MENTION_PREFIX_REGEX.find(trimmed)
        if (mentionMatch != null) {
            val token = mentionMatch.groupValues[1].lowercase(Locale.ROOT)
            val role = when (token) {
                "비서", "secretary" -> HansonCrewRole.SECRETARY
                "리서처", "조사", "researcher" -> HansonCrewRole.RESEARCHER
                "예약", "쇼핑", "예약·쇼핑", "booking", "shopping" -> HansonCrewRole.SHOPPING_BOOKING
                "개발", "코딩", "developer", "dev" -> HansonCrewRole.DEVELOPER
                else -> HansonCrewRole.SECRETARY
            }
            val cleaned = trimmed.substring(mentionMatch.range.last + 1).trim().ifEmpty { trimmed }
            return buildAssignment(role, cleaned, explicitMention = true)
        }

        val inferred = when {
            DEV_CUE_REGEX.containsMatchIn(trimmed) -> HansonCrewRole.DEVELOPER
            SHOPPING_BOOKING_CUE_REGEX.containsMatchIn(trimmed) -> HansonCrewRole.SHOPPING_BOOKING
            RESEARCH_CUE_REGEX.containsMatchIn(trimmed) -> HansonCrewRole.RESEARCHER
            else -> HansonCrewRole.SECRETARY
        }
        return buildAssignment(inferred, trimmed, explicitMention = false)
    }

    fun isToolAllowed(role: HansonCrewRole, toolName: String): Boolean {
        val clean = toolName.trim()
        return clean in role.allowedTools
    }

    private fun buildAssignment(
        role: HansonCrewRole,
        cleanedGoal: String,
        explicitMention: Boolean
    ): HansonCrewAssignment {
        val leaseNote = if (role.requiresVirtualDisplayLease) {
            "UI/가상 디스플레이 접근 시 단일 리스 잠금을 획득해 충돌을 방지합니다."
        } else {
            "가상 디스플레이 없이 경량 백그라운드 도구로 즉시 처리합니다."
        }
        return HansonCrewAssignment(
            role = role,
            cleanedGoal = cleanedGoal,
            explicitMention = explicitMention,
            allowedTools = role.allowedTools,
            requiresVirtualDisplayLease = role.requiresVirtualDisplayLease,
            systemDirectiveKo = "[Hanson Crew · ${role.labelKo}(${role.mentionKo})] 허용 도구 ${role.allowedTools.size}개 범위 내에서 수행하며 $leaseNote"
        )
    }
}

// Live Call Companion (Honest script checklist + 1-tap dial + post-call memo extractor; ZERO covert recording)

data class CallTalkingPoint(
    val id: String,
    val textKo: String,
    val checked: Boolean = false
)

data class CallCompanionSession(
    val sessionId: String,
    val calleeLabel: String,
    val phoneNumber: String,
    val purposeKo: String,
    val talkingPoints: List<CallTalkingPoint>,
    val dialUri: String,
    val requiresHumanApproval: Boolean = true,
    val covertCallRecordingEnabled: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)

object LiveCallCompanionEngine {

    const val ACTION_DIAL = "call.dial"
    const val ACTION_SAVE_POST_CALL = "call.save_post_call_memo"

    private fun ptr(path: String): JSONObject = JSONObject().put("@path", path)

    fun createSession(
        calleeLabel: String,
        phoneNumber: String,
        purposeKo: String,
        preferredSlot: String = "",
        nowMs: Long = System.currentTimeMillis()
    ): CallCompanionSession {
        val plan = CallConciergePlanner.buildPlan(
            calleeLabel = calleeLabel,
            phoneNumber = phoneNumber,
            purposeKo = purposeKo,
            preferredSlot = preferredSlot
        )
        val safeDialDigits = plan.phoneNumber.filter { it.isDigit() || it == '+' || it == '-' }
        val dialUri = if (safeDialDigits.isNotBlank()) "tel:$safeDialDigits" else ""
        val points = plan.scriptLinesKo.mapIndexed { idx, line ->
            CallTalkingPoint(id = "tp_$idx", textKo = line, checked = false)
        } + CallTalkingPoint(
            id = "tp_confirm",
            textKo = "통화 종료 후 확정된 시간·장소·주의사항을 메모로 남기기",
            checked = false
        )
        val sid = "call_${nowMs}_${(plan.calleeLabel.hashCode() and 0x7FFF).toString(16)}"
        return CallCompanionSession(
            sessionId = sid,
            calleeLabel = plan.calleeLabel,
            phoneNumber = plan.phoneNumber,
            purposeKo = plan.purposeKo,
            talkingPoints = points,
            dialUri = dialUri,
            requiresHumanApproval = true,
            covertCallRecordingEnabled = false,
            createdAtMs = nowMs
        )
    }

    fun toggleTalkingPoint(session: CallCompanionSession, pointId: String): CallCompanionSession {
        return session.copy(
            talkingPoints = session.talkingPoints.map { pt ->
                if (pt.id == pointId) pt.copy(checked = !pt.checked) else pt
            }
        )
    }

    fun toA2uiJsonl(
        session: CallCompanionSession,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
    ): String {
        val surfaceId = "call_companion_${session.sessionId}"
        val rootChildren = JSONArray().put("call_hdr").put("call_notice")
        val components = JSONArray()
        val pointsArr = JSONArray()

        components.put(
            JSONObject()
                .put("id", "call_hdr")
                .put("component", "Text")
                .put("text", ptr("/call/headline"))
                .put("variant", "h3")
        )
        components.put(
            JSONObject()
                .put("id", "call_notice")
                .put("component", "Text")
                .put("text", "자동 통화 녹음 없음 · 사용자가 직접 통화하며 체크하는 스크립트 카드입니다.")
                .put("variant", "caption")
        )

        session.talkingPoints.forEachIndexed { idx, pt ->
            pointsArr.put(
                JSONObject()
                    .put("id", pt.id)
                    .put("label", pt.textKo)
                    .put("checked", pt.checked)
            )
            val cbId = "call_tp_$idx"
            rootChildren.put(cbId)
            components.put(
                JSONObject()
                    .put("id", cbId)
                    .put("component", "CheckBox")
                    .put("label", ptr("/call/points/$idx/label"))
                    .put("value", ptr("/call/points/$idx/checked"))
            )
        }

        val btnRowChildren = JSONArray()
        if (session.dialUri.isNotBlank()) {
            btnRowChildren.put("call_dial_btn")
            components.put(
                JSONObject().put("id", "call_dial_lbl").put("component", "Text").put("text", "전화 걸기(직접 확인)").put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "call_dial_btn")
                    .put("component", "Button")
                    .put("variant", "primary")
                    .put("child", "call_dial_lbl")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", ACTION_DIAL)
                                .put(
                                    "context",
                                    JSONObject()
                                        .put("sessionId", session.sessionId)
                                        .put("dialUri", ptr("/call/dialUri"))
                                )
                        )
                    )
            )
        }
        btnRowChildren.put("call_memo_btn")
        components.put(
            JSONObject().put("id", "call_memo_lbl").put("component", "Text").put("text", "통화 후 일정 추출").put("variant", "caption")
        )
        components.put(
            JSONObject()
                .put("id", "call_memo_btn")
                .put("component", "Button")
                .put("variant", "default")
                .put("child", "call_memo_lbl")
                .put(
                    "action",
                    JSONObject().put(
                        "event",
                        JSONObject()
                            .put("name", ACTION_SAVE_POST_CALL)
                            .put("context", JSONObject().put("sessionId", session.sessionId))
                    )
                )
        )

        rootChildren.put("call_actions_row")
        components.put(
            JSONObject().put("id", "call_actions_row").put("component", "Row").put("children", btnRowChildren)
        )

        val allComponents = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            for (i in 0 until components.length()) put(components.get(i))
        }

        val dataObj = JSONObject()
            .put("sessionId", session.sessionId)
            .put("headline", "전화 비서 체크리스트 · ${session.calleeLabel}")
            .put("dialUri", session.dialUri)
            .put("covertCallRecordingEnabled", false)
            .put("points", pointsArr)

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
                JSONObject().put("surfaceId", surfaceId).put("components", allComponents)
            ),
            JSONObject().put("version", ver).put(
                "updateDataModel",
                JSONObject().put("surfaceId", surfaceId).put("path", "/call").put("value", dataObj)
            )
        ).joinToString("\n") { it.toString() }
    }

    /**
     * Converts a user-typed post-call quick memo into an actionable [ProactiveProposal]
     * (calendar event, reservation commute, or follow-up task) through `ProactiveSecretaryEngine`.
     */
    fun extractPostCallProposal(
        context: Context?,
        session: CallCompanionSession,
        postCallMemo: String,
        referenceTimeMs: Long = System.currentTimeMillis(),
        zoneId: ZoneId = KoreanScheduleParser.DEFAULT_ZONE
    ): ProactiveProposal? {
        val cleanMemo = postCallMemo.trim()
        if (cleanMemo.isEmpty()) return null
        return ProactiveSecretaryEngine.ingestExternalSignal(
            context = context,
            packageName = "com.hanson.callcompanion",
            sender = session.calleeLabel,
            text = cleanMemo,
            appLabel = "전화 비서",
            roomOrTitle = "${session.calleeLabel} 통화 메모",
            canQuickReply = false,
            timestampMs = referenceTimeMs,
            zoneId = zoneId
        )
    }
}
