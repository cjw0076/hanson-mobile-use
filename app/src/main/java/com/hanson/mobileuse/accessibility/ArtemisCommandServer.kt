package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.hanson.mobileuse.agent.A2uiIngestResult
import com.hanson.mobileuse.agent.A2uiProtocolConstants
import com.hanson.mobileuse.agent.A2uiSurfaceRegistry
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.CoDrawElement
import com.hanson.mobileuse.agent.CoDrawingCanvasEngine
import com.hanson.mobileuse.agent.CommitmentDebtTrackerEngine
import com.hanson.mobileuse.agent.CrossLabPortabilityPack
import com.hanson.mobileuse.agent.DynamicSseEnvelope
import com.hanson.mobileuse.agent.DynamicUiEventBus
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LifestyleBeatScheduler
import com.hanson.mobileuse.agent.LiveChatMessage
import com.hanson.mobileuse.agent.PrivacyAndQuotaProofLedger
import com.hanson.mobileuse.agent.ProactiveProposalStatus
import com.hanson.mobileuse.agent.ProactiveSecretaryEngine
import com.hanson.mobileuse.agent.RelationshipCareCadenceEngine
import com.hanson.mobileuse.notification.NotificationStormShieldEngine
import com.hanson.mobileuse.notification.NotificationThreadBuffer
import com.hanson.mobileuse.service.HansonVirtualDisplayManager
import com.hanson.mobileuse.service.VirtualDisplayMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Artemis Loopback Bridge (`127.0.0.1:18888`)
 * Supports `/ping`, `/snapshot`, `/dump`, `/dump_xml`, `/elements`, `/action`, `/rpc`,
 * AND `/chat` for continuous runtime Live Chat synchronization with the Laptop CLI (Antigravity / Codex / Artemis).
 *
 * Security Model (v3.1 Hardened):
 * - Token authentication (`requireTokenAuth = true`) is enabled by default using a 256-bit random token.
 * - Constant-time token comparison via [MessageDigest.isEqual] on `X-Artemis-Token` or `Authorization: Bearer`.
 * - Any request arriving through a tunnel/proxy (`CF-Connecting-IP`, `X-Forwarded-For`, `Ngrok-Trace-Id`, etc.)
 *   or carrying browser cross-origin headers (`Origin`, `Referer`, `Sec-Fetch-Site`) ALWAYS enforces token auth
 *   even if local ADB token-free mode was toggled in UI.
 * - Wildcard CORS (`Access-Control-Allow-Origin: *`) is removed to prevent browser localhost hijacking.
 */
class ArtemisCommandServer(
    private val service: AccessibilityService,
    val port: Int = DEFAULT_PORT,
    var requireTokenAuth: Boolean = true
) {
    companion object {
        private const val TAG = "ArtemisCommandServer"
        const val DEFAULT_PORT = 18888
        private const val MAX_BODY_BYTES = 65_536

        fun verifyTokenConstantTime(provided: String?, expected: String): Boolean {
            val cleanProvided = provided?.trim().orEmpty()
            val cleanExpected = expected.trim()
            if (cleanProvided.length < 32 || cleanExpected.length < 32) return false
            return MessageDigest.isEqual(
                cleanProvided.toByteArray(StandardCharsets.UTF_8),
                cleanExpected.toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    val sessionToken: String = generateToken()
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    val totalRequests = AtomicInteger(0)

    var onBridgeEvent: ((endpoint: String, status: String) -> Unit)? = null

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        executor.execute {
            try {
                val socket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "Artemis Loopback Server listening on 127.0.0.1:$port")
                while (isRunning.get()) {
                    val client = socket.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Artemis Server error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }

    fun isActive(): Boolean = isRunning.get() && serverSocket?.isClosed == false

    private fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 10_000
                val rawIn = sock.getInputStream()
                val out = sock.getOutputStream()
                val firstLine = readAsciiLine(rawIn) ?: return
                totalRequests.incrementAndGet()

                if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ")) {
                    handleHttp(sock, firstLine, rawIn, out)
                } else {
                    handleJsonRpc(firstLine, out)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client handler error: ${e.message}")
            }
        }
    }

    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = java.io.ByteArrayOutputStream()
        var foundAny = false
        while (true) {
            val b = input.read()
            if (b == -1) break
            foundAny = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
        }
        if (!foundAny && sb.size() == 0) return null
        return sb.toString("UTF-8")
    }

    private fun handleHttp(sock: Socket, requestLine: String, rawIn: java.io.InputStream, out: OutputStream) {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val fullUri = parts[1]

        var path = fullUri
        val queryParams = mutableMapOf<String, String>()
        val qIdx = fullUri.indexOf('?')
        if (qIdx != -1) {
            path = fullUri.substring(0, qIdx)
            val query = fullUri.substring(qIdx + 1)
            for (pair in query.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) queryParams[kv[0]] = kv[1]
            }
        }

        var contentLength = 0
        var isProxiedOrBrowserRequest = false
        var headerLine = readAsciiLine(rawIn)
        while (!headerLine.isNullOrEmpty()) {
            val lower = headerLine.lowercase()
            when {
                lower.startsWith("content-length:") -> {
                    contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                lower.startsWith("last-event-id:") -> {
                    if (!queryParams.containsKey("from")) {
                        queryParams["from"] = headerLine.substringAfter(":").trim()
                    }
                }
                lower.startsWith("x-artemis-token:") -> {
                    queryParams["token"] = headerLine.substringAfter(":").trim()
                }
                lower.startsWith("authorization:") -> {
                    val authVal = headerLine.substringAfter(":").trim()
                    if (authVal.lowercase().startsWith("bearer ")) {
                        queryParams["token"] = authVal.substring(7).trim()
                    }
                }
                lower.startsWith("cf-connecting-ip:") ||
                    lower.startsWith("x-forwarded-for:") ||
                    lower.startsWith("x-real-ip:") ||
                    lower.startsWith("forwarded:") ||
                    lower.startsWith("ngrok-trace-id:") ||
                    lower.startsWith("x-forwarded-host:") ||
                    lower.startsWith("tailscale-user-login:") ||
                    lower.startsWith("origin:") ||
                    lower.startsWith("referer:") ||
                    lower.startsWith("sec-fetch-site:") -> {
                    isProxiedOrBrowserRequest = true
                }
            }
            headerLine = readAsciiLine(rawIn)
        }

        if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
            sendHttpResponse(out, 413, "application/json", """{"status":"error","error":"Payload too large"}""")
            return
        }

        var body = ""
        if ("POST".equals(method, ignoreCase = true) && contentLength > 0) {
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = rawIn.read(buf, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            body = String(buf, 0, read, StandardCharsets.UTF_8)
        }

        val mustEnforceToken = requireTokenAuth || isProxiedOrBrowserRequest
        if (mustEnforceToken && !verifyTokenConstantTime(queryParams["token"], sessionToken)) {
            onBridgeEvent?.invoke(path, "401 Unauthorized")
            sendHttpResponse(
                out,
                401,
                "application/json",
                """{"status":"error","error":"Unauthorized: valid X-Artemis-Token or Authorization Bearer token required"}"""
            )
            return
        }

        when (path) {
            "/.well-known/agent-card.json", "/agent-card.json" -> {
                val card = JSONObject().apply {
                    put("name", "Hanson Mobile-Use Agent")
                    put("description", "Open-Source Full-Control Android Mobile-Use Engine with Set-of-Marks Grounding, Accessibility/Gesture Control, Screen-Off Notification Reply, and MCP Server Support.")
                    put("version", "6.0.0")
                    put("url", "http://127.0.0.1:$port")
                    put("repository", "https://github.com/cjw0076/hanson-mobile-use")
                    put("protocol", "A2A/1.0")
                    put(
                        "capabilities",
                        JSONObject().apply {
                            put("streaming", true)
                            put("sseDynamicUi", true)
                            put("a2ui", true)
                            put("a2uiVersions", JSONArray().put("1.0").put("v0.9").put("v0.8"))
                            put("a2uiCatalog", A2uiProtocolConstants.BASIC_CATALOG_V1_0)
                            put("crossAppCoDrawing", true)
                            put("pushNotifications", true)
                            put("proactiveSecretary", true)
                            put("deepLinkInspector", true)
                            put("a11yReadGate", true)
                            put("lifestyleBeats", true)
                            put("whiteSpaceLifeCare", true)
                            put("stateTransitionHistory", true)
                            put("portalProtocol", "droidrun-portal-v6")
                            put("parallelLanes", JSONArray().put("FG").put("BG").put("AUTO"))
                        }
                    )
                    put(
                        "endpoints",
                        JSONObject().apply {
                            put("a2a", "/a2a")
                            put("a2uiMessages", "/a2ui/messages")
                            put("a2uiSurfaces", "/a2ui/surfaces")
                            put("secretaryProposals", "/secretary/proposals")
                            put("secretaryScan", "/secretary/scan")
                            put("secretaryIngest", "/secretary/ingest")
                            put("secretaryAct", "/secretary/act")
                            put("chat", "/chat")
                            put("mcpTools", "/mcp/tools")
                            put("snapshot", "/snapshot")
                            put("elements", "/elements")
                            put("sseBoard", "/sse/board")
                            put("boardEvents", "/board/events")
                            put("codrawCommand", "/codraw/command")
                            put("preflight", "/preflight")
                            put("scrollNoFling", "/scroll_no_fling")
                            put("waitForText", "/wait_for_text")
                            put("waitStable", "/wait_stable")
                            put("vdisplayStart", "/vdisplay/start")
                            put("vdisplayStatus", "/vdisplay/status")
                            put("vdisplaySnapshot", "/vdisplay/snapshot")
                            put("vdisplayStop", "/vdisplay/stop")
                            put("vdisplayLaunch", "/vdisplay/launch")
                            put("vdisplayInput", "/vdisplay/input")
                            put("vdisplayMode", "/vdisplay/mode")
                            put("vdisplayTakeover", "/vdisplay/takeover")
                            put("portalPing", "/portal/ping")
                            put("portalState", "/portal/state")
                            put("portalBrief", "/portal/brief")
                            put("portalUndo", "/portal/undo")
                            put("portalDeepLinks", "/portal/deep-links")
                            put("portalDeepLinkResolve", "/portal/deep-link/resolve")
                            put("lifestyleStatus", "/lifestyle/status")
                            put("lifestylePortabilityExport", "/lifestyle/portability/export")
                        }
                    )
                }
                onBridgeEvent?.invoke(path, "200 OK (A2A Card)")
                sendHttpResponse(out, 200, "application/json", card.toString())
            }
            "/a2ui/messages" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                if (body.isBlank()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Empty A2UI payload"}""")
                    return
                }
                val results = A2uiSurfaceRegistry.ingestJsonl(body)
                var appliedCount = 0
                var rejectedCount = 0
                var firstError = ""
                val touchedSurfaceIds = LinkedHashSet<String>()
                for (res in results) {
                    when (res) {
                        is A2uiIngestResult.Applied -> {
                            appliedCount++
                            touchedSurfaceIds.add(res.surfaceId)
                        }
                        is A2uiIngestResult.Rejected -> {
                            rejectedCount++
                            if (firstError.isEmpty()) firstError = res.reason
                        }
                        is A2uiIngestResult.Ignored -> Unit
                    }
                }
                if (appliedCount == 0 && rejectedCount > 0) {
                    val errResp = JSONObject()
                        .put("status", "error")
                        .put("applied", 0)
                        .put("rejected", rejectedCount)
                        .put("error", firstError.ifBlank { "Invalid A2UI payload" })
                    onBridgeEvent?.invoke("POST /a2ui/messages", "400 Invalid A2UI")
                    sendHttpResponse(out, 400, "application/json", errResp.toString())
                    return
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("protocol", "A2UI")
                    put("applied", appliedCount)
                    put("rejected", rejectedCount)
                    put("surfaceIds", JSONArray(touchedSurfaceIds.toList()))
                    put("activeSurfaceCount", A2uiSurfaceRegistry.surfaces.value.size)
                }
                onBridgeEvent?.invoke("POST /a2ui/messages", "200 OK (applied=$appliedCount)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/a2ui/surfaces" -> {
                if (!"GET".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val includeComponents = queryParams["components"] == "true"
                val requestedId = queryParams["surfaceId"]?.trim().orEmpty()
                if (requestedId.isNotEmpty()) {
                    val surface = A2uiSurfaceRegistry.getSurface(requestedId)
                    if (surface == null) {
                        sendHttpResponse(out, 404, "application/json", """{"status":"error","error":"Surface not found"}""")
                        return
                    }
                    val resp = JSONObject()
                        .put("status", "ok")
                        .put("surface", surface.toSummaryJson(includeDataModel = true, includeComponents = includeComponents))
                    onBridgeEvent?.invoke("GET /a2ui/surfaces", "200 OK ($requestedId)")
                    sendHttpResponse(out, 200, "application/json", resp.toString())
                } else {
                    val summary = A2uiSurfaceRegistry.surfacesSummaryJson(includeComponents = includeComponents)
                    onBridgeEvent?.invoke("GET /a2ui/surfaces", "200 OK (${summary.optInt("surfaceCount")} surfaces)")
                    sendHttpResponse(out, 200, "application/json", summary.toString())
                }
            }
            "/sse/board" -> {
                if (!"GET".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val fromSeq = queryParams["from"]?.toLongOrNull() ?: 0L
                onBridgeEvent?.invoke("GET /sse/board", "200 SSE Stream (from=$fromSeq)")
                streamBoardSse(sock, out, fromSeq)
            }
            "/board/events" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                var ingested = 0
                val batch = parsed.optJSONArray("events")
                if (batch != null) {
                    for (i in 0 until batch.length()) {
                        val item = batch.optJSONObject(i) ?: continue
                        val evType = item.optString("event", item.optString("name", "tutor_command"))
                        if (DynamicUiEventBus.ingestExternalJsonEvent(evType, item)) ingested++
                    }
                } else {
                    val evType = parsed.optString("event", parsed.optString("name", "tutor_command"))
                    if (DynamicUiEventBus.ingestExternalJsonEvent(evType, parsed)) ingested++
                }
                val resp = JSONObject()
                    .put("status", "ok")
                    .put("ingested", ingested)
                    .put("lastSequenceId", DynamicUiEventBus.surfaceState.value.lastSequenceId)
                onBridgeEvent?.invoke("POST /board/events", "200 OK (ingested=$ingested)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/codraw/command" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val action = parsed.optString("action", "").trim().lowercase()
                var addedCount = 0
                when (action) {
                    "clear" -> CoDrawingCanvasEngine.clear()
                    "overlay_start" -> {
                        com.hanson.mobileuse.service.DynamicCanvasOverlayService.startIfPermitted(
                            service.applicationContext,
                            initialDrawMode = parsed.optBoolean("drawMode", false)
                        )
                    }
                    "overlay_stop" -> {
                        com.hanson.mobileuse.service.DynamicCanvasOverlayService.stop(service.applicationContext)
                    }
                    else -> {
                        val elementsArr = parsed.optJSONArray("elements")
                        if (elementsArr != null) {
                            for (i in 0 until elementsArr.length()) {
                                val elJson = elementsArr.optJSONObject(i) ?: continue
                                if (DynamicUiEventBus.ingestExternalJsonEvent("codraw_command", elJson)) addedCount++
                            }
                        } else if (CoDrawElement.fromJson(parsed) != null) {
                            if (DynamicUiEventBus.ingestExternalJsonEvent("codraw_command", parsed)) addedCount++
                        }
                    }
                }
                val resp = JSONObject()
                    .put("status", "ok")
                    .put("added", addedCount)
                    .put("totalElements", CoDrawingCanvasEngine.state.value.elements.size)
                onBridgeEvent?.invoke("POST /codraw/command", "200 OK (added=$addedCount)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/preflight" -> {
                val snap = HierarchyDumper.captureAtomicSnapshot(service, includeScreenshot = false)
                val visibleText = snap.indexedElements.joinToString("\n") {
                    listOf(it.text, it.contentDesc).filter(String::isNotBlank).joinToString(" ")
                }
                val verdict = com.hanson.mobileuse.agent.UntrustedScreenDataGuard.evaluatePreflightSafety(
                    packageName = snap.activePackage,
                    visibleScreenText = visibleText
                )
                val resp = JSONObject().apply {
                    put("status", if (verdict.isSafeForUnattendedSession) "ok" else "blocked")
                    put("safe", verdict.isSafeForUnattendedSession)
                    put("riskLevel", verdict.level.name)
                    put("packageName", snap.activePackage)
                    put("matchedTerms", JSONArray(verdict.matchedTerms))
                    put("promptInjectionSignals", JSONArray(verdict.promptInjectionSignals))
                    put("reasonKo", verdict.reasonKo)
                    put("screenWidth", snap.screenWidth)
                    put("screenHeight", snap.screenHeight)
                    put("elementCount", snap.indexedElements.size)
                }
                onBridgeEvent?.invoke("GET /preflight", if (verdict.isSafeForUnattendedSession) "200 SAFE" else "200 BLOCKED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/scroll_no_fling" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                val snap = HierarchyDumper.captureAtomicSnapshot(service, includeScreenshot = false)
                val w = snap.screenWidth.coerceAtLeast(360).toFloat()
                val h = snap.screenHeight.coerceAtLeast(640).toFloat()
                // Direction matches phone-harness semantics: "down" reveals content further down (finger drags up)
                val direction = parsed.optString("direction", "down").trim().lowercase()
                val amount = parsed.optDouble("amount", 0.55).toFloat().coerceIn(0.10f, 0.85f)
                val cx = parsed.optDouble("x", (w / 2.0)).toFloat().coerceIn(w * 0.08f, w * 0.92f)
                val cy = parsed.optDouble("y", (h / 2.0)).toFloat().coerceIn(h * 0.12f, h * 0.88f)
                val (dx, dy) = when (direction) {
                    "up" -> 0f to (h * amount)
                    "left" -> (w * amount) to 0f
                    "right" -> -(w * amount) to 0f
                    else -> 0f to -(h * amount) // "down": finger moves upward to reveal lower rows
                }
                val sx = (cx - dx / 2f).coerceIn(w * 0.06f, w * 0.94f)
                val sy = (cy - dy / 2f).coerceIn(h * 0.08f, h * 0.92f)
                val ex = (cx + dx / 2f).coerceIn(w * 0.06f, w * 0.94f)
                val ey = (cy + dy / 2f).coerceIn(h * 0.08f, h * 0.92f)
                val ok = withTemporaryHudPassThrough {
                    GestureController.scrollWithoutFling(
                        service = service,
                        startX = sx,
                        startY = sy,
                        endX = ex,
                        endY = ey,
                        moveDurationMs = parsed.optLong("moveDurationMs", 420L),
                        settleHoldMs = parsed.optLong("settleHoldMs", 200L)
                    )
                }
                val resp = JSONObject()
                    .put("status", if (ok) "ok" else "failed")
                    .put("success", ok)
                    .put("direction", direction)
                    .put("from", JSONArray().put(sx.toInt()).put(sy.toInt()))
                    .put("to", JSONArray().put(ex.toInt()).put(ey.toInt()))
                onBridgeEvent?.invoke("POST /scroll_no_fling", if (ok) "200 OK ($direction)" else "200 FAILED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/wait_for_text" -> {
                val parsed = if ("POST".equals(method, ignoreCase = true)) {
                    runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                } else {
                    JSONObject()
                }
                val query = parsed.optString("query", queryParams["query"].orEmpty()).trim()
                val exact = parsed.optBoolean("exact", queryParams["exact"] == "true")
                val timeoutMs = parsed.optLong("timeoutMs", queryParams["timeoutMs"]?.toLongOrNull() ?: 6000L).coerceIn(250L, 15_000L)
                val intervalMs = parsed.optLong("intervalMs", 350L).coerceIn(150L, 1500L)
                if (query.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing query"}""")
                    return
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                var matchedNode: IndexedUiElement? = null
                while (System.currentTimeMillis() <= deadline) {
                    val snap = HierarchyDumper.captureAtomicSnapshot(service, includeScreenshot = false)
                    matchedNode = snap.indexedElements.firstOrNull { node ->
                        val fields = listOf(node.text, node.contentDesc).filter(String::isNotBlank)
                        fields.any { f -> if (exact) f.equals(query, ignoreCase = true) else f.contains(query, ignoreCase = true) }
                    }
                    if (matchedNode != null) break
                    runCatching { Thread.sleep(intervalMs) }
                }
                val resp = JSONObject().apply {
                    put("status", if (matchedNode != null) "ok" else "timeout")
                    put("found", matchedNode != null)
                    put("query", query)
                    if (matchedNode != null) {
                        put("index", matchedNode.index)
                        put("text", matchedNode.text.ifBlank { matchedNode.contentDesc })
                        put("x", matchedNode.centerX)
                        put("y", matchedNode.centerY)
                    }
                }
                onBridgeEvent?.invoke("/wait_for_text", if (matchedNode != null) "200 FOUND" else "200 TIMEOUT")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/wait_stable" -> {
                val parsed = if ("POST".equals(method, ignoreCase = true)) {
                    runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                } else {
                    JSONObject()
                }
                val timeoutMs = parsed.optLong("timeoutMs", queryParams["timeoutMs"]?.toLongOrNull() ?: 4500L).coerceIn(300L, 12_000L)
                val intervalMs = parsed.optLong("intervalMs", 300L).coerceIn(150L, 1000L)
                val settleReads = parsed.optInt("settle", 2).coerceIn(2, 5)
                val deadline = System.currentTimeMillis() + timeoutMs
                var prevSet: Set<String>? = null
                var sameCount = 0
                var settled = false
                var lastContent: Set<String> = emptySet()
                while (System.currentTimeMillis() <= deadline) {
                    val snap = HierarchyDumper.captureAtomicSnapshot(service, includeScreenshot = false)
                    val h = snap.screenHeight.coerceAtLeast(1).toFloat()
                    val pairs = snap.indexedElements.mapNotNull { el ->
                        val txt = el.text.ifBlank { el.contentDesc }.trim()
                        if (txt.isEmpty()) null else txt to (el.centerY.toFloat() / h)
                    }
                    lastContent = com.hanson.mobileuse.agent.ContentBandScreenFilter.filterContentBandTexts(pairs)
                    if (prevSet != null && lastContent == prevSet) {
                        sameCount++
                        if (sameCount >= settleReads - 1) {
                            settled = true
                            break
                        }
                    } else {
                        sameCount = 0
                    }
                    prevSet = lastContent
                    runCatching { Thread.sleep(intervalMs) }
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("settled", settled)
                    put("contentItemCount", lastContent.size)
                    put("contentTexts", JSONArray(lastContent.take(40)))
                }
                onBridgeEvent?.invoke("/wait_stable", if (settled) "200 SETTLED" else "200 TIMEOUT")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/start" -> {
                val parsed = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                val st = com.hanson.mobileuse.service.HansonVirtualDisplayManager.start(
                    context = service.applicationContext,
                    width = parsed.optInt("width", 1080),
                    height = parsed.optInt("height", 1920),
                    dpi = parsed.optInt("dpi", 320)
                )
                val resp = JSONObject()
                    .put("status", if (st.active) "ok" else "failed")
                    .put("active", st.active)
                    .put("displayId", st.displayId)
                    .put("width", st.width)
                    .put("height", st.height)
                    .put("dpi", st.dpi)
                onBridgeEvent?.invoke("/vdisplay/start", if (st.active) "200 OK (id=${st.displayId})" else "200 FAILED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/status" -> {
                val uiSnap = HansonVirtualDisplayManager.uiSnapshot.value
                val resp = uiSnap.toJson().apply {
                    put("status", "ok")
                }
                onBridgeEvent?.invoke("/vdisplay/status", "200 OK (${uiSnap.mode.name})")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/snapshot" -> {
                val st = HansonVirtualDisplayManager.status.value
                val b64 = HansonVirtualDisplayManager.captureBase64Png(service.applicationContext)
                val resp = JSONObject()
                    .put("status", if (b64 != null) "ok" else "no_frame")
                    .put("active", st.active)
                    .put("displayId", st.displayId)
                    .put("imageBase64", b64 ?: "")
                onBridgeEvent?.invoke("/vdisplay/snapshot", if (b64 != null) "200 PNG" else "200 NO_FRAME")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/stop" -> {
                HansonVirtualDisplayManager.stop()
                onBridgeEvent?.invoke("/vdisplay/stop", "200 STOPPED")
                sendHttpResponse(out, 200, "application/json", """{"status":"ok","active":false}""")
            }
            "/vdisplay/launch" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val targetPkg = parsed.optString("package", parsed.optString("packageName", "")).trim()
                if (targetPkg.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing package"}""")
                    return
                }
                val (ok, msg) = HansonVirtualDisplayManager.launchAppOnVirtualDisplay(
                    context = service.applicationContext,
                    packageName = targetPkg
                )
                val resp = JSONObject().apply {
                    put("status", if (ok) "ok" else "failed")
                    put("success", ok)
                    put("package", targetPkg)
                    put("message", msg)
                    put("vdisplay", HansonVirtualDisplayManager.uiSnapshot.value.toJson())
                }
                onBridgeEvent?.invoke("POST /vdisplay/launch", if (ok) "200 OK ($targetPkg)" else "200 DENIED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/input" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val action = parsed.optString("action", "").trim()
                if (action.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing action"}""")
                    return
                }
                val x = parsed.optInt("x", 0)
                val y = parsed.optInt("y", 0)
                val x2 = parsed.optInt("x2", x)
                val y2 = parsed.optInt("y2", y)
                val text = parsed.optString("text", "")
                val (ok, msg) = HansonVirtualDisplayManager.injectDisplayInput(
                    action = action,
                    x = x,
                    y = y,
                    x2 = x2,
                    y2 = y2,
                    text = text
                )
                val resp = JSONObject().apply {
                    put("status", if (ok) "ok" else "failed")
                    put("success", ok)
                    put("action", action)
                    put("message", msg)
                    put("vdisplay", HansonVirtualDisplayManager.uiSnapshot.value.toJson())
                }
                onBridgeEvent?.invoke("POST /vdisplay/input", if (ok) "200 OK ($action)" else "200 FAILED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/mode" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val rawMode = parsed.optString("mode", "").trim().uppercase()
                val targetMode = VirtualDisplayMode.entries.firstOrNull { it.name == rawMode }
                if (targetMode == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid mode (use FOREGROUND, BACKGROUND, or IDLE)"}""")
                    return
                }
                val snap = HansonVirtualDisplayManager.switchMode(targetMode)
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("mode", snap.mode.name)
                    put("vdisplay", snap.toJson())
                }
                onBridgeEvent?.invoke("POST /vdisplay/mode", "200 OK (${snap.mode.name})")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/vdisplay/takeover" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val action = parsed.optString("action", "").trim().lowercase()
                val reason = parsed.optString("reason", "사용자 직접 조작이 필요합니다.").trim()
                val snap = when (action) {
                    "request" -> HansonVirtualDisplayManager.requestUserTakeover(reason)
                    "return" -> HansonVirtualDisplayManager.returnControlFromUser()
                    "resume" -> HansonVirtualDisplayManager.resumeAgentAfterTakeover()
                    else -> {
                        sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid takeover action (use request, return, or resume)"}""")
                        return
                    }
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("action", action)
                    put("takeoverState", snap.takeoverState.name)
                    put("vdisplay", snap.toJson())
                }
                onBridgeEvent?.invoke("POST /vdisplay/takeover", "200 OK ($action)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/mcp/tools" -> {
                val mcpTools: Any = com.hanson.mobileuse.agent.ToolSpecRegistry.toMcpToolsJson()
                val payload = when (mcpTools) {
                    is JSONObject -> if (mcpTools.has("tools")) mcpTools else JSONObject().put("tools", mcpTools)
                    else -> JSONObject().put("tools", mcpTools)
                }
                onBridgeEvent?.invoke(path, "200 OK (MCP Tools)")
                sendHttpResponse(out, 200, "application/json", payload.toString())
            }
            "/a2a" -> {
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                val req = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                val rpcId = req.opt("id") ?: "a2a_1"
                val rpcMethod = req.optString("method", "tasks/send")
                val params = req.optJSONObject("params") ?: req
                val senderAgent = params.optString("senderAgent", "External-A2A-Agent")
                val lane = params.optString("lane", "AUTO").uppercase()
                val prompt = params.optString("prompt", params.optString("text", "")).trim()

                if (rpcMethod == "pickle/negotiate_schedule") {
                    val outcome = com.hanson.mobileuse.agent.PickleA2AScheduleNegotiator.negotiate(
                        peerName = params.optString("peerName", senderAgent),
                        durationMinutes = params.optInt("durationMinutes", 60),
                        preferredArea = params.optString("preferredArea", "성수·강남 중간지점"),
                        myBusyCsv = params.optString("myBusySlots", ""),
                        peerFreeCsv = params.optString("peerFreeSlots", "12:00-14:00, 18:30-21:00")
                    )
                    val scheduleObj = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", rpcId)
                        put("result", outcome.a2aJsonRpcPayload)
                    }
                    onBridgeEvent?.invoke("POST /a2a (schedule)", "200 OK ($senderAgent)")
                    sendHttpResponse(out, 200, "application/json", scheduleObj.toString())
                    return
                }

                if (rpcMethod == "tasks/send" && prompt.isNotEmpty()) {
                    val routedPrompt = when (lane) {
                        "BG", "BACKGROUND" -> "[BG] $prompt"
                        "FG", "FOREGROUND" -> "[FG] $prompt"
                        else -> prompt
                    }
                    engine.sendChatMessage(routedPrompt)
                }
                val resultObj = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", rpcId)
                    put(
                        "result",
                        JSONObject().apply {
                            put("status", engine.currentSession.value.status.name)
                            put("acceptedFrom", senderAgent)
                            put("lane", lane)
                            put("latestReply", engine.chatMessages.value.lastOrNull()?.text.orEmpty())
                        }
                    )
                }
                onBridgeEvent?.invoke("POST /a2a", "200 OK ($senderAgent)")
                sendHttpResponse(out, 200, "application/json", resultObj.toString())
            }
            "/engine_stats", "/stats" -> {
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                val session = engine.currentSession.value
                val stats = JSONObject().apply {
                    put("status", "ok")
                    put("agentStatus", session.status.name)
                    put("sessionSteps", session.steps.size)
                    put("activeParallelCount", engine.activeParallelTasks.value.size)
                    put("chatMessageCount", engine.chatMessages.value.size)
                    put("bridgeRequests", totalRequests.get())
                    put("watcherRuleCount", engine.resolvedWatcherEngine.listRules().size)
                    put("scratchpadEntryCount", engine.resolvedScratchpad.listEntries().size)
                    put("memoryBubbleCount", engine.resolvedMemoryBubbles.bubbles.value.size)
                    put("routineCount", engine.resolvedPersonalRoutines.routines.value.size)
                }
                onBridgeEvent?.invoke("/engine_stats", "200 OK")
                sendHttpResponse(out, 200, "application/json", stats.toString())
            }
            "/ping", "/health" -> {
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                val parallelArr = JSONArray()
                engine.activeParallelTasks.value.forEach { slot ->
                    parallelArr.put(
                        JSONObject().apply {
                            put("taskId", slot.taskId)
                            put("lane", slot.lane)
                            put("goal", slot.goal)
                        }
                    )
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("engine", "Hanson-Artemis-Hybrid-v2-Parallel")
                    put("port", port)
                    put("requests", totalRequests.get())
                    put("agentStatus", engine.currentSession.value.status.name)
                    put("activeParallelCount", parallelArr.length())
                    put("activeParallelTasks", parallelArr)
                    put("chatMessageCount", engine.chatMessages.value.size)
                }
                onBridgeEvent?.invoke(path, "200 OK")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/chat" -> {
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                if ("GET".equals(method, ignoreCase = true)) {
                    val arr = JSONArray()
                    engine.chatMessages.value.forEach { arr.put(it.toJson()) }
                    val resp = JSONObject().apply {
                        put("status", "ok")
                        put("agentStatus", engine.currentSession.value.status.name)
                        put("messages", arr)
                    }
                    onBridgeEvent?.invoke("GET /chat", "200 OK (${arr.length()} msgs)")
                    sendHttpResponse(out, 200, "application/json", resp.toString())
                } else {
                    val req = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                    val text = req.optString("text", req.optString("message", "")).trim()
                    val role = req.optString("role", "assistant").lowercase()
                    val lane = req.optString("lane", "").uppercase()
                    val badge = req.optString("badge", "LAPTOP CLI")
                    if (text.isNotEmpty()) {
                        if (role == "user" || req.optBoolean("executeGoal", false)) {
                            val prefixedText = when (lane) {
                                "BG", "BACKGROUND" -> "[BG] $text"
                                "FG", "FOREGROUND", "GUI" -> "[FG] $text"
                                else -> text
                            }
                            engine.sendChatMessage(prefixedText)
                        } else {
                            engine.injectExternalReply(text, badge)
                        }
                    }
                    onBridgeEvent?.invoke("POST /chat", "200 OK ($role)")
                    sendHttpResponse(out, 200, "application/json", """{"status":"ok"}""")
                }
            }
            "/snapshot" -> {
                val includeScreenshot = queryParams["screenshot"] != "false"
                val gated = A11yReadGate.captureGated(
                    service = service,
                    includeScreenshot = includeScreenshot,
                    filterInvisible = queryParams["filter"] != "false"
                )
                val elemCount = gated.snapshot?.indexedElements?.size ?: 0
                onBridgeEvent?.invoke(
                    path,
                    if (gated.degraded) "200 DEGRADED ($elemCount els)" else "200 OK ($elemCount els)"
                )
                sendHttpResponse(out, 200, "application/json", gated.toJson(includeFullTree = true).toString())
            }
            "/dump", "/elements" -> {
                val gated = A11yReadGate.captureGated(
                    service = service,
                    includeScreenshot = false,
                    filterInvisible = queryParams["filter"] != "false"
                )
                val elemCount = gated.snapshot?.indexedElements?.size ?: 0
                onBridgeEvent?.invoke(
                    path,
                    if (gated.degraded) "200 DEGRADED ($elemCount els)" else "200 OK ($elemCount els)"
                )
                sendHttpResponse(out, 200, "application/json", gated.toJson(includeFullTree = path == "/dump").toString())
            }
            "/dump_xml", "/hierarchy.xml" -> {
                val snapshot = HierarchyDumper.captureAtomicSnapshot(
                    service = service,
                    includeScreenshot = false,
                    filterInvisible = queryParams["filter"] != "false"
                )
                val xml = HierarchyDumper.dumpToXml(snapshot)
                onBridgeEvent?.invoke(path, "200 OK (XML)")
                sendHttpResponse(out, 200, "application/xml", xml)
            }
            "/action", "/rpc" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"error":"POST required"}""")
                    return
                }
                val result = executeJsonCommand(body, preAuthenticatedHttp = true)
                onBridgeEvent?.invoke(path, "200 OK")
                sendHttpResponse(out, 200, "application/json", result.toString())
            }
            "/portal/ping" -> {
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("portalVersion", "6.0.0")
                    put("accessibilityReady", true)
                    put("activePackage", service.rootInActiveWindow?.packageName?.toString().orEmpty())
                }
                onBridgeEvent?.invoke(path, "200 OK (Portal)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/portal/state" -> {
                val gated = A11yReadGate.captureGated(
                    service = service,
                    includeScreenshot = queryParams["screenshot"] == "true",
                    filterInvisible = queryParams["filter"] != "false"
                )
                val snapshot = gated.snapshot
                val outline = if (snapshot != null) {
                    com.hanson.mobileuse.agent.ContextorCompactor.buildCompactOutline(
                        snapshot = snapshot,
                        vault = com.hanson.mobileuse.agent.HansonRuntimeHolder.getBlindVault(service.applicationContext)
                    )
                } else {
                    ""
                }
                val resp = JSONObject().apply {
                    put("status", if (snapshot != null) "ok" else "degraded")
                    put("portalVersion", "6.0.0")
                    put("activePackage", snapshot?.activePackage.orEmpty())
                    put("activeWindowTitle", snapshot?.activeWindowTitle.orEmpty())
                    put("elementCount", snapshot?.indexedElements?.size ?: 0)
                    put("compactOutline", outline)
                    put("elements", snapshot?.toJson(includeFullTree = false)?.optJSONArray("elements") ?: JSONArray())
                    put("degraded", gated.degraded)
                    put("degradedReason", gated.degradedReason ?: JSONObject.NULL)
                    put("snapshotAgeMs", gated.snapshotAgeMs)
                    put("cacheCleared", gated.cacheCleared)
                }
                val elemCount = snapshot?.indexedElements?.size ?: 0
                onBridgeEvent?.invoke(
                    path,
                    if (gated.degraded) "200 DEGRADED ($elemCount els)" else "200 OK ($elemCount els)"
                )
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/portal/deep-links" -> {
                if (!"GET".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val rawPkg = queryParams["package"]?.let { encoded ->
                    runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
                }?.trim()?.ifBlank { null }
                val links = DeepLinkInspector.inspectInstalledDeepLinks(
                    context = service.applicationContext,
                    packageFilter = rawPkg
                )
                val arr = JSONArray()
                links.forEach { arr.put(it.toJson()) }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("count", links.size)
                    put("packageFilter", rawPkg ?: JSONObject.NULL)
                    put("deepLinks", arr)
                }
                onBridgeEvent?.invoke("GET /portal/deep-links", "200 OK (${links.size} links)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/portal/deep-link/resolve" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val uri = parsed.optString("uri", parsed.optString("url", "")).trim()
                if (uri.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing uri"}""")
                    return
                }
                val resolved = DeepLinkInspector.resolveDeepLinkDryRun(
                    context = service.applicationContext,
                    uri = uri
                )
                if (!resolved.has("status")) {
                    resolved.put("status", "ok")
                }
                onBridgeEvent?.invoke("POST /portal/deep-link/resolve", "200 OK")
                sendHttpResponse(out, 200, "application/json", resolved.toString())
            }
            "/portal/brief" -> {
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val notifications = com.hanson.mobileuse.notification.RemoteInputSessionCache.listRecent(limit = 15)
                val watchers = engine.resolvedWatcherEngine.listRules()
                val scratchpadEntries = engine.resolvedScratchpad.listEntries()
                val bubbles = engine.resolvedMemoryBubbles.bubbles.value
                val routines = engine.resolvedPersonalRoutines.routines.value
                val brief = com.hanson.mobileuse.agent.ProactiveDailyBriefEngine.buildBrief(
                    hourOfDay = hour,
                    notifications = notifications,
                    watcherRules = watchers,
                    scratchpadEntries = scratchpadEntries,
                    bubbles = bubbles,
                    pinnedRoutines = routines,
                    proactiveProposals = ProactiveSecretaryEngine.listPendingProposals()
                )
                val highlightsArr = JSONArray().apply { brief.highlights.forEach { put(it) } }
                val actionsArr = JSONArray().apply {
                    brief.quickActions.forEach { a ->
                        put(
                            JSONObject().apply {
                                put("labelKo", a.labelKo)
                                put("goalPrompt", a.goalPrompt)
                                put("badgeKo", a.badgeKo)
                            }
                        )
                    }
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("portalVersion", "6.0.0")
                    put("uxMode", engine.uxMode.value.id)
                    put("bubbleCount", bubbles.size)
                    put("routineCount", routines.size)
                    put("headlineKo", brief.headlineKo)
                    put("summaryKo", brief.summaryKo)
                    put("highlights", highlightsArr)
                    put("quickActions", actionsArr)
                }
                onBridgeEvent?.invoke(path, "200 OK (Portal Brief)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/portal/undo" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"error":"POST required"}""")
                    return
                }
                val engine = HansonRuntimeHolder.getAgentEngine(service.applicationContext)
                val req = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                val receiptId = req.optString("receiptId", "").trim().ifBlank { null }
                val undone = engine.executeOneTapUndo(receiptId)
                val resp = JSONObject().apply {
                    put("status", if (undone) "ok" else "no_reversible_action")
                    put("undone", undone)
                }
                onBridgeEvent?.invoke(path, if (undone) "200 OK (Portal Undo)" else "200 OK (No Undo)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/secretary/proposals" -> {
                if (!"GET".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val appCtx = service.applicationContext
                runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }
                val rawStatus = queryParams["status"]?.trim()?.uppercase()?.ifBlank { "PENDING" } ?: "PENDING"
                val limit = (queryParams["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 200)
                val filtered = if (rawStatus == "ALL") {
                    ProactiveSecretaryEngine.listProposals(statusFilter = null)
                } else {
                    val statusEnum = ProactiveProposalStatus.fromNameOrDefault(rawStatus, ProactiveProposalStatus.PENDING)
                    ProactiveSecretaryEngine.listProposals(statusFilter = statusEnum)
                }.take(limit)

                val arr = JSONArray()
                filtered.forEach { arr.put(it.toJson()) }
                val unreadRooms = NotificationThreadBuffer.activeRoomCount()
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("count", filtered.size)
                    put("unread_rooms", unreadRooms)
                    put("proposals", arr)
                }
                onBridgeEvent?.invoke("GET /secretary/proposals", "200 OK (${filtered.size} items)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/secretary/scan" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val appCtx = service.applicationContext
                runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }
                val parsed = runCatching {
                    if (body.isNotBlank()) JSONObject(body) else JSONObject()
                }.getOrDefault(JSONObject())
                val includeWeather = parsed.optBoolean("include_weather", false)
                val lat = if (parsed.has("lat") && !parsed.isNull("lat")) {
                    parsed.optDouble("lat").takeIf { !it.isNaN() }
                } else {
                    null
                }
                val lon = if (parsed.has("lon") && !parsed.isNull("lon")) {
                    parsed.optDouble("lon").takeIf { !it.isNaN() }
                } else {
                    null
                }
                val emitted = runCatching {
                    HansonRuntimeHolder.getAgentEngine(appCtx).runProactiveSecretaryScan(
                        includeWeather = includeWeather,
                        lat = lat,
                        lon = lon
                    )
                }.getOrElse {
                    ProactiveSecretaryEngine.scanProactiveContext(appCtx)
                }
                val pending = ProactiveSecretaryEngine.listPendingProposals()
                val arr = JSONArray()
                emitted.forEach { arr.put(it.toJson()) }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("new_proposals", emitted.size)
                    put("total_pending", pending.size)
                    put("proposals", arr)
                }
                onBridgeEvent?.invoke("POST /secretary/scan", "200 OK (new=${emitted.size}, pending=${pending.size})")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/secretary/ingest" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val packageName = parsed.optString("package_name", parsed.optString("packageName", "")).trim()
                val appLabel = parsed.optString("app_label", parsed.optString("appLabel", "")).trim()
                val sender = parsed.optString("sender", "").trim()
                val room = parsed.optString("room", parsed.optString("roomOrTitle", "")).trim()
                val text = parsed.optString("text", "")
                val canQuickReply = parsed.optBoolean("can_quick_reply", parsed.optBoolean("canQuickReply", false))
                val timestampMs = parsed.optLong("timestamp_ms", System.currentTimeMillis())
                    .takeIf { it > 0L } ?: System.currentTimeMillis()
                if (packageName.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing package_name"}""")
                    return
                }
                val appCtx = service.applicationContext
                runCatching { ProactiveSecretaryEngine.ensureInitialized(appCtx) }
                val proposal = ProactiveSecretaryEngine.ingestExternalSignal(
                    context = appCtx,
                    packageName = packageName,
                    sender = sender,
                    text = text,
                    appLabel = appLabel,
                    roomOrTitle = room,
                    canQuickReply = canQuickReply,
                    timestampMs = timestampMs
                )
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("accepted", proposal != null)
                    put("proposal", proposal?.toJson() ?: JSONObject.NULL)
                }
                onBridgeEvent?.invoke("POST /secretary/ingest", if (proposal != null) "200 ACCEPTED" else "200 FILTERED")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/secretary/act" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val parsed = runCatching { JSONObject(body) }.getOrNull()
                if (parsed == null) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Invalid JSON body"}""")
                    return
                }
                val proposalId = parsed.optString("proposal_id", parsed.optString("proposalId", "")).trim()
                val action = parsed.optString("action", "approve").trim().ifBlank { "approve" }
                val replyText = parsed.optString("reply_text", parsed.optString("replyText", ""))
                val snoozeMinutes = parsed.optLong("snooze_minutes", parsed.optLong("snoozeMinutes", 60L))
                    .coerceIn(1L, 24L * 60L)
                if (proposalId.isEmpty()) {
                    sendHttpResponse(out, 400, "application/json", """{"status":"error","error":"Missing proposal_id"}""")
                    return
                }
                val appCtx = service.applicationContext
                val engine = HansonRuntimeHolder.getAgentEngine(appCtx)
                val (succeeded, message) = engine.executeProactiveProposal(
                    proposalId = proposalId,
                    actionKey = action,
                    replyText = replyText,
                    snoozeMinutes = snoozeMinutes
                )
                val newStatus = ProactiveSecretaryEngine.getProposal(proposalId)?.status?.name ?: "UNKNOWN"
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("proposal_id", proposalId)
                    put("action", action)
                    put("succeeded", succeeded)
                    put("message", message)
                    put("new_status", newStatus)
                }
                onBridgeEvent?.invoke("POST /secretary/act", if (succeeded) "200 OK ($action)" else "200 FAILED ($action)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/lifestyle/status" -> {
                if (!"GET".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val appCtx = service.applicationContext
                runCatching {
                    LifestyleBeatScheduler.ensureInitialized(appCtx)
                    PrivacyAndQuotaProofLedger.ensureInitialized(appCtx)
                    CommitmentDebtTrackerEngine.ensureInitialized(appCtx)
                    RelationshipCareCadenceEngine.ensureInitialized(appCtx)
                }
                val stormCfg = NotificationStormShieldEngine.config.value
                val stormObj = JSONObject().apply {
                    put("enabled", stormCfg.enabled)
                    put("focusBlockActive", stormCfg.focusBlockActive)
                    put("burstThresholdCount", stormCfg.burstThresholdCount)
                    put("burstWindowMs", stormCfg.burstWindowMs)
                    put("vipCount", stormCfg.vipNames.size)
                    put("heldMessageCount", NotificationStormShieldEngine.heldMessageCount())
                }
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("privacyLedger", PrivacyAndQuotaProofLedger.toJson())
                    put("beats", LifestyleBeatScheduler.toStatusJson())
                    put("vdisplay", HansonVirtualDisplayManager.uiSnapshot.value.toJson())
                    put("stormShield", stormObj)
                    put("vipCount", RelationshipCareCadenceEngine.listVips().size)
                    put("commitmentsCount", CommitmentDebtTrackerEngine.listOpenCommitments().size)
                }
                onBridgeEvent?.invoke("GET /lifestyle/status", "200 OK")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            "/lifestyle/portability/export" -> {
                if (!"POST".equals(method, ignoreCase = true)) {
                    sendHttpResponse(out, 405, "application/json", """{"status":"error","error":"Method Not Allowed"}""")
                    return
                }
                val appCtx = service.applicationContext
                runCatching {
                    LifestyleBeatScheduler.ensureInitialized(appCtx)
                    RelationshipCareCadenceEngine.ensureInitialized(appCtx)
                }
                val engine = HansonRuntimeHolder.getAgentEngine(appCtx)
                val markdown = CrossLabPortabilityPack.exportMarkdown(
                    bubbles = engine.resolvedMemoryBubbles.bubbles.value,
                    routines = engine.resolvedPersonalRoutines.routines.value,
                    vipContacts = RelationshipCareCadenceEngine.listVips(),
                    beats = LifestyleBeatScheduler.beats.value
                )
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("markdown", markdown)
                    put("length", markdown.length)
                }
                onBridgeEvent?.invoke("POST /lifestyle/portability/export", "200 OK (${markdown.length} chars)")
                sendHttpResponse(out, 200, "application/json", resp.toString())
            }
            else -> {
                onBridgeEvent?.invoke(path, "404 Not Found")
                sendHttpResponse(out, 404, "application/json", """{"error":"Not Found: $path"}""")
            }
        }
    }

    private fun handleJsonRpc(jsonLine: String, out: OutputStream) {
        val result = executeJsonCommand(jsonLine, preAuthenticatedHttp = false)
        val bytes = (result.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        out.write(bytes)
        out.flush()
    }

    private fun executeJsonCommand(rawJson: String, preAuthenticatedHttp: Boolean = false): JSONObject {
        val response = JSONObject()
        try {
            val req = JSONObject(rawJson)
            if (requireTokenAuth && !preAuthenticatedHttp && !verifyTokenConstantTime(req.optString("token"), sessionToken)) {
                response.put("status", "error")
                response.put("error", "Unauthorized token")
                return response
            }
            val cmd = req.optString("cmd", req.optString("action", req.optString("method")))
            val params = req.optJSONObject("params") ?: req
            var success = false

            when (cmd.lowercase()) {
                "ping" -> success = true
                "tap", "click" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    val times = params.optInt("times", 1)
                    val delay = params.optLong("delay", 120L)
                    success = withTemporaryHudPassThrough {
                        GestureController.multiTap(service, x, y, times, delay)
                    }
                }
                "double_tap" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    success = withTemporaryHudPassThrough {
                        GestureController.doubleTap(service, x, y)
                    }
                }
                "long_press" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    val duration = params.optLong("duration", 700L)
                    success = withTemporaryHudPassThrough {
                        GestureController.longPress(service, x, y, duration)
                    }
                }
                "swipe" -> {
                    val x1 = params.optDouble("x1", params.optDouble("startX", 0.0)).toFloat()
                    val y1 = params.optDouble("y1", params.optDouble("startY", 0.0)).toFloat()
                    val x2 = params.optDouble("x2", params.optDouble("endX", 0.0)).toFloat()
                    val y2 = params.optDouble("y2", params.optDouble("endY", 0.0)).toFloat()
                    val duration = params.optLong("duration", 350L)
                    success = withTemporaryHudPassThrough {
                        GestureController.swipe(service, x1, y1, x2, y2, duration)
                    }
                }
                "click_sequence" -> {
                    val arr: JSONArray? = params.optJSONArray("points") ?: params.optJSONArray("sequence")
                    val delay = params.optLong("delay_ms", 180L)
                    val pts = mutableListOf<Pair<Float, Float>>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONArray(i)
                            if (item != null && item.length() >= 2) {
                                pts.add(item.optDouble(0).toFloat() to item.optDouble(1).toFloat())
                            }
                        }
                    }
                    success = withTemporaryHudPassThrough {
                        GestureController.clickSequence(service, pts, delay)
                    }
                }
                "type", "input_text" -> {
                    val text = params.optString("text", "")
                    val clear = params.optBoolean("clear", false)
                    success = withTemporaryHudPassThrough {
                        GestureController.inputText(service, text, clearExisting = clear)
                    }
                }
                "clear" -> {
                    success = GestureController.clearText(service)
                }
                "clipboard" -> {
                    val text = params.optString("text", "")
                    success = GestureController.setClipboard(service, text)
                }
                "global", "press_key" -> {
                    val action = params.optString("action", params.optString("key", "HOME"))
                    success = GestureController.performGlobalKey(service, action)
                }
                "click_element", "launch_app", "list_apps", "open_url", "open_link", "wait", "wait_for_delay", "observe_screen", "notifications", "reply_notification", "device_setting", "tool" -> {
                    val harness = com.hanson.mobileuse.agent.AgentToolExecutionHarness(service.applicationContext)
                    val snap = HierarchyDumper.captureAtomicSnapshot(service, includeScreenshot = false)
                    val (rawMappedTool, mappedArgs) = when (cmd.lowercase()) {
                        "click_element" -> "click" to params
                        "launch_app" -> "manage_app" to JSONObject().apply {
                            put("action", "launch")
                            put("app_name", params.optString("app_name", params.optString("package", "")))
                        }
                        "list_apps" -> "manage_app" to JSONObject().apply {
                            put("action", params.optString("filter_action", "list"))
                            put("app_name", params.optString("query", ""))
                        }
                        "open_url", "open_link" -> "open_link" to JSONObject().put("url", params.optString("url", ""))
                        "wait", "wait_for_delay" -> "wait_for_delay" to params
                        "observe_screen" -> "observe_screen" to params
                        "notifications" -> "list_notifications" to params
                        "reply_notification" -> "reply_notification" to params
                        "device_setting" -> "control_device_setting" to params
                        else -> params.optString("tool_name", params.optString("name", "")) to (params.optJSONObject("args") ?: params)
                    }
                    val mappedTool = normalizeToolAlias(rawMappedTool)
                    val outcome = harness.execute(mappedTool, mappedArgs, snap)
                    success = outcome.succeeded
                    response.put("output", outcome.outputText)
                }
                else -> {
                    response.put("status", "error")
                    response.put("error", "Unknown command: $cmd")
                    return response
                }
            }
            response.put("status", if (success) "ok" else "failed")
            response.put("success", success)
        } catch (e: Exception) {
            response.put("status", "error")
            response.put("error", e.message ?: "JSON command execution error")
        }
        return response
    }

    private fun streamBoardSse(sock: Socket, out: OutputStream, fromSeq: Long) {
        val sseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = LinkedBlockingQueue<DynamicSseEnvelope>(256)
        val collectJob = sseScope.launch {
            DynamicUiEventBus.envelopes.collect { env ->
                if (!queue.offer(env)) {
                    queue.poll()
                    queue.offer(env)
                }
            }
        }
        try {
            sock.soTimeout = 0
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream; charset=utf-8\r\n")
                append("Cache-Control: no-cache, no-transform\r\n")
                append("Connection: keep-alive\r\n")
                append("X-Accel-Buffering: no\r\n")
                append("X-Content-Type-Options: nosniff\r\n\r\n")
            }
            out.write(headers.toByteArray(StandardCharsets.UTF_8))
            out.flush()

            // Replay buffered events strictly newer than `fromSeq` (`Last-Event-ID`)
            var lastSent = fromSeq
            for (env in DynamicUiEventBus.replayEnvelopesSince(fromSeq)) {
                out.write(env.toSseWireFrame().toByteArray(StandardCharsets.UTF_8))
                lastSent = maxOf(lastSent, env.sequenceId)
            }
            out.write(": connected seq=$lastSent\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()

            while (isRunning.get() && !sock.isClosed) {
                val next = queue.poll(15, TimeUnit.SECONDS)
                if (next != null) {
                    if (next.sequenceId > lastSent) {
                        out.write(next.toSseWireFrame().toByteArray(StandardCharsets.UTF_8))
                        out.flush()
                        lastSent = next.sequenceId
                    }
                } else {
                    out.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                }
            }
        } catch (_: Exception) {
            // Client disconnected cleanly
        } finally {
            collectJob.cancel()
        }
    }

    private fun sendHttpResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: String
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    private fun normalizeToolAlias(rawToolName: String): String {
        return when (rawToolName.trim().lowercase()) {
            "open_url" -> "open_link"
            "wait" -> "wait_for_delay"
            else -> rawToolName.trim()
        }
    }

    private inline fun <T> withTemporaryHudPassThrough(block: () -> T): T {
        com.hanson.mobileuse.service.FloatingHudService.setPassThroughTemporarily(true)
        return try {
            block()
        } finally {
            com.hanson.mobileuse.service.FloatingHudService.setPassThroughTemporarily(false)
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
