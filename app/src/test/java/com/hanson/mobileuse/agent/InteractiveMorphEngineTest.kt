package com.hanson.mobileuse.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveMorphEngineTest {

    @Test
    fun parseAmountWon_handlesCommaManwonAndCheonwon() {
        assertEquals(148_000L, InteractiveMorphEngine.parseAmountWon("4명 148,000원 더치페이"))
        assertEquals(148_000L, InteractiveMorphEngine.parseAmountWon("성수동 저녁 14만8천원 정산"))
        assertEquals(150_000L, InteractiveMorphEngine.parseAmountWon("회식비 15만원 정산"))
        assertNull(InteractiveMorphEngine.parseAmountWon("안녕 한슨"))
    }

    @Test
    fun computeSplitPerPerson_supportsExactAnd100WonRounding() {
        assertEquals(37_000L, InteractiveMorphEngine.computeSplitPerPerson(148_000L, 4, roundTo100Won = true))
        // 100,000 / 3 = 33,333.3 -> 33,400 when rounded up to 100won
        assertEquals(33_400L, InteractiveMorphEngine.computeSplitPerPerson(100_000L, 3, roundTo100Won = true))
        assertEquals(33_333L, InteractiveMorphEngine.computeSplitPerPerson(100_000L, 3, roundTo100Won = false))
    }

    @Test
    fun synthesizeFromPrompt_generatesSplitBillWithHybridStoryboardAndBubbles() {
        val bubbles = listOf(
            HumanMemoryBubble(id = "b1", emoji = "🪟", factKo = "조용한 창가 자리", category = "PLACE", enabled = true),
            HumanMemoryBubble(id = "b2", emoji = "🍗", factKo = "덜 맵게", category = "FOOD", enabled = false)
        )
        val surface = InteractiveMorphEngine.synthesizeFromPrompt(
            prompt = "성수동 저녁 4명 148,000원 더치페이하고 코스 영상 보드 만들어줘",
            bubbles = bubbles
        )
        assertNotNull(surface)
        assertEquals(MorphKind.SPLIT_BILL, surface!!.kind)
        assertEquals(148_000L, surface.totalAmountWon)
        assertEquals(4, surface.headcount)
        assertEquals(37_000L, surface.perPersonWon)
        assertEquals(3, surface.stops.size)
        assertTrue(surface.appliedBubbleLabels.any { it.contains("조용한 창가 자리") })
    }

    @Test
    fun synthesizeFromPrompt_foodCuratorInjectsEnabledFoodBubbles() {
        val bubbles = listOf(
            HumanMemoryBubble(id = "f1", emoji = "🍗", factKo = "음식은 덜 맵게 선호", category = "FOOD", enabled = true)
        )
        val surface = InteractiveMorphEngine.synthesizeFromPrompt("배민에서 오늘 저녁 메뉴 추천해줘", bubbles)
        assertNotNull(surface)
        assertEquals(MorphKind.FOOD_CURATOR, surface!!.kind)
        assertTrue(surface.appliedBubbleLabels.any { it.contains("덜 맵게") })
        assertEquals(3, surface.selectableItems.size)
    }

    @Test
    fun synthesizeFromPrompt_coversStoryboardTimelineCallAndBlankFallback() {
        assertNull(InteractiveMorphEngine.synthesizeFromPrompt("   "))

        val sb = InteractiveMorphEngine.synthesizeFromPrompt("성수동 산책 동선 스토리보드 만들어줘")
        assertNotNull(sb)
        assertEquals(MorphKind.MEDIA_STORYBOARD, sb!!.kind)
        assertEquals(3, sb.stops.size)

        val tl = InteractiveMorphEngine.synthesizeFromPrompt("오늘 저녁 약속 일정 맞춰줘")
        assertNotNull(tl)
        assertEquals(MorphKind.TIMELINE_PLANNER, tl!!.kind)

        val call = InteractiveMorphEngine.synthesizeFromPrompt("식당에 창가석 전화 대본 준비해줘")
        assertNotNull(call)
        assertEquals(MorphKind.CALL_CONCIERGE, call!!.kind)
        assertEquals(3, call.selectableItems.size)
    }

    @Test
    fun buildAchromaticMicroAppHtml_keepsGrayscaleAndHansonOsBridge() {
        val surface = InteractiveMorphEngine.synthesizeFromPrompt("5명 200,000원 더치페이 정산해줘")!!
        val html = InteractiveMorphEngine.buildAchromaticMicroAppHtml(surface)
        assertTrue(html.contains("filter: grayscale(100%) !important"))
        assertTrue(html.contains("window.HansonOS"))
        assertTrue(html.contains("40,000원"))
        assertFalse(html.contains("#FF00"))
    }

    @Test
    fun shouldAutoInterceptChatPrompt_alwaysReturnsFalseSoGeneralChatReachesRealAi() {
        val prompts = listOf(
            "배민에서 오늘 저녁 메뉴 추천해줘",
            "4명 148,000원 더치페이 정산해줘",
            "soxs 가격 알려 줘",
            "성수동 산책 동선 스토리보드 만들어줘",
            "오늘 저녁 약속 일정 맞춰줘",
            ""
        )
        for (p in prompts) {
            assertFalse(
                "InteractiveMorphEngine must stay opt-in and never auto-intercept chat prompt '$p'",
                InteractiveMorphEngine.shouldAutoInterceptChatPrompt(p)
            )
        }
    }
}
