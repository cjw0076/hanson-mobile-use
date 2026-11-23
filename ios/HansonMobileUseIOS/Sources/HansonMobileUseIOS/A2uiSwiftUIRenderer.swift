import SwiftUI

// MARK: - Pure Achromatic (0% Saturation) Obsidian Paper & Carbon Palette

public enum HansonAppearanceMode: String, CaseIterable, Sendable {
    case paper = "PAPER"
    case carbon = "CARBON"

    public func toggled() -> HansonAppearanceMode {
        self == .paper ? .carbon : .paper
    }
}

/// Strictly 0%-saturation monochrome tokens aligned 1:1 with Android `ObsidianColors`.
/// Paper Light: `#FAFAFA` canvas / `#09090B` ink.
/// Carbon Dark: `#09090B` canvas / `#FAFAFA` ink.
public struct HansonAchromaticPalette: Sendable {
    public let mode: HansonAppearanceMode

    public init(mode: HansonAppearanceMode = .paper) {
        self.mode = mode
    }

    private var isDark: Bool { mode == .carbon }

    public var background: Color { isDark ? .hansonMono(0x09090B) : .hansonMono(0xFAFAFA) }
    public var surface: Color { isDark ? .hansonMono(0x121214) : .hansonMono(0xFFFFFF) }
    public var surfaceHigh: Color { isDark ? .hansonMono(0x18181B) : .hansonMono(0xF4F4F5) }
    public var surfaceTop: Color { isDark ? .hansonMono(0x27272A) : .hansonMono(0xE4E4E7) }
    public var hairline: Color { isDark ? .hansonMono(0x27272A) : .hansonMono(0xE4E4E7) }
    public var hairlineStrong: Color { isDark ? .hansonMono(0x3F3F46) : .hansonMono(0xD4D4D8) }
    public var ink: Color { isDark ? .hansonMono(0xFAFAFA) : .hansonMono(0x09090B) }
    public var onInk: Color { isDark ? .hansonMono(0x09090B) : .hansonMono(0xFAFAFA) }
    public var textPrimary: Color { isDark ? .hansonMono(0xFAFAFA) : .hansonMono(0x09090B) }
    public var textSecondary: Color { isDark ? .hansonMono(0xA1A1AA) : .hansonMono(0x52525B) }
    public var textMuted: Color { .hansonMono(0x71717A) }
    public var dotGrid: Color { (isDark ? Color.hansonMono(0xFFFFFF) : Color.hansonMono(0x09090B)).opacity(0.08) }
}

extension Color {
    static func hansonMono(_ rgbHex: UInt32) -> Color {
        let r = Double((rgbHex >> 16) & 0xFF) / 255.0
        let g = Double((rgbHex >> 8) & 0xFF) / 255.0
        let b = Double(rgbHex & 0xFF) / 255.0
        return Color(red: r, green: g, blue: b)
    }
}

public struct HansonDotGridBackground: View {
    public let palette: HansonAchromaticPalette
    public let spacing: CGFloat

    public init(palette: HansonAchromaticPalette, spacing: CGFloat = 18) {
        self.palette = palette
        self.spacing = spacing
    }

    public var body: some View {
        ZStack {
            palette.background
            Canvas { context, size in
                let cols = Int(size.width / spacing) + 1
                let rows = Int(size.height / spacing) + 1
                for r in 0..<rows {
                    for c in 0..<cols {
                        let rect = CGRect(
                            x: CGFloat(c) * spacing,
                            y: CGFloat(r) * spacing,
                            width: 1.5,
                            height: 1.5
                        )
                        context.fill(Path(ellipseIn: rect), with: .color(palette.dotGrid))
                    }
                }
            }
        }
        .ignoresSafeArea()
    }
}

// MARK: - Native SwiftUI A2UI Basic Catalog Renderer

public struct A2uiSurfaceView: View {
    public let surface: A2uiSurfaceState
    public let palette: HansonAchromaticPalette
    public let onDataMutation: (String, A2uiJsonValue) -> Void
    public let onUserAction: (String, String, [String: A2uiJsonValue]) -> Void

    public init(
        surface: A2uiSurfaceState,
        palette: HansonAchromaticPalette = HansonAchromaticPalette(mode: .paper),
        onDataMutation: @escaping (String, A2uiJsonValue) -> Void,
        onUserAction: @escaping (String, String, [String: A2uiJsonValue]) -> Void
    ) {
        self.surface = surface
        self.palette = palette
        self.onDataMutation = onDataMutation
        self.onUserAction = onUserAction
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text("A2UI · \(surface.version.rawValue.uppercased())")
                    .font(.system(size: 10, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.onInk)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(palette.ink)
                    .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))

                Text(surface.surfaceId)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
                    .lineLimit(1)

                Spacer()

                Text("\(surface.components.count) nodes")
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(palette.textMuted)
            }

            if let rootNode = surface.components[surface.rootComponentId] {
                A2uiNodeView(
                    node: rootNode,
                    surface: surface,
                    palette: palette,
                    visited: [rootNode.id],
                    depth: 0,
                    onDataMutation: onDataMutation,
                    onUserAction: onUserAction
                )
            } else {
                Text("Waiting for root component '\(surface.rootComponentId)'...")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(palette.textMuted)
                    .padding(.vertical, 8)
            }
        }
        .padding(14)
        .background(palette.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(palette.hairline, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .shadow(color: palette.ink.opacity(0.08), radius: 0, x: 0, y: 3)
    }
}

private struct A2uiNodeView: View {
    let node: A2uiComponentNode
    let surface: A2uiSurfaceState
    let palette: HansonAchromaticPalette
    let visited: Set<String>
    let depth: Int
    let onDataMutation: (String, A2uiJsonValue) -> Void
    let onUserAction: (String, String, [String: A2uiJsonValue]) -> Void

    private static let maxDepth = 24

    var body: some View {
        if depth > Self.maxDepth {
            EmptyView()
        } else {
            renderComponent()
        }
    }

    @ViewBuilder
    private func renderComponent() -> some View {
        switch node.type {
        case "Column":
            VStack(alignment: .leading, spacing: 10) {
                renderChildren()
            }
            .frame(maxWidth: .infinity, alignment: .leading)

        case "Row":
            HStack(alignment: .center, spacing: 10) {
                renderChildren()
            }

        case "Card":
            VStack(alignment: .leading, spacing: 8) {
                renderChildOrChildren()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(palette.surfaceHigh)
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(palette.hairlineStrong, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

        case "Box":
            ZStack(alignment: .topLeading) {
                renderChildOrChildren()
            }

        case "Text":
            let resolved = A2uiDynamicResolver.resolveString(node.properties["text"], dataModel: surface.dataModel)
            let hint = (node.properties["usageHint"]?.stringValue ?? node.properties["variant"]?.stringValue ?? "body").lowercased()
            Text(resolved)
                .font(fontForUsageHint(hint))
                .foregroundColor(hint == "caption" ? palette.textSecondary : palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

        case "Button":
            renderButton()

        case "TextField":
            renderTextField()

        case "CheckBox":
            renderCheckBox()

        case "MultipleChoice":
            renderMultipleChoice()

        case "Slider":
            renderSlider()

        case "Divider":
            Rectangle()
                .fill(palette.hairline)
                .frame(height: 1)
                .padding(.vertical, 2)

        case "Icon":
            let rawName = A2uiDynamicResolver.resolveString(node.properties["name"], dataModel: surface.dataModel, fallback: "circle")
            Image(systemName: sfSymbol(for: rawName))
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(palette.ink)

        case "Image":
            renderImage()

        default:
            VStack(alignment: .leading, spacing: 4) {
                Text("[\(node.type)]")
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.textMuted)
                renderChildOrChildren()
            }
        }
    }

    @ViewBuilder
    private func renderChildren() -> some View {
        ForEach(node.childrenIds, id: \.self) { cid in
            if !visited.contains(cid), let child = surface.components[cid] {
                A2uiNodeView(
                    node: child,
                    surface: surface,
                    palette: palette,
                    visited: visited.union([cid]),
                    depth: depth + 1,
                    onDataMutation: onDataMutation,
                    onUserAction: onUserAction
                )
            }
        }
    }

    @ViewBuilder
    private func renderChildOrChildren() -> some View {
        if let cid = node.childId, !visited.contains(cid), let child = surface.components[cid] {
            A2uiNodeView(
                node: child,
                surface: surface,
                palette: palette,
                visited: visited.union([cid]),
                depth: depth + 1,
                onDataMutation: onDataMutation,
                onUserAction: onUserAction
            )
        } else {
            renderChildren()
        }
    }

    @ViewBuilder
    private func renderButton() -> some View {
        let actionObj = node.properties["action"]?.objectValue
        let actionName = actionObj?["name"]?.stringValue ?? node.id
        let actionCtx = parseActionContext(actionObj?["context"])
        let directLabel = A2uiDynamicResolver.resolveString(
            node.properties["label"] ?? node.properties["text"],
            dataModel: surface.dataModel
        )
        let isPrimary = (node.properties["primary"]?.boolValue ?? true)

        Button {
            onUserAction(actionName, node.id, actionCtx)
        } label: {
            Group {
                if let cid = node.childId, let child = surface.components[cid] {
                    A2uiNodeView(
                        node: child,
                        surface: surface,
                        palette: HansonAchromaticPalette(mode: isPrimary ? palette.mode.toggled() : palette.mode),
                        visited: visited.union([cid]),
                        depth: depth + 1,
                        onDataMutation: onDataMutation,
                        onUserAction: onUserAction
                    )
                } else {
                    Text(directLabel.isEmpty ? actionName : directLabel)
                        .font(.system(size: 13, weight: .semibold))
                }
            }
            .foregroundColor(isPrimary ? palette.onInk : palette.textPrimary)
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(isPrimary ? palette.ink : palette.surfaceHigh)
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(palette.ink, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func renderTextField() -> some View {
        let label = A2uiDynamicResolver.resolveString(node.properties["label"], dataModel: surface.dataModel)
        let valueProp = node.properties["text"] ?? node.properties["value"]
        let boundPtr = A2uiDynamicResolver.boundPointer(in: valueProp)
        let current = A2uiDynamicResolver.resolveString(valueProp, dataModel: surface.dataModel)
        let binding = Binding<String>(
            get: { current },
            set: { updated in
                if let ptr = boundPtr {
                    onDataMutation(ptr, .string(updated))
                }
            }
        )

        VStack(alignment: .leading, spacing: 4) {
            if !label.isEmpty {
                Text(label)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
            }
            TextField(label.isEmpty ? "Enter value" : label, text: binding)
                .font(.system(size: 14))
                .foregroundColor(palette.textPrimary)
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(palette.background)
                .overlay(
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .stroke(palette.hairlineStrong, lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
        }
    }

    @ViewBuilder
    private func renderCheckBox() -> some View {
        let label = A2uiDynamicResolver.resolveString(node.properties["label"], dataModel: surface.dataModel)
        let valProp = node.properties["value"]
        let boundPtr = A2uiDynamicResolver.boundPointer(in: valProp)
        let checked = A2uiDynamicResolver.resolveBool(valProp, dataModel: surface.dataModel)

        Button {
            if let ptr = boundPtr {
                onDataMutation(ptr, .bool(!checked))
            }
        } label: {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 4, style: .continuous)
                        .fill(checked ? palette.ink : palette.background)
                        .frame(width: 18, height: 18)
                        .overlay(
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .stroke(palette.ink, lineWidth: 1.2)
                        )
                    if checked {
                        Image(systemName: "checkmark")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundColor(palette.onInk)
                    }
                }
                Text(label)
                    .font(.system(size: 14))
                    .foregroundColor(palette.textPrimary)
            }
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func renderMultipleChoice() -> some View {
        let options = (node.properties["options"]?.arrayValue ?? []).compactMap { item -> (String, String)? in
            guard let obj = item.objectValue else { return nil }
            let val = obj["value"]?.stringValue ?? ""
            let lbl = A2uiDynamicResolver.resolveString(obj["label"], dataModel: surface.dataModel, fallback: val)
            return val.isEmpty ? nil : (val, lbl)
        }
        let selProp = node.properties["selections"] ?? node.properties["value"]
        let boundPtr = A2uiDynamicResolver.boundPointer(in: selProp)
        let currentVal = A2uiDynamicResolver.resolveString(selProp, dataModel: surface.dataModel)

        VStack(alignment: .leading, spacing: 6) {
            ForEach(options, id: \.0) { value, label in
                let selected = currentVal == value
                Button {
                    if let ptr = boundPtr {
                        onDataMutation(ptr, .string(value))
                    }
                } label: {
                    HStack {
                        Text(label)
                            .font(.system(size: 13, weight: selected ? .semibold : .regular))
                            .foregroundColor(selected ? palette.onInk : palette.textPrimary)
                        Spacer()
                        if selected {
                            Image(systemName: "checkmark")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(palette.onInk)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(selected ? palette.ink : palette.surfaceHigh)
                    .overlay(
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .stroke(selected ? palette.ink : palette.hairlineStrong, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder
    private func renderSlider() -> some View {
        let valProp = node.properties["value"]
        let boundPtr = A2uiDynamicResolver.boundPointer(in: valProp)
        let minVal = node.properties["minValue"]?.doubleValue ?? node.properties["min"]?.doubleValue ?? 0.0
        let maxVal = max(minVal + 1.0, node.properties["maxValue"]?.doubleValue ?? node.properties["max"]?.doubleValue ?? 100.0)
        let current = min(max(A2uiDynamicResolver.resolveDouble(valProp, dataModel: surface.dataModel, fallback: minVal), minVal), maxVal)
        let binding = Binding<Double>(
            get: { current },
            set: { updated in
                if let ptr = boundPtr {
                    onDataMutation(ptr, .double(updated))
                }
            }
        )

        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(A2uiDynamicResolver.resolveString(node.properties["label"], dataModel: surface.dataModel, fallback: "Value"))
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundColor(palette.textSecondary)
                Spacer()
                Text(String(format: "%.1f", current))
                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                    .foregroundColor(palette.textPrimary)
            }
            Slider(value: binding, in: minVal...maxVal)
                .tint(palette.ink)
        }
    }

    @ViewBuilder
    private func renderImage() -> some View {
        let urlStr = A2uiDynamicResolver.resolveString(node.properties["url"], dataModel: surface.dataModel)
        if let url = URL(string: urlStr), HansonSseEndpointValidator.isAllowed(url) {
            AsyncImage(url: url) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFit()
                        .saturation(0)
                default:
                    imagePlaceholder(label: url.lastPathComponent)
                }
            }
            .frame(maxHeight: 180)
            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(palette.hairline, lineWidth: 1))
        } else {
            imagePlaceholder(label: "IMAGE")
        }
    }

    private func imagePlaceholder(label: String) -> some View {
        HStack {
            Image(systemName: "photo")
                .foregroundColor(palette.textMuted)
            Text(label.isEmpty ? "IMAGE" : label)
                .font(.system(size: 11, design: .monospaced))
                .foregroundColor(palette.textMuted)
        }
        .frame(maxWidth: .infinity, minHeight: 56)
        .background(palette.surfaceHigh)
        .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
    }

    private func fontForUsageHint(_ hint: String) -> Font {
        switch hint {
        case "h1": return .system(size: 22, weight: .bold)
        case "h2": return .system(size: 18, weight: .bold)
        case "h3": return .system(size: 16, weight: .semibold)
        case "h4", "h5": return .system(size: 14, weight: .semibold)
        case "caption": return .system(size: 12, weight: .regular)
        default: return .system(size: 14, weight: .regular)
        }
    }

    private func parseActionContext(_ raw: A2uiJsonValue?) -> [String: A2uiJsonValue] {
        if let dict = raw?.objectValue { return dict }
        if let arr = raw?.arrayValue {
            var out: [String: A2uiJsonValue] = [:]
            for item in arr {
                if let obj = item.objectValue, let key = obj["key"]?.stringValue, let val = obj["value"] {
                    out[key] = val
                }
            }
            return out
        }
        return [:]
    }

    private func sfSymbol(for name: String) -> String {
        switch name.lowercased() {
        case "check", "done", "verified": return "checkmark.circle"
        case "warning", "alert": return "exclamationmark.triangle"
        case "search": return "magnifyingglass"
        case "arrow_forward", "next": return "arrow.right"
        case "arrow_back", "back": return "arrow.left"
        case "settings": return "gearshape"
        case "sparkles", "ai": return "sparkles"
        case "lock", "shield": return "lock.shield"
        default: return "circle.fill"
        }
    }
}
