import XCTest
@testable import HansonMobileUseIOS

final class HansonMobileUseIOSTests: XCTestCase {
    func testA2uiV10SingleMessageCreateUpdateAndDeleteSurface() throws {
        var processor = A2uiMessageProcessor()
        let createLine = """
        {"version":"v1.0","createSurface":{"surfaceId":"card_1","catalogId":"https://a2ui.org/specification/v1_0/basic_catalog.json","components":[{"id":"root","component":"Column","children":["title","btn"]},{"id":"title","component":"Text","text":{"@path":"/header/title"},"usageHint":"h2"},{"id":"btn","component":"Button","label":"Confirm","action":{"name":"submit_plan","context":{"title":{"@path":"/header/title"}}}}],"data":{"header":{"title":"Hanson iOS Ready"}}}}
        """
        let sid = try processor.ingestJsonLine(createLine)
        XCTAssertEqual(sid, "card_1")
        let surface = try XCTUnwrap(processor.surfaces["card_1"])
        XCTAssertEqual(surface.components.count, 3)
        XCTAssertEqual(
            A2uiDynamicResolver.resolveString(surface.components["title"]?.properties["text"], dataModel: surface.dataModel),
            "Hanson iOS Ready"
        )

        let updateLine = """
        {"version":"v1.0","updateDataModel":{"surfaceId":"card_1","path":"/header/title","op":"replace","value":"Updated via JSON Pointer"}}
        """
        _ = try processor.ingestJsonLine(updateLine)
        let updated = try XCTUnwrap(processor.surfaces["card_1"])
        XCTAssertEqual(updated.dataModel.resolve(pointer: "/header/title")?.stringValue, "Updated via JSON Pointer")

        let deleteLine = """
        {"version":"v1.0","deleteSurface":{"surfaceId":"card_1"}}
        """
        _ = try processor.ingestJsonLine(deleteLine)
        XCTAssertNil(processor.surfaces["card_1"])
    }

    func testSseParserRfc8895MultiLineAndEndpointSecurity() {
        var parser = HansonSseParser()
        XCTAssertNil(parser.feedLine(": heartbeat"))
        XCTAssertNil(parser.feedLine("id: 42"))
        XCTAssertNil(parser.feedLine("event: a2ui"))
        XCTAssertNil(parser.feedLine("data: {\"line\":1}"))
        XCTAssertNil(parser.feedLine("data: {\"line\":2}"))
        let evt = parser.feedLine("")
        XCTAssertEqual(evt?.id, "42")
        XCTAssertEqual(evt?.event, "a2ui")
        XCTAssertEqual(evt?.data, "{\"line\":1}\n{\"line\":2}")
        XCTAssertEqual(parser.lastEventId, "42")

        XCTAssertTrue(HansonSseEndpointValidator.isAllowed(URL(string: "https://agent.example.com/sse")!))
        XCTAssertTrue(HansonSseEndpointValidator.isAllowed(URL(string: "http://127.0.0.1:18888/sse/board")!))
        XCTAssertFalse(HansonSseEndpointValidator.isAllowed(URL(string: "http://example.com/insecure")!))
    }

    @MainActor
    func testCoDrawingNormalizedUndoRedoAndSpatialSummary() {
        let state = CoDrawingCanvasState()
        state.addElement(CoDrawElement(
            author: .human,
            kind: .spotlightRing,
            points: [NormalizedPoint(nx: 0.42, ny: 0.65)],
            radius: 0.10,
            label: "Target"
        ))
        XCTAssertEqual(state.elements.count, 1)
        XCTAssertTrue(state.canUndo)
        XCTAssertTrue(state.buildSpatialPromptSummary().contains("0.42"))

        state.undo()
        XCTAssertTrue(state.elements.isEmpty)
        XCTAssertTrue(state.canRedo)

        state.redo()
        XCTAssertEqual(state.elements.count, 1)
    }
}
