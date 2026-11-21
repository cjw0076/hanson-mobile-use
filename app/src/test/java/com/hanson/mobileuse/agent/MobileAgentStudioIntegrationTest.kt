package com.hanson.mobileuse.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.hanson.mobileuse.accessibility.AtomicSnapshot
import com.hanson.mobileuse.accessibility.HansonAccessibilityService
import com.hanson.mobileuse.accessibility.IndexedUiElement
import com.hanson.mobileuse.service.FloatingHudService
import com.hanson.mobileuse.workspace.AgentSessionStore
import com.hanson.mobileuse.workspace.AgentWorkMode
import com.hanson.mobileuse.workspace.ComposerCommandParser
import com.hanson.mobileuse.workspace.MobileWorkspaceEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MobileAgentStudioIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDirRoot: File
    private lateinit var workspaceDir: File
    private lateinit var sessionsFile: File
    private lateinit var dummyContext: Context
    private lateinit var permissionHub: PermissionHubManager
    private lateinit var workspaceEngine: MobileWorkspaceEngine
    private lateinit var sessionStore: AgentSessionStore
    private lateinit var harness: AgentToolExecutionHarness
    private lateinit var engine: MobileUseAgentEngine

    @Before
    fun setUp() {
        assertNull(HansonAccessibilityService.instance)
        filesDirRoot = tempFolder.newFolder("app_files")
        workspaceDir = tempFolder.newFolder("sandbox_workspace")
        sessionsFile = File(filesDirRoot, "sessions.json")

        val prefsMap = ConcurrentHashMap<String, Any>()
        val dummyPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = prefsMap
            override fun getString(key: String?, defValue: String?): String? = (prefsMap[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (prefsMap[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String?, defValue: Int): Int = (prefsMap[key] as? Int) ?: defValue
            override fun getLong(key: String?, defValue: Long): Long = (prefsMap[key] as? Long) ?: defValue
            override fun getFloat(key: String?, defValue: Float): Float = (prefsMap[key] as? Float) ?: defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = (prefsMap[key] as? Boolean) ?: defValue
            override fun contains(key: String?): Boolean = prefsMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
                override fun putString(k: String?, v: String?): SharedPreferences.Editor { if (k != null && v != null) prefsMap[k] = v; return this }
                override fun putStringSet(k: String?, v: MutableSet<String>?): SharedPreferences.Editor { if (k != null && v != null) prefsMap[k] = v; return this }
                override fun putInt(k: String?, v: Int): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putLong(k: String?, v: Long): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putFloat(k: String?, v: Float): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun putBoolean(k: String?, v: Boolean): SharedPreferences.Editor { if (k != null) prefsMap[k] = v; return this }
                override fun remove(k: String?): SharedPreferences.Editor { prefsMap.remove(k); return this }
                override fun clear(): SharedPreferences.Editor { prefsMap.clear(); return this }
                override fun commit(): Boolean = true
                override fun apply() {}
            }
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        dummyContext = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.hanson.mobileuse"
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = filesDirRoot
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = dummyPrefs
        }

        permissionHub = PermissionHubManager(dummyContext)
        workspaceEngine = MobileWorkspaceEngine(workspaceDir).also { it.initWorkspace() }
        sessionStore = AgentSessionStore(sessionsFile)
        harness = AgentToolExecutionHarness(dummyContext, workspaceEngine)
        engine = MobileUseAgentEngine(
            context = dummyContext,
            permissionHub = permissionHub,
            workspaceEngine = workspaceEngine,
            sessionStore = sessionStore
        )
    }

    @After
    fun tearDown() {
        engine.stopRun("test cleanup")
    }

    @Test
    fun allSixWorkspaceTools_executeCleanlyThroughHarness() {
        val writeOutcome = harness.execute(
            toolName = "workspace_write",
            args = JSONObject()
                .put("path", "src/Greeting.kt")
                .put("content", "package demo\nfun greet() = \"hello studio\"\n"),
            latestSnapshot = null
        )
        assertTrue(writeOutcome.succeeded)
        assertTrue(writeOutcome.outputText.contains("src/Greeting.kt"))
        assertNotNull(writeOutcome.receipt)

        val listOutcome = harness.execute(
            toolName = "workspace_list",
            args = JSONObject().put("sub_dir", "src").put("max_depth", 2),
            latestSnapshot = null
        )
        assertTrue(listOutcome.succeeded)
        assertTrue(listOutcome.outputText.contains("Greeting.kt"))

        val readOutcome = harness.execute(
            toolName = "workspace_read",
            args = JSONObject().put("path", "src/Greeting.kt").put("start_line", 1).put("end_line", 10),
            latestSnapshot = null
        )
        assertTrue(readOutcome.succeeded)
        assertTrue(readOutcome.outputText.contains("hello studio"))

        val editOutcome = harness.execute(
            toolName = "workspace_edit",
            args = JSONObject()
                .put("path", "src/Greeting.kt")
                .put("old_text", "\"hello studio\"")
                .put("new_text", "\"hello v4 studio\""),
            latestSnapshot = null
        )
        assertTrue(editOutcome.succeeded)
        assertTrue(editOutcome.outputText.contains("hello v4 studio"))

        val searchOutcome = harness.execute(
            toolName = "workspace_search",
            args = JSONObject().put("query", "hello v4 studio").put("path_prefix", "src"),
            latestSnapshot = null
        )
        assertTrue(searchOutcome.succeeded)
        assertTrue(searchOutcome.outputText.contains("src/Greeting.kt"))

        val execOutcome = harness.execute(
            toolName = "workspace_exec",
            args = JSONObject().put("command", "ls src"),
            latestSnapshot = null
        )
        assertTrue(execOutcome.succeeded)
        assertTrue(execOutcome.outputText.contains("Greeting.kt"))
    }

    @Test
    fun workspaceTools_rejectPathTraversalWithInformativeError() {
        val readTraversal = harness.execute(
            "workspace_read",
            JSONObject().put("path", "../secret.txt"),
            null
        )
        assertFalse(readTraversal.succeeded)

        val writeTraversal = harness.execute(
            "workspace_write",
            JSONObject().put("path", "../../escape.txt").put("content", "bad"),
            null
        )
        assertFalse(writeTraversal.succeeded)

        val editTraversal = harness.execute(
            "workspace_edit",
            JSONObject().put("path", "../escape.txt").put("old_text", "a").put("new_text", "b"),
            null
        )
        assertFalse(editTraversal.succeeded)

        val listTraversal = harness.execute(
            "workspace_list",
            JSONObject().put("sub_dir", "../"),
            null
        )
        assertFalse(listTraversal.succeeded)

        val searchTraversal = harness.execute(
            "workspace_search",
            JSONObject().put("query", "secret").put("path_prefix", "../"),
            null
        )
        assertFalse(searchTraversal.succeeded)
    }

    @Test
    fun agentWorkModeAndSessionStore_persistAndSwitchCleanly() {
        assertEquals(AgentWorkMode.AUTO, AgentWorkMode.fromId("unknown"))
        assertEquals(AgentWorkMode.PLAN, AgentWorkMode.fromId("plan"))
        assertEquals(AgentWorkMode.BUILD, AgentWorkMode.fromId("BUILD"))

        val created = sessionStore.createSession(
            title = "Refactor Auth",
            mode = AgentWorkMode.PLAN,
            isForeground = false
        )
        assertEquals(AgentWorkMode.PLAN, created.mode)
        assertEquals(created.id, sessionStore.state.value.activeSessionId)

        sessionStore.updateArtifacts(
            sessionId = created.id,
            planMarkdown = "# PLAN\n- Step 1",
            walkthroughMarkdown = "# WALKTHROUGH\n- Verified"
        )
        sessionStore.updateActiveMode(AgentWorkMode.BUILD)

        val reloaded = AgentSessionStore(sessionsFile)
        val reloadedThread = reloaded.state.value.sessions.firstOrNull { it.id == created.id }
        assertNotNull(reloadedThread)
        assertEquals(AgentWorkMode.BUILD, reloadedThread?.mode)
        assertTrue(reloadedThread?.planMarkdown?.contains("Step 1") == true)
        assertTrue(reloadedThread?.walkthroughMarkdown?.contains("Verified") == true)
    }

    @Test
    fun composerCommandParser_parsesSlashCommandsAndMentions() {
        val parsedPlan = ComposerCommandParser.parse(
            "/plan Refactor storage @file:README.md @screen",
            AgentWorkMode.AUTO
        )
        assertEquals("/plan", parsedPlan.slashCommand)
        assertEquals(AgentWorkMode.PLAN, parsedPlan.resolvedMode)
        assertEquals(listOf("@file:README.md", "@screen"), parsedPlan.mentions)
        assertEquals("Refactor storage", parsedPlan.cleanPrompt)

        val parsedInit = ComposerCommandParser.parse("/init Android Kotlin Studio", AgentWorkMode.AUTO)
        assertEquals("/init", parsedInit.slashCommand)
        assertEquals(AgentWorkMode.AUTO, parsedInit.resolvedMode)
        assertEquals("Android Kotlin Studio", parsedInit.cleanPrompt)

        val slashHits = ComposerCommandParser.filterSlashSuggestions("/bu")
        assertTrue(slashHits.any { it.command == "/build" })

        val mentionOptions = ComposerCommandParser.buildMentionSuggestions(
            workspaceFiles = workspaceEngine.state.value.files,
            foregroundApp = "com.hanson.mobileuse"
        )
        val mentionHits = ComposerCommandParser.filterMentionSuggestions("check @fi", mentionOptions)
        assertTrue(mentionHits.any { it.token.startsWith("@file:") })
    }

    @Test
    fun mobileUseAgentEngine_handlesInitPlanModeContextInjectionAndHudHooks() {
        engine.setWorkMode(AgentWorkMode.PLAN)
        assertEquals(AgentWorkMode.PLAN, engine.workMode.value)
        assertEquals(AgentWorkMode.PLAN, sessionStore.state.value.activeSession?.mode)

        engine.startRun("/init Studio Workspace Rules")
        assertTrue(workspaceEngine.state.value.hasAgentsMd)
        assertTrue(File(workspaceDir, "AGENTS.md").exists())

        engine.startRun("/plan Audit starter files @file:README.md")
        val planMarkdown = workspaceEngine.state.value.planArtifactMarkdown
        assertTrue(planMarkdown.contains("# PLAN.md"))
        assertTrue(planMarkdown.contains("Audit starter files"))
        assertEquals(planMarkdown, sessionStore.state.value.activeSession?.planMarkdown)

        workspaceEngine.writeFile("docs/spec.md", "line-one\nstudio-spec-token")
        val parsed = ComposerCommandParser.parse(
            "Review @file:docs/spec.md @diff @agents @screen @app @tree",
            AgentWorkMode.BUILD
        )
        val snapshot = AtomicSnapshot(
            screenWidth = 1080,
            screenHeight = 2400,
            activePackage = "com.example.target",
            activeWindowTitle = "Target Screen",
            indexedElements = listOf(
                IndexedUiElement(
                    index = 0,
                    shortClass = "Button",
                    className = "android.widget.Button",
                    packageName = "com.example.target",
                    text = "Confirm",
                    contentDesc = "",
                    resourceId = "btn_confirm",
                    left = 100,
                    top = 200,
                    right = 400,
                    bottom = 300,
                    centerX = 250,
                    centerY = 250,
                    normX = 231,
                    normY = 104,
                    clickable = true,
                    longClickable = false,
                    scrollable = false,
                    editable = false,
                    checkable = false,
                    checked = false,
                    focused = false,
                    enabled = true,
                    windowType = "APPLICATION",
                    windowLayer = 0
                )
            )
        )
        val enriched = engine.buildContextualPrompt(parsed.cleanPrompt, parsed, snapshot)
        assertTrue(enriched.contains("<attached_context>"))
        assertTrue(enriched.contains("studio-spec-token"))
        assertTrue(enriched.contains("[Context @diff]"))
        assertTrue(enriched.contains("[Context @agents]"))
        assertTrue(enriched.contains("com.example.target"))
        assertTrue(enriched.contains("Confirm"))

        // Non-null HUD update call is safe without live overlay service instance
        FloatingHudService.updateApprovalPrompt(true, "Allow workspace check")
        FloatingHudService.updateApprovalPrompt(false)

        engine.approveAndExecutePlan()
        assertEquals(AgentWorkMode.BUILD, engine.workMode.value)
    }

    @Test
    fun toolSpecRegistry_exposes42ToolsWithBridgeMatchingWorkspaceSchemas() {
        val specs = ToolSpecRegistry.allSpecs
        assertEquals(42, specs.size)

        val expectedWorkspaceParams = mapOf(
            "workspace_list" to listOf("sub_dir", "max_depth"),
            "workspace_read" to listOf("path", "start_line", "end_line"),
            "workspace_write" to listOf("path", "content"),
            "workspace_edit" to listOf("path", "old_text", "new_text"),
            "workspace_search" to listOf("query", "path_prefix"),
            "workspace_exec" to listOf("command")
        )

        for ((name, paramNames) in expectedWorkspaceParams) {
            val spec = ToolSpecRegistry.find(name)
            assertNotNull("Missing ToolSpec: $name", spec)
            assertEquals(paramNames, spec?.parameters?.map { it.name })
        }

        val geminiDeclarations = ToolSpecRegistry.toGeminiToolsJson()
            .getJSONObject(0)
            .getJSONArray("functionDeclarations")
        assertEquals(42, geminiDeclarations.length())
        assertEquals(42, ToolSpecRegistry.toGeminiInteractionsToolsJson().length())
    }
}
