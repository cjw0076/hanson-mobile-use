package com.hanson.mobileuse.agent

import org.json.JSONArray
import org.json.JSONObject

enum class ToolRiskTier {
    SAFE,      // Read-only or navigation wait/status
    STATEFUL,  // Local GUI tap, scroll, app launch, alarm, note, system setting
    EFFECTFUL  // External communication: phone call, SMS send, messenger reply
}

data class ToolParamSpec(
    val name: String,
    val type: String, // "STRING", "INTEGER", "BOOLEAN"
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
     * 2026 Interactions API (`/v1beta/interactions`) flat function tool schema:
     * `{"type": "function", "name": "...", "description": "...", "parameters": {"type": "object", ...}}`
     */
    fun toGeminiInteractionsToolDeclaration(): JSONObject {
        val properties = JSONObject()
        val requiredArr = JSONArray()

        for (param in parameters) {
            properties.put(
                param.name,
                JSONObject().apply {
                    put("type", param.type.lowercase())
                    put("description", param.description)
                }
            )
            if (param.required) {
                requiredArr.put(param.name)
            }
        }

        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (requiredArr.length() > 0) {
                put("required", requiredArr)
            }
        }

        return JSONObject().apply {
            put("type", "function")
            put("name", name)
            put("description", description)
            put("parameters", schema)
        }
    }
}

/**
 * Single Source of Truth for all 20 Mobile-Use Agent Tools:
 * Combines Google Artemis (`third_party/mobile_use`) GUI operator actions,
 * Hanson Screen-Off `RemoteInput` quick-reply tools, and EZmap OS Fast-Lane tools.
 */
object ToolSpecRegistry {

    val allSpecs: List<ToolSpec> = listOf(
        // =====================================================================
        // 1. Google Artemis GUI Operator Tools (Accessibility Engine)
        // =====================================================================
        ToolSpec(
            name = "click",
            description = "Tap a UI element on the screen. Prefer passing `element_index` from the Visible UI Elements list, or normalized [0..1000] coordinates (`norm_x`, `norm_y`). Supports multi-tap via `times`.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("element_index", "INTEGER", "Index [n] from Visible UI Elements list (preferred when available)"),
                ToolParamSpec("norm_x", "INTEGER", "Normalized X coordinate (0..1000) if element_index is not used"),
                ToolParamSpec("norm_y", "INTEGER", "Normalized Y coordinate (0..1000) if element_index is not used"),
                ToolParamSpec("target_description", "STRING", "Brief human-readable description of the target element", required = true),
                ToolParamSpec("times", "INTEGER", "Number of taps (1 for single tap, 2 for double tap)"),
                ToolParamSpec("delay_ms", "INTEGER", "Delay between multi-taps in milliseconds (default 120)")
            )
        ),
        ToolSpec(
            name = "long_press",
            description = "Long-press a UI element by `element_index` or normalized coordinates (`norm_x`, `norm_y`) to open context menus or selection mode.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("element_index", "INTEGER", "Index [n] from Visible UI Elements list"),
                ToolParamSpec("norm_x", "INTEGER", "Normalized X coordinate (0..1000)"),
                ToolParamSpec("norm_y", "INTEGER", "Normalized Y coordinate (0..1000)"),
                ToolParamSpec("target_description", "STRING", "Element being long-pressed", required = true),
                ToolParamSpec("duration_ms", "INTEGER", "Hold duration in milliseconds (default 700)")
            )
        ),
        ToolSpec(
            name = "input_text",
            description = "Type text into an input field. If `element_index` or (`norm_x`, `norm_y`) is provided, taps the field first to focus it, then injects text via Accessibility ACTION_SET_TEXT or clipboard paste.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.GUI_AUTOMATION,
            parameters = listOf(
                ToolParamSpec("text", "STRING", "Exact text to enter", required = true),
                ToolParamSpec("element_index", "INTEGER", "Optional index [n] of the Editable/EditText element to tap first"),
                ToolParamSpec("norm_x", "INTEGER", "Optional normalized X (0..1000) of the input field"),
                ToolParamSpec("norm_y", "INTEGER", "Optional normalized Y (0..1000) of the input field"),
                ToolParamSpec("clear_existing", "BOOLEAN", "Whether to clear existing text before typing (default false)"),
                ToolParamSpec("press_enter", "BOOLEAN", "Whether to trigger IME Enter/Search after typing (default true)")
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
            description = "Directly control device OS settings: 'brightness' (0..255), 'volume_media' (0..100), 'volume_ring' (0..100), 'volume_alarm' (0..100), 'dnd' ('on'|'off'), 'clipboard' (text), 'wifi_panel', or 'bluetooth_panel'.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("setting", "STRING", "One of: brightness, volume_media, volume_ring, volume_alarm, dnd, clipboard, wifi_panel, bluetooth_panel", required = true),
                ToolParamSpec("value", "STRING", "Target value e.g. '180' for brightness, '50' for volume, 'on'/'off' for dnd, or text for clipboard", required = true)
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
            description = "Execute a Hanson Mobile-Use Native OS CLI (`hanson`) command or privileged system/shell command (`status`, `top`, `tap`, `swipe`, `text`, `key`, `app stop|grant|appops`, `setting get|put system|secure|global`, `dump`, `provider`, `self-heal`, or raw shell pipeline). Routes via UID-2000 OS Daemon (:18890) -> `/data/local/tmp/hanson` -> on-device Privileged Binder/Shell.",
            riskTier = ToolRiskTier.STATEFUL,
            capability = AgentCapability.SYSTEM_SETTINGS,
            parameters = listOf(
                ToolParamSpec("command", "STRING", "Hanson CLI subcommand or raw shell pipeline, e.g. 'status', 'top', 'setting put global window_animation_scale 0.5', 'app stop com.google.android.youtube', 'dump activity top'", required = true)
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
        )
    )

    private val byName: Map<String, ToolSpec> = allSpecs.associateBy { it.name }

    fun find(name: String): ToolSpec? = byName[name]

    fun toGeminiToolsJson(): JSONArray {
        val declarations = JSONArray()
        for (spec in allSpecs) {
            declarations.put(spec.toGeminiFunctionDeclaration())
        }
        return JSONArray().put(
            JSONObject().apply {
                put("functionDeclarations", declarations)
            }
        )
    }

    fun toGeminiInteractionsToolsJson(): JSONArray {
        val tools = JSONArray()
        for (spec in allSpecs) {
            tools.put(spec.toGeminiInteractionsToolDeclaration())
        }
        return tools
    }
}
