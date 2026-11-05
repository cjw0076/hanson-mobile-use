package com.hanson.mobileuse.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
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
 * Compatible with `packages/artemis-accessibility-helper` HTTP & raw JSON-RPC protocol,
 * plus extended `/elements` and `/action` endpoints for local MCP/ADB/Termux agents.
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
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                val out = sock.getOutputStream()
                val firstLine = reader.readLine() ?: return
                totalRequests.incrementAndGet()

                if (firstLine.startsWith("GET ") || firstLine.startsWith("POST ")) {
                    handleHttp(firstLine, reader, out)
                } else {
                    handleJsonRpc(firstLine, out)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Client handler error: ${e.message}")
            }
        }
    }

    private fun handleHttp(requestLine: String, reader: BufferedReader, out: OutputStream) {
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
        var headerLine = reader.readLine()
        while (!headerLine.isNullOrEmpty()) {
            if (headerLine.lowercase().startsWith("content-length:")) {
                contentLength = headerLine.substringAfter(":").trim().toIntOrNull() ?: 0
            } else if (headerLine.lowercase().startsWith("x-artemis-token:")) {
                queryParams["token"] = headerLine.substringAfter(":").trim()
            }
            headerLine = reader.readLine()
        }

        var body = ""
        if ("POST".equals(method, ignoreCase = true) && contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val r = reader.read(buf, read, contentLength - read)
                if (r == -1) break
                read += r
            }
            body = String(buf, 0, read)
        }

        if (requireTokenAuth && queryParams["token"] != sessionToken) {
            onBridgeEvent?.invoke(path, "401 Unauthorized")
            sendHttpResponse(out, 401, "application/json", """{"status":"error","error":"Unauthorized token"}""")
            return
        }

        when (path) {
            "/ping", "/health" -> {
                val resp = JSONObject().apply {
                    put("status", "ok")
                    put("engine", "Hanson-Artemis-Hybrid-v1")
                    put("port", port)
                    put("requests", totalRequests.get())
                }
                onBridgeEvent?.invoke(path, "200 OK")
                sendHttpResponse(out, 200, "application/json", resp.toString())
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
