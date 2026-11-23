import SwiftUI

// MARK: - Normalized 0..1 Coordinate Model

public struct NormalizedPoint: Equatable, Codable, Sendable {
    public let nx: Double
    public let ny: Double

    public init(nx: Double, ny: Double) {
        self.nx = min(max(nx, 0.0), 1.0)
        self.ny = min(max(ny, 0.0), 1.0)
    }

    public func toCGPoint(in size: CGSize) -> CGPoint {
        CGPoint(x: nx * size.width, y: ny * size.height)
    }

    public static func from(point: CGPoint, in size: CGSize) -> NormalizedPoint {
        guard size.width > 0, size.height > 0 else { return NormalizedPoint(nx: 0, ny: 0) }
        return NormalizedPoint(nx: Double(point.x / size.width), ny: Double(point.y / size.height))
    }

    public static func fromNorm1000(x: Double, y: Double) -> NormalizedPoint {
        NormalizedPoint(nx: x / 1000.0, ny: y / 1000.0)
    }
}

public enum CoDrawAuthor: String, Codable, Sendable {
    case human = "HUMAN"
    case ai = "AI"
}

public enum CoDrawTool: String, CaseIterable, Sendable {
    case pen = "PEN"
    case arrow = "ARROW"
    case spotlightRing = "RING"

    public var symbolName: String {
        switch self {
        case .pen: return "pencil.tip"
        case .arrow: return "arrow.up.right"
        case .spotlightRing: return "scope"
        }
    }
}

public enum CoDrawElementKind: String, Codable, Sendable {
    case freehandStroke = "FREEHAND"
    case arrow = "ARROW"
    case spotlightRing = "SPOTLIGHT_RING"
}

public struct CoDrawElement: Identifiable, Equatable, Codable, Sendable {
    public let id: String
    public let author: CoDrawAuthor
    public let kind: CoDrawElementKind
    public var points: [NormalizedPoint]
    public var radius: Double
    public var label: String
    public var stepBadge: String

    public init(
        id: String = UUID().uuidString,
        author: CoDrawAuthor,
        kind: CoDrawElementKind,
        points: [NormalizedPoint],
        radius: Double = 0.09,
        label: String = "",
        stepBadge: String = ""
    ) {
        self.id = id
        self.author = author
        self.kind = kind
        self.points = points
        self.radius = min(max(radius, 0.02), 0.45)
        self.label = label
        self.stepBadge = stepBadge
    }
}

// MARK: - Event-Sourced Co-Drawing State Engine (Undo / Redo + Remote SSE Ingress)

@MainActor
public final class CoDrawingCanvasState: ObservableObject {
    @Published public private(set) var elements: [CoDrawElement] = []
    @Published public var activeTool: CoDrawTool = .pen
    @Published public private(set) var canUndo: Bool = false
    @Published public private(set) var canRedo: Bool = false

    private var undoHistory: [[CoDrawElement]] = []
    private var redoHistory: [[CoDrawElement]] = []

    public init(initialElements: [CoDrawElement] = []) {
        self.elements = initialElements
    }

    public func addElement(_ element: CoDrawElement) {
        pushUndoSnapshot()
        elements.append(element)
        refreshStackFlags()
    }

    public func undo() {
        guard let previous = undoHistory.popLast() else { return }
        redoHistory.append(elements)
        elements = previous
        refreshStackFlags()
    }

    public func redo() {
        guard let next = redoHistory.popLast() else { return }
        undoHistory.append(elements)
        elements = next
        refreshStackFlags()
    }

    public func clear() {
        guard !elements.isEmpty else { return }
        pushUndoSnapshot()
        elements.removeAll()
        refreshStackFlags()
    }

    /// Apply a Hanson SSE `codraw_command` JSON payload (`SPOTLIGHT_RING`, `ARROW`, `clear`).
    public func applyRemoteCommandJSON(_ jsonString: String) {
        guard let data = jsonString.data(using: .utf8),
              let val = try? JSONDecoder().decode(A2uiJsonValue.self, from: data),
              let obj = val.objectValue
        else { return }

        if obj["action"]?.stringValue?.lowercased() == "clear" {
            clear()
            return
        }

        let author: CoDrawAuthor = (obj["author"]?.stringValue?.uppercased() == "HUMAN") ? .human : .ai
        let kindRaw = (obj["kind"]?.stringValue ?? "").uppercased()
        let label = obj["label"]?.stringValue ?? ""
        let badge = obj["stepBadge"]?.stringValue ?? ""
        let id = obj["id"]?.stringValue ?? UUID().uuidString

        if kindRaw == "SPOTLIGHT_RING" {
            let nx = obj["nx"]?.doubleValue ?? 0.5
            let ny = obj["ny"]?.doubleValue ?? 0.5
            let radius = obj["radius"]?.doubleValue ?? 0.10
            addElement(CoDrawElement(
                id: id,
                author: author,
                kind: .spotlightRing,
                points: [NormalizedPoint(nx: nx, ny: ny)],
                radius: radius,
                label: label,
                stepBadge: badge
            ))
        } else if kindRaw == "ARROW" || kindRaw == "FREEHAND" {
            let pts: [NormalizedPoint] = (obj["points"]?.arrayValue ?? []).compactMap { item in
                guard let po = item.objectValue,
                      let nx = po["nx"]?.doubleValue,
                      let ny = po["ny"]?.doubleValue
                else { return nil }
                return NormalizedPoint(nx: nx, ny: ny)
            }
            guard !pts.isEmpty else { return }
            addElement(CoDrawElement(
                id: id,
                author: author,
                kind: kindRaw == "ARROW" ? .arrow : .freehandStroke,
                points: pts,
                label: label,
                stepBadge: badge
            ))
        }
    }

    /// Summarize human sketch bounding regions in normalized `0..1` space for VLM grounding.
    public func buildSpatialPromptSummary() -> String {
        let humanItems = elements.filter { $0.author == .human }
        guard !humanItems.isEmpty else { return "" }
        let descriptions: [String] = humanItems.enumerated().compactMap { idx, item in
            let xs = item.points.map(\.nx)
            let ys = item.points.map(\.ny)
            guard let minX = xs.min(), let maxX = xs.max(),
                  let minY = ys.min(), let maxY = ys.max()
            else { return nil }
            return String(
                format: "Human %@ #%d at x=%.2f..%.2f, y=%.2f..%.2f",
                item.kind.rawValue.lowercased(),
                idx + 1, minX, maxX, minY, maxY
            )
        }
        return descriptions.joined(separator: "; ")
    }

    private func pushUndoSnapshot() {
        undoHistory.append(elements)
        if undoHistory.count > 64 {
            undoHistory.removeFirst()
        }
        redoHistory.removeAll()
    }

    private func refreshStackFlags() {
        canUndo = !undoHistory.isEmpty
        canRedo = !redoHistory.isEmpty
    }
}

// MARK: - Monochrome SwiftUI Interactive Co-Drawing Canvas

public struct CoDrawingSwiftUICanvas: View {
    @ObservedObject public var state: CoDrawingCanvasState
    public let palette: HansonAchromaticPalette
    public let onSendSketchToAgent: ((String) -> Void)?

    @State private var draftPoints: [NormalizedPoint] = []
    @State private var draftRadius: Double = 0.08

    public init(
        state: CoDrawingCanvasState,
        palette: HansonAchromaticPalette = HansonAchromaticPalette(mode: .paper),
        onSendSketchToAgent: ((String) -> Void)? = nil
    ) {
        self.state = state
        self.palette = palette
        self.onSendSketchToAgent = onSendSketchToAgent
    }

    public var body: some View {
        VStack(spacing: 10) {
            GeometryReader { geo in
                ZStack {
                    palette.surface
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(palette.ink, lineWidth: 1.5)
                        )

                    Canvas { context, size in
                        drawDotGrid(in: &context, size: size)
                        for element in state.elements {
                            draw(element: element, in: &context, size: size)
                        }
                        if let draft = buildDraftElement() {
                            draw(element: draft, in: &context, size: size)
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                let pt = NormalizedPoint.from(point: value.location, in: geo.size)
                                switch state.activeTool {
                                case .pen:
                                    draftPoints.append(pt)
                                case .arrow:
                                    if draftPoints.isEmpty {
                                        draftPoints = [pt, pt]
                                    } else {
                                        draftPoints = [draftPoints[0], pt]
                                    }
                                case .spotlightRing:
                                    if draftPoints.isEmpty {
                                        draftPoints = [pt]
                                        draftRadius = 0.07
                                    } else {
                                        let center = draftPoints[0]
                                        let dx = pt.nx - center.nx
                                        let dy = pt.ny - center.ny
                                        draftRadius = min(max(hypot(dx, dy), 0.04), 0.35)
                                    }
                                }
                            }
                            .onEnded { _ in
                                if let finalized = buildDraftElement() {
                                    state.addElement(finalized)
                                }
                                draftPoints = []
                            }
                    )
                }
            }
            .frame(minHeight: 240)

            toolbarView
        }
    }

    private var toolbarView: some View {
        HStack(spacing: 6) {
            ForEach(CoDrawTool.allCases, id: \.self) { tool in
                let selected = state.activeTool == tool
                Button {
                    state.activeTool = tool
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: tool.symbolName)
                            .font(.system(size: 11, weight: .semibold))
                        Text(tool.rawValue)
                            .font(.system(size: 10, weight: .bold, design: .monospaced))
                    }
                    .foregroundColor(selected ? palette.onInk : palette.textPrimary)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 6)
                    .background(selected ? palette.ink : palette.surfaceHigh)
                    .overlay(
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .stroke(palette.hairlineStrong, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .buttonStyle(.plain)
            }

            Spacer()

            toolbarIconButton("arrow.uturn.backward", enabled: state.canUndo) { state.undo() }
            toolbarIconButton("arrow.uturn.forward", enabled: state.canRedo) { state.redo() }
            toolbarIconButton("trash", enabled: !state.elements.isEmpty) { state.clear() }

            if let sendHandler = onSendSketchToAgent {
                Button {
                    sendHandler(state.buildSpatialPromptSummary())
                } label: {
                    Text("SEND")
                        .font(.system(size: 10, weight: .bold, design: .monospaced))
                        .foregroundColor(palette.onInk)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(palette.ink)
                        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func toolbarIconButton(_ symbol: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(enabled ? palette.ink : palette.textMuted.opacity(0.45))
                .frame(width: 28, height: 28)
                .background(palette.surfaceHigh)
                .overlay(
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .stroke(palette.hairline, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
        }
        .disabled(!enabled)
        .buttonStyle(.plain)
    }

    private func buildDraftElement() -> CoDrawElement? {
        guard !draftPoints.isEmpty else { return nil }
        switch state.activeTool {
        case .pen:
            return CoDrawElement(author: .human, kind: .freehandStroke, points: draftPoints)
        case .arrow:
            guard draftPoints.count >= 2 else { return nil }
            return CoDrawElement(author: .human, kind: .arrow, points: [draftPoints[0], draftPoints[1]])
        case .spotlightRing:
            return CoDrawElement(author: .human, kind: .spotlightRing, points: [draftPoints[0]], radius: draftRadius)
        }
    }

    private func drawDotGrid(in context: inout GraphicsContext, size: CGSize) {
        let step: CGFloat = 20
        var y: CGFloat = step
        while y < size.height {
            var x: CGFloat = step
            while x < size.width {
                context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 1.5, height: 1.5)), with: .color(palette.dotGrid))
                x += step
            }
            y += step
        }
    }

    private func draw(element: CoDrawElement, in context: inout GraphicsContext, size: CGSize) {
        let isAi = element.author == .ai
        let strokeColor = isAi ? palette.textSecondary : palette.ink
        let strokeStyle = StrokeStyle(
            lineWidth: isAi ? 2.0 : 2.6,
            lineCap: .round,
            lineJoin: .round,
            dash: isAi ? [6, 4] : []
        )

        switch element.kind {
        case .freehandStroke:
            guard element.points.count >= 2 else { return }
            var path = Path()
            let pts = element.points.map { $0.toCGPoint(in: size) }
            path.move(to: pts[0])
            for idx in 1..<pts.count {
                let prev = pts[idx - 1]
                let cur = pts[idx]
                let mid = CGPoint(x: (prev.x + cur.x) / 2, y: (prev.y + cur.y) / 2)
                path.addQuadCurve(to: mid, control: prev)
            }
            if let last = pts.last {
                path.addLine(to: last)
            }
            context.stroke(path, with: .color(strokeColor), style: strokeStyle)

        case .arrow:
            guard element.points.count >= 2 else { return }
            let start = element.points[0].toCGPoint(in: size)
            let end = element.points[element.points.count - 1].toCGPoint(in: size)
            var shaft = Path()
            shaft.move(to: start)
            shaft.addLine(to: end)
            context.stroke(shaft, with: .color(strokeColor), style: strokeStyle)

            let angle = atan2(end.y - start.y, end.x - start.x)
            let headLen: CGFloat = 12
            let wing1 = CGPoint(
                x: end.x - headLen * cos(angle - .pi / 6),
                y: end.y - headLen * sin(angle - .pi / 6)
            )
            let wing2 = CGPoint(
                x: end.x - headLen * cos(angle + .pi / 6),
                y: end.y - headLen * sin(angle + .pi / 6)
            )
            var head = Path()
            head.move(to: wing1)
            head.addLine(to: end)
            head.addLine(to: wing2)
            context.stroke(head, with: .color(strokeColor), style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round))
            drawBadgeIfPresent(element, anchor: end, in: &context)

        case .spotlightRing:
            guard let centerNorm = element.points.first else { return }
            let center = centerNorm.toCGPoint(in: size)
            let r = CGFloat(element.radius) * min(size.width, size.height)
            let rect = CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2)
            context.fill(Path(ellipseIn: rect), with: .color(palette.ink.opacity(0.06)))
            context.stroke(Path(ellipseIn: rect), with: .color(strokeColor), style: strokeStyle)
            let inner = rect.insetBy(dx: 5, dy: 5)
            if inner.width > 4 {
                context.stroke(Path(ellipseIn: inner), with: .color(palette.hairlineStrong), lineWidth: 1)
            }
            drawBadgeIfPresent(element, anchor: CGPoint(x: center.x, y: max(14, center.y - r - 12)), in: &context)
        }
    }

    private func drawBadgeIfPresent(_ element: CoDrawElement, anchor: CGPoint, in context: inout GraphicsContext) {
        let combined = [element.stepBadge.isEmpty ? nil : "#\(element.stepBadge)", element.label.isEmpty ? nil : element.label]
            .compactMap { $0 }
            .joined(separator: " ")
        guard !combined.isEmpty else { return }
        let text = Text(combined)
            .font(.system(size: 10, weight: .bold, design: .monospaced))
            .foregroundColor(palette.onInk)
        let resolved = context.resolve(text)
        let textSize = resolved.measure(in: CGSize(width: 220, height: 24))
        let pill = CGRect(
            x: anchor.x - textSize.width / 2 - 6,
            y: anchor.y - textSize.height / 2 - 3,
            width: textSize.width + 12,
            height: textSize.height + 6
        )
        context.fill(Path(roundedRect: pill, cornerRadius: 5), with: .color(palette.ink))
        context.draw(resolved, at: CGPoint(x: pill.midX, y: pill.midY))
    }
}
