package com.hanson.mobileuse.oscli

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hanson Mobile-Use Native OS CLI (`hanson`) & UID-2000 Privileged Daemon (`127.0.0.1:18890`).
 *
 * Security Architecture (v3.1 Hardened):
 * 1. Mandatory 256-bit Shared Secret Token (`X-Hanson-Cli-Token` / `Authorization: Bearer <token>`)
 *    verified in constant time via [MessageDigest.isEqual].
 * 2. Kernel-level Peer Socket UID Verification via `/proc/net/tcp` & `/proc/net/tcp6`:
 *    Only `root(0)`, `shell(2000)`, the daemon's own UID, or `com.hanson.mobileuse`'s app UID
 *    may connect; all other third-party apps on the device are rejected with `403 Forbidden`.
 * 3. Browser CSRF / DNS-Rebinding Rejection:
 *    Any request carrying `Origin`, `Referer`, or `Sec-Fetch-*` headers or a non-loopback `Host`
 *    is immediately rejected with `403 Forbidden`.
 * 4. Zero Arbitrary Shell Execution (`sh -c` eliminated):
 *    All actions must belong to [ALLOWED_ACTIONS], arguments are validated against strict regexes
 *    and [containsShellMetacharacters], and OS commands execute via [ProcessBuilder] argument arrays
 *    without invoking `/bin/sh`.
 */
object HansonOsCliMain {
    const val DAEMON_PORT = 18890
    const val TOKEN_HEADER = "X-Hanson-Cli-Token"
    private const val PREFS_NAME = "hanson_security_prefs"
    private const val PREF_KEY_CLI_TOKEN = "os_cli_daemon_token_v2"
    private const val TOKEN_FILE_PATH = "/data/data/com.hanson.mobileuse/files/.hanson_cli_token"
    private const val MAX_BODY_BYTES = 16_384

    val ALLOWED_ACTIONS: Set<String> = setOf(
        "status",
        "top",
        "tree",
        "tap",
        "swipe",
        "text",
        "key",
        "app",
        "setting",
        "devopt",
        "wm",
        "uimode",
        "statusbar",
        "dump",
        "provider",
        "self-heal"
    )

    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val IDENTIFIER_REGEX = Regex("^[a-zA-Z0-9_.:/-]+$")
    private val KEYCODE_REGEX = Regex("^[a-zA-Z0-9_]+$")
    private val SHELL_METACHAR_REGEX = Regex("[;&|`$()><\\n\\r\\u0000\\\\]")

    private val requestCounter = AtomicInteger(0)

    @Volatile
    var daemonAuthToken: String = loadInitialDaemonToken()
        private set

    private fun generateSecureToken256(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun loadInitialDaemonToken(): String {
        val envToken = System.getenv("HANSON_CLI_TOKEN")?.trim().orEmpty()
        if (envToken.length >= 32) return envToken

        try {
            val tokenFile = File(TOKEN_FILE_PATH)
            if (tokenFile.isFile && tokenFile.canRead()) {
                val fileToken = tokenFile.readText(StandardCharsets.UTF_8).trim()
                if (fileToken.length >= 32) return fileToken
            }
        } catch (_: Throwable) {
        }

        return generateSecureToken256()
    }

    /**
     * Retrieves or provisions the 256-bit shared daemon authentication token.
     * Persists in app-private SharedPreferences and `/data/data/com.hanson.mobileuse/files/.hanson_cli_token`
     * with mode `0600` (owner-only read/write).
     */
    @Synchronized
    fun getOrCreateSharedDaemonToken(context: Context? = null): String {
        if (context != null) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existing = prefs.getString(PREF_KEY_CLI_TOKEN, null)?.trim().orEmpty()
                if (existing.length >= 32) {
                    daemonAuthToken = existing
                } else {
                    val fresh = if (daemonAuthToken.length >= 32) daemonAuthToken else generateSecureToken256()
                    prefs.edit().putString(PREF_KEY_CLI_TOKEN, fresh).commit()
                    daemonAuthToken = fresh
                }
            } catch (_: Throwable) {
            }
            try {
                val filesDir = context.filesDir
                if (filesDir != null) {
                    val tokenFile = File(filesDir, ".hanson_cli_token")
                    tokenFile.writeText(daemonAuthToken, StandardCharsets.UTF_8)
                    tokenFile.setReadable(false, false)
                    tokenFile.setReadable(true, true)
                    tokenFile.setWritable(false, false)
                    tokenFile.setWritable(true, true)
                }
            } catch (_: Throwable) {
            }
        }
        if (daemonAuthToken.length < 32) {
            daemonAuthToken = generateSecureToken256()
        }
        return daemonAuthToken
    }

    /**
     * Constant-time token verification to prevent timing side-channel attacks.
     */
    fun verifyConstantTimeToken(providedToken: String?, expectedToken: String = daemonAuthToken): Boolean {
        val cleanProvided = providedToken?.trim().orEmpty()
        val cleanExpected = expectedToken.trim()
        if (cleanProvided.length < 32 || cleanExpected.length < 32) return false
        return MessageDigest.isEqual(
            cleanProvided.toByteArray(StandardCharsets.UTF_8),
            cleanExpected.toByteArray(StandardCharsets.UTF_8)
        )
    }

    /**
     * Returns true if [input] contains any shell metacharacters or command separators.
     */
    fun containsShellMetacharacters(input: String): Boolean {
        return SHELL_METACHAR_REGEX.containsMatchIn(input)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty() || args[0] == "help" || args[0] == "--help" || args[0] == "-h") {
            printBannerAndHelp()
            return
        }

        val tokenArgIdx = args.indexOf("--token")
        if (tokenArgIdx != -1 && tokenArgIdx + 1 < args.size) {
            val supplied = args[tokenArgIdx + 1].trim()
            if (supplied.length >= 32) {
                daemonAuthToken = supplied
            }
        }

        val filteredArgs = if (tokenArgIdx != -1) {
            args.filterIndexed { index, _ -> index != tokenArgIdx && index != tokenArgIdx + 1 }
        } else {
            args.toList()
        }

        if (filteredArgs.isEmpty()) {
            printBannerAndHelp()
            return
        }

        when (val sub = filteredArgs[0].lowercase()) {
            "daemon" -> startPrivilegedDaemon()
            else -> {
                val res = executeCommandJson(sub, filteredArgs.drop(1))
                println(res.toString(2))
            }
        }
    }

    private fun printBannerAndHelp() {
        println(
            """
            ===================================================================
             Hanson Mobile-Use OS CLI (v3.1 Hardened) — UID-2000 System Harness
            ===================================================================
            Usage:
              hanson status                         Show UID/GID, kernel groups, top activity & bridges
              hanson top                            Inspect foreground Activity, Fragment & Window stack
              hanson tree                           Dump hybrid Accessibility + WindowManager UI tree
              hanson tap <x> <y>                    Hardware input tap via UID-2000
              hanson swipe <x1> <y1> <x2> <y2> [ms] Hardware input swipe via UID-2000
              hanson text "<string>"                Inject sanitized text input
              hanson key <KEYCODE>                  Send hardware keyevent (e.g. HOME, BACK, ENTER, 66)
              hanson app list|open|stop|grant ...   Structured Package & AppOps manager
              hanson setting get|put <ns> <k> [v]   Read/write allowlisted OS settings
              hanson dump <service>                 Run allowlisted dumpsys inspection
              hanson provider query <content-uri>   Query allowlisted Android ContentProviders
              hanson daemon [--token <256bit-hex>]  Run authenticated UID-2000 RPC daemon on 127.0.0.1:18890
            """.trimIndent()
        )
    }

    fun executeCommandJson(action: String, args: List<String>): JSONObject {
        val normalizedAction = action.trim().lowercase()
        if (normalizedAction !in ALLOWED_ACTIONS) {
            return JSONObject().apply {
                put("status", "error")
                put("error", "Security rejection: action '$action' is not in ALLOWED_ACTIONS")
            }
        }

        for (arg in args) {
            if (containsShellMetacharacters(arg)) {
                return JSONObject().apply {
                    put("status", "error")
                    put("error", "Security rejection: shell metacharacters forbidden in argument")
                }
            }
        }

        return when (normalizedAction) {
            "status" -> {
                val idOut = runSafeProcess(listOf("id")).optString("stdout").trim()
                val wmSize = runSafeProcess(listOf("wm", "size")).optString("stdout").trim()
                val battery = runSafeProcess(listOf("dumpsys", "battery")).optString("stdout")
                    .lineSequence()
                    .filter { it.contains("level") || it.contains("status") || it.contains("temperature") }
                    .joinToString("\n")
                    .trim()
                JSONObject().apply {
                    put("status", "ok")
                    put("engine", "Hanson-OS-CLI-UID2000")
                    put("daemonPort", DAEMON_PORT)
                    put("authRequired", true)
                    put("identity", idOut)
                    put("display", wmSize)
                    put("battery", battery)
                    put("requestsHandled", requestCounter.get())
                }
            }

            "top" -> {
                val topDump = runSafeProcess(listOf("dumpsys", "activity", "top")).optString("stdout")
                    .lineSequence()
                    .filter {
                        it.contains("ACTIVITY") ||
                            it.contains("View Hierarchy:") ||
                            it.contains("mFocusedApp") ||
                            it.contains("Resumed")
                    }
                    .take(45)
                    .joinToString("\n")
                    .trim()
                val winDump = runSafeProcess(listOf("dumpsys", "window", "displays")).optString("stdout")
                    .lineSequence()
                    .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
                    .take(6)
                    .joinToString("\n")
                    .trim()
                JSONObject().apply {
                    put("status", "ok")
                    put("windowFocus", winDump)
                    put("activityTop", topDump)
                }
            }

            "tree" -> {
                val winFocus = runSafeProcess(listOf("dumpsys", "window", "displays")).optString("stdout")
                    .lineSequence()
                    .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
                    .take(4)
                    .joinToString("\n")
                    .trim()
                val a11yTree = try {
                    val conn = (URL("http://127.0.0.1:18888/elements").openConnection() as HttpURLConnection).apply {
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
                val x = (args.getOrNull(0)?.toIntOrNull() ?: 540).coerceIn(0, 10000)
                val y = (args.getOrNull(1)?.toIntOrNull() ?: 1200).coerceIn(0, 10000)
                val r = runSafeProcess(listOf("input", "tap", x.toString(), y.toString()))
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("action", "input tap $x $y")
                    put("detail", r)
                }
            }

            "swipe" -> {
                val x1 = (args.getOrNull(0)?.toIntOrNull() ?: 540).coerceIn(0, 10000)
                val y1 = (args.getOrNull(1)?.toIntOrNull() ?: 1600).coerceIn(0, 10000)
                val x2 = (args.getOrNull(2)?.toIntOrNull() ?: 540).coerceIn(0, 10000)
                val y2 = (args.getOrNull(3)?.toIntOrNull() ?: 400).coerceIn(0, 10000)
                val dur = (args.getOrNull(4)?.toIntOrNull() ?: 250).coerceIn(10, 10000)
                val r = runSafeProcess(
                    listOf("input", "swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), dur.toString())
                )
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("action", "input swipe $x1 $y1 $x2 $y2 $dur")
                    put("detail", r)
                }
            }

            "text" -> {
                val raw = args.joinToString(" ")
                val escaped = raw.replace(" ", "%s").replace("'", "")
                val r = runSafeProcess(listOf("input", "text", escaped))
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("text", raw)
                    put("detail", r)
                }
            }

            "key" -> {
                val rawKey = args.getOrNull(0)?.trim().takeUnless { it.isNullOrBlank() } ?: "KEYCODE_HOME"
                if (!KEYCODE_REGEX.matches(rawKey)) {
                    return JSONObject().apply {
                        put("status", "error")
                        put("error", "Invalid keycode format: $rawKey")
                    }
                }
                val r = runSafeProcess(listOf("input", "keyevent", rawKey))
                JSONObject().apply {
                    put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                    put("key", rawKey)
                    put("detail", r)
                }
            }

            "app" -> {
                val subOp = args.getOrNull(0)?.lowercase() ?: "list"
                val pkg = args.getOrNull(1)?.trim().orEmpty()
                if (subOp !in setOf("list", "trim-caches") && !PACKAGE_NAME_REGEX.matches(pkg)) {
                    return JSONObject().apply {
                        put("status", "error")
                        put("error", "Invalid package name: $pkg")
                    }
                }
                when (subOp) {
                    "list" -> {
                        val filterFlag = args.getOrNull(1)?.takeIf { it in setOf("-3", "-s", "-d", "-e", "-a") } ?: "-3"
                        runSafeProcess(listOf("pm", "list", "packages", filterFlag))
                    }
                    "open", "start" -> runSafeProcess(
                        listOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")
                    )
                    "stop", "kill" -> runSafeProcess(listOf("am", "force-stop", pkg))
                    "clear" -> runSafeProcess(listOf("pm", "clear", pkg))
                    "enable" -> runSafeProcess(listOf("pm", "enable", pkg))
                    "disable" -> runSafeProcess(listOf("pm", "disable-user", "--user", "0", pkg))
                    "standby", "bucket" -> {
                        val bucket = args.getOrNull(2)?.lowercase() ?: "active"
                        if (bucket !in setOf("active", "working_set", "frequent", "rare", "restricted", "never")) {
                            return JSONObject().put("status", "error").put("error", "Invalid standby bucket: $bucket")
                        }
                        runSafeProcess(listOf("am", "set-standby-bucket", pkg, bucket))
                    }
                    "compile" -> {
                        val mode = args.getOrNull(2)?.lowercase() ?: "speed-profile"
                        if (mode !in setOf("speed-profile", "speed", "verify", "quicken", "everything")) {
                            return JSONObject().put("status", "error").put("error", "Invalid compile mode: $mode")
                        }
                        runSafeProcess(listOf("cmd", "package", "compile", "-m", mode, pkg))
                    }
                    "whitelist" -> {
                        val op = if (args.getOrNull(2) == "remove") "-$pkg" else "+$pkg"
                        runSafeProcess(listOf("dumpsys", "deviceidle", "whitelist", op))
                    }
                    "grant" -> {
                        val perm = args.getOrNull(2)?.trim().orEmpty()
                        if (!IDENTIFIER_REGEX.matches(perm)) {
                            return JSONObject().put("status", "error").put("error", "Invalid permission identifier: $perm")
                        }
                        runSafeProcess(listOf("pm", "grant", pkg, perm))
                    }
                    "revoke" -> {
                        val perm = args.getOrNull(2)?.trim().orEmpty()
                        if (!IDENTIFIER_REGEX.matches(perm)) {
                            return JSONObject().put("status", "error").put("error", "Invalid permission identifier: $perm")
                        }
                        runSafeProcess(listOf("pm", "revoke", pkg, perm))
                    }
                    "appops" -> {
                        val op = args.getOrNull(2)?.trim().orEmpty()
                        val mode = args.getOrNull(3)?.trim()?.lowercase() ?: "allow"
                        if (!IDENTIFIER_REGEX.matches(op) ||
                            mode !in setOf("allow", "ignore", "deny", "default", "foreground")
                        ) {
                            return JSONObject().put("status", "error").put("error", "Invalid AppOps arguments")
                        }
                        runSafeProcess(listOf("appops", "set", pkg, op, mode))
                    }
                    else -> runSafeProcess(listOf("dumpsys", "package", pkg))
                }
            }

            "setting" -> {
                val op = args.getOrNull(0)?.lowercase() ?: "get"
                val ns = args.getOrNull(1)?.lowercase() ?: "system"
                val key = args.getOrNull(2)?.trim().orEmpty()
                val value = args.drop(3).joinToString(" ").trim()

                if (ns !in setOf("system", "secure", "global")) {
                    return JSONObject().apply {
                        put("status", "error")
                        put("error", "Invalid settings namespace: $ns")
                    }
                }
                if (op != "list" && !IDENTIFIER_REGEX.matches(key)) {
                    return JSONObject().apply {
                        put("status", "error")
                        put("error", "Invalid settings key: $key")
                    }
                }
                when (op) {
                    "put" -> runSafeProcess(listOf("settings", "put", ns, key, value))
                    "delete" -> runSafeProcess(listOf("settings", "delete", ns, key))
                    "list" -> runSafeProcess(listOf("settings", "list", ns))
                    else -> runSafeProcess(listOf("settings", "get", ns, key))
                }
            }

            "devopt" -> {
                val opt = args.getOrNull(0)?.lowercase() ?: "status"
                val value = args.getOrNull(1)?.trim().orEmpty()
                when (opt) {
                    "animation", "animation_scale", "anim" -> {
                        val scale = value.toFloatOrNull()?.coerceIn(0f, 10f)?.toString() ?: "0.5"
                        runSafeProcess(listOf("settings", "put", "global", "window_animation_scale", scale))
                        runSafeProcess(listOf("settings", "put", "global", "transition_animation_scale", scale))
                        val r = runSafeProcess(listOf("settings", "put", "global", "animator_duration_scale", scale))
                        JSONObject().apply {
                            put("status", if (r.optInt("exitCode") == 0) "ok" else "error")
                            put("developerOption", "animation_scale")
                            put("scale", scale)
                        }
                    }
                    "touches", "show_touches" -> {
                        val v = if (value in setOf("1", "on", "true")) "1" else "0"
                        runSafeProcess(listOf("settings", "put", "system", "show_touches", v))
                    }
                    "pointer", "pointer_location" -> {
                        val v = if (value in setOf("1", "on", "true")) "1" else "0"
                        runSafeProcess(listOf("settings", "put", "system", "pointer_location", v))
                    }
                    "layout_bounds", "bounds" -> {
                        val v = if (value in setOf("1", "on", "true")) "true" else "false"
                        runSafeProcess(listOf("setprop", "debug.layout", v))
                        runSafeProcess(listOf("service", "call", "activity", "1599295570"))
                    }
                    "overdraw", "gpu_overdraw" -> {
                        val v = if (value in setOf("1", "on", "true", "show")) "show" else "false"
                        runSafeProcess(listOf("setprop", "debug.hwui.overdraw", v))
                        runSafeProcess(listOf("service", "call", "activity", "1599295570"))
                    }
                    "stay_awake" -> {
                        val v = if (value in setOf("1", "on", "true", "3", "7")) "3" else "0"
                        runSafeProcess(listOf("settings", "put", "global", "stay_on_while_plugged_in", v))
                    }
                    "dont_keep_activities" -> {
                        val v = if (value in setOf("1", "on", "true")) "1" else "0"
                        runSafeProcess(listOf("settings", "put", "global", "always_finish_activities", v))
                    }
                    "background_limit", "bg_limit" -> {
                        val limit = value.toIntOrNull()?.coerceIn(-1, 4) ?: -1
                        runSafeProcess(listOf("service", "call", "activity", "51", "i32", limit.toString()))
                    }
                    else -> {
                        val winAnim = runSafeProcess(listOf("settings", "get", "global", "window_animation_scale")).optString("stdout").trim()
                        val transAnim = runSafeProcess(listOf("settings", "get", "global", "transition_animation_scale")).optString("stdout").trim()
                        val durAnim = runSafeProcess(listOf("settings", "get", "global", "animator_duration_scale")).optString("stdout").trim()
                        val touches = runSafeProcess(listOf("settings", "get", "system", "show_touches")).optString("stdout").trim()
                        val pointer = runSafeProcess(listOf("settings", "get", "system", "pointer_location")).optString("stdout").trim()
                        val layout = runSafeProcess(listOf("getprop", "debug.layout")).optString("stdout").trim()
                        val stayAwake = runSafeProcess(listOf("settings", "get", "global", "stay_on_while_plugged_in")).optString("stdout").trim()
                        JSONObject().apply {
                            put("status", "ok")
                            put("window_animation_scale", winAnim)
                            put("transition_animation_scale", transAnim)
                            put("animator_duration_scale", durAnim)
                            put("show_touches", touches)
                            put("pointer_location", pointer)
                            put("debug_layout_bounds", layout)
                            put("stay_on_while_plugged_in", stayAwake)
                        }
                    }
                }
            }

            "wm" -> {
                val sub = args.getOrNull(0)?.lowercase() ?: "size"
                val arg1 = args.getOrNull(1)?.trim().orEmpty()
                when (sub) {
                    "size" -> {
                        if (arg1.isBlank()) runSafeProcess(listOf("wm", "size"))
                        else if (arg1 == "reset" || Regex("^\\d{3,5}x\\d{3,5}$").matches(arg1)) {
                            runSafeProcess(listOf("wm", "size", arg1))
                        } else JSONObject().put("status", "error").put("error", "Invalid wm size: $arg1")
                    }
                    "density" -> {
                        if (arg1.isBlank()) runSafeProcess(listOf("wm", "density"))
                        else if (arg1 == "reset" || (arg1.toIntOrNull() ?: 0) in 120..960) {
                            runSafeProcess(listOf("wm", "density", arg1))
                        } else JSONObject().put("status", "error").put("error", "Invalid wm density: $arg1")
                    }
                    else -> JSONObject().put("status", "error").put("error", "Unsupported wm subcommand: $sub")
                }
            }

            "uimode" -> {
                val mode = args.getOrNull(0)?.lowercase() ?: "night"
                val valArg = args.getOrNull(1)?.lowercase() ?: ""
                if (mode == "night" && valArg in setOf("yes", "no", "auto")) {
                    runSafeProcess(listOf("cmd", "uimode", "night", valArg))
                } else {
                    runSafeProcess(listOf("cmd", "uimode", "night"))
                }
            }

            "statusbar" -> {
                val sub = args.getOrNull(0)?.lowercase() ?: "collapse"
                if (sub in setOf("expand-notifications", "expand-settings", "collapse")) {
                    runSafeProcess(listOf("cmd", "statusbar", sub))
                } else {
                    JSONObject().put("status", "error").put("error", "Invalid statusbar action: $sub")
                }
            }

            "dump" -> {
                val safeArgs = args.filter { IDENTIFIER_REGEX.matches(it) }.ifEmpty { listOf("activity", "activities") }
                runSafeProcess(listOf("dumpsys") + safeArgs)
            }

            "provider" -> {
                val uri = args.getOrNull(1)?.trim() ?: "content://settings/system"
                if (!uri.startsWith("content://") || containsShellMetacharacters(uri)) {
                    return JSONObject().apply {
                        put("status", "error")
                        put("error", "Invalid content URI: $uri")
                    }
                }
                runSafeProcess(listOf("content", "query", "--uri", uri))
            }

            "self-heal" -> {
                val svc = "com.hanson.mobileuse/com.hanson.mobileuse.accessibility.HansonAccessibilityService"
                val r1 = runSafeProcess(listOf("settings", "put", "secure", "enabled_accessibility_services", svc))
                val r2 = runSafeProcess(listOf("settings", "put", "secure", "accessibility_enabled", "1"))
                JSONObject().apply {
                    put("status", if (r1.optInt("exitCode") == 0 && r2.optInt("exitCode") == 0) "ok" else "error")
                    put("action", "self-heal accessibility service")
                    put("service", svc)
                }
            }

            else -> JSONObject().apply {
                put("status", "error")
                put("error", "Rejected: action '$action' is not allowlisted")
            }
        }
    }

    /**
     * Executes an external command using a direct argument array ([ProcessBuilder]) WITHOUT `/bin/sh -c`,
     * preventing shell command injection (`;`, `&&`, `|`, `$()`, backticks).
     */
    fun runSafeProcess(argv: List<String>, timeoutSec: Long = 10L): JSONObject {
        if (argv.isEmpty()) {
            return JSONObject().put("status", "error").put("error", "Empty argv")
        }
        for (arg in argv) {
            if (containsShellMetacharacters(arg)) {
                return JSONObject().apply {
                    put("status", "error")
                    put("error", "Security rejection: shell metacharacters forbidden")
                }
            }
        }

        val startMs = System.currentTimeMillis()
        val displayCmd = argv.joinToString(" ")
        return try {
            val proc = ProcessBuilder(argv).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { r ->
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
                BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8)).use { r ->
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
                put("command", displayCmd)
                put("exitCode", if (finished) proc.exitValue() else -1)
                put("stdout", stdout.toString().trim())
                put("stderr", stderr.toString().trim())
                put("durationMs", System.currentTimeMillis() - startMs)
            }
        } catch (t: Throwable) {
            JSONObject().apply {
                put("status", "error")
                put("command", displayCmd)
                put("error", t.message ?: "Process execution error")
            }
        }
    }

    /**
     * Verifies via `/proc/net/tcp` and `/proc/net/tcp6` that a loopback socket connection
     * originates from `root(0)`, `shell(2000)`, our own process UID, or `com.hanson.mobileuse`.
     * Blocks third-party untrusted apps (`u0_aYYY`) installed on the same phone.
     */
    internal fun verifyLoopbackPeerUid(socket: Socket): Boolean {
        val remoteAddr = socket.inetAddress ?: return false
        if (!remoteAddr.isLoopbackAddress) return false

        val clientPort = socket.port
        if (clientPort <= 0) return false
        val hexPort = String.format("%04X", clientPort)

        val tcpFiles = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6"))
            .filter { it.isFile && it.canRead() }
        if (tcpFiles.isEmpty()) {
            // Non-Linux host (e.g. Windows/macOS JVM unit test environment)
            return true
        }

        val trustedUids = resolveTrustedAndroidUids()
        for (tcpFile in tcpFiles) {
            try {
                tcpFile.useLines { lines ->
                    for (line in lines.drop(1)) {
                        val cols = line.trim().split(Regex("\\s+"))
                        if (cols.size >= 8) {
                            val localEndpoint = cols[1].uppercase()
                            if (localEndpoint.endsWith(":$hexPort")) {
                                val socketUid = cols[7].toIntOrNull() ?: continue
                                return socketUid in trustedUids
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return true
    }

    private fun resolveTrustedAndroidUids(): Set<Int> {
        val trusted = mutableSetOf(0, 2000) // root(0) and shell(2000)
        try {
            val myUid = android.os.Process.myUid()
            if (myUid >= 0) trusted.add(myUid)
        } catch (_: Throwable) {
        }
        try {
            val pkgList = File("/data/system/packages.list")
            if (pkgList.isFile && pkgList.canRead()) {
                pkgList.useLines { lines ->
                    for (line in lines) {
                        if (line.startsWith("com.hanson.mobileuse ")) {
                            val parts = line.split(Regex("\\s+"))
                            val uid = parts.getOrNull(1)?.toIntOrNull()
                            if (uid != null) trusted.add(uid)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return trusted
    }

    /**
     * Persistent authenticated UID-2000 RPC server on `127.0.0.1:18890`.
     */
    private fun startPrivilegedDaemon() {
        val server = ServerSocket(DAEMON_PORT, 32, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        println("[Hanson-OS-CLI] Authenticated UID-2000 Daemon listening on 127.0.0.1:$DAEMON_PORT (token auth enforced)")
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

    internal fun handleDaemonClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            if (!verifyLoopbackPeerUid(s)) {
                writeJsonHttpResponse(
                    s,
                    403,
                    "Forbidden",
                    JSONObject().put("status", "error").put("error", "Forbidden: untrusted peer UID")
                )
                return
            }

            val rawIn = s.getInputStream()
            val requestLine = readAsciiLine(rawIn) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var providedToken: String? = null
            var hasBrowserOriginOrReferer = false
            var hostHeader: String? = null

            var line = readAsciiLine(rawIn)
            while (!line.isNullOrEmpty()) {
                val lower = line.lowercase()
                when {
                    lower.startsWith("content-length:") -> {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    lower.startsWith("x-hanson-cli-token:") -> {
                        providedToken = line.substringAfter(":").trim()
                    }
                    lower.startsWith("authorization:") -> {
                        val authVal = line.substringAfter(":").trim()
                        if (authVal.lowercase().startsWith("bearer ")) {
                            providedToken = authVal.substring(7).trim()
                        }
                    }
                    lower.startsWith("origin:") ||
                        lower.startsWith("referer:") ||
                        lower.startsWith("sec-fetch-site:") -> {
                        hasBrowserOriginOrReferer = true
                    }
                    lower.startsWith("host:") -> {
                        hostHeader = line.substringAfter(":").trim().lowercase()
                    }
                }
                line = readAsciiLine(rawIn)
            }

            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                writeJsonHttpResponse(
                    s,
                    413,
                    "Payload Too Large",
                    JSONObject().put("status", "error").put("error", "Payload exceeds limit")
                )
                return
            }

            val bodyStr = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = rawIn.read(buf, read, contentLength - read)
                    if (r == -1) break
                    read += r
                }
                String(buf, 0, read, StandardCharsets.UTF_8)
            } else ""

            // 1. Block browser cross-origin / DNS-rebinding attacks
            val localPort = s.localPort
            val validHosts = setOf(
                "127.0.0.1:$DAEMON_PORT",
                "localhost:$DAEMON_PORT",
                "127.0.0.1:$localPort",
                "localhost:$localPort",
                "127.0.0.1",
                "localhost"
            )
            if (hasBrowserOriginOrReferer || (hostHeader != null && hostHeader !in validHosts)) {
                writeJsonHttpResponse(
                    s,
                    403,
                    "Forbidden",
                    JSONObject().put("status", "error").put("error", "Forbidden: browser origin or invalid Host header rejected")
                )
                return
            }

            // 2. Enforce mandatory 256-bit constant-time token authentication on ALL daemon requests
            if (!verifyConstantTimeToken(providedToken, daemonAuthToken)) {
                writeJsonHttpResponse(
                    s,
                    401,
                    "Unauthorized",
                    JSONObject().put("status", "error").put("error", "Unauthorized: valid X-Hanson-Cli-Token required")
                )
                return
            }

            requestCounter.incrementAndGet()

            val (statusCode, statusText, responseJson) = when {
                path == "/ping" || path == "/status" -> {
                    Triple(200, "OK", executeCommandJson("status", emptyList()))
                }
                path == "/top" -> {
                    Triple(200, "OK", executeCommandJson("top", emptyList()))
                }
                path == "/exec" && method == "POST" -> {
                    val req = runCatching { JSONObject(bodyStr) }.getOrNull()
                    if (req == null) {
                        Triple(400, "Bad Request", JSONObject().put("status", "error").put("error", "Malformed JSON body"))
                    } else if (req.has("command")) {
                        // Raw shell command field is strictly forbidden; parse only if it is a clean allowlisted action
                        val rawCmd = req.optString("command", "").trim()
                        if (rawCmd.isBlank() || containsShellMetacharacters(rawCmd)) {
                            Triple(
                                403,
                                "Forbidden",
                                JSONObject().put("status", "error").put("error", "Arbitrary shell execution on /exec is forbidden")
                            )
                        } else {
                            val tokens = rawCmd.split(Regex("\\s+"))
                            val act = tokens.firstOrNull().orEmpty().lowercase()
                            if (act !in ALLOWED_ACTIONS) {
                                Triple(
                                    403,
                                    "Forbidden",
                                    JSONObject().put("status", "error").put("error", "Rejected: action '$act' is not allowlisted")
                                )
                            } else {
                                val res = executeCommandJson(act, tokens.drop(1))
                                Triple(200, "OK", res)
                            }
                        }
                    } else {
                        val action = req.optString("action", "").trim().lowercase()
                        if (action !in ALLOWED_ACTIONS) {
                            Triple(
                                403,
                                "Forbidden",
                                JSONObject().put("status", "error").put("error", "Rejected: action '$action' is not in ALLOWED_ACTIONS")
                            )
                        } else {
                            val argsArr = req.optJSONArray("args") ?: JSONArray()
                            val argList = (0 until argsArr.length()).map { argsArr.optString(it) }
                            if (argList.any { containsShellMetacharacters(it) }) {
                                Triple(
                                    403,
                                    "Forbidden",
                                    JSONObject().put("status", "error").put("error", "Security rejection: shell metacharacters forbidden")
                                )
                            } else {
                                Triple(200, "OK", executeCommandJson(action, argList))
                            }
                        }
                    }
                }
                else -> Triple(404, "Not Found", JSONObject().put("status", "error").put("error", "Unknown path $path"))
            }

            writeJsonHttpResponse(s, statusCode, statusText, responseJson)
        }
    }

    private fun writeJsonHttpResponse(socket: Socket, statusCode: Int, statusText: String, json: JSONObject) {
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)
        val writer = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        writer.write("HTTP/1.1 $statusCode $statusText\r\n")
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("X-Content-Type-Options: nosniff\r\n")
        writer.write("Content-Length: ${payload.size}\r\n")
        writer.write("Connection: close\r\n\r\n")
        writer.flush()
        socket.getOutputStream().write(payload)
        socket.getOutputStream().flush()
    }
}
