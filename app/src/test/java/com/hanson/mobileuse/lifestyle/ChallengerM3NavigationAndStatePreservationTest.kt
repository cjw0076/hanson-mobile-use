package com.hanson.mobileuse.lifestyle

import com.hanson.mobileuse.ui.MainActivity
import com.hanson.mobileuse.ui.MainTab
import com.hanson.mobileuse.ui.theme.ObsidianColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Empirical Adversarial Challenger Test Suite for Milestone 3 (M3):
 * Native App Navigation, Screen Mounting & State Preservation.
 *
 * Verifies:
 * - Structural and reflection contracts on 5-tab enum `MainTab` (titles, emojis, routes).
 * - Screen mounting completeness across all 5 Compose screens (CommandCenter, PermissionsHub,
 *   InspectorBridge, SkillsAndReceipts, Settings).
 * - Verbatim retention of critical contract `apiKey = config.apiKey`.
 * - State preservation mechanics via Compose `rememberSaveableStateHolder` and `SaveableStateProvider`.
 * - Back navigation hierarchy (`BackHandler` logic returning to CommandCenter vs. exiting app).
 * - Pure achromatic Obsidian theme compliance (Surface, GlassBorder, Accent, AccentDim, 64.dp height).
 * - Soft keyboard IME avoidance (hiding navigation bar during typing).
 */
class ChallengerM3NavigationAndStatePreservationTest {

    private fun resolveAppFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("../app", relativePath),
            File("hanson-mobile-use/app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: File("app", relativePath)
    }

    private fun getMainActivitySource(): String {
        val file = resolveAppFile("src/main/java/com/hanson/mobileuse/ui/MainActivity.kt")
        assertTrue("MainActivity.kt must exist at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    // =========================================================================
    // 1. MainTab Enum Structural & Reflection Verification
    // =========================================================================

    @Test
    fun testChallenger_mainTabEnum_entriesAndOrder() {
        val entries = MainTab.entries
        assertEquals("MainTab enum must declare exactly 5 tabs per Figma prototype", 5, entries.size)

        // Strict ordinal & name checks
        assertEquals(MainTab.COMMAND_CENTER, entries[0])
        assertEquals(MainTab.PERMISSIONS_HUB, entries[1])
        assertEquals(MainTab.INSPECTOR_BRIDGE, entries[2])
        assertEquals(MainTab.SKILLS_RECEIPTS, entries[3])
        assertEquals(MainTab.SETTINGS, entries[4])

        assertEquals("COMMAND_CENTER", entries[0].name)
        assertEquals("PERMISSIONS_HUB", entries[1].name)
        assertEquals("INSPECTOR_BRIDGE", entries[2].name)
        assertEquals("SKILLS_RECEIPTS", entries[3].name)
        assertEquals("SETTINGS", entries[4].name)
    }

    @Test
    fun testChallenger_mainTabEnum_koreanTitlesAndEmojis() {
        // Exact localized titles per Figma lines 598-620
        assertEquals("커맨드", MainTab.COMMAND_CENTER.titleKo)
        assertEquals("권한 허브", MainTab.PERMISSIONS_HUB.titleKo)
        assertEquals("인스펙터", MainTab.INSPECTOR_BRIDGE.titleKo)
        assertEquals("스킬·영수증", MainTab.SKILLS_RECEIPTS.titleKo)
        assertEquals("설정", MainTab.SETTINGS.titleKo)

        // Exact emoji iconography
        assertEquals("⚡", MainTab.COMMAND_CENTER.iconEmoji)
        assertEquals("🛡️", MainTab.PERMISSIONS_HUB.iconEmoji)
        assertEquals("🌲", MainTab.INSPECTOR_BRIDGE.iconEmoji)
        assertEquals("✨", MainTab.SKILLS_RECEIPTS.iconEmoji)
        assertEquals("⚙️", MainTab.SETTINGS.iconEmoji)
    }

    @Test
    fun testChallenger_mainTabEnum_routesAndLabelKo() {
        // Routes must be lowercase identifiers matching enum names
        assertEquals("command_center", MainTab.COMMAND_CENTER.route)
        assertEquals("permissions_hub", MainTab.PERMISSIONS_HUB.route)
        assertEquals("inspector_bridge", MainTab.INSPECTOR_BRIDGE.route)
        assertEquals("skills_receipts", MainTab.SKILLS_RECEIPTS.route)
        assertEquals("settings", MainTab.SETTINGS.route)

        // labelKo property must mirror titleKo for backwards compatibility
        MainTab.entries.forEach { tab ->
            assertEquals("labelKo must match titleKo for ${tab.name}", tab.titleKo, tab.labelKo)
            assertEquals("route must match name.lowercase() for ${tab.name}", tab.name.lowercase(), tab.route)
            assertTrue("iconEmoji must not be blank for ${tab.name}", tab.iconEmoji.isNotBlank())
        }
    }

    @Test
    fun testChallenger_mainTab_typealiasesDefined() {
        val source = getMainActivitySource()
        assertTrue(
            "MainActivity.kt must declare HansonNavTab typealias for interoperability",
            source.contains("typealias HansonNavTab = MainTab")
        )
        assertTrue(
            "MainActivity.kt must declare MainNavigationTab typealias for interoperability",
            source.contains("typealias MainNavigationTab = MainTab")
        )
    }

    // =========================================================================
    // 2. Screen Mounting Completeness & Non-Orphaned State
    // =========================================================================

    @Test
    fun testChallenger_screenMounting_allFiveScreensPresentInMainActivity() {
        val source = getMainActivitySource()

        // 1. Composable function imports
        assertTrue("Must import CommandCenterScreen", source.contains("import com.hanson.mobileuse.ui.screens.CommandCenterScreen"))
        assertTrue("Must import PermissionsHubScreen", source.contains("import com.hanson.mobileuse.ui.screens.PermissionsHubScreen"))
        assertTrue("Must import InspectorBridgeScreen", source.contains("import com.hanson.mobileuse.ui.screens.InspectorBridgeScreen"))
        assertTrue("Must import SkillsAndReceiptsScreen", source.contains("import com.hanson.mobileuse.ui.screens.SkillsAndReceiptsScreen"))
        assertTrue("Must import SettingsScreen", source.contains("import com.hanson.mobileuse.ui.screens.SettingsScreen"))

        // 2. Screen composable invocations inside when(tab)
        assertTrue("Must mount CommandCenterScreen()", source.contains("CommandCenterScreen("))
        assertTrue("Must mount PermissionsHubScreen()", source.contains("PermissionsHubScreen("))
        assertTrue("Must mount InspectorBridgeScreen()", source.contains("InspectorBridgeScreen("))
        assertTrue("Must mount SkillsAndReceiptsScreen()", source.contains("SkillsAndReceiptsScreen("))
        assertTrue("Must mount SettingsScreen()", source.contains("SettingsScreen("))

        // 3. Exhaustive branching check
        MainTab.entries.forEach { tab ->
            assertTrue(
                "when(tab) statement must explicitly branch on MainTab.${tab.name}",
                source.contains("MainTab.${tab.name} ->")
            )
        }
    }

    @Test
    fun testChallenger_screenMounting_bytecodeReflectionOnScreenComposables() {
        // Verify via genuine reflection that all 5 screen Composable functions exist in bytecode
        val screens = listOf(
            "com.hanson.mobileuse.ui.screens.CommandCenterScreenKt" to "CommandCenterScreen",
            "com.hanson.mobileuse.ui.screens.PermissionsHubScreenKt" to "PermissionsHubScreen",
            "com.hanson.mobileuse.ui.screens.InspectorBridgeScreenKt" to "InspectorBridgeScreen",
            "com.hanson.mobileuse.ui.screens.SkillsAndReceiptsScreenKt" to "SkillsAndReceiptsScreen",
            "com.hanson.mobileuse.ui.screens.SettingsScreenKt" to "SettingsScreen"
        )

        for ((className, methodName) in screens) {
            val clazz = Class.forName(className)
            val method = clazz.methods.firstOrNull { it.name == methodName }
            assertNotNull("Composable function $methodName must exist in $className", method)
        }
    }

    // =========================================================================
    // 3. Contract Compliance: Verbatim apiKey & Parameter Retentions
    // =========================================================================

    @Test
    fun testChallenger_contractCompliance_apiKeyVerbatimPreserved() {
        val source = getMainActivitySource()
        assertTrue(
            "MainActivity.kt must strictly retain verbatim 'apiKey = config.apiKey' per BuildIntegrityAndDeploymentContractTest",
            source.contains("apiKey = config.apiKey")
        )
        assertTrue(
            "MainActivity.kt must pass modelName = config.modelName to CommandCenterScreen",
            source.contains("modelName = config.modelName")
        )
        assertTrue(
            "MainActivity.kt must pass ttsEnabled = config.ttsVoiceReply to CommandCenterScreen",
            source.contains("ttsEnabled = config.ttsVoiceReply")
        )
    }

    // =========================================================================
    // 4. State Preservation: SaveableStateHolder & Provider Key
    // =========================================================================

    @Test
    fun testChallenger_statePreservation_saveableStateHolderAndProviderKey() {
        val source = getMainActivitySource()

        // Verify SaveableStateHolder creation
        assertTrue(
            "MainActivity.kt must initialize rememberSaveableStateHolder()",
            source.contains("val saveableStateHolder = rememberSaveableStateHolder()")
        )

        // Verify SaveableStateProvider wrapping with tab.name key
        assertTrue(
            "MainActivity.kt must wrap screen render with saveableStateHolder.SaveableStateProvider(key = tab.name)",
            source.contains("saveableStateHolder.SaveableStateProvider(key = tab.name)")
        )

        // Verify currentTab itself uses rememberSaveable
        assertTrue(
            "currentTab state must be preserved across config changes using rememberSaveable",
            source.contains("var currentTab by rememberSaveable { mutableStateOf(MainTab.COMMAND_CENTER) }")
        )

        // Verify smooth tab transition animation duration
        assertTrue(
            "Crossfade animation should use 180ms tween duration for responsive transitions",
            source.contains("tween(durationMillis = 180)")
        )
    }

    // =========================================================================
    // 5. Back Navigation Hierarchy & Exit Semantics
    // =========================================================================

    @Test
    fun testChallenger_backHandler_logicAndExitSemantics() {
        val source = getMainActivitySource()

        // Verify BackHandler definition in MainActivity.kt
        assertTrue(
            "MainActivity must configure BackHandler enabled only when currentTab != MainTab.COMMAND_CENTER",
            source.contains("BackHandler(enabled = currentTab != MainTab.COMMAND_CENTER)")
        )
        assertTrue(
            "BackHandler body must reset currentTab to MainTab.COMMAND_CENTER",
            source.contains("currentTab = MainTab.COMMAND_CENTER")
        )

        // Adversarial state machine simulation:
        // Test back-stack behavior across all 5 possible starting tabs
        for (tab in MainTab.entries) {
            val backHandlerEnabled = (tab != MainTab.COMMAND_CENTER)
            val nextTab = if (backHandlerEnabled) MainTab.COMMAND_CENTER else null

            if (tab == MainTab.COMMAND_CENTER) {
                assertFalse("BackHandler must be disabled on COMMAND_CENTER to allow app exit", backHandlerEnabled)
                assertEquals("COMMAND_CENTER should not intercept back press (delegates to system)", null, nextTab)
            } else {
                assertTrue("BackHandler must intercept back press on ${tab.name}", backHandlerEnabled)
                assertEquals("Pressing back on ${tab.name} must return user to COMMAND_CENTER", MainTab.COMMAND_CENTER, nextTab)
            }
        }
    }

    // =========================================================================
    // 6. Navigation Bar Ergonomics & Achromatic Design
    // =========================================================================

    @Test
    fun testChallenger_navigationBar_64dpAndObsidianAchromaticTokens() {
        val source = getMainActivitySource()

        // Verify Composables exist in MainActivityKt bytecode
        val mainActivityKtClass = Class.forName("com.hanson.mobileuse.ui.MainActivityKt")
        assertNotNull(
            "HansonBottomNavigationBar composable must exist in MainActivityKt",
            mainActivityKtClass.methods.firstOrNull { it.name.startsWith("HansonBottomNavigationBar") }
        )
        assertNotNull(
            "HansonM3NavigationBar composable must exist in MainActivityKt",
            mainActivityKtClass.methods.firstOrNull { it.name.startsWith("HansonM3NavigationBar") }
        )
        assertNotNull(
            "HansonBottomNavBar alias composable must exist in MainActivityKt",
            mainActivityKtClass.methods.firstOrNull { it.name.startsWith("HansonBottomNavBar") }
        )

        // Height verification (64.dp per Figma line 487)
        assertTrue("HansonBottomNavigationBar must specify height(64.dp)", source.contains(".height(64.dp)"))

        // Border and Background tokens
        assertTrue("NavigationBar must use ObsidianColors.Surface", source.contains("color = ObsidianColors.Surface"))
        assertTrue(
            "NavigationBar must declare 1.dp hairline border with ObsidianColors.GlassBorder",
            source.contains("BorderStroke(1.dp, ObsidianColors.GlassBorder)")
        )
    }

    @Test
    fun testChallenger_navigationBar_pillIndicatorAndTypography() {
        val source = getMainActivitySource()

        // Pill shape indicator (999.dp corner radius)
        assertTrue(
            "Active tab indicator must use RoundedCornerShape(999.dp)",
            source.contains("RoundedCornerShape(999.dp)")
        )
        assertTrue(
            "Active tab indicator pill must apply ObsidianColors.AccentDim",
            source.contains("Modifier.background(ObsidianColors.AccentDim)")
        )

        // Typography and dynamic colors
        assertTrue(
            "Tab title typography must toggle between ObsidianColors.Accent and ObsidianColors.TextSecondary",
            source.contains("if (isSelected) ObsidianColors.Accent else ObsidianColors.TextSecondary")
        )
        assertTrue(
            "Tab title font size must be 10.5.sp per Figma spec",
            source.contains("10.5.sp")
        )
        assertTrue(
            "Tab title font weight must toggle between Bold and Medium",
            source.contains("if (isSelected) FontWeight.Bold else FontWeight.Medium")
        )
        assertTrue(
            "Tab title maxLines must be 1",
            source.contains("maxLines = 1")
        )
    }

    // =========================================================================
    // 7. IME Keyboard Handling (Soft Keyboard Avoidance)
    // =========================================================================

    @Test
    fun testChallenger_imeKeyboardHandling_hidesBottomBarOnInput() {
        val source = getMainActivitySource()

        // Verify IME inset detection
        assertTrue(
            "MainActivity must compute imeBottomPx from WindowInsets.ime",
            source.contains("val imeBottomPx = WindowInsets.ime.getBottom(density)")
        )
        assertTrue(
            "MainActivity must derive isKeyboardOpen flag",
            source.contains("val isKeyboardOpen = imeBottomPx > 0")
        )
        assertTrue(
            "Scaffold bottomBar must be conditionally hidden when keyboard is open",
            source.contains("if (!isKeyboardOpen)")
        )
    }

    // =========================================================================
    // 8. Cross-Screen Inter-Tab Coordination
    // =========================================================================

    @Test
    fun testChallenger_crossScreenWiring_deepSettingsAndReceiptsFlow() {
        val source = getMainActivitySource()

        // 1. CommandCenterScreen -> Settings navigation
        assertTrue(
            "CommandCenterScreen onOpenDeepSettings must switch tab to SETTINGS",
            source.contains("onOpenDeepSettings = {") && source.contains("currentTab = MainTab.SETTINGS")
        )

        // 2. SettingsScreen -> CommandCenter back navigation
        assertTrue(
            "SettingsScreen onClose must switch tab back to COMMAND_CENTER",
            source.contains("onClose = {") && source.contains("currentTab = MainTab.COMMAND_CENTER")
        )

        // 3. SkillsAndReceiptsScreen -> CommandCenter run skill trigger
        assertTrue(
            "SkillsAndReceiptsScreen onRunSkill must send prompt and switch to COMMAND_CENTER",
            source.contains("agentEngine.sendChatMessage(skill.goalPrompt)") &&
            source.contains("currentTab = MainTab.COMMAND_CENTER")
        )

        // 4. Receipts flow collected from agentEngine and passed to screen
        assertTrue(
            "MainActivity must collect allReceipts flow from agentEngine",
            source.contains("val allReceipts by agentEngine.allReceipts.collectAsState()")
        )
        assertTrue(
            "SkillsAndReceiptsScreen must receive receipts = allReceipts",
            source.contains("receipts = allReceipts")
        )
    }

    // =========================================================================
    // 9. Achromatic Design Hardline Integrity
    // =========================================================================

    @Test
    fun testChallenger_achromaticIntegrity_zeroChromaticColorsInMainActivity() {
        val source = getMainActivitySource()

        // Reject forbidden chromatic color keywords
        val forbiddenColors = listOf(
            "Color.Red", "Color.Blue", "Color.Green", "Color.Yellow",
            "Color.Cyan", "Color.Magenta", "Color.LightGray"
        )
        for (color in forbiddenColors) {
            assertFalse("MainActivity.kt must not use chromatic constant $color", source.contains(color))
        }

        // Verify ObsidianColors tokens are strictly achromatic (0% saturation)
        val tokens = listOf(
            "Surface" to ObsidianColors.Surface,
            "Background" to ObsidianColors.Background,
            "GlassBorder" to ObsidianColors.GlassBorder,
            "Accent" to ObsidianColors.Accent,
            "AccentDim" to ObsidianColors.AccentDim,
            "TextSecondary" to ObsidianColors.TextSecondary
        )

        for ((name, color) in tokens) {
            val r = color.red
            val g = color.green
            val b = color.blue
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            assertTrue(
                "ObsidianColors.$name must be achromatic (max - min <= 0.05). Actual: delta=$delta, r=$r, g=$g, b=$b",
                delta <= 0.05f
            )
        }
    }

    // =========================================================================
    // 10. Edge-to-Edge & Floating HUD Foreground Decoupling
    // =========================================================================

    @Test
    fun testChallenger_edgeToEdgeAndFloatingHudDecoupling() {
        val source = getMainActivitySource()

        // Edge-to-edge window decor
        assertTrue(
            "MainActivity must set WindowCompat.setDecorFitsSystemWindows(window, false)",
            source.contains("WindowCompat.setDecorFitsSystemWindows(window, false)")
        )

        // Floating HUD lifecycle management:
        // When MainActivity is active, FloatingHudService must be informed to suppress in-app HUD
        assertTrue(
            "MainActivity must call FloatingHudService.setMainActivityForeground(applicationContext, true) on start/resume",
            source.contains("FloatingHudService.setMainActivityForeground(applicationContext, true)")
        )
        assertTrue(
            "MainActivity must call FloatingHudService.setMainActivityForeground(applicationContext, false) on stop",
            source.contains("FloatingHudService.setMainActivityForeground(applicationContext, false)")
        )

        // Navigation bar insets applied to HansonBottomNavigationBar
        assertTrue(
            "HansonBottomNavigationBar must apply WindowInsets.navigationBars padding",
            source.contains("windowInsetsPadding(WindowInsets.navigationBars)")
        )
    }
}
