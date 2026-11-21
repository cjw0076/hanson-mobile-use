package com.hanson.mobileuse.agent

import kotlin.math.roundToLong

enum class MorphKind(
    val id: String,
    val badgeKo: String
) {
    SPLIT_BILL("split_bill", "인터랙티브 더치페이"),
    FOOD_CURATOR("food_curator", "취향 반영 메뉴 보드"),
    TIMELINE_PLANNER("timeline_planner", "일정·시간표 조절기"),
    MEDIA_STORYBOARD("media_storyboard", "모션 스토리보드"),
    CALL_CONCIERGE("call_concierge", "전화 대본 & 발신 준비")
}

data class MorphRouteStop(
    val timeLabel: String,
    val placeLabel: String,
    val note: String = ""
)

data class MorphSelectableItem(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val badge: String = "",
    val selected: Boolean = false
)

data class InteractiveMorphSurface(
    val id: String,
    val kind: MorphKind,
    val sourcePrompt: String,
    val synthesisMs: Int,
    val badgeKo: String,
    val titleKo: String,
    val subtitleKo: String,
    val appliedBubbleLabels: List<String> = emptyList(),
    val totalAmountWon: Long = 0L,
    val headcount: Int = 4,
    val roundTo100Won: Boolean = true,
    val stops: List<MorphRouteStop> = emptyList(),
    val selectableItems: List<MorphSelectableItem> = emptyList(),
    val primaryActionLabelKo: String = "이 설정으로 실행하기",
    val secondaryActionLabelKo: String = "미니 보드로 열기",
    val storyboardCaptionKo: String = ""
) {
    val perPersonWon: Long
        get() = InteractiveMorphEngine.computeSplitPerPerson(totalAmountWon, headcount, roundTo100Won)
}

/**
 * Natural-Language -> Interactive UI + Motion Storyboard Synthesizer.
 *
 * Transforms flat natural language requests + active [HumanMemoryBubble]s into
 * direct-manipulation widgets (steppers, toggles, selectable cards, and animated
 * route storyboards) without sacrificing pure achromatic clarity.
 */
object InteractiveMorphEngine {

    private val commaNumberRegex = Regex("""(\d[\d,]{2,})\s*원?""")
    private val manCheonWonRegex = Regex("""(\d+(?:\.\d+)?)\s*만(?:\s*(\d+)\s*천)?\s*원?""")
    private val headcountRegex = Regex("""(\d{1,2})\s*(?:명|인분|인)""")

    fun parseAmountWon(prompt: String): Long? {
        val manMatch = manCheonWonRegex.find(prompt)
        if (manMatch != null) {
            val man = manMatch.groupValues[1].toDoubleOrNull() ?: 0.0
            val cheon = manMatch.groupValues.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val total = (man * 10_000.0 + cheon * 1_000.0).roundToLong()
            if (total > 0L) return total
        }
        val numMatch = commaNumberRegex.findAll(prompt)
            .mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }
            .firstOrNull { it >= 500L }
        return numMatch
    }

    fun parseHeadcount(prompt: String): Int? {
        val match = headcountRegex.find(prompt) ?: return null
        val n = match.groupValues[1].toIntOrNull() ?: return null
        return n.coerceIn(1, 30)
    }

    fun computeSplitPerPerson(totalAmountWon: Long, headcount: Int, roundTo100Won: Boolean): Long {
        if (totalAmountWon <= 0L) return 0L
        val safeCount = headcount.coerceIn(1, 50)
        val raw = totalAmountWon / safeCount
        return if (roundTo100Won) {
            ((totalAmountWon + safeCount - 1) / safeCount + 99L) / 100L * 100L
        } else {
            raw
        }
    }

    fun formatWon(amount: Long): String {
        val sign = if (amount < 0L) "-" else ""
        val absStr = kotlin.math.abs(amount).toString()
        val sb = StringBuilder()
        var count = 0
        for (i in absStr.length - 1 downTo 0) {
            sb.append(absStr[i])
            count++
            if (count % 3 == 0 && i != 0) sb.append(',')
        }
        return "${sign}${sb.reverse()}원"
    }

    fun synthesizeFromPrompt(
        prompt: String,
        bubbles: List<HumanMemoryBubble> = emptyList()
    ): InteractiveMorphSurface? {
        val startedNs = System.nanoTime()
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        val activeBubbles = bubbles.filter { it.enabled }

        val isSplit = lower.contains("더치페이") || lower.contains("정산") ||
            lower.contains("n빵") || lower.contains("나눠") || lower.contains("dutch") ||
            (parseAmountWon(trimmed) != null && parseHeadcount(trimmed) != null)
        val isCall = lower.contains("전화") || lower.contains("통화") || lower.contains("대본") ||
            lower.contains("call")
        val isFood = lower.contains("메뉴") || lower.contains("족발") || lower.contains("배달") ||
            lower.contains("맛집") || lower.contains("카페") || lower.contains("커피") ||
            lower.contains("식당") || lower.contains("밥") || lower.contains("추천")
        val isStoryboard = lower.contains("스토리보드") || lower.contains("코스") ||
            lower.contains("동선") || lower.contains("산책") || lower.contains("영상") ||
            lower.contains("모션") || lower.contains("여행") || lower.contains("성수")
        val isTimeline = lower.contains("일정") || lower.contains("알람") ||
            lower.contains("타이머") || lower.contains("약속") || lower.contains("회의")

        val surface = when {
            isSplit -> buildSplitSurface(trimmed, activeBubbles, includeStoryboard = isStoryboard)
            isCall -> buildCallSurface(trimmed, activeBubbles)
            isStoryboard && !isFood -> buildStoryboardSurface(trimmed, activeBubbles)
            isFood -> buildFoodCuratorSurface(trimmed, activeBubbles)
            isTimeline -> buildTimelineSurface(trimmed, activeBubbles)
            else -> return null
        }

        val elapsedMs = (((System.nanoTime() - startedNs) / 1_000_000L).toInt()).coerceIn(28, 120)
        return surface.copy(synthesisMs = elapsedMs)
    }

    private fun buildSplitSurface(
        prompt: String,
        activeBubbles: List<HumanMemoryBubble>,
        includeStoryboard: Boolean
    ): InteractiveMorphSurface {
        val totalWon = parseAmountWon(prompt) ?: 148_000L
        val people = parseHeadcount(prompt) ?: 4
        val placeBubbles = activeBubbles
            .filter { it.category == "PLACE" || it.category == "COMMUTE" || it.category == "GENERAL" }
            .take(2)
            .map { "${it.emoji} ${it.factKo}".trim() }

        val stops = if (includeStoryboard) {
            defaultCourseStops()
        } else {
            emptyList()
        }

        return InteractiveMorphSurface(
            id = "morph_split_${prompt.hashCode()}",
            kind = MorphKind.SPLIT_BILL,
            sourcePrompt = prompt,
            synthesisMs = 48,
            badgeKo = if (includeStoryboard) "더치페이 + 동선 모션 보드" else "실시간 더치페이 계산기",
            titleKo = "1인 정산 금액 (총 ${formatWon(totalWon)})",
            subtitleKo = "인원을 조절하거나 반올림 옵션을 바꾸고 바로 카톡으로 보내보세요.",
            appliedBubbleLabels = placeBubbles,
            totalAmountWon = totalWon,
            headcount = people,
            roundTo100Won = true,
            stops = stops,
            primaryActionLabelKo = "카톡 정산 카드 보내기 · 바로 실행",
            secondaryActionLabelKo = "앱 워크벤치에서 열기",
            storyboardCaptionKo = if (includeStoryboard) "성수 저녁 3코스 동선 프리뷰" else ""
        )
    }

    private fun buildFoodCuratorSurface(
        prompt: String,
        activeBubbles: List<HumanMemoryBubble>
    ): InteractiveMorphSurface {
        val foodBubbles = activeBubbles
            .filter { it.category == "FOOD" || it.category == "PLACE" }
            .take(3)
            .map { "${it.emoji} ${it.factKo}".trim() }

        val isCoffee = prompt.contains("커피") || prompt.contains("카페") || prompt.contains("아메리카노")
        val items = if (isCoffee) {
            listOf(
                MorphSelectableItem("opt_1", "연한 아이스 아메리카노", "취향 반영 · 산미 적은 원두", "4,500원", selected = true),
                MorphSelectableItem("opt_2", "오트 플랫 화이트", "부드러운 라떼 · 디카페인 변경 가능", "5,800원", selected = false),
                MorphSelectableItem("opt_3", "저당 말차 라떼", "조용한 창가 매장에서 픽업 8분", "6,200원", selected = false)
            )
        } else {
            listOf(
                MorphSelectableItem("opt_1", "덜 매운 마늘보쌈 & 순한 족발 (중)", "취향 '덜 맵게' 자동 필터 적용", "38,000원", selected = true),
                MorphSelectableItem("opt_2", "성수 로스터리 담백 브런치 세트", "조용한 창가석 예약 가능", "29,000원", selected = false),
                MorphSelectableItem("opt_3", "저자극 들기름 막국수 & 수육", "별점 4.9 · 배달 24분 예정", "31,000원", selected = false)
            )
        }

        return InteractiveMorphSurface(
            id = "morph_food_${prompt.hashCode()}",
            kind = MorphKind.FOOD_CURATOR,
            sourcePrompt = prompt,
            synthesisMs = 56,
            badgeKo = "취향 반영 인터랙티브 메뉴 보드",
            titleKo = "내 취향을 자동 반영해 3가지로 추렸어요",
            subtitleKo = "원하는 카드를 탭해 선택하면 결제 직전까지만 자동으로 담아드려요.",
            appliedBubbleLabels = foodBubbles.ifEmpty { listOf("🍗 음식은 덜 맵게 반영") },
            selectableItems = items,
            primaryActionLabelKo = "선택한 메뉴 장바구니 담기",
            secondaryActionLabelKo = "다른 메뉴 더 찾기"
        )
    }

    private fun buildStoryboardSurface(
        prompt: String,
        activeBubbles: List<HumanMemoryBubble>
    ): InteractiveMorphSurface {
        val bubbles = activeBubbles.take(2).map { "${it.emoji} ${it.factKo}".trim() }
        val stops = defaultCourseStops()
        val items = stops.mapIndexed { idx, s ->
            MorphSelectableItem(
                id = "stop_$idx",
                title = s.placeLabel,
                subtitle = "${s.timeLabel} · ${s.note}",
                badge = "0${idx + 1}",
                selected = idx == 0
            )
        }
        return InteractiveMorphSurface(
            id = "morph_sb_${prompt.hashCode()}",
            kind = MorphKind.MEDIA_STORYBOARD,
            sourcePrompt = prompt,
            synthesisMs = 62,
            badgeKo = "모션 동선 스토리보드",
            titleKo = "성수동 저녁 3코스 모션 프리뷰",
            subtitleKo = "타임라인을 재생하거나 구간을 눌러 바로 길찾기·예약을 실행하세요.",
            appliedBubbleLabels = bubbles,
            stops = stops,
            selectableItems = items,
            primaryActionLabelKo = "선택 구간 길찾기 시작",
            secondaryActionLabelKo = "코스 요약 공유하기",
            storyboardCaptionKo = "PLAYABLE ROUTE · 3 STOPS"
        )
    }

    private fun buildTimelineSurface(
        prompt: String,
        activeBubbles: List<HumanMemoryBubble>
    ): InteractiveMorphSurface {
        val commuteBubbles = activeBubbles
            .filter { it.category == "COMMUTE" || it.category == "SCHEDULE" || it.category == "GENERAL" }
            .take(2)
            .map { "${it.emoji} ${it.factKo}".trim() }
        val items = listOf(
            MorphSelectableItem("slot_1", "18:35 출발 알림 + 지하철 2호선", "약속 25분 전 자동 브리핑", "추천", selected = true),
            MorphSelectableItem("slot_2", "19:00 조용한 창가석 체크인", "취향 버블 자동 적용", "예약", selected = false),
            MorphSelectableItem("slot_3", "21:15 귀가 택시 준비 카드 띄우기", "실행 전 1회 확인 후 호출", "안전", selected = false)
        )
        return InteractiveMorphSurface(
            id = "morph_time_${prompt.hashCode()}",
            kind = MorphKind.TIMELINE_PLANNER,
            sourcePrompt = prompt,
            synthesisMs = 52,
            badgeKo = "인터랙티브 타임라인 스위치",
            titleKo = "일정에 맞춰 자동 준비 항목을 골라보세요",
            subtitleKo = "켜진 항목만 OS 알람·캘린더·알림에 바로 등록해요.",
            appliedBubbleLabels = commuteBubbles,
            selectableItems = items,
            primaryActionLabelKo = "선택 일정 바로 등록",
            secondaryActionLabelKo = "브리핑 새로고침"
        )
    }

    private fun buildCallSurface(
        prompt: String,
        activeBubbles: List<HumanMemoryBubble>
    ): InteractiveMorphSurface {
        val bubbles = activeBubbles.take(2).map { "${it.emoji} ${it.factKo}".trim() }
        val items = listOf(
            MorphSelectableItem("call_1", "1. 인사와 방문 시간 전달", "\"안녕하세요, 오늘 저녁 7시 4명 자리 문의드려요.\"", "도입", selected = true),
            MorphSelectableItem("call_2", "2. 조용한 창가석 및 메뉴 옵션 확인", "\"조용한 창가 쪽 자리와 순한맛 가능할까요?\"", "취향", selected = true),
            MorphSelectableItem("call_3", "3. 예약 확정 및 감사 인사", "\"네, 7시에 성함 한슨으로 뵙겠습니다. 감사합니다.\"", "마무리", selected = true)
        )
        return InteractiveMorphSurface(
            id = "morph_call_${prompt.hashCode()}",
            kind = MorphKind.CALL_CONCIERGE,
            sourcePrompt = prompt,
            synthesisMs = 54,
            badgeKo = "전화 전 말할 대본 보드",
            titleKo = "전화를 걸기 전에 대본을 한눈에 준비했어요",
            subtitleKo = "항목을 확인한 뒤 버튼을 누르면 안전하게 전화 앱을 열어드려요.",
            appliedBubbleLabels = bubbles,
            selectableItems = items,
            primaryActionLabelKo = "승인 후 전화 대본 열고 발신",
            secondaryActionLabelKo = "대본 메모에 저장"
        )
    }

    private fun defaultCourseStops(): List<MorphRouteStop> = listOf(
        MorphRouteStop("18:30", "뚝섬역 4번출구", "지하철 도착"),
        MorphRouteStop("19:00", "성수 로스터리", "창가 자리"),
        MorphRouteStop("20:30", "서울숲 산책길", "귀가 동선")
    )

    fun buildExecutionPrompt(surface: InteractiveMorphSurface): String {
        return when (surface.kind) {
            MorphKind.SPLIT_BILL -> {
                val perPerson = formatWon(surface.perPersonWon)
                val roundingNote = if (surface.roundTo100Won) "100원 단위 깔끔 반올림" else "원 단위 정확 계산"
                "총 ${formatWon(surface.totalAmountWon)}을 ${surface.headcount}명으로 나눈 1인 $perPerson ($roundingNote) 정산 안내 메시지를 카카오톡 나와의 채팅 또는 모임방에 작성 준비해줘"
            }
            MorphKind.FOOD_CURATOR -> {
                val picked = surface.selectableItems.firstOrNull { it.selected } ?: surface.selectableItems.firstOrNull()
                val title = picked?.title ?: surface.sourcePrompt
                val prefs = surface.appliedBubbleLabels.joinToString(", ")
                val suffix = if (prefs.isNotBlank()) " ($prefs 반영)" else ""
                "'$title'$suffix 메뉴를 찾아 장바구니 담기 직전까지 준비해줘 (결제 전 반드시 멈춤)"
            }
            MorphKind.MEDIA_STORYBOARD -> {
                val picked = surface.selectableItems.firstOrNull { it.selected } ?: surface.selectableItems.firstOrNull()
                "지도 앱에서 '${picked?.title ?: "성수동"}' 위치 길찾기를 열어줘"
            }
            MorphKind.TIMELINE_PLANNER -> {
                val picked = surface.selectableItems.firstOrNull { it.selected } ?: surface.selectableItems.firstOrNull()
                "'${picked?.title ?: surface.sourcePrompt}' 일정과 알림을 등록해줘"
            }
            MorphKind.CALL_CONCIERGE -> {
                "준비된 통화 대본을 메모장에 저장하고 전화 앱 발신 준비를 열어줘"
            }
        }
    }

    fun buildAchromaticMicroAppHtml(surface: InteractiveMorphSurface): String {
        val safeTitle = escapeHtml(surface.titleKo)
        val safeSub = escapeHtml(surface.subtitleKo)
        val execPromptEsc = escapeJsString(buildExecutionPrompt(surface))
        val detailRows = when (surface.kind) {
            MorphKind.SPLIT_BILL -> """
                <div class="big">${formatWon(surface.perPersonWon)} <span class="unit">/ 인 (${surface.headcount}명)</span></div>
                <div class="meta">총 ${formatWon(surface.totalAmountWon)} · ${if (surface.roundTo100Won) "100원 깔끔 반올림" else "원 단위 계산"}</div>
            """.trimIndent()
            else -> surface.selectableItems.joinToString("") { item ->
                val activeMark = if (item.selected) "●" else "○"
                """<div class="row"><span>$activeMark ${escapeHtml(item.title)}</span><span class="tag">${escapeHtml(item.badge)}</span></div>"""
            }
        }

        return """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<style>
  html, body { filter: grayscale(100%) !important; -webkit-filter: grayscale(100%) !important; background: transparent; font-family: -apple-system, BlinkMacSystemFont, "Pretendard", sans-serif; color: #09090B; margin: 0; padding: 6px; }
  .card { background: #FFFFFF; border: 1.5px solid #09090B; border-radius: 22px; padding: 16px; box-shadow: 0 3px 0 0 #09090B; }
  .badge { display: inline-block; font-size: 11px; font-weight: 700; background: #F4F4F5; padding: 4px 10px; border-radius: 999px; margin-bottom: 8px; }
  .title { font-size: 16px; font-weight: 800; margin-bottom: 4px; }
  .sub { font-size: 12px; color: #52525B; margin-bottom: 12px; }
  .big { font-size: 24px; font-weight: 900; margin-bottom: 4px; }
  .unit { font-size: 13px; color: #52525B; font-weight: 600; }
  .meta { font-size: 12px; color: #52525B; margin-bottom: 12px; }
  .row { display: flex; justify-content: space-between; align-items: center; background: #F4F4F5; border-radius: 12px; padding: 8px 10px; margin-bottom: 6px; font-size: 12.5px; font-weight: 700; }
  .tag { font-size: 11px; color: #52525B; }
  .cta { width: 100%; border: none; background: #09090B; color: #FAFAFA; border-radius: 999px; padding: 11px; font-size: 13px; font-weight: 800; cursor: pointer; margin-top: 8px; }
</style>
</head>
<body>
  <div class="card">
    <div class="badge">${escapeHtml(surface.badgeKo)}</div>
    <div class="title">$safeTitle</div>
    <div class="sub">$safeSub</div>
    $detailRows
    <button class="cta" onclick="window.HansonOS && window.HansonOS.runCommand('$execPromptEsc')">${escapeHtml(surface.primaryActionLabelKo)}</button>
  </div>
</body>
</html>
        """.trimIndent()
    }

    private fun escapeHtml(input: String): String =
        input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private fun escapeJsString(input: String): String =
        input.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\u0022")
            .replace("<", "\\u003C")
            .replace(">", "\\u003E")
            .replace("&", "\\u0026")
            .replace("\n", " ")
            .replace("\r", " ")
            .replace("\u2028", " ")
            .replace("\u2029", " ")
}
