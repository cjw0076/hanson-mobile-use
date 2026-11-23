package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ToolRiskTier {
    SAFE,      // Read-only or navigation wait/status
    STATEFUL,  // Local GUI tap, scroll, app launch, alarm, note, system setting
    EFFECTFUL  // External communication: phone call, SMS send, messenger reply
}

data class ToolParamSpec(
    val name: String,
    val type: String, // "STRING", "INTEGER", "NUMBER", "BOOLEAN", "ARRAY", "OBJECT"
    val description: String,
    val required: Boolean = false
)

data class ToolSpec(
    val name: String,
    val description: String,
    val riskTier: ToolRiskTier,
    val capability: AgentCapability?,
    val parameters: List<ToolParamSpec>
) {
    fun toGeminiFunctionDeclaration(): JSONObject {
        val properties = JSONObject()
        val requiredArr = JSONArray()

        for (param in parameters) {
            properties.put(
                param.name,
                JSONObject().apply {
                    put("type", param.type)
                    put("description", param.description)
                }
            )
            if (param.required) {
                requiredArr.put(param.name)
            }
        }
        if (!properties.has("spoken_reply")) {
            properties.put(
                "spoken_reply",
                JSONObject().apply {
                    put("type", "STRING")
                    put("description", "Natural 1~2 sentence Korean conversational message spoken aloud to the user while executing this action")
                }
            )
        }

        val schema = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", properties)
            if (requiredArr.length() > 0) {
                put("required", requiredArr)
            }
        }

        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", schema)
        }
    }

    /**
     * Standard JSON Schema (lowercase `type` values, including `spoken_reply`) for OpenAI,
     * Anthropic, MCP, and Gemini Interactions API callers.
     */
    fun toStandardParametersSchema(): JSONObject =
        ToolSpecRegistry.toStandardJsonSchema(
            toGeminiFunctionDeclaration().getJSONObject("parameters")
        )

    /**
     * 2026 Interactions API (`/v1beta/interactions`) flat function tool schema:
     * `{"type": "function", "name": "...", "description": "...", "parameters": {"type": "object", ...}}`
     * Includes `spoken_reply` for full parity with `toGeminiFunctionDeclaration()`.
     */
    fun toGeminiInteractionsToolDeclaration(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", toStandardParametersSchema())
        }
    }
}

/**
 * Single Source of Truth for all 45 Mobile-Use Agent Tools:
 * Combines Google Artemis (`third_party/mobile_use`) GUI operator actions,
 * on-demand screen observation (`observe_screen`), Hanson Screen-Off `RemoteInput` quick-reply tools,
 * EZmap OS Fast-Lane & lifestyle routine tools, local workspace tools, and Pickle SOTA tools.
 */
object ToolSpecRegistry {

    private val PROTO_TO_STANDARD_JSON_SCHEMA_TYPES = mapOf(
        "OBJECT" to "object",
        "STRING" to "string",
        "INTEGER" to "integer",
        "NUMBER" to "number",
        "BOOLEAN" to "boolean",
        "ARRAY" to "array"
    )

    /**
     * GUI-mutation and low-level device inspection tools omitted on pure text-first Q&A turns
     * unless `includeGuiTools = true`. Note: `observe_screen` is intentionally NOT in this set
     * so text-first turns can request live screen inspection on demand.
     */
    val GUI_TOOL_NAMES: Set<String> = setOf(
        "click",
        "long_press",
        "input_text",
        "swipe",
        "drag_and_drop",
        "haptic_trajectory",
        "click_sequence",
        "press_key",
        "wait_for_delay",
        "os_cli_exec",
        "reverse_engineer_app",
        "learn_app_skill",
        "vault_blind_inject"
    )

    /**
     * Local sandbox workspace file/search/exec tools omitted unless `includeWorkspaceTools = true`.
     */
    val WORKSPACE_TOOL_NAMES: Set<String> = setOf(
        "workspace_list",
        "workspace_read",
        "workspace_write",
        "workspace_edit",
        "workspace_search",
        "workspace_exec"
    )

    val allSpecs: List<ToolSpec> = listOf(
        // =====================================================================
        // 1. Google Artemis GUI Operator & On-Demand Observation Tools
        // =====================================================================
        ToolSpec(
            name = "observe_screen",
            description = "Capture a fresh live UI accessibility tree and optional Set-of-Marks screenshot on demand. Use this during text-first turns whenever you need to inspect the current device screen before choosing a GUI action.",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("include_screenshot", "BOOLEAN", "Whether to also capture a Set-of-Marks visual screenshot alongside the UI tree (default true)"),
                ToolParamSpec("reason", "STRING", "Brief explanation of what you want to check on the current screen")
            )
        ),
        ToolSpec(
            name = "click",
            description = "Tap a UI element on the screen. Prefer passing `element_index` from the Visible UI Elements list; if no accessibility node exists, pass normalized coordinates `norm_x` and `norm_y` (`0..1000`, not raw pixels). Supports multi-tap via `times`.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("element_index", "INTEGER", "Index [n] from Visible UI Elements list (preferred when available)"),
                ToolParamSpec("norm_x", "INTEGER", "Normalized X coordinate in 0..1000 range (fallback when element_index is not used)"),
                ToolParamSpec("norm_y", "INTEGER", "Normalized Y coordinate in 0..1000 range (fallback when element_index is not used)"),
                ToolParamSpec("target_description", "STRING", "Brief human-readable description of the target element", required = true),
                ToolParamSpec("times", "INTEGER", "Number of taps (1 for single tap, 2 for double tap)"),
                ToolParamSpec("delay_ms", "INTEGER", "Delay between multi-taps in milliseconds (default 120)")
            )
        ),
        ToolSpec(
            name = "long_press",
            description = "Long-press a UI element to open context menus or selection mode. Prefer `element_index` from Visible UI Elements, or pass normalized coordinates `norm_x` and `norm_y` (`0..1000`, not raw pixels).",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("element_index", "INTEGER", "Index [n] from Visible UI Elements list (preferred when available)"),
                ToolParamSpec("norm_x", "INTEGER", "Normalized X coordinate in 0..1000 range (fallback when element_index is not used)"),
                ToolParamSpec("norm_y", "INTEGER", "Normalized Y coordinate in 0..1000 range (fallback when element_index is not used)"),
                ToolParamSpec("target_description", "STRING", "Element being long-pressed", required = true),
                ToolParamSpec("duration_ms", "INTEGER", "Hold duration in milliseconds (default 700)")
            )
        ),
        ToolSpec(
            name = "input_text",
            description = "Type text into an input field. Prefer passing `element_index` (or normalized `norm_x`/`norm_y` in `0..1000`) to tap and focus the field first, then inject text via Accessibility ACTION_SET_TEXT or clipboard paste. Use `press_enter` (default true) to control IME Enter/Search submission.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("text", "STRING", "Exact text to enter", required = true),
                ToolParamSpec("element_index", "INTEGER", "Index [n] of the Editable/EditText element to tap and focus first (preferred when available)"),
                ToolParamSpec("norm_x", "INTEGER", "Optional normalized X coordinate in 0..1000 range of the input field (fallback when element_index is unavailable)"),
                ToolParamSpec("norm_y", "INTEGER", "Optional normalized Y coordinate in 0..1000 range of the input field (fallback when element_index is unavailable)"),
                ToolParamSpec("clear_existing", "BOOLEAN", "Whether to clear existing text before typing (default false)"),
                ToolParamSpec("press_enter", "BOOLEAN", "Whether to trigger IME Enter/Search submission after typing (default true; set false when filling multi-field forms)")
            )
        ),
        ToolSpec(
            name = "swipe",
            description = "Scroll or swipe the screen. Note direction semantics: direction='up' swipes finger UP (scrolls page DOWN to reveal lower content); direction='down' swipes finger DOWN (scrolls page UP); 'left' and 'right' swipe horizontally. Maintains 40% visual anchor overlap.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("direction", "STRING", "Swipe direction: 'up' (scroll down), 'down' (scroll up), 'left', or 'right'", required = true),
                ToolParamSpec("element_index", "INTEGER", "Optional scrollable container index [n] to anchor the swipe"),
                ToolParamSpec("start_norm_x", "INTEGER", "Optional custom start X (0..1000)"),
                ToolParamSpec("start_norm_y", "INTEGER", "Optional custom start Y (0..1000)"),
                ToolParamSpec("end_norm_x", "INTEGER", "Optional custom end X (0..1000)"),
                ToolParamSpec("end_norm_y", "INTEGER", "Optional custom end Y (0..1000)"),
                ToolParamSpec("duration_ms", "INTEGER", "Swipe duration in milliseconds (default 350)")
            )
        ),
        ToolSpec(
            name = "drag_and_drop",
            description = "True Haptic Drag-and-Drop Trajectory: holds at the source element/coordinate for `hold_ms` (pickup haptic), smoothly drags along a curve to the destination element/coordinate over `duration_ms`, and releases.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("from_element_index", "INTEGER", "Source UI element index [n] to pick up"),
                ToolParamSpec("to_element_index", "INTEGER", "Destination UI element index [n] to drop onto"),
                ToolParamSpec("start_norm_x", "INTEGER", "Or normalized start X (0..1000)"),
                ToolParamSpec("start_norm_y", "INTEGER", "Or normalized start Y (0..1000)"),
                ToolParamSpec("end_norm_x", "INTEGER", "Or normalized end X (0..1000)"),
                ToolParamSpec("end_norm_y", "INTEGER", "Or normalized end Y (0..1000)"),
                ToolParamSpec("hold_ms", "INTEGER", "Long-press hold duration before drag in ms (default 480)"),
                ToolParamSpec("duration_ms", "INTEGER", "Drag movement duration in ms (default 680)"),
                ToolParamSpec("description", "STRING", "Description of what is being dragged and dropped", required = true)
            )
        ),
        ToolSpec(
            name = "haptic_trajectory",
            description = "Execute a multi-waypoint continuous finger trajectory path (e.g. slider control, drawing/pattern, map pan, or curved gesture) defined by semicolon-separated normalized coordinates 'x1,y1;x2,y2;x3,y3'.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("waypoints_csv", "STRING", "Semicolon-separated normalized coordinates (0..1000), e.g. '200,500;500,450;850,500'", required = true),
                ToolParamSpec("duration_ms", "INTEGER", "Total trajectory duration in ms (default 650)"),
                ToolParamSpec("description", "STRING", "Description of the haptic trajectory", required = true)
            )
        ),
        ToolSpec(
            name = "click_sequence",
            description = "Google Artemis Fast-Action Burst: rapidly tap multiple element indices or normalized coordinates in sequence (e.g. keypad digits, transient popup menus, multi-select).",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("indices_csv", "STRING", "Comma-separated element indices e.g. '3,7,12'"),
                ToolParamSpec("coords_csv", "STRING", "Or semicolon-separated normalized coords e.g. '500,300;500,600'"),
                ToolParamSpec("delay_ms", "INTEGER", "Delay between taps in ms (default 180)"),
                ToolParamSpec("description", "STRING", "Description of the burst sequence", required = true)
            )
        ),
        ToolSpec(
            name = "press_key",
            description = "Trigger an Android global hardware/system action: 'BACK', 'HOME', 'RECENTS', 'NOTIFICATIONS', 'QUICK_SETTINGS', 'POWER_DIALOG', 'TOGGLE_SPLIT_SCREEN', 'LOCK_SCREEN', 'TAKE_SCREENSHOT', or 'ENTER'.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("key", "STRING", "One of: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, POWER_DIALOG, TOGGLE_SPLIT_SCREEN, LOCK_SCREEN, TAKE_SCREENSHOT, ENTER", required = true)
            )
        ),
        ToolSpec(
            name = "manage_app",
            description = "Launch an installed app by its Korean/English name or package name, search matching apps, or scan all installed games/apps ('launch', 'search', 'list_games', 'list_all').",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.APP_LAUNCH,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "'launch' to open an app, 'search' to search installed apps, 'list_games' to find all installed games on the device, or 'list_all' to list installed user apps", required = true),
                ToolParamSpec("app_name", "STRING", "App name (e.g. '카카오톡', '유튜브', '설정', 'Chrome'), package name, or search keyword (optional for 'list_games'/'list_all')", required = false)
            )
        ),
        ToolSpec(
            name = "wait_for_delay",
            description = "Pause execution for `time_in_ms` milliseconds to wait for animations, network loading, or video playback.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("time_in_ms", "INTEGER", "Milliseconds to wait (500..10000)", required = true)
            )
        ),
        ToolSpec(
            name = "open_link",
            description = "Open any HTTP/HTTPS URL or custom app deep-link URI (e.g. 'https://...', 'kakaotalk://...', ' nmap://...') directly via Android Intent.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.EXTERNAL_WEB,
            parameters = listOf(
                ToolParamSpec("url", "STRING", "Full URL or deep-link URI to open", required = true)
            )
        ),

        // =====================================================================
        // 2. Hanson Screen-Off Notification & Sub-200ms Quick Reply Tools
        // =====================================================================
        ToolSpec(
            name = "list_notifications",
            description = "List recent intercepted notifications from KakaoTalk, SMS, Telegram, Slack, Discord, etc., including their `sessionId` and whether instant background reply (`canQuickReply`) is available.",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.NOTIFICATION_REPLY,
            parameters = listOf(
                ToolParamSpec("only_replyable", "BOOLEAN", "If true, only return notifications with active RemoteInput quick-reply tokens"),
                ToolParamSpec("limit", "INTEGER", "Maximum number of notifications to return (default 10)")
            )
        ),
        ToolSpec(
            name = "reply_notification",
            description = "Send an instant (<200ms) background reply to a KakaoTalk/SMS/Telegram/Slack notification via Android `RemoteInput` without turning on the screen or opening the app.",
            riskTier = ToolRiskTier.EFFECTFUL,
            capability = AgentCapability.NOTIFICATION_REPLY,
            parameters = listOf(
                ToolParamSpec("target", "STRING", "Notification sessionId, sender name (e.g. '김철수'), room title, or app name (e.g. '카카오톡'). Pass '' to reply to the most recent replyable notification.", required = true),
                ToolParamSpec("message", "STRING", "Reply message text to send", required = true)
            )
        ),

        // =====================================================================
        // 3. EZmap OS Fast-Lane Tools (System, Telephony, SMS, Contacts, Calendar)
        // =====================================================================
        ToolSpec(
            name = "control_device_setting",
            description = "Directly control device OS settings: 'brightness' (0..255), 'volume_media' (0..100), 'volume_ring' (0..100), 'volume_alarm' (0..100), 'dnd' ('on'|'off'), 'flashlight' ('on'|'off'), 'ringer_mode' ('normal'|'vibrate'|'silent'), 'clipboard' (text), 'wifi_panel', or 'bluetooth_panel'.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("setting", "STRING", "One of: brightness, volume_media, volume_ring, volume_alarm, dnd, flashlight, ringer_mode, clipboard, wifi_panel, bluetooth_panel", required = true),
                ToolParamSpec("value", "STRING", "Target value e.g. '180' for brightness, '50' for volume, 'on'/'off' for dnd/flashlight, 'silent'/'vibrate'/'normal' for ringer_mode, or text for clipboard", required = true)
            )
        ),
        ToolSpec(
            name = "make_call",
            description = "Place a phone call to a phone number or contact name. Uses direct ACTION_CALL if CALL_PHONE is granted, or opens dialer.",
            riskTier = ToolRiskTier.EFFECTFUL,
            capability = AgentCapability.PHONE_CALL,
            parameters = listOf(
                ToolParamSpec("recipient", "STRING", "Phone number (e.g. '010-1234-5678') or contact name (e.g. '엄마')", required = true),
                ToolParamSpec("direct_call", "BOOLEAN", "True to place the call immediately via ACTION_CALL; false to open dial pad")
            )
        ),
        ToolSpec(
            name = "send_sms",
            description = "Send an SMS text message to a phone number or contact name via SmsManager.",
            riskTier = ToolRiskTier.EFFECTFUL,
            capability = AgentCapability.SMS_SEND,
            parameters = listOf(
                ToolParamSpec("recipient", "STRING", "Phone number or contact name", required = true),
                ToolParamSpec("message", "STRING", "SMS body text", required = true)
            )
        ),
        ToolSpec(
            name = "search_contacts",
            description = "Search the device Contacts database by name or partial phone number.",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.CONTACTS_ACCESS,
            parameters = listOf(
                ToolParamSpec("query", "STRING", "Contact name or number query (pass '' to list top contacts)", required = true)
            )
        ),
        ToolSpec(
            name = "manage_calendar",
            description = "Read upcoming calendar events or add a new event to the device Calendar.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.CALENDAR_ALARM,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "'list' to view upcoming events, or 'add' to create a new event", required = true),
                ToolParamSpec("title", "STRING", "Event title (required for 'add')"),
                ToolParamSpec("offset_minutes_from_now", "INTEGER", "Minutes from now when the event starts (default 60)"),
                ToolParamSpec("duration_minutes", "INTEGER", "Event duration in minutes (default 60)"),
                ToolParamSpec("description", "STRING", "Optional event notes/description")
            )
        ),
        ToolSpec(
            name = "set_alarm",
            description = "Set a system clock alarm for `hour` (0..23) and `minute` (0..59).",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.CALENDAR_ALARM,
            parameters = listOf(
                ToolParamSpec("hour", "INTEGER", "Hour in 24-hour format (0..23)", required = true),
                ToolParamSpec("minute", "INTEGER", "Minute (0..59)", required = true),
                ToolParamSpec("label", "STRING", "Alarm label/message")
            )
        ),
        ToolSpec(
            name = "set_timer",
            description = "Start a system countdown timer via Android AlarmClock for `seconds` (5..86400) with an optional `label`.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.CALENDAR_ALARM,
            parameters = listOf(
                ToolParamSpec("seconds", "INTEGER", "Countdown duration in seconds (5..86400, default 180)", required = true),
                ToolParamSpec("label", "STRING", "Optional timer label/message")
            )
        ),
        ToolSpec(
            name = "execute_lifestyle_routine",
            description = "Execute a multi-step everyday lifestyle routine: 'sleep' (bedtime volume + 07:30 alarm + sleep sound), 'focus' (volume + 25m pomodoro timer + focus sound), or 'briefing' (calendar + recent notifications + saved notes summary).",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("routine", "STRING", "Routine preset: 'sleep', 'focus', or 'briefing'", required = true),
                ToolParamSpec("raw_prompt", "STRING", "Optional original user request text for routine context")
            )
        ),
        ToolSpec(
            name = "save_note",
            description = "Save a key-value note or extracted screen information into persistent agent memory.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = null,
            parameters = listOf(
                ToolParamSpec("key", "STRING", "Memory key / title", required = true),
                ToolParamSpec("content", "STRING", "Content to remember", required = true)
            )
        ),
        ToolSpec(
            name = "read_note",
            description = "Read saved notes from persistent agent memory.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("key", "STRING", "Specific key to read, or '' to list all saved notes", required = true)
            )
        ),

        // =====================================================================
        // 4. Native Mobile OS CLI (`hanson` UID-2000 / Privileged System Shell)
        // =====================================================================
        ToolSpec(
            name = "os_cli_exec",
            description = "Execute a Hanson Mobile-Use Native OS CLI (`hanson`) command for Developer Options & OS-Level Package/System control: `status`, `top`, `tap`, `swipe`, `text`, `key`, `devopt status|animation_scale|show_touches|pointer_location|layout_bounds|overdraw|stay_awake|dont_keep_activities|background_limit`, `app list|open|stop|clear|enable|disable|standby|compile|whitelist|grant|revoke|appops`, `setting get|put|delete|list system|secure|global`, `wm size|density`, `uimode night yes|no|auto`, `statusbar expand-notifications|expand-settings|collapse`, `dump`, `provider`, `self-heal`. Routes via authenticated UID-2000 OS Daemon (:18890) & WRITE_SECURE_SETTINGS.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("command", "STRING", "Structured Hanson OS CLI command, e.g. 'devopt animation_scale 0.5', 'devopt show_touches 1', 'devopt layout_bounds true', 'app standby com.instagram.android restricted', 'app compile com.kakao.talk speed-profile', 'wm density 420', 'uimode night yes'", required = true)
            )
        ),
        ToolSpec(
            name = "reverse_engineer_app",
            description = "Universal App Reverse-Engineering Protocol (UARP): Inspects any installed app's binary APK (`base.apk`), `AndroidManifest.xml`, `PackageManager`, and `dumpsys package` + live UI tree to extract its UI engine (Flutter/RN/Unity/Compose/Native), custom Deep-Link URI schemes, exported Activities, ContentProviders, and UI anchors, then compiles and saves a permanent reusable App Skill.",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.APP_LAUNCH,
            parameters = listOf(
                ToolParamSpec("app_query", "STRING", "App name (e.g. '카카오톡', '쿠팡', '토스', '현재앱') or package name to reverse-engineer and synthesize into a skill", required = true),
                ToolParamSpec("custom_recipe", "STRING", "Optional learned workflow recipe or tip to attach to the synthesized skill")
            )
        ),
        ToolSpec(
            name = "learn_app_skill",
            description = "Save or append a newly discovered workflow recipe, deep-link shortcut, or UI interaction rule into the persistent UARP App Protocol store for a target app so future runs can execute it in 1 step.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.APP_LAUNCH,
            parameters = listOf(
                ToolParamSpec("app_query", "STRING", "Target app name or package name", required = true),
                ToolParamSpec("recipe", "STRING", "Step-by-step protocol, deep-link URI, or UI anchor rule learned during interaction", required = true)
            )
        ),
        ToolSpec(
            name = "render_generative_ui",
            description = "Dynamically synthesize and render an interactive HTML5/CSS/Canvas/JS Generative UI micro-app directly inside the Hanson Spatial Glass Command Center, wired to `window.HansonOS.runCommand(prompt)`, `window.HansonOS.openDeepLink(uri)`, and `window.HansonOS.speak(text)`.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("title", "STRING", "Title of the bespoke Generative UI widget", required = true),
                ToolParamSpec("subtitle", "STRING", "Short description of what this interactive widget controls"),
                ToolParamSpec("html_content", "STRING", "Self-contained HTML5/CSS/JS string calling window.HansonOS methods", required = true)
            )
        ),
        ToolSpec(
            name = "browser_use",
            description = "Headless & Interactive Mobile Browser-Use Engine: Fetches any URL or web search query in the background without hijacking the foreground screen, extracts clean DOM text and top interactive links, or mounts an interactive DOM-instrumented WebView on the board.",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.EXTERNAL_WEB,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "'fetch_clean_dom' for headless background web extraction, or 'open_interactive_board' to render inside the Command Center board", required = true),
                ToolParamSpec("url", "STRING", "Full URL (e.g. 'https://news.ycombinator.com') or search query", required = true),
                ToolParamSpec("goal", "STRING", "What information or interaction to extract from the web page")
            )
        ),
        ToolSpec(
            name = "cross_device_control",
            description = "Cross-Device Mesh & Peripheral / IoT Controller: Inspect and control Bluetooth/BLE peripherals, switch audio routing (speakerphone/Bluetooth SCO), sync clipboard or RPC tasks to a PC/Laptop CLI bridge (:18889), or send commands to external A2A agents and HTTP IoT / smart-home edge nodes.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "One of: 'bluetooth_status', 'audio_route', 'pc_clipboard_or_rpc', 'iot_robot_command', 'a2a_delegate'", required = true),
                ToolParamSpec("target", "STRING", "Target device name, IP/URL endpoint, or audio route ('speakerphone_on', 'bluetooth')"),
                ToolParamSpec("payload", "STRING", "Data, clipboard text, or JSON command payload to transmit", required = true)
            )
        ),
        ToolSpec(
            name = "draw_with_code",
            description = "Draw with Code (Live SVG / HTML5 Canvas / Interactive Chart / Architecture Diagram / Mini-App): Dynamically draws diagrams, performance charts, or interactive canvas graphics using code and immediately displays them on the screen.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("prompt", "STRING", "What to draw or visualize (e.g. '시스템 아키텍처 다이어그램', '속도 비교 차트', '파동 시뮬레이션')", required = true),
                ToolParamSpec("title", "STRING", "Optional title for the rendered code canvas"),
                ToolParamSpec("code_or_svg", "STRING", "Optional custom SVG or HTML5 Canvas code to render directly")
            )
        ),
        ToolSpec(
            name = "generate_visual_media",
            description = "Generate, resolve, or display visual media inline on the board: AI images/illustrations (`image` via Gemini or procedural PNG fallback), interactive 3D `.glb`/`.obj` models (`glb`), YouTube direct video cards or native search (`youtube`), external media or interactive storyboard/animation previews (`mp4`, `gif`), or code canvases (`code_canvas`).",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("prompt", "STRING", "Description of the visual scene, illustration, 3D model, or media to generate/display", required = true),
                ToolParamSpec("format", "STRING", "Optional media format: 'image' (default), 'glb', 'youtube', 'mp4', 'gif', or 'code_canvas'"),
                ToolParamSpec("media_url", "STRING", "Optional direct HTTPS media URL, YouTube watch/youtu.be URL, or local file path to load"),
                ToolParamSpec("style", "STRING", "Optional visual style for image generation, e.g. 'achromatic_editorial', 'blueprint', 'geometric'")
            )
        ),

        // =====================================================================
        // 5. Task Lifecycle & Human-in-the-Loop Control
        // =====================================================================
        ToolSpec(
            name = "ask_user",
            description = "Pause and ask the user for clarification or confirmation (e.g. before financial payment or ambiguous choice).",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("question", "STRING", "Question to display to the user", required = true)
            )
        ),
        ToolSpec(
            name = "complete_task",
            description = "Conclude the agent run once the user's goal is achieved and provide a final Korean summary.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("summary", "STRING", "Clear final summary of what was accomplished", required = true)
            )
        ),

        // =====================================================================
        // 6. Local Sandbox Workspace & CLI Bridge Tools (Files, Search, Exec)
        // =====================================================================
        ToolSpec(
            name = "workspace_list",
            description = "List files and directories inside the mobile agent sandbox workspace.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("sub_dir", "STRING", "Optional workspace relative subdirectory path (default root '')"),
                ToolParamSpec("max_depth", "INTEGER", "Optional max recursion depth (default 4)")
            )
        ),
        ToolSpec(
            name = "workspace_read",
            description = "Read a text file inside the mobile agent sandbox workspace with optional line range.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("path", "STRING", "Relative file path inside the workspace", required = true),
                ToolParamSpec("start_line", "INTEGER", "Optional 1-based inclusive start line (default 1)"),
                ToolParamSpec("end_line", "INTEGER", "Optional 1-based inclusive end line (default 200)")
            )
        ),
        ToolSpec(
            name = "workspace_write",
            description = "Create or overwrite a file inside the mobile agent sandbox workspace and capture a diff checkpoint.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = null,
            parameters = listOf(
                ToolParamSpec("path", "STRING", "Relative file path inside the workspace to write", required = true),
                ToolParamSpec("content", "STRING", "Full UTF-8 file contents to write", required = true)
            )
        ),
        ToolSpec(
            name = "workspace_edit",
            description = "Patch an existing file inside the mobile agent sandbox workspace by replacing an exact unique text block.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = null,
            parameters = listOf(
                ToolParamSpec("path", "STRING", "Relative file path inside the workspace to patch", required = true),
                ToolParamSpec("old_text", "STRING", "Exact unique substring to replace", required = true),
                ToolParamSpec("new_text", "STRING", "Replacement text substring", required = true)
            )
        ),
        ToolSpec(
            name = "workspace_search",
            description = "Search text or code snippets across files inside the mobile agent sandbox workspace.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("query", "STRING", "Search query string", required = true),
                ToolParamSpec("path_prefix", "STRING", "Optional relative subdirectory prefix to limit search")
            )
        ),
        ToolSpec(
            name = "workspace_exec",
            description = "Run a shell-free sandboxed read-only/diagnostic command inside the mobile workspace. Allowed commands ONLY: ls, cat, wc, grep, tree, pwd, diff, echo, status (shell metacharacters are rejected).",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("command", "STRING", "Allowlisted workspace command line using only: ls, cat, wc, grep, tree, pwd, diff, echo, status", required = true)
            )
        ),

        // =====================================================================
        // 7. Pickle & SOTA Mobile Agent v5.0 Absorption Tools (45-tool parity)
        // =====================================================================
        ToolSpec(
            name = "vault_blind_inject",
            description = "Inject an encrypted Blind Credential Vault secret by handle directly into the focused or indexed UI field without ever exposing plaintext credentials to the LLM. Prefer `element_index` or normalized `norm_x`/`norm_y` (`0..1000`).",
            riskTier = ToolRiskTier.EFFECTFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("handle", "STRING", "Registered vault handle name (e.g. 'naver_login', 'corp_email')", required = true),
                ToolParamSpec("element_index", "INTEGER", "Target 0-based editable node index on screen (preferred when available)"),
                ToolParamSpec("norm_x", "INTEGER", "Normalized X coordinate in 0..1000 range fallback"),
                ToolParamSpec("norm_y", "INTEGER", "Normalized Y coordinate in 0..1000 range fallback"),
                ToolParamSpec("clear_existing", "BOOLEAN", "Clear existing field text before blind injection (default true)"),
                ToolParamSpec("press_enter", "BOOLEAN", "Submit with ENTER after injection (default false)")
            )
        ),
        ToolSpec(
            name = "vault_list_handles",
            description = "List registered Blind Credential Vault handle names and categories without ever revealing raw secret values.",
            riskTier = ToolRiskTier.SAFE,
            capability = null,
            parameters = listOf(
                ToolParamSpec("category", "STRING", "Optional category filter matching VaultSecretCategory: LOGIN, PAYMENT, IDENTITY, ADDRESS, API_TOKEN")
            )
        ),
        ToolSpec(
            name = "watcher_manage",
            description = "Create, list, toggle, evaluate, or remove proactive background screen/ticket/status watchers (Poke & Pickle trigger pattern). Specific non-wildcard keywords are required.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "One of: list, add, toggle, remove, evaluate", required = true),
                ToolParamSpec("rule_id", "STRING", "Watcher rule ID (for toggle/remove)"),
                ToolParamSpec("title", "STRING", "Human-readable watcher title (required for add)"),
                ToolParamSpec("match_keywords", "STRING", "Comma-separated specific keywords to watch for on screen (e.g. '잔여좌석, 예매가능', required for add; blanket wildcards like '전체' are rejected)"),
                ToolParamSpec("target_app_or_url", "STRING", "Optional app name, package name, or URL filter (empty matches any app)"),
                ToolParamSpec("action_mode", "STRING", "NOTIFY_ONLY or HOLD_FOR_APPROVAL"),
                ToolParamSpec("interval_sec", "INTEGER", "Polling interval in seconds (10..86400, default 60)"),
                ToolParamSpec("enabled", "BOOLEAN", "New enabled state for toggle (default true)")
            )
        ),
        ToolSpec(
            name = "a2a_negotiate_schedule",
            description = "Compute local calendar intersection candidates between local busy windows and a peer's free intervals, generating an offline/local schedule proposal draft and JSON-RPC 2.0 payload (no remote network send unless dispatched to a configured endpoint).",
            riskTier = ToolRiskTier.SAFE,
            capability = AgentCapability.CALENDAR_ALARM,
            parameters = listOf(
                ToolParamSpec("peer_name", "STRING", "Counterpart participant or agent name (e.g. '김대리', 'Peer Agent')", required = true),
                ToolParamSpec("duration_minutes", "INTEGER", "Required meeting length in minutes (default 60)"),
                ToolParamSpec("preferred_area", "STRING", "Optional location or meeting link preference (e.g. '강남역', 'Google Meet')"),
                ToolParamSpec("my_busy_slots", "STRING", "Comma-separated local busy intervals HH:mm-HH:mm (e.g. '09:00-10:30, 12:00-13:00')"),
                ToolParamSpec("peer_free_slots", "STRING", "Comma-separated peer free intervals HH:mm-HH:mm (e.g. '10:00-12:00, 14:00-17:30')", required = true),
                ToolParamSpec("remote_endpoint_url", "STRING", "Optional configured remote A2A peer HTTP(S) endpoint URL; when omitted, output is labeled as an offline local draft")
            )
        ),
        ToolSpec(
            name = "scratchpad_note",
            description = "Read, write, clear, or summarize cross-app working memory in the Mobile-Agent-v3 Notetaker Scratchpad.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = null,
            parameters = listOf(
                ToolParamSpec("action", "STRING", "One of: list, put, get, remove, clear", required = true),
                ToolParamSpec("key", "STRING", "Scratchpad key name"),
                ToolParamSpec("value", "STRING", "Scratchpad value to store for put"),
                ToolParamSpec("source_app", "STRING", "Source app/package attribution (optional)")
            )
        )
    )

    private val byName: Map<String, ToolSpec> = allSpecs.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]

    /**
     * Deep-copies a Gemini ProtoJSON schema and recursively normalizes `"type"` strings from
     * ProtoJSON uppercase (`OBJECT`, `STRING`, `INTEGER`, `NUMBER`, `BOOLEAN`, `ARRAY`) to
     * standard JSON Schema lowercase (`object`, `string`, `integer`, `number`, `boolean`, `array`).
     */
    fun toStandardJsonSchema(schema: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = schema.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = schema.get(key)
            val converted: Any = when {
                key == "type" && value is String ->
                    PROTO_TO_STANDARD_JSON_SCHEMA_TYPES[value.uppercase(Locale.ROOT)] ?: value.lowercase(Locale.ROOT)
                value is JSONObject -> toStandardJsonSchema(value)
                value is JSONArray -> convertJsonArrayForStandardSchema(value)
                else -> value
            }
            out.put(key, converted)
        }
        return out
    }

    private fun convertJsonArrayForStandardSchema(array: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until array.length()) {
            val elem = array.get(i)
            out.put(
                when (elem) {
                    is JSONObject -> toStandardJsonSchema(elem)
                    is JSONArray -> convertJsonArrayForStandardSchema(elem)
                    else -> elem
                }
            )
        }
        return out
    }

    /**
     * Selects a token-scoped subset of [allSpecs] so pure Q&A / text-first turns avoid sending
     * unnecessary GUI automation or sandbox workspace tool schemas while preserving `observe_screen`.
     */
    fun selectScopedSpecs(
        includeGuiTools: Boolean,
        includeWorkspaceTools: Boolean
    ): List<ToolSpec> {
        if (includeGuiTools && includeWorkspaceTools) return allSpecs
        return allSpecs.filter { spec ->
            val allowedByGui = includeGuiTools || spec.name !in GUI_TOOL_NAMES
            val allowedByWorkspace = includeWorkspaceTools || spec.name !in WORKSPACE_TOOL_NAMES
            allowedByGui && allowedByWorkspace
        }
    }

    private fun buildGeminiToolsJson(specs: List<ToolSpec>): JSONArray {
        val declarations = JSONArray()
        for (spec in specs) {
            declarations.put(spec.toGeminiFunctionDeclaration())
        }
        return JSONArray().put(
            JSONObject().apply {
                put("functionDeclarations", declarations)
            }
        )
    }

    fun toGeminiToolsJson(): JSONArray = buildGeminiToolsJson(allSpecs)

    fun toScopedGeminiToolsJson(
        includeGuiTools: Boolean,
        includeWorkspaceTools: Boolean
    ): JSONArray = buildGeminiToolsJson(selectScopedSpecs(includeGuiTools, includeWorkspaceTools))

    @JvmOverloads
    fun toGeminiInteractionsToolsJson(specs: List<ToolSpec> = allSpecs): JSONArray {
        val tools = JSONArray()
        for (spec in specs) {
            tools.put(spec.toGeminiInteractionsToolDeclaration())
        }
        return tools
    }

    fun toScopedGeminiInteractionsToolsJson(
        includeGuiTools: Boolean,
        includeWorkspaceTools: Boolean
    ): JSONArray = toGeminiInteractionsToolsJson(selectScopedSpecs(includeGuiTools, includeWorkspaceTools))

    /**
     * Standard OpenAI / OpenRouter / Custom Chat Completions `tools` array with lowercase JSON Schema types:
     * `[{"type":"function","function":{"name":...,"description":...,"parameters": lowercaseSchema}}]`
     */
    @JvmOverloads
    fun toOpenAiToolsJson(specs: List<ToolSpec> = allSpecs): JSONArray {
        val tools = JSONArray()
        for (spec in specs) {
            tools.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", spec.name)
                            put("description", spec.description)
                            put("parameters", spec.toStandardParametersSchema())
                        }
                    )
                }
            )
        }
        return tools
    }

    fun toScopedOpenAiToolsJson(
        includeGuiTools: Boolean,
        includeWorkspaceTools: Boolean
    ): JSONArray = toOpenAiToolsJson(selectScopedSpecs(includeGuiTools, includeWorkspaceTools))

    /**
     * Standard Anthropic Messages API `tools` array with lowercase JSON Schema types:
     * `[{"name":...,"description":...,"input_schema": lowercaseSchema}]`
     */
    @JvmOverloads
    fun toAnthropicToolsJson(specs: List<ToolSpec> = allSpecs): JSONArray {
        val tools = JSONArray()
        for (spec in specs) {
            tools.put(
                JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("input_schema", spec.toStandardParametersSchema())
                }
            )
        }
        return tools
    }

    fun toScopedAnthropicToolsJson(
        includeGuiTools: Boolean,
        includeWorkspaceTools: Boolean
    ): JSONArray = toAnthropicToolsJson(selectScopedSpecs(includeGuiTools, includeWorkspaceTools))

    /**
     * Standard Model Context Protocol (MCP) `tools` array with lowercase JSON Schema types:
     * `[{"name":...,"description":...,"inputSchema": lowercaseSchema}]`
     */
    @JvmOverloads
    fun toMcpToolsJson(specs: List<ToolSpec> = allSpecs): JSONArray {
        val tools = JSONArray()
        for (spec in specs) {
            tools.put(
                JSONObject().apply {
                    put("name", spec.name)
                    put("description", spec.description)
                    put("inputSchema", spec.toStandardParametersSchema())
                }
            )
        }
        return tools
    }
}
