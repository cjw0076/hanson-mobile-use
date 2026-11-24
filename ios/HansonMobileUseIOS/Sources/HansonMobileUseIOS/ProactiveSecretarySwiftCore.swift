import Foundation
import SwiftUI

#if canImport(EventKit)
import EventKit
#endif

// MARK: - Palette Alias (Strict 0%-Saturation Paper / Carbon Monochrome)

/// Convenience alias for Hanson's 0%-saturation `HansonAchromaticPalette`.
public typealias HansonPalette = HansonAchromaticPalette

// MARK: - Proactive Secretary Wire Contract Models (:18888/secretary/*)

public enum ProactiveProposalType: String, Codable, CaseIterable, Sendable {
    case calendarEvent = "CALENDAR_EVENT"
    case deadlineTask = "DEADLINE_TASK"
    case unansweredFollowup = "UNANSWERED_FOLLOWUP"
    case meetingLinkPrep = "MEETING_LINK_PREP"
    case reservationCommute = "RESERVATION_COMMUTE"
    case deliveryTracking = "DELIVERY_TRACKING"
    case billingSubscriptionWatch = "BILLING_SUBSCRIPTION_WATCH"
    case upcomingPrepBrief = "UPCOMING_PREP_BRIEF"
    // Track E: 6 Unbuilt White-Space Life-Care Proposal Types
    case selfCommitmentDebt = "SELF_COMMITMENT_DEBT"
    case subscriptionTrialDefense = "SUBSCRIPTION_TRIAL_DEFENSE"
    case focusReentryDigest = "FOCUS_REENTRY_DIGEST"
    case scamPreShield = "SCAM_PRE_SHIELD"
    case plainNoticeTranslation = "PLAIN_NOTICE_TRANSLATION"
    case relationshipCareNudge = "RELATIONSHIP_CARE_NUDGE"
    case unknown = "UNKNOWN"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = (try? container.decode(String.self)) ?? ""
        self = ProactiveProposalType(rawValue: raw) ?? .unknown
    }

    public var badgeLabelKo: String {
        switch self {
        case .calendarEvent: return "일정 등록"
        case .deadlineTask: return "마감 · 할 일"
        case .unansweredFollowup: return "미답장 회신"
        case .meetingLinkPrep: return "화상회의 준비"
        case .reservationCommute: return "예약 · 출발"
        case .deliveryTracking: return "배송 · 픽업"
        case .billingSubscriptionWatch: return "결제 · 구독 안내"
        case .upcomingPrepBrief: return "브리핑 · 충돌"
        case .selfCommitmentDebt: return "내가 한 약속"
        case .subscriptionTrialDefense: return "구독 · 반품 방어"
        case .focusReentryDigest: return "집중 복귀 브리프"
        case .scamPreShield: return "스미싱 사전 방어"
        case .plainNoticeTranslation: return "안내문 1줄 통역"
        case .relationshipCareNudge: return "안부 케어"
        case .unknown: return "비서 제안"
        }
    }

    public var symbolName: String {
        switch self {
        case .calendarEvent: return "calendar.badge.plus"
        case .deadlineTask: return "checklist"
        case .unansweredFollowup: return "bubble.left.and.exclamationmark.bubble.right"
        case .meetingLinkPrep: return "video"
        case .reservationCommute: return "map"
        case .deliveryTracking: return "shippingbox"
        case .billingSubscriptionWatch: return "lock.shield"
        case .upcomingPrepBrief: return "clock.badge.exclamationmark"
        case .selfCommitmentDebt: return "checkmark.seal"
        case .subscriptionTrialDefense: return "shield.lefthalf.filled"
        case .focusReentryDigest: return "bell.slash"
        case .scamPreShield: return "exclamationmark.shield"
        case .plainNoticeTranslation: return "doc.text.magnifyingglass"
        case .relationshipCareNudge: return "person.2"
        case .unknown: return "sparkles"
        }
    }
}

public enum ProactiveReversibilityClass: String, Codable, CaseIterable, Sendable {
    case reversible = "REVERSIBLE"
    case compensatable = "COMPENSATABLE"
    case irreversibleExternal = "IRREVERSIBLE_EXTERNAL"
    case unknown = "UNKNOWN"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = (try? container.decode(String.self)) ?? ""
        self = ProactiveReversibilityClass(rawValue: raw) ?? .unknown
    }
}

/// Explainable "왜 제안했나요?" provenance & reversibility card matching Android `ProposalEvidenceCard`.
public struct ProactiveProposalEvidenceCard: Codable, Equatable, Hashable, Sendable {
    public let sourceSummaryKo: String
    public let ruleSummaryKo: String
    public let reversibility: ProactiveReversibilityClass
    public let reversibilityLabelKo: String
    public let confidenceBasisKo: String
    public let cloudUploadCount: Int

    public init(
        sourceSummaryKo: String,
        ruleSummaryKo: String,
        reversibility: ProactiveReversibilityClass = .reversible,
        reversibilityLabelKo: String = "1탭 원상복구 가능",
        confidenceBasisKo: String = "",
        cloudUploadCount: Int = 0
    ) {
        self.sourceSummaryKo = sourceSummaryKo
        self.ruleSummaryKo = ruleSummaryKo
        self.reversibility = reversibility
        self.reversibilityLabelKo = reversibilityLabelKo
        self.confidenceBasisKo = confidenceBasisKo
        self.cloudUploadCount = cloudUploadCount
    }

    private enum CodingKeys: String, CodingKey {
        case sourceSummaryKo, ruleSummaryKo, reversibility, reversibilityLabelKo, confidenceBasisKo, cloudUploadCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.sourceSummaryKo = (try? c.decode(String.self, forKey: .sourceSummaryKo)) ?? ""
        self.ruleSummaryKo = (try? c.decode(String.self, forKey: .ruleSummaryKo)) ?? ""
        self.reversibility = (try? c.decode(ProactiveReversibilityClass.self, forKey: .reversibility)) ?? .unknown
        self.reversibilityLabelKo = (try? c.decode(String.self, forKey: .reversibilityLabelKo)) ?? ""
        self.confidenceBasisKo = (try? c.decode(String.self, forKey: .confidenceBasisKo)) ?? ""
        self.cloudUploadCount = (try? c.decode(Int.self, forKey: .cloudUploadCount)) ?? 0
    }
}

public enum ProactiveProposalStatus: String, Codable, CaseIterable, Sendable {
    case pending = "PENDING"
    case approved = "APPROVED"
    case dismissed = "DISMISSED"
    case snoozed = "SNOOZED"
    case executed = "EXECUTED"
    case expired = "EXPIRED"
    case unknown = "UNKNOWN"

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = (try? container.decode(String.self)) ?? ""
        self = ProactiveProposalStatus(rawValue: raw) ?? .unknown
    }
}

public struct ProactiveProposalAction: Codable, Equatable, Hashable, Sendable {
    public let key: String
    public let labelKo: String
    public let kind: String

    public init(key: String, labelKo: String, kind: String) {
        self.key = key
        self.labelKo = labelKo
        self.kind = kind
    }
}

/// Canonical Proactive Chief-of-Staff proposal matching Hanson `:18888/secretary/*`.
public struct ProactiveProposalModel: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public let fingerprint: String
    public let type: ProactiveProposalType
    public var status: ProactiveProposalStatus
    public let sourceApp: String
    public let packageName: String
    public let sender: String
    public let roomOrTitle: String
    public let titleKo: String
    public let summaryKo: String
    public let detailKo: String
    public let quotedExcerpt: String
    public let confidence: Double
    public let hasCalendarConflict: Bool
    public let conflictSummaryKo: String?
    public let startTimeMs: Int64?
    public let endTimeMs: Int64?
    public let location: String?
    public let meetingUrl: String?
    public let primaryAction: ProactiveProposalAction
    public let secondaryActions: [ProactiveProposalAction]
    public let readOnlyAdvisory: Bool
    public let evidence: ProactiveProposalEvidenceCard?
    public let approvalTier: String?
    public let topicChannel: String?
    public let createdAtMs: Int64
    public let updatedAtMs: Int64

    public init(
        id: String,
        fingerprint: String,
        type: ProactiveProposalType,
        status: ProactiveProposalStatus = .pending,
        sourceApp: String,
        packageName: String,
        sender: String,
        roomOrTitle: String,
        titleKo: String,
        summaryKo: String,
        detailKo: String = "",
        quotedExcerpt: String = "",
        confidence: Double = 0.85,
        hasCalendarConflict: Bool = false,
        conflictSummaryKo: String? = nil,
        startTimeMs: Int64? = nil,
        endTimeMs: Int64? = nil,
        location: String? = nil,
        meetingUrl: String? = nil,
        primaryAction: ProactiveProposalAction,
        secondaryActions: [ProactiveProposalAction] = [],
        readOnlyAdvisory: Bool = false,
        evidence: ProactiveProposalEvidenceCard? = nil,
        approvalTier: String? = nil,
        topicChannel: String? = nil,
        createdAtMs: Int64 = 0,
        updatedAtMs: Int64 = 0
    ) {
        self.id = id
        self.fingerprint = fingerprint
        self.type = type
        self.status = status
        self.sourceApp = sourceApp
        self.packageName = packageName
        self.sender = sender
        self.roomOrTitle = roomOrTitle
        self.titleKo = titleKo
        self.summaryKo = summaryKo
        self.detailKo = detailKo
        self.quotedExcerpt = quotedExcerpt
        self.confidence = confidence
        self.hasCalendarConflict = hasCalendarConflict
        self.conflictSummaryKo = conflictSummaryKo
        self.startTimeMs = startTimeMs
        self.endTimeMs = endTimeMs
        self.location = location
        self.meetingUrl = meetingUrl
        self.primaryAction = primaryAction
        self.secondaryActions = secondaryActions
        self.readOnlyAdvisory = readOnlyAdvisory
        self.evidence = evidence
        self.approvalTier = approvalTier
        self.topicChannel = topicChannel
        self.createdAtMs = createdAtMs
        self.updatedAtMs = updatedAtMs
    }

    private enum CodingKeys: String, CodingKey {
        case id, fingerprint, type, status, sourceApp, packageName, sender, roomOrTitle
        case titleKo, summaryKo, detailKo, quotedExcerpt, confidence
        case hasCalendarConflict, conflictSummaryKo, startTimeMs, endTimeMs
        case location, meetingUrl, primaryAction, secondaryActions
        case readOnlyAdvisory, evidence, approvalTier, topicChannel
        case createdAtMs, updatedAtMs
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id)
        self.fingerprint = (try? c.decode(String.self, forKey: .fingerprint)) ?? id
        self.type = (try? c.decode(ProactiveProposalType.self, forKey: .type)) ?? .unknown
        self.status = (try? c.decode(ProactiveProposalStatus.self, forKey: .status)) ?? .pending
        self.sourceApp = (try? c.decode(String.self, forKey: .sourceApp)) ?? ""
        self.packageName = (try? c.decode(String.self, forKey: .packageName)) ?? ""
        self.sender = (try? c.decode(String.self, forKey: .sender)) ?? ""
        self.roomOrTitle = (try? c.decode(String.self, forKey: .roomOrTitle)) ?? ""
        self.titleKo = try c.decode(String.self, forKey: .titleKo)
        self.summaryKo = (try? c.decode(String.self, forKey: .summaryKo)) ?? ""
        self.detailKo = (try? c.decode(String.self, forKey: .detailKo)) ?? ""
        self.quotedExcerpt = (try? c.decode(String.self, forKey: .quotedExcerpt)) ?? ""
        self.confidence = (try? c.decode(Double.self, forKey: .confidence)) ?? 0.0
        self.hasCalendarConflict = (try? c.decode(Bool.self, forKey: .hasCalendarConflict)) ?? false
        self.conflictSummaryKo = try? c.decodeIfPresent(String.self, forKey: .conflictSummaryKo)
        self.startTimeMs = try? c.decodeIfPresent(Int64.self, forKey: .startTimeMs)
        self.endTimeMs = try? c.decodeIfPresent(Int64.self, forKey: .endTimeMs)
        self.location = try? c.decodeIfPresent(String.self, forKey: .location)
        self.meetingUrl = try? c.decodeIfPresent(String.self, forKey: .meetingUrl)
        self.primaryAction = (try? c.decode(ProactiveProposalAction.self, forKey: .primaryAction))
            ?? ProactiveProposalAction(key: "approve", labelKo: "확인", kind: "APPROVE")
        self.secondaryActions = (try? c.decode([ProactiveProposalAction].self, forKey: .secondaryActions)) ?? []
        self.readOnlyAdvisory = (try? c.decode(Bool.self, forKey: .readOnlyAdvisory)) ?? false
        self.evidence = try? c.decodeIfPresent(ProactiveProposalEvidenceCard.self, forKey: .evidence)
        self.approvalTier = try? c.decodeIfPresent(String.self, forKey: .approvalTier)
        self.topicChannel = try? c.decodeIfPresent(String.self, forKey: .topicChannel)
        self.createdAtMs = (try? c.decode(Int64.self, forKey: .createdAtMs)) ?? 0
        self.updatedAtMs = (try? c.decode(Int64.self, forKey: .updatedAtMs)) ?? 0
    }

    /// True for banking/billing/trial-defense notices where automation is strictly read-only advisory.
    public var isReadOnlyAdvisory: Bool {
        readOnlyAdvisory || type == .billingSubscriptionWatch || type == .subscriptionTrialDefense
    }

    /// True for high-priority zero-network smishing/impersonation pre-tap shield warnings.
    public var isScamShieldWarning: Bool {
        type == .scamPreShield
    }

    /// Monochrome badge label surfaced when the proposal overlaps an existing calendar event.
    public var conflictBadgeText: String? {
        guard hasCalendarConflict else { return nil }
        let trimmed = (conflictSummaryKo ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "CONFLICT · 일정 충돌" : "CONFLICT · \(trimmed)"
    }

    public var confidencePercent: Int {
        Int((min(max(confidence, 0.0), 1.0) * 100.0).rounded())
    }
}

public struct ProactiveProposalsResponse: Codable, Equatable, Sendable {
    public let status: String
    public let count: Int
    public let unreadRooms: Int
    public let proposals: [ProactiveProposalModel]

    private enum CodingKeys: String, CodingKey {
        case status
        case count
        case unreadRooms = "unread_rooms"
        case proposals
    }

    public init(status: String, count: Int, unreadRooms: Int, proposals: [ProactiveProposalModel]) {
        self.status = status
        self.count = count
        self.unreadRooms = unreadRooms
        self.proposals = proposals
    }
}

public struct ProactiveScanResponse: Codable, Equatable, Sendable {
    public let status: String
    public let newProposals: Int
    public let totalPending: Int
    public let proposals: [ProactiveProposalModel]

    private enum CodingKeys: String, CodingKey {
        case status
        case newProposals = "new_proposals"
        case totalPending = "total_pending"
        case proposals
    }
}

public struct ProactiveActResponse: Codable, Equatable, Sendable {
    public let status: String
    public let proposalId: String
    public let action: String
    public let succeeded: Bool
    public let message: String
    public let newStatus: String

    private enum CodingKeys: String, CodingKey {
        case status
        case proposalId = "proposal_id"
        case action
        case succeeded
        case message
        case newStatus = "new_status"
    }
}

public enum ProactiveSecretaryWireDecoder {
    public static func decodeProposalsResponse(from data: Data) throws -> ProactiveProposalsResponse {
        try JSONDecoder().decode(ProactiveProposalsResponse.self, from: data)
    }

    public static func decodeProposalsResponse(jsonString: String) throws -> ProactiveProposalsResponse {
        guard let data = jsonString.data(using: .utf8) else {
            throw DecodingError.dataCorrupted(
                .init(codingPath: [], debugDescription: "Invalid UTF-8 string for ProactiveProposalsResponse")
            )
        }
        return try decodeProposalsResponse(from: data)
    }

    public static func decodeProposal(from data: Data) throws -> ProactiveProposalModel {
        try JSONDecoder().decode(ProactiveProposalModel.self, from: data)
    }

    public static func decodeActResponse(from data: Data) throws -> ProactiveActResponse {
        try JSONDecoder().decode(ProactiveActResponse.self, from: data)
    }
}

// MARK: - A2UI v1.0 JSONL Surface Builder (Rendered by A2uiSwiftUIRenderer)

public enum ProactiveProposalA2uiBuilder {
    public static let basicCatalogId = "https://a2ui.org/specification/v1_0/basic_catalog.json"

    /// Build canonical A2UI v1.0 JSONL for a single proposal so `A2uiMessageProcessor`
    /// and `A2uiSurfaceView` render it natively in 0%-saturation Paper/Carbon monochrome.
    public static func buildV10Jsonl(for proposal: ProactiveProposalModel) -> String {
        let surfaceId = "secretary_\(proposal.id)"
        var rootChildren: [A2uiJsonValue] = [
            .string("meta_row"),
            .string("title_text"),
            .string("summary_text"),
        ]

        var components: [A2uiJsonValue] = [
            .object([
                "id": .string("meta_row"),
                "component": .string("Row"),
                "children": .array([.string("type_icon"), .string("meta_caption")]),
            ]),
            .object([
                "id": .string("type_icon"),
                "component": .string("Icon"),
                "name": .string(proposal.hasCalendarConflict ? "warning" : (proposal.isReadOnlyAdvisory ? "lock" : "sparkles")),
            ]),
            .object([
                "id": .string("meta_caption"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/metaLine")]),
                "usageHint": .string("caption"),
            ]),
            .object([
                "id": .string("title_text"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/titleKo")]),
                "usageHint": .string("h3"),
            ]),
            .object([
                "id": .string("summary_text"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/summaryKo")]),
                "usageHint": .string("body"),
            ]),
        ]

        if let conflictBadge = proposal.conflictBadgeText {
            rootChildren.append(.string("conflict_card"))
            components.append(.object([
                "id": .string("conflict_card"),
                "component": .string("Card"),
                "children": .array([.string("conflict_row")]),
            ]))
            components.append(.object([
                "id": .string("conflict_row"),
                "component": .string("Row"),
                "children": .array([.string("conflict_icon"), .string("conflict_text")]),
            ]))
            components.append(.object([
                "id": .string("conflict_icon"),
                "component": .string("Icon"),
                "name": .string("warning"),
            ]))
            components.append(.object([
                "id": .string("conflict_text"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/conflictBadge")]),
                "usageHint": .string("h5"),
            ]))
            _ = conflictBadge
        }

        if !proposal.quotedExcerpt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            rootChildren.append(.string("excerpt_card"))
            components.append(.object([
                "id": .string("excerpt_card"),
                "component": .string("Card"),
                "child": .string("excerpt_text"),
            ]))
            components.append(.object([
                "id": .string("excerpt_text"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/quotedExcerpt")]),
                "usageHint": .string("caption"),
            ]))
        }

        if proposal.isReadOnlyAdvisory {
            rootChildren.append(.string("advisory_note"))
            components.append(.object([
                "id": .string("advisory_note"),
                "component": .string("Text"),
                "text": .string("READ-ONLY ADVISORY · 금융/결제 앱은 자동 조작하지 않고 알림·메모만 제안합니다."),
                "usageHint": .string("caption"),
            ]))
        }

        if let ev = proposal.evidence {
            rootChildren.append(.string("evidence_card"))
            components.append(.object([
                "id": .string("evidence_card"),
                "component": .string("Card"),
                "child": .string("evidence_text"),
            ]))
            components.append(.object([
                "id": .string("evidence_text"),
                "component": .string("Text"),
                "text": .object(["@path": .string("/proposal/evidenceSummary")]),
                "usageHint": .string("caption"),
            ]))
            _ = ev
        }

        rootChildren.append(.string("divider_bottom"))
        components.append(.object([
            "id": .string("divider_bottom"),
            "component": .string("Divider"),
        ]))

        var actionButtonIds: [A2uiJsonValue] = [.string("btn_primary")]
        components.append(makeActionButton(
            id: "btn_primary",
            action: proposal.primaryAction,
            proposalId: proposal.id,
            isPrimary: true
        ))

        for (idx, secAction) in proposal.secondaryActions.prefix(3).enumerated() {
            let btnId = "btn_secondary_\(idx)"
            actionButtonIds.append(.string(btnId))
            components.append(makeActionButton(
                id: btnId,
                action: secAction,
                proposalId: proposal.id,
                isPrimary: false
            ))
        }

        rootChildren.append(.string("actions_row"))
        components.append(.object([
            "id": .string("actions_row"),
            "component": .string("Row"),
            "children": .array(actionButtonIds),
        ]))

        components.insert(.object([
            "id": .string("root"),
            "component": .string("Column"),
            "children": .array(rootChildren),
        ]), at: 0)

        let sourceLineParts = [
            proposal.type.badgeLabelKo,
            proposal.sourceApp,
            proposal.sender.isEmpty ? nil : proposal.sender,
            "\(proposal.confidencePercent)%",
        ].compactMap { $0 }.filter { !$0.isEmpty }

        var proposalData: [String: A2uiJsonValue] = [
            "id": .string(proposal.id),
            "type": .string(proposal.type.rawValue),
            "status": .string(proposal.status.rawValue),
            "metaLine": .string(sourceLineParts.joined(separator: " · ")),
            "titleKo": .string(proposal.titleKo),
            "summaryKo": .string(proposal.summaryKo),
            "quotedExcerpt": .string("\"\(proposal.quotedExcerpt)\""),
            "hasCalendarConflict": .bool(proposal.hasCalendarConflict),
            "isReadOnlyAdvisory": .bool(proposal.isReadOnlyAdvisory),
        ]
        if let badge = proposal.conflictBadgeText {
            proposalData["conflictBadge"] = .string(badge)
        }
        if let loc = proposal.location, !loc.isEmpty {
            proposalData["location"] = .string(loc)
        }
        if let url = proposal.meetingUrl, !url.isEmpty {
            proposalData["meetingUrl"] = .string(url)
        }
        if let tier = proposal.approvalTier, !tier.isEmpty {
            proposalData["approvalTier"] = .string(tier)
        }
        if let chan = proposal.topicChannel, !chan.isEmpty {
            proposalData["topicChannel"] = .string(chan)
        }
        if let ev = proposal.evidence {
            let evLine = "왜 제안했나요? · \(ev.sourceSummaryKo) · \(ev.ruleSummaryKo) · \(ev.reversibilityLabelKo) · 클라우드 전송 \(ev.cloudUploadCount)건"
            proposalData["evidenceSummary"] = .string(evLine)
        }

        let createEnvelope: A2uiJsonValue = .object([
            "version": .string("v1.0"),
            "createSurface": .object([
                "surfaceId": .string(surfaceId),
                "catalogId": .string(basicCatalogId),
                "root": .string("root"),
                "components": .array(components),
                "data": .object(["proposal": .object(proposalData)]),
            ]),
        ])

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(createEnvelope),
              let line = String(data: data, encoding: .utf8) else {
            return ""
        }
        return line
    }

    public static func buildInboxJsonl(for proposals: [ProactiveProposalModel]) -> String {
        proposals.map { buildV10Jsonl(for: $0) }.filter { !$0.isEmpty }.joined(separator: "\n")
    }

    private static func makeActionButton(
        id: String,
        action: ProactiveProposalAction,
        proposalId: String,
        isPrimary: Bool
    ) -> A2uiJsonValue {
        let wireActionName = action.key.hasPrefix("secretary.") ? action.key : "secretary.\(action.key)"
        return .object([
            "id": .string(id),
            "component": .string("Button"),
            "label": .string(action.labelKo),
            "primary": .bool(isPrimary),
            "action": .object([
                "name": .string(wireActionName),
                "context": .object([
                    "proposalId": .object(["@path": .string("/proposal/id")]),
                    "actionKey": .string(action.key),
                    "actionKind": .string(action.kind),
                    "fallbackProposalId": .string(proposalId),
                ]),
            ]),
        ])
    }
}

// MARK: - EventKit Calendar Helper & Honest iOS Sandbox Boundary

/// Lightweight local calendar snapshot used for conflict checks on iOS / macOS.
public struct ProactiveCalendarEventSnapshot: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let startTimeMs: Int64
    public let endTimeMs: Int64
    public let location: String?
    public let isAllDay: Bool

    public init(
        id: String,
        title: String,
        startTimeMs: Int64,
        endTimeMs: Int64,
        location: String? = nil,
        isAllDay: Bool = false
    ) {
        self.id = id
        self.title = title
        self.startTimeMs = startTimeMs
        self.endTimeMs = endTimeMs
        self.location = location
        self.isAllDay = isAllDay
    }
}

public struct ProactiveCalendarConflictReport: Equatable, Sendable {
    public let hasConflict: Bool
    public let conflictingEvents: [ProactiveCalendarEventSnapshot]
    public let summaryKo: String?

    public init(
        hasConflict: Bool,
        conflictingEvents: [ProactiveCalendarEventSnapshot],
        summaryKo: String?
    ) {
        self.hasConflict = hasConflict
        self.conflictingEvents = conflictingEvents
        self.summaryKo = summaryKo
    }
}

/// Pure offline overlap detector shared by EventKit and unit tests.
public enum ProactiveCalendarConflictChecker {
    public static func findConflicts(
        startTimeMs: Int64,
        endTimeMs: Int64,
        existingEvents: [ProactiveCalendarEventSnapshot]
    ) -> ProactiveCalendarConflictReport {
        guard endTimeMs > startTimeMs else {
            return ProactiveCalendarConflictReport(hasConflict: false, conflictingEvents: [], summaryKo: nil)
        }
        let overlaps = existingEvents.filter { ev in
            !ev.isAllDay && ev.startTimeMs < endTimeMs && startTimeMs < ev.endTimeMs
        }
        guard let first = overlaps.first else {
            return ProactiveCalendarConflictReport(hasConflict: false, conflictingEvents: [], summaryKo: nil)
        }
        let overlapMs = min(endTimeMs, first.endTimeMs) - max(startTimeMs, first.startTimeMs)
        let overlapMin = max(1, Int(overlapMs / 60_000))
        let extra = overlaps.count > 1 ? " 외 \(overlaps.count - 1)건" : ""
        let summary = "'\(first.title)' 일정과 \(overlapMin)분 겹침\(extra)"
        return ProactiveCalendarConflictReport(
            hasConflict: true,
            conflictingEvents: overlaps,
            summaryKo: summary
        )
    }
}

/// Protocol abstracting on-device iOS/macOS `EventKit` calendar reads.
///
/// Honest iOS Sandbox Note:
/// Stock iOS strictly forbids third-party apps from reading notifications posted by
/// KakaoTalk, Slack, SMS, or banking apps (there is no iOS equivalent of Android's
/// `NotificationListenerService`). On iOS, Hanson Proactive Secretary therefore
/// combines three Apple-compliant context sources:
/// 1. On-device `EventKit` (`NSCalendarsFullAccessUsageDescription`) for conflict & prep checks.
/// 2. Explicit user handoff via Share Sheet / Siri / `AppIntents`.
/// 3. Mac or Cloud `harness/hanson_secretary_sync.py` pushing sanitized ICS, GitHub,
///    Open-Meteo, and desktop inbox signals to the Companion over HTTPS/loopback A2UI SSE.
public protocol ProactiveCalendarBridge: Sendable {
    var sandboxNotice: String { get }
    func requestCalendarAccess() async -> Bool
    func fetchEvents(fromStartMs: Int64, toEndMs: Int64) async -> [ProactiveCalendarEventSnapshot]
}

public extension ProactiveCalendarBridge {
    func checkConflicts(startTimeMs: Int64, endTimeMs: Int64) async -> ProactiveCalendarConflictReport {
        let events = await fetchEvents(fromStartMs: startTimeMs, toEndMs: endTimeMs)
        return ProactiveCalendarConflictChecker.findConflicts(
            startTimeMs: startTimeMs,
            endTimeMs: endTimeMs,
            existingEvents: events
        )
    }
}

/// Apple `EventKit` calendar bridge (gracefully returns empty snapshots when unauthorized or on non-Apple builds).
public final class ProactiveEventKitCalendarBridge: ProactiveCalendarBridge, @unchecked Sendable {
    public let sandboxNotice: String =
        "iOS forbids third-party NotificationListenerService for KakaoTalk/SMS; " +
        "use EventKit + Share Sheet + Mac/Cloud hanson_secretary_sync.py + A2UI SSE."

    #if canImport(EventKit)
    private let eventStore = EKEventStore()
    #endif

    public init() {}

    public func requestCalendarAccess() async -> Bool {
        #if canImport(EventKit)
        if #available(iOS 17.0, macOS 14.0, *) {
            return (try? await eventStore.requestFullAccessToEvents()) ?? false
        }
        return false
        #else
        return false
        #endif
    }

    public func fetchEvents(fromStartMs: Int64, toEndMs: Int64) async -> [ProactiveCalendarEventSnapshot] {
        #if canImport(EventKit)
        let status = EKEventStore.authorizationStatus(for: .event)
        let authorized: Bool
        if #available(iOS 17.0, macOS 14.0, *) {
            authorized = (status == .fullAccess)
        } else {
            authorized = false
        }
        guard authorized else { return [] }
        let startDate = Date(timeIntervalSince1970: Double(fromStartMs) / 1000.0)
        let endDate = Date(timeIntervalSince1970: Double(toEndMs) / 1000.0)
        let predicate = eventStore.predicateForEvents(withStart: startDate, end: endDate, calendars: nil)
        return eventStore.events(matching: predicate).map { ev in
            ProactiveCalendarEventSnapshot(
                id: ev.eventIdentifier ?? UUID().uuidString,
                title: ev.title ?? "(Untitled)",
                startTimeMs: Int64(ev.startDate.timeIntervalSince1970 * 1000.0),
                endTimeMs: Int64(ev.endDate.timeIntervalSince1970 * 1000.0),
                location: ev.location,
                isAllDay: ev.isAllDay
            )
        }
        #else
        return []
        #endif
    }
}

// MARK: - MainActor Store & 0%-Saturation Monochrome SwiftUI Inbox View

@MainActor
public final class ProactiveSecretaryStore: ObservableObject {
    @Published public var proposals: [ProactiveProposalModel] = []
    @Published public var unreadRooms: Int = 0
    @Published public var showAllStatuses: Bool = false
    @Published public var isSyncing: Bool = false
    @Published public var lastError: String?

    public init(proposals: [ProactiveProposalModel] = []) {
        self.proposals = proposals
    }

    public var visibleProposals: [ProactiveProposalModel] {
        if showAllStatuses { return proposals }
        return proposals.filter { $0.status == .pending }
    }

    public func ingestWirePayload(_ jsonString: String) throws {
        let decoded = try ProactiveSecretaryWireDecoder.decodeProposalsResponse(jsonString: jsonString)
        proposals = decoded.proposals
        unreadRooms = decoded.unreadRooms
        lastError = nil
    }

    public func publishToA2uiStore(_ a2uiStore: A2uiSurfaceStore, proposal: ProactiveProposalModel) {
        let jsonl = ProactiveProposalA2uiBuilder.buildV10Jsonl(for: proposal)
        if !jsonl.isEmpty {
            a2uiStore.ingestJsonLines(jsonl)
        }
    }

    public func applyLocalAction(proposalId: String, actionKey: String) {
        guard let idx = proposals.firstIndex(where: { $0.id == proposalId }) else { return }
        switch actionKey.lowercased() {
        case "approve", "reply", "open_map":
            proposals[idx].status = .approved
        case "dismiss":
            proposals[idx].status = .dismissed
        case "snooze":
            proposals[idx].status = .snoozed
        default:
            break
        }
    }

    public func fetchFromBridge(baseURL: URL, bearerToken: String = "", limit: Int = 20) async {
        guard HansonSseEndpointValidator.isAllowed(baseURL) else {
            lastError = "Blocked insecure bridge URL: only https:// or loopback http://127.0.0.1 is allowed."
            return
        }
        isSyncing = true
        defer { isSyncing = false }

        var comps = URLComponents(url: baseURL.appendingPathComponent("secretary/proposals"), resolvingAgainstBaseURL: false)
        comps?.queryItems = [
            URLQueryItem(name: "status", value: showAllStatuses ? "ALL" : "PENDING"),
            URLQueryItem(name: "limit", value: String(max(1, min(limit, 200)))),
        ]
        guard let reqURL = comps?.url else { return }

        var request = URLRequest(url: reqURL)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !bearerToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            request.setValue(bearerToken, forHTTPHeaderField: "X-Artemis-Token")
        }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                lastError = "Secretary HTTP \(http.statusCode)"
                return
            }
            let decoded = try ProactiveSecretaryWireDecoder.decodeProposalsResponse(from: data)
            proposals = decoded.proposals
            unreadRooms = decoded.unreadRooms
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }
}

/// Pure 0%-saturation Paper / Carbon monochrome Proactive Secretary Inbox view.
public struct ProactiveSecretaryInboxView: View {
    @ObservedObject public var store: ProactiveSecretaryStore
    public let palette: HansonPalette
    public let onAction: (ProactiveProposalModel, ProactiveProposalAction) -> Void
    public let onOpenA2ui: ((ProactiveProposalModel) -> Void)?

    public init(
        store: ProactiveSecretaryStore,
        palette: HansonPalette = HansonPalette(mode: .paper),
        onAction: @escaping (ProactiveProposalModel, ProactiveProposalAction) -> Void,
        onOpenA2ui: ((ProactiveProposalModel) -> Void)? = nil
    ) {
        self.store = store
        self.palette = palette
        self.onAction = onAction
        self.onOpenA2ui = onOpenA2ui
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            headerSection

            if let err = store.lastError, !err.isEmpty {
                Text("SYNC NOTICE · \(err)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surfaceHigh)
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(palette.hairlineStrong, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            if store.visibleProposals.isEmpty {
                emptyStateCard
            } else {
                ForEach(store.visibleProposals) { proposal in
                    proposalCard(proposal)
                }
            }
        }
    }

    private var headerSection: some View {
        HStack(alignment: .center, spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text("PROACTIVE SECRETARY · iOS")
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.textPrimary)
                Text("EventKit · Share Sheet · Mac/Cloud Sync Bridge · \(store.unreadRooms) rooms")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
            }
            Spacer()
            Button(store.showAllStatuses ? "ALL" : "PENDING") {
                store.showAllStatuses.toggle()
            }
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundColor(palette.onInk)
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(palette.ink)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .buttonStyle(.plain)
        }
    }

    private var emptyStateCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("대기 중인 비서 제안이 없습니다")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(palette.textPrimary)
            Text("iOS 샌드박스 정책상 카카오톡·문자 알림을 제3자 앱이 직접 수집할 수 없습니다. EventKit 캘린더, Share Sheet, 또는 Mac/Cloud `hanson_secretary_sync.py` 브리지로 안전하게 제안을 받아보세요.")
                .font(.system(size: 12))
                .foregroundColor(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(palette.hairline, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private func proposalCard(_ proposal: ProactiveProposalModel) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                Image(systemName: proposal.type.symbolName)
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(palette.ink)

                Text(proposal.type.badgeLabelKo)
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.onInk)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(palette.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))

                Text("\(proposal.sourceApp) · \(proposal.sender)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
                    .lineLimit(1)

                Spacer()

                Text("\(proposal.confidencePercent)%")
                    .font(.system(size: 10, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.textMuted)
            }

            if let conflictText = proposal.conflictBadgeText {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(palette.onInk)
                    Text(conflictText)
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundColor(palette.onInk)
                        .lineLimit(2)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.ink)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            }

            if proposal.isScamShieldWarning {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.shield")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(palette.onInk)
                    Text("SCAM PRE-SHIELD · 링크 클릭 전 발신자 직접 확인 필수")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(palette.onInk)
                        .lineLimit(2)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.ink)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            }

            if proposal.isReadOnlyAdvisory {
                HStack(spacing: 6) {
                    Image(systemName: "lock.shield")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(palette.textPrimary)
                    Text("READ-ONLY ADVISORY · 금융 앱 자동 조작 금지")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(palette.textPrimary)
                }
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(palette.surfaceHigh)
                .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(palette.hairlineStrong, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            }

            if let ev = proposal.evidence {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 6) {
                        Text("왜 제안했나요?")
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                            .foregroundColor(palette.textPrimary)
                        if let tier = proposal.approvalTier, !tier.isEmpty {
                            Text(tier)
                                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                .foregroundColor(palette.textSecondary)
                        }
                        if let chan = proposal.topicChannel, !chan.isEmpty {
                            Text("#\(chan)")
                                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                                .foregroundColor(palette.textMuted)
                        }
                    }
                    Text("\(ev.sourceSummaryKo) · \(ev.ruleSummaryKo) · \(ev.reversibilityLabelKo) · 클라우드 전송 \(ev.cloudUploadCount)건")
                        .font(.system(size: 11))
                        .foregroundColor(palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(palette.surfaceHigh)
                .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).stroke(palette.hairline, lineWidth: 1))
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            }

            Text(proposal.titleKo)
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            if !proposal.summaryKo.isEmpty {
                Text(proposal.summaryKo)
                    .font(.system(size: 13))
                    .foregroundColor(palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if !proposal.quotedExcerpt.isEmpty {
                Text("\"\(proposal.quotedExcerpt)\"")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
                    .padding(9)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(palette.surfaceHigh)
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(palette.hairline, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }

            if let loc = proposal.location, !loc.isEmpty {
                HStack(spacing: 5) {
                    Image(systemName: "mappin.and.ellipse")
                        .font(.system(size: 11))
                        .foregroundColor(palette.textMuted)
                    Text(loc)
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundColor(palette.textSecondary)
                        .lineLimit(1)
                }
            }

            Rectangle()
                .fill(palette.hairline)
                .frame(height: 1)

            HStack(spacing: 8) {
                Button {
                    onAction(proposal, proposal.primaryAction)
                } label: {
                    Text(proposal.primaryAction.labelKo)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundColor(palette.onInk)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(palette.ink)
                        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)

                ForEach(proposal.secondaryActions.prefix(2), id: \.key) { secAction in
                    Button {
                        onAction(proposal, secAction)
                    } label: {
                        Text(secAction.labelKo)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(palette.textPrimary)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(palette.surfaceHigh)
                            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(palette.hairlineStrong, lineWidth: 1))
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }

                Spacer()

                if let onOpenA2ui {
                    Button("A2UI") {
                        onOpenA2ui(proposal)
                    }
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.textPrimary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(palette.surfaceHigh)
                    .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(palette.hairlineStrong, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(14)
        .background(palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(palette.hairline, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: palette.ink.opacity(0.08), radius: 0, x: 0, y: 3)
    }
}
