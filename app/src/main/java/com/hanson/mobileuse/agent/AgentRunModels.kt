package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject

enum class SubGoalStatus(val marker: String) {
    PENDING("[ ]"),
    IN_PROGRESS("[/]"),
    COMPLETED("[x]")
}

data class SubGoalItem(
    val title: String,
    val status: SubGoalStatus = SubGoalStatus.PENDING
)

data class AgentStepLog(
    val stepNumber: Int,
    val observation: String,
    val planLedger: List<SubGoalItem>,
    val toolName: String,
    val toolArgsJson: String,
    val resultSummary: String,
    val succeeded: Boolean,
    val activePackage: String = "",
    val elementCount: Int = 0,
    val screenshotBase64: String? = null,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class ChatSender {
    USER,
    AGENT,
    ACTION,
    NOTIFICATION
}

/**
 * Pre-structured Human UI Card Templates that the AI and tools can selectively populate.
 * Keeps the main chat clean and scannable while exposing rich details on tap.
 */
enum class StructuredUiTemplateType(val labelKo: String) {
    CURATED_LIST("핵심 큐레이션 리스트"),
    METRIC_GRID("핵심 상태·지표 그리드"),
    COMPARISON_TABLE("비교·분석 테이블"),
    STEP_TIMELINE("실행 궤적 요약"),
    CONTROL_RECEIPT("기기·시스템 제어 완료"),
    MEMORY_DASHBOARD("3-Tier 메모리 대시보드")
}

/**
 * Multimodal Media Formats supported inline in Chat & Board (Ahaboard.ai style).
 * Supports IMAGE (.png/.jpg/SVG/Canvas), GIF (animated loops), MP4 (motion-graphic video),
 * GLB_3D (interactive 3D mesh/wireframe/PBR viewer), and CODE_CANVAS (live HTML5/JS micro-UI).
 */
enum class MediaFormatType(val badge: String, val labelKo: String) {
    IMAGE("IMAGE", "생성형 이미지 · 일러스트"),
    GIF("GIF · LOOP", "루프 모션 GIF 애니메이션"),
    MP4("MP4 · VIDEO", "모션그래픽 MP4 비디오"),
    YOUTUBE("YOUTUBE · IN-APP", "인앱 유튜브 비디오 플레이어"),
    GLB_3D("GLB · 3D", "인터랙티브 3D 모델 (GLB/WebGL)"),
    CODE_CANVAS("CODE · UI", "라이브 코드 · SVG 위젯")
}

/**
 * Self-contained Multimodal Media Block (`IMAGE`, `GIF`, `MP4`, `YOUTUBE`, `GLB_3D`, `CODE_CANVAS`)
 * rendered inline right inside the chat message bubble AND expandable inside `ChatDetailModalDialog`.
 */
data class MultimodalMediaBlock(
    val format: MediaFormatType,
    val title: String,
    val subtitle: String = "",
    val mediaUrlOrUri: String = "",
    val inlineHtmlContent: String = "",
    val recommendedHeightDp: Int = 225,
    val interactiveHintKo: String = "드래그·터치로 직접 조작하거나 탭하여 크게 열기 ↗",
    val localFilePath: String = "",
    val fileSizeBytes: Long = 0L,
    val sourceLabel: String = "",
    val youtubeVideoId: String = "",
    val generatedSourceCode: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format.name)
        put("title", title)
        put("subtitle", subtitle)
        put("mediaUrlOrUri", mediaUrlOrUri)
        put("inlineHtmlContent", inlineHtmlContent)
        put("recommendedHeightDp", recommendedHeightDp)
        put("interactiveHintKo", interactiveHintKo)
        put("localFilePath", localFilePath)
        put("fileSizeBytes", fileSizeBytes)
        put("sourceLabel", sourceLabel)
        put("youtubeVideoId", youtubeVideoId)
        put("generatedSourceCode", generatedSourceCode)
    }

    companion object {
        fun fromJson(obj: JSONObject?): MultimodalMediaBlock? {
            if (obj == null) return null
            val fmtName = obj.optString("format", MediaFormatType.CODE_CANVAS.name)
            val fmt = runCatching { MediaFormatType.valueOf(fmtName) }
                .getOrDefault(MediaFormatType.CODE_CANVAS)
            return MultimodalMediaBlock(
                format = fmt,
                title = obj.optString("title", ""),
                subtitle = obj.optString("subtitle", ""),
                mediaUrlOrUri = obj.optString("mediaUrlOrUri", ""),
                inlineHtmlContent = obj.optString("inlineHtmlContent", ""),
                recommendedHeightDp = obj.optInt("recommendedHeightDp", 225),
                interactiveHintKo = obj.optString("interactiveHintKo", "드래그·터치로 직접 조작하거나 탭하여 크게 열기 ↗"),
                localFilePath = obj.optString("localFilePath", ""),
                fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                sourceLabel = obj.optString("sourceLabel", ""),
                youtubeVideoId = obj.optString("youtubeVideoId", ""),
                generatedSourceCode = obj.optString("generatedSourceCode", "")
            )
        }
    }
}

data class StructuredUiItem(
    val badge: String = "",
    val title: String,
    val subtitle: String = "",
    val meta: String = "",
    val actionUrlOrCmd: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("badge", badge)
        put("title", title)
        put("subtitle", subtitle)
        put("meta", meta)
        put("actionUrlOrCmd", actionUrlOrCmd)
    }

    companion object {
        fun fromJson(obj: JSONObject): StructuredUiItem = StructuredUiItem(
            badge = obj.optString("badge", ""),
            title = obj.optString("title", ""),
            subtitle = obj.optString("subtitle", ""),
            meta = obj.optString("meta", ""),
            actionUrlOrCmd = obj.optString("actionUrlOrCmd", "")
        )
    }
}

data class StructuredUiCard(
    val templateType: StructuredUiTemplateType,
    val headerTitle: String,
    val headerSubtitle: String = "",
    val items: List<StructuredUiItem> = emptyList(),
    val footerHint: String = "카드를 탭하면 전체 원문·사용 모델·실행 상세를 볼 수 있습니다 ↗"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("templateType", templateType.name)
        put("headerTitle", headerTitle)
        put("headerSubtitle", headerSubtitle)
        put("footerHint", footerHint)
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        put("items", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject?): StructuredUiCard? {
            if (obj == null) return null
            val typeName = obj.optString("templateType", StructuredUiTemplateType.CURATED_LIST.name)
            val type = runCatching { StructuredUiTemplateType.valueOf(typeName) }
                .getOrDefault(StructuredUiTemplateType.CURATED_LIST)
            val itemsArr = obj.optJSONArray("items")
            val parsedItems = mutableListOf<StructuredUiItem>()
            if (itemsArr != null) {
                for (i in 0 until itemsArr.length()) {
                    val itemObj = itemsArr.optJSONObject(i) ?: continue
                    parsedItems.add(StructuredUiItem.fromJson(itemObj))
                }
            }
            return StructuredUiCard(
                templateType = type,
                headerTitle = obj.optString("headerTitle", ""),
                headerSubtitle = obj.optString("headerSubtitle", ""),
                items = parsedItems,
                footerHint = obj.optString("footerHint", "카드를 탭하면 전체 원문·사용 모델·실행 상세를 볼 수 있습니다 ↗")
            )
        }
    }
}

/**
 * Deep Detail Payload displayed when the user taps on an assistant chat message.
 * Separates AI/tool technical telemetry & full raw text from the concise human-facing chat summary.
 */
data class ChatDetailPayload(
    val humanSummary: String,
    val modelBadge: String = "",
    val specialistRoleKo: String = "",
    val executedToolName: String = "",
    val executedToolArgs: String = "",
    val rawTechnicalOutput: String = "",
    val sourceUrl: String = "",
    val turnLedger: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("humanSummary", humanSummary)
        put("modelBadge", modelBadge)
        put("specialistRoleKo", specialistRoleKo)
        put("executedToolName", executedToolName)
        put("executedToolArgs", executedToolArgs)
        put("rawTechnicalOutput", rawTechnicalOutput)
        put("sourceUrl", sourceUrl)
        val arr = JSONArray()
        turnLedger.forEach { arr.put(it) }
        put("turnLedger", arr)
    }

    companion object {
        fun fromJson(obj: JSONObject?): ChatDetailPayload? {
            if (obj == null) return null
            val ledgerArr = obj.optJSONArray("turnLedger")
            val ledger = mutableListOf<String>()
            if (ledgerArr != null) {
                for (i in 0 until ledgerArr.length()) {
                    ledger.add(ledgerArr.optString(i))
                }
            }
            return ChatDetailPayload(
                humanSummary = obj.optString("humanSummary", ""),
                modelBadge = obj.optString("modelBadge", ""),
                specialistRoleKo = obj.optString("specialistRoleKo", ""),
                executedToolName = obj.optString("executedToolName", ""),
                executedToolArgs = obj.optString("executedToolArgs", ""),
                rawTechnicalOutput = obj.optString("rawTechnicalOutput", ""),
                sourceUrl = obj.optString("sourceUrl", ""),
                turnLedger = ledger
            )
        }
    }
}

/**
 * Continuous Runtime Live Chat Message.
 * - `text` always holds the clean, human-readable Korean summary.
 * - `structuredCard` holds an optional pre-structured visual UI card rendered inline.
 * - `mediaBlock` holds an optional Ahaboard-style multimodal media block (`IMAGE`, `GIF`, `MP4`, `GLB_3D`, `CODE_CANVAS`) rendered inline!
 * - `detailPayload` holds the full technical/raw details shown when the user taps the message.
 */
data class LiveChatMessage(
    val id: String = "msg_${System.nanoTime()}",
    val sender: ChatSender,
    val text: String,
    val subtitle: String = "",
    val toolBadge: String? = null,
    val succeeded: Boolean = true,
    val replySessionId: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
    val structuredCard: StructuredUiCard? = null,
    val mediaBlock: MultimodalMediaBlock? = null,
    val detailPayload: ChatDetailPayload? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("sender", sender.name)
        put("text", text)
        put("subtitle", subtitle)
        if (toolBadge != null) put("toolBadge", toolBadge)
        put("succeeded", succeeded)
        if (replySessionId != null) put("replySessionId", replySessionId)
        put("timestampMs", timestampMs)
        if (structuredCard != null) put("structuredCard", structuredCard.toJson())
        if (mediaBlock != null) put("mediaBlock", mediaBlock.toJson())
        if (detailPayload != null) put("detailPayload", detailPayload.toJson())
    }
}

/**
 * Automatically converts raw AI/tool outputs (such as `[모바일 Browser-Use 추출 완료]`,
 * raw device telemetry, or multi-step logs) into:
 * 1. A clean, natural Korean human summary (`text`)
 * 2. A structured UI card (`StructuredUiCard`) selected from the template library
 * 3. A deep detail payload (`ChatDetailPayload`) accessible on tap.
 */
object HumanSummaryAndCardBuilder {

    fun decodeAllHtmlEntities(raw: String): String {
        if (raw.isEmpty()) return raw
        var out = raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#x2F;", "/")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        out = Regex("&#(\\d+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 32..0x10FFFF) code.toChar().toString() else m.value
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 32..0x10FFFF) code.toChar().toString() else m.value
        }
        return out
    }

    fun enrichMessageForHumanDisplay(
        msg: LiveChatMessage,
        specialistRoleKo: String = "",
        turnLedger: List<String> = emptyList()
    ): LiveChatMessage {
        if (msg.sender == ChatSender.USER) return msg
        val cleanedRaw = decodeAllHtmlEntities(msg.text).trim()

        // If already enriched with a structuredCard and clean text, just ensure detailPayload is present
        if (msg.structuredCard != null && !cleanedRaw.startsWith("[모바일 Browser-Use")) {
            val detail = msg.detailPayload ?: ChatDetailPayload(
                humanSummary = cleanedRaw,
                modelBadge = msg.toolBadge.orEmpty(),
                specialistRoleKo = specialistRoleKo,
                rawTechnicalOutput = cleanedRaw,
                turnLedger = turnLedger
            )
            return msg.copy(text = cleanedRaw, detailPayload = detail)
        }

        // Case 1: Mobile Browser-Use raw dump (e.g., Hacker News, GitHub, Web Extraction)
        if (cleanedRaw.contains("[모바일 Browser-Use") || (msg.toolBadge?.contains("BROWSER") == true && cleanedRaw.contains("URL:"))) {
            return transformBrowserUseDump(msg, cleanedRaw, specialistRoleKo, turnLedger)
        }

        // Case 2: Bluetooth / Peripheral / Cross-Device status dump
        if (msg.toolBadge?.contains("PERIPHERAL") == true || cleanedRaw.contains("블루투스") && cleanedRaw.contains("오디오")) {
            val card = StructuredUiCard(
                templateType = StructuredUiTemplateType.METRIC_GRID,
                headerTitle = "주변 기기 및 오디오 연결 상태",
                headerSubtitle = "실시간 블루투스 · 스피커폰 · 브리지 스캔",
                items = listOf(
                    StructuredUiItem("BT", "블루투스 어댑터", cleanedRaw.take(65), "ACTIVE"),
                    StructuredUiItem("AUDIO", "오디오 라우팅", "온디바이스 스피커 / 이어폰 상태 확인 완료", "READY")
                )
            )
            return msg.copy(
                text = cleanedRaw.lines().firstOrNull()?.take(110) ?: "주변 기기 및 오디오 연결 상태를 확인했습니다.",
                structuredCard = card,
                detailPayload = ChatDetailPayload(
                    humanSummary = cleanedRaw,
                    modelBadge = msg.toolBadge.orEmpty(),
                    specialistRoleKo = specialistRoleKo.ifBlank { "크로스 디바이스·주변기기 제어" },
                    executedToolName = "cross_device_control",
                    rawTechnicalOutput = cleanedRaw,
                    turnLedger = turnLedger
                )
            )
        }

        // Case 3: Multimodal Media Block (`GLB_3D`, `MP4`, `YOUTUBE`, `GIF`, `IMAGE`, `CODE_CANVAS`)
        if (msg.mediaBlock != null ||
            msg.toolBadge?.contains("CANVAS") == true ||
            msg.toolBadge?.contains("IMAGE") == true ||
            msg.toolBadge?.contains("GLB") == true ||
            msg.toolBadge?.contains("MP4") == true ||
            msg.toolBadge?.contains("YOUTUBE") == true ||
            msg.toolBadge?.contains("GIF") == true
        ) {
            val fmt = msg.mediaBlock?.format ?: when {
                msg.toolBadge?.contains("YOUTUBE") == true -> MediaFormatType.YOUTUBE
                msg.toolBadge?.contains("GLB") == true -> MediaFormatType.GLB_3D
                msg.toolBadge?.contains("MP4") == true -> MediaFormatType.MP4
                msg.toolBadge?.contains("GIF") == true -> MediaFormatType.GIF
                msg.toolBadge?.contains("IMAGE") == true -> MediaFormatType.IMAGE
                else -> MediaFormatType.CODE_CANVAS
            }
            val defaultSummary = when (fmt) {
                MediaFormatType.YOUTUBE -> "요청하신 유튜브 영상을 앱 내 인라인 플레이어로 바로 띄웠어요. 채팅창 안에서 바로 재생하거나 전체화면으로 볼 수 있습니다."
                MediaFormatType.GLB_3D -> "요청하신 3D 모델(.glb 파일)을 생성·로드해 채팅 카드에 바로 띄웠어요. 손가락으로 드래그해 360° 회전할 수 있습니다."
                MediaFormatType.MP4 -> "요청하신 비디오(.mp4 파일)를 로드해 인라인 플레이어로 띄웠어요. 재생/일시정지 및 타임라인 탐색이 가능합니다."
                MediaFormatType.GIF -> "요청하신 애니메이션(.gif 파일)을 생성·로드해 바로 띄웠어요."
                MediaFormatType.IMAGE -> "요청하신 이미지(.png 파일)를 생성·로드해 채팅 카드에 바로 띄웠어요."
                MediaFormatType.CODE_CANVAS -> "코드 어시스턴트 에이전트가 실시간으로 작성한 인터랙티브 UI 코드를 파일로 저장하고 바로 렌더링했어요."
            }
            val humanSummary = if (cleanedRaw.isNotBlank() && !cleanedRaw.startsWith("{") && !cleanedRaw.startsWith("<") && cleanedRaw.length <= 240) {
                cleanedRaw
            } else {
                defaultSummary
            }
            val detailTech = buildString {
                appendLine(cleanedRaw)
                msg.mediaBlock?.let { mb ->
                    if (mb.localFilePath.isNotBlank()) {
                        appendLine("Local File: ${mb.localFilePath} (${mb.fileSizeBytes} bytes)")
                    }
                    if (mb.mediaUrlOrUri.isNotBlank()) {
                        appendLine("Source URL: ${mb.mediaUrlOrUri}")
                    }
                    if (mb.youtubeVideoId.isNotBlank()) {
                        appendLine("YouTube Video ID: ${mb.youtubeVideoId} (https://www.youtube.com/watch?v=${mb.youtubeVideoId})")
                    }
                    if (mb.generatedSourceCode.isNotBlank()) {
                        appendLine("\n--- [Code Assistant Generated Source] ---")
                        appendLine(mb.generatedSourceCode.take(1800))
                    }
                }
            }.trim()
            return msg.copy(
                text = humanSummary,
                detailPayload = msg.detailPayload ?: ChatDetailPayload(
                    humanSummary = humanSummary,
                    modelBadge = msg.toolBadge ?: "NEXUS-${fmt.name}",
                    specialistRoleKo = specialistRoleKo.ifBlank { "Code Assistant & Multimodal File/YouTube Engine" },
                    executedToolName = if (fmt == MediaFormatType.CODE_CANVAS) "draw_with_code" else "generate_visual_media",
                    executedToolArgs = """{"format":"${fmt.name}","file":"${msg.mediaBlock?.localFilePath.orEmpty()}","yt":"${msg.mediaBlock?.youtubeVideoId.orEmpty()}"}""",
                    rawTechnicalOutput = detailTech,
                    sourceUrl = msg.mediaBlock?.mediaUrlOrUri.orEmpty(),
                    turnLedger = turnLedger
                )
            )
        }

        // Default: Ensure every assistant message has a tap-to-open detail payload
        val defaultDetail = msg.detailPayload ?: ChatDetailPayload(
            humanSummary = cleanedRaw,
            modelBadge = msg.toolBadge ?: "AUTO-ROUTER",
            specialistRoleKo = specialistRoleKo.ifBlank { "적재적소 멀티모델 오케스트레이션" },
            rawTechnicalOutput = cleanedRaw,
            turnLedger = turnLedger
        )
        return msg.copy(
            text = cleanedRaw,
            detailPayload = defaultDetail
        )
    }

    private fun transformBrowserUseDump(
        msg: LiveChatMessage,
        cleanedRaw: String,
        specialistRoleKo: String,
        turnLedger: List<String>
    ): LiveChatMessage {
        val pageTitle = Regex("제목:\\s*(.+)").find(cleanedRaw)?.groupValues?.getOrNull(1)?.trim()
            ?: "웹 페이지 브리핑"
        val url = Regex("URL:\\s*(\\S+)").find(cleanedRaw)?.groupValues?.getOrNull(1)?.trim()
            ?: ""
        val bodySection = cleanedRaw.substringAfter("핵심 본문 요약:", "")
            .substringBefore("주요 링크")
            .trim()

        // Parse numbered items like "1. Title ( domain.com ) 149 points by user 2 hours ago | hide | 25 comments 2. ..."
        val numberedRegex = Regex("(\\d{1,2})\\.\\s+([^0-9][^\\n]+?)(?=\\s+\\d{1,2}\\.\\s+|$)")
        val parsedItems = mutableListOf<StructuredUiItem>()

        for (m in numberedRegex.findAll(bodySection)) {
            if (parsedItems.size >= 5) break
            val rank = m.groupValues[1]
            val rawChunk = m.groupValues[2].trim()
            val domainMatch = Regex("\\(\\s*([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})\\s*\\)").find(rawChunk)
            val domain = domainMatch?.groupValues?.getOrNull(1) ?: ""
            val pointsMatch = Regex("(\\d+)\\s*points").find(rawChunk)?.groupValues?.getOrNull(1)
            val commentsMatch = Regex("(\\d+)\\s*comments").find(rawChunk)?.groupValues?.getOrNull(1)

            val cleanHeadline = rawChunk
                .substringBefore(" ( ")
                .substringBefore(" (")
                .replace(Regex("\\d+\\s*points.*$"), "")
                .trim()
                .take(85)

            if (cleanHeadline.length >= 4) {
                val metaParts = mutableListOf<String>()
                if (domain.isNotBlank()) metaParts.add(domain)
                if (pointsMatch != null) metaParts.add("▲ ${pointsMatch}점")
                if (commentsMatch != null) metaParts.add("댓글 ${commentsMatch}개")

                parsedItems.add(
                    StructuredUiItem(
                        badge = "#$rank",
                        title = cleanHeadline,
                        subtitle = metaParts.joinToString(" · ").ifBlank { pageTitle },
                        meta = if (pointsMatch != null) "${pointsMatch}pt" else "NEW",
                        actionUrlOrCmd = url
                    )
                )
            }
        }

        // Fallback if not a numbered list page
        if (parsedItems.isEmpty()) {
            val cleanBody = bodySection
                .replace(Regex("(?i)Hacker News\\s+new\\s*\\|\\s*past\\s*\\|\\s*comments\\s*\\|\\s*ask\\s*\\|\\s*show\\s*\\|\\s*jobs\\s*\\|\\s*submit\\s*login"), "")
                .trim()
            val sentences = cleanBody.split(Regex("(?<=[.!?다요])\\s+")).filter { it.length > 10 }.take(3)
            sentences.forEachIndexed { idx, s ->
                parsedItems.add(
                    StructuredUiItem(
                        badge = "0${idx + 1}",
                        title = s.take(80),
                        subtitle = url.substringAfter("://").substringBefore("/"),
                        meta = "핵심"
                    )
                )
            }
        }

        val humanHeadline = if (pageTitle.contains("Hacker News", ignoreCase = true) && parsedItems.isNotEmpty()) {
            "해커뉴스(Hacker News) 현재 인기 글 상위 ${parsedItems.size}건을 보기 편하게 정리했어요. 가장 주목받는 토픽은 '${parsedItems.first().title.take(38)}'입니다."
        } else {
            "'$pageTitle' 페이지의 핵심 내용을 추출해 한눈에 보기 쉽게 정리했어요."
        }

        val card = StructuredUiCard(
            templateType = StructuredUiTemplateType.CURATED_LIST,
            headerTitle = "$pageTitle · 핵심 브리핑",
            headerSubtitle = url.ifBlank { "모바일 브라우저 클린 추출" },
            items = parsedItems,
            footerHint = "카드를 탭하면 전체 원문·링크 및 AI 실행 상세를 볼 수 있습니다 ↗"
        )

        val detail = ChatDetailPayload(
            humanSummary = humanHeadline,
            modelBadge = msg.toolBadge ?: "NEXUS-BROWSER-USE",
            specialistRoleKo = specialistRoleKo.ifBlank { "모바일 헤드리스 브라우저 & 핵심 요약 큐레이터" },
            executedToolName = "browser_use",
            executedToolArgs = """{"url":"$url"}""",
            rawTechnicalOutput = cleanedRaw,
            sourceUrl = url,
            turnLedger = turnLedger
        )

        return msg.copy(
            text = humanHeadline,
            structuredCard = card,
            detailPayload = detail
        )
    }
}

/**
 * EZmap Reversibility Receipt ('에이전트가 수행한 작업')
 */
data class ActionReceipt(
    val id: String = "rcpt_${System.currentTimeMillis()}",
    val timestampMs: Long = System.currentTimeMillis(),
    val toolName: String,
    val summaryKo: String,
    val detailKo: String,
    val undoHintKo: String,
    val undoToolName: String = "",
    val undoArgsJson: String = "",
    val undone: Boolean = false,
    val approvalReceiptId: String? = null,
    val controlEpoch: Long? = null
) {
    val canOneTapUndo: Boolean
        get() = undoToolName.isNotBlank() && !undone
}

data class PendingApprovalRequest(
    val toolSpec: ToolSpec,
    val args: JSONObject,
    val reasonKo: String,
    val isHighStakesFinancial: Boolean = false,
    val detectedPriceText: String? = null,
    val priceDriftNoticeKo: String? = null,
    val targetAppOrMerchant: String? = null,
    val controlEpoch: Long = 0L,
    val externalApprovalReceiptId: String? = null
)

data class HumanTakeoverState(
    val takeoverId: String = "takeover_${System.currentTimeMillis()}",
    val kind: HighStakesKind,
    val titleKo: String,
    val reasonKo: String,
    val activePackage: String = "",
    val resumeHintKo: String = "직접 인증/확인을 마친 뒤 '직접 조작 완료 · 에이전트 재개'를 누르세요."
)

enum class AgentRunStatus(val labelKo: String) {
    IDLE("라이브 대기 중"),
    OBSERVING("화면 및 UI 트리 분석 중"),
    THINKING("멀티모델 적재적소 추론 중"),
    AWAITING_APPROVAL("사용자 승인 대기 중"),
    AWAITING_TAKEOVER("사용자 직접 조작 대기 중 (CAPTCHA / 본인확인)"),
    ACTING("모바일 액션 실행 중"),
    COMPLETED("라이브 응답 완료"),
    STOPPED("사용자 중단"),
    ERROR("오류 발생")
}

data class AgentRunSession(
    val runId: String = "run_${System.currentTimeMillis()}",
    val goal: String = "",
    val status: AgentRunStatus = AgentRunStatus.IDLE,
    val currentIteration: Int = 0,
    val maxIterations: Int = 15,
    val subGoals: List<SubGoalItem> = emptyList(),
    val steps: List<AgentStepLog> = emptyList(),
    val receipts: List<ActionReceipt> = emptyList(),
    val pendingApproval: PendingApprovalRequest? = null,
    val pendingTakeover: HumanTakeoverState? = null,
    val finalSummary: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long? = null
)

sealed class SafetyGateDecision {
    data object Allow : SafetyGateDecision()
    data class AskUser(val reasonKo: String) : SafetyGateDecision()
    data class Deny(val reasonKo: String) : SafetyGateDecision()
}

/**
 * Unified Tool Safety Gate combining EZmap's 3-tier governance with Hanson's
 * Unrestricted Autonomous Mode toggle.
 */
object ToolSafetyGate {

    fun evaluate(
        toolSpec: ToolSpec,
        args: JSONObject,
        permissionHub: PermissionHubManager
    ): SafetyGateDecision {
        if (toolSpec.riskTier == ToolRiskTier.SAFE) {
            return SafetyGateDecision.Allow
        }
        if (permissionHub.isUnrestrictedMode()) {
            return SafetyGateDecision.Allow
        }
        val capability = toolSpec.capability ?: return SafetyGateDecision.Allow
        return when (permissionHub.getCapabilityLevel(capability)) {
            PermissionLevel.ALWAYS -> SafetyGateDecision.Allow
            PermissionLevel.ASK_EVERY_TIME -> {
                val preview = args.toString().take(120)
                SafetyGateDecision.AskUser(
                    "${capability.displayName} 권한이 '실행 전 확인'으로 설정되어 있습니다.\n요청 도구: ${toolSpec.name} ($preview)"
                )
            }
            PermissionLevel.NEVER -> {
                SafetyGateDecision.Deny(
                    "${capability.displayName} 기능이 거버넌스 설정에서 '차단'되어 있습니다."
                )
            }
        }
    }
}
