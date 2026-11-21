# Hanson Mobile-Use MCP Server (`stdio`)

Zero-dependency (Python 3.9+ standard library only) **Model Context Protocol (MCP)** server that gives **Claude Code**, **OpenAI Codex CLI**, **Gemini CLI**, **Cursor**, and **Antigravity** full Set-of-Marks (`[index]`) visual + Accessibility control over any Android phone — with **$0 extra API cost** on top of your existing coding CLI subscription.

---

## 1. Prerequisites (30 Seconds)

1. Connect your Android phone via USB (or Wireless ADB) with **USB Debugging** enabled.
2. *(Recommended — 10× faster + Set-of-Marks visual badges + screen-off KakaoTalk/SMS reply)*:
   - Install & launch **Hanson Mobile-Use** on your phone and grant **Accessibility** + **Notification Access**.
   - Open the **[🌲 인스펙터 (Inspector)]** tab: either copy your **Session Token** (`ARTEMIS_TOKEN`) or toggle **Local ADB Token-Free Mode** for trusted USB debugging.
   - Forward the loopback port:
     ```bash
     adb forward tcp:18888 tcp:18888
     ```
   *(If the Hanson app is not running yet, `mobile_use_mcp_server.py` automatically falls back to `adb shell` `uiautomator` + `screencap` so every tool still works out of the box.)*

---

## 2. One-Line Registration by Coding Agent

### Claude Code (`claude`)
```bash
claude mcp add android-mobile-use -- python /absolute/path/to/hanson-mobile-use/mcp/mobile_use_mcp_server.py
```

### OpenAI Codex CLI (`codex`)
Add to `~/.codex/config.toml`:
```toml
[mcp_servers.android_mobile_use]
command = "python"
args = ["/absolute/path/to/hanson-mobile-use/mcp/mobile_use_mcp_server.py"]
```

### Gemini CLI (`gemini`) / Cursor / Antigravity (`mcp_config.json`)
```json
{
  "mcpServers": {
    "android-mobile-use": {
      "command": "python",
      "args": ["/absolute/path/to/hanson-mobile-use/mcp/mobile_use_mcp_server.py"],
      "env": {
        "ARTEMIS_URL": "http://127.0.0.1:18888",
        "ARTEMIS_TOKEN": ""
      }
    }
  }
}
```

---

## 3. Exposed MCP Tools (12 Tools)

| Tool | Purpose |
|:---|:---|
| `mobile_get_screen` | Returns indexed UI elements (`[index]`, `text`, `desc`, `norm=[x,y]`, `px=(x,y)`) + Set-of-Marks JPEG image block with `[index]` bounding boxes |
| `mobile_click` | Taps by `element_index` (preferred), `text` label, `norm_x`/`norm_y` (`0..1000`), or `x`/`y` pixels |
| `mobile_long_press` | Long-presses an element or coordinate (`duration_ms`, default `700`) |
| `mobile_input_text` | Focuses optional `element_index`, inserts full Unicode/Korean/English text via `ACTION_SET_TEXT`, and optionally submits `ENTER` |
| `mobile_swipe` | Swipes `up` (scroll down), `down` (scroll up), `left`, `right`, or custom `0..1000` coordinates |
| `mobile_press_key` | Sends `HOME`, `BACK`, `RECENTS`, `ENTER`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `VOLUME_UP`, `VOLUME_DOWN` |
| `mobile_launch_app` | Launches an app by Korean/English name (`"배달의민족"`, `"카카오톡"`, `"YouTube"`) or package name |
| `mobile_list_apps` | Lists installed launchable apps on the device (optional `query` filter) |
| `mobile_open_url` | Opens any `https://` URL or deep link (`intent://`, `kakaotalk://`, `youtube://`) |
| `mobile_get_notifications` | Reads active intercepted notifications and whether each supports `RemoteInput` quick-reply |
| `mobile_reply_notification` | Replies to KakaoTalk / SMS / Slack notifications in the background without waking the screen |
| `mobile_device_setting` | Adjusts `brightness`, `volume_media`, `wifi`, `bluetooth`, `dnd`, `torch`, `ringer_mode` |
