package com.hanson.mobileuse.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Live connection & streaming lifecycle phase for both internal LLM SSE streams and
 * external SSE feeds.
 */
enum class SseStreamPhase(val labelKo: String) {
    IDLE("대기"),
    CONNECTING("연결 중"),
    STREAMING("실시간 수신"),
    RECONNECTING("재연결 중"),
    COMPLETED("완료"),
    ERROR("연결 오류")
}

/**
 * Interactive Dynamic UI widgets that can arrive dynamically inside an SSE stream alongside
 * standard [BoardEventCommand] items (Ahaboard Quiz / Choice Cards + Live Checklists).
 */
sealed class DynamicUiWidget {
    abstract val id: String

    data class QuizCard(
        override val id: String,
        val question: String,
        val options: List<String>,
        val correctIndex: Int = -1,
        val selectedIndex: Int? = null
    ) : DynamicUiWidget()

    data class ChecklistItem(
        override val id: String,
        val label: String,
        val checked: Boolean = false
    ) : DynamicUiWidget()
}

/**
 * Envelope published on the unified [DynamicUiEventBus] and serialized over
 * `GET /sse/board` (`text/event-stream`) with strictly monotonic [sequenceId] for `Last-Event-ID`.
 */
data class DynamicSseEnvelope(
    val sequenceId: Long,
    val eventType: String,
    val payloadJson: String,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toSseWireFrame(): String = buildString {
        append("id: ").append(sequenceId).append('\n')
        append("event: ").append(eventType).append('\n')
        payloadJson.lines().forEach { line ->
            append("data: ").append(line).append('\n')
        }
        append('\n')
    }
}

/**
 * Reactive snapshot observed by BOTH in-app Compose screens (`CommandCenterScreen`) AND
 * out-of-app `SYSTEM_ALERT_WINDOW` Compose overlays (`DynamicCanvasOverlayService`).
 */
data class DynamicUiSurfaceState(
    val streamId: String = "",
    val spaceId: String = "live_board",
    val turnIndex: Int = 0,
    val phase: SseStreamPhase = SseStreamPhase.IDLE,
    val sourceLabel: String = "",
    val rawAccumulatedText: String = "",
    val visualText: String = "",
    val activeCaptionText: String = "",
    val activeElementId: String? = null,
    val events: List<BoardStreamEvent> = emptyList(),
    val widgets: List<DynamicUiWidget> = emptyList(),
    val citations: List<StructuredUiItem> = emptyList(),
    val lastSequenceId: Long = 0L,
    val errorReason: String? = null
)

/**
 * RFC 8895 compliant incremental Server-Sent Events (`text/event-stream`) parser.
 * Safe across arbitrary line/chunk boundaries and supports `id:`, `event:`, multi-line `data:`,
 * `retry:`, and `:comment` keepalive frames.
 */
data class ParsedSseFrame(
    val id: String? = null,
    val event: String = "message",
    val data: String = "",
    val retryMs: Long? = null
)

class SseLineParser {
    private var currentId: String? = null
    private var currentEvent: String? = null
    private val dataLines = mutableListOf<String>()
    private var retryMs: Long? = null

    /**
     * Feeds a single line (without trailing `\r`/`\n`). Returns a [ParsedSseFrame] when an empty
     * line dispatches an event, or `null` while still accumulating fields.
     */
    fun feedLine(rawLine: String): ParsedSseFrame? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) {
            return flushPendingFrame()
        }
        if (line.startsWith(":")) {
            // Comment / keepalive heartbeat
            return null
        }
        val colonIdx = line.indexOf(':')
        val field: String
        val value: String
        if (colonIdx == -1) {
            field = line
            value = ""
        } else {
            field = line.substring(0, colonIdx)
            val after = line.substring(colonIdx + 1)
            value = if (after.startsWith(" ")) after.substring(1) else after
        }

        when (field) {
            "id" -> if (!value.contains('\u0000')) currentId = value
            "event" -> currentEvent = value.ifBlank { "message" }
            "data" -> dataLines.add(value)
            "retry" -> value.toLongOrNull()?.takeIf { it >= 0L }?.let { retryMs = it }
        }
        return null
    }

    /** Flushes any trailing buffered frame when the underlying stream reaches EOF cleanly. */
    fun flushPendingFrame(): ParsedSseFrame? {
        if (dataLines.isEmpty() && currentEvent == null && retryMs == null) {
            return null
        }
        val frame = ParsedSseFrame(
            id = currentId,
            event = currentEvent ?: "message",
            data = dataLines.joinToString("\n"),
            retryMs = retryMs
        )
        currentEvent = null
        dataLines.clear()
        retryMs = null
        return frame
    }
}

/**
 * Incremental streaming compiler converting live LLM deltas (`SPEAK:`, `BOARD:`, `QUIZ:`, `CHECK:`,
 * `CODRAW:`) into progressively rendered [BoardStreamEvent]s, [DynamicUiWidget]s, and [CoDrawElement]s
 * BEFORE the LLM finishes generating the full response.
 */
object IncrementalBoardStreamCompiler {

    private val QUIZ_LINE_REGEX = Regex("""^\s*QUIZ\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val CHECK_LINE_REGEX = Regex("""^\s*CHECK\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val CODRAW_LINE_REGEX = Regex("""^\s*CODRAW\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)

    data class IncrementalCompileOutput(
        val script: BoardEventScript,
        val explicitSpeak: String?,
        val widgets: List<DynamicUiWidget>,
        val coDrawElements: List<CoDrawElement>
    )

    fun compileIncremental(
        accumulatedRaw: String,
        citations: List<StructuredUiItem> = emptyList(),
        turnIndex: Int = 0,
        eyebrowOverride: String = "실시간 SSE 브리핑"
    ): IncrementalCompileOutput {
        val visualAndSpeakLines = mutableListOf<String>()
        val widgets = mutableListOf<DynamicUiWidget>()
        val coDrawElements = mutableListOf<CoDrawElement>()
        var quizIdx = 0
        var checkIdx = 0
        var coDrawIdx = 0

        for (rawLine in accumulatedRaw.lines()) {
            val trimmed = rawLine.trim()
            val quizMatch = QUIZ_LINE_REGEX.matchEntire(trimmed)
            val checkMatch = CHECK_LINE_REGEX.matchEntire(trimmed)
            when {
                quizMatch != null -> {
                    val parts = quizMatch.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    if (parts.size >= 3) {
                        val question = parts[0]
                        val maybeCorrect = parts.last().toIntOrNull()
                        val options = if (maybeCorrect != null && parts.size >= 4) {
                            parts.subList(1, parts.size - 1)
                        } else {
                            parts.subList(1, parts.size)
                        }
                        val correctIdx = maybeCorrect?.takeIf { it in options.indices } ?: -1
                        widgets.add(
                            DynamicUiWidget.QuizCard(
                                id = "quiz_${turnIndex}_${quizIdx++}",
                                question = question,
                                options = options.take(4),
                                correctIndex = correctIdx
                            )
                        )
                    }
                }
                checkMatch != null -> {
                    val label = checkMatch.groupValues[1].trim()
                    if (label.isNotEmpty()) {
                        widgets.add(
                            DynamicUiWidget.ChecklistItem(
                                id = "check_${turnIndex}_${checkIdx++}",
                                label = label,
                                checked = false
                            )
                        )
                    }
                }
                CODRAW_LINE_REGEX.matches(trimmed) -> {
                    coDrawIdx++
                    CoDrawingCanvasEngine.parseCoDrawDirectiveLine(trimmed, stepNumber = coDrawIdx)
                        ?.let { coDrawElements.add(it) }
                }
                else -> visualAndSpeakLines.add(rawLine)
            }
        }

        val cleanedStreamText = visualAndSpeakLines.joinToString("\n")
        val (explicitSpeak, _) = BoardEventStreamCompiler.splitSpeakAndVisual(cleanedStreamText)
        val script = BoardEventStreamCompiler.compile(
            rawText = cleanedStreamText,
            citations = citations,
            turnIndex = turnIndex,
            eyebrowOverride = eyebrowOverride
        )

        return IncrementalCompileOutput(
            script = script,
            explicitSpeak = explicitSpeak,
            widgets = widgets,
            coDrawElements = coDrawElements
        )
    }
}

/**
 * Unified Dual-Channel Event Bus powering:
 * - In-app `SseLiveDynamicUiSurface` (Jetpack Compose)
 * - Out-of-app `DynamicCanvasOverlayService` (floating `ComposeView` over other apps)
 * - Outgoing `ArtemisCommandServer` `GET /sse/board?from=<seq>` SSE clients
 *
 * Maintains a bounded 256-event ring buffer for lossless `Last-Event-ID` replay.
 */
object DynamicUiEventBus {

    private const val MAX_HISTORY = 256

    private val seqGenerator = AtomicLong(0L)
    private val historyLock = Any()
    private val historyRing = ArrayDeque<DynamicSseEnvelope>()
    private val appliedCoDrawIds = LinkedHashSet<String>()

    private val _envelopes = MutableSharedFlow<DynamicSseEnvelope>(
        replay = 0,
        extraBufferCapacity = 128
    )
    val envelopes: SharedFlow<DynamicSseEnvelope> = _envelopes.asSharedFlow()

    private val _surfaceState = MutableStateFlow(DynamicUiSurfaceState())
    val surfaceState: StateFlow<DynamicUiSurfaceState> = _surfaceState.asStateFlow()

    /** Starts a fresh streaming turn (resets active incremental buffer while advancing sequence IDs). */
    fun beginStream(
        sourceLabel: String,
        spaceId: String = "live_board",
        turnIndex: Int = (_surfaceState.value.turnIndex + 1)
    ): String {
        val streamId = "sse_${System.currentTimeMillis()}_${turnIndex}"
        synchronized(historyLock) {
            appliedCoDrawIds.clear()
        }
        _surfaceState.value = DynamicUiSurfaceState(
            streamId = streamId,
            spaceId = spaceId,
            turnIndex = turnIndex,
            phase = SseStreamPhase.STREAMING,
            sourceLabel = sourceLabel,
            lastSequenceId = seqGenerator.get()
        )
        publishEnvelope(
            eventType = "stream_start",
            payload = JSONObject()
                .put("streamId", streamId)
                .put("spaceId", spaceId)
                .put("turnIndex", turnIndex)
                .put("sourceLabel", sourceLabel)
        )
        return streamId
    }

    /** Appends an incremental LLM text chunk during live SSE generation and re-compiles the Dynamic UI live. */
    fun appendStreamingDelta(
        deltaText: String,
        citations: List<StructuredUiItem> = emptyList()
    ) {
        if (deltaText.isEmpty()) return
        val prev = _surfaceState.value
        val nextRaw = prev.rawAccumulatedText + deltaText
        val compiled = IncrementalBoardStreamCompiler.compileIncremental(
            accumulatedRaw = nextRaw,
            citations = citations.ifEmpty { prev.citations },
            turnIndex = prev.turnIndex,
            eyebrowOverride = prev.sourceLabel.ifBlank { "실시간 SSE 브리핑" }
        )

        // Emit any newly completed CODRAW directives straight onto CoDrawingCanvasEngine
        compiled.coDrawElements.forEachIndexed { idx, elem ->
            val stableId = "stream_${prev.turnIndex}_cd_$idx"
            val shouldAdd = synchronized(historyLock) { appliedCoDrawIds.add(stableId) }
            if (shouldAdd) {
                val anchored = elem.copy(id = stableId)
                CoDrawingCanvasEngine.addAiElement(anchored)
                publishEnvelope("codraw_command", anchored.toJson())
            }
        }

        val caption = compiled.explicitSpeak
            ?: compiled.script.events.mapNotNull { it.command as? BoardEventCommand.Speak }.lastOrNull()?.text
            ?: prev.activeCaptionText
        val activeEl = compiled.script.events.lastOrNull()?.elementId ?: prev.activeElementId

        val preservedWidgets = mergeWidgetSelections(prev.widgets, compiled.widgets)
        val seq = publishEnvelope(
            eventType = "ui_delta",
            payload = JSONObject()
                .put("turnIndex", prev.turnIndex)
                .put("spaceId", compiled.script.spaceId)
                .put("eventCount", compiled.script.events.size)
                .put("caption", caption)
                .put("delta", deltaText)
        )

        _surfaceState.value = prev.copy(
            spaceId = compiled.script.spaceId,
            phase = SseStreamPhase.STREAMING,
            rawAccumulatedText = nextRaw,
            visualText = compiled.script.visualBodyText,
            activeCaptionText = caption,
            activeElementId = activeEl,
            events = compiled.script.events,
            widgets = preservedWidgets,
            citations = citations.ifEmpty { prev.citations },
            lastSequenceId = seq,
            errorReason = null
        )
    }

    /** Finalizes a streaming or compiled turn and publishes all canonical `tutor_command` frames. */
    fun completeStreamWithScript(
        rawText: String,
        citations: List<StructuredUiItem> = emptyList(),
        sourceLabel: String = ""
    ): BoardEventScript {
        val prev = _surfaceState.value
        val compiled = IncrementalBoardStreamCompiler.compileIncremental(
            accumulatedRaw = rawText,
            citations = citations.ifEmpty { prev.citations },
            turnIndex = prev.turnIndex,
            eyebrowOverride = sourceLabel.ifBlank { prev.sourceLabel.ifBlank { "실시간 SSE 브리핑" } }
        )

        compiled.coDrawElements.forEachIndexed { idx, elem ->
            val stableId = "stream_${prev.turnIndex}_cd_$idx"
            val shouldAdd = synchronized(historyLock) { appliedCoDrawIds.add(stableId) }
            if (shouldAdd) {
                val anchored = elem.copy(id = stableId)
                CoDrawingCanvasEngine.addAiElement(anchored)
                publishEnvelope("codraw_command", anchored.toJson())
            }
        }

        val jsonArr = compiled.script.toJsonArray()
        var lastSeq = seqGenerator.get()
        for (i in 0 until jsonArr.length()) {
            lastSeq = publishEnvelope("tutor_command", jsonArr.getJSONObject(i))
        }
        lastSeq = publishEnvelope(
            eventType = "stream_complete",
            payload = JSONObject()
                .put("turnIndex", prev.turnIndex)
                .put("spaceId", compiled.script.spaceId)
                .put("totalEvents", compiled.script.events.size)
                .put("spokenText", compiled.script.combinedSpokenText)
        )

        val preservedWidgets = mergeWidgetSelections(prev.widgets, compiled.widgets)
        _surfaceState.value = prev.copy(
            spaceId = compiled.script.spaceId,
            phase = SseStreamPhase.COMPLETED,
            sourceLabel = sourceLabel.ifBlank { prev.sourceLabel },
            rawAccumulatedText = rawText,
            visualText = compiled.script.visualBodyText,
            activeCaptionText = compiled.explicitSpeak ?: compiled.script.combinedSpokenText,
            activeElementId = compiled.script.events.firstOrNull()?.elementId,
            events = compiled.script.events,
            widgets = preservedWidgets,
            citations = citations.ifEmpty { prev.citations },
            lastSequenceId = lastSeq,
            errorReason = null
        )
        return compiled.script
    }

    /** Marks the active stream as errored transparently (never swallows failures). */
    fun failStream(reason: String) {
        val clean = reason.trim().ifBlank { "실시간 스트림 연결에 실패했어요." }
        val seq = publishEnvelope("stream_error", JSONObject().put("error", clean))
        _surfaceState.update {
            it.copy(phase = SseStreamPhase.ERROR, errorReason = clean, lastSequenceId = seq)
        }
    }

    /**
     * Ingests an external `tutor_command`, `ui_widget`, or `codraw_command` JSON payload
     * (from `POST :18888/board/events`, `POST :18888/codraw/command`, or [ExternalSseStreamClient]).
     */
    fun ingestExternalJsonEvent(eventType: String, payload: JSONObject): Boolean {
        val normalizedType = eventType.trim().lowercase()
        val nameField = payload.optString("name", "").trim().lowercase()

        if (normalizedType == "codraw_command" || nameField == "codraw_command") {
            val elem = CoDrawElement.fromJson(payload) ?: return false
            val added = CoDrawingCanvasEngine.addAiElement(elem)
            val seq = publishEnvelope("codraw_command", added.toJson())
            _surfaceState.update { it.copy(lastSequenceId = seq) }
            return true
        }

        if (normalizedType == "ui_widget" || nameField == "ui_widget") {
            val widgetType = payload.optString("widgetType", "").trim().uppercase()
            val id = payload.optString("id", "w_${System.currentTimeMillis()}")
            val widget: DynamicUiWidget = when (widgetType) {
                "QUIZ" -> {
                    val q = payload.optString("question", "").trim()
                    val arr = payload.optJSONArray("options") ?: JSONArray()
                    val opts = (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotEmpty) }
                    if (q.isBlank() || opts.size < 2) return false
                    DynamicUiWidget.QuizCard(
                        id = id,
                        question = q,
                        options = opts.take(4),
                        correctIndex = payload.optInt("correctIndex", -1)
                    )
                }
                "CHECKLIST" -> {
                    val label = payload.optString("label", "").trim()
                    if (label.isBlank()) return false
                    DynamicUiWidget.ChecklistItem(
                        id = id,
                        label = label,
                        checked = payload.optBoolean("checked", false)
                    )
                }
                else -> return false
            }
            val seq = publishEnvelope("ui_widget", payload)
            _surfaceState.update { prev ->
                prev.copy(
                    phase = SseStreamPhase.STREAMING,
                    widgets = prev.widgets.filterNot { it.id == widget.id } + widget,
                    lastSequenceId = seq
                )
            }
            return true
        }

        // Standard Ahaboard-compatible `tutor_command` envelope
        val cmdName = payload.optString("commandName", "").trim().lowercase()
        val posObj = payload.optJSONObject("position")
        val pos = BoardPosition(
            spaceId = posObj?.optString("spaceId", "external_board") ?: "external_board",
            columnId = posObj?.optString("columnId", "main") ?: "main"
        )
        val turnIdx = payload.optInt("turnIndex", _surfaceState.value.turnIndex)
        val orderIdx = payload.optInt("orderIndex", _surfaceState.value.events.size)
        val elementId = payload.optString("elementId", "ext_$orderIdx")

        val cmd: BoardEventCommand = when (cmdName) {
            "write_headline" -> BoardEventCommand.WriteHeadline(
                eyebrow = payload.optString("eyebrow", "외부 SSE 보드"),
                headline = payload.optString("text", "").trim()
            )
            "add_stat_pill" -> BoardEventCommand.AddStatPill(
                label = payload.optString("label", "").trim(),
                value = payload.optString("value", "").trim()
            )
            "add_takeaway" -> BoardEventCommand.AddTakeaway(
                index = payload.optInt("index", orderIdx + 1),
                keyPrefix = payload.optString("keyPrefix", "").trim(),
                body = payload.optString("text", "").trim()
            )
            "write_note", "write_text" -> BoardEventCommand.WriteNote(
                text = payload.optString("text", "").trim()
            )
            "add_citation" -> {
                val url = payload.optString("url", "").trim()
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://127.0.0.1")) {
                    return false
                }
                BoardEventCommand.AddCitation(
                    badge = payload.optString("badge", "01"),
                    title = payload.optString("title", "출처"),
                    subtitle = payload.optString("subtitle", ""),
                    verifiedUrl = url
                )
            }
            "speak" -> BoardEventCommand.Speak(
                text = RichAnswerBoardFormatter.formatForNaturalSpeech(payload.optString("text", ""), maxLength = 240),
                style = payload.optString("style", "warm"),
                linkedElementId = payload.optString("linkedElementId", "").takeIf { it.isNotBlank() }
            )
            else -> return false
        }

        val event = BoardStreamEvent(
            turnIndex = turnIdx,
            orderIndex = orderIdx,
            elementId = elementId,
            position = pos,
            command = cmd
        )
        val seq = publishEnvelope("tutor_command", payload)
        _surfaceState.update { prev ->
            val nextEvents = (prev.events.filterNot { it.elementId == event.elementId && it.turnIndex == event.turnIndex } + event)
                .sortedWith(compareBy<BoardStreamEvent> { it.turnIndex }.thenBy { it.orderIndex })
            val nextCaption = (cmd as? BoardEventCommand.Speak)?.text ?: prev.activeCaptionText
            val nextActiveId = (cmd as? BoardEventCommand.Speak)?.linkedElementId ?: event.elementId
            prev.copy(
                spaceId = pos.spaceId,
                turnIndex = turnIdx,
                phase = SseStreamPhase.STREAMING,
                sourceLabel = prev.sourceLabel.ifBlank { "외부 SSE 스트림" },
                events = nextEvents,
                activeCaptionText = nextCaption,
                activeElementId = nextActiveId,
                lastSequenceId = seq,
                errorReason = null
            )
        }
        return true
    }

    /** Interactive handler when the user taps an answer on an SSE-rendered Quiz card. */
    fun selectQuizOption(widgetId: String, optionIndex: Int) {
        _surfaceState.update { prev ->
            prev.copy(
                widgets = prev.widgets.map { w ->
                    if (w is DynamicUiWidget.QuizCard && w.id == widgetId) {
                        w.copy(selectedIndex = optionIndex)
                    } else w
                }
            )
        }
    }

    /** Interactive handler when the user toggles an SSE-rendered Checklist item. */
    fun toggleChecklistItem(widgetId: String) {
        _surfaceState.update { prev ->
            prev.copy(
                widgets = prev.widgets.map { w ->
                    if (w is DynamicUiWidget.ChecklistItem && w.id == widgetId) {
                        w.copy(checked = !w.checked)
                    } else w
                }
            )
        }
    }

    /** Returns all buffered SSE envelopes strictly newer than [fromSequenceId] for `Last-Event-ID` replay. */
    fun replayEnvelopesSince(fromSequenceId: Long): List<DynamicSseEnvelope> {
        synchronized(historyLock) {
            return historyRing.filter { it.sequenceId > fromSequenceId }
        }
    }

    /** Clears state and ring buffer for unit tests or user-initiated reset. */
    fun reset() {
        synchronized(historyLock) {
            historyRing.clear()
            appliedCoDrawIds.clear()
        }
        _surfaceState.value = DynamicUiSurfaceState()
    }

    private fun publishEnvelope(eventType: String, payload: JSONObject): Long {
        val seq = seqGenerator.incrementAndGet()
        val envelope = DynamicSseEnvelope(
            sequenceId = seq,
            eventType = eventType,
            payloadJson = payload.toString()
        )
        synchronized(historyLock) {
            if (historyRing.size >= MAX_HISTORY) {
                historyRing.removeFirst()
            }
            historyRing.addLast(envelope)
        }
        _envelopes.tryEmit(envelope)
        return seq
    }

    private fun mergeWidgetSelections(
        previous: List<DynamicUiWidget>,
        incoming: List<DynamicUiWidget>
    ): List<DynamicUiWidget> {
        val prevById = previous.associateBy { it.id }
        return incoming.map { w ->
            val existing = prevById[w.id]
            when {
                w is DynamicUiWidget.QuizCard && existing is DynamicUiWidget.QuizCard ->
                    w.copy(selectedIndex = existing.selectedIndex)
                w is DynamicUiWidget.ChecklistItem && existing is DynamicUiWidget.ChecklistItem ->
                    w.copy(checked = existing.checked)
                else -> w
            }
        }
    }
}

/**
 * Security-hardened external SSE subscriber with `Last-Event-ID` resume and exponential backoff.
 * Allows ONLY `https://` endpoints or explicit loopback (`http://127.0.0.1` / `http://localhost`).
 */
object ExternalSseStreamClient {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite read timeout for long-lived SSE
        .build()

    @Volatile
    private var activeJob: Job? = null

    /** Security gate: reject non-HTTPS remote URLs to prevent cleartext credential/UI hijacking. */
    fun isAllowedSseUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (scheme == "https") return true
        if (scheme == "http" && (host == "127.0.0.1" || host == "localhost" || host == "::1")) {
            return true
        }
        return false
    }

    fun connect(
        url: String,
        bearerToken: String = "",
        extraHeaderName: String = "",
        extraHeaderValue: String = ""
    ): Boolean {
        if (!isAllowedSseUrl(url)) {
            DynamicUiEventBus.failStream("보안 정책상 HTTPS 또는 로컬 루프백(127.0.0.1) SSE 주소만 연결할 수 있습니다.")
            return false
        }
        disconnect()
        activeJob = scope.launch {
            var lastEventId: String? = null
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    val reqBuilder = Request.Builder()
                        .url(url.trim())
                        .header("Accept", "text/event-stream")
                        .header("Cache-Control", "no-cache")
                    if (bearerToken.isNotBlank()) {
                        reqBuilder.header("Authorization", "Bearer ${bearerToken.trim()}")
                    }
                    if (extraHeaderName.isNotBlank() && extraHeaderValue.isNotBlank()) {
                        reqBuilder.header(extraHeaderName.trim(), extraHeaderValue.trim())
                    }
                    if (!lastEventId.isNullOrBlank()) {
                        reqBuilder.header("Last-Event-ID", lastEventId!!)
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            DynamicUiEventBus.failStream("외부 SSE 연결 실패 (HTTP ${resp.code})")
                            return@launch
                        }
                        backoffMs = 1_000L
                        val bodyStream = resp.body?.byteStream() ?: return@use
                        val parser = SseLineParser()
                        BufferedReader(InputStreamReader(bodyStream, StandardCharsets.UTF_8)).use { reader ->
                            while (isActive) {
                                val line = reader.readLine() ?: break
                                val frame = parser.feedLine(line) ?: continue
                                if (!frame.id.isNullOrBlank()) lastEventId = frame.id
                                if (frame.retryMs != null) backoffMs = frame.retryMs.coerceIn(500L, 30_000L)
                                dispatchParsedFrame(frame)
                            }
                            parser.flushPendingFrame()?.let { dispatchParsedFrame(it) }
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2L).coerceAtMost(16_000L)
                }
            }
        }
        return true
    }

    fun disconnect() {
        activeJob?.cancel()
        activeJob = null
    }

    internal fun dispatchParsedFrame(frame: ParsedSseFrame) {
        val rawData = frame.data.trim()
        if (rawData.isEmpty() || rawData == "[DONE]") return
        val json = runCatching { JSONObject(rawData) }.getOrNull()
        if (json != null) {
            DynamicUiEventBus.ingestExternalJsonEvent(frame.event, json)
        } else {
            DynamicUiEventBus.appendStreamingDelta(rawData)
        }
    }
}
