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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.hanson.mobileuse.agent.LiveVoiceSessionManager
import com.hanson.mobileuse.ui.theme.ObsidianColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

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
