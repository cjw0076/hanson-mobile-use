package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spatial placement of a board event inside a named topic space and column,
 * modeled after Ahaboard's `{ position: { spaceId, columnId } }` event stream contract.
 */
data class BoardPosition(
    val spaceId: String,
    val columnId: String = "main"
)

/**
 * Typed dual-channel board command separating **Visual Board Rendering** from **Natural Spoken Voice**.
 * Every visual command carries clean markdown-free structured fields; every [Speak] command carries
 * spoken Korean prose linked to the visual [linkedElementId] for synchronized highlight & karaoke captions.
 */
sealed class BoardEventCommand {
    abstract val commandName: String

    data class WriteHeadline(
        val eyebrow: String,
        val headline: String
    ) : BoardEventCommand() {
        override val commandName: String = "write_headline"
    }

    data class AddStatPill(
        val label: String,
        val value: String
    ) : BoardEventCommand() {
        override val commandName: String = "add_stat_pill"
    }

    data class AddTakeaway(
        val index: Int,
        val keyPrefix: String,
        val body: String
    ) : BoardEventCommand() {
        override val commandName: String = "add_takeaway"
    }

    data class WriteNote(
        val text: String
    ) : BoardEventCommand() {
        override val commandName: String = "write_note"
    }

    data class AddCitation(
        val badge: String,
        val title: String,
        val subtitle: String,
        val verifiedUrl: String
    ) : BoardEventCommand() {
        override val commandName: String = "add_citation"
    }

    data class Speak(
        val text: String,
        val style: String = "warm",
        val linkedElementId: String? = null
    ) : BoardEventCommand() {
        override val commandName: String = "speak"
    }
}

/**
 * Single ordered event inside a dynamic board stream (`turnIndex`, `orderIndex`, `elementId`).
 */
data class BoardStreamEvent(
    val turnIndex: Int,
    val orderIndex: Int,
    val elementId: String,
    val position: BoardPosition,
    val command: BoardEventCommand
)

/**
 * Compiled dual-channel board script driving both sequential Compose animations and clean TTS readout.
 */
data class BoardEventScript(
    val spaceId: String,
    val turnIndex: Int,
    val visualBodyText: String,
    val events: List<BoardStreamEvent>,
    val combinedSpokenText: String,
    val isRichBoard: Boolean
) {
    /**
     * Serializes the ordered command stream into Ahaboard-compatible `tutor_command` JSON envelopes
     * (`name`, `turnIndex`, `orderIndex`, `commandName`, `elementId`, `position`, payload fields).
     */
    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (ev in events) {
            val obj = JSONObject().apply {
                put("name", "tutor_command")
                put("turnIndex", ev.turnIndex)
                put("orderIndex", ev.orderIndex)
                put("commandName", ev.command.commandName)
                put("elementId", ev.elementId)
                put(
                    "position",
                    JSONObject()
                        .put("spaceId", ev.position.spaceId)
                        .put("columnId", ev.position.columnId)
                )
                when (val cmd = ev.command) {
                    is BoardEventCommand.WriteHeadline -> {
                        put("eyebrow", cmd.eyebrow)
                        put("text", cmd.headline)
                    }
                    is BoardEventCommand.AddStatPill -> {
                        put("label", cmd.label)
                        put("value", cmd.value)
                    }
                    is BoardEventCommand.AddTakeaway -> {
                        put("index", cmd.index)
                        put("keyPrefix", cmd.keyPrefix)
                        put("text", cmd.body)
                    }
                    is BoardEventCommand.WriteNote -> {
                        put("text", cmd.text)
                    }
                    is BoardEventCommand.AddCitation -> {
                        put("badge", cmd.badge)
                        put("title", cmd.title)
                        put("subtitle", cmd.subtitle)
                        put("url", cmd.verifiedUrl)
                    }
                    is BoardEventCommand.Speak -> {
                        put("text", cmd.text)
                        put("style", cmd.style)
                        if (!cmd.linkedElementId.isNullOrBlank()) {
                            put("linkedElementId", cmd.linkedElementId)
                        }
                    }
                }
            }
            arr.put(obj)
        }
        return arr
    }
}

/**
 * Compiles LLM output (with optional explicit `SPEAK:` voice channel + visual board prose + citations)
 * into an ordered dual-channel [BoardEventScript] so UI elements draw sequentially while TTS receives
 * ONLY natural spoken Korean with zero raw markdown artifacts.
 */
object BoardEventStreamCompiler {

    private val SPEAK_LINE_REGEX = Regex("""^\s*(?:SPEAK|VOICE|말하기)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val BOARD_HEADER_REGEX = Regex("""^\s*(?:BOARD|VISUAL|화면)\s*[:：]\s*$""", RegexOption.IGNORE_CASE)
    private val SPACE_ID_CLEAN_REGEX = Regex("""[^a-zA-Z0-9가-힣]+""")

    /**
     * Separates an optional explicit `SPEAK: ...` voice directive emitted by the LLM from
     * the visual board markdown text.
     */
    fun splitSpeakAndVisual(rawText: String): Pair<String?, String> {
        if (rawText.isBlank()) return null to ""
        val speakParts = mutableListOf<String>()
        val visualLines = mutableListOf<String>()

        for (rawLine in rawText.lines()) {
            val trimmed = rawLine.trim()
            val speakMatch = SPEAK_LINE_REGEX.matchEntire(trimmed)
            when {
                speakMatch != null -> {
                    val spoken = speakMatch.groupValues[1].trim()
                    if (spoken.isNotEmpty()) speakParts.add(spoken)
                }
                BOARD_HEADER_REGEX.matches(trimmed) -> {
                    // Strip purely structural "BOARD:" marker line from visual output
                }
                else -> visualLines.add(rawLine)
            }
        }

        val explicitSpeak = speakParts
            .joinToString(" ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { RichAnswerBoardFormatter.formatForNaturalSpeech(it, maxLength = 260) }
            ?.takeIf { it.isNotBlank() }

        val visualClean = visualLines.joinToString("\n").trim()
        return explicitSpeak to visualClean.ifBlank { rawText.trim() }
    }

    /**
     * Compiles raw LLM response + verified citations into an ordered dual-channel [BoardEventScript].
     */
    fun compile(
        rawText: String,
        citations: List<StructuredUiItem> = emptyList(),
        turnIndex: Int = 0,
        eyebrowOverride: String = ""
    ): BoardEventScript {
        val (explicitSpeak, visualText) = splitSpeakAndVisual(rawText)
        val board = RichAnswerBoardFormatter.buildStructuredBoard(
            rawText = visualText,
            citationCount = citations.size,
            eyebrowOverride = eyebrowOverride
        )

        val spaceSeed = board.headline.ifBlank { visualText.take(24) }
        val spaceId = SPACE_ID_CLEAN_REGEX
            .replace(spaceSeed.lowercase(), "_")
            .trim('_')
            .take(32)
            .ifBlank { "board_turn_$turnIndex" }

        val mainPos = BoardPosition(spaceId = spaceId, columnId = "main")
        val metricsPos = BoardPosition(spaceId = spaceId, columnId = "metrics")
        val sourcesPos = BoardPosition(spaceId = spaceId, columnId = "sources")

        val events = mutableListOf<BoardStreamEvent>()
        var nextOrder = 0
        fun emit(elementId: String, pos: BoardPosition, cmd: BoardEventCommand) {
            events.add(
                BoardStreamEvent(
                    turnIndex = turnIndex,
                    orderIndex = nextOrder++,
                    elementId = elementId,
                    position = pos,
                    command = cmd
                )
            )
        }

        // 1. Headline + initial voice hook
        if (board.headline.isNotBlank()) {
            emit(
                elementId = "title",
                pos = mainPos,
                cmd = BoardEventCommand.WriteHeadline(
                    eyebrow = board.eyebrow,
                    headline = board.headline
                )
            )
            val headlineSpeech = explicitSpeak ?: RichAnswerBoardFormatter.formatForNaturalSpeech(board.headline, maxLength = 140)
            if (headlineSpeech.isNotBlank()) {
                emit(
                    elementId = "speak_title",
                    pos = mainPos,
                    cmd = BoardEventCommand.Speak(
                        text = headlineSpeech,
                        style = "warm",
                        linkedElementId = "title"
                    )
                )
            }
        } else if (explicitSpeak != null) {
            emit(
                elementId = "speak_main",
                pos = mainPos,
                cmd = BoardEventCommand.Speak(
                    text = explicitSpeak,
                    style = "warm",
                    linkedElementId = "note_0"
                )
            )
        }

        // 2. Stat pills
        board.statPills.forEachIndexed { idx, pill ->
            emit(
                elementId = "stat_$idx",
                pos = metricsPos,
                cmd = BoardEventCommand.AddStatPill(label = pill.label, value = pill.value)
            )
        }

        // 3. Numbered Key Takeaways + synchronized per-card Speak events (if no single explicit SPEAK override)
        board.takeaways.forEachIndexed { idx, item ->
            val cardId = "takeaway_${item.index}"
            emit(
                elementId = cardId,
                pos = mainPos,
                cmd = BoardEventCommand.AddTakeaway(
                    index = item.index,
                    keyPrefix = item.keyPrefix,
                    body = item.body
                )
            )
            if (explicitSpeak == null && idx < 3) {
                val rawCardSpeech = if (item.keyPrefix.isNotBlank()) {
                    "${item.keyPrefix}, ${item.body}"
                } else {
                    item.body
                }
                val cleanCardSpeech = RichAnswerBoardFormatter.formatForNaturalSpeech(rawCardSpeech, maxLength = 120)
                if (cleanCardSpeech.isNotBlank()) {
                    emit(
                        elementId = "speak_$cardId",
                        pos = mainPos,
                        cmd = BoardEventCommand.Speak(
                            text = cleanCardSpeech,
                            style = "clear",
                            linkedElementId = cardId
                        )
                    )
                }
            }
        }

        // 4. Clean closing / body notes
        board.bodyParagraphs.forEachIndexed { idx, para ->
            val cleanPara = RichAnswerBoardFormatter.stripMarkdownForDisplay(para)
            if (cleanPara.isNotBlank()) {
                emit(
                    elementId = "note_$idx",
                    pos = mainPos,
                    cmd = BoardEventCommand.WriteNote(text = para)
                )
            }
        }

        // 5. Verified HTTPS citation pills
        citations.forEachIndexed { idx, cit ->
            val url = cit.actionUrlOrCmd.trim()
            if (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)) {
                emit(
                    elementId = "citation_$idx",
                    pos = sourcesPos,
                    cmd = BoardEventCommand.AddCitation(
                        badge = cit.badge,
                        title = cit.title,
                        subtitle = RichAnswerBoardFormatter.friendlySourceHost(cit.subtitle, cit.title),
                        verifiedUrl = url
                    )
                )
            }
        }

        val spokenCombined = explicitSpeak ?: events
            .mapNotNull { (it.command as? BoardEventCommand.Speak)?.text }
            .joinToString(". ")
            .ifBlank { RichAnswerBoardFormatter.formatForNaturalSpeech(visualText, maxLength = 240) }

        val finalSpoken = RichAnswerBoardFormatter.formatForNaturalSpeech(spokenCombined, maxLength = 260)

        return BoardEventScript(
            spaceId = spaceId,
            turnIndex = turnIndex,
            visualBodyText = visualText,
            events = events,
            combinedSpokenText = finalSpoken,
            isRichBoard = board.isRichBoard
        )
    }
}
