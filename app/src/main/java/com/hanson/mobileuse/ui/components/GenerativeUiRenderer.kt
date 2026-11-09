package com.hanson.mobileuse.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.agent.MediaFormatType
import com.hanson.mobileuse.agent.MultimodalMediaBlock
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
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
    @JavascriptInterface
    fun runCommand(prompt: String) {
        if (prompt.isNotBlank()) {
            onRunCommand(prompt)
        }
    }

    @JavascriptInterface
    fun openDeepLink(uriString: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            onRunCommand("딥링크 실행: $uriString")
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
 * Custom WebViewClient that intercepts page load and resource errors and forwards them to HansonJsBridge.
 */
open class HansonWebViewClient(
    private val bridge: HansonJsBridge
) : WebViewClient() {
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
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianColors.SurfaceHigh)
            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        // Ahaboard Media Block Header Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(ObsidianColors.Accent)
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = block.format.badge,
                        color = ObsidianColors.OnAccent,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = block.title,
                        color = ObsidianColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    if (block.subtitle.isNotBlank()) {
                        Text(
                            text = block.subtitle,
                            color = ObsidianColors.TextMuted,
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            if (onExpandClick != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianColors.Surface)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                        .clickable { onExpandClick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = when (block.format) {
                            MediaFormatType.GLB_3D -> "3D 뷰어 확대 ↗"
                            MediaFormatType.MP4 -> "비디오 크게 보기 ↗"
                            MediaFormatType.GIF -> "GIF 크게 보기 ↗"
                            MediaFormatType.IMAGE -> "이미지 크게 보기 ↗"
                            MediaFormatType.CODE_CANVAS -> "전체화면 조작 ↗"
                        },
                        color = ObsidianColors.TextPrimary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Native 120Hz Interactive Media Stage
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewportHeight.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianColors.Surface)
                .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(10.dp))
        ) {
            when (block.format) {
                MediaFormatType.GLB_3D -> NativeGlb3DViewport(title = block.title)
                MediaFormatType.MP4 -> NativeMp4CinemaViewport(title = block.title)
                MediaFormatType.GIF -> NativeGifLoopViewport(title = block.title)
                MediaFormatType.IMAGE -> NativeImageArtViewport(title = block.title)
                MediaFormatType.CODE_CANVAS -> NativeCodeCanvasViewport(
                    title = block.title,
                    onRunCommand = onRunCommand
                )
            }
        }

        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = block.interactiveHintKo,
            color = ObsidianColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
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

@Composable
private fun NativeGlb3DViewport(title: String) {
    var meshIdx by remember { mutableIntStateOf(0) }
    var renderMode by remember { mutableIntStateOf(0) } // 0=SOLID, 1=WIRE, 2=POINTS
    var autoRotate by remember { mutableStateOf(true) }
    var rotX by remember { mutableFloatStateOf(0.28f) }
    var rotY by remember { mutableFloatStateOf(-0.55f) }

    val (verts, faces) = remember(meshIdx) { buildProceduralGlbMesh(meshIdx) }
    val meshLabels = listOf("MECH 로봇", "TORUS 매듭", "CYBER 핸드", "CRYSTAL 옥타")
    val modeLabels = listOf("SOLID", "WIRE", "POINTS")

    LaunchedEffect(autoRotate) {
        while (autoRotate) {
            withFrameNanos {
                rotY += 0.015f
            }
        }
    }

    val isDark = ObsidianColors.isDark
    val stageBg = if (isDark) Color(0xFF09090B) else Color(0xFFF4F4F5)
    val primaryInk = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val gridColor = if (isDark) Color(0x22FFFFFF) else Color(0x1E09090B)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(stageBg)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    rotY += dragAmount.x * 0.011f
                    rotX = (rotX + dragAmount.y * 0.011f).coerceIn(-1.35f, 1.35f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f - 6f
            val scaleBase = min(w, h) * 0.92f

            val cosY = cos(rotY)
            val sinY = sin(rotY)
            val cosX = cos(rotX)
            val sinX = sin(rotX)

            fun project(v: Vec3D): Triple<Float, Float, Float> {
                val x1 = v.x * cosY - v.z * sinY
                val z1 = v.x * sinY + v.z * cosY
                val y2 = v.y * cosX - z1 * sinX
                val z2 = v.y * sinX + z1 * cosX
                val perspective = scaleBase / (3.4f + z2)
                return Triple(cx + x1 * perspective, cy + y2 * perspective, z2)
            }

            // 1. Perspective Floor Grid
            var g = -1.6f
            while (g <= 1.61f) {
                val p1 = project(Vec3D(g, 1.18f, -1.6f))
                val p2 = project(Vec3D(g, 1.18f, 1.6f))
                drawLine(gridColor, Offset(p1.first, p1.second), Offset(p2.first, p2.second), strokeWidth = 1.2f)
                val p3 = project(Vec3D(-1.6f, 1.18f, g))
                val p4 = project(Vec3D(1.6f, 1.18f, g))
                drawLine(gridColor, Offset(p3.first, p3.second), Offset(p4.first, p4.second), strokeWidth = 1.2f)
                g += 0.4f
            }

            val projected = verts.map { project(it) }

            if (renderMode == 2) {
                // POINT CLOUD MODE
                projected.forEach { p ->
                    drawCircle(
                        color = primaryInk,
                        radius = 4.2f,
                        center = Offset(p.first, p.second)
                    )
                }
            } else {
                // Depth-sorted faces
                val sortedFaces = faces.map { f ->
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
                    if (renderMode == 0) {
                        val baseLum = if (isDark) {
                            (0.42f - avgZ * 0.16f + boost).coerceIn(0.16f, 0.92f)
                        } else {
                            (0.68f - avgZ * 0.16f + boost).coerceIn(0.22f, 0.96f)
                        }
                        drawPath(
                            path = path,
                            color = Color(baseLum, baseLum, (baseLum + 0.02f).coerceAtMost(1f)),
                            style = Fill
                        )
                        drawPath(
                            path = path,
                            color = if (isDark) Color(0xFF09090B) else Color(0xFF27272A),
                            style = Stroke(width = 1.6f)
                        )
                    } else {
                        drawPath(
                            path = path,
                            color = primaryInk,
                            style = Stroke(width = 2.0f)
                        )
                    }
                }
            }
        }

        // Top HUD Overlay
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianColors.Surface.copy(alpha = 0.88f))
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "GLB·3D · ${meshLabels[meshIdx]} (${faces.size} FACES)",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(ObsidianColors.Surface.copy(alpha = 0.88f))
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "DRAG 360°",
                    color = ObsidianColors.TextSecondary,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom Interactive Control Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                meshLabels.forEachIndexed { idx, label ->
                    val active = meshIdx == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface.copy(alpha = 0.9f))
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                            .clickable { meshIdx = idx }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label.substringBefore(" "),
                            color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                modeLabels.forEachIndexed { idx, label ->
                    val active = renderMode == idx
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) ObsidianColors.Accent else ObsidianColors.Surface.copy(alpha = 0.9f))
                            .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                            .clickable { renderMode = idx }
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (active) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (autoRotate) ObsidianColors.Accent else ObsidianColors.Surface.copy(alpha = 0.9f))
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(6.dp))
                        .clickable { autoRotate = !autoRotate }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "AUTO",
                        color = if (autoRotate) ObsidianColors.OnAccent else ObsidianColors.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun NativeMp4CinemaViewport(title: String) {
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var currentSec by remember { mutableFloatStateOf(0f) }
    val durationSec = 12f

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

    val actName = when {
        currentSec < 4f -> "ACT 01 · MOBILE-USE SWARM"
        currentSec < 8f -> "ACT 02 · JEV MULTI-MODEL ROUTER"
        else -> "ACT 03 · 2ms HABIT REFLEX"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clickable { playing = !playing }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w / 2f
                val cy = h / 2f

                // Subtle cinema grid
                var gx = 0f
                while (gx < w) {
                    drawLine(Color(0x16FFFFFF), Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                    gx += 42f
                }

                // Animated scanline
                val scanY = ((currentSec * 110f) % max(1f, h))
                drawLine(Color(0x44FFFFFF), Offset(0f, scanY), Offset(w, scanY), strokeWidth = 2f)

                // Concentric kinetic motion rings
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

                // Spectrum telemetry bars at bottom of stage
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

            // Top Cinema Badges
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC18181B))
                        .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "MP4 · 60FPS CINEMA",
                        color = Color(0xFFFAFAFA),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xCC18181B))
                        .border(1.dp, Color(0xFF3F3F46), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = actName,
                        color = Color(0xFFA1A1AA),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Center Motion Typography Overlay
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = actName.substringAfter("· ").trim(),
                    color = Color(0xFFFAFAFA),
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "FRAME #${(currentSec * 60).toInt()} · 1080P MOTION STREAM",
                    color = Color(0xFFA1A1AA),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Interactive Video Transport & Scrubber Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF18181B))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFAFAFA))
                    .clickable { playing = !playing }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (playing) "❚❚" else "▶",
                    color = Color(0xFF09090B),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Timeline Scrubber
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
                        }
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((currentSec / durationSec).coerceIn(0.03f, 1f))
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
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF27272A))
                    .clickable { speed = if (speed == 1f) 2f else 1f }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "${speed.toInt()}x",
                    color = Color(0xFFFAFAFA),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun NativeGifLoopViewport(title: String) {
    var patternIdx by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }

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
    val frameNum = (((phase / (PI.toFloat() * 2f)) * 60).toInt() % 60) + 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Surface)
            .clickable { patternIdx = (patternIdx + 1) % 3 }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f - 8f
            val count = 12
            for (i in 0 until count) {
                val angle = (i.toFloat() / count) * (PI.toFloat() * 2f) + phase * (if (patternIdx == 1) -1f else 1f)
                val dist = 46f + sin(phase * 2f + i) * (if (patternIdx == 2) 34f else 20f)
                val px = cx + cos(angle) * dist * 1.45f
                val py = cy + sin(angle) * dist
                val rad = (9f + cos(phase + i) * 4f).coerceAtLeast(3f)
                drawLine(subInk, Offset(cx, cy), Offset(px, py), strokeWidth = 2f)
                drawCircle(ink, radius = rad, center = Offset(px, py), style = Stroke(width = 2.4f))
            }
            drawCircle(ink, radius = 14f + sin(phase * 2f) * 4f, center = Offset(cx, cy))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "GIF · ∞ LOOP PATTERN #${patternIdx + 1}",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .clickable { paused = !paused }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (paused) "PAUSED · #$frameNum" else "FRAME ${frameNum.toString().padStart(2, '0')}/60",
                    color = ObsidianColors.OnAccent,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun NativeImageArtViewport(title: String) {
    var seed by remember { mutableIntStateOf((title.hashCode() and 0x7FFF) % 8999 + 1000) }
    var animT by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                animT += 0.02f
            }
        }
    }

    val isDark = ObsidianColors.isDark
    val ink = if (isDark) Color(0xFFFAFAFA) else Color(0xFF09090B)
    val midInk = if (isDark) Color(0x44FAFAFA) else Color(0x3309090B)
    val gridInk = if (isDark) Color(0x14FFFFFF) else Color(0x12000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Surface)
            .clickable { seed = (seed * 1664525 + 1013) % 8999 + 1000 }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            var gx = 0f
            while (gx < w) {
                drawLine(gridInk, Offset(gx, 0f), Offset(gx, h), strokeWidth = 1f)
                gx += 36f
            }
            var gy = 0f
            while (gy < h) {
                drawLine(gridInk, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
                gy += 36f
            }

            // Harmonic architectural wave layers
            for (layer in 0..4) {
                val path = Path()
                val freq = 0.009f + layer * 0.003f + (seed % 13) * 0.0005f
                val amp = 28f + layer * 12f
                var x = 0f
                while (x <= w) {
                    val y = h / 2f + sin(x * freq + animT + layer) * amp * cos(x * 0.004f - animT * 0.5f)
                    if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                    x += 8f
                }
                drawPath(
                    path = path,
                    color = if (layer == 0) ink else midInk,
                    style = Stroke(width = if (layer == 0) 2.8f else 1.5f)
                )
            }

            val cx = w / 2f
            val cy = h / 2f
            drawCircle(ink, radius = 48f + sin(animT * 1.5f) * 6f, center = Offset(cx, cy), style = Stroke(width = 2.2f))
            for (i in 0 until 8) {
                val ang = animT * 0.6f + i * (PI.toFloat() / 4f)
                val rx = cx + cos(ang) * 86f
                val ry = cy + sin(ang) * 50f
                drawCircle(ink, radius = 4.5f, center = Offset(rx, ry))
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.SurfaceHigh)
                    .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "GENERATIVE ART · VECTOR CANVAS",
                    color = ObsidianColors.TextPrimary,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ObsidianColors.Accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "SEED #$seed · TAP REMIX",
                    color = ObsidianColors.OnAccent,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }
    }
}

@Composable
private fun NativeCodeCanvasViewport(
    title: String,
    onRunCommand: (String) -> Unit
) {
    var selectedNode by remember { mutableIntStateOf(0) }
    val nodes = listOf(
        Triple("S1 REFLEX & HABIT", "2ms JIT Macro · VLM Probe", "98%"),
        Triple("JEV MULTI-MODEL", "Argon · Opus · S25 Nano", "95%"),
        Triple("OMNI-NEXUS ENGINE", "Zero-GUI · Browser · 3D/MP4", "99%"),
        Triple("3-TIER MEMORY VAULT", "Working · Semantic · Heap GC", "94%")
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianColors.Surface)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LIVE INTERACTIVE ARCHITECTURE · TAP NODE",
                color = ObsidianColors.TextMuted,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "ACTIVE #${selectedNode + 1}",
                color = ObsidianColors.TextPrimary,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            nodes.forEachIndexed { idx, (name, sub, pct) ->
                val isSelected = selectedNode == idx
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) ObsidianColors.Accent else ObsidianColors.SurfaceHigh)
                        .border(1.dp, ObsidianColors.GlassBorder, RoundedCornerShape(8.dp))
                        .clickable { selectedNode = idx }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = sub,
                            color = if (isSelected) ObsidianColors.OnAccent.copy(alpha = 0.8f) else ObsidianColors.TextSecondary,
                            fontSize = 9.5.sp
                        )
                    }
                    Text(
                        text = pct,
                        color = if (isSelected) ObsidianColors.OnAccent else ObsidianColors.TextPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}
