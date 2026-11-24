package com.hanson.mobileuse.accessibility

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Discovered read-only `ACTION_VIEW` deep-link entry extracted from an APK's manifest.
 */
data class DiscoveredDeepLink(
    val packageName: String,
    val activityName: String,
    val scheme: String,
    val host: String,
    val pathPrefix: String,
    val sampleUri: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("activityName", activityName)
        put("scheme", scheme)
        put("host", host)
        put("pathPrefix", pathPrefix)
        put("sampleUri", sampleUri)
    }
}

/**
 * Bounded, read-only base+split APK `ACTION_VIEW` manifest deep-link inspector & dry-run URI resolver
 * absorbed from `droidrun/mobilerun-portal` (`DeepLinkInspector.kt`).
 *
 * Hard safety invariants:
 * - Strictly read-only: NEVER calls `startActivity(...)` or launches any app.
 * - Bounded resource caps: scans at most [MAX_APKS_SCANNED] (128) APK files, enforces a
 *   [MAX_MANIFEST_BYTES] (4 MiB) per-manifest cap, and caches parsed packages in an LRU-16 cache.
 * - Rejects unsafe URI schemes (`javascript:`, `file:`, `content:`, `data:`, `vbscript:`, `blob:`, `about:`).
 */
object DeepLinkInspector {

    const val MAX_APKS_SCANNED: Int = 128
    const val MAX_MANIFEST_BYTES: Int = 4 * 1024 * 1024
    const val LRU_CACHE_SIZE: Int = 16
    private const val MAX_COMBINATIONS_PER_FILTER: Int = 64
    private const val ACTION_VIEW = "android.intent.action.VIEW"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private val SCHEME_REGEX = Regex("^[a-z][a-z0-9+.-]{1,31}$")
    private val CONTROL_OR_WHITESPACE_REGEX = Regex("[\\s\\u0000-\\u001F\\u007F]")

    /** Schemes explicitly prohibited from deep-link discovery and dry-run resolution. */
    val FORBIDDEN_SCHEMES: Set<String> = setOf(
        "javascript",
        "vbscript",
        "file",
        "content",
        "data",
        "blob",
        "about",
        "jar"
    )

    private val cacheLock = Any()
    private val lruCache: LinkedHashMap<String, List<DiscoveredDeepLink>> =
        object : LinkedHashMap<String, List<DiscoveredDeepLink>>(LRU_CACHE_SIZE + 1, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<DiscoveredDeepLink>>?
            ): Boolean = size > LRU_CACHE_SIZE
        }

    fun clearCache() {
        synchronized(cacheLock) {
            lruCache.clear()
        }
    }

    /**
     * Returns true iff [scheme] is a valid RFC 3986 scheme outside [FORBIDDEN_SCHEMES].
     */
    fun isSafeDeepLinkScheme(scheme: String): Boolean {
        val lower = scheme.trim().lowercase(Locale.ROOT)
        if (lower.isEmpty()) return false
        if (lower in FORBIDDEN_SCHEMES) return false
        return SCHEME_REGEX.matches(lower)
    }

    /**
     * Pure JVM URI validator for dry-run resolution. Refuses `javascript:`, `file:`, `content:`,
     * `data:`, control characters, and malformed URIs without touching Android `Uri`.
     */
    fun validateDeepLinkUri(uri: String): Pair<Boolean, String> {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) {
            return false to "URI must not be empty."
        }
        if (trimmed.length > 2048) {
            return false to "URI exceeds 2048 character safety cap."
        }
        if (CONTROL_OR_WHITESPACE_REGEX.containsMatchIn(trimmed)) {
            return false to "URI contains whitespace or forbidden control characters."
        }
        val colonIdx = trimmed.indexOf(':')
        if (colonIdx <= 0) {
            return false to "URI is missing a valid scheme."
        }
        val scheme = trimmed.substring(0, colonIdx).lowercase(Locale.ROOT)
        if (scheme in FORBIDDEN_SCHEMES) {
            return false to "Refused unsafe URI scheme '$scheme:'."
        }
        if (!SCHEME_REGEX.matches(scheme)) {
            return false to "Invalid URI scheme '$scheme'."
        }
        return true to scheme
    }

    /**
     * Scans installed base + split APKs read-only (capped at [MAX_APKS_SCANNED] APKs and
     * [MAX_MANIFEST_BYTES] per manifest) and extracts `ACTION_VIEW` deep links.
     * Never launches any activity.
     */
    fun inspectInstalledDeepLinks(
        context: Context,
        packageFilter: String? = null
    ): List<DiscoveredDeepLink> {
        val pm = runCatching { context.packageManager }.getOrNull() ?: return emptyList()
        val filter = packageFilter?.trim()?.takeIf { it.isNotEmpty() }

        val installedApps = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        }.getOrDefault(emptyList())

        val matchingApps = if (filter == null) {
            installedApps.sortedBy { it.packageName }
        } else {
            val exact = installedApps.filter { it.packageName.equals(filter, ignoreCase = true) }
            if (exact.isNotEmpty()) {
                exact
            } else {
                installedApps
                    .filter { it.packageName.contains(filter, ignoreCase = true) }
                    .sortedBy { it.packageName }
            }
        }

        val discovered = LinkedHashSet<DiscoveredDeepLink>()
        var apksScanned = 0

        for (appInfo in matchingApps) {
            if (apksScanned >= MAX_APKS_SCANNED) break
            val pkg = appInfo.packageName ?: continue

            val apkPaths = buildList {
                appInfo.sourceDir?.takeIf { it.isNotBlank() }?.let { add(it) }
                appInfo.splitSourceDirs?.forEach { split ->
                    if (!split.isNullOrBlank()) add(split)
                }
            }.distinct()

            if (apkPaths.isEmpty()) continue

            val cacheKey = buildPackageCacheKey(pkg, apkPaths)
            val cached = synchronized(cacheLock) { lruCache[cacheKey] }
            if (cached != null) {
                discovered.addAll(cached)
                continue
            }

            val pkgLinks = LinkedHashSet<DiscoveredDeepLink>()
            for (apkPath in apkPaths) {
                if (apksScanned >= MAX_APKS_SCANNED) break
                val apkFile = File(apkPath)
                if (!apkFile.isFile || !apkFile.canRead()) continue
                apksScanned++
                val manifestBytes = readManifestBytesBounded(apkFile) ?: continue
                pkgLinks.addAll(parseManifestBytes(pkg, manifestBytes))
            }

            val pkgList = pkgLinks.toList()
            synchronized(cacheLock) {
                lruCache[cacheKey] = pkgList
            }
            discovered.addAll(pkgList)
        }

        return discovered.toList()
    }

    /**
     * Pure offline AndroidManifest XML deep-link parser (fully unit-testable on JVM).
     * Extracts safe `ACTION_VIEW` deep links across `<activity>` and `<activity-alias>` blocks
     * with XXE protection and [MAX_MANIFEST_BYTES] size cap.
     */
    fun parseManifestXmlDeepLinks(
        packageName: String,
        xmlText: String
    ): List<DiscoveredDeepLink> {
        val pkg = packageName.trim()
        if (pkg.isEmpty() || xmlText.isBlank()) return emptyList()
        if (xmlText.toByteArray(StandardCharsets.UTF_8).size > MAX_MANIFEST_BYTES) {
            return emptyList()
        }

        val document = runCatching {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isXIncludeAware = false
                isExpandEntityReferences = false
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            }
            dbf.newDocumentBuilder().parse(InputSource(StringReader(xmlText)))
        }.getOrNull() ?: return emptyList()

        val results = LinkedHashSet<DiscoveredDeepLink>()
        val componentTags = listOf("activity", "activity-alias")

        for (tagName in componentTags) {
            val nodes = document.getElementsByTagName(tagName)
            for (i in 0 until nodes.length) {
                val elem = nodes.item(i) as? Element ?: continue
                if (attrValue(elem, "enabled").equals("false", ignoreCase = true)) continue
                if (attrValue(elem, "exported").equals("false", ignoreCase = true)) continue

                val rawActivity = attrValue(elem, "name").ifBlank { attrValue(elem, "targetActivity") }
                val activityName = normalizeActivityName(pkg, rawActivity)

                val children = elem.childNodes
                for (c in 0 until children.length) {
                    val filterEl = children.item(c) as? Element ?: continue
                    if (filterEl.tagName != "intent-filter") continue
                    extractFromIntentFilterElement(pkg, activityName, filterEl, results)
                }
            }
        }

        return results.toList()
    }

    /**
     * Dry-run URI resolution via [PackageManager.queryIntentActivities] without EVER calling
     * `startActivity`. Validates scheme allowlist and refuses `javascript:`, `file:`, `content:`, etc.
     */
    fun resolveDeepLinkDryRun(context: Context, uri: String): JSONObject {
        val (valid, detailOrScheme) = validateDeepLinkUri(uri)
        if (!valid) {
            return JSONObject().apply {
                put("uri", uri)
                put("allowed", false)
                put("resolvable", false)
                put("handlerCount", 0)
                put("error", detailOrScheme)
                put("handlers", JSONArray())
            }
        }

        val scheme = detailOrScheme
        val handlers = JSONArray()

        val resolveList = runCatching {
            val parsedUri = Uri.parse(uri.trim())
            val intent = Intent(Intent.ACTION_VIEW, parsedUri)
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrDefault(emptyList())

        for (info in resolveList) {
            val act = info.activityInfo ?: continue
            handlers.put(
                JSONObject().apply {
                    put("packageName", act.packageName.orEmpty())
                    put("activityName", act.name.orEmpty())
                    put("exported", act.exported)
                    put("isDefault", info.isDefault)
                }
            )
        }

        return JSONObject().apply {
            put("uri", uri.trim())
            put("scheme", scheme)
            put("allowed", true)
            put("resolvable", handlers.length() > 0)
            put("handlerCount", handlers.length())
            put("handlers", handlers)
        }
    }

    private fun buildPackageCacheKey(packageName: String, apkPaths: List<String>): String {
        val stamps = apkPaths.joinToString("|") { path ->
            val f = File(path)
            "${f.name}:${f.length()}:${f.lastModified()}"
        }
        return "$packageName#$stamps"
    }

    private fun readManifestBytesBounded(apkFile: File): ByteArray? = runCatching {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml") ?: return@use null
            if (entry.size > MAX_MANIFEST_BYTES) return@use null
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_MANIFEST_BYTES) return@use null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }
    }.getOrNull()

    private fun parseManifestBytes(packageName: String, bytes: ByteArray): List<DiscoveredDeepLink> {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) return emptyList()
        // Plain-text XML starts with '<' (optionally after UTF-8 BOM or whitespace)
        var firstNonWs = 0
        while (firstNonWs < bytes.size && firstNonWs < 32) {
            val b = bytes[firstNonWs].toInt() and 0xFF
            if (b == 0xEF || b == 0xBB || b == 0xBF || b == ' '.code || b == '\t'.code || b == '\r'.code || b == '\n'.code) {
                firstNonWs++
            } else {
                break
            }
        }
        if (firstNonWs < bytes.size && bytes[firstNonWs].toInt() == '<'.code) {
            return parseManifestXmlDeepLinks(packageName, String(bytes, StandardCharsets.UTF_8))
        }
        return parseBinaryManifestDeepLinks(packageName, bytes)
    }

    private data class RawDataTag(
        val scheme: String,
        val host: String,
        val port: String,
        val pathPrefix: String
    )

    private fun extractFromIntentFilterElement(
        packageName: String,
        activityName: String,
        filterEl: Element,
        out: MutableSet<DiscoveredDeepLink>
    ) {
        var hasActionView = false
        val dataTags = mutableListOf<RawDataTag>()
        val children = filterEl.childNodes

        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            when (child.tagName) {
                "action" -> {
                    if (attrValue(child, "name") == ACTION_VIEW) {
                        hasActionView = true
                    }
                }
                "data" -> {
                    val scheme = attrValue(child, "scheme").trim().lowercase(Locale.ROOT)
                    val host = attrValue(child, "host").trim()
                    val port = attrValue(child, "port").trim()
                    val path = attrValue(child, "pathPrefix")
                        .ifBlank { attrValue(child, "path") }
                        .ifBlank { attrValue(child, "pathPattern") }
                        .trim()
                    if (scheme.isNotEmpty() || host.isNotEmpty() || path.isNotEmpty()) {
                        dataTags.add(RawDataTag(scheme, host, port, path))
                    }
                }
            }
        }

        if (!hasActionView || dataTags.isEmpty()) return
        emitFromDataTags(packageName, activityName, dataTags, out)
    }

    private fun emitFromDataTags(
        packageName: String,
        activityName: String,
        dataTags: List<RawDataTag>,
        out: MutableSet<DiscoveredDeepLink>
    ) {
        val allHaveScheme = dataTags.all { it.scheme.isNotEmpty() }
        if (allHaveScheme) {
            for (tag in dataTags.take(MAX_COMBINATIONS_PER_FILTER)) {
                if (!isSafeDeepLinkScheme(tag.scheme)) continue
                val hostWithPort = formatHostWithPort(tag.host, tag.port)
                out.add(
                    DiscoveredDeepLink(
                        packageName = packageName,
                        activityName = activityName,
                        scheme = tag.scheme,
                        host = hostWithPort,
                        pathPrefix = tag.pathPrefix,
                        sampleUri = buildSampleUri(tag.scheme, hostWithPort, tag.pathPrefix)
                    )
                )
            }
            return
        }

        val schemes = dataTags.mapNotNull { it.scheme.takeIf { s -> isSafeDeepLinkScheme(s) } }.distinct()
        if (schemes.isEmpty()) return
        val hosts = dataTags.mapNotNull {
            val hp = formatHostWithPort(it.host, it.port)
            hp.takeIf { h -> h.isNotEmpty() }
        }.distinct().ifEmpty { listOf("") }
        val paths = dataTags.mapNotNull { it.pathPrefix.takeIf { p -> p.isNotEmpty() } }
            .distinct()
            .ifEmpty { listOf("") }

        var emitted = 0
        for (scheme in schemes) {
            for (host in hosts) {
                for (path in paths) {
                    if (emitted >= MAX_COMBINATIONS_PER_FILTER) return
                    out.add(
                        DiscoveredDeepLink(
                            packageName = packageName,
                            activityName = activityName,
                            scheme = scheme,
                            host = host,
                            pathPrefix = path,
                            sampleUri = buildSampleUri(scheme, host, path)
                        )
                    )
                    emitted++
                }
            }
        }
    }

    private fun formatHostWithPort(host: String, port: String): String {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return ""
        val cleanPort = port.trim()
        return if (cleanPort.isNotEmpty() && cleanPort.all { it.isDigit() }) {
            "$cleanHost:$cleanPort"
        } else {
            cleanHost
        }
    }

    private fun buildSampleUri(scheme: String, host: String, pathPrefix: String): String {
        val cleanPath = pathPrefix
            .replace(".*", "sample")
            .replace("\\.", ".")
            .trim()
        val normalizedPath = when {
            cleanPath.isEmpty() -> ""
            cleanPath.startsWith("/") -> cleanPath
            else -> "/$cleanPath"
        }
        return when {
            host.isNotBlank() -> "$scheme://$host$normalizedPath"
            normalizedPath.isNotBlank() -> "$scheme://${normalizedPath.trimStart('/')}"
            else -> "$scheme://"
        }
    }

    private fun normalizeActivityName(packageName: String, rawActivity: String): String {
        val trimmed = rawActivity.trim()
        return when {
            trimmed.isEmpty() -> "$packageName.MainActivity"
            trimmed.startsWith(".") -> "$packageName$trimmed"
            !trimmed.contains('.') -> "$packageName.$trimmed"
            else -> trimmed
        }
    }

    private fun attrValue(element: Element, localName: String): String {
        val prefixed = element.getAttribute("android:$localName")
        if (!prefixed.isNullOrBlank()) return prefixed
        val nsVal = runCatching { element.getAttributeNS(ANDROID_NS, localName) }.getOrDefault("")
        if (!nsVal.isNullOrBlank()) return nsVal
        return element.getAttribute(localName).orEmpty()
    }

    /**
     * Bounds-checked Android Binary XML (AXML) parser for real APK `AndroidManifest.xml` entries
     * across both base and split APKs without using hidden `AssetManager` APIs.
     */
    internal fun parseBinaryManifestDeepLinks(
        packageName: String,
        bytes: ByteArray
    ): List<DiscoveredDeepLink> {
        if (bytes.size < 8 || bytes.size > MAX_MANIFEST_BYTES) return emptyList()
        return runCatching {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val rootType = buf.getShort(0).toInt() and 0xFFFF
            if (rootType != 0x0003) return@runCatching emptyList() // RES_XML_TYPE
            val rootHeaderSize = buf.getShort(2).toInt() and 0xFFFF
            val totalSize = buf.getInt(4).coerceAtMost(bytes.size)

            var strings: List<String> = emptyList()
            var offset = rootHeaderSize
            val results = LinkedHashSet<DiscoveredDeepLink>()

            var inActivity = false
            var activityBlocked = false
            var currentActivity = ""
            var inIntentFilter = false
            var hasActionView = false
            val currentDataTags = mutableListOf<RawDataTag>()

            while (offset + 8 <= totalSize) {
                val chunkType = buf.getShort(offset).toInt() and 0xFFFF
                val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
                val chunkSize = buf.getInt(offset + 4)
                if (chunkSize < 8 || offset + chunkSize > totalSize) break

                when (chunkType) {
                    0x0001 -> { // RES_STRING_POOL_TYPE
                        strings = decodeStringPool(bytes, offset, chunkSize)
                    }
                    0x0102 -> { // RES_XML_START_ELEMENT_TYPE
                        if (offset + headerSize + 20 <= offset + chunkSize) {
                            val ext = offset + headerSize
                            val nameIdx = buf.getInt(ext + 4)
                            val attrStart = buf.getShort(ext + 8).toInt() and 0xFFFF
                            val attrSize = buf.getShort(ext + 10).toInt() and 0xFFFF
                            val attrCount = buf.getShort(ext + 12).toInt() and 0xFFFF
                            val tagName = strings.getOrNull(nameIdx).orEmpty()

                            val attrs = HashMap<String, String>(attrCount)
                            if (attrSize >= 20 && attrCount in 0..256) {
                                var attrOff = ext + attrStart
                                for (a in 0 until attrCount) {
                                    if (attrOff + 20 > offset + chunkSize) break
                                    val attrNameIdx = buf.getInt(attrOff + 4)
                                    val rawValIdx = buf.getInt(attrOff + 8)
                                    val dataType = bytes[attrOff + 15].toInt() and 0xFF
                                    val dataVal = buf.getInt(attrOff + 16)
                                    val attrName = strings.getOrNull(attrNameIdx).orEmpty()
                                    val attrVal = when {
                                        rawValIdx >= 0 && rawValIdx < strings.size -> strings[rawValIdx]
                                        dataType == 0x03 && dataVal in strings.indices -> strings[dataVal]
                                        dataType == 0x12 -> if (dataVal != 0) "true" else "false"
                                        dataType == 0x10 || dataType == 0x11 -> dataVal.toString()
                                        else -> ""
                                    }
                                    if (attrName.isNotEmpty()) {
                                        attrs[attrName] = attrVal
                                    }
                                    attrOff += attrSize
                                }
                            }

                            when (tagName) {
                                "activity", "activity-alias" -> {
                                    inActivity = true
                                    activityBlocked = attrs["enabled"].equals("false", ignoreCase = true) ||
                                        attrs["exported"].equals("false", ignoreCase = true)
                                    val rawAct = attrs["name"].orEmpty().ifBlank { attrs["targetActivity"].orEmpty() }
                                    currentActivity = normalizeActivityName(packageName, rawAct)
                                }
                                "intent-filter" -> if (inActivity && !activityBlocked) {
                                    inIntentFilter = true
                                    hasActionView = false
                                    currentDataTags.clear()
                                }
                                "action" -> if (inIntentFilter && attrs["name"] == ACTION_VIEW) {
                                    hasActionView = true
                                }
                                "data" -> if (inIntentFilter) {
                                    val scheme = attrs["scheme"].orEmpty().trim().lowercase(Locale.ROOT)
                                    val host = attrs["host"].orEmpty().trim()
                                    val port = attrs["port"].orEmpty().trim()
                                    val path = attrs["pathPrefix"].orEmpty()
                                        .ifBlank { attrs["path"].orEmpty() }
                                        .ifBlank { attrs["pathPattern"].orEmpty() }
                                        .trim()
                                    if (scheme.isNotEmpty() || host.isNotEmpty() || path.isNotEmpty()) {
                                        currentDataTags.add(RawDataTag(scheme, host, port, path))
                                    }
                                }
                            }
                        }
                    }
                    0x0103 -> { // RES_XML_END_ELEMENT_TYPE
                        if (offset + headerSize + 8 <= offset + chunkSize) {
                            val nameIdx = buf.getInt(offset + headerSize + 4)
                            when (strings.getOrNull(nameIdx).orEmpty()) {
                                "intent-filter" -> {
                                    if (inIntentFilter && hasActionView && currentDataTags.isNotEmpty()) {
                                        emitFromDataTags(packageName, currentActivity, currentDataTags, results)
                                    }
                                    inIntentFilter = false
                                    hasActionView = false
                                    currentDataTags.clear()
                                }
                                "activity", "activity-alias" -> {
                                    inActivity = false
                                    activityBlocked = false
                                    inIntentFilter = false
                                    currentDataTags.clear()
                                }
                            }
                        }
                    }
                }
                offset += chunkSize
            }
            results.toList()
        }.getOrDefault(emptyList())
    }

    private fun decodeStringPool(bytes: ByteArray, chunkOffset: Int, chunkSize: Int): List<String> {
        if (chunkSize < 28) return emptyList()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringCount = buf.getInt(chunkOffset + 8)
        val flags = buf.getInt(chunkOffset + 16)
        val stringsStart = buf.getInt(chunkOffset + 20)
        if (stringCount <= 0 || stringCount > 65_535) return emptyList()

        val isUtf8 = (flags and 0x100) != 0
        val offsetsBase = chunkOffset + 28
        val dataBase = chunkOffset + stringsStart
        val chunkEnd = chunkOffset + chunkSize
        val out = ArrayList<String>(stringCount)

        for (i in 0 until stringCount) {
            val offPos = offsetsBase + i * 4
            if (offPos + 4 > chunkEnd) break
            val strRelOffset = buf.getInt(offPos)
            var pos = dataBase + strRelOffset
            if (pos < dataBase || pos >= chunkEnd) {
                out.add("")
                continue
            }
            if (isUtf8) {
                // Skip UTF-16 char length varint (1 or 2 bytes)
                val first = bytes[pos].toInt() and 0xFF
                pos += if ((first and 0x80) != 0) 2 else 1
                if (pos >= chunkEnd) {
                    out.add("")
                    continue
                }
                val b0 = bytes[pos].toInt() and 0xFF
                val byteLen = if ((b0 and 0x80) != 0 && pos + 1 < chunkEnd) {
                    val b1 = bytes[pos + 1].toInt() and 0xFF
                    pos += 2
                    ((b0 and 0x7F) shl 8) or b1
                } else {
                    pos += 1
                    b0
                }
                if (pos + byteLen > chunkEnd || byteLen < 0) {
                    out.add("")
                } else {
                    out.add(String(bytes, pos, byteLen, StandardCharsets.UTF_8))
                }
            } else {
                if (pos + 2 > chunkEnd) {
                    out.add("")
                    continue
                }
                val u0 = buf.getShort(pos).toInt() and 0xFFFF
                val charLen = if ((u0 and 0x8000) != 0 && pos + 4 <= chunkEnd) {
                    val u1 = buf.getShort(pos + 2).toInt() and 0xFFFF
                    pos += 4
                    ((u0 and 0x7FFF) shl 16) or u1
                } else {
                    pos += 2
                    u0
                }
                val byteLen = charLen * 2
                if (pos + byteLen > chunkEnd || byteLen < 0) {
                    out.add("")
                } else {
                    out.add(String(bytes, pos, byteLen, StandardCharsets.UTF_16LE))
                }
            }
        }
        return out
    }
}
