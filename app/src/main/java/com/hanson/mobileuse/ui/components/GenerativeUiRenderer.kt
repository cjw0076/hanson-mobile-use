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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.ui.graphics.Brush
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
    val heightDp: Int = 270
)

/**
 * Holds the currently active Generative UI micro-app rendered inside the Spatial Glass Command Center.
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
            id = "gen_ui_superapp_cockpit",
            title = "Generative UI · 실시간 합성 마이크로 앱",
            subtitle = "window.HansonOS 브리지로 폰 OS · 딥링크 · 카톡을 즉시 제어하는 동적 렌더링 캔버스",
            heightDp = 255,
            htmlContent = """
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
<style>
  :root {
    --bg: rgba(13, 18, 30, 0.92);
    --card: rgba(22, 31, 50, 0.85);
    --border: rgba(56, 189, 248, 0.28);
    --cyan: #38bdf8;
    --indigo: #818cf8;
    --emerald: #34d399;
    --text: #f8fafc;
    --muted: #94a3b8;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body {
    background: transparent;
    color: var(--text);
    padding: 8px;
    user-select: none;
  }
  .grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }
  .tile {
    background: linear-gradient(145deg, rgba(25,35,58,0.9), rgba(14,20,34,0.92));
    border: 1px solid var(--border);
    border-radius: 14px;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    justify-content: space-between;
    transition: transform 0.12s ease, border-color 0.15s ease;
  }
  .tile:active {
    transform: scale(0.97);
    border-color: var(--cyan);
  }
  .badge {
    display: inline-block;
    font-size: 10px;
    font-family: monospace;
    color: var(--cyan);
    background: rgba(56, 189, 248, 0.12);
    padding: 2px 6px;
    border-radius: 999px;
    width: fit-content;
    margin-bottom: 5px;
  }
  .title {
    font-size: 13px;
    font-weight: 700;
    margin-bottom: 2px;
  }
  .desc {
    font-size: 10.5px;
    color: var(--muted);
  }
  .slider-card {
    margin-top: 8px;
    background: rgba(18, 26, 44, 0.9);
    border: 1px solid rgba(129, 140, 248, 0.3);
    border-radius: 14px;
    padding: 10px 12px;
  }
  .slider-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 11.5px;
    margin-bottom: 6px;
  }
  input[type=range] {
    width: 100%;
    accent-color: var(--cyan);
  }
  .status-bar {
    margin-top: 7px;
    font-size: 11px;
    font-family: monospace;
    color: var(--emerald);
  }
</style>
</head>
<body>
  <div class="grid">
    <div class="tile" onclick="triggerDeepLink('kakaotalk://digitalcard', '카카오톡 디지털카드 텔레포트')">
      <span class="badge">kakaotalk://</span>
      <div class="title">카톡 디지털카드 직행</div>
      <div class="desc">중간 메뉴 없이 0.1초 텔레포트</div>
    </div>
    <div class="tile" onclick="triggerDeepLink('banktoss://', '토스 뱅크 메인 텔레포트')">
      <span class="badge">banktoss://</span>
      <div class="title">토스 금융 직행</div>
      <div class="desc">UARP 역분석 딥링크 호출</div>
    </div>
    <div class="tile" onclick="runCmd('hanson top', 'OS 최상단 액티비티 스택 스캔')">
      <span class="badge">UID 2000 CLI</span>
      <div class="title">실시간 Activity 스캔</div>
      <div class="desc">/data/local/tmp/hanson top</div>
    </div>
    <div class="tile" onclick="runCmd('최근 알림 확인하고 브리핑해줘', '수신 알림 0.2초 스캔')">
      <span class="badge">RemoteInput</span>
      <div class="title">백그라운드 알림 브리핑</div>
      <div class="desc">화면 켜지 않고 메신저 요약</div>
    </div>
  </div>

  <div class="slider-card">
    <div class="slider-head">
      <span>⚡ 실시간 OS 애니메이션 배속 & 밝기 믹서</span>
      <span id="valLabel" style="color:#38bdf8;font-family:monospace;">180 / 0.5x</span>
    </div>
    <input type="range" min="40" max="255" value="180" oninput="updateBrightness(this.value)" onchange="applyBrightness(this.value)" />
    <div class="status-bar" id="statusBox">&gt;_ window.HansonOS 브리지 연결됨 · 원탭 제어 대기 중</div>
  </div>

  <script>
    function setStatus(msg) {
      document.getElementById('statusBox').innerText = '>_ ' + msg;
    }
    function triggerDeepLink(uri, label) {
      setStatus(label + ' (' + uri + ') 실행 중...');
      if (window.HansonOS) {
        window.HansonOS.openDeepLink(uri);
      }
    }
    function runCmd(cmd, label) {
      setStatus(label + ' 요청 전송...');
      if (window.HansonOS) {
        window.HansonOS.runCommand(cmd);
      }
    }
    function updateBrightness(val) {
      document.getElementById('valLabel').innerText = '밝기 ' + val + ' / 0.5x';
    }
    function applyBrightness(val) {
      setStatus('시스템 밝기 ' + val + ' 즉시 적용 완료');
      if (window.HansonOS) {
        window.HansonOS.runCommand('밝기 ' + val + '으로 설정해줘');
      }
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        ObsidianColors.SurfaceHigh.copy(alpha = 0.94f),
                        ObsidianColors.Surface.copy(alpha = 0.90f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        ObsidianColors.Accent.copy(alpha = 0.65f),
                        ObsidianColors.GlassBorder
                    )
                ),
                shape = RoundedCornerShape(22.dp)
            )
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.AccentDim),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = ObsidianColors.Accent,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = currentSpec.title,
                            color = ObsidianColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "GEN-UI",
                            color = ObsidianColors.Accent,
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(ObsidianColors.AccentDim)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = currentSpec.subtitle,
                        color = ObsidianColors.TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Surface)
                        .clickable { GenerativeUiStateHolder.restoreDefaultCockpit() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "기본 조종석 복원",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(ObsidianColors.Surface)
                        .clickable { GenerativeUiStateHolder.dismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "닫기",
                        tint = ObsidianColors.TextSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(currentSpec.heightDp.dp)
                .clip(RoundedCornerShape(14.dp)),
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
