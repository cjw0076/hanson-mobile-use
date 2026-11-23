package com.hanson.mobileuse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardEventStreamEngineTest {

    @Test
    fun testDualChannelSpeakAndVisualSplit_keepsSpokenProseSeparateFromVisualBoard() {
        val llmReply = """
            SPEAK: SOXS 최근 종가는 83.16달러이며 직전 거래일에는 67.05달러에서 84.86달러 사이에서 움직였어요.
            BOARD:
            SOXS (Direxion Daily Semiconductor Bear 3X ETF) 시세 요약
            • 현재 주가: $83.16
            • 직전 거래일 범위: $67.05 ~ $84.86
            장 개장 후 변동성이 클 수 있으니 주의해 주세요.
        """.trimIndent()

        val citations = listOf(
            StructuredUiItem("01", "investing.com", "vertexaisearch.cloud.google.com", "출처 ↗", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/demo1"),
            StructuredUiItem("02", "moomoo.com", "vertexaisearch.cloud.google.com", "출처 ↗", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/demo2")
        )

        val script = BoardEventStreamCompiler.compile(
            rawText = llmReply,
            citations = citations,
            turnIndex = 2
        )

        assertTrue(script.isRichBoard)
        assertEquals(2, script.turnIndex)
        assertFalse("Visual body must NEVER leak 'SPEAK:' directive", script.visualBodyText.contains("SPEAK:"))
        assertFalse("Visual body must NEVER leak 'BOARD:' directive", script.visualBodyText.contains("BOARD:"))
        assertTrue("Spoken text must preserve natural explicit SPEAK line", script.combinedSpokenText.contains("83.16달러"))
        assertFalse("Spoken text must NEVER contain asterisks", script.combinedSpokenText.contains("*"))

        // Verify strictly monotonic orderIndex envelope (0..N-1)
        assertEquals((0 until script.events.size).toList(), script.events.map { it.orderIndex })
        assertTrue(script.events.all { it.turnIndex == 2 })

        // Verify typed commands exist in sequence
        assertTrue(script.events.any { it.command is BoardEventCommand.WriteHeadline })
        assertTrue(script.events.any { it.command is BoardEventCommand.Speak && (it.command as BoardEventCommand.Speak).linkedElementId == "title" })
        assertTrue(script.events.any { it.command is BoardEventCommand.AddStatPill })
        assertEquals(2, script.events.count { it.command is BoardEventCommand.AddTakeaway })
        assertEquals(2, script.events.count { it.command is BoardEventCommand.AddCitation })

        // Verify Ahaboard-compatible JSON serialization
        val jsonArr = script.toJsonArray()
        assertEquals(script.events.size, jsonArr.length())
        val first = jsonArr.getJSONObject(0)
        assertEquals("tutor_command", first.getString("name"))
        assertEquals(2, first.getInt("turnIndex"))
        assertEquals(0, first.getInt("orderIndex"))
        assertEquals("write_headline", first.getString("commandName"))
        assertEquals("title", first.getString("elementId"))
        assertNotNull(first.getJSONObject("position").getString("spaceId"))
        assertEquals("main", first.getJSONObject("position").getString("columnId"))
    }

    @Test
    fun testCompile_rawMarkdownFallback_emitsLinkedPerTakeawaySpeakEventsWithZeroAsterisks() {
        val rawMarkdown = """
            **SOXS (Direxion Daily Semiconductor Bear 3X ETF)**의 최근 종가 기준 가격이에요:
            * **현재 주가(최근 종가):** **$83.16**
            * **직전 거래일 변동 범위:** $67.05 ~ $84.86
        """.trimIndent()

        val script = BoardEventStreamCompiler.compile(rawText = rawMarkdown)
        assertTrue(script.isRichBoard)
        val speakEvents = script.events.mapNotNull { it.command as? BoardEventCommand.Speak }
        assertTrue("Expected headline + per-takeaway linked Speak events", speakEvents.size >= 3)
        assertTrue(speakEvents.any { it.linkedElementId == "takeaway_1" })
        assertTrue(speakEvents.any { it.linkedElementId == "takeaway_2" })
        assertFalse(script.combinedSpokenText.contains("*"))
        assertTrue(script.combinedSpokenText.contains("83.16달러"))
        assertTrue(script.combinedSpokenText.contains("67.05달러에서 84.86달러"))
    }
}
