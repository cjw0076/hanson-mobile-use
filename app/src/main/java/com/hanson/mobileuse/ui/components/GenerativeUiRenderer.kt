package com.hanson.mobileuse.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hanson.mobileuse.agent.AgentMediaFileEngine
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.LoadedGlb3DMesh
import com.hanson.mobileuse.agent.MediaFormatType
import com.hanson.mobileuse.agent.MultimodalMediaBlock
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Structured canvas and WebView error telemetry captured from console, runtime errors, and resource failures.
 */
data class CanvasErrorTelemetry(
    val type: String = "runtime_error",
    val message: String = "",
    val source: String? = null,
    val lineNumber: Int? = null,
    val columnNumber: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val rawJson: String = ""
) {
    fun toFeedbackPrompt(): String {
        val loc = buildString {
            if (lineNumber != null) append(" 라인 $lineNumber")
            if (columnNumber != null) append(" 컬럼 $columnNumber")
            if (!source.isNullOrBlank()) append(" (소스: $source)")
        }.trim()
        val locStr = if (loc.isNotBlank()) " [$loc]" else ""
        return "[Canvas Error Intercepted] $type: $message$locStr. 해당 오류를 분석하고 수정된 코드로 즉시 재시도(Self-Correction)하세요."
    }

    fun toJsonString(): String {
        return JSONObject().apply {
            put("type", type)
            put("message", message)
            if (source != null) put("source", source)
            if (lineNumber != null) put("lineNumber", lineNumber)
            if (columnNumber != null) put("columnNumber", columnNumber)
            put("timestamp", timestamp)
            if (rawJson.isNotBlank()) put("rawJson", rawJson)
        }.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): CanvasErrorTelemetry {
            return runCatching {
                val obj = JSONObject(jsonStr)
                CanvasErrorTelemetry(
                    type = obj.optString("type", "runtime_error"),
                    message = obj.optString("message", obj.optString("reason", jsonStr)),
                    source = if (obj.has("source") && !obj.isNull("source")) obj.optString("source") else null,
                    lineNumber = when {
                        obj.has("lineNumber") && !obj.isNull("lineNumber") -> obj.optInt("lineNumber")
                        obj.has("line") && !obj.isNull("line") -> obj.optInt("line")
                        else -> null
                    },
                    columnNumber = when {
                        obj.has("columnNumber") && !obj.isNull("columnNumber") -> obj.optInt("columnNumber")
                        obj.has("column") && !obj.isNull("column") -> obj.optInt("column")
                        obj.has("col") && !obj.isNull("col") -> obj.optInt("col")
                        else -> null
                    },
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    rawJson = jsonStr
                )
            }.getOrElse {
                CanvasErrorTelemetry(
                    type = "raw_error",
                    message = jsonStr,
                    rawJson = jsonStr
                )
            }
        }
    }
}

object GenerativeUiRendererDefaults {
    const val GLOBAL_ERROR_HANDLER_SCRIPT = """
<script>
window.onerror = function(msg, url, line, col, error) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
    }
};
window.addEventListener('unhandledrejection', function(event) {
    if (window.HansonOS && window.HansonOS.reportError) {
        window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
    }
});
</script>
"""

    const val ACHROMATIC_DEFENSIVE_CSS = """
<style id="achromatic-guard">
  html, body {
    -webkit-filter: grayscale(100%) !important;
    filter: grayscale(100%) !important;
  }
</style>
"""

    fun instrumentHtml(rawHtml: String): String {
        var html = rawHtml
        if (!html.contains("filter: grayscale(100%)") && !html.contains("filter:grayscale(100%)")) {
            html = when {
                html.contains("<head>", ignoreCase = true) -> html.replaceFirst(Regex("(?i)<head>"), "<head>\n$ACHROMATIC_DEFENSIVE_CSS\n")
                html.contains("<html>", ignoreCase = true) -> html.replaceFirst(Regex("(?i)<html>"), "<html>\n<head>\n$ACHROMATIC_DEFENSIVE_CSS\n</head>\n")
                else -> "$ACHROMATIC_DEFENSIVE_CSS\n$html"
            }
        }
        if (!html.contains("window.onerror") || !html.contains("reportError")) {
            html = when {
                html.contains("<head>", ignoreCase = true) -> html.replaceFirst(Regex("(?i)<head>"), "<head>\n$GLOBAL_ERROR_HANDLER_SCRIPT\n")
                html.contains("<body>", ignoreCase = true) -> html.replaceFirst(Regex("(?i)<body>"), "<body>\n$GLOBAL_ERROR_HANDLER_SCRIPT\n")
                else -> "$GLOBAL_ERROR_HANDLER_SCRIPT\n$html"
            }
        }
        return html
    }
}

data class GenerativeUiSpec(
    val id: String = "gen_ui_${System.currentTimeMillis()}",
    val title: String,
    val subtitle: String,
    val htmlContent: String,
    val heightDp: Int = 220
)

/**
 * Holds the currently active Generative UI micro-app rendered inside the Ahaboard Achromatic Board.
 * Both the AI (`render_generative_ui` tool), the Laptop CLI Subscription Bridge (`:18889`), and user prompts
 * can dynamically push bespoke HTML5/CSS/Canvas/JS mini-apps wired to `window.HansonOS`.
 */
object GenerativeUiStateHolder {

    private val _activeSpec = MutableStateFlow<GenerativeUiSpec?>(defaultSuperAppCockpitSpec())
    val activeSpec: StateFlow<GenerativeUiSpec?> = _activeSpec.asStateFlow()

    private val _lastError = MutableStateFlow<CanvasErrorTelemetry?>(null)
    val lastError: StateFlow<CanvasErrorTelemetry?> = _lastError.asStateFlow()

    private val _errorHistory = MutableStateFlow<List<CanvasErrorTelemetry>>(emptyList())
    val errorHistory: StateFlow<List<CanvasErrorTelemetry>> = _errorHistory.asStateFlow()

    var onErrorReported: ((CanvasErrorTelemetry) -> Unit)? = null
    private val errorListeners = CopyOnWriteArrayList<(CanvasErrorTelemetry) -> Unit>()

    fun addErrorListener(listener: (CanvasErrorTelemetry) -> Unit) {
        errorListeners.add(listener)
    }

    fun removeErrorListener(listener: (CanvasErrorTelemetry) -> Unit) {
        errorListeners.remove(listener)
    }

    fun reportError(errorJson: String) {
        val telemetry = CanvasErrorTelemetry.fromJson(errorJson)
        reportError(telemetry)
    }

    fun reportError(telemetry: CanvasErrorTelemetry) {
        _lastError.value = telemetry
        _errorHistory.value = _errorHistory.value + telemetry
        onErrorReported?.invoke(telemetry)
        errorListeners.forEach { runCatching { it.invoke(telemetry) } }
    }

    fun clearError() {
        _lastError.value = null
    }

    fun clearErrorHistory() {
        _errorHistory.value = emptyList()
        _lastError.value = null
    }

    fun hasError(): Boolean = _lastError.value != null

    fun publishSpec(spec: GenerativeUiSpec) {
        clearError()
        _activeSpec.value = spec
    }

    fun dismiss() {
        _activeSpec.value = null
    }

    fun restoreDefaultCockpit() {
        clearError()
        _activeSpec.value = defaultSuperAppCockpitSpec()
    }

    fun defaultSuperAppCockpitSpec(): GenerativeUiSpec {
        return GenerativeUiSpec(
            id = "gen_ui_achromatic_board",
            title = "원터치 마이크로 보드",
            subtitle = "window.HansonOS 직결 · 무채색 원터치 액션",
            heightDp = 210,
            htmlContent = """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  :root {
    --surface: #FFFFFF;
    --subtle: #F4F4F5;
    --line: #E4E4E7;
    --ink: #09090B;
    --muted: #71717A;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --surface: #121214;
      --subtle: #18181B;
      --line: #27272A;
      --ink: #FAFAFA;
      --muted: #A1A1AA;
    }
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Pretendard", sans-serif; }
  html, body {
    filter: grayscale(100%) !important;
    -webkit-filter: grayscale(100%) !important;
  }
  body {
    background: transparent;
    color: var(--ink);
    padding: 4px;
    user-select: none;
  }
  .grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .tile {
    background: var(--surface);
    border: 1px solid var(--line);
    border-radius: 14px;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    transition: transform 0.1s ease, box-shadow 0.12s ease, border-color 0.12s ease;
  }
  .tile:active {
    transform: translateY(1px);
    border-color: var(--ink);
    box-shadow: 0 2px 0 0 var(--ink);
  }
  .badge {
    display: inline-block;
    font-size: 10px;
    font-family: monospace;
    color: var(--ink);
    background: var(--subtle);
    border: 1px solid var(--line);
    padding: 2px 6px;
    border-radius: 999px;
    width: fit-content;
    margin-bottom: 5px;
  }
  .title {
    font-size: 12.5px;
    font-weight: 700;
    margin-bottom: 2px;
  }
  .desc {
    font-size: 10.5px;
    color: var(--muted);
  }
  .status-bar {
    margin-top: 8px;
    padding: 7px 10px;
    border-radius: 10px;
    background: var(--subtle);
    border: 1px solid var(--line);
    font-size: 11px;
    font-family: monospace;
    color: var(--ink);
  }
</style>
<script>
  window.onerror = function(msg, url, line, col, error) {
      if (window.HansonOS && window.HansonOS.reportError) {
          window.HansonOS.reportError(JSON.stringify({ type: 'runtime_error', message: String(msg), line: line, column: col }));
      }
  };
  window.addEventListener('unhandledrejection', function(event) {
      if (window.HansonOS && window.HansonOS.reportError) {
          window.HansonOS.reportError(JSON.stringify({ type: 'unhandled_rejection', reason: String(event.reason) }));
      }
  });
</script>
</head>
<body>
  <div class="grid">
    <div class="tile" onclick="triggerDeepLink('kakaotalk://digitalcard', '카카오톡 디지털카드 직행')">
      <span class="badge">TELEPORT</span>
      <div class="title">카톡 디지털카드</div>
      <div class="desc">0.1초 딥링크 텔레포트</div>
    </div>
    <div class="tile" onclick="triggerDeepLink('banktoss://', '토스 메인 직행')">
      <span class="badge">TELEPORT</span>
      <div class="title">토스 금융 직행</div>
      <div class="desc">중간 탐색 생략 호출</div>
    </div>
    <div class="tile" onclick="runCmd('최근 알림 확인하고 한 줄로 브리핑해줘', '백그라운드 알림 요약')">
      <span class="badge">REMOTE-INPUT</span>
      <div class="title">알림 원터치 요약</div>
      <div class="desc">화면 전환 없이 즉시 브리핑</div>
    </div>
    <div class="tile" onclick="runCmd('권한 및 환경 원터치 자동 구성해줘', '원터치 환경 자가 치유')">
      <span class="badge">SELF-HEAL</span>
      <div class="title">원터치 환경 치유</div>
      <div class="desc">권한·접근성 자동 활성화</div>
    </div>
  </div>
  <div class="status-bar" id="statusBox">&gt;_ window.HansonOS 준비 완료 · 원터치 타일을 누르세요</div>
  <script>
    function setStatus(msg) {
      document.getElementById('statusBox').innerText = '>_ ' + msg;
    }
    function triggerDeepLink(uri, label) {
      setStatus(label + ' (' + uri + ')');
      if (window.HansonOS) window.HansonOS.openDeepLink(uri);
    }
    function runCmd(cmd, label) {
      setStatus(label + ' 실행 중...');
      if (window.HansonOS) window.HansonOS.runCommand(cmd);
    }
  </script>
</body>
</html>
            """.trimIndent()
        )
    }
}

class HansonJsBridge(
    private val context: Context,
    private val onRunCommand: (String) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    companion object {
        private val ALLOWED_DEEP_LINK_SCHEMES = setOf(
            "https",
            "http",
            "kakaotalk",
            "youtube",
            "nmap",
            "kakaomap",
            "baemin",
            "supertoss",
            "coupang",
            "instagram",
            "market",
            "geo"
        )

        fun isAllowedDeepLinkUri(uriString: String): Boolean {
            val trimmed = uriString.trim()
            if (trimmed.isEmpty() || trimmed.length > 2048) return false
            if (trimmed.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx <= 0) return false
            val scheme = trimmed.substring(0, colonIdx).lowercase()
            return scheme in ALLOWED_DEEP_LINK_SCHEMES
        }
    }

    @JavascriptInterface
    fun runCommand(prompt: String) {
        if (prompt.isNotBlank()) {
            onRunCommand(prompt)
        }
    }

    @JavascriptInterface
    fun openDeepLink(uriString: String) {
        if (!isAllowedDeepLinkUri(uriString)) {
            val errObj = JSONObject().apply {
                put("type", "security_blocked_deeplink")
                put("message", "Blocked disallowed deep-link scheme")
                put("timestamp", System.currentTimeMillis())
            }
            reportError(errObj.toString())
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString.trim())).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            onRunCommand("딥링크 실행: ${uriString.trim()}")
        }
    }

    @JavascriptInterface
    fun speak(text: String) {
        LiveVoiceSessionManager.speakConversationalFiller(text)
    }

    @JavascriptInterface
    fun reportError(errorJson: String) {
        if (errorJson.isNotBlank()) {
            GenerativeUiStateHolder.reportError(errorJson)
            onError?.invoke(errorJson)
        }
    }
}

/**
 * Custom WebChromeClient that intercepts console errors and forwards them to HansonJsBridge.
 */
open class HansonWebChromeClient(
    private val bridge: HansonJsBridge
) : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            val errObj = JSONObject().apply {
                put("type", "console_error")
                put("message", consoleMessage.message().orEmpty())
                put("source", consoleMessage.sourceId().orEmpty())
                put("lineNumber", consoleMessage.lineNumber())
                put("timestamp", System.currentTimeMillis())
            }
            bridge.reportError(errObj.toString())
        }
        return super.onConsoleMessage(consoleMessage)
    }
}

/**
 * Custom WebViewClient that locks the privileged `window.HansonOS` WebView strictly
 * to its local synthetic origin (`https://hanson.local/`) and forwards load errors.
 */
open class HansonWebViewClient(
    private val bridge: HansonJsBridge
) : WebViewClient() {
    companion object {
        fun isAllowedNavigationUrl(rawUrl: String?): Boolean {
            val clean = rawUrl?.trim().orEmpty()
            if (clean.isEmpty()) return false
            if (clean.equals("about:blank", ignoreCase = true)) return true
            return clean.startsWith("https://hanson.local/", ignoreCase = true) ||
                clean.equals("https://hanson.local", ignoreCase = true)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val target = request?.url?.toString().orEmpty()
        if (isAllowedNavigationUrl(target)) return false
        val errObj = JSONObject().apply {
            put("type", "security_blocked_navigation")
            put("message", "Blocked external navigation inside privileged HansonOS WebView")
            put("source", target.take(120))
            put("timestamp", System.currentTimeMillis())
        }
        bridge.reportError(errObj.toString())
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (isAllowedNavigationUrl(url)) return false
        val errObj = JSONObject().apply {
            put("type", "security_blocked_navigation")
            put("message", "Blocked external navigation inside privileged HansonOS WebView")
            put("source", url.orEmpty().take(120))
            put("timestamp", System.currentTimeMillis())
        }
        bridge.reportError(errObj.toString())
        return true
    }
    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        val errObj = JSONObject().apply {
            put("type", "webview_load_error")
            put("message", error?.description?.toString() ?: "Unknown WebView Error")
            put("source", request?.url?.toString().orEmpty())
            put("errorCode", error?.errorCode ?: -1)
            put("timestamp", System.currentTimeMillis())
        }
        bridge.reportError(errObj.toString())
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        @Suppress("DEPRECATION")
        super.onReceivedError(view, errorCode, description, failingUrl)
        val errObj = JSONObject().apply {
            put("type", "webview_load_error")
            put("message", description ?: "Unknown WebView Error")
            put("source", failingUrl.orEmpty())
            put("errorCode", errorCode)
            put("timestamp", System.currentTimeMillis())
        }
        bridge.reportError(errObj.toString())
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GenerativeUiSurfaceCard(
    onRunCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: ((String) -> Unit)? = null
) {
    val spec by GenerativeUiStateHolder.activeSpec.collectAsState()
    val lastError by GenerativeUiStateHolder.lastError.collectAsState()
    val currentSpec = spec ?: return

    val finalHtml = remember(currentSpec.htmlContent) {
        GenerativeUiRendererDefaults.instrumentHtml(currentSpec.htmlContent)
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 14.dp,
        borderColor = ObsidianColors.GlassBorder,
        containerColor = ObsidianColors.Surface,
        tactileBottomLip = false
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentSpec.title,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "BOARD",
                        color = ObsidianColors.TextPrimary,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(ObsidianColors.SurfaceHigh)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = currentSpec.subtitle,
                    color = ObsidianColors.TextSecondary,
                    fontSize = 11.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { GenerativeUiStateHolder.restoreDefaultCockpit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "복원",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, CircleShape)
                        .clickable { GenerativeUiStateHolder.dismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentSpec.heightDp.dp)
                .clip(RoundedCornerShape(12.dp)),
            factory = { ctx ->
                val bridge = HansonJsBridge(ctx.applicationContext, onRunCommand, onError)
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    settings.allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    settings.allowUniversalAccessFromFileURLs = false
                    webChromeClient = HansonWebChromeClient(bridge)
                    webViewClient = HansonWebViewClient(bridge)
                    addJavascriptInterface(bridge, "HansonOS")

                    // Pure achromatic defense via hardware layer ColorMatrix filter
                    val achromaticPaint = Paint().apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    }
                    setLayerType(View.LAYER_TYPE_HARDWARE, achromaticPaint)

                    val key = "${currentSpec.id}_${finalHtml.hashCode()}"
                    tag = key
                    loadDataWithBaseURL(
                        "https://hanson.local/",
                        finalHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            },
            update = { webView ->
                val key = "${currentSpec.id}_${finalHtml.hashCode()}"
                if (webView.tag != key) {
                    webView.tag = key
                    webView.loadDataWithBaseURL(
                        "https://hanson.local/",
                        finalHtml,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        )

        if (lastError != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠ [CANVAS ERROR] ${lastError?.type}: ${lastError?.message?.take(36)}",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
                Text(
                    text = "CLEAR",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { GenerativeUiStateHolder.clearError() }
                )
            }
        }
    }
}

/**
 * Ahaboard Lifestyle Card Data Model (F06, R2, M3).
 * Structured card hierarchy: hero media stage (16:9 or 1:1), editorial title, warm companion briefing, quick action chips.
 */
data class AhaboardLifestyleCard(
    val heroMediaUrl: String = "",
    val editorialTitle: String = "",
    val companionBriefing: String = "",
    val actionChips: List<String> = emptyList(),
    val mediaBlock: MultimodalMediaBlock? = null
)

@Composable
fun AhaboardLifestyleCardView(
    card: AhaboardLifestyleCard,
    modifier: Modifier = Modifier,
    onChipClick: ((String) -> Unit)? = null,
    onExpandClick: (() -> Unit)? = null,
    onRunCommand: (String) -> Unit = {}
) {
    SquircleCard(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 26.dp,
        borderColor = ObsidianColors.GlassBorder,
        containerColor = ObsidianColors.Surface,
        contentPadding = PaddingValues(20.dp)
    ) {
        // 1. Hero media stage (16:9 or 1:1) with 20dp squircle clipping
        val hasMedia = card.heroMediaUrl.isNotBlank() || card.mediaBlock != null
        if (hasMedia) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
            ) {
                if (card.mediaBlock != null) {
                    AhaboardMultimodalMediaView(
                        block = card.mediaBlock,
                        onExpandClick = onExpandClick,
                        onRunCommand = onRunCommand
                    )
                } else {
                    val localBmp = remember(card.heroMediaUrl) {
                        if (card.heroMediaUrl.startsWith("/") || card.heroMediaUrl.startsWith("file://")) {
                            runCatching { BitmapFactory.decodeFile(card.heroMediaUrl.removePrefix("file://")) }.getOrNull()
                        } else null
                    }
                    if (localBmp != null) {
                        Image(
                            bitmap = localBmp.asImageBitmap(),
                            contentDescription = card.editorialTitle,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "성수동 감성 매거진 · Vibe Companion",
                                color = ObsidianColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        // 2. Editorial title (wraps naturally up to 2-3 lines, markdown links cleaned)
        if (card.editorialTitle.isNotBlank()) {
            val cleanTitle = card.editorialTitle
                .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[*#_`]"), "")
                .trim()
            Text(
                text = cleanTitle,
                color = ObsidianColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                lineHeight = 22.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        // 3. Warm companion briefing (markdown links and syntax cleanly stripped)
        if (card.companionBriefing.isNotBlank()) {
            val cleanBriefing = card.companionBriefing
                .replace(Regex("\\[([^\\]]+)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[*#_`]"), "")
                .trim()
            Text(
                text = cleanBriefing,
                color = ObsidianColors.TextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.5.sp
            )
        }

        // 4. Quick action chips
        if (card.actionChips.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            if (card.actionChips.size > 4) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    card.actionChips.forEach { chip ->
                        EditorialActionChip(chip = chip, onClick = { onChipClick?.invoke(chip) })
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    card.actionChips.forEach { chip ->
                        EditorialActionChip(chip = chip, onClick = { onChipClick?.invoke(chip) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorialActionChip(
    chip: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = chip,
            color = ObsidianColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Ahaboard.ai-style Inline Multimodal Media Block Renderer.
 * Renders `GLB_3D`, `MP4`, `GIF`, `IMAGE`, and `CODE_CANVAS` at native 120Hz inside chat cards
 * and inside the full-detail interactive modal (`ChatDetailModalDialog`).
 */
@Composable
fun AhaboardMultimodalMediaView(
    block: MultimodalMediaBlock,
    heightDpOverride: Int? = null,
    onExpandClick: (() -> Unit)? = null,
    onRunCommand: (String) -> Unit = {}
) {
    val viewportHeight = (heightDpOverride ?: block.recommendedHeightDp).coerceIn(170, 420)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
            .padding(12.dp)
    ) {
        // Ahaboard Media Block Header Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.title,
                    color = ObsidianColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (block.subtitle.isNotBlank()) {
                    Text(
                        text = block.subtitle,
                        color = ObsidianColors.TextMuted,
                        fontSize = 10.5.sp,
                        maxLines = 1
                    )
                }
            }

            if (onExpandClick != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable { onExpandClick() }
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when (block.format) {
                            MediaFormatType.YOUTUBE -> "유튜브 크게 보기 ↗"
                            MediaFormatType.GLB_3D -> "3D 파일 확대 ↗"
                            MediaFormatType.MP4 -> "비디오 크게 보기 ↗"
                            MediaFormatType.GIF -> "GIF 크게 보기 ↗"
                            MediaFormatType.IMAGE -> "이미지 크게 보기 ↗"
                            MediaFormatType.CODE_CANVAS -> "코드·UI 상세 ↗"
                        },
                        color = ObsidianColors.TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Native 120Hz File-Backed & Stream-Backed Interactive Media Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewportHeight.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ObsidianColors.Surface)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(20.dp))
        ) {
            when (block.format) {
                MediaFormatType.YOUTUBE -> InAppYouTubePlayerViewport(block = block)
                MediaFormatType.GLB_3D -> NativeGlb3DViewport(block = block)
                MediaFormatType.MP4 -> NativeMp4CinemaViewport(block = block)
                MediaFormatType.GIF -> NativeGifLoopViewport(block = block)
                MediaFormatType.IMAGE -> NativeImageArtViewport(block = block)
                MediaFormatType.CODE_CANVAS -> NativeCodeCanvasViewport(
                    block = block,
                    onRunCommand = onRunCommand
                )
            }
        }

        if (block.interactiveHintKo.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = block.interactiveHintKo,
                color = ObsidianColors.TextMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private data class Vec3D(val x: Float, val y: Float, val z: Float)
private data class Face3D(val indices: List<Int>, val shadeBoost: Float = 0f)

private fun buildProceduralGlbMesh(meshType: Int): Pair<List<Vec3D>, List<Face3D>> {
    val verts = mutableListOf<Vec3D>()
    val faces = mutableListOf<Face3D>()

    fun addBox(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, boost: Float = 0f) {
        val base = verts.size
        val dx = sx / 2f
        val dy = sy / 2f
        val dz = sz / 2f
        verts.add(Vec3D(cx - dx, cy - dy, cz - dz))
        verts.add(Vec3D(cx + dx, cy - dy, cz - dz))
        verts.add(Vec3D(cx + dx, cy + dy, cz - dz))
        verts.add(Vec3D(cx - dx, cy + dy, cz - dz))
        verts.add(Vec3D(cx - dx, cy - dy, cz + dz))
        verts.add(Vec3D(cx + dx, cy - dy, cz + dz))
        verts.add(Vec3D(cx + dx, cy + dy, cz + dz))
        verts.add(Vec3D(cx - dx, cy + dy, cz + dz))

        val boxFaces = listOf(
            listOf(0, 1, 2, 3),
            listOf(5, 4, 7, 6),
            listOf(4, 0, 3, 7),
            listOf(1, 5, 6, 2),
            listOf(4, 5, 1, 0),
            listOf(3, 2, 6, 7)
        )
        boxFaces.forEach { f ->
            faces.add(Face3D(f.map { base + it }, boost))
        }
    }

    when (meshType) {
        0 -> {
            // MECH ROBOT CORE (.glb procedural rig)
            addBox(0f, -0.82f, 0f, 0.72f, 0.54f, 0.56f, 0.12f) // Head
            addBox(0f, -0.83f, 0.30f, 0.52f, 0.18f, 0.08f, 0.28f) // Visor
            addBox(-0.42f, -0.92f, 0f, 0.10f, 0.38f, 0.10f, 0.18f) // L Antenna
            addBox(0.42f, -0.92f, 0f, 0.10f, 0.38f, 0.10f, 0.18f) // R Antenna
            addBox(0f, -0.04f, 0f, 1.04f, 0.88f, 0.64f, 0.04f) // Torso
            addBox(0f, -0.06f, 0.34f, 0.44f, 0.40f, 0.12f, 0.30f) // Arc Core
            addBox(-0.74f, -0.04f, 0f, 0.32f, 0.80f, 0.36f, -0.04f) // L Arm
            addBox(0.74f, -0.04f, 0f, 0.32f, 0.80f, 0.36f, -0.04f) // R Arm
            addBox(-0.29f, 0.80f, 0f, 0.36f, 0.74f, 0.42f, -0.08f) // L Leg
            addBox(0.29f, 0.80f, 0f, 0.36f, 0.74f, 0.42f, -0.08f) // R Leg
        }
        1 -> {
            // 3D TORUS KNOT MESH
            val segU = 32
            val segV = 8
            for (i in 0 until segU) {
                val u = (i.toFloat() / segU) * (PI.toFloat() * 2f)
                val r = 0.68f + 0.24f * cos(3f * u)
                val cx = r * cos(2f * u)
                val cy = r * sin(2f * u)
                val cz = 0.34f * sin(3f * u)
                for (j in 0 until segV) {
                    val v = (j.toFloat() / segV) * (PI.toFloat() * 2f)
                    val tube = 0.17f
                    verts.add(
                        Vec3D(
                            cx + tube * cos(v) * cos(2f * u),
                            cy + tube * cos(v) * sin(2f * u),
                            cz + tube * sin(v)
                        )
                    )
                }
            }
            for (i in 0 until segU) {
                val ni = (i + 1) % segU
                for (j in 0 until segV) {
                    val nj = (j + 1) % segV
                    faces.add(
                        Face3D(
                            listOf(i * segV + j, ni * segV + j, ni * segV + nj, i * segV + nj),
                            if ((i + j) % 2 == 0) 0.12f else -0.05f
                        )
                    )
                }
            }
        }
        2 -> {
            // CYBERNETIC SPATIAL HAND
            addBox(0f, 0.34f, 0f, 0.92f, 0.70f, 0.26f, 0.08f)
            val fx = listOf(-0.34f, -0.11f, 0.11f, 0.34f)
            val fh = listOf(0.58f, 0.72f, 0.66f, 0.48f)
            for (k in 0..3) {
                addBox(fx[k], -0.24f, 0f, 0.17f, fh[k], 0.19f, 0.14f)
                addBox(fx[k], -0.24f - fh[k] * 0.62f, 0.05f, 0.14f, fh[k] * 0.52f, 0.16f, 0.24f)
            }
            addBox(-0.60f, 0.16f, 0.10f, 0.22f, 0.48f, 0.20f, 0.16f)
        }
        else -> {
            // GEODESIC OCTAHEDRAL CRYSTAL + ORBITAL SATELLITES
            verts.add(Vec3D(0f, -1.10f, 0f))
            verts.add(Vec3D(0f, 1.10f, 0f))
            verts.add(Vec3D(-0.82f, 0f, -0.82f))
            verts.add(Vec3D(0.82f, 0f, -0.82f))
            verts.add(Vec3D(0.82f, 0f, 0.82f))
            verts.add(Vec3D(-0.82f, 0f, 0.82f))
            faces.add(Face3D(listOf(0, 2, 3), 0.20f))
            faces.add(Face3D(listOf(0, 3, 4), 0.10f))
            faces.add(Face3D(listOf(0, 4, 5), 0.25f))
            faces.add(Face3D(listOf(0, 5, 2), 0.05f))
            faces.add(Face3D(listOf(1, 3, 2), -0.05f))
            faces.add(Face3D(listOf(1, 4, 3), 0.08f))
            faces.add(Face3D(listOf(1, 5, 4), -0.10f))
            faces.add(Face3D(listOf(1, 2, 5), 0.12f))
            for (k in 0 until 8) {
                val a = (k.toFloat() / 8f) * (PI.toFloat() * 2f)
                addBox(cos(a) * 1.18f, sin(a * 2f) * 0.16f, sin(a) * 1.18f, 0.18f, 0.18f, 0.18f, 0.22f)
            }
        }
    }
    return verts to faces
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppYouTubePlayerViewport(block: MultimodalMediaBlock) {
    val context = LocalContext.current
    val videoId = block.youtubeVideoId.ifBlank {
        AgentMediaFileEngine.extractYouTubeVideoIdFromUrl(block.mediaUrlOrUri) ?: "jfKfPfyJRdk"
    }
    val shouldAutoPlay = remember(block.title, videoId, block.sourceLabel) {
        block.sourceLabel.contains("AUTOPLAY") || block.interactiveHintKo.contains("자동 재생")
    }
    var playInlineWebView by remember(videoId) { mutableStateOf(shouldAutoPlay) }

    val thumbBitmap = remember(block.localFilePath) {
        if (block.localFilePath.isNotBlank()) {
            runCatching { BitmapFactory.decodeFile(block.localFilePath) }.getOrNull()
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF09090B))
    ) {
        if (!playInlineWebView) {
            // High-res real YouTube thumbnail downloaded to local disk + 1-tap In-App Stream starter
            if (thumbBitmap != null) {
                Image(
                    bitmap = thumbBitmap.asImageBitmap(),
                    contentDescription = block.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x66000000))
                )
            }

            // Center Frosted-Glass Play Pill -> Launches In-App WebView YouTube Player right inside this card!
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x9918181B))
                    .border(1.5.dp, Color(0x66FAFAFA), RoundedCornerShape(999.dp))
                    .clickable { playInlineWebView = true }
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "▶",
                        color = Color(0xFFFAFAFA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "영상 재생",
                        color = Color(0xFFFAFAFA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Bottom title & channel info strip (clean lifestyle framing without technical IDs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xDD09090B))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.title,
                        color = Color(0xFFFAFAFA),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = block.subtitle.ifBlank { "YouTube 동영상" },
                        color = Color(0xFFA1A1AA),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x33FAFAFA))
                        .clickable {
                            runCatching {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "유튜브 앱 ↗",
                        color = Color(0xFFFAFAFA),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            // Live In-App Android WebView YouTube Player with automatic embed-restriction fallback to m.youtube.com
            val targetEmbedHtml = remember(videoId) {
                """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0"/>
<meta name="referrer" content="strict-origin-when-cross-origin"/>
<style>
  html, body { margin: 0; padding: 0; width: 100%; height: 100%; background: #09090B; overflow: hidden; }
  #player, iframe { width: 100%; height: 100%; border: 0; display: block; }
</style>
</head>
<body>
  <div id="player"></div>
  <script src="https://www.youtube.com/iframe_api"></script>
  <script>
    var fallbackTriggered = false;
    function fallbackToMobileWatch() {
      if (fallbackTriggered) return;
      fallbackTriggered = true;
      window.location.replace("https://m.youtube.com/watch?v=$videoId");
    }
    function onYouTubeIframeAPIReady() {
      new YT.Player('player', {
        videoId: '$videoId',
        playerVars: { autoplay: 1, playsinline: 1, controls: 1, rel: 0, origin: 'https://www.youtube.com' },
        events: {
          onReady: function(e) { try { e.target.playVideo(); } catch (_) {} },
          onError: function(e) { fallbackToMobileWatch(); }
        }
      });
    }
    setTimeout(function() {
      if (typeof YT === 'undefined' || !YT.Player) { fallbackToMobileWatch(); }
    }, 3500);
  </script>
</body>
</html>
                """.trimIndent()
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 15; SM-S931B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Mobile Safari/537.36"
                        webChromeClient = WebChromeClient()
                        webViewClient = WebViewClient()
                        tag = videoId
                        loadDataWithBaseURL(
                            "https://www.youtube.com",
                            targetEmbedHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                update = { wv ->
                    if (wv.tag != videoId) {
                        wv.tag = videoId
                        wv.loadDataWithBaseURL(
                            "https://www.youtube.com",
                            targetEmbedHtml,
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                }
            )

            // Clean Top-Right Close Button
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0x9918181B))
                    .border(1.dp, Color(0x33FAFAFA), CircleShape)
                    .clickable { playInlineWebView = false },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = Color(0xFFFAFAFA),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun NativeGlb3DViewport(block: MultimodalMediaBlock) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Parse the REAL .glb or .obj binary file from block.localFilePath!
    var loadedMesh by remember(block.localFilePath) {
        mutableStateOf(
            if (block.localFilePath.isNotBlank()) {
                AgentMediaFileEngine.parseGlbOrObjFile(File(block.localFilePath), block.sourceLabel)
            } else null
        )
    }
    var autoRotate by remember { mutableStateOf(true) }
    var isUserInteracting by remember { mutableStateOf(false) }
    var lastInteractionTime by remember { mutableStateOf(0L) }
    var rotX by remember { mutableFloatStateOf(0.26f) }
    var rotY by remember { mutableFloatStateOf(-0.55f) }
    var scale by remember { mutableFloatStateOf(1.0f) }

    // If no initial file was parsed yet, compile one immediately via AgentMediaFileEngine
    LaunchedEffect(block.localFilePath, block.title) {
        if (loadedMesh == null) {
            withContext(Dispatchers.IO) {
                val compiled = AgentMediaFileEngine.resolveOrCompileGlb3DMesh(
                    context = context,
                    prompt = block.title,
                    explicitUrlOrPath = block.localFilePath.ifBlank { block.mediaUrlOrUri }
                )
                loadedMesh = compiled
            }
        }
    }

    val (fallbackVerts, fallbackFaces) = remember { buildProceduralGlbMesh(0) }

    val verts: List<Vec3D> = remember(loadedMesh) {
        val m = loadedMesh
        if (m != null && m.vertices.isNotEmpty()) {
            m.vertices.map { Vec3D(it[0], it[1], it[2]) }
        } else {
            fallbackVerts
        }
    }
    val faces: List<Face3D> = remember(loadedMesh) {
        val m = loadedMesh
        if (m != null && m.triangles.isNotEmpty()) {
            m.triangles.map { t ->
                Face3D(listOf(t[0], t[1], t[2]), (t.getOrElse(3) { 0 }) / 100f)
            }
        } else {
            fallbackFaces
        }
    }

    // Auto-rotation when idle (smoothly resumes after touch release)
    LaunchedEffect(autoRotate) {
        while (autoRotate) {
            withFrameNanos {
                if (!isUserInteracting || System.currentTimeMillis() - lastInteractionTime > 600L) {
                    isUserInteracting = false
                    rotY = (rotY + 0.007f) % (PI.toFloat() * 2f)
                }
            }
        }
    }

    val isDark = ObsidianColors.isDark
    // Warm studio backdrop without CAD perspective floor grid lines
    val stageBg = if (isDark) Color(0xFF141416) else Color(0xFFFAF9F6)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(20.dp))
            .background(stageBg)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    isUserInteracting = true
                    lastInteractionTime = System.currentTimeMillis()
                    rotY += pan.x * 0.009f
                    rotX = (rotX + pan.y * 0.009f).coerceIn(-1.45f, 1.45f)
                    scale = (scale * zoom).coerceIn(0.5f, 3.0f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f - 4f
            val scaleBase = min(w, h) * 0.88f * scale

            val cosY = cos(rotY)
            val sinY = sin(rotY)
            val cosX = cos(rotX)
            val sinX = sin(rotX)

            fun project(v: Vec3D): Triple<Float, Float, Float> {
                val x1 = v.x * cosY - v.z * sinY
                val z1 = v.x * sinY + v.z * cosY
                val y2 = v.y * cosX - z1 * sinX
                val z2 = v.y * sinX + z1 * cosX
                val perspective = scaleBase / (3.3f + z2).coerceAtLeast(0.4f)
                return Triple(cx + x1 * perspective, cy + y2 * perspective, z2)
            }

            // Diffuse studio ground shadow (replaces CAD floor grid)
            val shadowY = cy + scaleBase * 0.40f
            val shadowRadiusX = scaleBase * 0.36f
            val shadowRadiusY = scaleBase * 0.09f
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        if (isDark) Color(0x66000000) else Color(0x28000000),
                        Color.Transparent
                    ),
                    center = Offset(cx, shadowY),
                    radius = shadowRadiusX
                ),
                topLeft = Offset(cx - shadowRadiusX, shadowY - shadowRadiusY),
                size = Size(shadowRadiusX * 2f, shadowRadiusY * 2f)
            )

            val projected = verts.map { project(it) }

            val sortedFaces = faces.mapNotNull { f ->
                if (f.indices.any { it !in projected.indices }) return@mapNotNull null
                val pts = f.indices.map { projected[it] }
                val avgZ = pts.sumOf { it.third.toDouble() }.toFloat() / pts.size
                Triple(pts, avgZ, f.shadeBoost)
            }.sortedByDescending { it.second }

            sortedFaces.forEach { (pts, avgZ, boost) ->
                val path = Path().apply {
                    pts.forEachIndexed { idx, pt ->
                        if (idx == 0) moveTo(pt.first, pt.second)
                        else lineTo(pt.first, pt.second)
                    }
                    close()
                }
                val baseLum = if (isDark) {
                    (0.45f - avgZ * 0.18f + boost).coerceIn(0.16f, 0.93f)
                } else {
                    (0.68f - avgZ * 0.18f + boost).coerceIn(0.22f, 0.96f)
                }
                drawPath(
                    path = path,
                    color = Color(baseLum, baseLum, (baseLum + 0.02f).coerceAtMost(1f)),
                    style = Fill
                )
                drawPath(
                    path = path,
                    color = if (isDark) Color(0xFF09090B) else Color(0xFF27272A),
                    style = Stroke(width = if (faces.size > 300) 0.7f else 1.4f)
                )
            }
        }

        // Clean subtle indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianColors.Surface.copy(alpha = 0.85f))
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text(
                text = "3D 스튜디오 · 드래그하여 회전 · 핀치하여 확대",
                color = ObsidianColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun NativeMp4CinemaViewport(block: MultimodalMediaBlock) {
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var currentSec by remember { mutableFloatStateOf(0f) }
    val durationSec = 12f
    var isMuted by remember { mutableStateOf(false) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    var mediaPlayerRef by remember { mutableStateOf<android.media.MediaPlayer?>(null) }

    val hasRealVideoFile = remember(block.localFilePath) {
        val file = File(block.localFilePath)
        file.exists() && file.isFile && (block.localFilePath.endsWith(".mp4", ignoreCase = true) || block.localFilePath.endsWith(".m4v", ignoreCase = true))
    }

    LaunchedEffect(isMuted) {
        val vol = if (isMuted) 0f else 1f
        mediaPlayerRef?.setVolume(vol, vol)
    }

    LaunchedEffect(playing, speed) {
        var lastNanos = 0L
        while (playing) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = (now - lastNanos) / 1_000_000_000f
                    currentSec = (currentSec + dt * speed) % durationSec
                }
                lastNanos = now
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF09090B))
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { playing = !playing }
        ) {
            if (hasRealVideoFile) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoPath(block.localFilePath)
                            setOnPreparedListener { mp ->
                                mediaPlayerRef = mp
                                mp.isLooping = true
                                if (isMuted) mp.setVolume(0f, 0f) else mp.setVolume(1f, 1f)
                                start()
                            }
                        }
                    },
                    update = { vv ->
                        val targetMs = (currentSec * 1000).toInt()
                        if (Math.abs(vv.currentPosition - targetMs) > 1200) vv.seekTo(targetMs)
                        videoViewRef = vv
                        if (playing && !vv.isPlaying) vv.start()
                        else if (!playing && vv.isPlaying) vv.pause()
                    }
                )
            } else {
                // Smooth cinematic motion visualizer stage
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val cx = w / 2f
                    val cy = h / 2f

                    for (r in 0..3) {
                        val radius = 38f + r * 28f + sin(currentSec * 3f + r) * 8f
                        val startDeg = (currentSec * (if (r % 2 == 0) 95f else -75f)) % 360f
                        drawArc(
                            color = if (r == 0) Color(0xFFFAFAFA) else Color(0x66FAFAFA),
                            startAngle = startDeg,
                            sweepAngle = 230f,
                            useCenter = false,
                            topLeft = Offset(cx - radius, cy - radius),
                            size = Size(radius * 2f, radius * 2f),
                            style = Stroke(width = if (r == 0) 3.5f else 2f)
                        )
                    }

                    val barCount = 24
                    val barW = (w - 64f) / barCount
                    for (b in 0 until barCount) {
                        val bh = 12f + abs(sin(currentSec * 4.5f + b * 0.45f)) * 34f
                        drawRect(
                            color = Color(0x55FAFAFA),
                            topLeft = Offset(32f + b * barW, h - bh - 8f),
                            size = Size(barW * 0.65f, bh)
                        )
                    }
                }
            }

            // Clean title banner (NO procedural mock badges like codegen_MP4, SCENE 02/03, FRAME #330, etc.)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
                    .align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x8818181B))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = block.title.take(30),
                        color = Color(0xFFFAFAFA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Clean playback and scrub controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181B))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFFAFAFA))
                    .clickable { playing = !playing }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (playing) "❚❚" else "▶",
                    color = Color(0xFF09090B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Scrub Bar
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF27272A))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            currentSec = ratio * durationSec
                            videoViewRef?.seekTo((currentSec * 1000).toInt())
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((currentSec / durationSec).coerceIn(0.02f, 1f))
                        .height(8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xFFFAFAFA))
                )
            }

            val secInt = currentSec.toInt()
            val tenths = ((currentSec - secInt) * 10).toInt()
            Text(
                text = "00:${secInt.toString().padStart(2, '0')}.$tenths / 00:12",
                color = Color(0xFFA1A1AA),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            // Mute toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF27272A))
                    .clickable {
                        isMuted = !isMuted
                        val vol = if (isMuted) 0f else 1f
                        mediaPlayerRef?.setVolume(vol, vol)
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isMuted) "🔇" else "🔊",
                    fontSize = 11.sp
                )
            }

            // Speed toggle
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF27272A))
                    .clickable { speed = if (speed == 1f) 2f else 1f }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${speed.toInt()}x",
                    color = Color(0xFFFAFAFA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun NativeGifLoopViewport(block: MultimodalMediaBlock) {
    var paused by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }

    val hasRealGifFile = remember(block.localFilePath) {
        val file = File(block.localFilePath)
        file.exists() && file.isFile && block.localFilePath.endsWith(".gif", ignoreCase = true)
    }

    LaunchedEffect(paused) {
        while (!paused) {
            withFrameNanos {
                phase = (phase + 0.045f) % (PI.toFloat() * 2f)
            }
        }
    }

    val isDark = ObsidianColors.isDark
    val ink = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val subInk = if (isDark) Color(0x44FAFAFA) else Color(0x3309090B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(26.dp))
            .background(ObsidianColors.Surface)
            .clickable { paused = !paused }
    ) {
        if (hasRealGifFile && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        runCatching {
                            val source = ImageDecoder.createSource(File(block.localFilePath))
                            val drawable = ImageDecoder.decodeDrawable(source)
                            setImageDrawable(drawable)
                            if (drawable is AnimatedImageDrawable) {
                                drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                drawable.start()
                            }
                        }
                    }
                },
                update = { iv ->
                    val d = iv.drawable
                    if (d is AnimatedImageDrawable) {
                        if (paused && d.isRunning) {
                            d.stop()
                        } else if (!paused && !d.isRunning) {
                            d.start()
                        }
                    }
                }
            )
        } else {
            // Smooth 60/120Hz procedural ambient motion loop
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f - 4f
                val count = 12
                for (i in 0 until count) {
                    val angle = (i.toFloat() / count) * (PI.toFloat() * 2f) + phase
                    val dist = 46f + sin(phase * 2f + i) * 24f
                    val px = cx + cos(angle) * dist * 1.45f
                    val py = cy + sin(angle) * dist
                    val rad = (9f + cos(phase + i) * 4f).coerceAtLeast(3f)
                    drawLine(subInk, Offset(cx, cy), Offset(px, py), strokeWidth = 2f)
                    drawCircle(ink, radius = rad, center = Offset(px, py), style = Stroke(width = 2.4f))
                }
                drawCircle(ink, radius = 14f + sin(phase * 2f) * 4f, center = Offset(cx, cy))
            }
        }

        // Clean pause indicator pill if paused
        if (paused) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0x9918181B))
                    .border(1.dp, Color(0x33FAFAFA), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "일시정지됨 (탭하여 재생)",
                    color = Color(0xFFFAFAFA),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun NativeImageArtViewport(block: MultimodalMediaBlock) {
    val loadedBitmap = remember(block.localFilePath) {
        if (block.localFilePath.isNotBlank()) {
            runCatching { BitmapFactory.decodeFile(block.localFilePath) }.getOrNull()
        } else null
    }

    var showLightbox by remember { mutableStateOf(false) }
    var seed by remember { mutableIntStateOf((block.title.hashCode() and 0x7FFF) % 8999 + 1000) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(ObsidianColors.Surface)
            .clickable { showLightbox = true }
    ) {
        if (loadedBitmap != null) {
            Image(
                bitmap = loadedBitmap.asImageBitmap(),
                contentDescription = block.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (block.localFilePath.isNotBlank() && !File(block.localFilePath).exists()) {
            // Missing image placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "이미지를 불러오는 중입니다...",
                    color = ObsidianColors.TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            // High-resolution procedural photographic artwork
            val isDark = ObsidianColors.isDark
            val ink = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
            val midInk = if (isDark) Color(0x44FAFAFA) else Color(0x3309090B)
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                for (layer in 0..4) {
                    val path = Path()
                    val freq = 0.009f + layer * 0.003f + (seed % 13) * 0.0005f
                    val amp = 28f + layer * 12f
                    var x = 0f
                    while (x <= w) {
                        val y = h / 2f + sin(x * freq + layer) * amp * cos(x * 0.004f)
                        if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                        x += 8f
                    }
                    drawPath(
                        path = path,
                        color = if (layer == 0) ink else midInk,
                        style = Stroke(width = if (layer == 0) 2.8f else 1.5f)
                    )
                }
            }
        }

        // Clean caption overlay at bottom (no raw memory addresses or hex IDs)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0x8809090B))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            val caption = block.subtitle.ifBlank { block.title }.replace(Regex("0x[0-9a-fA-F]+"), "").trim()
            Text(
                text = caption,
                color = Color(0xFFFAFAFA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    // Tap-to-Expand Lightbox Dialog
    if (showLightbox) {
        Dialog(
            onDismissRequest = { showLightbox = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            var zoomScale by remember { mutableFloatStateOf(1.0f) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xF009090B))
                    .clickable { showLightbox = false }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, zoom, _ ->
                                zoomScale = (zoomScale * zoom).coerceIn(1.0f, 5.0f)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (loadedBitmap != null) {
                        Image(
                            bitmap = loadedBitmap.asImageBitmap(),
                            contentDescription = block.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .graphicsLayer {
                                    scaleX = zoomScale
                                    scaleY = zoomScale
                                }
                                .clip(RoundedCornerShape(24.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(24.dp))
                                .background(ObsidianColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "이미지를 불러오는 중입니다...",
                                color = ObsidianColors.TextPrimary,
                                fontSize = 14.sp
                            )
                        }
                    }
                }

                // Top Close Button
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(24.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x8827272A))
                        .clickable { showLightbox = false },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = Color(0xFFFAFAFA),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Bottom Editorial Caption
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x9918181B))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    val caption = block.subtitle.ifBlank { block.title }.replace(Regex("0x[0-9a-fA-F]+"), "").trim()
                    Text(
                        text = caption,
                        color = Color(0xFFFAFAFA),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun NativeCodeCanvasViewport(
    block: MultimodalMediaBlock,
    onRunCommand: (String) -> Unit
) {
    // 0 = LIVE WEBVIEW (Code-Assistant .html file), 1 = SOURCE CODE, 2 = ARCHITECTURE NODES
    var viewTab by remember { mutableIntStateOf(0) }
    var selectedNode by remember { mutableIntStateOf(0) }
    val fileBadge = block.localFilePath.substringAfterLast('/').ifBlank { "codegen_ui.html" }
    val htmlSource = remember(block.inlineHtmlContent, block.localFilePath) {
        when {
            block.inlineHtmlContent.isNotBlank() -> block.inlineHtmlContent
            block.localFilePath.isNotBlank() -> runCatching { File(block.localFilePath).readText() }.getOrDefault("")
            else -> ""
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Surface)
    ) {
        // Compact 1-line Header Bar (No multi-line wrapping or clipping!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ObsidianColors.SurfaceHigh)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FILE: ${fileBadge.take(20)} (${(block.fileSizeBytes / 1024L).coerceAtLeast(1L)}KB)",
                color = ObsidianColors.TextPrimary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("LIVE UI", "SOURCE", "NODES").forEachIndexed { idx, label ->
                    val active = viewTab == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface)
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(5.dp))
                            .clickable { viewTab = idx }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 8.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        // Main Interactive Stage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (viewTab) {
                0 -> {
                    if (htmlSource.isNotBlank()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    webChromeClient = WebChromeClient()
                                    webViewClient = WebViewClient()
                                    addJavascriptInterface(
                                        HansonJsBridge(
                                            context = ctx,
                                            onRunCommand = onRunCommand,
                                            onError = { telemetry ->
                                                GenerativeUiStateHolder.reportError(telemetry)
                                            }
                                        ),
                                        "HansonOS"
                                    )
                                    loadDataWithBaseURL(
                                        "https://local.hanson.agent/",
                                        htmlSource,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            },
                            update = { wv ->
                                if (wv.tag != htmlSource.hashCode()) {
                                    wv.tag = htmlSource.hashCode()
                                    wv.loadDataWithBaseURL(
                                        "https://local.hanson.agent/",
                                        htmlSource,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "코드 어시스턴트 HTML 파일 로드 완료 ($fileBadge)",
                                color = ObsidianColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                1 -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF09090B))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "// PATH: ${block.localFilePath}\n" + htmlSource.take(900),
                            color = Color(0xFFE4E4E7),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 12.sp
                        )
                    }
                }

                else -> {
                    val nodes = listOf(
                        Triple("CODE-ASSISTANT FILE", "$fileBadge (${block.fileSizeBytes}B)", "SAVED"),
                        Triple("AUTO MULTI-MODEL", "Argon · 3.8-Flash · OpenAI · S25", "ACTIVE"),
                        Triple("OMNI-NEXUS ENGINE", "GLB · YouTube · MP4 · GIF · HTML", "120Hz"),
                        Triple("3-TIER MEMORY VAULT", "Working · Semantic · Heap GC", "OPTIMAL")
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        nodes.forEachIndexed { idx, (name, sub, pct) ->
                            val isSelected = selectedNode == idx
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                                    .clickable { selectedNode = idx }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "$name · $sub",
                                    color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                                    fontSize = 9.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = pct,
                                    color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
