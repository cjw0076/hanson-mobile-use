package com.hanson.mobileuse.agent

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.oscli.HansonOsCliMain
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Universal App Reverse-Engineering & Skill Synthesis Protocol (UARP).
 *
 * Allows Hanson Mobile-Use AI to inspect ANY unknown Android app on the fly by combining:
 *  1. Binary APK Inspection (`publicSourceDir` / `base.apk`):
 *     - Detects UI framework (`Flutter`, `React Native`, `Unity/IL2CPP`, `WebView/Capacitor`, `Jetpack Compose`, `Android View`)
 *     - Extracts binary `AndroidManifest.xml` string pool (custom URI schemes, deep-links, intent actions, route patterns)
 *  2. PackageManager & UID-2000 `dumpsys package` Inspection:
 *     - Enumerates exported Activities (direct screen jump shortcuts), ContentProviders, Services, Receivers
 *  3. Live Accessibility & Window Topology Profiling:
 *     - Identifies navigation tabs, search inputs, resource-id namespaces, and interactive anchors
 *  4. Autonomous Skill Synthesis:
 *     - Compiles and persists a reusable `LearnedAppProtocol` so the agent can control the app via
 *       Fast-Lane Deep-Links/Intents or tailored GUI strategies on every future run.
 */
data class LearnedAppProtocol(
    val packageName: String,
    val appLabel: String,
    val versionName: String,
    val uiFramework: String,
    val controlStrategyKo: String,
    val launcherActivity: String,
    val exportedActivities: List<String>,
    val deepLinkSchemes: List<String>,
    val contentProviders: List<String>,
    val uiAnchors: List<String>,
    val learnedRecipes: List<String>,
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("appLabel", appLabel)
        put("versionName", versionName)
        put("uiFramework", uiFramework)
        put("controlStrategyKo", controlStrategyKo)
        put("launcherActivity", launcherActivity)
        put("exportedActivities", JSONArray(exportedActivities))
        put("deepLinkSchemes", JSONArray(deepLinkSchemes))
        put("contentProviders", JSONArray(contentProviders))
        put("uiAnchors", JSONArray(uiAnchors))
        put("learnedRecipes", JSONArray(learnedRecipes))
        put("timestampMs", timestampMs)
    }

    fun toPromptContext(): String = buildString {
        appendLine("### [UARP 학습된 앱 프로토콜: $appLabel ($packageName)]")
        appendLine("- UI 엔진: $uiFramework → 제어 전략: $controlStrategyKo")
        if (launcherActivity.isNotBlank()) {
            appendLine("- 메인 런처 Activity: $launcherActivity")
        }
        if (deepLinkSchemes.isNotEmpty()) {
            appendLine("- 추출된 딥링크/URI 스킴 (open_link 직행 가능): ${deepLinkSchemes.joinToString(", ")}")
        }
        if (exportedActivities.isNotEmpty()) {
            appendLine("- 외부 진입 가능 Activity (os_cli_exec 'am start -n' 직행 가능): ${exportedActivities.take(8).joinToString(", ")}")
        }
        if (uiAnchors.isNotEmpty()) {
            appendLine("- 학습된 화면 앵커(검색/탭/주요 버튼): ${uiAnchors.joinToString(" | ")}")
        }
        if (learnedRecipes.isNotEmpty()) {
            appendLine("- 학습된 워크플로우 레시피:")
            learnedRecipes.forEach { appendLine("  * $it") }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LearnedAppProtocol {
            fun JSONArray?.toStringList(): List<String> {
                if (this == null) return emptyList()
                return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
            }
            return LearnedAppProtocol(
                packageName = json.optString("packageName"),
                appLabel = json.optString("appLabel"),
                versionName = json.optString("versionName", "1.0"),
                uiFramework = json.optString("uiFramework", "Android Native View"),
                controlStrategyKo = json.optString("controlStrategyKo"),
                launcherActivity = json.optString("launcherActivity"),
                exportedActivities = json.optJSONArray("exportedActivities").toStringList(),
                deepLinkSchemes = json.optJSONArray("deepLinkSchemes").toStringList(),
                contentProviders = json.optJSONArray("contentProviders").toStringList(),
                uiAnchors = json.optJSONArray("uiAnchors").toStringList(),
                learnedRecipes = json.optJSONArray("learnedRecipes").toStringList(),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis())
            )
        }
    }
}

class AppReverseEngineeringEngine(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "hanson_uarp_learned_protocols"
        private val IGNORED_SCHEMES = setOf(
            "http", "https", "schemas.android.com", "android", "file", "content",
            "package", "tel", "sms", "smsto", "mms", "mmsto", "mailto", "geo"
        )
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Reverse-engineers any installed app by its Korean/English label or package name,
     * merges live UI snapshot anchors if the app is currently on screen, and saves the
     * resulting `LearnedAppProtocol` permanently.
     */
    fun reverseEngineerAndLearn(
        appQuery: String,
        latestSnapshot: AtomicSnapshot? = null,
        customRecipeNote: String? = null
    ): LearnedAppProtocol? {
        val pm = context.packageManager
        val targetPkg = resolvePackageName(pm, appQuery, latestSnapshot) ?: return null

        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_PROVIDERS or
            PackageManager.GET_SERVICES or
            PackageManager.GET_META_DATA
        val pkgInfo: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(targetPkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(targetPkg, flags)
            }
        } catch (_: Exception) {
            return null
        }

        val appInfo = pkgInfo.applicationInfo
        val appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: targetPkg
        val versionName = pkgInfo.versionName ?: "unknown"
        val apkPath = appInfo?.publicSourceDir.orEmpty()

        // 1. Binary APK Inspection (UI Framework Fingerprinting + Binary AndroidManifest AXML String Pool)
        val (uiFramework, controlStrategy, apkExtractedSchemes) = inspectApkBinary(apkPath)

        // 2. Exported Activities & Search/DeepLink Entry Points
        val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
        val launcherActivity = launchIntent?.component?.className.orEmpty()

        val exportedActivities = pkgInfo.activities
            .orEmpty()
            .filter { it.exported || it.name == launcherActivity }
            .map { info: ActivityInfo ->
                val shortName = if (info.name.startsWith(targetPkg)) {
                    info.name.removePrefix(targetPkg)
                } else {
                    info.name
                }
                shortName.ifBlank { info.name }
            }
            .distinct()
            .take(15)

        // 3. ContentProviders
        val providers = pkgInfo.providers
            .orEmpty()
            .filter { it.exported }
            .mapNotNull { it.authority }
            .distinct()
            .take(10)

        // 4. Supplement Deep-Links via UID-2000 `dumpsys package` if available
        val dumpsysSchemes = extractSchemesFromDumpsys(targetPkg)
        val allSchemes = (apkExtractedSchemes + dumpsysSchemes).distinct().take(15)

        // 5. Extract Live UI Semantic Anchors if the app is currently visible on screen
        val existing = getLearnedProtocol(targetPkg)
        val discoveredAnchors = mutableListOf<String>()
        if (existing != null) {
            discoveredAnchors.addAll(existing.uiAnchors)
        }
        if (latestSnapshot != null && (latestSnapshot.activePackage == targetPkg || latestSnapshot.activePackage.isBlank())) {
            latestSnapshot.indexedElements.forEach { el ->
                val label = el.text.ifBlank { el.contentDesc }.trim()
                val idShort = el.resourceId.substringAfterLast("/")
                val isImportant = el.editable ||
                    label.contains("검색") || label.contains("보내기") || label.contains("전송") ||
                    label.contains("홈") || label.contains("마이") || label.contains("메뉴") ||
                    label.contains("장바구니") || label.contains("결제") || label.contains("설정") ||
                    idShort.contains("search", ignoreCase = true) ||
                    idShort.contains("tab", ignoreCase = true) ||
                    idShort.contains("send", ignoreCase = true)
                if (isImportant && (label.isNotBlank() || idShort.isNotBlank())) {
                    val anchorDesc = buildString {
                        if (label.isNotBlank()) append("'$label'")
                        if (idShort.isNotBlank()) append("(id:$idShort)")
                        if (el.editable) append("[입력창]")
                        append("@(${el.normX},${el.normY})")
                    }
                    if (anchorDesc !in discoveredAnchors && discoveredAnchors.size < 18) {
                        discoveredAnchors.add(anchorDesc)
                    }
                }
            }
        }

        // 6. Synthesize Reusable Recipes
        val recipes = mutableListOf<String>()
        if (existing != null) {
            recipes.addAll(existing.learnedRecipes)
        }
        if (allSchemes.isNotEmpty()) {
            recipes.add("딥링크 패스트레인: GUI 탐색 대신 `open_link` 도구로 `${allSchemes.first()}` 스킴을 호출하면 1단계로 목표 화면에 진입 가능")
        }
        val searchAct = exportedActivities.firstOrNull {
            it.contains("Search", ignoreCase = true) || it.contains("Query", ignoreCase = true)
        }
        if (searchAct != null) {
            val fullComp = if (searchAct.startsWith(".")) "$targetPkg/$targetPkg$searchAct" else "$targetPkg/$searchAct"
            recipes.add("검색 직행: `os_cli_exec`로 `am start -n $fullComp` 실행 시 메인 화면을 건너뛰고 즉시 검색창 진입")
        }
        if (!customRecipeNote.isNullOrBlank() && customRecipeNote !in recipes) {
            recipes.add(0, customRecipeNote)
        }

        val protocol = LearnedAppProtocol(
            packageName = targetPkg,
            appLabel = appLabel,
            versionName = versionName,
            uiFramework = uiFramework,
            controlStrategyKo = controlStrategy,
            launcherActivity = launcherActivity,
            exportedActivities = exportedActivities,
            deepLinkSchemes = allSchemes,
            contentProviders = providers,
            uiAnchors = discoveredAnchors.distinct(),
            learnedRecipes = recipes.distinct()
        )
        saveProtocol(protocol)
        return protocol
    }

    fun saveProtocol(protocol: LearnedAppProtocol) {
        prefs.edit().putString(protocol.packageName, protocol.toJson().toString()).apply()
    }

    fun getLearnedProtocol(packageName: String): LearnedAppProtocol? {
        val raw = prefs.getString(packageName, null) ?: return null
        return runCatching { LearnedAppProtocol.fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun getAllLearnedProtocols(): List<LearnedAppProtocol> {
        return prefs.all.values
            .mapNotNull { it as? String }
            .mapNotNull { raw -> runCatching { LearnedAppProtocol.fromJson(JSONObject(raw)) }.getOrNull() }
            .sortedByDescending { it.timestampMs }
    }

    /**
     * Inspects the target app's `base.apk` without requiring root (`publicSourceDir` is readable
     * by any app with `QUERY_ALL_PACKAGES`).
     *  - Fingerprints native libraries (`libflutter.so`, `libreactnativejni.so`, `libunity.so`, etc.)
     *  - Parses the binary `AndroidManifest.xml` UTF-8/UTF-16 string pool to discover custom deep-link schemes!
     */
    private fun inspectApkBinary(apkPath: String): Triple<String, String, List<String>> {
        if (apkPath.isBlank() || !File(apkPath).exists()) {
            return Triple(
                "Android Native View",
                "표준 접근성 노드 인덱스([n]) 및 resource-id 기반 정밀 제어",
                emptyList()
            )
        }

        var hasFlutter = false
        var hasReactNative = false
        var hasUnity = false
        var hasCapacitorOrCordova = false
        val extractedSchemes = mutableSetOf<String>()

        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    when {
                        name.endsWith("libflutter.so") || name.contains("flutter_assets/") -> hasFlutter = true
                        name.endsWith("libreactnativejni.so") || name.endsWith("libhermes.so") || name.endsWith("index.android.bundle") -> hasReactNative = true
                        name.endsWith("libunity.so") || name.endsWith("libil2cpp.so") -> hasUnity = true
                        name.contains("capacitor.config.json") || name.contains("cordova.js") -> hasCapacitorOrCordova = true
                    }
                }

                // Parse binary AndroidManifest.xml string pool for custom URI schemes & deep-links
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry != null) {
                    val bytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
                    extractStringsFromBinaryManifest(bytes).forEach { str ->
                        val s = str.trim().trimStart('*', '"', '\'', '(', '[')
                        if (s.contains("://") &&
                            !s.contains("schemas.android.com") &&
                            !s.contains("play.google.com") &&
                            !s.contains("w3.org") &&
                            !s.contains("apache.org")
                        ) {
                            extractedSchemes.add(s.take(80))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Ignore zip read errors
        }

        val (framework, strategy) = when {
            hasUnity -> "Unity / IL2CPP Canvas" to
                "접근성 트리가 비어 있으므로 비전 스크린샷 좌표([0..1000]) + Hanson OS CLI(`hanson tap <x> <y>`) 커널 입력으로 제어"
            hasFlutter -> "Flutter Engine (libflutter.so)" to
                "Flutter Semantics 트리 활성화 + contentDesc 매칭 및 비전 정규화 좌표 하이브리드 제어"
            hasReactNative -> "React Native / Hermes" to
                "ViewGroup 계층이 깊으므로 text/contentDesc 매칭 및 딥링크 스킴 직행 우선 사용"
            hasCapacitorOrCordova -> "Hybrid WebView (Capacitor/Cordova)" to
                "WebView 내부 DOM 접근성 노드 스캔 및 URL 딥링크 라우팅 병행"
            else -> "Android Native / Jetpack Compose" to
                "Artemis 멀티윈도우 접근성 노드 인덱스([n]) + Exported Activity 인텐트 패스트레인 제어"
        }

        return Triple(framework, strategy, extractedSchemes.toList())
    }

    /**
     * Lightweight AXML string scrubber that extracts readable ASCII/UTF-16LE strings
     * from compiled binary `AndroidManifest.xml`.
     */
    private fun extractStringsFromBinaryManifest(bytes: ByteArray): List<String> {
        val results = mutableListOf<String>()
        if (bytes.size < 36) return results
        try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // Scan for printable ASCII sequences (UTF-8 AXML) and UTF-16LE sequences
            val sb = StringBuilder()
            for (b in bytes) {
                val c = b.toInt() and 0xFF
                if (c in 33..126) {
                    sb.append(c.toChar())
                } else {
                    if (sb.length in 4..90) {
                        results.add(sb.toString())
                    }
                    sb.setLength(0)
                }
            }
            // Also scan UTF-16LE (every other byte is 0x00)
            var i = 0
            val u16 = StringBuilder()
            while (i + 1 < bytes.size) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt() and 0xFF
                if (hi == 0 && lo in 33..126) {
                    u16.append(lo.toChar())
                    i += 2
                } else {
                    if (u16.length in 4..90) {
                        results.add(u16.toString())
                    }
                    u16.setLength(0)
                    i += 2
                }
            }
        } catch (_: Throwable) {
        }
        return results.distinct()
    }

    private fun extractSchemesFromDumpsys(packageName: String): List<String> {
        return try {
            val out = HansonOsCliMain.runRawShell(
                "dumpsys package $packageName | grep -E 'Scheme: \"|Authority: \"' | head -n 30",
                timeoutSec = 3L
            ).optString("stdout")
            val schemes = mutableListOf<String>()
            var lastScheme = ""
            out.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("Scheme:")) {
                    val sc = trimmed.substringAfter("\"").substringBefore("\"")
                    if (sc.isNotBlank() && sc !in IGNORED_SCHEMES) {
                        lastScheme = sc
                        schemes.add("$sc://")
                    }
                } else if (trimmed.startsWith("Authority:") && lastScheme.isNotBlank()) {
                    val host = trimmed.substringAfter("\"").substringBefore("\"").substringBefore(":")
                    if (host.isNotBlank()) {
                        schemes.add("$lastScheme://$host")
                    }
                }
            }
            schemes.distinct()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolvePackageName(
        pm: PackageManager,
        query: String,
        latestSnapshot: AtomicSnapshot?
    ): String? {
        val q = query.trim()
        if (q.isBlank() || q == "현재앱" || q == "current" || q.contains("지금 앱") || q.contains("이 앱")) {
            val active = latestSnapshot?.activePackage.orEmpty()
            if (active.isNotBlank() && active != context.packageName) return active
        }
        // Direct package match
        if (q.contains(".") && !q.contains(" ")) {
            val exists = runCatching { pm.getApplicationInfo(q, 0) }.isSuccess
            if (exists) return q
        }
        // Match by installed app label
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val exact = apps.firstOrNull { pm.getApplicationLabel(it).toString().equals(q, ignoreCase = true) }
        if (exact != null) return exact.packageName

        val partial = apps.firstOrNull {
            val label = pm.getApplicationLabel(it).toString()
            label.contains(q, ignoreCase = true) || q.contains(label, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
        return partial?.packageName
    }
}
