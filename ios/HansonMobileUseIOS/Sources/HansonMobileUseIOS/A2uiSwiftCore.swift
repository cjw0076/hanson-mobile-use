import Foundation

// MARK: - Protocol Version & Dynamic JSON Value (RFC 6901 JSON Pointer)

public enum A2uiVersion: String, Codable, Sendable, Equatable {
    case v0_8 = "v0.8"
    case v0_9 = "v0.9"
    case v1_0 = "v1.0"
}

public enum A2uiJsonValue: Equatable, Hashable, Sendable, Codable {
    case null
    case bool(Bool)
    case int(Int)
    case double(Double)
    case string(String)
    case array([A2uiJsonValue])
    case object([String: A2uiJsonValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let boolVal = try? container.decode(Bool.self) {
            self = .bool(boolVal)
        } else if let intVal = try? container.decode(Int.self) {
            self = .int(intVal)
        } else if let doubleVal = try? container.decode(Double.self) {
            self = .double(doubleVal)
        } else if let strVal = try? container.decode(String.self) {
            self = .string(strVal)
        } else if let arrVal = try? container.decode([A2uiJsonValue].self) {
            self = .array(arrVal)
        } else if let objVal = try? container.decode([String: A2uiJsonValue].self) {
            self = .object(objVal)
        } else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Unsupported JSON value in A2UI payload"
            )
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null:
            try container.encodeNil()
        case .bool(let v):
            try container.encode(v)
        case .int(let v):
            try container.encode(v)
        case .double(let v):
            try container.encode(v)
        case .string(let v):
            try container.encode(v)
        case .array(let v):
            try container.encode(v)
        case .object(let v):
            try container.encode(v)
        }
    }

    public var stringValue: String? {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return d.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(d)) : String(d)
        case .bool(let b): return b ? "true" : "false"
        default: return nil
        }
    }

    public var boolValue: Bool? {
        switch self {
        case .bool(let b): return b
        case .string(let s): return s.lowercased() == "true"
        case .int(let i): return i != 0
        default: return nil
        }
    }

    public var doubleValue: Double? {
        switch self {
        case .double(let d): return d
        case .int(let i): return Double(i)
        case .string(let s): return Double(s)
        default: return nil
        }
    }

    public var arrayValue: [A2uiJsonValue]? {
        if case .array(let arr) = self { return arr }
        return nil
    }

    public var objectValue: [String: A2uiJsonValue]? {
        if case .object(let obj) = self { return obj }
        return nil
    }

    public subscript(key: String) -> A2uiJsonValue? {
        if case .object(let obj) = self { return obj[key] }
        return nil
    }

    /// Parse RFC 6901 JSON Pointer tokens (unescaping `~1` -> `/` and `~0` -> `~`).
    public static func parsePointerTokens(_ pointer: String) -> [String] {
        let trimmed = pointer.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == "/" { return [] }
        let stripped = trimmed.hasPrefix("/") ? String(trimmed.dropFirst()) : trimmed
        return stripped.split(separator: "/", omittingEmptySubsequences: false).map { token in
            String(token)
                .replacingOccurrences(of: "~1", with: "/")
                .replacingOccurrences(of: "~0", with: "~")
        }
    }

    /// Resolve a value at an RFC 6901 JSON Pointer path (e.g. `"/user/name"` or `"/items/0/title"`).
    public func resolve(pointer: String) -> A2uiJsonValue? {
        let tokens = Self.parsePointerTokens(pointer)
        var current: A2uiJsonValue = self
        for token in tokens {
            switch current {
            case .object(let dict):
                guard let next = dict[token] else { return nil }
                current = next
            case .array(let arr):
                guard let idx = Int(token), idx >= 0, idx < arr.count else { return nil }
                current = arr[idx]
            default:
                return nil
            }
        }
        return current
    }

    /// Return a new JSON value with `value` set at the RFC 6901 JSON Pointer `pointer`.
    public func setting(pointer: String, value: A2uiJsonValue) -> A2uiJsonValue {
        let tokens = Self.parsePointerTokens(pointer)
        if tokens.isEmpty { return value }
        return Self.setRecursive(current: self, tokens: tokens, index: 0, value: value)
    }

    /// Return a new JSON value with the key/index at `pointer` removed.
    public func removing(pointer: String) -> A2uiJsonValue {
        let tokens = Self.parsePointerTokens(pointer)
        if tokens.isEmpty { return .object([:]) }
        return Self.removeRecursive(current: self, tokens: tokens, index: 0)
    }

    private static func setRecursive(
        current: A2uiJsonValue,
        tokens: [String],
        index: Int,
        value: A2uiJsonValue
    ) -> A2uiJsonValue {
        let key = tokens[index]
        let isLast = index == tokens.count - 1
        if case .array(var arr) = current, let arrIdx = Int(key), arrIdx >= 0, arrIdx < arr.count {
            arr[arrIdx] = isLast
                ? value
                : setRecursive(current: arr[arrIdx], tokens: tokens, index: index + 1, value: value)
            return .array(arr)
        }
        var dict = current.objectValue ?? [:]
        if isLast {
            dict[key] = value
        } else {
            let child = dict[key] ?? .object([:])
            dict[key] = setRecursive(current: child, tokens: tokens, index: index + 1, value: value)
        }
        return .object(dict)
    }

    private static func removeRecursive(
        current: A2uiJsonValue,
        tokens: [String],
        index: Int
    ) -> A2uiJsonValue {
        let key = tokens[index]
        let isLast = index == tokens.count - 1
        switch current {
        case .object(var dict):
            if isLast {
                dict.removeValue(forKey: key)
            } else if let child = dict[key] {
                dict[key] = removeRecursive(current: child, tokens: tokens, index: index + 1)
            }
            return .object(dict)
        case .array(var arr):
            if let arrIdx = Int(key), arrIdx >= 0, arrIdx < arr.count {
                if isLast {
                    arr.remove(at: arrIdx)
                } else {
                    arr[arrIdx] = removeRecursive(current: arr[arrIdx], tokens: tokens, index: index + 1)
                }
            }
            return .array(arr)
        default:
            return current
        }
    }
}

// MARK: - Surface, Component & Outbound Action Models

public struct A2uiComponentNode: Equatable, Sendable, Identifiable {
    public let id: String
    public let type: String
    public let properties: [String: A2uiJsonValue]
    public let childrenIds: [String]
    public let childId: String?
    public let catalogId: String?

    public init(
        id: String,
        type: String,
        properties: [String: A2uiJsonValue] = [:],
        childrenIds: [String] = [],
        childId: String? = nil,
        catalogId: String? = nil
    ) {
        self.id = id
        self.type = type
        self.properties = properties
        self.childrenIds = childrenIds
        self.childId = childId
        self.catalogId = catalogId
    }
}

public struct A2uiSurfaceState: Equatable, Sendable, Identifiable {
    public var id: String { surfaceId }
    public let surfaceId: String
    public var catalogId: String
    public var version: A2uiVersion
    public var rootComponentId: String
    public var components: [String: A2uiComponentNode]
    public var dataModel: A2uiJsonValue
    public var isReady: Bool

    public init(
        surfaceId: String,
        catalogId: String = "https://a2ui.org/specification/v1_0/basic_catalog.json",
        version: A2uiVersion = .v1_0,
        rootComponentId: String = "root",
        components: [String: A2uiComponentNode] = [:],
        dataModel: A2uiJsonValue = .object([:]),
        isReady: Bool = true
    ) {
        self.surfaceId = surfaceId
        self.catalogId = catalogId
        self.version = version
        self.rootComponentId = rootComponentId
        self.components = components
        self.dataModel = dataModel
        self.isReady = isReady
    }
}

public struct A2uiUserAction: Equatable, Sendable {
    public let name: String
    public let surfaceId: String
    public let sourceComponentId: String
    public let timestampIso8601: String
    public let context: [String: A2uiJsonValue]

    public init(
        name: String,
        surfaceId: String,
        sourceComponentId: String,
        timestampIso8601: String = ISO8601DateFormatter().string(from: Date()),
        context: [String: A2uiJsonValue] = [:]
    ) {
        self.name = name
        self.surfaceId = surfaceId
        self.sourceComponentId = sourceComponentId
        self.timestampIso8601 = timestampIso8601
        self.context = context
    }

    public func toWirePayload(version: A2uiVersion = .v1_0) -> A2uiJsonValue {
        let body: A2uiJsonValue = .object([
            "name": .string(name),
            "surfaceId": .string(surfaceId),
            "sourceComponentId": .string(sourceComponentId),
            "timestamp": .string(timestampIso8601),
            "context": .object(context),
        ])
        switch version {
        case .v1_0:
            return .object(["version": .string("v1.0"), "action": body])
        case .v0_9:
            return .object(["version": .string("v0.9"), "action": body])
        case .v0_8:
            return .object(["userAction": body])
        }
    }

    public func toWireJSONString(version: A2uiVersion = .v1_0) -> String? {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        guard let data = try? encoder.encode(toWirePayload(version: version)) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

// MARK: - Dynamic Data Binding Resolver (`path` / `@path` / `${/ptr}` / `@{/ptr}`)

public enum A2uiDynamicResolver {
    /// Extract an RFC 6901 JSON Pointer if a component property binds to a path.
    public static func boundPointer(in raw: A2uiJsonValue?) -> String? {
        guard let obj = raw?.objectValue else { return nil }
        if let p = obj["@path"]?.stringValue { return p }
        if let p = obj["path"]?.stringValue { return p }
        return nil
    }

    /// Resolve a dynamic A2UI property against `dataModel`.
    public static func resolve(_ raw: A2uiJsonValue?, dataModel: A2uiJsonValue) -> A2uiJsonValue? {
        guard let raw else { return nil }
        switch raw {
        case .object(let dict):
            if let ptr = dict["@path"]?.stringValue ?? dict["path"]?.stringValue {
                return dataModel.resolve(pointer: ptr)
            }
            if let lit = dict["literalString"] { return lit }
            if let lit = dict["literalNumber"] { return lit }
            if let lit = dict["literalBoolean"] { return lit }
            if let lit = dict["literal"] { return lit }
            return raw
        case .string(let text):
            return .string(interpolate(text, dataModel: dataModel))
        default:
            return raw
        }
    }

    public static func resolveString(_ raw: A2uiJsonValue?, dataModel: A2uiJsonValue, fallback: String = "") -> String {
        resolve(raw, dataModel: dataModel)?.stringValue ?? fallback
    }

    public static func resolveBool(_ raw: A2uiJsonValue?, dataModel: A2uiJsonValue, fallback: Bool = false) -> Bool {
        resolve(raw, dataModel: dataModel)?.boolValue ?? fallback
    }

    public static func resolveDouble(_ raw: A2uiJsonValue?, dataModel: A2uiJsonValue, fallback: Double = 0.0) -> Double {
        resolve(raw, dataModel: dataModel)?.doubleValue ?? fallback
    }

    /// Interpolate `${/pointer}` or `@{/pointer}` expressions inside strings.
    public static func interpolate(_ template: String, dataModel: A2uiJsonValue) -> String {
        guard template.contains("${/") || template.contains("@{/") else { return template }
        let pattern = #"[\$@]\{"# + #"(\/[^\}]+)"# + #"\}"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return template }
        let ns = template as NSString
        let matches = regex.matches(in: template, range: NSRange(location: 0, length: ns.length))
        var result = template
        for match in matches.reversed() {
            guard match.numberOfRanges >= 2 else { continue }
            let pointer = ns.substring(with: match.range(at: 1))
            let replacement = dataModel.resolve(pointer: pointer)?.stringValue ?? ""
            if let range = Range(match.range, in: result) {
                result.replaceSubrange(range, with: replacement)
            }
        }
        return result
    }
}

// MARK: - Streaming JSONL / SSE Message Processor (Pure State Machine)

public struct A2uiMessageProcessor: Sendable {
    public private(set) var surfaces: [String: A2uiSurfaceState] = [:]
    public private(set) var orderedSurfaceIds: [String] = []

    public init() {}

    @discardableResult
    public mutating func ingestJsonLine(_ line: String) throws -> String? {
        let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        guard let data = trimmed.data(using: .utf8) else { return nil }
        let envelope = try JSONDecoder().decode(A2uiJsonValue.self, from: data)
        return ingest(envelope: envelope)
    }

    @discardableResult
    public mutating func ingest(envelope: A2uiJsonValue) -> String? {
        guard let obj = envelope.objectValue else { return nil }
        let detectedVersion = detectVersion(from: obj["version"]?.stringValue)

        // v0.9 / v1.0: createSurface (supports v1.0 single-message components + data)
        if let payload = obj["createSurface"]?.objectValue,
           let surfaceId = payload["surfaceId"]?.stringValue {
            let catalogId = payload["catalogId"]?.stringValue
                ?? payload["@catalogId"]?.stringValue
                ?? "https://a2ui.org/specification/v1_0/basic_catalog.json"
            let rootId = payload["root"]?.stringValue ?? "root"
            var surface = surfaces[surfaceId] ?? A2uiSurfaceState(
                surfaceId: surfaceId,
                catalogId: catalogId,
                version: detectedVersion,
                rootComponentId: rootId,
                isReady: true
            )
            surface.catalogId = catalogId
            surface.version = detectedVersion
            surface.rootComponentId = rootId
            if let inlineComponents = payload["components"]?.arrayValue {
                applyComponents(inlineComponents, to: &surface)
            }
            if let inlineData = payload["data"] ?? payload["dataModel"] {
                surface.dataModel = inlineData
            }
            upsertSurface(surface)
            return surfaceId
        }

        // v0.9 / v1.0: updateComponents (and v0.8 surfaceUpdate)
        if let payload = (obj["updateComponents"] ?? obj["surfaceUpdate"])?.objectValue,
           let surfaceId = payload["surfaceId"]?.stringValue {
            var surface = surfaces[surfaceId] ?? A2uiSurfaceState(surfaceId: surfaceId, version: detectedVersion)
            if let comps = payload["components"]?.arrayValue {
                applyComponents(comps, to: &surface)
            }
            upsertSurface(surface)
            return surfaceId
        }

        // v0.9 / v1.0: updateDataModel (and v0.8 dataModelUpdate)
        if let payload = (obj["updateDataModel"] ?? obj["dataModelUpdate"])?.objectValue,
           let surfaceId = payload["surfaceId"]?.stringValue {
            var surface = surfaces[surfaceId] ?? A2uiSurfaceState(surfaceId: surfaceId, version: detectedVersion)
            let path = payload["path"]?.stringValue ?? "/"
            let op = (payload["op"]?.stringValue ?? "replace").lowercased()
            if op == "remove" {
                surface.dataModel = surface.dataModel.removing(pointer: path)
            } else if let value = payload["value"] {
                surface.dataModel = surface.dataModel.setting(pointer: path, value: value)
            } else if let contents = payload["contents"]?.arrayValue {
                applyV08Contents(contents, basePath: path, to: &surface.dataModel)
            }
            upsertSurface(surface)
            return surfaceId
        }

        // v0.8: beginRendering
        if let payload = obj["beginRendering"]?.objectValue,
           let surfaceId = payload["surfaceId"]?.stringValue {
            var surface = surfaces[surfaceId] ?? A2uiSurfaceState(surfaceId: surfaceId, version: .v0_8)
            if let root = payload["root"]?.stringValue {
                surface.rootComponentId = root
            }
            if let cat = payload["catalogId"]?.stringValue {
                surface.catalogId = cat
            }
            surface.isReady = true
            upsertSurface(surface)
            return surfaceId
        }

        // v0.9 / v1.0 / v0.8: deleteSurface
        if let payload = obj["deleteSurface"]?.objectValue,
           let surfaceId = payload["surfaceId"]?.stringValue {
            surfaces.removeValue(forKey: surfaceId)
            orderedSurfaceIds.removeAll { $0 == surfaceId }
            return surfaceId
        }

        return nil
    }

    public mutating func mutateDataModel(surfaceId: String, pointer: String, value: A2uiJsonValue) {
        guard var surface = surfaces[surfaceId] else { return }
        surface.dataModel = surface.dataModel.setting(pointer: pointer, value: value)
        surfaces[surfaceId] = surface
    }

    private func detectVersion(from raw: String?) -> A2uiVersion {
        guard let raw else { return .v1_0 }
        if raw.hasPrefix("v0.8") || raw == "0.8" { return .v0_8 }
        if raw.hasPrefix("v0.9") || raw == "0.9" { return .v0_9 }
        return .v1_0
    }

    private mutating func upsertSurface(_ surface: A2uiSurfaceState) {
        if surfaces[surface.surfaceId] == nil {
            orderedSurfaceIds.append(surface.surfaceId)
        }
        surfaces[surface.surfaceId] = surface
    }

    private func applyComponents(_ rawList: [A2uiJsonValue], to surface: inout A2uiSurfaceState) {
        for item in rawList {
            guard let node = parseComponentNode(item) else { continue }
            surface.components[node.id] = node
            if surface.components[surface.rootComponentId] == nil, node.id == "root" {
                surface.rootComponentId = "root"
            }
        }
    }

    private func parseComponentNode(_ raw: A2uiJsonValue) -> A2uiComponentNode? {
        guard let dict = raw.objectValue, let id = dict["id"]?.stringValue else { return nil }
        let catalogId = dict["@catalogId"]?.stringValue ?? dict["catalogId"]?.stringValue

        // v0.9 / v1.0 flat discriminator: { "id": "...", "component": "Text", "text": ... }
        if let typeName = dict["component"]?.stringValue {
            var props = dict
            props.removeValue(forKey: "id")
            props.removeValue(forKey: "component")
            let childrenIds = extractChildrenIds(from: props["children"])
            let childId = props["child"]?.stringValue
            return A2uiComponentNode(
                id: id,
                type: typeName,
                properties: props,
                childrenIds: childrenIds,
                childId: childId,
                catalogId: catalogId
            )
        }

        // v0.8 wrapped discriminator: { "id": "...", "component": { "Text": { "text": ... } } }
        if let compObj = dict["component"]?.objectValue,
           let (typeName, innerVal) = compObj.first {
            let props = innerVal.objectValue ?? [:]
            let childrenIds = extractChildrenIds(from: props["children"])
            let childId = props["child"]?.stringValue
            return A2uiComponentNode(
                id: id,
                type: typeName,
                properties: props,
                childrenIds: childrenIds,
                childId: childId,
                catalogId: catalogId
            )
        }
        return nil
    }

    private func extractChildrenIds(from value: A2uiJsonValue?) -> [String] {
        guard let value else { return [] }
        if let arr = value.arrayValue {
            return arr.compactMap { $0.stringValue }
        }
        if let obj = value.objectValue, let explicit = obj["explicitList"]?.arrayValue {
            return explicit.compactMap { $0.stringValue }
        }
        return []
    }

    private func applyV08Contents(_ entries: [A2uiJsonValue], basePath: String, to model: inout A2uiJsonValue) {
        let prefix = basePath == "/" ? "" : (basePath.hasSuffix("/") ? String(basePath.dropLast()) : basePath)
        for entry in entries {
            guard let obj = entry.objectValue, let key = obj["key"]?.stringValue else { continue }
            let ptr = "\(prefix)/\(key)"
            if let s = obj["valueString"] {
                model = model.setting(pointer: ptr, value: s)
            } else if let n = obj["valueNumber"] {
                model = model.setting(pointer: ptr, value: n)
            } else if let b = obj["valueBoolean"] {
                model = model.setting(pointer: ptr, value: b)
            } else if let nested = obj["valueMap"]?.arrayValue {
                applyV08Contents(nested, basePath: ptr, to: &model)
            }
        }
    }
}

// MARK: - Observable MainActor Store for SwiftUI

@MainActor
public final class A2uiSurfaceStore: ObservableObject {
    @Published public private(set) var surfaces: [String: A2uiSurfaceState] = [:]
    @Published public private(set) var orderedSurfaceIds: [String] = []
    @Published public private(set) var lastError: String?
    @Published public private(set) var actionLog: [A2uiUserAction] = []

    public var onUserAction: ((A2uiUserAction) -> Void)?
    private var processor = A2uiMessageProcessor()

    public init(onUserAction: ((A2uiUserAction) -> Void)? = nil) {
        self.onUserAction = onUserAction
    }

    public func ingestJsonLine(_ line: String) {
        do {
            _ = try processor.ingestJsonLine(line)
            syncState()
            lastError = nil
        } catch {
            lastError = error.localizedDescription
        }
    }

    public func ingestJsonLines(_ payload: String) {
        for rawLine in payload.components(separatedBy: .newlines) {
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmed.isEmpty { continue }
            ingestJsonLine(trimmed)
        }
    }

    public func mutateDataModel(surfaceId: String, pointer: String, value: A2uiJsonValue) {
        processor.mutateDataModel(surfaceId: surfaceId, pointer: pointer, value: value)
        syncState()
    }

    public func dispatchUserAction(
        name: String,
        surfaceId: String,
        sourceComponentId: String,
        rawContext: [String: A2uiJsonValue] = [:]
    ) {
        let model = surfaces[surfaceId]?.dataModel ?? .object([:])
        var resolvedContext: [String: A2uiJsonValue] = [:]
        for (k, v) in rawContext {
            resolvedContext[k] = A2uiDynamicResolver.resolve(v, dataModel: model) ?? v
        }
        let action = A2uiUserAction(
            name: name,
            surfaceId: surfaceId,
            sourceComponentId: sourceComponentId,
            context: resolvedContext
        )
        actionLog.append(action)
        onUserAction?(action)
    }

    private func syncState() {
        surfaces = processor.surfaces
        orderedSurfaceIds = processor.orderedSurfaceIds
    }
}
