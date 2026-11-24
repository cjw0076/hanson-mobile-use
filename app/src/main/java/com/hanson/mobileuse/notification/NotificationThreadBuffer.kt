package com.hanson.mobileuse.notification

import java.util.Locale

/**
 * Single privacy-filtered notification message retained inside a room's rolling thread window.
 */
data class ThreadedNotificationMessage(
    val packageName: String,
    val roomKey: String,
    val sender: String,
    val text: String,
    val timestampMs: Long
)

/**
 * Snapshot of recent multi-turn messages for a specific `(packageName, roomKey)` conversation.
 */
data class NotificationThreadContext(
    val packageName: String,
    val roomKey: String,
    val messages: List<ThreadedNotificationMessage> = emptyList(),
    val combinedText: String = "",
    val participants: List<String> = emptyList(),
    val latestTimestampMs: Long = 0L
) {
    val isEmpty: Boolean get() = messages.isEmpty()
}

/**
 * Thread-safe rolling notification thread buffer storing up to [MAX_MESSAGES_PER_ROOM] (12)
 * privacy-filtered messages per `(packageName, roomKey)` over a [WINDOW_MS] (30-minute)
 * window, bounded to at most [MAX_ROOMS] (60) active rooms.
 *
 * Privacy hardlines:
 * - Hard secrets (16-digit card numbers, Korean RRNs, OTPs, API keys, Bearer tokens, login
 *   passwords) are NEVER stored in the buffer.
 * - Door / locker / entrance PINs inside parcel/delivery notifications are redacted in place
 *   (`[PIN 비공개]`) so delivery tracking remains useful without ever persisting PIN digits.
 */
object NotificationThreadBuffer {

    const val MAX_MESSAGES_PER_ROOM = 12
    const val WINDOW_MS = 30L * 60L * 1000L
    const val MAX_ROOMS = 60

    private val lock = Any()
    private val rooms = LinkedHashMap<String, ArrayDeque<ThreadedNotificationMessage>>()

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
    private val HARD_PASSWORD_REGEX = Regex(
        "(?i)(?:계좌\\s*비번|보안카드|password|passwd|passphrase|secret[_\\s-]?key|private[_\\s-]?key|access[_\\s-]?token|api[_\\s-]?key|\\bcvv\\b|\\bcvc\\b)"
    )
    private val GENERIC_KO_PASSWORD_REGEX = Regex("(?:비밀번호|패스워드|비번|핀번호)")
    private val DELIVERY_CONTEXT_REGEX = Regex(
        "(?i)(?:택배|배송|로켓배송|무인\\s*택배함|택배함|보관함|문앞|공동현관|현관|도어락|배달|cj대한통운|한진택배|롯데택배|쿠팡)"
    )
    private val DOOR_OR_LOCKER_PIN_REGEX = Regex(
        "(?i)((?:공동\\s*현관|현관|도어락|무인\\s*택배함|택배함|보관함|출입|호출)(?:\\s*(?:비밀번호|비번|출입번호|번호|코드|pin))?(?:은|는)?\\s*[:：=]?\\s*[*#]?\\d{3,8}[*#]?|(?:비밀번호|비번|핀번호|출입번호|pin)(?:은|는)?\\s*[:：=]?\\s*[*#]?\\d{3,8}[*#]?)"
    )
    private val PIN_VALUE_CAPTURE_REGEX = Regex("([*#]?\\d{3,8}[*#]?)")

    /**
     * Redacts door/locker PINs inside delivery notifications while rejecting hard secrets
     * (returns null if the text contains OTPs, cards, RRNs, API keys, or non-delivery passwords).
     */
    fun sanitizeForBuffer(rawText: String, packageName: String = ""): String? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return null

        // Hard secrets are NEVER retained under any circumstance.
        if (containsHardSecret(trimmed)) return null

        val isDeliveryContext = DELIVERY_CONTEXT_REGEX.containsMatchIn(trimmed) ||
            packageName.lowercase(Locale.ROOT).let { p ->
                p.contains("coupang") || p.contains("sampleapp") || p.contains("baemin") ||
                    p.contains("logistics") || p.contains("doortodoor") ||
                    p.contains("hanjin") || p.contains("lotteglogis") || p.contains("sweettracker")
            }

        if (isDeliveryContext) {
            val redacted = redactDoorAndLockerPins(trimmed)
            if (containsHardSecret(redacted)) return null
            return redacted
        }

        // Outside delivery context, any Korean password/PIN cue is rejected outright.
        if (GENERIC_KO_PASSWORD_REGEX.containsMatchIn(trimmed)) return null

        return trimmed
    }

    /**
     * Replaces door/locker/entrance PIN digits with `[PIN 비공개]`.
     */
    fun redactDoorAndLockerPins(text: String): String {
        if (text.isEmpty()) return text
        return DOOR_OR_LOCKER_PIN_REGEX.replace(text) { match ->
            val segment = match.value
            PIN_VALUE_CAPTURE_REGEX.replace(segment, "[PIN 비공개]")
        }
    }

    fun containsHardSecret(text: String): Boolean {
        if (text.isBlank()) return false
        return CARD_16_SEPARATED_REGEX.containsMatchIn(text) ||
            CARD_16_PLAIN_REGEX.containsMatchIn(text) ||
            RRN_REGEX.containsMatchIn(text) ||
            PURE_OTP_DIGITS_REGEX.matches(text) ||
            CONTEXTUAL_OTP_REGEX.containsMatchIn(text) ||
            TRAILING_OTP_REGEX.containsMatchIn(text) ||
            GOOGLE_API_KEY_REGEX.containsMatchIn(text) ||
            SK_KEY_REGEX.containsMatchIn(text) ||
            BEARER_TOKEN_REGEX.containsMatchIn(text) ||
            GITHUB_OR_SLACK_TOKEN_REGEX.containsMatchIn(text) ||
            HARD_PASSWORD_REGEX.containsMatchIn(text)
    }

    fun buildRoomStorageKey(packageName: String, roomKey: String): String {
        val pkgNorm = packageName.trim().lowercase(Locale.ROOT)
        val roomNorm = roomKey.trim().lowercase(Locale.ROOT).ifEmpty { "default" }
        return "$pkgNorm::$roomNorm"
    }

    /**
     * Appends a single privacy-filtered message to the rolling room buffer.
     * Deduplicates identical `(sender, text)` entries already inside the active window.
     * Returns true if a new message was added.
     */
    fun appendMessage(
        packageName: String,
        roomKey: String,
        sender: String,
        text: String,
        timestampMs: Long = System.currentTimeMillis(),
        nowMs: Long = timestampMs
    ): Boolean {
        val safePkg = packageName.trim()
        val safeRoom = roomKey.trim().ifEmpty { sender.trim().ifEmpty { safePkg } }
        if (safePkg.isEmpty() || safeRoom.isEmpty()) return false

        val sanitizedText = sanitizeForBuffer(text, safePkg) ?: return false
        val safeSender = sender.trim().ifEmpty { safeRoom }
        val storageKey = buildRoomStorageKey(safePkg, safeRoom)
        val effectiveNow = maxOf(nowMs, timestampMs)

        synchronized(lock) {
            pruneAllLocked(effectiveNow)
            val deque = rooms.remove(storageKey) ?: ArrayDeque()
            pruneDequeLocked(deque, effectiveNow)

            // Deduplicate re-delivered MessagingStyle history lines
            val alreadyPresent = deque.any { existing ->
                existing.sender.equals(safeSender, ignoreCase = true) &&
                    existing.text == sanitizedText &&
                    kotlin.math.abs(existing.timestampMs - timestampMs) <= WINDOW_MS
            }
            if (alreadyPresent) {
                rooms[storageKey] = deque
                return false
            }

            deque.addLast(
                ThreadedNotificationMessage(
                    packageName = safePkg,
                    roomKey = safeRoom,
                    sender = safeSender,
                    text = sanitizedText,
                    timestampMs = timestampMs
                )
            )
            while (deque.size > MAX_MESSAGES_PER_ROOM) {
                deque.removeFirst()
            }
            // Re-insert at tail so LinkedHashMap preserves most-recently-active LRU order
            rooms[storageKey] = deque

            while (rooms.size > MAX_ROOMS) {
                val oldestKey = rooms.entries.firstOrNull()?.key ?: break
                rooms.remove(oldestKey)
            }
            return true
        }
    }

    /**
     * Appends a batch of messages (e.g., extracted from `NotificationCompat.MessagingStyle.messages`).
     * Returns the count of newly inserted messages.
     */
    fun appendMessages(
        packageName: String,
        roomKey: String,
        items: List<ThreadedNotificationMessage>,
        nowMs: Long = System.currentTimeMillis()
    ): Int {
        var added = 0
        for (item in items.sortedBy { it.timestampMs }) {
            if (appendMessage(
                    packageName = packageName,
                    roomKey = roomKey,
                    sender = item.sender,
                    text = item.text,
                    timestampMs = item.timestampMs,
                    nowMs = maxOf(nowMs, item.timestampMs)
                )
            ) {
                added++
            }
        }
        return added
    }

    fun getThreadContext(
        packageName: String,
        roomKey: String,
        nowMs: Long = System.currentTimeMillis()
    ): NotificationThreadContext {
        val safePkg = packageName.trim()
        val safeRoom = roomKey.trim()
        val storageKey = buildRoomStorageKey(safePkg, safeRoom)

        synchronized(lock) {
            val deque = rooms[storageKey] ?: return NotificationThreadContext(
                packageName = safePkg,
                roomKey = safeRoom
            )
            pruneDequeLocked(deque, nowMs)
            if (deque.isEmpty()) {
                rooms.remove(storageKey)
                return NotificationThreadContext(
                    packageName = safePkg,
                    roomKey = safeRoom
                )
            }
            return buildContextSnapshot(safePkg, safeRoom, deque)
        }
    }

    fun listActiveThreads(
        nowMs: Long = System.currentTimeMillis(),
        limit: Int = 20
    ): List<NotificationThreadContext> {
        synchronized(lock) {
            pruneAllLocked(nowMs)
            return rooms.values
                .filter { it.isNotEmpty() }
                .map { deque ->
                    val last = deque.last()
                    buildContextSnapshot(last.packageName, last.roomKey, deque)
                }
                .sortedByDescending { it.latestTimestampMs }
                .take(limit.coerceAtLeast(0))
        }
    }

    fun activeRoomCount(nowMs: Long = System.currentTimeMillis()): Int {
        synchronized(lock) {
            pruneAllLocked(nowMs)
            return rooms.size
        }
    }

    fun clearRoom(packageName: String, roomKey: String) {
        val storageKey = buildRoomStorageKey(packageName, roomKey)
        synchronized(lock) {
            rooms.remove(storageKey)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            rooms.clear()
        }
    }

    private fun buildContextSnapshot(
        packageName: String,
        roomKey: String,
        deque: ArrayDeque<ThreadedNotificationMessage>
    ): NotificationThreadContext {
        val ordered = deque.sortedBy { it.timestampMs }
        val combined = ordered.joinToString("\n") { msg ->
            if (msg.sender.isNotBlank()) "${msg.sender}: ${msg.text}" else msg.text
        }
        val participants = ordered.map { it.sender }.filter { it.isNotBlank() }.distinct()
        val latestTs = ordered.maxOfOrNull { it.timestampMs } ?: 0L
        return NotificationThreadContext(
            packageName = packageName,
            roomKey = roomKey,
            messages = ordered,
            combinedText = combined,
            participants = participants,
            latestTimestampMs = latestTs
        )
    }

    private fun pruneDequeLocked(deque: ArrayDeque<ThreadedNotificationMessage>, nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (deque.isNotEmpty() && deque.first().timestampMs < cutoff) {
            deque.removeFirst()
        }
    }

    private fun pruneAllLocked(nowMs: Long) {
        val iter = rooms.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            pruneDequeLocked(entry.value, nowMs)
            if (entry.value.isEmpty()) {
                iter.remove()
            }
        }
    }
}
