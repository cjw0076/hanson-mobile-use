package com.hanson.mobileuse.agent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Official Google A2UI (Agent-to-User Interface) protocol constants for Hanson Mobile-Use AI.
 *
 * Aligns with `https://a2ui.org/` and `https://github.com/a2ui-project/a2ui`:
 * - Primary wire standard: v1.0 & v0.9 (`createSurface`, `updateComponents`, `updateDataModel`, `deleteSurface`, `action`)
 * - Backward compatibility: v0.8 (`surfaceUpdate`, `dataModelUpdate`, `beginRendering`, `deleteSurface`, `userAction`)
 */
object A2uiProtocolConstants {
    const val VERSION_1_0 = "1.0"
    const val VERSION_0_9 = "v0.9"
    const val VERSION_0_8 = "v0.8"

    const val BASIC_CATALOG_V1_0 = "https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json"
    const val BASIC_CATALOG_V0_9 = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"
    const val STANDARD_CATALOG_V0_8 = "https://a2ui.org/specification/v0_8/standard_catalog_definition.json"

    const val MIME_TYPE = "application/a2ui+json"
    const val MIME_TYPE_JSONL = "application/a2ui+jsonl"

    val SUPPORTED_BASIC_COMPONENTS: Set<String> = setOf(
        "Column",
        "Row",
        "List",
        "Card",
        "Box",
        "Text",
        "Button",
        "TextField",
        "CheckBox",
        "MultipleChoice",
        "ChoicePicker",
        "Slider",
        "Divider",
        "Icon",
        "Image"
    )
}

/**
 * Single node in an A2UI surface's flat adjacency-list component registry.
 * Stores immutable Kotlin primitives/maps/lists for safe `StateFlow` equality and Compose recomposition.
 */
data class A2uiComponentNode(
    val id: String,
    val type: String,
    val weight: Float? = null,
    val catalogId: String? = null,
    val properties: Map<String, Any?> = emptyMap()
) {
    fun toJson(protocolVersion: String = A2uiProtocolConstants.VERSION_1_0): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        if (protocolVersion == A2uiProtocolConstants.VERSION_0_8) {
            if (weight != null) obj.put("weight", weight.toDouble())
            val inner = A2uiJsonCodec.mapToJsonObject(properties)
            obj.put("component", JSONObject().put(type, inner))
        } else {
            obj.put("component", type)
            if (weight != null) obj.put("weight", weight.toDouble())
            if (!catalogId.isNullOrBlank()) obj.put("@catalogId", catalogId)
            for ((k, v) in properties) {
                if (k != "id" && k != "component" && k != "weight" && k != "@catalogId") {
                    obj.put(k, A2uiJsonCodec.toOrgJsonValue(v))
                }
            }
        }
        return obj
    }
}

/**
 * Dynamic template child specification (`children: { componentId, path }` in v0.9/v1.0
 * or `children: { template: { componentId, dataBinding } }` in v0.8).
 */
data class A2uiTemplateChildren(
    val componentId: String,
    val path: String
)

/**
 * Parsed child references for layout containers (`Column`, `Row`, `List`, `Box`).
 */
data class A2uiChildrenSpec(
    val explicitIds: List<String> = emptyList(),
    val template: A2uiTemplateChildren? = null
)

/**
 * Parsed interactive action handler from a `Button` (or custom interactive component).
 */
data class A2uiActionSpec(
    val name: String,
    val contextBindings: Map<String, Any?> = emptyMap(),
    val isFunctionCall: Boolean = false
)

/**
 * Outbound A2UI user interaction event emitted when a user triggers an action on a surface.
 * Serializes to both canonical v0.9/v1.0 (`{ version, action }`) and v0.8 (`{ userAction }`) envelopes.
 */
data class A2uiUserAction(
    val name: String,
    val surfaceId: String,
    val sourceComponentId: String,
    val timestampIso: String = Instant.now().toString(),
    val context: Map<String, Any?> = emptyMap(),
    val protocolVersion: String = A2uiProtocolConstants.VERSION_1_0
) {
    fun toWireJson(useV08Envelope: Boolean = (protocolVersion == A2uiProtocolConstants.VERSION_0_8)): JSONObject {
        val payload = JSONObject().apply {
            put("name", name)
            put("surfaceId", surfaceId)
            put("sourceComponentId", sourceComponentId)
            put("timestamp", timestampIso)
            put("context", A2uiJsonCodec.mapToJsonObject(context))
        }
        return if (useV08Envelope) {
            JSONObject().put("userAction", payload)
        } else {
            val ver = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
                A2uiProtocolConstants.VERSION_0_9
            } else {
                A2uiProtocolConstants.VERSION_1_0
            }
            JSONObject()
                .put("version", ver)
                .put("action", payload)
        }
    }

    fun toHumanPromptSummary(): String {
        val ctxPreview = if (context.isEmpty()) {
            ""
        } else {
            context.entries.joinToString(", ") { (k, v) -> "$k=${v ?: ""}" }
        }
        return if (ctxPreview.isBlank()) {
            "A2UI action '$name' on surface '$surfaceId' (component '$sourceComponentId')"
        } else {
            "A2UI action '$name' on surface '$surfaceId' ($ctxPreview)"
        }
    }
}

/**
 * Immutable reactive state of a single A2UI UI surface (`surfaceId`).
 */
data class A2uiSurfaceState(
    val surfaceId: String,
    val rootComponentId: String = "root",
    val catalogId: String = A2uiProtocolConstants.BASIC_CATALOG_V1_0,
    val protocolVersion: String = A2uiProtocolConstants.VERSION_1_0,
    val sendDataModel: Boolean = false,
    val isReady: Boolean = true,
    val components: Map<String, A2uiComponentNode> = emptyMap(),
    val dataModel: Map<String, Any?> = emptyMap(),
    val metadata: Map<String, Any?> = emptyMap(),
    val revision: Long = 0L,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val hasRootComponent: Boolean
        get() = components.containsKey(rootComponentId)

    fun dataModelToJson(): JSONObject = A2uiJsonCodec.mapToJsonObject(dataModel)

    fun toSummaryJson(includeDataModel: Boolean = true, includeComponents: Boolean = false): JSONObject {
        return JSONObject().apply {
            put("surfaceId", surfaceId)
            put("rootComponentId", rootComponentId)
            put("catalogId", catalogId)
            put("protocolVersion", protocolVersion)
            put("sendDataModel", sendDataModel)
            put("isReady", isReady)
            put("hasRootComponent", hasRootComponent)
            put("componentCount", components.size)
            put("revision", revision)
            put("updatedAtMs", updatedAtMs)
            if (includeDataModel) {
                put("dataModel", dataModelToJson())
            }
            if (includeComponents) {
                val compArr = JSONArray()
                components.values.forEach { compArr.put(it.toJson(protocolVersion)) }
                put("components", compArr)
            }
        }
    }
}

/**
 * Result of processing a single A2UI message or JSONL line.
 */
sealed class A2uiIngestResult {
    data class Applied(
        val messageType: String,
        val surfaceId: String,
        val protocolVersion: String
    ) : A2uiIngestResult()

    data class Ignored(val reason: String) : A2uiIngestResult()

    data class Rejected(val reason: String) : A2uiIngestResult()
}

/**
 * Safe conversion between `org.json` objects and immutable Kotlin collections.
 */
object A2uiJsonCodec {

    fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        val iter = obj.keys()
        while (iter.hasNext()) {
            val key = iter.next()
            result[key] = fromOrgJsonValue(obj.opt(key))
        }
        return result
    }

    fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val result = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            result.add(fromOrgJsonValue(arr.opt(i)))
        }
        return result
    }

    fun fromOrgJsonValue(raw: Any?): Any? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(raw)
            is JSONArray -> jsonArrayToList(raw)
            is Boolean, is String, is Int, is Long, is Double, is Float -> raw
            is Number -> raw.toDouble()
            else -> raw.toString()
        }
    }

    fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in map) {
            obj.put(k, toOrgJsonValue(v))
        }
        return obj
    }

    fun listToJsonArray(list: Collection<Any?>): JSONArray {
        val arr = JSONArray()
        for (item in list) {
            arr.put(toOrgJsonValue(item))
        }
        return arr
    }

    fun toOrgJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray -> value
            is Map<*, *> -> {
                val obj = JSONObject()
                for ((k, v) in value) {
                    if (k != null) obj.put(k.toString(), toOrgJsonValue(v))
                }
                obj
            }
            is Collection<*> -> listToJsonArray(value)
            is Array<*> -> listToJsonArray(value.toList())
            is Boolean, is Int, is Long, is Double, is Float, is String -> value
            is Number -> value.toDouble()
            else -> value.toString()
        }
    }
}

/**
 * RFC 6901 JSON Pointer resolver and immutable mutator supporting:
 * - Root pointers (`""` and `"/"`)
 * - Escaped reference tokens (`~1` -> `/`, `~0` -> `~`)
 * - Nested `Map<String, Any?>` keys and `List<Any?>` numeric indices (including `"-"` append)
 * - Scoped relative pointers for dynamic template iteration (e.g. scope `/items/0` + `title` -> `/items/0/title`)
 */
object A2uiJsonPointer {

    fun resolvePointerPath(pointer: String, scopePath: String? = null): String {
        val trimmed = pointer.trim()
        if (trimmed.isEmpty() || trimmed == "/") {
            return if (!scopePath.isNullOrBlank() && trimmed.isEmpty()) scopePath else "/"
        }
        if (trimmed.startsWith("/")) {
            return trimmed
        }
        val cleanedRelative = trimmed.removePrefix("./")
        if (!scopePath.isNullOrBlank()) {
            val base = scopePath.trimEnd('/')
            return if (cleanedRelative.isEmpty()) base.ifEmpty { "/" } else "$base/$cleanedRelative"
        }
        return "/$cleanedRelative"
    }

    fun parseTokens(pointer: String, scopePath: String? = null): List<String> {
        val resolved = resolvePointerPath(pointer, scopePath)
        if (resolved == "/" || resolved.isEmpty()) return emptyList()
        val stripped = if (resolved.startsWith("/")) resolved.substring(1) else resolved
        if (stripped.isEmpty()) return emptyList()
        return stripped.split("/").map { unescapeToken(it) }
    }

    fun escapeToken(token: String): String = token.replace("~", "~0").replace("/", "~1")

    fun unescapeToken(token: String): String = token.replace("~1", "/").replace("~0", "~")

    fun resolve(root: Map<String, Any?>, pointer: String, scopePath: String? = null): Any? {
        val tokens = parseTokens(pointer, scopePath)
        if (tokens.isEmpty()) return root
        var current: Any? = root
        for (token in tokens) {
            current = when (current) {
                is Map<*, *> -> current[token]
                is List<*> -> {
                    val idx = token.toIntOrNull() ?: return null
                    if (idx in current.indices) current[idx] else null
                }
                else -> return null
            }
            if (current == null) return null
        }
        return current
    }

    @Suppress("UNCHECKED_CAST")
    fun set(
        root: Map<String, Any?>,
        pointer: String,
        value: Any?,
        scopePath: String? = null
    ): Map<String, Any?> {
        val tokens = parseTokens(pointer, scopePath)
        if (tokens.isEmpty()) {
            return when (value) {
                null -> emptyMap()
                is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
                else -> LinkedHashMap<String, Any?>().apply { put("value", value) }
            }
        }
        val updated = setRecursive(root, tokens, 0, value)
        return (updated as? Map<String, Any?>) ?: root
    }

    @Suppress("UNCHECKED_CAST")
    fun delete(
        root: Map<String, Any?>,
        pointer: String,
        scopePath: String? = null
    ): Map<String, Any?> {
        val tokens = parseTokens(pointer, scopePath)
        if (tokens.isEmpty()) return emptyMap()
        val updated = deleteRecursive(root, tokens, 0)
        return (updated as? Map<String, Any?>) ?: root
    }

    @Suppress("UNCHECKED_CAST")
    private fun setRecursive(current: Any?, tokens: List<String>, depth: Int, value: Any?): Any? {
        if (depth >= tokens.size) return value
        val key = tokens[depth]
        val isLast = depth == tokens.lastIndex

        if (current is List<*>) {
            val copy = ArrayList<Any?>(current)
            if (key == "-") {
                copy.add(if (isLast) value else setRecursive(emptyMap<String, Any?>(), tokens, depth + 1, value))
                return copy
            }
            val idx = key.toIntOrNull()
            if (idx != null && idx >= 0) {
                while (copy.size <= idx) copy.add(null)
                copy[idx] = if (isLast) value else setRecursive(copy[idx], tokens, depth + 1, value)
                return copy
            }
        }

        val mapCopy = LinkedHashMap<String, Any?>()
        if (current is Map<*, *>) {
            for ((k, v) in current) {
                if (k != null) mapCopy[k.toString()] = v
            }
        }
        mapCopy[key] = if (isLast) {
            value
        } else {
            val nextExisting = mapCopy[key]
            setRecursive(nextExisting, tokens, depth + 1, value)
        }
        return mapCopy
    }

    private fun deleteRecursive(current: Any?, tokens: List<String>, depth: Int): Any? {
        if (depth >= tokens.size) return current
        val key = tokens[depth]
        val isLast = depth == tokens.lastIndex

        return when (current) {
            is Map<*, *> -> {
                val mapCopy = LinkedHashMap<String, Any?>()
                for ((k, v) in current) {
                    if (k != null) mapCopy[k.toString()] = v
                }
                if (isLast) {
                    mapCopy.remove(key)
                } else if (mapCopy.containsKey(key)) {
                    mapCopy[key] = deleteRecursive(mapCopy[key], tokens, depth + 1)
                }
                mapCopy
            }
            is List<*> -> {
                val idx = key.toIntOrNull() ?: return current
                if (idx !in current.indices) return current
                val copy = ArrayList<Any?>(current)
                if (isLast) {
                    copy.removeAt(idx)
                } else {
                    copy[idx] = deleteRecursive(copy[idx], tokens, depth + 1)
                }
                copy
            }
            else -> current
        }
    }
}

/**
 * Resolves A2UI dynamic values across v1.0 (`@path`, `${/ptr}`), v0.9 (`path`, `${/ptr}`),
 * and v0.8 (`path`, `literalString`, `literalNumber`, `literalBoolean`, `literalArray`).
 */
object A2uiDataBindingResolver {

    private val INTERPOLATION_REGEX = Regex("""[$@]\{([^}]+)\}""")

    /** Extracts the RFC 6901 JSON Pointer if [raw] represents a bound path (`@path` or `path`). */
    fun extractBindingPath(raw: Any?): String? {
        val map = raw as? Map<*, *> ?: return null
        val v1Path = map["@path"] as? String
        if (!v1Path.isNullOrBlank()) return v1Path.trim()
        val v09Path = map["path"] as? String
        if (!v09Path.isNullOrBlank()) return v09Path.trim()
        return null
    }

    fun resolveValue(
        raw: Any?,
        dataModel: Map<String, Any?>,
        scopePath: String? = null
    ): Any? {
        return when (raw) {
            null -> null
            is Map<*, *> -> resolveMapDynamicValue(raw, dataModel, scopePath)
            is String -> interpolateString(raw, dataModel, scopePath)
            else -> raw
        }
    }

    private fun resolveMapDynamicValue(
        map: Map<*, *>,
        dataModel: Map<String, Any?>,
        scopePath: String?
    ): Any? {
        val path = extractBindingPath(map)
        if (path != null) {
            val resolved = A2uiJsonPointer.resolve(dataModel, path, scopePath)
            if (resolved != null) return resolved
            // v0.8 combined binding + literal fallback
            if (map.containsKey("literalString")) return map["literalString"]
            if (map.containsKey("literalNumber")) return map["literalNumber"]
            if (map.containsKey("literalBoolean")) return map["literalBoolean"]
            if (map.containsKey("literalArray")) return map["literalArray"]
            return null
        }

        if (map.containsKey("literalString")) return map["literalString"]
        if (map.containsKey("literalNumber")) return map["literalNumber"]
        if (map.containsKey("literalBoolean")) return map["literalBoolean"]
        if (map.containsKey("literalArray")) return map["literalArray"]

        // v0.9 / v1.0 client FunctionCall (e.g. formatString)
        val fnName = (map["call"] as? String)?.trim()
        if (!fnName.isNullOrEmpty()) {
            val args = (map["args"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
            return evaluateClientFunction(fnName, args, dataModel, scopePath)
        }

        return map
    }

    private fun evaluateClientFunction(
        fnName: String,
        args: Map<*, *>,
        dataModel: Map<String, Any?>,
        scopePath: String?
    ): Any? {
        return when (fnName) {
            "formatString" -> {
                val template = resolveString(args["value"], dataModel, scopePath)
                interpolateString(template, dataModel, scopePath)
            }
            "not" -> !resolveBoolean(args["value"], dataModel, scopePath, default = false)
            "required" -> {
                val v = resolveValue(args["value"], dataModel, scopePath)
                when (v) {
                    null -> false
                    is String -> v.isNotBlank()
                    is Collection<*> -> v.isNotEmpty()
                    else -> true
                }
            }
            else -> resolveValue(args["value"], dataModel, scopePath)
        }
    }

    fun interpolateString(
        template: String,
        dataModel: Map<String, Any?>,
        scopePath: String? = null
    ): String {
        if (!template.contains("\${") && !template.contains("@{")) return template
        return INTERPOLATION_REGEX.replace(template) { match ->
            val expr = match.groupValues[1].trim()
            val resolved = A2uiJsonPointer.resolve(dataModel, expr, scopePath)
            formatResolvedScalar(resolved)
        }
    }

    fun formatResolvedScalar(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            is Double -> {
                if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value <= Long.MAX_VALUE.toDouble()) {
                    value.toLong().toString()
                } else {
                    value.toString()
                }
            }
            is Float -> {
                if (value % 1.0f == 0.0f) value.toInt().toString() else value.toString()
            }
            is Collection<*> -> value.joinToString(", ") { formatResolvedScalar(it) }
            else -> value.toString()
        }
    }

    fun resolveString(
        raw: Any?,
        dataModel: Map<String, Any?>,
        scopePath: String? = null,
        default: String = ""
    ): String {
        val resolved = resolveValue(raw, dataModel, scopePath)
        return if (resolved == null) default else formatResolvedScalar(resolved)
    }

    fun resolveBoolean(
        raw: Any?,
        dataModel: Map<String, Any?>,
        scopePath: String? = null,
        default: Boolean = false
    ): Boolean {
        return when (val resolved = resolveValue(raw, dataModel, scopePath)) {
            is Boolean -> resolved
            is String -> resolved.equals("true", ignoreCase = true) || resolved == "1"
            is Number -> resolved.toDouble() != 0.0
            else -> default
        }
    }

    fun resolveDouble(
        raw: Any?,
        dataModel: Map<String, Any?>,
        scopePath: String? = null,
        default: Double = 0.0
    ): Double {
        return when (val resolved = resolveValue(raw, dataModel, scopePath)) {
            is Number -> resolved.toDouble()
            is String -> resolved.trim().toDoubleOrNull() ?: default
            else -> default
        }
    }

    fun resolveStringList(
        raw: Any?,
        dataModel: Map<String, Any?>,
        scopePath: String? = null
    ): List<String> {
        return when (val resolved = resolveValue(raw, dataModel, scopePath)) {
            is Collection<*> -> resolved.mapNotNull { item ->
                val str = formatResolvedScalar(item).trim()
                str.takeIf { it.isNotEmpty() }
            }
            is String -> resolved.trim().takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    fun resolveChildrenSpec(node: A2uiComponentNode): A2uiChildrenSpec {
        val raw = node.properties["children"]
        when (raw) {
            is List<*> -> {
                val ids = raw.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                return A2uiChildrenSpec(explicitIds = ids)
            }
            is Map<*, *> -> {
                // v0.9/v1.0 template object: { componentId, path }
                val tplComp = raw["componentId"]?.toString()?.trim().orEmpty()
                val tplPath = (raw["path"] ?: raw["@path"] ?: raw["dataBinding"])?.toString()?.trim().orEmpty()
                if (tplComp.isNotEmpty() && tplPath.isNotEmpty()) {
                    return A2uiChildrenSpec(template = A2uiTemplateChildren(tplComp, tplPath))
                }
                // v0.8 explicitList: { explicitList: [...] }
                val explicit = raw["explicitList"] as? List<*>
                if (explicit != null) {
                    val ids = explicit.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    return A2uiChildrenSpec(explicitIds = ids)
                }
                // v0.8 template wrapper: { template: { componentId, dataBinding } }
                val nestedTpl = raw["template"] as? Map<*, *>
                if (nestedTpl != null) {
                    val cId = nestedTpl["componentId"]?.toString()?.trim().orEmpty()
                    val dPath = (nestedTpl["dataBinding"] ?: nestedTpl["path"])?.toString()?.trim().orEmpty()
                    if (cId.isNotEmpty() && dPath.isNotEmpty()) {
                        return A2uiChildrenSpec(template = A2uiTemplateChildren(cId, dPath))
                    }
                }
            }
        }
        val singleChild = node.properties["child"]?.toString()?.trim()
        if (!singleChild.isNullOrEmpty()) {
            return A2uiChildrenSpec(explicitIds = listOf(singleChild))
        }
        return A2uiChildrenSpec()
    }

    fun parseActionSpec(rawAction: Any?): A2uiActionSpec? {
        return when (rawAction) {
            is String -> rawAction.trim().takeIf { it.isNotEmpty() }?.let { A2uiActionSpec(name = it) }
            is Map<*, *> -> {
                // v0.9 / v1.0: { event: { name, context } }
                val eventObj = rawAction["event"] as? Map<*, *>
                if (eventObj != null) {
                    val name = eventObj["name"]?.toString()?.trim().orEmpty()
                    if (name.isEmpty()) return null
                    val ctx = parseContextMap(eventObj["context"])
                    return A2uiActionSpec(name = name, contextBindings = ctx, isFunctionCall = false)
                }
                // v0.9 / v1.0: { functionCall: { call, args } }
                val fnObj = rawAction["functionCall"] as? Map<*, *>
                if (fnObj != null) {
                    val callName = fnObj["call"]?.toString()?.trim().orEmpty()
                    if (callName.isEmpty()) return null
                    val argsMap = parseContextMap(fnObj["args"])
                    return A2uiActionSpec(name = callName, contextBindings = argsMap, isFunctionCall = true)
                }
                // v0.8: { name, context: [ { key, value } ] } or { name, context: { ... } }
                val name = rawAction["name"]?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val ctx = parseContextMap(rawAction["context"])
                    return A2uiActionSpec(name = name, contextBindings = ctx, isFunctionCall = false)
                }
                null
            }
            else -> null
        }
    }

    private fun parseContextMap(rawContext: Any?): Map<String, Any?> {
        return when (rawContext) {
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in rawContext) {
                    if (k != null) out[k.toString()] = v
                }
                out
            }
            is List<*> -> {
                // v0.8 array of { key, value }
                val out = LinkedHashMap<String, Any?>()
                for (entry in rawContext) {
                    val m = entry as? Map<*, *> ?: continue
                    val key = m["key"]?.toString()?.trim().orEmpty()
                    if (key.isNotEmpty()) {
                        out[key] = m["value"]
                    }
                }
                out
            }
            else -> emptyMap()
        }
    }

    fun resolveActionContext(
        bindings: Map<String, Any?>,
        dataModel: Map<String, Any?>,
        scopePath: String? = null
    ): Map<String, Any?> {
        val resolved = LinkedHashMap<String, Any?>()
        for ((k, rawVal) in bindings) {
            resolved[k] = resolveValue(rawVal, dataModel, scopePath)
        }
        return resolved
    }
}

/**
 * Stateful registry and streaming JSONL / SSE message processor for Google A2UI surfaces.
 *
 * Thread-safe and shared across:
 * - In-app Jetpack Compose (`CommandCenterScreen` -> `A2uiSurfaceCard`)
 * - Out-of-app `SYSTEM_ALERT_WINDOW` Compose overlay (`DynamicCanvasOverlayService` -> `A2uiSurfaceCard`)
 * - Loopback HTTP bridge (`ArtemisCommandServer` `POST /a2ui/messages`, `GET /a2ui/surfaces`)
 * - Unified SSE stream bus (`DynamicUiEventBus`)
 */
object A2uiSurfaceRegistry {

    private val lock = Any()
    private val revisionCounter = AtomicLong(0L)

    private val _surfaces = MutableStateFlow<Map<String, A2uiSurfaceState>>(emptyMap())
    val surfaces: StateFlow<Map<String, A2uiSurfaceState>> = _surfaces.asStateFlow()

    private val _userActions = MutableSharedFlow<A2uiUserAction>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val userActions: SharedFlow<A2uiUserAction> = _userActions.asSharedFlow()

    private val _lastUserAction = MutableStateFlow<A2uiUserAction?>(null)
    val lastUserAction: StateFlow<A2uiUserAction?> = _lastUserAction.asStateFlow()

    @Volatile
    var onUserActionListener: ((A2uiUserAction) -> Unit)? = null

    /** Quick detector for routing A2UI JSON payloads arriving on shared SSE/HTTP channels. */
    fun looksLikeA2uiMessage(obj: JSONObject): Boolean {
        return obj.has("createSurface") ||
            obj.has("updateComponents") ||
            obj.has("updateDataModel") ||
            obj.has("deleteSurface") ||
            obj.has("surfaceUpdate") ||
            obj.has("dataModelUpdate") ||
            obj.has("beginRendering") ||
            obj.has("a2ui") ||
            (obj.has("messages") && obj.optJSONArray("messages")?.let { arr ->
                arr.length() > 0 && arr.optJSONObject(0)?.let(::looksLikeA2uiMessage) == true
            } == true)
    }

    /**
     * Ingests a single JSONL line or SSE `data:` line containing an A2UI message (v1.0, v0.9, or v0.8).
     * Also accepts a JSON array of A2UI messages on a single line.
     */
    fun ingestJsonLine(line: String): A2uiIngestResult {
        val cleaned = normalizeIncomingLine(line)
            ?: return A2uiIngestResult.Ignored("Blank, comment, or control line")

        if (cleaned.startsWith("[")) {
            val arr = runCatching { JSONArray(cleaned) }.getOrNull()
                ?: return A2uiIngestResult.Rejected("Malformed A2UI JSON array")
            var lastApplied: A2uiIngestResult.Applied? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val res = ingestJsonObject(item)
                if (res is A2uiIngestResult.Applied) lastApplied = res
            }
            return lastApplied ?: A2uiIngestResult.Ignored("Array contained no actionable A2UI messages")
        }

        val obj = runCatching { JSONObject(cleaned) }.getOrNull()
            ?: return A2uiIngestResult.Rejected("Malformed A2UI JSON object")
        return ingestJsonObject(obj)
    }

    /**
     * Ingests a multi-line JSONL stream, an SSE stream (`data: ...`), a JSON array,
     * or a wrapped `{"messages": [...]}` A2UI payload.
     */
    fun ingestJsonl(payload: String): List<A2uiIngestResult> {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return emptyList()

        val nonBlankLines = trimmed.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val hasSsePrefix = nonBlankLines.any { line ->
            line.startsWith("data:") ||
                line.startsWith("event:") ||
                line.startsWith("id:") ||
                line.startsWith("retry:") ||
                line.startsWith(":")
        }
        val isMultiLineJsonl = nonBlankLines.size > 1 && (
            hasSsePrefix ||
                (nonBlankLines[0].startsWith("{") && nonBlankLines[0].endsWith("}")) ||
                nonBlankLines.all { it.startsWith("{") }
            )

        if (isMultiLineJsonl) {
            val results = mutableListOf<A2uiIngestResult>()
            for (rawLine in trimmed.lineSequence()) {
                val res = ingestJsonLine(rawLine)
                if (res !is A2uiIngestResult.Ignored) {
                    results.add(res)
                }
            }
            return results
        }

        // Support single JSON array payload (`[ {...}, {...} ]`)
        if (trimmed.startsWith("[")) {
            val arr = runCatching { JSONArray(trimmed) }.getOrNull()
            if (arr != null) {
                val results = ArrayList<A2uiIngestResult>(arr.length())
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i)
                    if (item != null) {
                        results.add(ingestJsonObject(item))
                    } else {
                        results.add(A2uiIngestResult.Rejected("Non-object item at index $i"))
                    }
                }
                return results
            }
        }

        // Support single pretty-printed or compact JSON object or wrapper `{ "messages": [...] }`
        if (trimmed.startsWith("{")) {
            val wholeObj = runCatching { JSONObject(trimmed) }.getOrNull()
            if (wholeObj != null) {
                val batch = wholeObj.optJSONArray("messages")
                if (batch != null && !wholeObj.has("createSurface") && !wholeObj.has("updateComponents")) {
                    val results = ArrayList<A2uiIngestResult>(batch.length())
                    for (i in 0 until batch.length()) {
                        val item = batch.optJSONObject(i)
                        if (item != null) results.add(ingestJsonObject(item))
                    }
                    return results
                }
                return listOf(ingestJsonObject(wholeObj))
            }
        }

        // Fallback line-by-line JSONL / SSE parsing
        val results = mutableListOf<A2uiIngestResult>()
        for (rawLine in trimmed.lineSequence()) {
            val res = ingestJsonLine(rawLine)
            if (res !is A2uiIngestResult.Ignored) {
                results.add(res)
            }
        }
        return results
    }

    /**
     * Processes one parsed A2UI JSON message object across v1.0, v0.9, and v0.8 schemas.
     */
    fun ingestJsonObject(rawObj: JSONObject): A2uiIngestResult {
        // Unwrap envelope if nested under `a2ui` or `message`
        val obj = rawObj.optJSONObject("a2ui")
            ?: rawObj.optJSONObject("message")?.takeIf { looksLikeA2uiMessage(it) }
            ?: rawObj

        // Support wrapper { "messages": [ ... ] }
        val wrappedList = obj.optJSONArray("messages")
        if (wrappedList != null && !obj.has("createSurface") && !obj.has("updateComponents")) {
            var lastApplied: A2uiIngestResult.Applied? = null
            for (i in 0 until wrappedList.length()) {
                val item = wrappedList.optJSONObject(i) ?: continue
                val r = ingestJsonObject(item)
                if (r is A2uiIngestResult.Applied) lastApplied = r
            }
            return lastApplied ?: A2uiIngestResult.Ignored("Empty messages wrapper")
        }

        val declaredVersion = normalizeProtocolVersion(obj.optString("version", ""))

        return when {
            obj.has("createSurface") -> handleCreateSurface(obj.optJSONObject("createSurface"), declaredVersion)
            obj.has("updateComponents") -> handleUpdateComponents(obj.optJSONObject("updateComponents"), declaredVersion)
            obj.has("updateDataModel") -> handleUpdateDataModel(obj.optJSONObject("updateDataModel"), declaredVersion)
            obj.has("deleteSurface") -> handleDeleteSurface(obj.optJSONObject("deleteSurface"), declaredVersion)
            // A2UI v0.8 compatibility messages
            obj.has("surfaceUpdate") -> handleV08SurfaceUpdate(obj.optJSONObject("surfaceUpdate"))
            obj.has("dataModelUpdate") -> handleV08DataModelUpdate(obj.optJSONObject("dataModelUpdate"))
            obj.has("beginRendering") -> handleV08BeginRendering(obj.optJSONObject("beginRendering"))
            else -> A2uiIngestResult.Ignored("Unrecognized A2UI envelope keys")
        }
    }

    /**
     * Mutates a surface's data model at an RFC 6901 JSON Pointer (used by two-way interactive inputs
     * such as `TextField`, `CheckBox`, `MultipleChoice` / `ChoicePicker`, and `Slider`).
     */
    fun updateDataModelAtPath(
        surfaceId: String,
        pointer: String,
        value: Any?,
        scopePath: String? = null
    ): Boolean {
        if (surfaceId.isBlank()) return false
        synchronized(lock) {
            val current = _surfaces.value[surfaceId] ?: return false
            val nextModel = if (value == null) {
                A2uiJsonPointer.delete(current.dataModel, pointer, scopePath)
            } else {
                A2uiJsonPointer.set(current.dataModel, pointer, value, scopePath)
            }
            val nextSurface = current.copy(
                dataModel = nextModel,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(nextSurface)
            return true
        }
    }

    /**
     * Resolves an action's context bindings against the surface's live data model and dispatches
     * an [A2uiUserAction] event to listeners, [userActions] flow, and [DynamicUiEventBus].
     */
    fun dispatchUserAction(
        surfaceId: String,
        sourceComponentId: String,
        actionSpec: A2uiActionSpec,
        scopePath: String? = null
    ): A2uiUserAction? {
        val surface = _surfaces.value[surfaceId] ?: return null
        val resolvedContext = A2uiDataBindingResolver.resolveActionContext(
            bindings = actionSpec.contextBindings,
            dataModel = surface.dataModel,
            scopePath = scopePath
        )
        val userAction = A2uiUserAction(
            name = actionSpec.name,
            surfaceId = surfaceId,
            sourceComponentId = sourceComponentId,
            timestampIso = Instant.now().toString(),
            context = resolvedContext,
            protocolVersion = surface.protocolVersion
        )
        _lastUserAction.value = userAction
        _userActions.tryEmit(userAction)
        runCatching { onUserActionListener?.invoke(userAction) }
        runCatching { DynamicUiEventBus.publishA2uiUserAction(userAction) }
        return userAction
    }

    fun deleteSurface(surfaceId: String): Boolean {
        if (surfaceId.isBlank()) return false
        synchronized(lock) {
            if (!_surfaces.value.containsKey(surfaceId)) return false
            val next = LinkedHashMap(_surfaces.value)
            next.remove(surfaceId)
            _surfaces.value = next
            return true
        }
    }

    fun getSurface(surfaceId: String): A2uiSurfaceState? = _surfaces.value[surfaceId]

    fun activeSurfacesOrdered(): List<A2uiSurfaceState> = _surfaces.value.values.toList()

    fun surfacesSummaryJson(includeComponents: Boolean = false): JSONObject {
        val list = activeSurfacesOrdered()
        val arr = JSONArray()
        list.forEach { arr.put(it.toSummaryJson(includeDataModel = true, includeComponents = includeComponents)) }
        return JSONObject().apply {
            put("status", "ok")
            put("protocol", "A2UI")
            put("supportedVersions", JSONArray().put("1.0").put("v0.9").put("v0.8"))
            put("basicCatalogId", A2uiProtocolConstants.BASIC_CATALOG_V1_0)
            put("surfaceCount", list.size)
            put("surfaces", arr)
        }
    }

    /** Clears all active surfaces and resets state (for tests or user-initiated clear). */
    fun reset() {
        synchronized(lock) {
            _surfaces.value = emptyMap()
            _lastUserAction.value = null
            onUserActionListener = null
        }
    }

    // -------------------------------------------------------------------------
    // Internal Protocol Handlers (v1.0, v0.9, v0.8)
    // -------------------------------------------------------------------------

    private fun handleCreateSurface(payload: JSONObject?, version: String): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("createSurface payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("createSurface missing surfaceId")

        val effectiveVersion = version.ifBlank { A2uiProtocolConstants.VERSION_1_0 }
        val defaultCatalog = if (effectiveVersion == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.BASIC_CATALOG_V0_9
        } else {
            A2uiProtocolConstants.BASIC_CATALOG_V1_0
        }
        val catalogId = payload.optString("catalogId", defaultCatalog).trim().ifBlank { defaultCatalog }
        val sendDataModel = payload.optBoolean("sendDataModel", false)
        val metadata = payload.optJSONObject("metadata")?.let(A2uiJsonCodec::jsonObjectToMap) ?: emptyMap()

        // v1.0 allows inline initial `components` and `dataModel` directly inside createSurface
        val initialComponents = LinkedHashMap<String, A2uiComponentNode>()
        payload.optJSONArray("components")?.let { compArr ->
            parseComponentsArray(compArr).forEach { node -> initialComponents[node.id] = node }
        }
        val initialDataModel = payload.optJSONObject("dataModel")
            ?.let(A2uiJsonCodec::jsonObjectToMap)
            ?: emptyMap()

        synchronized(lock) {
            val surface = A2uiSurfaceState(
                surfaceId = surfaceId,
                rootComponentId = "root",
                catalogId = catalogId,
                protocolVersion = effectiveVersion,
                sendDataModel = sendDataModel,
                isReady = true,
                components = initialComponents,
                dataModel = initialDataModel,
                metadata = metadata,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(surface)
        }
        return A2uiIngestResult.Applied("createSurface", surfaceId, effectiveVersion)
    }

    private fun handleUpdateComponents(payload: JSONObject?, version: String): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("updateComponents payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("updateComponents missing surfaceId")
        val compArr = payload.optJSONArray("components")
            ?: return A2uiIngestResult.Rejected("updateComponents missing components array")

        val parsedNodes = parseComponentsArray(compArr)
        if (parsedNodes.isEmpty()) {
            return A2uiIngestResult.Rejected("updateComponents contained zero valid components")
        }

        val effectiveVersion = version.ifBlank { A2uiProtocolConstants.VERSION_1_0 }
        synchronized(lock) {
            val existing = _surfaces.value[surfaceId] ?: A2uiSurfaceState(
                surfaceId = surfaceId,
                rootComponentId = "root",
                catalogId = if (effectiveVersion == A2uiProtocolConstants.VERSION_0_9) {
                    A2uiProtocolConstants.BASIC_CATALOG_V0_9
                } else {
                    A2uiProtocolConstants.BASIC_CATALOG_V1_0
                },
                protocolVersion = effectiveVersion,
                isReady = true
            )
            val merged = LinkedHashMap(existing.components)
            for (node in parsedNodes) {
                merged[node.id] = node
            }
            val updated = existing.copy(
                protocolVersion = if (version.isNotBlank()) effectiveVersion else existing.protocolVersion,
                components = merged,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(updated)
        }
        return A2uiIngestResult.Applied("updateComponents", surfaceId, effectiveVersion)
    }

    private fun handleUpdateDataModel(payload: JSONObject?, version: String): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("updateDataModel payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("updateDataModel missing surfaceId")

        val path = payload.optString("path", "/").trim().ifEmpty { "/" }
        val isDelete = !payload.has("value") || payload.isNull("value")
        val decodedValue = if (isDelete) null else A2uiJsonCodec.fromOrgJsonValue(payload.opt("value"))
        val effectiveVersion = version.ifBlank { A2uiProtocolConstants.VERSION_1_0 }

        synchronized(lock) {
            val existing = _surfaces.value[surfaceId] ?: A2uiSurfaceState(
                surfaceId = surfaceId,
                protocolVersion = effectiveVersion,
                isReady = true
            )
            val nextModel = if (isDelete) {
                A2uiJsonPointer.delete(existing.dataModel, path)
            } else {
                A2uiJsonPointer.set(existing.dataModel, path, decodedValue)
            }
            val updated = existing.copy(
                dataModel = nextModel,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(updated)
        }
        return A2uiIngestResult.Applied("updateDataModel", surfaceId, effectiveVersion)
    }

    private fun handleDeleteSurface(payload: JSONObject?, version: String): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("deleteSurface payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("deleteSurface missing surfaceId")
        val effectiveVersion = version.ifBlank { A2uiProtocolConstants.VERSION_1_0 }
        deleteSurface(surfaceId)
        return A2uiIngestResult.Applied("deleteSurface", surfaceId, effectiveVersion)
    }

    private fun handleV08SurfaceUpdate(payload: JSONObject?): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("surfaceUpdate payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("surfaceUpdate missing surfaceId")
        val compArr = payload.optJSONArray("components")
            ?: return A2uiIngestResult.Rejected("surfaceUpdate missing components array")

        val parsedNodes = parseComponentsArray(compArr)
        if (parsedNodes.isEmpty()) {
            return A2uiIngestResult.Rejected("surfaceUpdate contained zero valid components")
        }

        synchronized(lock) {
            val existing = _surfaces.value[surfaceId] ?: A2uiSurfaceState(
                surfaceId = surfaceId,
                rootComponentId = "root",
                catalogId = A2uiProtocolConstants.STANDARD_CATALOG_V0_8,
                protocolVersion = A2uiProtocolConstants.VERSION_0_8,
                isReady = false
            )
            val merged = LinkedHashMap(existing.components)
            for (node in parsedNodes) {
                merged[node.id] = node
            }
            val updated = existing.copy(
                protocolVersion = A2uiProtocolConstants.VERSION_0_8,
                components = merged,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(updated)
        }
        return A2uiIngestResult.Applied("surfaceUpdate", surfaceId, A2uiProtocolConstants.VERSION_0_8)
    }

    private fun handleV08DataModelUpdate(payload: JSONObject?): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("dataModelUpdate payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        if (surfaceId.isEmpty()) return A2uiIngestResult.Rejected("dataModelUpdate missing surfaceId")
        val contentsArr = payload.optJSONArray("contents")
            ?: return A2uiIngestResult.Rejected("dataModelUpdate missing contents array")

        val path = payload.optString("path", "/").trim().ifEmpty { "/" }
        val decodedMap = parseV08ContentsArray(contentsArr)

        synchronized(lock) {
            val existing = _surfaces.value[surfaceId] ?: A2uiSurfaceState(
                surfaceId = surfaceId,
                catalogId = A2uiProtocolConstants.STANDARD_CATALOG_V0_8,
                protocolVersion = A2uiProtocolConstants.VERSION_0_8,
                isReady = false
            )
            val nextModel = if (path == "/" || path.isEmpty()) {
                val mergedRoot = LinkedHashMap(existing.dataModel)
                mergedRoot.putAll(decodedMap)
                mergedRoot
            } else {
                A2uiJsonPointer.set(existing.dataModel, path, decodedMap)
            }
            val updated = existing.copy(
                dataModel = nextModel,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(updated)
        }
        return A2uiIngestResult.Applied("dataModelUpdate", surfaceId, A2uiProtocolConstants.VERSION_0_8)
    }

    private fun handleV08BeginRendering(payload: JSONObject?): A2uiIngestResult {
        if (payload == null) return A2uiIngestResult.Rejected("beginRendering payload must be a JSON object")
        val surfaceId = payload.optString("surfaceId", "").trim()
        val rootId = payload.optString("root", "").trim()
        if (surfaceId.isEmpty() || rootId.isEmpty()) {
            return A2uiIngestResult.Rejected("beginRendering requires surfaceId and root")
        }
        val catalogId = payload.optString("catalogId", A2uiProtocolConstants.STANDARD_CATALOG_V0_8)
            .trim()
            .ifBlank { A2uiProtocolConstants.STANDARD_CATALOG_V0_8 }

        synchronized(lock) {
            val existing = _surfaces.value[surfaceId] ?: A2uiSurfaceState(
                surfaceId = surfaceId,
                rootComponentId = rootId,
                catalogId = catalogId,
                protocolVersion = A2uiProtocolConstants.VERSION_0_8,
                isReady = true
            )
            val updated = existing.copy(
                rootComponentId = rootId,
                catalogId = catalogId,
                protocolVersion = A2uiProtocolConstants.VERSION_0_8,
                isReady = true,
                revision = revisionCounter.incrementAndGet(),
                updatedAtMs = System.currentTimeMillis()
            )
            putSurfaceLocked(updated)
        }
        return A2uiIngestResult.Applied("beginRendering", surfaceId, A2uiProtocolConstants.VERSION_0_8)
    }

    private fun parseComponentsArray(arr: JSONArray): List<A2uiComponentNode> {
        val nodes = ArrayList<A2uiComponentNode>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            parseComponentNode(item)?.let { nodes.add(it) }
        }
        return nodes
    }

    private fun parseComponentNode(obj: JSONObject): A2uiComponentNode? {
        val id = obj.optString("id", "").trim()
        if (id.isEmpty()) return null
        val weight = if (obj.has("weight") && !obj.isNull("weight")) {
            obj.optDouble("weight", Double.NaN).takeIf { !it.isNaN() && it > 0.0 }?.toFloat()
        } else {
            null
        }
        val catalogId = obj.optString("@catalogId", "").trim().ifBlank { null }

        val rawComp = obj.opt("component")
        // v0.9 / v1.0 flat discriminator: { "id": "...", "component": "Text", "text": "..." }
        if (rawComp is String && rawComp.isNotBlank()) {
            val props = LinkedHashMap<String, Any?>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == "id" || k == "component" || k == "weight" || k == "@catalogId") continue
                props[k] = A2uiJsonCodec.fromOrgJsonValue(obj.opt(k))
            }
            return A2uiComponentNode(
                id = id,
                type = canonicalizeComponentType(rawComp),
                weight = weight,
                catalogId = catalogId,
                properties = props
            )
        }

        // v0.8 nested wrapper: { "id": "...", "component": { "Text": { "text": { "literalString": "..." } } } }
        if (rawComp is JSONObject) {
            val keys = rawComp.keys()
            if (!keys.hasNext()) return null
            val typeName = keys.next()
            val innerObj = rawComp.optJSONObject(typeName) ?: JSONObject()
            val props = A2uiJsonCodec.jsonObjectToMap(innerObj)
            return A2uiComponentNode(
                id = id,
                type = canonicalizeComponentType(typeName),
                weight = weight,
                catalogId = catalogId,
                properties = props
            )
        }

        return null
    }

    private fun parseV08ContentsArray(arr: JSONArray): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val key = entry.optString("key", "").trim()
            if (key.isEmpty()) continue
            val value: Any? = when {
                entry.has("valueString") -> entry.optString("valueString", "")
                entry.has("valueNumber") -> entry.optDouble("valueNumber", 0.0)
                entry.has("valueBoolean") -> entry.optBoolean("valueBoolean", false)
                entry.has("valueMap") -> {
                    val nested = entry.optJSONArray("valueMap")
                    if (nested != null) parseV08ContentsArray(nested) else emptyMap<String, Any?>()
                }
                entry.has("valueArray") -> {
                    val nestedArr = entry.optJSONArray("valueArray")
                    if (nestedArr != null) A2uiJsonCodec.jsonArrayToList(nestedArr) else emptyList<Any?>()
                }
                else -> null
            }
            out[key] = value
        }
        return out
    }

    private fun canonicalizeComponentType(rawType: String): String {
        val t = rawType.trim()
        return when (t.lowercase()) {
            "column" -> "Column"
            "row" -> "Row"
            "list" -> "List"
            "card" -> "Card"
            "box", "container" -> "Box"
            "text", "heading" -> "Text"
            "button" -> "Button"
            "textfield" -> "TextField"
            "checkbox" -> "CheckBox"
            "multiplechoice" -> "MultipleChoice"
            "choicepicker" -> "ChoicePicker"
            "slider" -> "Slider"
            "divider" -> "Divider"
            "icon" -> "Icon"
            "image" -> "Image"
            else -> t
        }
    }

    private fun normalizeProtocolVersion(raw: String): String {
        return when (raw.trim().lowercase()) {
            "1.0", "v1.0", "v1" -> A2uiProtocolConstants.VERSION_1_0
            "0.9", "v0.9", "0.9.1", "v0.9.1" -> A2uiProtocolConstants.VERSION_0_9
            "0.8", "v0.8" -> A2uiProtocolConstants.VERSION_0_8
            else -> ""
        }
    }

    private fun normalizeIncomingLine(rawLine: String): String? {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith(":")) return null
        if (trimmed.startsWith("event:") || trimmed.startsWith("id:") || trimmed.startsWith("retry:")) return null
        val afterData = if (trimmed.startsWith("data:")) {
            trimmed.substring(5).trim()
        } else {
            trimmed
        }
        if (afterData.isEmpty() || afterData == "[DONE]") return null
        return afterData
    }

    private fun putSurfaceLocked(surface: A2uiSurfaceState) {
        val next = LinkedHashMap(_surfaces.value)
        next[surface.surfaceId] = surface
        _surfaces.value = next
    }
}

/**
 * Converts Hanson Mobile-Use AI's dual-channel [BoardEventScript] into canonical Google A2UI
 * v0.9 / v1.0 messages (`createSurface`, `updateComponents`, `updateDataModel`) using strictly
 * Basic catalog components and RFC 6901 JSON Pointer data bindings.
 */
object BoardToA2uiBridge {

    fun fromBoardScript(
        script: BoardEventScript,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0,
        surfaceIdOverride: String? = null
    ): List<JSONObject> {
        val version = if (protocolVersion == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.VERSION_0_9
        } else {
            A2uiProtocolConstants.VERSION_1_0
        }
        val catalogId = if (version == A2uiProtocolConstants.VERSION_0_9) {
            A2uiProtocolConstants.BASIC_CATALOG_V0_9
        } else {
            A2uiProtocolConstants.BASIC_CATALOG_V1_0
        }
        val pathKey = if (version == A2uiProtocolConstants.VERSION_0_9) "path" else "@path"
        val surfaceId = surfaceIdOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: "board_${script.spaceId}_${script.turnIndex}"

        fun ptr(path: String): JSONObject = JSONObject().put(pathKey, path)

        val components = JSONArray()
        val rootChildren = JSONArray()
        val boardData = JSONObject()

        val headlineCmd = script.events.mapNotNull { it.command as? BoardEventCommand.WriteHeadline }.firstOrNull()
        val statCmds = script.events.mapNotNull { it.command as? BoardEventCommand.AddStatPill }
        val takeawayCmds = script.events.mapNotNull { it.command as? BoardEventCommand.AddTakeaway }
        val noteCmds = script.events.mapNotNull { it.command as? BoardEventCommand.WriteNote }
        val citationCmds = script.events.mapNotNull { it.command as? BoardEventCommand.AddCitation }

        // 1. Headline block
        if (headlineCmd != null) {
            boardData.put("eyebrow", headlineCmd.eyebrow)
            boardData.put("headline", headlineCmd.headline)
            if (headlineCmd.eyebrow.isNotBlank()) {
                rootChildren.put("board_eyebrow")
                components.put(
                    JSONObject()
                        .put("id", "board_eyebrow")
                        .put("component", "Text")
                        .put("text", ptr("/board/eyebrow"))
                        .put("variant", "caption")
                )
            }
            rootChildren.put("board_headline")
            components.put(
                JSONObject()
                    .put("id", "board_headline")
                    .put("component", "Text")
                    .put("text", ptr("/board/headline"))
                    .put("variant", "h2")
            )
        }

        // 2. Stat pills row
        if (statCmds.isNotEmpty()) {
            val statsData = JSONArray()
            val statRowChildren = JSONArray()
            statCmds.forEachIndexed { idx, stat ->
                statsData.put(JSONObject().put("label", stat.label).put("value", stat.value))
                val cardId = "stat_card_$idx"
                val colId = "stat_col_$idx"
                val lblId = "stat_lbl_$idx"
                val valId = "stat_val_$idx"
                statRowChildren.put(cardId)

                components.put(
                    JSONObject()
                        .put("id", cardId)
                        .put("component", "Card")
                        .put("weight", 1)
                        .put("child", colId)
                )
                components.put(
                    JSONObject()
                        .put("id", colId)
                        .put("component", "Column")
                        .put("children", JSONArray().put(lblId).put(valId))
                )
                components.put(
                    JSONObject()
                        .put("id", lblId)
                        .put("component", "Text")
                        .put("text", ptr("/board/stats/$idx/label"))
                        .put("variant", "caption")
                )
                components.put(
                    JSONObject()
                        .put("id", valId)
                        .put("component", "Text")
                        .put("text", ptr("/board/stats/$idx/value"))
                        .put("variant", "h3")
                )
            }
            boardData.put("stats", statsData)
            rootChildren.put("board_stats_row")
            components.put(
                JSONObject()
                    .put("id", "board_stats_row")
                    .put("component", "Row")
                    .put("justify", "spaceBetween")
                    .put("children", statRowChildren)
            )
        }

        // 3. Key Takeaways
        if (takeawayCmds.isNotEmpty()) {
            val takeawaysData = JSONArray()
            takeawayCmds.forEachIndexed { idx, item ->
                val title = if (item.keyPrefix.isNotBlank()) "${item.index}. ${item.keyPrefix}" else "핵심 ${item.index}"
                takeawaysData.put(
                    JSONObject()
                        .put("title", title)
                        .put("body", item.body)
                )
                val cardId = "takeaway_card_$idx"
                val colId = "takeaway_col_$idx"
                val titleId = "takeaway_title_$idx"
                val bodyId = "takeaway_body_$idx"
                rootChildren.put(cardId)

                components.put(
                    JSONObject()
                        .put("id", cardId)
                        .put("component", "Card")
                        .put("child", colId)
                )
                components.put(
                    JSONObject()
                        .put("id", colId)
                        .put("component", "Column")
                        .put("children", JSONArray().put(titleId).put(bodyId))
                )
                components.put(
                    JSONObject()
                        .put("id", titleId)
                        .put("component", "Text")
                        .put("text", ptr("/board/takeaways/$idx/title"))
                        .put("variant", "h4")
                )
                components.put(
                    JSONObject()
                        .put("id", bodyId)
                        .put("component", "Text")
                        .put("text", ptr("/board/takeaways/$idx/body"))
                        .put("variant", "body")
                )
            }
            boardData.put("takeaways", takeawaysData)
        }

        // 4. Body notes
        if (noteCmds.isNotEmpty()) {
            val notesData = JSONArray()
            noteCmds.forEachIndexed { idx, note ->
                notesData.put(note.text)
                val noteId = "board_note_$idx"
                rootChildren.put(noteId)
                components.put(
                    JSONObject()
                        .put("id", noteId)
                        .put("component", "Text")
                        .put("text", ptr("/board/notes/$idx"))
                        .put("variant", "body")
                )
            }
            boardData.put("notes", notesData)
        }

        // 5. Verified citations as interactive A2UI Buttons
        if (citationCmds.isNotEmpty()) {
            val citationsData = JSONArray()
            val citRowChildren = JSONArray()
            citationCmds.forEachIndexed { idx, cit ->
                citationsData.put(
                    JSONObject()
                        .put("title", cit.title)
                        .put("url", cit.verifiedUrl)
                )
                val btnId = "citation_btn_$idx"
                val btnTxtId = "citation_txt_$idx"
                citRowChildren.put(btnId)

                components.put(
                    JSONObject()
                        .put("id", btnTxtId)
                        .put("component", "Text")
                        .put("text", ptr("/board/citations/$idx/title"))
                        .put("variant", "caption")
                )
                components.put(
                    JSONObject()
                        .put("id", btnId)
                        .put("component", "Button")
                        .put("variant", "default")
                        .put("child", btnTxtId)
                        .put(
                            "action",
                            JSONObject().put(
                                "event",
                                JSONObject()
                                    .put("name", "open_citation")
                                    .put(
                                        "context",
                                        JSONObject()
                                            .put("url", ptr("/board/citations/$idx/url"))
                                            .put("title", ptr("/board/citations/$idx/title"))
                                    )
                            )
                        )
                )
            }
            boardData.put("citations", citationsData)
            rootChildren.put("board_citations_row")
            components.put(
                JSONObject()
                    .put("id", "board_citations_row")
                    .put("component", "Row")
                    .put("children", citRowChildren)
            )
        }

        // 6. Voice caption footer + Speak action button
        if (script.combinedSpokenText.isNotBlank()) {
            boardData.put("spokenText", script.combinedSpokenText)
            rootChildren.put("board_voice_divider")
            rootChildren.put("board_voice_row")
            components.put(
                JSONObject()
                    .put("id", "board_voice_divider")
                    .put("component", "Divider")
                    .put("axis", "horizontal")
            )
            components.put(
                JSONObject()
                    .put("id", "board_voice_icon")
                    .put("component", "Icon")
                    .put("name", "volumeUp")
            )
            components.put(
                JSONObject()
                    .put("id", "board_voice_caption")
                    .put("component", "Text")
                    .put("weight", 1)
                    .put("text", ptr("/board/spokenText"))
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "board_speak_label")
                    .put("component", "Text")
                    .put("text", "듣기")
                    .put("variant", "caption")
            )
            components.put(
                JSONObject()
                    .put("id", "board_speak_btn")
                    .put("component", "Button")
                    .put("variant", "primary")
                    .put("child", "board_speak_label")
                    .put(
                        "action",
                        JSONObject().put(
                            "event",
                            JSONObject()
                                .put("name", "speak_board")
                                .put("context", JSONObject().put("text", ptr("/board/spokenText")))
                        )
                    )
            )
            components.put(
                JSONObject()
                    .put("id", "board_voice_row")
                    .put("component", "Row")
                    .put("align", "center")
                    .put("children", JSONArray().put("board_voice_icon").put("board_voice_caption").put("board_speak_btn"))
            )
        }

        // Fallback body if script had no visual events
        if (rootChildren.length() == 0) {
            boardData.put("fallbackBody", script.visualBodyText.ifBlank { "보드 준비 완료" })
            rootChildren.put("board_fallback_text")
            components.put(
                JSONObject()
                    .put("id", "board_fallback_text")
                    .put("component", "Text")
                    .put("text", ptr("/board/fallbackBody"))
                    .put("variant", "body")
            )
        }

        // Root Column component
        val allComponents = JSONArray().apply {
            put(
                JSONObject()
                    .put("id", "root")
                    .put("component", "Column")
                    .put("children", rootChildren)
            )
            for (i in 0 until components.length()) {
                put(components.get(i))
            }
        }

        val createSurfaceMsg = JSONObject()
            .put("version", version)
            .put(
                "createSurface",
                JSONObject()
                    .put("surfaceId", surfaceId)
                    .put("catalogId", catalogId)
                    .put("sendDataModel", false)
            )

        val updateComponentsMsg = JSONObject()
            .put("version", version)
            .put(
                "updateComponents",
                JSONObject()
                    .put("surfaceId", surfaceId)
                    .put("components", allComponents)
            )

        val updateDataModelMsg = JSONObject()
            .put("version", version)
            .put(
                "updateDataModel",
                JSONObject()
                    .put("surfaceId", surfaceId)
                    .put("path", "/board")
                    .put("value", boardData)
            )

        return listOf(createSurfaceMsg, updateComponentsMsg, updateDataModelMsg)
    }

    fun toJsonl(
        script: BoardEventScript,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0,
        surfaceIdOverride: String? = null
    ): String = fromBoardScript(script, protocolVersion, surfaceIdOverride).joinToString("\n") { it.toString() }

    fun publishBoardScript(
        script: BoardEventScript,
        protocolVersion: String = A2uiProtocolConstants.VERSION_1_0,
        surfaceIdOverride: String? = null
    ): String {
        val messages = fromBoardScript(script, protocolVersion, surfaceIdOverride)
        val surfaceId = messages.firstOrNull()
            ?.optJSONObject("createSurface")
            ?.optString("surfaceId")
            .orEmpty()
        messages.forEach { A2uiSurfaceRegistry.ingestJsonObject(it) }
        return surfaceId
    }
}
