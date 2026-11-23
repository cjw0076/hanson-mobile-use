package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive JUnit 4 verification suite for Google A2UI (`v1.0` / `v0.9` + `v0.8` compatibility)
 * integration inside Hanson Mobile-Use AI.
 */
class A2uiProtocolEngineTest {

    @Before
    fun setUp() {
        A2uiSurfaceRegistry.reset()
        DynamicUiEventBus.reset()
    }

    @After
    fun tearDown() {
        A2uiSurfaceRegistry.reset()
        DynamicUiEventBus.reset()
    }

    private fun resolveRepoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return if (File(cwd, "settings.gradle.kts").exists()) cwd else cwd.parentFile ?: cwd
    }

    // =========================================================================
    // 1. A2UI v1.0 & v0.9 Streaming JSONL Parsing + Adjacency-List Registry
    // =========================================================================

    @Test
    fun testV10JsonlStream_createUpdateDataModelAndDeleteSurface() {
        val jsonl = """
            {"version":"1.0","createSurface":{"surfaceId":"booking_v1","catalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json","sendDataModel":true}}
            {"version":"1.0","updateComponents":{"surfaceId":"booking_v1","components":[{"id":"root","component":"Column","children":["hdr","form_card","submit_btn"]},{"id":"hdr","component":"Text","text":"예약 확인: @{/booking/restaurant}","variant":"h2"},{"id":"form_card","component":"Card","child":"form_col"},{"id":"form_col","component":"Column","children":["guests_slider","name_input","agree_cb","seat_choice"]},{"id":"guests_slider","component":"Slider","label":"인원","min":1,"max":8,"value":{"@path":"/booking/guests"}},{"id":"name_input","component":"TextField","label":"예약자명","value":{"@path":"/booking/guestName"}},{"id":"agree_cb","component":"CheckBox","label":"알림 수신 동의","value":{"@path":"/booking/notify"}},{"id":"seat_choice","component":"ChoicePicker","label":"좌석 유형","variant":"mutuallyExclusive","options":[{"label":"창가석","value":"window"},{"label":"홀","value":"hall"}],"value":{"@path":"/booking/seat"}},{"id":"submit_lbl","component":"Text","text":"예약 확정"},{"id":"submit_btn","component":"Button","variant":"primary","child":"submit_lbl","action":{"event":{"name":"confirm_booking","context":{"restaurant":{"@path":"/booking/restaurant"},"guests":{"@path":"/booking/guests"},"guestName":{"@path":"/booking/guestName"},"notify":{"@path":"/booking/notify"},"seat":{"@path":"/booking/seat"},"channel":"android"}}}}]}}
            {"version":"1.0","updateDataModel":{"surfaceId":"booking_v1","path":"/booking","value":{"restaurant":"성수 오스테리아","guests":4,"guestName":"김한손","notify":true,"seat":["window"]}}}
        """.trimIndent()

        val results = A2uiSurfaceRegistry.ingestJsonl(jsonl)
        assertEquals(3, results.size)
        assertTrue(results.all { it is A2uiIngestResult.Applied })

        val surface = A2uiSurfaceRegistry.getSurface("booking_v1")
        assertNotNull(surface)
        assertEquals(A2uiProtocolConstants.VERSION_1_0, surface!!.protocolVersion)
        assertEquals(A2uiProtocolConstants.BASIC_CATALOG_V1_0, surface.catalogId)
        assertTrue(surface.sendDataModel)
        assertTrue(surface.hasRootComponent)
        assertEquals(10, surface.components.size)

        // Verify @path interpolation on headline Text
        val hdrNode = surface.components["hdr"]!!
        val resolvedTitle = A2uiDataBindingResolver.resolveString(hdrNode.properties["text"], surface.dataModel)
        assertEquals("예약 확인: 성수 오스테리아", resolvedTitle)

        // Two-way state mutations (TextField, CheckBox, ChoicePicker, Slider)
        assertTrue(A2uiSurfaceRegistry.updateDataModelAtPath("booking_v1", "/booking/guestName", "이모바일"))
        assertTrue(A2uiSurfaceRegistry.updateDataModelAtPath("booking_v1", "/booking/notify", false))
        assertTrue(A2uiSurfaceRegistry.updateDataModelAtPath("booking_v1", "/booking/guests", 6.0))
        assertTrue(A2uiSurfaceRegistry.updateDataModelAtPath("booking_v1", "/booking/seat", listOf("hall")))

        val updated = A2uiSurfaceRegistry.getSurface("booking_v1")!!
        assertEquals("이모바일", A2uiJsonPointer.resolve(updated.dataModel, "/booking/guestName"))
        assertEquals(false, A2uiJsonPointer.resolve(updated.dataModel, "/booking/notify"))
        assertEquals(6.0, A2uiJsonPointer.resolve(updated.dataModel, "/booking/guests"))
        assertEquals(listOf("hall"), A2uiJsonPointer.resolve(updated.dataModel, "/booking/seat"))

        // Dispatch outbound userAction and verify live dataModel context resolution
        var capturedAction: A2uiUserAction? = null
        A2uiSurfaceRegistry.onUserActionListener = { capturedAction = it }

        val btnNode = updated.components["submit_btn"]!!
        val actionSpec = A2uiDataBindingResolver.parseActionSpec(btnNode.properties["action"])!!
        val dispatched = A2uiSurfaceRegistry.dispatchUserAction("booking_v1", "submit_btn", actionSpec)
        assertNotNull(dispatched)
        assertNotNull(capturedAction)
        assertEquals("confirm_booking", capturedAction!!.name)
        assertEquals("booking_v1", capturedAction!!.surfaceId)
        assertEquals("submit_btn", capturedAction!!.sourceComponentId)
        assertEquals("이모바일", capturedAction!!.context["guestName"])
        assertEquals(false, capturedAction!!.context["notify"])
        assertEquals(6.0, capturedAction!!.context["guests"])
        assertEquals(listOf("hall"), capturedAction!!.context["seat"])
        assertEquals("android", capturedAction!!.context["channel"])

        val wireJson = capturedAction!!.toWireJson()
        assertEquals("1.0", wireJson.getString("version"))
        assertEquals("confirm_booking", wireJson.getJSONObject("action").getString("name"))
        assertEquals("이모바일", wireJson.getJSONObject("action").getJSONObject("context").getString("guestName"))

        // Delete key via updateDataModel with null value
        val delKeyRes = A2uiSurfaceRegistry.ingestJsonLine(
            """{"version":"1.0","updateDataModel":{"surfaceId":"booking_v1","path":"/booking/guestName","value":null}}"""
        )
        assertTrue(delKeyRes is A2uiIngestResult.Applied)
        assertNull(A2uiJsonPointer.resolve(A2uiSurfaceRegistry.getSurface("booking_v1")!!.dataModel, "/booking/guestName"))

        // Delete entire surface via deleteSurface
        val delSurfaceRes = A2uiSurfaceRegistry.ingestJsonLine(
            """{"version":"1.0","deleteSurface":{"surfaceId":"booking_v1"}}"""
        )
        assertTrue(delSurfaceRes is A2uiIngestResult.Applied)
        assertNull(A2uiSurfaceRegistry.getSurface("booking_v1"))
        assertTrue(A2uiSurfaceRegistry.surfaces.value.isEmpty())
    }

    @Test
    fun testV09JsonlAndSseDataLines_pathBindingsAndFormatStringFunction() {
        val sseStream = """
            : keepalive
            event: a2ui
            data: {"version":"v0.9","createSurface":{"surfaceId":"status_09","catalogId":"https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"}}

            data: {"version":"v0.9","updateComponents":{"surfaceId":"status_09","components":[{"id":"root","component":"Column","children":["banner","fmt_txt"]},{"id":"banner","component":"Text","text":"배터리 ${'$'}{/device/battery}% · ${'$'}{/device/mode}","variant":"h3"},{"id":"fmt_txt","component":"Text","text":{"call":"formatString","args":{"value":"모델: ${'$'}{/device/name}"}}}]}}
            data: {"version":"v0.9","updateDataModel":{"surfaceId":"status_09","path":"/device","value":{"battery":88,"mode":"PAPER","name":"Galaxy S25"}}}
        """.trimIndent()

        val results = A2uiSurfaceRegistry.ingestJsonl(sseStream)
        assertEquals(3, results.size)

        val surface = A2uiSurfaceRegistry.getSurface("status_09")!!
        assertEquals(A2uiProtocolConstants.VERSION_0_9, surface.protocolVersion)
        assertEquals(
            "배터리 88% · PAPER",
            A2uiDataBindingResolver.resolveString(surface.components["banner"]!!.properties["text"], surface.dataModel)
        )
        assertEquals(
            "모델: Galaxy S25",
            A2uiDataBindingResolver.resolveString(surface.components["fmt_txt"]!!.properties["text"], surface.dataModel)
        )

        // Verify v0.9 outbound wire envelope uses version = "v0.9"
        val action = A2uiSurfaceRegistry.dispatchUserAction(
            surfaceId = "status_09",
            sourceComponentId = "banner",
            actionSpec = A2uiActionSpec(name = "refresh_status")
        )!!
        assertEquals("v0.9", action.toWireJson().getString("version"))
    }

    // =========================================================================
    // 2. A2UI v0.8 Compatibility (`surfaceUpdate`, `dataModelUpdate`, `beginRendering`)
    // =========================================================================

    @Test
    fun testV08Compatibility_nestedComponentsAdjacencyDataModelAndBeginRendering() {
        val v08Stream = """
            {"surfaceUpdate":{"surfaceId":"legacy_08","components":[{"id":"main_col","component":{"Column":{"children":{"explicitList":["title_txt","mc_picker","go_btn"]}}}},{"id":"title_txt","component":{"Text":{"text":{"path":"/header/title","literalString":"기본 제목"},"usageHint":"h1"}}},{"id":"mc_picker","component":{"MultipleChoice":{"label":{"literalString":"옵션 선택"},"maxAllowedSelections":2,"options":[{"label":{"literalString":"빠른 배송"},"value":"fast"},{"label":{"literalString":"포장"},"value":"pickup"}],"selections":{"path":"/form/selected"}}}},{"id":"go_lbl","component":{"Text":{"text":{"literalString":"전송"}}}},{"id":"go_btn","component":{"Button":{"child":"go_lbl","primary":true,"action":{"name":"submit_legacy","context":[{"key":"title","value":{"path":"/header/title"}},{"key":"selected","value":{"path":"/form/selected"}}]}}}}]}}
            {"dataModelUpdate":{"surfaceId":"legacy_08","path":"/","contents":[{"key":"header","valueMap":[{"key":"title","valueString":"v0.8 호환 카드"}]},{"key":"form","valueMap":[{"key":"selected","valueString":"fast"}]}]}}
            {"beginRendering":{"surfaceId":"legacy_08","root":"main_col"}}
        """.trimIndent()

        val results = A2uiSurfaceRegistry.ingestJsonl(v08Stream)
        assertEquals(3, results.size)

        val surface = A2uiSurfaceRegistry.getSurface("legacy_08")!!
        assertEquals(A2uiProtocolConstants.VERSION_0_8, surface.protocolVersion)
        assertTrue(surface.isReady)
        assertEquals("main_col", surface.rootComponentId)
        assertTrue(surface.hasRootComponent)

        val titleNode = surface.components["title_txt"]!!
        assertEquals("Text", titleNode.type)
        assertEquals(
            "v0.8 호환 카드",
            A2uiDataBindingResolver.resolveString(titleNode.properties["text"], surface.dataModel)
        )

        val childrenSpec = A2uiDataBindingResolver.resolveChildrenSpec(surface.components["main_col"]!!)
        assertEquals(listOf("title_txt", "mc_picker", "go_btn"), childrenSpec.explicitIds)

        val btnSpec = A2uiDataBindingResolver.parseActionSpec(surface.components["go_btn"]!!.properties["action"])!!
        val dispatched = A2uiSurfaceRegistry.dispatchUserAction("legacy_08", "go_btn", btnSpec)!!
        assertEquals("submit_legacy", dispatched.name)
        assertEquals("v0.8 호환 카드", dispatched.context["title"])
        assertEquals("fast", dispatched.context["selected"])

        val v08Wire = dispatched.toWireJson()
        assertTrue("v0.8 outbound wire format must use userAction envelope", v08Wire.has("userAction"))
        assertEquals("submit_legacy", v08Wire.getJSONObject("userAction").getString("name"))
    }

    // =========================================================================
    // 3. RFC 6901 JSON Pointer Escaping, Arrays, and Scoped Relative Paths
    // =========================================================================

    @Test
    fun testRfc6901JsonPointer_escapingArraysAndTemplateScoping() {
        var model: Map<String, Any?> = emptyMap()
        model = A2uiJsonPointer.set(model, "/meta/a~1b/c~0d", "escaped_ok")
        assertEquals("escaped_ok", A2uiJsonPointer.resolve(model, "/meta/a~1b/c~0d"))

        model = A2uiJsonPointer.set(
            model,
            "/items",
            listOf(
                mapOf("title" to "첫 번째", "score" to 10),
                mapOf("title" to "두 번째", "score" to 20)
            )
        )
        assertEquals("두 번째", A2uiJsonPointer.resolve(model, "title", scopePath = "/items/1"))
        assertEquals(20, A2uiJsonPointer.resolve(model, "./score", scopePath = "/items/1"))

        // Append via RFC 6901 "-" token
        model = A2uiJsonPointer.set(model, "/items/-", mapOf("title" to "세 번째", "score" to 30))
        assertEquals("세 번째", A2uiJsonPointer.resolve(model, "/items/2/title"))

        // Delete item at index 0
        model = A2uiJsonPointer.delete(model, "/items/0")
        assertEquals("두 번째", A2uiJsonPointer.resolve(model, "/items/0/title"))
    }

    // =========================================================================
    // 4. BoardToA2uiBridge: Hanson BoardEventScript -> Canonical A2UI v1.0 / v0.9
    // =========================================================================

    @Test
    fun testBoardToA2uiBridge_convertsBoardEventScriptToValidA2uiSurface() {
        val rawAnswer = """
            SPEAK: 오늘 성수동 날씨와 핵심 포인트 두 가지를 정리했어요.
            BOARD:
            성수동 오늘 브리핑
            • 기온: 21°C
            • 미세먼지: 좋음
            1. 산책 추천: 오후 4시 이후 서울숲 산책이 가장 쾌적합니다.
            2. 우산 여부: 강수 확률이 낮아 우산이 필요 없어요.
        """.trimIndent()

        val citations = listOf(
            StructuredUiItem(
                badge = "01",
                title = "기상청 날씨누리",
                subtitle = "weather.go.kr",
                actionUrlOrCmd = "https://www.weather.go.kr/"
            )
        )
        val script = BoardEventStreamCompiler.compile(
            rawText = rawAnswer,
            citations = citations,
            turnIndex = 3,
            eyebrowOverride = "실시간 브리핑"
        )

        val messages = BoardToA2uiBridge.fromBoardScript(script, protocolVersion = A2uiProtocolConstants.VERSION_1_0)
        assertEquals(3, messages.size)
        assertTrue(messages[0].has("createSurface"))
        assertTrue(messages[1].has("updateComponents"))
        assertTrue(messages[2].has("updateDataModel"))
        assertEquals("1.0", messages[0].getString("version"))

        val publishedId = BoardToA2uiBridge.publishBoardScript(script)
        assertTrue(publishedId.startsWith("board_"))
        val surface = A2uiSurfaceRegistry.getSurface(publishedId)
        assertNotNull(surface)
        assertTrue(surface!!.hasRootComponent)
        assertEquals("Column", surface.components["root"]?.type)

        val headline = A2uiJsonPointer.resolve(surface.dataModel, "/board/headline")?.toString().orEmpty()
        assertTrue("Expected compiled headline in A2UI dataModel, got '$headline'", headline.contains("성수동"))

        val spoken = A2uiJsonPointer.resolve(surface.dataModel, "/board/spokenText")?.toString().orEmpty()
        assertTrue(spoken.contains("성수동"))

        // Trigger the generated citation button and verify URL resolution from dataModel
        val citBtn = surface.components["citation_btn_0"]
        assertNotNull("Expected generated citation Button component", citBtn)
        val citAction = A2uiDataBindingResolver.parseActionSpec(citBtn!!.properties["action"])!!
        val dispatched = A2uiSurfaceRegistry.dispatchUserAction(publishedId, "citation_btn_0", citAction)!!
        assertEquals("open_citation", dispatched.name)
        assertEquals("https://www.weather.go.kr/", dispatched.context["url"])
    }

    // =========================================================================
    // 5. DynamicUiEventBus & ExternalSseStreamClient Integration
    // =========================================================================

    @Test
    fun testDynamicUiEventBus_routesA2uiMimeAndEventIntoSurfaceRegistry() {
        val createMsg = JSONObject()
            .put("version", "1.0")
            .put(
                "createSurface",
                JSONObject()
                    .put("surfaceId", "bus_surface_1")
                    .put("catalogId", A2uiProtocolConstants.BASIC_CATALOG_V1_0)
                    .put(
                        "components",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "root")
                                .put("component", "Text")
                                .put("text", "버스로 수신된 A2UI 카드")
                        )
                    )
            )

        assertTrue(DynamicUiEventBus.ingestExternalJsonEvent(A2uiProtocolConstants.MIME_TYPE, createMsg))
        val surface = A2uiSurfaceRegistry.getSurface("bus_surface_1")
        assertNotNull(surface)
        assertEquals(
            "버스로 수신된 A2UI 카드",
            A2uiDataBindingResolver.resolveString(surface!!.components["root"]!!.properties["text"], surface.dataModel)
        )

        // Dispatching via ExternalSseStreamClient with event: a2ui updates data model cleanly
        val updateFrame = ParsedSseFrame(
            id = "99",
            event = "a2ui",
            data = """{"version":"1.0","updateDataModel":{"surfaceId":"bus_surface_1","path":"/status","value":"LIVE"}}"""
        )
        ExternalSseStreamClient.dispatchParsedFrame(updateFrame)
        assertEquals("LIVE", A2uiJsonPointer.resolve(A2uiSurfaceRegistry.getSurface("bus_surface_1")!!.dataModel, "/status"))

        // Surfaces summary JSON exposes clean metadata for GET /a2ui/surfaces
        val summary = A2uiSurfaceRegistry.surfacesSummaryJson(includeComponents = true)
        assertEquals("ok", summary.getString("status"))
        assertEquals("A2UI", summary.getString("protocol"))
        assertEquals(1, summary.getInt("surfaceCount"))
    }

    // =========================================================================
    // 6. Monochrome Achromatic & Route Security Contract Verification
    // =========================================================================

    @Test
    fun testA2uiIntegrationContracts_achromaticRendererAndAuthenticatedBridgeRoutes() {
        val repoRoot = resolveRepoRoot()

        val rendererSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/ui/components/A2uiComposeRenderer.kt"
        ).readText()
        assertTrue("Renderer must use ObsidianColors tokens", rendererSrc.contains("ObsidianColors."))
        for (chromatic in listOf("Color.Red", "Color.Green", "Color.Blue", "Color.Yellow", "Color.Cyan", "Color.Magenta")) {
            assertFalse("A2uiComposeRenderer must NEVER use chromatic constant $chromatic", rendererSrc.contains(chromatic))
        }

        val serverSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/accessibility/ArtemisCommandServer.kt"
        ).readText()
        val authIdx = serverSrc.indexOf("val mustEnforceToken =")
        val msgRouteIdx = serverSrc.indexOf("\"/a2ui/messages\"")
        val surfRouteIdx = serverSrc.indexOf("\"/a2ui/surfaces\"")
        assertTrue("ArtemisCommandServer must enforce token auth before /a2ui/messages", authIdx in 1 until msgRouteIdx)
        assertTrue("ArtemisCommandServer must enforce token auth before /a2ui/surfaces", authIdx in 1 until surfRouteIdx)

        val cmdScreenSrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/ui/screens/CommandCenterScreen.kt"
        ).readText()
        assertTrue("CommandCenterScreen must mount A2uiSurfaceCard", cmdScreenSrc.contains("A2uiSurfaceCard("))

        val overlaySrc = File(
            repoRoot,
            "app/src/main/java/com/hanson/mobileuse/service/DynamicCanvasOverlayService.kt"
        ).readText()
        assertTrue("DynamicCanvasOverlayService must mount A2uiSurfaceCard", overlaySrc.contains("A2uiSurfaceCard("))
    }
}
