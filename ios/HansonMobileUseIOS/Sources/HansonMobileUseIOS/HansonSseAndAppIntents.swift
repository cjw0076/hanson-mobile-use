import Foundation
import SwiftUI

#if canImport(AppIntents)
import AppIntents
#endif

// MARK: - RFC 8895 SSE Parser & Endpoint Validator

public struct HansonSseEvent: Equatable, Sendable {
    public let id: String?
    public let event: String
    public let data: String
    public let retryMs: Int?

    public init(id: String?, event: String, data: String, retryMs: Int? = nil) {
        self.id = id
        self.event = event
        self.data = data
        self.retryMs = retryMs
    }
}

public enum HansonSseEndpointValidator {
    /// Permit only HTTPS endpoints or explicit loopback HTTP (`127.0.0.1`, `localhost`, `::1`).
    public static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), let host = url.host?.lowercased() else {
            return false
        }
        if scheme == "https" { return true }
        if scheme == "http" {
            return host == "127.0.0.1" || host == "localhost" || host == "::1"
        }
        return false
    }
}

/// Pure RFC 8895 `text/event-stream` incremental line parser with `Last-Event-ID` tracking.
public struct HansonSseParser: Sendable {
    public private(set) var lastEventId: String?
    public private(set) var retryMs: Int?

    private var currentId: String?
    private var currentEvent: String?
    private var dataLines: [String] = []

    public init() {}

    public mutating func feedLine(_ rawLine: String) -> HansonSseEvent? {
        let line = rawLine.hasSuffix("\r") ? String(rawLine.dropLast()) : rawLine
        if line.isEmpty {
            return flushEvent()
        }
        if line.hasPrefix(":") {
            return nil
        }

        let field: String
        let value: String
        if let colon = line.firstIndex(of: ":") {
            field = String(line[..<colon])
            let afterColon = line.index(after: colon)
            let rawVal = String(line[afterColon...])
            value = rawVal.hasPrefix(" ") ? String(rawVal.dropFirst()) : rawVal
        } else {
            field = line
            value = ""
        }

        switch field {
        case "event":
            currentEvent = value
        case "data":
            dataLines.append(value)
        case "id":
            if !value.contains("\0") {
                currentId = value
                lastEventId = value
            }
        case "retry":
            if let ms = Int(value), ms >= 0 {
                retryMs = ms
            }
        default:
            break
        }
        return nil
    }

    public mutating func flushEvent() -> HansonSseEvent? {
        guard !dataLines.isEmpty else {
            currentEvent = nil
            currentId = nil
            return nil
        }
        let evt = HansonSseEvent(
            id: currentId ?? lastEventId,
            event: (currentEvent?.isEmpty == false) ? currentEvent! : "message",
            data: dataLines.joined(separator: "\n"),
            retryMs: retryMs
        )
        dataLines.removeAll()
        currentEvent = nil
        currentId = nil
        return evt
    }
}

// MARK: - Async RFC 8895 SSE Client

@MainActor
public final class HansonSseClient: ObservableObject {
    public enum ConnectionState: String, Sendable {
        case idle = "IDLE"
        case connecting = "CONNECTING"
        case connected = "CONNECTED"
        case error = "ERROR"
    }

    @Published public private(set) var connectionState: ConnectionState = .idle
    @Published public private(set) var lastEventId: String?
    @Published public private(set) var lastError: String?

    private var streamTask: Task<Void, Never>?
    private var parser = HansonSseParser()

    public init() {}

    public func connect(
        url: URL,
        bearerToken: String = "",
        a2uiStore: A2uiSurfaceStore,
        coDrawState: CoDrawingCanvasState
    ) {
        disconnect()
        guard HansonSseEndpointValidator.isAllowed(url) else {
            connectionState = .error
            lastError = "Blocked insecure SSE endpoint: only https:// or loopback http://127.0.0.1 is permitted."
            return
        }

        connectionState = .connecting
        lastError = nil
        let resumeId = lastEventId

        streamTask = Task { [weak self] in
            guard let self else { return }
            var request = URLRequest(url: url)
            request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
            if !bearerToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                request.setValue(bearerToken, forHTTPHeaderField: "X-Artemis-Token")
            }
            if let resumeId, !resumeId.isEmpty {
                request.setValue(resumeId, forHTTPHeaderField: "Last-Event-ID")
            }

            do {
                let (bytes, response) = try await URLSession.shared.bytes(for: request)
                if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
                    self.connectionState = .error
                    self.lastError = "SSE HTTP \(http.statusCode)"
                    return
                }
                self.connectionState = .connected
                for try await line in bytes.lines {
                    if Task.isCancelled { break }
                    if let event = self.parser.feedLine(line) {
                        self.lastEventId = self.parser.lastEventId
                        self.route(event: event, a2uiStore: a2uiStore, coDrawState: coDrawState)
                    }
                }
                if !Task.isCancelled {
                    self.connectionState = .idle
                }
            } catch {
                if !Task.isCancelled {
                    self.connectionState = .error
                    self.lastError = error.localizedDescription
                }
            }
        }
    }

    public func disconnect() {
        streamTask?.cancel()
        streamTask = nil
        connectionState = .idle
    }

    private func route(
        event: HansonSseEvent,
        a2uiStore: A2uiSurfaceStore,
        coDrawState: CoDrawingCanvasState
    ) {
        let name = event.event.lowercased()
        if name == "codraw_command" || name == "codraw" {
            coDrawState.applyRemoteCommandJSON(event.data)
            return
        }
        if name == "a2ui" || name == "application/a2ui+json" || event.data.contains("createSurface") || event.data.contains("updateComponents") || event.data.contains("updateDataModel") || event.data.contains("deleteSurface") {
            a2uiStore.ingestJsonLines(event.data)
        }
    }
}

// MARK: - Honest iOS Platform Capability Model

public enum HansonIosSupportTier: String, Sendable {
    case onDeviceNative = "ON_DEVICE_NATIVE"
    case macOsCompanion = "MACOS_COMPANION"
    case developerUsb = "DEVELOPER_USB_WDA"
    case sandboxForbidden = "IOS_SANDBOX_FORBIDDEN"
}

public struct HansonIosCapability: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let tier: HansonIosSupportTier
    public let detail: String
}

public enum HansonIosCapabilityMatrix {
    public static let all: [HansonIosCapability] = [
        HansonIosCapability(
            id: "a2ui-swiftui",
            title: "Native A2UI v0.9/v1.0 SwiftUI Surfaces",
            tier: .onDeviceNative,
            detail: "Full streaming Basic catalog rendering in 0%-saturation Paper/Carbon inside HansonMobileUseIOS."
        ),
        HansonIosCapability(
            id: "codraw-canvas",
            title: "In-App Human + AI 0..1 Co-Drawing Canvas",
            tier: .onDeviceNative,
            detail: "Real-time SwiftUI Canvas pen, arrow, and spotlight-ring annotations synced over RFC 8895 SSE."
        ),
        HansonIosCapability(
            id: "app-intents",
            title: "Siri, Shortcuts & Action Button (AppIntents)",
            tier: .onDeviceNative,
            detail: "System-sanctioned voice/Shortcut handoff into Hanson agent workflows and A2UI surfaces."
        ),
        HansonIosCapability(
            id: "macos-mirroring",
            title: "Zero-Jailbreak Cross-App iPhone Control",
            tier: .macOsCompanion,
            detail: "macOS 15+ iPhone Mirroring + Apple Vision OCR + CGEvent/SkyLight via `harness/hanson_ios.py`."
        ),
        HansonIosCapability(
            id: "wda-xcuitest",
            title: "WDA / XCUITest Developer Automation",
            tier: .developerUsb,
            detail: "USB developer-mode UI automation for owned devices and CI test labs."
        ),
        HansonIosCapability(
            id: "no-a11y-service",
            title: "Third-Party AccessibilityService Control",
            tier: .sandboxForbidden,
            detail: "Strictly forbidden by iOS sandbox without jailbreak; use macOS iPhone Mirroring instead."
        ),
        HansonIosCapability(
            id: "no-system-overlay",
            title: "Floating Overlay Over Arbitrary Apps",
            tier: .sandboxForbidden,
            detail: "iOS has no Android `SYSTEM_ALERT_WINDOW` equivalent; PiP/Live Activities are limited to media/status."
        ),
    ]
}

// MARK: - iOS AppIntents / Shortcuts Bridge

#if canImport(AppIntents)
@available(iOS 17.0, macOS 14.0, *)
public struct RunHansonAgentIntent: AppIntent {
    public static var title: LocalizedStringResource = "Run Hanson Mobile Agent"
    public static var description = IntentDescription("Dispatch a natural-language task to Hanson Mobile-Use AI via your configured bridge or A2UI workspace.")
    public static var openAppWhenRun: Bool = true

    @Parameter(title: "Task Prompt")
    public var prompt: String

    @Parameter(title: "Bridge Endpoint (Optional)", default: "http://127.0.0.1:18888")
    public var bridgeEndpoint: String

    public init() {
        self.prompt = ""
        self.bridgeEndpoint = "http://127.0.0.1:18888"
    }

    public init(prompt: String, bridgeEndpoint: String = "http://127.0.0.1:18888") {
        self.prompt = prompt
        self.bridgeEndpoint = bridgeEndpoint
    }

    public func perform() async throws -> some IntentResult & ProvidesDialog {
        let cleaned = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else {
            return .result(dialog: "Provide a task prompt for Hanson Mobile Agent.")
        }
        guard let url = URL(string: bridgeEndpoint), HansonSseEndpointValidator.isAllowed(url) else {
            return .result(dialog: "Blocked insecure bridge URL. Use https:// or http://127.0.0.1.")
        }
        return .result(dialog: "Queued Hanson task '\(cleaned)' for \(url.host ?? "local") A2UI workspace.")
    }
}

@available(iOS 17.0, macOS 14.0, *)
public struct SendHansonCoDrawSpotlightIntent: AppIntent {
    public static var title: LocalizedStringResource = "Highlight Region on Hanson Canvas"
    public static var description = IntentDescription("Place a normalized 0..1 monochrome spotlight ring on the active Hanson Co-Drawing canvas.")

    @Parameter(title: "Normalized X (0..1)", default: 0.5)
    public var nx: Double

    @Parameter(title: "Normalized Y (0..1)", default: 0.5)
    public var ny: Double

    @Parameter(title: "Callout Label", default: "Focus here")
    public var label: String

    public init() {
        self.nx = 0.5
        self.ny = 0.5
        self.label = "Focus here"
    }

    public func perform() async throws -> some IntentResult & ProvidesDialog {
        let pt = NormalizedPoint(nx: nx, ny: ny)
        return .result(dialog: "Spotlight '\(label)' pinned at (\(String(format: "%.2f", pt.nx)), \(String(format: "%.2f", pt.ny))).")
    }
}

@available(iOS 17.0, macOS 14.0, *)
public struct HansonShortcutsProvider: AppShortcutsProvider {
    public static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RunHansonAgentIntent(),
            phrases: [
                "Ask \(.applicationName) to run a task",
                "Open \(.applicationName) workspace",
            ],
            shortTitle: "Run Hanson Agent",
            systemImageName: "sparkles"
        )
    }
}
#endif

// MARK: - Drop-In Monochrome Companion Workspace View

public struct HansonIosCompanionView: View {
    @StateObject private var a2uiStore = A2uiSurfaceStore()
    @StateObject private var coDrawState = CoDrawingCanvasState()
    @StateObject private var sseClient = HansonSseClient()
    @State private var appearance: HansonAppearanceMode = .paper
    @State private var showCapabilities = false

    public init() {}

    public var body: some View {
        let palette = HansonAchromaticPalette(mode: appearance)
        ZStack {
            HansonDotGridBackground(palette: palette)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    headerBar(palette: palette)

                    ForEach(a2uiStore.orderedSurfaceIds, id: \.self) { sid in
                        if let surface = a2uiStore.surfaces[sid] {
                            A2uiSurfaceView(
                                surface: surface,
                                palette: palette,
                                onDataMutation: { ptr, val in
                                    a2uiStore.mutateDataModel(surfaceId: sid, pointer: ptr, value: val)
                                },
                                onUserAction: { name, sourceId, ctx in
                                    a2uiStore.dispatchUserAction(
                                        name: name,
                                        surfaceId: sid,
                                        sourceComponentId: sourceId,
                                        rawContext: ctx
                                    )
                                }
                            )
                        }
                    }

                    CoDrawingSwiftUICanvas(state: coDrawState, palette: palette) { _ in }

                    if showCapabilities {
                        capabilityMatrixCard(palette: palette)
                    }
                }
                .padding(16)
            }
        }
    }

    private func headerBar(palette: HansonAchromaticPalette) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("HANSON · iOS COMPANION")
                    .font(.system(size: 13, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.textPrimary)
                Text("A2UI v1.0 · Co-Draw · \(sseClient.connectionState.rawValue)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
            }
            Spacer()
            Button(appearance.rawValue) {
                appearance = appearance.toggled()
            }
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundColor(palette.onInk)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(palette.ink)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .buttonStyle(.plain)

            Button("CAPS") {
                showCapabilities.toggle()
            }
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundColor(palette.textPrimary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(palette.surfaceHigh)
            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).stroke(palette.hairlineStrong, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .buttonStyle(.plain)
        }
    }

    private func capabilityMatrixCard(palette: HansonAchromaticPalette) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("IOS CAPABILITY BOUNDARIES")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(palette.textPrimary)
            ForEach(HansonIosCapabilityMatrix.all) { cap in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(cap.title)
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(palette.textPrimary)
                        Spacer()
                        Text(cap.tier.rawValue)
                            .font(.system(size: 9, weight: .bold, design: .monospaced))
                            .foregroundColor(cap.tier == .sandboxForbidden ? palette.textMuted : palette.textPrimary)
                    }
                    Text(cap.detail)
                        .font(.system(size: 11))
                        .foregroundColor(palette.textSecondary)
                }
                .padding(.vertical, 4)
            }
        }
        .padding(12)
        .background(palette.surface)
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).stroke(palette.hairline, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}
