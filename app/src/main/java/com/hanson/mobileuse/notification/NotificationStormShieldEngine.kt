package com.hanson.mobileuse.notification

import com.hanson.mobileuse.agent.A2uiProtocolConstants
import com.hanson.mobileuse.agent.A2uiSurfaceRegistry
import com.hanson.mobileuse.agent.PrivacyAndQuotaProofLedger
import com.hanson.mobileuse.agent.ProactiveProposal
import com.hanson.mobileuse.agent.ProactiveProposalStatus
import com.hanson.mobileuse.agent.ProactiveProposalType
import com.hanson.mobileuse.agent.ProposalAction
import com.hanson.mobileuse.agent.ProposalActionKind
import com.hanson.mobileuse.agent.UntrustedScreenDataGuard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// =============================================================================
// #3. Notification-Storm Cognitive Focus Shield + Re-Entry 3-Line Brief
// =============================================================================

data class StormShieldConfig(
    val enabled: Boolean = false,
    val focusBlockActive: Boolean = false,
    val burstThresholdCount: Int = 8,
    val burstWindowMs: Long = 180_000L,
    val vipNames: Set<String> = emptySet(),
    val snoozeDurationMs: Long = 10 * 60_000L
)

data class StormShieldDecision(
    val shouldSnooze: Boolean,
    val bypassedByVipOrEmergency: Boolean,
    val burstCountInWindow: Int,
    val reasonKo: String
)

data class HeldStormMessage(
    val packageName: String,
    val roomKey: String,
    val sender: String,
    val text: String,
    val timestampMs: Long
)

data class FocusReentryDigest(
    val sessionId: String,
    val totalHeldCount: Int,
    val line1DecisionsKo: String,
    val line2ActionNeededKo: String,
    val line3ChatterSummaryKo: String,
    val heldRooms: List<String>,
    val generatedAtMs: Long
)

/**
 * On-device `$0` Notification-Storm Focus Shield:
 * - Tracks per-room sliding burst rate (`>= 8 msgs / 180s` configurable) during active Focus Blocks
 *   or active calendar meetings.
 * - VIP senders, `@mentions`, and emergency keywords ALWAYS bypass the shield.
 * - Buffers non-VIP storm alerts locally (secrets & prompt injections stripped) and builds a
 *   deterministic 3-Line Re-Entry Digest + Google A2UI `v1.0` surface.
 */
object NotificationStormShieldEngine {

    const val ACTION_ACK_ALL = "shield.ack_all"
    const val ACTION_EXTEND_30M = "shield.extend_30m"
    const val ACTION_ADD_VIP = "shield.add_vip"

    private const val MAX_HELD_MESSAGES = 120

    private val lock = Any()
    private val _config = MutableStateFlow(StormShieldConfig())
    val config: StateFlow<StormShieldConfig> = _config.asStateFlow()

    private val roomTimestamps = LinkedHashMap<String, ArrayDeque<Long>>()
    private val heldMessages = mutableListOf<HeldStormMessage>()

    private val EMERGENCY_CUE_REGEX = Regex(
        "(?i)(?:긴급|응급|병원|사고|중요|위급|urgent|emergency)"
    )
    private val MENTION_REGEX = Regex("@[A-Za-z0-9가-힣_]{1,24}")
    private val DECISION_LINE_REGEX = Regex("(?:확정|결정|완료|예약|합의|통과|승인|하기로)")
    private val ACTION_NEEDED_REGEX = Regex("(?:\\?|부탁|확인|답장|회신|어때|가능|언제|리뷰)")

    fun updateConfig(transform: (StormShieldConfig) -> StormShieldConfig): StormShieldConfig {
        synchronized(lock) {
            val next = transform(_config.value)
            _config.value = next
            return next
        }
    }

    fun startFocusBlock(
        vipNames: Set<String> = emptySet(),
        burstThreshold: Int = 8,
        burstWindowMs: Long = 180_000L
    ): StormShieldConfig = updateConfig { cur ->
        cur.copy(
            enabled = true,
            focusBlockActive = true,
            burstThresholdCount = burstThreshold.coerceIn(2, 50),
            burstWindowMs = burstWindowMs.coerceIn(10_000L, 3_600_000L),
            vipNames = cur.vipNames + vipNames.map { it.trim() }.filter { it.isNotEmpty() }
        )
    }

    fun stopFocusBlock(): StormShieldConfig = updateConfig { cur ->
        cur.copy(focusBlockActive = false)
    }

    fun addVip(name: String): StormShieldConfig {
        val clean = name.trim()
        if (clean.isEmpty()) return _config.value
        return updateConfig { cur -> cur.copy(vipNames = cur.vipNames + clean) }
    }

    fun isVipOrEmergency(
        sender: String,
        text: String,
        roomKey: String = ""
    ): Boolean {
        val cfg = _config.value
        val sLower = sender.trim().lowercase(Locale.ROOT)
        val rLower = roomKey.trim().lowercase(Locale.ROOT)
        if (cfg.vipNames.any { vip ->
                val vLower = vip.trim().lowercase(Locale.ROOT)
                vLower.isNotEmpty() && (sLower.contains(vLower) || rLower.contains(vLower))
            }
        ) {
            return true
        }
        if (MENTION_REGEX.containsMatchIn(text)) return true
        if (EMERGENCY_CUE_REGEX.containsMatchIn(text)) return true
        return false
    }

    fun evaluateIncoming(
        packageName: String,
        roomKey: String,
        sender: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
        isInActiveMeeting: Boolean = false
    ): StormShieldDecision {
        val cfg = _config.value
        val safeRoom = roomKey.trim().ifEmpty { sender.trim().ifEmpty { packageName } }
        val storageKey = NotificationThreadBuffer.buildRoomStorageKey(packageName, safeRoom)

        val burstCount: Int
        synchronized(lock) {
            val q = roomTimestamps.getOrPut(storageKey) { ArrayDeque() }
            val cutoff = nowMs - cfg.burstWindowMs
            while (q.isNotEmpty() && q.first() < cutoff) {
                q.removeFirst()
            }
            q.addLast(nowMs)
            burstCount = q.size
        }

        val shieldEngaged = cfg.focusBlockActive || (cfg.enabled && isInActiveMeeting)
        if (!shieldEngaged) {
            return StormShieldDecision(
                shouldSnooze = false,
                bypassedByVipOrEmergency = false,
                burstCountInWindow = burstCount,
                reasonKo = "집중 방패 비활성 상태"
            )
        }

        // VIP & emergency ALWAYS break through
        if (isVipOrEmergency(sender, text, safeRoom)) {
            return StormShieldDecision(
                shouldSnooze = false,
                bypassedByVipOrEmergency = true,
                burstCountInWindow = burstCount,
                reasonKo = "VIP·멘션·긴급 키워드 감지로 집중 방패 즉시 통과"
            )
        }

        if (burstCount < cfg.burstThresholdCount) {
            return StormShieldDecision(
                shouldSnooze = false,
                bypassedByVipOrEmergency = false,
                burstCountInWindow = burstCount,
                reasonKo = "폭주 임계치 미만 (${burstCount}/${cfg.burstThresholdCount})"
            )
        }

        // Hold non-VIP storm alert locally (secrets & prompt injections stripped!)
        val sanitized = NotificationThreadBuffer.sanitizeForBuffer(text, packageName)
        if (sanitized != null && UntrustedScreenDataGuard.detectPromptInjectionSignals(sanitized).isEmpty()) {
            synchronized(lock) {
                heldMessages.add(
                    HeldStormMessage(
                        packageName = packageName,
                        roomKey = safeRoom,
                        sender = sender.ifBlank { safeRoom },
                        text = sanitized.take(140),
                        timestampMs = nowMs
                    )
                )
                while (heldMessages.size > MAX_HELD_MESSAGES) {
                    heldMessages.removeAt(0)
                }
            }
            PrivacyAndQuotaProofLedger.recordLocalZeroCostScan(nowMs = nowMs)
        }

        return StormShieldDecision(
            shouldSnooze = true,
            bypassedByVipOrEmergency = false,
            burstCountInWindow = burstCount,
            reasonKo = "알림 폭풍 감지 (${burstCount}건/${cfg.burstWindowMs / 1000}초) · 복귀 3줄 브리프로 보관"
        )
    }

    fun heldMessageCount(): Int = synchronized(lock) { heldMessages.size }

    /**
     * Builds the deterministic 3-Line Re-Entry Digest from held non-VIP storm alerts and clears buffer.
     */
    fun buildReentryDigestAndClear(
        sessionId: String = "focus_${System.currentTimeMillis()}",
        nowMs: Long = System.currentTimeMillis()
    ): FocusReentryDigest? {
        val snapshot = synchronized(lock) {
            if (heldMessages.isEmpty()) return null
            val copy = heldMessages.toList()
            heldMessages.clear()
            copy
        }

        val decisions = mutableListOf<HeldStormMessage>()
        val actionNeeded = mutableListOf<HeldStormMessage>()
        var chatterCount = 0

        for (msg in snapshot) {
            when {
                DECISION_LINE_REGEX.containsMatchIn(msg.text) -> decisions.add(msg)
                ACTION_NEEDED_REGEX.containsMatchIn(msg.text) -> actionNeeded.add(msg)
                else -> chatterCount++
            }
        }

        val line1 = if (decisions.isNotEmpty()) {
            val preview = decisions.take(2).joinToString(" / ") { "${it.sender}: ${it.text.take(32)}" }
            "1. 확정·결정 사항 (${decisions.size}건): $preview"
        } else {
            "1. 확정·결정 사항: 새로 확정된 공지 없음"
        }

        val line2 = if (actionNeeded.isNotEmpty()) {
            val preview = actionNeeded.take(2).joinToString(" / ") { "${it.sender}: ${it.text.take(32)}" }
            "2. 내 확인·답장 필요 (${actionNeeded.size}건): $preview"
        } else {
            "2. 내 확인·답장 필요: 즉각 회신이 필요한 요청 없음"
        }

        val line3 = "3. 일반 잡담 ${chatterCount}건 조용히 묶음 (전체 ${snapshot.size}건 로컬 정리 · 클라우드 전송 0건)"
        val rooms = snapshot.map { it.roomKey }.distinct()

        return FocusReentryDigest(
            sessionId = sessionId,
            totalHeldCount = snapshot.size,
            line1DecisionsKo = line1,
            line2ActionNeededKo = line2,
            line3ChatterSummaryKo = line3,
            heldRooms = rooms,
            generatedAtMs = nowMs
        )
    }

    /**
     * Converts a [FocusReentryDigest] into a [ProactiveProposal] (`FOCUS_REENTRY_DIGEST`)
     * AND its Google A2UI `v1.0` JSONL surface (`focus_digest_<sessionId>`).
     */
    fun buildProposalAndA2ui(
        digest: FocusReentryDigest,
        publishToRegistry: Boolean = false
    ): Pair<ProactiveProposal, String> {
        val topRoom = digest.heldRooms.firstOrNull().orEmpty().ifBlank { "알림 폭풍 방패" }
        val proposal = ProactiveProposal(
            id = "focus_${digest.sessionId}",
            fingerprint = "focus_digest_${digest.sessionId}",
            type = ProactiveProposalType.FOCUS_REENTRY_DIGEST,
            status = ProactiveProposalStatus.PENDING,
            sourceApp = "집중 방패",
            packageName = "com.hanson.mobileuse",
            sender = "집중 방패",
            roomOrTitle = topRoom,
            titleKo = "집중 방패 종료 · ${digest.totalHeldCount}건 → 핵심 3줄",
            summaryKo = "${digest.line1DecisionsKo} | ${digest.line2ActionNeededKo}",
            detailKo = "${digest.line1DecisionsKo}\n${digest.line2ActionNeededKo}\n${digest.line3ChatterSummaryKo}",
            quotedExcerpt = digest.line1DecisionsKo.take(140),
            confidence = 0.92,
            primaryAction = ProposalAction(
                key = ACTION_ACK_ALL,
                labelKo = "모두 확인",
                kind = ProposalActionKind.DISMISS
            ),
            secondaryActions = listOf(
                ProposalAction(
                    key = ACTION_EXTEND_30M,
                    labelKo = "30분 더 집중",
                    kind = ProposalActionKind.SNOOZE,
                    payload = mapOf("minutes" to "30")
                ),
                ProposalAction(
                    key = ACTION_ADD_VIP,
                    labelKo = "방 VIP 예외 추가",
                    kind = ProposalActionKind.SAVE_NOTE,
                    payload = mapOf("room" to topRoom)
                )
            ),
            readOnlyAdvisory = false,
            createdAtMs = digest.generatedAtMs,
            updatedAtMs = digest.generatedAtMs
        )

        val surfaceId = "focus_digest_${digest.sessionId}"
        val rootChildren = JSONArray()
            .put("fd_title").put("fd_line1").put("fd_line2").put("fd_line3").put("fd_actions_row")
        val components = JSONArray().apply {
            put(JSONObject().put("id", "root").put("component", "Column").put("children", rootChildren))
            put(JSONObject().put("id", "fd_title").put("component", "Text").put("text", JSONObject().put("@path", "/focus/titleKo")).put("variant", "h3"))
            put(JSONObject().put("id", "fd_line1").put("component", "Text").put("text", JSONObject().put("@path", "/focus/line1")).put("variant", "body"))
            put(JSONObject().put("id", "fd_line2").put("component", "Text").put("text", JSONObject().put("@path", "/focus/line2")).put("variant", "body"))
            put(JSONObject().put("id", "fd_line3").put("component", "Text").put("text", JSONObject().put("@path", "/focus/line3")).put("variant", "caption"))

            put(JSONObject().put("id", "fd_ack_lbl").put("component", "Text").put("text", "모두 확인").put("variant", "caption"))
            put(
                JSONObject().put("id", "fd_ack_btn").put("component", "Button").put("variant", "primary").put("child", "fd_ack_lbl")
                    .put("action", JSONObject().put("event", JSONObject().put("name", ACTION_ACK_ALL).put("context", JSONObject().put("sessionId", digest.sessionId))))
            )
            put(JSONObject().put("id", "fd_ext_lbl").put("component", "Text").put("text", "30분 더 집중").put("variant", "caption"))
            put(
                JSONObject().put("id", "fd_ext_btn").put("component", "Button").put("variant", "default").put("child", "fd_ext_lbl")
                    .put("action", JSONObject().put("event", JSONObject().put("name", ACTION_EXTEND_30M).put("context", JSONObject().put("sessionId", digest.sessionId).put("minutes", 30))))
            )
            put(JSONObject().put("id", "fd_vip_lbl").put("component", "Text").put("text", "VIP 예외 추가").put("variant", "caption"))
            put(
                JSONObject().put("id", "fd_vip_btn").put("component", "Button").put("variant", "default").put("child", "fd_vip_lbl")
                    .put("action", JSONObject().put("event", JSONObject().put("name", ACTION_ADD_VIP).put("context", JSONObject().put("room", topRoom))))
            )
            put(
                JSONObject().put("id", "fd_actions_row").put("component", "Row")
                    .put("children", JSONArray().put("fd_ack_btn").put("fd_ext_btn").put("fd_vip_btn"))
            )
        }
        val data = JSONObject()
            .put("sessionId", digest.sessionId)
            .put("titleKo", proposal.titleKo)
            .put("line1", digest.line1DecisionsKo)
            .put("line2", digest.line2ActionNeededKo)
            .put("line3", digest.line3ChatterSummaryKo)

        val jsonl = listOf(
            JSONObject().put("version", A2uiProtocolConstants.VERSION_1_0).put(
                "createSurface",
                JSONObject().put("surfaceId", surfaceId).put("catalogId", A2uiProtocolConstants.BASIC_CATALOG_V1_0).put("sendDataModel", true)
            ),
            JSONObject().put("version", A2uiProtocolConstants.VERSION_1_0).put(
                "updateComponents",
                JSONObject().put("surfaceId", surfaceId).put("components", components)
            ),
            JSONObject().put("version", A2uiProtocolConstants.VERSION_1_0).put(
                "updateDataModel",
                JSONObject().put("surfaceId", surfaceId).put("path", "/focus").put("value", data)
            )
        ).joinToString("\n") { it.toString() }

        if (publishToRegistry) {
            A2uiSurfaceRegistry.ingestJsonl(jsonl)
        }
        return proposal to jsonl
    }

    fun resetForTesting() {
        synchronized(lock) {
            _config.value = StormShieldConfig()
            roomTimestamps.clear()
            heldMessages.clear()
        }
    }
}
