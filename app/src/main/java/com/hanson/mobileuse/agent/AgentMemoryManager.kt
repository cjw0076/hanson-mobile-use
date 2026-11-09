package com.hanson.mobileuse.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class SemanticMemoryEntry(
    val id: String,
    val category: String, // "PREFERENCE", "CONTACT_ROOM", "FREQUENT_APP", "EPISODE", "FACT"
    val titleKo: String,
    val contentKo: String,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category)
        put("titleKo", titleKo)
        put("contentKo", contentKo)
        put("updatedAtMs", updatedAtMs)
    }

    companion object {
        fun fromJson(obj: JSONObject): SemanticMemoryEntry = SemanticMemoryEntry(
            id = obj.optString("id", "mem_${System.nanoTime()}"),
            category = obj.optString("category", "FACT"),
            titleKo = obj.optString("titleKo", ""),
            contentKo = obj.optString("contentKo", ""),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }
}

data class MemoryTelemetryState(
    val jvmUsedMb: Int = 0,
    val jvmMaxMb: Int = 0,
    val deviceAvailRamMb: Int = 0,
    val deviceTotalRamMb: Int = 0,
    val activeChatTurns: Int = 0,
    val compactedDigestSummary: String = "",
    val semanticEntries: List<SemanticMemoryEntry> = emptyList(),
    val trimmedSnapshotCount: Int = 0,
    val lastOptimizedAtMs: Long = 0L
)

/**
 * 3-Tier Cognitive & Runtime Memory Manager (`AgentMemoryManager`).
 *
 * Manages both AI Context/Semantic Memory and Android App RAM/Snapshot Memory:
 * 1. **Tier 1 — Working Context Auto-Compaction**:
 *    Compresses older chat turns and execution steps into a compact episodic digest
 *    so multi-model API calls remain low-latency and token-efficient.
 * 2. **Tier 2 — Persistent Semantic & Episodic Vault**:
 *    Extracts and remembers user preferences, frequent chat rooms/contacts, favorite apps,
 *    and completed task episodes across sessions.
 * 3. **Tier 3 — Runtime JVM Heap & Snapshot Bitmap Guard**:
 *    Monitors JVM heap & `/proc/meminfo`, strips heavy Base64 screenshots from older
 *    `AgentStepLog` items (retaining only the latest 2 turns in RAM), and runs 1-touch GC/cache cleanup.
 */
class AgentMemoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "hanson_cognitive_memory_vault_v1"
        private const val KEY_SEMANTIC_ENTRIES = "semantic_entries_json"
        private const val KEY_COMPACTED_DIGEST = "compacted_working_digest"
        private const val MAX_SEMANTIC_ENTRIES = 40
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(MemoryTelemetryState())
    val state: StateFlow<MemoryTelemetryState> = _state.asStateFlow()

    private var totalTrimmedSnapshots = 0

    init {
        loadFromDisk()
        refreshRuntimeMemoryMetrics(activeChatCount = 0)
    }

    fun refreshRuntimeMemoryMetrics(activeChatCount: Int = _state.value.activeChatTurns) {
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt().coerceAtLeast(1)
        val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt().coerceAtLeast(64)
        val (availSysMb, totalSysMb) = readSystemMemInfoMb()

        _state.update {
            it.copy(
                jvmUsedMb = usedMb,
                jvmMaxMb = maxMb,
                deviceAvailRamMb = availSysMb,
                deviceTotalRamMb = totalSysMb,
                activeChatTurns = activeChatCount,
                trimmedSnapshotCount = totalTrimmedSnapshots
            )
        }
    }

    /**
     * Automatically learns user intents, frequent apps, chat rooms, or explicit memory requests
     * from a user utterance and task summary.
     */
    fun observeAndLearnFromTurn(userPrompt: String, assistantSummary: String) {
        val p = userPrompt.trim()
        val pLower = p.lowercase(Locale.ROOT)
        if (p.length < 3) return

        val currentEntries = _state.value.semanticEntries.toMutableList()

        // 1. Explicit "기억해" / "외워둬" / "메모해"
        if (pLower.contains("기억해") || pLower.contains("외워") || pLower.contains("잊지 마")) {
            val fact = p.replace(Regex("(꼭\\s*)?(기억해\\s*줘|기억해\\s*둬|기억해|외워\\s*둬|외워\\s*줘)"), "").trim()
            if (fact.isNotBlank()) {
                upsertEntry(
                    currentEntries,
                    SemanticMemoryEntry(
                        id = "pref_${fact.hashCode()}",
                        category = "PREFERENCE",
                        titleKo = "사용자 지정 기억",
                        contentKo = fact.take(120)
                    )
                )
            }
        }

        // 2. Frequent KakaoTalk room / contact learning
        if (pLower.contains("카톡") || pLower.contains("단톡방") || pLower.contains("대화방")) {
            val roomMatch = Regex("([가-힣a-zA-Z0-9_]{2,15})\\s*(카톡방|단톡방|대화방|채팅방)").find(p)?.groupValues?.getOrNull(1)
            if (!roomMatch.isNullOrBlank()) {
                upsertEntry(
                    currentEntries,
                    SemanticMemoryEntry(
                        id = "room_$roomMatch",
                        category = "CONTACT_ROOM",
                        titleKo = "자주 찾는 대화방",
                        contentKo = "$roomMatch 대화방"
                    )
                )
            }
        }

        // 3. Frequent Web / News source learning
        if (pLower.contains("해커뉴스") || pLower.contains("hacker news")) {
            upsertEntry(
                currentEntries,
                SemanticMemoryEntry(
                    id = "interest_hn",
                    category = "PREFERENCE",
                    titleKo = "관심 뉴스 소스",
                    contentKo = "Hacker News (news.ycombinator.com) 기술 트렌드 요약"
                )
            )
        }

        // 4. Record recent completed episode summary (keep compact)
        val cleanSummary = assistantSummary.lines().firstOrNull { it.isNotBlank() }?.take(90).orEmpty()
        if (cleanSummary.isNotBlank() && !pLower.contains("메모리")) {
            upsertEntry(
                currentEntries,
                SemanticMemoryEntry(
                    id = "ep_latest",
                    category = "EPISODE",
                    titleKo = "최근 완료 작업",
                    contentKo = "${p.take(32)} → $cleanSummary"
                )
            )
        }

        val bounded = currentEntries.sortedByDescending { it.updatedAtMs }.take(MAX_SEMANTIC_ENTRIES)
        _state.update { it.copy(semanticEntries = bounded) }
        saveToDisk(bounded, _state.value.compactedDigestSummary)
    }

    /**
     * Compacts long chat histories (>10 messages) into a rolling `compactedDigestSummary`
     * and returns the trimmed list of messages so the active UI and LLM context stay fast.
     */
    fun compactChatHistoryIfNeeded(
        messages: List<LiveChatMessage>,
        keepRecentCount: Int = 14
    ): List<LiveChatMessage> {
        refreshRuntimeMemoryMetrics(activeChatCount = messages.size)
        if (messages.size <= keepRecentCount) return messages

        val older = messages.dropLast(keepRecentCount)
        val recent = messages.takeLast(keepRecentCount)

        val digestLines = older
            .filter { it.sender == ChatSender.USER || it.sender == ChatSender.AGENT }
            .takeLast(8)
            .joinToString(" | ") { msg ->
                val prefix = if (msg.sender == ChatSender.USER) "U" else "A"
                "$prefix:${msg.text.replace("\n", " ").take(42)}"
            }

        val combinedDigest = if (_state.value.compactedDigestSummary.isBlank()) {
            digestLines
        } else {
            ("${_state.value.compactedDigestSummary} | $digestLines").takeLast(360)
        }

        _state.update {
            it.copy(
                compactedDigestSummary = combinedDigest,
                activeChatTurns = recent.size
            )
        }
        saveToDisk(_state.value.semanticEntries, combinedDigest)
        return recent
    }

    /**
     * Strips heavy Base64 JPEG screenshots from older `AgentStepLog` entries in a session,
     * keeping only the last [keepRecentScreenshots] step screenshots in RAM to prevent OOM.
     */
    fun trimStepSnapshotsInPlace(
        steps: List<AgentStepLog>,
        keepRecentScreenshots: Int = 2
    ): List<AgentStepLog> {
        if (steps.size <= keepRecentScreenshots) return steps
        val cutoffIndex = steps.size - keepRecentScreenshots
        var newlyTrimmed = 0
        val result = steps.mapIndexed { idx, step ->
            if (idx < cutoffIndex && !step.screenshotBase64.isNullOrEmpty()) {
                newlyTrimmed++
                step.copy(screenshotBase64 = null)
            } else {
                step
            }
        }
        if (newlyTrimmed > 0) {
            totalTrimmedSnapshots += newlyTrimmed
            _state.update { it.copy(trimmedSnapshotCount = totalTrimmedSnapshots) }
        }
        return result
    }

    /**
     * Performs an immediate 3-Tier Memory Optimization (Working Context Compaction + Temp Cache Sweep + JVM GC).
     */
    fun optimizeMemoryNow(
        currentMessages: List<LiveChatMessage>,
        onUpdateMessages: (List<LiveChatMessage>) -> Unit
    ): StructuredUiCard {
        val beforeUsedMb = _state.value.jvmUsedMb.coerceAtLeast(1)
        val compacted = compactChatHistoryIfNeeded(currentMessages, keepRecentCount = 8)
        onUpdateMessages(compacted)

        // Clean temporary screenshot / dump files in cacheDir
        var freedFiles = 0
        runCatching {
            appContext.cacheDir?.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".jpg") || f.name.endsWith(".png") || f.name.endsWith(".tmp"))) {
                    if (f.delete()) freedFiles++
                }
            }
        }

        System.gc()
        refreshRuntimeMemoryMetrics(activeChatCount = compacted.size)
        val afterUsedMb = _state.value.jvmUsedMb.coerceAtLeast(1)
        val now = System.currentTimeMillis()
        _state.update { it.copy(lastOptimizedAtMs = now) }

        return buildMemoryDashboardCard(
            headerTitle = "3-Tier 메모리 최적화 완료",
            headerSubtitle = "JVM 힙 ${beforeUsedMb}MB → ${afterUsedMb}MB · 장기 기억 ${_state.value.semanticEntries.size}건 유지"
        )
    }

    fun buildMemoryDashboardCard(
        headerTitle: String = "에이전트 3-Tier 메모리 상태",
        headerSubtitle: String = "단기 컨텍스트 · 장기 시맨틱 볼트 · 런타임 RAM 가드"
    ): StructuredUiCard {
        refreshRuntimeMemoryMetrics()
        val s = _state.value
        val items = mutableListOf(
            StructuredUiItem(
                badge = "RAM",
                title = "앱 런타임 힙: ${s.jvmUsedMb}MB / ${s.jvmMaxMb}MB",
                subtitle = if (s.deviceTotalRamMb > 0) {
                    "기기 가용 RAM: ${s.deviceAvailRamMb}MB / ${s.deviceTotalRamMb}MB"
                } else {
                    "스냅샷 자동 해제: ${s.trimmedSnapshotCount}건"
                },
                meta = "${((s.jvmUsedMb * 100) / s.jvmMaxMb.coerceAtLeast(1))}%"
            ),
            StructuredUiItem(
                badge = "CTX",
                title = "단기 작업 컨텍스트: 최근 ${s.activeChatTurns}턴 유지",
                subtitle = if (s.compactedDigestSummary.isNotBlank()) {
                    "압축 요약본 활성 (${s.compactedDigestSummary.length}자)"
                } else {
                    "초과 대화 발생 시 자동 요약 압축"
                },
                meta = "T1-COMPACT"
            ),
            StructuredUiItem(
                badge = "VAULT",
                title = "장기 시맨틱 기억: ${s.semanticEntries.size}건 저장됨",
                subtitle = s.semanticEntries.take(2).joinToString(" · ") { "${it.titleKo}: ${it.contentKo}" }
                    .ifBlank { "자주 쓰는 대화방·선호도·최근 작업을 자동 학습합니다" },
                meta = "T2-SEMANTIC"
            )
        )
        return StructuredUiCard(
            templateType = StructuredUiTemplateType.MEMORY_DASHBOARD,
            headerTitle = headerTitle,
            headerSubtitle = headerSubtitle,
            items = items,
            footerHint = "탭하여 저장된 장기 기억 목록 및 상세 메모리 리포트 보기 ↗"
        )
    }

    fun buildMemoryPromptInjection(): String {
        val s = _state.value
        if (s.semanticEntries.isEmpty() && s.compactedDigestSummary.isBlank()) return ""
        return buildString {
            if (s.compactedDigestSummary.isNotBlank()) {
                appendLine("Compacted Earlier Conversation Digest: ${s.compactedDigestSummary}")
            }
            if (s.semanticEntries.isNotEmpty()) {
                appendLine("User Semantic Memory Vault:")
                s.semanticEntries.take(6).forEach { entry ->
                    appendLine("- [${entry.category}] ${entry.titleKo}: ${entry.contentKo}")
                }
            }
        }.trim()
    }

    fun clearAllSemanticMemories() {
        _state.update { it.copy(semanticEntries = emptyList(), compactedDigestSummary = "") }
        prefs.edit().clear().apply()
    }

    private fun upsertEntry(list: MutableList<SemanticMemoryEntry>, entry: SemanticMemoryEntry) {
        val idx = list.indexOfFirst { it.id == entry.id || (it.category == entry.category && it.titleKo == entry.titleKo && it.category == "EPISODE") }
        if (idx >= 0) {
            list[idx] = entry
        } else {
            list.add(0, entry)
        }
    }

    private fun readSystemMemInfoMb(): Pair<Int, Int> {
        return runCatching {
            val lines = File("/proc/meminfo").readLines()
            var totalKb = 0L
            var availKb = 0L
            for (line in lines) {
                if (line.startsWith("MemTotal:")) {
                    totalKb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                } else if (line.startsWith("MemAvailable:")) {
                    availKb = line.substringAfter(":").trim().substringBefore(" ").toLongOrNull() ?: 0L
                }
            }
            ((availKb / 1024).toInt()) to ((totalKb / 1024).toInt())
        }.getOrDefault(0 to 0)
    }

    private fun loadFromDisk() {
        val rawArr = prefs.getString(KEY_SEMANTIC_ENTRIES, null)
        val digest = prefs.getString(KEY_COMPACTED_DIGEST, "").orEmpty()
        val parsed = mutableListOf<SemanticMemoryEntry>()
        if (!rawArr.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(rawArr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parsed.add(SemanticMemoryEntry.fromJson(obj))
                }
            }
        }
        _state.update {
            it.copy(
                semanticEntries = parsed,
                compactedDigestSummary = digest
            )
        }
    }

    private fun saveToDisk(entries: List<SemanticMemoryEntry>, digest: String) {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_SEMANTIC_ENTRIES, arr.toString())
            .putString(KEY_COMPACTED_DIGEST, digest)
            .apply()
    }
}
