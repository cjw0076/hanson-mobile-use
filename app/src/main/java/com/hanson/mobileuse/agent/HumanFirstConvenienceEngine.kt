package com.hanson.mobileuse.agent

import android.content.Context
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.notification.InterceptedNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

// =============================================================================
// Shared Internal File & Security Guard Helpers
// =============================================================================

private object ConvenienceStorageHelper {

    fun writeAtomically(target: File, content: String) {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent ?: File("."), "${target.name}.tmp.${System.nanoTime()}")
        tempFile.writeText(content, StandardCharsets.UTF_8)
        if (tempFile.renameTo(target)) {
            return
        }
        try {
            Files.move(
                tempFile.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            try {
                Files.move(
                    tempFile.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                target.writeText(content, StandardCharsets.UTF_8)
                tempFile.delete()
            }
        }
    }
}

private object ConvenienceSecretGuard {
    private val CARD_16_SEPARATED_REGEX = Regex("(?<!\\d)(?:\\d{4}[-\\s]){3}\\d{4}(?!\\d)")
    private val CARD_16_PLAIN_REGEX = Regex("(?<!\\d)\\d{16}(?!\\d)")
    private val RRN_REGEX = Regex("(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)")
    private val PURE_OTP_DIGITS_REGEX = Regex("^\\s*\\d{4,8}\\s*$")
    private val CONTEXTUAL_OTP_REGEX = Regex(
        "(?i)(?:otp|인증번호|인증\\s*코드|보안코드|보안\\s*번호|확인코드|verification\\s*code|passcode|2fa)[\\s:：\\[\\]#-]*\\d{4,8}\\b"
    )
    private val TRAILING_OTP_REGEX = Regex(
        "(?i)(?<![\\d-])\\d{4,8}(?![\\d-]).{0,12}(?:otp|인증번호|인증코드|보안코드)"
    )
    private val GOOGLE_API_KEY_REGEX = Regex("AIza[0-9A-Za-z_\\-]{8,}")
    private val SK_KEY_REGEX = Regex("(?<![A-Za-z0-9_])sk-[A-Za-z0-9_\\-]{8,}")
    private val BEARER_TOKEN_REGEX = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]{8,}")
    private val GITHUB_OR_SLACK_TOKEN_REGEX = Regex("(?:ghp_[A-Za-z0-9]{12,}|xox[baprs]-[A-Za-z0-9\\-]{10,})")
    private val PASSWORD_KEYWORD_REGEX = Regex(
        "(?i)(?:비밀번호|패스워드|비번|핀번호|계좌\\s*비번|보안카드|password|passwd|passphrase|secret[_\\s-]?key|private[_\\s-]?key|access[_\\s-]?token|api[_\\s-]?key|cvv|cvc)"
    )

    fun containsSensitiveSecret(rawText: String, vault: BlindCredentialVault? = null): Boolean {
        if (rawText.isBlank()) return false
        if (vault != null) {
            val redacted = runCatching { vault.redactSensitiveText(rawText) }.getOrDefault(rawText)
            if (redacted != rawText) return true
        }
        return CARD_16_SEPARATED_REGEX.containsMatchIn(rawText) ||
            CARD_16_PLAIN_REGEX.containsMatchIn(rawText) ||
            RRN_REGEX.containsMatchIn(rawText) ||
            PURE_OTP_DIGITS_REGEX.matches(rawText) ||
            CONTEXTUAL_OTP_REGEX.containsMatchIn(rawText) ||
            TRAILING_OTP_REGEX.containsMatchIn(rawText) ||
            GOOGLE_API_KEY_REGEX.containsMatchIn(rawText) ||
            SK_KEY_REGEX.containsMatchIn(rawText) ||
            BEARER_TOKEN_REGEX.containsMatchIn(rawText) ||
            GITHUB_OR_SLACK_TOKEN_REGEX.containsMatchIn(rawText) ||
            PASSWORD_KEYWORD_REGEX.containsMatchIn(rawText)
    }
}

// =============================================================================
// 1. User Experience Mode (Human Everyday vs Studio Pro)
// =============================================================================

enum class UserExperienceMode(
    val id: String,
    val labelKo: String,
    val badgeKo: String
) {
    SIMPLE_HUMAN("human", "일상 모드", "일상"),
    STUDIO_PRO("studio", "스튜디오", "PRO");

    companion object {
        fun fromId(id: String?): UserExperienceMode {
            val clean = id?.trim() ?: return SIMPLE_HUMAN
            return entries.firstOrNull { it.id.equals(clean, ignoreCase = true) } ?: SIMPLE_HUMAN
        }
    }
}

// =============================================================================
// 2. Human Memory Bubbles Store (Pickle "Bubbles" Transparent Memory)
// =============================================================================

data class HumanMemoryBubble(
    val id: String,
    val emoji: String,
    val category: String,
    val factKo: String,
    val enabled: Boolean = true,
    val updatedAtMs: Long = System.currentTimeMillis()
)

class HumanMemoryBubblesStore @JvmOverloads constructor(
    private val storageFile: File
) {
    constructor(context: Context) : this(
        storageFile = File((context.applicationContext ?: context).filesDir, BUBBLES_FILE_NAME)
    )

    companion object {
        private const val BUBBLES_FILE_NAME = "hanson_memory_bubbles.json"
        private const val MAX_BUBBLES = 50
        private val EXPLICIT_LEADING_TRIGGERS = Regex(
            "^(?:꼭\\s*)?(?:기억해\\s*줘|기억해\\s*둬|기억해|외워\\s*둬|외워\\s*줘|메모해\\s*줘|메모해\\s*둬)\\s*[:：-]?\\s*"
        )
        private val EXPLICIT_TRAILING_TRIGGERS = Regex(
            "\\s*(?:꼭\\s*)?(?:기억해\\s*줘|기억해\\s*둬|외워\\s*둬|외워\\s*줘)\\s*[.!?~]*$"
        )
        private val PREFERENCE_DETECTION_PATTERNS = listOf(
            Regex("(?:기억해\\s*줘|기억해\\s*둬|외워\\s*둬|메모해\\s*줘)"),
            Regex("(?:내|제)\\s*취향은"),
            Regex("(?:집|우리\\s*집|회사)\\s*주소는"),
            Regex("선호\\s*(?:하는\\s*)?시간은"),
            Regex("(?:나는|난|저는|제가)\\s+.{2,64}?(?:선호해|선호해요|선호합니다|좋아해|좋아해요|좋아합니다|못\\s*먹어|못먹어|못\\s*먹어요|안\\s*먹어|안먹어요|즐겨\\s*마셔|자주\\s*시켜)"),
            Regex("(?:못\\s*먹어|못먹어|못\\s*먹어요|알레르기(?:가|는|라)?)")
        )
    }

    private val lock = Any()
    private val idCounter = AtomicInteger(0)
    private val bubbleList = mutableListOf<HumanMemoryBubble>()
    private val _bubbles = MutableStateFlow<List<HumanMemoryBubble>>(emptyList())
    val bubbles: StateFlow<List<HumanMemoryBubble>> = _bubbles.asStateFlow()

    init {
        loadFromDisk()
    }

    fun addBubble(
        factKo: String,
        category: String = "PREF",
        emoji: String = "🫧"
    ): HumanMemoryBubble? {
        val cleanFact = factKo.trim().replace(Regex("\\s+"), " ").take(160)
        if (cleanFact.isEmpty()) return null
        if (ConvenienceSecretGuard.containsSensitiveSecret(cleanFact)) return null

        val cleanCategory = category.trim().uppercase(Locale.ROOT).ifBlank { "PREF" }
        val cleanEmoji = emoji.trim().ifBlank { "🫧" }
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val existingIdx = bubbleList.indexOfFirst {
                it.factKo.equals(cleanFact, ignoreCase = true)
            }
            val result = if (existingIdx >= 0) {
                val existing = bubbleList.removeAt(existingIdx)
                existing.copy(
                    emoji = cleanEmoji,
                    category = cleanCategory,
                    factKo = cleanFact,
                    enabled = true,
                    updatedAtMs = now
                )
            } else {
                HumanMemoryBubble(
                    id = "bubble_${now}_${idCounter.incrementAndGet()}",
                    emoji = cleanEmoji,
                    category = cleanCategory,
                    factKo = cleanFact,
                    enabled = true,
                    updatedAtMs = now
                )
            }
            bubbleList.add(0, result)
            while (bubbleList.size > MAX_BUBBLES) {
                bubbleList.removeAt(bubbleList.lastIndex)
            }
            saveToDiskLocked()
            _bubbles.value = bubbleList.toList()
            return result
        }
    }

    fun toggleBubble(id: String, enabled: Boolean? = null): Boolean {
        synchronized(lock) {
            val idx = bubbleList.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val current = bubbleList[idx]
            val targetEnabled = enabled ?: !current.enabled
            bubbleList[idx] = current.copy(
                enabled = targetEnabled,
                updatedAtMs = System.currentTimeMillis()
            )
            saveToDiskLocked()
            _bubbles.value = bubbleList.toList()
            return true
        }
    }

    fun removeBubble(id: String): Boolean {
        synchronized(lock) {
            val removed = bubbleList.removeAll { it.id == id }
            if (removed) {
                saveToDiskLocked()
                _bubbles.value = bubbleList.toList()
            }
            return removed
        }
    }

    fun clearAll() {
        synchronized(lock) {
            bubbleList.clear()
            saveToDiskLocked()
            _bubbles.value = emptyList()
        }
    }

    /**
     * Merges LWW-resolved cloud/remote bubbles while preserving exact IDs, timestamps, and enabled flags.
     */
    fun upsertMergedBubbles(merged: List<HumanMemoryBubble>) {
        synchronized(lock) {
            val byId = LinkedHashMap<String, HumanMemoryBubble>(bubbleList.size + merged.size)
            for (existing in bubbleList) {
                if (existing.id.isNotBlank()) {
                    byId[existing.id] = existing
                }
            }
            for (incoming in merged) {
                val cleanId = incoming.id.trim().take(128)
                val cleanFact = incoming.factKo.trim().replace(Regex("\\s+"), " ").take(500)
                if (cleanId.isEmpty() || cleanFact.isEmpty()) continue
                if (ConvenienceSecretGuard.containsSensitiveSecret(cleanFact)) continue
                val normalized = HumanMemoryBubble(
                    id = cleanId,
                    emoji = incoming.emoji.trim().ifBlank { "🫧" }.take(32),
                    category = incoming.category.trim().uppercase(Locale.ROOT).ifBlank { "PREF" }.take(32),
                    factKo = cleanFact,
                    enabled = incoming.enabled,
                    updatedAtMs = incoming.updatedAtMs.coerceIn(0L, 253_402_300_799_999L)
                )
                val current = byId[cleanId]
                if (current == null || normalized.updatedAtMs >= current.updatedAtMs) {
                    byId[cleanId] = normalized
                }
            }
            val nextList = byId.values
                .sortedByDescending { it.updatedAtMs }
                .take(MAX_BUBBLES)
            bubbleList.clear()
            bubbleList.addAll(nextList)
            saveToDiskLocked()
            _bubbles.value = bubbleList.toList()
        }
    }

    fun seedDefaultsIfEmpty() {
        synchronized(lock) {
            if (bubbleList.isNotEmpty()) return
            val now = System.currentTimeMillis()
            bubbleList.addAll(
                listOf(
                    HumanMemoryBubble(
                        id = "seed_coffee",
                        emoji = "☕",
                        category = "FOOD",
                        factKo = "커피는 아이스 아메리카노를 주로 선호해요",
                        enabled = true,
                        updatedAtMs = now
                    ),
                    HumanMemoryBubble(
                        id = "seed_route",
                        emoji = "🚇",
                        category = "PLACE",
                        factKo = "이동 경로는 환승이 적은 편한 경로를 선호해요",
                        enabled = true,
                        updatedAtMs = now - 1L
                    ),
                    HumanMemoryBubble(
                        id = "seed_tone",
                        emoji = "💬",
                        category = "STYLE",
                        factKo = "답장이나 요약은 짧고 다정한 한국어로 정리해 주세요",
                        enabled = true,
                        updatedAtMs = now - 2L
                    )
                )
            )
            saveToDiskLocked()
            _bubbles.value = bubbleList.toList()
        }
    }

    fun extractAndLearnFromUserUtterance(
        utterance: String,
        vault: BlindCredentialVault? = null
    ): HumanMemoryBubble? {
        val trimmed = utterance.trim()
        if (trimmed.isEmpty()) return null
        if (ConvenienceSecretGuard.containsSensitiveSecret(trimmed, vault)) return null

        val matchesExplicitCue = PREFERENCE_DETECTION_PATTERNS.any { it.containsMatchIn(trimmed) }
        if (!matchesExplicitCue) return null

        val cleanedFact = trimmed
            .replace(EXPLICIT_LEADING_TRIGGERS, "")
            .replace(EXPLICIT_TRAILING_TRIGGERS, "")
            .trim()
        if (cleanedFact.length < 2) return null
        if (ConvenienceSecretGuard.containsSensitiveSecret(cleanedFact, vault)) return null

        val (category, emoji) = inferCategoryAndEmoji(cleanedFact)
        return addBubble(factKo = cleanedFact, category = category, emoji = emoji)
    }

    fun toPromptContextBlock(
        vault: BlindCredentialVault? = null,
        maxItems: Int = 8
    ): String {
        if (maxItems <= 0) return ""
        val active = synchronized(lock) {
            bubbleList.filter { it.enabled }.take(maxItems)
        }
        if (active.isEmpty()) return ""

        val lines = active.mapNotNull { b ->
            val safeFact = vault?.let { runCatching { it.redactSensitiveText(b.factKo) }.getOrDefault(b.factKo) }
                ?: b.factKo
            if (safeFact.isBlank() || ConvenienceSecretGuard.containsSensitiveSecret(safeFact)) {
                null
            } else {
                "- ${b.emoji} [${b.category}] $safeFact"
            }
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("[사용자 기억 버블 (Active Memory Bubbles)]")
            lines.forEach { appendLine(it) }
        }.trim()
    }

    private fun inferCategoryAndEmoji(fact: String): Pair<String, String> {
        val lower = fact.lowercase(Locale.ROOT)
        return when {
            lower.contains("커피") || lower.contains("아메리카노") || lower.contains("라떼") -> "FOOD" to "☕"
            lower.contains("못 먹") || lower.contains("못먹") || lower.contains("안 먹") ||
                lower.contains("알레르기") || lower.contains("음식") || lower.contains("매운") ||
                lower.contains("고수") || lower.contains("메뉴") -> "FOOD" to "🍽️"
            lower.contains("집 주소") || lower.contains("주소") || lower.contains("동네") ||
                lower.contains("역") || lower.contains("회사") -> "PLACE" to "🏠"
            lower.contains("시간") || lower.contains("오전") || lower.contains("오후") ||
                lower.contains("저녁") || lower.contains("주말") -> "TIME" to "⏰"
            else -> "PREF" to "🫧"
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            bubbleList.clear()
            if (!storageFile.isFile) {
                _bubbles.value = emptyList()
                return
            }
            runCatching {
                val root = JSONObject(storageFile.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("bubbles") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val fact = obj.optString("factKo", "").trim()
                    if (id.isEmpty() || fact.isEmpty()) continue
                    if (ConvenienceSecretGuard.containsSensitiveSecret(fact)) continue
                    bubbleList.add(
                        HumanMemoryBubble(
                            id = id,
                            emoji = obj.optString("emoji", "🫧").ifBlank { "🫧" },
                            category = obj.optString("category", "PREF").ifBlank { "PREF" },
                            factKo = fact,
                            enabled = obj.optBoolean("enabled", true),
                            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
            _bubbles.value = bubbleList.toList()
        }
    }

    private fun saveToDiskLocked() {
        val arr = JSONArray()
        bubbleList.forEach { b ->
            arr.put(
                JSONObject().apply {
                    put("id", b.id)
                    put("emoji", b.emoji)
                    put("category", b.category)
                    put("factKo", b.factKo)
                    put("enabled", b.enabled)
                    put("updatedAtMs", b.updatedAtMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("bubbles", arr)
        }
        ConvenienceStorageHelper.writeAtomically(storageFile, root.toString(2))
    }
}

// =============================================================================
// 3. Proactive Daily Brief Engine
// =============================================================================

data class DailyBriefAction(
    val labelKo: String,
    val goalPrompt: String,
    val badgeKo: String
)

data class DailyBriefSnapshot(
    val headlineKo: String,
    val summaryKo: String,
    val highlights: List<String>,
    val quickActions: List<DailyBriefAction>
)

object ProactiveDailyBriefEngine {

    fun buildBrief(
        hourOfDay: Int,
        notifications: List<InterceptedNotification>,
        watcherRules: List<WatcherRule>,
        scratchpadEntries: List<ScratchpadEntry>,
        bubbles: List<HumanMemoryBubble>,
        pinnedRoutines: List<PersonalRoutine>
    ): DailyBriefSnapshot {
        val normalizedHour = ((hourOfDay % 24) + 24) % 24
        val headlineKo = when (normalizedHour) {
            in 5..10 -> "좋은 아침이에요 · 오늘의 시작 브리핑"
            in 11..13 -> "점심 무렵이에요 · 놓친 소식과 오후 브리핑"
            in 14..17 -> "오후 브리핑 · 지금 챙겨볼 항목"
            in 18..21 -> "저녁 브리핑 · 하루 정리와 내일 준비"
            else -> "편안한 밤이에요 · 취침 전 한눈에 보기"
        }

        val sortedNotifications = notifications.sortedByDescending { it.timestampMs }
        val replyableUnreplied = sortedNotifications.filter {
            it.canQuickReply && it.repliedText.isNullOrBlank()
        }
        val activeWatchers = watcherRules.filter { it.enabled }
        val activeBubbles = bubbles.filter { it.enabled }
        val pinned = pinnedRoutines.filter { it.pinned }

        val highlights = mutableListOf<String>()
        if (sortedNotifications.isNotEmpty()) {
            val latest = sortedNotifications.first()
            highlights.add(
                "새 알림 ${sortedNotifications.size}건 (빠른 답장 가능 ${replyableUnreplied.size}건) · 최근: ${latest.appLabel} '${latest.sender}'"
            )
        } else {
            highlights.add("새로 확인해야 할 알림은 없어요")
        }

        if (activeWatchers.isNotEmpty()) {
            val names = activeWatchers.take(2).joinToString(", ") { it.title }
            highlights.add("능동 감시 ${activeWatchers.size}건 작동 중 ($names)")
        }

        if (scratchpadEntries.isNotEmpty()) {
            val latestNote = scratchpadEntries.maxByOrNull { it.updatedAtMs } ?: scratchpadEntries.first()
            highlights.add("저장된 작업 메모 ${scratchpadEntries.size}건 · 최근: ${latestNote.key}")
        }

        if (activeBubbles.isNotEmpty()) {
            val firstBubble = activeBubbles.first()
            highlights.add("기억 중인 취향 ${activeBubbles.size}개 · ${firstBubble.emoji} ${firstBubble.factKo.take(32)}")
        }

        if (pinned.isNotEmpty()) {
            val names = pinned.take(2).joinToString(", ") { "${it.emoji} ${it.titleKo}" }
            highlights.add("고정 루틴 ${pinned.size}개 준비됨 ($names)")
        }

        val summaryParts = buildList {
            if (sortedNotifications.isNotEmpty()) {
                add("확인할 알림 ${sortedNotifications.size}건")
            }
            if (activeWatchers.isNotEmpty()) {
                add("작동 중인 감시 ${activeWatchers.size}건")
            }
            if (scratchpadEntries.isNotEmpty()) {
                add("작업 메모 ${scratchpadEntries.size}건")
            }
            if (pinned.isNotEmpty()) {
                add("자주 쓰는 루틴 ${pinned.size}개")
            }
        }
        val summaryKo = if (summaryParts.isEmpty()) {
            when (normalizedHour) {
                in 5..11 -> "지금은 막힌 일 없이 조용해요. 오늘 일정이나 자주 쓰는 루틴을 한 번에 시작해 보세요."
                in 12..17 -> "아직 급한 알림이나 메모는 없어요. 필요한 작업이 있으면 편하게 말씀해 주세요."
                else -> "오늘 남은 중요한 알림은 없어요. 내일 기상 알람이나 수면 준비를 도와드릴까요?"
            }
        } else {
            "현재 ${summaryParts.joinToString(", ")}이 준비되어 있어요. 아래 원탭 버튼으로 바로 이어서 진행할 수 있어요."
        }

        val actions = mutableListOf<DailyBriefAction>()
        replyableUnreplied.firstOrNull()?.let { n ->
            actions.add(
                DailyBriefAction(
                    labelKo = "${n.sender}님에게 답장",
                    goalPrompt = "최근 수신된 ${n.appLabel} '${n.sender}' 알림 내용을 확인하고 적절한 답장을 준비해줘",
                    badgeKo = "알림"
                )
            )
        }

        pinned.take(2).forEach { routine ->
            actions.add(
                DailyBriefAction(
                    labelKo = "${routine.emoji} ${routine.titleKo}",
                    goalPrompt = routine.goalPrompt,
                    badgeKo = "루틴"
                )
            )
        }

        when (normalizedHour) {
            in 5..11 -> {
                actions.add(
                    DailyBriefAction(
                        labelKo = "오늘 일정 요약",
                        goalPrompt = "오늘 캘린더 일정을 조회해서 한눈에 요약해줘",
                        badgeKo = "아침"
                    )
                )
            }
            in 12..17 -> {
                actions.add(
                    DailyBriefAction(
                        labelKo = "알림·메모 한눈에 보기",
                        goalPrompt = "최근 알림과 저장된 작업 메모를 핵심만 정리해서 보여줘",
                        badgeKo = "정리"
                    )
                )
            }
            else -> {
                actions.add(
                    DailyBriefAction(
                        labelKo = "취침 루틴 준비",
                        goalPrompt = "내일 오전 7시 30분 알람을 설정하고 미디어 볼륨을 18로 낮춰줘",
                        badgeKo = "취침"
                    )
                )
            }
        }

        if (activeWatchers.isNotEmpty() && actions.size < 4) {
            actions.add(
                DailyBriefAction(
                    labelKo = "감시 상태 확인",
                    goalPrompt = "등록된 능동 감시 규칙 현황과 최근 감지 내역을 알려줘",
                    badgeKo = "감시"
                )
            )
        }

        return DailyBriefSnapshot(
            headlineKo = headlineKo,
            summaryKo = summaryKo,
            highlights = highlights,
            quickActions = actions.distinctBy { it.labelKo }.take(4)
        )
    }
}

// =============================================================================
// 4. Smart Clipboard & Share Intent Detector
// =============================================================================

enum class QuickIntentKind {
    URL_SUMMARY,
    SCHEDULE_A2A,
    PLACE_OR_MAP,
    PHONE_CONCIERGE,
    TRANSLATE_REPLY,
    DIRECT_COMMAND
}

data class ClipboardQuickSuggestion(
    val kind: QuickIntentKind,
    val badgeKo: String,
    val titleKo: String,
    val actionPrompt: String,
    val previewText: String
)

object SmartClipboardAndShareIntentDetector {

    private const val MAX_ANALYZE_CHARS = 2000
    private val URL_REGEX = Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+")
    private val PHONE_REGEX = Regex(
        "(?<!\\d)(?:(?:01[016789]|02|0[3-6][1-5]|070|050\\d?)[-\\s]?\\d{3,4}[-\\s]?\\d{4}|1[568]\\d{2}[-\\s]?\\d{4})(?!\\d)"
    )
    private val SCHEDULE_DATE_OR_TIME_REGEX = Regex(
        "(?:오늘|내일|모레|글피|이번\\s*주|다음\\s*주|(?:월|화|수|목|금|토|일)요일|\\d{1,2}월\\s*\\d{1,2}일|\\d{1,2}:\\d{2}|(?:오전|오후|아침|점심|저녁)\\s*\\d{1,2}시|\\d{1,2}시(?:\\s*\\d{1,2}분)?)"
    )
    private val SCHEDULE_MEETUP_CUE_REGEX = Regex(
        "(?:만나|약속|미팅|회의|일정|시간\\s*(?:어때|괜찮|되|돼|가능)|보실래|볼까|캘린더|예약)"
    )
    private val PLACE_ADDRESS_REGEX = Regex(
        "(?:(?:서울|부산|대구|인천|광주|대전|울산|세종|경기|강원|충북|충남|전북|전남|경북|경남|제주)(?:특별시|광역시|특별자치시|특별자치도|도)?\\s+[가-힣0-9-]+(?:시|군|구|동|로|길|역)?|[가-힣]{1,8}(?:시|군|구)\\s+[가-힣0-9-]+(?:동|읍|면|로|길)\\s*\\d*|[가-힣A-Za-z0-9]{1,12}역(?:\\s*\\d+번\\s*출구)?)"
    )
    private val PLACE_HINT_REGEX = Regex(
        "(?:주소|위치|장소|오시는\\s*길|가는\\s*길|출구|맛집|카페|식당)\\s*[:：]?"
    )
    private val DIRECT_COMMAND_REGEX = Regex(
        "(?:해\\s*줘|해\\s*주세요|해\\s*봐|해\\s*볼래|열어\\s*줘|알려\\s*줘|찾아\\s*줘|검색해\\s*줘|보내\\s*줘|맞춰\\s*줘|틀어\\s*줘|요약해\\s*줘|예약해\\s*줘|켜\\s*줘|꺼\\s*줘)\\s*[.!?~]*$"
    )
    private val ENGLISH_MULTI_WORD_REGEX = Regex("[A-Za-z]{2,}(?:\\s+[A-Za-z]{2,})+")
    private val JAPANESE_REGEX = Regex("[\\u3040-\\u30FF]{2,}")
    private val CJK_REGEX = Regex("[\\u4E00-\\u9FFF]{2,}")

    fun analyzeText(
        rawText: String,
        vault: BlindCredentialVault? = null
    ): List<ClipboardQuickSuggestion> {
        if (rawText.isBlank()) return emptyList()
        if (rawText.length > MAX_ANALYZE_CHARS) return emptyList()
        if (ConvenienceSecretGuard.containsSensitiveSecret(rawText, vault)) return emptyList()

        val trimmed = rawText.trim()
        val preview = trimmed.replace(Regex("\\s+"), " ").take(80)
        val suggestions = mutableListOf<ClipboardQuickSuggestion>()

        // 1. URL summary
        URL_REGEX.find(trimmed)?.let { match ->
            val rawUrl = match.value.trimEnd('.', ',', ')', ']', '"', '\'')
            val normalizedUrl = if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl"
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.URL_SUMMARY,
                    badgeKo = "링크 요약",
                    titleKo = "웹페이지 핵심 내용 요약하기",
                    actionPrompt = "브라우저로 $normalizedUrl 핵심 내용 요약해서 보여줘",
                    previewText = preview
                )
            )
        }

        // 2. Korean schedule / meetup A2A
        val hasDateOrTime = SCHEDULE_DATE_OR_TIME_REGEX.containsMatchIn(trimmed)
        val hasMeetupCue = SCHEDULE_MEETUP_CUE_REGEX.containsMatchIn(trimmed)
        if ((hasDateOrTime && hasMeetupCue) ||
            (hasDateOrTime && (trimmed.contains("시") || trimmed.contains(":")) && trimmed.length <= 160)
        ) {
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.SCHEDULE_A2A,
                    badgeKo = "일정 조율",
                    titleKo = "내 일정 확인 및 약속 시간 잡기",
                    actionPrompt = "다음 내용을 보고 내 캘린더 일정과 비교해 약속 조율 및 등록을 도와줘: \"$preview\"",
                    previewText = preview
                )
            )
        }

        // 3. Place or address -> Map lookup
        val placeMatch = PLACE_ADDRESS_REGEX.find(trimmed)
        if (placeMatch != null || (PLACE_HINT_REGEX.containsMatchIn(trimmed) && trimmed.length <= 140)) {
            val placeTarget = placeMatch?.value?.trim() ?: preview
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.PLACE_OR_MAP,
                    badgeKo = "지도·길찾기",
                    titleKo = "지도에서 위치·가는 길 찾기",
                    actionPrompt = "네이버 지도로 '$placeTarget' 위치와 가는 길을 찾아줘",
                    previewText = preview
                )
            )
        }

        // 4. Phone number -> Phone concierge
        PHONE_REGEX.find(trimmed)?.let { phoneMatch ->
            val phone = phoneMatch.value.trim()
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.PHONE_CONCIERGE,
                    badgeKo = "전화 비서",
                    titleKo = "전화·예약 비서로 연결 준비하기",
                    actionPrompt = "전화 비서로 '$phone' 번호에 걸 용건과 통화 멘트를 준비해줘",
                    previewText = preview
                )
            )
        }

        // 5. Foreign-language prose (after stripping URLs & phone numbers)
        val withoutUrlsAndPhones = trimmed
            .replace(URL_REGEX, " ")
            .replace(PHONE_REGEX, " ")
            .trim()
        if (looksLikeForeignLanguageText(withoutUrlsAndPhones)) {
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.TRANSLATE_REPLY,
                    badgeKo = "번역·답장",
                    titleKo = "한국어로 번역하고 답장 초안 쓰기",
                    actionPrompt = "다음 외국어 내용을 자연스러운 한국어로 번역하고 알맞은 답장 초안도 작성해줘: \"$preview\"",
                    previewText = preview
                )
            )
        }

        // 6. Direct Korean command ("~해줘")
        if (trimmed.length <= 120 && DIRECT_COMMAND_REGEX.containsMatchIn(trimmed)) {
            suggestions.add(
                ClipboardQuickSuggestion(
                    kind = QuickIntentKind.DIRECT_COMMAND,
                    badgeKo = "바로 실행",
                    titleKo = "복사한 요청 바로 실행하기",
                    actionPrompt = trimmed,
                    previewText = preview
                )
            )
        }

        return suggestions.distinctBy { it.kind }
    }

    private fun looksLikeForeignLanguageText(text: String): Boolean {
        if (text.length < 4) return false
        if (JAPANESE_REGEX.containsMatchIn(text) || CJK_REGEX.containsMatchIn(text)) {
            return true
        }
        val latinCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val hangulCount = text.count { it in '\uAC00'..'\uD7A3' }
        if (latinCount < 6) return false
        val hasMultiWordEnglish = ENGLISH_MULTI_WORD_REGEX.containsMatchIn(text)
        return hasMultiWordEnglish && (hangulCount == 0 || latinCount > hangulCount * 2)
    }
}

// =============================================================================
// 5. App-Specific Tip Bank + ACL 2026 D-Artemis Deliberative Aligner (TAC + ACA)
// =============================================================================

object AppSpecificTipBank {

    fun getTipForPackageOrGoal(activePackage: String, goal: String): String {
        val pkgLower = activePackage.trim().lowercase(Locale.ROOT)
        val goalLower = goal.trim().lowercase(Locale.ROOT)

        val pkgTip = matchTipByPackage(pkgLower)
        if (pkgTip.isNotEmpty()) return pkgTip

        return matchTipByGoal(goalLower)
    }

    private fun matchTipByPackage(pkg: String): String = when {
        pkg.isEmpty() -> ""
        pkg.contains("com.kakao.talk") ->
            "카카오톡 팁: 상단 돋보기(검색) 아이콘으로 친구·대화방 이름을 찾은 뒤, 채팅방 하단 메시지 입력창을 눌러 전송하세요."
        pkg.contains("com.kakao.taxi") ->
            "카카오 T 팁: 출도착지가 맞는지 확인하고, 호출·결제 확정 버튼 직전에는 금액과 차량 종류를 사용자에게 꼭 확인받으세요."
        pkg.contains("com.sampleapp") || pkg.contains("baemin") ->
            "배달의민족 팁: 첫 화면 배너·혜택 팝업이 검색창을 가릴 수 있으니 닫기 후 상단 검색바에 메뉴를 입력하고, 장바구니·결제 단계에서는 멈추세요."
        pkg.contains("com.coupang.mobile.eats") ->
            "쿠팡이츠 팁: 배달 주소 배지가 맞는지 먼저 확인한 뒤 검색 탭에서 가게나 메뉴를 찾고, 결제 전 최종 주문서에서 멈추세요."
        pkg.contains("com.coupang.mobile") ->
            "쿠팡 팁: 상단 검색창에 상품명을 입력할 때 자동완성 추천을 활용하고, 바로구매·결제하기 버튼은 사용자 승인 후 진행하세요."
        pkg.contains("com.nhn.android.nmap") ->
            "네이버 지도 팁: 상단 검색창에 장소·주소를 입력한 뒤 '도착'과 '길찾기' 버튼을 눌러 대중교통·도보·자동차 경로를 비교하세요."
        pkg.contains("com.nhn.android.search") ->
            "네이버 팁: 홈 상단 그린닷/검색창을 탭해 검색어를 입력하고, 요약이 필요하면 뉴스·블로그 본문 상단을 확인하세요."
        pkg.contains("viva.republica.toss") ->
            "토스 팁: 송금·결제 화면에는 보안키패드나 본인인증이 나타날 수 있으므로, 금액·받는 사람을 확인한 뒤 인증 단계에서는 사용자에게 넘기세요."
        pkg.contains("com.google.android.youtube") ->
            "유튜브 팁: 상단 돋보기 검색 아이콘으로 제목을 검색한 뒤 광고가 아닌 본 영상 카드를 선택하세요. 하단 미니플레이어가 겹치면 닫거나 확장하세요."
        pkg.contains("catchtable") ->
            "캐치테이블 팁: 매장 검색 후 날짜·인원·시간 슬롯을 차례로 선택하고, 예약금 결제나 최종 예약 확정 직전에 사용자 확인을 받으세요."
        pkg.contains("com.korail.talk") ->
            "코레일톡 팁: 출발역·도착역·날짜·인원을 정확히 맞춘 뒤 열차 조회를 누르고, 매진 구간은 잔여좌석 감시 또는 선택 화면에서 확인하세요."
        pkg.contains("com.android.settings") ->
            "설정 팁: 상단 검색 돋보기에 원하는 항목 이름(예: 와이파이, 블루투스, 디스플레이)을 입력하면 하위 메뉴를 스크롤 없이 바로 찾을 수 있어요."
        pkg.contains("clock") || pkg.contains("alarm") || pkg.contains("calendar") ->
            "시계·캘린더 팁: 알람·타이머·일정 등록은 가능하면 OS 전용 도구(set_alarm, set_timer, manage_calendar)로 한 번에 처리하면 훨씬 빠릅니다."
        else -> ""
    }

    private fun matchTipByGoal(goal: String): String = when {
        goal.isEmpty() -> ""
        goal.contains("카카오톡") || goal.contains("카톡") ->
            matchTipByPackage("com.kakao.talk")
        goal.contains("카카오 t") || goal.contains("카카오t") || goal.contains("카카오택시") || goal.contains("택시") ->
            matchTipByPackage("com.kakao.taxi")
        goal.contains("배달의민족") || goal.contains("배달의 민족") || goal.contains("배민") ->
            matchTipByPackage("com.sampleapp")
        goal.contains("쿠팡이츠") || goal.contains("쿠팡 이츠") ->
            matchTipByPackage("com.coupang.mobile.eats")
        goal.contains("쿠팡") ->
            matchTipByPackage("com.coupang.mobile")
        goal.contains("네이버 지도") || goal.contains("네이버지도") || goal.contains("길찾기") ->
            matchTipByPackage("com.nhn.android.nmap")
        goal.contains("네이버") ->
            matchTipByPackage("com.nhn.android.search")
        goal.contains("토스") ->
            matchTipByPackage("viva.republica.toss")
        goal.contains("유튜브") || goal.contains("youtube") ->
            matchTipByPackage("com.google.android.youtube")
        goal.contains("캐치테이블") || goal.contains("catchtable") ->
            matchTipByPackage("catchtable")
        goal.contains("코레일") || goal.contains("ktx") || goal.contains("기차표") ->
            matchTipByPackage("com.korail.talk")
        goal.contains("설정") ->
            matchTipByPackage("com.android.settings")
        goal.contains("알람") || goal.contains("타이머") || goal.contains("시계") || goal.contains("캘린더") || goal.contains("일정") ->
            matchTipByPackage("clock")
        else -> ""
    }
}

data class ArtemisAlignmentResult(
    val toolName: String,
    val alignedArgs: JSONObject,
    val wasCorrected: Boolean = false,
    val correctionReasonKo: String = "",
    val appTipKo: String = ""
)

object ArtemisDeliberativeAligner {

    private const val HANSON_PACKAGE_PREFIX = "com.hanson.mobileuse"
    private val QUOTED_LABEL_REGEX = Regex("['\"‘“]([^'\"‘“’”]{1,40})['\"’”]")
    private val SUFFIX_LABEL_REGEX = Regex(
        "((?:[가-힣A-Za-z0-9_+\\-·]{1,20}\\s+)?[가-힣A-Za-z0-9_+\\-·]{1,20})\\s*(?:버튼|탭|아이콘|메뉴|항목)"
    )
    private val SEARCH_OR_CHAT_HINTS = listOf(
        "검색", "search", "query", "입력", "메시지", "채팅", "chat", "message", "msg", "comment", "댓글", "url", "주소"
    )
    private val PARTICLE_SUFFIX_REGEX = Regex("(?:을|를|이|가|은|는|의|에|로|으로)$")
    private val LEADING_NOISE_WORDS = setOf(
        "이제", "먼저", "일단", "상단", "상단의", "하단", "하단의", "오른쪽", "왼쪽", "화면의", "해당", "그", "이", "저", "다음에"
    )

    fun alignBeforeExecution(
        toolName: String,
        args: JSONObject,
        thoughtOrReply: String,
        snapshot: AtomicSnapshot?,
        iteration: Int
    ): ArtemisAlignmentResult {
        val clonedArgs = JSONObject(args.toString())
        val appTip = AppSpecificTipBank.getTipForPackageOrGoal(
            activePackage = snapshot?.activePackage.orEmpty(),
            goal = thoughtOrReply
        )

        // (c) Step-1 premature press_key("HOME") escape guard inside non-Hanson target app
        if (iteration == 1 &&
            toolName == "press_key" &&
            clonedArgs.optString("key", "").trim().equals("HOME", ignoreCase = true) &&
            snapshot != null &&
            snapshot.activePackage.isNotBlank() &&
            !snapshot.activePackage.startsWith(HANSON_PACKAGE_PREFIX)
        ) {
            val waitArgs = JSONObject().apply {
                put("duration_ms", 600)
                put("time_in_ms", 600)
            }
            return ArtemisAlignmentResult(
                toolName = "wait_for_delay",
                alignedArgs = waitArgs,
                wasCorrected = true,
                correctionReasonKo = "첫 단계에서 현재 앱(${snapshot.activePackage})을 바로 벗어나지 않도록 잠시 대기 후 화면을 다시 확인해요.",
                appTipKo = appTip
            )
        }

        if (snapshot == null || snapshot.indexedElements.isEmpty()) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = clonedArgs,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        // (a) ACA unfocused input_text auto-bind to best editable node
        if (toolName == "input_text") {
            return alignInputText(toolName, clonedArgs, snapshot, appTip)
        }

        // (b) TAC + ACA semantic click rebind
        if (toolName == "click" || toolName == "long_press") {
            return alignSemanticClick(toolName, clonedArgs, thoughtOrReply, snapshot, appTip)
        }

        return ArtemisAlignmentResult(
            toolName = toolName,
            alignedArgs = clonedArgs,
            wasCorrected = false,
            appTipKo = appTip
        )
    }

    private fun alignInputText(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot,
        appTip: String
    ): ArtemisAlignmentResult {
        val hasIndex = args.has("element_index")
        val rawIndex = if (hasIndex) args.optInt("element_index", -1) else -1
        val currentEl = if (hasIndex && rawIndex >= 0) {
            snapshot.indexedElements.firstOrNull { it.index == rawIndex }
        } else null

        val currentIsValidEditable = currentEl != null &&
            currentEl.editable &&
            currentEl.enabled &&
            !isOwnElement(currentEl)

        if (currentIsValidEditable) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = args,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        val bestEditable = snapshot.indexedElements
            .filter { el ->
                el.enabled &&
                    !isOwnElement(el) &&
                    (el.editable || el.shortClass.contains("EditText", ignoreCase = true))
            }
            .sortedWith(
                compareByDescending<IndexedUiElement> { it.editable }
                    .thenByDescending { it.focused }
                    .thenByDescending { hasSearchOrChatHint(it) }
                    .thenBy { it.top }
                    .thenBy { it.index }
            )
            .firstOrNull()

        if (bestEditable == null) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = args,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        args.put("element_index", bestEditable.index)
        args.put("norm_x", bestEditable.normX)
        args.put("norm_y", bestEditable.normY)
        return ArtemisAlignmentResult(
            toolName = toolName,
            alignedArgs = args,
            wasCorrected = true,
            correctionReasonKo = "입력할 수 있는 입력창 [${bestEditable.index}]을 자동으로 찾아 연결했어요.",
            appTipKo = appTip
        )
    }

    private fun alignSemanticClick(
        toolName: String,
        args: JSONObject,
        thoughtOrReply: String,
        snapshot: AtomicSnapshot,
        appTip: String
    ): ArtemisAlignmentResult {
        val candidateQueries = extractClickLabelQueries(thoughtOrReply)
        if (candidateQueries.isEmpty()) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = args,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        val hasIndex = args.has("element_index")
        val rawIndex = if (hasIndex) args.optInt("element_index", -1) else -1
        val selectedEl = if (hasIndex && rawIndex >= 0) {
            snapshot.indexedElements.firstOrNull { it.index == rawIndex }
        } else null

        val selectedIsCleanMatch = selectedEl != null &&
            selectedEl.enabled &&
            !isOwnElement(selectedEl) &&
            candidateQueries.any { q -> scoreElementAgainstQuery(selectedEl, q) >= 0.68 }

        if (selectedIsCleanMatch) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = args,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        var bestCandidate: IndexedUiElement? = null
        var bestQuery = ""
        var bestScore = 0.0

        for (query in candidateQueries) {
            for (el in snapshot.indexedElements) {
                if (!el.enabled || !el.clickable || isOwnElement(el)) continue
                val score = scoreElementAgainstQuery(el, query)
                if (score >= 0.68 && score > bestScore) {
                    bestScore = score
                    bestCandidate = el
                    bestQuery = query
                }
            }
        }

        if (bestCandidate == null || bestCandidate.index == rawIndex) {
            return ArtemisAlignmentResult(
                toolName = toolName,
                alignedArgs = args,
                wasCorrected = false,
                appTipKo = appTip
            )
        }

        args.put("element_index", bestCandidate.index)
        args.put("norm_x", bestCandidate.normX)
        args.put("norm_y", bestCandidate.normY)
        if (args.optString("target_description", "").isBlank()) {
            val bestLabel = bestCandidate.text.trim()
                .ifBlank { bestCandidate.contentDesc.trim() }
                .ifBlank { bestQuery }
            args.put("target_description", bestLabel)
        }

        return ArtemisAlignmentResult(
            toolName = toolName,
            alignedArgs = args,
            wasCorrected = true,
            correctionReasonKo = "설명된 '$bestQuery' 요소 [${bestCandidate.index}]로 터치 대상을 정확히 맞췄어요.",
            appTipKo = appTip
        )
    }

    private fun extractClickLabelQueries(thoughtOrReply: String): List<String> {
        if (thoughtOrReply.isBlank()) return emptyList()
        val queries = mutableListOf<String>()

        QUOTED_LABEL_REGEX.findAll(thoughtOrReply).forEach { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty()) queries.add(q)
        }
        SUFFIX_LABEL_REGEX.findAll(thoughtOrReply).forEach { m ->
            val raw = m.groupValues[1].trim()
            if (raw.isNotEmpty()) {
                val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                val strippedTokens = if (tokens.size >= 2 && tokens.first() in LEADING_NOISE_WORDS) {
                    tokens.drop(1)
                } else {
                    tokens
                }
                val phrase = strippedTokens.joinToString(" ")
                if (phrase.isNotEmpty()) {
                    queries.add(phrase)
                    val withoutParticle = phrase.replace(PARTICLE_SUFFIX_REGEX, "").trim()
                    if (withoutParticle.isNotEmpty() && withoutParticle != phrase) {
                        queries.add(withoutParticle)
                    }
                }
                val lastToken = strippedTokens.lastOrNull().orEmpty()
                if (lastToken.isNotEmpty() && lastToken != phrase) {
                    queries.add(lastToken)
                    val lastNoParticle = lastToken.replace(PARTICLE_SUFFIX_REGEX, "").trim()
                    if (lastNoParticle.isNotEmpty() && lastNoParticle != lastToken) {
                        queries.add(lastNoParticle)
                    }
                }
            }
        }
        return queries.distinct()
    }

    private fun scoreElementAgainstQuery(el: IndexedUiElement, query: String): Double {
        val normQ = normalizeUiText(query)
        if (normQ.isEmpty()) return 0.0

        val candidates = listOf(
            el.text.trim(),
            el.contentDesc.trim(),
            el.resourceId.substringAfterLast('/').trim()
        ).filter { it.isNotEmpty() }

        var best = 0.0
        for ((idx, label) in candidates.withIndex()) {
            val normL = normalizeUiText(label)
            if (normL.isEmpty()) continue
            val weightPenalty = if (idx == 2) 0.12 else 0.0
            val baseScore = when {
                normL == normQ -> 1.0
                normL.contains(normQ) -> 0.90
                normQ.contains(normL) && normL.length >= 2 -> 0.84
                normQ.length >= 2 && normL.length >= 2 ->
                    IntentDecomposerAndRouter.computeQueryToElementMatchScore(query, label)
                else -> 0.0
            }
            val adjusted = (baseScore - weightPenalty).coerceAtLeast(0.0)
            if (adjusted > best) best = adjusted
        }
        return best
    }

    private fun normalizeUiText(raw: String): String =
        raw.lowercase(Locale.ROOT)
            .replace(Regex("[\\s'\"`·•,\\-_.:;/\\\\()\\[\\]{}!?~]+"), "")

    private fun hasSearchOrChatHint(el: IndexedUiElement): Boolean {
        val combined = "${el.resourceId} ${el.contentDesc} ${el.text}".lowercase(Locale.ROOT)
        return SEARCH_OR_CHAT_HINTS.any { combined.contains(it) }
    }

    private fun isOwnElement(el: IndexedUiElement): Boolean {
        return el.packageName.startsWith(HANSON_PACKAGE_PREFIX) ||
            IntentDecomposerAndRouter.isHansonOwnElement(el)
    }
}

// =============================================================================
// 6. Human Step Translator (Zero Technical Jargon)
// =============================================================================

object HumanStepTranslator {

    private val NORM_COORD_REGEX = Regex(
        "(?i)\\bnorm(?:X|Y|_x|_y)?\\s*[=:]\\s*(?:\\[[^\\]]*\\]|\\([^)]*\\)|-?\\d+(?:\\.\\d+)?)\\s*,?"
    )
    private val PX_COORD_REGEX = Regex("(?i)\\bpx\\s*[=:]\\s*\\([^)]*\\)")
    private val BOUNDS_REGEX = Regex("(?i)\\bbounds\\s*[=:]\\s*(?:\\[[0-9,\\s-]+\\])+")
    private val FLAGS_REGEX = Regex("(?i)\\bflags\\s*[=:]\\s*\\{[^}]*\\}")
    private val ID_TAG_REGEX = Regex("(?i)\\bid\\s*=\\s*\"[^\"]*\"")
    private val STACK_TRACE_AT_REGEX = Regex("(?m)(?:^|\\s)at\\s+[A-Za-z0-9_.$<>]+\\([^)]*\\)")
    private val CAUSED_BY_REGEX = Regex("(?m)^\\s*Caused by:.*$")
    private val FILE_LINE_REGEX = Regex("\\b[A-Za-z0-9_$]+\\.(?:kt|java):\\d+(?!\\d)")
    private val EXCEPTION_CLASS_REGEX = Regex(
        "(?:java|javax|android|androidx|kotlin|kotlinx)\\.[A-Za-z0-9_.]+(?:Exception|Error|Throwable)(?::\\s*)?"
    )

    fun toPlainKoreanStep(
        toolName: String,
        args: JSONObject,
        snapshot: AtomicSnapshot? = null,
        rawSummary: String = ""
    ): String {
        val targetLabel = args.optString("target_description", "")
            .ifBlank { args.optString("description", "") }
            .trim()
            .ifBlank { resolveElementLabel(args, snapshot) }

        val translated = when (toolName) {
            "click" -> {
                if (targetLabel.isNotBlank()) "'${targetLabel.take(28)}' 버튼을 눌렀어요"
                else "화면을 가볍게 터치했어요"
            }
            "long_press" -> {
                if (targetLabel.isNotBlank()) "'${targetLabel.take(28)}' 항목을 길게 눌렀어요"
                else "화면 항목을 길게 눌렀어요"
            }
            "input_text" -> {
                val txt = args.optString("text", "").trim().take(24)
                if (txt.isNotEmpty()) "입력창에 '$txt' 내용을 적었어요"
                else "입력창에 내용을 입력했어요"
            }
            "swipe" -> {
                when (args.optString("direction", "up").trim().lowercase(Locale.ROOT)) {
                    "up" -> "화면을 아래로 스크롤했어요"
                    "down" -> "화면을 위로 스크롤했어요"
                    "left" -> "화면을 오른쪽으로 넘겼어요"
                    "right" -> "화면을 왼쪽으로 넘겼어요"
                    else -> "화면을 스크롤했어요"
                }
            }
            "drag_and_drop" -> {
                val desc = targetLabel.ifBlank { "항목" }
                "'${desc.take(24)}'을 원하는 위치로 옮겼어요"
            }
            "haptic_trajectory" -> "화면에 손가락 이동 동작을 적용했어요"
            "click_sequence" -> "여러 항목을 순서대로 눌렀어요"
            "press_key" -> {
                when (args.optString("key", "BACK").trim().uppercase(Locale.ROOT)) {
                    "BACK" -> "이전 화면으로 돌아갔어요"
                    "HOME" -> "홈 화면으로 이동했어요"
                    "RECENTS", "APP_SWITCH" -> "최근 앱 목록을 열었어요"
                    "ENTER", "IME_ENTER" -> "입력 확인을 눌렀어요"
                    "NOTIFICATIONS" -> "알림 창을 열었어요"
                    "QUICK_SETTINGS" -> "빠른 설정 창을 열었어요"
                    else -> "시스템 버튼을 눌렀어요"
                }
            }
            "manage_app" -> {
                val action = args.optString("action", "launch").trim().lowercase(Locale.ROOT)
                val app = args.optString("app_name", "").trim()
                if (action == "launch" && app.isNotBlank()) "'$app' 앱을 열었어요"
                else if (action == "launch") "요청하신 앱을 열었어요"
                else "설치된 앱 목록을 살펴봤어요"
            }
            "wait_for_delay" -> "화면이 준비될 때까지 잠시 기다렸어요"
            "open_link" -> "요청하신 링크를 열었어요"
            "list_notifications" -> "최근 도착한 알림 내용을 확인했어요"
            "reply_notification" -> "알림에 바로 답장을 보냈어요"
            "control_device_setting" -> translateSettingStep(args)
            "make_call" -> {
                val who = args.optString("recipient", "").trim().ifBlank { "상대방" }
                "'$who'에게 전화를 걸 준비를 했어요"
            }
            "send_sms" -> {
                val who = args.optString("recipient", "").trim().ifBlank { "상대방" }
                "'$who'에게 문자 메시지를 준비했어요"
            }
            "search_contacts" -> "연락처에서 번호를 찾았어요"
            "manage_calendar" -> {
                val action = args.optString("action", "list").trim().lowercase(Locale.ROOT)
                if (action == "add" || action == "create") {
                    val title = args.optString("title", "새 일정").trim().ifBlank { "새 일정" }
                    "'$title' 일정을 캘린더에 등록했어요"
                } else {
                    "다가오는 일정을 확인했어요"
                }
            }
            "set_alarm" -> {
                val h = args.optInt("hour", 7).coerceIn(0, 23)
                val m = args.optInt("minute", 0).coerceIn(0, 59)
                val amPm = if (h < 12) "오전" else "오후"
                val h12 = when {
                    h == 0 -> 12
                    h > 12 -> h - 12
                    else -> h
                }
                val minPart = if (m > 0) " ${m}분" else ""
                "$amPm ${h12}시$minPart 알람을 맞췄어요"
            }
            "set_timer" -> "타이머를 시작했어요"
            "save_note", "scratchpad_note" -> "중요한 내용을 메모에 안전하게 기록했어요"
            "read_note" -> "저장된 메모를 읽어왔어요"
            "watcher_manage" -> "자동 알림 감시 설정을 정리했어요"
            "a2a_negotiate_schedule" -> "가능한 약속 시간 후보를 비교했어요"
            "vault_blind_inject", "vault_list_handles" -> "안전 금고 정보를 확인·입력했어요"
            "browser_use" -> "웹페이지 내용을 깔끔하게 정리했어요"
            "draw_with_code", "generate_visual_media", "render_generative_ui" -> "요청하신 보기 카드를 만들었어요"
            "ask_user" -> "사용자 확인을 기다리고 있어요"
            "complete_task" -> {
                val sum = args.optString("summary", rawSummary)
                sanitizeForHumanDisplay(sum).ifBlank { "요청하신 작업을 마쳤어요" }
            }
            else -> sanitizeForHumanDisplay(rawSummary).ifBlank { "요청하신 동작을 실행했어요" }
        }

        return sanitizeForHumanDisplay(translated).ifBlank { "작업을 진행했어요" }
    }

    fun sanitizeForHumanDisplay(raw: String): String {
        if (raw.isBlank()) return ""
        var text = stripRawJsonObjects(raw)
        text = STACK_TRACE_AT_REGEX.replace(text, " ")
        text = CAUSED_BY_REGEX.replace(text, " ")
        text = NORM_COORD_REGEX.replace(text, " ")
        text = PX_COORD_REGEX.replace(text, " ")
        text = BOUNDS_REGEX.replace(text, " ")
        text = FLAGS_REGEX.replace(text, " ")
        text = ID_TAG_REGEX.replace(text, " ")
        text = FILE_LINE_REGEX.replace(text, "")
        text = EXCEPTION_CLASS_REGEX.replace(text, "")
        text = text.replace(Regex("\\(\\s*\\)"), "")
        text = text.replace(Regex("\\[\\s*]"), "")
        text = text.replace(Regex("[ \\t]+"), " ")
        text = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .replace(Regex("\\s+([.,!?])"), "$1")
            .trim()
        return text
    }

    private fun translateSettingStep(args: JSONObject): String {
        val setting = args.optString("setting", "").trim().lowercase(Locale.ROOT)
        val value = args.optString("value", "").trim().lowercase(Locale.ROOT)
        return when (setting) {
            "flashlight", "torch" -> {
                val off = value in listOf("0", "off", "false", "끄기", "꺼")
                if (off) "손전등을 껐어요" else "손전등을 켰어요"
            }
            "dnd" -> {
                val on = value in listOf("on", "true", "1", "켜기")
                if (on) "방해금지 모드를 켰어요" else "방해금지 모드를 껐어요"
            }
            "brightness" -> "화면 밝기를 조절했어요"
            "volume_media", "volume_ring", "volume_alarm" -> "소리 크기를 조절했어요"
            "ringer_mode" -> when {
                value.contains("silent") || value.contains("무음") -> "무음 모드로 전환했어요"
                value.contains("vibrate") || value.contains("진동") -> "진동 모드로 전환했어요"
                else -> "벨소리 모드로 전환했어요"
            }
            "clipboard" -> "클립보드에 내용을 복사했어요"
            else -> "기기 설정을 변경했어요"
        }
    }

    private fun resolveElementLabel(args: JSONObject, snapshot: AtomicSnapshot?): String {
        if (snapshot == null || !args.has("element_index")) return ""
        val idx = args.optInt("element_index", -1)
        val el = snapshot.indexedElements.firstOrNull { it.index == idx } ?: return ""
        return el.text.trim().ifBlank { el.contentDesc.trim() }
    }

    private fun stripRawJsonObjects(input: String): String {
        if (!input.contains("{")) return input
        val out = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            if (input[i] == '{') {
                val endIdx = findJsonObjectEnd(input, i)
                if (endIdx > i) {
                    val slice = input.substring(i, endIdx + 1)
                    if (slice.contains("\"") && slice.contains(":")) {
                        out.append(' ')
                        i = endIdx + 1
                        continue
                    }
                }
            }
            out.append(input[i])
            i++
        }
        return out.toString()
    }

    private fun findJsonObjectEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }
}

// =============================================================================
// 7. Reversible Receipt Planner (1-Tap Undo Planning)
// =============================================================================

data class ReversibleUndoSpec(
    val undoToolName: String,
    val undoArgsJson: String,
    val undoLabelKo: String
)

object ReversibleReceiptPlanner {

    fun planUndoForAction(
        toolName: String,
        args: JSONObject,
        previousNoteValue: String? = null
    ): ReversibleUndoSpec? {
        return when (toolName) {
            "control_device_setting" -> planDeviceSettingUndo(args, previousNoteValue)
            "save_note" -> {
                val key = args.optString("key", "").trim()
                if (key.isEmpty()) return null
                val restoreValue = previousNoteValue ?: ""
                val undoArgs = JSONObject().apply {
                    put("key", key)
                    put("content", restoreValue)
                }
                ReversibleUndoSpec(
                    undoToolName = "save_note",
                    undoArgsJson = undoArgs.toString(),
                    undoLabelKo = if (previousNoteValue != null) "이전 메모로 되돌리기" else "저장한 메모 비우기"
                )
            }
            "scratchpad_note" -> {
                val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
                val key = args.optString("key", "").trim()
                if (action != "put" || key.isEmpty()) return null
                val undoArgs = if (previousNoteValue != null) {
                    JSONObject().put("action", "put").put("key", key).put("value", previousNoteValue)
                } else {
                    JSONObject().put("action", "remove").put("key", key)
                }
                ReversibleUndoSpec(
                    undoToolName = "scratchpad_note",
                    undoArgsJson = undoArgs.toString(),
                    undoLabelKo = if (previousNoteValue != null) "이전 작업 메모로 복원" else "방금 적은 메모 삭제"
                )
            }
            "watcher_manage" -> {
                val action = args.optString("action", "").trim().lowercase(Locale.ROOT)
                if (action != "add") return null
                val ruleId = args.optString("rule_id", "").trim()
                    .ifBlank { previousNoteValue?.trim().orEmpty() }
                val undoArgs = JSONObject().apply {
                    put("action", "remove")
                    if (ruleId.isNotEmpty()) {
                        put("rule_id", ruleId)
                    } else {
                        put("title", args.optString("title", "").trim())
                    }
                }
                ReversibleUndoSpec(
                    undoToolName = "watcher_manage",
                    undoArgsJson = undoArgs.toString(),
                    undoLabelKo = "등록한 감시 규칙 삭제"
                )
            }
            "click", "open_link" -> {
                ReversibleUndoSpec(
                    undoToolName = "press_key",
                    undoArgsJson = JSONObject().put("key", "BACK").toString(),
                    undoLabelKo = "이전 화면으로 돌아가기"
                )
            }
            "manage_app" -> {
                val action = args.optString("action", "launch").trim().lowercase(Locale.ROOT)
                if (action.isNotEmpty() && action != "launch") return null
                ReversibleUndoSpec(
                    undoToolName = "press_key",
                    undoArgsJson = JSONObject().put("key", "BACK").toString(),
                    undoLabelKo = "이전 화면으로 돌아가기"
                )
            }
            else -> null
        }
    }

    private fun planDeviceSettingUndo(
        args: JSONObject,
        previousNoteValue: String?
    ): ReversibleUndoSpec? {
        val setting = args.optString("setting", "").trim().lowercase(Locale.ROOT)
        val rawValue = args.optString("value", args.optString("state", "")).trim().lowercase(Locale.ROOT)

        return when (setting) {
            "flashlight", "torch" -> {
                val isCurrentlyOff = rawValue in listOf("0", "off", "false", "끄기", "꺼")
                val targetValue = if (isCurrentlyOff) "1" else "0"
                ReversibleUndoSpec(
                    undoToolName = "control_device_setting",
                    undoArgsJson = JSONObject().put("setting", "flashlight").put("value", targetValue).toString(),
                    undoLabelKo = if (isCurrentlyOff) "손전등 다시 켜기" else "손전등 다시 끄기"
                )
            }
            "dnd" -> {
                val isCurrentlyOn = rawValue in listOf("on", "true", "1", "켜기")
                val targetValue = if (isCurrentlyOn) "off" else "on"
                ReversibleUndoSpec(
                    undoToolName = "control_device_setting",
                    undoArgsJson = JSONObject().put("setting", "dnd").put("value", targetValue).toString(),
                    undoLabelKo = if (isCurrentlyOn) "방해금지 모드 끄기" else "방해금지 모드 켜기"
                )
            }
            "ringer_mode" -> {
                val prev = previousNoteValue?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                val targetMode = prev ?: when {
                    rawValue.contains("silent") || rawValue.contains("무음") ||
                        rawValue.contains("vibrate") || rawValue.contains("진동") -> "normal"
                    else -> "vibrate"
                }
                val label = when {
                    targetMode.contains("silent") || targetMode.contains("무음") -> "무음 모드로 되돌리기"
                    targetMode.contains("vibrate") || targetMode.contains("진동") -> "진동 모드로 되돌리기"
                    else -> "벨소리 모드로 되돌리기"
                }
                ReversibleUndoSpec(
                    undoToolName = "control_device_setting",
                    undoArgsJson = JSONObject().put("setting", "ringer_mode").put("value", targetMode).toString(),
                    undoLabelKo = label
                )
            }
            "brightness" -> {
                val prevBright = previousNoteValue?.trim()?.toIntOrNull()?.coerceIn(0, 255)
                val currentBright = rawValue.toIntOrNull()?.coerceIn(0, 255) ?: 150
                val restoreBright = (prevBright ?: if (currentBright <= 120) 180 else 120).toString()
                ReversibleUndoSpec(
                    undoToolName = "control_device_setting",
                    undoArgsJson = JSONObject().put("setting", "brightness").put("value", restoreBright).toString(),
                    undoLabelKo = "화면 밝기 되돌리기"
                )
            }
            "volume_media", "volume_ring", "volume_alarm" -> {
                val prevVol = previousNoteValue?.trim()?.toIntOrNull()?.coerceIn(0, 100)
                val currentVol = rawValue.toIntOrNull()?.coerceIn(0, 100) ?: 50
                val restoreVol = (prevVol ?: if (currentVol <= 30) 50 else 25).toString()
                ReversibleUndoSpec(
                    undoToolName = "control_device_setting",
                    undoArgsJson = JSONObject().put("setting", setting).put("value", restoreVol).toString(),
                    undoLabelKo = "소리 크기 되돌리기"
                )
            }
            else -> null
        }
    }
}

// =============================================================================
// 8. Personal Routine Store (End-to-End "Show-Me-Once" Compiler)
// =============================================================================

data class PersonalRoutine(
    val id: String,
    val titleKo: String,
    val emoji: String,
    val goalPrompt: String,
    val sourceBadge: String,
    val pinned: Boolean = true,
    val stepCount: Int = 1,
    val createdAtMs: Long = System.currentTimeMillis()
)

class PersonalRoutineStore @JvmOverloads constructor(
    private val storageFile: File
) {
    constructor(context: Context) : this(
        storageFile = File((context.applicationContext ?: context).filesDir, ROUTINES_FILE_NAME)
    )

    companion object {
        private const val ROUTINES_FILE_NAME = "hanson_personal_routines.json"
        private const val MAX_ROUTINES = 40
    }

    private val lock = Any()
    private val idCounter = AtomicInteger(0)
    private val routineList = mutableListOf<PersonalRoutine>()
    private val _routines = MutableStateFlow<List<PersonalRoutine>>(emptyList())
    val routines: StateFlow<List<PersonalRoutine>> = _routines.asStateFlow()

    init {
        loadFromDisk()
    }

    fun seedDefaultsIfEmpty() {
        synchronized(lock) {
            if (routineList.isNotEmpty()) return
            val now = System.currentTimeMillis()
            routineList.addAll(
                listOf(
                    PersonalRoutine(
                        id = "routine_morning_brief",
                        titleKo = "아침 시작 브리핑",
                        emoji = "☀️",
                        goalPrompt = "오늘 캘린더 일정과 최근 수신된 알림을 확인해서 한눈에 요약해줘",
                        sourceBadge = "기본 루틴",
                        pinned = true,
                        stepCount = 2,
                        createdAtMs = now
                    ),
                    PersonalRoutine(
                        id = "routine_focus_mode",
                        titleKo = "집중 모드 켜기",
                        emoji = "🎧",
                        goalPrompt = "방해금지 모드를 켜고 미디어 볼륨을 20으로 낮춘 뒤 25분 타이머를 맞춰줘",
                        sourceBadge = "기본 루틴",
                        pinned = true,
                        stepCount = 3,
                        createdAtMs = now - 1L
                    ),
                    PersonalRoutine(
                        id = "routine_sleep_ready",
                        titleKo = "취침 전 준비",
                        emoji = "🌙",
                        goalPrompt = "내일 오전 7시 30분 알람을 설정하고 미디어 볼륨을 18로 낮춰줘",
                        sourceBadge = "기본 루틴",
                        pinned = true,
                        stepCount = 2,
                        createdAtMs = now - 2L
                    )
                )
            )
            saveToDiskLocked()
            _routines.value = routineList.toList()
        }
    }

    fun addRoutine(
        titleKo: String,
        goalPrompt: String,
        emoji: String = "⚡",
        sourceBadge: String = "내 루틴",
        pinned: Boolean = true,
        stepCount: Int = 1
    ): PersonalRoutine {
        val cleanGoal = goalPrompt.trim()
        require(cleanGoal.isNotEmpty()) { "goalPrompt must not be blank." }
        val cleanTitle = titleKo.trim().ifBlank { cleanGoal.take(24) }
        val cleanEmoji = emoji.trim().ifBlank { "⚡" }
        val cleanBadge = sourceBadge.trim().ifBlank { "내 루틴" }
        val now = System.currentTimeMillis()

        val routine = PersonalRoutine(
            id = "routine_${now}_${idCounter.incrementAndGet()}",
            titleKo = cleanTitle,
            emoji = cleanEmoji,
            goalPrompt = cleanGoal,
            sourceBadge = cleanBadge,
            pinned = pinned,
            stepCount = stepCount.coerceAtLeast(1),
            createdAtMs = now
        )

        synchronized(lock) {
            routineList.add(0, routine)
            while (routineList.size > MAX_ROUTINES) {
                routineList.removeAt(routineList.lastIndex)
            }
            saveToDiskLocked()
            _routines.value = routineList.toList()
        }
        return routine
    }

    fun togglePinned(id: String, pinned: Boolean? = null): Boolean {
        synchronized(lock) {
            val idx = routineList.indexOfFirst { it.id == id }
            if (idx < 0) return false
            val current = routineList[idx]
            routineList[idx] = current.copy(pinned = pinned ?: !current.pinned)
            saveToDiskLocked()
            _routines.value = routineList.toList()
            return true
        }
    }

    fun removeRoutine(id: String): Boolean {
        synchronized(lock) {
            val removed = routineList.removeAll { it.id == id }
            if (removed) {
                saveToDiskLocked()
                _routines.value = routineList.toList()
            }
            return removed
        }
    }

    /**
     * Merges LWW-resolved cloud/remote routines while preserving exact IDs, step counts, and createdAtMs.
     */
    fun upsertMergedRoutines(merged: List<PersonalRoutine>) {
        synchronized(lock) {
            val byId = LinkedHashMap<String, PersonalRoutine>(routineList.size + merged.size)
            for (existing in routineList) {
                if (existing.id.isNotBlank()) {
                    byId[existing.id] = existing
                }
            }
            for (incoming in merged) {
                val cleanId = incoming.id.trim().take(128)
                val cleanGoal = incoming.goalPrompt.trim().take(1000)
                if (cleanId.isEmpty() || cleanGoal.isEmpty()) continue
                if (ConvenienceSecretGuard.containsSensitiveSecret(cleanGoal)) continue
                val cleanTitle = incoming.titleKo.trim().take(200).ifBlank { cleanGoal.take(24) }
                if (ConvenienceSecretGuard.containsSensitiveSecret(cleanTitle)) continue
                val normalized = PersonalRoutine(
                    id = cleanId,
                    titleKo = cleanTitle,
                    emoji = incoming.emoji.trim().ifBlank { "⚡" }.take(32),
                    goalPrompt = cleanGoal,
                    sourceBadge = incoming.sourceBadge.trim().ifBlank { "클라우드 동기화" }.take(64),
                    pinned = incoming.pinned,
                    stepCount = incoming.stepCount.coerceIn(1, 50),
                    createdAtMs = incoming.createdAtMs.coerceIn(0L, 253_402_300_799_999L)
                )
                val current = byId[cleanId]
                if (current == null || normalized.createdAtMs >= current.createdAtMs) {
                    byId[cleanId] = normalized
                }
            }
            val nextList = byId.values
                .sortedByDescending { it.createdAtMs }
                .take(MAX_ROUTINES)
            routineList.clear()
            routineList.addAll(nextList)
            saveToDiskLocked()
            _routines.value = routineList.toList()
        }
    }

    fun compileFromDemonstration(
        customTitle: String,
        recordedPackage: String,
        compiledRecipe: String,
        stepCount: Int
    ): PersonalRoutine {
        val safeSteps = stepCount.coerceAtLeast(1)
        val appLabel = resolveFriendlyAppLabel(recordedPackage)
        val title = customTitle.trim().ifBlank { "${appLabel} 시연 루틴" }
        val cleanRecipe = compiledRecipe.trim()
        val goalPrompt = if (cleanRecipe.isNotEmpty()) {
            "${appLabel}에서 다음 직접 시연한 순서를 따라 실행해줘: $cleanRecipe"
        } else {
            "${appLabel}에서 '${title}' 작업을 실행해줘"
        }
        return addRoutine(
            titleKo = title,
            goalPrompt = goalPrompt,
            emoji = "🎬",
            sourceBadge = "시연 학습 · ${safeSteps}단계",
            pinned = true,
            stepCount = safeSteps
        )
    }

    private fun resolveFriendlyAppLabel(rawPackage: String): String {
        val pkg = rawPackage.trim().lowercase(Locale.ROOT)
        return when {
            pkg.isEmpty() || pkg == "현재앱" -> "현재 앱"
            pkg.contains("com.kakao.talk") -> "카카오톡"
            pkg.contains("com.kakao.taxi") -> "카카오 T"
            pkg.contains("com.sampleapp") || pkg.contains("baemin") -> "배달의민족"
            pkg.contains("com.coupang.mobile.eats") -> "쿠팡이츠"
            pkg.contains("com.coupang.mobile") -> "쿠팡"
            pkg.contains("com.nhn.android.nmap") -> "네이버 지도"
            pkg.contains("com.nhn.android.search") -> "네이버"
            pkg.contains("viva.republica.toss") -> "토스"
            pkg.contains("com.google.android.youtube") -> "유튜브"
            pkg.contains("catchtable") -> "캐치테이블"
            pkg.contains("com.korail.talk") -> "코레일톡"
            pkg.contains("com.android.settings") -> "설정"
            else -> rawPackage.trim().substringAfterLast('.').ifBlank { "현재 앱" }
        }
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            routineList.clear()
            if (!storageFile.isFile) {
                _routines.value = emptyList()
                return
            }
            runCatching {
                val root = JSONObject(storageFile.readText(StandardCharsets.UTF_8))
                val arr = root.optJSONArray("routines") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val title = obj.optString("titleKo", "").trim()
                    val goal = obj.optString("goalPrompt", "").trim()
                    if (id.isEmpty() || title.isEmpty() || goal.isEmpty()) continue
                    routineList.add(
                        PersonalRoutine(
                            id = id,
                            titleKo = title,
                            emoji = obj.optString("emoji", "⚡").ifBlank { "⚡" },
                            goalPrompt = goal,
                            sourceBadge = obj.optString("sourceBadge", "내 루틴").ifBlank { "내 루틴" },
                            pinned = obj.optBoolean("pinned", true),
                            stepCount = obj.optInt("stepCount", 1).coerceAtLeast(1),
                            createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
                        )
                    )
                }
            }
            _routines.value = routineList.toList()
        }
    }

    private fun saveToDiskLocked() {
        val arr = JSONArray()
        routineList.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("titleKo", r.titleKo)
                    put("emoji", r.emoji)
                    put("goalPrompt", r.goalPrompt)
                    put("sourceBadge", r.sourceBadge)
                    put("pinned", r.pinned)
                    put("stepCount", r.stepCount)
                    put("createdAtMs", r.createdAtMs)
                }
            )
        }
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("routines", arr)
        }
        ConvenienceStorageHelper.writeAtomically(storageFile, root.toString(2))
    }
}

// =============================================================================
// 9. Call Concierge Planner (Pickle Phone Concierge)
// =============================================================================

data class CallConciergePlan(
    val calleeLabel: String,
    val phoneNumber: String,
    val purposeKo: String,
    val scriptLinesKo: List<String>,
    val requiresHumanApproval: Boolean = true
)

object CallConciergePlanner {

    fun buildPlan(
        calleeLabel: String,
        phoneNumber: String,
        purposeKo: String,
        preferredSlot: String = ""
    ): CallConciergePlan {
        val cleanCallee = calleeLabel.trim().ifBlank { "상대방" }
        val cleanNumber = phoneNumber.trim()
        val cleanPurpose = purposeKo.trim().ifBlank { "일정 및 이용 가능 여부 문의" }
        val cleanSlot = preferredSlot.trim()

        val line1 = "안녕하세요, ${cleanCallee} 맞으신가요? ${cleanPurpose} 관련해서 여쭤보려고 전화드렸습니다."
        val line2 = if (cleanSlot.isNotEmpty()) {
            "혹시 ${cleanSlot} 시간대에 이용이나 진행이 가능할지 확인 부탁드립니다."
        } else {
            "지금 가능한 시간대나 안내 사항을 잠깐 확인해 주실 수 있을까요?"
        }
        val line3 = "친절히 확인해 주셔서 감사합니다. 말씀 주신 내용 참고해서 바로 진행하겠습니다."

        return CallConciergePlan(
            calleeLabel = cleanCallee,
            phoneNumber = cleanNumber,
            purposeKo = cleanPurpose,
            scriptLinesKo = listOf(line1, line2, line3),
            requiresHumanApproval = true
        )
    }
}
