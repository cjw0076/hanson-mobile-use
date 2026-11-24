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

    func testProactiveSecretaryWireDecodingAndConflictBadgeData() throws {
        let wireJson = """
        {
          "status": "ok",
          "count": 2,
          "unread_rooms": 3,
          "proposals": [
            {
              "id": "prop_kko_01",
              "fingerprint": "fp_kko_01",
              "type": "CALENDAR_EVENT",
              "status": "PENDING",
              "sourceApp": "카카오톡",
              "packageName": "com.kakao.talk",
              "sender": "민수",
              "roomOrTitle": "팀 저녁",
              "titleKo": "강남역 저녁 약속 등록",
              "summaryKo": "금요일 오후 7시 강남역 11번 출구 스타벅스",
              "detailKo": "멀티턴 대화에서 확정된 일정",
              "quotedExcerpt": "오후 7시 강남역 11번 출구 스타벅스로 확정!",
              "confidence": 0.94,
              "hasCalendarConflict": true,
              "conflictSummaryKo": "19:00 주간 점검 회의와 30분 겹침",
              "startTimeMs": 1795687200000,
              "endTimeMs": 1795690800000,
              "location": "강남역 11번 출구 스타벅스",
              "meetingUrl": null,
              "primaryAction": {"key": "approve", "labelKo": "일정 등록", "kind": "CALENDAR_INSERT"},
              "secondaryActions": [
                {"key": "snooze", "labelKo": "1시간 미루기", "kind": "SNOOZE"},
                {"key": "dismiss", "labelKo": "무시", "kind": "DISMISS"}
              ],
              "createdAtMs": 1795600000000,
              "updatedAtMs": 1795600100000
            },
            {
              "id": "prop_fin_02",
              "fingerprint": "fp_fin_02",
              "type": "BILLING_SUBSCRIPTION_WATCH",
              "status": "PENDING",
              "sourceApp": "토스",
              "packageName": "viva.republica.toss",
              "sender": "토스",
              "roomOrTitle": "구독 갱신 알림",
              "titleKo": "클라우드 구독 갱신 확인",
              "summaryKo": "내일 정기 결제 예정 (읽기 전용 안내)",
              "detailKo": "",
              "quotedExcerpt": "내일 정기 결제 예정입니다.",
              "confidence": 0.88,
              "hasCalendarConflict": false,
              "conflictSummaryKo": null,
              "startTimeMs": null,
              "endTimeMs": null,
              "location": null,
              "meetingUrl": null,
              "primaryAction": {"key": "approve", "labelKo": "리마인더 저장", "kind": "NOTE_REMINDER"},
              "secondaryActions": [],
              "createdAtMs": 1795600200000,
              "updatedAtMs": 1795600200000
            }
          ]
        }
        """

        let decoded = try ProactiveSecretaryWireDecoder.decodeProposalsResponse(jsonString: wireJson)
        XCTAssertEqual(decoded.status, "ok")
        XCTAssertEqual(decoded.count, 2)
        XCTAssertEqual(decoded.unreadRooms, 3)
        XCTAssertEqual(decoded.proposals.count, 2)

        let first = decoded.proposals[0]
        XCTAssertEqual(first.type, .calendarEvent)
        XCTAssertEqual(first.status, .pending)
        XCTAssertTrue(first.hasCalendarConflict)
        XCTAssertEqual(first.conflictBadgeText, "CONFLICT · 19:00 주간 점검 회의와 30분 겹침")
        XCTAssertEqual(first.confidencePercent, 94)
        XCTAssertFalse(first.isReadOnlyAdvisory)

        let second = decoded.proposals[1]
        XCTAssertEqual(second.type, .billingSubscriptionWatch)
        XCTAssertTrue(second.isReadOnlyAdvisory)
        XCTAssertNil(second.conflictBadgeText)
    }

    func testProactiveProposalA2uiBuilderAndCalendarConflictChecker() throws {
        let proposal = ProactiveProposalModel(
            id: "prop_meet_7",
            fingerprint: "fp_meet_7",
            type: .meetingLinkPrep,
            status: .pending,
            sourceApp: "Slack",
            packageName: "com.Slack",
            sender: "지민",
            roomOrTitle: "#design-review",
            titleKo: "화상 디자인 리뷰 준비",
            summaryKo: "오후 2시 Google Meet 링크 및 5분 전 알림",
            quotedExcerpt: "2시 링크 https://meet.google.com/abc-defg-hij",
            confidence: 0.91,
            hasCalendarConflict: true,
            conflictSummaryKo: "고객 온보딩 콜과 30분 겹침",
            startTimeMs: 1795669200000,
            endTimeMs: 1795672800000,
            meetingUrl: "https://meet.google.com/abc-defg-hij",
            primaryAction: ProactiveProposalAction(key: "approve", labelKo: "알림·일정 등록", kind: "CALENDAR_INSERT"),
            secondaryActions: [
                ProactiveProposalAction(key: "dismiss", labelKo: "닫기", kind: "DISMISS")
            ]
        )

        let jsonl = ProactiveProposalA2uiBuilder.buildV10Jsonl(for: proposal)
        XCTAssertFalse(jsonl.isEmpty)

        var processor = A2uiMessageProcessor()
        let sid = try processor.ingestJsonLine(jsonl)
        XCTAssertEqual(sid, "secretary_prop_meet_7")
        let surface = try XCTUnwrap(processor.surfaces["secretary_prop_meet_7"])
        XCTAssertNotNil(surface.components["conflict_card"])
        XCTAssertNotNil(surface.components["btn_primary"])
        XCTAssertEqual(
            A2uiDynamicResolver.resolveString(surface.components["title_text"]?.properties["text"], dataModel: surface.dataModel),
            "화상 디자인 리뷰 준비"
        )
        XCTAssertEqual(
            A2uiDynamicResolver.resolveString(surface.components["conflict_text"]?.properties["text"], dataModel: surface.dataModel),
            "CONFLICT · 고객 온보딩 콜과 30분 겹침"
        )
        XCTAssertEqual(
            surface.components["btn_primary"]?.properties["action"]?["name"]?.stringValue,
            "secretary.approve"
        )

        // Verify offline EventKit-free conflict overlap checker
        let existing = [
            ProactiveCalendarEventSnapshot(
                id: "ev1",
                title: "고객 온보딩 콜",
                startTimeMs: 1795671000000,
                endTimeMs: 1795674600000
            ),
            ProactiveCalendarEventSnapshot(
                id: "ev2",
                title: "저녁 운동",
                startTimeMs: 1795687200000,
                endTimeMs: 1795690800000
            ),
        ]
        let report = ProactiveCalendarConflictChecker.findConflicts(
            startTimeMs: 1795669200000,
            endTimeMs: 1795672800000,
            existingEvents: existing
        )
        XCTAssertTrue(report.hasConflict)
        XCTAssertEqual(report.conflictingEvents.count, 1)
        XCTAssertEqual(report.conflictingEvents.first?.id, "ev1")
        XCTAssertTrue(report.summaryKo?.contains("30분 겹침") == true)
    }

    func testProactiveSecretaryWhiteSpaceTypesEvidenceTierAndTopicChannel() throws {
        let wireJson = """
        {
          "status": "ok",
          "count": 6,
          "unread_rooms": 4,
          "proposals": [
            {
              "id": "ws_1", "type": "SELF_COMMITMENT_DEBT", "status": "PENDING",
              "sourceApp": "카카오톡", "packageName": "com.kakao.talk", "sender": "나", "roomOrTitle": "팀장님",
              "titleKo": "내가 한 약속: 오늘 밤까지 초안 발송", "summaryKo": "직접 남긴 약속을 추적합니다",
              "confidence": 0.92, "approvalTier": "BATCH_DIGEST", "topicChannel": "WORK",
              "evidence": {
                "sourceSummaryKo": "카카오톡 본인 답장", "ruleSummaryKo": "로컬 $0 1인칭 약속 규칙",
                "reversibility": "REVERSIBLE", "reversibilityLabelKo": "1탭 원상복구 가능",
                "confidenceBasisKo": "시간+약속 동사 일치", "cloudUploadCount": 0
              },
              "primaryAction": {"key": "commitment.mark_done", "labelKo": "완료", "kind": "SAVE_NOTE"}
            },
            {
              "id": "ws_2", "type": "SUBSCRIPTION_TRIAL_DEFENSE", "status": "PENDING",
              "sourceApp": "Gmail", "packageName": "com.google.android.gm", "sender": "Billing", "roomOrTitle": "무료체험",
              "titleKo": "무료체험 D-2 해지 알림", "summaryKo": "자동결제 전환 전 확인 (읽기 전용)",
              "confidence": 0.95, "readOnlyAdvisory": true, "approvalTier": "HARD_MANUAL_GATE", "topicChannel": "SHOPPING",
              "primaryAction": {"key": "defense.schedule_d2_alarm", "labelKo": "D-2 알림 등록", "kind": "SET_ALARM"}
            },
            {
              "id": "ws_3", "type": "FOCUS_REENTRY_DIGEST", "status": "PENDING",
              "sourceApp": "Slack", "packageName": "com.Slack", "sender": "Focus Shield", "roomOrTitle": "#general",
              "titleKo": "집중 복귀 3줄 브리프", "summaryKo": "확정 1건 · 확인필요 1건 · 일반 잡담 12건",
              "confidence": 0.90, "approvalTier": "BATCH_DIGEST", "topicChannel": "WORK",
              "primaryAction": {"key": "shield.ack_all", "labelKo": "모두 확인", "kind": "DISMISS"}
            },
            {
              "id": "ws_4", "type": "SCAM_PRE_SHIELD", "status": "PENDING",
              "sourceApp": "삼성 메시지", "packageName": "com.samsung.android.messaging", "sender": "국제발신", "roomOrTitle": "SMS",
              "titleKo": "스미싱 의심 링크 사전 차단", "summaryKo": "링크 클릭 전 발신자에게 직접 확인하세요",
              "confidence": 0.99, "approvalTier": "HARD_MANUAL_GATE", "topicChannel": "ALL",
              "primaryAction": {"key": "shield.bohonara_guide", "labelKo": "보호나라 안내", "kind": "OPEN_LINK"}
            },
            {
              "id": "ws_5", "type": "PLAIN_NOTICE_TRANSLATION", "status": "PENDING",
              "sourceApp": "삼성 메시지", "packageName": "com.samsung.android.messaging", "sender": "건강보험", "roomOrTitle": "안내문",
              "titleKo": "안내문 1줄 통역", "summaryKo": "금요일까지 공복 검진 예약 확인 필요",
              "confidence": 0.89, "approvalTier": "AUTO_SAFE_LOCAL", "topicChannel": "FAMILY",
              "primaryAction": {"key": "approve", "labelKo": "일정 메모", "kind": "SAVE_NOTE"}
            },
            {
              "id": "ws_6", "type": "RELATIONSHIP_CARE_NUDGE", "status": "PENDING",
              "sourceApp": "카카오톡", "packageName": "com.kakao.talk", "sender": "어머니", "roomOrTitle": "어머니",
              "titleKo": "안부 케어: 마지막 연락 9일 전", "summaryKo": "자동 발송 없음 — 대화방만 열어드립니다",
              "confidence": 0.87, "approvalTier": "HARD_MANUAL_GATE", "topicChannel": "FAMILY",
              "primaryAction": {"key": "care.open_chat", "labelKo": "대화방 열기", "kind": "OPEN_LINK"}
            }
          ]
        }
        """

        let decoded = try ProactiveSecretaryWireDecoder.decodeProposalsResponse(jsonString: wireJson)
        XCTAssertEqual(decoded.proposals.count, 6)
        XCTAssertEqual(decoded.proposals.map(\.type), [
            .selfCommitmentDebt,
            .subscriptionTrialDefense,
            .focusReentryDigest,
            .scamPreShield,
            .plainNoticeTranslation,
            .relationshipCareNudge,
        ])
        XCTAssertEqual(decoded.proposals[0].approvalTier, "BATCH_DIGEST")
        XCTAssertEqual(decoded.proposals[0].topicChannel, "WORK")
        XCTAssertEqual(decoded.proposals[0].evidence?.reversibility, .reversible)
        XCTAssertEqual(decoded.proposals[0].evidence?.cloudUploadCount, 0)
        XCTAssertTrue(decoded.proposals[1].isReadOnlyAdvisory)
        XCTAssertTrue(decoded.proposals[3].isScamShieldWarning)

        // Verify A2UI v1.0 evidence card renders cleanly through A2uiMessageProcessor
        let jsonl = ProactiveProposalA2uiBuilder.buildV10Jsonl(for: decoded.proposals[0])
        var processor = A2uiMessageProcessor()
        let sid = try processor.ingestJsonLine(jsonl)
        XCTAssertEqual(sid, "secretary_ws_1")
        let surface = try XCTUnwrap(processor.surfaces["secretary_ws_1"])
        XCTAssertNotNil(surface.components["evidence_card"])
        let evResolved = A2uiDynamicResolver.resolveString(
            surface.components["evidence_text"]?.properties["text"],
            dataModel: surface.dataModel
        )
        XCTAssertTrue(evResolved.contains("왜 제안했나요?"))
        XCTAssertTrue(evResolved.contains("클라우드 전송 0건"))
    }
}
