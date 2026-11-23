package com.hanson.mobileuse.agent

/**
 * Styled text segment emitted by [RichAnswerBoardFormatter.parseInlineMarkdown]
 * so Compose UI can render bold / inline-code typography without EVER showing raw `**` or `` ` ``.
 */
data class StyledTextSpan(
    val text: String,
    val isBold: Boolean = false,
    val isCode: Boolean = false
)

/**
 * Compact stat pill modeled after Ahaboard's pill grammar (`5 min`, `2 turns`, `$83.16`).
 */
data class BoardStatPill(
    val label: String,
    val value: String
)

/**
 * Numbered takeaway card item modeled after Ahaboard's `Key takeaways` card anatomy
 * (`1`, `2`, `3` circular badge + optional bold key prefix + clean body).
 */
data class BoardTakeawayItem(
    val index: Int,
    val keyPrefix: String = "",
    val body: String
)

/**
 * Structured Ahaboard-style visual board decomposition of any LLM response.
 * Separates UI visual presentation (headline + stat pills + numbered takeaway cards + clean prose + source pills)
 * from natural TTS speech ([formatForNaturalSpeech]).
 */
data class AhaboardResponseModel(
    val eyebrow: String = "",
    val headline: String = "",
    val statPills: List<BoardStatPill> = emptyList(),
    val takeaways: List<BoardTakeawayItem> = emptyList(),
    val bodyParagraphs: List<String> = emptyList(),
    val isRichBoard: Boolean = false
)

/**
 * Dual-channel formatter separating:
 * 1. **Visual UI Board (`buildStructuredBoard` + `parseInlineMarkdown`)**:
 *    Converts raw LLM markdown (`**bold**`, `* bullet`, `### heading`, `[label](url)`) into
 *    Ahaboard-style structured cards (animated highlight headline, stat pills, numbered takeaway cards,
 *    bold-lead typography, and domain citation pills) with ZERO literal `**` or `*` artifacts.
 * 2. **Natural Korean TTS Speech (`formatForNaturalSpeech`)**:
 *    Strips all markdown punctuation/URLs and normalizes currency/range symbols (`$83.16` → `83.16달러`,
 *    `$67.05 ~ $84.86` → `67.05달러에서 84.86달러`) so TTS never reads `"별표 별표"` aloud.
 */
object RichAnswerBoardFormatter {

    private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)\]\((https?://[^)\s]+)\)""")
    private val BARE_URL_REGEX = Regex("""https?://\S+""")
    private val HEADING_PREFIX_REGEX = Regex("""^\s{0,3}#{1,6}\s+""")
    private val BULLET_PREFIX_REGEX = Regex("""^\s*(?:[-*•▪▶]|(?:\d+[.)]))\s+""")
    private val BLOCKQUOTE_PREFIX_REGEX = Regex("""^\s*>\s?""")
    private val DOLLAR_RANGE_REGEX = Regex("""\$\s*(\d[\d,]*(?:\.\d+)?)\s*[~\-–—]\s*\$?\s*(\d[\d,]*(?:\.\d+)?)""")
    private val DOLLAR_SINGLE_REGEX = Regex("""\$\s*(\d[\d,]*(?:\.\d+)?)""")
    private val NUMERIC_RANGE_REGEX = Regex("""(\d[\d,]*(?:\.\d+)?)\s*~\s*(\d[\d,]*(?:\.\d+)?)""")
    private val KEY_VALUE_REGEX = Regex("""^([^:：]{2,36})[:：]\s*(.+)$""")
    private val METRIC_VALUE_REGEX = Regex("""(\$\s*\d[\d,]*(?:\.\d+)?(?:\s*[~\-–]\s*\$?\s*\d[\d,]*(?:\.\d+)?)?|\d[\d,]*(?:\.\d+)?\s*(?:달러|원|만원|억원|%|℃|°C|km|m|분|시간|초|명|건))""")
    private val BOLD_OR_CODE_TOKEN_REGEX = Regex("""(\*\*[^*]+\*\*|__[^_]+__|`[^`]+`|\*[^*\n]+\*)""")

    /**
     * Parses inline markdown (`**bold**`, `__bold__`, `*emphasis*`, `` `code` ``, `[label](url)`)
     * into clean [StyledTextSpan] segments guaranteed free of raw `**`, `*`, or `` ` `` markers.
     */
    fun parseInlineMarkdown(raw: String): List<StyledTextSpan> {
        if (raw.isEmpty()) return emptyList()
        val preCleaned = raw
            .replace(MARKDOWN_LINK_REGEX) { it.groupValues[1].trim() }
            .replace("~~", "")

        val spans = mutableListOf<StyledTextSpan>()
        var cursor = 0
        for (match in BOLD_OR_CODE_TOKEN_REGEX.findAll(preCleaned)) {
            if (match.range.first > cursor) {
                val plain = stripResidualMarkdownMarkers(preCleaned.substring(cursor, match.range.first))
                if (plain.isNotEmpty()) spans.add(StyledTextSpan(plain))
            }
            val token = match.value
            when {
                (token.startsWith("**") && token.endsWith("**")) ||
                    (token.startsWith("__") && token.endsWith("__")) -> {
                    val inner = stripResidualMarkdownMarkers(token.substring(2, token.length - 2))
                    if (inner.isNotEmpty()) spans.add(StyledTextSpan(inner, isBold = true))
                }
                token.startsWith("`") && token.endsWith("`") -> {
                    val inner = token.substring(1, token.length - 1).trim()
                    if (inner.isNotEmpty()) spans.add(StyledTextSpan(inner, isCode = true))
                }
                token.startsWith("*") && token.endsWith("*") -> {
                    val inner = stripResidualMarkdownMarkers(token.substring(1, token.length - 1))
                    if (inner.isNotEmpty()) spans.add(StyledTextSpan(inner, isBold = true))
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < preCleaned.length) {
            val tail = stripResidualMarkdownMarkers(preCleaned.substring(cursor))
            if (tail.isNotEmpty()) spans.add(StyledTextSpan(tail))
        }
        return spans
    }

    /**
     * Removes markdown decoration while keeping clean readable text for headings, pills, and summaries.
     */
    fun stripMarkdownForDisplay(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lines().joinToString("\n") { line ->
            val strippedLine = line
                .replace(HEADING_PREFIX_REGEX, "")
                .replace(BLOCKQUOTE_PREFIX_REGEX, "")
                .replace(BULLET_PREFIX_REGEX, "• ")
            parseInlineMarkdown(strippedLine).joinToString("") { it.text }
                .replace(Regex("""\s+"""), " ")
                .trim()
        }.trim()
    }

    private fun stripResidualMarkdownMarkers(text: String): String {
        return text
            .replace("**", "")
            .replace("__", "")
            .replace("~~", "")
            .replace("`", "")
            .replace(Regex("""(?<!\d)\*(?!\d)"""), "")
    }

    /**
     * Converts raw LLM markdown into natural spoken Korean for Android TTS / voice readout.
     * Guarantees ZERO `"별표"`, `"샵"`, `"백틱"`, or raw URLs in spoken output.
     */
    fun formatForNaturalSpeech(raw: String, maxLength: Int = 240): String {
        if (raw.isBlank()) return ""
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { line ->
                // Skip pure citation list lines in voice readout
                line.startsWith("- [") && line.contains("](http") ||
                    line.startsWith("• [") && line.contains("](http") ||
                    line.startsWith("http://") ||
                    line.startsWith("https://") ||
                    line.startsWith("출처:") ||
                    line.startsWith("참고 출처")
            }

        val normalizedLines = lines.mapNotNull { rawLine ->
            var s = rawLine
                .replace(MARKDOWN_LINK_REGEX) { it.groupValues[1].trim() }
                .replace(BARE_URL_REGEX, " ")
                .replace(Regex("""\[[^\]]*\]"""), " ")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(HEADING_PREFIX_REGEX, "")
                .replace(BLOCKQUOTE_PREFIX_REGEX, "")
                .replace(BULLET_PREFIX_REGEX, "")
                .replace("**", "")
                .replace("__", "")
                .replace("~~", "")
                .replace("`", "")
                .replace("*", "")
                .replace("#", "")
                .replace(Regex("""[•▪▶↗✦🫧⚡📞🛡️⚙️🌲✨]"""), " ")

            // Naturalize currency & ranges for Korean TTS
            s = DOLLAR_RANGE_REGEX.replace(s) { m ->
                "${m.groupValues[1]}달러에서 ${m.groupValues[2]}달러"
            }
            s = DOLLAR_SINGLE_REGEX.replace(s) { m ->
                "${m.groupValues[1]}달러"
            }
            s = NUMERIC_RANGE_REGEX.replace(s) { m ->
                "${m.groupValues[1]}에서 ${m.groupValues[2]}"
            }
            s = s.replace("~", "에서 ")
                .replace(":", ", ")
                .replace("：", ", ")
                .replace(Regex("""\s*,\s*,\s*"""), ", ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trimEnd(',', ':')

            s.takeIf { it.isNotBlank() }
        }

        if (normalizedLines.isEmpty()) return ""
        val joined = normalizedLines.joinToString(". ") { line ->
            if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?") || line.endsWith("요") || line.endsWith("다")) {
                line
            } else {
                line
            }
        }
            .replace(Regex("""\.\s*\."""), ".")
            .replace(Regex("""([!?])\."""), "$1")
            .replace(Regex("""\s+"""), " ")
            .trim()

        return if (joined.length <= maxLength) {
            joined
        } else {
            val cut = joined.take(maxLength)
            val lastBoundary = maxOf(cut.lastIndexOf('.'), cut.lastIndexOf('!'), cut.lastIndexOf('?'), cut.lastIndexOf(' '))
            if (lastBoundary > maxLength / 2) cut.substring(0, lastBoundary).trim() else cut.trim()
        }
    }

    /**
     * Decomposes an AI answer into an Ahaboard-style visual board:
     * - Highlighted 1-line headline (`hl-swipe`)
     * - Compact metric stat pills (`현재 주가 · $83.16`, `출처 · 2건`, etc.)
     * - Numbered takeaway cards (`1`, `2`, `3` circular badge + bold key-lead)
     * - Clean follow-up prose paragraphs (all markdown-free)
     */
    fun buildStructuredBoard(
        rawText: String,
        citationCount: Int = 0,
        eyebrowOverride: String = ""
    ): AhaboardResponseModel {
        val rawLines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return AhaboardResponseModel()

        val bulletLines = mutableListOf<String>()
        val nonBulletLines = mutableListOf<String>()
        var explicitHeading = ""

        for (line in rawLines) {
            when {
                HEADING_PREFIX_REGEX.containsMatchIn(line) -> {
                    val cleanH = parseInlineMarkdown(line.replace(HEADING_PREFIX_REGEX, ""))
                        .joinToString("") { it.text }.trim()
                    if (explicitHeading.isEmpty() && cleanH.isNotEmpty()) {
                        explicitHeading = cleanH
                    } else if (cleanH.isNotEmpty()) {
                        nonBulletLines.add(cleanH)
                    }
                }
                BULLET_PREFIX_REGEX.containsMatchIn(line) -> {
                    val stripped = line.replace(BULLET_PREFIX_REGEX, "").trim()
                    if (stripped.isNotEmpty()) bulletLines.add(stripped)
                }
                else -> nonBulletLines.add(line)
            }
        }

        val hasRichStructure = explicitHeading.isNotEmpty() ||
            bulletLines.isNotEmpty() ||
            citationCount > 0 ||
            rawText.contains("**")

        if (!hasRichStructure) {
            return AhaboardResponseModel(
                bodyParagraphs = listOf(stripMarkdownForDisplay(rawText)),
                isRichBoard = false
            )
        }

        // Determine headline (prefer explicit heading, else concise first lead line)
        var headline = explicitHeading
        val remainingProse = mutableListOf<String>()
        if (headline.isEmpty() && nonBulletLines.isNotEmpty()) {
            val firstClean = parseInlineMarkdown(nonBulletLines.first())
                .joinToString("") { it.text }
                .trim()
                .trimEnd(':', '：')
            headline = firstClean
            for (i in 1 until nonBulletLines.size) {
                remainingProse.add(nonBulletLines[i])
            }
        } else {
            remainingProse.addAll(nonBulletLines)
        }

        // Build numbered Key Takeaways cards + auto-extract Ahaboard stat pills
        val statPills = mutableListOf<BoardStatPill>()
        val seenPillLabels = mutableSetOf<String>()
        val takeaways = mutableListOf<BoardTakeawayItem>()

        bulletLines.forEachIndexed { idx, rawBullet ->
            val cleanFull = parseInlineMarkdown(rawBullet).joinToString("") { it.text }.trim()
            if (cleanFull.isEmpty()) return@forEachIndexed

            val kvMatch = KEY_VALUE_REGEX.matchEntire(cleanFull)
            val keyPrefix = kvMatch?.groupValues?.get(1)?.trim().orEmpty()
            val valueBody = kvMatch?.groupValues?.get(2)?.trim().orEmpty()

            if (keyPrefix.isNotEmpty() && valueBody.isNotEmpty()) {
                takeaways.add(
                    BoardTakeawayItem(
                        index = takeaways.size + 1,
                        keyPrefix = keyPrefix,
                        body = valueBody
                    )
                )
                // Extract compact metric stat pill if value contains price/number/percent/unit
                if (statPills.size < 3) {
                    val metricHit = METRIC_VALUE_REGEX.find(valueBody)?.value?.trim()
                    if (!metricHit.isNullOrBlank()) {
                        val shortLabel = keyPrefix
                            .replace(Regex("""\([^)]*\)"""), "")
                            .trim()
                            .take(12)
                        if (shortLabel.isNotBlank() && seenPillLabels.add(shortLabel)) {
                            statPills.add(BoardStatPill(label = shortLabel, value = metricHit))
                        }
                    }
                }
            } else {
                takeaways.add(
                    BoardTakeawayItem(
                        index = takeaways.size + 1,
                        keyPrefix = "",
                        body = cleanFull
                    )
                )
            }
        }

        if (citationCount > 0 && statPills.size < 4) {
            statPills.add(BoardStatPill(label = "웹 출처", value = "${citationCount}건"))
        }

        val eyebrow = eyebrowOverride.ifBlank {
            when {
                citationCount > 0 -> "LIVE WEB BOARD"
                takeaways.isNotEmpty() -> "KEY TAKEAWAYS"
                else -> "SUMMARY BOARD"
            }
        }

        return AhaboardResponseModel(
            eyebrow = eyebrow,
            headline = headline,
            statPills = statPills,
            takeaways = takeaways,
            bodyParagraphs = remainingProse,
            isRichBoard = headline.isNotBlank() || takeaways.isNotEmpty() || statPills.isNotEmpty() || citationCount > 0
        )
    }

    /**
     * Converts raw redirect hosts like `vertexaisearch.cloud.google.com` into crisp human labels.
     */
    fun friendlySourceHost(rawHost: String, fallbackTitle: String = ""): String {
        val normalized = rawHost.trim().lowercase().removePrefix("www.")
        if (normalized.isEmpty()) return fallbackTitle.ifBlank { "웹 출처" }
        if (normalized.contains("vertexaisearch.cloud.google.com") ||
            normalized.contains("grounding-api-redirect")
        ) {
            val cleanTitleHost = fallbackTitle.trim().lowercase().removePrefix("www.")
            if (cleanTitleHost.contains('.') && !cleanTitleHost.contains(' ') && cleanTitleHost.length <= 40) {
                return "$cleanTitleHost · Google 검증"
            }
            return "Google 검색 출처"
        }
        return normalized
    }
}
