package com.hanson.mobileuse.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parsed 3D Mesh loaded directly from a real `.glb` (glTF 2.0 Binary) or `.obj` file on disk.
 */
data class LoadedGlb3DMesh(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val sourceLabel: String,
    val vertices: List<FloatArray>, // Each element is floatArrayOf(x, y, z) normalized to [-1.2f, 1.2f]
    val triangles: List<IntArray>,  // Each element is intArrayOf(i0, i1, i2, shadeBias)
    val generatorCode: String = ""
)

/**
 * Resolved YouTube Video Metadata & Cached Thumbnail File.
 */
data class ResolvedYouTubeMedia(
    val videoId: String,
    val title: String,
    val channelName: String,
    val watchUrl: String,
    val embedUrl: String,
    val thumbnailFilePath: String,
    val thumbnailSizeBytes: Long
)

/**
 * **Agent Media File & Code-Assistant Synthesis Engine (`AgentMediaFileEngine`)**
 *
 * Replaces static/packaged UI mocks with **real file-backed & URL-backed mobile rendering**:
 * 1. **YouTube Link & Search In-App Resolver**:
 *    Extracts `videoId` from any `youtube.com/watch?v=...`, `youtu.be/...`, `youtube.com/shorts/...` URL
 *    OR searches YouTube live to find the real top `videoId`, fetches official oEmbed title/channel,
 *    and downloads the real `hqdefault.jpg` thumbnail to `filesDir/agent_media/yt_<id>.jpg`.
 * 2. **Real `.glb` (glTF 2.0 Binary) & `.obj` Downloader, Code-Assistant 3D Compiler & Binary Parser**:
 *    Downloads real `.glb` files (e.g. Khronos `Duck.glb`, `Avocado.glb`, `CesiumMilkTruck.glb`, `BoxVertexColors.glb`, or any `.glb` URL),
 *    OR runs the Code Assistant 3D Mesh Synthesizer to compile a spec-compliant IEEE glTF 2.0 `.glb` binary file
 *    (`magic = 0x46546C67` `"glTF"`, JSON chunk, BIN float32 vertex & uint16 index buffers) to disk,
 *    and parses the actual `.glb` binary file from disk for 120Hz 3D rendering.
 * 3. **Real `.mp4`, `.gif`, `.png`, `.html` File Downloader & Code-Assistant File Generator**:
 *    Every visual card in chat is backed by a real file in `filesDir/agent_media/` (or `/sdcard/...`)
 *    that can be inspected, re-parsed, or opened at any time.
 */
object AgentMediaFileEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"

    /**
     * Official Khronos Group glTF 2.0 Binary (`.glb`) sample assets for instant real-file download
     * when the user asks to fetch/test real external 3D `.glb` files.
     */
    private val KHRONOS_SAMPLE_GLB_URLS = mapOf(
        "duck" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Duck/glTF-Binary/Duck.glb",
        "오리" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Duck/glTF-Binary/Duck.glb",
        "avocado" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Avocado/glTF-Binary/Avocado.glb",
        "아보카도" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Avocado/glTF-Binary/Avocado.glb",
        "truck" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CesiumMilkTruck/glTF-Binary/CesiumMilkTruck.glb",
        "트럭" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CesiumMilkTruck/glTF-Binary/CesiumMilkTruck.glb",
        "자동차" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CesiumMilkTruck/glTF-Binary/CesiumMilkTruck.glb",
        "helmet" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb",
        "헬멧" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/DamagedHelmet/glTF-Binary/DamagedHelmet.glb",
        "fox" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb",
        "여우" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/Fox/glTF-Binary/Fox.glb",
        "box" to "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/BoxVertexColors/glTF-Binary/BoxVertexColors.glb"
    )

    fun mediaDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, "agent_media")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    // =========================================================================
    // 1. YOUTUBE URL & SEARCH IN-APP RESOLVER
    // =========================================================================

    fun containsYouTubeUrlOrInAppRequest(prompt: String): Boolean {
        val pLower = prompt.lowercase(Locale.ROOT)
        if (extractYouTubeVideoIdFromUrl(prompt) != null) return true
        if ((pLower.contains("유튜브") || pLower.contains("youtube") || pLower.contains("youtu.be")) &&
            (pLower.contains("띄워") || pLower.contains("보여") || pLower.contains("앱 내") ||
                pLower.contains("앱에서") || pLower.contains("인앱") || pLower.contains("여기") ||
                pLower.contains("채팅") || pLower.contains("링크") || pLower.contains("영상") ||
                pLower.contains("재생") || pLower.contains("틀어"))
        ) {
            return true
        }
        return false
    }

    fun extractYouTubeVideoIdFromUrl(text: String): String? {
        val lower = text.lowercase()
        if (!lower.contains("youtube.com") && !lower.contains("youtu.be")) {
            return null
        }
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?[^\\s\"']*v=|youtu\\.be/|youtube\\.com/shorts/|youtube\\.com/embed/)([a-zA-Z0-9_-]{11})"),
            Regex("\\bv=([a-zA-Z0-9_-]{11})\\b")
        )
        for (regex in patterns) {
            val match = regex.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    fun resolveYouTubeMedia(context: Context, promptOrUrl: String): ResolvedYouTubeMedia {
        val directId = extractYouTubeVideoIdFromUrl(promptOrUrl)
        val videoId = directId ?: searchYouTubeTopVideoId(promptOrUrl) ?: "jfKfPfyJRdk" // Lofi Girl 24/7 stream fallback

        // Fetch official YouTube oEmbed JSON for real video title & channel name
        var realTitle = ""
        var channelName = "YouTube"
        runCatching {
            val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
            val req = Request.Builder().url(oembedUrl).header("User-Agent", USER_AGENT).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    realTitle = obj.optString("title", "").trim()
                    channelName = obj.optString("author_name", "YouTube").trim()
                }
            }
        }

        if (realTitle.isBlank()) {
            realTitle = "YouTube Video ($videoId)"
        }

        // Download real YouTube thumbnail (hqdefault.jpg) into filesDir/agent_media/
        val thumbFile = File(mediaDir(context), "yt_${videoId}.jpg")
        if (!thumbFile.exists() || thumbFile.length() < 512L) {
            runCatching {
                val thumbUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                val req = Request.Builder().url(thumbUrl).header("User-Agent", USER_AGENT).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            thumbFile.writeBytes(bytes)
                        }
                    }
                }
            }
        }

        return ResolvedYouTubeMedia(
            videoId = videoId,
            title = realTitle,
            channelName = channelName,
            watchUrl = "https://www.youtube.com/watch?v=$videoId",
            embedUrl = "https://www.youtube-nocookie.com/embed/$videoId?autoplay=1&playsinline=1&controls=1&rel=0",
            thumbnailFilePath = if (thumbFile.exists()) thumbFile.absolutePath else "",
            thumbnailSizeBytes = if (thumbFile.exists()) thumbFile.length() else 0L
        )
    }

    private fun searchYouTubeTopVideoId(rawPrompt: String): String? {
        val cleanedQuery = IntentDecomposerAndRouter.cleanYouTubeSearchQuery(rawPrompt)
            .ifBlank { "lofi hip hop radio beats to relax" }

        return runCatching {
            val encoded = URLEncoder.encode(cleanedQuery, "UTF-8")
            val searchUrl = "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAQ%253D%253D" // Filter: Videos only (excludes channels/playlists/ads)
            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                // Prefer videoRenderer blocks over adSlotRenderer so we never pick an ad videoId
                Regex("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\"").find(html)?.groupValues?.getOrNull(1)
                    ?: Regex("\"videoId\":\"([a-zA-Z0-9_-]{11})\"").find(html)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    // =========================================================================
    // 2. REAL .GLB (glTF 2.0 Binary) DOWNLOADER, CODE-ASSISTANT COMPILER & PARSER
    // =========================================================================

    fun resolveOrCompileGlb3DMesh(
        context: Context,
        prompt: String,
        explicitUrlOrPath: String = "",
        apiKey: String = ""
    ): LoadedGlb3DMesh {
        val pLower = prompt.lowercase(Locale.ROOT)
        val dir = mediaDir(context)

        // 1. Check if explicit local file path exists on device (/sdcard/... or filesDir/...)
        if (explicitUrlOrPath.startsWith("/") || explicitUrlOrPath.startsWith("file://")) {
            val localFile = File(explicitUrlOrPath.removePrefix("file://"))
            if (localFile.isFile && localFile.length() > 16) {
                parseGlbOrObjFile(localFile, "LOCAL FILE · ${localFile.name}")?.let { return it }
            }
        }

        // 2. Check if explicit HTTP(S) URL or matching Khronos glTF 2.0 Sample Model keyword
        val matchedSampleEntry = KHRONOS_SAMPLE_GLB_URLS.entries.firstOrNull { (kw, _) ->
            pLower.contains(kw)
        }
        val targetDownloadUrl = when {
            explicitUrlOrPath.startsWith("http://") || explicitUrlOrPath.startsWith("https://") -> explicitUrlOrPath
            matchedSampleEntry != null -> matchedSampleEntry.value
            else -> ""
        }

        if (targetDownloadUrl.isNotBlank()) {
            val fileName = targetDownloadUrl.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".glb", true) || it.endsWith(".obj", true) }
                ?: "downloaded_${abs(targetDownloadUrl.hashCode() % 10000)}.glb"
            val targetFile = File(dir, fileName)
            if (!targetFile.exists() || targetFile.length() < 64L) {
                runCatching {
                    val req = Request.Builder().url(targetDownloadUrl).header("User-Agent", USER_AGENT).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.bytes()?.let { bytes ->
                                if (bytes.size > 64) {
                                    targetFile.writeBytes(bytes)
                                }
                            }
                        }
                    }
                }
            }
            if (targetFile.isFile && targetFile.length() > 64L) {
                parseGlbOrObjFile(targetFile, "REAL GLB DOWNLOAD · ${targetFile.name}")?.let { return it }
            }
        }

        // 3. Code Assistant Agent 3D Mesh Synthesizer -> Compiles a REAL glTF 2.0 .glb binary file on disk!
        val slug = prompt.replace(Regex("[^가-힣a-zA-Z0-9]"), "_").take(18).ifBlank { "mesh" }
        val generatedGlbFile = File(dir, "codegen_${slug}_${abs(prompt.hashCode()) % 1000}.glb")

        val (rawVerts, rawFaces, generatorCode) = synthesize3DGeometryViaCodeAssistant(prompt, apiKey)
        writeGlbBinaryFile(generatedGlbFile, rawVerts, rawFaces)

        // Parse the newly written .glb binary file back from disk to verify 100% real file loading!
        return parseGlbOrObjFile(
            file = generatedGlbFile,
            sourceLabel = "CODE-ASSISTANT .GLB COMPILER (${generatedGlbFile.name})",
            generatorCode = generatorCode
        ) ?: LoadedGlb3DMesh(
            fileName = generatedGlbFile.name,
            filePath = generatedGlbFile.absolutePath,
            fileSizeBytes = generatedGlbFile.length(),
            sourceLabel = "CODE-ASSISTANT .GLB COMPILER",
            vertices = normalizeVertices(rawVerts),
            triangles = rawFaces,
            generatorCode = generatorCode
        )
    }

    /**
     * Calls the Code Assistant Agent (Gemini / Claude API when key is configured, or parametric 3D code synthesizer)
     * to generate custom 3D mesh vertices, triangle faces, and inspectable generator source code.
     */
    private fun synthesize3DGeometryViaCodeAssistant(
        prompt: String,
        apiKey: String
    ): Triple<List<FloatArray>, List<IntArray>, String> {
        val pLower = prompt.lowercase(Locale.ROOT)

        // Try Cloud LLM Code Assistant if API key is provided
        if (apiKey.isNotBlank()) {
            val cloudMesh = trySynthesize3DMeshFromCloudLlm(prompt, apiKey)
            if (cloudMesh != null) return cloudMesh
        }

        val verts = mutableListOf<FloatArray>()
        val faces = mutableListOf<IntArray>()

        fun addVert(x: Float, y: Float, z: Float): Int {
            verts.add(floatArrayOf(x, y, z))
            return verts.size - 1
        }
        fun addTri(a: Int, b: Int, c: Int, shade: Int = 0) {
            faces.add(intArrayOf(a, b, c, shade))
        }
        fun addQuad(a: Int, b: Int, c: Int, d: Int, shade: Int = 0) {
            faces.add(intArrayOf(a, b, c, shade))
            faces.add(intArrayOf(a, c, d, shade))
        }
        fun addBox(cx: Float, cy: Float, cz: Float, wx: Float, wy: Float, wz: Float, shade: Int = 0) {
            val base = verts.size
            val dx = wx / 2f; val dy = wy / 2f; val dz = wz / 2f
            addVert(cx - dx, cy - dy, cz - dz)
            addVert(cx + dx, cy - dy, cz - dz)
            addVert(cx + dx, cy + dy, cz - dz)
            addVert(cx - dx, cy + dy, cz - dz)
            addVert(cx - dx, cy - dy, cz + dz)
            addVert(cx + dx, cy - dy, cz + dz)
            addVert(cx + dx, cy + dy, cz + dz)
            addVert(cx - dx, cy + dy, cz + dz)
            addQuad(base + 4, base + 5, base + 6, base + 7, shade)
            addQuad(base + 1, base + 0, base + 3, base + 2, shade)
            addQuad(base + 3, base + 7, base + 6, base + 2, shade + 10)
            addQuad(base + 0, base + 1, base + 5, base + 4, shade - 10)
            addQuad(base + 1, base + 2, base + 6, base + 5, shade + 5)
            addQuad(base + 0, base + 4, base + 7, base + 3, shade - 5)
        }

        val shapeKind: String
        when {
            pLower.contains("dna") || pLower.contains("나선") || pLower.contains("helix") -> {
                shapeKind = "DNA_DOUBLE_HELIX"
                val rungs = 22
                for (i in 0 until rungs) {
                    val t = i.toFloat() / rungs
                    val y = -1.25f + t * 2.5f
                    val ang = t * PI.toFloat() * 3.2f
                    val r = 0.68f
                    val x1 = cos(ang) * r
                    val z1 = sin(ang) * r
                    val x2 = cos(ang + PI.toFloat()) * r
                    val z2 = sin(ang + PI.toFloat()) * r
                    addBox(x1, y, z1, 0.17f, 0.14f, 0.17f, 18)
                    addBox(x2, y, z2, 0.17f, 0.14f, 0.17f, -10)
                    if (i % 2 == 0) {
                        for (s in -2..2) {
                            val ratio = (s + 2) / 4f
                            val bx = x1 + (x2 - x1) * ratio
                            val bz = z1 + (z2 - z1) * ratio
                            addBox(bx, y, bz, 0.09f, 0.06f, 0.09f, 4)
                        }
                    }
                }
            }
            pLower.contains("타워") || pLower.contains("에펠") || pLower.contains("탑") || pLower.contains("건축") || pLower.contains("tower") -> {
                shapeKind = "ARCHITECTURAL_TOWER"
                val levels = 9
                for (lv in 0 until levels) {
                    val t0 = lv.toFloat() / levels
                    val t1 = (lv + 1).toFloat() / levels
                    val y0 = 1.15f - t0 * 2.3f
                    val y1 = 1.15f - t1 * 2.3f
                    val spread0 = 0.78f * (1f - t0 * 0.82f) + 0.06f
                    val spread1 = 0.78f * (1f - t1 * 0.82f) + 0.06f
                    val b = verts.size
                    addVert(-spread0, y0, -spread0); addVert(spread0, y0, -spread0)
                    addVert(spread0, y0, spread0); addVert(-spread0, y0, spread0)
                    addVert(-spread1, y1, -spread1); addVert(spread1, y1, -spread1)
                    addVert(spread1, y1, spread1); addVert(-spread1, y1, spread1)
                    addQuad(b + 0, b + 1, b + 5, b + 4, (lv % 2) * 14)
                    addQuad(b + 1, b + 2, b + 6, b + 5, 8)
                    addQuad(b + 2, b + 3, b + 7, b + 6, -6)
                    addQuad(b + 3, b + 0, b + 4, b + 7, -12)
                    if (lv == 2 || lv == 5) {
                        addBox(0f, y0, 0f, spread0 * 2.15f, 0.08f, spread0 * 2.15f, 20)
                    }
                }
                addBox(0f, -1.26f, 0f, 0.06f, 0.35f, 0.06f, 25)
            }
            pLower.contains("우주선") || pLower.contains("비행기") || pLower.contains("전투기") || pLower.contains("드론") || pLower.contains("로켓") || pLower.contains("ship") -> {
                shapeKind = "STARSHIP_CRAFT"
                // Fuselage + swept delta wings + twin thruster nacelles
                addBox(0f, 0f, 0f, 0.36f, 0.24f, 1.75f, 16)
                addBox(0f, -0.08f, -0.95f, 0.20f, 0.14f, 0.45f, 24) // Nose cone
                addBox(0f, -0.14f, -0.25f, 0.24f, 0.16f, 0.55f, 28) // Cockpit canopy
                // Swept wings
                for (step in 1..5) {
                    val f = step / 5f
                    val wx = 0.22f + f * 0.85f
                    val wz = -0.20f + f * 0.55f
                    addBox(-wx, 0.02f, wz, 0.32f, 0.05f, 0.36f, 6)
                    addBox(wx, 0.02f, wz, 0.32f, 0.05f, 0.36f, 6)
                }
                // Twin tail fins & thrusters
                addBox(-0.42f, -0.22f, 0.62f, 0.06f, 0.38f, 0.32f, 18)
                addBox(0.42f, -0.22f, 0.62f, 0.06f, 0.38f, 0.32f, 18)
                addBox(-0.32f, 0.04f, 0.78f, 0.22f, 0.22f, 0.36f, -12)
                addBox(0.32f, 0.04f, 0.78f, 0.22f, 0.22f, 0.36f, -12)
            }
            pLower.contains("기어") || pLower.contains("톱니") || pLower.contains("시계") || pLower.contains("토러스") || pLower.contains("도넛") || pLower.contains("링") -> {
                shapeKind = "PRECISION_TORUS_GEAR"
                val uSteps = 28
                val vSteps = 12
                for (i in 0 until uSteps) {
                    val u = (i.toFloat() / uSteps) * (PI.toFloat() * 2f)
                    val toothBoost = if (i % 2 == 0) 0.18f else 0f
                    val rMajor = 0.78f + toothBoost
                    val rMinor = 0.24f
                    for (j in 0 until vSteps) {
                        val v = (j.toFloat() / vSteps) * (PI.toFloat() * 2f)
                        val x = (rMajor + rMinor * cos(v)) * cos(u)
                        val y = (rMajor + rMinor * cos(v)) * sin(u)
                        val z = rMinor * sin(v)
                        addVert(x, y, z)
                    }
                }
                for (i in 0 until uSteps) {
                    val ni = (i + 1) % uSteps
                    for (j in 0 until vSteps) {
                        val nj = (j + 1) % vSteps
                        addQuad(
                            i * vSteps + j,
                            ni * vSteps + j,
                            ni * vSteps + nj,
                            i * vSteps + nj,
                            if (i % 2 == 0) 16 else -8
                        )
                    }
                }
            }
            pLower.contains("구") || pLower.contains("지구") || pLower.contains("행성") || pLower.contains("돔") || pLower.contains("sphere") || pLower.contains("결정") || pLower.contains("크리스탈") -> {
                shapeKind = "GEODESIC_CRYSTAL_SPHERE"
                val latSteps = 14
                val lonSteps = 20
                val rBase = 0.96f
                for (i in 0..latSteps) {
                    val theta = (i.toFloat() / latSteps) * PI.toFloat()
                    for (j in 0 until lonSteps) {
                        val phi = (j.toFloat() / lonSteps) * (PI.toFloat() * 2f)
                        val facetMod = if ((i + j) % 2 == 0 && pLower.contains("크리스탈")) 1.08f else 1.0f
                        val x = rBase * facetMod * sin(theta) * cos(phi)
                        val y = rBase * facetMod * cos(theta)
                        val z = rBase * facetMod * sin(theta) * sin(phi)
                        addVert(x, y, z)
                    }
                }
                for (i in 0 until latSteps) {
                    for (j in 0 until lonSteps) {
                        val nj = (j + 1) % lonSteps
                        val a = i * lonSteps + j
                        val b = (i + 1) * lonSteps + j
                        val c = (i + 1) * lonSteps + nj
                        val d = i * lonSteps + nj
                        addQuad(a, b, c, d, ((i + j) % 3 - 1) * 10)
                    }
                }
            }
            else -> {
                shapeKind = "ARTICULATED_MECH_UNIT"
                // Articulated Mech Robot with head visor, antenna, torso reactor, shoulder pauldrons, arms, legs
                addBox(0f, -0.78f, 0f, 0.56f, 0.44f, 0.48f, 18)
                addBox(0f, -0.80f, 0.25f, 0.44f, 0.14f, 0.06f, 28)
                addBox(0f, -1.10f, 0f, 0.07f, 0.24f, 0.07f, 24)
                addBox(0f, -0.14f, 0f, 0.86f, 0.72f, 0.54f, 6)
                addBox(0f, -0.18f, 0.28f, 0.34f, 0.30f, 0.08f, 24)
                addBox(-0.62f, -0.36f, 0f, 0.34f, 0.28f, 0.44f, 14)
                addBox(0.62f, -0.36f, 0f, 0.34f, 0.28f, 0.44f, 14)
                addBox(-0.64f, 0.12f, 0.08f, 0.24f, 0.62f, 0.28f, -6)
                addBox(0.64f, 0.12f, 0.08f, 0.24f, 0.62f, 0.28f, -6)
                addBox(-0.24f, 0.66f, 0f, 0.30f, 0.76f, 0.34f, -10)
                addBox(0.24f, 0.66f, 0f, 0.30f, 0.76f, 0.34f, -10)
                addBox(-0.24f, 1.08f, 0.08f, 0.34f, 0.16f, 0.48f, 10)
                addBox(0.24f, 1.08f, 0.08f, 0.34f, 0.16f, 0.48f, 10)
            }
        }

        val codeSummary = """
// [Code Assistant 3D Mesh -> IEEE glTF 2.0 (.glb) Binary Compiler]
// Target Prompt: "$prompt"
// Synthesized Preset Geometry: $shapeKind
// Vertices: ${verts.size} (FLOAT32 VEC3) | Triangles: ${faces.size} (UINT16 SCALAR)
fun compileMeshToGlbBinary(): ByteArray {
    val vertices = FloatArray(${verts.size * 3})
    val indices = ShortArray(${faces.size * 3})
    return GlbBinaryWriter.packGlb20(vertices, indices) // magic = 0x46546C67 ("glTF" v2)
}
        """.trimIndent()

        return Triple(verts, faces, codeSummary)
    }

    private fun callCloudCodeAssistantText(
        systemPrompt: String,
        userPrompt: String,
        apiKey: String
    ): Pair<String, String>? {
        val keySet = GeminiMultimodalClient.Companion.MultiProviderApiKeySet.parse(apiKey)

        // 1. Try Google Gemini API (gemini-2.5-flash-lite / gemini-2.0-flash)
        val geminiKey = keySet.geminiKey
        if (!geminiKey.isNullOrBlank()) {
            for (model in listOf("gemini-2.5-flash-lite", "gemini-2.0-flash")) {
                val res = runCatching {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$geminiKey"
                    val bodyJson = JSONObject().apply {
                        put(
                            "contents",
                            JSONArray().put(
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", "$systemPrompt\n\nUser Request: $userPrompt"))
                                )
                            )
                        )
                    }
                    val req = Request.Builder()
                        .url(url)
                        .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val raw = JSONObject(resp.body?.string() ?: return@use null)
                        val text = raw.optJSONArray("candidates")
                            ?.optJSONObject(0)
                            ?.optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (text.isNotBlank()) "GEMINI-CODE ($model)" to text else null
                    }
                }.getOrNull()
                if (res != null) return res
            }
        }

        // 2. Try OpenAI API (gpt-4o-mini) if OpenAI key is configured
        val openAiKey = keySet.openAiKey
        if (!openAiKey.isNullOrBlank()) {
            val res = runCatching {
                val url = "https://api.openai.com/v1/chat/completions"
                val bodyJson = JSONObject().apply {
                    put("model", "gpt-4o-mini")
                    put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", systemPrompt))
                            .put(JSONObject().put("role", "user").put("content", userPrompt))
                    )
                    put("temperature", 0.3)
                }
                val req = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer $openAiKey")
                    .post(bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = JSONObject(resp.body?.string() ?: return@use null)
                    val text = raw.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
                        .orEmpty()
                        .trim()
                    if (text.isNotBlank()) "OPENAI-CODE (gpt-4o-mini)" to text else null
                }
            }.getOrNull()
            if (res != null) return res
        }

        return null
    }

    private fun trySynthesize3DMeshFromCloudLlm(
        prompt: String,
        apiKey: String
    ): Triple<List<FloatArray>, List<IntArray>, String>? {
        val sysPrompt = """
You are a 3D Mesh Code Assistant that outputs Wavefront OBJ geometry (`v x y z` and `f i1 i2 i3`) for a 3D object matching the user's request.
Coordinates must be within [-1.2, 1.2]. Output between 24 and 180 vertices and 36 to 300 triangles so the 3D silhouette is recognizable.
Output ONLY raw OBJ lines (`v ...` and `f ...`, 1-based indices), no markdown fences.
        """.trimIndent()
        val (providerBadge, rawText) = callCloudCodeAssistantText(sysPrompt, prompt, apiKey) ?: return null
        val cleaned = rawText
            .removePrefix("```obj")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val parsed = parseObjText(cleaned)
        return if (parsed != null && parsed.first.size >= 8 && parsed.second.size >= 8) {
            Triple(parsed.first, parsed.second, "# Synthesized by $providerBadge\n$cleaned")
        } else {
            null
        }
    }

    /**
     * Writes a real spec-compliant glTF 2.0 `.glb` binary file (`magic = 0x46546C67`, `version = 2`)
     * containing a `JSON` scene chunk and a `BIN` geometry chunk (`POSITION` float32 + `indices` uint16).
     */
    fun writeGlbBinaryFile(targetFile: File, vertices: List<FloatArray>, triangles: List<IntArray>) {
        val normVerts = normalizeVertices(vertices)
        val vertCount = normVerts.size
        val indexCount = triangles.size * 3

        val posByteLen = vertCount * 3 * 4
        val idxByteLen = indexCount * 2
        val idxAlignedLen = (idxByteLen + 3) and -4
        val totalBinLen = posByteLen + idxAlignedLen

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (v in normVerts) {
            minX = min(minX, v[0]); minY = min(minY, v[1]); minZ = min(minZ, v[2])
            maxX = max(maxX, v[0]); maxY = max(maxY, v[1]); maxZ = max(maxZ, v[2])
        }

        val gltfJson = JSONObject().apply {
            put("asset", JSONObject().put("version", "2.0").put("generator", "Hanson-CodeAssistant-GLB-Compiler"))
            put("scene", 0)
            put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
            put("nodes", JSONArray().put(JSONObject().put("mesh", 0)))
            put(
                "meshes",
                JSONArray().put(
                    JSONObject().put(
                        "primitives",
                        JSONArray().put(
                            JSONObject()
                                .put("attributes", JSONObject().put("POSITION", 0))
                                .put("indices", 1)
                                .put("mode", 4)
                        )
                    )
                )
            )
            put("buffers", JSONArray().put(JSONObject().put("byteLength", totalBinLen)))
            put(
                "bufferViews",
                JSONArray()
                    .put(JSONObject().put("buffer", 0).put("byteOffset", 0).put("byteLength", posByteLen).put("target", 34962))
                    .put(JSONObject().put("buffer", 0).put("byteOffset", posByteLen).put("byteLength", idxByteLen).put("target", 34963))
            )
            put(
                "accessors",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("bufferView", 0)
                            .put("byteOffset", 0)
                            .put("componentType", 5126) // FLOAT
                            .put("count", vertCount)
                            .put("type", "VEC3")
                            .put("min", JSONArray().put(minX).put(minY).put(minZ))
                            .put("max", JSONArray().put(maxX).put(maxY).put(maxZ))
                    )
                    .put(
                        JSONObject()
                            .put("bufferView", 1)
                            .put("byteOffset", 0)
                            .put("componentType", 5123) // UNSIGNED_SHORT
                            .put("count", indexCount)
                            .put("type", "SCALAR")
                    )
            )
        }

        val jsonBytesRaw = gltfJson.toString().toByteArray(Charsets.UTF_8)
        val jsonPaddedLen = (jsonBytesRaw.size + 3) and -4
        val totalFileLen = 12 + 8 + jsonPaddedLen + 8 + totalBinLen

        val buf = ByteBuffer.allocate(totalFileLen).order(ByteOrder.LITTLE_ENDIAN)
        // 12-byte GLB Header
        buf.putInt(0x46546C67) // "glTF"
        buf.putInt(2)          // version 2
        buf.putInt(totalFileLen)

        // Chunk 0: JSON
        buf.putInt(jsonPaddedLen)
        buf.putInt(0x4E4F534A) // "JSON"
        buf.put(jsonBytesRaw)
        for (p in 0 until (jsonPaddedLen - jsonBytesRaw.size)) {
            buf.put(0x20.toByte()) // pad with spaces per glTF 2.0 spec
        }

        // Chunk 1: BIN
        buf.putInt(totalBinLen)
        buf.putInt(0x004E4942) // "BIN\0"
        for (v in normVerts) {
            buf.putFloat(v[0])
            buf.putFloat(v[1])
            buf.putFloat(v[2])
        }
        for (tri in triangles) {
            buf.putShort(tri[0].coerceIn(0, max(0, vertCount - 1)).toShort())
            buf.putShort(tri[1].coerceIn(0, max(0, vertCount - 1)).toShort())
            buf.putShort(tri[2].coerceIn(0, max(0, vertCount - 1)).toShort())
        }
        for (p in 0 until (idxAlignedLen - idxByteLen)) {
            buf.put(0.toByte())
        }

        targetFile.writeBytes(buf.array())
    }

    /**
     * Parses a real `.glb` (glTF 2.0 Binary) or `.obj` file from disk into 3D vertices & triangles!
     */
    fun parseGlbOrObjFile(
        file: File,
        sourceLabel: String = "LOCAL GLB FILE",
        generatorCode: String = ""
    ): LoadedGlb3DMesh? {
        if (!file.isFile || file.length() < 20L) return null
        return runCatching {
            val bytes = file.readBytes()
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.getInt(0)

            // Check if IEEE glTF 2.0 binary (.glb): magic == 0x46546C67 ("glTF")
            if (magic == 0x46546C67 && bytes.size >= 28) {
                val jsonChunkLen = buf.getInt(12)
                val jsonChunkType = buf.getInt(16)
                if (jsonChunkType != 0x4E4F534A || 20 + jsonChunkLen > bytes.size) return@runCatching null

                val jsonStr = String(bytes, 20, jsonChunkLen, Charsets.UTF_8).trim()
                val gltf = JSONObject(jsonStr)

                val binChunkHeaderOffset = 20 + jsonChunkLen
                if (binChunkHeaderOffset + 8 > bytes.size) return@runCatching null
                val binChunkLen = buf.getInt(binChunkHeaderOffset)
                val binStart = binChunkHeaderOffset + 8
                if (binStart + binChunkLen > bytes.size) return@runCatching null

                val accessors = gltf.optJSONArray("accessors") ?: return@runCatching null
                val bufferViews = gltf.optJSONArray("bufferViews") ?: return@runCatching null
                val meshes = gltf.optJSONArray("meshes") ?: return@runCatching null

                val allVerts = mutableListOf<FloatArray>()
                val allTris = mutableListOf<IntArray>()

                for (mIdx in 0 until meshes.length()) {
                    val mesh = meshes.optJSONObject(mIdx) ?: continue
                    val primitives = mesh.optJSONArray("primitives") ?: continue
                    for (pIdx in 0 until primitives.length()) {
                        val prim = primitives.optJSONObject(pIdx) ?: continue
                        val attrs = prim.optJSONObject("attributes") ?: continue
                        val posAccIdx = attrs.optInt("POSITION", -1)
                        if (posAccIdx < 0 || posAccIdx >= accessors.length()) continue

                        val posAcc = accessors.getJSONObject(posAccIdx)
                        val posBvIdx = posAcc.optInt("bufferView", -1)
                        if (posBvIdx < 0 || posBvIdx >= bufferViews.length()) continue
                        val posBv = bufferViews.getJSONObject(posBvIdx)

                        val posCount = posAcc.optInt("count", 0)
                        val posByteOffset = posBv.optInt("byteOffset", 0) + posAcc.optInt("byteOffset", 0)
                        val posStride = posBv.optInt("byteStride", 12).let { if (it <= 0) 12 else it }

                        val baseVertexIndex = allVerts.size
                        for (vIdx in 0 until posCount) {
                            val off = binStart + posByteOffset + vIdx * posStride
                            if (off + 12 <= bytes.size) {
                                val vx = buf.getFloat(off)
                                val vy = buf.getFloat(off + 4)
                                val vz = buf.getFloat(off + 8)
                                allVerts.add(floatArrayOf(vx, -vy, vz)) // flip Y for screen projection
                            }
                        }

                        val idxAccIdx = prim.optInt("indices", -1)
                        if (idxAccIdx in 0 until accessors.length()) {
                            val idxAcc = accessors.getJSONObject(idxAccIdx)
                            val idxBvIdx = idxAcc.optInt("bufferView", -1)
                            if (idxBvIdx in 0 until bufferViews.length()) {
                                val idxBv = bufferViews.getJSONObject(idxBvIdx)
                                val idxCount = idxAcc.optInt("count", 0)
                                val compType = idxAcc.optInt("componentType", 5123)
                                val idxByteOffset = idxBv.optInt("byteOffset", 0) + idxAcc.optInt("byteOffset", 0)
                                val elemSize = when (compType) {
                                    5121 -> 1 // UNSIGNED_BYTE
                                    5123 -> 2 // UNSIGNED_SHORT
                                    5125 -> 4 // UNSIGNED_INT
                                    else -> 2
                                }
                                val triTotal = idxCount / 3
                                // Smart stride if mesh has > 1600 triangles so 120Hz canvas stays ultra-fast
                                val step = max(1, triTotal / 1400)
                                var t = 0
                                while (t < triTotal) {
                                    val iOff = binStart + idxByteOffset + (t * 3) * elemSize
                                    if (iOff + elemSize * 3 <= bytes.size) {
                                        val i0 = readIndex(buf, bytes, iOff, compType) + baseVertexIndex
                                        val i1 = readIndex(buf, bytes, iOff + elemSize, compType) + baseVertexIndex
                                        val i2 = readIndex(buf, bytes, iOff + elemSize * 2, compType) + baseVertexIndex
                                        if (i0 in allVerts.indices && i1 in allVerts.indices && i2 in allVerts.indices) {
                                            allTris.add(intArrayOf(i0, i1, i2, (t % 5 - 2) * 6))
                                        }
                                    }
                                    t += step
                                }
                            }
                        } else {
                            val triTotal = posCount / 3
                            for (t in 0 until triTotal) {
                                val i0 = baseVertexIndex + t * 3
                                if (i0 + 2 < allVerts.size) {
                                    allTris.add(intArrayOf(i0, i0 + 1, i0 + 2, (t % 5 - 2) * 6))
                                }
                            }
                        }
                    }
                }

                if (allVerts.isNotEmpty() && allTris.isNotEmpty()) {
                    return@runCatching LoadedGlb3DMesh(
                        fileName = file.name,
                        filePath = file.absolutePath,
                        fileSizeBytes = file.length(),
                        sourceLabel = sourceLabel,
                        vertices = normalizeVertices(allVerts),
                        triangles = allTris,
                        generatorCode = generatorCode.ifBlank { "Parsed binary glTF 2.0 (${file.name})" }
                    )
                }
            }

            // Fallback: Try parsing as Wavefront .obj ASCII file
            val text = file.readText(Charsets.UTF_8)
            val objParsed = parseObjText(text)
            if (objParsed != null && objParsed.first.isNotEmpty()) {
                return@runCatching LoadedGlb3DMesh(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    sourceLabel = sourceLabel,
                    vertices = normalizeVertices(objParsed.first),
                    triangles = objParsed.second,
                    generatorCode = generatorCode.ifBlank { text.take(1200) }
                )
            }
            null
        }.getOrNull()
    }

    private fun readIndex(buf: ByteBuffer, bytes: ByteArray, offset: Int, componentType: Int): Int {
        return when (componentType) {
            5121 -> bytes[offset].toInt() and 0xFF
            5123 -> buf.getShort(offset).toInt() and 0xFFFF
            5125 -> buf.getInt(offset)
            else -> buf.getShort(offset).toInt() and 0xFFFF
        }
    }

    private fun parseObjText(objText: String): Pair<List<FloatArray>, List<IntArray>>? {
        val verts = mutableListOf<FloatArray>()
        val faces = mutableListOf<IntArray>()
        for (rawLine in objText.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("v ")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 4) {
                    val x = parts[1].toFloatOrNull() ?: continue
                    val y = parts[2].toFloatOrNull() ?: continue
                    val z = parts[3].toFloatOrNull() ?: continue
                    verts.add(floatArrayOf(x, -y, z))
                }
            } else if (line.startsWith("f ")) {
                val parts = line.split(Regex("\\s+")).drop(1)
                val idxList = parts.mapNotNull { token ->
                    token.substringBefore('/').toIntOrNull()?.minus(1)
                }
                if (idxList.size >= 3) {
                    val i0 = idxList[0]
                    for (k in 1 until idxList.size - 1) {
                        val i1 = idxList[k]
                        val i2 = idxList[k + 1]
                        if (i0 in verts.indices && i1 in verts.indices && i2 in verts.indices) {
                            faces.add(intArrayOf(i0, i1, i2, (faces.size % 5 - 2) * 7))
                        }
                    }
                }
            }
        }
        return if (verts.isNotEmpty() && faces.isNotEmpty()) verts to faces else null
    }

    private fun normalizeVertices(raw: List<FloatArray>): List<FloatArray> {
        if (raw.isEmpty()) return raw
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        for (v in raw) {
            minX = min(minX, v[0]); minY = min(minY, v[1]); minZ = min(minZ, v[2])
            maxX = max(maxX, v[0]); maxY = max(maxY, v[1]); maxZ = max(maxZ, v[2])
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val maxSpan = max(0.001f, max(maxX - minX, max(maxY - minY, maxZ - minZ)))
        val scale = 2.0f / maxSpan
        return raw.map { v ->
            floatArrayOf((v[0] - cx) * scale, (v[1] - cy) * scale, (v[2] - cz) * scale)
        }
    }

    // =========================================================================
    // 3. REAL IMAGE (.png/.jpg) & CODE-ASSISTANT FILE SYNTHESIZER
    // =========================================================================

    fun saveOrDownloadImageFile(
        context: Context,
        prompt: String,
        mediaUrlOrPath: String = "",
        base64Data: String? = null
    ): Triple<File, String, String> {
        val dir = mediaDir(context)
        val slug = prompt.replace(Regex("[^가-힣a-zA-Z0-9]"), "_").take(18).ifBlank { "image" }

        // 1. Local file path
        if (mediaUrlOrPath.startsWith("/") || mediaUrlOrPath.startsWith("file://")) {
            val f = File(mediaUrlOrPath.removePrefix("file://"))
            if (f.isFile && f.length() > 32) {
                return Triple(f, "LOCAL IMAGE FILE (${f.name})", "")
            }
        }

        // 2. Remote HTTP(S) image download
        if (mediaUrlOrPath.startsWith("http://") || mediaUrlOrPath.startsWith("https://")) {
            val target = File(dir, "dl_${slug}_${abs(mediaUrlOrPath.hashCode()) % 10000}.png")
            runCatching {
                val req = Request.Builder().url(mediaUrlOrPath).header("User-Agent", USER_AGENT).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.bytes()?.let { bytes ->
                            if (bytes.size > 64) target.writeBytes(bytes)
                        }
                    }
                }
            }
            if (target.isFile && target.length() > 64) {
                return Triple(target, "WEB DOWNLOADED IMAGE (${target.name})", "")
            }
        }

        // 3. Gemini Cloud Image Base64 -> Save as real .png file
        if (!base64Data.isNullOrBlank()) {
            val target = File(dir, "gemini_${slug}_${System.currentTimeMillis() % 10000}.png")
            runCatching {
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                target.writeBytes(bytes)
            }
            if (target.isFile && target.length() > 64) {
                return Triple(target, "GEMINI GENERATED PNG (${target.name})", "// Gemini Multimodal Image API output saved to ${target.name}")
            }
        }

        // 4. Code Assistant Procedural High-DPI PNG File Renderer -> Renders & writes real .png to disk
        val target = File(dir, "codegen_${slug}_${abs(prompt.hashCode()) % 10000}.png")
        val bmp = Bitmap.createBitmap(960, 560, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#09090B"))

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#18181B")
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        for (x in 0..960 step 40) c.drawLine(x.toFloat(), 0f, x.toFloat(), 560f, gridPaint)
        for (y in 0..560 step 40) c.drawLine(0f, y.toFloat(), 960f, y.toFloat(), gridPaint)

        val seed = abs(prompt.hashCode()) % 997 + 1
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        for (layer in 0 until 6) {
            val path = Path()
            val freq = 0.006f + layer * 0.0022f + (seed % 19) * 0.0003f
            val amp = 44f + layer * 22f
            for (x in 0..960 step 8) {
                val y = 280f + sin(x * freq + layer + seed * 0.1f) * amp * cos(x * 0.0035f - layer * 0.4f)
                if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            linePaint.color = if (layer == 0) Color.parseColor("#FAFAFA") else Color.argb(180 - layer * 24, 244, 244, 245)
            linePaint.strokeWidth = if (layer == 0) 3.8f else 1.8f
            c.drawPath(path, linePaint)
        }

        // Focal geometry rings & title
        linePaint.color = Color.parseColor("#FAFAFA")
        linePaint.strokeWidth = 2.8f
        c.drawCircle(480f, 265f, 92f, linePaint)
        linePaint.strokeWidth = 1.4f
        c.drawCircle(480f, 265f, 136f, linePaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FAFAFA")
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        c.drawText(prompt.take(32), 480f, 495f, textPaint)
        textPaint.textSize = 16f
        textPaint.color = Color.parseColor("#A1A1AA")
        c.drawText("Vibe Lifestyle Companion", 480f, 526f, textPaint)

        FileOutputStream(target).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bmp.recycle()

        val genCode = """
// [Code Assistant PNG Artwork Synthesizer]
// Output File: ${target.name}
val bitmap = Bitmap.createBitmap(960, 560, Bitmap.Config.ARGB_8888)
renderHarmonicField(Canvas(bitmap), prompt = "$prompt", seed = $seed)
bitmap.compress(Bitmap.CompressFormat.PNG, 100, FileOutputStream("${target.name}"))
        """.trimIndent()

        return Triple(target, "이미지 파일 (${target.name})", genCode)
    }

    /**
     * Resolves or downloads a real `.mp4` video file if a URL or local path is provided,
     * or writes a Code-Assistant motion-graphic storyboard & script specification file on disk.
     */
    fun resolveOrSynthesizeMp4File(
        context: Context,
        prompt: String,
        mediaUrlOrPath: String = ""
    ): Triple<File, String, String> {
        val dir = mediaDir(context)
        val slug = prompt.replace(Regex("[^가-힣a-zA-Z0-9]"), "_").take(18).ifBlank { "video" }

        // 1. Local .mp4 file on device (/sdcard/... or filesDir/...)
        if (mediaUrlOrPath.startsWith("/") || mediaUrlOrPath.startsWith("file://")) {
            val f = File(mediaUrlOrPath.removePrefix("file://"))
            if (f.isFile && f.length() > 128) {
                return Triple(f, "LOCAL MP4 FILE (${f.name})", "")
            }
        }

        // 2. Check if there is any .mp4 in /sdcard/Download matching or if a URL is provided
        if (mediaUrlOrPath.startsWith("http://") || mediaUrlOrPath.startsWith("https://")) {
            val target = File(dir, "dl_${slug}_${abs(mediaUrlOrPath.hashCode()) % 10000}.mp4")
            runCatching {
                val req = Request.Builder().url(mediaUrlOrPath).header("User-Agent", USER_AGENT).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { input ->
                            FileOutputStream(target).use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
            if (target.isFile && target.length() > 256) {
                return Triple(target, "DOWNLOADED MP4 VIDEO (${target.name})", "")
            }
        }

        // 3. Code Assistant Motion-Graphic Scene File (.json / .mp4 spec) saved on disk
        val specFile = File(dir, "codegen_${slug}_motion.json")
        val sceneJson = JSONObject().apply {
            put("title", prompt.take(36))
            put("fps", 60)
            put("durationSec", 12.0)
            put("generator", "Hanson-CodeAssistant-MotionEngine")
            put(
                "acts",
                JSONArray()
                    .put(JSONObject().put("act", 1).put("headline", prompt.take(26)).put("sub", "60FPS REALTIME MOTION"))
                    .put(JSONObject().put("act", 2).put("headline", "CODE ASSISTANT SYNTHESIS").put("sub", "DYNAMIC SCENE GRAPH"))
                    .put(JSONObject().put("act", 3).put("headline", "MOBILE FILE ENGINE").put("sub", specFile.name))
            )
        }
        specFile.writeText(sceneJson.toString(2), Charsets.UTF_8)
        return Triple(specFile, "CODE-ASSISTANT MOTION SPEC (${specFile.name})", sceneJson.toString(2))
    }

    /**
     * Synthesizes a custom interactive HTML5 / SVG / Canvas UI file via the Code Assistant Agent
     * (live Gemini / OpenAI Code Assistant when `apiKey` is present, with procedural fallback)
     * and saves it to `filesDir/agent_media/codegen_<slug>.html`.
     */
    fun synthesizeAndSaveCodeCanvasFile(
        context: Context,
        prompt: String,
        fallbackHtmlContent: String,
        apiKey: String = "",
        hasCustomCode: Boolean = false
    ): Triple<File, String, String> {
        val dir = mediaDir(context)
        val slug = prompt.replace(Regex("[^가-힣a-zA-Z0-9]"), "_").take(18).ifBlank { "ui" }
        val file = File(dir, "codegen_${slug}_${abs(prompt.hashCode()) % 10000}.html")

        var finalHtml = fallbackHtmlContent
        var sourceLabel = "CODE-ASSISTANT UI FILE (${file.name})"

        if (!hasCustomCode && apiKey.isNotBlank()) {
            val sysPrompt = """
You are an elite Mobile Code Assistant Agent that writes self-contained, interactive HTML5 + CSS + JavaScript / SVG / Canvas mini-apps for a dark-mode monochrome (Ahaboard style: #09090B background, #18181B cards, #FAFAFA primary ink, #A1A1AA secondary text, #27272A borders) mobile viewport (width 100%, height 220px).
Make the UI immediately interactive on touch/click (buttons, canvas animation, sliders, nodes, or interactive simulation matching the user's Korean prompt).
Output ONLY a complete valid `<!DOCTYPE html><html>...</html>` document with no markdown code fences.
            """.trimIndent()
            val cloudResult = callCloudCodeAssistantText(sysPrompt, prompt, apiKey)
            if (cloudResult != null) {
                val (badge, rawHtml) = cloudResult
                val cleaned = rawHtml
                    .removePrefix("```html")
                    .removePrefix("```HTML")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                if (cleaned.contains("<html", ignoreCase = true) || cleaned.contains("<svg", ignoreCase = true) || cleaned.contains("<div", ignoreCase = true)) {
                    finalHtml = if (cleaned.contains("<html", ignoreCase = true)) {
                        cleaned
                    } else {
                        "<!DOCTYPE html><html><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width,initial-scale=1.0'/><style>body{margin:0;padding:8px;background:#09090B;color:#FAFAFA;font-family:sans-serif;}</style></head><body>$cleaned</body></html>"
                    }
                    sourceLabel = "$badge · ${file.name}"
                }
            }
        }

        file.writeText(finalHtml, Charsets.UTF_8)
        return Triple(file, sourceLabel, finalHtml)
    }
}
