package com.hanson.mobileuse.oscli

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hanson Mobile-Use Native OS CLI (`hanson`) & UID-2000 Privileged Daemon (`127.0.0.1:18890`).
 *
 * Launched directly inside the Android OS via `app_process`:
 *   CLASSPATH=/data/local/tmp/hanson-os.apk /system/bin/app_process /system/bin com.hanson.mobileuse.oscli.HansonOsCliMain [args...]
 *
 * Runs as UID 2000 (`shell`) with kernel groups:
 *   `input(1004)`, `log(1007)`, `adb(1011)`, `sdcard_rw(1015)`, `inet(3003)`, `readproc(3009)`, `uhid(3011)`.
 *
 * Persists across USB unplugs when started in `daemon` mode, giving both the user's terminal
 * and the on-device Hanson AI Agent full UID-2000 OS CLI power over localhost `:18890`.
 */
object HansonOsCliMain {
    const val DAEMON_PORT = 18890
    private val requestCounter = AtomicInteger(0)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] == "help" || args[0] == "--help" || args[0] == "-h") {
            printBannerAndHelp()
            return
        }

        when (val sub = args[0].lowercase()) {
            "daemon" -> startPrivilegedDaemon()
            "status" -> println(executeCommandJson("status", emptyList()).toString(2))
            "top" -> println(executeCommandJson("top", emptyList()).toString(2))
            "tree" -> println(executeCommandJson("tree", emptyList()).toString(2))
            "tap" -> {
                val rest = args.drop(1)
                println(executeCommandJson("tap", rest).toString(2))
            }
            "swipe" -> {
                val rest = args.drop(1)
                println(executeCommandJson("swipe", rest).toString(2))
            }
            "text" -> {
                val rest = args.drop(1)
                println(executeCommandJson("text", rest).toString(2))
            }
            "key" -> {
                val rest = args.drop(1)
                println(executeCommandJson("key", rest).toString(2))
            }
            "app" -> {
                val rest = args.drop(1)
                println(executeCommandJson("app", rest).toString(2))
            }
            "setting" -> {
                val rest = args.drop(1)
                println(executeCommandJson("setting", rest).toString(2))
            }
            "dump" -> {
                val rest = args.drop(1)
                println(executeCommandJson("dump", rest).toString(2))
            }
            "provider" -> {
                val rest = args.drop(1)
                println(executeCommandJson("provider", rest).toString(2))
            }
            "sh", "exec" -> {
                val cmd = args.drop(1).joinToString(" ")
                val res = runRawShell(cmd)
                println(res.optString("stdout"))
                val err = res.optString("stderr")
                if (err.isNotBlank()) System.err.println(err)
            }
            else -> {
                // Treat arbitrary subcommands as shell commands or pass-through
                val res = executeCommandJson(sub, args.drop(1))
                println(res.toString(2))
            }
        }
    }

    private fun printBannerAndHelp() {
        println(
            """
            ===================================================================
             Hanson Mobile-Use OS CLI (v1.2) — Android UID-2000 System Harness
            ===================================================================
            Usage:
              hanson status                         Show UID/GID, kernel groups, top activity & bridges
              hanson top                            Inspect foreground Activity, Fragment & Window stack
              hanson tree                           Dump hybrid Accessibility + WindowManager UI tree
              hanson tap <x> <y>                    Hardware input tap via UID-2000 (bypasses FLAG_SECURE)
              hanson swipe <x1> <y1> <x2> <y2> [ms] Hardware input swipe via UID-2000
              hanson text "<string>"                Inject text input via input/clipboard
              hanson key <KEYCODE>                  Send hardware keyevent (e.g. HOME, BACK, ENTER, 66)
              hanson app list|open|stop|grant ...   Package & AppOps manager (force-stop, pm grant, etc.)
              hanson setting get|put <ns> <k> [v]   Read/write system, secure, or global OS settings
              hanson dump <service>                 Run privileged dumpsys (activity, window, meminfo, etc.)
              hanson provider query <content-uri>   Query Android ContentProviders directly as UID-2000
              hanson sh "<shell-pipeline>"          Execute arbitrary UID-2000 Linux/Android shell command
              hanson daemon                         Run persistent UID-2000 RPC daemon on 127.0.0.1:18890
            """.trimIndent()
        )
    }

    fun executeCommandJson(action: String, args: List<String>): JSONObject {
        return when (action.lowercase()) {
            "status" -> {
                val idOut = runRawShell("id").optString("stdout").trim()
                val topActivity = runRawShell("dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity' | head -n 3")
                    .optString("stdout").trim()
                val wmSize = runRawShell("wm size").optString("stdout").trim()
                val battery = runRawShell("dumpsys battery | grep -E 'level|status|temperature'").optString("stdout").trim()
                JSONObject().apply {
                    put("status", "ok")
                    put("engine", "Hanson-OS-CLI-UID2000")
                    put("daemonPort", DAEMON_PORT)
                    put("identity", idOut)
                    put("display", wmSize)
                    put("topActivity", topActivity)
                    put("battery", battery)
                    put("requestsHandled", requestCounter.get())
                }
            }

            "top" -> {
                val topDump = runRawShell(
                    "dumpsys activity top | grep -E 'ACTIVITY|View Hierarchy:|Looper|mFocusedApp|Resumed' -A 3 | head -n 45"
                ).optString("stdout").trim()
                val winDump = runRawShell(
                    "dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp' | head -n 6"
                ).optString("stdout").trim()
                JSONObject().apply {
                    put("status", "ok")
                    put("windowFocus", winDump)
                    put("activityTop", topDump)
                }
            }

            "tree" -> {
                // Combine :18888 semantic accessibility tree with UID-2000 window focus metadata
                val winFocus = runRawShell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4")
                    .optString("stdout").trim()
                val a11yTree = try {
                    val conn = (URL("http://127.0.0.1:18888/tree").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 1500
                        readTimeout = 2000
                    }
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Throwable) {
                    "{}"
                }
                JSONObject().apply {
                    put("status", "ok")
                    put("windowFocus", winFocus)
                    put("accessibilityTree", runCatching { JSONObject(a11yTree) }.getOrDefault(JSONObject()))
                }
            }

            "tap" -> {
                val x = args.getOrNull(0)?.toIntOrNull() ?: 540
                val y = args.getOrNull(1)?.toIntOrNull() ?: 1200
                val r = runRawShell("input tap $x $y")
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("action", "input tap $x $y")
                    put("detail", r)
                }
            }

            "swipe" -> {
                val x1 = args.getOrNull(0)?.toIntOrNull() ?: 540
                val y1 = args.getOrNull(1)?.toIntOrNull() ?: 1600
                val x2 = args.getOrNull(2)?.toIntOrNull() ?: 540
                val y2 = args.getOrNull(3)?.toIntOrNull() ?: 400
                val dur = args.getOrNull(4)?.toIntOrNull() ?: 250
                val r = runRawShell("input swipe $x1 $y1 $x2 $y2 $dur")
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("action", "input swipe $x1 $y1 $x2 $y2 $dur")
                    put("detail", r)
                }
            }

            "text" -> {
                val raw = args.joinToString(" ")
                val escaped = raw.replace(" ", "%s").replace("'", "")
                val r = runRawShell("input text '$escaped'")
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("text", raw)
                    put("detail", r)
                }
            }

            "key" -> {
                val key = args.getOrNull(0) ?: "KEYCODE_HOME"
                val r = runRawShell("input keyevent $key")
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("key", key)
                    put("detail", r)
                }
            }

            "app" -> {
                val subOp = args.getOrNull(0)?.lowercase() ?: "list"
                val pkg = args.getOrNull(1) ?: ""
                val cmd = when (subOp) {
                    "list" -> "pm list packages -3 | sed 's/package://g' | sort"
                    "open", "start" -> "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
                    "stop", "kill" -> "am force-stop $pkg"
                    "clear" -> "pm clear $pkg"
                    "grant" -> {
                        val perm = args.getOrNull(2) ?: ""
                        "pm grant $pkg $perm"
                    }
                    "appops" -> {
                        val op = args.getOrNull(2) ?: ""
                        val mode = args.getOrNull(3) ?: "allow"
                        "appops set $pkg $op $mode"
                    }
                    else -> "dumpsys package $pkg | head -n 40"
                }
                runRawShell(cmd)
            }

            "setting" -> {
                val op = args.getOrNull(0)?.lowercase() ?: "get"
                val ns = args.getOrNull(1)?.lowercase() ?: "system"
                val key = args.getOrNull(2) ?: ""
                val value = args.drop(3).joinToString(" ")
                val cmd = if (op == "put") {
                    "settings put $ns $key $value"
                } else if (op == "list") {
                    "settings list $ns | head -n 60"
                } else {
                    "settings get $ns $key"
                }
                runRawShell(cmd)
            }

            "dump" -> {
                val service = args.joinToString(" ").ifBlank { "activity activities" }
                runRawShell("dumpsys $service | head -n 80")
            }

            "provider" -> {
                val op = args.getOrNull(0)?.lowercase() ?: "query"
                val uri = args.getOrNull(1) ?: "content://settings/system"
                runRawShell("content $op --uri $uri | head -n 50")
            }

            else -> {
                val fullCmd = (listOf(action) + args).joinToString(" ")
                runRawShell(fullCmd)
            }
        }
    }

    fun runRawShell(command: String, timeoutSec: Long = 10L): JSONObject {
        val startMs = System.currentTimeMillis()
        return try {
            val proc = ProcessBuilder("sh", "-c", command).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { r ->
                    var line: String?
                    var lines = 0
                    while (r.readLine().also { line = it } != null) {
                        if (lines < 120) {
                            stdout.append(line).append('\n')
                        }
                        lines++
                    }
                }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8)).use { r ->
                    var line: String?
                    var lines = 0
                    while (r.readLine().also { line = it } != null) {
                        if (lines < 60) {
                            stderr.append(line).append('\n')
                        }
                        lines++
                    }
                }
            }
            outThread.start()
            errThread.start()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
            }
            outThread.join(1000)
            errThread.join(1000)

            JSONObject().apply {
                put("status", if (finished && proc.exitValue() == 0) "ok" else "error")
                put("command", command)
                put("exitCode", if (finished) proc.exitValue() else -1)
                put("stdout", stdout.toString().trim())
                put("stderr", stderr.toString().trim())
                put("durationMs", System.currentTimeMillis() - startMs)
            }
        } catch (t: Throwable) {
            JSONObject().apply {
                put("status", "error")
                put("command", command)
                put("error", t.message ?: "Shell execution error")
            }
        }
    }

    /**
     * Persistent UID-2000 RPC server on `127.0.0.1:18890`.
     * Allows the sandboxed Android app (`u0_aXXX`) to execute UID-2000 (`shell`) commands at any time,
     * even when unplugged from the laptop!
     */
    private fun startPrivilegedDaemon() {
        val server = ServerSocket(DAEMON_PORT, 32, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        println("[Hanson-OS-CLI] UID-2000 Daemon listening on 127.0.0.1:$DAEMON_PORT")
        while (true) {
            val client = server.accept()
            pool.execute { handleDaemonClient(client) }
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

    private fun handleDaemonClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 12_000
            val rawIn = s.getInputStream()
            val requestLine = readAsciiLine(rawIn) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var line = readAsciiLine(rawIn)
            while (!line.isNullOrEmpty()) {
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = readAsciiLine(rawIn)
            }

            val bodyStr = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = rawIn.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            requestCounter.incrementAndGet()

            val responseJson = when {
                path == "/ping" || path == "/status" -> executeCommandJson("status", emptyList())
                path == "/top" -> executeCommandJson("top", emptyList())
                path == "/exec" && method == "POST" -> {
                    val req = runCatching { JSONObject(bodyStr) }.getOrDefault(JSONObject())
                    val cmd = req.optString("command", "")
                    val action = req.optString("action", "")
                    if (cmd.isNotBlank()) {
                        runRawShell(cmd)
                    } else {
                        val argsArr = req.optJSONArray("args") ?: JSONArray()
                        val argList = (0 until argsArr.length()).map { argsArr.optString(it) }
                        executeCommandJson(action.ifBlank { "status" }, argList)
                    }
                }
                else -> JSONObject().put("error", "Unknown path $path")
            }

            val payload = responseJson.toString().toByteArray(Charsets.UTF_8)
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json; charset=utf-8\r\n")
            writer.write("Content-Length: ${payload.size}\r\n")
            writer.write("Connection: close\r\n\r\n")
            writer.flush()
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
        }
    }
}
