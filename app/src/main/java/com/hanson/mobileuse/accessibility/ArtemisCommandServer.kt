package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import com.hanson.mobileuse.agent.ChatSender
import com.hanson.mobileuse.agent.HansonRuntimeHolder
import com.hanson.mobileuse.agent.LiveChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Google Artemis Loopback Bridge (`127.0.0.1:18888`)
 * Supports `/ping`, `/snapshot`, `/dump`, `/dump_xml`, `/elements`, `/action`, `/rpc`,
 * AND `/chat` for continuous runtime Live Chat synchronization with the Laptop CLI (Antigravity / Codex / Artemis).
 */
class ArtemisCommandServer(
    private val service: AccessibilityService,
    val port: Int = DEFAULT_PORT,
    var requireTokenAuth: Boolean = false
) {
    companion object {
        private const val TAG = "ArtemisCommandServer"
        const val DEFAULT_PORT = 18888
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
                    handleHttp(firstLine, rawIn, out)
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

    private fun handleHttp(requestLine: String, rawIn: java.io.InputStream, out: OutputStream) {
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
        var headerLine = readAsciiLine(rawIn)
        while (!headerLine.isNullOrEmpty()) {
            if (headerLine.lowercase().startsWith("content-length:")) {
                contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
            } else if (headerLine.lowercase().startsWith("x-artemis-token:")) {
                queryParams["token"] = headerLine.substringAfter(":").trim()
            }
            headerLine = readAsciiLine(rawIn)
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

        if (requireTokenAuth && queryParams["token"] != sessionToken) {
            onBridgeEvent?.invoke(path, "401 Unauthorized")
            sendHttpResponse(out, 401, "application/json", """{"status":"error","error":"Unauthorized token"}""")
            return
        }

        when (path) {
            "/.well-known/agent-card.json", "/agent-card.json" -> {
                val card = JSONObject().apply {
                    put("name", "Hanson Mobile-Use Agent")
                    put("description", "Open-Source Full-Control Android Mobile-Use AI Agent with FG/BG Multi-Lane Parallel Execution, S25 On-Device NPU Routing, and Clean-Room App Reverse Engineering.")
                    put("version", "2.4.0")
                    put("url", "http://127.0.0.1:$port")
                    put("repository", "https://github.com/cjw0076/hanson-mobile-use")
                    put("protocol", "A2A/1.0")
                    put(
                        "capabilities",
                        JSONObject().apply {
                            put("streaming", false)
                            put("pushNotifications", true)
                            put("stateTransitionHistory", true)
                            put("parallelLanes", JSONArray().put("FG").put("BG").put("AUTO"))
                        }
                    )
                    put(
                        "endpoints",
                        JSONObject().apply {
                            put("a2a", "/a2a")
                            put("chat", "/chat")
                            put("mcpTools", "/mcp/tools")
                            put("snapshot", "/snapshot")
                            put("elements", "/elements")
                        }
                    )
                }
                onBridgeEvent?.invoke(path, "200 OK (A2A Card)")
                sendHttpResponse(out, 200, "application/json", card.toString())
            }
            "/mcp/tools" -> {
                val toolsJson = com.hanson.mobileuse.agent.ToolSpecRegistry.toGeminiToolsJson()
                onBridgeEvent?.invoke(path, "200 OK (MCP Tools)")
                sendHttpResponse(out, 200, "application/json", toolsJson.toString())
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
                val snapshot = HierarchyDumper.captureAtomicSnapshot(
                    service = service,
                    includeScreenshot = includeScreenshot,
                    filterInvisible = queryParams["filter"] != "false"
                )
                onBridgeEvent?.invoke(path, "200 OK (${snapshot.indexedElements.size} els)")
                sendHttpResponse(out, 200, "application/json", snapshot.toJson(includeFullTree = true).toString())
            }
            "/dump", "/elements" -> {
                val snapshot = HierarchyDumper.captureAtomicSnapshot(
                    service = service,
                    includeScreenshot = false,
                    filterInvisible = queryParams["filter"] != "false"
                )
                onBridgeEvent?.invoke(path, "200 OK (${snapshot.indexedElements.size} els)")
                sendHttpResponse(out, 200, "application/json", snapshot.toJson(includeFullTree = path == "/dump").toString())
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
                val result = executeJsonCommand(body)
                onBridgeEvent?.invoke(path, "200 OK")
                sendHttpResponse(out, 200, "application/json", result.toString())
            }
            else -> {
                onBridgeEvent?.invoke(path, "404 Not Found")
                sendHttpResponse(out, 404, "application/json", """{"error":"Not Found: $path"}""")
            }
        }
    }

    private fun handleJsonRpc(jsonLine: String, out: OutputStream) {
        val result = executeJsonCommand(jsonLine)
        val bytes = (result.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
        out.write(bytes)
        out.flush()
    }

    private fun executeJsonCommand(rawJson: String): JSONObject {
        val response = JSONObject()
        try {
            val req = JSONObject(rawJson)
            if (requireTokenAuth && req.optString("token") != sessionToken) {
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
                    success = GestureController.multiTap(service, x, y, times, delay)
                }
                "double_tap" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    success = GestureController.doubleTap(service, x, y)
                }
                "long_press" -> {
                    val x = params.optDouble("x", 0.0).toFloat()
                    val y = params.optDouble("y", 0.0).toFloat()
                    val duration = params.optLong("duration", 700L)
                    success = GestureController.longPress(service, x, y, duration)
                }
                "swipe" -> {
                    val x1 = params.optDouble("x1", params.optDouble("startX", 0.0)).toFloat()
                    val y1 = params.optDouble("y1", params.optDouble("startY", 0.0)).toFloat()
                    val x2 = params.optDouble("x2", params.optDouble("endX", 0.0)).toFloat()
                    val y2 = params.optDouble("y2", params.optDouble("endY", 0.0)).toFloat()
                    val duration = params.optLong("duration", 350L)
                    success = GestureController.swipe(service, x1, y1, x2, y2, duration)
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
                    success = GestureController.clickSequence(service, pts, delay)
                }
                "type", "input_text" -> {
                    val text = params.optString("text", "")
                    val clear = params.optBoolean("clear", false)
                    success = GestureController.inputText(service, text, clearExisting = clear)
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

    private fun sendHttpResponse(
        out: OutputStream,
        statusCode: Int,
        contentType: String,
        body: String
    ) {
        val statusText = when (statusCode) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(StandardCharsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
