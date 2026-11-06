package com.hanson.mobileuse.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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

    fun publishSpec(spec: GenerativeUiSpec) {
        _activeSpec.value = spec
    }

    fun dismiss() {
        _activeSpec.value = null
    }

    fun restoreDefaultCockpit() {
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
    private val onRunCommand: (String) -> Unit
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
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GenerativeUiSurfaceCard(
    onRunCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spec by GenerativeUiStateHolder.activeSpec.collectAsState()
    val currentSpec = spec ?: return

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
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    addJavascriptInterface(
                        HansonJsBridge(ctx.applicationContext, onRunCommand),
                        "HansonOS"
                    )
                    tag = currentSpec.id
                    loadDataWithBaseURL(
                        "https://hanson.local/",
                        currentSpec.htmlContent,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            },
            update = { webView ->
                if (webView.tag != currentSpec.id) {
                    webView.tag = currentSpec.id
                    webView.loadDataWithBaseURL(
                        "https://hanson.local/",
                        currentSpec.htmlContent,
                        "text/html",
                        "utf-8",
                        null
                    )
                }
            }
        )
    }
}
