package com.hanson.mobileuse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichAnswerBoardFormatterTest {

    private val realGeminiSoxsReply = """
        **SOXS (Direxion Daily Semiconductor Bear 3X ETF)**의 최근 종가 기준 가격이에요:
        * **현재 주가(최근 종가):** **$83.16**
        * **직전 거래일 변동 범위:** $67.05 ~ $84.86
        미국 정규장이 열리면 프리마켓 및 실시간 거래 상황에 따라 변동될 수 있어요. 더 궁금한 점 있으시면 언제든 편하게 말씀해 주세요!
    """.trimIndent()

    @Test
    fun testBuildStructuredBoard_decomposesRealSoxsMarkdownIntoAhaboardStyleBlocksWithZeroAsterisks() {
        val board = RichAnswerBoardFormatter.buildStructuredBoard(
            rawText = realGeminiSoxsReply,
            citationCount = 2
        )

        assertTrue("Rich markdown reply must render as Ahaboard structured board", board.isRichBoard)
        assertEquals("LIVE WEB BOARD", board.eyebrow)
        assertFalse("Headline must not contain literal asterisks", board.headline.contains("*"))
        assertTrue("Headline must contain SOXS", board.headline.contains("SOXS"))

        // Stat pills extracted automatically
        assertTrue("Expected at least 3 stat pills (price, range, citations)", board.statPills.size >= 3)
        assertTrue("Price pill must capture $83.16", board.statPills.any { it.value.contains("$83.16") })
        assertTrue("Range pill must capture $67.05 ~ $84.86", board.statPills.any { it.value.contains("67.05") })
        assertTrue("Citation pill must show 2건", board.statPills.any { it.value == "2건" })

        // Numbered Key Takeaways cards
        assertEquals(2, board.takeaways.size)
        assertEquals(1, board.takeaways[0].index)
        assertEquals("현재 주가(최근 종가)", board.takeaways[0].keyPrefix)
        assertEquals("$83.16", board.takeaways[0].body)
        assertEquals(2, board.takeaways[1].index)
        assertEquals("직전 거래일 변동 범위", board.takeaways[1].keyPrefix)
        assertTrue(board.takeaways[1].body.contains("$67.05"))
        for (item in board.takeaways) {
            assertFalse("Takeaway keyPrefix must have no asterisks", item.keyPrefix.contains("*"))
            assertFalse("Takeaway body must have no asterisks", item.body.contains("*"))
        }

        // Closing prose kept cleanly
        assertEquals(1, board.bodyParagraphs.size)
        assertTrue(board.bodyParagraphs[0].contains("정규장"))
    }

    @Test
    fun testFormatForNaturalSpeech_stripsAllMarkdownAndNaturalizesCurrencyForKoreanTts() {
        val rawWithLinks = realGeminiSoxsReply + "\n\n참고 출처:\n- [investing.com](https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc)\n"
        val spoken = RichAnswerBoardFormatter.formatForNaturalSpeech(rawWithLinks)

        assertFalse("TTS text must NEVER contain '*'", spoken.contains("*"))
        assertFalse("TTS text must NEVER contain '#'", spoken.contains("#"))
        assertFalse("TTS text must NEVER contain backticks", spoken.contains("`"))
        assertFalse("TTS text must NEVER contain raw URLs", spoken.contains("https://"))
        assertFalse("TTS text must NEVER contain '~'", spoken.contains("~"))
        assertTrue("Dollar price must naturalize to '83.16달러'", spoken.contains("83.16달러"))
        assertTrue("Dollar range must naturalize to '67.05달러에서 84.86달러'", spoken.contains("67.05달러에서 84.86달러"))
    }

    @Test
    fun testParseInlineMarkdown_separatesBoldAndCodeCleanlyWithoutRawMarkers() {
        val spans = RichAnswerBoardFormatter.parseInlineMarkdown("가격은 **$83.16**이며 티커는 `SOXS`입니다.")
        assertTrue(spans.any { it.isBold && it.text == "$83.16" })
        assertTrue(spans.any { it.isCode && it.text == "SOXS" })
        val joined = spans.joinToString("") { it.text }
        assertFalse(joined.contains("*"))
        assertFalse(joined.contains("`"))
    }

    @Test
    fun testFriendlySourceHost_hidesVertexRedirectHostnameWhilePreservingSiteIdentity() {
        assertEquals(
            "investing.com · Google 검증",
            RichAnswerBoardFormatter.friendlySourceHost("vertexaisearch.cloud.google.com", "investing.com")
        )
        assertEquals(
            "Google 검색 출처",
            RichAnswerBoardFormatter.friendlySourceHost("vertexaisearch.cloud.google.com", "SOXS Quote Page")
        )
        assertEquals(
            "finance.yahoo.com",
            RichAnswerBoardFormatter.friendlySourceHost("www.finance.yahoo.com", "Yahoo Finance")
        )
    }
}
