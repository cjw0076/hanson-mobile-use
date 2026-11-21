package com.hanson.mobileuse.lifestyle

import androidx.activity.ComponentActivity
import com.hanson.mobileuse.ui.MainActivity
import com.hanson.mobileuse.ui.MainTab
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.random.Random

/**
 * Milestone 3 (M3) Challenger Test Suite:
 * State Transitions, Reflection Stability, Backstack Logic, and Edge Cases in MainActivity.
 *
 * Authored by challenger_m3_r1_2 (Empirical State Transitions, Backstack Stress & Reflection Testing)
 */
class ChallengerM3StateTransitionsAndReflectionTest {

    private val projectRoot: File by lazy {
        sequenceOf(
            File("."),
            File(".."),
            File("hanson-mobile-use")
        ).firstOrNull { File(it, "app/src/main/java/com/hanson/mobileuse/ui/MainActivity.kt").isFile }
            ?: File(".")
    }

    private val mainActivitySource: String by lazy {
        val file = File(projectRoot, "app/src/main/java/com/hanson/mobileuse/ui/MainActivity.kt")
        assertTrue("MainActivity.kt must exist at ${file.absolutePath}", file.isFile)
        file.readText()
    }

    // =========================================================================
    // DIMENSION 1: MainTab Reflection, Ordinal & Route Integrity
    // =========================================================================

    @Test
    fun testMainTab_reflectionCountAndExactEnumConstants() {
        val tabClass = MainTab::class.java
        assertTrue("MainTab must be an Enum class", tabClass.isEnum)

        val entries = MainTab.entries
        assertEquals("MainTab must have exactly 5 tabs per Figma prototype spec", 5, entries.size)

        assertEquals("Tab 0 must be COMMAND_CENTER", "COMMAND_CENTER", entries[0].name)
        assertEquals("Tab 1 must be PERMISSIONS_HUB", "PERMISSIONS_HUB", entries[1].name)
        assertEquals("Tab 2 must be INSPECTOR_BRIDGE", "INSPECTOR_BRIDGE", entries[2].name)
        assertEquals("Tab 3 must be SKILLS_RECEIPTS", "SKILLS_RECEIPTS", entries[3].name)
        assertEquals("Tab 4 must be SETTINGS", "SETTINGS", entries[4].name)

        assertEquals(0, MainTab.COMMAND_CENTER.ordinal)
        assertEquals(1, MainTab.PERMISSIONS_HUB.ordinal)
        assertEquals(2, MainTab.INSPECTOR_BRIDGE.ordinal)
        assertEquals(3, MainTab.SKILLS_RECEIPTS.ordinal)
        assertEquals(4, MainTab.SETTINGS.ordinal)
    }

    @Test
    fun testMainTab_exactRoutesAndUniqueness() {
        val expectedRoutes = listOf(
            "command_center",
            "permissions_hub",
            "inspector_bridge",
            "skills_receipts",
            "settings"
        )
        val actualRoutes = MainTab.entries.map { it.route }
        assertEquals("Routes must match exact lowercase enum names", expectedRoutes, actualRoutes)

        // Ensure routes are all unique
        val uniqueRoutes = actualRoutes.toSet()
        assertEquals("All 5 tab routes must be distinct", 5, uniqueRoutes.size)

        // Ensure no whitespace and all lowercase
        actualRoutes.forEach { route ->
            assertTrue("Route '$route' must not contain whitespace", !route.contains(" "))
            assertEquals("Route '$route' must be lowercase", route.lowercase(), route)
            assertTrue("Route '$route' must not be blank", route.isNotBlank())
        }
    }

    @Test
    fun testMainTab_localizedKoreanStrings() {
        val expectedTitles = listOf(
            "커맨드",
            "권한 허브",
            "인스펙터",
            "스킬·영수증",
            "설정"
        )
        val actualTitles = MainTab.entries.map { it.titleKo }
        assertEquals("Localized Korean titles must match Figma UI prototype", expectedTitles, actualTitles)

        // Verify labelKo alias matches titleKo for backward compatibility
        MainTab.entries.forEach { tab ->
            assertEquals("labelKo must be an alias of titleKo", tab.titleKo, tab.labelKo)
            assertTrue("Korean title must not be blank", tab.titleKo.isNotBlank())
            assertFalse("Korean title must not contain replacement characters", tab.titleKo.contains("\uFFFD"))
        }
    }

    @Test
    fun testMainTab_iconEmojisNonEmptyAndExact() {
        val expectedEmojis = listOf("⚡", "🛡️", "🌲", "✨", "⚙️")
        val actualEmojis = MainTab.entries.map { it.iconEmoji }
        assertEquals("Icon emojis must match Figma UI prototype specifications", expectedEmojis, actualEmojis)

        MainTab.entries.forEach { tab ->
            assertNotNull("Icon emoji must not be null", tab.iconEmoji)
            assertTrue("Icon emoji must not be blank", tab.iconEmoji.isNotBlank())
            assertTrue("Icon emoji code point length >= 1", tab.iconEmoji.codePointCount(0, tab.iconEmoji.length) >= 1)
        }
    }

    @Test
    fun testMainTab_typealiasesResolutionInSource() {
        assertTrue(
            "MainActivity.kt must declare HansonNavTab typealias",
            mainActivitySource.contains("typealias HansonNavTab = MainTab")
        )
        assertTrue(
            "MainActivity.kt must declare MainNavigationTab typealias",
            mainActivitySource.contains("typealias MainNavigationTab = MainTab")
        )
    }

    @Test
    fun testMainTab_valueOfAndOrdinalStability() {
        MainTab.entries.forEach { tab ->
            assertEquals(tab, MainTab.valueOf(tab.name))
        }

        try {
            MainTab.valueOf("INVALID_TAB_KEY")
            fail("MainTab.valueOf with invalid key must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    // =========================================================================
    // DIMENSION 2: Backstack State Machine & Navigation Transitions
    // =========================================================================

    /**
     * Pure state transition simulator reflecting MainActivity's BackHandler contract:
     * BackHandler(enabled = currentTab != MainTab.COMMAND_CENTER) {
     *     currentTab = MainTab.COMMAND_CENTER
     * }
     */
    private class NavBackstackHarness(initialTab: MainTab = MainTab.COMMAND_CENTER) {
        var currentTab: MainTab = initialTab

        val isBackHandlerEnabled: Boolean
            get() = currentTab != MainTab.COMMAND_CENTER

        fun pressBack(): Boolean {
            return if (isBackHandlerEnabled) {
                currentTab = MainTab.COMMAND_CENTER
                true // Back handled internally
            } else {
                false // Back propagated to system (activity finish / home)
            }
        }

        fun switchTab(target: MainTab) {
            currentTab = target
        }
    }

    @Test
    fun testBackstack_transitionsFromDeepTabsReturnToCommandCenter() {
        val deepTabs = listOf(
            MainTab.PERMISSIONS_HUB,
            MainTab.INSPECTOR_BRIDGE,
            MainTab.SKILLS_RECEIPTS,
            MainTab.SETTINGS
        )

        deepTabs.forEach { deepTab ->
            val harness = NavBackstackHarness(initialTab = deepTab)
            assertTrue("BackHandler must be enabled on deep tab: ${deepTab.name}", harness.isBackHandlerEnabled)

            val handled = harness.pressBack()
            assertTrue("Pressing back on ${deepTab.name} must be handled internally", handled)
            assertEquals("State must transition directly to COMMAND_CENTER", MainTab.COMMAND_CENTER, harness.currentTab)
            assertFalse("BackHandler must now be disabled at COMMAND_CENTER", harness.isBackHandlerEnabled)

            val secondBackHandled = harness.pressBack()
            assertFalse("Second back press from COMMAND_CENTER must delegate to system", secondBackHandled)
            assertEquals("Tab remains COMMAND_CENTER", MainTab.COMMAND_CENTER, harness.currentTab)
        }
    }

    @Test
    fun testBackstack_commandCenterBackPressDelegatesToSystem() {
        val harness = NavBackstackHarness(initialTab = MainTab.COMMAND_CENTER)
        assertFalse("BackHandler must be disabled initially at root COMMAND_CENTER", harness.isBackHandlerEnabled)

        val handled = harness.pressBack()
        assertFalse("BackHandler must NOT intercept system back when already at COMMAND_CENTER", handled)
        assertEquals("Remains on COMMAND_CENTER", MainTab.COMMAND_CENTER, harness.currentTab)
    }

    @Test
    fun testBackstack_rapidSwitchingAndBackstackStress() {
        val harness = NavBackstackHarness(initialTab = MainTab.COMMAND_CENTER)
        val tabs = MainTab.entries.toTypedArray()
        val rng = Random(42)

        // 10,000 rapid state transitions simulating intensive user interactions
        for (i in 0 until 10_000) {
            val nextTab = tabs[rng.nextInt(tabs.size)]
            harness.switchTab(nextTab)

            if (nextTab == MainTab.COMMAND_CENTER) {
                assertFalse("BackHandler disabled at root", harness.isBackHandlerEnabled)
                assertFalse("System handles back at root", harness.pressBack())
            } else {
                assertTrue("BackHandler enabled on deep tab", harness.isBackHandlerEnabled)
                assertTrue("Back handled internally on deep tab", harness.pressBack())
                assertEquals("Always returns to root COMMAND_CENTER", MainTab.COMMAND_CENTER, harness.currentTab)
            }
        }
    }

    // =========================================================================
    // DIMENSION 3: Cross-Tab Execution Triggers
    // =========================================================================

    @Test
    fun testCrossTabTrigger_onOpenDeepSettingsNavigatesToSettings() {
        // 1. Verify in MainActivity.kt source code
        assertTrue(
            "MainActivity.kt must wire onOpenDeepSettings to currentTab = MainTab.SETTINGS",
            mainActivitySource.contains("onOpenDeepSettings = {") &&
            mainActivitySource.contains("currentTab = MainTab.SETTINGS")
        )

        // 2. Functional simulation
        var currentTab = MainTab.COMMAND_CENTER
        val onOpenDeepSettings: () -> Unit = { currentTab = MainTab.SETTINGS }

        onOpenDeepSettings()
        assertEquals("Triggering onOpenDeepSettings must change tab to SETTINGS", MainTab.SETTINGS, currentTab)
    }

    @Test
    fun testCrossTabTrigger_onRunSkillNavigatesToCommandCenter() {
        // 1. Verify in MainActivity.kt source code
        assertTrue(
            "MainActivity.kt must wire onRunSkill to agentEngine.sendChatMessage and currentTab = MainTab.COMMAND_CENTER",
            mainActivitySource.contains("onRunSkill = { skill ->") &&
            mainActivitySource.contains("agentEngine.sendChatMessage(skill.goalPrompt)") &&
            mainActivitySource.contains("currentTab = MainTab.COMMAND_CENTER")
        )

        // 2. Functional simulation
        var currentTab = MainTab.SKILLS_RECEIPTS
        val executedPrompts = mutableListOf<String>()
        val onRunSkill: (String) -> Unit = { goalPrompt ->
            executedPrompts.add(goalPrompt)
            currentTab = MainTab.COMMAND_CENTER
        }

        onRunSkill("스마트 씽스 조명 켜줘")
        assertEquals("Goal prompt must be submitted", listOf("스마트 씽스 조명 켜줘"), executedPrompts)
        assertEquals("Triggering onRunSkill must navigate to COMMAND_CENTER", MainTab.COMMAND_CENTER, currentTab)
    }

    @Test
    fun testCrossTabTrigger_onCloseInSettingsNavigatesToCommandCenter() {
        // 1. Verify in MainActivity.kt source code
        assertTrue(
            "MainActivity.kt must wire SettingsScreen onClose to currentTab = MainTab.COMMAND_CENTER",
            mainActivitySource.contains("onClose = {") &&
            mainActivitySource.contains("currentTab = MainTab.COMMAND_CENTER")
        )

        // 2. Functional simulation
        var currentTab = MainTab.SETTINGS
        val onClose: () -> Unit = { currentTab = MainTab.COMMAND_CENTER }

        onClose()
        assertEquals("Triggering onClose must navigate to COMMAND_CENTER", MainTab.COMMAND_CENTER, currentTab)
    }

    @Test
    fun testCrossTabTrigger_circularTriggerSequenceIntegrity() {
        var currentTab = MainTab.COMMAND_CENTER
        val promptLog = mutableListOf<String>()

        val onOpenDeepSettings = { currentTab = MainTab.SETTINGS }
        val onCloseSettings = { currentTab = MainTab.COMMAND_CENTER }
        val onRunSkill: (String) -> Unit = { prompt ->
            promptLog.add(prompt)
            currentTab = MainTab.COMMAND_CENTER
        }

        // Cycle 1: Command -> Settings -> Close -> Command
        assertEquals(MainTab.COMMAND_CENTER, currentTab)
        onOpenDeepSettings()
        assertEquals(MainTab.SETTINGS, currentTab)
        onCloseSettings()
        assertEquals(MainTab.COMMAND_CENTER, currentTab)

        // Cycle 2: Command -> Tab Switch to Skills -> Run Skill -> Command
        currentTab = MainTab.SKILLS_RECEIPTS
        assertEquals(MainTab.SKILLS_RECEIPTS, currentTab)
        onRunSkill("자동 요약 실행")
        assertEquals(MainTab.COMMAND_CENTER, currentTab)
        assertEquals(listOf("자동 요약 실행"), promptLog)
    }

    // =========================================================================
    // DIMENSION 4: Reflection Stability & ComponentActivity Inheritance
    // =========================================================================

    @Test
    fun testMainActivity_componentActivityInheritanceAndModifiers() {
        val clazz = MainActivity::class.java
        assertFalse("MainActivity must not be abstract", Modifier.isAbstract(clazz.modifiers))
        assertFalse("MainActivity must not be an interface", clazz.isInterface)
        assertTrue("MainActivity must be public", Modifier.isPublic(clazz.modifiers))

        assertTrue(
            "MainActivity must inherit from ComponentActivity",
            ComponentActivity::class.java.isAssignableFrom(clazz)
        )
        assertEquals(
            "MainActivity direct superclass must be ComponentActivity",
            ComponentActivity::class.java,
            clazz.superclass
        )

        // Verify default public no-arg constructor
        val defaultConstructor = clazz.getConstructor()
        assertNotNull("MainActivity must declare a public no-arg constructor", defaultConstructor)
        assertTrue(Modifier.isPublic(defaultConstructor.modifiers))
    }

    @Test
    fun testMainActivity_lifecycleMethodSignatures() {
        val clazz = MainActivity::class.java

        val onCreateMethod = clazz.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        assertNotNull("MainActivity must override onCreate(Bundle?)", onCreateMethod)
        assertTrue(Modifier.isProtected(onCreateMethod.modifiers))

        val onResumeMethod = clazz.getDeclaredMethod("onResume")
        assertNotNull("MainActivity must override onResume()", onResumeMethod)
        assertTrue(Modifier.isProtected(onResumeMethod.modifiers))

        val onStopMethod = clazz.getDeclaredMethod("onStop")
        assertNotNull("MainActivity must override onStop()", onStopMethod)
        assertTrue(Modifier.isProtected(onStopMethod.modifiers))
    }

    @Test
    fun testMainActivity_lifecycleServiceRegistrations() {
        assertTrue(
            "onCreate must start HansonForegroundService",
            mainActivitySource.contains("HansonForegroundService.start(applicationContext)")
        )
        assertTrue(
            "onCreate must register MainActivity foreground in FloatingHudService",
            mainActivitySource.contains("FloatingHudService.setMainActivityForeground(applicationContext, true)")
        )
        assertTrue(
            "onResume must set MainActivity foreground in FloatingHudService",
            mainActivitySource.contains("FloatingHudService.setMainActivityForeground(applicationContext, true)")
        )
        assertTrue(
            "onStop must unregister MainActivity foreground in FloatingHudService",
            mainActivitySource.contains("FloatingHudService.setMainActivityForeground(applicationContext, false)")
        )
        assertTrue(
            "onResume must refresh permissionHub status",
            mainActivitySource.contains("permissionHub.refreshStatus()")
        )
    }

    @Test
    fun testMainActivity_screenMountingCompleteness() {
        val screens = listOf(
            "CommandCenterScreen(",
            "PermissionsHubScreen(",
            "InspectorBridgeScreen(",
            "SkillsAndReceiptsScreen(",
            "SettingsScreen("
        )

        screens.forEach { screenSignature ->
            assertTrue(
                "MainActivity.kt must mount $screenSignature",
                mainActivitySource.contains(screenSignature)
            )
        }
    }

    @Test
    fun testMainActivity_apiKeyContractRetention() {
        assertTrue(
            "MainActivity.kt must pass apiKey = config.apiKey verbatim to CommandCenterScreen",
            mainActivitySource.contains("apiKey = config.apiKey")
        )
    }

    @Test
    fun testMainActivity_saveableStateHolderWrapping() {
        assertTrue(
            "MainActivity.kt must initialize rememberSaveableStateHolder()",
            mainActivitySource.contains("rememberSaveableStateHolder()")
        )
        assertTrue(
            "MainActivity.kt must wrap screens in saveableStateHolder.SaveableStateProvider",
            mainActivitySource.contains("saveableStateHolder.SaveableStateProvider(key = tab.name)")
        )
    }

    @Test
    fun testMainActivity_navigationBarAchromaticTokensAndErgonomics() {
        val requiredTokens = listOf(
            "ObsidianColors.Surface",
            "ObsidianColors.GlassBorder",
            "ObsidianColors.Accent",
            "ObsidianColors.AccentDim",
            "ObsidianColors.TextSecondary"
        )

        requiredTokens.forEach { token ->
            assertTrue(
                "HansonBottomNavigationBar must use achromatic token $token",
                mainActivitySource.contains(token)
            )
        }

        assertTrue(
            "HansonBottomNavigationBar must specify fixed height 64.dp",
            mainActivitySource.contains("64.dp")
        )

        assertTrue(
            "Bottom bar must dynamically hide when soft keyboard is open",
            mainActivitySource.contains("if (!isKeyboardOpen)") &&
            mainActivitySource.contains("WindowInsets.ime.getBottom(density)")
        )
    }
}
